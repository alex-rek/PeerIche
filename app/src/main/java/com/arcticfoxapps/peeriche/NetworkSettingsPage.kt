package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NetworkSettingsPage() {
    val state by ProxyNetwork.state.collectAsState()
    val saved = state.config
    // Passwords stay in memory, outside saved instance state and ordinary preferences.
    var type by remember(saved) { mutableStateOf(saved.type) }
    var host by remember(saved) { mutableStateOf(saved.host) }
    var port by remember(saved) { mutableStateOf(saved.port.takeIf { it > 0 }?.toString().orEmpty()) }
    var username by remember(saved) { mutableStateOf(saved.username) }
    var password by remember(saved) { mutableStateOf(saved.password) }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var httpCode by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var activeCall by remember { mutableStateOf<Call?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { activeCall?.cancel() } }

    fun save(checkConnection: Boolean) {
        val config = try { ProxyConfiguration(type, host, port.toIntOrNull() ?: 0, username, password).checked() }
        catch (_: Exception) { status = R.string.proxy_bad_config; error = true; return }
        busy = true; status = null; httpCode = null; error = false
        scope.launch {
            try {
                ProxyNetwork.save(config)
                status = R.string.proxy_saved
                if (checkConnection) {
                    status = R.string.proxy_checking
                    val base = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()
                    val call = ProxyNetwork.newCall(base, Request.Builder().url("https://example.com/").build())
                    activeCall = call
                    val code = withContext(Dispatchers.IO) { call.execute().use { it.code } }
                    when {
                        code in 200..399 -> status = R.string.proxy_ok
                        code == 407 -> { status = R.string.proxy_auth_required; error = true }
                        else -> { status = R.string.proxy_http_status; httpCode = code; error = true }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = if (checkConnection) R.string.proxy_check_failed else R.string.proxy_save_failed; error = true }
            finally { activeCall = null; busy = false }
        }
    }
    // SettingsScreen already pads by safeDrawing, which includes the keyboard; a second imePadding left no room for fields.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(uiText(R.string.proxy_scope_note), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        if (state.storageError) Text(uiText(R.string.proxy_storage_error), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.proxy_type), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
            // All choices stay on screen: a row that opens a picker did not look tappable.
            val choices = listOf("off" to uiText(R.string.proxy_off), "http" to "HTTP", "socks5" to "SOCKS5")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { index, (key, label) ->
                    SegmentedButton(selected = type == key, onClick = { type = key; status = null }, enabled = !busy,
                        shape = SegmentedButtonDefaults.itemShape(index, choices.size)) { Text(label) }
                }
            }
            if (type != "off") ProxyFields(host, port, username, password, visible, !busy,
                { host = it; status = null }, { port = it; status = null }, { username = it; status = null },
                { password = it; status = null }, { visible = !visible })
            Button(enabled = !busy, onClick = { save(false) }) { Text(uiText(R.string.proxy_save)) }
            OutlinedButton(enabled = !busy, onClick = { save(true) }) { Text(uiText(R.string.proxy_save_check)) }
            Text(uiText(R.string.proxy_check_target), style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { message -> Text(if (message == R.string.proxy_http_status) uiText(message, httpCode ?: 0) else uiText(message),
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(enabled = !busy, onClick = { reset = true }) { Text(uiText(R.string.proxy_reset)) }
        }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text(uiText(R.string.proxy_reset_question)) },
        confirmButton = { TextButton(onClick = {
            reset = false
            scope.launch {
                try { ProxyNetwork.reset(); type = "off"; host = ""; port = ""; username = ""; password = ""; error = false; status = R.string.proxy_saved }
                catch (_: Exception) { error = true; status = R.string.proxy_save_failed }
            }
        }) { Text(uiText(R.string.proxy_reset)) } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text(uiText(R.string.ui_cancel_28fb55)) } })
}

@Composable private fun ProxyFields(host: String, port: String, username: String, password: String, visible: Boolean, enabled: Boolean,
    onHost: (String) -> Unit, onPort: (String) -> Unit, onUsername: (String) -> Unit, onPassword: (String) -> Unit, onToggleVisible: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(host, onHost, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(uiText(R.string.proxy_host)) }, placeholder = { Text("proxy.example.org") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(port, onPort, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(uiText(R.string.proxy_port)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(username, onUsername, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(uiText(R.string.proxy_username)) }, singleLine = true)
        OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(uiText(R.string.proxy_password)) }, singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = {
                IconButton(onClick = onToggleVisible) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    uiText(if (visible) R.string.proxy_hide_password else R.string.proxy_show_password)) }
            })
        Text(uiText(R.string.proxy_reopen_video), style = MaterialTheme.typography.bodySmall)
    }
}
