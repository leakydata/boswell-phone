package net.boswell.phone.assistant

import android.content.Context
import net.boswell.phone.process.Transcript
import net.boswell.phone.speakers.SpeakerStore

/**
 * Runs voice triggers over one newly written transcript.
 *
 * A line fires a trigger when it contains one of its phrases and -- unless
 * the trigger allows anyone -- the voice is the owner's. Each line fires at
 * most once (remembered in assistant.db), and nothing recorded before the
 * triggers were switched on fires at all. The assistant gets the line, the
 * lines around it for context, the time it was said, and the trigger's
 * instruction, and is told to do nothing if it was not really a request.
 */
class TriggerEngine(private val context: Context) {

    /** [force] skips the master switch and start time (debug builds use it to test without changing settings). */
    fun run(t: Transcript, clipStarted: Double, force: Boolean = false) {
        if (!force && (!Triggers.enabled(context) || clipStarted < Triggers.since(context))) return
        if (Llm.forAssistant(context) == null) return
        val triggers = Triggers.all(context).filter { it.enabled }
        if (triggers.isEmpty() || t.segments.isEmpty()) return
        val owner = AssistantPrefs.owner(context)
        val speakers = SpeakerStore(context)
        val store = AssistantStore(context)
        try {
            for ((i, seg) in t.segments.withIndex()) {
                val label = seg.speaker
                // Boswell's own spoken answer ("…say Hey Boswell…") is never a request.
                if (label == net.boswell.phone.process.BoswellLines.LABEL || net.boswell.phone.process.BoswellLines.isBoswell(label?.let { t.speakers[it] })) continue
                val person = label?.let { speakers.currentPerson(t.clip, it, t.speakers[it]?.personId) }
                for (trig in triggers) {
                    val phrase = Triggers.match(trig, seg.text) ?: continue
                    // "Only my voice": the owner, or a voice whose best guess is the owner
                    // (saying the phrase is itself a strong hint) -- a filed-but-unsure
                    // owner voice once made "Hey Boswell" do nothing.
                    val guess = label?.let { t.speakers[it] }?.takeIf { it.score >= net.boswell.phone.speakers.Matching.MATCH_LOW }
                        ?.candidates?.firstOrNull()?.personId?.let(speakers::resolve)
                    if (trig.ownerOnly && (owner == null || (person != owner && guess != owner))) continue
                    if (!store.claimTrigger(t.clip, seg.start, trig.id)) continue
                    val around = t.segments.subList((i - 2).coerceAtLeast(0), (i + 3).coerceAtMost(t.segments.size))
                        .joinToString("\n") { s -> (if (s === seg) "> " else "  ") + s.text }
                    val said = clipStarted + seg.start
                    val a = Assistant(context).ask(
                        question = "\"${seg.text}\"\n\nAround it:\n$around",
                        source = Assistant.TRIGGER,
                        instruction = instruction(trig),
                        saidAt = said,
                        fileOnly = trig.action == Trigger.Action.TODO || trig.action == Trigger.Action.CALENDAR,
                        display = seg.text,
                        asked = said to clipStarted + seg.end,
                    )
                    if (a.text == Assistant.NONE) continue
                    AssistantNotify.post(context, AssistantNotify.ANSWERS, "Heard \"$phrase\"", a.text)
                }
            }
        } finally {
            speakers.close(); store.close()
        }
    }

    private fun instruction(t: Trigger): String = when (t.action) {
        Trigger.Action.TODO -> "file what they asked to be reminded of or to remember with add_todo" +
            (t.category?.let { " in the category \"$it\"" } ?: ", picking a fitting category") +
            ", with a due time only if they gave one. Reply with a very short confirmation."
        Trigger.Action.CALENDAR -> "add the event they described with add_calendar_event (ask nothing back; make reasonable assumptions and say what you assumed). Reply with a very short confirmation."
        Trigger.Action.ANSWER -> "answer what they asked or do what they requested, briefly, as a notification."
        Trigger.Action.CUSTOM -> t.instruction?.takeIf { it.isNotBlank() } ?: "do what they asked."
    }
}
