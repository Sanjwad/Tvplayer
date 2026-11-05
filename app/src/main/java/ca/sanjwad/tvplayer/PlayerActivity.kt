package ca.sanjwad.tvplayer

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import ca.sanjwad.tvplayer.stalker.StalkerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen playback. Live TV: up/down (or channel +/-) changes channel while
 * the controls are hidden. Movies and episodes: normal seek controls.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private var liveIndex = -1
    private var switchJob: Job? = null

    // Some video servers only accept certain players. For movies/episodes, try these in turn
    // when refused, and remember the one that worked.
    private val agents = listOf(
        StalkerClient.UA,
        "Lavf/58.76.100",
        "VLC/3.0.20 LibVLC/3.0.20",
        "okhttp/4.12.0",
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    )
    @Volatile private var agentIndex = 0
    private var firstAgent = 0
    private var currentUrl: String? = null
    /** Set when the address says .mp4/.mkv/.ts but the server actually sends an HLS playlist. */
    private var asHls = false
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
    /** Recent request outcomes, shown when playback fails so the cause is visible. */
    private val trail = java.util.Collections.synchronizedList(mutableListOf<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Portal headers go only to the portal itself; video servers get a plain request,
        // and any cookies they hand out are kept for the follow-up requests (playlists, segments).
        val client = Session.client
        val portalHost = client?.portalHost
        val portalHeaders = client?.streamHeaders() ?: emptyMap()
        val okhttp = OkHttpClient.Builder()
            .cookieJar(MemoryCookieJar())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val b = chain.request().newBuilder().header("User-Agent", agents[agentIndex])
                if (chain.request().url.host == portalHost) portalHeaders.forEach { (k, v) -> b.header(k, v) }
                val resp = chain.proceed(b.build())
                val u = resp.request.url
                var line = "${resp.code} ${u.host}:${u.port}${u.encodedPath.take(40)}"
                resp.priorResponse?.let { pr -> line = "${pr.code}→" + line }
                if (resp.code >= 400) {
                    val body = runCatching { resp.peekBody(300).string().replace(Regex("\\s+"), " ").trim() }.getOrDefault("")
                    val hdrs = listOf("Server", "Content-Type", "X-Reason", "X-Error").mapNotNull { h -> resp.header(h)?.let { "$h: $it" } }
                    line += "\n   " + (hdrs + listOfNotNull(body.ifBlank { null })).joinToString(" | ")
                }
                trail += line
                if (trail.size > 12) trail.removeAt(0)
                resp
            }
            .build()
        val http = OkHttpDataSource.Factory(okhttp)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && liveIndex < 0 && asHls != prefs.getBoolean("vod_hls", false)) {
                    prefs.edit().putBoolean("vod_hls", asHls).apply()
                }
                if (state == Player.STATE_READY && liveIndex < 0 && agentIndex != prefs.getInt("vod_agent", 0)) {
                    prefs.edit().putInt("vod_agent", agentIndex).apply()
                    toast("Worked with player type ${agentIndex + 1}, remembered for next time")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                var cause: Throwable? = error
                var detail = error.errorCodeName
                var code = 0
                while (cause != null) {
                    if (cause is HttpDataSource.InvalidResponseCodeException) {
                        code = cause.responseCode
                        detail = "server said $code for ${cause.dataSpec.uri.host}${cause.dataSpec.uri.path}"
                        break
                    }
                    cause = cause.cause
                }
                val url = currentUrl
                if (url != null && !asHls && code == 0 &&
                    (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
                ) {
                    asHls = true
                    play(url, keepFormat = true)
                    return
                }
                if (liveIndex < 0 && url != null && code in 400..499) {
                    val next = (agentIndex + 1) % agents.size
                    if (next != firstAgent) {
                        agentIndex = next
                        toast("Server refused, trying another way (${tried() + 1}/${agents.size})…")
                        play(url)
                        return
                    }
                    detail += "\n(tried ${agents.size} player types)"
                }
                val log = synchronized(trail) { trail.takeLast(6).joinToString("\n") }
                android.app.AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Playback problem")
                    .setMessage("$detail\n\nWhat happened:\n$log")
                    .setPositiveButton("OK", null)
                    .show()
            }
        })

        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        setContentView(view)

        liveIndex = intent.getIntExtra("live_index", -1)
        val url = intent.getStringExtra("url")
        when {
            url != null -> {
                view.controllerAutoShow = true
                firstAgent = prefs.getInt("vod_agent", 0).coerceIn(0, agents.size - 1)
                agentIndex = firstAgent
                asHls = prefs.getBoolean("vod_hls", false)
                play(url, keepFormat = true)
            }
            liveIndex >= 0 -> {
                view.controllerAutoShow = false
                playLive(liveIndex)
            }
            else -> finish()
        }
    }

    private fun tried() = (agentIndex - firstAgent + agents.size) % agents.size

    private fun play(url: String, keepFormat: Boolean = false) {
        if (url != currentUrl && !keepFormat) asHls = false
        currentUrl = url
        val item = MediaItem.Builder().setUri(url)
            .apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    private fun playLive(index: Int) {
        val list = Session.channels
        val client = Session.client
        if (list.isEmpty() || client == null) {
            finish(); return
        }
        liveIndex = ((index % list.size) + list.size) % list.size
        val ch = list[liveIndex]
        toast(if (ch.number > 0) "${ch.number}  ${ch.name}" else ch.name)
        switchJob?.cancel()
        switchJob = lifecycleScope.launch {
            val link = withContext(Dispatchers.IO) { runCatching { client.createLink("itv", ch.cmd, "", null) } }
            link.onSuccess { play(it) }
                .onFailure { toast(it.message ?: "Couldn't start this channel") }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (liveIndex >= 0 && event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    playLive(liveIndex + 1); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    playLive(liveIndex - 1); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var lastToast: Toast? = null
    private fun toast(msg: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (player.playbackState != Player.STATE_IDLE) player.play()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

/** Keeps cookies a video server sets so later requests in the same playback carry them. */
private class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            list.removeAll { old -> cookies.any { it.name == old.name } }
            list.addAll(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(store) { store[url.host]?.toList() ?: emptyList() }
}
