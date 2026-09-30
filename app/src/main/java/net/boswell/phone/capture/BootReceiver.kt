package net.boswell.phone.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Picks recording back up after the phone restarts, if it was recording
 * before. "Keep recording" is set by Connect and cleared by Disconnect, so a
 * phone that was deliberately not recording stays that way.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("boswell", Context.MODE_PRIVATE)
        val address = prefs.getString("omi_address", null) ?: return
        if (!prefs.getBoolean(KEEP_RECORDING, false)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        runCatching { CaptureService.start(context, address) }
    }

    companion object {
        const val KEEP_RECORDING = "keep_recording"
    }
}
