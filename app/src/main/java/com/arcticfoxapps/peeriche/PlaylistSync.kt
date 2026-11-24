package com.arcticfoxapps.peeriche

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/*
 * Ordinary playlists are synced with the signed-in PeerTube account the same way as "Watch later":
 * each local playlist is linked to one server playlist, and the videos in each pair are merged three ways.
 * Playlists created in PeerIche go to the server as private playlists, so nothing is published.
 */

internal const val PLAYLIST_DEFINITIONS = "playlist"
internal const val PLAYLIST_SYNCED = "synced"
internal const val PLAYLIST_REMOVE = "remove"
internal const val PLAYLIST_LOCAL_ONLY = "local_only"
internal const val PEERTUBE_PLAYLIST_REGULAR = 1
internal const val PEERTUBE_PRIVACY_PRIVATE = 3

/** Links a local playlist to a server playlist of one account. */
@Entity(tableName = "playlist_links", primaryKeys = ["accountId", "localId"])
data class PlaylistLink(val accountId: String, val localId: String, val remoteId: Long?, val state: String)

/** Sync state of one video inside a linked playlist; same meaning as [WatchLaterSyncState]. */
@Entity(tableName = "playlist_elements", primaryKeys = ["accountId", "localId", "videoKey"])
data class PlaylistElementState(val accountId: String, val localId: String, val videoKey: String, val elementId: Long?, val state: String)

@Dao interface PlaylistSyncDao {
    @Query("SELECT * FROM playlist_links WHERE accountId = :accountId") suspend fun links(accountId: String): List<PlaylistLink>
    @Query("SELECT * FROM playlist_links WHERE accountId = :accountId AND localId = :localId") suspend fun link(accountId: String, localId: String): PlaylistLink?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLink(link: PlaylistLink)
    @Query("DELETE FROM playlist_links WHERE accountId = :accountId AND localId = :localId") suspend fun deleteLink(accountId: String, localId: String)
    @Query("SELECT * FROM playlist_elements WHERE accountId = :accountId AND localId = :localId") suspend fun elements(accountId: String, localId: String): List<PlaylistElementState>
    @Query("SELECT * FROM playlist_elements WHERE accountId = :accountId AND localId = :localId AND videoKey = :videoKey")
    suspend fun element(accountId: String, localId: String, videoKey: String): PlaylistElementState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putElement(state: PlaylistElementState)
    @Query("DELETE FROM playlist_elements WHERE accountId = :accountId AND localId = :localId AND videoKey = :videoKey")
    suspend fun deleteElement(accountId: String, localId: String, videoKey: String)
    @Query("DELETE FROM playlist_elements WHERE accountId = :accountId AND localId = :localId") suspend fun deleteElements(accountId: String, localId: String)
    @Query("SELECT accountId FROM playlist_links UNION SELECT accountId FROM playlist_elements") suspend fun accounts(): List<String>
    @Query("DELETE FROM playlist_links WHERE accountId = :accountId") suspend fun deleteAccountLinks(accountId: String)
    @Query("DELETE FROM playlist_elements WHERE accountId = :accountId") suspend fun deleteAccountElements(accountId: String)
    /** Local playlists linked to the account whose library is visible now (the "Watch later" owner). */
    @Query("SELECT localId FROM playlist_links WHERE state = 'synced' AND accountId = (SELECT videoKey FROM watch_later_sync WHERE accountId = '' LIMIT 1)")
    fun observeSynced(): Flow<List<String>>
}

internal data class RemotePlaylist(val id: Long, val name: String)

internal data class PlaylistPlan(
    /** Local playlists to create on the server. */
    val create: List<String> = emptyList(),
    /** Local playlists that match an unlinked server playlist by name. */
    val link: Map<String, Long> = emptyMap(),
    /** Server playlists to create locally. */
    val download: List<RemotePlaylist> = emptyList(),
    val rename: Map<String, String> = emptyMap(),
    /** Linked playlists deleted on the server. */
    val deleteLocal: List<String> = emptyList(),
    /** Playlists deleted in PeerIche, by local id, with the server playlist to delete. */
    val deleteRemote: Map<String, Long> = emptyMap(),
    val dropLink: List<String> = emptyList()
)

private fun playlistName(value: String) = value.trim().lowercase(Locale.ROOT)

/** [remote] must be every regular playlist of the account, or missing ones would look deleted. */
internal fun planPlaylistSync(local: Map<String, String>, links: List<PlaylistLink>, remote: List<RemotePlaylist>): PlaylistPlan {
    val remoteById = remote.associateBy { it.id }
    val create = mutableListOf<String>(); val link = linkedMapOf<String, Long>()
    val rename = linkedMapOf<String, String>(); val deleteLocal = mutableListOf<String>()
    val deleteRemote = linkedMapOf<String, Long>(); val dropLink = mutableListOf<String>()
    val claimed = links.mapNotNull { it.remoteId }.toMutableSet()
    for (entry in links) {
        val server = entry.remoteId?.let(remoteById::get)
        val title = local[entry.localId]
        when (entry.state) {
            PLAYLIST_REMOVE -> if (server != null) deleteRemote[entry.localId] = server.id else dropLink += entry.localId
            PLAYLIST_SYNCED -> when {
                // A missing local playlist without a removal is never treated as a deletion: the server copy comes back.
                title == null -> { dropLink += entry.localId; entry.remoteId?.let(claimed::remove) }
                server == null -> deleteLocal += entry.localId
                server.name != title -> rename[entry.localId] = server.name
            }
            else -> if (title == null) dropLink += entry.localId
        }
    }
    val linkedLocal = links.map { it.localId }.toSet()
    for ((id, title) in local) {
        if (id in linkedLocal) continue
        val match = remote.firstOrNull { it.id !in claimed && playlistName(it.name) == playlistName(title) }
        if (match != null) { link[id] = match.id; claimed += match.id } else create += id
    }
    val download = remote.filter { it.id !in claimed }
    return PlaylistPlan(create, link, download, rename, deleteLocal, deleteRemote, dropLink)
}

/** Kind of the playlist list or a playlist's videos while its account is not the visible one. */
internal fun storedPlaylistKind(kind: String, accountId: String) = "$kind@$accountId"

private const val PLAYLIST_VIDEOS_PREFIX = "$PLAYLIST_DEFINITIONS:"
private const val UUID_LENGTH = 36

/**
 * Splits a stored kind into its visible kind and account. Account ids may themselves contain "@",
 * so the split relies on the fixed shapes "playlist@…" and "playlist:<uuid>@…".
 */
internal fun storedPlaylistOwner(kind: String): Pair<String, String>? = when {
    kind.startsWith("$PLAYLIST_DEFINITIONS@") -> PLAYLIST_DEFINITIONS to kind.removePrefix("$PLAYLIST_DEFINITIONS@")
    kind.startsWith(PLAYLIST_VIDEOS_PREFIX) && kind.length > PLAYLIST_VIDEOS_PREFIX.length + UUID_LENGTH + 1 &&
        kind[PLAYLIST_VIDEOS_PREFIX.length + UUID_LENGTH] == '@' ->
        kind.substring(0, PLAYLIST_VIDEOS_PREFIX.length + UUID_LENGTH) to kind.substring(PLAYLIST_VIDEOS_PREFIX.length + UUID_LENGTH + 1)
    else -> null
}?.takeIf { it.second.isNotEmpty() }

/** Visible kinds: the playlist list and "playlist:<uuid>" video lists, never a stored copy. */
internal fun isVisiblePlaylistKind(kind: String): Boolean =
    kind == PLAYLIST_DEFINITIONS || (kind.startsWith(PLAYLIST_VIDEOS_PREFIX) && kind.length == PLAYLIST_VIDEOS_PREFIX.length + UUID_LENGTH)
