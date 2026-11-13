package com.arcticfoxapps.peeriche

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal val homeContentModes = listOf("recommended", "interests", "server", "channel")
internal fun homeContentLabel(mode: String): Int = when (mode) {
    "interests" -> R.string.home_interests
    "server" -> R.string.home_server
    "channel" -> R.string.home_channel
    else -> R.string.home_recommended
}

internal fun homeChannel(value: String): PeerTubeChannel {
    val text = value.trim()
    val url = text.toHttpUrlOrNull()
    val handle = if (url != null) {
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        val segments = url.pathSegments.filter(String::isNotBlank)
        require(segments.size == 2 && segments[0] in listOf("c", "video-channels"))
        val host = url.host + if (url.port == 443) "" else ":${url.port}"
        "${segments[1]}@$host"
    } else text
    val valid = validSubscriptionHandle(handle)
    return PeerTubeChannel(name = valid.substringBeforeLast('@'), host = valid.substringAfterLast('@'))
}

internal suspend fun loadConfiguredHome(options: AppOptions, activeServer: String): List<Video> = when (options.homeContent) {
    "server" -> PeerTubeClients.serverPage(normalizeServer(options.homeServer), 0, options.includeSensitive).videos
    "channel" -> homeChannel(options.homeChannel).let { channel ->
        val origin = "https://${channel.host}"
        PeerTubeClients.server(origin).channelVideos(channel.name).data.filter { options.includeSensitive || !it.nsfw }.map { it.copy(host = it.host ?: channel.host) }
    }
    "interests" -> {
        require(options.homeInterests.isNotEmpty())
        PeerTubeClients.interestVideos(activeServer, options.homeInterests, options.includeSensitive)
    }
    else -> emptyList()
}

@Composable internal fun HomeContentDialog(options: AppOptions, preferences: AppPreferences, onClose: () -> Unit) {
    var mode by remember { mutableStateOf(options.homeContent) }
    var interests by remember { mutableStateOf(options.homeInterests.toSet()) }
    var server by remember { mutableStateOf(options.homeServer) }
    var channel by remember { mutableStateOf(options.homeChannel) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(uiText(R.string.home_content)) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            homeContentModes.forEach { value ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy) { mode = value }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(mode == value, onClick = null)
                    Text(uiText(homeContentLabel(value)))
                }
            }
            when (mode) {
                "interests" -> (1..19).forEach { id ->
                    val label = interestLabel(id)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy) { interests = if (id in interests) interests - id else interests + id }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(id in interests, onCheckedChange = null)
                        Text(label)
                    }
                }
                "server" -> OutlinedTextField(server, { server = it }, enabled = !busy, label = { Text(uiText(R.string.ui_https_server_address_102d29)) }, singleLine = true)
                "channel" -> OutlinedTextField(channel, { channel = it }, enabled = !busy, label = { Text(uiText(R.string.home_channel_address)) }, placeholder = { Text("channel@example.org") }, singleLine = true)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(uiText(R.string.home_source_invalid), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !busy && (mode != "interests" || interests.isNotEmpty()), onClick = {
            busy = true; failed = false
            scope.launch {
                try {
                    if (mode == "server") PeerTubeClients.checkServer(normalizeServer(server))
                    if (mode == "channel") homeChannel(channel).let { PeerTubeClients.server("https://${it.host}").channel(it.name) }
                    preferences.update { it.copy(homeContent = mode, homeInterests = interests.sorted(), homeServer = server.trim(), homeChannel = channel.trim()) }
                    onClose()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(uiText(R.string.ui_done_76ab9d)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(uiText(R.string.ui_cancel_28fb55)) } })
}

@Composable private fun interestLabel(id: Int): String {
    val labels = listOf(R.string.interest_music, R.string.interest_films, R.string.interest_vehicles, R.string.interest_art,
        R.string.interest_sports, R.string.interest_travel, R.string.interest_gaming, R.string.interest_people,
        R.string.interest_comedy, R.string.interest_entertainment, R.string.interest_news, R.string.interest_howto,
        R.string.interest_education, R.string.interest_activism, R.string.interest_science, R.string.interest_animals,
        R.string.interest_kids, R.string.interest_food, R.string.interest_nature)
    return uiText(labels[id - 1])
}
