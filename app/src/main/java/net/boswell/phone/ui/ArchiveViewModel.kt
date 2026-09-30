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
)

data class PeopleState(val queue: List<Person> = emptyList(), val named: List<Person> = emptyList(), val media: List<Person> = emptyList())

data class PersonState(val person: Person? = null, val conversations: List<Conversation> = emptyList(), val groups: List<VoiceGroup> = emptyList())

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

    private val skipped = mutableSetOf<Long>()
    private var searchJob: Job? = null

    init {
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
        p.setMediaItems(clips.map { MediaItem.fromUri(File(dir, it.name).toURI().toString()) })
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
            }
        }
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
    fun nameVoice(conversation: Long, key: String, name: String) = act {
        val v = _conv.value.voices[key]
        val pid = v?.personId
        if (pid != null && v.named.not()) speakers.name(pid, name)
        else {
            val (emb, secs, clip) = archive.voiceOf(conversation, key) ?: return@act
            val target = speakers.people().firstOrNull { it.name == name }?.id ?: speakers.newPerson(name)
            speakers.addVoiceprint(target, emb, secs, clip, labelOf(conversation, key, clip), "confirmed")
        }
    }

    /** "Yes, that's them": the guess becomes a confirmed reference covering this condition. */
    fun confirmGuess(conversation: Long, key: String, person: Person) = act {
        val v = _conv.value.voices[key]
        if (v?.personId != null && !v.named) speakers.name(v.personId, person.name ?: return@act)
        else {
            val (emb, secs, clip) = archive.voiceOf(conversation, key) ?: return@act
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

    fun namePerson(id: Long, name: String) = act { speakers.name(id, name) }
    fun setKind(id: Long, kind: String?) = act { speakers.setKind(id, kind) }
    fun unnameGroup(personId: Long, group: Long) = act { speakers.unnameGroup(personId, group) }
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

    override fun onCleared() {
        releasePlayer()
        speakers.close()
        archive.close()
    }
}
