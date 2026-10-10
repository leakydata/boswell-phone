package net.boswell.phone.omi

/**
 * When OmiConnection keeps trying an operation the stack refused, and when it
 * gives up on the connection.
 *
 * Android runs one GATT request per connection at a time and refuses the next
 * (readCharacteristic returns false, writeCharacteristic BUSY) until the one
 * before it calls back. Our own timeout is 5 s, but the stack waits up to 30 s
 * for an answer (the ATT transaction timeout) before it lets go. So after one
 * of ours times out, the next is refused for up to half a minute on a link
 * that is still up: wait that out instead of failing everything after it --
 * on 10-10 a fetch's reconnect lost every read, the LED, the mic gain and the
 * audio subscription that way.
 *
 * A closed connection refuses too, forever, and looks up from outside. So a
 * few refusals in a row end the connection, and the caller reconnects whole.
 */
object GattPolicy {
    /** How long a refused operation is tried again when nothing is known to hold the stack. */
    const val REFUSED_RETRY_MS = 4_500L
    /** The stack's own wait for an answer (ATT, 30 s) plus a margin: past it, a timed-out request has let go. */
    const val STACK_HOLDS_MS = 32_000L
    /** Operations in a row the stack refused, after waiting: the connection is no use. */
    const val REFUSED_LIMIT = 3

    /**
     * Until when an operation refused at [now] is tried again. [hungAt] is when
     * an earlier operation timed out with its answer still owed (0: none).
     */
    fun retryUntil(now: Long, hungAt: Long): Long =
        maxOf(now + REFUSED_RETRY_MS, if (hungAt > 0) hungAt + STACK_HOLDS_MS else 0L)

    /** The pause before try number [tries] (1, 2, ...): short at first, then steady. */
    fun pause(tries: Int): Long = (300L * tries).coerceAtMost(1_500L)
}

/** Refused operations in a row; any operation the stack takes clears it. */
class RefusedInARow(private val limit: Int = GattPolicy.REFUSED_LIMIT) {
    var count = 0
        private set

    /** One more refused; true when that makes the connection no use. */
    fun refused(): Boolean = ++count >= limit

    fun taken() { count = 0 }
}
