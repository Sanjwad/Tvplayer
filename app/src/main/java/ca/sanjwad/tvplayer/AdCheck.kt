package ca.sanjwad.tvplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ad-signal checker: while live TV plays, notes any ad-break markers the provider passes through
 * (SCTE-35 in the stream, or cue tags in an HLS playlist). Results are listed in Settings.
 */
object AdCheck {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val seenTags = LinkedHashSet<String>()
    private val counts = HashMap<String, Int>()
    private val described = HashSet<String>()
    /** Bumped on every new line so the Settings screen refreshes. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    @Synchronized fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "adcheck.txt").also { f ->
            runCatching { f.readLines().takeLast(MAX).forEach { lines.addLast(it) } }
        }
    }

    @Synchronized fun all(): List<String> = lines.toList()

    @Synchronized fun clear() {
        lines.clear(); seenTags.clear(); counts.clear(); described.clear()
        runCatching { file?.delete() }
        version.intValue++
    }

    @Synchronized private fun add(channel: String, msg: String) {
        val t = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$t  $channel: $msg"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        runCatching { file?.writeText(lines.joinToString("\n")) }
        version.intValue++
    }

    private val adTag = Regex("CUE|SCTE|SPLICE|OATCLS|DATERANGE|PLACEMENT|ASSET|#EXT-X-AD|INTERSTITIAL", RegexOption.IGNORE_CASE)

    @OptIn(UnstableApi::class)
    fun listener(player: ExoPlayer, channel: () -> String?): Player.Listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val ch = channel() ?: return
            val m = player.currentManifest as? HlsManifest ?: return
            val tags = m.mediaPlaylist.tags
            if (described.add("hls|$ch")) add(ch, "HLS stream (playlist with ${tags.size} lines)")
            for (tag in tags) {
                if (!adTag.containsMatchIn(tag)) continue
                val key = "$ch|$tag"
                synchronized(this@AdCheck) {
                    if (!seenTags.add(key)) return@synchronized null
                    while (seenTags.size > 2000) seenTags.remove(seenTags.first())
                    key
                } ?: continue
                add(ch, "AD TAG ${tag.take(160)}")
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val ch = channel() ?: return
            val mimes = tracks.groups.flatMap { g -> (0 until g.length).mapNotNull { g.getTrackFormat(it).sampleMimeType } }.distinct()
            if (mimes.isEmpty()) return
            if (described.add("tracks|$ch|${mimes.sorted()}")) {
                val scte = mimes.any { it.contains("scte", ignoreCase = true) }
                add(ch, (if (scte) "AD SIGNAL TRACK FOUND. " else "") + "Tracks: ${mimes.joinToString(", ")}")
            }
        }

        override fun onMetadata(metadata: Metadata) {
            val ch = channel() ?: return
            for (i in 0 until metadata.length()) {
                val e = metadata.get(i)
                val kind = e.javaClass.simpleName
                val n = synchronized(this@AdCheck) { (counts["$ch|$kind"] ?: 0).plus(1).also { counts["$ch|$kind"] = it } }
                val important = kind.contains("Splice", true) && !kind.contains("Null") || kind.contains("TimeSignal", true)
                // Ad commands are always noted; other kinds (timestamps, heartbeats) just once and every 100th.
                if (important || n == 1 || n % 100 == 0)
                    add(ch, (if (important) "AD SIGNAL " else "") + "$kind #$n ${e.toString().replace('\n', ' ').take(160)}")
            }
        }
    }
}
