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
            busy = raw; failure = false
            try {
                val normalized = normalizeServer(raw)
                val page = withTimeout(12_000) { PeerTubeClients.server(normalized).videos(count = 1) }
                val host = java.net.URI(normalized).authority.orEmpty()
                probes = probes + (host to ServerProbe(true, page.total))
                saveServerList(context, (configured + normalized).distinct().joinToString("\n"), normalized)
                address = ""
                cache.edit().putString("probes", Gson().toJson(probes)).apply()
            } catch (timeout: TimeoutCancellationException) { failure = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = true }
            finally { busy = null }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Column(Modifier.padding(24.dp)) {
            OutlinedTextField(address, { address = it; failure = false }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(uiText("HTTPS-адрес сервера", "HTTPS server address")) }, isError = failure)
            FilledTonalButton(enabled = address.isNotBlank() && busy == null, onClick = { connect(address) }) { Text(uiText("Проверить и подключить", "Check and connect")) }
            if (failure) Text(uiText("Подключение не удалось. Проверьте HTTPS-адрес, сеть и доступность PeerTube API. Предыдущий сервер сохранён.", "Connection failed. Check the HTTPS address, network and PeerTube API. Your previous server is retained."), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), singleLine = true,
                label = { Text(uiText("Поиск по названию или адресу", "Find by name or address")) })
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
                }) { Text(uiText("Найти серверы", "Discover servers")) }
                TextButton(enabled = busy == null && all.isNotEmpty(), onClick = {
                    scope.launch {
                        busy = "check"; checked = 0
                        try {
                            val semaphore = Semaphore(5)
                            all.map { row -> async { semaphore.withPermit {
                                val probe = try {
                                    val page = withTimeout(8_000) { PeerTubeClients.server("https://${row.host}").videos(count = 1) }
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
                }) { Text(uiText("Проверить доступность", "Check availability")) }
            }
            if (busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (busy == "check") Text("$checked / ${all.size}")
            }
        } }
        item { SettingsChoice(uiText("Сортировать серверы", "Sort servers"), sort,
            listOf("connected" to uiText("Подключённые", "Connected"), "active" to uiText("Сначала активные", "Online first"), "videos" to uiText("По количеству видео", "Video count"))) { sort = it } }
        items(sorted, key = { it.host }) { instance ->
            val url = "https://${instance.host}"
            val state = probes[instance.host]
            val count = state?.videos?.toLong() ?: instance.totalVideos ?: instance.stats?.totalVideos
            SettingsRow(instance.name?.takeIf(String::isNotBlank) ?: instance.host,
                instance.host + " · " + when (state?.online) {
                    true -> uiText("активен", "online")
                    false -> uiText("недоступен", "unavailable")
                    null -> uiText("не проверен", "not checked")
                } + (count?.let { " · $it ${uiText("видео", "videos")}" } ?: "")) {
                Column {
                    TextButton(enabled = busy == null, onClick = { connect(url) }) { Text(if (url == active) uiText("Выбран", "Selected") else uiText("Подключить", "Connect")) }
                    if (url in configured && configured.size > 1) TextButton(enabled = busy == null, onClick = {
                        scope.launch {
                            val remaining = configured - url
                            saveServerList(context, remaining.joinToString("\n"), if (active == url) remaining.first() else active)
                        }
                    }) { Text(uiText("Убрать", "Remove")) }
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
            }) { Text(uiText("Ещё серверы", "More servers")) }
        }
    }
}
