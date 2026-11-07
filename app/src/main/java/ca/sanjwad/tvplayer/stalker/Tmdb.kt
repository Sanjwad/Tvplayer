package ca.sanjwad.tvplayer.stalker

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The Movie Database: trending and in-cinema lists. Accepts either a v3 API key or a
 * v4 "API Read Access Token" (the long one).
 */
class TmdbClient(private val key: String) {

    data class Hit(val title: String, val year: String, val overview: String, val backdrop: String?, val poster: String?)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun get(path: String, vararg q: Pair<String, String>): JSONObject {
        val bearer = key.length > 40
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder().apply {
            if (!bearer) addQueryParameter("api_key", key)
            addQueryParameter("language", "en-CA")
            q.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).apply { if (bearer) header("Authorization", "Bearer $key") }.build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("TMDB didn't accept the key.")
            if (!r.isSuccessful) throw IOException("TMDB error ${r.code}")
            return JSONObject(r.body?.string().orEmpty())
        }
    }

    private fun list(path: String, pages: Int, vararg q: Pair<String, String>): List<Hit> {
        val out = mutableListOf<Hit>()
        for (p in 1..pages) {
            val res = get(path, *q, "page" to p.toString()).optJSONArray("results") ?: break
            for (i in 0 until res.length()) {
                val o = res.optJSONObject(i) ?: continue
                val img = { k: String, size: String ->
                    o.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/$size$it" }
                }
                out += Hit(
                    title = o.optString("title").ifBlank { o.optString("name") },
                    year = o.optString("release_date").ifBlank { o.optString("first_air_date") }.take(4),
                    overview = o.optString("overview"),
                    backdrop = img("backdrop_path", "w1280"),
                    poster = img("poster_path", "w342"),
                )
            }
        }
        return out
    }

    data class EpisodeInfo(val name: String, val overview: String, val still: String?, val runtime: Int)

    private val tvIds = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val seasonInfo = java.util.concurrent.ConcurrentHashMap<String, Map<Int, EpisodeInfo>>()

    /** Finds a show on TMDB from the provider's (often messy) title. */
    fun findTv(title: String, year: String): Int? {
        val clean = TitleMatch.queryName(title)
        if (clean.isBlank()) return null
        tvIds["$clean|$year"]?.let { return it }
        val res = get("search/tv", "query" to clean).optJSONArray("results") ?: return null
        val y = year.take(4).toIntOrNull()
        var best: JSONObject? = null
        for (i in 0 until res.length()) {
            val o = res.optJSONObject(i) ?: continue
            val oy = o.optString("first_air_date").take(4).toIntOrNull()
            if (best == null) best = o
            if (y != null && oy != null && abs(oy - y) <= 1) { best = o; break }
            if (TitleMatch.norm(o.optString("name")) == TitleMatch.norm(clean)) { best = o; break }
        }
        val id = best?.optInt("id")?.takeIf { it > 0 } ?: return null
        tvIds["$clean|$year"] = id
        return id
    }

    /** Episode names, descriptions, stills and runtimes for one season, keyed by episode number. */
    fun season(tvId: Int, seasonNumber: Int): Map<Int, EpisodeInfo> {
        val key = "$tvId/$seasonNumber"
        seasonInfo[key]?.let { return it }
        val eps = get("tv/$tvId/season/$seasonNumber").optJSONArray("episodes") ?: return emptyMap()
        val map = HashMap<Int, EpisodeInfo>()
        for (i in 0 until eps.length()) {
            val o = eps.optJSONObject(i) ?: continue
            val n = o.optInt("episode_number", -1).takeIf { it > 0 } ?: continue
            map[n] = EpisodeInfo(
                name = o.optString("name"),
                overview = o.optString("overview"),
                still = o.optString("still_path").takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/w300$it" },
                runtime = o.optInt("runtime", 0),
            )
        }
        seasonInfo[key] = map
        return map
    }

    /** The exact IMDb id ("tt1234567") of the best TMDB match for a title, or null. */
    fun imdbId(title: String, year: String, isSeries: Boolean): String? {
        val q = TitleMatch.queryName(title)
        if (q.isBlank()) return null
        val y = year.take(4)
        val res = if (isSeries) get("search/tv", "query" to q, *(if (y.isNotEmpty()) arrayOf("first_air_date_year" to y) else emptyArray()))
        else get("search/movie", "query" to q, *(if (y.isNotEmpty()) arrayOf("year" to y) else emptyArray()))
        val id = res.optJSONArray("results")?.optJSONObject(0)?.optInt("id", 0)?.takeIf { it > 0 } ?: return null
        val ext = if (isSeries) get("tv/$id/external_ids") else get("movie/$id")
        return ext.optString("imdb_id").takeIf { it.startsWith("tt") }
    }

    /** TMDB's average score for the best match of a title, or null. */
    fun score(title: String, year: String, isSeries: Boolean): Double? {
        val q = TitleMatch.queryName(title)
        if (q.isBlank()) return null
        val y = year.take(4)
        val res = if (isSeries) get("search/tv", "query" to q, *(if (y.isNotEmpty()) arrayOf("first_air_date_year" to y) else emptyArray()))
        else get("search/movie", "query" to q, *(if (y.isNotEmpty()) arrayOf("year" to y) else emptyArray()))
        val o = res.optJSONArray("results")?.optJSONObject(0) ?: return null
        if (o.optInt("vote_count", 0) < 10) return null
        return o.optDouble("vote_average", 0.0).takeIf { it > 0 }
    }

    // ---------- cast & filmographies ----------

    data class CastMember(val personId: Int, val name: String, val role: String, val photo: String?)
    data class Credit(
        val title: String, val year: String, val isSeries: Boolean, val role: String,
        val poster: String?, val backdrop: String?, val overview: String, val popularity: Double,
    )
    data class Person(
        val id: Int, val name: String, val bio: String, val born: String, val died: String,
        val place: String, val photo: String?, val knownFor: String, val credits: List<Credit>,
    )

    private fun img(o: JSONObject, k: String, size: String) =
        o.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/$size$it" }

    private val movieIds = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Finds a movie on TMDB from the provider's title. */
    fun findMovie(title: String, year: String): Int? {
        val clean = TitleMatch.queryName(title)
        if (clean.isBlank()) return null
        movieIds["$clean|$year"]?.let { return it }
        val y = year.take(4)
        var res = get("search/movie", "query" to clean, *(if (y.isNotEmpty()) arrayOf("year" to y) else emptyArray()))
            .optJSONArray("results")
        if ((res == null || res.length() == 0) && y.isNotEmpty()) res = get("search/movie", "query" to clean).optJSONArray("results")
        val id = res?.optJSONObject(0)?.optInt("id", 0)?.takeIf { it > 0 } ?: return null
        movieIds["$clean|$year"] = id
        return id
    }

    private val castCache = java.util.concurrent.ConcurrentHashMap<String, List<CastMember>>()

    /** Main cast (and director/creator) of a movie or show, best-known first. */
    fun cast(title: String, year: String, isSeries: Boolean): List<CastMember> {
        val id = (if (isSeries) findTv(title, year) else findMovie(title, year)) ?: return emptyList()
        val key = "${if (isSeries) "tv" else "movie"}/$id"
        castCache[key]?.let { return it }
        val out = mutableListOf<CastMember>()
        if (isSeries) {
            val js = get("tv/$id/aggregate_credits")
            js.optJSONArray("cast")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 30)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val role = o.optJSONArray("roles")?.optJSONObject(0)?.optString("character").orEmpty()
                    out += CastMember(o.optInt("id"), o.optString("name"), role, img(o, "profile_path", "w185"))
                }
            }
            runCatching {
                get("tv/$id").optJSONArray("created_by")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o ->
                        out += CastMember(o.optInt("id"), o.optString("name"), "Creator", img(o, "profile_path", "w185"))
                    }
                }
            }
        } else {
            val js = get("movie/$id/credits")
            js.optJSONArray("cast")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 30)) {
                    val o = arr.optJSONObject(i) ?: continue
                    out += CastMember(o.optInt("id"), o.optString("name"), o.optString("character"), img(o, "profile_path", "w185"))
                }
            }
            js.optJSONArray("crew")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("job") == "Director")
                        out += CastMember(o.optInt("id"), o.optString("name"), "Director", img(o, "profile_path", "w185"))
                }
            }
        }
        return out.filter { it.personId > 0 }.distinctBy { it.personId to it.role }.also { castCache[key] = it }
    }

    private val people = java.util.concurrent.ConcurrentHashMap<Int, Person>()

    /** An actor's or director's details and every film and show they're in, best-known first. */
    fun person(personId: Int): Person {
        people[personId]?.let { return it }
        val o = get("person/$personId", "append_to_response" to "combined_credits")
        val cc = o.optJSONObject("combined_credits")
        val credits = mutableListOf<Credit>()
        val directing = o.optString("known_for_department") == "Directing"
        fun add(arr: org.json.JSONArray?, crew: Boolean) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val tv = c.optString("media_type") == "tv"
                if (crew && c.optString("job") !in setOf("Director", "Creator", "Executive Producer")) continue
                if (crew && c.optString("job") == "Executive Producer" && !tv) continue
                val role = if (crew) c.optString("job") else c.optString("character")
                // Skip talk shows, awards and "Himself" appearances: not what you're looking for here.
                val ids = c.optJSONArray("genre_ids")
                val genres = if (ids == null) emptySet() else (0 until ids.length()).map { ids.optInt(it) }.toSet()
                if (10767 in genres || 10763 in genres || 99 in genres && tv) continue
                if (Regex("^(him|her|them)self\\b|^self\\b|narrator \\(voice\\)$", RegexOption.IGNORE_CASE).containsMatchIn(role)) continue
                credits += Credit(
                    title = c.optString("title").ifBlank { c.optString("name") },
                    year = c.optString("release_date").ifBlank { c.optString("first_air_date") }.take(4),
                    isSeries = tv,
                    role = role,
                    poster = img(c, "poster_path", "w342"),
                    backdrop = img(c, "backdrop_path", "w1280"),
                    overview = c.optString("overview"),
                    popularity = c.optDouble("popularity", 0.0) + c.optInt("vote_count", 0) / 200.0,
                )
            }
        }
        add(cc?.optJSONArray("cast"), crew = false)
        if (directing) add(cc?.optJSONArray("crew"), crew = true)
        val p = Person(
            id = personId,
            name = o.optString("name"),
            bio = o.optString("biography"),
            born = o.optString("birthday").takeIf { it != "null" }.orEmpty(),
            died = o.optString("deathday").takeIf { it != "null" }.orEmpty(),
            place = o.optString("place_of_birth").takeIf { it != "null" }.orEmpty(),
            photo = img(o, "profile_path", "h632"),
            knownFor = o.optString("known_for_department"),
            credits = credits.filter { it.title.isNotBlank() }
                .groupBy { "${TitleMatch.norm(it.title)}|${it.isSeries}" }
                .map { (_, g) -> g.maxBy { it.popularity }.copy(role = g.map { it.role }.filter { it.isNotBlank() }.distinct().joinToString(" / ")) }
                .sortedByDescending { it.popularity },
        )
        people[personId] = p
        return p
    }

    fun trendingMovies() = list("trending/movie/week", 3)
    fun inTheatres() = list("movie/now_playing", 3, "region" to "CA")
    fun trendingTv() = list("trending/tv/week", 3)
    fun popularTv() = list("tv/popular", 2)
}

/** Matches TMDB titles to a provider's catalogue, whose names often carry prefixes like "EN | " or "(2024)". */
object TitleMatch {
    private val prefix = Regex("^\\s*[a-z]{2,4}\\s*[|:\\-]\\s*")
    private val brackets = Regex("\\(.*?\\)|\\[.*?\\]")
    private val noise = Regex("\\b(4k|uhd|fhd|hd|sd|multi|multisub|sub|dub|eng)\\b")
    private val nonAlnum = Regex("[^a-z0-9]+")
    private val yearInName = Regex("\\((\\d{4})\\)|\\b(19|20)\\d{2}\\b$")

    /** A search-friendly show name: drops "EN | ", brackets, years and "S01E02" bits. */
    fun queryName(t: String): String = t
        .replace(Regex("^\\s*[A-Za-z]{2,4}\\s*[|:\\-]\\s*"), "")
        .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        .replace(Regex("\\bS\\d{1,2}\\s*E\\d{1,3}\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\b(19|20)\\d{2}\\s*$"), " ")
        .replace(Regex("\\s+en\\s*-\\s*all\\b.*$", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun norm(t: String): String = t.lowercase()
        .replace(prefix, "").replace(brackets, " ").replace(noise, " ")
        .replace("&", " and ").replace(nonAlnum, " ").trim()
        .removePrefix("the ").trim()

    private fun yearOf(v: VodItem): Int? =
        v.year.take(4).toIntOrNull() ?: yearInName.find(v.name)?.value?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

    fun match(hits: List<TmdbClient.Hit>, catalogue: List<VodItem>): List<VodItem> {
        val index = HashMap<String, MutableList<VodItem>>()
        catalogue.forEach { index.getOrPut(norm(it.name)) { mutableListOf() }.add(it) }
        return hits.mapNotNull { h ->
            val cands = index[norm(h.title)] ?: return@mapNotNull null
            val hy = h.year.toIntOrNull()
            val pick = cands.firstOrNull { c -> val cy = yearOf(c); hy == null || cy == null || abs(cy - hy) <= 1 }
                ?: return@mapNotNull null
            pick.copy(
                description = pick.description.ifBlank { h.overview },
                backdrop = pick.backdrop ?: h.backdrop,
                poster = pick.poster ?: h.poster,
            )
        }.distinctBy { it.id }
    }
}
