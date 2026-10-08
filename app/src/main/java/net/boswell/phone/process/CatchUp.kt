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
 * Catch-up at home: recordings the phone transcribed itself while the home
 * server couldn't be reached are done again there, at low priority, keeping
 * everything done by hand (CarryOver).
 *
 * Automatically ([auto], on unless switched off): recordings since home
 * processing was first used ([since]) and at most [AUTO_DAYS] old. By hand
 * ([requestAll]): every recording the phone transcribed that still has its
 * sound. Either way only recordings with speech, transcribed by the phone
 * (not the cloud, not home), whose sound (Ogg or WAV) is still here; a
 * deleted recording is gone from the archive and never comes back.
 *
 * The work itself is ProcessingWorker in catch-up mode, one recording at a
 * time and newest first, under its own name: it never runs while new
 * recordings wait, so live transcripts are never held up; it stops when home
 * can't be reached, and is tried again later.
 */
object CatchUp {
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    val state = MutableStateFlow(CatchUpState())

    /** Recordings older than this are caught up only by hand. */
    const val AUTO_DAYS = 7

    fun auto(c: Context) = p(c).getBoolean("home_catch_up", true)
    fun setAuto(c: Context, on: Boolean) {
        p(c).edit().putBoolean("home_catch_up", on).apply()
        if (on) enqueue(c)
    }

    /**
     * When home processing was first used (epoch seconds): set when pairing or
     * switching it on. For a pairing made before this was kept, the earliest
     * recording transcribed at home stands in ([settleSince]).
     */
    fun since(c: Context): Double? = p(c).getLong("home_since", 0L).takeIf { it > 0 }?.toDouble()

    fun markSince(c: Context) {
        if (since(c) == null) p(c).edit().putLong("home_since", System.currentTimeMillis() / 1000).apply()
    }

    fun settleSince(c: Context, archive: Archive) {
        if (since(c) != null || !net.boswell.phone.home.HomeServer.paired(c)) return
        val first = archive.readableDatabase.rawQuery("SELECT MIN(started) FROM clips WHERE home = 1", null).use { cur ->
            if (cur.moveToFirst() && !cur.isNull(0)) cur.getDouble(0) else null
        } ?: return
        p(c).edit().putLong("home_since", first.toLong()).apply()
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

    /** Transcribed by the phone itself: not in the cloud, not at home. */
    fun byPhone(engine: String) = !engine.contains("(cloud)") && !engine.contains("(home")

    private const val PHONE_MADE = "speech = 1 AND audio = 1 AND cloud = 0 AND home = 0"

    /** Every recording the phone transcribed that still has its sound: what "Redo them at home" covers. */
    fun phoneMade(archive: Archive): List<String> = archive.readableDatabase.rawQuery(
        "SELECT name FROM clips WHERE $PHONE_MADE ORDER BY started DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    /** What's left to catch up, newest first: asked for by hand, and (when [auto]) the recent ones since [since]. */
    fun queue(c: Context, archive: Archive): List<String> {
        if (!net.boswell.phone.home.HomeServer.paired(c)) return emptyList()
        val asked = lines(c, MANUAL)
        val skip = lines(c, SKIP)
        val from = since(c)?.let { maxOf(it, System.currentTimeMillis() / 1000.0 - AUTO_DAYS * 86_400) }
        val recent = from != null && auto(c) && net.boswell.phone.home.HomeServer.enabled(c)
        return archive.readableDatabase.rawQuery("SELECT name, started FROM clips WHERE $PHONE_MADE ORDER BY started DESC", null).use { cur ->
            buildList {
                while (cur.moveToNext()) {
                    val name = cur.getString(0)
                    if (name in skip) continue
                    if (name in asked || (recent && cur.getDouble(1) >= from!!)) add(name)
                }
            }
        }
    }

    /** "Redo them at home": every recording the phone transcribed, tried again even if it failed before. */
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
