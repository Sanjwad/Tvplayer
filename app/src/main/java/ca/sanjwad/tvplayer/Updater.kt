package ca.sanjwad.tvplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Checks GitHub for a newer build and installs it. */
object Updater {
    private const val BASE = "https://github.com/Sanjwad/Tvplayer/releases/latest/download"

    data class Update(val versionCode: Int, val versionName: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Returns the newer version on GitHub, or null if this one is current (or the check failed). */
    fun check(): Update? = runCatching {
        http.newCall(Request.Builder().url("$BASE/version.json").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JSONObject(r.body?.string().orEmpty())
            val code = o.optInt("versionCode", 0)
            if (code > BuildConfig.VERSION_CODE) Update(code, o.optString("versionName")) else null
        }
    }.getOrNull()

    /** Downloads the new APK, reporting progress 0..1. */
    fun download(ctx: Context, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "sanjtv.apk")
        http.newCall(Request.Builder().url("$BASE/tvplayer.apk").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Download failed (${r.code})")
            val body = r.body ?: throw java.io.IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        return out
    }

    /** True if Android will let Sanj TV install updates; otherwise opens the setting to allow it. */
    fun ensureInstallAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
