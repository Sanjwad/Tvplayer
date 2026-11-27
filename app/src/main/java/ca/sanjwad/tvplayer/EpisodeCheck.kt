package ca.sanjwad.tvplayer

import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Keeps the episode lists of My List shows fresh in the background (shortly after start, then every
 * two hours), so My List can show "new episodes" straight away instead of checking when you open it.
 */
object EpisodeCheck {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val fetched = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Bumped whenever a show's episode list arrives; My List reads it to redraw. */
    val version = mutableIntStateOf(0)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(20_000)
            while (isActive) {
                if (Session.client == null) { delay(30_000); continue }
                runCatching { refresh(maxAgeMs = 2 * 3_600_000L) }
                delay(2 * 3_600_000L)
            }
        }
    }

    /** Fetches every saved show whose list is missing or older than [maxAgeMs], four at a time. */
    suspend fun refresh(maxAgeMs: Long) = coroutineScope {
        val client = Session.client ?: return@coroutineScope
        val gate = Semaphore(4)
        val now = System.currentTimeMillis()
        Library.myList.toList().filter { it.item.isSeries }.map { sv ->
            async {
                val id = sv.item.id
                val t = fetched[id]
                if (Session.seasonCache[id] != null && t != null && now - t < maxAgeMs) return@async
                gate.withPermit {
                    val list = runCatching { client.seasons(sv.type, sv.item) }.getOrNull() ?: return@withPermit
                    Session.seasonCache[id] = list
                    fetched[id] = System.currentTimeMillis()
                    version.intValue++
                }
            }
        }.awaitAll()
    }

    fun clear() { fetched.clear() }
}
