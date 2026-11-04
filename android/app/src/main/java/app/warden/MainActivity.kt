package app.warden

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import app.warden.data.WardenStore
import app.warden.service.WardenVpnService
import app.warden.ui.WardenViewModel
import app.warden.ui.screens.WardenApp
import app.warden.ui.theme.WardenTheme

class MainActivity : ComponentActivity() {

    private val viewModel: WardenViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WardenTheme {
                WardenApp(viewModel, onToggleProtection = { toggleProtection() })
            }
        }
    }

    private fun toggleProtection() {
        if (WardenStore.isProtectionOn.value) {
            val stop = Intent(this, WardenVpnService::class.java).apply {
                action = WardenVpnService.ACTION_STOP
            }
            startService(stop)
            WardenStore.setProtectionOn(false)
        } else {
            // Android asks the user once to allow the VPN. The user taps
            // OK themselves; Warden never sees a password here.
            val prepare = VpnService.prepare(this)
            if (prepare == null) {
                startVpnService()
            } else {
                vpnPermissionLauncher.launch(prepare)
            }
        }
    }

    private fun startVpnService() {
        val start = Intent(this, WardenVpnService::class.java).apply {
            action = WardenVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, start)
    }
}
