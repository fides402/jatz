package com.jatz.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.jatz.app.data.LibraryStore
import com.jatz.app.ui.JatzApp as JatzRoot
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            LibraryStore.ensureSeeded(applicationContext)
            LibraryStore.ensureSeededRap(applicationContext)
        }

        val app = application as com.jatz.app.JatzApp
        setContent {
            JatzRoot(playerController = app.playerController)
        }
    }
}
