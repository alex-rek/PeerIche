package com.arcticfoxapps.peeriche

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.*

internal fun accountErrorCode(error: Exception): String = when (error) {
    is AuthFailure -> error.reason
    is javax.net.ssl.SSLException -> "certificate"
    else -> "network"
}

@Composable internal fun accountErrorText(reason: String): String = when (reason) {
    "account_changed" -> uiText("Аккаунт изменился. Повторите действие от имени нужного аккаунта.", "The account changed. Retry using the intended account.")
    "account_video_unavailable" -> uiText("Сервер аккаунта не нашёл это видео. Проверьте доступность ролика на нём и повторите попытку.", "The account server could not find this video. Check its availability there and retry.")
    "account_resource_missing" -> uiText("Видео, канал или комментарий не найдены на сервере аккаунта.", "The video, channel or comment was not found on the account server.")
    "invalid_channel" -> uiText("Не удалось определить адрес канала.", "Could not determine the channel address.")
    "invalid_comment" -> uiText("Комментарий должен содержать от 1 до 10 000 символов.", "A comment must contain 1 to 10,000 characters.")
    "comment_delivery_unknown" -> uiText("Не удалось подтвердить отправку. Обновите комментарии перед повторной отправкой.", "Delivery could not be confirmed. Refresh comments before sending again.")
    "invalid_server" -> uiText("Укажите адрес HTTPS-сервера PeerTube без пути, параметров и данных входа в адресе.", "Enter a PeerTube HTTPS server address without a path, query or credentials.")
    "missing_credentials" -> uiText("Введите имя пользователя и пароль.", "Enter a username and password.")
    "missing_two_factor" -> uiText("Введите код двухфакторной защиты и повторите вход.", "Enter your two-factor code and sign in again.")
    "invalid_two_factor" -> uiText("Код двухфакторной защиты не подошёл. Введите новый код.", "The two-factor code was rejected. Enter a new code.")
    "invalid_grant" -> uiText("Не удалось войти. Проверьте имя пользователя, пароль и выбранный сервер.", "Sign-in failed. Check your username, password and server.")
    "session_expired", "invalid_token" -> uiText("Сессия завершена или отозвана. Войдите снова.", "The session expired or was revoked. Sign in again.")
    "account_blocked" -> uiText("Аккаунт заблокирован на сервере.", "The account is blocked on the server.")
    "email_not_verified" -> uiText("Подтвердите адрес почты на сайте сервера.", "Verify your email address on the server website.")
    "account_waiting_for_approval" -> uiText("Аккаунт ожидает одобрения администратора сервера.", "The account is waiting for server administrator approval.")
    "account_approval_rejected" -> uiText("Сервер отклонил заявку на регистрацию.", "The server rejected this account registration.")
    "rate_limited" -> uiText("Слишком много попыток. Подождите перед повторным входом.", "Too many attempts. Wait before trying again.")
    "too_long_password" -> uiText("Сервер не принимает пароль такой длины.", "The server does not accept this password length.")
    "certificate" -> uiText("Не удалось проверить защищённое соединение с сервером. Проверьте адрес и дату устройства.", "Could not verify the secure server connection. Check the address and device date.")
    "redirect" -> uiText("Сервер перенаправляет запрос. Укажите его окончательный HTTPS-адрес; данные входа не пересылались.", "The server redirects this request. Enter its final HTTPS address; credentials were not forwarded.")
    "not_peertube", "invalid_response" -> uiText("Адрес не предоставляет совместимый API входа PeerTube.", "This address does not provide a compatible PeerTube sign-in API.")
    "invalid_client" -> uiText("Сервер изменил параметры входа. Войдите в аккаунт снова.", "The server changed its sign-in configuration. Sign in again.")
    "forbidden" -> uiText("Сервер запретил это действие. Проверьте состояние аккаунта на его сайте.", "The server denied this action. Check your account on its website.")
    "storage_error" -> uiText("Не удалось открыть или сохранить защищённые данные входа. Повторите попытку; при необходимости удалите сохранённые входы.", "Could not open or save protected sign-in data. Retry, or remove stored sign-ins if needed.")
    "account_limit" -> uiText("Можно сохранить до 20 аккаунтов. Удалите ненужный вход.", "Up to 20 accounts can be saved. Remove an unused sign-in.")
    "local_logout" -> uiText("Вход удалён на этом устройстве. Завершить сессию на сервере не удалось; её можно отозвать на сайте PeerTube.", "Sign-in was removed from this device. The server session could not be revoked; you can revoke it on the PeerTube website.")
    "server_error" -> uiText("Ошибка сервера. Попробуйте позже.", "Server error. Try again later.")
    "request_failed" -> uiText("Сервер отклонил запрос. Проверьте данные и разрешения аккаунта.", "The server rejected the request. Check the data and account permissions.")
    else -> uiText("Сервер недоступен. Проверьте подключение к сети и повторите попытку.", "The server is unavailable. Check your network and retry.")
}

@Composable internal fun AccountScreen(selectedServer: String, manager: AccountSessionManager,
    onUseServer: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var form by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(selectedServer) }
    var username by rememberSaveable { mutableStateOf("") }
    // Credentials deliberately never enter saved instance state or preferences.
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var showOtp by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        job = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = accountErrorCode(failure)
                if (error in listOf("missing_two_factor", "invalid_two_factor")) { showOtp = true; otp = "" }
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try { manager.initialize() } catch (failure: AuthFailure) { error = failure.reason }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel(); password = ""; otp = "" } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText("Назад", "Back")) }
                    Text(uiText("Аккаунты PeerTube", "PeerTube accounts"), style = MaterialTheme.typography.titleLarge)
                }
                if (busy || !state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f).align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.storageError) {
                        Text(accountErrorText("storage_error"), color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = !busy, onClick = { action { manager.initialize() } }) { Text(uiText("Повторить", "Retry")) }
                        TextButton(enabled = !busy, onClick = { confirmReset = true }) { Text(uiText("Удалить сохранённые входы", "Remove saved sign-ins")) }
                    }
                    state.accounts.forEach { account ->
                        Surface(shape = MaterialTheme.shapes.medium, color = if (account.id == state.activeId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(account.displayName, fontWeight = FontWeight.SemiBold)
                                Text("${account.username}@${java.net.URI(account.server).authority}", style = MaterialTheme.typography.bodyMedium)
                                Text(if (account.needsLogin) uiText("Требуется повторный вход", "Sign in again") else if (account.id == state.activeId) uiText("Активный аккаунт", "Active account") else uiText("Сохранённый аккаунт", "Saved account"), style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(enabled = !busy, onClick = {
                                        if (account.needsLogin) { server = account.server; username = account.username; password = ""; otp = ""; form = true; error = null }
                                        else action { manager.activate(account.id); onUseServer(account.server); manager.refreshProfile(account.id) }
                                    }) { Text(if (account.needsLogin) uiText("Войти снова", "Sign in again") else uiText("Использовать", "Use account")) }
                                    TextButton(enabled = !busy, onClick = { action {
                                        if (!manager.logout(account.id)) error = "local_logout"
                                    } }) { Text(uiText("Выйти", "Sign out")) }
                                }
                            }
                        }
                    }
                    val active = state.active
                    if (active != null && !active.needsLogin) {
                        if (active.server != selectedServer) Text(uiText("Видео сейчас с сервера $selectedServer. Аккаунт связан с ${active.server}.",
                            "Videos currently come from $selectedServer. This account belongs to ${active.server}."), style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = !busy, onClick = { action { manager.refreshProfile(active.id) } }) { Text(uiText("Проверить сессию", "Check session")) }
                    }
                    if (state.accounts.isNotEmpty() && !form) OutlinedButton(enabled = !busy && !state.storageError, onClick = {
                        form = true; server = selectedServer; username = ""; password = ""; otp = ""; showOtp = false; error = null
                    }) { Text(uiText("Добавить аккаунт", "Add account")) }
                    if ((form || state.accounts.isEmpty()) && state.ready && !state.storageError) {
                        Text(uiText("Вход на сервер", "Sign in to a server"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                        OutlinedTextField(server, { server = it; error = null }, enabled = !busy, singleLine = true,
                            label = { Text(uiText("Сервер PeerTube", "PeerTube server")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(username, { username = it; error = null }, enabled = !busy, singleLine = true,
                            label = { Text(uiText("Имя пользователя", "Username")) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it; error = null }, enabled = !busy, singleLine = true,
                            label = { Text(uiText("Пароль", "Password")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, uiText("Показать или скрыть пароль", "Show or hide password")) } }, modifier = Modifier.fillMaxWidth())
                        if (showOtp) OutlinedTextField(otp, { otp = it; error = null }, enabled = !busy, singleLine = true,
                            label = { Text(uiText("Код двухфакторной защиты", "Two-factor code")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                        else TextButton(enabled = !busy, onClick = { showOtp = true }) { Text(uiText("У меня включена двухфакторная защита", "I use two-factor authentication")) }
                        Button(enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), onClick = { action {
                            val account = manager.login(server, username, password, otp)
                            password = ""; otp = ""; showOtp = false; form = false
                            onUseServer(account.server)
                        } }, modifier = Modifier.fillMaxWidth()) { Text(uiText(if (busy) "Вход…" else "Войти", if (busy) "Signing in…" else "Sign in")) }
                        Text(uiText("Используйте имя пользователя аккаунта на этом сервере. Пароль не сохраняется. Вход через внешние сервисы (SSO) пока не подключён.",
                            "Use your account's username on this server. Your password is not saved. External provider sign-in (SSO) is not supported yet."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = !busy, onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authServerOrigin(server) + "/login"))) }
                            catch (failure: Exception) { error = if (failure is AuthFailure) failure.reason else "network" }
                        }) { Text(uiText("Открыть сайт сервера", "Open server website")) }
                    }
                    error?.let { Text(accountErrorText(it), color = if (it == "local_logout") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                    Text(uiText("Подписки аккаунта доступны с его сервера. Подписки на устройстве, избранное и история хранятся отдельно и не переносятся автоматически.",
                        "Account subscriptions come from its server. Device subscriptions, favorites and history remain separate and are not automatically transferred."), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text(uiText("Удалить сохранённые входы?", "Remove saved sign-ins?")) },
            text = { Text(uiText("Будут удалены только данные входа на этом устройстве. Избранное, история и локальные подписки останутся.", "Only sign-in data on this device will be removed. Favorites, history and local subscriptions remain.")) },
            confirmButton = { TextButton(onClick = { confirmReset = false; action { manager.resetStorage() } }) { Text(uiText("Удалить", "Remove")) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(uiText("Отмена", "Cancel")) } })
    }
}
