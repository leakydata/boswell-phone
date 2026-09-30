package net.boswell.phone.sound

import kotlinx.serialization.Serializable

/** A sound heard in a clip: its AudioSet label, best score, and where (seconds into the clip). */
@Serializable
data class SoundTag(val label: String, val score: Double, val at: Double)

/**
 * desktop Boswell's rules for what a clip holds (host/audio_tags.py,
 * web/pipeline.py), ported unchanged.
 */
object Sounds {
    /** Any of these means a person was audible, whatever the transcriber concluded. */
    val VOICE = setOf(
        "Speech", "Male speech, man speaking", "Female speech, woman speaking",
        "Child speech, kid speaking", "Conversation", "Narration, monologue",
        "Whispering", "Shout", "Yell", "Screaming", "Laughter", "Crying, sobbing",
        "Singing", "Speech synthesizer",
    )

    /** Present in almost every recording and not evidence of content. */
    val AMBIENT = setOf(
        "Silence", "White noise", "Pink noise", "Wind noise (microphone)",
        "Wind", "Mechanical fan", "Air conditioning", "Hum", "Mains hum",
        "Static", "Noise", "Environmental noise", "Inside, small room",
        "Inside, large room or hall", "Rustling leaves", "Vehicle",
        "Tick", "Tick-tock", "Clock",
    )

    /** Sounds that mean a screen was talking: its voices are media, not people. */
    val MEDIA = setOf("Television", "Radio", "Music", "Video game music", "Theme music",
        "Background music", "Soundtrack music", "Speech synthesizer")

    const val VOICE_FLOOR = 0.10
    const val EVENT_FLOOR = 0.35

    const val WINDOW_S = 10.0
    const val HOP_S = 5.0
    const val WINDOW_MIN = 0.25
    const val WINDOW_KEEP = 0.35
    const val WHOLE_KEEP = 0.20
    const val FLOOR = 0.02
    const val KEEP = 10

    enum class Verdict { KEEP, EMPTY }

    /**
     * keep | empty, deliberately biased towards keeping: deleting a recording
     * cannot be undone, and the device exists to have been there when you were
     * not paying attention.
     */
    fun verdict(tags: List<SoundTag>): Verdict = when {
        tags.any { it.label in VOICE && it.score >= VOICE_FLOOR } -> Verdict.KEEP
        tags.any { it.label !in AMBIENT && it.label !in VOICE && it.score >= EVENT_FLOOR } -> Verdict.KEEP
        else -> Verdict.EMPTY
    }

    /** Short, human names for the chips a person sees. */
    fun display(label: String): String = when (label) {
        "Typing", "Computer keyboard", "Typewriter" -> "typing"
        "Television" -> "TV"
        "Speech", "Male speech, man speaking", "Female speech, woman speaking", "Conversation", "Narration, monologue" -> "talking"
        "Music", "Background music", "Soundtrack music", "Theme music" -> "music"
        "Dog", "Bark", "Domestic animals, pets" -> "dog"
        "Vehicle", "Car", "Motor vehicle (road)" -> "vehicle"
        "Laughter" -> "laughter"
        "Silence" -> "quiet"
        else -> label.substringBefore(",").lowercase()
    }
}
