package net.boswell.phone.sync

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.ParcelUuid
import net.boswell.phone.omi.OmiUuids
import java.util.concurrent.Executor

/**
 * Pair the Omi with the app as a companion device -- not a Bluetooth bond.
 *
 * One system dialog, once. It lets Android wake the app when the Omi comes
 * into range and lets the app start sync visits from the background. It does
 * not bond: a bonded Omi gets auto-connected by the OS and stops advertising,
 * which the desktop learned makes it invisible to everything else.
 */
object OmiCompanion {
    fun isPaired(activity: Activity): Boolean {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java) ?: return false
        val id = Modes.associationId(activity)
        return cdm.myAssociations.any { it.id == id }
    }

    fun pair(activity: Activity, address: String, launch: (IntentSender) -> Unit, done: (String?) -> Unit) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java) ?: return done("companion devices not supported")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(address).setServiceUuid(ParcelUuid(OmiUuids.SERVICE)).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        val main = Executor { activity.runOnUiThread(it) }
        cdm.associate(request, main, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)

            override fun onAssociationCreated(info: AssociationInfo) {
                Modes.setAssociationId(activity, info.id)
                @Suppress("DEPRECATION")
                runCatching { cdm.startObservingDevicePresence(address) }
                done(null)
            }

            override fun onFailure(error: CharSequence?) = done(error?.toString() ?: "pairing failed")
        })
    }
}
