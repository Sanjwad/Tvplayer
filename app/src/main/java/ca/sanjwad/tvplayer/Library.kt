package ca.sanjwad.tvplayer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ca.sanjwad.tvplayer.stalker.Channel
import ca.sanjwad.tvplayer.stalker.VodItem
import org.json.JSONArray
import org.json.JSONObject

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

    fun load(ctx: Context, accountKey: String) {
        val prefs = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
        sp = prefs
        key = accountKey.hashCode().toString()
        favourites = runCatching { parseChannels(prefs.getString("fav_$key", "[]")!!) }.getOrDefault(emptyList())
        myList = runCatching { parseSaved(prefs.getString("list_$key", "[]")!!) }.getOrDefault(emptyList())
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
        .put("year", s.item.year).put("rating", s.item.rating)

    private fun parseSaved(s: String): List<SavedItem> {
        val a = JSONArray(s)
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let {
                SavedItem(
                    it.optString("kind"), it.optString("type"),
                    VodItem(it.optString("id"), it.optString("name"), it.optString("description"),
                        it.optString("poster").ifBlank { null }, it.optString("cmd"), it.optBoolean("isSeries"),
                        it.optString("year"), it.optString("rating")),
                )
            }
        }
    }
}
