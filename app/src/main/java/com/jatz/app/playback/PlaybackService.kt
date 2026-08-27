package com.jatz.app.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background audio, the whole point of choosing NewPipeExtractor + ExoPlayer
 * over the YouTube IFrame digmore uses: a MediaSessionService keeps playing
 * with the screen off, survives the app going to the background, and gets
 * lock-screen / Bluetooth / notification controls from Media3 automatically —
 * none of which an IFrame can do (see PIANO.md §3).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Recorded jazz LPs have far less headroom than modern masters;
            // ducking (instead of pausing) on a transient notification sound
            // would otherwise make quiet passages disappear.
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
