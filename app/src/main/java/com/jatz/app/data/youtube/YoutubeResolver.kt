package com.jatz.app.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "JATZ-YT"

data class ResolvedStream(
    val watchUrl: String,
    val streamUrl: String,
    val title: String,
)

/**
 * Outcome of a resolve attempt, WITH a reason on failure. A first real device
 * test failed silently for every track of every album with only a generic
 * "Nessuna traccia trovata" — every exception in this pipeline was caught by
 * a blanket `runCatching {}.getOrNull()` with nothing logged and nothing
 * surfaced, so there was no way to tell whether the cause was "no search
 * results", "NewPipeExtractor threw", or "found a video but every stream was
 * DASH-only", all of which need a different fix. Never swallow silently here
 * again — every failure path returns a reason, and PlayerController surfaces
 * the first one it sees.
 */
sealed class ResolveOutcome {
    data class Success(val stream: ResolvedStream) : ResolveOutcome()
    data class Failed(val reason: String) : ResolveOutcome()
}

/**
 * Finds a playable YouTube audio stream for (artist, title) with no API key
 * and no cookies, entirely on-device — the piece that makes the phone
 * independent of any server (see PIANO.md §3).
 *
 * This is also the one dependency expected to break periodically: YouTube
 * changes its internal API often enough that NewPipeExtractor needs regular
 * version bumps. When resolution starts failing across the board, that's the
 * first thing to check (bump `com.github.TeamNewPipe:NewPipeExtractor` in
 * app/build.gradle.kts).
 */
object YoutubeResolver {
    private val initialized = AtomicBoolean(false)
    private val service = ServiceList.YouTube

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun ensureInit() {
        if (initialized.compareAndSet(false, true)) {
            // Explicit en-US: NewPipe.init()'s default locale/country combo
            // steers YouTube toward less heavily-tested response variants,
            // and a first real run hit a NullPointerException deep inside
            // NewPipeExtractor's own JSON parsing (chained .getObject() calls
            // assuming a shape YouTube didn't return) -- a known, recurring
            // class of bug for exactly this kind of locale/experiment
            // mismatch. en-US is what NewPipeExtractor's own test suite and
            // the NewPipe app itself are most tested against.
            NewPipe.init(OkHttpDownloader(httpClient), Localization("en", "US"), ContentCountry("US"))
        }
    }

    /**
     * Resolves a playable audio stream. If [preferredWatchUrl] is set (a
     * previous resolution for this exact track), search is skipped entirely.
     */
    suspend fun resolve(
        artist: String,
        title: String,
        preferredWatchUrl: String? = null,
    ): ResolveOutcome = withContext(Dispatchers.IO) {
        ensureInit()

        val watchUrl: String = if (preferredWatchUrl != null) {
            preferredWatchUrl
        } else {
            val searchResult = runCatching { search(artist, title) }
            val error = searchResult.exceptionOrNull()
            if (error != null) {
                Log.w(TAG, "search failed for \"$artist - $title\"", error)
                return@withContext ResolveOutcome.Failed(
                    "ricerca YouTube fallita (${error.javaClass.simpleName}: ${error.message})",
                )
            }
            searchResult.getOrNull() ?: return@withContext ResolveOutcome.Failed(
                "nessun risultato di ricerca per “$artist - $title”",
            )
        }

        val infoResult = runCatching { StreamInfo.getInfo(service, watchUrl) }
        val info = infoResult.getOrElse { error ->
            Log.w(TAG, "StreamInfo.getInfo failed for $watchUrl", error)
            return@withContext ResolveOutcome.Failed(
                "risoluzione video fallita (${error.javaClass.simpleName}: ${error.message})",
            )
        }

        val audio = pickAudioStream(info.audioStreams)
        if (audio == null) {
            val methods = info.audioStreams.map { it.deliveryMethod }.distinct()
            Log.w(TAG, "no progressive-HTTP audio stream for $watchUrl " +
                "(${info.audioStreams.size} streams, delivery methods: $methods)")
            return@withContext ResolveOutcome.Failed(
                if (info.audioStreams.isEmpty()) "il video non ha alcuno stream audio"
                else "nessuno stream riproducibile tra ${info.audioStreams.size} " +
                    "(formati: ${methods.joinToString()})",
            )
        }

        ResolveOutcome.Success(
            ResolvedStream(watchUrl = watchUrl, streamUrl = audio.content, title = info.name ?: title),
        )
    }

    private fun pickAudioStream(streams: List<AudioStream>): AudioStream? =
        streams
            // Progressive HTTP is a single direct file URL ExoPlayer plays
            // as-is. DASH/HLS need a manifest-aware MediaSource this app
            // doesn't build, so they're skipped rather than mishandled.
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.averageBitrate }

    private fun search(artist: String, title: String): String? {
        val query = "$artist $title".trim()
        val extractor = service.getSearchExtractor(query)
        // SearchExtractor (unlike single-item extractors) has no separate
        // fetchPage() step: getInitialPage() itself performs the network call.
        val page = extractor.initialPage

        val candidates = page.items
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM }
            .take(10)
        if (candidates.isEmpty()) {
            Log.w(TAG, "search returned ${page.items.size} items, 0 usable video streams for \"$query\"")
            return null
        }

        val target = normalize("$artist $title")
        val best = candidates.maxByOrNull { scoreCandidate(it, target) } ?: return null
        return best.url
    }

    // Cheap lexical scoring, in the same spirit as digmore's yt_hunter: reward
    // token overlap with the Discogs (artist, title) pair, penalise the
    // wrong-version keywords that most often surface for an oldies search.
    private val penaltyWords = listOf(
        "live", "cover", "reaction", "karaoke", "8d audio", "sped up",
        "slowed", "remix", "tutorial", "lyric video", "lyrics video",
    )

    private fun scoreCandidate(item: StreamInfoItem, target: String): Double {
        val name = normalize(item.name ?: "")
        val targetTokens = target.split(" ").filter { it.length > 2 }.toSet()
        val nameTokens = name.split(" ").toSet()
        val overlap = if (targetTokens.isEmpty()) 0.0
            else targetTokens.count { it in nameTokens }.toDouble() / targetTokens.size

        var score = overlap
        for (bad in penaltyWords) {
            if (bad in name && bad !in target) score -= 0.35
        }
        // A short teaser/short-form upload is almost never the full track.
        if (item.isShortFormContent) score -= 0.5
        return score
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
