package com.arcticfoxapps.peeriche

import android.content.Context
import androidx.compose.runtime.*
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.net.UnknownHostException
import java.net.InetAddress
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

internal val catalogKey = stringPreferencesKey("serverCatalog")
private val hostname = Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,62}")

internal fun catalogHosts(lines: Sequence<String>): List<String> = lines.map { it.trim().lowercase().removeSuffix(".") }
    .filter { hostname.matches(it) && it.substringAfterLast('.') !in setOf("local", "localhost", "internal", "test", "invalid", "onion") }
    .distinct().sorted().toList()

internal fun preferredBrowsingServer(configured: List<String>, active: String, account: AccountSummary?): String? =
    active.takeIf { it.isNotBlank() && it in configured } ?: account?.takeUnless { it.needsLogin }?.server

@Composable internal fun bundledCatalog(context: Context): List<String> {
    val hosts by produceState(emptyList<String>(), context) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("peertube-servers.txt").bufferedReader().use { catalogHosts(it.lineSequence()) }
        }
    }
    return hosts
}

internal data class CatalogUpdate(val hosts: List<String>, val partial: Boolean)

internal fun updatedCatalog(previous: List<String>, official: List<String>?, fedidb: List<String>?): CatalogUpdate {
    require(official != null || fedidb != null)
    val partial = official == null || fedidb == null
    val fallback = if (partial) previous else emptyList()
    val hosts = catalogHosts((fallback + official.orEmpty() + fedidb.orEmpty()).asSequence())
    require(hosts.isNotEmpty())
    return CatalogUpdate(hosts, partial)
}

internal suspend fun refreshServerCatalog(context: Context, previous: List<String>): CatalogUpdate = withTimeout(60_000) {
    coroutineScope {
        suspend fun fetchSource(fetch: suspend () -> List<String>): List<String>? {
            return try {
                fetch().also { require(it.isNotEmpty()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        val official = async { fetchSource { PeerTubeClients.discoverAllHosts() } }
        val fedidb = async(Dispatchers.IO) {
            fetchSource {
                fedidbHttp.download("https://api.fedidb.org/datasets/server-domains/peertube.txt.gz").use { body ->
                    GZIPInputStream(body.byteStream()).use { input ->
                        val bytes = input.readBytesUpTo(2_000_000)
                        catalogHosts(bytes.toString(Charsets.UTF_8).lineSequence()).also { require(it.size <= 25_000) }
                    }
                }
            }
        }
        val result = updatedCatalog(previous, official.await(), fedidb.await())
        context.peerIcheDataStore.edit { it[catalogKey] = result.hosts.joinToString("\n") }
        result
    }
}

private fun java.io.InputStream.readBytesUpTo(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
}

internal interface CatalogHttp {
    @Streaming @GET suspend fun download(@Url url: String): ResponseBody
}

private val fedidbHttp by lazy {
    val client = OkHttpClient.Builder().followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "PeerIche/Android").build())
        }.build()
    Retrofit.Builder().baseUrl("https://api.fedidb.org/").client(client).build().create(CatalogHttp::class.java)
}

private val catalogHttp by lazy {
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(4, TimeUnit.SECONDS).dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return Dns.SYSTEM.lookup(hostname).filterNot { address ->
                    address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
                        address.isMulticastAddress || (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
                }.ifEmpty { throw UnknownHostException(hostname) }
            }
        }).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "PeerIche/Android").build())
        }.build()
    Retrofit.Builder().baseUrl("https://api.fedidb.org/").client(client).build().create(CatalogHttp::class.java)
}

internal suspend fun fastestCatalogHost(hosts: List<String>, probe: suspend (String) -> Long?): String? = coroutineScope {
    val semaphore = Semaphore(8)
    hosts.map { host ->
        async {
            semaphore.withPermit {
                val duration = probe(host)
                duration?.let { host to it }
            }
        }
    }.awaitAll().filterNotNull().minByOrNull { it.second }?.first
}

internal suspend fun chooseCatalogServer(hosts: List<String>): String? {
    val selected = fastestCatalogHost(hosts.shuffled().take(32)) { host ->
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            val videos = withContext(Dispatchers.IO) {
                catalogHttp.download("https://$host/api/v1/videos?count=1&nsfw=false").use { body ->
                    parsePeerTubeVideoPage(body.byteStream().readBytesUpTo(128 * 1024).toString(Charsets.UTF_8))
                }
            }
            if (videos.data.any { it.uuid.isNotBlank() }) android.os.SystemClock.elapsedRealtime() - start else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    return selected?.let { "https://$it" }
}
