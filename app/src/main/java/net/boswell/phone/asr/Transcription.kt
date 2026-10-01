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

    fun cloud(c: Context): Boolean = prefs(c).getBoolean("cloud_transcribe", false)
    fun setCloud(c: Context, on: Boolean) = prefs(c).edit().putBoolean("cloud_transcribe", on).apply()

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

    fun wantsCloud(c: Context, clip: String): Boolean = cloud(c) || requested(c, clip)
}
