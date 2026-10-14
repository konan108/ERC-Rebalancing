package de.konan.hiresplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import de.konan.hiresplayer.MainActivity
import de.konan.hiresplayer.engine

/** Hintergrundwiedergabe, Benachrichtigung und Mediensteuerung (Kopfhörer, Auto, Wear). */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val onPlayerChanged: (Player) -> Unit = { session?.player = it }

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, engine.player)
            .setSessionActivity(openApp)
            .build()
        engine.addPlayerChangeListener(onPlayerChanged)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        engine.removePlayerChangeListener(onPlayerChanged)
        // Der Player gehört der Engine und überlebt den Service.
        session?.release()
        session = null
        super.onDestroy()
    }
}
