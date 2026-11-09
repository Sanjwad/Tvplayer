package ca.sanjwad.tvplayer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ca.sanjwad.tvplayer.stalker.Channel
import ca.sanjwad.tvplayer.stalker.IptvSource

/** Shared between the browse screens and the player. */
object Session {
    /** Set by the player when a show's episode plays to the end (the show's id), so its page can offer the next one. */
    var episodeEnded by androidx.compose.runtime.mutableStateOf<String?>(null)
    @Volatile var client: IptvSource? = null
    @Volatile var channels: List<Channel> = emptyList()
    /** Seasons per series id, so My List doesn't refetch them every visit. */
    /** Bumped when the full TV guide finishes loading, so guide rows refresh. */
    var guideVersion by androidx.compose.runtime.mutableIntStateOf(0)
    /** Kept so returning to Search shows the last results. */
    var lastSearch = ""
    @Volatile var omdb: ca.sanjwad.tvplayer.stalker.OmdbClient? = null
    @Volatile var tmdb: ca.sanjwad.tvplayer.stalker.TmdbClient? = null
    val seasonCache = java.util.concurrent.ConcurrentHashMap<String, List<ca.sanjwad.tvplayer.stalker.Season>>()
    /** Every live channel and the live groups, for the multi-screen channel picker. */
    var liveAll: List<ca.sanjwad.tvplayer.stalker.Channel> = emptyList()
    var liveGenres: List<ca.sanjwad.tvplayer.stalker.Genre> = emptyList()
    /** Portal section used for each tab ("movies"/"series" → e.g. "vod"/"series"), once known. */
    val vodTypes = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** A title the player asked the main screen to open (e.g. a show picked from an actor's page). */
    var pendingOpen by androidx.compose.runtime.mutableStateOf<Pair<String, ca.sanjwad.tvplayer.stalker.VodItem>?>(null)
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var xtServer: String
        get() = sp.getString("xt_server", "").orEmpty()
        set(v) = sp.edit().putString("xt_server", v).apply()
    var xtUser: String
        get() = sp.getString("xt_user", "").orEmpty()
        set(v) = sp.edit().putString("xt_user", v).apply()
    var xtPass: String
        get() = sp.getString("xt_pass", "").orEmpty()
        set(v) = sp.edit().putString("xt_pass", v).apply()
    var tmdbKey: String
        get() = sp.getString("tmdb_key", "").orEmpty()
        set(v) = sp.edit().putString("tmdb_key", v).apply()
    var osKey: String
        get() = sp.getString("os_key", "").orEmpty()
        set(v) = sp.edit().putString("os_key", v).apply()
    var osUser: String
        get() = sp.getString("os_user", "").orEmpty()
        set(v) = sp.edit().putString("os_user", v).apply()
    var osPass: String
        get() = sp.getString("os_pass", "").orEmpty()
        set(v) = sp.edit().putString("os_pass", v).apply()
    /** Preferred subtitle language (ISO 639-1), e.g. "en". */
    var subLang: String
        get() = sp.getString("sub_lang", "en").orEmpty()
        set(v) = sp.edit().putString("sub_lang", v).apply()
    var omdbKey: String
        get() = sp.getString("omdb_key", "").orEmpty()
        set(v) = sp.edit().putString("omdb_key", v).apply()
    var startOnLastChannel: Boolean
        get() = sp.getBoolean("start_last_channel", true)
        set(v) = sp.edit().putBoolean("start_last_channel", v).apply()
    /** "xtream" or "stalker". */
    var loginType: String
        get() = sp.getString("login_type", "xtream").orEmpty()
        set(v) = sp.edit().putString("login_type", v).apply()
    val isStalker get() = loginType == "stalker"
    var stPortal: String
        get() = sp.getString("st_portal", "").orEmpty()
        set(v) = sp.edit().putString("st_portal", v).apply()
    var stMac: String
        get() = sp.getString("st_mac", "").orEmpty()
        set(v) = sp.edit().putString("st_mac", v).apply()
    var stSerial: String
        get() = sp.getString("st_serial", "").orEmpty()
        set(v) = sp.edit().putString("st_serial", v).apply()
    var stDeviceId: String
        get() = sp.getString("st_device_id", "").orEmpty()
        set(v) = sp.edit().putString("st_device_id", v).apply()
    var stDeviceId2: String
        get() = sp.getString("st_device_id2", "").orEmpty()
        set(v) = sp.edit().putString("st_device_id2", v).apply()

    /** Identifies this login for per-login favourites and My List. */
    val accountKey: String
        get() = if (isStalker) "${stPortal.trim()}|${stMac.trim().uppercase()}" else "$xtServer|$xtUser"

    val isConfigured: Boolean
        get() = if (isStalker) stPortal.isNotBlank() && stMac.isNotBlank()
            else xtServer.isNotBlank() && xtUser.isNotBlank() && xtPass.isNotBlank()
}
