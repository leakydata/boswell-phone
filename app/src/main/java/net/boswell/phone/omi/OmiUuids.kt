package net.boswell.phone.omi

import java.util.UUID

/**
 * The Omi CV 1's GATT table, as read off a working device (firmware 3.0.21)
 * and its open-source firmware. See OMI-PROTOCOL.md at the repo root.
 */
object OmiUuids {
    val SERVICE: UUID = UUID.fromString("19b10000-e8f2-537e-4f6c-d104768a1214")
    val AUDIO: UUID = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214")      // notify
    val CODEC: UUID = UUID.fromString("19b10002-e8f2-537e-4f6c-d104768a1214")      // read
    val DIM_RATIO: UUID = UUID.fromString("19b10011-e8f2-537e-4f6c-d104768a1214")  // LED, rw
    val MIC_GAIN: UUID = UUID.fromString("19b10012-e8f2-537e-4f6c-d104768a1214")   // rw
    val CHARGING: UUID = UUID.fromString("19b10013-e8f2-537e-4f6c-d104768a1214")   // 1 while charging
    val FEATURES: UUID = UUID.fromString("19b10021-e8f2-537e-4f6c-d104768a1214")
    val TIME_READ: UUID = UUID.fromString("19b10032-e8f2-537e-4f6c-d104768a1214")  // epoch, LE u32
    val TIME_WRITE: UUID = UUID.fromString("19b10031-e8f2-537e-4f6c-d104768a1214")

    /** Offload: one characteristic carries commands and data. */
    val STORAGE_SERVICE: UUID = UUID.fromString("30295780-4301-eabd-2904-2849adfeae43")
    val STORAGE: UUID = UUID.fromString("30295781-4301-eabd-2904-2849adfeae43")

    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val FIRMWARE: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val HARDWARE: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val MAKER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")

    /** The button: notifies [event:u32 LE, ...]; 1 tap, 2 double tap, 3 long press (which powers the Omi off), 4 press, 5 release. */
    val BUTTON: UUID = UUID.fromString("23ba7925-0000-1000-7450-346eac492e92")

    /** Vibration: write 1, 2 or 3 for a 100, 300 or 500 ms buzz (firmware src/haptic.c). */
    val HAPTIC: UUID = UUID.fromString("cab1ab96-2ea5-4f4d-bb56-874b72cfc984")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Codec ids are numbered by frame length: 20 = Opus 10 ms, 21 = Opus 20 ms (CV 1). */
    val CODEC_FRAME_SAMPLES = mapOf(20 to 160, 21 to 320)
    const val SAMPLE_RATE = 16_000
}
