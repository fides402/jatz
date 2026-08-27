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
private const val WATCH_URL_PREFIX = "https://www.youtube.com/watch?v="

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

        val watchUrls: List<String> = preferredWatchUrl?.let { listOf(it) }
            ?: findWatchUrls(artist, title)
        if (watchUrls.isEmpty()) {
            return@withContext ResolveOutcome.Failed(
                "nessun risultato di ricerca per “$artist - $title”",
            )
        }

        // Try more than one match: an individual video can be age-gated,
        // region-blocked, or DASH-only, none of which means the track is
        // unavailable — the next result usually plays fine.
        var lastReason = "nessun video utilizzabile"
        for (watchUrl in watchUrls.take(3)) {
            val info = runCatching { StreamInfo.getInfo(service, watchUrl) }.getOrElse { error ->
                Log.w(TAG, "StreamInfo.getInfo failed for $watchUrl", error)
                lastReason = "risoluzione video fallita (${error.javaClass.simpleName}: ${error.message})"
                null
            } ?: continue

            val audio = pickAudioStream(info.audioStreams)
            if (audio == null) {
                val methods = info.audioStreams.map { it.deliveryMethod }.distinct()
                Log.w(TAG, "no progressive-HTTP audio stream for $watchUrl " +
                    "(${info.audioStreams.size} streams, delivery methods: $methods)")
                lastReason = if (info.audioStreams.isEmpty()) "il video non ha alcuno stream audio"
                    else "nessuno stream riproducibile tra ${info.audioStreams.size} " +
                        "(formati: ${methods.joinToString()})"
                continue
            }

            return@withContext ResolveOutcome.Success(
                ResolvedStream(watchUrl = watchUrl, streamUrl = audio.content, title = info.name ?: title),
            )
        }

        ResolveOutcome.Failed(lastReason)
    }

    private fun pickAudioStream(streams: List<AudioStream>): AudioStream? =
        streams
            // Progressive HTTP is a single direct file URL ExoPlayer plays
            // as-is. DASH/HLS need a manifest-aware MediaSource this app
            // doesn't build, so they're skipped rather than mishandled.
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.averageBitrate }

    /**
     * Two independent strategies, tried in order — deliberately not one.
     *
     * NewPipeExtractor's search parser NPE'd on every single track on device,
     * so relying on it alone (even with a locale fix) is not something to bet
     * playback on. If it throws, [YoutubeSearchFallback] does the search
     * without it and cannot fail the same way.
     */
    private fun findWatchUrls(artist: String, title: String): List<String> {
        val query = "$artist $title".trim()
        val target = normalize(query)

        runCatching { searchViaNewPipe(query, target) }
            .onFailure { Log.w(TAG, "NewPipe search failed for \"$query\", using fallback", it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val candidates = YoutubeSearchFallback.search(httpClient, query)
        if (candidates.isEmpty()) {
            Log.w(TAG, "fallback search also found nothing for \"$query\"")
            return emptyList()
        }
        // Untitled candidates come from the crude id-only scan, where page
        // order is the only ranking signal available — keep that order rather
        // than scoring them all identically at zero.
        val ranked = if (candidates.all { it.title.isBlank() }) {
            candidates
        } else {
            candidates.sortedByDescending { scoreTitle(it.title, target) }
        }
        return ranked.map { WATCH_URL_PREFIX + it.videoId }
    }

    private fun searchViaNewPipe(query: String, target: String): List<String> {
        // Explicitly restrict to videos: the unfiltered "all" search mixes in
        // channel/playlist/"did you mean" blocks, which is the response shape
        // NewPipeExtractor's parser handles worst.
        val extractor = service.getSearchExtractor(query, listOf("videos"), "")
        // SearchExtractor (unlike single-item extractors) has no separate
        // fetchPage() step: getInitialPage() itself performs the network call.
        val page = extractor.initialPage

        val candidates = page.items
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM }
            .take(10)
        if (candidates.isEmpty()) {
            Log.w(TAG, "NewPipe search returned ${page.items.size} items, 0 usable videos for \"$query\"")
            return emptyList()
        }

        return candidates
            .sortedByDescending {
                scoreTitle(it.name ?: "", target) - if (it.isShortFormContent) 0.5 else 0.0
            }
            .mapNotNull { it.url }
    }

    // Cheap lexical scoring, in the same spirit as digmore's yt_hunter: reward
    // token overlap with the Discogs (artist, title) pair, penalise the
    // wrong-version keywords that most often surface for an oldies search.
    private val penaltyWords = listOf(
        "live", "cover", "reaction", "karaoke", "8d audio", "sped up",
        "slowed", "remix", "tutorial", "lyric video", "lyrics video",
    )

    private fun scoreTitle(rawName: String, target: String): Double {
        val name = normalize(rawName)
        val targetTokens = target.split(" ").filter { it.length > 2 }.toSet()
        val nameTokens = name.split(" ").toSet()
        val overlap = if (targetTokens.isEmpty()) 0.0
            else targetTokens.count { it in nameTokens }.toDouble() / targetTokens.size

        var score = overlap
        for (bad in penaltyWords) {
            if (bad in name && bad !in target) score -= 0.35
        }
        return score
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
