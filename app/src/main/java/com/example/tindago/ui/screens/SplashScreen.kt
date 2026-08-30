package com.example.tindago.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.R
import com.example.tindago.ui.localization.LocalLanguage
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.Green600
import com.example.tindago.ui.theme.TindaGoTheme
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onNavigateToSetup: () -> Unit,
    onNavigateToHome: () -> Unit
) {
    var showContent by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        delay(800)
        showContent = true
        delay(700)
        val prefs = context.getSharedPreferences("tindago_prefs", 0)
        val setupComplete = prefs.getBoolean("has_completed_setup", false)
        if (setupComplete) onNavigateToHome() else onNavigateToSetup()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Green600),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_logo),
                contentDescription = "TindaGo Logo",
                modifier = Modifier.size(100.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "TindaGo",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            val langState = LocalLanguage.current
            Text(
                text = "splashSubtitle".t(langState.value),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                // Center-align so longer translations stay centered when they wrap.
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(32.dp))
            if (!showContent) {
                LinearProgressIndicator(
                    modifier = Modifier.width(120.dp).height(4.dp),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Splash Screen")
@Composable
fun SplashScreenPreview() {
    TindaGoTheme {
        SplashScreen(
            onNavigateToSetup = {},
            onNavigateToHome = {}
        )
    }
}
