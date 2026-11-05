package ca.sanjwad.tvplayer

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(StalkerClient.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast("Playback problem: ${error.errorCodeName}")
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
                play(url)
            }
            liveIndex >= 0 -> {
                view.controllerAutoShow = false
                playLive(liveIndex)
            }
            else -> finish()
        }
    }

    private fun play(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
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
            val link = withContext(Dispatchers.IO) { runCatching { client.createLink("itv", ch.cmd) } }
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
