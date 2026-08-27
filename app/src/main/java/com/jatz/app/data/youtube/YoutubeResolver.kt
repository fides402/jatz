package com.jatz.app.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ResolvedStream(
    val watchUrl: String,
    val streamUrl: String,
    val title: String,
)

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
            NewPipe.init(OkHttpDownloader(httpClient))
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
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        ensureInit()

        val watchUrl = preferredWatchUrl ?: runCatching { search(artist, title) }.getOrNull()
            ?: return@withContext null

        runCatching {
            val info = StreamInfo.getInfo(service, watchUrl)
            val audio = pickAudioStream(info.audioStreams) ?: return@withContext null
            ResolvedStream(watchUrl = watchUrl, streamUrl = audio.content, title = info.name ?: title)
        }.getOrNull()
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
        if (candidates.isEmpty()) return null

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
