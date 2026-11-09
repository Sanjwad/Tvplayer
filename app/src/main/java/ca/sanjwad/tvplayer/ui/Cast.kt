package ca.sanjwad.tvplayer.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
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
import kotlinx.coroutines.launch
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
                    // Selectable, so Up from the rows below comes back here; OK shows the whole biography.
                    var full by remember { mutableStateOf(false) }
                    FocusBox(onClick = { full = !full }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(p.bio.ifBlank { "No biography available." }, fontSize = 14.sp,
                                color = if (p.bio.isBlank()) TextDim else Color(0xFFD5DAE1),
                                maxLines = if (full) 60 else 6, overflow = TextOverflow.Ellipsis)
                            if (p.bio.length > 400) Text(if (full) "Show less" else "Read more", fontSize = 13.sp,
                                color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
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
    onResume: () -> Unit, onCast: (() -> Unit)?, onBack: () -> Unit, onAbout: (() -> Unit)? = null,
    onTracks: (() -> Unit)? = null,
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
            Column(Modifier.width(820.dp)) {
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
                    if (onTracks != null) TvButton("Subtitles", onClick = onTracks)
                    if (onAbout != null) TvButton("About", onClick = onAbout)
                    if (onCast != null) TvButton("Cast & crew", onClick = onCast)
                    TvButton("Controls", onClick = onBack)
                }
            }
        }
    }
}

// ---------------- About this film / show ----------------

class AboutNav(val title: String, val year: String, val isSeries: Boolean)

private fun money(v: Long): String = when {
    v >= 1_000_000_000 -> "$" + String.format(java.util.Locale.US, "%.2fB", v / 1e9).replace(".00B", "B")
    v >= 1_000_000 -> "$" + String.format(java.util.Locale.US, "%.0fM", v / 1e6)
    else -> "$" + String.format(java.util.Locale.US, "%,d", v)
}

private fun runtimeText(min: Int) = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

/** Opens a YouTube video in the TV's YouTube app (or a browser if that's all there is). */
fun openTrailer(ctx: android.content.Context, key: String) {
    val tries = listOf("vnd.youtube:$key", "https://www.youtube.com/watch?v=$key")
    for (u in tries) {
        try {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: android.content.ActivityNotFoundException) { }
    }
    android.widget.Toast.makeText(ctx, "Install the YouTube app to watch trailers.", android.widget.Toast.LENGTH_LONG).show()
}

@Composable
private fun DimTile(c: TmdbClient.Credit, modifier: Modifier = Modifier) {
    FocusBox(onClick = {}, modifier = modifier.width(112.dp), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(4.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Bg)) {
                Text(c.title, Modifier.align(Alignment.Center).padding(6.dp), color = TextDim, fontSize = 12.sp, maxLines = 4)
                AsyncImage(model = c.poster, contentDescription = c.title, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, alpha = 0.5f)
            }
            Spacer(Modifier.height(4.dp))
            Text(c.title, fontSize = 12.sp, color = Color(0xFFB8C0CC), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (c.year.isBlank()) "Not on your service" else "${c.year} · not on service", fontSize = 11.sp, color = TextDim, maxLines = 1)
        }
    }
}

@Composable
fun AboutPage(nav: AboutNav, onClose: () -> Unit, onCast: () -> Unit, onPick: (type: String, item: VodItem) -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var about by remember(nav) { mutableStateOf<TmdbClient.About?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var omdb by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var wiki by remember { mutableStateOf<ca.sanjwad.tvplayer.stalker.Wikidata.Facts?>(null) }
    var owned by remember { mutableStateOf<Map<TmdbClient.Credit, Pair<String, VodItem>>?>(null) }
    val fr = remember { FocusRequester() }

    LaunchedEffect(nav) {
        val tmdb = Session.tmdb ?: run { failed = "This needs a TMDB key (Settings)."; return@LaunchedEffect }
        val a = withContext(Dispatchers.IO) { runCatching { tmdb.about(nav.title, nav.year, nav.isSeries) } }
            .onFailure { failed = it.message }.getOrNull()
        if (a == null) { if (failed == null) failed = "Couldn't find this title on TMDB."; return@LaunchedEffect }
        about = a
        delay(150); runCatching { fr.requestFocus() }
        kotlinx.coroutines.coroutineScope {
            launch(Dispatchers.IO) { a.imdbId?.let { id -> omdb = runCatching { Session.omdb?.details(id) }.getOrNull() } }
            launch(Dispatchers.IO) { a.imdbId?.let { id -> wiki = runCatching { ca.sanjwad.tvplayer.stalker.Wikidata.facts(id) }.getOrNull() } }
            launch(Dispatchers.IO) { owned = runCatching { CastLibrary.match(a.collection + a.similar.take(20)) }.getOrDefault(emptyMap()) }
        }
    }

    val a = about
    if (a == null) {
        Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
            Text(failed ?: "Loading…", color = TextDim, fontSize = 18.sp)
        }
        return
    }
    fun om(k: String) = omdb?.optString(k)?.takeIf { it.isNotBlank() && it != "N/A" }

    // "Did you know?" facts, most interesting first.
    val facts = buildList {
        om("Awards")?.let { add("🏆" to it) }
        if (a.revenue > 0 && a.budget > 0) {
            val x = a.revenue.toDouble() / a.budget
            add("💰" to "Made ${money(a.revenue)} worldwide on a ${money(a.budget)} budget" +
                if (x >= 1.5) " — ${String.format(java.util.Locale.US, "%.1f", x)}× what it cost." else if (x < 1) " — it lost money." else ".")
        } else if (a.revenue > 0) add("💰" to "Made ${money(a.revenue)} at the box office worldwide.")
        else if (a.budget > 0) add("💰" to "Cost ${money(a.budget)} to make.")
        else om("BoxOffice")?.let { add("💰" to "Made $it at the US box office.") }
        wiki?.basedOn?.takeIf { it.isNotEmpty() }?.let { add("📖" to "Based on ${it.joinToString("; ")}") }
        wiki?.filmedIn?.takeIf { it.isNotEmpty() }?.let { add("🎬" to "Filmed in ${it.joinToString(", ")}") }
        wiki?.setIn?.takeIf { it.isNotEmpty() }?.let { add("📍" to "Set in ${it.joinToString(", ")}") }
        if (a.isSeries) {
            if (a.creators.isNotEmpty()) add("✍" to "Created by ${a.creators.joinToString(", ")}")
            if (a.seasons > 0) add("📺" to "${a.seasons} season${if (a.seasons == 1) "" else "s"}, ${a.episodes} episodes" +
                (if (a.networks.isNotEmpty()) " on ${a.networks.joinToString(", ")}" else "") +
                when (a.status) { "Ended" -> " — ended ${a.lastAired.take(4)}."; "Returning Series" -> " — still running."; "Canceled" -> " — cancelled ${a.lastAired.take(4)}."; else -> "." })
        } else {
            om("Director")?.let { add("🎥" to "Directed by $it") }
            om("Writer")?.let { add("✍" to "Written by $it") }
        }
        if (a.studios.isNotEmpty()) add("🏢" to (if (a.isSeries) "Produced by " else "From ") + a.studios.joinToString(", "))
        listOfNotNull(om("Language")?.let { "Language: $it" }, om("Country")?.let { "Country: $it" })
            .takeIf { it.isNotEmpty() }?.let { add("🌍" to it.joinToString("   ·   ")) }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        AsyncImage(model = a.backdrop, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, alpha = 0.16f)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row {
                    Box(Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Panel)) {
                        AsyncImage(model = a.poster, contentDescription = a.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text("About", fontSize = 14.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                        Text(a.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                        if (a.tagline.isNotBlank()) Text("“${a.tagline}”", fontSize = 16.sp, color = LogoYellow,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        val line = listOfNotNull(
                            a.released.takeIf { it.isNotBlank() }?.let { prettyDate(it) },
                            a.runtime.takeIf { it > 0 }?.let { if (a.isSeries) "${it}m episodes" else runtimeText(it) },
                            a.certification.takeIf { it.isNotBlank() }?.let { "Rated $it" },
                            a.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        )
                        if (line.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(line.joinToString("  ·  "), fontSize = 14.sp, color = TextDim) }
                        if (a.overview.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(a.overview, fontSize = 14.sp, color = Color(0xFFD5DAE1), maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val t = a.trailer
                            if (t != null) TvButton("▶  Watch trailer", onClick = { openTrailer(ctx, t) }, modifier = Modifier.focusRequester(fr))
                            TvButton("Cast & crew", onClick = onCast, modifier = if (t == null) Modifier.focusRequester(fr) else Modifier)
                        }
                    }
                }
            }
            if (facts.isNotEmpty() || omdb == null || wiki == null) item {
                Text("Did you know?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    facts.forEach { (icon, text) ->
                        FocusBox(onClick = {}, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                                Text(icon, fontSize = 16.sp, modifier = Modifier.width(30.dp))
                                Text(text, fontSize = 15.sp, color = Color(0xFFE2E6EC))
                            }
                        }
                    }
                    if (omdb == null && a.imdbId != null || wiki == null && a.imdbId != null)
                        Text("Looking up more…", fontSize = 13.sp, color = TextDim)
                }
            }
            val have = owned
            if (a.collection.isNotEmpty()) item {
                Text(a.collectionName ?: "The whole series", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                val firstOwned = remember { FocusRequester() }
                val firstIdx = a.collection.indexOfFirst { have?.get(it) != null }
                LazyRow(Modifier.enterAt(firstOwned, firstIdx >= 0), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(a.collection) { i, c ->
                        val hit = have?.get(c)
                        if (hit != null) PosterTile(hit.second, modifier = if (i == firstIdx) Modifier.focusRequester(firstOwned) else Modifier) {
                            onPick(hit.first, hit.second)
                        } else DimTile(c)
                    }
                }
            }
            // Newest first (TMDB's own order puts old favourites first).
            val similar = have?.let { h -> a.similar.sortedByDescending { it.year.take(4).toIntOrNull() ?: 0 }.mapNotNull { h[it] } }.orEmpty()
            if (similar.isNotEmpty()) item {
                Text("More like this on your service", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                val firstSimilar = remember { FocusRequester() }
                LazyRow(Modifier.enterAt(firstSimilar, true), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(similar) { i, hit ->
                        PosterTile(hit.second, modifier = if (i == 0) Modifier.focusRequester(firstSimilar) else Modifier) { onPick(hit.first, hit.second) }
                    }
                }
            }
        }
    }
}

/** Moving into a row from above or below lands on its first title, not whichever is nearest. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.enterAt(first: FocusRequester, ok: Boolean): Modifier =
    this.focusProperties { enter = { if (ok) first else FocusRequester.Default } }.focusGroup()
