package ca.sanjwad.tvplayer.stalker

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Xtream Codes API (player_api.php). Stream addresses are built directly from the
 * server, username, password and stream id, so playback needs no extra round trip.
 */
class XtreamClient(
    serverUrl: String,
    private val user: String,
    private val pass: String,
    /** Where category lists are saved between launches, so browsing opens instantly. */
    private val cacheDir: java.io.File? = null,
) : IptvSource {

    private val base: HttpUrl = normalise(serverUrl)
    private val root = "${base.scheme}://${base.host}:${base.port}"
    override val portalHost: String get() = base.host

    /** Small API calls: give up after 30s overall so a stalled server can't hang the screen. */
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Big catalogue downloads: allowed longer overall, but must keep data flowing. */
    private val bulkHttp = http.newBuilder()
        .callTimeout(4, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.SECONDS)
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
        val req = Request.Builder().url(url).header("User-Agent", Net.UA).build()
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

    override fun liveGenres(): List<Genre> {
        val file = cacheFile("live_cats")
        readCats(file, maxAgeMs = 6 * HOUR)?.let { return it }
        return try {
            api("action" to "get_live_categories").objects().map { Genre(it.str("category_id"), it.str("category_name")) }
                .also { writeCats(file, it) }
        } catch (e: Exception) {
            readCats(file, Long.MAX_VALUE) ?: throw e
        }
    }

    override fun liveChannels(): List<Channel> {
        val file = cacheFile("live_channels")
        // Older saved lists lack guide ids; refetch those once.
        readChannels(file, maxAgeMs = 6 * HOUR)?.takeIf { l -> l.any { it.epgId.isNotEmpty() } }?.let { return it }
        return try {
            streamObjects("action" to "get_live_streams", fields = setOf("stream_id", "name", "num", "stream_icon", "category_id", "epg_channel_id"))
                .mapNotNull {
                    val id = it.s("stream_id").ifEmpty { return@mapNotNull null }
                    Channel(
                        id = id,
                        name = it.s("name"),
                        number = it.s("num").toIntOrNull() ?: 0,
                        cmd = "$root/live/$user/$pass/$id.m3u8",
                        logo = it.im("stream_icon"),
                        genreId = it.s("category_id"),
                        epgId = it.s("epg_channel_id"),
                    )
                }.sortedBy { if (it.number > 0) it.number else Int.MAX_VALUE }
                .also { writeChannels(file, it) }
        } catch (e: Exception) {
            readChannels(file, Long.MAX_VALUE) ?: throw e
        }
    }

    private fun writeChannels(f: java.io.File?, list: List<Channel>) {
        f ?: return
        runCatching {
            val a = JSONArray()
            list.forEach { a.put(JSONArray().put(it.id).put(it.name).put(it.number).put(it.logo ?: "").put(it.genreId).put(it.epgId)) }
            f.writeText(a.toString())
        }
    }

    private fun readChannels(f: java.io.File?, maxAgeMs: Long): List<Channel>? {
        if (!fresh(f, maxAgeMs)) return null
        return runCatching {
            val a = JSONArray(f!!.readText())
            (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                val id = r.getString(0)
                Channel(id, r.getString(1), r.optInt(2), "$root/live/$user/$pass/$id.m3u8", r.optString(3).ifBlank { null },
                    r.optString(4), r.optString(5))
            }
        }.getOrNull()
    }

    // ---------- TV guide ----------

    private val epgCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Programme>>>()
    /** Only a few guide lookups at once, so fast scrolling doesn't flood the provider. */
    private val epgGate = java.util.concurrent.Semaphore(4)

    private fun b64(s: String): String = runCatching {
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrDefault(s).trim()

    /** The last raw guide reply, shown in Settings so a missing guide can be diagnosed. */
    @Volatile var lastGuideReply: String = "(no guide requested yet)"
        private set

    private val serverTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

    private fun timeOf(o: JSONObject, vararg keys: String): Long? {
        for (k in keys) {
            val v = o.str(k)
            if (v.isEmpty()) continue
            v.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
            runCatching { synchronized(serverTime) { serverTime.parse(v.take(19)) }?.time }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun listings(action: String, channel: Channel, now: Long): List<Programme> {
        val reply = if (action == "get_short_epg") api("action" to action, "stream_id" to channel.id, "limit" to "4")
        else api("action" to action, "stream_id" to channel.id)
        lastGuideReply = "$action for ${channel.name}: " + reply.toString().take(300)
        val rows = when (reply) {
            is JSONObject -> reply.optJSONArray("epg_listings").objects()
            else -> reply.objects()
        }
        return rows.mapNotNull {
            val start = timeOf(it, "start_timestamp", "start") ?: return@mapNotNull null
            val end = timeOf(it, "stop_timestamp", "end_timestamp", "stop", "end") ?: return@mapNotNull null
            Programme(b64(it.str("title")), b64(it.str("description")), start, end)
        }.filter { it.end > now }.sortedBy { it.start }.take(4)
    }

    // ---------- full guide (XMLTV) ----------

    @Volatile private var guide: Map<String, List<Programme>> = emptyMap()
    @Volatile private var guideLoadedAt = 0L
    @Volatile private var guideStatusText: String = ""
    override val guideStatus: String get() = guideStatusText
    private val guideLock = Any()

    private val guideHttp = http.newBuilder()
        .callTimeout(10, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun programmes(channel: Channel, from: Long, to: Long): List<Programme> {
        val g = guide[channel.epgId.lowercase()] ?: return emptyList()
        return g.filter { it.end > from && it.start < to }
    }

    override fun ensureGuide() {
        synchronized(guideLock) {
            val now = System.currentTimeMillis()
            if (guide.isNotEmpty() && now - guideLoadedAt < 12 * HOUR) return
            val file = cacheFile("guide")
            if (guide.isEmpty() && fresh(file, 12 * HOUR)) {
                readGuide(file)?.let { guide = it; guideLoadedAt = file!!.lastModified(); return }
            }
            guideStatusText = "Downloading TV guide…"
            try {
                val wanted = runCatching { liveChannels() }.getOrDefault(emptyList())
                    .mapNotNull { it.epgId.lowercase().ifEmpty { null } }.toSet()
                val g = downloadGuide(wanted, now - HOUR, now + 26 * HOUR)
                if (g.isNotEmpty()) {
                    guide = g; guideLoadedAt = now
                    writeGuide(file, g)
                }
                guideStatusText = if (g.isEmpty()) "Your provider's full TV guide was empty." else ""
            } catch (e: Exception) {
                // Keep an older copy if there is one.
                if (guide.isEmpty()) readGuide(file)?.let { guide = it; guideLoadedAt = now }
                guideStatusText = if (guide.isEmpty()) "Couldn't download the TV guide: ${e.message}" else ""
            }
        }
    }

    private val xmltvTime = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
    private val xmltvTimeNoZone = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

    private fun parseXmltvTime(v: String?): Long? {
        if (v.isNullOrBlank()) return null
        val t = v.trim()
        return runCatching { xmltvTime.parse(if (t.length > 14 && t[14] != ' ') t.substring(0, 14) + " " + t.substring(14).trim() else t)?.time }
            .getOrNull() ?: runCatching { xmltvTimeNoZone.parse(t.take(14))?.time }.getOrNull()
    }

    /** Streams the provider's xmltv.php, keeping only wanted channels within [from, to). */
    private fun downloadGuide(wanted: Set<String>, from: Long, to: Long): Map<String, List<Programme>> {
        val url = "$root/xmltv.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user).addQueryParameter("password", pass).build()
        val req = Request.Builder().url(url).header("User-Agent", Net.UA).build()
        val out = HashMap<String, MutableList<Programme>>()
        guideHttp.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("error ${r.code}")
            val parser = android.util.Xml.newPullParser()
            parser.setInput(r.body!!.charStream())
            var event = parser.eventType
            var ch: String? = null; var start: Long? = null; var stop: Long? = null
            var title = ""; var desc = ""; var field: String? = null
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                        "programme" -> {
                            ch = parser.getAttributeValue(null, "channel")?.lowercase()
                            start = parseXmltvTime(parser.getAttributeValue(null, "start"))
                            stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            title = ""; desc = ""
                        }
                        "title", "desc" -> field = parser.name
                    }
                    org.xmlpull.v1.XmlPullParser.TEXT -> when (field) {
                        "title" -> if (title.isEmpty()) title = parser.text.trim()
                        "desc" -> if (desc.isEmpty()) desc = parser.text.trim().take(400)
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                        "title", "desc" -> field = null
                        "programme" -> {
                            val c = ch; val s0 = start; val e0 = stop
                            if (c != null && s0 != null && e0 != null && e0 > from && s0 < to &&
                                (wanted.isEmpty() || c in wanted) && title.isNotEmpty()
                            ) out.getOrPut(c) { mutableListOf() }.add(Programme(title, desc, s0, e0))
                            ch = null
                        }
                    }
                }
                event = parser.next()
            }
        }
        return out.mapValues { (_, v) -> v.sortedBy { it.start }.distinctBy { it.start } }
    }

    private fun writeGuide(f: java.io.File?, g: Map<String, List<Programme>>) {
        f ?: return
        runCatching {
            val o = JSONObject()
            g.forEach { (k, list) ->
                o.put(k, JSONArray().apply { list.forEach { put(JSONArray().put(it.start).put(it.end).put(it.title).put(it.description)) } })
            }
            f.writeText(o.toString())
        }
    }

    private fun readGuide(f: java.io.File?): Map<String, List<Programme>>? {
        if (f == null || !f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                (0 until a.length()).map { i -> a.getJSONArray(i).let { r -> Programme(r.getString(2), r.optString(3), r.getLong(0), r.getLong(1)) } }
            }
        }.getOrNull()
    }

    override fun nowNext(channel: Channel): List<Programme> {
        val now = System.currentTimeMillis()
        // Prefer the full guide when it's loaded.
        if (channel.epgId.isNotEmpty()) {
            val g = programmes(channel, now, now + 12 * HOUR).take(3)
            if (g.isNotEmpty()) return g
        }
        epgCache[channel.id]?.let { (at, list) ->
            val firstEnd = list.firstOrNull()?.end ?: 0
            if (now - at < 15 * 60_000 && (list.isEmpty() || firstEnd > now)) return list.filter { it.end > now }
        }
        epgGate.acquire()
        try {
            var list = runCatching { listings("get_short_epg", channel, now) }.getOrDefault(emptyList())
            // The quick guide often only has what's on now; the full table also has what's next.
            if (list.size < 2) {
                val table = runCatching { listings("get_simple_data_table", channel, now) }.getOrDefault(emptyList())
                list = (list + table).distinctBy { it.start }.sortedBy { it.start }.take(3)
            }
            list = list.filterNot { it.title.isBlank() || it.title.equals("No information available", true) }
            epgCache[channel.id] = now to list
            return list
        } catch (e: Exception) {
            lastGuideReply = "Guide error for ${channel.name}: ${e.message}"
            epgCache[channel.id] = now to emptyList()
            return emptyList()
        } finally {
            epgGate.release()
        }
    }

    // ---------- movies & series ----------

    private val catCache = java.util.concurrent.ConcurrentHashMap<String, List<Genre>>()

    override fun browseCategories(kind: String): Pair<String, List<Genre>> {
        val type = if (kind == "series") "series" else "vod"
        catCache[type]?.let { return type to it }
        val file = cacheFile("cats_$type")
        val fresh = readCats(file, maxAgeMs = 12 * HOUR)
        val cats = fresh ?: try {
            val action = if (type == "series") "get_series_categories" else "get_vod_categories"
            api("action" to action).objects().map { Genre(it.str("category_id"), it.str("category_name")) }
                .also { writeCats(file, it) }
        } catch (e: Exception) {
            readCats(file, maxAgeMs = Long.MAX_VALUE) ?: throw e // offline or slow: fall back to an older copy
        }
        catCache[type] = cats
        return type to cats
    }

    // ---------- on-device cache ----------

    private val HOUR = 3_600_000L
    private val accountTag = "${root}|$user".hashCode().toUInt().toString(16)

    private fun cacheFile(name: String): java.io.File? =
        cacheDir?.let { java.io.File(it, "xt_${accountTag}_" + name.replace(Regex("[^A-Za-z0-9_]"), "_") + ".json") }

    private fun fresh(f: java.io.File?, maxAgeMs: Long) =
        f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs

    private fun writeCats(f: java.io.File?, cats: List<Genre>) {
        f ?: return
        runCatching { f.writeText(JSONArray(cats.map { JSONArray().put(it.id).put(it.title) }).toString()) }
    }

    private fun readCats(f: java.io.File?, maxAgeMs: Long): List<Genre>? {
        if (!fresh(f, maxAgeMs)) return null
        return runCatching {
            val a = JSONArray(f!!.readText())
            (0 until a.length()).map { a.getJSONArray(it).let { r -> Genre(r.getString(0), r.getString(1)) } }
        }.getOrNull()
    }

    private fun writeItems(f: java.io.File?, items: List<VodItem>) {
        f ?: return
        runCatching {
            val a = JSONArray()
            items.forEach {
                a.put(JSONArray().put(it.id).put(it.name).put(it.poster ?: "").put(it.cmd).put(if (it.isSeries) 1 else 0)
                    .put(it.year).put(it.rating).put(it.backdrop ?: "").put(it.description))
            }
            f.writeText(a.toString())
        }
    }

    private fun readItems(f: java.io.File?, maxAgeMs: Long): List<VodItem>? {
        if (!fresh(f, maxAgeMs)) return null
        return runCatching {
            val a = JSONArray(f!!.readText())
            (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                VodItem(
                    id = r.getString(0), name = r.getString(1), poster = r.optString(2).ifBlank { null },
                    cmd = r.optString(3), isSeries = r.optInt(4) == 1, year = r.optString(5), rating = r.optString(6),
                    backdrop = r.optString(7).ifBlank { null }, description = r.optString(8),
                )
            }
        }.getOrNull()
    }

    private val fetchLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Reads a (possibly huge) JSON array of objects without loading it all into memory,
     * keeping only [fields] from each object.
     */
    private fun streamObjects(vararg params: Pair<String, String>, fields: Set<String>): List<Map<String, String>> {
        val url = "$root/player_api.php".toHttpUrl().newBuilder().apply {
            addQueryParameter("username", user)
            addQueryParameter("password", pass)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).header("User-Agent", Net.UA).build()
        bulkHttp.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The server replied with error ${r.code}.")
            val reader = JsonReader(r.body!!.charStream())
            val out = ArrayList<Map<String, String>>()
            if (reader.peek() != JsonToken.BEGIN_ARRAY) return emptyList()
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                reader.beginObject()
                val m = HashMap<String, String>(8)
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    val tok = reader.peek()
                    if (name in fields && (tok == JsonToken.STRING || tok == JsonToken.NUMBER)) m[name] = reader.nextString()
                    else if (name in fields && tok == JsonToken.BEGIN_ARRAY) {
                        // e.g. backdrop_path: ["http://…"] — keep the first string
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (m[name] == null && reader.peek() == JsonToken.STRING) m[name] = reader.nextString() else reader.skipValue()
                        }
                        reader.endArray()
                    } else reader.skipValue()
                }
                reader.endObject()
                out += m
            }
            reader.endArray()
            return out
        }
    }

    private fun Map<String, String>.s(k: String) = this[k]?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
    private fun Map<String, String>.im(k: String) = s(k).takeIf { it.startsWith("http", true) }

    private fun fullList(type: String, categoryId: String): List<VodItem> {
        val key = "$type/$categoryId"
        synchronized(listCache) { listCache[key]?.let { return it } }
        // One download per list at a time, so repeated searches or quick scrolling don't pile up.
        synchronized(fetchLocks.getOrPut(key) { Any() }) {
            synchronized(listCache) { listCache[key]?.let { return it } }
            val file = cacheFile("list_$key")
            readItems(file, maxAgeMs = 12 * HOUR)?.let { cached ->
                synchronized(listCache) { listCache[key] = cached }
                return cached
            }
            val all = categoryId == "*"
            val cat: Array<Pair<String, String>> = if (all) emptyArray() else arrayOf("category_id" to categoryId)
            val list = if (type == "series") {
                val fields = if (all) setOf("series_id", "name", "cover", "year", "releaseDate", "rating")
                else setOf("series_id", "name", "cover", "plot", "year", "releaseDate", "rating", "backdrop_path")
                streamObjects("action" to "get_series", *cat, fields = fields).mapNotNull {
                    val id = it.s("series_id").ifEmpty { return@mapNotNull null }
                    VodItem(
                        id = id, name = it.s("name"), description = it.s("plot"),
                        poster = it.im("cover"), cmd = "", isSeries = true,
                        year = it.s("year").ifEmpty { it.s("releaseDate").take(4) },
                        rating = it.s("rating").takeIf { r -> r != "0" }.orEmpty(),
                        backdrop = it.im("backdrop_path"),
                    )
                }
            } else {
                val fields = setOf("stream_id", "name", "stream_icon", "container_extension", "year", "rating")
                streamObjects("action" to "get_vod_streams", *cat, fields = fields).mapNotNull {
                    val id = it.s("stream_id").ifEmpty { return@mapNotNull null }
                    val ext = it.s("container_extension").ifEmpty { "mp4" }
                    VodItem(
                        id = id, name = it.s("name"), description = "",
                        poster = it.im("stream_icon"), cmd = "$root/movie/$user/$pass/$id.$ext",
                        isSeries = false, year = it.s("year"),
                        rating = it.s("rating").takeIf { r -> r != "0" }.orEmpty(),
                    )
                }
            }
            synchronized(listCache) { listCache[key] = list }
            writeItems(file, list)
            return list
        }
    }

    override fun catalogue(type: String): List<VodItem> = fullList(type, "*")

    override fun search(type: String, query: String): List<VodItem> {
        val words = query.lowercase().split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return fullList(type, "*").filter { item -> val n = item.name.lowercase(); words.all { it in n } }.take(300)
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
            backdrop = item.backdrop ?: firstImage(info.opt("backdrop_path")),
        )
    }

    private fun firstImage(v: Any?): String? = when (v) {
        is String -> v.takeIf { it.startsWith("http", true) }
        is JSONArray -> (0 until v.length()).map { v.optString(it) }.firstOrNull { it.startsWith("http", true) }
        else -> null
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
                            thumb = e.optJSONObject("info")?.img("movie_image"),
                            duration = prettyDuration(e.optJSONObject("info")),
                        )
                    }.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE },
                )
            }
    }

    /** "00:45:12" or 2712 seconds → "45m" / "1h 5m". */
    private fun prettyDuration(info: JSONObject?): String {
        info ?: return ""
        val secs = info.optInt("duration_secs", 0).takeIf { it > 0 } ?: run {
            val p = info.str("duration").split(":").mapNotNull { it.toIntOrNull() }
            when (p.size) { 3 -> p[0] * 3600 + p[1] * 60 + p[2]; 2 -> p[0] * 60 + p[1]; else -> 0 }
        }
        if (secs <= 0) return ""
        val h = secs / 3600; val m = (secs % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    override fun episodes(type: String, item: VodItem, season: Season): List<Episode> = season.episodes.orEmpty()

    override fun createLink(type: String, cmd: String, series: String, movieId: String?): String {
        if (cmd.startsWith("http", true)) return cmd
        throw IOException("No stream address for this.")
    }
}
