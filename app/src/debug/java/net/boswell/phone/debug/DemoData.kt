package net.boswell.phone.debug

import android.content.Context
import kotlinx.serialization.json.Json
import net.boswell.phone.assistant.AssistantPrefs
import net.boswell.phone.assistant.AssistantStore
import net.boswell.phone.assistant.LifeStore
import net.boswell.phone.capture.ClipTimes
import net.boswell.phone.process.Candidate
import net.boswell.phone.process.Segment
import net.boswell.phone.sound.SoundTag
import net.boswell.phone.process.SpeakerId
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson
import net.boswell.phone.speakers.SpeakerStore
import net.boswell.phone.todo.TodoStore
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * A made-up day for screenshots (debug builds only): invented people with
 * invented voiceprints, a few conversations, to-dos and assistant answers.
 * Refuses to run where real recordings exist.
 *
 *   adb shell am broadcast -a net.boswell.phone.debug.DEMO -n net.boswell.phone.debug/net.boswell.phone.debug.DebugReceiver
 */
object DemoData {
    private data class Line(val who: String, val text: String)
    private data class Talk(val at: LocalTime, val lines: List<Line>, val sounds: List<String> = emptyList())

    private val people = listOf("Alex Morgan", "Priya Shah", "Sam Rivera", "Jordan Lee", "?")

    private val day = listOf(
        Talk(LocalTime.of(9, 2), listOf(
            Line("Priya Shah", "Morning! Did the sensor boards come back from the fab?"),
            Line("Alex Morgan", "They did, all twelve. Two have a cold solder joint on the battery connector, so I'll rework those today."),
            Line("Jordan Lee", "Can we still demo on Thursday?"),
            Line("Alex Morgan", "Yes. I'll have ten working boards by Wednesday afternoon."),
            Line("Priya Shah", "Great. I'll send the client the agenda tonight and book the big conference room."),
            Line("Jordan Lee", "I can bring the tripods and the spare chargers."),
            Line("Priya Shah", "Perfect. Let's keep it to forty minutes, with questions at the end."),
        )),
        Talk(LocalTime.of(12, 34), listOf(
            Line("Sam Rivera", "So are you finally coming climbing on Saturday?"),
            Line("Alex Morgan", "I'm in. Is it the gym or outside?"),
            Line("Sam Rivera", "Outside if it's dry. Meet at the trailhead at eight, and bring the long rope."),
            Line("Alex Morgan", "Eight it is. I'll pick up coffee on the way."),
            Line("Sam Rivera", "Oh, and my sister's birthday is on the twelfth, remind me to get her something."),
        ), sounds = listOf("Dishes, pots, and pans")),
        Talk(LocalTime.of(15, 10), listOf(
            Line("Priya Shah", "Quick one: the client wants the battery life numbers in the deck."),
            Line("Alex Morgan", "We measured nineteen hours with the radio on. I'll add the chart."),
            Line("Priya Shah", "Can you send it to me by tomorrow noon?"),
            Line("Alex Morgan", "Sure, tomorrow before noon."),
        )),
        Talk(LocalTime.of(18, 41), listOf(
            Line("?", "Hi, I'm here for the package for apartment four."),
            Line("Alex Morgan", "Oh right, it's by the door. Thanks for waiting."),
            Line("?", "No problem. Have a good evening."),
        ), sounds = listOf("Dog")),
    )

    fun seed(c: Context): String {
        val clips = File(c.filesDir, "clips").apply { mkdirs() }
        val transcripts = File(c.filesDir, "transcripts").apply { mkdirs() }
        if (transcripts.listFiles().orEmpty().isNotEmpty()) return "not seeding: this install already has recordings"
        val rnd = Random(7)
        val speakers = SpeakerStore(c)
        val ids = HashMap<String, Long>(); val vecs = HashMap<String, FloatArray>()
        try {
            for (name in people) {
                val id = speakers.newPerson(if (name == "?") null else name)
                val v = FloatArray(256) { (rnd.nextDouble() * 2 - 1).toFloat() }
                speakers.addVoiceprint(id, v, 30.0, null, null, "demo")
                ids[name] = id; vecs[name] = v
            }
        } finally { speakers.close() }
        AssistantPrefs.setOwner(c, ids["Alex Morgan"])

        val zone = ZoneId.systemDefault()
        val json = Json { encodeDefaults = true }
        for (talk in day) {
            var t = LocalDate.now().atTime(talk.at).atZone(zone).toEpochSecond().toDouble()
            // About three lines per 30-second clip, as on a real day.
            for ((k, chunk) in talk.lines.chunked(3).withIndex()) {
                val name = "omi_${t.toLong()}.wav"
                // Full 30-second clips, the lines spread through them with pauses between.
                val seconds = 30.0
                val talkTime = chunk.sumOf { 2.0 + it.text.length / 12.0 }
                val gap = ((seconds - 2 - talkTime) / chunk.size).coerceAtLeast(0.5)
                var at = 1.0
                val segs = chunk.map { l -> val d = 2.0 + l.text.length / 12.0; Segment(at, at + d, "SPEAKER_0${people.indexOf(l.who)}", l.text).also { at += d + gap } }
                val labels = chunk.map { it.who }.distinct()
                val tr = Transcript(name, t + seconds + 5, segs,
                    labels.associate { who -> "SPEAKER_0${people.indexOf(who)}" to SpeakerId(who.takeIf { it != "?" }, 0.86, "match", 0.4,
                        listOf(Candidate(ids[who]!!, who.takeIf { it != "?" }, 0.86, 0)), ids[who], segs.filter { it.speaker == "SPEAKER_0${people.indexOf(who)}" }.sumOf { it.end - it.start }) },
                    labels.associate { who -> "SPEAKER_0${people.indexOf(who)}" to vecs[who]!!.toList() },
                    "demo", 0, sounds = (if (k == 0) talk.sounds else emptyList()).map { SoundTag(it, 0.8, 3.0) })
                File(transcripts, name.removeSuffix(".wav") + ".json").writeText(TranscriptJson.json.encodeToString(Transcript.serializer(), tr))
                File(clips, name.removeSuffix(".wav") + ".json").writeText(json.encodeToString(ClipTimes.serializer(),
                    ClipTimes(t, t + seconds, seconds, "live", null, null, 0, null, true, 16000, (seconds * 50).toInt())))
                t += seconds
            }
        }

        TodoStore(c).use { s ->
            val tomorrowNoon = LocalDate.now().plusDays(1).atTime(11, 30).atZone(zone).toEpochSecond().toDouble()
            s.add("Send Priya the battery life chart", "Promises", tomorrowNoon, "noticed")
            s.add("Rework the two boards with cold joints", "Work", null, "voice")
            s.add("Gift for Sam's sister (birthday on the 12th)", "Personal", null, "assistant")
            s.add("Climbing Saturday 8:00 at the trailhead, bring the long rope", "Personal", null, "noticed")
        }
        LifeStore(c).use { it.addFact("Sam Rivera", "Sam's sister's birthday is on the 12th"); it.addFact("Priya Shah", "Priya is running the client demo on Thursday") }
        AssistantStore(c).use { s ->
            s.addExchange("typed", "What did I promise Priya?",
                "You told Priya you'd send her the battery life chart by tomorrow before noon, for the client deck [L15]. You also said you'd have ten working boards by Wednesday afternoon [L4].", 0.0011)
            s.addExchange("omi", "When am I meeting Sam on Saturday?",
                "Eight in the morning at the trailhead, if it's dry, and Sam asked you to bring the long rope [L10].", 0.0006)
        }
        // A placeholder so the Ask screen looks set up; nothing is asked unless someone types.
        net.boswell.phone.assistant.Secrets.put(c, net.boswell.phone.assistant.Secrets.OPENROUTER, "sk-demo-placeholder")
        c.getSharedPreferences("boswell", Context.MODE_PRIVATE).edit().putBoolean("setup_done", true).commit()
        return "seeded a demo day: ${day.size} conversations"
    }
}
