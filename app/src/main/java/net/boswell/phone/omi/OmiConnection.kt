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
                if (!start(g)) throw GattException("$what: refused by the stack")
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

    /**
     * Ask the ring buffer how full it is. Reads nothing out of it, so this is
     * safe to call at any time. See [Offload] for why nothing else is exposed.
     */
    suspend fun ringInfo(timeoutMs: Long = 5_000): RingInfo {
        if (!storageNotifying) {
            enableNotifications(OmiUuids.STORAGE)
            storageNotifying = true
        }
        while (storageMessages.tryReceive().isSuccess) { /* discard anything stale */ }
        write(OmiUuids.STORAGE, Offload.ringInfoCommand())
        return withTimeout(timeoutMs) {
            var info: RingInfo? = null
            while (info == null) info = RingInfo.parse(storageMessages.receive())
            info
        }
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
