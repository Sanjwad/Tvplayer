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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import ca.sanjwad.tvplayer.stalker.Ratings
import ca.sanjwad.tvplayer.stalker.Season
import ca.sanjwad.tvplayer.stalker.VodItem
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

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
    // (label, section id) — ids stay stable so saved state keeps working.
    val entries = listOf(
        Triple("Search", 5, ca.sanjwad.tvplayer.R.drawable.ic_menu_search), Triple("Live TV", 0, ca.sanjwad.tvplayer.R.drawable.ic_menu_live),
        Triple("Movies", 1, ca.sanjwad.tvplayer.R.drawable.ic_menu_movies), Triple("TV Shows", 2, ca.sanjwad.tvplayer.R.drawable.ic_menu_shows),
        Triple("My List", 3, ca.sanjwad.tvplayer.R.drawable.ic_menu_list), Triple("Settings", 4, ca.sanjwad.tvplayer.R.drawable.ic_menu_settings),
    )
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { fr.requestFocus() } }

    // Back: first jump to the menu; from the menu, ask before closing the app.
    val ctx = LocalContext.current
    var menuFocused by remember { mutableStateOf(false) }
    var askExit by remember { mutableStateOf(false) }
    BackHandler(enabled = !askExit) {
        if (!menuFocused) runCatching { fr.requestFocus() } else askExit = true
    }

    // The menu folds down to a slim strip of icons while you browse, giving the posters the room,
    // and opens again when you move left onto it (or press Back), like TiVimate and Netflix.
    val menuWidth by androidx.compose.animation.core.animateDpAsState(if (menuFocused) 210.dp else 72.dp, label = "menu")
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(menuWidth).fillMaxHeight().background(Rail)
                    .padding(horizontal = if (menuFocused) 14.dp else 10.dp, vertical = 24.dp)
                    .onFocusChanged { menuFocused = it.hasFocus },
            ) {
                if (menuFocused) Logo(22.sp, Modifier.padding(start = 6.dp))
                else androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(ca.sanjwad.tvplayer.R.drawable.ic_launcher), contentDescription = "Sanj TV",
                    modifier = Modifier.padding(start = 6.dp).size(34.dp),
                )
                Spacer(Modifier.height(if (menuFocused) 36.dp else 34.dp))
                entries.forEach { (label, i, icon) ->
                    FocusBox(
                        onClick = { if (i == 4) onSettings() else onSection(i) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .then(if (i == section) Modifier.focusRequester(fr) else Modifier),
                        selected = i == section,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(
                                painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null,
                                tint = when { i == section -> Accent; i == 3 -> LogoYellow; else -> Color(0xFFC9D1DE) },
                                modifier = Modifier.size(20.dp),
                            )
                            if (menuFocused) {
                                Spacer(Modifier.width(14.dp))
                                Text(label, fontSize = 17.sp, color = if (i == 3) LogoYellow else Color.White, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                when (section) {
                    0 -> LiveScreen(live)
                    1 -> VodTab(movies) { onOpen(movies.type, it, null) }
                    2 -> VodTab(series) { onOpen(series.type, it, null) }
                    5 -> GlobalSearch(movies.type, series.type, onOpen)
                    else -> MyListScreen(onOpen)
                }
            }
        }
        if (askExit) ExitDialog(
            onClose = { (ctx as? android.app.Activity)?.finish() },
            onStay = { askExit = false; runCatching { fr.requestFocus() } },
        )
    }
}

@Composable
private fun ExitDialog(onClose: () -> Unit, onStay: () -> Unit) {
    val yes = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { yes.requestFocus() } }
    // A real dialog window, so the cursor can't wander onto the screen underneath. Back = Stay.
    androidx.compose.ui.window.Dialog(onDismissRequest = onStay,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(28.dp)) {
            Text("Close Sanj TV?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("Press Back to stay.", fontSize = 14.sp, color = TextDim)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Yes, close", onClick = onClose, modifier = Modifier.focusRequester(yes))
                TvButton("Stay", onClick = onStay)
            }
        }
    }
}

// ---------------- ratings ----------------

private fun isRealTitle(item: VodItem) = !item.id.startsWith("m:") && !item.id.startsWith("e:")

/** Small "★ 7.3" badge for a poster: the provider's score, else TMDB's. */
@Composable
fun RatingBadge(item: VodItem, modifier: Modifier = Modifier) {
    if (!isRealTitle(item)) return
    val score by produceState(Ratings.providerScore(item) ?: Ratings.peekTmdb(item), item.id) {
        value = Ratings.providerScore(item) ?: Ratings.peekTmdb(item) // reset for this title
        if (value == null) {
            val t = Session.tmdb ?: return@produceState
            value = withContext(Dispatchers.IO) { runCatching { Ratings.tmdb(item, t) }.getOrNull() }
        }
    }
    val s = score
    if (s.isNullOrBlank()) return
    Text("★ $s", modifier.padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000))
        .padding(horizontal = 5.dp, vertical = 1.dp), color = GoldTint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Pill(label: String, value: String, bg: Color, fg: Color) {
    Row(Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0x33FFFFFF)), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.background(bg).padding(horizontal = 6.dp, vertical = 2.dp), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Text(value, Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** IMDb and TMDB ratings for the featured area and movie/show pages. */
@Composable
fun RatingPills(item: VodItem) {
    val tmdb by produceState(Ratings.peekTmdb(item), item.id) {
        value = Ratings.peekTmdb(item) // reset for this title, don't keep the previous one's
        if (value == null) {
            val t = Session.tmdb ?: return@produceState
            value = withContext(Dispatchers.IO) { runCatching { Ratings.tmdb(item, t) }.getOrNull() }
        }
    }
    val imdb by produceState(Ratings.peekImdb(item), item.id) {
        value = Ratings.peekImdb(item)
        if (value == null) {
            val o = Session.omdb ?: return@produceState
            delay(300) // only look up titles that stay on screen
            value = withContext(Dispatchers.IO) { runCatching { Ratings.imdb(item, o, Session.tmdb) }.getOrNull() }
        }
    }
    val provider = Ratings.providerScore(item)
    val i = imdb; val t = tmdb
    if (i.isNullOrBlank() && t.isNullOrBlank() && provider == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        if (!i.isNullOrBlank()) Pill("IMDb", i, Color(0xFFF5C518), Color.Black)
        if (!t.isNullOrBlank()) Pill("TMDB", t, Color(0xFF01B4E4), Color(0xFF0D253F))
        else if (provider != null) Pill("★", provider, GoldTint, Color.Black)
    }
}

// ---------------- posters ----------------

@Composable
fun PosterTile(item: VodItem, onFocus: () -> Unit = {}, badge: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier.width(112.dp), onFocus = onFocus, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(4.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Bg)) {
                Text(item.name, Modifier.align(Alignment.Center).padding(6.dp), color = TextDim, fontSize = 12.sp, maxLines = 4)
                AsyncImage(model = item.poster, contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                RatingBadge(item, Modifier.align(Alignment.BottomEnd))
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
    firstItem: Modifier = Modifier,
    /** Coming back from "See all": open scrolled to the end with the cursor on "See all". */
    focusSeeAll: Boolean = false, onSeeAllFocused: () -> Unit = {},
    /** Any tile in this row got the cursor (used to line the row up with the top). */
    onRowFocus: () -> Unit = {},
) {
    Column {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        when {
            list == null -> Text("Loading…", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            list.isEmpty() -> Text("Nothing here", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            else -> {
              val rowState = androidx.compose.foundation.lazy.rememberLazyListState(if (focusSeeAll) list.size else 0)
              val seeAll = remember { FocusRequester() }
              if (focusSeeAll && onSeeAll != null) LaunchedEffect(Unit) {
                  delay(150); runCatching { seeAll.requestFocus() }; onSeeAllFocused()
              }
              LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list.size) { i -> val item = list[i]
                    PosterTile(item, { onFocusItem(item); onRowFocus() }, badgeFor(item), if (i == 0) firstItem else Modifier) { onOpen(item) } }
                if (onSeeAll != null) item {
                    FocusBox(onClick = onSeeAll, modifier = Modifier.focusRequester(seeAll).width(112.dp).aspectRatio(0.6f), shape = RoundedCornerShape(8.dp),
                        onFocus = onRowFocus) {
                        Text("See all ›", Modifier.align(Alignment.Center), color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
              }
            }
        }
    }
}

// ---------------- featured area ----------------

@Composable
private fun Hero(item: VodItem?) {
    // Always the same height, even before anything is selected, so the rows never jump.
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
            val meta = listOfNotNull(item.year.ifBlank { null }, if (item.isSeries) "Series" else null)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(meta.joinToString("  ·  "), fontSize = 14.sp, color = TextDim)
            }
            RatingPills(item)
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
    if (s.editMode) {
        BackHandler { s.editMode = false }
        ManageGroups(s.kind, s.categories) { s.editMode = false }
    } else if (s.gridMode) {
        BackHandler { s.gridMode = false }
        VodScreen(s, onOpen)
    } else {
        VodRows(s, onOpen)
    }
}

/** Loads a Movies/TV Shows tab's lists; used both in the background at startup and when the tab opens. */
object VodLoader {
    suspend fun categories(s: VodState, showErrors: Boolean) = s.catsLock.withLock {
        if (s.catsLoaded) return@withLock
        if (showErrors) s.error = null
        val r = withContext(Dispatchers.IO) {
            runCatching { (Session.client ?: error("Not connected")).browseCategories(s.kind) }
        }
        r.exceptionOrNull()?.let {
            if (showErrors) s.error = if (it is java.io.InterruptedIOException) "Your provider took too long to reply." else it.message
        }
        r.getOrNull()?.let { (type, cats) -> Library.noteCategories(s.kind, cats); s.type = type; Session.vodTypes[s.kind] = type; s.categories = cats; s.catsLoaded = true }
    }

    /** Trending rows from TMDB, matched to this provider's catalogue. */
    suspend fun trending(s: VodState) = s.trendLock.withLock {
        val tmdb = Session.tmdb ?: return@withLock
        if (!s.catsLoaded || s.trending.any { it.second != null }) return@withLock
        val rows: List<Pair<String, () -> List<ca.sanjwad.tvplayer.stalker.TmdbClient.Hit>>> =
            if (s.kind == "series") listOf("🔥  Trending TV shows" to tmdb::trendingTv, "⭐  Popular TV shows" to tmdb::popularTv)
            else listOf("🔥  Trending this week" to tmdb::trendingMovies, "🎟  In theatres now" to tmdb::inTheatres)
        s.trending = rows.map { it.first to null }
        val catalogue = withContext(Dispatchers.IO) {
            runCatching { Session.client?.catalogue(s.type) }.getOrNull()
        }.orEmpty()
        s.trending = rows.map { (title, fetch) ->
            val hits = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrDefault(emptyList()) }
            title to ca.sanjwad.tvplayer.stalker.TitleMatch.match(hits, catalogue)
        }
    }

    suspend fun row(s: VodState, categoryId: String) {
        if (s.rowItems[categoryId] != null) return
        // A failed request isn't an empty row: keep it "Loading…" and keep trying while it's on screen
        // (this stops when the row scrolls away, and starts again when it comes back).
        var attempt = 0
        while (s.rowItems[categoryId] == null) {
            val page = withContext(Dispatchers.IO) {
                runCatching { Session.client?.vodPage(s.type, categoryId, 1) }.getOrNull()
            }
            val list = page?.items
            if (list != null) {
                if (s.rowItems[categoryId] == null) {
                    s.rowItems[categoryId] = list.take(40)
                    s.rowHasMore[categoryId] = list.size > 40 || page.totalItems > minOf(list.size, 40)
                }
                break
            }
            attempt++
            delay(minOf(2_000L * attempt, 15_000L))
        }
    }

    /** Everything the top of the tab needs: categories, trending rows, the first few category rows. */
    suspend fun preload(s: VodState) {
        categories(s, showErrors = false)
        if (!s.catsLoaded) return
        // The rows you see first, then the slow trending rows.
        s.categories.filter { !Library.isHidden(s.kind, it.id) }.take(4).forEach { row(s, it.id) }
        trending(s)
    }
}

@Composable
private fun VodRows(s: VodState, onOpen: (VodItem) -> Unit) {
    var waited by remember { mutableStateOf(0) }
    LaunchedEffect(s.kind, s.catsAttempt) { VodLoader.categories(s, showErrors = true) }
    // Count seconds while waiting, so a slow provider is obvious rather than looking frozen.
    LaunchedEffect(s.catsLoaded, s.catsAttempt) {
        waited = 0
        while (!s.catsLoaded && s.error == null) { delay(1000); waited++ }
    }
    // Trending rows from TMDB, matched to this provider's catalogue.
    LaunchedEffect(s.kind, s.catsLoaded) { VodLoader.trending(s) }
    val (hero, setHero) = rememberHero()
    val label = kindLabel(s.kind)
    // Show the first title straight away so the panel is filled before you move into the rows.
    val firstTitle = s.trending.firstNotNullOfOrNull { it.second?.firstOrNull() }
        ?: s.categories.firstNotNullOfOrNull { c -> if (Library.isHidden(s.kind, c.id)) null else s.rowItems[c.id]?.firstOrNull() }
    LaunchedEffect(firstTitle?.id) { if (hero == null && firstTitle != null) setHero(firstTitle) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState(s.rowsIndex, s.rowsOffset)

    fun openGrid(categoryId: String) {
        s.rowsIndex = listState.firstVisibleItemIndex; s.rowsOffset = listState.firstVisibleItemScrollOffset
        s.returnTo = categoryId
        s.category = categoryId; s.items = emptyList(); s.total = 0; s.page = 0
        s.gridMode = true
        s.focusGridOnce = true
    }

    Column(Modifier.fillMaxSize()) {
        Hero(hero)
        Spacer(Modifier.height(6.dp))
        val err = s.error
        when {
            !s.catsLoaded && err != null -> Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load $label", fontSize = 18.sp, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(err, fontSize = 14.sp, color = TextDim)
                Spacer(Modifier.height(16.dp))
                TvButton("Try again", onClick = { s.catsAttempt++ })
            }
            !s.catsLoaded -> CenterMessage("Loading $label…",
                if (waited >= 8) "Waiting for your provider… ${waited}s" else null, loading = true)
            s.categories.isEmpty() -> CenterMessage("No $label on this server")
            else -> {
                val rowScope = rememberCoroutineScope()
                // Moving between rows lines the row with the cursor up at the top, so it's always shown whole.
                fun alignRow(index: Int) {
                    if (listState.firstVisibleItemIndex == index && listState.firstVisibleItemScrollOffset == 0) return
                    rowScope.launch { delay(30); listState.animateScrollToItem(index) }
                }
                val trendRows = s.trending.withIndex().filter { (_, t) -> t.second == null || t.second!!.isNotEmpty() }
                val cats = s.categories.filter { !Library.isHidden(s.kind, it.id) }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    trendRows.forEachIndexed { pos, (i, t) ->
                        item("trend$i") {
                            PosterRow(t.first, t.second, setHero, onOpen, onRowFocus = { alignRow(pos) })
                        }
                    }
                    itemsIndexed(cats, key = { _, c -> c.id }) { ci, cat ->
                        LaunchedEffect(cat.id) { VodLoader.row(s, cat.id) }
                        PosterRow(cat.title, s.rowItems[cat.id], setHero, onOpen, onSeeAll = if (s.rowHasMore[cat.id] == true) ({ openGrid(cat.id) }) else null,
                            focusSeeAll = s.returnTo == cat.id, onSeeAllFocused = { s.returnTo = null },
                            onRowFocus = { alignRow(trendRows.size + ci) })
                    }
                }
            }
        }
    }
}

// ---------------- search everything ----------------

@Composable
fun GlobalSearch(movieType: String, showType: String, onOpen: (type: String, item: VodItem, seasonId: String?) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") } // starts empty each time
    var channels by remember { mutableStateOf<List<ca.sanjwad.tvplayer.stalker.Channel>?>(emptyList()) }
    var movies by remember { mutableStateOf<List<VodItem>?>(emptyList()) }
    var shows by remember { mutableStateOf<List<VodItem>?>(emptyList()) }
    val fr = remember { FocusRequester() }
    val firstChannel = remember { FocusRequester() }
    val firstMovie = remember { FocusRequester() }
    val firstShow = remember { FocusRequester() }
    // Set when the keyboard's search key is pressed: jump to the first result as soon as there is one.
    var wantResults by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { channels = emptyList(); movies = emptyList(); shows = emptyList(); return@LaunchedEffect }
        delay(500)
        channels = null; movies = null; shows = null
        val c = Session.client ?: return@LaunchedEffect
        val lower = q.lowercase()
        channels = withContext(Dispatchers.IO) {
            runCatching { c.liveChannels().filter { it.name.lowercase().contains(lower) || it.number.toString() == q }.take(40) }
                .getOrDefault(emptyList())
        }
        movies = withContext(Dispatchers.IO) { runCatching { c.search(movieType, q).filterNot { it.isSeries } }.getOrDefault(emptyList()) }
        shows = withContext(Dispatchers.IO) {
            runCatching { c.search(showType, q).let { l -> if (showType == movieType) l.filter { it.isSeries } else l } }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(wantResults, channels, movies, shows) {
        if (!wantResults) return@LaunchedEffect
        val target = when {
            !channels.isNullOrEmpty() -> firstChannel
            !movies.isNullOrEmpty() -> firstMovie
            !shows.isNullOrEmpty() -> firstShow
            channels != null && movies != null && shows != null -> { wantResults = false; null } // nothing found
            else -> null // still searching; try again when results arrive
        } ?: return@LaunchedEffect
        delay(80)
        if (runCatching { target.requestFocus() }.isSuccess) wantResults = false
    }
    LaunchedEffect(Unit) { delay(200); runCatching { fr.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it; wantResults = false }, "Search channels, movies and TV shows", Modifier.focusRequester(fr),
            onSearch = { wantResults = true })
        val indexStatus by produceState((Session.client as? ca.sanjwad.tvplayer.stalker.StalkerClient)?.indexStatus.orEmpty()) {
            while (true) { value = (Session.client as? ca.sanjwad.tvplayer.stalker.StalkerClient)?.indexStatus.orEmpty(); delay(3000) }
        }
        if (indexStatus.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("$indexStatus  Results so far may be incomplete.", fontSize = 12.sp, color = LogoYellow, modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.height(14.dp))
        val q = query.trim()
        if (q.length < 2) {
            CenterMessage("Type or speak at least two letters", "Results from Live TV, Movies and TV Shows appear here.")
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column {
                    Text("Live TV", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                    val ch = channels
                    when {
                        ch == null -> Text("Searching…", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                        ch.isEmpty() -> Text("No channels match", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ch.size) { i ->
                                val c = ch[i]
                                FocusBox(onClick = {
                                    Session.channels = ch
                                    ctx.startActivity(android.content.Intent(ctx, ca.sanjwad.tvplayer.PlayerActivity::class.java).putExtra("live_index", i))
                                }, modifier = (if (i == 0) Modifier.focusRequester(firstChannel) else Modifier).width(150.dp), shape = RoundedCornerShape(8.dp)) {
                                    Column(Modifier.padding(8.dp)) {
                                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Bg)) {
                                            AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.fillMaxSize().padding(10.dp),
                                                contentScale = ContentScale.Fit)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text((if (c.number > 0) "${c.number}  " else "") + c.name, fontSize = 12.sp, color = Color.White,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { PosterRow("Movies", movies, {}, { onOpen(movieType, it, null) }, firstItem = Modifier.focusRequester(firstMovie)) }
            item { PosterRow("TV Shows", shows, {}, { onOpen(showType, it, null) }, firstItem = Modifier.focusRequester(firstShow)) }
        }
    }
}

// ---------------- smart My List ----------------

@Composable
private fun ResumeMenu(
    p: ca.sanjwad.tvplayer.Progress,
    onResume: () -> Unit, onStart: () -> Unit, onPage: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
    val left = ((p.durMs - p.posMs) / 60_000).coerceAtLeast(1)
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(24.dp)) {
            Text(p.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.subtitle.isNotBlank() && p.seriesId != null) Text(p.subtitle, fontSize = 14.sp, color = TextDim, maxLines = 1)
            Spacer(Modifier.height(16.dp))
            listOf(
                "▶   Resume  ·  ${left}m left" to onResume,
                "⟲   Play from the start" to onStart,
                (if (p.seriesId != null) "☰   Go to the show page" else "☰   Go to the movie page") to onPage,
                "✕   Remove from Continue watching" to onRemove,
            ).forEachIndexed { i, (label, action) ->
                FocusBox(onClick = action, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier), shape = RoundedCornerShape(8.dp)) {
                    Text(label, Modifier.padding(horizontal = 14.dp, vertical = 11.dp), fontSize = 16.sp,
                        color = if (i == 3) ErrorRed else Color.White)
                }
            }
        }
    }
}

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

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val resume = Library.progress.values.sortedByDescending { it.updated }
    if (saved.isEmpty() && resume.isEmpty()) {
        CenterMessage("Your list is empty", "Open any movie or show and choose ＋ My List.")
        return
    }

    var menuFor by remember { mutableStateOf<ca.sanjwad.tvplayer.Progress?>(null) }

    fun resumePlay(p: ca.sanjwad.tvplayer.Progress, fromStart: Boolean = false) {
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                runCatching { Session.client?.createLink("vod", p.cmd, "", null) }.getOrNull()
            } ?: p.cmd
            ctx.startActivity(android.content.Intent(ctx, ca.sanjwad.tvplayer.PlayerActivity::class.java)
                .putExtra("url", url).putExtra("cmd", p.cmd).putExtra("key", p.key).putExtra("title", p.title)
                .putExtra("subtitle", p.subtitle).putExtra("poster", p.poster).putExtra("series_id", p.seriesId)
                .putExtra("from_start", fromStart))
        }
    }

    fun openTitlePage(p: ca.sanjwad.tvplayer.Progress) {
        val sid = p.seriesId
        if (sid != null) {
            val show = Library.myList.firstOrNull { it.item.id == sid }
            if (show != null) onOpen(show.type, show.item, null)
            else onOpen("series", VodItem(sid, p.title, "", p.poster, "", true, "", ""), null)
        } else {
            val id = p.key.removePrefix("m:")
            onOpen("vod", VodItem(id, p.title, "", p.poster, p.cmd, false, p.subtitle, ""), null)
        }
    }

    @Composable
    fun showRow(title: String, rows: List<Pair<ca.sanjwad.tvplayer.SavedItem, ShowStatus?>>) {
        if (rows.isEmpty()) return
        val byId = rows.associate { it.first.item.id to it }
        PosterRow(title, rows.map { it.first.item }, setHero,
            onOpen = { item -> byId[item.id]?.let { (sv, st) -> onOpen(sv.type, item, st?.seasonId) } },
            badgeFor = { item -> byId[item.id]?.second?.badge })
    }

    val firstSaved = (newEps + notStarted + caughtUp).firstOrNull()?.first?.item ?: movies.firstOrNull()?.item
    LaunchedEffect(firstSaved?.id) { if (hero == null && firstSaved != null) setHero(firstSaved) }
    Column(Modifier.fillMaxSize()) {
        Hero(hero)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (resume.isNotEmpty()) item {
                val byKey = resume.associateBy { it.key }
                val tiles = resume.map { p ->
                    VodItem(id = p.key, name = if (p.subtitle.isNotBlank() && p.seriesId != null) "${p.title} · ${p.subtitle}" else p.title,
                        description = p.subtitle, poster = p.poster, cmd = p.cmd, isSeries = false, year = "", rating = "")
                }
                PosterRow("▶  Continue watching", tiles, {},
                    onOpen = { v -> byKey[v.id]?.let { menuFor = it } },
                    badgeFor = { v -> byKey[v.id]?.let { "${((it.durMs - it.posMs) / 60_000).coerceAtLeast(1)}m left" } })
            }
            item { showRow("New episodes", newEps) }
            item { showRow("Start watching", notStarted) }
            item { showRow("Checking for new episodes…", checking) }
            if (movies.isNotEmpty()) item {
                val byId = movies.associateBy { it.item.id }
                PosterRow("Movies", movies.map { it.item }, setHero,
                    onOpen = { item -> byId[item.id]?.let { onOpen(it.type, item, null) } })
            }
            item { showRow("Caught up — waiting for new episodes", caughtUp) }
        }
    }
    menuFor?.let { p ->
        ResumeMenu(p,
            onResume = { menuFor = null; resumePlay(p) },
            onStart = { menuFor = null; resumePlay(p, fromStart = true) },
            onPage = { menuFor = null; openTitlePage(p) },
            onRemove = { menuFor = null; Library.removeProgress(p.key) },
            onDismiss = { menuFor = null })
    }
}
