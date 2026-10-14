package de.konan.hiresplayer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import de.konan.hiresplayer.playback.PlaybackService
import de.konan.hiresplayer.ui.AppRoot
import de.konan.hiresplayer.ui.HiResTheme

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleTidalRedirect(intent)
        setContent {
            HiResTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTidalRedirect(intent)
    }

    private fun handleTidalRedirect(intent: Intent?) {
        (application as HiResApp).tidal.handleRedirect(intent?.data)
    }

    override fun onStart() {
        super.onStart()
        // Bindet den MediaSessionService, damit Wiedergabe im Hintergrund weiterläuft.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}
