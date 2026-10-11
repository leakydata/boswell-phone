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
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.capture.logged
import net.boswell.phone.models.ModelCatalog
import net.boswell.phone.models.ModelDownloadWorker
import net.boswell.phone.models.ModelProgressRepository
import net.boswell.phone.models.ModelSpec
import net.boswell.phone.models.ModelStore
import net.boswell.phone.omi.OmiUuids
import net.boswell.phone.process.ProcessingRepository
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.speakers.SpeakerStore
import java.io.File

data class FoundDevice(val address: String, val name: String?, val advertisedRssi: Int)

data class ModelRow(val spec: ModelSpec, val installed: Boolean, val partialBytes: Long)
data class PersonRow(val id: Long, val name: String?, val voiceprints: Int, val seconds: Double)

data class UiState(
    val savedAddress: String? = null,
    val scanning: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    val playing: File? = null,
    val models: List<ModelRow> = emptyList(),
    val modelsReady: Boolean = false,
    val wifiOnly: Boolean = true,
    val people: List<PersonRow> = emptyList(),
    val usage: net.boswell.phone.archive.Archive.Usage? = null,
    val autoClean: Boolean = false,
    /** Quiet clips lose their audio after this many days: 0 right away, -1 never. */
    val quietDays: Int = 0,
    val batteryExempt: Boolean = true,
    val mode: net.boswell.phone.sync.Mode = net.boswell.phone.sync.Mode.OFF,
    val syncMinutes: Int = 60,
    val lastSync: Long = 0,
    val lastSyncResult: String? = null,
    val companionPaired: Boolean = false,
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

    init {
        // Opening the app brings Live capture back if it should be running and isn't.
        run {
            val ctx = getApplication<Application>()
            val address = net.boswell.phone.sync.Modes.address(ctx)
            if (net.boswell.phone.sync.Modes.mode(ctx) == net.boswell.phone.sync.Mode.LIVE && address != null &&
                CaptureRepository.state.value.link == net.boswell.phone.capture.Link.IDLE) {
                runCatching { CaptureService.start(ctx, address) }.logged("starting Live capture")
            }
        }
        // Older clips become compact copies; quiet ones lose their sound, per the setting.
        net.boswell.phone.process.CleanupWorker.runNow(getApplication())
        net.boswell.phone.process.CleanupWorker.schedule(getApplication(), true)
        net.boswell.phone.assistant.RoutinesWorker.schedule(getApplication())
        net.boswell.phone.backup.AutoBackup.schedule(getApplication())
        net.boswell.phone.backup.HomeBackup.schedule(getApplication())
        net.boswell.phone.speakers.VoiceMigration.schedule(getApplication())
        net.boswell.phone.speakers.SnrBackfill.schedule(getApplication())
        net.boswell.phone.speakers.MediaCheck.schedule(getApplication())
        net.boswell.phone.process.CatchUp.schedule(getApplication())
        refreshAll()
        viewModelScope.launch { ProcessingRepository.state.distinctUntilChangedBy { it.done to it.running }.collect { refreshPeople() } }
        viewModelScope.launch { ModelProgressRepository.state.collect { refreshModels() } }
        // Anything recorded while processing could not run gets its turn now.
        if (_ui.value.modelsReady) ProcessingWorker.enqueue(app)
    }

    fun refreshAll() {
        refreshSync()
        refreshModels()
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

    /** Remember this Omi without starting anything (setup picks the mode next). */
    fun chooseOnly(address: String) {
        stopScan()
        prefs.edit().putString(KEY_ADDRESS, address).apply()
        _ui.update { it.copy(savedAddress = address, found = emptyList()) }
    }

    fun choose(address: String) {
        stopScan()
        prefs.edit().putString(KEY_ADDRESS, address).apply()
        _ui.update { it.copy(savedAddress = address, found = emptyList()) }
        if (_ui.value.mode == net.boswell.phone.sync.Mode.OFF) setMode(net.boswell.phone.sync.Mode.LIVE) else setMode(_ui.value.mode)
    }

    /**
     * Off, Sync or Live. Switching tears down whatever the old mode was doing
     * before starting the new one: the radio is exclusive.
     */
    fun setMode(m: net.boswell.phone.sync.Mode) {
        val ctx = getApplication<Application>()
        val address = _ui.value.savedAddress
        net.boswell.phone.sync.Modes.setMode(ctx, m)
        when (m) {
            net.boswell.phone.sync.Mode.OFF -> {
                net.boswell.phone.sync.SyncWorker.cancel(ctx)
                CaptureService.stop(ctx)
            }
            net.boswell.phone.sync.Mode.LIVE -> {
                net.boswell.phone.sync.SyncWorker.cancel(ctx)
                address?.let { CaptureService.start(ctx, it) }
            }
            net.boswell.phone.sync.Mode.SYNC -> {
                CaptureService.stop(ctx)
                net.boswell.phone.sync.SyncWorker.schedule(ctx)
                address?.let { a -> viewModelScope.launch { delay(1_500); CaptureService.sync(ctx, a) } }
            }
        }
        refreshSync()
    }

    fun connect() = setMode(net.boswell.phone.sync.Mode.LIVE)

    fun disconnect() = setMode(net.boswell.phone.sync.Mode.OFF)

    fun syncNow() {
        val a = _ui.value.savedAddress ?: return
        CaptureService.sync(getApplication(), a)
    }

    fun setSyncMinutes(m: Int) {
        net.boswell.phone.sync.Modes.setSyncMinutes(getApplication(), m)
        if (_ui.value.mode == net.boswell.phone.sync.Mode.SYNC) net.boswell.phone.sync.SyncWorker.schedule(getApplication())
        refreshSync()
    }

    fun refreshSync(activity: android.app.Activity? = null) {
        val ctx = getApplication<Application>()
        _ui.update {
            it.copy(
                mode = net.boswell.phone.sync.Modes.mode(ctx),
                syncMinutes = net.boswell.phone.sync.Modes.syncMinutes(ctx),
                lastSync = net.boswell.phone.sync.Modes.lastSync(ctx),
                lastSyncResult = net.boswell.phone.sync.Modes.lastSyncResult(ctx),
                companionPaired = activity?.let { a -> net.boswell.phone.sync.OmiCompanion.isPaired(a) } ?: it.companionPaired,
            )
        }
    }

    // --- Storage and reliability ---------------------------------------------

    private val archive = net.boswell.phone.archive.Archive(app)

    fun refreshStorage() {
        viewModelScope.launch {
            val u = withContext(Dispatchers.IO) {
                runCatching { archive.sync(speakers) }.logged("archive sync")
                archive.usage(net.boswell.phone.process.CleanupWorker.days(getApplication()).coerceAtLeast(0))
            }
            val pm = getApplication<Application>().getSystemService(android.os.PowerManager::class.java)
            _ui.update { it.copy(usage = u, autoClean = net.boswell.phone.process.CleanupWorker.days(getApplication()) >= 0, quietDays = net.boswell.phone.process.CleanupWorker.days(getApplication()),
                batteryExempt = pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)) }
        }
    }

    fun cleanNow() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                archive.deleteAudio(archive.quietCandidates(net.boswell.phone.process.CleanupWorker.days(getApplication()).coerceAtLeast(0)).map { it.name })
            }
            refreshStorage()
        }
    }

    fun setQuietDays(d: Int) {
        net.boswell.phone.process.CleanupWorker.setDays(getApplication(), d)
        _ui.update { it.copy(quietDays = d, autoClean = d >= 0) }
        refreshStorage()
    }

    fun setAutoClean(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CLEAN, on).apply()
        net.boswell.phone.process.CleanupWorker.schedule(getApplication(), on)
        _ui.update { it.copy(autoClean = on) }
    }

    fun batteryExemptionIntent(): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:" + getApplication<Application>().packageName))


    fun forget() {
        disconnect()
        prefs.edit().remove(KEY_ADDRESS).apply()
        _ui.update { it.copy(savedAddress = null) }
    }

    // --- Clips and transcripts ---------------------------------------------
    // The list of every clip with its transcript that used to be kept here was
    // shown by no screen, and was read again in full -- every sidecar and every
    // transcript, 21,000 of each -- whenever a clip was written or processing
    // moved on, each time in a new coroutine: during a download, many full
    // reads at once (the OutOfMemoryErrors of 2026-10-09). The archive's index
    // (ArchiveViewModel) is what the screens show.

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
        }
    }

    // --- Models ------------------------------------------------------------

    fun refreshModels() {
        // The speaker-ID model is opt-in (Device -> Better voice recognition): listed, and downloaded, only once chosen.
        val optIn = net.boswell.phone.diarize.VoiceModels.wanted(getApplication()) == net.boswell.phone.diarize.VoiceModel.SPEAKER_ID
        val rows = models.catalog.models.filter { it.id != ModelCatalog.SPEAKER_ID || optIn }
            .map { ModelRow(it, models.isInstalled(it.id), models.installedBytes(it.id)) }
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

    /** Better voice recognition on or off: download the model if needed, then convert (VoiceMigration) on the charger. */
    fun setBetterVoices(on: Boolean) {
        val c = getApplication<Application>()
        val VM = net.boswell.phone.diarize.VoiceModels
        VM.setWanted(c, if (on) net.boswell.phone.diarize.VoiceModel.SPEAKER_ID else net.boswell.phone.diarize.VoiceModel.WESPEAKER)
        if (on && !VM.speakerIdInstalled(models)) download(ModelCatalog.SPEAKER_ID)
        net.boswell.phone.speakers.VoiceMigration.schedule(c)
        refreshModels()
    }

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
        // The databases stay open: work started on the IO dispatcher can still be
        // reading after the screen is gone, and closing them under it crashed the
        // app (and Live capture with it). An open SQLite helper costs nothing.
        stopScan()
        player?.release()
    }

    companion object {
        private const val KEY_ADDRESS = "omi_address"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_AUTO_CLEAN = "auto_clean"
    }
}
