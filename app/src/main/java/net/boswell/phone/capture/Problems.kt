package net.boswell.phone.capture

/**
 * Failures that used to vanish into runCatching, written to the Device log
 * instead: "problem: <what>: <exception class>: <message>". The same failure
 * from the same place is written at most once a minute, so a loop that fails
 * on every pass shows once, with how many were left out since.
 */
object Problems {
    const val PREFIX = "problem: "

    @PublishedApi internal var sink: (String) -> Unit = CaptureRepository::log
    @PublishedApi internal val quiet = Quiet(60_000L)

    /**
     * Log that [what] failed with [e]; [detail] (which clip, say) is shown but doesn't
     * count as a different failure. A cancelled coroutine isn't a problem and isn't logged.
     */
    fun report(what: String, e: Throwable, detail: String? = null) {
        if (e is kotlinx.coroutines.CancellationException) return
        val name = e.javaClass.simpleName.ifEmpty { e.javaClass.name }
        val left = quiet.allow("$what|${e.javaClass.name}") ?: return
        val line = "$PREFIX$what${detail?.let { " ($it)" } ?: ""}: $name: ${e.message ?: "(no message)"}"
        sink(line.take(300) + if (left > 0) " (+$left since)" else "")
    }

    /** Whether a Device log line is a problem or a slow step. */
    fun isProblem(line: String) = PREFIX in line || Slow.PREFIX in line
}

/** [block], or null if it threw (the failure goes to [Problems]). */
inline fun <T> logged(what: String, block: () -> T): T? =
    try { block() } catch (e: Throwable) { Problems.report(what, e); null }

/** This result, unchanged, with a failure written to [Problems]. */
fun <T> Result<T>.logged(what: String): Result<T> = onFailure { Problems.report(what, it) }

/**
 * At most one line per key per [periodMs]. [allow] answers how many were held back
 * since the last one let through, or null to hold this one back too.
 */
class Quiet(private val periodMs: Long, @PublishedApi internal var clock: () -> Long = System::currentTimeMillis) {
    private val last = HashMap<String, Long>()
    private val held = HashMap<String, Int>()

    @Synchronized fun allow(key: String): Int? {
        val now = clock()
        val at = last[key]
        if (at != null && now - at < periodMs) { held[key] = (held[key] ?: 0) + 1; return null }
        last[key] = now
        return held.remove(key) ?: 0
    }
}
