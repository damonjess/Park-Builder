package com.example.parkbuilder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.parkbuilder.ui.GameScreen
import com.example.parkbuilder.ui.theme.ParkBuilderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ParkBuilderTheme {
                // No Scaffold: the park should render behind the system bars the way a game
                // does. Each HUD panel applies its own inset padding instead.
                GameScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
