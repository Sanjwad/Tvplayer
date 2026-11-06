package ca.sanjwad.tvplayer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ca.sanjwad.tvplayer.stalker.Channel
import ca.sanjwad.tvplayer.stalker.Episode
import ca.sanjwad.tvplayer.stalker.Season
import ca.sanjwad.tvplayer.stalker.VodItem
import org.json.JSONArray
import org.json.JSONObject

/** Where playback stopped for a movie or episode. [key] is "m:<movie id>" or "e:<episode address>". */
data class Progress(
    val key: String, val title: String, val subtitle: String, val poster: String?, val cmd: String,
    val posMs: Long, val durMs: Long, val updated: Long, val seriesId: String?,
)

/** A movie or show saved to My List. [kind] is the tab ("movies"/"series"), [type] the section used for API calls. */
data class SavedItem(val kind: String, val type: String, val item: VodItem)

/**
 * Favourite channels and My List, stored on the device per login so portal and
 * Xtream accounts keep separate lists. Compose screens observe the state directly.
 */
object Library {
    private var sp: SharedPreferences? = null
    private var key = ""

    var favourites by mutableStateOf<List<Channel>>(emptyList())
        private set
    var myList by mutableStateOf<List<SavedItem>>(emptyList())
        private set

    /** Series id → furthest (season, episode) started. */
    var watched by mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap())
        private set

    /** Hidden category ids per section ("live", "movies", "series"). */
    var hidden by mutableStateOf<Map<String, Set<String>>>(emptyMap())
        private set

    var progress by mutableStateOf<Map<String, Progress>>(emptyMap())
        private set

    fun removeProgress(key: String) {
        progress = progress - key
        writeProgress()
    }

    fun saveProgress(p: Progress) {
        val done = p.durMs > 0 && p.posMs > p.durMs * 0.95
        progress = if (done || p.posMs < 30_000) progress - p.key else progress + (p.key to p)
        writeProgress()
    }

    private fun writeProgress() {
        val o = JSONObject()
        progress.values.sortedByDescending { it.updated }.take(60).forEach {
            o.put(it.key, JSONObject().put("t", it.title).put("s", it.subtitle).put("p", it.poster ?: "").put("c", it.cmd)
                .put("pos", it.posMs).put("dur", it.durMs).put("u", it.updated).put("sid", it.seriesId ?: ""))
        }
        sp?.edit()?.putString("progress_$key", o.toString())?.apply()
    }

    /** The live channel watched last, so the app can reopen on it. */
    var lastChannelId: String
        get() = sp?.getString("last_channel_$key", "").orEmpty()
        set(v) { sp?.edit()?.putString("last_channel_$key", v)?.apply() }

    // ---------- category names (so hidden categories can move to a new login) ----------

    fun noteCategories(section: String, cats: List<ca.sanjwad.tvplayer.stalker.Genre>) {
        val prefs = sp ?: return
        runCatching {
            val o = JSONObject(prefs.getString("catnames_$key", "{}")!!)
            cats.forEach { o.put("$section|${it.id}", it.title) }
            prefs.edit().putString("catnames_$key", o.toString()).apply()
        }
    }

    // ---------- carry lists over to a new login ----------

    data class OldAccount(val key: String, val label: String)

    private fun hasData(k: String): Boolean {
        val prefs = sp ?: return false
        return listOf("fav_$k", "list_$k").any { (prefs.getString(it, "[]") ?: "[]").length > 2 } ||
            (prefs.getString("watch_$k", "{}") ?: "{}").length > 2
    }

    /** A previous login with saved lists, if this login is new and hasn't been asked yet. */
    fun previousAccount(): OldAccount? {
        val prefs = sp ?: return null
        if (hasData(key) || prefs.getBoolean("asked_$key", false)) return null
        val acc = runCatching { JSONObject(prefs.getString("accounts", "{}")!!) }.getOrNull() ?: return null
        return acc.keys().asSequence().filter { it != key && hasData(it) }
            .maxByOrNull { acc.optJSONObject(it)?.optLong("t") ?: 0 }
            ?.let { OldAccount(it, acc.optJSONObject(it)?.optString("label").orEmpty()) }
    }

    fun markAsked() { sp?.edit()?.putBoolean("asked_$key", true)?.apply() }

    private fun n(s: String) = ca.sanjwad.tvplayer.stalker.TitleMatch.norm(ca.sanjwad.tvplayer.stalker.TitleMatch.queryName(s))

    /** Copies favourites, My List, watched episodes and hidden categories from [old], matching by name. */
    fun importFrom(old: OldAccount, c: ca.sanjwad.tvplayer.stalker.IptvSource): String {
        val prefs = sp ?: return ""
        val k = old.key
        val oldFav = runCatching { parseChannels(prefs.getString("fav_$k", "[]")!!) }.getOrDefault(emptyList())
        val oldList = runCatching { parseSaved(prefs.getString("list_$k", "[]")!!) }.getOrDefault(emptyList())
        val oldWatch = runCatching { JSONObject(prefs.getString("watch_$k", "{}")!!) }.getOrDefault(JSONObject())
        val oldHidden = runCatching { JSONObject(prefs.getString("hidden_$k", "{}")!!) }.getOrDefault(JSONObject())
        val oldNames = runCatching { JSONObject(prefs.getString("catnames_$k", "{}")!!) }.getOrDefault(JSONObject())

        // Channels by name.
        val chans = runCatching { c.liveChannels() }.getOrDefault(emptyList())
        val byName = chans.groupBy { it.name.trim().lowercase() }
        val newFav = oldFav.mapNotNull { byName[it.name.trim().lowercase()]?.firstOrNull() }.distinctBy { it.id }

        // Movies and shows by title (and year when there's more than one match).
        val catalogues = HashMap<String, Map<String, List<ca.sanjwad.tvplayer.stalker.VodItem>>>()
        val idMap = HashMap<String, String>()
        val newList = oldList.mapNotNull { sv ->
            val type = runCatching { c.browseCategories(sv.kind).first }.getOrDefault(sv.type)
            val cat = catalogues.getOrPut(type) {
                runCatching { c.catalogue(type) }.getOrDefault(emptyList()).groupBy { n(it.name) }
            }
            val cands = cat[n(sv.item.name)].orEmpty()
                .filter { if (sv.kind == "series") it.isSeries || type == "series" else !it.isSeries }
            val pick = cands.firstOrNull { sv.item.year.isNotBlank() && it.year.take(4) == sv.item.year.take(4) } ?: cands.firstOrNull()
            pick?.let { idMap[sv.item.id] = it.id; SavedItem(sv.kind, type, it) }
        }.distinctBy { it.item.id }

        val newWatch = JSONObject()
        oldWatch.keys().forEach { oldId -> idMap[oldId]?.let { newWatch.put(it, oldWatch.optString(oldId)) } }

        // Hidden categories by name.
        val newHidden = JSONObject()
        oldHidden.keys().forEach { section ->
            val arr = oldHidden.optJSONArray(section) ?: return@forEach
            val names = (0 until arr.length()).mapNotNull { oldNames.optString("$section|${arr.optString(it)}").ifBlank { null } }
                .map { it.trim().lowercase() }.toSet()
            if (names.isEmpty()) return@forEach
            val cats = runCatching { if (section == "live") c.liveGenres() else c.browseCategories(section).second }.getOrDefault(emptyList())
            newHidden.put(section, JSONArray(cats.filter { it.title.trim().lowercase() in names }.map { it.id }))
        }

        prefs.edit()
            .putString("fav_$key", JSONArray(newFav.map(::channelJson)).toString())
            .putString("list_$key", JSONArray(newList.map(::savedJson)).toString())
            .putString("watch_$key", newWatch.toString())
            .putString("hidden_$key", newHidden.toString())
            .putBoolean("asked_$key", true)
            .commit()
        return "Brought over ${newFav.size} of ${oldFav.size} favourite channels and ${newList.size} of ${oldList.size} titles."
    }

    fun isHidden(section: String, id: String) = hidden[section]?.contains(id) == true

    fun setHidden(section: String, ids: Collection<String>, hide: Boolean) {
        val cur = hidden[section].orEmpty()
        hidden = hidden + (section to (if (hide) cur + ids else cur - ids.toSet()))
        val o = JSONObject()
        hidden.forEach { (k, v) -> o.put(k, JSONArray(v.toList())) }
        sp?.edit()?.putString("hidden_$key", o.toString())?.apply()
    }

    fun load(ctx: Context, accountKey: String) {
        val prefs = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
        sp = prefs
        key = accountKey.hashCode().toString()
        // Remember every login used on this TV, so a new one can copy lists from the last.
        runCatching {
            val acc = JSONObject(prefs.getString("accounts", "{}")!!)
            acc.put(key, JSONObject().put("label", accountKey.substringAfter("://")).put("t", System.currentTimeMillis()))
            prefs.edit().putString("accounts", acc.toString()).apply()
        }
        favourites = runCatching { parseChannels(prefs.getString("fav_$key", "[]")!!) }.getOrDefault(emptyList())
        myList = runCatching { parseSaved(prefs.getString("list_$key", "[]")!!) }.getOrDefault(emptyList())
        watched = runCatching {
            val o = JSONObject(prefs.getString("watch_$key", "{}")!!)
            o.keys().asSequence().mapNotNull { k ->
                val parts = o.optString(k).split(":")
                val a = parts.getOrNull(0)?.toIntOrNull(); val b = parts.getOrNull(1)?.toIntOrNull()
                if (a != null && b != null) k to (a to b) else null
            }.toMap()
        }.getOrDefault(emptyMap())
        progress = runCatching {
            val o = JSONObject(prefs.getString("progress_$key", "{}")!!)
            o.keys().asSequence().associateWith { k ->
                val v = o.getJSONObject(k)
                Progress(k, v.optString("t"), v.optString("s"), v.optString("p").ifBlank { null }, v.optString("c"),
                    v.optLong("pos"), v.optLong("dur"), v.optLong("u"), v.optString("sid").ifBlank { null })
            }
        }.getOrDefault(emptyMap())
        hidden = runCatching {
            val o = JSONObject(prefs.getString("hidden_$key", "{}")!!)
            o.keys().asSequence().associateWith { k ->
                val a = o.optJSONArray(k) ?: JSONArray()
                (0 until a.length()).map { a.optString(it) }.toSet()
            }
        }.getOrDefault(emptyMap())
    }

    fun markWatched(seriesId: String, season: Int, episode: Int) {
        val cur = watched[seriesId]
        if (cur != null && (cur.first > season || (cur.first == season && cur.second >= episode))) return
        watched = watched + (seriesId to (season to episode))
        val o = JSONObject()
        watched.forEach { (k, v) -> o.put(k, "${v.first}:${v.second}") }
        sp?.edit()?.putString("watch_$key", o.toString())?.apply()
    }

    // Stalker season ids are portal record ids (e.g. 48213), so a small id or the number in the name wins.
    fun seasonNumber(s: Season, index: Int) = s.id.toIntOrNull()?.takeIf { it in 0..200 }
        ?: Regex("\\d+").find(s.name)?.value?.toIntOrNull() ?: (index + 1)
    fun episodeNumber(e: Episode, index: Int) = e.number.toIntOrNull() ?: (index + 1)

    /** The first episode after the furthest one started, or the very first if none. Null = caught up. */
    fun nextEpisode(seasons: List<Season>, last: Pair<Int, Int>?): Triple<Season, Episode, Pair<Int, Int>>? {
        val ordered = seasons.mapIndexed { si, s -> seasonNumber(s, si) to s }.sortedBy { it.first }
        for ((sn, s) in ordered) {
            val eps = s.episodes.orEmpty().mapIndexed { ei, e -> episodeNumber(e, ei) to e }.sortedBy { it.first }
            for ((en, e) in eps) {
                if (last == null || sn > last.first || (sn == last.first && en > last.second)) return Triple(s, e, sn to en)
            }
        }
        return null
    }

    fun isFavourite(id: String) = favourites.any { it.id == id }

    fun toggleFavourite(ch: Channel) {
        favourites = if (isFavourite(ch.id)) favourites.filterNot { it.id == ch.id } else favourites + ch
        sp?.edit()?.putString("fav_$key", JSONArray(favourites.map(::channelJson)).toString())?.apply()
    }

    fun inList(id: String) = myList.any { it.item.id == id }

    fun toggleList(kind: String, type: String, item: VodItem) {
        myList = if (inList(item.id)) myList.filterNot { it.item.id == item.id }
        else listOf(SavedItem(kind, type, item)) + myList
        sp?.edit()?.putString("list_$key", JSONArray(myList.map(::savedJson)).toString())?.apply()
    }

    // ---------- JSON ----------

    private fun channelJson(c: Channel) = JSONObject()
        .put("id", c.id).put("name", c.name).put("number", c.number)
        .put("cmd", c.cmd).put("logo", c.logo ?: "").put("genre", c.genreId)

    private fun parseChannels(s: String): List<Channel> {
        val a = JSONArray(s)
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let {
                Channel(it.optString("id"), it.optString("name"), it.optInt("number"), it.optString("cmd"),
                    it.optString("logo").ifBlank { null }, it.optString("genre"))
            }
        }
    }

    private fun savedJson(s: SavedItem) = JSONObject()
        .put("kind", s.kind).put("type", s.type)
        .put("id", s.item.id).put("name", s.item.name).put("description", s.item.description)
        .put("poster", s.item.poster ?: "").put("cmd", s.item.cmd).put("isSeries", s.item.isSeries)
        .put("year", s.item.year).put("rating", s.item.rating).put("backdrop", s.item.backdrop ?: "")

    private fun parseSaved(s: String): List<SavedItem> {
        val a = JSONArray(s)
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let {
                SavedItem(
                    it.optString("kind"), it.optString("type"),
                    VodItem(it.optString("id"), it.optString("name"), it.optString("description"),
                        it.optString("poster").ifBlank { null }, it.optString("cmd"), it.optBoolean("isSeries"),
                        it.optString("year"), it.optString("rating"), it.optString("backdrop").ifBlank { null }),
                )
            }
        }
    }
}
