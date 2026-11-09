package ca.sanjwad.tvplayer.stalker

import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
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

/**
 * Looks up IPv4 addresses first. Stalker video links are often locked to the address the portal
 * saw; if the portal is reached over IPv6 and the video server over IPv4 they won't match.
 */
object Ipv4First : okhttp3.Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> =
        okhttp3.Dns.SYSTEM.lookup(hostname).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
}

/** The last portal requests and replies, shown on the Portal log screen in Settings. */
object PortalLog {
    private val lines = ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized fun add(s: String) {
        lines.addLast("${fmt.format(java.util.Date())}  $s")
        while (lines.size > 600) lines.removeFirst()
    }

    /** Short summary of the last play attempt, shown pinned at the top of the log. */
    @Volatile var pinned: List<String> = emptyList()

    @Synchronized fun all(): List<String> = lines.toList()
    @Synchronized fun clear() = lines.clear()

    /**
     * Uploads the log (MAC addresses hidden) to a public paste site that deletes it after a couple of
     * days, and returns the link, so it can be read in full instead of photographed.
     */
    fun share(): String {
        val mac = Regex("[0-9A-Fa-f]{2}(?:(?::|%3A|%3a)[0-9A-Fa-f]{2}){5}")
        val text = (listOf("Sanj TV portal log, ${java.util.Date()}", "", "PINNED:") + pinned +
            listOf("", "LOG (oldest first):") + all()).joinToString("\n").replace(mac, "[MAC hidden]")
        val http = okhttp3.OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val tries = listOf(
            okhttp3.Request.Builder().url("https://dpaste.com/api/v2/").header("User-Agent", "SanjTV/1.0")
                .post(okhttp3.FormBody.Builder().add("content", text).add("syntax", "text")
                    .add("expiry_days", "2").add("title", "Sanj TV portal log").build()).build(),
            okhttp3.Request.Builder().url("https://paste.rs/").header("User-Agent", "SanjTV/1.0")
                .post(text.toRequestBody("text/plain".toMediaType())).build(),
        )
        var err = "no reply"
        for (req in tries) {
            try {
                http.newCall(req).execute().use { r ->
                    val link = r.body?.string().orEmpty().trim().ifBlank { r.header("Location").orEmpty() }
                    if (r.isSuccessful && link.startsWith("http")) return link
                    err = "${req.url.host} replied ${r.code}"
                }
            } catch (e: Exception) { err = "${req.url.host}: ${e.message ?: e.javaClass.simpleName}" }
        }
        throw IOException("Couldn't upload the log ($err).")
    }
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
    /** Where the on-TV copy of the movie list lives (null = don't keep one). */
    private val indexDir: java.io.File? = null,
) : IptvSource {

    private val mac = macAddress.trim().uppercase()
    private val portal: HttpUrl = normalise(portalUrl)
    override val portalHost: String get() = portal.host
    private val root = "${portal.scheme}://${portal.host}:${portal.port}"
    private val candidates = buildCandidates()

    private val http = OkHttpClient.Builder()
        .dns(Ipv4First)
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

    private val sn get() = serial.trim().ifBlank { md5(mac).take(13) }

    /** Ways portals expect the MAC in the cookie. The first is what a real MAG box sends; the rest are fallbacks. */
    private val macFormats: List<Pair<String, (String) -> String>> = listOf(
        "upper %3A" to { m: String -> m.uppercase().replace(":", "%3A") },
        "upper" to { m: String -> m.uppercase() },
        "lower %3a" to { m: String -> m.lowercase().replace(":", "%3a") },
        "lower" to { m: String -> m.lowercase() },
        "upper no colons" to { m: String -> m.uppercase().replace(":", "") },
        "lower no colons" to { m: String -> m.lowercase().replace(":", "") },
    )
    @Volatile private var macFormat = 0

    /** Cookies the portal set (PHPSESSID and friends), sent back on every request like a browser would. */
    private val portalCookies = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val cookie get() = buildString {
        append("mac=").append(macFormats[macFormat].second(mac))
        append("; sn=").append(sn)
        append("; stb_lang=en; timezone=America%2FToronto")
        portalCookies.forEach { (k, v) -> if (k !in setOf("mac", "sn", "stb_lang", "timezone")) append("; $k=$v") }
    }

    /** Headers a MAG box sends when it fetches the video itself; some stream servers check them. */
    override fun streamHeaders(): Map<String, String> = buildMap {
        put("X-User-Agent", "Model: MAG250; Link: WiFi")
        put("Referer", portal.toString())
        put("Cookie", cookie)
        token?.let { put("Authorization", "Bearer $it") }
    }

    // ---------- low level ----------

    /** Portals throttle a MAC that fires many requests at once, so at most three go out together. */
    private val gate = java.util.concurrent.Semaphore(3, true)
    /** Background paging (the whole catalogue, for trending rows) gets its own single lane so it never holds up the screen. */
    private val bgGate = java.util.concurrent.Semaphore(2, true)
    private val background = ThreadLocal.withInitial { false }

    private fun request(base: String, params: Map<String, String>): JSONObject {
        val g = if (background.get()) bgGate else gate
        g.acquire()
        try { return requestNow(base, params) } finally { g.release() }
    }

    private fun requestNow(base: String, params: Map<String, String>): JSONObject {
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
                r.headers("Set-Cookie").forEach { c ->
                    val pair = c.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotEmpty() && pair.contains('=')) portalCookies[name] = pair.substringAfter('=').trim()
                }
                if (r.code == 401 || r.code == 403 || body.contains("Authorization failed", true) ||
                    body.contains("access denied", true)) {
                    throw AuthException()
                }
                if (!r.isSuccessful) throw IOException("The portal replied with error ${r.code}.")
                val start = body.indexOf('{')
                if (start < 0) throw IOException("Unexpected reply from the portal.")
                val json = try {
                    JSONObject(body.substring(start).trim().removeSuffix("*/").trim())
                } catch (e: Exception) {
                    throw IOException("Couldn't read the portal's reply.")
                }
                // Expired session: the portal answers 200 but flags the token as not valid.
                val nv = json.optJSONObject("js")?.opt("not_valid")
                if (nv == 1 || nv == true || nv == "1" || nv == "true") throw AuthException()
                return json
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
        portalCookies.clear()
        // Try the MAC format that worked last time first, then the others.
        val order = listOf(macFormat) + macFormats.indices.filter { it != macFormat }
        for ((round, fmt) in order.withIndex()) {
        // Another MAC format only helps if the portal answered but wouldn't log us in.
        if (round > 0 && !refused && report.none { it.endsWith("no login token") }) break
        macFormat = fmt
        if (round > 0) PortalLog.add("login: trying MAC as ${macFormats[fmt].first}")
        for (base in candidates) {
            val short = base.removePrefix(root).ifEmpty { "/" }
            try {
                token = null
                portalCookies.clear()
                val js = request(base, mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"))
                    .optJSONObject("js")
                val t = js?.optString("token").orEmpty()
                if (t.isBlank()) { if (round == 0) report += "$short: no login token"; continue }
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
                if (round > 0) PortalLog.add("login: worked with MAC as ${macFormats[fmt].first}")
                startWatchdog()
                runCatching {
                    val acc = request(base, mapOf("type" to "account_info", "action" to "get_main_info")).optJSONObject("js")
                    expiry = acc?.optString("phone").orEmpty().takeIf { it.isNotBlank() && it != "null" }
                        ?: acc?.optString("end_date").orEmpty().takeIf { it != "null" }.orEmpty()
                }
                return
            } catch (e: AuthException) {
                refused = true; if (round == 0) report += "$short: MAC refused"
            } catch (e: IOException) {
                if (e.message?.startsWith("The portal says") == true) { api = null; throw e }
                if (round == 0) report += "$short: ${e.message.orEmpty()}".trim()
            } catch (e: Exception) {
                if (round == 0) report += "$short: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
            }
        }
        }
        macFormat = order.first()
        api = null
        val head = if (refused) "The portal refused this MAC address ($mac)." else "Couldn't log in to $root."
        throw IOException(head + "\n\n" + report.joinToString("\n"))
    }

    private fun call(params: Map<String, String>): JSONObject {
        if (api == null) synchronized(this) { if (api == null) connect() }
        val used = token
        return try {
            request(api!!, params)
        } catch (e: AuthException) {
            // Several rows load at once; only the first to hit an expired session logs in again,
            // the rest just retry with the new token instead of each starting another login.
            synchronized(this) { if (token == used || api == null) connect() }
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

    override fun search(type: String, query: String): List<VodItem> {
        // Search the copy on the TV once there is one: the portal's own search times out on big catalogues.
        index?.takeIf { it.count() > 0 }?.let { idx ->
            val hits = idx.search(query)
            return if (type == "series") hits.filter { it.isSeries } else hits
        }
        fun fetch(q: String, pages: Int) = runCatching {
            allPages(
                mapOf("type" to type, "action" to "get_ordered_list", "category" to "*", "genre" to "*",
                    "search" to q, "sortby" to "added", "fav" to "0"),
                maxPages = pages,
            ).map { toVod(it, type) }
        }
        // The portal matches the text exactly, so "Mission Impossible" misses "Mission: Impossible – Fallout".
        // Ask for the longest word too, then keep titles that contain every word, ignoring punctuation.
        val words = TitleMatch.norm(query).split(" ").filter { it.isNotBlank() }
        fun matches(v: VodItem) = TitleMatch.norm(v.name).split(" ").toSet().let { t -> words.all { w -> t.any { it.startsWith(w) } } }
        val exact = fetch(query, 3)
        val hits = exact.getOrDefault(emptyList()).filter(::matches)
        val out = if (hits.isNotEmpty() || words.size < 2) hits.ifEmpty { exact.getOrThrow() }
        else {
            val key = words.maxByOrNull { it.length }!!
            fetch(key, 6).getOrDefault(emptyList()).filter(::matches)
        }
        return out.distinctBy { it.id }
    }

    private val catalogueCache = java.util.concurrent.ConcurrentHashMap<String, List<VodItem>>()

    /**
     * Stalker portals only hand out about 14 titles per request, so the full catalogue would take
     * hundreds of calls. The newest few hundred are enough to match trending lists.
     */
    override fun catalogue(type: String): List<VodItem> = catalogueCache.getOrPut(type) {
        // This portal has ~100,000 titles at 14 a page, so this is slow: run it in the background lane.
        background.set(true)
        try {
            allPages(
                mapOf("type" to type, "action" to "get_ordered_list", "category" to "*", "genre" to "*",
                    "sortby" to "added", "fav" to "0"),
                maxPages = 30,
            ).map { toVod(it, type) }.distinctBy { it.id }
        } finally { background.set(false) }
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
            Season(s.optString("id"), name, eps) to (num.toIntOrNull() ?: numberIn(name))
        }.sortedWith(compareBy(nullsLast()) { it.second }).map { it.first }.map { s ->
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
        ).let { rows ->
            // Portals often list episodes newest first and sometimes leave the number out, so use the
            // real episode number (from its fields or its name) and sort on it; position is the last resort.
            val numbered = rows.map { r ->
                val name = r.optString("name").clean().ifBlank { r.optString("series_name").clean() }
                r to (listOf("series_number", "episode_number", "number").firstNotNullOfOrNull { k -> r.optString(k).clean().toIntOrNull() }
                    ?: episodeNumberIn(name))
            }
            if (numbered.all { it.second != null }) numbered.sortedBy { it.second }.map { it.first to it.second!! }
            else rows.mapIndexed { i, r -> r to (i + 1) }
        }.map { (it, num) ->
            val n = num.toString()
            val cmd = it.optString("cmd").clean()
            Episode(
                title = it.optString("name").clean().ifBlank { it.optString("series_name").clean() }.ifBlank { "Episode $n" },
                number = n,
                // No cmd: the episode's video files have to be looked up by id when it's played.
                cmd = cmd.ifBlank { "ep:${item.id}:${season.id}:${it.optString("id")}" },
                seriesParam = if (cmd.isBlank()) "" else n,
            )
        }

    /** "Season 3" → 3. */
    private fun numberIn(s: String): Int? = Regex("\\d+").find(s)?.value?.toIntOrNull()

    /** "S02E05 …", "Episode 5", "E5" → 5. */
    private fun episodeNumberIn(s: String): Int? =
        Regex("(?:S\\d{1,2}\\s*|\\b)E(?:pisode)?\\s*(\\d{1,4})", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toIntOrNull()
            ?: s.trim().toIntOrNull()

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

    // ---------- movie list on the TV ----------

    @Volatile private var index: VodIndex? = null
    @Volatile private var syncStarted = false

    /** Progress of the movie-list download, shown on the Search screen ("" when done). */
    @Volatile var indexStatus: String = ""
        private set

    /** Called after login (alongside the TV guide download): keeps the on-TV movie list up to date. */
    override fun ensureGuide() {
        if (indexDir == null) return
        synchronized(this) { if (syncStarted) return; syncStarted = true }
        Thread({
            while (true) {
                try { syncIndex() } catch (e: Exception) {
                    indexStatus = "Movie list download paused; it carries on next time the app starts."
                    PortalLog.add("movie list: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                    return@Thread
                }
                // Top up with new titles a few times a day while the app is open.
                Thread.sleep(6 * 3600_000L)
            }
        }, "vod-index").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun syncIndex() {
        Thread.sleep(8_000) // let the first screen load first
        background.set(true)
        val dir = indexDir ?: return
        dir.mkdirs()
        val idx = index ?: VodIndex(java.io.File(dir, md5("$root|$mac").take(16) + ".db")).also { index = it }
        val seriesCats = runCatching { vodCategories("vod").filter { isSeriesLike(it.title) }.map { it.id }.toSet() }
            .getOrDefault(emptySet())

        fun page(p: Int): Pair<List<JSONObject>, Int> {
            var last: Exception? = null
            repeat(4) { attempt ->
                try {
                    background.set(true)
                    val js = call(mapOf("type" to "vod", "action" to "get_ordered_list", "category" to "*", "genre" to "*",
                        "sortby" to "added", "fav" to "0", "p" to p.toString())).optJSONObject("js")
                    return js?.optJSONArray("data").mapObjects { it } to (js?.optInt("total_items", 0) ?: 0)
                } catch (e: Exception) { last = e; Thread.sleep(3_000L * (attempt + 1)) }
            }
            throw last ?: IOException("page $p failed")
        }
        fun store(rows: List<JSONObject>) = idx.put(rows.map { o ->
            val cat = o.optString("category_id").clean()
            val v = toVod(o, "vod")
            (if (cat in seriesCats) v.copy(isSeries = true) else v) to cat
        })

        // 1. New titles since last time (the list is newest first): stop at the first one already here.
        if (idx.count() > 0) {
            var p = 1
            while (p <= 80) {
                val (rows, _) = page(p)
                if (rows.isEmpty()) break
                val known = rows.any { idx.has(it.optString("id")) }
                store(rows)
                if (known) break
                p++
            }
            PortalLog.add("movie list: topped up ($p page${if (p == 1) "" else "s"} checked), ${idx.count()} titles")
        }

        // 2. The first full download, resumed where it stopped. Two pages at a time, in the background lane.
        if (idx.meta("full") != "1") {
            var p = idx.meta("next")?.toIntOrNull() ?: 1
            val (first, total) = page(p)
            store(first)
            val per = first.size.coerceAtLeast(1)
            val pages = (total + per - 1) / per
            PortalLog.add("movie list: downloading from page $p of $pages ($total titles)")
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                while (p < pages) {
                    val batch = ((p + 1)..minOf(p + 2, pages)).toList()
                    batch.map { n -> pool.submit<List<JSONObject>> { page(n).first } }.forEach { store(it.get()) }
                    p = batch.last()
                    idx.setMeta("next", (p + 1).toString())
                    indexStatus = "Downloading the movie list for search: ${p * 100 / pages}% (${idx.count()} titles so far)"
                }
            } finally { pool.shutdown() }
            idx.setMeta("full", "1")
            PortalLog.add("movie list: complete, ${idx.count()} titles")
        }
        idx.setMeta("synced", System.currentTimeMillis().toString())
        indexStatus = ""
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

    @Volatile private var lastWatchdog = 0L
    @Volatile private var watchdogThread: Thread? = null

    private fun startWatchdog() {
        if (watchdogThread != null) return
        watchdogThread = Thread {
            while (true) {
                try { Thread.sleep(60_000) } catch (e: InterruptedException) { return@Thread }
                if (api != null) watchdog()
            }
        }.apply { isDaemon = true; name = "stalker-watchdog"; start() }
    }

    /** The "I'm still here" call a MAG box makes about once a minute; some protection systems require it. */
    fun watchdog(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWatchdog < 50_000) return
        lastWatchdog = now
        runCatching {
            call(mapOf("type" to "watchdog", "action" to "get_events", "cur_play_type" to "1",
                "event_active_id" to "0", "init" to "0"))
        }
    }

    /** Tells the portal what we're about to play, as a MAG box does. */
    private fun reportPlay(url: String, contentId: String) {
        runCatching {
            call(mapOf("type" to "stb", "action" to "log", "real_action" to "play", "param" to url,
                "content_id" to contentId, "tmp_type" to "2"))
        }
    }

    /** create_link for [cmd]; returns the address the portal gave back, or null. */
    /** What the portal said to the last create_link that gave no address, for the Portal log. */
    @Volatile private var lastLinkReply = ""

    private fun linkFor(cmd: String, series: String = ""): String? {
        val reply = try {
            call(mapOf("type" to "vod", "action" to "create_link", "cmd" to cmd, "series" to series,
                "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0", "force_ch_link_check" to "0"))
        } catch (e: Exception) {
            lastLinkReply = "${e.javaClass.simpleName} ${e.message.orEmpty()}".take(100); return null
        }
        val js = reply.opt("js")
        val raw = when (js) { is JSONObject -> js.optString("cmd").clean(); is String -> js; else -> "" }
        return directUrl(raw).also { if (it == null) lastLinkReply = js?.toString()?.take(100) ?: "(empty reply)" }
    }

    private fun playFiles(movieId: String, seasonId: String, episodeId: String, skip: String, log: MutableList<String>): String? {
        val rows = runCatching {
            allPages(mapOf("type" to "vod", "action" to "get_ordered_list", "movie_id" to movieId,
                "season_id" to seasonId, "episode_id" to episodeId), maxPages = 2)
        }.onFailure { log += "• file list: ${it.message.orEmpty()}" }.getOrDefault(emptyList())

        // Every way a box might ask for this title's video, in order. The portal's file records
        // (/media/file_<id>.mpg, best quality first) come first: that's what a MAG box plays.
        val tries = mutableListOf<Pair<String, () -> String?>>()
        val later = mutableListOf<Pair<String, () -> String?>>()
        val ep = episodeId.takeIf { it != "0" }.orEmpty()
        fun quality(o: JSONObject): Int = Regex("\\d{3,4}").find(o.optString("quality") + " " + o.optString("name"))
            ?.value?.toIntOrNull() ?: if (o.optString("quality").contains("4k", true)) 2160 else 0
        // Some portals ignore movie_id and send back the whole catalogue (newest first); playing
        // file_<id> from that plays some other title. Keep only rows that are this title's files.
        val mine = rows.filter { r ->
            val mid = r.optString("movie_id").clean()
            val looksLikeTitle = r.has("category_id") || r.has("genres_str")
            when {
                mid.isNotBlank() -> mid == movieId
                episodeId == "0" && seasonId == "0" -> !looksLikeTitle || r.optString("id") == movieId
                else -> !looksLikeTitle
            }
        }
        if (mine.size != rows.size) log += "• ignored ${rows.size - mine.size} file rows for other titles"
        PortalLog.add("files for $movieId/$seasonId/$episodeId: " + mine.joinToString(" | ") { r ->
            listOf("id", "movie_id", "quality", "name", "series_number").mapNotNull { k -> r.optString(k).clean().takeIf { it.isNotBlank() }?.let { "$k=$it" } }.joinToString(",")
        }.take(500))
        for (row in mine) {
            val inner = row.optJSONArray("files")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.orEmpty()
            for (f in inner.sortedByDescending(::quality) + row) {
                val id = f.optString("id").clean()
                val url = f.optString("url").clean()
                val cmd = f.optString("cmd").clean()
                if (id.isNotBlank()) {
                    tries += "file_$id" to { linkFor("/media/file_$id.mpg") }
                    // series=1 means "episode 1" on some portals, so only movies get it.
                    if (ep.isBlank()) tries += "file_$id series=1" to { linkFor("/media/file_$id.mpg", "1") }
                }
                if (cmd.isNotBlank() && cmd != skip) {
                    later += "link(cmd) ${cmd.take(50)}" to { linkFor(cmd) }
                    if (cmd.startsWith("http", true)) later += "link(ffmpeg cmd)" to { linkFor("ffmpeg $cmd") }
                    if (directUrl(cmd) != null) later += "cmd as is" to { directUrl(cmd) }
                }
                if (url.startsWith("http", true)) {
                    later += "link(url)" to { linkFor(url) }
                    later += "file url as is" to { url }
                }
            }
        }
        tries += later
        if (tries.isEmpty()) { log += "• file list: (none)"; PortalLog.pinned = listOf("PLAY: no video files listed"); return null }

        watchdog(force = true)
        val summary = mutableListOf("PLAY $movieId/$seasonId/$episodeId — tried in order (watchdog sent, IPv4 first):")
        var firstUrl: String? = null
        val seen = HashSet<String>()
        for ((name, get) in tries) {
            val u = runCatching { get() }.getOrNull()
            if (u == null) { summary += "  $name → portal gave no link: $lastLinkReply"; continue }
            if (!seen.add(u)) { summary += "  $name → same link as before"; continue }
            if (firstUrl == null) firstUrl = u
            summary += "  $name → ${u.take(70)}"
            reportPlay(u, movieId)
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

        // Last resort: the address in the cmd itself, without the portal's say-so.
        directUrl(cmd)?.let { return it }
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
