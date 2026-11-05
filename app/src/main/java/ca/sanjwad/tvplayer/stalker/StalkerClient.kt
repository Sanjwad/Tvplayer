package ca.sanjwad.tvplayer.stalker

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Genre(val id: String, val title: String)

data class Channel(
    val id: String,
    val name: String,
    val number: Int,
    val cmd: String,
    val logo: String?,
    val genreId: String,
)

data class VodItem(
    val id: String,
    val name: String,
    val description: String,
    val poster: String?,
    val cmd: String,
    val isSeries: Boolean,
    val year: String,
    val rating: String,
)

data class Season(val id: String, val name: String, val episodes: List<Episode>?)

/** [seriesParam] is the episode number some portals expect alongside the season's cmd. */
data class Episode(val title: String, val number: String, val cmd: String, val seriesParam: String)

data class Page(val items: List<VodItem>, val totalItems: Int)

class AuthException : IOException("Authorization failed")

/**
 * Talks to a Stalker / Ministra middleware portal the same way a MAG box does:
 * handshake for a token, then JSON calls against portal.php or server/load.php.
 */
class StalkerClient(
    portalUrl: String,
    macAddress: String,
    private val serial: String = "",
    private val deviceId: String = "",
    private val deviceId2: String = "",
) {

    private val mac = macAddress.trim().uppercase()
    private val portal: HttpUrl = normalise(portalUrl)
    private val root = "${portal.scheme}://${portal.host}:${portal.port}"
    private val candidates = buildCandidates()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile private var api: String? = null
    @Volatile private var token: String? = null

    companion object {
        const val UA =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        private fun normalise(url: String): HttpUrl {
            var u = url.trim()
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
            return try {
                u.toHttpUrl()
            } catch (e: IllegalArgumentException) {
                throw IOException("That portal address doesn't look right.")
            }
        }

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray())
                .joinToString("") { "%02X".format(it) }

        /** Portals often wrap the real address, e.g. "ffmpeg http://host/stream". */
        fun cleanCmd(c: String): String =
            c.trim().split(" ").lastOrNull { it.startsWith("http", true) } ?: c.trim()
    }

    private fun buildCandidates(): List<String> {
        val path = portal.encodedPath
        val list = mutableListOf<String>()
        if (path.endsWith(".php")) list += "$root$path"
        if (path.contains("stalker_portal")) list += "$root/stalker_portal/server/load.php"
        if (path.contains("/c/")) {
            val prefix = path.substringBefore("/c/")
            if (prefix.isNotEmpty()) list += "$root$prefix/portal.php"
        }
        list += "$root/portal.php"
        list += "$root/stalker_portal/server/load.php"
        list += "$root/server/load.php"
        list += "$root/c/portal.php"
        list += "$root/c/server/load.php"
        return list.distinct()
    }

    // ---------- low level ----------

    private fun request(base: String, params: Map<String, String>): JSONObject {
        val url = base.toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("JsHttpRequest", "1-xml")
        }.build()

        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", portal.toString())
            .header("Accept", "*/*")
            .header("Cookie", "mac=${mac.replace(":", "%3A")}; stb_lang=en; timezone=America%2FToronto")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()

        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403 || body.contains("Authorization failed", true)) {
                throw AuthException()
            }
            if (!r.isSuccessful) throw IOException("The portal replied with error ${r.code}.")
            val start = body.indexOf('{')
            if (start < 0) throw IOException("Unexpected reply from the portal.")
            return try {
                JSONObject(body.substring(start))
            } catch (e: Exception) {
                throw IOException("Couldn't read the portal's reply.")
            }
        }
    }

    private fun profileParams(): Map<String, String> {
        val sn = serial.trim().ifBlank { md5(mac).take(13) }
        return mapOf(
            "type" to "stb", "action" to "get_profile", "hd" to "1",
            "ver" to "ImageDescription: 0.2.18-r23-250; ImageDate: Thu Sep 13 11:31:16 EEST 2018; PORTAL version: 5.6.2; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c",
            "num_banks" to "2", "sn" to sn, "stb_type" to "MAG250", "image_version" to "218",
            "video_out" to "hdmi", "device_id" to deviceId.trim(), "device_id2" to deviceId2.trim(),
            "signature" to "", "auth_second_step" to "1", "hw_version" to "1.7-BD-00",
            "not_valid_token" to "0", "api_signature" to "262", "prehash" to "",
        )
    }

    /** Logs in. Tries the usual API paths until one gives a token. */
    @Synchronized
    fun connect() {
        val report = mutableListOf<String>()
        var refused = false
        for (base in candidates) {
            val short = base.removePrefix(root).ifEmpty { "/" }
            try {
                token = null
                val js = request(base, mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"))
                    .optJSONObject("js")
                val t = js?.optString("token").orEmpty()
                if (t.isBlank()) { report += "$short: no login token"; continue }
                token = t
                api = base
                request(base, profileParams())
                return
            } catch (e: AuthException) {
                refused = true; report += "$short: MAC refused"
            } catch (e: Exception) {
                report += "$short: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
            }
        }
        api = null
        val head = if (refused) "The portal refused this MAC address." else "Couldn't log in to $root."
        throw IOException(head + "\n\n" + report.joinToString("\n"))
    }

    private fun call(params: Map<String, String>): JSONObject {
        if (api == null) connect()
        return try {
            request(api!!, params)
        } catch (e: AuthException) {
            connect()
            request(api!!, params)
        }
    }

    private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { o -> f(o)?.let { out.add(it) } }
        return out
    }

    private fun absolute(path: String?): String? {
        if (path.isNullOrBlank() || path == "null") return null
        if (path.startsWith("http", true)) return path
        return "$root/${path.trimStart('/')}"
    }

    /** Fetches every page of an ordered list (episodes, seasons). */
    private fun allPages(params: Map<String, String>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var p = 1
        while (p <= 30) {
            val js = call(params + ("p" to p.toString())).optJSONObject("js") ?: break
            val data = js.optJSONArray("data").mapObjects { it }
            if (data.isEmpty()) break
            out += data
            val total = js.optInt("total_items", 0)
            if (total <= 0 || out.size >= total) break
            p++
        }
        return out
    }

    // ---------- live TV ----------

    fun liveGenres(): List<Genre> =
        call(mapOf("type" to "itv", "action" to "get_genres")).optJSONArray("js")
            .mapObjects { Genre(it.optString("id"), it.optString("title")) }

    fun liveChannels(): List<Channel> {
        val js = call(mapOf("type" to "itv", "action" to "get_all_channels")).optJSONObject("js")
        return js?.optJSONArray("data").mapObjects {
            Channel(
                id = it.optString("id"),
                name = it.optString("name"),
                number = it.optString("number").toIntOrNull() ?: 0,
                cmd = it.optString("cmd"),
                logo = it.optString("logo").let { l ->
                    if (l.isNotBlank() && l != "null" && !l.contains('/')) "$root/stalker_portal/misc/logos/320/$l"
                    else absolute(l)
                },
                genreId = it.optString("tv_genre_id"),
            )
        }.sortedBy { if (it.number > 0) it.number else Int.MAX_VALUE }
    }

    // ---------- movies & series ----------

    fun vodCategories(type: String): List<Genre> =
        call(mapOf("type" to type, "action" to "get_categories")).optJSONArray("js")
            .mapObjects { Genre(it.optString("id"), it.optString("title")) }

    private fun toVod(o: JSONObject, type: String) = VodItem(
        id = o.optString("id"),
        name = o.optString("name"),
        description = o.optString("description").takeIf { it != "null" }.orEmpty(),
        poster = absolute(o.optString("screenshot_uri")),
        cmd = o.optString("cmd"),
        isSeries = type == "series" || o.optString("is_series") == "1",
        year = o.optString("year").takeIf { it != "null" && it != "0" }.orEmpty(),
        rating = o.optString("rating_imdb").takeIf { it != "null" && it != "0" && it != "N/A" }.orEmpty(),
    )

    fun vodPage(type: String, categoryId: String, page: Int): Page {
        val js = call(
            mapOf(
                "type" to type, "action" to "get_ordered_list", "category" to categoryId,
                "genre" to "*", "p" to page.toString(), "sortby" to "added", "fav" to "0",
            )
        ).optJSONObject("js")
        val items = js?.optJSONArray("data").mapObjects { toVod(it, type) }
        return Page(items, js?.optInt("total_items", items.size) ?: items.size)
    }

    fun seasons(type: String, item: VodItem): List<Season> {
        val rows = allPages(
            mapOf("type" to type, "action" to "get_ordered_list", "movie_id" to item.id,
                "season_id" to "0", "episode_id" to "0")
        )
        return rows.map { s ->
            val arr = s.optJSONArray("series")
            val eps = if (arr != null && arr.length() > 0) {
                (0 until arr.length()).map { i ->
                    val n = arr.opt(i).toString()
                    Episode("Episode $n", n, s.optString("cmd"), n)
                }
            } else null
            Season(s.optString("id"), s.optString("name"), eps)
        }
    }

    fun episodes(type: String, item: VodItem, season: Season): List<Episode> =
        allPages(
            mapOf("type" to type, "action" to "get_ordered_list", "movie_id" to item.id,
                "season_id" to season.id, "episode_id" to "0")
        ).map {
            Episode(
                title = it.optString("name"),
                number = it.optString("series_number").takeIf { n -> n != "null" }.orEmpty(),
                cmd = it.optString("cmd"),
                seriesParam = "",
            )
        }

    // ---------- playback ----------

    fun createLink(type: String, cmd: String, series: String = ""): String {
        val js = call(
            mapOf(
                "type" to type, "action" to "create_link", "cmd" to cmd, "series" to series,
                "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0",
            )
        ).optJSONObject("js")
        val out = cleanCmd(js?.optString("cmd").orEmpty())
        if (out.startsWith("http", true)) return out
        val fallback = cleanCmd(cmd)
        if (fallback.startsWith("http", true) && !fallback.contains("localhost")) return fallback
        throw IOException("The portal didn't return a stream for this.")
    }
}
