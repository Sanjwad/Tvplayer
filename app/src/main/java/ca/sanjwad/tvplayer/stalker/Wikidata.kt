package ca.sanjwad.tvplayer.stalker

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Free facts from Wikidata by IMDb id: what a title is based on, where it was filmed and where it's set. */
object Wikidata {
    data class Facts(val basedOn: List<String>, val filmedIn: List<String>, val setIn: List<String>)

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Facts>()

    fun facts(imdbId: String): Facts {
        cache[imdbId]?.let { return it }
        val q = """
            SELECT ?basedOnLabel ?authorLabel ?locLabel ?setLabel WHERE {
              ?f wdt:P345 "$imdbId" .
              OPTIONAL { ?f wdt:P144 ?basedOn . OPTIONAL { ?basedOn wdt:P50 ?author . } }
              OPTIONAL { ?f wdt:P915 ?loc . }
              OPTIONAL { ?f wdt:P840 ?set . }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
            } LIMIT 200
        """.trimIndent()
        val url = "https://query.wikidata.org/sparql".toHttpUrl().newBuilder()
            .addQueryParameter("query", q).addQueryParameter("format", "json").build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "SanjTV/1.0 (Android TV media app)")
            .header("Accept", "application/sparql-results+json").build()
        val rows = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return Facts(emptyList(), emptyList(), emptyList())
            JSONObject(r.body?.string().orEmpty()).optJSONObject("results")?.optJSONArray("bindings")
        } ?: return Facts(emptyList(), emptyList(), emptyList())
        val based = LinkedHashMap<String, MutableSet<String>>()
        val filmed = LinkedHashSet<String>()
        val set = LinkedHashSet<String>()
        // Unlabelled items come back as their Q-number; skip those.
        fun v(o: JSONObject, k: String) = o.optJSONObject(k)?.optString("value")?.takeIf { it.isNotBlank() && !Regex("^Q\\d+$").matches(it) }
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            v(o, "basedOnLabel")?.let { b -> based.getOrPut(b) { LinkedHashSet() }.apply { v(o, "authorLabel")?.let { add(it) } } }
            v(o, "locLabel")?.let { filmed += it }
            v(o, "setLabel")?.let { set += it }
        }
        val f = Facts(
            basedOn = based.map { (b, authors) -> if (authors.isEmpty()) b else "$b by ${authors.joinToString(" & ")}" },
            filmedIn = filmed.take(8), setIn = set.take(6),
        )
        cache[imdbId] = f
        return f
    }
}
