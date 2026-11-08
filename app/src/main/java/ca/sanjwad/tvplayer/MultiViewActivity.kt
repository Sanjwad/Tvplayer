package ca.sanjwad.tvplayer

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ca.sanjwad.tvplayer.stalker.Channel
import ca.sanjwad.tvplayer.stalker.Net
import ca.sanjwad.tvplayer.stalker.XtreamClient
import ca.sanjwad.tvplayer.ui.Accent
import ca.sanjwad.tvplayer.ui.AppTheme
import ca.sanjwad.tvplayer.ui.Bg
import ca.sanjwad.tvplayer.ui.FocusBox
import ca.sanjwad.tvplayer.ui.LogoYellow
import ca.sanjwad.tvplayer.ui.Panel
import ca.sanjwad.tvplayer.ui.TextDim
import ca.sanjwad.tvplayer.ui.TvButton
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Up to four live channels on one screen. The highlighted one has the sound; OK on a screen
 * changes its channel, makes it full screen or removes it.
 */
@OptIn(UnstableApi::class)
class MultiViewActivity : ComponentActivity() {

    private val okhttp by lazy {
        val c = Session.client
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val b = chain.request().newBuilder().header("User-Agent", Net.UA)
                if (c != null && chain.request().url.host == c.portalHost) c.streamHeaders().forEach { (k, v) -> b.header(k, v) }
                chain.proceed(b.build())
            }.build()
    }

    /** One player per screen; made when a channel is put there, released when it's removed. */
    fun newPlayer(): ExoPlayer = ExoPlayer.Builder(this)
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 40_000, 2_500, 5_000).build())
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(okhttp)))
        .build()

    suspend fun linkFor(ch: Channel): String? = withContext(Dispatchers.IO) {
        val c = Session.client ?: return@withContext null
        runCatching { c.createLink("itv", ch.cmd, "", null) }.getOrNull()?.let {
            // Same as single-screen live TV: Xtream channels as one continuous .ts stream.
            if (c is XtreamClient && it.endsWith(".m3u8")) it.removeSuffix(".m3u8") + ".ts" else it
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val all = Session.liveAll
        val first = intent.getStringExtra("channel_id")?.let { id -> all.firstOrNull { it.id == id } }
        val maxConn = (Session.client as? XtreamClient)?.maxConnections ?: 0
        setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density * 0.85f, d.fontScale)) {
                AppTheme { MultiView(all, first, maxConn) }
            }
        }
    }

    @Composable
    private fun MultiView(all: List<Channel>, first: Channel?, maxConn: Int) {
        val limit = if (maxConn in 1..3) maxConn else 4
        val cells = remember { mutableStateListOf<Channel?>(first, null).apply { if (limit < 2) removeAt(1) } }
        var focused by remember { mutableIntStateOf(0) }
        var menuFor by remember { mutableStateOf<Int?>(null) }
        var pickFor by remember { mutableStateOf<Int?>(if (first == null) 0 else null) }
        val requesters = remember { List(4) { FocusRequester() } }

        BackHandler(enabled = menuFor == null && pickFor == null) { finish() }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val rows = if (cells.size <= 2) listOf(cells.indices.toList()) else listOf(listOf(0, 1), listOf(2, 3).filter { it < cells.size })
            Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { i ->
                            Cell(cells[i], hasSound = i == focused, isFocused = i == focused,
                                modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(requesters[i])
                                    .onFocusChanged { if (it.isFocused) focused = i },
                                onOk = { if (cells[i] == null) pickFor = i else menuFor = i })
                        }
                        // Odd number of screens in a 2×2: keep the last one half-width.
                        if (row.size == 1 && cells.size > 2) Spacer(Modifier.weight(1f))
                    }
                }
            }
            LaunchedEffect(Unit) { runCatching { requesters[0].requestFocus() } }

            menuFor?.let { i ->
                CellMenu(cells[i], canAdd = cells.size < limit,
                    onChange = { menuFor = null; pickFor = i },
                    onFull = {
                        val ch = cells[i] ?: return@CellMenu
                        Session.channels = all
                        startActivity(android.content.Intent(this@MultiViewActivity, PlayerActivity::class.java)
                            .putExtra("live_index", all.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)))
                        finish()
                    },
                    onRemove = {
                        menuFor = null
                        if (cells.size > 1) { cells.removeAt(i); focused = 0 } else cells[i] = null
                        lifecycleScope.launch { kotlinx.coroutines.delay(100); runCatching { requesters[0].requestFocus() } }
                    },
                    onAdd = { menuFor = null; cells.add(null); pickFor = cells.lastIndex },
                    onClose = { menuFor = null; runCatching { requesters[i].requestFocus() } })
            }
            pickFor?.let { i ->
                ChannelPicker(all, onPick = { ch ->
                    cells[i] = ch; pickFor = null; focused = i
                    lifecycleScope.launch { kotlinx.coroutines.delay(150); runCatching { requesters[i].requestFocus() } }
                }, onClose = {
                    pickFor = null
                    if (cells.all { it == null }) finish() else runCatching { requesters[i].requestFocus() }
                })
            }
            if (maxConn in 1..3) Text("Your account allows $maxConn screen${if (maxConn == 1) "" else "s"} at once",
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), fontSize = 12.sp, color = TextDim)
        }
    }

    @Composable
    private fun Cell(ch: Channel?, hasSound: Boolean, isFocused: Boolean, modifier: Modifier, onOk: () -> Unit) {
        var status by remember(ch?.id) { mutableStateOf(if (ch == null) "" else "Starting…") }
        Box(modifier
            .border(if (isFocused) 3.dp else 1.dp, if (isFocused) Accent else Color(0xFF2A3140), RoundedCornerShape(6.dp))
            .background(Color(0xFF07090C), RoundedCornerShape(6.dp))
            .onKeyEvent { e ->
                if (e.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                    (e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) { onOk(); true } else false
            }
            .focusable()) {
            if (ch == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+", fontSize = 48.sp, color = if (isFocused) Color.White else TextDim)
                    Text("Add a channel", fontSize = 16.sp, color = if (isFocused) Color.White else TextDim)
                }
            } else {
                val player = remember(ch.id) { newPlayer() }
                DisposableEffect(ch.id) {
                    val l = object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            status = when (state) { Player.STATE_READY -> ""; Player.STATE_BUFFERING -> "Loading…"; else -> status }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            status = "Couldn't play (${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})"
                            // Reconnect after a moment, like single-screen live TV.
                            window.decorView.postDelayed({ runCatching { player.prepare() } }, 2000)
                        }
                    }
                    player.addListener(l)
                    lifecycleScope.launch {
                        val url = linkFor(ch)
                        if (url == null) status = "Couldn't get this channel" else {
                            player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true
                        }
                    }
                    onDispose { player.removeListener(l); player.release() }
                }
                LaunchedEffect(hasSound) { player.volume = if (hasSound) 1f else 0f }
                AndroidView(factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        isFocusable = false
                        this.player = player
                    }
                }, modifier = Modifier.fillMaxSize().padding(3.dp))
                // Channel name, and a sound icon on the one you're hearing.
                Row(Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasSound) Text("🔊 ", fontSize = 13.sp)
                    Text(ch.name, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (status.isNotEmpty()) Text(status, Modifier.align(Alignment.Center), fontSize = 14.sp, color = TextDim)
            }
        }
    }

    @Composable
    private fun CellMenu(ch: Channel?, canAdd: Boolean, onChange: () -> Unit, onFull: () -> Unit, onRemove: () -> Unit,
                         onAdd: () -> Unit, onClose: () -> Unit) {
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { fr.requestFocus() } }
        androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(420.dp).background(Panel, RoundedCornerShape(14.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ch?.name ?: "Screen", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                TvButton("Change channel", onClick = onChange, modifier = Modifier.fillMaxWidth().focusRequester(fr))
                TvButton("Watch full screen", onClick = onFull, modifier = Modifier.fillMaxWidth())
                if (canAdd) TvButton("Add another screen", onClick = onAdd, modifier = Modifier.fillMaxWidth())
                TvButton("Remove this screen", onClick = onRemove, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun ChannelPicker(all: List<Channel>, onPick: (Channel) -> Unit, onClose: () -> Unit) {
        val favs = Library.favourites.mapNotNull { f -> all.firstOrNull { it.id == f.id } ?: f }
        val genres = Session.liveGenres
        var group by remember { mutableStateOf(if (favs.isNotEmpty()) "fav" else "*") }
        val shown = when (group) {
            "fav" -> favs
            "*" -> all
            else -> all.filter { it.genreId == group }
        }
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { fr.requestFocus() } }
        androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(720.dp).height(520.dp).background(Bg, RoundedCornerShape(14.dp)).padding(20.dp)) {
                Text("Pick a channel", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(10.dp))
                val chips = buildList {
                    if (favs.isNotEmpty()) add("fav" to "★ Favourites")
                    add("*" to "All channels")
                    genres.filter { it.id != "*" }.forEach { add(it.id to it.title) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(chips) { (id, title) ->
                        FocusBox(onClick = { group = id }, selected = id == group, shape = RoundedCornerShape(14.dp)) {
                            Text(title, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp,
                                color = if (id == "fav") LogoYellow else Color.White, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(shown, key = { _, c -> c.id }) { i, c ->
                        FocusBox(onClick = { onPick(c) }, shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(fr) else Modifier)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (c.number > 0) Text("${c.number}", fontSize = 13.sp, color = TextDim, modifier = Modifier.width(44.dp))
                                AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(width = 40.dp, height = 26.dp),
                                    contentScale = ContentScale.Fit)
                                Spacer(Modifier.width(10.dp))
                                Text(c.name, fontSize = 15.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
