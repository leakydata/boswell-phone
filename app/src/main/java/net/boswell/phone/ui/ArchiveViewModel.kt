package net.boswell.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.boswell.phone.archive.Archive
import net.boswell.phone.archive.ClipRow
import net.boswell.phone.archive.Conversation
import net.boswell.phone.archive.LineRow
import net.boswell.phone.archive.SearchHit
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.process.ProcessingRepository
import net.boswell.phone.speakers.Person
import net.boswell.phone.speakers.SpeakerStore
import net.boswell.phone.speakers.VoiceGroup
import java.io.File
import java.time.LocalDate

/** How a conversation-level voice is shown. */
data class Voice(val key: String, val name: String, val named: Boolean, val personId: Long?, val media: Boolean)

data class DayState(
    val day: LocalDate = LocalDate.now(),
    val daysWithTalk: List<LocalDate> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val ribbon: List<ClipRow> = emptyList(),
    val voices: Map<String, Voice> = emptyMap(),
    val loading: Boolean = true,
    /** To-dos due this day (and, on today, anything overdue). */
    val todos: List<net.boswell.phone.todo.Todo> = emptyList(),
)

data class ConversationState(
    val conversation: Conversation? = null,
    val lines: List<LineRow> = emptyList(),
    val voices: Map<String, Voice> = emptyMap(),
    val guesses: Map<String, Pair<Person, Double>> = emptyMap(),
    val playingLine: Long? = null,
    val playing: Boolean = false,
    val position: Double = 0.0,
    val length: Double = 0.0,
    /** Jump over stretches without speech longer than PAUSE_SKIP_S while playing. */
    val skipPauses: Boolean = true,
)

data class PeopleState(val queue: List<Person> = emptyList(), val named: List<Person> = emptyList(), val media: List<Person> = emptyList())

data class PersonState(val person: Person? = null, val conversations: List<Conversation> = emptyList(), val groups: List<VoiceGroup> = emptyList())

/** A little air either side of a voice's part, so words aren't clipped. */
private const val PAD_S = 0.15

/** Pauses at least this long are skipped in conversation playback. */
private const val PAUSE_SKIP_S = 3.0

class ArchiveViewModel(app: Application) : AndroidViewModel(app) {
    private val archive = Archive(app)
    private val speakers = SpeakerStore(app)

    private val _day = MutableStateFlow(DayState())
    val day: StateFlow<DayState> = _day.asStateFlow()
    private val _conv = MutableStateFlow(ConversationState())
    val conv: StateFlow<ConversationState> = _conv.asStateFlow()
    private val _people = MutableStateFlow(PeopleState())
    val people: StateFlow<PeopleState> = _people.asStateFlow()
    private val _person = MutableStateFlow(PersonState())
    val person: StateFlow<PersonState> = _person.asStateFlow()
    private val _search = MutableStateFlow<List<SearchHit>>(emptyList())
    val search: StateFlow<List<SearchHit>> = _search.asStateFlow()
    private val _searchVoices = MutableStateFlow<Map<String, Voice>>(emptyMap())
    val searchVoices: StateFlow<Map<String, Voice>> = _searchVoices.asStateFlow()

    /** A voice that might be someone known, with what it said, for the review. */
    data class ReviewItem(val s: net.boswell.phone.speakers.Suggestion, val said: String)
    private val _review = MutableStateFlow<List<ReviewItem>?>(null)
    val review: StateFlow<List<ReviewItem>?> = _review.asStateFlow()
    private val _recheckNote = MutableStateFlow<String?>(null)
    val recheckNote: StateFlow<String?> = _recheckNote.asStateFlow()

    private val skipped = mutableSetOf<Long>()
    private var searchJob: Job? = null

    init {
        // Once: lines older transcripts left without a speaker get the nearest one's.
        val prefs = app.getSharedPreferences("boswell", android.content.Context.MODE_PRIVATE)
        // Once per change to how voices are matched: look at past recordings again.
        val wave = "voices_rechecked_v2"     // v2: unnamed voices count as the field to be clear of
        if (!prefs.getBoolean(wave, false)) viewModelScope.launch(Dispatchers.IO) {
            runCatching { net.boswell.phone.speakers.VoiceReview(app).recheck() }
                .onSuccess { if (it.matched > 0) net.boswell.phone.capture.CaptureRepository.log("re-check: ${it.matched} more voices recognized") }
            prefs.edit().putBoolean(wave, true).putBoolean("orphans_attributed_v1", true).apply()
            refresh(force = true)
        }
        refresh()
        // New transcripts and new clips both change what the day shows.
        viewModelScope.launch { ProcessingRepository.state.distinctUntilChangedBy { it.done to it.running }.collect { refresh() } }
        viewModelScope.launch { CaptureRepository.state.distinctUntilChangedBy { it.clipsWritten }.collect { refresh() } }
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { archive.sync(speakers, force) }
            loadDay(_day.value.day)
            loadPeople()
            _conv.value.conversation?.let { openConversation(it.id, keepPlayer = true) }
        }
    }

    // ------------------------------------------------------------------ voices

    private fun voice(key: String, people: Map<Long, Person>): Voice {
        if (key.startsWith("p")) {
            val id = key.drop(1).toLongOrNull()
            val p = id?.let { people[it] }
            return when {
                p?.name != null -> Voice(key, p.name, true, id, p.kind == "media")
                p?.kind == "media" -> Voice(key, "TV / media", false, id, true)
                else -> Voice(key, "Unknown voice ${id ?: ""}".trim(), false, id, false)
            }
        }
        return Voice(key, "Voice ${key.drop(1)}", false, null, false)
    }

    private fun voices(keys: Collection<String>): Map<String, Voice> {
        val people = speakers.people().associateBy { it.id }
        return keys.distinct().associateWith { voice(it, people) }
    }

    // --------------------------------------------------------------------- day

    fun showDay(d: LocalDate) = viewModelScope.launch { loadDay(d) }

    fun shiftDay(by: Long) = showDay(_day.value.day.plusDays(by).coerceAtMost(LocalDate.now()))

    private suspend fun loadDay(d: LocalDate) {
        val (convs, ribbon, days) = withContext(Dispatchers.IO) { Triple(archive.conversations(d), archive.clips(d), archive.days().map { it.first }) }
        val vs = withContext(Dispatchers.IO) { voices(convs.flatMap { it.speakers }) }
        val todos = withContext(Dispatchers.IO) { dueOn(d) }
        _day.value = DayState(d, days, convs, ribbon, vs, loading = false, todos = todos)
    }

    private fun dueOn(d: LocalDate): List<net.boswell.phone.todo.Todo> {
        val zone = java.time.ZoneId.systemDefault()
        val from = d.atStartOfDay(zone).toEpochSecond().toDouble()
        val to = d.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble()
        val store = net.boswell.phone.todo.TodoStore(getApplication())
        return try {
            store.all().filter { t -> val due = t.due ?: return@filter false
                (due in from..<to) || (d == LocalDate.now() && !t.done && due < from) }
        } finally { store.close() }
    }

    fun toggleTodo(t: net.boswell.phone.todo.Todo) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val store = net.boswell.phone.todo.TodoStore(getApplication())
            try { store.setDone(t.id, !t.done) } finally { store.close() }
            if (!t.done) net.boswell.phone.todo.TodoReminders.cancel(getApplication(), t.id)
        }
        loadDay(_day.value.day)
    }

    // ------------------------------------------------------------ conversation

    private var player: ExoPlayer? = null
    private var playerClips: List<ClipRow> = emptyList()
    private var ticker: Job? = null

    fun openConversation(id: Long, keepPlayer: Boolean = false) = viewModelScope.launch {
        val c = withContext(Dispatchers.IO) { archive.conversation(id) } ?: return@launch
        val lines = withContext(Dispatchers.IO) { archive.lines(id) }
        val keys = lines.mapNotNull { it.speaker }.distinct()
        val vs = withContext(Dispatchers.IO) { voices(keys) }
        val guesses = withContext(Dispatchers.IO) {
            val people = speakers.people().associateBy { it.id }
            keys.filter { vs[it]?.named != true }.mapNotNull { k ->
                archive.guess(id, k)?.let { (pid, score) ->
                    val p = people[speakers.resolve(pid)] ?: return@let null
                    if (p.name != null && score >= 0.55) k to (p to score) else null
                }
            }.toMap()
        }
        val sameConversation = _conv.value.conversation?.id == id
        _conv.update { it.copy(conversation = c, lines = lines, voices = vs, guesses = guesses) }
        if (!(keepPlayer && sameConversation)) preparePlayer(id)
    }

    private suspend fun preparePlayer(conversation: Long) {
        releasePlayer()
        val clips = withContext(Dispatchers.IO) { archive.clipsOf(conversation) }.filter { it.audio }
        playerClips = clips
        val dir = CaptureService.clipsDir(getApplication())
        val p = ExoPlayer.Builder(getApplication()).build()
        p.setMediaItems(clips.map { MediaItem.fromUri((net.boswell.phone.audio.ClipAudio.file(dir, it.name) ?: File(dir, it.name)).toURI().toString()) })
        p.prepare()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = _conv.update { it.copy(playing = isPlaying) }
        })
        player = p
        _conv.update { it.copy(length = clips.sumOf { c -> c.ended - c.started }, position = 0.0, playingLine = null) }
        ticker = viewModelScope.launch {
            while (true) {
                delay(200)
                val pl = player ?: break
                val idx = pl.currentMediaItemIndex
                val before = playerClips.take(idx).sumOf { it.ended - it.started }
                val clip = playerClips.getOrNull(idx) ?: continue
                val abs = clip.started + pl.currentPosition / 1000.0
                val line = _conv.value.lines.lastOrNull { it.clip == clip.name && it.t0 <= abs + 0.05 }
                _conv.update { it.copy(position = before + pl.currentPosition / 1000.0, playingLine = if (pl.isPlaying) line?.id else it.playingLine) }
                if (pl.isPlaying && _conv.value.skipPauses) skipPause(pl, idx, clip, pl.currentPosition / 1000.0)
            }
        }
    }

    fun setSkipPauses(on: Boolean) = _conv.update { it.copy(skipPauses = on) }

    /**
     * In a stretch with nobody speaking (the transcript's lines are the
     * speech, so typing or a TV with no words counts as a pause too) longer
     * than PAUSE_SKIP_S, jump to just before the next line -- in this clip or
     * the next. Nothing is cut from the recording; this only moves the player.
     */
    private fun skipPause(pl: ExoPlayer, idx: Int, clip: net.boswell.phone.archive.ClipRow, at: Double) {
        val lines = _conv.value.lines
        val here = lines.filter { it.clip == clip.name }.map { it.offset to it.offset + (it.t1 - it.t0) }
        if (here.any { (a, b) -> at >= a - 0.3 && at <= b + 0.3 }) return
        val prevEnd = here.filter { it.second <= at }.maxOfOrNull { it.second } ?: 0.0
        val next = here.filter { it.first > at }.minOfOrNull { it.first }
        if (next != null) {
            if (next - prevEnd >= PAUSE_SKIP_S && next - at > 1.0) pl.seekTo(idx, ((next - 0.5) * 1000).toLong())
            return
        }
        // Nothing more said in this clip: on to the next clip's first words.
        val len = clip.ended - clip.started
        if (len - prevEnd < PAUSE_SKIP_S || idx + 1 >= playerClips.size) return
        val nc = playerClips[idx + 1]
        val first = lines.filter { it.clip == nc.name }.minOfOrNull { it.offset } ?: 0.0
        pl.seekTo(idx + 1, ((first - 0.5).coerceAtLeast(0.0) * 1000).toLong())
    }

    fun playLine(line: LineRow) {
        val p = player ?: return
        val idx = playerClips.indexOfFirst { it.name == line.clip }
        if (idx < 0) return
        p.seekTo(idx, (line.offset * 1000).toLong().coerceAtLeast(0))
        p.play()
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekTo(seconds: Double) {
        val p = player ?: return
        var left = seconds
        for ((i, c) in playerClips.withIndex()) {
            val len = c.ended - c.started
            if (left <= len || i == playerClips.lastIndex) { p.seekTo(i, (left * 1000).toLong()); return }
            left -= len
        }
    }

    fun closeConversation() {
        releasePlayer()
        _conv.value = ConversationState()
    }

    private fun releasePlayer() {
        ticker?.cancel()
        player?.release()
        player = null
    }

    // ------------------------------------------------------------------ naming

    /** Everyone with a name, for the "who is this?" sheet. */
    fun namedPeople(): List<Person> = _people.value.named

    /**
     * Name a voice in a conversation. An unnamed cluster is named (every
     * sighting at once, merging into an existing person of that name); a voice
     * too short to have been clustered is enrolled as a confirmed voiceprint.
     */
    fun nameVoice(conversation: Long, key: String, name: String) = actVoices {
        val v = _conv.value.voices[key]
        val pid = v?.personId
        if (pid != null && v.named.not()) speakers.name(pid, name)
        else {
            val (emb, secs, clip) = archive.voiceOf(conversation, key) ?: return@actVoices
            val target = speakers.people().firstOrNull { it.name == name }?.id ?: speakers.newPerson(name)
            speakers.addVoiceprint(target, emb, secs, clip, labelOf(conversation, key, clip), "confirmed")
        }
    }

    /** "Yes, that's them": the guess becomes a confirmed reference covering this condition. */
    fun confirmGuess(conversation: Long, key: String, person: Person) = actVoices {
        val v = _conv.value.voices[key]
        if (v?.personId != null && !v.named) speakers.name(v.personId, person.name ?: return@actVoices)
        else {
            val (emb, secs, clip) = archive.voiceOf(conversation, key) ?: return@actVoices
            speakers.addVoiceprint(person.id, emb, secs, clip, labelOf(conversation, key, clip), "confirmed")
        }
    }

    /** A screen talking. Kept and still collected, never given a person's name. */
    fun markMedia(conversation: Long, key: String) = act {
        val v = _conv.value.voices[key]
        val pid = v?.personId ?: run {
            val (emb, secs, clip) = archive.voiceOf(conversation, key) ?: return@act
            speakers.newPerson(null).also { speakers.addVoiceprint(it, emb, secs, clip, labelOf(conversation, key, clip), "auto") }
        }
        speakers.setKind(pid, "media")
    }

    private fun labelOf(conversation: Long, key: String, clip: String): String? =
        archive.readableDatabase.rawQuery("SELECT label FROM clip_speakers WHERE clip = ? AND conv_key = ? LIMIT 1", arrayOf(clip, key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun namePerson(id: Long, name: String) = actVoices { speakers.name(id, name) }
    fun setKind(id: Long, kind: String?) = act { speakers.setKind(id, kind) }
    fun unnameGroup(personId: Long, group: Long) = actVoices { speakers.unnameGroup(personId, group) }

    /** An identity action, then another look at every voice with what is now known. */
    private fun actVoices(block: () -> Unit) = act {
        block()
        val r = net.boswell.phone.speakers.VoiceReview(getApplication()).recheck()
        if (r.matched > 0 || r.merged > 0) _recheckNote.value = recheckText(r)
        if (_review.value != null) loadReviewNow()
    }

    private fun recheckText(r: net.boswell.phone.speakers.VoiceReview.Recheck): String = listOfNotNull(
        if (r.matched > 0) "${r.matched} more voice${if (r.matched == 1) "" else "s"} recognized" else null,
        if (r.merged > 0) "${r.merged} unnamed voice${if (r.merged == 1) "" else "s"} combined" else null,
    ).joinToString(" · ").ifEmpty { "No changes: nothing new to recognize yet" }

    fun recheckVoices() = act {
        _recheckNote.value = recheckText(net.boswell.phone.speakers.VoiceReview(getApplication()).recheck())
        if (_review.value != null) loadReviewNow()
    }

    fun clearRecheckNote() { _recheckNote.value = null }

    fun loadReview() = viewModelScope.launch(Dispatchers.IO) { loadReviewNow() }

    private fun loadReviewNow() {
        _review.value = net.boswell.phone.speakers.VoiceReview(getApplication()).suggestions().map {
            ReviewItem(it, archive.linesOf(it.clip, it.label).joinToString(" "))
        }
    }

    /** A screen talking: kept and still collected, never given a person's name, and no longer asked about. */
    fun reviewIsMedia(item: ReviewItem) {
        _review.value = _review.value?.filter { it.s.key != item.s.key }
        act { item.s.clusterId?.let { speakers.setKind(it, "media") } }
    }

    fun answerReview(item: ReviewItem, yes: Boolean) {
        _review.value = _review.value?.filter { it.s.key != item.s.key }
        actVoices {
            val vr = net.boswell.phone.speakers.VoiceReview(getApplication())
            if (yes) vr.confirm(item.s) else vr.reject(item.s)
        }
    }
    fun skip(id: Long) { skipped += id; viewModelScope.launch { loadPeople() } }

    private fun act(block: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            block()
            archive.sync(speakers, force = true)
        }
        loadDay(_day.value.day)
        loadPeople()
        _conv.value.conversation?.let { openConversation(it.id, keepPlayer = true) }
        _person.value.person?.let { openPerson(it.id) }
    }

    // ------------------------------------------------------------------ people

    private suspend fun loadPeople() {
        val all = withContext(Dispatchers.IO) { speakers.people() }
        _people.value = PeopleState(
            // The queue: recurring voices nobody has named, the most heard first.
            queue = all.filter { it.name == null && it.kind == null && it.id !in skipped && it.seconds >= 5 }.sortedByDescending { it.seconds },
            named = all.filter { it.name != null }.sortedByDescending { it.lastHeard ?: 0.0 },
            media = all.filter { it.kind == "media" },
        )
    }

    fun openPerson(id: Long) = viewModelScope.launch {
        val p = withContext(Dispatchers.IO) { speakers.person(id) }
        val convs = withContext(Dispatchers.IO) { archive.conversationsWith("p$id") }
        val groups = withContext(Dispatchers.IO) { speakers.groups(id) }
        _person.value = PersonState(p, convs, groups)
    }

    /** A conversation where this person talked, to play a sample of them. */
    fun sampleConversation(personId: Long): Long? = archive.conversationsWith("p$personId").firstOrNull()?.id

    // ------------------------------------------------------------------ search

    fun search(q: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(200)
            val hits = withContext(Dispatchers.IO) { runCatching { archive.search(q) }.getOrDefault(emptyList()) }
            _searchVoices.value = withContext(Dispatchers.IO) { voices(hits.mapNotNull { it.line.speaker }) }
            _search.value = hits
        }
    }

    fun conversationStartOf(id: Long): Double? = archive.conversation(id)?.started

    // ----------------------------------------------------------- clip actions

    /** Transcript of conversations as plain text: "Name (3:14 PM): words". */
    private fun transcriptText(ids: List<Long>): String {
        val people = speakers.people().associateBy { it.id }
        return ids.sortedBy { it }.joinToString("\n\n") { id ->
            val c = archive.conversation(id)
            val header = c?.let { "${Fmt.shortDay(archive.dayOf(it.started))} ${Fmt.time(it.started)} – ${Fmt.time(it.ended)}" } ?: ""
            header + "\n" + archive.lines(id).joinToString("\n") { l -> "${l.speaker?.let { voice(it, people).name } ?: "?"} (${Fmt.time(l.t0)}): ${l.text}" }
        }
    }

    fun shareConversations(ids: List<Long>, launch: (android.content.Intent) -> Unit) = viewModelScope.launch {
        val intent = withContext(Dispatchers.IO) {
            val clips = ids.flatMap { archive.clipsOf(it) }.sortedBy { it.started }.map { it.name }
            net.boswell.phone.process.ClipActions.shareIntent(getApplication(), clips,
                if (ids.size == 1) "Conversation ${archive.conversation(ids[0])?.let { Fmt.time(it.started) } ?: ""}" else "${ids.size} conversations",
                transcriptText(ids))
        }
        launch(intent)
    }

    fun deleteConversations(ids: List<Long>) = act2 {
        net.boswell.phone.process.ClipActions.delete(getApplication(), ids.flatMap { archive.clipsOf(it).map { c -> c.name } })
    }

    fun deleteClips(names: List<String>) = act2 { net.boswell.phone.process.ClipActions.delete(getApplication(), names) }

    fun retranscribe(names: List<String>) = act2 { net.boswell.phone.process.ClipActions.retranscribe(getApplication(), names) }

    /** Transcribe these again in the cloud (Parakeet), whatever the transcription setting. */
    fun retranscribeCloud(names: List<String>) = act2 {
        net.boswell.phone.asr.Transcription.requestCloud(getApplication(), names)
        net.boswell.phone.process.ClipActions.retranscribe(getApplication(), names)
    }

    fun shareClips(names: List<String>, launch: (android.content.Intent) -> Unit) = viewModelScope.launch {
        val intent = withContext(Dispatchers.IO) {
            val rows = names.mapNotNull { n -> archive.readableDatabase.rawQuery("SELECT started FROM clips WHERE name = ?", arrayOf(n)).use { c ->
                if (c.moveToFirst()) n to c.getDouble(0) else null } }.sortedBy { it.second }
            val text = rows.joinToString("\n") { (n, t) ->
                val lines = archive.readableDatabase.rawQuery("SELECT text FROM lines WHERE clip = ? ORDER BY t0", arrayOf(n)).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) } }
                "${Fmt.time(t)}: ${lines.joinToString(" ").ifEmpty { "(no speech)" }}"
            }
            net.boswell.phone.process.ClipActions.shareIntent(getApplication(), rows.map { it.first }, "${rows.size} recordings", text)
        }
        launch(intent)
    }

    /** Ask the assistant for a summary; it lands in the Ask feed and as a notification. */
    fun summarize(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val c = archive.conversation(id) ?: return@launch
        val a = net.boswell.phone.assistant.Assistant(getApplication()).ask(
            "Summarize conversation $id (${Fmt.time(c.started)}–${Fmt.time(c.ended)}): who talked, what about, and any decisions or things to follow up. Use read_conversation.", "typed")
        net.boswell.phone.assistant.AssistantNotify.post(getApplication(), net.boswell.phone.assistant.AssistantNotify.ANSWERS, "Summary · ${Fmt.time(c.started)}", a.text)
    }

    /** Meeting notes for a conversation -- summary, decisions, action items -- shared as text. */
    fun meetingNotes(id: Long, launch: (android.content.Intent) -> Unit) = viewModelScope.launch {
        val c = archive.conversation(id) ?: return@launch
        val a = withContext(Dispatchers.IO) {
            net.boswell.phone.assistant.Assistant(getApplication()).ask(
                "Write meeting notes for conversation $id (${Fmt.time(c.started)}-${Fmt.time(c.ended)}) using read_conversation: " +
                    "a 2-3 sentence summary, then 'Decisions:' and 'Action items:' as short bullet lists (who does what, by when if said). " +
                    "Plain text for sharing; no moment labels.", "notes", display = "Meeting notes · ${Fmt.time(c.started)}")
        }
        if (a.error) return@launch
        launch(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Notes · ${Fmt.time(c.started)}")
            .putExtra(android.content.Intent.EXTRA_TEXT, net.boswell.phone.assistant.Moments.strip(a.text)), "Share notes"))
    }

    /** Save hand corrections for several lines at once, then refresh what shows them. */
    fun speakersInClip(clip: String): List<Pair<String, String?>> = archive.speakersInClip(clip)

    /** "Who said this?" for a line nobody was attributed to. */
    fun assignLine(line: LineRow, label: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { net.boswell.phone.process.ClipActions.setLineSpeaker(getApplication(), line.clip, line.offset, label) }
        _conv.value.conversation?.let { openConversation(it.id, keepPlayer = true) }
        loadDay(_day.value.day)
    }

    fun editLines(edits: List<Pair<LineRow, String>>) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            for ((line, text) in edits) net.boswell.phone.process.ClipActions.editLine(getApplication(), line.clip, line.offset, text)
        }
        _conv.value.conversation?.let { openConversation(it.id, keepPlayer = true) }
        loadDay(_day.value.day)
    }

    fun lineToTodo(line: LineRow) = viewModelScope.launch(Dispatchers.IO) {
        val t = net.boswell.phone.todo.TodoStore(getApplication())
        try { t.add(line.text, null, null, "typed") } finally { t.close() }
    }

    fun askAbout(line: LineRow) = viewModelScope.launch(Dispatchers.IO) {
        val a = net.boswell.phone.assistant.Assistant(getApplication()).ask(
            "About this line, said at ${Fmt.time(line.t0)}: \"${line.text}\" — explain or add useful context, briefly. Use recent_lines or read_conversation if the surrounding conversation helps.", "typed")
        net.boswell.phone.assistant.AssistantNotify.post(getApplication(), net.boswell.phone.assistant.AssistantNotify.ANSWERS, line.text.take(60), a.text)
    }

    // ------------------------------------------------------------- recordings

    data class Recording(val clip: ClipRow, val text: String, val sounds: List<String>)

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    fun loadRecordings(d: LocalDate) = viewModelScope.launch {
        _recordings.value = withContext(Dispatchers.IO) {
            archive.clips(d).reversed().map { c ->
                val text = archive.readableDatabase.rawQuery("SELECT text FROM lines WHERE clip = ? ORDER BY t0", arrayOf(c.name)).use { cur ->
                    buildList { while (cur.moveToNext()) add(cur.getString(0)) } }.joinToString(" ")
                val sounds = archive.readableDatabase.rawQuery("SELECT label FROM sounds WHERE clip = ? ORDER BY score DESC LIMIT 3", arrayOf(c.name)).use { cur ->
                    buildList { while (cur.moveToNext()) add(net.boswell.phone.sound.Sounds.display(cur.getString(0))) } }.distinct()
                Recording(c, text, sounds)
            }
        }
    }

    private var clipPlayer: android.media.MediaPlayer? = null
    private val _playingClip = MutableStateFlow<String?>(null)
    val playingClip: StateFlow<String?> = _playingClip.asStateFlow()

    private var voicePlayer: ExoPlayer? = null

    /**
     * Play only what one voice said in a clip, its parts back to back, so the
     * question "is this you?" is answered by the voice itself rather than by
     * whoever else talks first. Falls back to the whole clip without lines.
     */
    fun toggleVoice(clip: String, label: String) {
        val key = "$clip|$label"
        val wasPlaying = _playingClip.value == key
        clipPlayer?.release(); clipPlayer = null
        voicePlayer?.release(); voicePlayer = null
        _playingClip.value = null
        if (wasPlaying) return
        val f = net.boswell.phone.audio.ClipAudio.file(CaptureService.clipsDir(getApplication()), clip) ?: return
        val spans = archive.spansOf(clip, label)
        android.util.Log.i("Boswell", "playing $label of $clip: " + spans.joinToString { "%.1f-%.1f s".format(it.first, it.second) })
        if (spans.isEmpty()) { toggleClip(clip); return }
        val uri = f.toURI().toString()
        voicePlayer = ExoPlayer.Builder(getApplication()).build().apply {
            setMediaItems(spans.map { (a, b) ->
                MediaItem.Builder().setUri(uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(((a - PAD_S).coerceAtLeast(0.0) * 1000).toLong())
                    .setEndPositionMs(((b + PAD_S) * 1000).toLong()).build()).build()
            })
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) android.util.Log.i("Boswell", "finished $label of $clip")
                    if (state == Player.STATE_ENDED && _playingClip.value == key) _playingClip.value = null
                }
            })
            prepare(); play()
        }
        _playingClip.value = key
    }

    fun toggleClip(name: String) {
        voicePlayer?.release(); voicePlayer = null
        clipPlayer?.release(); clipPlayer = null
        if (_playingClip.value == name) { _playingClip.value = null; return }
        val f = net.boswell.phone.audio.ClipAudio.file(CaptureService.clipsDir(getApplication()), name) ?: return
        clipPlayer = android.media.MediaPlayer().apply {
            setDataSource(f.path); setOnCompletionListener { _playingClip.value = null }; prepare(); start()
        }
        _playingClip.value = name
    }

    private fun act2(block: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { block() }
        loadDay(_day.value.day)
        loadPeople()
        loadRecordings(_day.value.day)
    }

    override fun onCleared() {
        // The databases stay open: work started on the IO dispatcher can still be
        // reading after the screen is gone, and closing them under it crashed the
        // app (and Live capture with it). An open SQLite helper costs nothing.
        releasePlayer()
        clipPlayer?.release()
        voicePlayer?.release()
    }
}
