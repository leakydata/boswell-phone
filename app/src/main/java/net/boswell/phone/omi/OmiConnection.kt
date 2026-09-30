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
import kotlinx.coroutines.withTimeout
import java.util.UUID

class GattException(message: String) : Exception(message)

/** How long a storage transfer may go silent before it counts as stopped. */
private const val IDLE_MS = 20_000L
/** How long to wait for the first reply to a read (the firmware may be waiting on its SD card). */
private const val FIRST_REPLY_MS = 45_000L

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
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    private val connected = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null
    private var storageNotifying = false
    private val storageMessages = Channel<ByteArray>(Channel.UNLIMITED)

    /** Completes with the GATT status when the link goes down, for any reason. */
    val disconnected = CompletableDeferred<Int>()

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
                connected.completeExceptionally(e)
                pending?.completeExceptionally(e)
                disconnected.complete(status)
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status, status)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = finish(status, mtu)

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = finish(status, value)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish(status, Unit)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(status, Unit)

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) = finish(status, rssi)

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

    private fun finish(status: Int, value: Any?) {
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(value)
        else p.completeExceptionally(GattException("GATT status $status"))
    }

    private suspend fun <T> op(what: String, timeoutMs: Long = 5_000, start: (BluetoothGatt) -> Boolean): T =
        opLock.withLock {
            val g = gatt ?: throw GattException("$what: not connected")
            val d = CompletableDeferred<Any?>()
            pending = d
            try {
                // "Refused" usually means the stack is still finishing an
                // operation whose callback came late -- common on a weak link.
                // A short pause and another try beats failing the whole visit.
                var started = start(g)
                var tries = 0
                while (!started && tries++ < 5) {
                    kotlinx.coroutines.delay(300L * tries)
                    started = start(g)
                }
                if (!started) throw GattException("$what: refused by the stack")
                @Suppress("UNCHECKED_CAST")
                withTimeout(timeoutMs) { d.await() } as T
            } finally {
                pending = null
            }
        }

    suspend fun connect(timeoutMs: Long = 30_000) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(timeoutMs) { connected.await() }
        op<Int>("discover services", 15_000) { it.discoverServices() }
        // Bigger MTU means whole frames per notification; the desktop never saw
        // a split frame on a CV 1, and this keeps it that way.
        mtu = runCatching { op<Int>("request MTU") { it.requestMtu(247) } }.getOrDefault(23)
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

    suspend fun read(uuid: UUID): ByteArray {
        val c = char(uuid)
        return op("read $uuid") { it.readCharacteristic(c) }
    }

    suspend fun write(uuid: UUID, value: ByteArray) {
        val c = char(uuid)
        op<Unit>("write $uuid") {
            it.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        }
    }

    suspend fun enableNotifications(uuid: UUID) {
        val c = char(uuid)
        val cccd = c.getDescriptor(OmiUuids.CCCD) ?: throw GattException("$uuid has no CCCD")
        op<Unit>("enable notifications $uuid") {
            it.setCharacteristicNotification(c, true) &&
                it.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        }
    }

    /**
     * RSSI of the live connection, read from the controller -- not a cached
     * advertisement value, which is what misled the desktop for hours.
     */
    suspend fun rssi(): Int = op("read RSSI") { it.readRemoteRssi() }

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
        return withTimeout(timeoutMs) {
            var info: RingInfo? = null
            while (info == null) info = RingInfo.parse(storageMessages.receive())
            info
        }
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
    suspend fun readStored(start: Long, count: Int, timeoutMs: Long = 90_000): Pair<ByteArray, Long> {
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
        var begun = false
        while (true) {
            val left = deadline - System.currentTimeMillis()
            // The first reply can take a while: the firmware waits for its SD
            // card to be ready after a connection. Once data flows, silence
            // means the transfer stopped.
            val idle = if (begun || data.size() > 0) IDLE_MS else FIRST_REPLY_MS
            val msg = try {
                if (left <= 0) null else kotlinx.coroutines.withTimeoutOrNull(minOf(left, idle)) { storageMessages.receive() }
            } catch (e: Exception) {
                throw TransferInterrupted(whole(), "link lost: ${e.message}")
            } ?: throw TransferInterrupted(whole(), "the device stopped sending")
            if (!begun && msg.firstOrNull() != Offload.MSG_DATA) android.util.Log.i("Boswell", "storage reply 0x%02x (%d bytes)".format(msg.firstOrNull() ?: 0, msg.size))
            when (msg.firstOrNull()) {
                Offload.MSG_BEGIN -> begun = true
                Offload.MSG_DATA -> { begun = true; data.write(msg, 1, msg.size - 1) }
                Offload.MSG_DONE -> {
                    val (status, next) = Offload.parseDone(msg) ?: continue
                    if (status != 0 && data.size() == 0) throw GattException("device refused the read: ${Offload.STATUS[status] ?: status}")
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

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        disconnected.complete(-1)
    }
}
