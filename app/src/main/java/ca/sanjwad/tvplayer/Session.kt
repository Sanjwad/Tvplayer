package ca.sanjwad.tvplayer

import android.content.Context
import ca.sanjwad.tvplayer.stalker.Channel
import ca.sanjwad.tvplayer.stalker.IptvSource

/** Shared between the browse screens and the player. */
object Session {
    @Volatile var client: IptvSource? = null
    @Volatile var channels: List<Channel> = emptyList()
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
    val isConfigured: Boolean
        get() = xtServer.isNotBlank() && xtUser.isNotBlank() && xtPass.isNotBlank()
}
