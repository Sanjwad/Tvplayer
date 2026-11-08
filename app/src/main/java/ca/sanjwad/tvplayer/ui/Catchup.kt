package ca.sanjwad.tvplayer.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.sanjwad.tvplayer.PlayerActivity
import ca.sanjwad.tvplayer.Session
import ca.sanjwad.tvplayer.stalker.Channel
import ca.sanjwad.tvplayer.stalker.Programme
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object Catchup {
    private val dayFmt = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())

    fun dayLabel(ms: Long): String {
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = ms
        val d = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        return when (d) { today -> "Today"; yesterday -> "Yesterday"; else -> dayFmt.format(java.util.Date(ms)) }
    }

    /** Plays a past (or the current, from its start) programme on a catch-up channel. */
    fun play(ctx: Context, channel: Channel, p: Programme) {
        val urls = Session.client?.catchupUrls(channel, p.start, p.end).orEmpty()
        if (urls.isEmpty()) {
            android.widget.Toast.makeText(ctx, "Catch-up isn't available for this channel.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java)
            .putExtra("url", urls.first())
            .putExtra("fallback_urls", ArrayList(urls.drop(1)))
            .putExtra("catchup", "1")
            .putExtra("title", p.title.ifBlank { channel.name })
            .putExtra("subtitle", "${channel.name}  ·  ${dayLabel(p.start)} ${clockTime(p.start)}–${clockTime(p.end)}")
            .putExtra("plot", p.description)
            .putExtra("poster", channel.logo))
    }
}

/** A catch-up channel's past programmes, day by day. Picking one plays it from the start. */
@Composable
fun CatchupScreen(channel: Channel, onPlay: (Programme) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var progs by remember(channel.id) { mutableStateOf<List<Programme>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(channel.id) {
        val r = withContext(Dispatchers.IO) { runCatching { Session.client?.catchupProgrammes(channel).orEmpty() } }
        r.onSuccess { progs = it; if (it.isEmpty()) error = "No catch-up programmes listed for this channel." }
            .onFailure { progs = emptyList(); error = it.message }
    }
    val list = progs
    val days = list.orEmpty().groupBy { Catchup.dayLabel(it.start) }.toList().reversed() // newest day first
    var day by remember { mutableStateOf<String?>(null) }
    val selected = day ?: days.firstOrNull()?.first
    val dayFr = remember { FocusRequester() }
    val listFr = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val shown = days.firstOrNull { it.first == selected }?.second.orEmpty()

    LaunchedEffect(list) {
        if (!list.isNullOrEmpty()) {
            // Start on the most recent programme of today.
            delay(150)
            if (shown.isNotEmpty()) listState.scrollToItem((shown.size - 1).coerceAtLeast(0))
            runCatching { listFr.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(width = 64.dp, height = 40.dp),
                    contentScale = ContentScale.Fit)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Catch-up", fontSize = 14.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                    Text(channel.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Up to ${channel.archiveDays} day${if (channel.archiveDays == 1) "" else "s"} back", fontSize = 13.sp, color = TextDim)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (list == null) { Text("Loading…", color = TextDim, fontSize = 16.sp); return@Column }
            if (list.isEmpty()) { Text(error ?: "Nothing to catch up on.", color = TextDim, fontSize = 16.sp); return@Column }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(days) { i, (label, _) ->
                    FocusBox(onClick = { day = label }, selected = label == selected, shape = RoundedCornerShape(16.dp),
                        modifier = if (i == 0) Modifier.focusRequester(dayFr) else Modifier) {
                        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 7.dp), fontSize = 14.sp, color = Color.White,
                            fontWeight = if (label == selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val now = System.currentTimeMillis()
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(shown, key = { _, p -> p.start }) { i, p ->
                    val live = now in p.start until p.end
                    val ok = p.archive
                    FocusBox(onClick = { if (ok) onPlay(p) }, selected = live, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().then(if (i == shown.lastIndex) Modifier.focusRequester(listFr) else Modifier)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${clockTime(p.start)} – ${clockTime(p.end)}", fontSize = 14.sp, color = if (ok) Accent else TextDim,
                                modifier = Modifier.width(130.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, fontSize = 15.sp, color = if (ok) Color.White else TextDim, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.description.isNotBlank()) Text(p.description, fontSize = 12.sp, color = TextDim, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            Text(when { live -> "On now · start over"; ok -> "▶ Watch"; else -> "Not saved" },
                                fontSize = 13.sp, color = if (live) LogoYellow else if (ok) Color.White else TextDim)
                        }
                    }
                }
            }
        }
    }
}

/** Shown when you pause a live channel that has catch-up: resume, start the show over, or browse catch-up. */
@Composable
fun LivePauseOverlay(
    channel: Channel, now: Programme?, onResume: () -> Unit, onStartOver: (() -> Unit)?, onCatchup: () -> Unit, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xEE0B0E13), Color(0xAA0B0E13), Color(0x220B0E13))))) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(600.dp)) {
                Text("Paused  ·  Live TV", fontSize = 14.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(now?.title ?: channel.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                Text(listOfNotNull(channel.name, now?.let { "${clockTime(it.start)} – ${clockTime(it.end)}" }).joinToString("  ·  "),
                    fontSize = 15.sp, color = TextDim)
                if (!now?.description.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(now!!.description, fontSize = 14.sp, color = Color(0xFFD5DAE1), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("▶  Back to live", onClick = onResume, modifier = Modifier.focusRequester(fr))
                    if (onStartOver != null) TvButton("↺  Start over", onClick = onStartOver)
                    TvButton("Catch-up", onClick = onCatchup)
                }
            }
        }
    }
}
