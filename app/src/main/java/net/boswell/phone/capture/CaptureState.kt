package net.boswell.phone.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.boswell.phone.omi.RingInfo

enum class Link {
    /** Not trying. */
    IDLE,
    CONNECTING,
    /**
     * Connected and subscribed to audio. Frames arrive only while there is
     * sound: the CV 1's mic sleeps through silence.
     */
    STREAMING,
    /**
     * Not reachable, retrying on a backoff. Away is not broken: a recorder
     * that is switched off or out of range is not recording, so nothing is
     * being lost while it is away.
     */
    AWAY,
    /** Visiting to download what the device stored; lets go when done. */
    SYNCING,
}

data class SyncStatus(val took: Long, val target: Long, val bytesPerSecond: Double, val phase: String)

/** A reading from the device, with when it was read. A number without its age is a lie waiting to happen. */
data class Reading<T>(val value: T, val atMillis: Long)

data class DeviceInfo(
    val name: String? = null,
    val address: String,
    val model: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val codec: Int? = null,
    val frameSamples: Int = 320,
    val mtu: Int? = null,
)

data class CaptureState(
    val link: Link = Link.IDLE,
    val device: DeviceInfo? = null,
    val battery: Reading<Int>? = null,
    val charging: Reading<Boolean>? = null,
    /** The Omi's LED brightness, 0-100, as it last reported. */
    val ledBrightness: Int? = null,
    /** The Omi's microphone gain level, 0-8, as it reports it. */
    val micGain: Int? = null,
    val rssi: Reading<Int>? = null,
    val deviceClockSkewSeconds: Reading<Long>? = null,
    val ring: Reading<RingInfo>? = null,
    val frames: Long = 0,
    val dropped: Long = 0,
    val reboots: Int = 0,
    val clipsWritten: Int = 0,
    val heldSeconds: Double = 0.0,
    /** When the last audio packet arrived. Liveness is progress, not announcement. */
    val lastAudioMillis: Long? = null,
    val nextRetryMillis: Long? = null,
    val sync: SyncStatus? = null,
    /** listening | thinking while a button question is in flight. */
    val asking: String? = null,
    /** Whether the Omi accepted a button subscription this session (null: not tried). */
    val buttonReady: Boolean? = null,
    /** The last tap (1) or double tap (2) the Omi reported, and when. */
    val lastButton: Pair<Int, Long>? = null,
    val log: List<String> = emptyList(),
)

/**
 * While setup's "try the button" step is showing, taps are only recorded and
 * acknowledged with a buzz -- they don't start a question.
 */
object ButtonTest {
    @Volatile var active = false
}

/** Process-wide capture state: the service writes it, the UI reads it. */
object CaptureRepository {
    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    fun update(f: (CaptureState) -> CaptureState) = _state.update(f)

    fun log(line: String) {
        val stamped = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $line"
        android.util.Log.i("Boswell", line)
        _state.update { it.copy(log = (it.log + stamped).takeLast(200)) }
    }
}
