package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_videos", primaryKeys = ["kind", "identity"])
data class SavedVideo(
    val identity: String,
    val kind: String,
    val title: String,
    val videoUrl: String?,
    val server: String,
    val thumbnailUrl: String?,
    val channelName: String?,
    val views: Long,
    val updatedAt: Long,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val metadata: String? = null
)

@Dao interface LibraryDao {
    @Query("SELECT * FROM saved_videos WHERE kind = :kind ORDER BY updatedAt DESC") fun observe(kind: String): Flow<List<SavedVideo>>
    @Query("SELECT * FROM saved_videos WHERE kind = :kind ORDER BY updatedAt DESC") suspend fun getAll(kind: String): List<SavedVideo>
    @Query("SELECT * FROM saved_videos WHERE identity = :identity AND kind LIKE 'playlist:%'") fun playlistMemberships(identity: String): Flow<List<SavedVideo>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(video: SavedVideo)
    @Query("DELETE FROM saved_videos WHERE identity = :identity AND kind = :kind") suspend fun delete(identity: String, kind: String)
    @Query("DELETE FROM saved_videos WHERE kind = :kind") suspend fun deleteKind(kind: String)
}

@Database(entities = [SavedVideo::class], version = 3, exportSchema = false)
abstract class PeerIcheDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    companion object {
        @Volatile private var instance: PeerIcheDatabase? = null
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE saved_videos_new (identity TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, videoUrl TEXT, server TEXT NOT NULL, thumbnailUrl TEXT, channelName TEXT, views INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(kind, identity))")
                db.execSQL("INSERT INTO saved_videos_new (identity, kind, title, videoUrl, server, thumbnailUrl, channelName, views, updatedAt) SELECT identity, kind, title, videoUrl, server, thumbnailUrl, channelName, views, updatedAt FROM saved_videos")
                db.execSQL("DROP TABLE saved_videos")
                db.execSQL("ALTER TABLE saved_videos_new RENAME TO saved_videos")
            }
        }
        internal val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_videos ADD COLUMN metadata TEXT DEFAULT NULL")
            }
        }
        fun get(context: Context): PeerIcheDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PeerIcheDatabase::class.java, "peeriche.db").addMigrations(migration1To2, migration2To3).build().also { instance = it }
        }
    }
}

val Context.peerIcheDataStore by preferencesDataStore(name = "peeriche_settings")
private val serverListKey = stringPreferencesKey("servers")
private val activeServerKey = stringPreferencesKey("activeServer")
val darkThemeKey = booleanPreferencesKey("darkTheme")

suspend fun saveServerList(context: Context, value: String, active: String) {
    context.peerIcheDataStore.edit {
        it[serverListKey] = value
        it[activeServerKey] = active
    }
}

internal suspend fun useAccountServer(context: Context, host: String) {
    val origin = authServerOrigin(host)
    context.peerIcheDataStore.edit {
        val servers = it[serverListKey].orEmpty().lines().mapNotNull { value -> runCatching { normalizeServer(value) }.getOrNull() }
        it[serverListKey] = (servers + origin).distinct().joinToString("\n")
        it[activeServerKey] = origin
    }
}

suspend fun saveDarkTheme(context: Context, enabled: Boolean) {
    context.peerIcheDataStore.edit { it[darkThemeKey] = enabled }
}

fun SavedVideo.toVideo(): Video = metadata?.let { raw ->
    runCatching { com.google.gson.Gson().fromJson(raw, Video::class.java) }.getOrNull()
} ?: Video(
    uuid = identity.substringAfter('|'), name = title, directStreamUrl = videoUrl?.takeIf(::looksLikeMediaStream), thumbnailUrl = thumbnailUrl,
    views = views, channel = Channel(displayName = channelName), host = runCatching { java.net.URI(server).host }.getOrNull()
)

private fun looksLikeMediaStream(value: String): Boolean {
    val path = runCatching { java.net.URI(value).path.lowercase() }.getOrDefault("")
    return path.endsWith(".m3u8") || path.endsWith(".mpd") || listOf(".mp4", ".webm", ".m4v", ".ogv", ".mp3", ".m4a", ".ogg").any(path::endsWith)
}

val themeModeKey = stringPreferencesKey("themeMode")

suspend fun saveThemeMode(context: Context, mode: String) {
    context.peerIcheDataStore.edit { it[themeModeKey] = mode }
}
