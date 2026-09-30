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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.capture.ClipTimes
import net.boswell.phone.omi.OmiUuids
import java.io.File

data class FoundDevice(val address: String, val name: String?, val advertisedRssi: Int)

data class ClipEntry(val file: File, val seconds: Double, val endedMillis: Long, val timeKnown: Boolean)

data class UiState(
    val savedAddress: String? = null,
    val scanning: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    val clips: List<ClipEntry> = emptyList(),
    val playing: File? = null,
)

@SuppressLint("MissingPermission")   // the activity requests permissions before any of this runs
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("boswell", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(UiState(savedAddress = prefs.getString(KEY_ADDRESS, null)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val capture = CaptureRepository.state

    private var scanJob: Job? = null
    private var player: MediaPlayer? = null
    private val json = Json { ignoreUnknownKeys = true }

    init {
        refreshClips()
        viewModelScope.launch {
            capture.distinctUntilChangedBy { it.clipsWritten }.collect { refreshClips() }
        }
    }

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
        CaptureService.start(getApplication(), address)
    }

    fun disconnect() = CaptureService.stop(getApplication())

    fun refreshRing() = CaptureService.refreshRing(getApplication())

    fun forget() {
        disconnect()
        prefs.edit().remove(KEY_ADDRESS).apply()
        _ui.update { it.copy(savedAddress = null) }
    }

    fun refreshClips() {
        val dir = CaptureService.clipsDir(getApplication())
        val clips = dir.listFiles { f -> f.extension == "wav" }.orEmpty().map { wav ->
            val times = runCatching {
                json.decodeFromString(ClipTimes.serializer(), File(dir, wav.nameWithoutExtension + ".json").readText())
            }.getOrNull()
            ClipEntry(
                file = wav,
                seconds = times?.seconds ?: ((wav.length() - 44) / 32_000.0),
                endedMillis = ((times?.ended ?: (wav.lastModified() / 1000.0)) * 1000).toLong(),
                timeKnown = times?.timeKnown ?: false,
            )
        }.sortedByDescending { it.endedMillis }
        _ui.update { it.copy(clips = clips) }
    }

    fun togglePlay(file: File) {
        val wasPlaying = _ui.value.playing
        player?.release()
        player = null
        _ui.update { it.copy(playing = null) }
        if (wasPlaying == file) return
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { _ui.update { s -> s.copy(playing = null) } }
            prepare()
            start()
        }
        _ui.update { it.copy(playing = file) }
    }

    override fun onCleared() {
        stopScan()
        player?.release()
    }

    companion object {
        private const val KEY_ADDRESS = "omi_address"
    }
}
