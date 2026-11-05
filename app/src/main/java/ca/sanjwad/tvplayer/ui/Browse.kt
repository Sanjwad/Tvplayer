package ca.sanjwad.tvplayer.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.sanjwad.tvplayer.Library
import ca.sanjwad.tvplayer.Session
import ca.sanjwad.tvplayer.stalker.Genre
import ca.sanjwad.tvplayer.stalker.Season
import ca.sanjwad.tvplayer.stalker.VodItem
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Rail = Color(0xFF121826)
private val GoldTint = Color(0xFFFFC94D)

// ---------------- shell with side menu ----------------

@Composable
fun HomeScreen(
    section: Int, onSection: (Int) -> Unit,
    live: LiveState, movies: VodState, series: VodState,
    onOpen: (type: String, item: VodItem, seasonId: String?) -> Unit,
    onSettings: () -> Unit,
) {
    val entries = listOf("📺   Live TV", "🎬   Movies", "🎞   Series", "★   My List", "⚙   Settings")
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { fr.requestFocus() } }

    // Back: first jump to the menu; from the menu, press twice quickly to leave the app.
    val ctx = LocalContext.current
    var menuFocused by remember { mutableStateOf(false) }
    var lastBack by remember { mutableStateOf(0L) }
    BackHandler {
        if (!menuFocused) runCatching { fr.requestFocus() }
        else {
            val now = System.currentTimeMillis()
            if (now - lastBack < 2000) (ctx as? android.app.Activity)?.finish()
            else {
                lastBack = now
                android.widget.Toast.makeText(ctx, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(210.dp).fillMaxHeight().background(Rail)
                .padding(horizontal = 14.dp, vertical = 24.dp)
                .onFocusChanged { menuFocused = it.hasFocus },
        ) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 8.dp)) {
                Text("sanj", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Accent)
                Spacer(Modifier.width(4.dp))
                Text("tv", fontSize = 30.sp, fontWeight = FontWeight.Light, color = Color.White)
            }
            Spacer(Modifier.height(36.dp))
            entries.forEachIndexed { i, label ->
                FocusBox(
                    onClick = { if (i == 4) onSettings() else onSection(i) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .then(if (i == section) Modifier.focusRequester(fr) else Modifier),
                    selected = i == section,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(label, Modifier.padding(horizontal = 14.dp, vertical = 11.dp), fontSize = 17.sp,
                        color = if (i == 3) GoldTint else Color.White)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            when (section) {
                0 -> LiveScreen(live)
                1 -> VodTab(movies) { onOpen(movies.type, it, null) }
                2 -> VodTab(series) { onOpen(series.type, it, null) }
                else -> MyListScreen(onOpen)
            }
        }
    }
}

// ---------------- posters ----------------

@Composable
fun PosterTile(item: VodItem, onFocus: () -> Unit = {}, badge: String? = null, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.width(112.dp), onFocus = onFocus, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(4.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Bg)) {
                Text(item.name, Modifier.align(Alignment.Center).padding(6.dp), color = TextDim, fontSize = 12.sp, maxLines = 4)
                AsyncImage(model = item.poster, contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (badge != null) {
                    Text(badge, Modifier.align(Alignment.TopStart).padding(5.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Accent).padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color(0xFF0A101E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(item.name, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PosterRow(
    title: String, list: List<VodItem>?, onFocusItem: (VodItem) -> Unit, onOpen: (VodItem) -> Unit,
    badgeFor: (VodItem) -> String? = { null }, onSeeAll: (() -> Unit)? = null,
) {
    Column {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        when {
            list == null -> Text("Loading…", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            list.isEmpty() -> Text("Nothing here", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list) { item -> PosterTile(item, { onFocusItem(item) }, badgeFor(item)) { onOpen(item) } }
                if (onSeeAll != null) item {
                    FocusBox(onClick = onSeeAll, modifier = Modifier.width(112.dp).aspectRatio(0.6f), shape = RoundedCornerShape(8.dp)) {
                        Text("See all ›", Modifier.align(Alignment.Center), color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ---------------- featured area ----------------

@Composable
private fun Hero(item: VodItem?) {
    Box(Modifier.fillMaxWidth().height(220.dp)) {
        if (item == null) return@Box
        AsyncImage(
            model = item.backdrop ?: item.poster, contentDescription = null,
            modifier = Modifier.fillMaxHeight().fillMaxWidth(0.62f).align(Alignment.TopEnd).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
        // Fade the picture into the background on the left so the text stays readable.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0.0f to Bg, 0.38f to Bg, 0.62f to Bg.copy(alpha = 0.55f), 1.0f to Color.Transparent)))
        Box(Modifier.fillMaxWidth().height(60.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Bg))))
        Column(Modifier.fillMaxWidth(0.55f).padding(top = 8.dp)) {
            Text(item.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(item.year.ifBlank { null }, item.rating.ifBlank { null }?.let { "★ $it" },
                if (item.isSeries) "Series" else null)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(meta.joinToString("  ·  "), fontSize = 14.sp, color = TextDim)
            }
            if (item.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(item.description, fontSize = 14.sp, color = Color(0xFFD5DAE1), maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Follows the focused title and fills in its description/backdrop after a short pause. */
@Composable
private fun rememberHero(): Pair<VodItem?, (VodItem) -> Unit> {
    var focused by remember { mutableStateOf<VodItem?>(null) }
    var shown by remember { mutableStateOf<VodItem?>(null) }
    LaunchedEffect(focused?.id) {
        val f = focused ?: return@LaunchedEffect
        shown = f
        if (f.isSeries || (f.description.isNotBlank() && f.backdrop != null)) return@LaunchedEffect
        delay(400)
        val full = withContext(Dispatchers.IO) {
            runCatching { Session.client?.movieInfo(f) }.getOrNull()
        }
        if (full != null && focused?.id == f.id) shown = full
    }
    val setter: (VodItem) -> Unit = { focused = it }
    return shown to setter
}

// ---------------- movies & series tab ----------------

@Composable
fun VodTab(s: VodState, onOpen: (VodItem) -> Unit) {
    if (s.gridMode) {
        BackHandler { s.gridMode = false }
        VodScreen(s, onOpen)
    } else {
        VodRows(s, onOpen)
    }
}

@Composable
private fun VodRows(s: VodState, onOpen: (VodItem) -> Unit) {
    LaunchedEffect(s.kind) {
        if (!s.catsLoaded) {
            val r = withContext(Dispatchers.IO) {
                runCatching { (Session.client ?: error("Not connected")).browseCategories(s.kind) }
            }
            r.exceptionOrNull()?.let { s.error = it.message }
            r.getOrNull()?.let { (type, cats) -> s.type = type; s.categories = cats; s.catsLoaded = true }
        }
    }
    val (hero, setHero) = rememberHero()

    fun openGrid(categoryId: String) {
        s.category = categoryId; s.items = emptyList(); s.total = 0; s.page = 0
        s.gridMode = true
    }

    Column(Modifier.fillMaxSize()) {
        Hero(hero)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            FocusBox(onClick = { openGrid("__search") }, shape = RoundedCornerShape(16.dp)) {
                Text("🔍  Search", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 14.sp, color = Color.White)
            }
            FocusBox(onClick = { openGrid(s.categories.firstOrNull()?.id ?: "__list") }, shape = RoundedCornerShape(16.dp)) {
                Text("☰  All categories", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 14.sp, color = Color.White)
            }
        }
        val err = s.error
        when {
            !s.catsLoaded && err != null -> CenterMessage("Couldn't load ${s.kind}", err)
            !s.catsLoaded -> CenterMessage("Loading ${s.kind}…", loading = true)
            s.categories.isEmpty() -> CenterMessage("No ${s.kind} on this server")
            else -> {
                val saved = Library.myList.filter { it.kind == s.kind }.map { it.item }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (saved.isNotEmpty()) item("mylist") {
                        PosterRow("★  My List", saved, setHero, onOpen)
                    }
                    items(s.categories, key = { it.id }) { cat ->
                        LaunchedEffect(cat.id) {
                            if (s.rowItems[cat.id] == null) {
                                val list = withContext(Dispatchers.IO) {
                                    runCatching { Session.client?.vodPage(s.type, cat.id, 1)?.items }.getOrNull()
                                }
                                s.rowItems[cat.id] = list.orEmpty().take(40)
                            }
                        }
                        PosterRow(cat.title, s.rowItems[cat.id], setHero, onOpen, onSeeAll = { openGrid(cat.id) })
                    }
                }
            }
        }
    }
}

// ---------------- smart My List ----------------

private data class ShowStatus(val badge: String, val seasonId: String?, val group: Int)

@Composable
fun MyListScreen(onOpen: (type: String, item: VodItem, seasonId: String?) -> Unit) {
    val saved = Library.myList
    val watched = Library.watched
    val seasonsById = remember { mutableStateMapOf<String, List<Season>>() }
    val (hero, setHero) = rememberHero()

    // Fetch each saved show's episode list (cached for the session) to work out what's next.
    LaunchedEffect(saved.map { it.item.id }) {
        for (sv in saved.filter { it.item.isSeries }) {
            val id = sv.item.id
            val cached = Session.seasonCache[id]
            if (cached != null) { seasonsById[id] = cached; continue }
            val list = withContext(Dispatchers.IO) {
                runCatching { Session.client?.seasons(sv.type, sv.item) }.getOrNull()
            }
            if (list == null) continue
            Session.seasonCache[id] = list
            seasonsById[id] = list
        }
    }

    fun status(item: VodItem): ShowStatus? {
        val seasons = seasonsById[item.id] ?: return null
        val last = watched[item.id]
        val next = Library.nextEpisode(seasons, last)
        return when {
            next == null -> ShowStatus("Caught up", null, 2)
            last == null -> ShowStatus("S${next.third.first} E${next.third.second}", next.first.id, 1)
            else -> ShowStatus("S${next.third.first} E${next.third.second}", next.first.id, 0)
        }
    }

    val shows = saved.filter { it.item.isSeries }
    val movies = saved.filter { !it.item.isSeries }
    val withStatus = shows.map { it to status(it.item) }
    val newEps = withStatus.filter { it.second?.group == 0 }
    val notStarted = withStatus.filter { it.second?.group == 1 }
    val caughtUp = withStatus.filter { it.second?.group == 2 }
    val checking = withStatus.filter { it.second == null }

    if (saved.isEmpty()) {
        CenterMessage("Your list is empty", "Open any movie or show and choose ＋ My List.")
        return
    }

    @Composable
    fun showRow(title: String, rows: List<Pair<ca.sanjwad.tvplayer.SavedItem, ShowStatus?>>) {
        if (rows.isEmpty()) return
        val byId = rows.associate { it.first.item.id to it }
        PosterRow(title, rows.map { it.first.item }, setHero,
            onOpen = { item -> byId[item.id]?.let { (sv, st) -> onOpen(sv.type, item, st?.seasonId) } },
            badgeFor = { item -> byId[item.id]?.second?.badge })
    }

    Column(Modifier.fillMaxSize()) {
        Hero(hero)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { showRow("New episodes", newEps) }
            item { showRow("Start watching", notStarted) }
            item { showRow("Checking for new episodes…", checking) }
            if (movies.isNotEmpty()) item {
                val byId = movies.associateBy { it.item.id }
                PosterRow("Movies", movies.map { it.item }, setHero,
                    onOpen = { item -> byId[item.id]?.let { onOpen(it.type, item, null) } })
            }
            item { showRow("Caught up — waiting for new seasons", caughtUp) }
        }
    }
}
