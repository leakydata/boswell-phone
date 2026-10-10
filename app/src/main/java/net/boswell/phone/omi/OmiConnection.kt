package net.boswell.phone.omi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class GattException(message: String) : Exception(message)

/** How long a storage transfer may go silent before it counts as stopped. */
private const val IDLE_MS = 20_000L
/** How long to wait for the first reply to a read (the firmware may be waiting on its SD card). */
private const val FIRST_REPLY_MS = 45_000L
/** How long to wait for the stop command's answer, and for any data still in flight. */
private const val STOP_ANSWER_MS = 2_000L

/**
 * One GATT connection to one Omi, with suspend functions over Android's
 * callback API.
 *
 * Android allows a single outstanding GATT operation per connection and
 * silently drops a second one, so every operation goes through [op]: a mutex,
 * a deferred the callback completes, and a timeout so a lost callback fails
 * loudly instead of hanging -- a hang looks exactly like health from outside.
 *
 * Never bonds. The desktop learned that a bonded Omi is auto-connected by the
 * OS and stops advertising, which makes it invisible to everything else.
 */
@SuppressLint("MissingPermission")   // callers hold BLUETOOTH_CONNECT; checked in the service
class OmiConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onAudio: (ByteArray) -> Unit,
    private val onButton: (Int) -> Unit = {},
) {
    private val opLock = Mutex()
    /**
     * The operation in flight and the reply it is waiting for ("read <uuid>",
     * "mtu", ...). A reply that arrives after its own operation timed out
     * must not complete the next one: on a weak link it did, and every read
     * after it answered the question before -- the firmware version came back
     * as the model name, and a stray byte read as "charging".
     */
    private class Pending(val expects: String, val d: CompletableDeferred<Any?>)
    @Volatile private var pending: Pending? = null
    private val connected = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null
    private var storageNotifying = false
    private val storageMessages = Channel<ByteArray>(Channel.UNLIMITED)
    /** When an operation of ours timed out with the stack still holding it (GattPolicy); 0: none. */
    @Volatile private var hungAt = 0L
    private val refused = RefusedInARow()

    /** Completes with the GATT status when the link goes down, for any reason (-1: closed here, -2: given up on). */
    val disconnected = CompletableDeferred<Int>()

    /** Why the link went down, when it was this side's doing or the stack said so. */
    @Volatile var lostWhy: String? = null
        private set

    var mtu = 23
        private set

    /** Every notification received, of any kind. */
    @Volatile var notifications = 0L
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val e = GattException("disconnected (status $status)")
                if (lostWhy == null) lostWhy = e.message
                connected.completeExceptionally(e)
                pending?.d?.completeExceptionally(e)
                disconnected.complete(status)
                // A storage transfer waiting on its next message hears at once,
                // not after its idle timeout; what already arrived is still read.
                storageMessages.close()
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish("discover", status, status)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = finish("mtu", status, mtu)

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = finish("read ${c.uuid}", status, value)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish("write ${c.uuid}", status, Unit)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish("notify ${d.characteristic.uuid}", status, Unit)

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            android.util.Log.i("Boswell", "PHY now tx $txPhy rx $rxPhy (1 = 1M, 2 = 2M, 3 = coded) status $status")
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) = finish("rssi", status, rssi)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (notifications++ < 3) android.util.Log.i("Boswell", "notify #$notifications ${c.uuid} ${value.size}B")
            when (c.uuid) {
                OmiUuids.AUDIO -> onAudio(value)
                OmiUuids.STORAGE -> storageMessages.trySend(value)
                OmiUuids.BUTTON -> if (value.size >= 4) onButton(
                    (value[0].toInt() and 0xff) or ((value[1].toInt() and 0xff) shl 8) or
                        ((value[2].toInt() and 0xff) shl 16) or ((value[3].toInt() and 0xff) shl 24))
            }
        }
    }

    private fun finish(reply: String, status: Int, value: Any?) {
        // Any answer means the stack has let go of the request it was holding.
        hungAt = 0L
        val p = pending ?: return
        if (p.expects != reply) {
            android.util.Log.i("Boswell", "late reply ($reply) ignored while waiting for ${p.expects}")
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS) p.d.complete(value)
        else p.d.completeExceptionally(GattException("GATT status $status"))
    }

    private suspend fun <T> op(what: String, expects: String, timeoutMs: Long = 5_000, start: (BluetoothGatt) -> Boolean): T =
        opLock.withLock {
            // A link that is down refuses everything; say so instead of
            // retrying each operation for seconds on a connection that is gone.
            val g = gatt?.takeIf { !disconnected.isCompleted } ?: throw GattException("$what: not connected")
            val d = CompletableDeferred<Any?>()
            pending = Pending(expects, d)
            try {
                // "Refused" means the stack is still finishing an operation --
                // one of ours that timed out, whose answer it waits up to 30 s
                // for (GattPolicy). Wait that out rather than fail this one too.
                var started = start(g)
                val until = GattPolicy.retryUntil(System.currentTimeMillis(), hungAt)
                var tries = 0
                while (!started && System.currentTimeMillis() < until && !disconnected.isCompleted) {
                    kotlinx.coroutines.delay(GattPolicy.pause(++tries))
                    started = start(g)
                }
                if (!started) {
                    if (disconnected.isCompleted) throw GattException("$what: not connected")
                    if (refused.refused()) giveUp("${refused.count} operations in a row refused by the stack")
                    throw GattException("$what: refused by the stack")
                }
                refused.taken()
                @Suppress("UNCHECKED_CAST")
                // A timeout here is a failed operation, not a cancellation: as a
                // CancellationException it would silently end the caller's
                // reconnect loop, which is exactly how capture once stalled.
                val done = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { d.await(); true }
                if (done == null) {
                    hungAt = System.currentTimeMillis()
                    throw GattException("$what: timed out after ${timeoutMs / 1000}s")
                }
                d.getCompleted() as T
            } finally {
                pending = null
            }
        }

    /**
     * End a connection the stack has stopped serving: closed and marked down,
     * so the caller's loop reconnects from scratch with a new BluetoothGatt.
     */
    private fun giveUp(why: String) {
        android.util.Log.i("Boswell", "giving up on the connection: $why")
        lostWhy = why
        close(-2)
    }

    suspend fun connect(timeoutMs: Long = 30_000, autoConnect: Boolean = false) {
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { connected.await(); true }
            ?: throw GattException("connect timed out after ${timeoutMs / 1000}s")
        op<Int>("discover services", "discover", 15_000) { it.discoverServices() }
        // Bigger MTU means whole frames per notification; the desktop never saw
        // a split frame on a CV 1, and this keeps it that way.
        mtu = runCatching { op<Int>("request MTU", "mtu") { it.requestMtu(247) } }.getOrDefault(23)
    }

    /**
     * For bulk transfer: the shortest connection interval and, where both ends
     * support it, the 2M PHY. Not used for live audio, which needs neither.
     */
    fun preferThroughput() {
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        gatt?.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
    }

    fun has(uuid: UUID): Boolean = find(uuid) != null

    private fun find(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    private fun char(uuid: UUID) = find(uuid) ?: throw GattException("no characteristic $uuid")

    suspend fun read(uuid: UUID, timeoutMs: Long = 5_000): ByteArray {
        val c = char(uuid)
        return op("read $uuid", "read $uuid", timeoutMs) { it.readCharacteristic(c) }
    }

    suspend fun write(uuid: UUID, value: ByteArray) {
        val c = char(uuid)
        op<Unit>("write $uuid", "write $uuid") {
            it.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        }
    }

    suspend fun enableNotifications(uuid: UUID) {
        val c = char(uuid)
        val cccd = c.getDescriptor(OmiUuids.CCCD) ?: throw GattException("$uuid has no CCCD")
        op<Unit>("enable notifications $uuid", "notify $uuid") {
            it.setCharacteristicNotification(c, true) &&
                it.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        }
    }

    /**
     * RSSI of the live connection, read from the controller -- not a cached
     * advertisement value, which is what misled the desktop for hours.
     */
    suspend fun rssi(): Int = op("read RSSI", "rssi") { it.readRemoteRssi() }

    private suspend fun ensureStorage() {
        if (!storageNotifying) {
            enableNotifications(OmiUuids.STORAGE)
            storageNotifying = true
            // The desktop waits half a second after subscribing before its first command.
            kotlinx.coroutines.delay(500)
        }
    }

    /**
     * Ask the ring buffer how full it is. Reads nothing out of it -- but it is
     * a storage command, and must not be sent while audio is streaming.
     */
    suspend fun ringInfo(timeoutMs: Long = 8_000): RingInfo {
        ensureStorage()
        while (storageMessages.tryReceive().isSuccess) { /* discard anything stale */ }
        write(OmiUuids.STORAGE, Offload.ringInfoCommand())
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            var info: RingInfo? = null
            while (info == null) info = RingInfo.parse(storageMessages.receive())
            info
        } ?: throw GattException("the device did not answer the ring query")
    }

    /** A batch that stopped part way, carrying the whole packets that did arrive. */
    class TransferInterrupted(val salvaged: ByteArray, reason: String) : Exception(reason)

    /**
     * Read up to [count] stored packets from [start]. Returns the raw bytes and
     * the sequence the device stopped at. Reading consumes: if the batch stops
     * part way, the packets that arrived are returned inside
     * [TransferInterrupted] rather than dropped, because dropping them would
     * delete them -- the device has already let go of them.
     */
    suspend fun readStored(start: Long, count: Int, timeoutMs: Long = 90_000, idleMs: Long = IDLE_MS): Pair<ByteArray, Long> {
        ensureStorage()
        while (storageMessages.tryReceive().isSuccess) { /* discard anything stale */ }
        val data = java.io.ByteArrayOutputStream()
        fun whole(): ByteArray = data.toByteArray().let { it.copyOf(it.size / Offload.STORED_PACKET_BYTES * Offload.STORED_PACKET_BYTES) }
        try {
            write(OmiUuids.STORAGE, Offload.readCommand(start, count))
        } catch (e: Exception) {
            throw TransferInterrupted(whole(), "read request failed: ${e.message}")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        val tAsk = System.currentTimeMillis()
        var tFirst = 0L
        var begun = false
        while (true) {
            val left = deadline - System.currentTimeMillis()
            // The first reply can take a while: the firmware waits for its SD
            // card to be ready after a connection. Once data flows, silence
            // means the transfer stopped.
            val idle = if (begun || data.size() > 0) idleMs else FIRST_REPLY_MS
            val msg = try {
                if (left <= 0) null else kotlinx.coroutines.withTimeoutOrNull(minOf(left, idle)) { storageMessages.receive() }
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                throw TransferInterrupted(whole(), "link lost: ${lostWhy ?: "disconnected"}")
            } catch (e: Exception) {
                throw TransferInterrupted(whole(), "link lost: ${e.message}")
            }
            if (msg == null) {
                stopTransfer(data)
                throw TransferInterrupted(whole(), if (disconnected.isCompleted) "link lost: ${lostWhy ?: "disconnected"}"
                    else "the device stopped sending for ${minOf(left, idle) / 1000} s")
            }
            if (!begun && msg.firstOrNull() != Offload.MSG_DATA) android.util.Log.i("Boswell", "storage reply 0x%02x (%d bytes)".format(msg.firstOrNull() ?: 0, msg.size))
            when (msg.firstOrNull()) {
                Offload.MSG_BEGIN -> begun = true
                Offload.MSG_DATA -> { if (tFirst == 0L) tFirst = System.currentTimeMillis(); begun = true; data.write(msg, 1, msg.size - 1) }
                Offload.MSG_DONE -> {
                    val (status, next) = Offload.parseDone(msg) ?: continue
                    if (status != 0 && data.size() == 0) throw GattException("device refused the read: ${Offload.STATUS[status] ?: status}")
                    val now = System.currentTimeMillis()
                    val xfer = now - (if (tFirst > 0) tFirst else tAsk)
                    android.util.Log.i("Boswell", "batch %d kB: first data after %d ms, transfer %d ms (%.1f kB/s)".format(
                        data.size() / 1000, (if (tFirst > 0) tFirst else now) - tAsk, xfer, data.size() / maxOf(xfer, 1L).toDouble()))
                    return whole() to next
                }
                Offload.MSG_ACK -> {
                    val status = msg.getOrNull(1)?.toInt()?.and(0xff) ?: 0
                    // A refusal is answered before anything is sent, so there is nothing to salvage.
                    if (status != 0) throw GattException("device refused the read: ${Offload.STATUS[status] ?: status}")
                }
                else -> Unit   // 0x05 begin, or an info reply
            }
        }
    }

    /**
     * Tell the Omi to stop a read we have given up on (0x03), and keep what
     * still arrives meanwhile. Without it the firmware carries on sending into
     * a transfer nobody reads, and when the link closes it marks everything
     * the radio delivered as taken (storage.c sync_checkpoint_advance on
     * disconnect) -- including what arrived after we stopped listening, which
     * would be lost. A stop resets its transfer without that checkpoint.
     * Best effort: on a dead link neither the stop nor its answer gets through.
     */
    private suspend fun stopTransfer(data: java.io.ByteArrayOutputStream) {
        if (disconnected.isCompleted) return
        runCatching { write(OmiUuids.STORAGE, Offload.stopCommand()) }.onFailure { return }
        kotlinx.coroutines.withTimeoutOrNull(STOP_ANSWER_MS) {
            while (true) {
                val m = storageMessages.receiveCatching().getOrNull() ?: break
                when (m.firstOrNull()) {
                    Offload.MSG_DATA -> data.write(m, 1, m.size - 1)
                    Offload.MSG_ACK -> break
                }
            }
        }
    }

    /**
     * Mark everything below [seq] as taken. Only ever called once those
     * packets are on disk: the device's pointer is the record of what has been
     * collected, and moving it early is how audio is lost.
     */
    suspend fun advance(seq: Long, timeoutMs: Long = 8_000): Int? {
        ensureStorage()
        while (storageMessages.tryReceive().isSuccess) { }
        write(OmiUuids.STORAGE, Offload.advanceCommand(seq))
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            var status: Int? = null
            while (status == null) {
                val m = storageMessages.receive()
                if (m.firstOrNull() == Offload.MSG_ACK) status = m.getOrNull(1)?.toInt()?.and(0xff)
            }
            status
        }
    }

    /** Set the device clock. Stored packets are stamped with it; a wrong clock files audio at the wrong hour. */
    suspend fun setClock(epochSeconds: Long) {
        val v = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(epochSeconds.toInt()).array()
        write(OmiUuids.TIME_WRITE, v)
    }

    fun close(status: Int = -1) {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        val e = GattException("closed")
        connected.completeExceptionally(e)
        pending?.d?.completeExceptionally(e)
        disconnected.complete(status)
        storageMessages.close()
    }
}
