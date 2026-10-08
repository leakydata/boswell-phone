package net.boswell.phone.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Picks the chosen mode back up after the phone restarts or the app updates:
 * live streaming restarts, sync visits are re-armed, and Off stays off.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val address = net.boswell.phone.sync.Modes.address(context) ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        when (net.boswell.phone.sync.Modes.mode(context)) {
            net.boswell.phone.sync.Mode.LIVE -> runCatching { CaptureService.start(context, address) }.logged("starting after boot")
            // WorkManager keeps the periodic visit across reboots; this only re-arms it after an update.
            net.boswell.phone.sync.Mode.SYNC -> net.boswell.phone.sync.SyncWorker.schedule(context)
            net.boswell.phone.sync.Mode.OFF -> Unit
        }
    }

    companion object {
        const val KEEP_RECORDING = "keep_recording"
    }
}
