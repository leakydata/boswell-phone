package net.boswell.phone.capture

/**
 * Work that took longer than it should, written to the Device log before it
 * becomes a hang: "slow: <what> took N ms". Once a minute per step at most.
 */
object Slow {
    const val PREFIX = "slow: "

    @PublishedApi internal var sink: (String) -> Unit = CaptureRepository::log
    @PublishedApi internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    @PublishedApi internal val quiet = Quiet(60_000L)

    @PublishedApi internal fun took(what: String, ms: Long, limitMs: Long) {
        if (ms <= limitMs) return
        val left = quiet.allow(what) ?: return
        sink("$PREFIX$what took $ms ms" + if (left > 0) " (+$left more since)" else "")
    }
}

/** [block], timed: over [limitMs] (failing or not), it's logged as slow. */
inline fun <T> timed(what: String, limitMs: Long = 2000, block: () -> T): T {
    val t0 = Slow.clock()
    try { return block() } finally { Slow.took(what, Slow.clock() - t0, limitMs) }
}
