package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable internal fun ServerSettingsPage(options: AppOptions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences by remember(context) { context.peerIcheDataStore.data.catch { emit(emptyPreferences()) } }.collectAsState(emptyPreferences())
    val configured = preferences[stringPreferencesKey("servers")]?.lines()?.filter(String::isNotBlank).orEmpty().ifEmpty { listOf("https://peertube.tv") }
    val active = preferences[stringPreferencesKey("activeServer")]?.takeIf { it in configured } ?: configured.first()
    val cache = remember { context.getSharedPreferences("server_directory", 0) }
    var directory by remember { mutableStateOf(runCatching {
        Gson().fromJson<List<PeerTubeInstance>>(cache.getString("instances", "[]"), object : TypeToken<List<PeerTubeInstance>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())) }
    var probes by remember { mutableStateOf(runCatching {
        Gson().fromJson<Map<String, ServerProbe>>(cache.getString("probes", "{}"), object : TypeToken<Map<String, ServerProbe>>() {}.type) ?: emptyMap()
    }.getOrDefault(emptyMap())) }
    var address by remember { mutableStateOf("") }
    var loadedQuery by remember { mutableStateOf(cache.getString("query", "").orEmpty()) }
    var query by remember { mutableStateOf(loadedQuery) }
    var sort by remember { mutableStateOf("connected") }
    var busy by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }
    var incompatible by remember { mutableStateOf(false) }
    var checked by remember { mutableIntStateOf(0) }
    var nextPage by remember { mutableIntStateOf(directory.size) }
    var hasMore by remember { mutableStateOf(true) }
    val all = (configured.map { PeerTubeInstance(host = java.net.URI(it).authority.orEmpty()) } + directory)
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
                TextButton(enabled = busy == null && all.isNotEmpty(), onClick = {
                    scope.launch {
                        busy = "check"; checked = 0
                        try {
                            val semaphore = Semaphore(5)
                            all.map { row -> async { semaphore.withPermit {
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
                }) { Text(uiText(R.string.ui_check_availability_db0909)) }
            }
            if (busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (busy == "check") Text("$checked / ${all.size}")
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
                } + (count?.let { " · $it ${uiText(R.string.ui_videos_89ea5c)}" } ?: "")) {
                Column {
                    TextButton(enabled = busy == null, onClick = { connect(url) }) { Text(if (url == active) uiText(R.string.ui_selected_af7b83) else uiText(R.string.ui_connect_53bb39)) }
                    if (url in configured && configured.size > 1) TextButton(enabled = busy == null, onClick = {
                        scope.launch {
                            val remaining = configured - url
                            saveServerList(context, remaining.joinToString("\n"), if (active == url) remaining.first() else active)
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
