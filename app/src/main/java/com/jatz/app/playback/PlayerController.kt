package com.jatz.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.jatz.app.data.LibraryStore
import com.jatz.app.data.model.AlbumDto
import com.jatz.app.data.model.TrackDto
import com.jatz.app.data.youtube.ResolveOutcome
import com.jatz.app.data.youtube.YoutubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RepeatCycle { OFF, ALBUM, TRACK }

data class PlayerUiState(
    val album: AlbumDto? = null,
    val queue: List<TrackDto> = emptyList(),   // only the tracks that resolved
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val loadingLabel: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeat: RepeatCycle = RepeatCycle.OFF,
    val shuffle: Boolean = false,
    val error: String? = null,
) {
    val currentTrack: TrackDto? get() = queue.getOrNull(currentIndex)
}

/**
 * The single owner of playback state for the whole app: connects to
 * [PlaybackService] via a [MediaController], resolves each track's YouTube
 * stream up front for the whole album (see PIANO.md — eager resolution keeps
 * the mid-playback state machine simple), and mirrors [Player] callbacks into
 * a [StateFlow] the Compose UI collects.
 */
class PlayerController(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startProgressTicker() else progressJob?.cancel()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            _state.update { it.copy(currentIndex = c.currentMediaItemIndex, positionMs = 0L) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            if (playbackState == Player.STATE_READY) {
                _state.update { it.copy(durationMs = c.duration.coerceAtLeast(0L)) }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeat = fromPlayerRepeat(repeatMode)) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(shuffle = shuffleModeEnabled) }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.update { it.copy(error = error.message, isLoading = false) }
        }
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** Resolves every track of [album] on YouTube, then starts playback at [startIndex]. */
    fun playAlbum(album: AlbumDto, startIndex: Int = 0) {
        scope.launch {
            _state.update {
                PlayerUiState(album = album, isLoading = true, loadingLabel = "Preparo il disco…")
            }
            YoutubeResolver.ensureInit()

            data class Resolved(val track: TrackDto, val item: MediaItem)
            val resolved = mutableListOf<Resolved>()
            // Kept so a total failure shows WHY, not just "found nothing" --
            // see ResolveOutcome's doc for why this replaced a silent null.
            var lastFailureReason: String? = null

            for (track in album.tracks) {
                _state.update { it.copy(loadingLabel = "Cerco “${track.title}”…") }
                val cached = withContext(Dispatchers.IO) {
                    LibraryStore.cachedStreamRef(appContext, album.id, track.position)
                }
                val artist = track.artist.ifBlank { album.artist }
                val outcome = YoutubeResolver.resolve(artist, track.title, cached)
                val stream = when (outcome) {
                    is ResolveOutcome.Success -> outcome.stream
                    is ResolveOutcome.Failed -> {
                        lastFailureReason = "${track.title}: ${outcome.reason}"
                        continue   // track skipped; the rest of the album still plays
                    }
                }

                if (cached == null) {
                    withContext(Dispatchers.IO) {
                        LibraryStore.cacheStreamRef(appContext, album.id, track.position, stream.watchUrl)
                    }
                }

                val item = MediaItem.Builder()
                    .setUri(stream.streamUrl)
                    .setMediaId("${album.id}#${track.position}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(artist)
                            .setAlbumTitle(album.title)
                            .apply { if (album.coverUrl.isNotBlank()) setArtworkUri(Uri.parse(album.coverUrl)) }
                            .build(),
                    )
                    .build()
                resolved += Resolved(track, item)
            }

            if (resolved.isEmpty()) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Nessuna traccia riproducibile. " + (lastFailureReason ?: "Motivo sconosciuto."),
                    )
                }
                return@launch
            }

            val c = controller
            if (c == null) {
                _state.update { it.copy(isLoading = false, error = "Player non ancora connesso, riprova.") }
                return@launch
            }

            val safeStart = startIndex.coerceIn(0, resolved.lastIndex)
            c.setMediaItems(resolved.map { it.item }, safeStart, 0L)
            c.prepare()
            c.play()

            _state.update {
                it.copy(
                    queue = resolved.map { r -> r.track },
                    currentIndex = safeStart,
                    isLoading = false,
                    loadingLabel = null,
                    error = null,
                )
            }
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        _state.update { it.copy(positionMs = ms) }
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() {
        val c = controller ?: return
        // Mirrors the usual player convention: "previous" restarts the current
        // track once you're a couple seconds in, and only jumps back before that.
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun cycleRepeat() {
        val c = controller ?: return
        val next = when (fromPlayerRepeat(c.repeatMode)) {
            RepeatCycle.OFF -> Player.REPEAT_MODE_ALL
            RepeatCycle.ALBUM -> Player.REPEAT_MODE_ONE
            RepeatCycle.TRACK -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun playQueueIndex(index: Int) {
        controller?.seekTo(index, 0L)
        controller?.play()
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0L)) }
                }
                delay(500)
            }
        }
    }

    private fun fromPlayerRepeat(mode: Int): RepeatCycle = when (mode) {
        Player.REPEAT_MODE_ALL -> RepeatCycle.ALBUM
        Player.REPEAT_MODE_ONE -> RepeatCycle.TRACK
        else -> RepeatCycle.OFF
    }

    fun release() {
        progressJob?.cancel()
        controller?.release()
        controller = null
    }
}
