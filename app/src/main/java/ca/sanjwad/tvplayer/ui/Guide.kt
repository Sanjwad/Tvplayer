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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun GuideGrid(channels: List<Channel>, onPlay: (index: Int) -> Unit) {
    val version = Session.guideVersion
    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / SLOT * SLOT) }
    var focused by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    // Keep the status line current while the guide downloads.
    val status by produceState(Session.client?.guideStatus.orEmpty(), version) {
        while (true) { value = Session.client?.guideStatus.orEmpty(); delay(2000) }
    }
    val windowEnd = windowStart + SPAN

    Column(Modifier.fillMaxSize()) {
        // Details of the highlighted programme, like TiVimate's top panel.
        Column(Modifier.fillMaxWidth().height(78.dp)) {
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
                    Text(p!!.description, fontSize = 13.sp, color = Color(0xFFD5DAE1), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
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
                                    Text(ch.name, fontSize = 12.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            ProgrammeRow(progs, windowStart, windowEnd, perMin, timeW,
                                onFocus = { p -> focused = ch to p }, onClick = { onPlay(index) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeRow(
    progs: List<Programme>?, from: Long, to: Long, perMin: Dp, totalW: Dp,
    onFocus: (Programme?) -> Unit, onClick: () -> Unit,
) {
    val now = System.currentTimeMillis()
    if (progs.isNullOrEmpty()) {
        FocusBox(onClick = onClick, modifier = Modifier.width(totalW).height(48.dp), shape = RoundedCornerShape(6.dp),
            onFocus = { onFocus(null) }) {
            Text(if (progs == null) "…" else "No guide", Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp),
                fontSize = 12.sp, color = TextDim)
        }
        return
    }
    Row {
        var cursor = from
        for (p in progs) {
            val s = maxOf(p.start, from); val e = minOf(p.end, to)
            if (e <= s) continue
            if (s > cursor) Spacer(Modifier.width(perMin * ((s - cursor) / MIN).toFloat()))
            val w = perMin * ((e - s) / MIN).toFloat()
            val live = now in p.start until p.end
            FocusBox(onClick = onClick, modifier = Modifier.width(maxOf(w - 2.dp, 6.dp)).height(48.dp),
                selected = live, shape = RoundedCornerShape(6.dp), onFocus = { onFocus(p) }) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(p.title, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(clockTime(p.start), fontSize = 10.sp, color = TextDim, maxLines = 1)
                }
            }
            Spacer(Modifier.width(2.dp))
            cursor = e
        }
    }
}
