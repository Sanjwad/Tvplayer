package ca.sanjwad.tvplayer.stalker

/** What the screens need from a provider, whether it's a Stalker portal or an Xtream server. */
interface IptvSource {
    /** Host that should receive [streamHeaders] when the player fetches video. */
    val portalHost: String

    fun connect()
    fun streamHeaders(): Map<String, String>

    fun liveGenres(): List<Genre>
    fun liveChannels(): List<Channel>

    /**
     * Categories for a tab ("movies" or "series"). Returns the section type to use for later
     * calls (e.g. "vod" or "series") along with the categories.
     */
    fun browseCategories(kind: String): Pair<String, List<Genre>>
    fun vodPage(type: String, categoryId: String, page: Int): Page
    fun seasons(type: String, item: VodItem): List<Season>
    fun episodes(type: String, item: VodItem, season: Season): List<Episode>

    /** Titles in a section ("vod" or "series") whose name matches [query]. */
    fun search(type: String, query: String): List<VodItem>

    /** Every title in a section, used to match trending lists against what this provider has. */
    fun catalogue(type: String): List<VodItem>

    /** Extra details for a movie (description, year, rating) when the list didn't include them. */
    fun movieInfo(item: VodItem): VodItem = item

    fun createLink(type: String, cmd: String, series: String, movieId: String?): String
}
