package ca.sanjwad.tvplayer.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.sanjwad.tvplayer.PlayerActivity
import ca.sanjwad.tvplayer.Prefs
import ca.sanjwad.tvplayer.Session
import ca.sanjwad.tvplayer.stalker.Channel
import ca.sanjwad.tvplayer.stalker.Episode
import ca.sanjwad.tvplayer.stalker.Genre
import ca.sanjwad.tvplayer.stalker.Season
import ca.sanjwad.tvplayer.stalker.IptvSource
import ca.sanjwad.tvplayer.stalker.StalkerClient
import ca.sanjwad.tvplayer.stalker.XtreamClient
import ca.sanjwad.tvplayer.stalker.VodItem
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- state kept while moving between screens ----------------

class LiveState {
    var genres by mutableStateOf<List<Genre>>(emptyList())
    var all by mutableStateOf<List<Channel>>(emptyList())
    var genre by mutableStateOf("*")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loaded = false
    fun reset() { genres = emptyList(); all = emptyList(); genre = "*"; error = null; loaded = false }
}

/** [kind] is the tab ("movies" or "series"); [type] is the portal section actually used for it. */
class VodState(val kind: String) {
    var type = "vod"
    var categories by mutableStateOf<List<Genre>>(emptyList())
    var category by mutableStateOf<String?>(null)
    var items by mutableStateOf<List<VodItem>>(emptyList())
    var page by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var catsLoaded = false
    var job: Job? = null
    fun reset() {
        categories = emptyList(); category = null; items = emptyList(); page = 0; total = 0
        error = null; catsLoaded = false; job?.cancel(); job = null; loading = false
    }
}

private suspend fun focusSoon(fr: FocusRequester) {
    delay(150)
    runCatching { fr.requestFocus() }
}

// ---------------- app shell ----------------

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var screen by remember { mutableStateOf(if (!prefs.isConfigured) "setup" else "connect") }
    var detail by remember { mutableStateOf<Pair<String, VodItem>?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    val live = remember { LiveState() }
    val movies = remember { VodState("movies") }
    val series = remember { VodState("series") }

    Box(Modifier.fillMaxSize().background(Bg)) {
        val d = detail
        when {
            screen == "setup" -> SetupScreen(prefs, hasSaved = prefs.isConfigured,
                onCancel = { screen = if (Session.client != null) "home" else "connect" }) {
                live.reset(); movies.reset(); series.reset(); Session.client = null
                screen = "connect"
            }
            screen == "connect" -> ConnectScreen(prefs, onDone = { screen = "home" }, onEdit = { screen = "setup" })
            d != null -> DetailScreen(d.first, d.second) { detail = null }
            else -> HomeScreen(
                tab = tab, onTab = { tab = it }, live = live, movies = movies, series = series,
                onOpen = { type, item -> detail = type to item },
                onSettings = { screen = "setup" },
            )
        }
    }
}

// ---------------- setup & connect ----------------

@Composable
fun SetupScreen(prefs: Prefs, hasSaved: Boolean, onCancel: () -> Unit, onSaved: () -> Unit) {
    var mode by remember { mutableStateOf(prefs.mode) }
    var portal by remember { mutableStateOf(prefs.portal) }
    var mac by remember { mutableStateOf(prefs.mac.ifBlank { "00:1A:79:" }) }
    var serial by remember { mutableStateOf(prefs.serial) }
    var dev1 by remember { mutableStateOf(prefs.deviceId) }
    var dev2 by remember { mutableStateOf(prefs.deviceId2) }
    var xtServer by remember { mutableStateOf(prefs.xtServer) }
    var xtUser by remember { mutableStateOf(prefs.xtUser) }
    var xtPass by remember { mutableStateOf(prefs.xtPass) }
    var err by remember { mutableStateOf<String?>(null) }
    if (hasSaved) BackHandler(onBack = onCancel)
    val w = Modifier.width(560.dp)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sanj TV", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Accent)
        Spacer(Modifier.height(8.dp))
        Text("How does your provider log you in?", fontSize = 18.sp, color = TextDim)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusBox(onClick = { mode = "xtream"; err = null }, selected = mode == "xtream", shape = RoundedCornerShape(20.dp)) {
                Text("Xtream (username & password)", Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 17.sp, color = Color.White)
            }
            FocusBox(onClick = { mode = "portal"; err = null }, selected = mode == "portal", shape = RoundedCornerShape(20.dp)) {
                Text("Portal (MAC address)", Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 17.sp, color = Color.White)
            }
        }
        Spacer(Modifier.height(20.dp))

        if (mode == "xtream") {
            OutlinedTextField(
                value = xtServer, onValueChange = { xtServer = it },
                label = { Text("Server (e.g. http://xt.example.com) or full M3U link") },
                singleLine = true, modifier = w,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = xtUser, onValueChange = { xtUser = it.trim() },
                label = { Text("Username") }, singleLine = true, modifier = w,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = xtPass, onValueChange = { xtPass = it.trim() },
                label = { Text("Password") }, singleLine = true, modifier = w,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        } else {
            OutlinedTextField(
                value = portal, onValueChange = { portal = it },
                label = { Text("Portal URL (e.g. http://example.com:8080/c/)") },
                singleLine = true, modifier = w,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = mac, onValueChange = { mac = it.uppercase() },
                label = { Text("MAC address") }, singleLine = true, modifier = w,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = serial, onValueChange = { serial = it },
                label = { Text("Serial number (optional, copy from TiVimate)") },
                singleLine = true, modifier = w,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            Spacer(Modifier.height(12.dp))
            Row(w, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = dev1, onValueChange = { dev1 = it },
                    label = { Text("Device ID (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = dev2, onValueChange = { dev2 = it },
                    label = { Text("Device ID 2 (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        err?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = ErrorRed, fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
        TvButton("Save and connect", onClick = {
            if (mode == "xtream") {
                var srv = xtServer.trim(); var u = xtUser; var pw = xtPass
                XtreamClient.parseM3u(srv)?.let { (a, b, c) -> srv = a; u = b; pw = c; xtServer = a; xtUser = b; xtPass = c }
                when {
                    srv.isBlank() -> err = "Enter the server address."
                    u.isBlank() || pw.isBlank() -> err = "Enter the username and password."
                    else -> {
                        prefs.mode = "xtream"; prefs.xtServer = srv; prefs.xtUser = u; prefs.xtPass = pw
                        onSaved()
                    }
                }
            } else {
                val m = mac.trim()
                when {
                    portal.isBlank() -> err = "Enter the portal URL."
                    !Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(m) -> err = "MAC should look like 00:1A:79:12:34:56"
                    else -> {
                        prefs.mode = "portal"; prefs.portal = portal.trim(); prefs.mac = m
                        prefs.serial = serial.trim(); prefs.deviceId = dev1.trim(); prefs.deviceId2 = dev2.trim()
                        onSaved()
                    }
                }
            }
        })
    }
}

@Composable
fun ConnectScreen(prefs: Prefs, onDone: () -> Unit, onEdit: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        error = null
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c: IptvSource = if (prefs.mode == "xtream") XtreamClient(prefs.xtServer, prefs.xtUser, prefs.xtPass)
                else StalkerClient(prefs.portal, prefs.mac, prefs.serial, prefs.deviceId, prefs.deviceId2)
                c.connect()
                c
            }
        }
        r.onSuccess { Session.client = it; onDone() }
            .onFailure { error = it.message ?: "Couldn't connect." }
    }
    LaunchedEffect(error) { if (error != null) focusSoon(fr) }

    val e = error
    if (e == null) {
        CenterMessage("Connecting to your provider…", loading = true)
    } else {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Couldn't connect", fontSize = 26.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(e, fontSize = 15.sp, color = TextDim, modifier = Modifier.width(760.dp))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Try again", onClick = { attempt++ }, modifier = Modifier.focusRequester(fr))
                TvButton("Edit details", onClick = onEdit)
            }
        }
    }
}

// ---------------- home ----------------

@Composable
fun HomeScreen(
    tab: Int, onTab: (Int) -> Unit,
    live: LiveState, movies: VodState, series: VodState,
    onOpen: (String, VodItem) -> Unit, onSettings: () -> Unit,
) {
    val tabs = listOf("Live TV", "Movies", "Series", "Settings")
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(fr) }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sanj TV", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Accent)
            Spacer(Modifier.width(28.dp))
            tabs.forEachIndexed { i, t ->
                FocusBox(
                    onClick = { if (i == 3) onSettings() else onTab(i) },
                    modifier = Modifier.padding(end = 8.dp)
                        .then(if (i == tab) Modifier.focusRequester(fr) else Modifier),
                    selected = i == tab,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(t, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 18.sp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        when (tab) {
            0 -> LiveScreen(live)
            1 -> VodScreen(movies) { onOpen(movies.type, it) }
            else -> VodScreen(series) { onOpen(series.type, it) }
        }
    }
}

// ---------------- live TV ----------------

@Composable
fun LiveScreen(s: LiveState) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (!s.loaded && !s.loading) {
            s.loading = true; s.error = null
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val c = Session.client ?: error("Not connected")
                    c.liveGenres() to c.liveChannels()
                }
            }
            r.onSuccess { (g, ch) ->
                s.genres = listOf(Genre("*", "All channels")) + g.filter { it.id != "*" }
                s.all = ch; s.loaded = true
            }.onFailure { s.error = it.message }
            s.loading = false
        }
    }

    val err = s.error
    when {
        s.loading -> CenterMessage("Loading channels…", loading = true)
        err != null -> CenterMessage("Couldn't load channels", err)
        else -> {
            val shown = remember(s.genre, s.all) {
                if (s.genre == "*") s.all else s.all.filter { it.genreId == s.genre }
            }
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(s.genres) { g ->
                        FocusBox(onClick = { s.genre = g.id }, modifier = Modifier.fillMaxWidth(), selected = g.id == s.genre) {
                            Text(g.title, Modifier.padding(12.dp), fontSize = 17.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (shown.isEmpty()) item { Text("No channels in this group", color = TextDim, fontSize = 17.sp) }
                    itemsIndexed(shown) { index, c ->
                        FocusBox(onClick = {
                            Session.channels = shown
                            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("live_index", index))
                        }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (c.number > 0) c.number.toString() else "", Modifier.width(56.dp), color = TextDim, fontSize = 16.sp)
                                AsyncImage(model = c.logo, contentDescription = null,
                                    modifier = Modifier.size(width = 56.dp, height = 34.dp), contentScale = ContentScale.Fit)
                                Spacer(Modifier.width(14.dp))
                                Text(c.name, fontSize = 18.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- movies & series ----------------

@Composable
fun VodScreen(s: VodState, onOpen: (VodItem) -> Unit) {
    val scope = rememberCoroutineScope()

    fun loadPage(reset: Boolean) {
        val cat = s.category ?: return
        if (!reset && (s.loading || (s.total > 0 && s.items.size >= s.total))) return
        s.job?.cancel()
        val p = if (reset) 1 else s.page + 1
        if (reset) { s.items = emptyList(); s.total = 0 }
        s.loading = true; s.error = null
        s.job = scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { (Session.client ?: error("Not connected")).vodPage(s.type, cat, p) }
            }
            if (!isActive) return@launch
            if (s.category == cat) {
                r.onSuccess { pg ->
                    s.items = if (reset) pg.items else s.items + pg.items
                    s.page = p; s.total = if (pg.items.isEmpty()) s.items.size else pg.totalItems
                }.onFailure { s.error = it.message }
            }
            s.loading = false
        }
    }

    LaunchedEffect(s.kind) {
        if (!s.catsLoaded) {
            val r = withContext(Dispatchers.IO) {
                runCatching { (Session.client ?: error("Not connected")).browseCategories(s.kind) }
            }
            r.exceptionOrNull()?.let { s.error = it.message }
            r.getOrNull()?.let { (type, cats) ->
                s.type = type
                s.categories = cats; s.catsLoaded = true
                if (s.category == null && cats.isNotEmpty()) { s.category = cats.first().id; loadPage(true) }
            }
        }
    }

    val label = s.kind
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(s.categories) { g ->
                FocusBox(onClick = { if (s.category != g.id) { s.category = g.id; loadPage(true) } },
                    modifier = Modifier.fillMaxWidth(), selected = g.id == s.category) {
                    Text(g.title, Modifier.padding(12.dp), fontSize = 17.sp, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val err = s.error
            when {
                err != null && s.items.isEmpty() -> CenterMessage("Couldn't load $label", err)
                s.catsLoaded && s.categories.isEmpty() -> CenterMessage("No $label section on this portal",
                    null)
                s.items.isEmpty() && (s.loading || !s.catsLoaded) -> CenterMessage("Loading $label…", loading = true)
                s.items.isEmpty() -> CenterMessage("Nothing in this category")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(s.items) { index, item ->
                        if (index >= s.items.size - 8) LaunchedEffect(index) { loadPage(false) }
                        PosterCard(item) { onOpen(item) }
                    }
                }
            }
        }
    }
}

@Composable
fun PosterCard(item: VodItem, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Bg)) {
                Text(item.name, Modifier.align(Alignment.Center).padding(8.dp), color = TextDim, fontSize = 14.sp, maxLines = 4)
                AsyncImage(model = item.poster, contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(6.dp))
            Text(item.name, fontSize = 14.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- details ----------------

@Composable
fun DetailScreen(type: String, start: VodItem, onBack: () -> Unit) {
    var item by remember { mutableStateOf(start) }
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var seasons by remember { mutableStateOf<List<Season>?>(null) }
    var seasonIdx by remember { mutableIntStateOf(0) }
    var episodes by remember { mutableStateOf<List<Episode>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }

    fun play(cmd: String, series: String, title: String, movieId: String? = null) {
        msg = "Starting…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { (Session.client ?: error("Not connected")).createLink("vod", cmd, series, movieId) }
            }
            r.onSuccess {
                msg = null
                ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("url", it).putExtra("title", title))
            }.onFailure { msg = it.message ?: "Couldn't start playback." }
        }
    }

    LaunchedEffect(item.id) {
        if (item.isSeries) {
            val r = withContext(Dispatchers.IO) {
                runCatching { (Session.client ?: error("Not connected")).seasons(type, item) }
            }
            r.onSuccess { seasons = it; if (it.isEmpty()) msg = "No seasons found for this show." }
                .onFailure { msg = it.message; seasons = emptyList() }
        } else {
            val current = item
            item = withContext(Dispatchers.IO) {
                runCatching { (Session.client ?: error("Not connected")).movieInfo(current) }.getOrDefault(current)
            }
        }
        focusSoon(fr)
    }

    LaunchedEffect(seasons, seasonIdx) {
        val s = seasons?.getOrNull(seasonIdx) ?: return@LaunchedEffect
        episodes = null
        val pre = s.episodes
        if (pre != null) { episodes = pre; return@LaunchedEffect }
        val r = withContext(Dispatchers.IO) {
            runCatching { (Session.client ?: error("Not connected")).episodes(type, item, s) }
        }
        r.onSuccess { episodes = it }.onFailure { msg = it.message; episodes = emptyList() }
    }

    Row(Modifier.fillMaxSize().padding(40.dp)) {
        Box(Modifier.width(260.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Panel)) {
            AsyncImage(model = item.poster, contentDescription = item.name,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
            val meta = listOfNotNull(item.year.ifBlank { null }, item.rating.ifBlank { null }?.let { "★ $it" })
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(meta.joinToString("  ·  "), fontSize = 17.sp, color = TextDim)
            }
            if (item.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(item.description, fontSize = 17.sp, color = Color(0xFFD5DAE1), maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            msg?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 14.sp, color = if (it == "Starting…") Accent else ErrorRed)
            }
            Spacer(Modifier.height(20.dp))

            if (!item.isSeries) {
                TvButton("▶  Play", onClick = { play(item.cmd, "", item.name, item.id) }, modifier = Modifier.focusRequester(fr))
            } else {
                val ss = seasons
                if (ss == null) {
                    Text("Loading seasons…", color = TextDim, fontSize = 17.sp)
                    Box(Modifier.size(1.dp).focusRequester(fr))
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(ss) { i, s ->
                            FocusBox(onClick = { seasonIdx = i }, selected = i == seasonIdx,
                                shape = RoundedCornerShape(20.dp),
                                modifier = if (i == 0) Modifier.focusRequester(fr) else Modifier) {
                                Text(s.name.ifBlank { "Season ${i + 1}" },
                                    Modifier.padding(horizontal = 18.dp, vertical = 8.dp), fontSize = 16.sp, color = Color.White)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    val eps = episodes
                    when {
                        eps == null -> Text("Loading episodes…", color = TextDim, fontSize = 17.sp)
                        eps.isEmpty() -> Text("No episodes found.", color = TextDim, fontSize = 17.sp)
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(eps) { ep ->
                                FocusBox(onClick = { play(ep.cmd, ep.seriesParam, "${item.name} ${ep.title}") },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                        Text(if (ep.number.isNotBlank()) "E${ep.number}" else "",
                                            Modifier.width(56.dp), color = TextDim, fontSize = 17.sp)
                                        Column {
                                            Text(ep.title, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (ep.plot.isNotBlank()) {
                                                Spacer(Modifier.height(2.dp))
                                                Text(ep.plot, color = TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
