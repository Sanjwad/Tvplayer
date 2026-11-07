package ca.sanjwad.tvplayer.stalker

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** OMDb gives the real IMDb rating for a title. Free keys allow about 1,000 lookups a day. */
class OmdbClient(private val key: String) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    /** "7.8", or null if OMDb has no rating. Throws when the key is refused or the daily limit is hit. */
    fun imdbRating(title: String, year: String, isSeries: Boolean): String? {
        val q = TitleMatch.queryName(title)
        if (q.isBlank()) return null
        return lookup(q, year.take(4), isSeries) ?: if (year.isNotBlank()) lookup(q, "", isSeries) else null
    }

    /** Exact lookup by IMDb id. */
    fun byId(imdbId: String): String? {
        val url = "https://www.omdbapi.com/".toHttpUrl().newBuilder()
            .addQueryParameter("apikey", key).addQueryParameter("i", imdbId).build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val o = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
            if (o.optString("Response") == "False") {
                val err = o.optString("Error")
                if (err.contains("limit", true) || err.contains("key", true)) throw IOException(err)
                return null
            }
            return o.optString("imdbRating").takeIf { it.isNotBlank() && it != "N/A" }
        }
    }

    /** Awards, box office, age rating, writers etc. for an IMDb id (fields as OMDb names them), or null. */
    fun details(imdbId: String): JSONObject? {
        val url = "https://www.omdbapi.com/".toHttpUrl().newBuilder()
            .addQueryParameter("apikey", key).addQueryParameter("i", imdbId).build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val o = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
            return if (o.optString("Response") == "False") null else o
        }
    }

    private fun lookup(q: String, year: String, isSeries: Boolean): String? {
        val url = "https://www.omdbapi.com/".toHttpUrl().newBuilder()
            .addQueryParameter("apikey", key).addQueryParameter("t", q)
            .addQueryParameter("type", if (isSeries) "series" else "movie")
            .apply { if (year.isNotBlank()) addQueryParameter("y", year) }
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val o = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
            if (o.optString("Response") == "False") {
                val err = o.optString("Error")
                if (err.contains("limit", true) || err.contains("key", true)) throw IOException(err)
                return null
            }
            return o.optString("imdbRating").takeIf { it.isNotBlank() && it != "N/A" }
        }
    }
}

/**
 * Ratings cache shared by posters and detail pages, kept on the TV for a week so each title
 * is looked up once. "" means "looked up, none found".
 */
object Ratings {
    private const val WEEK = 7 * 24 * 3600_000L
    private val map = ConcurrentHashMap<String, Pair<String, Long>>()
    private val tmdbGate = Semaphore(4)
    private val omdbGate = Semaphore(2)
    @Volatile private var file: File? = null
    @Volatile private var lastWrite = 0L

    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, "ratings.json")
        file = f
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().forEach { k -> o.optJSONArray(k)?.let { a -> map[k] = a.optString(0) to a.optLong(1) } }
        }
    }

    private fun save() {
        val now = System.currentTimeMillis()
        if (now - lastWrite < 5_000) return
        lastWrite = now
        val f = file ?: return
        runCatching {
            val o = JSONObject()
            map.forEach { (k, v) -> if (now - v.second < WEEK) o.put(k, org.json.JSONArray().put(v.first).put(v.second)) }
            f.writeText(o.toString())
        }
    }

    private fun key(kind: String, item: VodItem): String {
        val year = item.year.take(4).ifBlank { Regex("\\b(19|20)\\d{2}\\b").find(item.name)?.value.orEmpty() }
        return "$kind|${if (item.isSeries) "tv" else "m"}|${TitleMatch.norm(TitleMatch.queryName(item.name))}|$year"
    }

    private fun cached(k: String): String? {
        val (v, t) = map[k] ?: return null
        return if (System.currentTimeMillis() - t < WEEK) v else null
    }

    private fun put(k: String, v: String) { map[k] = v to System.currentTimeMillis(); save() }

    /** The provider's own rating, if it sent one (some send it out of 100). */
    fun providerScore(item: VodItem): String? {
        val d = item.rating.replace(",", ".").toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val v = if (d > 10) d / 10 else d
        return String.format(Locale.US, "%.1f", v)
    }

    fun peekTmdb(item: VodItem): String? = cached(key("t", item))
    fun peekImdb(item: VodItem): String? = cached(key("i2", item))

    /** TMDB score like "7.3", or "" if none. Blocking; call off the main thread. */
    fun tmdb(item: VodItem, client: TmdbClient): String {
        val k = key("t", item)
        cached(k)?.let { return it }
        tmdbGate.acquire()
        try {
            cached(k)?.let { return it }
            val year = item.year.take(4)
            val v = client.score(item.name, year, item.isSeries)?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
            put(k, v)
            return v
        } finally { tmdbGate.release() }
    }

    /**
     * IMDb rating like "7.8", or "" if none. Matches the exact title through TMDB's IMDb id when
     * possible, so films sharing a name don't get mixed up. Returns null (not cached) if the lookup failed.
     */
    fun imdb(item: VodItem, client: OmdbClient, tmdb: TmdbClient?): String? {
        val k = key("i2", item)
        cached(k)?.let { return it }
        omdbGate.acquire()
        try {
            cached(k)?.let { return it }
            val v = runCatching {
                if (tmdb != null) {
                    val idKey = key("id", item)
                    val id = cached(idKey) ?: (tmdb.imdbId(item.name, item.year, item.isSeries).orEmpty().also { put(idKey, it) })
                    if (id.isNotEmpty()) client.byId(id) else null
                } else client.imdbRating(item.name, item.year, item.isSeries)
            }.getOrElse { return null }.orEmpty()
            put(k, v)
            return v
        } finally { omdbGate.release() }
    }
}
