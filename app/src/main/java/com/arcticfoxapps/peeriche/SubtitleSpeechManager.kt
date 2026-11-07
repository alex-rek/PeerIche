package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal object SubtitleSpeechRuntime {
    val status = MutableStateFlow("off")
}

/** Owned by PlaybackService, never by a player view or an Activity. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SubtitleSpeechManager(private val context: Context, private val player: Player, initial: AppOptions) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var options = initial
    private var engine = TtsEngineController(context, initial.speech.engine)
    private val queue = SpeechQueueController()
    private var prepareJob: Job? = null
    private var embeddedJob: Job? = null
    private var engineInitJob: Job? = null
    private var speechJob: Job? = null
    private var generation = 0
    private var originals = emptyList<SpeechCue>()
    private var translated = emptyList<SpeechCue>()
    private var embedded: SpeechCue? = null
    private val audio = SpeechAudioController({ player.volume }, { player.volume = it })
    private var holding = false
    private var heldMediaId: String? = null
    private var closing = false
    private var selectedTextLanguage: String? = null
    private var loadingSourceLanguage: String? = null

    init {
        player.addListener(this)
        initializeEngine()
        load()
        if (BuildConfig.DEBUG) scope.launch { SubtitleSpeechRuntime.status.collect {
            android.util.Log.d("PeerIcheSpeech", "$it position=${player.currentPosition} volume=${player.volume}")
        } }
        scope.launch { while (isActive) { tick(); delay(100) } }
    }
    private fun initializeEngine() { engineInitJob?.cancel(); val current = engine; engineInitJob = scope.launch {
        try { current.initialize() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SubtitleSpeechRuntime.status.value = "engine_error" }
    } }
    fun configure(value: AppOptions) {
        val changed = options.speech != value.speech || options.translateTo != value.translateTo ||
            options.subtitleLanguage != value.subtitleLanguage || options.modelsWifiOnly != value.modelsWifiOnly
        if (!changed) return
        val oldEngine = options.speech.engine
        options = value
        stopSpeech()
        if (oldEngine != value.speech.engine) { engine.close(); engine = TtsEngineController(context, value.speech.engine); initializeEngine() }
        load()
    }
    private fun load() {
        prepareJob?.cancel()
        embeddedJob?.cancel()
        stopSpeech()
        queue.reset()
        originals = emptyList(); translated = emptyList(); embedded = null
        val item = player.currentMediaItem ?: return
        prepareJob = scope.launch {
            val allConfigs = item.localConfiguration?.subtitleConfigurations.orEmpty()
            val configs = allConfigs.filter { it.uri.scheme == "https" }
            val preferred = options.subtitleLanguage.ifBlank { selectedTextLanguage
                ?: player.trackSelectionParameters.preferredTextLanguages.firstOrNull()
                ?: androidx.core.os.ConfigurationCompat.getLocales(android.content.res.Resources.getSystem().configuration)[0]?.language.orEmpty() }
            val target = options.speech.language.ifBlank { options.translateTo }
            val nativeTarget = allConfigs.firstOrNull { it.language?.substringBefore('-') == target.substringBefore('-') }
            val source = (if (options.speech.mode in listOf("auto", "translated")) nativeTarget else null)
                ?: configs.firstOrNull { it.language?.substringBefore('-') == preferred.substringBefore('-') } ?: configs.firstOrNull()
            if (source == null) { SubtitleSpeechRuntime.status.value = "no_subtitles"; return@launch }
            loadingSourceLanguage = source.language
            try {
                originals = SubtitleCueAdapter.load(source.uri.toString(), source.language.orEmpty())
                if (source.language.isNullOrBlank() && originals.isNotEmpty()) {
                    val detected = SubtitleCueAdapter.detectLanguage(originals.take(3).joinToString(" ") { it.text })
                    originals = originals.map { it.copy(language = detected) }
                }
                SubtitleSpeechRuntime.status.value = if (originals.isEmpty()) "no_subtitles" else "ready"
                if (options.speech.mode != "original" && source.language?.substringBefore('-') != target.substringBefore('-')) {
                    SubtitleSpeechRuntime.status.value = "translating"
                    val existing = allConfigs.firstOrNull {
                        it.language?.substringBefore('-') == target.substringBefore('-')
                    }
                    translated = if (existing != null) SubtitleCueAdapter.load(existing.uri.toString(), target)
                    else {
                        val caption = VideoCaption(CaptionLanguage(source.language.orEmpty()), fileUrl = source.uri.toString())
                        val cached = LocalTranslation.subtitle(context, caption, "${source.uri.scheme}://${source.uri.authority}", target)
                        if (cached == null) emptyList() else SubtitleCueAdapter.load(cached.toURI().toString(), target)
                    }
                    SubtitleSpeechRuntime.status.value = "ready"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SubtitleSpeechRuntime.status.value = if (originals.isEmpty()) "captions_error" else "translation_error" }
        }
    }
    private fun tick() {
        if (closing || !options.speech.enabled || !player.isPlaying || player.playbackState != Player.STATE_READY) return
        val position = player.currentPosition
        val original = SubtitleCueAdapter.current(originals, position) ?: embedded?.takeIf { position in it.startMs until it.endMs }
        val translation = SubtitleCueAdapter.current(translated, position)
        val cue = original ?: translation
        if (cue == null) { if (speechJob?.isActive == true && !holding) stopSpeech(); return }
        val parts = speechParts(options.speech.mode, original, translation, options.speech.language.ifBlank { options.translateTo }, options.speech.fallback)
        if (parts.isEmpty()) return
        val key = cue.key
        if (!queue.claim(key)) return
        stopSpeech()
        val token = generation
        val itemId = player.currentMediaItem?.mediaId
        speechJob = scope.launch {
            try {
                val settings = options.speech
                val remaining = (cue.endMs - player.currentPosition).coerceAtLeast(1)
                val rate = speechRate(settings, player.playbackParameters.speed, parts.sumOf { it.text.length }, remaining)
                engine.prepare(parts.first().language, settings.voice)
                if (token != generation || !player.isPlaying) return@launch
                audio.begin(settings)
                if (BuildConfig.DEBUG) android.util.Log.d("PeerIcheSpeech", "speech_start languages=${parts.map { it.language }} volume=${player.volume}")
                if (settings.strategy == "wait") { holding = true; heldMediaId = itemId; player.pause() }
                SubtitleSpeechRuntime.status.value = "speaking"
                val deadline = if (settings.strategy == "wait") 30_000L else (remaining / player.playbackParameters.speed).toLong().coerceIn(1, 30_000)
                withTimeout(deadline) { parts.forEach { engine.speak(it, rate, settings.pitch, settings.voice) } }
                SubtitleSpeechRuntime.status.value = "ready"
            } catch (_: TimeoutCancellationException) { SubtitleSpeechRuntime.status.value = "ready" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: SpeechUnavailable) { SubtitleSpeechRuntime.status.value = error.code }
            catch (_: Exception) { SubtitleSpeechRuntime.status.value = "speech_error" }
            finally { if (generation == token) { engine.stop(); restoreAudio(); resumeHeld(itemId) } }
        }
    }
    private fun restoreAudio() { audio.restore() }
    override fun onVolumeChanged(volume: Float) { audio.volumeChanged(volume) }
    private fun resumeHeld(id: String?) {
        val resume = holding && !closing && heldMediaId == id && id == player.currentMediaItem?.mediaId && player.playbackState != Player.STATE_ENDED
        holding = false; heldMediaId = null
        if (resume) player.play()
    }
    private fun stopSpeech(resume: Boolean = true) {
        generation++
        speechJob?.cancel(); speechJob = null
        engine.stop()
        restoreAudio()
        if (SubtitleSpeechRuntime.status.value == "speaking") SubtitleSpeechRuntime.status.value = "ready"
        if (resume) resumeHeld(player.currentMediaItem?.mediaId) else { holding = false; heldMediaId = null }
    }
    fun userPause() { stopSpeech(false); queue.reset() }
    override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying && !holding) { stopSpeech(false); queue.reset() } }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) userPause()
    }
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) { if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) userPause() }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        stopSpeech(); queue.reset(); embeddedJob?.cancel(); embedded = null
    }
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { stopSpeech(); queue.reset() }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { selectedTextLanguage = null; loadingSourceLanguage = null; load() }
    override fun onTracksChanged(tracks: Tracks) {
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val language = group?.let { (0 until it.length).firstOrNull { index -> it.isTrackSelected(index) }?.let { index -> it.getTrackFormat(index).language } }
        if (!language.isNullOrBlank() && language != selectedTextLanguage) {
            selectedTextLanguage = language
            if (options.speech.mode == "original" && language != loadingSourceLanguage &&
                player.currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty().any { it.language == language && it.uri.scheme == "https" }) load()
        }
    }
    override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) stopSpeech(false) }
    override fun onPlayerError(error: PlaybackException) { stopSpeech(false) }
    override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
        val text = SubtitleCueAdapter.clean(cueGroup.cues.mapNotNull { it.text }.joinToString(" "))
        val language = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            ?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it).language } }.orEmpty()
        if (text.isBlank()) { embedded = null; embeddedJob?.cancel(); return }
        val start = cueGroup.presentationTimeUs.takeIf { it != C.TIME_UNSET && it >= 0 }?.div(1000) ?: player.currentPosition
        val next = SpeechCue(start, maxOf(start + 8_000, player.currentPosition + 100), text, language)
        if (embedded?.key == next.key) return
        embeddedJob?.cancel()
        embedded = next
        if (player.currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty().isNotEmpty()) return
        val id = player.currentMediaItem?.mediaId
        embeddedJob = scope.launch {
            var current = next
            if (current.language.isBlank()) { current = current.copy(language = SubtitleCueAdapter.detectLanguage(text)); embedded = current }
            val target = options.speech.language.ifBlank { options.translateTo }
            if (options.speech.mode != "original" && current.language.substringBefore('-') != target.substringBefore('-')) {
                try {
                    SubtitleSpeechRuntime.status.value = "translating"
                    val result = LocalTranslation.translate(context, text, current.language, target)
                    if (id == player.currentMediaItem?.mediaId && player.currentPosition < current.endMs) {
                        translated = listOf(current.copy(text = result, language = target))
                        SubtitleSpeechRuntime.status.value = "ready"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { SubtitleSpeechRuntime.status.value = "translation_error" }
            }
        }
    }
    fun close(resumeVideo: Boolean = true) {
        if (resumeVideo && holding) resumeHeld(player.currentMediaItem?.mediaId)
        closing = true
        stopSpeech(false); prepareJob?.cancel(); player.removeListener(this); scope.cancel(); engine.close()
        SubtitleSpeechRuntime.status.value = "off"
    }
}
