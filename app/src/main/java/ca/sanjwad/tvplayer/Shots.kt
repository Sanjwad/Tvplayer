package ca.sanjwad.tvplayer

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Screenshots for the website (Test builds only, switch in Settings): hold Back for about a second
 * and the screen is captured and uploaded to the sync service, where a GitHub Action collects them.
 * A short press of Back works exactly as before.
 */
object Shots {
    private val BASE = BuildConfig.SYNC_URL.trimEnd('/')
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    private var held: KeyEvent? = null
    private var long = false
    private var count = 0

    fun enabled(a: Activity) = BuildConfig.TEST_BUILD && BASE.isNotBlank() && Prefs(a).screenshotKey

    /** Call first from dispatchKeyEvent. Returns null when the event isn't ours. [pass] replays a short press. */
    fun intercept(a: Activity, e: KeyEvent, pass: (KeyEvent) -> Boolean): Boolean? {
        if (e.keyCode != KeyEvent.KEYCODE_BACK || !enabled(a)) return null
        when {
            e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 -> { held = KeyEvent(e); long = false }
            e.action == KeyEvent.ACTION_DOWN -> if (!long && e.eventTime - e.downTime >= 800) { long = true; take(a) }
            e.action == KeyEvent.ACTION_UP -> {
                val d = held; held = null
                if (!long && d != null) { pass(d); pass(e) }
            }
        }
        return true
    }

    fun take(a: Activity) {
        val root = a.window.decorView
        if (root.width <= 0 || root.height <= 0) return
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val done = { ok: Boolean -> if (ok) upload(a, bmp) else toast(a, "Couldn't capture the screen") }
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                PixelCopy.request(a.window, bmp, { r -> done(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            }.onFailure { done(runCatching { root.draw(android.graphics.Canvas(bmp)) }.isSuccess) }
        } else done(runCatching { root.draw(android.graphics.Canvas(bmp)) }.isSuccess)
    }

    private fun upload(a: Activity, bmp: Bitmap) {
        val app = a.applicationContext
        Thread {
            val msg = runCatching {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 93, out)
                bmp.recycle()
                val req = Request.Builder().url("$BASE/v1/shot")
                    .post(out.toByteArray().toRequestBody("image/jpeg".toMediaType())).build()
                http.newCall(req).execute().use { if (!it.isSuccessful) error("upload failed (${it.code})") }
                count++
                "Screenshot $count saved"
            }.getOrElse { "Screenshot not saved: ${it.message}" }
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
        }.apply { isDaemon = true }.start()
    }

    private fun toast(a: Activity, m: String) = Toast.makeText(a, m, Toast.LENGTH_SHORT).show()
}
