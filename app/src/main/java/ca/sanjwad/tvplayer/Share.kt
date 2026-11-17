package ca.sanjwad.tvplayer

import androidx.compose.runtime.mutableStateOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * "Send to a friend": every household gets a friend code (like SANJ-4821). Friends add each other's
 * codes, and a title sent from a title page lands in the friend's "Sent to you" row in My List.
 * Lives on the same Cloudflare service as sync. All calls block, so run them off the main thread.
 */
object Share {
    data class Friend(val code: String, val name: String)
    data class Item(val id: String, val at: Long, val from: String, val title: String, val year: String,
                    val series: Boolean, val poster: String?, val note: String, val to: String = "")

    private val BASE = BuildConfig.SYNC_URL.trimEnd('/')
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()
    @Volatile private var key: String? = null

    /** My code and name (null until set up), and my friends. Compose reads these directly. */
    val myCode = mutableStateOf<String?>(null)
    val myName = mutableStateOf("")
    val friends = mutableStateOf<List<Friend>>(emptyList())
    /** People asking to be my friend, and people I've asked who haven't answered yet. */
    val requestsIn = mutableStateOf<List<Friend>>(emptyList())
    val requestsOut = mutableStateOf<List<Friend>>(emptyList())
    val inbox = mutableStateOf<List<Item>>(emptyList())
    /** What this household has sent, newest first ("Sent by you" at the bottom of My List). */
    val outbox = mutableStateOf<List<Item>>(emptyList())

    val available get() = BASE.isNotBlank()

    /** Works out this household's private share key from the login (same household as sync). */
    fun init(prefs: Prefs) {
        val secret = if (prefs.isStalker) "stalker|${prefs.accountKey}" else "xtream|${prefs.accountKey}|${prefs.xtPass}"
        val name = prefs.syncName.trim().lowercase()
        val k = sha256("sanjtv-share-v1|$secret" + if (name == "family") "" else "|name:$name")
        if (k != key) { key = k; myCode.value = null; friends.value = emptyList(); requestsIn.value = emptyList(); requestsOut.value = emptyList(); inbox.value = emptyList() }
        myName.value = prefs.shareName
        if (available && prefs.shareName.isNotBlank()) Thread {
            runCatching { setUp(prefs.shareName) }; runCatching { refreshInbox() }
        }.apply { isDaemon = true }.start()
        // Check every 5 minutes for new friend requests, accepted requests and things sent to me,
        // so a TV that's left on with the app open still notices (only cheap reads).
        if (poller == null && available) poller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "share-poll").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay({ runCatching { refreshMe() }; runCatching { refreshInbox() } }, 5, 5, TimeUnit.MINUTES) }
    }
    private var poller: java.util.concurrent.ScheduledExecutorService? = null

    /** Creates my friend code (or keeps the name up to date). */
    fun setUp(name: String) = apply(post("me", JSONObject().put("key", key).put("name", name)))

    /** Changes the name friends see, with a new code to match it (friends keep you). */
    fun rename(name: String) = apply(post("rename", JSONObject().put("key", key).put("name", name)))

    /** Re-reads my friends and requests (cheap; done when the app comes back to the front). */
    fun refreshMe() {
        val k = key ?: return
        if (!available) return
        val o = get("me/$k")
        if (o.has("code")) apply(o)
    }

    /** Asks someone to be friends. Returns their name. If they'd already asked me, we're friends straight away. */
    fun request(code: String): String {
        // Typed any way: "chris 3307", "CHRIS3307" and "Chris-3307" all become CHRIS-3307.
        val raw = code.uppercase().filter { it.isLetterOrDigit() }
        val c = Regex("^([A-Z]+)(\\d{4})$").find(raw)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" } ?: code.trim().uppercase()
        val who = get("who/" + java.net.URLEncoder.encode(c, "UTF-8"))
        apply(post("request", JSONObject().put("key", key).put("to", who.getString("code"))))
        return who.optString("name")
    }

    /** Accept or decline a request (also cancels one I sent). */
    fun respond(code: String, accept: Boolean) = apply(post("respond", JSONObject().put("key", key).put("code", code).put("accept", accept)))

    /** Ends a friendship on both sides. */
    fun unfriend(code: String) = apply(post("unfriend", JSONObject().put("key", key).put("code", code)))

    private fun apply(o: JSONObject) {
        myCode.value = o.optString("code").ifBlank { null }
        myName.value = o.optString("name")
        friends.value = parseFriends(o.optJSONArray("friends"))
        requestsIn.value = parseFriends(o.optJSONArray("requestsIn"))
        requestsOut.value = parseFriends(o.optJSONArray("requestsOut"))
    }

    fun send(to: String, title: String, year: String, series: Boolean, poster: String?, note: String = "") {
        post("send", JSONObject().put("key", key).put("to", to).put("item", JSONObject()
            .put("title", title).put("year", year).put("series", series).put("poster", poster ?: "").put("note", note)))
        runCatching { key?.let { k -> outbox.value = items(get("outbox/$k").optJSONArray("items")) } }
    }

    fun refreshInbox() {
        val k = key ?: return
        if (!available || myCode.value == null) return
        inbox.value = items(get("inbox/$k").optJSONArray("items"))
        runCatching { outbox.value = items(get("outbox/$k").optJSONArray("items")) }
    }

    private fun items(arr: JSONArray?) = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.map { o ->
        Item(o.optString("id"), o.optLong("at"), o.optString("from"), o.optString("title"), o.optString("year"),
            o.optBoolean("series"), o.optString("poster").ifBlank { null }, o.optString("note"), o.optString("to"))
    }

    /** Takes a title off my own "Sent by you" row (the friend keeps theirs). */
    fun unsend(id: String) {
        outbox.value = outbox.value.filterNot { it.id == id }
        Thread { runCatching { post("unsend", JSONObject().put("key", key).put("id", id)) } }.apply { isDaemon = true }.start()
    }

    fun dismiss(id: String) {
        inbox.value = inbox.value.filterNot { it.id == id }
        Thread { runCatching { post("dismiss", JSONObject().put("key", key).put("id", id)) } }.apply { isDaemon = true }.start()
    }

    private fun parseFriends(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
        .map { Friend(it.optString("code"), it.optString("name")) }

    private fun get(path: String): JSONObject =
        http.newCall(Request.Builder().url("$BASE/v1/share/$path").header("Cache-Control", "no-store").build()).execute().use { r ->
            val o = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) throw java.io.IOException(o.optString("error").ifBlank { "Couldn't reach the share service (${r.code})" })
            o
        }

    private fun post(path: String, body: JSONObject): JSONObject {
        if (key == null) throw java.io.IOException("Log in first")
        return http.newCall(Request.Builder().url("$BASE/v1/share/$path").post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
            val o = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) throw java.io.IOException(o.optString("error").ifBlank { "Couldn't reach the share service (${r.code})" })
            o
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
