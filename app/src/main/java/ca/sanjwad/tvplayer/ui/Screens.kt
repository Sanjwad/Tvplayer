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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.sanjwad.tvplayer.Library
import ca.sanjwad.tvplayer.PlayerActivity
import ca.sanjwad.tvplayer.Prefs
import ca.sanjwad.tvplayer.Session
import ca.sanjwad.tvplayer.stalker.Channel
import ca.sanjwad.tvplayer.stalker.Episode
import ca.sanjwad.tvplayer.stalker.Genre
import ca.sanjwad.tvplayer.stalker.Season
import ca.sanjwad.tvplayer.stalker.IptvSource
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
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loaded = false
    fun reset() { genres = emptyList(); all = emptyList(); genre = "*"; query = ""; error = null; loaded = false }
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
    var query by mutableStateOf("")
    var results by mutableStateOf<List<VodItem>>(emptyList())
    var searching by mutableStateOf(false)
    var gridMode by mutableStateOf(false)
    var editMode by mutableStateOf(false)
    var catsAttempt by mutableIntStateOf(0)
    /** TMDB-driven rows: title → matched titles (null while loading). */
    var trending by mutableStateOf<List<Pair<String, List<VodItem>?>>>(emptyList())
    val rowItems = androidx.compose.runtime.mutableStateMapOf<String, List<VodItem>>()
    fun reset() {
        query = ""; results = emptyList(); searching = false; gridMode = false; rowItems.clear(); trending = emptyList()
        categories = emptyList(); category = null; items = emptyList(); page = 0; total = 0
        error = null; catsLoaded = false; job?.cancel(); job = null; loading = false
    }
}

fun kindLabel(kind: String) = if (kind == "series") "TV shows" else "movies"

private suspend fun focusSoon(fr: FocusRequester) {
    delay(150)
    runCatching { fr.requestFocus() }
}

// ---------------- app shell ----------------

private data class DetailReq(val type: String, val item: VodItem, val seasonId: String?)

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var screen by remember { mutableStateOf(if (!prefs.isConfigured) "setup" else "connect") }
    var detail by remember { mutableStateOf<DetailReq?>(null) }
    var section by remember { mutableIntStateOf(0) }
    val live = remember { LiveState() }
    val movies = remember { VodState("movies") }
    val series = remember { VodState("series") }

    // Draw everything a bit smaller than default so more fits on a TV screen.
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * 0.85f, d.fontScale),
    ) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            val dt = detail
            when {
                screen == "setup" -> SetupScreen(prefs, hasSaved = prefs.isConfigured,
                    onCancel = { screen = if (Session.client != null) "home" else "connect" }) {
                    live.reset(); movies.reset(); series.reset(); Session.client = null; Session.seasonCache.clear()
                    screen = "connect"
                }
                screen == "connect" -> ConnectScreen(prefs, onDone = { screen = "home" }, onEdit = { screen = "setup" })
                dt != null -> DetailScreen(dt.type, dt.item, dt.seasonId) { detail = null }
                else -> HomeScreen(
                    section = section, onSection = { section = it }, live = live, movies = movies, series = series,
                    onOpen = { type, item, seasonId -> detail = DetailReq(type, item, seasonId) },
                    onSettings = { screen = "setup" },
                )
            }
        }
    }
}

// ---------------- setup & connect ----------------

@Composable
fun SetupScreen(prefs: Prefs, hasSaved: Boolean, onCancel: () -> Unit, onSaved: () -> Unit) {
    var xtServer by remember { mutableStateOf(prefs.xtServer) }
    var xtUser by remember { mutableStateOf(prefs.xtUser) }
    var xtPass by remember { mutableStateOf(prefs.xtPass) }
    var tmdbKey by remember { mutableStateOf(prefs.tmdbKey) }
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
        Text("Enter the Xtream login from your provider", fontSize = 18.sp, color = TextDim)
        Spacer(Modifier.height(24.dp))
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
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = tmdbKey, onValueChange = { tmdbKey = it.trim() },
            label = { Text("TMDB key (optional) — adds Trending and In theatres rows") },
            singleLine = true, modifier = w,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        err?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = ErrorRed, fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
        TvButton("Save and connect", onClick = {
            prefs.tmdbKey = tmdbKey.trim()
            var srv = xtServer.trim(); var u = xtUser; var pw = xtPass
            XtreamClient.parseM3u(srv)?.let { (a, b, c) -> srv = a; u = b; pw = c; xtServer = a; xtUser = b; xtPass = c }
            when {
                srv.isBlank() -> err = "Enter the server address."
                u.isBlank() || pw.isBlank() -> err = "Enter the username and password."
                else -> { prefs.xtServer = srv; prefs.xtUser = u; prefs.xtPass = pw; onSaved() }
            }
        })
    }
}

@Composable
fun ConnectScreen(prefs: Prefs, onDone: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        error = null
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c: IptvSource = XtreamClient(prefs.xtServer, prefs.xtUser, prefs.xtPass,
                    java.io.File(ctx.filesDir, "catalogue").apply { mkdirs() })
                c.connect()
                c
            }
        }
        r.onSuccess { c ->
            Library.load(ctx, "${prefs.xtServer}|${prefs.xtUser}")
            Session.tmdb = prefs.tmdbKey.takeIf { it.isNotBlank() }?.let { ca.sanjwad.tvplayer.stalker.TmdbClient(it) }
            Session.client = c
            // Start fetching Movies and TV Shows categories now, while Live TV is on screen.
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching { c.browseCategories("movies") }
                runCatching { c.browseCategories("series") }
            }
            onDone()
        }
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

// ---------------- live TV ----------------

private const val SEARCH_ID = "__search"
private const val FAV_ID = "__fav"
private const val LIST_ID = "__list"
private const val EDIT_ID = "__edit"
private val Gold = Color(0xFFFFC94D)

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = query, onValueChange = onChange, label = { Text(hint) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

private val clock = java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault())
fun clockTime(ms: Long): String = clock.format(java.util.Date(ms))

/** "Now" and "Next" lines under a channel, with a thin progress bar for the current show. */
@Composable
fun ChannelGuide(c: ca.sanjwad.tvplayer.stalker.Channel) {
    var guide by remember(c.id) { mutableStateOf<List<ca.sanjwad.tvplayer.stalker.Programme>?>(null) }
    LaunchedEffect(c.id) {
        guide = withContext(Dispatchers.IO) { runCatching { Session.client?.nowNext(c) }.getOrNull().orEmpty() }
    }
    val g = guide ?: return
    val now = g.firstOrNull() ?: return
    val next = g.getOrNull(1)
    val t = System.currentTimeMillis()
    val frac = if (now.end > now.start) ((t - now.start).toFloat() / (now.end - now.start)).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(now.title, fontSize = 13.sp, color = Color(0xFFD5DAE1), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        if (next != null) {
            Text("   ›  ${clockTime(next.start)}  ${next.title}", fontSize = 12.sp, color = TextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
    }
    Box(Modifier.padding(top = 3.dp).width(160.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Accent))
    }
}

@Composable
private fun SideList(entries: List<Genre>, selected: String?, width: Int, onPick: (Genre) -> Unit) {
    LazyColumn(Modifier.width(width.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(entries) { g ->
            FocusBox(onClick = { onPick(g) }, modifier = Modifier.fillMaxWidth(), selected = g.id == selected) {
                Text(g.title, Modifier.padding(12.dp), fontSize = 17.sp,
                    color = if (g.id == FAV_ID || g.id == LIST_ID) Gold else Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

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
            val favs = Library.favourites
            val hiddenLive = Library.hidden["live"].orEmpty()
            val shown = remember(s.genre, s.all, favs, s.query, hiddenLive) {
                when (s.genre) {
                    SEARCH_ID -> {
                        val q = s.query.trim().lowercase()
                        if (q.isEmpty()) emptyList()
                        else s.all.filter { it.name.lowercase().contains(q) || it.number.toString() == q }
                    }
                    FAV_ID -> { val byId = s.all.associateBy { it.id }; favs.map { byId[it.id] ?: it } }
                    "*" -> { val hid = Library.hidden["live"].orEmpty(); if (hid.isEmpty()) s.all else s.all.filter { it.genreId !in hid } }
                    else -> s.all.filter { it.genreId == s.genre }
                }
            }
            val side = listOf(Genre(SEARCH_ID, "🔍  Search"), Genre(FAV_ID, "★  Favourites")) +
                s.genres.filter { it.id == "*" || it.id !in hiddenLive } + Genre(EDIT_ID, "✎  Edit groups")
            Row(Modifier.fillMaxSize()) {
                SideList(side, s.genre, 280) { s.genre = it.id }
                Spacer(Modifier.width(16.dp))
                if (s.genre == EDIT_ID) Column(Modifier.weight(1f).fillMaxHeight()) {
                    ManageGroups("live", s.genres) { s.genre = "*" }
                } else Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (s.genre == SEARCH_ID) {
                        SearchField(s.query, { s.query = it }, "Channel name or number")
                        Spacer(Modifier.height(10.dp))
                    }
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (shown.isEmpty()) item {
                            Text(
                                when (s.genre) {
                                    SEARCH_ID -> if (s.query.isBlank()) "Type a channel name or number above." else "No channels match."
                                    FAV_ID -> "No favourites yet. On any channel, press Right to reach the ☆, then OK."
                                    else -> "No channels in this group"
                                },
                                color = TextDim, fontSize = 17.sp,
                            )
                        }
                        itemsIndexed(shown) { index, c ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                FocusBox(onClick = {
                                    Session.channels = shown
                                    ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("live_index", index))
                                }, modifier = Modifier.weight(1f)) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (c.number > 0) c.number.toString() else "", Modifier.width(56.dp), color = TextDim, fontSize = 16.sp)
                                        AsyncImage(model = c.logo, contentDescription = null,
                                            modifier = Modifier.size(width = 56.dp, height = 34.dp), contentScale = ContentScale.Fit)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(c.name, fontSize = 17.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            ChannelGuide(c)
                                        }
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                val fav = favs.any { it.id == c.id }
                                FocusBox(onClick = { Library.toggleFavourite(c) }, modifier = Modifier.size(width = 56.dp, height = 50.dp)) {
                                    Text(if (fav) "★" else "☆", Modifier.align(Alignment.Center), fontSize = 24.sp,
                                        color = if (fav) Gold else TextDim)
                                }
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
private fun PosterGrid(items: List<VodItem>, onOpen: (VodItem) -> Unit, onNearEnd: (() -> Unit)? = null) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items) { index, item ->
            if (onNearEnd != null && index >= items.size - 8) LaunchedEffect(index) { onNearEnd() }
            PosterCard(item) { onOpen(item) }
        }
    }
}

@Composable
fun VodScreen(s: VodState, onOpen: (VodItem) -> Unit) {
    val scope = rememberCoroutineScope()

    fun loadPage(reset: Boolean) {
        val cat = s.category ?: return
        if (cat == SEARCH_ID || cat == LIST_ID) return
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

    // Opened from a row's "See all": the category is chosen but nothing loaded yet.
    LaunchedEffect(s.category, s.catsLoaded) {
        if (s.catsLoaded && s.items.isEmpty() && !s.loading) loadPage(true)
    }

    // Search as you type, after a short pause.
    LaunchedEffect(s.query, s.category) {
        if (s.category != SEARCH_ID) return@LaunchedEffect
        val q = s.query.trim()
        if (q.length < 2) { s.results = emptyList(); s.searching = false; return@LaunchedEffect }
        delay(600)
        s.searching = true
        val r = withContext(Dispatchers.IO) {
            runCatching { (Session.client ?: error("Not connected")).search(s.type, q) }
        }
        s.results = r.getOrDefault(emptyList()).let { list ->
            if (s.type != "vod") list
            else if (s.kind == "series") list.filter { it.isSeries } else list.filterNot { it.isSeries }
        }
        r.exceptionOrNull()?.let { s.error = it.message }
        s.searching = false
    }

    val label = kindLabel(s.kind)
    val side = listOf(Genre(SEARCH_ID, "🔍  Search")) + s.categories.filter { !Library.isHidden(s.kind, it.id) }
    Row(Modifier.fillMaxSize()) {
        SideList(side, s.category, 260) { g ->
            if (s.category != g.id) { s.category = g.id; loadPage(true) }
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val err = s.error
            when (s.category) {
                SEARCH_ID -> Column(Modifier.fillMaxSize()) {
                    SearchField(s.query, { s.query = it }, "Search $label")
                    Spacer(Modifier.height(12.dp))
                    when {
                        s.searching -> CenterMessage("Searching…", "The first search loads the full catalogue, so it can take a moment.", loading = true)
                        s.query.trim().length < 2 -> CenterMessage("Type at least two letters above.")
                        s.results.isEmpty() -> CenterMessage("Nothing found for \"${s.query.trim()}\"")
                        else -> PosterGrid(s.results, onOpen)
                    }
                }
                LIST_ID -> {
                    val saved = Library.myList.filter { it.kind == s.kind }.map { it.item }
                    if (saved.isEmpty()) CenterMessage("Your list is empty", "Open any title and choose ＋ My List.")
                    else PosterGrid(saved, onOpen)
                }
                else -> when {
                    err != null && s.items.isEmpty() -> CenterMessage("Couldn't load $label", err)
                    s.catsLoaded && s.categories.isEmpty() -> CenterMessage("No $label section on this server", null)
                    s.items.isEmpty() && (s.loading || !s.catsLoaded) -> CenterMessage("Loading $label…", loading = true)
                    s.items.isEmpty() -> CenterMessage("Nothing in this category")
                    else -> PosterGrid(s.items, onOpen) { loadPage(false) }
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
fun DetailScreen(type: String, start: VodItem, startSeasonId: String? = null, onBack: () -> Unit) {
    var item by remember { mutableStateOf(start) }
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var seasons by remember { mutableStateOf<List<Season>?>(Session.seasonCache[start.id]) }
    var seasonIdx by remember { mutableIntStateOf(0) }
    var episodes by remember { mutableStateOf<List<Episode>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }
    val last = Library.watched[item.id]

    fun play(cmd: String, series: String, title: String, movieId: String? = null, extras: Map<String, String?> = emptyMap()) {
        msg = "Starting…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { (Session.client ?: error("Not connected")).createLink("vod", cmd, series, movieId) }
            }
            r.onSuccess {
                msg = null
                val i = Intent(ctx, PlayerActivity::class.java).putExtra("url", it).putExtra("title", title).putExtra("cmd", cmd)
                extras.forEach { (k, v) -> if (v != null) i.putExtra(k, v) }
                ctx.startActivity(i)
            }.onFailure { msg = it.message ?: "Couldn't start playback." }
        }
    }

    fun playMovie() = play(item.cmd, "", item.name, item.id, mapOf(
        "key" to "m:${item.id}", "subtitle" to item.year, "poster" to (item.poster ?: item.backdrop)))

    fun playEpisode(s: Season, si: Int, ep: Episode, ei: Int) {
        val sn = Library.seasonNumber(s, si); val en = Library.episodeNumber(ep, ei)
        Library.markWatched(item.id, sn, en)
        play(ep.cmd, ep.seriesParam, item.name, null, mapOf(
            "key" to "e:${ep.cmd}", "subtitle" to "S$sn E$en  ·  ${ep.title}",
            "poster" to (item.poster ?: ep.thumb), "series_id" to item.id))
    }

    LaunchedEffect(item.id) {
        if (item.isSeries) {
            if (seasons == null) {
                val r = withContext(Dispatchers.IO) {
                    runCatching { (Session.client ?: error("Not connected")).seasons(type, item) }
                }
                r.onSuccess { seasons = it; Session.seasonCache[item.id] = it; if (it.isEmpty()) msg = "No seasons found for this show." }
                    .onFailure { msg = it.message; seasons = emptyList() }
            }
            // Open on the season with the next episode to watch (or the one asked for).
            val ss = seasons.orEmpty()
            val nextSeasonId = startSeasonId ?: Library.nextEpisode(ss, Library.watched[item.id])?.first?.id
            ss.indexOfFirst { it.id == nextSeasonId }.takeIf { it >= 0 }?.let { seasonIdx = it }
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

    // Fill in missing episode names, descriptions, pictures and runtimes from TMDB.
    LaunchedEffect(seasons, seasonIdx, episodes != null) {
        val tmdb = Session.tmdb ?: return@LaunchedEffect
        val s = seasons?.getOrNull(seasonIdx) ?: return@LaunchedEffect
        val eps = episodes ?: return@LaunchedEffect
        if (eps.isEmpty() || eps.all { it.plot.isNotBlank() && it.thumb != null }) return@LaunchedEffect
        val sn = Library.seasonNumber(s, seasonIdx)
        val info = withContext(Dispatchers.IO) {
            runCatching { tmdb.findTv(item.name, item.year)?.let { tmdb.season(it, sn) } }.getOrNull()
        } ?: return@LaunchedEffect
        if (seasons?.getOrNull(seasonIdx) !== s) return@LaunchedEffect
        val generic = Regex("S\\d{1,2}\\s*E\\d{1,3}|^episode \\d+$", RegexOption.IGNORE_CASE)
        episodes = eps.mapIndexed { ei, ep ->
            val t = info[Library.episodeNumber(ep, ei)] ?: return@mapIndexed ep
            ep.copy(
                title = if (ep.title.isBlank() || generic.containsMatchIn(ep.title)) t.name.ifBlank { ep.title } else ep.title,
                plot = ep.plot.ifBlank { t.overview },
                thumb = t.still ?: ep.thumb,
                duration = ep.duration.ifBlank { if (t.runtime > 0) "${t.runtime}m" else "" },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Backdrop behind everything, faded so text stays readable.
        AsyncImage(model = item.backdrop ?: item.poster, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.22f)
        Row(Modifier.fillMaxSize().padding(36.dp)) {
            Box(Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Panel)) {
                AsyncImage(model = item.poster, contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                val meta = listOfNotNull(item.year.ifBlank { null }, item.rating.ifBlank { null }?.let { "★ $it" })
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(meta.joinToString("  ·  "), fontSize = 15.sp, color = TextDim)
                }
                if (item.description.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(item.description, fontSize = 15.sp, color = Color(0xFFD5DAE1), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                msg?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 13.sp, color = if (it == "Starting…") Accent else ErrorRed)
                }
                Spacer(Modifier.height(16.dp))

                val saved = Library.inList(item.id)
                val listButton: @Composable () -> Unit = {
                    TvButton(if (saved) "✓  In My List" else "＋  My List",
                        onClick = { Library.toggleList(if (item.isSeries) "series" else "movies", type, item) })
                }
                if (!item.isSeries) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val prog = Library.progress["m:${item.id}"]
                        TvButton(if (prog != null) "▶  Resume  ·  ${((prog.durMs - prog.posMs) / 60_000).coerceAtLeast(1)}m left" else "▶  Play",
                            onClick = { playMovie() }, modifier = Modifier.focusRequester(fr))
                        listButton()
                    }
                } else {
                    val ss = seasons
                    val next = ss?.let { Library.nextEpisode(it, last) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            ss == null -> Box(Modifier.width(1.dp).focusRequester(fr))
                            next != null -> {
                                val (ns, ne, num) = next
                                TvButton(
                                    "▶  ${if (last == null) "Start" else "Play"} S${num.first} E${num.second}",
                                    onClick = { playEpisode(ns, ss.indexOf(ns), ne, ns.episodes.orEmpty().indexOf(ne)) },
                                    modifier = Modifier.focusRequester(fr),
                                )
                            }
                            else -> TvButton("✓  Caught up", onClick = {}, modifier = Modifier.focusRequester(fr))
                        }
                        listButton()
                    }
                    Spacer(Modifier.height(14.dp))
                    if (ss == null) {
                        Text("Loading seasons…", color = TextDim, fontSize = 15.sp)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(ss) { i, s ->
                                FocusBox(onClick = { seasonIdx = i }, selected = i == seasonIdx, shape = RoundedCornerShape(20.dp)) {
                                    Text(s.name.ifBlank { "Season ${i + 1}" },
                                        Modifier.padding(horizontal = 16.dp, vertical = 7.dp), fontSize = 14.sp, color = Color.White)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val eps = episodes
                        val cur = ss.getOrNull(seasonIdx)
                        when {
                            eps == null || cur == null -> Text("Loading episodes…", color = TextDim, fontSize = 15.sp)
                            eps.isEmpty() -> Text("No episodes found.", color = TextDim, fontSize = 15.sp)
                            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                itemsIndexed(eps) { ei, ep ->
                                    val sn = Library.seasonNumber(cur, seasonIdx)
                                    val en = Library.episodeNumber(ep, ei)
                                    val seen = last != null && (sn < last.first || (sn == last.first && en <= last.second))
                                    FocusBox(onClick = { playEpisode(cur, seasonIdx, ep, ei) }, modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
                                            Box(Modifier.width(176.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Bg)) {
                                                AsyncImage(model = ep.thumb ?: item.backdrop ?: item.poster, contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                                    alpha = if (seen) 0.55f else 1f)
                                                Text("▶", Modifier.align(Alignment.Center), color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp)
                                                Library.progress["e:${ep.cmd}"]?.let { pg ->
                                                    if (pg.durMs > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55000000))) {
                                                        Box(Modifier.fillMaxHeight().fillMaxWidth((pg.posMs.toFloat() / pg.durMs).coerceIn(0f, 1f)).background(Accent))
                                                    }
                                                }
                                                if (seen) Text("✓ Watched", Modifier.align(Alignment.BottomStart).padding(5.dp)
                                                    .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp), color = Accent, fontSize = 11.sp)
                                            }
                                            Spacer(Modifier.width(14.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text("$en.  ${ep.title}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (ep.duration.isNotBlank()) {
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(ep.duration, color = TextDim, fontSize = 12.sp)
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                Text(ep.plot.ifBlank { "No description from your provider for this episode." },
                                                    color = if (ep.plot.isBlank()) TextDim else Color(0xFFD5DAE1),
                                                    fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
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
