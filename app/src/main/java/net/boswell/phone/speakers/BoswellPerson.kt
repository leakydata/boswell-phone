package net.boswell.phone.speakers

import android.content.Context
import net.boswell.phone.archive.Archive
import net.boswell.phone.audio.writeAtomically
import net.boswell.phone.process.BoswellLines
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson

/**
 * Before Boswell recognized its own voice, the owner could only name it like
 * anyone else ("Boswell Male Voice"), so it became a person: in People, in
 * the day's "with …", in talk stats, a candidate for every voice. This turns
 * that person into Boswell, once and by itself: every recording voice that
 * was them is Boswell's (as if recognized when it was transcribed, so "Not
 * Boswell" works on it the same way), their voiceprints teach Boswell's
 * learned voice, and the person is gone. Nothing to do -- the usual case --
 * costs one query, and doing it twice changes nothing.
 */
object BoswellPerson {
    /** Returns how many people were turned into Boswell. */
    @Synchronized
    fun convert(c: Context): Int {
        val store = SpeakerStore(c)
        try {
            val owner = net.boswell.phone.assistant.AssistantPrefs.owner(c)?.let(store::resolve)
            val ids = store.boswellNamed(owner).toSet()
            if (ids.isEmpty()) return 0
            // Which recording voices are them, decided before they stop being anyone.
            for (f in ProcessingWorker.transcriptsDir(c).listFiles { x -> x.extension == "json" }.orEmpty()) {
                val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull() ?: continue
                val labels = t.speakers.filter { (label, sp) ->
                    label != BoswellLines.LABEL && !BoswellLines.isBoswell(sp) && store.currentPerson(t.clip, label, sp.personId) in ids
                }.keys
                val u = BoswellLines.relabel(t, labels) ?: continue
                writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(), u).toByteArray())
            }
            // Its learned voice belongs to the TTS voice it speaks with; if none has spoken yet, the next that does.
            val voice = runCatching { net.boswell.phone.assistant.AssistantStore(c).use { it.lastVoice(Double.MAX_VALUE) } }.getOrNull()
                ?: net.boswell.phone.assistant.AssistantPrefs.ttsVoice(c) ?: SpeakerStore.UNKNOWN_VOICE
            store.retireAsBoswell(ids, voice)
            Archive(c).use { it.sync(store, force = true) }
            net.boswell.phone.capture.CaptureRepository.log("${ids.size} voice${if (ids.size == 1) "" else "s"} named for Boswell now recognized as Boswell")
            return ids.size
        } finally { store.close() }
    }
}
