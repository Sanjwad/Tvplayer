package ca.sanjwad.tvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ca.sanjwad.tvplayer.ui.App
import ca.sanjwad.tvplayer.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.appContext = applicationContext
        // Lighter images: posters decode in half the memory (RGB_565, unnoticeable on posters), a big
        // disk cache so they come back instantly next time, and no fade-in work.
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(applicationContext)
                .allowRgb565(true)
                .crossfade(false)
                .memoryCache { coil.memory.MemoryCache.Builder(applicationContext).maxSizePercent(0.20).build() }
                .diskCache {
                    coil.disk.DiskCache.Builder().directory(cacheDir.resolve("images"))
                        .maxSizeBytes(250L * 1024 * 1024).build()
                }
                .respectCacheHeaders(false) // provider image servers often say "don't cache"; posters rarely change
                .build()
        )
        setContent { AppTheme { App() } }
    }

    // Coming back to the app (e.g. after watching on another TV): pick up changes straight away.
    override fun onResume() {
        super.onResume()
        Sync.now()
        Thread { runCatching { Share.refreshMe() }; runCatching { Share.refreshInbox() }; runCatching { Share.processPhone() } }.apply { isDaemon = true }.start()
    }
}
