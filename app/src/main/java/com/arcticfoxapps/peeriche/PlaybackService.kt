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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    companion object {
        private var playbackPlayer: ExoPlayer? = null
        private var pauseSpeech: (() -> Unit)? = null
        fun pausePlayback() { pauseSpeech?.invoke(); playbackPlayer?.pause() }
        private val ended = MutableStateFlow<String?>(null)
        val endedVideo = ended.asStateFlow()
    }
    private var session: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var subtitleSpeech: SubtitleSpeechManager? = null
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val settings = AppPreferences.get(this).options.value
        val dataSource = androidx.media3.datasource.ResolvingDataSource.Factory(
            androidx.media3.datasource.DefaultDataSource.Factory(this),
            androidx.media3.datasource.ResolvingDataSource.Resolver { spec ->
                val grant = AccountVideoPlayback.grant
                if (grant == null || !grant.accepts(spec.uri.toString())) spec
                else spec.withUri(android.net.Uri.parse(kotlinx.coroutines.runBlocking { grant.resolve(spec.uri.toString()) }))
            })
        val player = ExoPlayer.Builder(this).setSeekBackIncrementMs(settings.seekSeconds * 1000L)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSource))
            .setSeekForwardIncrementMs(settings.seekSeconds * 1000L).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
        playbackPlayer = player
        serviceScope.launch {
            PeerTubeAccounts.get(this@PlaybackService).state.collect {
                val grant = AccountVideoPlayback.grant
                if (grant != null && !grant.active()) { player.stop(); player.clearMediaItems(); AccountVideoPlayback.clear() }
            }
        }
        pauseSpeech = { subtitleSpeech?.userPause() }
        androidx.core.content.ContextCompat.registerReceiver(this, noisyReceiver,
            android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        serviceScope.launch { AppPreferences.get(this@PlaybackService).options.collect { value ->
            if (!value.speech.enabled) { subtitleSpeech?.close(); subtitleSpeech = null }
            else if (subtitleSpeech == null) subtitleSpeech = SubtitleSpeechManager(this@PlaybackService, player, value)
            else subtitleSpeech?.configure(value)
        } }
        ended.value = null
        val watchTime = WatchTimeTracker()
        fun sampleWatchTime() {
            val item = player.currentMediaItem
            if (watchTime.sample(item?.mediaId, android.os.SystemClock.elapsedRealtime(), player.isPlaying)) {
                val row = runCatching {
                    com.google.gson.Gson().fromJson(item?.mediaMetadata?.extras?.getString("history"), SavedVideo::class.java)
                }.getOrNull()?.takeIf { it.kind == "history" }
                if (row != null) serviceScope.launch {
                    PeerIcheDatabase.get(this@PlaybackService).library().put(row.copy(updatedAt = System.currentTimeMillis()))
                }
            }
        }
        serviceScope.launch { while (isActive) { sampleWatchTime(); delay(250) } }
        // Headset next/previous buttons seek within the current single-video session.
        val controls = object : ForwardingPlayer(player) {
            override fun pause() { subtitleSpeech?.userPause(); player.pause() }
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
            override fun onIsPlayingChanged(isPlaying: Boolean) { sampleWatchTime() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.value = player.currentMediaItem?.mediaId
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                ended.value = null
                sampleWatchTime()
                mediaItem ?: return
                val openPlayer = Intent(this@PlaybackService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("playing_id", mediaItem.mediaId)
                    .putExtra("playing_title", mediaItem.mediaMetadata.title?.toString())
                    .putExtra("playing_url", mediaItem.localConfiguration?.uri?.toString())
                    .putExtra("playing_account", mediaItem.mediaMetadata.extras?.getString("playback_account"))
                session?.setSessionActivity(PendingIntent.getActivity(this@PlaybackService, 0, openPlayer,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        AccountVideoPlayback.clear()
        subtitleSpeech?.close(resumeVideo = false); subtitleSpeech = null
        unregisterReceiver(noisyReceiver)
        pauseSpeech = null
        serviceScope.cancel()
        playbackPlayer = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
