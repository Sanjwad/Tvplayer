package ca.sanjwad.tvplayer.stalker

data class Genre(val id: String, val title: String)

data class Channel(
    val id: String,
    val name: String,
    val number: Int,
    val cmd: String,
    val logo: String?,
    val genreId: String,
    /** The channel's id in the provider's full TV guide (XMLTV), if any. */
    val epgId: String = "",
    /** Days of catch-up the provider keeps for this channel (0 = none, -1 = not known yet). */
    val archiveDays: Int = 0,
)

data class VodItem(
    val id: String,
    val name: String,
    val description: String,
    val poster: String?,
    val cmd: String,
    val isSeries: Boolean,
    val year: String,
    val rating: String,
    val backdrop: String? = null,
    /** The provider's category this title is filed under, e.g. "FR | Films 2025". */
    val group: String = "",
    /** Audio language reported by the provider for this file, e.g. "French". */
    val audioLang: String = "",
)

/** Works out which language a title is in from its category, its name or its audio details. */
object Lang {
    private val rules = listOf(
        "FR" to Regex("""\b(FR|FRA|FRE|FRENCH|FRANCAIS|FRANÇAIS|VF|VFF|VFQ|VOSTFR|QUEBEC|QUÉBEC|QC)\b""", RegexOption.IGNORE_CASE),
        "ES" to Regex("""\b(ES|ESP|SPA|SPANISH|ESPAÑOL|ESPANOL|LATINO|LAT|CASTELLANO)\b""", RegexOption.IGNORE_CASE),
        "DE" to Regex("""\b(DE|GER|GERMAN|DEUTSCH)\b""", RegexOption.IGNORE_CASE),
        "IT" to Regex("""\b(IT|ITA|ITALIAN|ITALIANO)\b""", RegexOption.IGNORE_CASE),
        "PT" to Regex("""\b(PT|POR|PORTUGUESE|PORTUGUES|BR|BRAZIL)\b""", RegexOption.IGNORE_CASE),
        "NL" to Regex("""\b(NL|DUTCH)\b""", RegexOption.IGNORE_CASE),
        "TR" to Regex("""\b(TR|TURKISH|TÜRK)\b""", RegexOption.IGNORE_CASE),
        "AR" to Regex("""\b(AR|ARA|ARABIC)\b""", RegexOption.IGNORE_CASE),
        "HI" to Regex("""\b(HINDI|BOLLYWOOD)\b""", RegexOption.IGNORE_CASE),
        "PL" to Regex("""\b(PL|POLISH)\b""", RegexOption.IGNORE_CASE),
        "MULTI" to Regex("""\bMULTI\b""", RegexOption.IGNORE_CASE),
    )
    private val audioNames = mapOf(
        "fre" to "French", "fra" to "French", "fr" to "French", "eng" to "English", "en" to "English",
        "spa" to "Spanish", "es" to "Spanish", "ger" to "German", "deu" to "German", "de" to "German",
        "ita" to "Italian", "it" to "Italian", "por" to "Portuguese", "pt" to "Portuguese", "ara" to "Arabic",
        "hin" to "Hindi", "tur" to "Turkish", "dut" to "Dutch", "nld" to "Dutch", "pol" to "Polish",
    )
    fun audioName(code: String): String = audioNames[code.trim().lowercase()].orEmpty()

    /** A short code like "FR" for a title that isn't in English, or null if it looks English/unknown. */
    fun badge(item: VodItem): String? {
        when (item.audioLang) {
            "" -> {}
            "English" -> return null
            else -> return rules.firstOrNull { it.first.equals(item.audioLang.take(2), true) }?.first ?: item.audioLang.take(3).uppercase()
        }
        // Name markers like "FR - Title" or "Title (VF)" first, then the category name.
        // Only capitals count here, so "It: Welcome to Derry" isn't mistaken for Italian.
        val name = Regex("""^\s*\[?([A-ZÀ-Ý]{2,9})\]?\s*[|:\-]|[\[(]([A-ZÀ-Ý]{2,9})[\])]""").findAll(item.name)
            .flatMap { m -> m.groupValues.drop(1).filter { it.isNotBlank() } }.joinToString(" ")
        for (src in listOf(name, item.group)) {
            if (src.isBlank()) continue
            if (Regex("""\b(EN|ENG|ENGLISH|UK|USA?|CA)\b""", RegexOption.IGNORE_CASE).containsMatchIn(src) &&
                rules.none { it.first != "MULTI" && it.second.containsMatchIn(src) }) return null
            rules.firstOrNull { it.second.containsMatchIn(src) }?.let { return it.first }
        }
        return null
    }

    /** Full name for the detail page, e.g. "French". */
    fun name(code: String) = when (code) {
        "FR" -> "French"; "ES" -> "Spanish"; "DE" -> "German"; "IT" -> "Italian"; "PT" -> "Portuguese"
        "NL" -> "Dutch"; "TR" -> "Turkish"; "AR" -> "Arabic"; "HI" -> "Hindi"; "PL" -> "Polish"; "MULTI" -> "Several languages"
        else -> code
    }
}

data class Season(val id: String, val name: String, val episodes: List<Episode>?)

data class Episode(
    val title: String, val number: String, val cmd: String, val seriesParam: String,
    val plot: String = "", val thumb: String? = null, val duration: String = "",
)

/** One TV-guide entry; times are epoch milliseconds. */
data class Programme(val title: String, val description: String, val start: Long, val end: Long, val archive: Boolean = false)

data class Page(val items: List<VodItem>, val totalItems: Int)

object Net {
    /** User-Agent sent to the provider; matches a common TV-box player. */
    const val UA =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    /** User-Agent for movie/episode video files: a widely accepted media player, as IPTV panels expect. */
    const val VIDEO_UA = "VLC/3.0.20 LibVLC/3.0.20"
}
