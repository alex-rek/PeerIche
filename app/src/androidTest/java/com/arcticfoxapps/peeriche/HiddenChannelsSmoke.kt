package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.room.Room
import java.util.UUID

internal suspend fun hiddenChannelsSmoke(context: Context) {
    val name = "hidden-channels-smoke-${UUID.randomUUID()}.db"
    fun open() = Room.databaseBuilder(context, PeerIcheDatabase::class.java, name).build()
    var database = open()
    try {
        val video = Video(uuid = "hidden-fixture", channel = Channel("Music", "music", "origin.example"))
        val row = video.hiddenChannelRow("https://replica.example", "Music")!!
        database.library().put(row.copy(kind = "subscription"))
        database.library().put(row)
        database.close(); database = open()
        val hidden = database.library().getAll(HIDDEN_CHANNEL)
        check(hidden.single() == row)
        check(listOf(video).withoutHiddenChannels(hidden.map { it.identity }.toSet(), "https://other-replica.example").isEmpty())
        database.library().delete(row.identity, HIDDEN_CHANNEL)
        check(database.library().getAll(HIDDEN_CHANNEL).isEmpty())
        check(database.library().getAll("subscription").single().identity == row.identity)
        database.close(); database = open()
        check(database.library().getAll(HIDDEN_CHANNEL).isEmpty())
    } finally { database.close(); context.deleteDatabase(name) }
}
