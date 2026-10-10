package net.boswell.phone

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.boswell.phone.ArchiveFixture.Companion.noisy
import net.boswell.phone.ArchiveFixture.Companion.randomUnit
import net.boswell.phone.archive.Archive
import net.boswell.phone.assistant.Assistant
import net.boswell.phone.assistant.MoreTools
import net.boswell.phone.backup.Backup
import net.boswell.phone.backup.HashCache
import net.boswell.phone.backup.Incremental
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.capture.ClipTimes
import net.boswell.phone.diarize.VoiceModel
import net.boswell.phone.process.Candidate
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.Segment
import net.boswell.phone.process.SpeakerId
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson
import net.boswell.phone.sound.SoundTag
import net.boswell.phone.speakers.LabelChecks
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.SpeakerStore
import net.boswell.phone.speakers.VoiceReview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * How much heap the app's big jobs take, on a made-up archive shaped like the
 * owner's (2026-10-10 backup: 21,500 transcripts of median 3.2 KB, 54% with
 * speech, 0.7 voices and 65 characters of text each on average; a few
 * thousand voiceprints). Each job is measured for what its thread allocates
 * (ThreadMXBean), its peak live heap (a sampler forcing a full GC every
 * [SAMPLE_MS] and reading what's left) and what stays reachable after it.
 *
 * The default size is an eighth of the archive, to keep the suite quick; the
 * whole thing: ./gradlew :app:testDebugUnitTest --tests '*MemoryPeaksTest*' -Dmem.clips=21500
 * The table is printed (build/test-results/.../TEST-net.boswell.phone.MemoryPeaksTest.xml).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryPeaksTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fx = ArchiveFixture(context)
    private val model = Matching.model
    private val owner = Matching.owner

    @After fun restore() { Matching.model = model; Matching.owner = owner; fx.wipe() }

    private val clips = System.getProperty("mem.clips")?.toIntOrNull() ?: 2_700

    // ------------------------------------------------------------- the archive

    private val words = listOf("the", "we", "should", "call", "back", "about", "Saturday", "meeting", "budget", "is", "due", "Friday", "okay",
        "really", "I", "think", "so", "dinner", "at", "seven", "kids", "school", "pick", "up", "number", "is", "store", "price", "dollars")

    private fun sentence(rnd: Random): String {
        val n = 4 + rnd.nextInt(14)
        val s = StringBuilder()
        repeat(n) { s.append(words[rnd.nextInt(words.size)]).append(' ') }
        when (rnd.nextInt(6)) {
            0 -> s.append("call 555 ${100 + rnd.nextInt(900)} ${1000 + rnd.nextInt(9000)}")
            1 -> s.append("it was $${rnd.nextInt(500)}.${rnd.nextInt(100)}")
            2 -> s.append("room ${rnd.nextInt(400)} at ${1 + rnd.nextInt(12)}")
            else -> s.append("right")
        }
        return s.toString()
    }

    /** The archive: [clips] clips 30 s long, now and then a gap, ending now. */
    private fun build() {
        val rnd = Random(11)
        val prefs = context.getSharedPreferences("boswell", Context.MODE_PRIVATE)
        prefs.edit().putString("voice_model", VoiceModel.SPEAKER_ID.id).commit()
        val store = SpeakerStore(context)
        try {
            val dim = Matching.model.dim
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                val named = List(30) { i -> store.newPerson(if (i == 0) "Me" else "Person $i") }
                prefs.edit().putLong("owner_person", named[0]).commit()
                val unnamed = List(maxOf(20, clips / 70)) { store.newPerson(null) }
                val centers = HashMap<Long, FloatArray>()
                for (p in named + unnamed) centers[p] = randomUnit(rnd, dim)
                // Everyone named has a few voiceprints of their own to start with.
                for (p in named) repeat(3) { store.addVoiceprint(p, noisy(rnd, centers.getValue(p), 0.5), 6.0, null, null, "manual") }

                var ended = System.currentTimeMillis() / 1000 - clips * 40L
                for (i in 0 until clips) {
                    ended += 30 + if (rnd.nextDouble() < 0.1) 120 + rnd.nextInt(1700) else 0
                    val clip = "omi_$ended.wav"
                    fx.sidecar(clip, ended - 30.0, ended.toDouble())
                    val speech = rnd.nextDouble() < 0.54
                    val voices = LinkedHashMap<String, SpeakerId>()
                    val embs = LinkedHashMap<String, List<Float>>()
                    val segs = mutableListOf<Segment>()
                    if (speech) {
                        repeat(if (rnd.nextDouble() < 0.3) 2 else 1) { k ->
                            val label = "SPEAKER_0$k"
                            val roll = rnd.nextDouble()
                            val who = when {
                                roll < 0.45 -> named[0]
                                roll < 0.65 -> named[1 + rnd.nextInt(named.size - 1)]
                                roll < 0.90 -> unnamed[rnd.nextInt(unnamed.size)]
                                else -> null       // a stranger heard once
                            }
                            val emb = noisy(rnd, who?.let { centers.getValue(it) } ?: randomUnit(rnd, dim), 0.4 + rnd.nextDouble())
                            val secs = 0.5 + rnd.nextDouble() * 12
                            val snr = 5 + rnd.nextDouble() * 25
                            val score = 0.3 + rnd.nextDouble() * 0.6
                            val isNamed = who != null && who in named
                            voices[label] = SpeakerId(null, score, if (isNamed && score > 0.6) "matched" else if (score > 0.5) "uncertain" else "none", 0.1,
                                listOf(Candidate(named[rnd.nextInt(named.size)], null, score, 1)), who?.takeIf { !isNamed || score > 0.6 }, secs, snr)
                            if (rnd.nextDouble() < 0.86) embs[label] = emb.toList()
                            // Filed as processing files voices: some of the named ones, most of the unnamed ones.
                            if (who != null && secs >= 3 && embs.containsKey(label) && rnd.nextDouble() < (if (isNamed) 0.25 else 0.6))
                                store.addVoiceprint(who, emb, secs, clip, label, "auto")
                            segs += Segment(k * 9.0 + 1, k * 9.0 + 7, label, sentence(rnd))
                        }
                    }
                    val sounds = List(10) { SoundTag(listOf("Speech", "Silence", "Music", "Inside, small room", "Television", "Dog")[it % 6], rnd.nextDouble(), it * 3.0) }
                    fx.transcript(Transcript(clip, ended.toDouble(), segs, voices, embs, "sherpa (phone)", 900, sounds, if (speech) "keep" else "empty"))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } finally { store.close() }
        fx.settle()
    }

    // ------------------------------------------------------------ measuring

    private data class Row(val name: String, val allocatedMb: Double, val peakMb: Double, val retainedMb: Double, val ms: Long)

    private val rows = mutableListOf<Row>()

    private fun used(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun settled(): Long { repeat(3) { System.gc(); Thread.sleep(15) }; return used() }
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Run [block] once, measured. [threadsAllocating]: other threads it starts, whose allocation counts too. */
    private fun <T> measure(name: String, block: () -> T): T {
        val base = settled()
        val peak = AtomicLong(base)
        val stop = AtomicBoolean(false)
        val sampler = thread(isDaemon = true) {
            while (!stop.get()) { System.gc(); peak.accumulateAndGet(used(), ::maxOf); Thread.sleep(SAMPLE_MS) }
        }
        val me = Thread.currentThread().id
        val a0 = threads.getThreadAllocatedBytes(me)
        val t0 = System.currentTimeMillis()
        val r = block()
        val ms = System.currentTimeMillis() - t0
        val allocated = threads.getThreadAllocatedBytes(me) - a0
        stop.set(true); sampler.join()
        val retained = settled() - base
        java.lang.ref.Reference.reachabilityFence(r)
        rows += Row(name, allocated / MB, (maxOf(peak.get(), base + retained) - base) / MB, retained / MB, ms)
        return r
    }

    private fun report(): String = buildString {
        val scale = 21_500.0 / clips
        appendLine("Memory on a made-up archive of $clips clips (x%.1f for the owner's 21,500)".format(scale))
        appendLine("%-58s %10s %9s %9s %8s %12s".format("job", "alloc MB", "peak MB", "kept MB", "ms", "peak x scale"))
        for (r in rows.sortedByDescending { it.peakMb })
            appendLine("%-58s %10.1f %9.1f %9.1f %8d %12.0f".format(r.name, r.allocatedMb, r.peakMb, r.retainedMb, r.ms, r.peakMb * scale))
    }

    // ------------------------------------------------------- the suspects

    /** MainViewModel.loadClips as it was until 0.10.0: every clip with its transcript, for a list no screen showed. */
    private data class OldLine(val who: String, val named: Boolean, val text: String)
    private data class OldView(val lines: List<OldLine>, val error: String? = null)
    private data class OldEntry(val file: File, val seconds: Double, val endedMillis: Long, val timeKnown: Boolean, val transcript: OldView? = null)

    private fun oldLoadClips(speakers: SpeakerStore): List<OldEntry> {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val dir = CaptureService.clipsDir(context)
        val tdir = ProcessingWorker.transcriptsDir(context)
        val names = HashMap<Long, String?>()
        fun nameOf(pid: Long) = names.getOrPut(pid) { speakers.nameOf(pid) }
        return dir.listFiles { f -> f.extension == "json" }.orEmpty().map { File(dir, it.nameWithoutExtension + ".wav") }.map { wav ->
            val times = runCatching { json.decodeFromString(ClipTimes.serializer(), File(dir, wav.nameWithoutExtension + ".json").readText()) }.getOrNull()
            OldEntry(wav, times?.seconds ?: 0.0, ((times?.ended ?: 0.0) * 1000).toLong(), times?.timeKnown ?: false,
                File(tdir, wav.nameWithoutExtension + ".json").takeIf { it.exists() }?.let { f ->
                    val text = f.readText()
                    runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), text) }.fold(
                        onSuccess = { t -> OldView(t.segments.map { s ->
                            val id = s.speaker?.let { t.speakers[it] }
                            val name = id?.personId?.let(::nameOf) ?: id?.name
                            OldLine(name ?: id?.personId?.let { "Unknown #$it" } ?: s.speaker ?: "?", name != null, s.text)
                        }) },
                        onFailure = { OldView(emptyList(), "unreadable") })
                })
        }.sortedByDescending { it.endedMillis }
    }

    /** MoreTools' find_details as it was: every match of the span built, to be cut to 12,000 characters after. */
    private fun oldFindDetails(archive: Archive, speakers: SpeakerStore, hours: Int): String {
        val patterns = mapOf(
            "phone" to Regex("""(?<!\d)(?:\+?1[\s.-]?)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}(?!\d)"""),
            "email" to Regex("""[\w.+-]+\s?(?:@|\bat\b)\s?[\w-]+\s?(?:\.|\bdot\b)\s?[a-z]{2,}""", RegexOption.IGNORE_CASE),
            "link" to Regex("""\b(?:https?://)?[\w-]+(?:\.|\s+dot\s+)(?:com|org|net|io|gov|edu|co)(?:/\S*)?""", RegexOption.IGNORE_CASE),
            "address" to Regex("""\b\d{1,6}\s+(?:[A-Z][a-z]+\s){1,3}(?:Street|St|Avenue|Ave|Road|Rd|Drive|Dr|Lane|Ln|Boulevard|Blvd|Court|Ct|Way|Place|Pl)\b"""),
            "number" to Regex("""(?<![\w.])\$?\d[\d,]*(?:\.\d+)?(?![\w.])"""),
        )
        val t = DateTimeFormatter.ofPattern("EEE MMM d h:mm a")
        fun at(epoch: Double) = Instant.ofEpochSecond(epoch.toLong()).atZone(ZoneId.systemDefault()).format(t)
        val since = System.currentTimeMillis() / 1000.0 - hours * 3600.0
        val out = StringBuilder()
        archive.readableDatabase.rawQuery("""
            SELECT l.id, l.t0, l.text, s.conv_key FROM lines l LEFT JOIN clip_speakers s ON s.clip = l.clip AND s.label = l.label
            WHERE l.t0 >= ? ORDER BY l.t0""", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(2)
                for (k in patterns.keys) for (m in patterns.getValue(k).findAll(text)) {
                    if (k == "number" && m.value.filter { it.isDigit() }.length < 2) continue
                    val who = if (c.getString(3) == Archive.BOSWELL) net.boswell.phone.process.BoswellLines.AS_SAID_BY
                        else c.getString(3)?.takeIf { it.startsWith("p") }?.drop(1)?.toLongOrNull()?.let(speakers::nameOf) ?: "someone"
                    out.appendLine("$k: ${m.value.trim()} — ${at(c.getDouble(1))} $who [L${c.getLong(0)}]: \"${text.take(160)}\"")
                }
            }
        }
        return out.toString().ifBlank { "no details found in the last $hours hours" }
    }

    private fun forgetCohort() {
        SpeakerStore::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
    }

    @Test fun `the big jobs, measured`() {
        val built = System.currentTimeMillis()
        build()
        println("built $clips clips in ${System.currentTimeMillis() - built} ms")
        val speakers = SpeakerStore(context)
        val archive = Archive(context)
        try {
            measure("Archive.sync, first index of every transcript") { archive.sync(speakers) }
            measure("Archive.sync(force): every conversation regrouped") { archive.sync(speakers, force = true) }

            // The clip list MainViewModel kept, read in full at every clip written and every step of processing.
            measure("MainViewModel.loadClips (removed): one full read") { oldLoadClips(speakers) }
            // Every clip written and every step of processing started another, never waiting for the last:
            // a download of the Omi's storage writes clips in a burst.
            for (n in listOf(4, 16)) measure("MainViewModel.loadClips (removed): $n at once, as a burst set off") {
                val kept = arrayOfNulls<List<OldEntry>>(n)
                List(n) { i -> thread { kept[i] = SpeakerStore(context).use { oldLoadClips(it) } } }.forEach { it.join() }
                kept.sumOf { it?.size ?: 0 }
            }

            forgetCohort()
            measure("SpeakerStore.norm: the AS-norm cohort made") { speakers.norm() }
            forgetCohort()
            val r = measure("VoiceReview.recheck (index, pool, cohort, every voice)") { VoiceReview(context).recheck() }
            println("recheck: $r")
            measure("VoiceReview.suggestions") { VoiceReview(context).suggestions() }
            measure("LabelChecks.find (pairwise over named prints)") { LabelChecks(context).find() }
            measure("SpeakerStore.unnamedClusters + namedPrints") { speakers.unnamedClusters() to speakers.namedPrints() }

            val tools = MoreTools(context)
            val month = buildJsonObject { put("kind", JsonPrimitive("any")); put("hours", JsonPrimitive(24 * 30)) }
            val before = measure("assistant find_details, a month of any (as it was)") { oldFindDetails(archive, speakers, 24 * 30) }
            val now = measure("assistant find_details, a month of any (now)") { tools.run("find_details", month, archive, speakers) }
            println("find_details: built ${before.length} characters before, ${now.length} now; the model is given ${Assistant.TOOL_RESULT_CHARS}")
            measure("assistant search_transcripts (60 hits)") { archive.search("budget friday", 60) }
            measure("assistant recent_lines / read_conversation (400 lines)") {
                Assistant(context).lines(archive, speakers, 0.0)
            }

            val files = Backup.listFiles(context.filesDir)
            val cacheFile = File(context.noBackupFilesDir, "hashes.tsv")
            measure("home backup: Incremental.list of ${files.size} files (hashing)") {
                val cache = HashCache(cacheFile)
                Incremental.list(emptyList(), files, cache).also { cache.save() }
            }
            measure("home backup: Incremental.list again (from the cache)") {
                val cache = HashCache(cacheFile)
                Incremental.list(emptyList(), files, cache).also { cache.save() }
            }
            measure("backup export: the zip streamed") {
                runCatching { Backup.export(context, java.io.OutputStream.nullOutputStream(), includeKeys = false) }.getOrNull()
            }
        } finally { archive.close(); speakers.close() }
        println(report())
    }

    // ------------------------------------------------------ what's checked

    /** find_details stops at what the model is given, and gives it exactly what it got before. */
    @Test fun `find_details gives the model what it did, without building the rest`() {
        build()
        val speakers = SpeakerStore(context)
        val archive = Archive(context)
        try {
            archive.sync(speakers)
            val tools = MoreTools(context)
            for (hours in listOf(1, 24, 24 * 30)) for (kind in listOf("any", "phone", "number", "email")) {
                val args = buildJsonObject { put("kind", JsonPrimitive(kind)); put("hours", JsonPrimitive(hours)) }
                val old = if (kind == "any") oldFindDetails(archive, speakers, hours) else null
                val new = tools.run("find_details", args, archive, speakers)
                if (old != null) assertEquals("$kind, $hours h", old.take(Assistant.TOOL_RESULT_CHARS), new.take(Assistant.TOOL_RESULT_CHARS))
                // Never much more than what's given: the line that crossed the limit at most.
                assertTrue("$kind, $hours h: ${new.length}", new.length < Assistant.TOOL_RESULT_CHARS + 400)
            }
            val month = buildJsonObject { put("kind", JsonPrimitive("any")); put("hours", JsonPrimitive(24 * 30)) }
            assertTrue("the made-up month has more than the limit", oldFindDetails(archive, speakers, 24 * 30).length > 3 * Assistant.TOOL_RESULT_CHARS)
            tools.run("find_details", month, archive, speakers)
        } finally { archive.close(); speakers.close() }
    }

    /** One heavy job at a time: the next waits; one inside another goes on. */
    @Test fun `heavy voice work takes turns`() {
        val inside = AtomicInteger(0)
        val most = AtomicInteger(0)
        List(6) {
            thread {
                repeat(20) {
                    HeavyWork.run("test") {
                        most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                        // Reentrant: a recheck inside a voice update doesn't wait on itself.
                        HeavyWork.run("inner") { Thread.sleep(1) }
                        inside.decrementAndGet()
                    }
                }
            }
        }.forEach { it.join() }
        assertEquals(1, most.get())
        assertFalse(HeavyWork.busy)
    }

    /** A recheck, a review and the label checks wait while another heavy job runs. */
    @Test fun `recheck, suggestions and label checks wait their turn`() {
        build()
        for (job in listOf<() -> Any>({ VoiceReview(context).recheck() }, { VoiceReview(context).suggestions() }, { LabelChecks(context).find() })) {
            val done = AtomicBoolean(false)
            val release = java.util.concurrent.CountDownLatch(1)
            val holding = java.util.concurrent.CountDownLatch(1)
            val holder = thread { HeavyWork.run("holder") { holding.countDown(); release.await() } }
            holding.await()
            val t = thread { job(); done.set(true) }
            Thread.sleep(300)
            assertFalse("ran beside another heavy job", done.get())
            release.countDown(); holder.join(); t.join(60_000)
            assertTrue(done.get())
        }
    }

    companion object {
        private const val MB = 1024.0 * 1024.0
        private val SAMPLE_MS = System.getProperty("mem.sample")?.toLongOrNull() ?: 40L
    }
}
