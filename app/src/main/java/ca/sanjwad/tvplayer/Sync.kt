package ca.sanjwad.tvplayer

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps My List, favourites, watched episodes and resume points the same on every TV that uses
 * the same login. The household is identified by a hash of the login, so the login itself never
 * leaves the TV. Changes upload a few seconds after they happen; other TVs pick them up every few
 * minutes and whenever the app opens.
 */
object Sync {
    /** The sync service on Cloudflare (blank = sync off). */
    private val BASE = BuildConfig.SYNC_URL.trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sync").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    private var poller: ScheduledFuture<*>? = null
    @Volatile private var householdKey: String? = null

    /** Last result, for Settings ("Synced 2 minutes ago" / an error). */
    @Volatile var status: String = ""
        private set

    /** Start syncing for this login (call after [Library.load]). */
    fun start(prefs: Prefs) {
        stop()
        if (BASE.isBlank()) return
        if (!prefs.syncOn) { status = "Off on this TV"; return }
        val secret = if (prefs.isStalker) "stalker|${prefs.accountKey}" else "xtream|${prefs.accountKey}|${prefs.xtPass}"
        // "Family" keeps the original key, so TVs set up before sync names existed stay together.
        val name = prefs.syncName.trim().lowercase()
        householdKey = sha256("sanjtv-sync-v1|$secret" + if (name == "family") "" else "|name:$name")
        // Real changes (lists, favourites, watched) go up in 5 seconds. A resume point moving on while
        // you watch waits for the next regular sync, so watching a film costs a handful of uploads, not hundreds.
        Library.onChange = { minor -> if (!minor) schedule(5) }
        poller?.cancel(false)
        poller = worker.scheduleWithFixedDelay({ runOnce() }, 0, 5, TimeUnit.MINUTES)
    }

    /** Stop syncing on this TV (lists stay as they are here). */
    fun stop() {
        householdKey = null
        Library.onChange = null
        poller?.cancel(false); poller = null
        synchronized(this) { pending?.cancel(false); pending = null }
        status = ""
    }

    /** Sync now (e.g. when the app comes back to the front). */
    fun now() = schedule(0)

    private fun schedule(delaySeconds: Long) {
        if (householdKey == null) return
        synchronized(this) {
            pending?.cancel(false)
            pending = worker.schedule({ runOnce() }, delaySeconds, TimeUnit.SECONDS)
        }
    }

    private fun runOnce() {
        val key = householdKey ?: return
        val url = "$BASE/v1/$key"
        try {
            val remote = http.newCall(Request.Builder().url(url).header("Cache-Control", "no-store").build()).execute().use { r ->
                if (!r.isSuccessful) error("sync service replied ${r.code}")
                JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
            }
            // Bring in anything newer from the other TVs (on the main thread: the screens watch these lists).
            val latch = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { Library.mergeDoc(remote) } finally { latch.countDown() }
            }
            latch.await(10, TimeUnit.SECONDS)
            // Upload the merged result if it differs from what's stored.
            val mine = Library.exportDoc()
            if (mine.optJSONObject("items")?.toString() != remote.optJSONObject("items")?.toString()) {
                val body = mine.toString().toRequestBody("application/json".toMediaType())
                http.newCall(Request.Builder().url(url).put(body).build()).execute().use { r ->
                    if (!r.isSuccessful) error("sync service replied ${r.code}")
                }
            }
            status = "Synced at " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        } catch (e: Exception) {
            status = "Sync paused: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
