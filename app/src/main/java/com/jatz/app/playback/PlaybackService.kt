package com.jatz.app.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

// Matches the User-Agent OkHttpDownloader presents to NewPipeExtractor. YouTube's
// CDN (googlevideo.com) commonly 403s a request whose User-Agent doesn't look like
// a real browser/app -- ExoPlayer's default HTTP data source sends a generic
// ExoPlayer UA, which is exactly the request shape that gets rejected. Without
// this, every resolved stream URL fails to play with no visible error beyond a
// generic HTTP 403 in PlaybackException, which read as "the player just does
// nothing" from the UI.
private const val STREAM_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

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

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpDataSourceFactory))
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
