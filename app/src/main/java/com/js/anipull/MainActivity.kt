package com.js.anipull

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge on purpose: the app itself adds statusBarsPadding
        // / navigationBarsPadding, so the top bar sits below the status
        // bar instead of under it (the bug in the last debug build).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Load saved accent/theme before first composition, so the
        // app never flashes default pink on launch.
        loadThemePrefs(this)
        setContent {
            AnipullTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AnipullApp()
                }
            }
        }
    }
}
