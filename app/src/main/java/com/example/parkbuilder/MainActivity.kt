package com.example.parkbuilder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.parkbuilder.ui.GameScreen
import com.example.parkbuilder.ui.theme.ParkBuilderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Immersive: the HUD panels sit flush against the screen edges and the park gets
        // the strip the status bar was using. A swipe from an edge brings the bars back.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            ParkBuilderTheme {
                // No Scaffold: the park should render behind the system bars the way a game
                // does. Each HUD panel applies its own inset padding instead.
                GameScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
