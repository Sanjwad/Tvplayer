package ca.sanjwad.tvplayer

/**
 * What to tell the viewer when something fails. Test builds show everything (handy for chasing a
 * problem); the normal app shows one plain sentence and keeps the technical detail out of sight.
 */
fun userMessage(t: Throwable?, fallback: String): String {
    val full = t?.message.orEmpty().trim()
    if (BuildConfig.TEST_BUILD) return full.ifEmpty { fallback }
    val first = full.lineSequence().firstOrNull()?.trim().orEmpty()
    return when {
        first.isEmpty() -> fallback
        first.startsWith("The portal didn't return a stream") ->
            "Your provider didn't send a video for this. Try again in a minute, or pick something else."
        Regex("Exception|https?://|→|\\b[45]\\d\\d\\b|\\bnull\\b").containsMatchIn(first) -> fallback
        else -> first
    }
}
