package com.arcticfoxapps.peeriche

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal fun playbackMediaItem(video: Video, server: String, options: AppOptions,
    tracks: List<MediaItem.SubtitleConfiguration>, translation: String): MediaItem =
    MediaItem.Builder().setMediaId(video.sourceServer(server) + "|" + video.apiId())
        .setUri(video.playableUrl(options.quality))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(video.name)
            .setArtist(video.channel?.displayName ?: video.account?.displayName)
            .setExtras(android.os.Bundle().apply {
                putInt("quality", options.quality)
                putString("translation", translation)
                putString("history", Gson().toJson(video.toSavedVideo(server, "history")))
                putString("playback_account", video.playbackAccountId)
                putBoolean("is_live", video.isLive)
            }).build()).setSubtitleConfigurations(tracks).build()

internal data class QueuedPlayback(val item: MediaItem, val grant: AccountVideoGrant?)

internal suspend fun prepareQueuedPlayback(context: Context, video: Video, server: String, options: AppOptions): QueuedPlayback {
    val accounts = PeerTubeAccounts.get(context)
    accounts.initialize()
    val account = video.playbackAccountId
    if (account != null) check(accounts.state.value.active?.let { it.id == account && !it.needsLogin } == true)
    val origin = video.sourceServer(server)
    val captions = try {
        withTimeoutOrNull(8000) {
            if (account == null) PeerTubeClients.server(origin).captions(video.apiId()).data
            else Gson().fromJson(accounts.request(account, AccountOperation.videoCaptions(video.apiId())), CaptionPage::class.java).data
        }.orEmpty()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { emptyList() }
    val tracks = captions.mapIndexedNotNull { index, caption -> caption.resolvedUrl(origin)?.let { url ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(caption.language?.id).setLabel(caption.language?.label)
            .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0).build()
    } }.toMutableList()
    var translation = ""
    if (options.translateSubtitles) {
        if (captions.any { it.language?.id?.substringBefore('-') == options.translateTo }) translation = options.translateTo
        else {
            val source = captions.firstOrNull { it.language?.id?.substringBefore('-') == options.subtitleLanguage && it.language?.id?.substringBefore('-') != options.translateTo }
                ?: captions.firstOrNull { it.language?.id?.substringBefore('-') == "en" && options.translateTo != "en" }
                ?: captions.firstOrNull { it.language?.id?.substringBefore('-') != options.translateTo }
            if (source != null) try {
                withTimeoutOrNull(20_000) { LocalTranslation.subtitle(context, source, origin) }?.let { file ->
                    tracks += MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(options.translateTo).setLabel(context.getString(R.string.translated_track)).build()
                    translation = options.translateTo
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Playback can continue with the original captions. */ }
        }
    }
    val grant = account?.let { AccountVideoGrant(it, video, accounts).also { access -> check(access.active()) } }
    return QueuedPlayback(playbackMediaItem(video, server, options, tracks, translation), grant)
}
