package net.boswell.phone.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Fmt {
    private val zone get() = ZoneId.systemDefault()
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val weekday = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
    private val short = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

    fun time(epoch: Double): String = Instant.ofEpochMilli((epoch * 1000).toLong()).atZone(zone).format(time)

    fun day(d: LocalDate): String {
        val today = LocalDate.now(zone)
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> d.format(if (d.year == today.year) weekday else DateTimeFormatter.ofPattern("MMM d, yyyy"))
        }
    }

    fun shortDay(d: LocalDate): String = if (d == LocalDate.now(zone)) "Today" else d.format(short)

    fun duration(seconds: Double): String {
        val s = seconds.toLong()
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    fun clock(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun ago(epoch: Double?): String {
        if (epoch == null) return "never"
        val s = (System.currentTimeMillis() / 1000.0 - epoch).toLong()
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    fun bytes(b: Long): String = when {
        b < 1_000_000 -> "%.0f KB".format(b / 1e3)
        b < 1_000_000_000 -> "%.0f MB".format(b / 1e6)
        else -> "%.1f GB".format(b / 1e9)
    }
}
