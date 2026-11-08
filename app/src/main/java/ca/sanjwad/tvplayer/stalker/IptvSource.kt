package ca.sanjwad.tvplayer.stalker

/** What the screens need from a provider, whether it's a Stalker portal or an Xtream server. */
interface IptvSource {
    /** Host that should receive [streamHeaders] when the player fetches video. */
    val portalHost: String

    fun connect()
    fun streamHeaders(): Map<String, String>

    fun liveGenres(): List<Genre>
    fun liveChannels(): List<Channel>

    /** What's on now and next for a channel (may be empty if the provider has no guide). */
    fun nowNext(channel: Channel): List<Programme> = emptyList()

    /** Guide entries overlapping [from, to) from the full guide, if it has been downloaded. */
    fun programmes(channel: Channel, from: Long, to: Long): List<Programme> = emptyList()

    /** Past programmes you can watch again on a catch-up channel, newest last (with [Programme.archive] set). */
    fun catchupProgrammes(channel: Channel): List<Programme> = emptyList()

    /** Addresses to try, in order, for a past programme on a catch-up channel. */
    fun catchupUrls(channel: Channel, start: Long, end: Long): List<String> = emptyList()

    /** Download the full TV guide if it is missing or old. Safe to call repeatedly. */
    fun ensureGuide() {}

    /** Short status of the full guide, e.g. "Downloading TV guide…" or "" when ready. */
    val guideStatus: String get() = ""

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
