package ca.sanjwad.tvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.sanjwad.tvplayer.Session
import ca.sanjwad.tvplayer.stalker.Channel
import ca.sanjwad.tvplayer.stalker.Programme
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MIN = 60_000L
private const val SLOT = 30 * MIN
private const val SPAN = 150 * MIN // 2½ hours on screen

/** TiVimate-style programme grid: channels down the side, programmes across time. */
@Composable
fun GuideGrid(channels: List<Channel>, onCatchup: ((Channel, Programme) -> Unit)? = null, onPlay: (index: Int) -> Unit) {
    val version = Session.guideVersion
    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / SLOT * SLOT) }
    var focused by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    // Keep the status line current while the guide downloads.
    val status by produceState(Session.client?.guideStatus.orEmpty(), version) {
        while (true) { value = Session.client?.guideStatus.orEmpty(); delay(2000) }
    }
    val windowEnd = windowStart + SPAN
    // Moving past either edge of the grid scrolls time by an hour (like TiVimate) and the cursor lands
    // on the programme at the right time on the same channel.
    var focusAt by remember { mutableStateOf<Pair<String, Long>?>(null) }

    Column(Modifier.fillMaxSize()) {
        // Details of the highlighted programme, like TiVimate's top panel, with a live preview of the
        // channel in the corner once the cursor rests on it.
        var previewOff by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(if (previewOff) 78.dp else 132.dp)) {
          Column(Modifier.weight(1f).fillMaxHeight()) {
            val f = focused
            val p = f?.second
            if (f == null) {
                Text(if (status.isNotEmpty()) status else "Move onto a programme to see its details.", fontSize = 14.sp, color = TextDim)
            } else {
                Text(p?.title ?: f.first.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (p != null) "${clockTime(p.start)} – ${clockTime(p.end)}  ·  " else "") + f.first.name,
                    fontSize = 13.sp, color = Accent,
                )
                if (!p?.description.isNullOrBlank()) {
                    Text(p!!.description, fontSize = 13.sp, color = Color(0xFFD5DAE1), maxLines = if (previewOff) 2 else 4, overflow = TextOverflow.Ellipsis)
                }
            }
          }
          if (!previewOff) {
            Spacer(Modifier.width(12.dp))
            LivePreview(focused?.first, Modifier.height(124.dp).aspectRatio(16f / 9f)) { previewOff = true }
          }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val chanW = 150.dp
            val timeW = maxWidth - chanW - 8.dp
            val perMin: Dp = timeW / (SPAN / MIN).toFloat()

            Column(Modifier.fillMaxSize()) {
                // Time ruler with earlier/later controls.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
                    Row(Modifier.width(chanW), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FocusBox(onClick = { windowStart -= SLOT * 2 }, shape = RoundedCornerShape(12.dp)) {
                            Text("◀", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 13.sp, color = Color.White)
                        }
                        FocusBox(onClick = { windowStart = System.currentTimeMillis() / SLOT * SLOT }, shape = RoundedCornerShape(12.dp)) {
                            Text("Now", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp, color = Color.White)
                        }
                        FocusBox(onClick = { windowStart += SLOT * 2 }, shape = RoundedCornerShape(12.dp)) {
                            Text("▶", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 13.sp, color = Color.White)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Row {
                        var t = windowStart
                        while (t < windowEnd) {
                            Text(clockTime(t), Modifier.width(perMin * (SLOT / MIN).toFloat()), fontSize = 12.sp, color = TextDim)
                            t += SLOT
                        }
                    }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { index, ch ->
                        val progs by produceState<List<Programme>?>(null, ch.id, windowStart, version) {
                            value = withContext(Dispatchers.IO) {
                                val c = Session.client
                                c?.programmes(ch, windowStart, windowEnd)?.takeIf { it.isNotEmpty() }
                                    ?: runCatching { c?.nowNext(ch) }.getOrNull().orEmpty()
                                        .filter { it.end > windowStart && it.start < windowEnd }
                            }
                        }
                        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            FocusBox(onClick = { onPlay(index) }, modifier = Modifier.width(chanW).height(48.dp),
                                shape = RoundedCornerShape(6.dp), onFocus = { focused = ch to null }) {
                                Row(Modifier.padding(horizontal = 8.dp).fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(model = ch.logo, contentDescription = null, modifier = Modifier.size(width = 34.dp, height = 24.dp),
                                        contentScale = ContentScale.Fit)
                                    Spacer(Modifier.width(6.dp))
                                    Text(ch.name, fontSize = 12.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                    if (ch.archiveDays > 0) Text("↺", fontSize = 13.sp, color = LogoYellow)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            ProgrammeRow(progs, windowStart, windowEnd, perMin, timeW, catchup = ch.archiveDays > 0,
                                focusAt = focusAt?.takeIf { it.first == ch.id }?.second,
                                onFocusedAt = { focusAt = null },
                                onPage = { dir, t ->
                                    val nowSlot = System.currentTimeMillis() / SLOT * SLOT
                                    // Earlier than now only on channels with catch-up, and only as far back as they keep.
                                    if (dir < 0 && windowStart - SLOT * 2 < nowSlot - ch.archiveDays * 24 * 60 * MIN) false
                                    else { windowStart += dir * SLOT * 2; focusAt = ch.id to t; true }
                                },
                                onFocus = { p -> focused = ch to p }, onClick = { p ->
                                    // Finished programme on a catch-up channel: watch it from the start.
                                    if (p != null && onCatchup != null && ch.archiveDays > 0 && p.end <= System.currentTimeMillis() &&
                                        p.start >= System.currentTimeMillis() - ch.archiveDays * 24 * 60 * MIN) onCatchup(ch, p)
                                    else onPlay(index)
                                })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeRow(
    progs: List<Programme>?, from: Long, to: Long, perMin: Dp, totalW: Dp, catchup: Boolean,
    onFocus: (Programme?) -> Unit, onClick: (Programme?) -> Unit,
    /** Put the cursor on the programme showing at this time (after the grid moved an hour). */
    focusAt: Long? = null, onFocusedAt: () -> Unit = {},
    /** Past the right (dir 1) or left (dir -1) edge: move the grid; returns false if it can't go further. */
    onPage: (dir: Int, at: Long) -> Boolean = { _, _ -> false },
) {
    val now = System.currentTimeMillis()
    if (progs.isNullOrEmpty()) {
        FocusBox(onClick = { onClick(null) }, modifier = Modifier.width(totalW).height(48.dp), shape = RoundedCornerShape(6.dp),
            onFocus = { onFocus(null) }) {
            Text(if (progs == null) "…" else "No guide", Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp),
                fontSize = 12.sp, color = TextDim)
        }
        return
    }
    val shown = progs.filter { minOf(it.end, to) > maxOf(it.start, from) }
    val target = focusAt?.let { t -> shown.firstOrNull { t >= it.start && t < it.end } ?: shown.lastOrNull { it.start <= t } ?: shown.firstOrNull() }
    val targetFr = remember { FocusRequester() }
    if (target != null) LaunchedEffect(focusAt, target) {
        delay(30)
        // Keep trying as the new hour's listings arrive, until the right programme has the cursor.
        if (runCatching { targetFr.requestFocus() }.isSuccess && focusAt!! >= target.start && focusAt < target.end) onFocusedAt()
    }
    Row {
        var cursor = from
        shown.forEachIndexed { i, p ->
            val s = maxOf(p.start, from); val e = minOf(p.end, to)
            if (s > cursor) Spacer(Modifier.width(perMin * ((s - cursor) / MIN).toFloat()))
            val w = perMin * ((e - s) / MIN).toFloat()
            val live = now in p.start until p.end
            val edgeKeys = Modifier.onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                if (k.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (k.keyCode) {
                    // Last programme on screen: Right moves the guide an hour later, onto what's on next.
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> i == shown.lastIndex && onPage(1, minOf(p.end, to))
                    // First programme, with earlier times available: Left moves an hour earlier.
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> i == 0 && onPage(-1, from - 1)
                    else -> false
                }
            }
            FocusBox(onClick = { onClick(p) }, modifier = Modifier.width(maxOf(w - 2.dp, 6.dp)).height(48.dp).then(edgeKeys)
                .then(if (p == target) Modifier.focusRequester(targetFr) else Modifier),
                selected = live, shape = RoundedCornerShape(6.dp), onFocus = { onFocus(p) }) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(p.title, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(clockTime(p.start) + if (catchup && p.end <= now) "  ↺ catch-up" else "", fontSize = 10.sp,
                        color = if (catchup && p.end <= now) LogoYellow else TextDim, maxLines = 1)
                }
            }
            Spacer(Modifier.width(2.dp))
            cursor = e
        }
    }
}

/**
 * A small live picture of [channel] for the guide: starts after the cursor has rested on a channel for a
 * moment, stops when it moves on, and lets go of the stream whenever the screen is left (so the full
 * player can use the connection). [onUnavailable]: the account can only play one stream at a time.
 */
@Composable
private fun LivePreview(channel: Channel?, modifier: Modifier, onUnavailable: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) visible = false
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) visible = true
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val player = remember {
        val c = Session.client
        val serving = c?.let { ca.sanjwad.tvplayer.stalker.mainOf(it) }
        val headers = c?.streamHeaders().orEmpty()
        val host = c?.portalHost
        val okhttp = okhttp3.OkHttpClient.Builder()
            .apply { if (serving is ca.sanjwad.tvplayer.stalker.StalkerClient) dns(ca.sanjwad.tvplayer.stalker.Ipv4First) }
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val b = chain.request().newBuilder().header("User-Agent", ca.sanjwad.tvplayer.stalker.Net.UA)
                if (chain.request().url.host == host) headers.forEach { (k, v) -> b.header(k, v) }
                chain.proceed(b.build())
            }.build()
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okhttp)))
            .build().apply { volume = 0.6f }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { player.release() } }
    var state by remember { mutableStateOf("") }
    LaunchedEffect(channel?.id, visible) {
        player.stop(); player.clearMediaItems()
        if (channel == null || !visible) { state = ""; return@LaunchedEffect }
        // A one-stream account can't preview and play at once.
        val xt = Session.client?.let { ca.sanjwad.tvplayer.stalker.mainOf(it) } as? ca.sanjwad.tvplayer.stalker.XtreamClient
        if (xt?.maxConnections == 1) { onUnavailable(); return@LaunchedEffect }
        state = "…"
        delay(900) // only once the cursor rests here
        val url = withContext(Dispatchers.IO) { runCatching { Session.client?.createLink("itv", channel.cmd, "", null) }.getOrNull() }
        if (url == null) { state = "No preview"; return@LaunchedEffect }
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
        player.prepare(); player.playWhenReady = true
        state = ""
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
            androidx.media3.ui.PlayerView(c).apply { useController = false; this.player = player; isFocusable = false }
        }, modifier = Modifier.fillMaxSize())
        if (state.isNotEmpty()) Text(state, Modifier.align(Alignment.Center), fontSize = 13.sp, color = TextDim)
    }
}
