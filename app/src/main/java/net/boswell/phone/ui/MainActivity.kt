package net.boswell.phone.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import net.boswell.phone.ui.theme.BoswellTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On first run the setup's Permissions step asks, with an explanation;
        // asking here too would put Android's prompts in front of the welcome
        // screen. Afterwards this only re-asks for anything since revoked.
        if (net.boswell.phone.setup.Setup.done(this)) permissions.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }.toTypedArray()
                .takeIf { it.isNotEmpty() } ?: return run { setContent { BoswellTheme { BoswellApp(vm, openTarget.value) } } }
        )
        setContent { BoswellTheme { BoswellApp(vm, openTarget.value) } }
    }

    /** Where a notification asked to land; a new one arriving while open replaces it. */
    private val openTarget by lazy { androidx.compose.runtime.mutableStateOf(intent?.getStringExtra("open")) }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("open")?.let { openTarget.value = it }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStorage()
    }
}
