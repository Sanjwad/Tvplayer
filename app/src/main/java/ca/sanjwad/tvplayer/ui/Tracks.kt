package ca.sanjwad.tvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.sanjwad.tvplayer.stalker.OpenSubs
import kotlinx.coroutines.delay

class TrackOpt(val label: String, val detail: String, val selected: Boolean, val onSelect: () -> Unit)

/** What the OpenSubtitles part of the panel is doing. */
sealed class SubSearch {
    object Idle : SubSearch()
    object Searching : SubSearch()
    class Results(val list: List<OpenSubs.Sub>) : SubSearch()
    class Downloading(val sub: OpenSubs.Sub) : SubSearch()
    class Failed(val message: String) : SubSearch()
}

val SUB_LANGS = listOf("en" to "English", "fr" to "French", "es" to "Spanish", "de" to "German", "it" to "Italian",
    "pt" to "Portuguese", "pl" to "Polish", "nl" to "Dutch", "ar" to "Arabic", "hi" to "Hindi", "ur" to "Urdu",
    "pa" to "Punjabi", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean", "tr" to "Turkish")

/** Side panel over the video: subtitles on/off and which, download from OpenSubtitles, audio track. */
@Composable
fun TracksOverlay(
    subtitles: List<TrackOpt>, subsOff: Boolean, onSubsOff: () -> Unit,
    audio: List<TrackOpt>,
    lang: String, onLang: (String) -> Unit,
    download: SubSearch?, onSearch: () -> Unit, onPick: (OpenSubs.Sub) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val fr = remember { FocusRequester() }
    val dlFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { fr.requestFocus() } }
    // The Search button disappears when results (or an error) arrive: put the cursor on what replaced it.
    LaunchedEffect(download) {
        if (download is SubSearch.Results || download is SubSearch.Failed || download is SubSearch.Idle) {
            delay(120); runCatching { dlFr.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.CenterEnd) {
        LazyColumn(
            Modifier.width(560.dp).fillMaxHeight().background(Color(0xF20E1218)).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Text("Subtitles", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            item { Row1("Off", "", subsOff, Modifier.focusRequester(fr), onSubsOff) }
            items(subtitles) { t -> Row1(t.label, t.detail, t.selected && !subsOff, Modifier, t.onSelect) }
            if (subtitles.isEmpty()) item { Text("This video has no subtitles of its own.", fontSize = 13.sp, color = TextDim) }

            if (download != null) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Text("Download subtitles", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("From OpenSubtitles.com", fontSize = 12.sp, color = TextDim)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(SUB_LANGS) { (code, name) ->
                            FocusBox(onClick = { onLang(code) }, selected = code == lang, shape = RoundedCornerShape(14.dp)) {
                                Text(name, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp, color = Color.White,
                                    fontWeight = if (code == lang) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                when (download) {
                    is SubSearch.Idle -> item { TvButton("Search for ${SUB_LANGS.firstOrNull { it.first == lang }?.second ?: lang} subtitles", onClick = onSearch,
                        modifier = Modifier.focusRequester(dlFr)) }
                    is SubSearch.Searching -> item { Text("Searching…", fontSize = 14.sp, color = TextDim) }
                    is SubSearch.Downloading -> item { Text("Downloading “${download.sub.release.take(60)}”…", fontSize = 14.sp, color = TextDim) }
                    is SubSearch.Failed -> item {
                        Text(download.message, fontSize = 14.sp, color = ErrorRed)
                        Spacer(Modifier.height(6.dp))
                        TvButton("Try again", onClick = onSearch, modifier = Modifier.focusRequester(dlFr))
                    }
                    is SubSearch.Results -> {
                        if (download.list.isEmpty()) item { Text("No subtitles found in this language.", fontSize = 14.sp, color = TextDim) }
                        itemsIndexed(download.list) { i, s ->
                            Row1(s.release.ifBlank { "Subtitle ${s.fileId}" },
                                listOfNotNull("${s.downloads} downloads", if (s.hearingImpaired) "SDH" else null,
                                    if (s.machine) "machine-translated" else null).joinToString(" · "),
                                false, if (i == 0) Modifier.focusRequester(dlFr) else Modifier) { onPick(s) }
                        }
                    }
                }
            }

            if (audio.size > 1) {
                item { Spacer(Modifier.height(10.dp)); Text("Audio", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                items(audio) { t -> Row1(t.label, t.detail, t.selected, Modifier, t.onSelect) }
            }
        }
    }
}

@Composable
private fun Row1(label: String, detail: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, selected = selected, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) "●" else "○", fontSize = 13.sp, color = if (selected) Accent else TextDim, modifier = Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = TextDim, maxLines = 1)
            }
        }
    }
}
