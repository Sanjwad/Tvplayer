package ca.sanjwad.tvplayer

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.media3.common.C
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import ca.sanjwad.tvplayer.stalker.Net
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
    /** Quiet reconnects for movies and episodes after a mid-stream blip. */
    private var vodRetries = 0
    private var currentUrl: String? = null
    /** True once the current movie/episode has actually started, so mid-stream errors are treated as blips. */
    private var everReady = false
    /** Connection drops during this playback (quiet retries + reconnects), shown when you leave. */
    private var drops = 0
    /** What the last drop was, so the exit message can say why. */
    @Volatile private var lastDrop = ""
    private var firstDrop = ""
    /** Set while quietly reconnecting a movie/episode, so the controls don't pop up. */
    private var quietReconnect = false
    /** Only show "Reconnecting…" if a reconnect hasn't recovered within a couple of seconds. */
    private val showReconnecting = Runnable { toast("Reconnecting…") }
    /** Set when the address says .mp4/.mkv/.ts but the server actually sends an HLS playlist. */
    private var asHls = false
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }

    // Resume support for movies and episodes.
    private var resumeKey: String? = null
    private var resumed = false
    private val saver = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() { saveProgress(); saver.postDelayed(this, 15_000) }
    }

    private fun saveProgress() {
        val key = resumeKey ?: return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it > 0 } ?: return
        Library.saveProgress(Progress(
            key = key,
            title = intent.getStringExtra("title").orEmpty(),
            subtitle = intent.getStringExtra("subtitle").orEmpty(),
            poster = intent.getStringExtra("poster"),
            cmd = intent.getStringExtra("cmd") ?: currentUrl.orEmpty(),
            posMs = pos, durMs = dur, updated = System.currentTimeMillis(),
            seriesId = intent.getStringExtra("series_id"),
        ))
    }
    /** Recent request outcomes, shown when playback fails so the cause is visible. */
    private val trail = java.util.Collections.synchronizedList(mutableListOf<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isLive = intent.getIntExtra("live_index", -1) >= 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Portal headers go only to the portal itself; video servers get a plain request,
        // and any cookies they hand out are kept for the follow-up requests (playlists, segments).
        val client = Session.client
        val portalHost = client?.portalHost
        val portalHeaders = client?.streamHeaders() ?: emptyMap()
        val okhttp = OkHttpClient.Builder()
            .cookieJar(MemoryCookieJar())
            .apply { if (Session.client is ca.sanjwad.tvplayer.stalker.StalkerClient) dns(ca.sanjwad.tvplayer.stalker.Ipv4First) }
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            // Movies/episodes: give up on a stalled connection quickly; it's resumed from the same byte.
            .readTimeout(if (isLive) 30 else 12, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // Stalker video servers expect the MAG box's User-Agent for movies too.
                val st = client as? ca.sanjwad.tvplayer.stalker.StalkerClient
                val ua: String? = when {
                    isLive || chain.request().url.host == portalHost -> Net.UA
                    st != null -> st.videoUa // whichever identity the video server accepted when we tested the link
                    else -> Net.VIDEO_UA
                }
                val b = chain.request().newBuilder().apply { if (ua != null) header("User-Agent", ua) else removeHeader("User-Agent") }
                if (chain.request().url.host == portalHost) portalHeaders.forEach { (k, v) -> b.header(k, v) }
                val req = b.build()
                val t0 = System.currentTimeMillis()
                var resp = try { chain.proceed(req) } catch (e: java.io.IOException) {
                    ca.sanjwad.tvplayer.stalker.PortalLog.add("player  ${req.url.host}:${req.url.port}${req.url.encodedPath.take(40)} → " +
                        "${e.javaClass.simpleName} ${e.message.orEmpty()} after ${(System.currentTimeMillis() - t0) / 1000}s")
                    throw e
                }
                // Stalker video server refused us: try again as a MAG box (portal cookie/headers), then as VLC.
                if (resp.code == 403 && client is ca.sanjwad.tvplayer.stalker.StalkerClient && req.url.host != portalHost) {
                    val variants = listOf(
                        "MAG headers" to req.newBuilder().header("User-Agent", Net.UA).apply {
                            portalHeaders.forEach { (k, v) -> if (k != "Authorization") header(k, v) }
                        }.build(),
                        "MAG headers + token" to req.newBuilder().header("User-Agent", Net.UA).apply {
                            portalHeaders.forEach { (k, v) -> header(k, v) }
                        }.build(),
                        "VLC" to req.newBuilder().header("User-Agent", Net.VIDEO_UA).build(),
                    )
                    for ((name, r2) in variants) {
                        ca.sanjwad.tvplayer.stalker.PortalLog.add("player  403 → retrying with $name")
                        resp.close()
                        resp = chain.proceed(r2)
                        ca.sanjwad.tvplayer.stalker.PortalLog.add("player  $name → ${resp.code}")
                        if (resp.code != 403) break
                    }
                }
                val u = resp.request.url
                var line = "${resp.code} ${u.host}:${u.port}${u.encodedPath.take(40)}"
                resp.priorResponse?.let { pr -> line = "${pr.code}→" + line }
                if (resp.code >= 400) {
                    val body = runCatching { resp.peekBody(300).string().replace(Regex("\\s+"), " ").trim() }.getOrDefault("")
                    val hdrs = listOf("Server", "Content-Type", "X-Reason", "X-Error").mapNotNull { h -> resp.header(h)?.let { "$h: $it" } }
                    line += "\n   " + (hdrs + listOfNotNull(body.ifBlank { null })).joinToString(" | ")
                }
                trail += line
                ca.sanjwad.tvplayer.stalker.PortalLog.add("player  $line  ${resp.header("Content-Type").orEmpty()} ${resp.header("Content-Length").orEmpty()}b (${System.currentTimeMillis() - t0}ms)")
                if (trail.size > 12) trail.removeAt(0)
                resp
            }
            .build()
        val http = OkHttpDataSource.Factory(okhttp)

        val loadControl = if (isLive) {
            // Start after ~2.5s of video and, after a stall, wait for ~5s before carrying on, so a slow
            // moment on the server doesn't turn into repeated spinning. (Was 1s/2s: quick, but no cushion.)
            DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000).build()
        } else {
            // Movies/episodes: keep ~90s loaded ahead, topped up continuously (min == max). If the player
            // fills up and then stops reading for a minute or more, video servers close the idle connection,
            // which is what caused the drops; live TV never gets ahead, which is why it was fine.
            DefaultLoadControl.Builder().setBufferDurationsMs(90_000, 90_000, 1_500, 3_000).build()
        }
        // Movies/episodes: when the connection drops, retry quietly many times, picking the file up
        // from exactly where it stopped, while playback carries on from what's already loaded.
        val errorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int) =
                if (isLive) super.getMinimumLoadableRetryCount(dataType) else 15

            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val base = super.getRetryDelayMsFor(info)
                if (isLive || base == C.TIME_UNSET) return base
                val why = describe(info.exception)
                if (info.errorCount == 1) runOnUiThread { drops++; if (firstDrop.isEmpty()) firstDrop = why }
                lastDrop = why
                // 403 on a reconnect usually means the server still counts the old connection; give it time to let go.
                val code = (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                return if (code in 400..499) minOf(2_000L * info.errorCount, 8_000L)
                else minOf(500L * info.errorCount, 3_000L)
            }
        }
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            // DefaultDataSource: video over our OkHttp client, downloaded subtitle files from storage.
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, http))
                .setLoadErrorHandlingPolicy(errorPolicy))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    liveRetries = 0; vodRetries = 0; everReady = true; liveStarted = true
                    saver.removeCallbacks(showReconnecting)
                    if (quietReconnect) {
                        quietReconnect = false
                        view.hideController()
                        view.controllerAutoShow = true
                    }
                }
                if (state == Player.STATE_READY && !resumed) {
                    resumed = true
                    val saved = resumeKey?.takeUnless { intent.getBooleanExtra("from_start", false) }?.let { Library.progress[it] }
                    val dur = player.duration
                    if (saved != null && saved.posMs > 30_000 && (dur <= 0 || saved.posMs < dur - 60_000)) {
                        player.seekTo(saved.posMs)
                        val m = saved.posMs / 60_000; val sec = (saved.posMs / 1000) % 60
                        toast("Resuming at $m:${"%02d".format(sec)}  ·  press ◀◀ to go back to the start")
                    }
                    if (resumeKey != null) { saver.removeCallbacks(saveTick); saver.postDelayed(saveTick, 15_000) }
                }
                if (state == Player.STATE_READY && liveIndex < 0 && asHls != prefs.getBoolean("vod_hls", false)) {
                    prefs.edit().putBoolean("vod_hls", asHls).apply()
                }

            }

            override fun onPlayerError(error: PlaybackException) {
                ca.sanjwad.tvplayer.stalker.PortalLog.add("player ✗ ${error.errorCodeName}: ${describe(error.cause ?: error)}")
                // Live TV fell behind after a stutter: jump back to live, no message.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                // Live channel never started as a .ts stream: try this channel as HLS instead, and remember that.
                if (liveIndex >= 0 && !liveStarted) {
                    val ch = Session.channels.getOrNull(liveIndex)
                    if (ch != null && ch.id !in hlsChannels) {
                        hlsChannels += ch.id
                        prefs.edit().putStringSet("live_hls_ids", hlsChannels.toSet()).apply()
                        playLive(liveIndex, retry = true)
                        return
                    }
                }
                // Other live hiccups: quietly reconnect the channel a few times before showing anything.
                if (liveIndex >= 0 && liveRetries < 3) {
                    liveRetries++
                    toast("Reconnecting…")
                    val idx = liveIndex
                    saver.postDelayed({ if (liveIndex == idx) playLive(idx, retry = true) }, 1500L * liveRetries)
                    return
                }
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
                // Catch-up: servers differ on which timeshift address works, so try the next one.
                if (liveIndex < 0 && !everReady && fallbackUrls.isNotEmpty()) {
                    asHls = false
                    play(fallbackUrls.removeAt(0), keepFormat = true)
                    return
                }
                // Only try the HLS fallback when the video never started; a mid-stream error is just a blip.
                if (url != null && !asHls && code == 0 && !(liveIndex < 0 && everReady) &&
                    (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
                ) {
                    asHls = true
                    play(url, keepFormat = true)
                    return
                }
                // Movie or episode blipped mid-stream: reconnect and carry on from the same point.
                // A 403 is a refusal, not a blip: retrying won't help, so say so straight away.
                if (liveIndex < 0 && url != null && vodRetries < 5 && !(code == 403 && client is ca.sanjwad.tvplayer.stalker.StalkerClient)) {
                    vodRetries++
                    drops++
                    lastDrop = "${error.errorCodeName}: ${describe(error.cause ?: error)}"
                    if (firstDrop.isEmpty()) firstDrop = lastDrop
                    // Keep the last picture on screen and the controls hidden while we reconnect;
                    // prepare() picks up from the current position with the same video.
                    quietReconnect = true
                    view.controllerAutoShow = false
                    view.hideController()
                    saver.removeCallbacks(showReconnecting)
                    saver.postDelayed(showReconnecting, 2_000)
                    saver.postDelayed({
                        if (currentUrl == url) player.prepare()
                    }, if (vodRetries == 1) 300L else 1500L * (vodRetries - 1))
                    return
                }
                saver.removeCallbacks(showReconnecting)
                if (quietReconnect) { quietReconnect = false; view.controllerAutoShow = true }
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
            // Don't flash black when the player reconnects; keep the last frame until video resumes.
            setKeepContentOnPlayerReset(true)
        }
        // Pause screen and cast pages are drawn over the video.
        overlayView = androidx.compose.ui.platform.ComposeView(this).apply {
            visibility = android.view.View.GONE
            isFocusable = true
            setContent { OverlayContent() }
        }
        setContentView(android.widget.FrameLayout(this).apply {
            addView(view)
            addView(overlayView)
        })
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { tracksVersion++ }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saver.removeCallbacks(showPause)
                if (isPlaying) { if (overlay != null) closeOverlay() }
                // Paused by you (not buffering): show the pause screen after a moment.
                else if ((liveIndex < 0 || liveChannelHasCatchup()) && !player.playWhenReady && player.playbackState == Player.STATE_READY)
                    saver.postDelayed(showPause, 1200)
            }
        })

        liveIndex = intent.getIntExtra("live_index", -1)
        subLang = appPrefs.subLang
        val url = intent.getStringExtra("url")
        when {
            url != null -> {
                resumeKey = intent.getStringExtra("key")
                view.controllerAutoShow = true
                fallbackUrls.addAll(intent.getStringArrayListExtra("fallback_urls").orEmpty())
                asHls = if (isCatchup) false else prefs.getBoolean("vod_hls", false)
                play(url, keepFormat = true)
            }
            liveIndex >= 0 -> {
                view.controllerAutoShow = false
                playLive(liveIndex)
            }
            else -> finish()
        }
    }


    private fun play(url: String, keepFormat: Boolean = false, startMs: Long = 0) {
        if (url != currentUrl && !keepFormat && liveIndex < 0) asHls = false
        currentUrl = url
        ca.sanjwad.tvplayer.stalker.PortalLog.add("player ▶ ${url.take(300)}")
        val item = MediaItem.Builder().setUri(url)
            .apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(externalSubs.toList())
            .build()
        if (startMs > 0) player.setMediaItem(item, startMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    private var liveRetries = 0
    /** Set once the current channel has actually started playing. */
    private var liveStarted = false
    /** Channels that wouldn't play as a continuous .ts stream, so they use HLS (.m3u8) instead. */
    private val hlsChannels by lazy { prefs.getStringSet("live_hls_ids", emptySet())!!.toMutableSet() }

    private fun playLive(index: Int, retry: Boolean = false) {
        if (!retry) { liveRetries = 0; liveStarted = false }
        val list = Session.channels
        val client = Session.client
        if (list.isEmpty() || client == null) {
            finish(); return
        }
        liveIndex = ((index % list.size) + list.size) % list.size
        val ch = list[liveIndex]
        Library.lastChannelId = ch.id
        if (!retry) toast(if (ch.number > 0) "${ch.number}  ${ch.name}" else ch.name)
        switchJob?.cancel()
        switchJob = lifecycleScope.launch {
            val link = withContext(Dispatchers.IO) { runCatching { client.createLink("itv", ch.cmd, "", null) } }
            // Live TV as one continuous MPEG-TS stream (what TiVimate uses for Xtream) rather than HLS,
            // which polls a playlist for short chunks and spins whenever the server is slow to post one.
            link.onSuccess {
                val asTs = client is ca.sanjwad.tvplayer.stalker.XtreamClient && ch.id !in hlsChannels && it.endsWith(".m3u8")
                asHls = false
                play(if (asTs) it.removeSuffix(".m3u8") + ".ts" else it)
            }
                .onFailure { toast(it.message ?: "Couldn't start this channel") }
            // Show what's on now, once the guide answers.
            val now = withContext(Dispatchers.IO) { runCatching { client.nowNext(ch) }.getOrNull()?.firstOrNull() }
            if (!retry && now != null && liveIndex >= 0 && Session.channels.getOrNull(liveIndex)?.id == ch.id) {
                toast("${if (ch.number > 0) "${ch.number}  " else ""}${ch.name}\nNow: ${now.title}")
            }
        }
    }

    // ---------- pause screen & cast ----------

    private lateinit var overlayView: androidx.compose.ui.platform.ComposeView
    /** null, "pause", "about", "cast", "tracks", "live" (paused live channel) or "catchup". */
    private var overlay by androidx.compose.runtime.mutableStateOf<String?>(null)
    private val castNav by lazy {
        val year = intent.getStringExtra("year")?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("subtitle")?.takeIf { Regex("^\\d{4}$").matches(it) }.orEmpty()
        val series = intent.getStringExtra("is_series")?.let { it == "1" } ?: (intent.getStringExtra("series_id") != null)
        ca.sanjwad.tvplayer.ui.CastNav(intent.getStringExtra("title").orEmpty(), year, series)
    }
    private val showPause = Runnable {
        if (player.isPlaying || player.playWhenReady || overlay != null) return@Runnable
        if (liveIndex < 0) openOverlay("pause")
        else if (liveChannelHasCatchup()) {
            val ch = Session.channels.getOrNull(liveIndex) ?: return@Runnable
            liveNow = null
            openOverlay("live")
            lifecycleScope.launch {
                liveNow = withContext(Dispatchers.IO) { runCatching { Session.client?.nowNext(ch)?.firstOrNull() }.getOrNull() }
            }
        }
    }

    // ---------- subtitles & audio ----------

    private var tracksVersion by androidx.compose.runtime.mutableIntStateOf(0)
    private val externalSubs = mutableListOf<MediaItem.SubtitleConfiguration>()
    private val appPrefs by lazy { Prefs(this) }
    private var subLang by androidx.compose.runtime.mutableStateOf("en")
    private var subSearch by androidx.compose.runtime.mutableStateOf<ca.sanjwad.tvplayer.ui.SubSearch>(ca.sanjwad.tvplayer.ui.SubSearch.Idle)
    private val openSubs by lazy {
        val key = appPrefs.osKey.ifBlank { BuildConfig.OPENSUBS_KEY }
        if (key.isBlank()) null else ca.sanjwad.tvplayer.stalker.OpenSubs(key, appPrefs.osUser, appPrefs.osPass)
    }

    private fun langName(code: String?): String =
        code?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: "Unknown language"

    /** Choices for the subtitles or audio list, from what the video actually contains. */
    private fun trackOpts(type: Int): List<ca.sanjwad.tvplayer.ui.TrackOpt> = player.currentTracks.groups
        .filter { it.type == type }
        .flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                val f = g.getTrackFormat(i)
                val label = f.label?.takeIf { it.isNotBlank() } ?: langName(f.language)
                val detail = buildList {
                    if (f.label != null && f.language != null) add(langName(f.language))
                    if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0)
                        add(when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount} ch" })
                    if (type == C.TRACK_TYPE_TEXT && f.id?.startsWith("dl:") == true) add("downloaded")
                    if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add("forced")
                }.joinToString(" · ")
                ca.sanjwad.tvplayer.ui.TrackOpt(label, detail, g.isTrackSelected(i)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                    tracksVersion++
                }
            }
        }

    private fun searchSubs() {
        val os = openSubs ?: return
        subSearch = ca.sanjwad.tvplayer.ui.SubSearch.Searching
        val title = intent.getStringExtra("title").orEmpty()
        val year = intent.getStringExtra("year").orEmpty()
        val series = intent.getStringExtra("is_series") == "1"
        val season = intent.getStringExtra("season")?.toIntOrNull()
        val episode = intent.getStringExtra("episode")?.toIntOrNull()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val tmdbId = runCatching { if (series) Session.tmdb?.findTv(title, year) else Session.tmdb?.findMovie(title, year) }.getOrNull()
                    os.search(tmdbId, ca.sanjwad.tvplayer.stalker.TitleMatch.queryName(title), year,
                        if (series) season else null, if (series) episode else null, subLang)
                }
            }
            subSearch = r.fold({ ca.sanjwad.tvplayer.ui.SubSearch.Results(it) },
                { ca.sanjwad.tvplayer.ui.SubSearch.Failed(it.message ?: "Search failed.") })
        }
    }

    private fun downloadSub(sub: ca.sanjwad.tvplayer.stalker.OpenSubs.Sub) {
        val os = openSubs ?: return
        val url = currentUrl ?: return
        subSearch = ca.sanjwad.tvplayer.ui.SubSearch.Downloading(sub)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { os.download(sub, cacheDir) } }
            r.onSuccess { file ->
                externalSubs += MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(sub.lang.ifBlank { subLang })
                    .setLabel("${langName(sub.lang.ifBlank { subLang })} (downloaded)")
                    .setId("dl:${sub.fileId}")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                // Reload the same video at the same point with the subtitle attached, and switch it on.
                val pos = player.currentPosition
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(sub.lang.ifBlank { subLang })
                    .build()
                play(url, keepFormat = true, startMs = pos)
                subSearch = ca.sanjwad.tvplayer.ui.SubSearch.Idle
                closeOverlay()
                toast("Subtitles on")
            }.onFailure { subSearch = ca.sanjwad.tvplayer.ui.SubSearch.Failed(it.message ?: "Download failed.") }
        }
    }

    // ---------- catch-up ----------

    private val fallbackUrls = mutableListOf<String>()
    private val isCatchup by lazy { intent.getStringExtra("catchup") == "1" }
    private var liveNow by androidx.compose.runtime.mutableStateOf<ca.sanjwad.tvplayer.stalker.Programme?>(null)

    private fun liveChannelHasCatchup() = liveIndex >= 0 && (Session.channels.getOrNull(liveIndex)?.archiveDays ?: 0) > 0

    private fun openOverlay(kind: String) {
        overlay = kind
        view.hideController()
        view.useController = false
        overlayView.visibility = android.view.View.VISIBLE
        overlayView.requestFocus()
    }

    private fun closeOverlay(showControls: Boolean = false) {
        overlay = null
        overlayView.visibility = android.view.View.GONE
        view.useController = true
        if (showControls) view.showController()
        view.requestFocus()
    }

    @androidx.compose.runtime.Composable
    private fun OverlayContent() {
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * 0.85f, d.fontScale),
        ) {
            ca.sanjwad.tvplayer.ui.AppTheme {
                when (overlay) {
                    "pause" -> ca.sanjwad.tvplayer.ui.PauseOverlay(
                        title = intent.getStringExtra("title").orEmpty(),
                        subtitle = intent.getStringExtra("subtitle").orEmpty(),
                        plot = intent.getStringExtra("plot").orEmpty(),
                        poster = intent.getStringExtra("poster"),
                        onResume = { closeOverlay(); player.play() },
                        // Catch-up is a TV programme, not a film: TMDB would guess wrongly.
                        onCast = if (Session.tmdb != null && !isCatchup) ({ overlay = "cast" }) else null,
                        onBack = { closeOverlay(showControls = true) },
                        onAbout = if (Session.tmdb != null && !isCatchup) ({ overlay = "about" }) else null,
                        onTracks = { overlay = "tracks" },
                    )
                    "tracks" -> {
                        @Suppress("UNUSED_EXPRESSION") tracksVersion
                        ca.sanjwad.tvplayer.ui.TracksOverlay(
                            subtitles = trackOpts(C.TRACK_TYPE_TEXT),
                            subsOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                                player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected },
                            onSubsOff = {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                                tracksVersion++
                            },
                            audio = trackOpts(C.TRACK_TYPE_AUDIO),
                            lang = subLang, onLang = { subLang = it; appPrefs.subLang = it; subSearch = ca.sanjwad.tvplayer.ui.SubSearch.Idle },
                            download = if (openSubs != null && liveIndex < 0 && !isCatchup) subSearch else null,
                            onSearch = { searchSubs() }, onPick = { downloadSub(it) },
                            onClose = { overlay = "pause" },
                        )
                    }
                    "live" -> Session.channels.getOrNull(liveIndex)?.let { ch ->
                        val now = liveNow
                        ca.sanjwad.tvplayer.ui.LivePauseOverlay(ch, now,
                            onResume = { closeOverlay(); playLive(liveIndex) },
                            onStartOver = if (now != null) ({ ca.sanjwad.tvplayer.ui.Catchup.play(this@PlayerActivity, ch, now) }) else null,
                            onCatchup = { overlay = "catchup" },
                            onBack = { closeOverlay(showControls = true) })
                    }
                    "catchup" -> Session.channels.getOrNull(liveIndex)?.let { ch ->
                        ca.sanjwad.tvplayer.ui.CatchupScreen(ch,
                            onPlay = { p -> ca.sanjwad.tvplayer.ui.Catchup.play(this@PlayerActivity, ch, p) },
                            onClose = { overlay = "live" })
                    }
                    "about" -> ca.sanjwad.tvplayer.ui.AboutPage(
                        ca.sanjwad.tvplayer.ui.AboutNav(castNav.title, castNav.year, castNav.isSeries),
                        onClose = { overlay = "pause" }, onCast = { overlay = "cast" },
                        onPick = { type, item -> playFromCast(type, item) })
                    "cast" -> ca.sanjwad.tvplayer.ui.CastBrowser(castNav, onClose = { overlay = "pause" },
                        onPick = { type, item -> playFromCast(type, item) })
                }
            }
        }
    }

    /** A title picked on an actor's page: films play straight away; shows open their page in the app. */
    private fun playFromCast(type: String, item: ca.sanjwad.tvplayer.stalker.VodItem) {
        saveProgress()
        if (item.isSeries) {
            Session.pendingOpen = type to item
            finish()
            return
        }
        toast("Starting ${item.name}…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { (Session.client ?: error("Not connected")).createLink(type, item.cmd, "", item.id) }
            }
            r.onSuccess { url ->
                startActivity(android.content.Intent(this@PlayerActivity, PlayerActivity::class.java)
                    .putExtra("url", url).putExtra("title", item.name).putExtra("cmd", item.cmd)
                    .putExtra("key", "m:${item.id}").putExtra("subtitle", item.year).putExtra("year", item.year)
                    .putExtra("poster", item.poster ?: item.backdrop).putExtra("plot", item.description).putExtra("is_series", "0"))
                finish()
            }.onFailure { toast(it.message ?: "Couldn't start ${item.name}") }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Play on the remote while the pause screen is up: just resume.
        if (overlay != null && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            closeOverlay(); if (liveIndex >= 0) playLive(liveIndex) else player.play(); return true
        }
        if (liveIndex >= 0 && overlay == null && event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
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

    /** Short, readable reason for a dropped connection, e.g. "HTTP 416" or "SocketTimeoutException". */
    private fun describe(e: Throwable): String {
        var c: Throwable? = e
        while (c != null) {
            if (c is HttpDataSource.InvalidResponseCodeException) return "HTTP ${c.responseCode}"
            c = c.cause
        }
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return root.javaClass.simpleName + (root.message?.take(60)?.let { " – $it" } ?: "")
    }

    private var lastToast: Toast? = null
    private fun toast(msg: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        saver.removeCallbacks(saveTick)
        saver.removeCallbacks(showReconnecting)
        saver.removeCallbacks(showPause)
        player.pause()
        // Testing aid: how many times the connection dropped (and was recovered) during this playback.
        if (liveIndex < 0 && drops > 0) {
            Toast.makeText(applicationContext, "Connection dropped $drops× (recovered)\nFirst: $firstDrop\nLast: $lastDrop", Toast.LENGTH_LONG).show()
            drops = 0; firstDrop = ""
        }
    }

    override fun onStart() {
        super.onStart()
        if (resumeKey != null && resumed) { saver.removeCallbacks(saveTick); saver.postDelayed(saveTick, 15_000) }
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
