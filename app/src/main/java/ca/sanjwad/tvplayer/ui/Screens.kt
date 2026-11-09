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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    var guideView by mutableStateOf(false)
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
    /** One load at a time, whether started by the background preload or by opening the tab. */
    val catsLock = kotlinx.coroutines.sync.Mutex()
    val trendLock = kotlinx.coroutines.sync.Mutex()
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
    // Pages opened over Home: title pages and cast pages, newest last. Back pops one.
    val stack = remember { androidx.compose.runtime.mutableStateListOf<Any>() }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    // A show picked from an actor's page inside the player opens here.
    LaunchedEffect(Session.pendingOpen) {
        Session.pendingOpen?.let { (t, i) -> stack.add(DetailReq(t, i, null)); Session.pendingOpen = null }
    }
    var section by remember { mutableIntStateOf(0) }
    val live = remember { LiveState() }
    val movies = remember { VodState("movies") }
    val series = remember { VodState("series") }
    // Get Movies and TV Shows (lists, trending rows, first few rows) ready in the background while
    // you watch live TV, so they're waiting when you open them. Starts after a pause so the live
    // channel has settled before anything big downloads.
    LaunchedEffect(screen) {
        if (screen != "home") return@LaunchedEffect
        kotlinx.coroutines.delay(15_000)
        VodLoader.preload(movies)
        VodLoader.preload(series)
    }

    // Draw everything a bit smaller than default so more fits on a TV screen.
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * 0.85f, d.fontScale),
    ) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            val top = stack.lastOrNull()
            when {
                screen == "setup" -> SetupScreen(prefs, hasSaved = prefs.isConfigured,
                    onCancel = { screen = if (Session.client != null) "home" else "connect" }) {
                    live.reset(); movies.reset(); series.reset(); Session.client = null; Session.seasonCache.clear()
                    stack.clear(); Session.vodTypes.clear()
                    screen = "connect"
                }
                screen == "connect" -> ConnectScreen(prefs, onDone = { screen = "home" }, onEdit = { screen = "setup" })
                top is DetailReq -> androidx.compose.runtime.key(top) {
                    DetailScreen(top.type, top.item, top.seasonId,
                        onCast = { stack.add(CastNav(top.item.name, top.item.year, top.item.isSeries)) },
                        onAbout = { stack.add(AboutNav(top.item.name, top.item.year, top.item.isSeries)) }) { pop() }
                }
                top is AboutNav -> AboutPage(top, onClose = { pop() },
                    onCast = { stack.add(CastNav(top.title, top.year, top.isSeries)) },
                    onPick = { t, i -> stack.add(DetailReq(t, i, null)) })
                top is CastNav -> CastBrowser(top, onClose = { pop() }, onPick = { t, i -> stack.add(DetailReq(t, i, null)) })
                else -> HomeScreen(
                    section = section, onSection = { section = it }, live = live, movies = movies, series = series,
                    onOpen = { type, item, seasonId -> stack.add(DetailReq(type, item, seasonId)) },
                    onSettings = { screen = "setup" },
                )
            }
            UpdatePrompt()
        }
    }
}

// ---------------- setup & connect ----------------

@Composable
fun SetupScreen(prefs: Prefs, hasSaved: Boolean, onCancel: () -> Unit, onSaved: () -> Unit) {
    var xtServer by remember { mutableStateOf(prefs.xtServer) }
    var xtUser by remember { mutableStateOf(prefs.xtUser) }
    var xtPass by remember { mutableStateOf(prefs.xtPass) }
    var stalker by remember { mutableStateOf(prefs.isStalker) }
    var stPortal by remember { mutableStateOf(prefs.stPortal) }
    var stMac by remember { mutableStateOf(prefs.stMac) }
    var stSerial by remember { mutableStateOf(prefs.stSerial) }
    var stDev by remember { mutableStateOf(prefs.stDeviceId) }
    var stDev2 by remember { mutableStateOf(prefs.stDeviceId2) }
    var stMore by remember { mutableStateOf(prefs.stSerial.isNotBlank() || prefs.stDeviceId.isNotBlank()) }
    var showLog by remember { mutableStateOf(false) }
    var tmdbKey by remember { mutableStateOf(prefs.tmdbKey) }
    var omdbKey by remember { mutableStateOf(prefs.omdbKey) }
    var osKey by remember { mutableStateOf(prefs.osKey) }
    var osUser by remember { mutableStateOf(prefs.osUser) }
    var osPass by remember { mutableStateOf(prefs.osPass) }
    var err by remember { mutableStateOf<String?>(null) }
    // With a working login, the details stay locked until "Change login details" is pressed.
    var editing by remember { mutableStateOf(!hasSaved) }
    var advanced by remember { mutableStateOf(false) }
    var tmdbSaved by remember { mutableStateOf(false) }
    var startLast by remember { mutableStateOf(prefs.startOnLastChannel) }
    if (hasSaved) BackHandler { if (editing) { editing = false; err = null } else onCancel() }

    if (showLog) {
        BackHandler { showLog = false }
        PortalLogScreen { showLog = false }
        return
    }

    // Category editor, opened from the Categories section below.
    var managing by remember { mutableStateOf<String?>(null) }
    val m = managing
    if (m != null) {
        BackHandler { managing = null }
        var cats by remember(m) { mutableStateOf<List<Genre>?>(null) }
        LaunchedEffect(m) {
            cats = withContext(Dispatchers.IO) {
                runCatching {
                    val c = Session.client ?: error("Not connected")
                    (if (m == "live") c.liveGenres() else c.browseCategories(m).second).also { Library.noteCategories(m, it) }
                }.getOrDefault(emptyList())
            }
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
            val c = cats
            if (c == null) CenterMessage("Loading categories…", loading = true)
            else ManageGroups(m, c) { managing = null }
        }
        return
    }
    val w = Modifier.width(600.dp)
    val fr = remember { FocusRequester() }
    LaunchedEffect(editing) { focusSoon(fr) }

    fun save() {
        if (stalker) {
            var mac = stMac.trim().uppercase().replace("-", ":")
            if (Regex("^[0-9A-F]{12}$").matches(mac)) mac = mac.chunked(2).joinToString(":")
            stMac = mac
            when {
                stPortal.isBlank() -> err = "Enter the portal address."
                !ca.sanjwad.tvplayer.stalker.StalkerClient.isValidMac(mac) -> err = "The MAC address should look like 00:1A:79:12:34:56."
                else -> {
                    prefs.stPortal = stPortal.trim(); prefs.stMac = mac
                    prefs.stSerial = stSerial.trim(); prefs.stDeviceId = stDev.trim(); prefs.stDeviceId2 = stDev2.trim()
                    prefs.loginType = "stalker"; onSaved()
                }
            }
            return
        }
        var srv = xtServer.trim(); var u = xtUser; var pw = xtPass
        XtreamClient.parseM3u(srv)?.let { (a, b, c) -> srv = a; u = b; pw = c; xtServer = a; xtUser = b; xtPass = c }
        when {
            srv.isBlank() -> err = "Enter the server address."
            u.isBlank() || pw.isBlank() -> err = "Enter the username and password."
            else -> { prefs.xtServer = srv; prefs.xtUser = u; prefs.xtPass = pw; prefs.loginType = "xtream"; onSaved() }
        }
    }

    @Composable
    fun xtreamFields() {
        OutlinedTextField(
            value = xtServer, onValueChange = { xtServer = it },
            label = { Text("Server (e.g. http://xt.example.com) or full M3U link") },
            singleLine = true, modifier = w.focusRequester(fr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = xtUser, onValueChange = { xtUser = it.trim() },
            label = { Text("Username") }, singleLine = true, modifier = w,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = xtPass, onValueChange = { xtPass = it.trim() },
            label = { Text("Password") }, singleLine = true, modifier = w,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
    }

    @Composable
    fun stalkerFields() {
        OutlinedTextField(
            value = stPortal, onValueChange = { stPortal = it.trim() },
            label = { Text("Portal address (e.g. http://portal.example.com/c/)") },
            singleLine = true, modifier = w.focusRequester(fr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = stMac, onValueChange = { stMac = it.trim() },
            label = { Text("MAC address (00:1A:79:…)") }, singleLine = true, modifier = w,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        Spacer(Modifier.height(10.dp))
        FocusBox(onClick = { stMore = !stMore }, modifier = w, shape = RoundedCornerShape(8.dp)) {
            Text((if (stMore) "▲ " else "▼ ") + "Serial number and device ID (only if your provider asks)",
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp), fontSize = 14.sp, color = TextDim)
        }
        if (stMore) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = stSerial, onValueChange = { stSerial = it.trim() }, label = { Text("Serial number") },
                singleLine = true, modifier = w, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = stDev, onValueChange = { stDev = it.trim() }, label = { Text("Device ID") },
                singleLine = true, modifier = w, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = stDev2, onValueChange = { stDev2 = it.trim() }, label = { Text("Device ID 2") },
                singleLine = true, modifier = w, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        }
    }

    @Composable
    fun loginFields() {
        // Stalker is still being tested: only the test build offers it (or a phone already set up with it).
        if (ca.sanjwad.tvplayer.BuildConfig.TEST_BUILD || prefs.isStalker) Row(w, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(false to "Xtream login", true to "Stalker portal (MAC)").forEach { (st, label) ->
                FocusBox(onClick = { stalker = st; err = null }, selected = stalker == st, shape = RoundedCornerShape(8.dp)) {
                    Text(label, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 15.sp,
                        color = Color.White, fontWeight = if (stalker == st) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (stalker) stalkerFields() else xtreamFields()
        err?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = ErrorRed, fontSize = 15.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Save and connect", onClick = { save() })
            if (hasSaved) TvButton("Cancel", onClick = {
                editing = false; err = null
                xtServer = prefs.xtServer; xtUser = prefs.xtUser; xtPass = prefs.xtPass
                stalker = prefs.isStalker; stPortal = prefs.stPortal; stMac = prefs.stMac
            })
        }
    }


    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!hasSaved) {
            // First run: just the login.
            Spacer(Modifier.height(40.dp))
            Logo(34.sp)
            Spacer(Modifier.height(8.dp))
            Text("Enter the login from your provider", fontSize = 18.sp, color = TextDim)
            Spacer(Modifier.height(24.dp))
            loginFields()
            return@Column
        }

        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = w)
        Spacer(Modifier.height(20.dp))

        // ---- Account
        Text("ACCOUNT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDim, modifier = w)
        Spacer(Modifier.height(8.dp))
        Column(w.clip(RoundedCornerShape(10.dp)).background(Panel).padding(18.dp)) {
            if (!editing) {
                val exp = (Session.client as? XtreamClient)?.expiry?.toLongOrNull()?.let {
                    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(it * 1000))
                } ?: (Session.client as? ca.sanjwad.tvplayer.stalker.StalkerClient)?.expiry?.takeIf { it.isNotBlank() }
                val server = if (prefs.isStalker) prefs.stPortal else prefs.xtServer
                Text(server.removePrefix("http://").removePrefix("https://"), fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(if (prefs.isStalker) "Stalker portal    ·    MAC: ${prefs.stMac}" else "Username: ${prefs.xtUser}    ·    Password: ••••••",
                    fontSize = 14.sp, color = TextDim)
                Text(if (Session.client != null) "Connected" + (exp?.let { "  ·  subscription ends $it" } ?: "") else "Not connected",
                    fontSize = 14.sp, color = if (Session.client != null) Accent else ErrorRed)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Change login details", onClick = { editing = true }, modifier = Modifier.focusRequester(fr))
                    if (prefs.isStalker) TvButton("Portal log", onClick = { showLog = true })
                }
            } else {
                Text("Change login details", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Saving reconnects with the new details. Favourites and My List are kept per login.", fontSize = 13.sp, color = TextDim)
                Spacer(Modifier.height(12.dp))
                loginFields()
            }
        }

        // ---- Categories
        Spacer(Modifier.height(24.dp))
        Text("CATEGORIES", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDim, modifier = w)
        Text("Hide the groups and categories you never watch.", fontSize = 13.sp, color = TextDim, modifier = w)
        Spacer(Modifier.height(8.dp))
        listOf("live" to "Live TV groups", "movies" to "Movie categories", "series" to "TV show categories").forEach { (key, label) ->
            val hiddenCount = Library.hidden[key].orEmpty().size
            FocusBox(onClick = { managing = key }, modifier = w.padding(vertical = 3.dp), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 15.sp, color = Color.White)
                    Text(if (hiddenCount > 0) "$hiddenCount hidden  ›" else "›", fontSize = 14.sp, color = TextDim)
                }
            }
        }

        // ---- Options
        Spacer(Modifier.height(24.dp))
        Text("OPTIONS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDim, modifier = w)
        Spacer(Modifier.height(8.dp))
        FocusBox(onClick = { startLast = !startLast; prefs.startOnLastChannel = startLast }, modifier = w, shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Open the last live channel when Sanj TV starts", Modifier.weight(1f), fontSize = 15.sp, color = Color.White)
                Text(if (startLast) "On" else "Off", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = if (startLast) Accent else TextDim)
            }
        }

        // ---- Advanced (collapsed)
        Spacer(Modifier.height(24.dp))
        FocusBox(onClick = { advanced = !advanced }, modifier = w, shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced", Modifier.weight(1f), fontSize = 15.sp, color = Color.White)
                Text(if (advanced) "▲" else "▼", fontSize = 14.sp, color = TextDim)
            }
        }
        if (advanced) {
            Spacer(Modifier.height(10.dp))
            Column(w.clip(RoundedCornerShape(10.dp)).background(Panel).padding(18.dp)) {
                Text("Your own TMDB key", fontSize = 15.sp, color = Color.White)
                Text(if (ca.sanjwad.tvplayer.BuildConfig.TMDB_KEY.isNotBlank()) "Optional. Sanj TV already has a built-in key; leave this empty to use it."
                    else "Optional. Adds Trending rows and episode descriptions.", fontSize = 13.sp, color = TextDim)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = tmdbKey, onValueChange = { tmdbKey = it.trim(); tmdbSaved = false },
                    label = { Text("TMDB key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                Spacer(Modifier.height(14.dp))
                Text("Your own OMDb key (IMDb ratings)", fontSize = 15.sp, color = Color.White)
                Text(if (ca.sanjwad.tvplayer.BuildConfig.OMDB_KEY.isNotBlank()) "Optional. A built-in key is already used."
                    else "Optional. Free from omdbapi.com; adds IMDb ratings to movie and show pages.", fontSize = 13.sp, color = TextDim)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = omdbKey, onValueChange = { omdbKey = it.trim(); tmdbSaved = false },
                    label = { Text("OMDb key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                Spacer(Modifier.height(14.dp))
                Text("OpenSubtitles (download subtitles)", fontSize = 15.sp, color = Color.White)
                Text((if (ca.sanjwad.tvplayer.BuildConfig.OPENSUBS_KEY.isNotBlank()) "A built-in key is used; the key field is optional. " else
                    "Free account and API key from opensubtitles.com. ") + "Logging in raises the daily download limit.", fontSize = 13.sp, color = TextDim)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = osKey, onValueChange = { osKey = it.trim(); tmdbSaved = false },
                    label = { Text("OpenSubtitles API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = osUser, onValueChange = { osUser = it.trim(); tmdbSaved = false },
                        label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    OutlinedTextField(value = osPass, onValueChange = { osPass = it; tmdbSaved = false },
                        label = { Text("Password") }, singleLine = true, modifier = Modifier.weight(1f),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvButton("Save keys", onClick = {
                        prefs.osKey = osKey.trim(); prefs.osUser = osUser.trim(); prefs.osPass = osPass
                        prefs.omdbKey = omdbKey.trim()
                        Session.omdb = prefs.omdbKey.ifBlank { ca.sanjwad.tvplayer.BuildConfig.OMDB_KEY }
                            .takeIf { it.isNotBlank() }?.let { ca.sanjwad.tvplayer.stalker.OmdbClient(it) }
                        prefs.tmdbKey = tmdbKey.trim()
                        Session.tmdb = prefs.tmdbKey.ifBlank { ca.sanjwad.tvplayer.BuildConfig.TMDB_KEY }
                            .takeIf { it.isNotBlank() }?.let { ca.sanjwad.tvplayer.stalker.TmdbClient(it) }
                        tmdbSaved = true
                    })
                    if (tmdbSaved) Text("  ✓ Saved", fontSize = 14.sp, color = Accent)
                }
            }
        }

        // ---- About
        Spacer(Modifier.height(28.dp))
        Text("Sanj TV${if (ca.sanjwad.tvplayer.BuildConfig.TEST_BUILD) " Test" else ""}  ·  build ${ca.sanjwad.tvplayer.BuildConfig.VERSION_CODE}", fontSize = 13.sp, color = TextDim, modifier = w)
        Spacer(Modifier.height(8.dp))
        Row(w, verticalAlignment = Alignment.CenterVertically) {
            Text("TMDB", Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF0D253F))
                .padding(horizontal = 6.dp, vertical = 2.dp), color = Color(0xFF01B4E4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", fontSize = 12.sp, color = TextDim)
        }
    }
}

@Composable
fun ConnectScreen(prefs: Prefs, onDone: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }
    var importOffer by remember { mutableStateOf<Library.OldAccount?>(null) }
    var importing by remember { mutableStateOf(false) }
    val importChoice = remember { kotlinx.coroutines.CompletableDeferred<Boolean>() }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        error = null
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c: IptvSource = if (prefs.isStalker)
                    ca.sanjwad.tvplayer.stalker.StalkerClient(prefs.stPortal, prefs.stMac, prefs.stSerial, prefs.stDeviceId, prefs.stDeviceId2,
                        java.io.File(ctx.filesDir, "vodindex"))
                else XtreamClient(prefs.xtServer, prefs.xtUser, prefs.xtPass,
                    java.io.File(ctx.filesDir, "catalogue").apply { mkdirs() })
                c.connect()
                c
            }
        }
        r.onSuccess { c ->
            Library.load(ctx, prefs.accountKey)
            // Your own key if you entered one, otherwise the key built into the app.
            Session.tmdb = prefs.tmdbKey.ifBlank { ca.sanjwad.tvplayer.BuildConfig.TMDB_KEY }
                .takeIf { it.isNotBlank() }?.let { ca.sanjwad.tvplayer.stalker.TmdbClient(it) }
            Session.omdb = prefs.omdbKey.ifBlank { ca.sanjwad.tvplayer.BuildConfig.OMDB_KEY }
                .takeIf { it.isNotBlank() }?.let { ca.sanjwad.tvplayer.stalker.OmdbClient(it) }
            ca.sanjwad.tvplayer.stalker.Ratings.init(ctx.filesDir)
            Session.client = c
            // A new login: offer to bring My List and favourites over from the previous one.
            Library.previousAccount()?.let { old ->
                importOffer = old
                val yes = importChoice.await()
                if (yes) {
                    importing = true
                    val summary = withContext(Dispatchers.IO) { runCatching { Library.importFrom(old, c) }.getOrDefault("") }
                    Library.load(ctx, prefs.accountKey)
                    if (summary.isNotBlank()) android.widget.Toast.makeText(ctx, summary, android.widget.Toast.LENGTH_LONG).show()
                } else Library.markAsked()
                importOffer = null; importing = false
            }
            // Reopen on the last live channel, like TiVimate.
            val lastId = Library.lastChannelId
            if (prefs.startOnLastChannel && lastId.isNotEmpty()) {
                val chans = withContext(Dispatchers.IO) { runCatching { c.liveChannels() }.getOrDefault(emptyList()) }
                val hid = Library.hidden["live"].orEmpty()
                val visible = chans.filter { it.genreId !in hid }
                val idx = visible.indexOfFirst { it.id == lastId }
                if (idx >= 0) {
                    Session.channels = visible
                    ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("live_index", idx))
                }
            }
            // Start fetching Movies and TV Shows categories now, while Live TV is on screen.
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching { c.browseCategories("movies") }
                runCatching { c.browseCategories("series") }
                runCatching { c.ensureGuide() }
                withContext(Dispatchers.Main) { Session.guideVersion++ }
            }
            onDone()
        }
            .onFailure { error = it.message ?: "Couldn't connect." }
    }
    LaunchedEffect(error) { if (error != null) focusSoon(fr) }
    if (showLog) {
        BackHandler { showLog = false }
        PortalLogScreen { showLog = false }
        return
    }

    // Drawn after the LaunchedEffects so returning early never removes (and cancels)
    // the connect coroutine that is waiting on importChoice.
    importOffer?.let { old ->
        if (importing) { CenterMessage("Bringing over your lists…", "Matching titles and channels on the new server.", loading = true); return }
        val yes = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusSoon(yes) }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("New login", fontSize = 26.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Bring over your My List, favourite channels, watched episodes and hidden categories from your previous login?",
                fontSize = 16.sp, color = TextDim, modifier = Modifier.width(620.dp))
            Spacer(Modifier.height(4.dp))
            Text("Previous login: ${old.label.substringBefore("|")}", fontSize = 13.sp, color = TextDim, modifier = Modifier.width(620.dp))
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Yes, bring them over", onClick = { importChoice.complete(true) }, modifier = Modifier.focusRequester(yes))
                TvButton("No, start fresh", onClick = { importChoice.complete(false) })
            }
        }
        return
    }


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
                if (prefs.isStalker) TvButton("Portal log", onClick = { showLog = true })
            }
        }
    }
}

// ---------------- live TV ----------------

private const val SEARCH_ID = "__search"
private const val FAV_ID = "__fav"
private const val CATCHUP_ID = "__catchup"
private const val LIST_ID = "__list"
private const val EDIT_ID = "__edit"
private val Gold = Color(0xFFFFC94D)

@Composable
fun SearchField(query: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    OutlinedTextField(
        value = query, onValueChange = onChange, label = { Text(hint) },
        singleLine = true, modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // The keyboard's search key: close the keyboard and drop down into the results.
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
            keyboard?.hide()
            if (onSearch != null) onSearch() else focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
        }),
    )
}

private val clock = java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault())
fun clockTime(ms: Long): String = clock.format(java.util.Date(ms))

/** "Now" and "Next" lines under a channel, with a thin progress bar for the current show. */
@Composable
fun ChannelGuide(c: ca.sanjwad.tvplayer.stalker.Channel) {
    var guide by remember(c.id) { mutableStateOf<List<ca.sanjwad.tvplayer.stalker.Programme>?>(null) }
    LaunchedEffect(c.id, Session.guideVersion) {
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
    // Start with an empty search box next time.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { s.query = ""; if (s.genre == SEARCH_ID) s.genre = "*" } }
    // Always open on Favourites when there are any.
    LaunchedEffect(Unit) { if (Library.favourites.isNotEmpty()) s.genre = FAV_ID }
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
                Library.noteCategories("live", g)
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
                    CATCHUP_ID -> s.all.filter { it.archiveDays > 0 }
                    "*" -> { val hid = Library.hidden["live"].orEmpty(); if (hid.isEmpty()) s.all else s.all.filter { it.genreId !in hid } }
                    else -> s.all.filter { it.genreId == s.genre }
                }
            }
            val catchupCount = remember(s.all) { s.all.count { it.archiveDays > 0 } }
            val side = listOf(Genre(SEARCH_ID, "🔍  Search"), Genre(FAV_ID, "★  Favourites")) +
                // Every channel with catch-up in one place (the test build shows it even when there are none).
                (if (catchupCount > 0 || ca.sanjwad.tvplayer.BuildConfig.TEST_BUILD) listOf(Genre(CATCHUP_ID, "↺  Catch-up ($catchupCount)")) else emptyList()) +
                s.genres.filter { it.id == "*" || it.id !in hiddenLive }
            var catchupFor by remember { mutableStateOf<ca.sanjwad.tvplayer.stalker.Channel?>(null) }
            catchupFor?.let { ch ->
                CatchupScreen(ch, onPlay = { p -> Catchup.play(ctx, ch, p) }, onClose = { catchupFor = null })
                return
            }
            Row(Modifier.fillMaxSize()) {
                SideList(side, s.genre, 280) { if (it.id != SEARCH_ID) s.query = ""; s.genre = it.id }
                Spacer(Modifier.width(16.dp))
                if (s.genre == EDIT_ID) Column(Modifier.weight(1f).fillMaxHeight()) {
                    ManageGroups("live", s.genres) { s.genre = "*" }
                } else Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (s.genre != SEARCH_ID) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            FocusBox(onClick = { s.guideView = false }, selected = !s.guideView, shape = RoundedCornerShape(16.dp)) {
                                Text("☰  List", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 14.sp, color = Color.White)
                            }
                            FocusBox(onClick = { s.guideView = true }, selected = s.guideView, shape = RoundedCornerShape(16.dp)) {
                                Text("▦  Guide", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 14.sp, color = Color.White)
                            }
                            FocusBox(onClick = {
                                // One-stream accounts can't show two channels; say so instead of opening it.
                                if ((Session.client as? ca.sanjwad.tvplayer.stalker.XtreamClient)?.maxConnections == 1) {
                                    android.widget.Toast.makeText(ctx, "Multi-screen needs an account that allows 2 or more streams at once. Yours allows 1 — ask your provider.",
                                        android.widget.Toast.LENGTH_LONG).show()
                                    return@FocusBox
                                }
                                Session.liveAll = s.all; Session.liveGenres = s.genres
                                ctx.startActivity(Intent(ctx, ca.sanjwad.tvplayer.MultiViewActivity::class.java)
                                    .putExtra("channel_id", Library.lastChannelId.takeIf { id -> s.all.any { it.id == id } }))
                            }, shape = RoundedCornerShape(16.dp)) {
                                Text("⊞  Multi-screen", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 14.sp, color = Color.White)
                            }
                        }
                    }
                    if (s.guideView && s.genre != SEARCH_ID) {
                        GuideGrid(shown, onCatchup = { ch, p -> Catchup.play(ctx, ch, p) }) { index ->
                            Session.channels = shown
                            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("live_index", index))
                        }
                        return@Column
                    }
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
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(c.name, fontSize = 17.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false))
                                                if (c.archiveDays > 0) Text("  ↺ ${c.archiveDays}d", fontSize = 13.sp, color = LogoYellow)
                                            }
                                            ChannelGuide(c)
                                        }
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                if (c.archiveDays > 0) {
                                    FocusBox(onClick = { catchupFor = c }, modifier = Modifier.size(width = 56.dp, height = 50.dp)) {
                                        Text("↺", Modifier.align(Alignment.Center), fontSize = 24.sp, color = LogoYellow)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }
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
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { s.query = ""; s.results = emptyList() } }

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
                Session.vodTypes[s.kind] = type
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
            if (g.id != SEARCH_ID) { s.query = ""; s.results = emptyList() }
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
                RatingBadge(item, Modifier.align(Alignment.BottomEnd))
            }
            Spacer(Modifier.height(6.dp))
            Text(item.name, fontSize = 14.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- details ----------------

@Composable
fun DetailScreen(type: String, start: VodItem, startSeasonId: String? = null, onCast: (() -> Unit)? = null, onAbout: (() -> Unit)? = null, onBack: () -> Unit) {
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
    var menu by remember { mutableStateOf<Pair<String, List<Pair<String, () -> Unit>>>?>(null) }

    /** The furthest point before season [sn] (end of the previous season), or null if it's the first. */
    fun beforeSeason(sn: Int): Pair<Int, Int>? = seasons.orEmpty()
        .mapIndexed { i, s -> Library.seasonNumber(s, i) }.filter { it < sn }.maxOrNull()
        ?.let { it to Library.WHOLE_SEASON }

    fun startOver(): Pair<String, () -> Unit>? =
        if (last != null) "Start the show over" to { Library.setWatched(item.id, null) } else null

    fun episodeMenu(s: Season, si: Int, eps: List<Episode>, ep: Episode, ei: Int) {
        val sn = Library.seasonNumber(s, si); val en = Library.episodeNumber(ep, ei)
        val seen = last != null && (sn < last.first || (sn == last.first && en <= last.second))
        val before = eps.mapIndexed { i, e -> Library.episodeNumber(e, i) }.filter { it < en }.maxOrNull()
            ?.let { sn to it } ?: beforeSeason(sn)
        val sName = s.name.ifBlank { "Season $sn" }
        menu = "S$sn E$en  ·  ${ep.title}" to listOfNotNull(
            "✓  Watched up to here" to { Library.setWatched(item.id, sn to en) },
            "✓  Watched all of $sName" to { Library.setWatched(item.id, sn to Library.WHOLE_SEASON) },
            if (seen) "Not watched from here on" to { Library.setWatched(item.id, before) } else null,
            startOver(),
        )
    }

    fun seasonMenu(s: Season, si: Int) {
        val sn = Library.seasonNumber(s, si)
        val sName = s.name.ifBlank { "Season $sn" }
        menu = sName to listOfNotNull(
            "✓  Watched all of $sName" to { Library.setWatched(item.id, sn to Library.WHOLE_SEASON) },
            if (last != null && last.first >= sn) "Not watched from $sName on" to { Library.setWatched(item.id, beforeSeason(sn)) } else null,
            startOver(),
        )
    }

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
        "key" to "m:${item.id}", "subtitle" to item.year, "poster" to (item.poster ?: item.backdrop),
        "year" to item.year, "plot" to item.description, "is_series" to "0"))

    fun playEpisode(s: Season, si: Int, ep: Episode, ei: Int) {
        val sn = Library.seasonNumber(s, si); val en = Library.episodeNumber(ep, ei)
        Library.markWatched(item.id, sn, en)
        play(ep.cmd, ep.seriesParam, item.name, null, mapOf(
            "key" to "e:${ep.cmd}", "subtitle" to "S$sn E$en  ·  ${ep.title}",
            "poster" to (item.poster ?: ep.thumb), "series_id" to item.id,
            "year" to item.year, "plot" to ep.plot.ifBlank { item.description }, "is_series" to "1",
            "season" to sn.toString(), "episode" to en.toString()))
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
                if (item.year.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(item.year, fontSize = 15.sp, color = TextDim)
                }
                RatingPills(item)
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
                        if (onAbout != null && Session.tmdb != null) TvButton("About", onClick = onAbout)
                        if (onCast != null && Session.tmdb != null) TvButton("Cast & crew", onClick = onCast)
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
                        if (onAbout != null && Session.tmdb != null) TvButton("About", onClick = onAbout)
                        if (onCast != null && Session.tmdb != null) TvButton("Cast & crew", onClick = onCast)
                    }
                    Spacer(Modifier.height(14.dp))
                    if (ss == null) {
                        Text("Loading seasons…", color = TextDim, fontSize = 15.sp)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(ss) { i, s ->
                                FocusBox(onClick = { seasonIdx = i }, selected = i == seasonIdx, shape = RoundedCornerShape(20.dp),
                                    onLongClick = { seasonMenu(s, i) }) {
                                    Text(s.name.ifBlank { "Season ${i + 1}" },
                                        Modifier.padding(horizontal = 16.dp, vertical = 7.dp), fontSize = 14.sp, color = Color.White)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Hold OK on an episode or season to mark it watched.", color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
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
                                    FocusBox(onClick = { playEpisode(cur, seasonIdx, ep, ei) }, modifier = Modifier.fillMaxWidth(),
                                        onLongClick = { episodeMenu(cur, seasonIdx, eps, ep, ei) }) {
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
        menu?.let { (title, options) -> WatchedMenu(title, options, onClose = { menu = null }) }
    }
}

/** Hold-OK menu for marking episodes watched. Ignores the held OK that opened it, so nothing gets picked by accident. */
@Composable
private fun WatchedMenu(title: String, options: List<Pair<String, () -> Unit>>, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(480.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(28.dp)
                .onPreviewKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    when {
                        !ok || armed -> false
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> { armed = true; false }
                        e.type == KeyEventType.KeyUp -> { armed = true; true }
                        else -> true
                    }
                },
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text("Press Back to cancel.", fontSize = 14.sp, color = TextDim)
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEachIndexed { i, (label, action) ->
                    TvButton(label, onClick = { action(); onClose() },
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier))
                }
            }
        }
    }
}


/** The last portal requests and replies, so a photo of the TV shows exactly what the portal said. */
@Composable
fun PortalLogScreen(onClose: () -> Unit) {
    var lines by remember { mutableStateOf(ca.sanjwad.tvplayer.stalker.PortalLog.all().reversed()) }
    var shareMsg by remember { mutableStateOf<String?>(null) }
    val shareScope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(fr) }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Portal log", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            TvButton("Refresh", onClick = { lines = ca.sanjwad.tvplayer.stalker.PortalLog.all().reversed() }, modifier = Modifier.focusRequester(fr))
            Spacer(Modifier.width(10.dp))
            TvButton("Share log", onClick = {
                if (shareMsg != "Uploading…") {
                    shareMsg = "Uploading…"
                    shareScope.launch {
                        shareMsg = withContext(Dispatchers.IO) {
                            runCatching { ca.sanjwad.tvplayer.stalker.PortalLog.share() }
                                .fold({ "Send this link to Claude:  $it" }, { it.message ?: "Couldn't upload the log." })
                        }
                    }
                }
            })
            Spacer(Modifier.width(10.dp))
            TvButton("Close", onClick = onClose)
        }
        shareMsg?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = if (it.startsWith("Send")) 24.sp else 16.sp, fontWeight = FontWeight.Bold,
                color = if (it.startsWith("Couldn't")) ErrorRed else LogoYellow)
            Spacer(Modifier.height(6.dp))
        }
        Text("Newest first. Take a photo of this screen, or press Share log, if something doesn't work.", fontSize = 13.sp, color = TextDim)
        Spacer(Modifier.height(10.dp))
        val pinned = ca.sanjwad.tvplayer.stalker.PortalLog.pinned
        if (pinned.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A2A00)).padding(10.dp)) {
                pinned.forEach { Text(it, fontSize = 13.sp, color = Color(0xFFFFE08A), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (lines.isEmpty()) Text("Nothing yet.", fontSize = 15.sp, color = TextDim)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
            items(lines.size) { i ->
                FocusBox(onClick = {}, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), shape = RoundedCornerShape(6.dp)) {
                    Text(lines[i], Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp, color = Color.White,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }
}
