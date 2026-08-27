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
 * [PlaybackService] via a [MediaController], resolves an album's tracks on
 * YouTube one at a time — starting playback as soon as the first is ready
 * rather than waiting on the whole album (see [playAlbum]) — and mirrors
 * [Player] callbacks into a [StateFlow] the Compose UI collects.
 */
class PlayerController(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var playbackJob: Job? = null

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

    /**
     * Resolves [album]'s tracks on YouTube and plays them, starting from
     * [startIndex]. Resolving one track at a time takes a few seconds each;
     * an 8-12 track album resolved fully before anything played made the
     * player look stuck for a long stretch. So this now resolves just the
     * requested track first and starts playback immediately, then keeps
     * resolving the rest (in playback order, wrapping back to the start of
     * the album) and appends each one to the live queue as it's ready —
     * audio starts in one track's worth of time, not the whole album's.
     */
    fun playAlbum(album: AlbumDto, startIndex: Int = 0) {
        playbackJob?.cancel()   // a second tap while the first is still resolving must not race it
        playbackJob = scope.launch {
            _state.update {
                PlayerUiState(album = album, isLoading = true, loadingLabel = "Preparo il disco…")
            }
            YoutubeResolver.ensureInit()

            val c = controller
            if (c == null) {
                _state.update { it.copy(isLoading = false, error = "Player non ancora connesso, riprova.") }
                return@launch
            }

            val safeStart = startIndex.coerceIn(0, album.tracks.lastIndex)
            // Playback order: the requested track first, then the rest of the
            // album in its own order, wrapping around — so "play track 5"
            // queues 5,6,7...,1,2,3,4, matching what a listener expects next.
            val playOrder = (safeStart until album.tracks.size) + (0 until safeStart)

            var startedPlayback = false
            var lastFailureReason: String? = null

            for (origIndex in playOrder) {
                val track = album.tracks[origIndex]
                if (!startedPlayback) {
                    _state.update { it.copy(loadingLabel = "Cerco “${track.title}”…") }
                }
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

                if (!startedPlayback) {
                    c.setMediaItems(listOf(item), 0, 0L)
                    c.prepare()
                    c.play()
                    startedPlayback = true
                    _state.update {
                        it.copy(queue = listOf(track), currentIndex = 0, isLoading = false,
                            loadingLabel = null, error = null)
                    }
                } else {
                    c.addMediaItem(item)
                    _state.update { it.copy(queue = it.queue + track) }
                }
            }

            if (!startedPlayback) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Nessuna traccia riproducibile. " + (lastFailureReason ?: "Motivo sconosciuto."),
                    )
                }
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
        playbackJob?.cancel()
        progressJob?.cancel()
        controller?.release()
        controller = null
    }
}
