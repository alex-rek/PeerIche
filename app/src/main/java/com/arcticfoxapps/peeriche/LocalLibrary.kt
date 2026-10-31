package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_videos")
data class SavedVideo(
    @PrimaryKey val identity: String,
    val kind: String,
    val title: String,
    val videoUrl: String?,
    val server: String,
    val thumbnailUrl: String?,
    val channelName: String?,
    val views: Long,
    val updatedAt: Long
)

@Dao interface LibraryDao {
    @Query("SELECT * FROM saved_videos WHERE kind = :kind ORDER BY updatedAt DESC") fun observe(kind: String): Flow<List<SavedVideo>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(video: SavedVideo)
    @Query("DELETE FROM saved_videos WHERE identity = :identity AND kind = :kind") suspend fun delete(identity: String, kind: String)
}

@Database(entities = [SavedVideo::class], version = 1, exportSchema = false)
abstract class PeerIcheDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    companion object {
        @Volatile private var instance: PeerIcheDatabase? = null
        fun get(context: Context): PeerIcheDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PeerIcheDatabase::class.java, "peeriche.db").build().also { instance = it }
        }
    }
}

val Context.peerIcheDataStore by preferencesDataStore(name = "peeriche_settings")
private val serverListKey = stringPreferencesKey("servers")
private val activeServerKey = stringPreferencesKey("activeServer")

suspend fun saveServerList(context: Context, value: String, active: String) {
    context.peerIcheDataStore.edit {
        it[serverListKey] = value
        it[activeServerKey] = active
    }
}

fun SavedVideo.toVideo(): Video = Video(
    uuid = identity, name = title, url = videoUrl, thumbnailUrl = thumbnailUrl,
    views = views, channel = Channel(displayName = channelName), host = runCatching { java.net.URI(server).host }.getOrNull()
)
