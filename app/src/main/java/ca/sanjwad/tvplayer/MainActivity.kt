package ca.sanjwad.tvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ca.sanjwad.tvplayer.ui.App
import ca.sanjwad.tvplayer.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { App() } }
    }

    // Coming back to the app (e.g. after watching on another TV): pick up changes straight away.
    override fun onResume() {
        super.onResume()
        Sync.now()
    }
}
