package net.boswell.phone.omi

/**
 * One notification from the live audio characteristic:
 * `[packet:u16 LE][index:u8][opus frame]`.
 *
 * There is no timestamp. The packet counter is the only clock the stream has,
 * 20 ms per packet on a CV 1, so live clips are placed by arrival time and
 * marked `time_known = false`.
 */
data class LivePacket(val counter: Int, val index: Int, val opus: ByteArray) {
    companion object {
        const val HEADER_LEN = 3

        fun parse(data: ByteArray): LivePacket? {
            if (data.size <= HEADER_LEN) return null
            val counter = (data[0].toInt() and 0xff) or ((data[1].toInt() and 0xff) shl 8)
            return LivePacket(counter, data[2].toInt() and 0xff, data.copyOfRange(HEADER_LEN, data.size))
        }
    }
}

/**
 * One continuous stretch of the device's packet counter.
 *
 * The counter restarting is the only evidence a device rebooted, so it is what
 * defines a run, and each run gets a random id -- the desktop calls it boot_id.
 *
 * The counter is 16 bits and wraps every 65,536 packets (about 22 minutes at
 * 20 ms). A wrap is not a reboot: going from near the top to near the bottom
 * extends the counter instead of starting a new run, so [extended] keeps
 * counting past 65,535 and packet x 20 ms stays monotonic for the whole run.
 */
class RunTracker(private val random: () -> Int = { (1..0xFFFF).random() }) {
    var id: Int = random()
        private set
    private var last: Int? = null
    private var wraps = 0L

    /** The counter with wraps unrolled, valid after [note]. */
    var extended: Long = 0
        private set

    /** Returns true when this packet starts a new run (the device rebooted). */
    fun note(counter: Int): Boolean {
        val prev = last
        if (prev != null && counter < prev - REORDER_SLACK) {
            if (prev >= 0x10000 - WRAP_WINDOW && counter < WRAP_WINDOW) {
                wraps++
            } else {
                id = random()
                wraps = 0
                last = counter
                extended = counter.toLong()
                return true
            }
        }
        last = counter
        extended = wraps * 0x10000 + counter
        return false
    }

    companion object {
        /** Backwards by more than reordering could explain. The desktop uses 8. */
        const val REORDER_SLACK = 8
        /** How close to either end of the range a jump must be to count as a wrap. */
        const val WRAP_WINDOW = 1024
    }
}
