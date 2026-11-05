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
