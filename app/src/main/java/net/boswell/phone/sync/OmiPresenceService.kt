package net.boswell.phone.sync

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.CaptureService

/**
 * Android tells this service when the paired Omi comes into range. In sync
 * mode that is the best moment to visit -- the device is here and has been
 * recording without us -- as long as the last visit was not moments ago.
 * In live mode it is the moment to pick the stream back up.
 */
class OmiPresenceService : CompanionDeviceService() {
    @Deprecated("API 33 callback, still delivered")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val address = Modes.address(this) ?: return
        CaptureRepository.log("Omi came into range")
        runCatching {
            when (Modes.mode(this)) {
                Mode.SYNC -> if (System.currentTimeMillis() - Modes.lastSync(this) > MIN_GAP_MS) CaptureService.sync(this, address)
                Mode.LIVE -> CaptureService.start(this, address)
                Mode.OFF -> Unit
            }
        }.onFailure { CaptureRepository.log("could not start on arrival: ${it.message}") }
    }

    @Deprecated("API 33 callback, still delivered")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        CaptureRepository.log("Omi out of range")
    }

    companion object {
        /** Presence can flap at the edge of range; one visit per ten minutes is plenty. */
        const val MIN_GAP_MS = 10 * 60_000L
    }
}
