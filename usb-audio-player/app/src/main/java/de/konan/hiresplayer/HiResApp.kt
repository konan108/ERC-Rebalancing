package de.konan.hiresplayer

import android.app.Application
import android.content.Context
import de.konan.hiresplayer.playback.PlaybackEngine
import de.konan.hiresplayer.tidal.TidalSession

class HiResApp : Application() {
    lateinit var engine: PlaybackEngine
        private set
    lateinit var tidal: TidalSession
        private set

    override fun onCreate() {
        super.onCreate()
        engine = PlaybackEngine(this)
        tidal = TidalSession(this)
    }
}

val Context.engine: PlaybackEngine get() = (applicationContext as HiResApp).engine
