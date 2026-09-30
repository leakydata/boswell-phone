package net.boswell.phone.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.MediaPlayer
import android.os.ParcelUuid
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.serialization.json.Json
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.capture.ClipTimes
import net.boswell.phone.models.ModelCatalog
import net.boswell.phone.models.ModelDownloadWorker
import net.boswell.phone.models.ModelProgressRepository
import net.boswell.phone.models.ModelSpec
import net.boswell.phone.models.ModelStore
import net.boswell.phone.omi.OmiUuids
import net.boswell.phone.process.ProcessingRepository
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson
import net.boswell.phone.speakers.SpeakerStore
import java.io.File

data class FoundDevice(val address: String, val name: String?, val advertisedRssi: Int)

data class Line(val who: String, val named: Boolean, val text: String)
data class TranscriptView(val lines: List<Line>, val error: String? = null)

data class ClipEntry(
    val file: File,
    val seconds: Double,
    val endedMillis: Long,
    val timeKnown: Boolean,
    val transcript: TranscriptView? = null,
)

data class ModelRow(val spec: ModelSpec, val installed: Boolean, val partialBytes: Long)
data class PersonRow(val id: Long, val name: String?, val voiceprints: Int, val seconds: Double)

data class UiState(
    val savedAddress: String? = null,
    val scanning: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    val clips: List<ClipEntry> = emptyList(),
    val playing: File? = null,
    val models: List<ModelRow> = emptyList(),
    val modelsReady: Boolean = false,
    val wifiOnly: Boolean = true,
    val people: List<PersonRow> = emptyList(),
    val usage: net.boswell.phone.archive.Archive.Usage? = null,
    val autoClean: Boolean = false,
    val batteryExempt: Boolean = true,
)

@SuppressLint("MissingPermission")   // the activity requests permissions before any of this runs
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("boswell", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(
        UiState(savedAddress = prefs.getString(KEY_ADDRESS, null), wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true))
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val capture = CaptureRepository.state

    private val models = ModelStore(app)
    private val speakers = SpeakerStore(app)
    private var scanJob: Job? = null
    private var player: MediaPlayer? = null
    private val json = Json { ignoreUnknownKeys = true }

    init {
        refreshAll()
        viewModelScope.launch { capture.distinctUntilChangedBy { it.clipsWritten }.collect { refreshClips() } }
        viewModelScope.launch { ProcessingRepository.state.distinctUntilChangedBy { it.done to it.running }.collect { refreshClips(); refreshPeople() } }
        viewModelScope.launch { ModelProgressRepository.state.collect { refreshModels() } }
        // Anything recorded while processing could not run gets its turn now.
        if (_ui.value.modelsReady) ProcessingWorker.enqueue(app)
    }

    fun refreshAll() {
        refreshModels()
        refreshClips()
        refreshPeople()
    }

    // --- Omi ---------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = FoundDevice(result.device.address, result.device.name ?: result.scanRecord?.deviceName, result.rssi)
            _ui.update { s -> s.copy(found = (s.found.filter { it.address != d.address } + d).sortedByDescending { it.advertisedRssi }) }
        }
    }

    fun scan() {
        val scanner = getApplication<Application>().getSystemService(BluetoothManager::class.java)
            .adapter?.bluetoothLeScanner ?: return CaptureRepository.log("Bluetooth is off")
        scanJob?.cancel()
        _ui.update { it.copy(scanning = true, found = emptyList()) }
        // The Omi advertises its audio service; filtering on it means nothing
        // else nearby shows up, and no location permission is involved.
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(OmiUuids.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        scanJob = viewModelScope.launch {
            delay(12_000)
            stopScan()
        }
    }

    fun stopScan() {
        runCatching {
            getApplication<Application>().getSystemService(BluetoothManager::class.java)
                .adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        _ui.update { it.copy(scanning = false) }
    }

    fun choose(address: String) {
        stopScan()
        prefs.edit().putString(KEY_ADDRESS, address).apply()
        _ui.update { it.copy(savedAddress = address, found = emptyList()) }
        connect()
    }

    fun connect() {
        val address = _ui.value.savedAddress ?: return
        prefs.edit().putBoolean(net.boswell.phone.capture.BootReceiver.KEEP_RECORDING, true).apply()
        CaptureService.start(getApplication(), address)
    }

    fun disconnect() {
        prefs.edit().putBoolean(net.boswell.phone.capture.BootReceiver.KEEP_RECORDING, false).apply()
        CaptureService.stop(getApplication())
    }

    // --- Storage and reliability ---------------------------------------------

    private val archive = net.boswell.phone.archive.Archive(app)

    fun refreshStorage() {
        viewModelScope.launch {
            val u = withContext(Dispatchers.IO) {
                archive.sync(speakers)
                archive.usage(net.boswell.phone.process.CleanupWorker.DAYS)
            }
            val pm = getApplication<Application>().getSystemService(android.os.PowerManager::class.java)
            _ui.update { it.copy(usage = u, autoClean = prefs.getBoolean(KEY_AUTO_CLEAN, false),
                batteryExempt = pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)) }
        }
    }

    fun cleanNow() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                archive.deleteAudio(archive.quietCandidates(net.boswell.phone.process.CleanupWorker.DAYS).map { it.name })
            }
            refreshStorage()
        }
    }

    fun setAutoClean(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CLEAN, on).apply()
        net.boswell.phone.process.CleanupWorker.schedule(getApplication(), on)
        _ui.update { it.copy(autoClean = on) }
    }

    fun batteryExemptionIntent(): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:" + getApplication<Application>().packageName))

    fun refreshRing() = CaptureService.refreshRing(getApplication())

    fun forget() {
        disconnect()
        prefs.edit().remove(KEY_ADDRESS).apply()
        _ui.update { it.copy(savedAddress = null) }
    }

    // --- Clips and transcripts ---------------------------------------------

    fun refreshClips() {
        viewModelScope.launch {
            val clips = withContext(Dispatchers.IO) { loadClips() }
            _ui.update { it.copy(clips = clips) }
        }
    }

    private fun loadClips(): List<ClipEntry> {
        val dir = CaptureService.clipsDir(getApplication())
        val tdir = ProcessingWorker.transcriptsDir(getApplication())
        val names = HashMap<Long, String?>()
        fun nameOf(pid: Long) = names.getOrPut(pid) { speakers.nameOf(pid) }
        return dir.listFiles { f -> f.extension == "wav" }.orEmpty().map { wav ->
            val times = runCatching {
                json.decodeFromString(ClipTimes.serializer(), File(dir, wav.nameWithoutExtension + ".json").readText())
            }.getOrNull()
            ClipEntry(
                file = wav,
                seconds = times?.seconds ?: ((wav.length() - 44) / 32_000.0),
                endedMillis = ((times?.ended ?: (wav.lastModified() / 1000.0)) * 1000).toLong(),
                timeKnown = times?.timeKnown ?: false,
                transcript = File(tdir, wav.nameWithoutExtension + ".json").takeIf { it.exists() }?.let { f ->
                    val text = f.readText()
                    runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), text) }.fold(
                        onSuccess = { t -> TranscriptView(t.segments.map { s ->
                            val id = s.speaker?.let { t.speakers[it] }
                            // The current name, not the one at transcription time:
                            // naming a voice later relabels every clip it is in.
                            val name = id?.personId?.let(::nameOf) ?: id?.name
                            val who = name ?: id?.personId?.let { "Unknown #$it" } ?: s.speaker?.let { "Voice ${it.takeLast(2).toInt() + 1}" } ?: "?"
                            Line(who, name != null, s.text)
                        }) },
                        onFailure = { TranscriptView(emptyList(), error = Regex("\"error\":\"([^\"]*)").find(text)?.groupValues?.get(1) ?: "unreadable") },
                    )
                },
            )
        }.sortedByDescending { it.endedMillis }
    }

    fun transcribeNow() = ProcessingWorker.enqueue(getApplication())

    fun togglePlay(file: File, fromSeconds: Double = 0.0) {
        val wasPlaying = _ui.value.playing
        player?.release()
        player = null
        _ui.update { it.copy(playing = null) }
        if (wasPlaying == file && fromSeconds == 0.0) return
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { _ui.update { s -> s.copy(playing = null) } }
            prepare()
            if (fromSeconds > 0) seekTo((fromSeconds * 1000).toInt())
            start()
        }
        _ui.update { it.copy(playing = file) }
    }

    // --- People ------------------------------------------------------------

    fun refreshPeople() {
        viewModelScope.launch {
            val people = withContext(Dispatchers.IO) { speakers.people() }
            _ui.update { it.copy(people = people.map { p -> PersonRow(p.id, p.name, p.voiceprints, p.seconds) }) }
        }
    }

    fun namePerson(id: Long, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { speakers.name(id, name) }
            refreshPeople()
            refreshClips()
        }
    }

    /** Play the most recent stretch of speech filed under this person, to answer "who is this?". */
    fun playSample(personId: Long) {
        viewModelScope.launch {
            val hit = withContext(Dispatchers.IO) {
                val tdir = ProcessingWorker.transcriptsDir(getApplication())
                val clipsDir = CaptureService.clipsDir(getApplication())
                tdir.listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.name }.firstNotNullOfOrNull { f ->
                    val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull()
                        ?: return@firstNotNullOfOrNull null
                    val label = t.speakers.entries.firstOrNull { it.value.personId == personId }?.key ?: return@firstNotNullOfOrNull null
                    val start = t.segments.firstOrNull { it.speaker == label }?.start ?: 0.0
                    File(clipsDir, t.clip) to start
                }
            } ?: return@launch
            togglePlay(hit.first, hit.second.coerceAtLeast(0.01))
        }
    }

    // --- Models ------------------------------------------------------------

    fun refreshModels() {
        val rows = models.catalog.models.map { ModelRow(it, models.isInstalled(it.id), models.installedBytes(it.id)) }
        val ready = listOf(ModelCatalog.ASR, ModelCatalog.SEGMENTATION, ModelCatalog.VOICEPRINT).all(models::isInstalled)
        val before = _ui.value.models.filter { it.installed }.map { it.spec.id }.toSet()
        val after = rows.filter { it.installed }.map { it.spec.id }.toSet()
        _ui.update { it.copy(models = rows, modelsReady = ready) }
        // Any model finishing its download is new work: transcription once all
        // three are in, sound tags for clips already transcribed, and so on.
        if (ready && (after - before).isNotEmpty() && before.isNotEmpty()) ProcessingWorker.enqueue(getApplication())
        else if (ready && before.isEmpty()) ProcessingWorker.enqueue(getApplication())
    }

    fun download(id: String) = ModelDownloadWorker.enqueue(getApplication(), id, _ui.value.wifiOnly)

    fun downloadAll() = _ui.value.models.filter { !it.installed }.forEach { download(it.spec.id) }

    fun cancelDownload(id: String) = ModelDownloadWorker.cancel(getApplication(), id)

    fun deleteModel(id: String) {
        models.delete(id)
        refreshModels()
    }

    fun setWifiOnly(on: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, on).apply()
        _ui.update { it.copy(wifiOnly = on) }
    }

    override fun onCleared() {
        stopScan()
        player?.release()
        speakers.close()
        archive.close()
    }

    companion object {
        private const val KEY_ADDRESS = "omi_address"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_AUTO_CLEAN = "auto_clean"
    }
}
