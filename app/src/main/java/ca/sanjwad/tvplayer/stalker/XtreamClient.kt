package ca.sanjwad.tvplayer.stalker

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Xtream Codes API (player_api.php). Stream addresses are built directly from the
 * server, username, password and stream id, so playback needs no extra round trip.
 */
class XtreamClient(serverUrl: String, private val user: String, private val pass: String) : IptvSource {

    private val base: HttpUrl = normalise(serverUrl)
    private val root = "${base.scheme}://${base.host}:${base.port}"
    override val portalHost: String get() = base.host

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Whole-category lists are cached so paging through them is instant. */
    private val listCache = HashMap<String, List<VodItem>>()

    var expiry: String = ""
        private set

    companion object {
        private fun normalise(url: String): HttpUrl {
            var u = url.trim()
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
            val parsed = u.toHttpUrlOrNull() ?: throw IOException("That server address doesn't look right.")
            return parsed.newBuilder().encodedPath("/").query(null).build()
        }

        /** Pulls server, username and password out of an M3U link like .../get.php?username=a&password=b */
        fun parseM3u(link: String): Triple<String, String, String>? {
            var u = link.trim()
            if (!u.startsWith("http", true)) u = "http://$u"
            val url = u.toHttpUrlOrNull() ?: return null
            val user = url.queryParameter("username") ?: return null
            val pass = url.queryParameter("password") ?: return null
            return Triple("${url.scheme}://${url.host}:${url.port}", user, pass)
        }
    }

    private fun api(vararg params: Pair<String, String>): Any? {
        val url = "$root/player_api.php".toHttpUrl().newBuilder().apply {
            addQueryParameter("username", user)
            addQueryParameter("password", pass)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).header("User-Agent", StalkerClient.UA).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw IOException("The server refused this username or password.")
            if (!r.isSuccessful) throw IOException("The server replied with error ${r.code}.")
            val body = r.body?.string().orEmpty().trim()
            if (body.isEmpty()) return null
            return try {
                JSONTokener(body).nextValue()
            } catch (e: Exception) {
                throw IOException("Couldn't read the server's reply.")
            }
        }
    }

    private fun Any?.objects(): List<JSONObject> = when (this) {
        is JSONArray -> (0 until length()).mapNotNull { optJSONObject(it) }
        is JSONObject -> keys().asSequence().mapNotNull { optJSONObject(it) }.toList()
        else -> emptyList()
    }

    private fun JSONObject.str(key: String): String =
        optString(key).takeIf { it.isNotBlank() && it != "null" }.orEmpty()

    private fun JSONObject.img(key: String): String? =
        str(key).takeIf { it.startsWith("http", true) }

    override fun connect() {
        val js = api() as? JSONObject ?: throw IOException("Unexpected reply from the server.")
        val info = js.optJSONObject("user_info") ?: throw IOException("This doesn't look like an Xtream server.")
        if (info.optInt("auth", 0) != 1) throw IOException("The server refused this username or password.")
        val status = info.str("status")
        if (status.isNotEmpty() && !status.equals("Active", true)) throw IOException("Account status: $status")
        expiry = info.str("exp_date")
    }

    override fun streamHeaders(): Map<String, String> = emptyMap()

    // ---------- live ----------

    override fun liveGenres(): List<Genre> =
        api("action" to "get_live_categories").objects().map { Genre(it.str("category_id"), it.str("category_name")) }

    override fun liveChannels(): List<Channel> =
        api("action" to "get_live_streams").objects().mapNotNull {
            val id = it.str("stream_id").ifEmpty { return@mapNotNull null }
            Channel(
                id = id,
                name = it.str("name"),
                number = it.str("num").toIntOrNull() ?: 0,
                cmd = "$root/live/$user/$pass/$id.ts",
                logo = it.img("stream_icon"),
                genreId = it.str("category_id"),
            )
        }.sortedBy { if (it.number > 0) it.number else Int.MAX_VALUE }

    // ---------- movies & series ----------

    override fun browseCategories(kind: String): Pair<String, List<Genre>> {
        val action = if (kind == "series") "get_series_categories" else "get_vod_categories"
        val cats = api("action" to action).objects().map { Genre(it.str("category_id"), it.str("category_name")) }
        return (if (kind == "series") "series" else "vod") to cats
    }

    private fun fullList(type: String, categoryId: String): List<VodItem> {
        val key = "$type/$categoryId"
        synchronized(listCache) { listCache[key]?.let { return it } }
        val list = if (type == "series") {
            api("action" to "get_series", "category_id" to categoryId).objects().mapNotNull {
                val id = it.str("series_id").ifEmpty { return@mapNotNull null }
                VodItem(
                    id = id, name = it.str("name"), description = it.str("plot"),
                    poster = it.img("cover"), cmd = "", isSeries = true,
                    year = it.str("year").ifEmpty { it.str("releaseDate").take(4) },
                    rating = it.str("rating").takeIf { r -> r != "0" }.orEmpty(),
                )
            }
        } else {
            api("action" to "get_vod_streams", "category_id" to categoryId).objects().mapNotNull {
                val id = it.str("stream_id").ifEmpty { return@mapNotNull null }
                val ext = it.str("container_extension").ifEmpty { "mp4" }
                VodItem(
                    id = id, name = it.str("name"), description = it.str("plot"),
                    poster = it.img("stream_icon"), cmd = "$root/movie/$user/$pass/$id.$ext",
                    isSeries = false, year = it.str("year"),
                    rating = it.str("rating").takeIf { r -> r != "0" }.orEmpty(),
                )
            }
        }
        synchronized(listCache) { listCache[key] = list }
        return list
    }

    override fun vodPage(type: String, categoryId: String, page: Int): Page {
        val all = fullList(type, categoryId)
        val size = 60
        val from = (page - 1) * size
        val items = if (from >= all.size) emptyList() else all.subList(from, minOf(all.size, from + size))
        return Page(items, all.size)
    }

    override fun movieInfo(item: VodItem): VodItem {
        if (item.isSeries) return item
        val info = (api("action" to "get_vod_info", "vod_id" to item.id) as? JSONObject)
            ?.optJSONObject("info") ?: return item
        return item.copy(
            description = item.description.ifBlank { info.str("plot").ifBlank { info.str("description") } },
            year = item.year.ifBlank { info.str("releasedate").take(4) },
            rating = item.rating.ifBlank { info.str("rating").takeIf { it != "0" }.orEmpty() },
            poster = item.poster ?: info.img("movie_image"),
        )
    }

    override fun seasons(type: String, item: VodItem): List<Season> {
        val js = api("action" to "get_series_info", "series_id" to item.id) as? JSONObject ?: return emptyList()
        val eps = js.opt("episodes")
        val bySeason = linkedMapOf<String, List<JSONObject>>()
        when (eps) {
            is JSONObject -> eps.keys().forEach { k -> bySeason[k] = eps.opt(k).objects() }
            is JSONArray -> {
                // Some servers send a list of season lists, others one flat list.
                for (i in 0 until eps.length()) {
                    when (val v = eps.opt(i)) {
                        is JSONArray -> bySeason["${i + 1}"] = v.objects()
                        is JSONObject -> {
                            val s = v.str("season").ifEmpty { "1" }
                            bySeason[s] = (bySeason[s] ?: emptyList()) + v
                        }
                    }
                }
            }
        }
        return bySeason.entries
            .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
            .map { (season, list) ->
                Season(
                    id = season,
                    name = "Season $season",
                    episodes = list.mapNotNull { e ->
                        val id = e.str("id").ifEmpty { return@mapNotNull null }
                        val ext = e.str("container_extension").ifEmpty { "mp4" }
                        val num = e.str("episode_num")
                        Episode(
                            title = e.str("title").ifEmpty { "Episode $num" },
                            number = num,
                            cmd = "$root/series/$user/$pass/$id.$ext",
                            seriesParam = "",
                            plot = e.optJSONObject("info")?.str("plot").orEmpty(),
                        )
                    }.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE },
                )
            }
    }

    override fun episodes(type: String, item: VodItem, season: Season): List<Episode> = season.episodes.orEmpty()

    override fun createLink(type: String, cmd: String, series: String, movieId: String?): String {
        if (cmd.startsWith("http", true)) return cmd
        throw IOException("No stream address for this.")
    }
}
