package net.boswell.phone.sync

import android.content.Context

/**
 * How the phone uses the Omi.
 *
 *   OFF   nothing connects.
 *   SYNC  the Omi records on its own; the phone visits every so often (and
 *         when the Omi comes back into range), downloads what it stored, and
 *         lets go. Easiest on both batteries, and every clip carries the
 *         device's own timestamp. Downloaded audio is gone from the Omi.
 *   LIVE  the phone holds the Omi and streams, so conversations appear within
 *         seconds and the assistant can listen.
 */
enum class Mode { OFF, SYNC, LIVE }

object Modes {
    private fun prefs(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    fun mode(c: Context): Mode = runCatching { Mode.valueOf(prefs(c).getString("mode", null) ?: legacy(c)) }.getOrDefault(Mode.OFF)

    /** Before modes existed, "keep recording" meant live. */
    private fun legacy(c: Context) = if (prefs(c).getBoolean("keep_recording", false)) "LIVE" else "OFF"

    fun setMode(c: Context, m: Mode) = prefs(c).edit().putString("mode", m.name).apply()

    fun address(c: Context): String? = prefs(c).getString("omi_address", null)

    fun syncMinutes(c: Context): Int = prefs(c).getInt("sync_minutes", 60)
    fun setSyncMinutes(c: Context, m: Int) = prefs(c).edit().putInt("sync_minutes", m).apply()

    fun lastSync(c: Context): Long = prefs(c).getLong("last_sync", 0L)
    fun lastSyncResult(c: Context): String? = prefs(c).getString("last_sync_result", null)
    fun recordSync(c: Context, result: String) =
        prefs(c).edit().putLong("last_sync", System.currentTimeMillis()).putString("last_sync_result", result).apply()

    fun associationId(c: Context): Int = prefs(c).getInt("cdm_association", -1)
    fun setAssociationId(c: Context, id: Int) = prefs(c).edit().putInt("cdm_association", id).apply()
}
