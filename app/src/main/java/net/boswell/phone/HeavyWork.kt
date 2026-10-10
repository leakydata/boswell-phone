package net.boswell.phone

import java.util.concurrent.locks.ReentrantLock

/**
 * One heavy job at a time in the app's process: the ones that hold every
 * voice of the archive (a recheck, review suggestions, label checks) or read
 * every transcript (a voice update, voice levels, Boswell's voice as a
 * person). Each fits in the heap on its own; started together from the
 * screens, a worker and a recheck after labeling, their peaks would stack.
 * The next one waits its turn on its own thread, and a job inside another
 * (a recheck at the end of a voice update) just goes on: the lock is
 * reentrant.
 */
object HeavyWork {
    private val lock = ReentrantLock(true)

    /** A wait longer than this is written to the Device log. */
    private const val SLOW_WAIT_MS = 5_000L

    fun <T> run(what: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        lock.lock()
        try {
            val waited = System.currentTimeMillis() - t0
            if (waited > SLOW_WAIT_MS) runCatching { net.boswell.phone.capture.CaptureRepository.log("slow: $what waited $waited ms for other voice work") }
            return block()
        } finally { lock.unlock() }
    }

    /** Whether a heavy job holds the lock now (for tests). */
    val busy: Boolean get() = lock.isLocked
}
