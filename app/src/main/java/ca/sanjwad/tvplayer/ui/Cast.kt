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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.sanjwad.tvplayer.Session
import ca.sanjwad.tvplayer.stalker.TitleMatch
import ca.sanjwad.tvplayer.stalker.TmdbClient
import ca.sanjwad.tvplayer.stalker.VodItem
import ca.sanjwad.tvplayer.stalker.XtreamClient
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Where you are in the cast pages: the title, then the actors you've opened, in order. */
class CastNav(val title: String, val year: String, val isSeries: Boolean) {
    val people = mutableStateListOf<Int>()
}

/** Finds an actor's films and shows in this provider's library. */
object CastLibrary {
    private val catalogues = java.util.concurrent.ConcurrentHashMap<String, List<VodItem>>()
    private val searched = java.util.concurrent.ConcurrentHashMap<String, List<VodItem>>()

    fun typeFor(kind: String): String = Session.vodTypes[kind] ?: run {
        val t = runCatching { Session.client?.browseCategories(kind)?.first }.getOrNull()
            ?: if (kind == "series") "series" else "vod"
        Session.vodTypes[kind] = t
        t
    }

    private fun catalogue(type: String): List<VodItem> = catalogues[type] ?: run {
        val c = runCatching { Session.client?.catalogue(type) }.getOrNull().orEmpty()
        if (c.isNotEmpty()) catalogues[type] = c
        c
    }

    private fun yearOf(v: VodItem): Int? =
        v.year.take(4).toIntOrNull() ?: Regex("\\((\\d{4})\\)").find(v.name)?.groupValues?.get(1)?.toIntOrNull()

    private fun pick(cands: List<VodItem>, c: TmdbClient.Credit): VodItem? {
        val want = TitleMatch.norm(c.title)
        val cy = c.year.toIntOrNull()
        return cands.filter { TitleMatch.norm(it.name) == want && it.isSeries == c.isSeries || TitleMatch.norm(it.name) == want }
            .firstOrNull { v -> val vy = yearOf(v); cy == null || vy == null || abs(vy - cy) <= 1 }
    }

    /** Credit → (portal type, title in the library) for everything the provider has. */
    fun match(credits: List<TmdbClient.Credit>): Map<TmdbClient.Credit, Pair<String, VodItem>> {
        val client = Session.client ?: return emptyMap()
        val out = LinkedHashMap<TmdbClient.Credit, Pair<String, VodItem>>()
        for (kind in listOf("movies", "series")) {
            val wanted = credits.filter { it.isSeries == (kind == "series") }
            if (wanted.isEmpty()) continue
            val type = typeFor(kind)
            val index = catalogue(type).groupBy { TitleMatch.norm(it.name) }
            val missing = mutableListOf<TmdbClient.Credit>()
            for (c in wanted) {
                val hit = index[TitleMatch.norm(c.title)]?.let { pick(it, c) }
                if (hit != null) out[c] = type to hit else missing += c
            }
            // Stalker portals only give us the newest titles up front, so search for the best-known rest.
            if (client !is XtreamClient) {
                for (c in missing.take(12)) {
                    val q = TitleMatch.queryName(c.title)
                    val res = searched.getOrPut("$type|$q") { runCatching { client.search(type, q) }.getOrDefault(emptyList()) }
                    pick(res, c)?.let { out[c] = type to it }
                }
            }
        }
        return out.mapValues { (c, v) ->
            v.first to v.second.copy(
                description = v.second.description.ifBlank { c.overview },
                poster = v.second.poster ?: c.poster,
                backdrop = v.second.backdrop ?: c.backdrop,
                year = v.second.year.ifBlank { c.year },
            )
        }
    }
}

/** Cast list → actor page → (pick a title you have). Back steps out one page at a time. */
@Composable
fun CastBrowser(nav: CastNav, onClose: () -> Unit, onPick: (type: String, item: VodItem) -> Unit) {
    BackHandler { if (nav.people.isNotEmpty()) nav.people.removeAt(nav.people.lastIndex) else onClose() }
    Box(Modifier.fillMaxSize().background(Bg)) {
        val person = nav.people.lastOrNull()
        if (person == null) CastList(nav) { nav.people.add(it) }
        else key(person) { PersonPage(person, onPick) }
    }
}

@Composable
private fun CastList(nav: CastNav, onPerson: (Int) -> Unit) {
    var cast by remember(nav) { mutableStateOf<List<TmdbClient.CastMember>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(nav) {
        val tmdb = Session.tmdb
        if (tmdb == null) { error = "Cast needs a TMDB key (Settings)."; cast = emptyList(); return@LaunchedEffect }
        val r = withContext(Dispatchers.IO) { runCatching { tmdb.cast(nav.title, nav.year, nav.isSeries) } }
        r.onSuccess { cast = it; if (it.isEmpty()) error = "Couldn't find the cast for this title." }
            .onFailure { error = it.message; cast = emptyList() }
    }
    LaunchedEffect(cast) { if (!cast.isNullOrEmpty()) { delay(150); runCatching { fr.requestFocus() } } }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Text("Cast & crew", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(nav.title + if (nav.year.isNotBlank()) "  ·  ${nav.year}" else "", fontSize = 16.sp, color = TextDim)
        Spacer(Modifier.height(18.dp))
        val list = cast
        when {
            list == null -> Text("Looking up the cast…", color = TextDim, fontSize = 16.sp)
            list.isEmpty() -> Text(error ?: "No cast found.", color = TextDim, fontSize = 16.sp)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(list) { i, m ->
                    FocusBox(onClick = { onPerson(m.personId) },
                        modifier = if (i == 0) Modifier.focusRequester(fr) else Modifier,
                        shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(110.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                                Text(m.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                                    color = TextDim, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                                AsyncImage(model = m.photo, contentDescription = m.name,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(m.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(m.role.ifBlank { " " }, color = if (m.role == "Director" || m.role == "Creator") LogoYellow else TextDim,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun prettyDate(d: String): String = runCatching {
    val inF = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(inF.parse(d)!!)
}.getOrDefault(d)

private fun ageOn(born: String, died: String): Int? = runCatching {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val b = java.util.Calendar.getInstance().apply { time = f.parse(born)!! }
    val e = java.util.Calendar.getInstance().apply { if (died.isNotBlank()) time = f.parse(died)!! }
    var a = e.get(java.util.Calendar.YEAR) - b.get(java.util.Calendar.YEAR)
    if (e.get(java.util.Calendar.DAY_OF_YEAR) < b.get(java.util.Calendar.DAY_OF_YEAR)) a--
    a
}.getOrNull()

@Composable
private fun PersonPage(personId: Int, onPick: (type: String, item: VodItem) -> Unit) {
    var person by remember { mutableStateOf<TmdbClient.Person?>(null) }
    var owned by remember { mutableStateOf<Map<TmdbClient.Credit, Pair<String, VodItem>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(personId) {
        val tmdb = Session.tmdb ?: return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { tmdb.person(personId) } }
        r.onFailure { error = it.message }
        val p = r.getOrNull() ?: return@LaunchedEffect
        person = p
        delay(150); runCatching { fr.requestFocus() }
        owned = withContext(Dispatchers.IO) { runCatching { CastLibrary.match(p.credits) }.getOrDefault(emptyMap()) }
    }
    val p = person
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "Loading…", color = TextDim, fontSize = 18.sp)
        }
        return
    }
    val have = owned
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row {
                Box(Modifier.width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Panel)) {
                    AsyncImage(model = p.photo, contentDescription = p.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    val facts = listOfNotNull(
                        p.knownFor.takeIf { it.isNotBlank() }?.let { if (it == "Acting") "Actor" else it },
                        p.born.takeIf { it.isNotBlank() }?.let { b ->
                            val age = ageOn(b, p.died)
                            if (p.died.isNotBlank()) "${prettyDate(b)} – ${prettyDate(p.died)}${age?.let { " (aged $it)" } ?: ""}"
                            else "Born ${prettyDate(b)}${age?.let { " (age $it)" } ?: ""}"
                        },
                        p.place.takeIf { it.isNotBlank() },
                    )
                    if (facts.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(facts.joinToString("  ·  "), fontSize = 14.sp, color = TextDim)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(p.bio.ifBlank { "No biography available." }, fontSize = 14.sp,
                        color = if (p.bio.isBlank()) TextDim else Color(0xFFD5DAE1), maxLines = 7, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Text(if (have == null) "On your service" else "On your service  ·  ${have.size}",
                fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            when {
                have == null -> Text("Checking your library…", color = TextDim, fontSize = 14.sp, modifier = Modifier.focusRequester(fr))
                have.isEmpty() -> Text("None of their films or shows are on your service.", color = TextDim, fontSize = 14.sp)
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(have.entries.toList()) { i, (credit, hit) ->
                        Column(Modifier.width(112.dp)) {
                            PosterTile(hit.second, badge = if (credit.isSeries) "TV" else null,
                                modifier = if (i == 0) Modifier.focusRequester(fr) else Modifier) { onPick(hit.first, hit.second) }
                            if (credit.role.isNotBlank()) Text(credit.role, color = TextDim, fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
            }
        }
        val others = p.credits.filter { have == null || it !in have }.take(40)
        if (others.isNotEmpty()) item {
            Text("Also known for", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("Not on your service", fontSize = 13.sp, color = TextDim)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(others) { i, c ->
                    FocusBox(onClick = {}, modifier = Modifier.width(112.dp)
                        .then(if (have != null && have.isEmpty() && i == 0) Modifier.focusRequester(fr) else Modifier),
                        shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.padding(4.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Bg)) {
                                Text(c.title, Modifier.align(Alignment.Center).padding(6.dp), color = TextDim, fontSize = 12.sp, maxLines = 4)
                                AsyncImage(model = c.poster, contentDescription = c.title, modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop, alpha = 0.55f)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(c.title, fontSize = 12.sp, color = Color(0xFFB8C0CC), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(c.year, c.role).filter { it.isNotBlank() }.joinToString(" · "),
                                fontSize = 11.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    // Library check finished: move focus to the first title you can actually watch.
    LaunchedEffect(have) { if (have != null) { delay(150); runCatching { fr.requestFocus() } } }
}

/** Shown over a paused movie or episode: what you're watching, Resume, and Cast. */
@Composable
fun PauseOverlay(
    title: String, subtitle: String, plot: String, poster: String?,
    onResume: () -> Unit, onCast: (() -> Unit)?, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xEE0B0E13), Color(0xAA0B0E13), Color(0x220B0E13))))) {
        Row(Modifier.fillMaxHeight().padding(start = 56.dp, end = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (poster != null) {
                Box(Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Panel)) {
                    AsyncImage(model = poster, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(28.dp))
            }
            Column(Modifier.width(560.dp)) {
                Text("Paused", fontSize = 14.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 16.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (plot.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(plot, fontSize = 14.sp, color = Color(0xFFD5DAE1), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("▶  Resume", onClick = onResume, modifier = Modifier.focusRequester(fr))
                    if (onCast != null) TvButton("Cast & crew", onClick = onCast)
                    TvButton("Seek / controls", onClick = onBack)
                }
            }
        }
    }
}
