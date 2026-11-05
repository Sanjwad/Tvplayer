package ca.sanjwad.tvplayer.stalker

data class Genre(val id: String, val title: String)

data class Channel(
    val id: String,
    val name: String,
    val number: Int,
    val cmd: String,
    val logo: String?,
    val genreId: String,
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
)

data class Season(val id: String, val name: String, val episodes: List<Episode>?)

data class Episode(val title: String, val number: String, val cmd: String, val seriesParam: String, val plot: String = "")

data class Page(val items: List<VodItem>, val totalItems: Int)

object Net {
    /** User-Agent sent to the provider; matches a common TV-box player. */
    const val UA =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
}
