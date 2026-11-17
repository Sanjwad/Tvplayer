package ca.sanjwad.tvplayer.stalker

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Several provider accounts shown as one: Live TV groups from every account (labelled with the
 * account's name), movies and shows from all of them, and search across the lot.
 *
 * Everything from the first (main) account keeps its own ids, so favourites, My List and watch
 * progress made before more accounts were added still match. Things from the other accounts get
 * a short prefix ("a1|", "a2|"…) so the app knows which account to ask when you press play.
 */
class MultiSource(val parts: List<Part>) : IptvSource {
    class Part(val label: String, val src: IptvSource)

    /** The account behind whatever was asked to play last (the player needs its headers and quirks). */
    @Volatile var active: IptvSource = parts.first().src
        private set

    /** Accounts that connected; one that's down shouldn't stop the others from working. */
    @Volatile var failed: List<Pair<String, String>> = emptyList()
        private set
    private val ok = BooleanArray(parts.size) { true }

    // ---- ids ----
    private val prefix = Regex("^a(\\d)\\|", RegexOption.DOT_MATCHES_ALL)
    fun tag(i: Int, s: String) = if (i == 0 || s.isEmpty()) s else "a$i|$s"
    fun split(s: String): Pair<Int, String> {
        val m = prefix.find(s) ?: return 0 to s
        val i = m.groupValues[1].toInt()
        return if (i in parts.indices) i to s.substring(m.range.last + 1) else 0 to s
    }
    private fun part(s: String) = parts[split(s).first].src
    fun partFor(id: String): Part = parts[split(id).first]

    private fun tagCh(i: Int, c: Channel) = if (i == 0) c else c.copy(id = tag(i, c.id), cmd = tag(i, c.cmd), genreId = tag(i, c.genreId))
    private fun untagCh(c: Channel): Pair<Int, Channel> {
        val (i, id) = split(c.id)
        return i to if (i == 0) c else c.copy(id = id, cmd = split(c.cmd).second, genreId = split(c.genreId).second)
    }
    private fun tagVod(i: Int, v: VodItem) = if (i == 0) v else v.copy(id = tag(i, v.id), cmd = tag(i, v.cmd))
    private fun untagVod(v: VodItem): Pair<Int, VodItem> {
        val (i, id) = split(v.id)
        return i to if (i == 0) v else v.copy(id = id, cmd = split(v.cmd).second)
    }
    private fun tagEp(i: Int, e: Episode) = if (i == 0) e else e.copy(cmd = tag(i, e.cmd))
    private fun tagSeason(i: Int, s: Season) = if (i == 0) s else s.copy(id = tag(i, s.id), episodes = s.episodes?.map { tagEp(i, it) })
    private fun untagSeason(s: Season): Season {
        val (i, id) = split(s.id)
        return if (i == 0) s else s.copy(id = id, episodes = s.episodes?.map { it.copy(cmd = split(it.cmd).second) })
    }
    private fun label(i: Int, title: String) = "$title  ·  ${parts[i].label}"

    // ---- section types ----
    // The screens use the main account's own types ("vod", "series"…), so saved lists keep working if
    // accounts are added or removed later. Other accounts get theirs worked out from the kind of thing asked for.
    private val types = ConcurrentHashMap<String, String>()
    private fun typeFor(i: Int, kind: String): String = types.getOrPut("$i|$kind") {
        runCatching { parts[i].src.browseCategories(kind).first }.getOrDefault(if (kind == "series") "series" else "vod")
    }
    /** Movies and shows share one type on the main account (some Stalker portals), so "which kind" is unclear. */
    private val mixed get() = typeFor(0, "movies") == typeFor(0, "series")
    /** Which kinds a main-account type stands for. */
    private fun kindsOf(type: String, isSeries: Boolean? = null): List<String> = when {
        isSeries == true -> listOf("series")
        type == typeFor(0, "series") && !mixed -> listOf("series")
        type == typeFor(0, "movies") && !mixed -> listOf("movies")
        type == "series" -> listOf("series")
        isSeries == false -> listOf("movies")
        else -> listOf("movies", "series")
    }

    private val pool = Executors.newFixedThreadPool(4)
    /** Runs [f] on every working account at once, keeping whatever answers in time. */
    private fun <T> each(timeoutSec: Long = 60, f: (Int, IptvSource) -> T): List<Pair<Int, T>> {
        val jobs = parts.indices.filter { ok[it] }.map { i -> i to pool.submit<T> { f(i, parts[i].src) } }
        return jobs.mapNotNull { (i, job) -> runCatching { i to job.get(timeoutSec, TimeUnit.SECONDS) }.getOrNull() }
    }

    // ---- IptvSource ----
    override val portalHost: String get() = active.portalHost
    override fun streamHeaders(): Map<String, String> = active.streamHeaders()

    override fun connect() {
        val errors = mutableListOf<Pair<String, String>>()
        val results = parts.indices.map { i -> i to pool.submit<Unit> { parts[i].src.connect() } }
        for ((i, job) in results) {
            val r = runCatching { job.get(90, TimeUnit.SECONDS) }
            ok[i] = r.isSuccess
            r.exceptionOrNull()?.let { errors += parts[i].label to ((it.cause ?: it).message ?: "Couldn't connect") }
        }
        failed = errors
        if (ok.none { it }) throw java.io.IOException(errors.joinToString("\n") { "${it.first}: ${it.second}" })
        active = parts[ok.indexOfFirst { it }].src
    }

    override fun liveGenres(): List<Genre> = each { i, s -> s.liveGenres() }.sortedBy { it.first }
        .flatMap { (i, gs) -> gs.map { Genre(tag(i, it.id), label(i, it.title)) } }

    override fun liveChannels(): List<Channel> = each { i, s -> s.liveChannels() }.sortedBy { it.first }
        .flatMap { (i, cs) -> cs.map { tagCh(i, it) } }

    override fun nowNext(channel: Channel): List<Programme> = untagCh(channel).let { (i, c) -> parts[i].src.nowNext(c) }
    override fun programmes(channel: Channel, from: Long, to: Long) = untagCh(channel).let { (i, c) -> parts[i].src.programmes(c, from, to) }
    override fun catchupProgrammes(channel: Channel) = untagCh(channel).let { (i, c) -> parts[i].src.catchupProgrammes(c) }
    override fun catchupUrls(channel: Channel, start: Long, end: Long): List<String> {
        val (i, c) = untagCh(channel)
        active = parts[i].src
        return parts[i].src.catchupUrls(c, start, end)
    }

    override fun ensureGuide() { parts.indices.filter { ok[it] }.forEach { i -> runCatching { parts[i].src.ensureGuide() } } }
    override val guideStatus: String get() = parts.indices.filter { ok[it] }.map { parts[it].src.guideStatus }.firstOrNull { it.isNotBlank() }.orEmpty()

    override fun browseCategories(kind: String): Pair<String, List<Genre>> {
        val all = each { i, s -> s.browseCategories(kind) }.sortedBy { it.first }
        all.forEach { (i, r) -> types["$i|$kind"] = r.first }
        val k = if (kind == "series") "s" else "m"
        // Other accounts' categories carry which kind they are, so a page can be asked for correctly.
        return typeFor(0, kind) to all.flatMap { (i, r) -> r.second.map { Genre(if (i == 0) it.id else "a$i|$k|${it.id}", label(i, it.title)) } }
    }

    override fun vodPage(type: String, categoryId: String, page: Int): Page {
        val (i, rest) = split(categoryId)
        if (i == 0) return parts[0].src.vodPage(type, rest, page)
        val kind = if (rest.startsWith("s|")) "series" else "movies"
        val p = parts[i].src.vodPage(typeFor(i, kind), rest.substringAfter('|'), page)
        return Page(p.items.map { tagVod(i, it) }, p.totalItems)
    }

    override fun seasons(type: String, item: VodItem): List<Season> {
        val (i, v) = untagVod(item)
        return parts[i].src.seasons(if (i == 0) type else typeFor(i, "series"), v).map { tagSeason(i, it) }
    }

    override fun episodes(type: String, item: VodItem, season: Season): List<Episode> {
        val (i, v) = untagVod(item)
        return parts[i].src.episodes(if (i == 0) type else typeFor(i, "series"), v, untagSeason(season)).map { tagEp(i, it) }
    }

    /** Asks every account; the main one with the type as given, the others for each kind it could mean. */
    private fun <T> acrossKinds(type: String, timeout: Long, f: (IptvSource, String) -> List<T>, tagIt: (Int, T) -> T): List<T> {
        val kinds = kindsOf(type)
        return each(timeout) { i, s ->
            if (i == 0) f(s, type) else kinds.flatMap { k -> runCatching { f(s, typeFor(i, k)) }.getOrDefault(emptyList()) }
        }.sortedBy { it.first }.flatMap { (i, l) -> l.map { tagIt(i, it) } }
    }

    override fun search(type: String, query: String): List<VodItem> =
        acrossKinds(type, 25, { s, t -> s.search(t, query) }, ::tagVod)

    override fun catalogue(type: String): List<VodItem> =
        acrossKinds(type, 120, { s, t -> s.catalogue(t) }, ::tagVod)

    override fun recentlyAdded(type: String, hidden: Set<String>): List<VodItem> {
        val kinds = kindsOf(type)
        val lists = each { i, s ->
            if (i == 0) s.recentlyAdded(type, hidden.filter { split(it).first == 0 }.toSet())
            else kinds.flatMap { k -> runCatching {
                s.recentlyAdded(typeFor(i, k), hidden.mapNotNull { h -> split(h).takeIf { it.first == i }?.second?.substringAfter('|') }.toSet())
            }.getOrDefault(emptyList()) }
        }.sortedBy { it.first }.map { (i, l) -> l.map { tagVod(i, it) } }
        // Take turns so one account's uploads don't push the other's out.
        val out = mutableListOf<VodItem>()
        val its = lists.map { it.iterator() }
        while (its.any { it.hasNext() }) its.forEach { if (it.hasNext()) out += it.next() }
        return out
    }

    override fun movieInfo(item: VodItem): VodItem {
        val (i, v) = untagVod(item)
        return tagVod(i, parts[i].src.movieInfo(v))
    }

    override fun createLink(type: String, cmd: String, series: String, movieId: String?): String {
        val (i, c) = split(cmd)
        active = parts[i].src
        val t = if (i == 0) type else when (type) {
            typeFor(0, "movies"), typeFor(0, "series") -> typeFor(i, if (series.isNotBlank() || kindsOf(type) == listOf("series")) "series" else "movies")
            else -> type // live TV ("itv") and anything else passes straight through
        }
        return parts[i].src.createLink(t, c, series, movieId?.let { split(it).second })
    }
}

/** The account that actually serves what's playing (for multi-account setups), or the source itself. */
fun IptvSource.serving(): IptvSource = (this as? MultiSource)?.active ?: this
/** The main (first) account. */
fun IptvSource.main(): IptvSource = (this as? MultiSource)?.parts?.first()?.src ?: this
fun mainOf(s: IptvSource): IptvSource = s.main()
