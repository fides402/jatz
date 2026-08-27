package com.jatz.app

import android.app.Application
import com.jatz.app.data.youtube.YoutubeResolver
import com.jatz.app.playback.PlayerController
import com.jatz.app.work.DailyFetchWorker
import com.jatz.app.work.ensureNotificationChannels

class JatzApp : Application() {

    // A single PlayerController for the process lifetime — every screen that
    // needs playback state reads the same instance, so the mini-player, the
    // full player screen and the album screen never disagree about what's
    // playing.
    lateinit var playerController: PlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
        YoutubeResolver.ensureInit()

        playerController = PlayerController(this)
        playerController.connect()

        DailyFetchWorker.scheduleInitial(this)
    }
}
