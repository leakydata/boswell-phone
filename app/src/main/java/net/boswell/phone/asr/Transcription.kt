package net.boswell.phone.asr

import android.content.Context
import java.io.File

/**
 * Where a clip's words come from: this phone (the default, and private) or
 * Parakeet v3 in the cloud, which read more accurately in testing
 * (tools/phone_vs_cloud.py). Either way the phone decides who spoke and who
 * they are; the cloud only ever sees clips that passed the speech check.
 *
 * Cloud use is either everything ([cloud]) or chosen clips ([requestCloud],
 * "Redo in the cloud"), and stops for the day at [dailyCap]. Anything the
 * cloud can't do -- no network, an error, the cap -- is transcribed on the
 * phone instead.
 */
object Transcription {
    val ENGINE = CloudAsr.Engine.PARAKEET
    const val PURPOSE = "transcribe"

    private fun prefs(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    /** Where words come from: the phone; the cloud when someone besides the owner speaks; or the cloud for everything. */
    enum class Mode { PHONE, OTHERS, ALL }

    fun mode(c: Context): Mode = prefs(c).getString("cloud_mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
        ?: if (prefs(c).getBoolean("cloud_transcribe", false)) Mode.ALL else Mode.PHONE
    fun setMode(c: Context, m: Mode) = prefs(c).edit().putString("cloud_mode", m.name).apply()
    fun cloud(c: Context): Boolean = mode(c) == Mode.ALL

    fun dailyCap(c: Context): Double = prefs(c).getFloat("cloud_transcribe_cap", 1.0f).toDouble()
    fun setDailyCap(c: Context, usd: Double) = prefs(c).edit().putFloat("cloud_transcribe_cap", usd.toFloat()).apply()

    private fun requests(c: Context) = File(c.filesDir, "cloud_requests.txt")

    /** Clips to be transcribed in the cloud next time, whatever the setting. */
    @Synchronized fun requestCloud(c: Context, clips: Collection<String>) {
        val f = requests(c)
        val now = if (f.exists()) f.readLines().toSet() else emptySet()
        f.writeText((now + clips).joinToString("\n"))
    }

    @Synchronized fun requested(c: Context, clip: String): Boolean = requests(c).let { it.exists() && clip in it.readLines() }

    @Synchronized fun handled(c: Context, clip: String) {
        val f = requests(c)
        if (!f.exists()) return
        val rest = f.readLines().filter { it.isNotBlank() && it != clip }
        if (rest.isEmpty()) f.delete() else f.writeText(rest.joinToString("\n"))
    }

    /**
     * [othersSpeak]: someone other than the owner (and not a TV) talks in the
     * clip. That's where the phone is weakest -- people across the room, over
     * each other -- and the owner's own dictation is where it does best.
     */
    fun wantsCloud(c: Context, clip: String, othersSpeak: Boolean): Boolean = when (mode(c)) {
        Mode.ALL -> true
        Mode.OTHERS -> othersSpeak
        Mode.PHONE -> false
    } || requested(c, clip)
}
