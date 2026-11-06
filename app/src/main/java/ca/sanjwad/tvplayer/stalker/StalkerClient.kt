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

class AuthException : IOException("Authorization failed")

/** The last portal requests and replies, shown on the Portal log screen in Settings. */
object PortalLog {
    private val lines = ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized fun add(s: String) {
        lines.addLast("${fmt.format(java.util.Date())}  $s")
        while (lines.size > 200) lines.removeFirst()
    }

    /** Short summary of the last play attempt, shown pinned at the top of the log. */
    @Volatile var pinned: List<String> = emptyList()

    @Synchronized fun all(): List<String> = lines.toList()
    @Synchronized fun clear() = lines.clear()
}

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
) : IptvSource {

    private val mac = macAddress.trim().uppercase()
    private val portal: HttpUrl = normalise(portalUrl)
    override val portalHost: String get() = portal.host
    private val root = "${portal.scheme}://${portal.host}:${portal.port}"
    private val candidates = buildCandidates()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile private var api: String? = null
    @Volatile private var token: String? = null

    /** Subscription end date shown in Settings, if the portal reports one. */
    @Volatile var expiry: String = ""
        private set

    companion object {
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

        fun isValidMac(m: String) = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(m.trim())
    }

    private fun buildCandidates(): List<String> {
        val path = portal.encodedPath
        val list = mutableListOf<String>()
        if (path.endsWith(".php")) list += "$root$path"
        if (path.contains("stalker_portal")) list += "$root/stalker_portal/server/load.php"
        if (path.contains("/c/")) {
            val prefix = path.substringBefore("/c/")
            if (prefix.isNotEmpty()) {
                list += "$root$prefix/portal.php"
                list += "$root$prefix/server/load.php"
            }
        }
        list += "$root/portal.php"
        list += "$root/stalker_portal/server/load.php"
        list += "$root/server/load.php"
        list += "$root/c/portal.php"
        list += "$root/c/server/load.php"
        return list.distinct()
    }

    private val cookie get() = "mac=${mac.replace(":", "%3A")}; stb_lang=en; timezone=America%2FToronto"

    /** Headers a MAG box sends when it fetches the video itself; some stream servers check them. */
    override fun streamHeaders(): Map<String, String> = buildMap {
        put("X-User-Agent", "Model: MAG250; Link: WiFi")
        put("Referer", portal.toString())
        put("Cookie", cookie)
        token?.let { put("Authorization", "Bearer $it") }
    }

    // ---------- low level ----------

    private fun request(base: String, params: Map<String, String>): JSONObject {
        val url = base.toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("JsHttpRequest", "1-xml")
        }.build()

        val req = Request.Builder().url(url)
            .header("User-Agent", Net.UA)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", portal.toString())
            .header("Accept", "*/*")
            .header("Cookie", cookie)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()

        val what = "${params["type"]}/${params["action"]}" +
            (params["cmd"]?.let { " cmd=${it.take(60)}" } ?: "") +
            (params["series"]?.takeIf { it.isNotBlank() }?.let { " series=$it" } ?: "")
        val short = base.removePrefix(root)
        val t0 = System.currentTimeMillis()
        try {
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val ms = System.currentTimeMillis() - t0
                PortalLog.add("$what @$short → ${r.code} (${ms}ms) ${body.replace(token ?: "\u0000", "•••").take(600)}")
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
        } catch (e: IOException) {
            if (e !is AuthException && e.message?.startsWith("The portal") != true && e.message?.startsWith("Unexpected") != true &&
                e.message?.startsWith("Couldn't read") != true) {
                PortalLog.add("$what @$short → ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            }
            throw e
        }
    }

    private fun profileParams(): Map<String, String> {
        val sn = serial.trim().ifBlank { md5(mac).take(13) }
        // Blank device IDs are sent blank, like TiVimate does when they're "Not set": portals that
        // verify devices compare these with what the box sent the first time.
        val dev = deviceId.trim()
        val dev2 = deviceId2.trim()
        return mapOf(
            "type" to "stb", "action" to "get_profile", "hd" to "1",
            "ver" to "ImageDescription: 0.2.18-r23-250; ImageDate: Thu Sep 13 11:31:16 EEST 2018; PORTAL version: 5.6.2; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c",
            "num_banks" to "2", "sn" to sn, "stb_type" to "MAG250", "image_version" to "218",
            "video_out" to "hdmi", "device_id" to dev, "device_id2" to dev2,
            "signature" to "", "auth_second_step" to "1", "hw_version" to "1.7-BD-00",
            "not_valid_token" to "0", "api_signature" to "262", "prehash" to "",
            "metrics" to JSONObject().put("mac", mac).put("sn", sn).put("model", "MAG250")
                .put("type", "STB").put("uid", dev).put("random", "").toString(),
        )
    }

    /** Logs in. Tries the usual API paths until one gives a token. */
    @Synchronized
    override fun connect() {
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
                val profile = request(base, profileParams()).optJSONObject("js")
                // A profile with "status": 1 (or a block message) means the MAC isn't active.
                val blocked = profile?.optString("block_msg").orEmpty()
                if (blocked.isNotBlank() && blocked != "null") {
                    val text = blocked.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("<[^>]+>"), "").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                    val hint = if (text.contains("verification", true))
                        "\n\nThis usually means the serial number or device ID doesn't match what this MAC first logged in with. " +
                            "Copy them exactly from TiVimate (Settings → Playlists → Stalker Portal parameters)." else ""
                    throw IOException("The portal says:\n$text$hint")
                }
                runCatching {
                    val acc = request(base, mapOf("type" to "account_info", "action" to "get_main_info")).optJSONObject("js")
                    expiry = acc?.optString("phone").orEmpty().takeIf { it.isNotBlank() && it != "null" }
                        ?: acc?.optString("end_date").orEmpty().takeIf { it != "null" }.orEmpty()
                }
                return
            } catch (e: AuthException) {
                refused = true; report += "$short: MAC refused"
            } catch (e: IOException) {
                if (e.message?.startsWith("The portal says") == true) { api = null; throw e }
                report += "$short: ${e.message.orEmpty()}".trim()
            } catch (e: Exception) {
                report += "$short: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
            }
        }
        api = null
        val head = if (refused) "The portal refused this MAC address ($mac)." else "Couldn't log in to $root."
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

    private fun String?.clean(): String = this?.takeIf { it != "null" }.orEmpty()

    /** Fetches every page of an ordered list (episodes, seasons). */
    private fun allPages(params: Map<String, String>, maxPages: Int = 30): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var p = 1
        while (p <= maxPages) {
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

    override fun liveGenres(): List<Genre> =
        call(mapOf("type" to "itv", "action" to "get_genres")).optJSONArray("js")
            .mapObjects { g -> Genre(g.optString("id"), g.optString("title")).takeIf { it.id != "*" } }

    @Volatile private var channelCache: List<Channel>? = null

    override fun liveChannels(): List<Channel> {
        channelCache?.let { return it }
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
                epgId = it.optString("xmltv_id").clean(),
            )
        }.sortedBy { if (it.number > 0) it.number else Int.MAX_VALUE }.also { channelCache = it }
    }

    private val epgCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Programme>>>()

    override fun nowNext(channel: Channel): List<Programme> {
        val now = System.currentTimeMillis()
        epgCache[channel.id]?.let { (t, l) -> if (now - t < 5 * 60_000 && (l.isEmpty() || l.first().end > now)) return l }
        val list = runCatching {
            call(mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to channel.id, "size" to "4"))
                .optJSONArray("js").mapObjects {
                    val s = it.optLong("start_timestamp") * 1000
                    val e = it.optLong("stop_timestamp") * 1000
                    if (s <= 0 || e <= s) null
                    else Programme(it.optString("name").clean(), it.optString("descr").clean(), s, e)
                }.filter { it.end > now }.take(2)
        }.getOrDefault(emptyList())
        epgCache[channel.id] = now to list
        return list
    }

    // ---------- movies & series ----------

    private val catCache = java.util.concurrent.ConcurrentHashMap<String, List<Genre>>()

    fun vodCategories(type: String): List<Genre> = catCache.getOrPut(type) {
        call(mapOf("type" to type, "action" to "get_categories")).optJSONArray("js")
            .mapObjects { Genre(it.optString("id"), it.optString("title")) }
    }

    private fun toVod(o: JSONObject, type: String) = VodItem(
        id = o.optString("id"),
        name = o.optString("name"),
        description = o.optString("description").clean(),
        poster = absolute(o.optString("screenshot_uri")),
        cmd = o.optString("cmd"),
        isSeries = type == "series" || o.optString("is_series") == "1",
        year = o.optString("year").clean().takeIf { it != "0" }.orEmpty(),
        rating = o.optString("rating_imdb").clean().takeIf { it != "0" && it != "N/A" }.orEmpty(),
    )

    override fun vodPage(type: String, categoryId: String, page: Int): Page {
        val js = call(
            mapOf(
                "type" to type, "action" to "get_ordered_list", "category" to categoryId,
                "genre" to "*", "p" to page.toString(), "sortby" to "added", "fav" to "0",
            )
        ).optJSONObject("js")
        val items = js?.optJSONArray("data").mapObjects { toVod(it, type) }
        return Page(items, js?.optInt("total_items", items.size) ?: items.size)
    }

    override fun search(type: String, query: String): List<VodItem> =
        allPages(
            mapOf("type" to type, "action" to "get_ordered_list", "category" to "*", "genre" to "*",
                "search" to query, "sortby" to "added", "fav" to "0"),
            maxPages = 3,
        ).map { toVod(it, type) }.distinctBy { it.id }

    private val catalogueCache = java.util.concurrent.ConcurrentHashMap<String, List<VodItem>>()

    /**
     * Stalker portals only hand out about 14 titles per request, so the full catalogue would take
     * hundreds of calls. The newest few hundred are enough to match trending lists.
     */
    override fun catalogue(type: String): List<VodItem> = catalogueCache.getOrPut(type) {
        allPages(
            mapOf("type" to type, "action" to "get_ordered_list", "category" to "*", "genre" to "*",
                "sortby" to "added", "fav" to "0"),
            maxPages = 30,
        ).map { toVod(it, type) }.distinctBy { it.id }
    }

    override fun seasons(type: String, item: VodItem): List<Season> {
        val rows = allPages(
            mapOf("type" to type, "action" to "get_ordered_list", "movie_id" to item.id,
                "season_id" to "0", "episode_id" to "0")
        )
        return rows.map { s ->
            // Older portals list each season's episode numbers in "series" and play the season's cmd.
            val arr = s.optJSONArray("series")
            val eps = if (arr != null && arr.length() > 0) {
                (0 until arr.length()).map { i ->
                    val n = arr.opt(i).toString()
                    Episode("Episode $n", n, s.optString("cmd"), n)
                }
            } else null
            val num = s.optString("season_number").clean()
            val name = s.optString("name").clean().ifBlank { s.optString("season_name").clean() }
                .ifBlank { if (num.isNotBlank()) "Season $num" else "" }
            Season(s.optString("id"), name, eps)
        }.map { s ->
            // Newer portals (Ministra 5.6) list episodes per season: fetch them now so "Play next"
            // and the episode numbers work (otherwise every show looks "Caught up").
            if (s.episodes != null) s
            else s.copy(episodes = runCatching { episodes(type, item, s) }.getOrDefault(emptyList()))
        }
    }

    override fun episodes(type: String, item: VodItem, season: Season): List<Episode> =
        season.episodes ?: allPages(
            mapOf("type" to type, "action" to "get_ordered_list", "movie_id" to item.id,
                "season_id" to season.id, "episode_id" to "0")
        ).mapIndexed { i, it ->
            val n = it.optString("series_number").clean().ifBlank { (i + 1).toString() }
            val cmd = it.optString("cmd").clean()
            Episode(
                title = it.optString("name").clean().ifBlank { it.optString("series_name").clean() }.ifBlank { "Episode $n" },
                number = n,
                // No cmd: the episode's video files have to be looked up by id when it's played.
                cmd = cmd.ifBlank { "ep:${item.id}:${season.id}:${it.optString("id")}" },
                seriesParam = if (cmd.isBlank()) "" else n,
            )
        }

    private fun isSeriesLike(title: String): Boolean =
        Regex("SERIES|SHOWS|TV SHOW|SEASON|SÉRIE|SERIE", RegexOption.IGNORE_CASE).containsMatchIn(title)

    /**
     * Many providers put TV series inside the movie (vod) section. Use the portal's own series
     * section when it has one; otherwise split the vod section by category name.
     */
    override fun browseCategories(kind: String): Pair<String, List<Genre>> {
        if (kind == "series") {
            val own = runCatching { vodCategories("series") }.getOrDefault(emptyList()).filter { it.id != "*" }
            if (own.isNotEmpty()) return "series" to own
        }
        val vod = runCatching { vodCategories("vod") }
        if (kind == "movies") {
            val all = vod.getOrThrow().filter { it.id != "*" }
            val (ser, mov) = all.partition { isSeriesLike(it.title) }
            return "vod" to if (ser.isEmpty() || mov.isEmpty()) all else mov
        }
        return "vod" to vod.getOrDefault(emptyList()).filter { isSeriesLike(it.title) }
    }

    // ---------- playback ----------

    /** Asks the portal for a playable address for [cmd]. */
    private fun requestLink(type: String, cmd: String, series: String, log: MutableList<String>): String? {
        val reply = try {
            call(
                mapOf(
                    "type" to type, "action" to "create_link", "cmd" to cmd, "series" to series,
                    "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0",
                )
            )
        } catch (e: Exception) {
            log += "• ${e.javaClass.simpleName} ${e.message.orEmpty()}"
            return null
        }
        val js = reply.opt("js")
        val raw = when (js) {
            is JSONObject -> js.optString("cmd").clean()
            is String -> js
            else -> ""
        }
        directUrl(raw)?.let { return it }
        val err = (js as? JSONObject)?.optString("error").clean()
        log += "• ${cmd.take(60)} → ${if (err.isNotBlank()) "error: $err" else js?.toString()?.take(120) ?: "(no js)"}"
        return null
    }

    /**
     * On Ministra portals a movie's or episode's cmd names the title, not a file. A real box
     * lists the title's video files and asks for a link to "/media/file_<id>.mpg".
     * Returns cmds to try, then direct addresses as a last resort.
     */
    private fun fileCmds(movieId: String, seasonId: String, episodeId: String): Pair<List<String>, List<String>> {
        val rows = allPages(
            mapOf("type" to "vod", "action" to "get_ordered_list", "movie_id" to movieId,
                "season_id" to seasonId, "episode_id" to episodeId),
            maxPages = 2,
        )
        val cmds = mutableListOf<String>()
        val urls = mutableListOf<String>()
        fun addFile(o: JSONObject) {
            o.optString("cmd").clean().takeIf { it.isNotBlank() }?.let { cmds += it }
            val id = o.optString("id").clean()
            if (id.isNotBlank() && (o.has("url") || o.has("file_type") || o.has("video_id"))) cmds += "/media/file_$id.mpg"
            o.optString("url").clean().takeIf { it.startsWith("http", true) }?.let { urls += it }
        }
        for (row in rows) {
            val files = row.optJSONArray("files")
            if (files != null) for (j in 0 until files.length()) files.optJSONObject(j)?.let(::addFile)
            addFile(row)
        }
        return cmds.distinct() to urls.distinct()
    }

    /**
     * Checks that the video server will actually serve [url] (not 403), the way the player will ask.
     * Returns a short result like "200 video/mp2t" or "403".
     */
    private fun probe(url: String, ua: String? = Net.UA): Pair<Boolean, String> = try {
        val req = Request.Builder().url(url)
            .apply { if (ua != null) header("User-Agent", ua) else removeHeader("User-Agent") }
            .build()
        http.newCall(req).execute().use { r ->
            val hops = generateSequence(r.priorResponse) { it.priorResponse }.toList().reversed().map { it.code }
            var txt = (hops + r.code).joinToString("→") + " ${r.request.url.host}:${r.request.url.port} ${r.header("Content-Type").orEmpty()}"
            if (r.code >= 400) {
                // The refusal page's visible text often says why (IP, user agent, expired…).
                val body = runCatching { r.peekBody(4000).string() }.getOrDefault("")
                    .replace(Regex("(?s)<style.*?</style>|<script.*?</script>|<head.*?</head>"), " ")
                    .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                if (body.isNotBlank()) txt += " \"${body.take(160)}\""
            }
            (r.code in 200..299) to txt
        }
    } catch (e: Exception) {
        false to "${e.javaClass.simpleName} ${e.message.orEmpty()}"
    }

    /** User-Agent the video server accepted on the last successful probe (null = no header). */
    @Volatile var videoUa: String? = Net.UA
        private set

    private val uaCandidates: List<Pair<String, String?>> = listOf(
        "MAG" to Net.UA,
        "Android ExoPlayer" to "ExoPlayerLib/2.19.1",
        "Android system" to "Dalvik/2.1.0 (Linux; U; Android 11; SHIELD Android TV Build/RQ1A.210105.003)",
        "TiviMate" to "TiviMate/5.1.6 (Linux; Android 11) ExoPlayerLib/2.19.1",
        "okhttp" to "okhttp/4.12.0",
        "Chrome" to "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "VLC" to Net.VIDEO_UA,
        "no user agent" to null,
    )

    /** create_link for [cmd]; returns the address the portal gave back, or null. */
    private fun linkFor(cmd: String, series: String = ""): String? {
        val reply = runCatching {
            call(mapOf("type" to "vod", "action" to "create_link", "cmd" to cmd, "series" to series,
                "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0"))
        }.getOrNull() ?: return null
        val js = reply.opt("js")
        val raw = when (js) { is JSONObject -> js.optString("cmd").clean(); is String -> js; else -> "" }
        return directUrl(raw)
    }

    private fun playFiles(movieId: String, seasonId: String, episodeId: String, skip: String, log: MutableList<String>): String? {
        val rows = runCatching {
            allPages(mapOf("type" to "vod", "action" to "get_ordered_list", "movie_id" to movieId,
                "season_id" to seasonId, "episode_id" to episodeId), maxPages = 2)
        }.onFailure { log += "• file list: ${it.message.orEmpty()}" }.getOrDefault(emptyList())

        // Every way a box might ask for this title's video, in order.
        val tries = mutableListOf<Pair<String, () -> String?>>()
        for (row in rows) {
            val files = row.optJSONArray("files")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.orEmpty() + row
            for (f in files) {
                val id = f.optString("id").clean()
                val url = f.optString("url").clean()
                val cmd = f.optString("cmd").clean()
                if (cmd.isNotBlank() && cmd != skip) tries += "cmd $cmd" to { directUrl(cmd) ?: linkFor(cmd) }
                if (id.isNotBlank() && f.has("video_id")) {
                    tries += "file_$id" to { linkFor("/media/file_$id.mpg") }
                    tries += "file_$id+series" to { linkFor("/media/file_$id.mpg", episodeId.takeIf { it != "0" }.orEmpty()) }
                }
                if (url.startsWith("http", true)) {
                    tries += "link(url)" to { linkFor(url) }
                    tries += "link(ffmpeg url)" to { linkFor("ffmpeg $url") }
                    tries += "file url as is" to { url }
                }
            }
        }
        if (tries.isEmpty()) { log += "• file list: (none)"; PortalLog.pinned = listOf("PLAY: no video files listed"); return null }

        val summary = mutableListOf("PLAY $movieId/$seasonId/$episodeId — tried in order:")
        var firstUrl: String? = null
        val seen = HashSet<String>()
        for ((name, get) in tries) {
            val u = runCatching { get() }.getOrNull()
            if (u == null) { summary += "  $name → portal gave no link"; continue }
            if (!seen.add(u)) { summary += "  $name → same link as before"; continue }
            if (firstUrl == null) firstUrl = u
            summary += "  $name → ${u.take(70)}"
            // Same link, different identities: the video server may only let some players in.
            for ((uaName, ua) in uaCandidates) {
                val (ok, res) = probe(u, ua)
                summary += "     as $uaName ⇒ $res"
                if (ok) {
                    videoUa = ua
                    summary += "  ✓ using $name as $uaName"
                    PortalLog.pinned = summary
                    summary.forEach { PortalLog.add(it) }
                    return u
                }
            }
        }
        summary += "  ✗ nothing worked; playing the first link anyway"
        PortalLog.pinned = summary
        summary.forEach { PortalLog.add(it) }
        log += summary.drop(1)
        return firstUrl
    }

    override fun createLink(type: String, cmd: String, series: String, movieId: String?): String {
        val log = mutableListOf<String>()

        // Episodes from newer portals: look the files up by movie/season/episode id.
        if (cmd.startsWith("ep:")) {
            val (m, sid, eid) = cmd.removePrefix("ep:").split(":").let { Triple(it[0], it.getOrElse(1) { "0" }, it.getOrElse(2) { "0" }) }
            playFiles(m, sid, eid, "", log)?.let { return it }
            throw IOException(
                (if (log.any { it.contains("(none)") }) "This episode isn't available from your provider yet."
                else "The portal didn't return a stream for this episode.") + "\n" + log.joinToString("\n")
            )
        }

        val t = if (type == "series") "vod" else type
        // A cmd that's already a real address needs no create_link round trip.
        if (t != "itv") directUrl(cmd)?.let { return it }

        requestLink(t, cmd, series, log)?.let { return it }
        // Portals differ on episodes: some want the episode number, some only the episode's own cmd.
        if (series.isNotBlank()) requestLink(t, cmd, "", log)?.let { return it }

        if (t == "vod" && series.isBlank()) {
            val id = movieId ?: Regex("/media/(\\d+)\\.mpg").find(cmd)?.groupValues?.get(1)
            if (id != null) playFiles(id, "0", "0", cmd, log)?.let { return it }
        }
        // Episodes from a portal's own series section may need type=series instead.
        if (type == "series" || series.isNotBlank()) {
            requestLink("series", cmd, series, log)?.let { return it }
            if (series.isNotBlank()) requestLink("series", cmd, "", log)?.let { return it }
        }

        if (t == "itv") directUrl(cmd)?.let { return it }
        throw IOException(
            "The portal didn't return a stream for this.\n" + log.joinToString("\n") +
                "\n\nSettings → Portal log has the full replies."
        )
    }

    /** Pulls a full http address out of a portal cmd such as "ffmpeg http://…". */
    private fun directUrl(raw: String): String? {
        val u = raw.trim().split(" ").lastOrNull { it.startsWith("http", true) } ?: return null
        if (u.contains("localhost") || u.contains("127.0.0.1")) return null
        return if (u.endsWith("stream=") || u.endsWith("stream=&")) null else u
    }
}
