package com.arcticfoxapps.peeriche

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SensitiveContentTest {
    @Test fun enablingSensitiveContentKeepsOrdinaryVideosInServerAndInterestFeeds() = runBlocking {
        val origin = "https://sensitive-filter-test.example"
        val requests = mutableListOf<okhttp3.HttpUrl>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url
            val ids = when (request.url.queryParameter("nsfw")) {
                "both" -> listOf("ordinary", "sensitive")
                "false" -> listOf("ordinary")
                "true" -> listOf("sensitive")
                else -> error("Missing content filter")
            }
            val videos = ids.joinToString(",") { """{"uuid":"$it","name":"$it","nsfw":${it == "sensitive"}}""" }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"total":${ids.size},"data":[$videos]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("$origin/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(PeerTubeApi::class.java)
        val field = PeerTubeClients.javaClass.getDeclaredField("retrofitCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(PeerTubeClients) as MutableMap<String, PeerTubeApi>
        val previous = cache.put(origin, api)
        try {
            assertEquals(listOf("ordinary"), PeerTubeClients.serverPage(origin, 0, false).videos.map { it.uuid })
            assertEquals(listOf("ordinary", "sensitive"), PeerTubeClients.serverPage(origin, 0, true).videos.map { it.uuid })
            assertEquals(listOf("ordinary"), PeerTubeClients.interestVideos(origin, listOf(1), false).map { it.uuid })
            assertEquals(listOf("ordinary", "sensitive"), PeerTubeClients.interestVideos(origin, listOf(1), true).map { it.uuid })
            assertEquals(listOf("false", "both", "false", "both"), requests.map { it.queryParameter("nsfw") })
            assertEquals("true", requests.first().queryParameter("isLocal"))
            assertEquals("1", requests.last().queryParameter("categoryOneOf"))
        } finally {
            if (previous == null) cache.remove(origin) else cache[origin] = previous
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun authenticatedSubscriptionsUseTheSameInclusiveFilter() {
        assertEquals("false", AccountOperation.feed(0, false).query["nsfw"])
        assertEquals("both", AccountOperation.feed(30, true).query["nsfw"])
        assertEquals("30", AccountOperation.feed(30, true).query["start"])
    }
}
