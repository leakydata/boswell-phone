package net.boswell.phone.process

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import net.boswell.phone.archive.Archive
import java.io.File
import java.util.concurrent.TimeUnit
import net.boswell.phone.capture.logged

/** Catching up at home, while it runs: [done] of [done] + [left], for Device → Home server. */
data class CatchUpState(val running: Boolean = false, val done: Int = 0, val left: Int = 0, val note: String? = null)

/**
 * Catch-up at home: every recording not transcribed on the home server -- done
 * by the phone itself while home couldn't be reached, or in the cloud -- is done
 * again there, at low priority, so the archive is as good as home makes it
 * without the cloud.
 *
 * Automatically ([auto], on unless switched off): all of them, however old,
 * except recordings with something corrected by hand ([byHand]), which are left
 * exactly as they are. By hand ([requestAll], "Redo them at home"): those too,
 * keeping what was done by hand (CarryOver). Either way only recordings with
 * speech whose sound (Ogg or WAV) is still here; a deleted recording is gone
 * from the archive and never comes back.
 *
 * The work itself is ProcessingWorker in catch-up mode, one recording at a
 * time and newest first, under its own name: it never runs while new
 * recordings wait, so live transcripts are never held up; it stops when home
 * can't be reached, and is tried again later.
 */
object CatchUp {
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    val state = MutableStateFlow(CatchUpState())

    fun auto(c: Context) = p(c).getBoolean("home_catch_up", true)
    fun setAuto(c: Context, on: Boolean) {
        p(c).edit().putBoolean("home_catch_up", on).apply()
        if (on) enqueue(c)
    }

    /**
     * The phone transcribed something itself while home was in use, so there may
     * be catching up to do: a recording run that ends with home answering starts
     * a catch-up only then, not after every recording.
     */
    fun owe(c: Context) = p(c).edit().putBoolean("home_catch_up_owed", true).apply()
    fun owed(c: Context) = p(c).getBoolean("home_catch_up_owed", true) || asked(c) > 0
    fun paid(c: Context) = p(c).edit().putBoolean("home_catch_up_owed", false).apply()

    // ------------------------------------------------------------- what to redo

    /** Not transcribed at home: by the phone, or in the cloud. */
    fun notHome(engine: String) = !engine.contains("(home")

    private const val NOT_HOME = "speech = 1 AND audio = 1 AND home = 0"

    /** Every recording not transcribed at home that still has its sound: what "Redo them at home" covers. */
    fun notHome(archive: Archive): List<String> = archive.readableDatabase.rawQuery(
        "SELECT name FROM clips WHERE $NOT_HOME ORDER BY started DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    /**
     * Recordings with something corrected by hand -- a line's words edited, or a voice
     * named or confirmed there (not one the phone filed itself, a TV check's included):
     * the automatic catch-up leaves them as they are.
     */
    fun byHand(archive: Archive, store: net.boswell.phone.speakers.SpeakerStore): Set<String> =
        archive.readableDatabase.rawQuery("SELECT DISTINCT clip FROM lines WHERE original IS NOT NULL", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        } + store.clipsNamedByHand()

    private fun byHand(c: Context, archive: Archive): Set<String> {
        val store = net.boswell.phone.speakers.SpeakerStore(c)
        return try { byHand(archive, store) } finally { store.close() }
    }

    /**
     * What's left to catch up, newest first: asked for by hand, and (when [auto]) every
     * other recording not done at home that wasn't corrected by hand. [hand] is [byHand],
     * when the caller has it already (it's asked for once per recording in a run).
     */
    fun queue(c: Context, archive: Archive, hand: Set<String>? = null): List<String> {
        if (!net.boswell.phone.home.HomeServer.paired(c)) return emptyList()
        val asked = lines(c, MANUAL)
        val skip = lines(c, SKIP)
        val automatic = auto(c) && net.boswell.phone.home.HomeServer.enabled(c)
        if (!automatic && asked.isEmpty()) return emptyList()
        val leave = if (automatic) hand ?: byHand(c, archive) else emptySet()
        return pick(notHome(archive), asked, skip, automatic, leave)
    }

    /** Of the recordings not done at home: those asked for by hand, and when [automatic] the rest but [leave]; never [skip]. */
    fun pick(notHome: List<String>, asked: Set<String>, skip: Set<String>, automatic: Boolean, leave: Set<String>): List<String> =
        notHome.filter { it !in skip && (it in asked || (automatic && it !in leave)) }

    /** "Redo them at home": every recording not done at home, tried again even if it failed before. */
    fun requestAll(c: Context, names: List<String>) {
        synchronized(this) {
            File(c.filesDir, MANUAL).writeText((lines(c, MANUAL) + names).joinToString("\n"))
            File(c.filesDir, SKIP).delete()
        }
        enqueue(c, now = true)
    }

    /** Done (or given up on): off the list asked for by hand. */
    @Synchronized fun handled(c: Context, name: String) {
        val f = File(c.filesDir, MANUAL)
        if (!f.exists()) return
        val rest = lines(c, MANUAL) - name
        if (rest.isEmpty()) f.delete() else f.writeText(rest.joinToString("\n"))
    }

    /** A recording home couldn't do (not for being unreachable): not tried again until asked by hand. */
    @Synchronized fun skip(c: Context, name: String) {
        File(c.filesDir, SKIP).writeText((lines(c, SKIP) + name).joinToString("\n"))
        handled(c, name)
    }

    /** Asked for by hand and not done yet. */
    fun asked(c: Context): Int = lines(c, MANUAL).size

    @Synchronized private fun lines(c: Context, file: String): Set<String> =
        File(c.filesDir, file).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.toSet().orEmpty()

    private const val MANUAL = "catch_up_asked.txt"
    private const val SKIP = "catch_up_skip.txt"

    // ------------------------------------------------------------- scheduling

    private const val WORK = "home-catch-up"

    /**
     * Run when there's a chance: KEEP, so a run already waiting or running is
     * left alone. [now] for the button; otherwise after a minute, so a burst
     * of live recordings settles first.
     */
    fun enqueue(c: Context, now: Boolean = false) {
        val wm = WorkManager.getInstance(c)
        // By hand: right away, in place of one waiting for later -- but a run under way is let finish (it takes the new ones too).
        val running = now && runCatching { wm.getWorkInfosForUniqueWork(WORK).get().any { it.state == androidx.work.WorkInfo.State.RUNNING } }.logged("catch-up: work state").getOrDefault(false)
        wm.enqueueUniqueWork(WORK, if (now && !running) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request(if (now) 0 else 1, TimeUnit.MINUTES))
    }

    /** From inside a run: the next one after this one ends (new recordings first, or home away for a while). */
    fun next(c: Context, delay: Long, unit: TimeUnit) =
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(delay, unit))

    private fun request(delay: Long, unit: TimeUnit) = OneTimeWorkRequestBuilder<ProcessingWorker>()
        .setInputData(workDataOf(ProcessingWorker.CATCH_UP to true))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
        .setInitialDelay(delay, unit)
        .build()

    /** A look every few hours, in case nothing else started one (home came back while the phone was quiet). */
    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<CatchUpKick>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("$WORK-kick", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** Starts a catch-up run if there's anything to catch up; does nothing itself. */
class CatchUpKick(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (net.boswell.phone.home.HomeServer.paired(applicationContext)) CatchUp.enqueue(applicationContext)
        return Result.success()
    }
}

/**
 * Recordings transcribed again (a Redo, or caught up at home): the transcript
 * being replaced is held here while its recording is done again, so what was
 * done by hand can be carried over (CarryOver), and its words are kept
 * afterward until the conversation's title and summary have been looked at
 * again (ConversationNotes).
 */
object Redone {
    private fun dir(c: Context, sub: String) = File(c.filesDir, sub).apply { mkdirs() }
    private fun base(clip: String) = clip.removeSuffix(".wav")

    /** The transcript a Redo replaces, kept until its recording has been transcribed again. */
    fun held(c: Context, clip: String) = File(dir(c, "redo"), base(clip) + ".json")

    fun heldClips(c: Context): Set<String> = dir(c, "redo").listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension + ".wav" }.toSet()

    /** The words before, for the notes: the first ones are kept when a recording is done again twice before they're looked at. */
    fun remember(c: Context, clip: String, text: String) {
        val f = File(dir(c, "redone"), base(clip) + ".txt")
        if (!f.exists()) net.boswell.phone.audio.writeAtomically(f, text.toByteArray())
    }

    /** Clip -> its words before, for the ones not looked at yet; any older than [KEEP_DAYS] are let go. */
    fun before(c: Context): Map<String, String> {
        val old = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        return dir(c, "redone").listFiles { f -> f.extension == "txt" }.orEmpty().mapNotNull { f ->
            if (f.lastModified() < old) { f.delete(); null } else f.nameWithoutExtension + ".wav" to f.readText()
        }.toMap()
    }

    fun forget(c: Context, clips: Collection<String>) {
        for (clip in clips) File(dir(c, "redone"), base(clip) + ".txt").delete()
    }

    /** A deleted recording leaves nothing behind here either. */
    fun delete(c: Context, clips: Collection<String>) {
        for (clip in clips) held(c, clip).delete()
        forget(c, clips)
    }

    private const val KEEP_DAYS = 14
}
