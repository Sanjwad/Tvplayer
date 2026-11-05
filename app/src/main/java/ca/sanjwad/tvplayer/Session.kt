package ca.sanjwad.tvplayer

import android.content.Context
import ca.sanjwad.tvplayer.stalker.Channel
import ca.sanjwad.tvplayer.stalker.StalkerClient

/** Shared between the browse screens and the player. */
object Session {
    @Volatile var client: StalkerClient? = null
    @Volatile var channels: List<Channel> = emptyList()
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var portal: String
        get() = sp.getString("portal", "").orEmpty()
        set(v) = sp.edit().putString("portal", v).apply()
    var mac: String
        get() = sp.getString("mac", "").orEmpty()
        set(v) = sp.edit().putString("mac", v).apply()
    var serial: String
        get() = sp.getString("serial", "").orEmpty()
        set(v) = sp.edit().putString("serial", v).apply()
    var deviceId: String
        get() = sp.getString("device_id", "").orEmpty()
        set(v) = sp.edit().putString("device_id", v).apply()
    var deviceId2: String
        get() = sp.getString("device_id2", "").orEmpty()
        set(v) = sp.edit().putString("device_id2", v).apply()
}
