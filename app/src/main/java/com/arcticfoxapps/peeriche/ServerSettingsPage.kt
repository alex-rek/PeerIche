package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class ServerProbe(val online: Boolean, val videos: Int)

@Composable internal fun ServerSettingsPage(options: AppOptions, onBrowseServer: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences by remember(context) { context.peerIcheDataStore.data.catch { emit(emptyPreferences()) } }.collectAsState(emptyPreferences())
    val configured = preferences[stringPreferencesKey("servers")]?.lines()?.mapNotNull { runCatching { normalizeServer(it) }.getOrNull() }.orEmpty().distinct()
    val active = preferences[stringPreferencesKey("activeServer")]?.takeIf { it in configured }.orEmpty()
    val bundled = bundledCatalog(context)
    val catalog = preferences[catalogKey]
    val hosts = remember(catalog, bundled) { catalog?.let { catalogHosts(it.lineSequence()) }?.takeIf { it.isNotEmpty() } ?: bundled }
    var catalogPartial by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf(false) }
    val cache = remember { context.getSharedPreferences("server_directory", 0) }
    var directory by remember { mutableStateOf(runCatching {
        Gson().fromJson<List<PeerTubeInstance>>(cache.getString("instances", "[]"), object : TypeToken<List<PeerTubeInstance>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())) }
    var probes by remember { mutableStateOf(runCatching {
        Gson().fromJson<Map<String, ServerProbe>>(cache.getString("probes", "{}"), object : TypeToken<Map<String, ServerProbe>>() {}.type) ?: emptyMap()
    }.getOrDefault(emptyMap())) }
    var address by rememberSaveable { mutableStateOf("") }
    var loadedQuery by remember { mutableStateOf(cache.getString("query", "").orEmpty()) }
    var query by rememberSaveable { mutableStateOf(loadedQuery) }
    var sort by rememberSaveable { mutableStateOf("connected") }
    var busy by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }
    var incompatible by remember { mutableStateOf(false) }
    var checked by remember { mutableIntStateOf(0) }
    var nextPage by remember { mutableIntStateOf(directory.size) }
    var hasMore by remember { mutableStateOf(true) }
    val all = (hosts.map { PeerTubeInstance(host = it) } + configured.map { PeerTubeInstance(host = java.net.URI(it).authority.orEmpty()) } + directory)
        .groupBy { it.host }.map { (_, values) -> values.last() }
        .filter { row -> row.host.contains(query, true) || row.name.orEmpty().contains(query, true) }
    val sorted = when (sort) {
        "active" -> all.sortedWith(compareByDescending<PeerTubeInstance> { probes[it.host]?.online == true }
            .thenByDescending { options.country.isNotBlank() && it.country.equals(options.country, true) }.thenBy { it.host })
        "videos" -> all.sortedByDescending { probes[it.host]?.videos?.toLong() ?: it.totalVideos ?: it.stats?.totalVideos ?: -1 }
        else -> all.sortedWith(compareByDescending<PeerTubeInstance> { "https://${it.host}" == active }
            .thenByDescending { "https://${it.host}" in configured }
            .thenByDescending { options.country.isNotBlank() && it.country.equals(options.country, true) }.thenBy { it.host })
    }
    val checkable = sorted.take(50)
    fun connect(raw: String) {
        scope.launch {
            busy = raw; failure = false; incompatible = false
            try {
                val normalized = normalizeServer(raw)
                val page = withTimeout(12_000) { PeerTubeClients.checkServer(normalized) }
                val host = java.net.URI(normalized).authority.orEmpty()
                probes = probes + (host to ServerProbe(true, page.total))
                saveServerList(context, (configured + normalized).distinct().joinToString("\n"), normalized)
                address = ""
                cache.edit().putString("probes", Gson().toJson(probes)).apply()
            } catch (_: NotPeerTubeServerException) { failure = true; incompatible = true }
            catch (timeout: TimeoutCancellationException) { failure = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = true }
            finally { busy = null }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Column(Modifier.padding(24.dp)) {
            OutlinedTextField(address, { address = it; failure = false }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(uiText(R.string.ui_https_server_address_102d29)) }, isError = failure)
            FilledTonalButton(enabled = address.isNotBlank() && busy == null, onClick = { connect(address) }) { Text(uiText(R.string.ui_check_and_connect_a891d1)) }
            if (failure) Text(if (incompatible) uiText(R.string.ui_this_website_does_not_support_the_peertube_3f0625) else uiText(R.string.ui_connection_failed_check_the_https_address_f92ec9), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), singleLine = true,
                label = { Text(uiText(R.string.ui_find_by_name_or_address_b99f1e)) })
            TextButton(enabled = busy == null, onClick = {
                scope.launch {
                    busy = "catalog"; catalogError = false; catalogPartial = false
                    try { catalogPartial = refreshServerCatalog(context, hosts).partial }
                    catch (_: TimeoutCancellationException) { catalogError = true }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { catalogError = true }
                    finally { busy = null }
                }
            }) { Text(uiText(R.string.server_catalog_refresh)) }
            Text(uiText(R.string.server_catalog_count, hosts.size))
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton(onClick = { uriHandler.openUri("https://fedidb.com/datasets") }) { Text("PeerTube + FediDB · CC BY 4.0") }
            if (catalogPartial) Text(uiText(R.string.server_catalog_partial))
            if (catalogError) Text(uiText(R.string.server_catalog_failed), color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = busy == null, onClick = {
                    scope.launch {
                        busy = "directory"; failure = false
                        try {
                            val searching = query.trim()
                            val result = PeerTubeClients.discoverInstancePage(0, searching)
                            loadedQuery = searching
                            directory = result.data.filter { it.host.isNotBlank() }
                            nextPage = result.data.size
                            hasMore = result.data.isNotEmpty() && (result.total == 0 || nextPage < result.total)
                            cache.edit().putString("instances", Gson().toJson(directory)).putString("query", loadedQuery).apply()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failure = true }
                        finally { busy = null }
                    }
                }) { Text(uiText(R.string.ui_discover_servers_f44c73)) }
                TextButton(enabled = busy == null && checkable.isNotEmpty(), onClick = {
                    scope.launch {
                        busy = "check"; checked = 0
                        try {
                            val semaphore = Semaphore(5)
                            checkable.map { row -> async { semaphore.withPermit {
                                val probe = try {
                                    val page = withTimeout(8_000) { PeerTubeClients.checkServer("https://${row.host}") }
                                    ServerProbe(true, page.total)
                                } catch (timeout: TimeoutCancellationException) { ServerProbe(false, 0) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { ServerProbe(false, 0) }
                                probes = probes + (row.host to probe)
                                checked++
                            } } }.awaitAll()
                            cache.edit().putString("probes", Gson().toJson(probes)).apply()
                        } finally { busy = null }
                    }
                }) { Text(uiText(R.string.ui_check_availability_db0909) + " (${checkable.size})") }
            }
            if (busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (busy == "check") Text("$checked / ${checkable.size}")
            }
        } }
        item { SettingsChoice(uiText(R.string.ui_sort_servers_f2aae1), sort,
            listOf("connected" to uiText(R.string.ui_connected_2fcbe2), "active" to uiText(R.string.ui_online_first_fc7de2), "videos" to uiText(R.string.ui_video_count_1e68ed))) { sort = it } }
        items(sorted, key = { it.host }) { instance ->
            val url = "https://${instance.host}"
            val state = probes[instance.host]
            val count = state?.videos?.toLong() ?: instance.totalVideos ?: instance.stats?.totalVideos
            SettingsRow(instance.name?.takeIf(String::isNotBlank) ?: instance.host,
                instance.host + " · " + when (state?.online) {
                    true -> uiText(R.string.ui_online_5fb239)
                    false -> uiText(R.string.ui_unavailable_abcd43)
                    null -> uiText(R.string.ui_not_checked_30ef8c)
                } + (count?.let { " · $it ${uiText(R.string.ui_videos_89ea5c)}" } ?: ""), onClick = { onBrowseServer(url) }) {
                Column {
                    TextButton(enabled = busy == null, onClick = { connect(url) }) { Text(if (url == active) uiText(R.string.ui_selected_af7b83) else uiText(R.string.ui_connect_53bb39)) }
                    if (url in configured) TextButton(enabled = busy == null, onClick = {
                        scope.launch {
                            val remaining = configured - url
                            saveServerList(context, remaining.joinToString("\n"), if (active == url) "" else active)
                        }
                    }) { Text(uiText(R.string.ui_remove_32d0b3)) }
                }
            }
        }
        if (hasMore && directory.isNotEmpty()) item {
            TextButton(enabled = busy == null, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    busy = "directory"; failure = false
                    try {
                        val result = PeerTubeClients.discoverInstancePage(nextPage, loadedQuery)
                        nextPage += result.data.size
                        directory = (directory + result.data).distinctBy { it.host }
                        hasMore = result.data.isNotEmpty() && (result.total == 0 || nextPage < result.total)
                        cache.edit().putString("instances", Gson().toJson(directory)).apply()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failure = true }
                    finally { busy = null }
                }
            }) { Text(uiText(R.string.ui_more_servers_7b0fae)) }
        }
    }
}
