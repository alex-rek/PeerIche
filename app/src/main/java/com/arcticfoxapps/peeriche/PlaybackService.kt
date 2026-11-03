package com.arcticfoxapps.peeriche

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    companion object {
        private var playbackPlayer: ExoPlayer? = null
        fun pausePlayback() { playbackPlayer?.pause() }
    }
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val settings = AppPreferences.get(this).options.value
        val player = ExoPlayer.Builder(this).setSeekBackIncrementMs(settings.seekSeconds * 1000L)
            .setSeekForwardIncrementMs(settings.seekSeconds * 1000L).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
        playbackPlayer = player
        // Headset next/previous buttons seek within the current single-video session.
        val controls = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
            override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
            override fun getSeekBackIncrement(): Long = AppPreferences.get(this@PlaybackService).options.value.seekSeconds * 1000L
            override fun getSeekForwardIncrement(): Long = seekBackIncrement
            override fun seekBack() { player.seekTo((player.currentPosition - seekBackIncrement).coerceAtLeast(0)) }
            override fun seekForward() { player.seekTo((player.currentPosition + seekForwardIncrement).coerceAtMost(player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }
            override fun seekToNext() = seekForward()
            override fun seekToPrevious() = seekBack()
            override fun seekToNextMediaItem() = seekForward()
            override fun seekToPreviousMediaItem() = seekBack()
        }
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, controls).setSessionActivity(activity).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem ?: return
                val openPlayer = Intent(this@PlaybackService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("playing_id", mediaItem.mediaId)
                    .putExtra("playing_title", mediaItem.mediaMetadata.title?.toString())
                    .putExtra("playing_url", mediaItem.localConfiguration?.uri?.toString())
                session?.setSessionActivity(PendingIntent.getActivity(this@PlaybackService, 0, openPlayer,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        playbackPlayer = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
