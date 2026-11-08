package ca.sanjwad.tvplayer.stalker

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Subtitle search and download from OpenSubtitles.com (free account + API key). */
class OpenSubs(private val key: String, private val user: String = "", private val pass: String = "") {
    data class Sub(val fileId: Long, val release: String, val lang: String, val downloads: Int, val hearingImpaired: Boolean, val machine: Boolean)

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val base = "https://api.opensubtitles.com/api/v1"
    private val json = "application/json".toMediaType()
    @Volatile private var token: String? = null

    private fun req(url: String) = Request.Builder().url(url)
        .header("Api-Key", key).header("User-Agent", "SanjTV v1.0").header("Accept", "application/json")
        .apply { token?.let { header("Authorization", "Bearer $it") } }

    private fun fail(code: Int, body: String): Nothing {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
        throw IOException(when {
            code == 401 || code == 403 -> "OpenSubtitles refused the key or login${if (msg.isNotBlank()) ": $msg" else "."}"
            code == 406 || code == 429 -> msg.ifBlank { "OpenSubtitles download limit reached for today." }
            else -> "OpenSubtitles error $code${if (msg.isNotBlank()) ": $msg" else ""}"
        })
    }

    fun search(tmdbId: Int?, query: String, year: String, season: Int?, episode: Int?, lang: String): List<Sub> {
        fun run(params: List<Pair<String, String>>): List<Sub> {
            val url = "$base/subtitles".toHttpUrl().newBuilder().apply {
                // OpenSubtitles wants parameters in alphabetical order.
                (params + ("languages" to lang) + ("order_by" to "download_count")).sortedBy { it.first }
                    .forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            http.newCall(req(url.toString()).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) fail(r.code, body)
                val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
                return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optJSONObject("attributes") }.mapNotNull { a ->
                    val f = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
                    Sub(f.optLong("file_id"), a.optString("release").ifBlank { f.optString("file_name") }, a.optString("language"),
                        a.optInt("download_count"), a.optBoolean("hearing_impaired"),
                        a.optBoolean("machine_translated") || a.optBoolean("ai_translated"))
                }.filter { it.fileId > 0 }
            }
        }
        val tries = mutableListOf<List<Pair<String, String>>>()
        if (season != null && episode != null) {
            if (tmdbId != null) tries += listOf("parent_tmdb_id" to "$tmdbId", "season_number" to "$season", "episode_number" to "$episode")
            tries += listOf("query" to query, "season_number" to "$season", "episode_number" to "$episode")
        } else {
            if (tmdbId != null) tries += listOf("tmdb_id" to "$tmdbId")
            tries += listOf("query" to query) + (if (year.isNotBlank()) listOf("year" to year.take(4)) else emptyList())
        }
        for (t in tries) {
            val res = run(t)
            if (res.isNotEmpty()) return res.sortedWith(compareBy<Sub> { it.machine }.thenByDescending { it.downloads }).take(25)
        }
        return emptyList()
    }

    private fun login() {
        if (user.isBlank() || pass.isBlank() || token != null) return
        val body = JSONObject().put("username", user).put("password", pass).toString().toRequestBody(json)
        http.newCall(req("$base/login").post(body).build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) fail(r.code, b)
            token = JSONObject(b).optString("token").takeIf { it.isNotBlank() }
        }
    }

    /** Downloads a subtitle as .srt into [dir]; returns the file. */
    fun download(sub: Sub, dir: File): File {
        login()
        val body = JSONObject().put("file_id", sub.fileId).put("sub_format", "srt").toString().toRequestBody(json)
        val link = http.newCall(req("$base/download").post(body).build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) fail(r.code, b)
            JSONObject(b).optString("link").takeIf { it.startsWith("http") } ?: throw IOException("OpenSubtitles gave no download link.")
        }
        val out = File(dir, "sub_${sub.fileId}.srt")
        http.newCall(Request.Builder().url(link).header("User-Agent", "SanjTV v1.0").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Couldn't download the subtitle (${r.code}).")
            out.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
        }
        return out
    }
}
