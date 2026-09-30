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
        permissions.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
        setContent { BoswellTheme { BoswellApp(vm, intent?.getStringExtra("open")) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStorage()
    }
}
