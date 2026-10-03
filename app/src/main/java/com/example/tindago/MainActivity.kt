package com.example.tindago

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.tindago.ui.localization.AppSettings
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.LocalTextScale
import com.example.tindago.ui.navigation.NavGraph
import com.example.tindago.ui.theme.TindaGoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keyboard avoidance: consistent edge-to-edge on all API levels + the
        // window resizes for the IME (manifest also sets adjustResize).
        enableEdgeToEdge()

        val appSettings = AppSettings(this)

        setContent {
            val langState = remember { mutableStateOf(appSettings.language) }
            val scaleState = remember { mutableStateOf(appSettings.getTextScaleFactor()) }

            CompositionLocalProvider(
                LocalLanguage provides langState,
                LocalTextScale provides scaleState
            ) {
                TindaGoTheme {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = rememberNavController()
                        NavGraph(
                            navController = navController,
                            appSettings = appSettings
                        )
                    }
                }
            }
        }
    }

    // V2.70: foreground suppression — no notifications while the app is visible.
    override fun onStart() {
        super.onStart()
        com.example.tindago.data.notifications.AppForegroundTracker.isForeground = true
    }

    override fun onStop() {
        super.onStop()
        com.example.tindago.data.notifications.AppForegroundTracker.isForeground = false
    }
}
