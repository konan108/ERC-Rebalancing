package de.konan.hiresplayer

import android.app.Application
import android.content.Context
import de.konan.hiresplayer.playback.PlaybackEngine

class HiResApp : Application() {
    lateinit var engine: PlaybackEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = PlaybackEngine(this)
    }
}

val Context.engine: PlaybackEngine get() = (applicationContext as HiResApp).engine
