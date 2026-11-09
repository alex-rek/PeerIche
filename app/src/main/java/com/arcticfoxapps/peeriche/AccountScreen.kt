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
    "account_changed" -> uiText(R.string.ui_the_account_changed_retry_using_the_intend_338ec1)
    "account_video_unavailable" -> uiText(R.string.ui_the_account_server_could_not_find_this_vid_90b052)
    "account_resource_missing" -> uiText(R.string.ui_the_video_channel_or_comment_was_not_found_56ee2c)
    "invalid_channel" -> uiText(R.string.ui_could_not_determine_the_channel_address_b19cce)
    "invalid_comment" -> uiText(R.string.ui_a_comment_must_contain_1_to_10_000_charact_1bbc71)
    "comment_delivery_unknown" -> uiText(R.string.ui_delivery_could_not_be_confirmed_refresh_co_ddf9ab)
    "invalid_server" -> uiText(R.string.ui_enter_a_peertube_https_server_address_with_f7f124)
    "missing_credentials" -> uiText(R.string.ui_enter_a_username_and_password_d3c026)
    "missing_two_factor" -> uiText(R.string.ui_enter_your_two_factor_code_and_sign_in_aga_11ed14)
    "invalid_two_factor" -> uiText(R.string.ui_the_two_factor_code_was_rejected_enter_a_n_e749c0)
    "invalid_grant" -> uiText(R.string.ui_sign_in_failed_check_your_username_passwor_23a85e)
    "session_expired", "invalid_token" -> uiText(R.string.ui_the_session_expired_or_was_revoked_sign_in_2b6a69)
    "account_blocked" -> uiText(R.string.ui_the_account_is_blocked_on_the_server_05ab34)
    "email_not_verified" -> uiText(R.string.ui_verify_your_email_address_on_the_server_we_eb9f69)
    "account_waiting_for_approval" -> uiText(R.string.ui_the_account_is_waiting_for_server_administ_f422c2)
    "account_approval_rejected" -> uiText(R.string.ui_the_server_rejected_this_account_registrat_1f9f3b)
    "rate_limited" -> uiText(R.string.ui_too_many_attempts_wait_before_trying_again_a0ffae)
    "too_long_password" -> uiText(R.string.ui_the_server_does_not_accept_this_password_l_96ca66)
    "certificate" -> uiText(R.string.ui_could_not_verify_the_secure_server_connect_1a1995)
    "redirect" -> uiText(R.string.ui_the_server_redirects_this_request_enter_it_d8d57d)
    "not_peertube", "invalid_response" -> uiText(R.string.ui_this_address_does_not_provide_a_compatible_702d33)
    "invalid_client" -> uiText(R.string.ui_the_server_changed_its_sign_in_configurati_bcd296)
    "forbidden" -> uiText(R.string.ui_the_server_denied_this_action_check_your_a_d471c9)
    "storage_error" -> uiText(R.string.ui_could_not_open_or_save_protected_sign_in_d_f64e67)
    "account_limit" -> uiText(R.string.ui_up_to_20_accounts_can_be_saved_remove_an_u_32727c)
    "local_logout" -> uiText(R.string.ui_sign_in_was_removed_from_this_device_the_s_7a170f)
    "server_error" -> uiText(R.string.ui_server_error_try_again_later_c789a0)
    "request_failed" -> uiText(R.string.ui_the_server_rejected_the_request_check_the_afc910)
    else -> uiText(R.string.ui_the_server_is_unavailable_check_your_netwo_c773e7)
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
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText(R.string.ui_back_02b74f)) }
                    Text(uiText(R.string.ui_peertube_accounts_8d748a), style = MaterialTheme.typography.titleLarge)
                }
                if (busy || !state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f).align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.storageError) {
                        Text(accountErrorText("storage_error"), color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = !busy, onClick = { action { manager.initialize() } }) { Text(uiText(R.string.ui_retry_e8368b)) }
                        TextButton(enabled = !busy, onClick = { confirmReset = true }) { Text(uiText(R.string.ui_remove_saved_sign_ins_824597)) }
                    }
                    state.accounts.forEach { account ->
                        Surface(shape = MaterialTheme.shapes.medium, color = if (account.id == state.activeId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(account.displayName, fontWeight = FontWeight.SemiBold)
                                Text("${account.username}@${java.net.URI(account.server).authority}", style = MaterialTheme.typography.bodyMedium)
                                Text(if (account.needsLogin) uiText(R.string.ui_sign_in_again_bd741c) else if (account.id == state.activeId) uiText(R.string.ui_active_account_7ead88) else uiText(R.string.ui_saved_account_46c390), style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(enabled = !busy, onClick = {
                                        if (account.needsLogin) { server = account.server; username = account.username; password = ""; otp = ""; form = true; error = null }
                                        else action { manager.activate(account.id); onUseServer(account.server); manager.refreshProfile(account.id) }
                                    }) { Text(if (account.needsLogin) uiText(R.string.ui_sign_in_again_75f938) else uiText(R.string.ui_use_account_a6c379)) }
                                    TextButton(enabled = !busy, onClick = { action {
                                        if (!manager.logout(account.id)) error = "local_logout"
                                    } }) { Text(uiText(R.string.ui_sign_out_2f12bc)) }
                                }
                            }
                        }
                    }
                    val active = state.active
                    if (active != null && !active.needsLogin) {
                        if (active.server != selectedServer) Text(uiText(R.string.ui_account_server_mismatch, selectedServer, active.server), style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = !busy, onClick = { action { manager.refreshProfile(active.id) } }) { Text(uiText(R.string.ui_check_session_5531bc)) }
                    }
                    if (state.accounts.isNotEmpty() && !form) OutlinedButton(enabled = !busy && !state.storageError, onClick = {
                        form = true; server = selectedServer; username = ""; password = ""; otp = ""; showOtp = false; error = null
                    }) { Text(uiText(R.string.ui_add_account_240e92)) }
                    if ((form || state.accounts.isEmpty()) && state.ready && !state.storageError) {
                        Text(uiText(R.string.ui_sign_in_to_a_server_85e839), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                        OutlinedTextField(server, { server = it; error = null }, enabled = !busy, singleLine = true,
                            label = { Text(uiText(R.string.ui_peertube_server_f36de6)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(username, { username = it; error = null }, enabled = !busy, singleLine = true,
                            label = { Text(uiText(R.string.ui_username_8f11d1)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it; error = null }, enabled = !busy, singleLine = true,
                            label = { Text(uiText(R.string.ui_password_367eb8)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, uiText(R.string.ui_show_or_hide_password_51b918)) } }, modifier = Modifier.fillMaxWidth())
                        if (showOtp) OutlinedTextField(otp, { otp = it; error = null }, enabled = !busy, singleLine = true,
                            label = { Text(uiText(R.string.ui_two_factor_code_2d51c2)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                        else TextButton(enabled = !busy, onClick = { showOtp = true }) { Text(uiText(R.string.ui_i_use_two_factor_authentication_68d856)) }
                        Button(enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), onClick = { action {
                            val account = manager.login(server, username, password, otp)
                            password = ""; otp = ""; showOtp = false; form = false
                            onUseServer(account.server)
                        } }, modifier = Modifier.fillMaxWidth()) { Text(uiText(if (busy) R.string.ui_signing_in_34c5a1 else R.string.ui_sign_in_f39424)) }
                        Text(uiText(R.string.ui_use_your_account_s_username_on_this_server_2200cb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = !busy, onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authServerOrigin(server) + "/login"))) }
                            catch (failure: Exception) { error = if (failure is AuthFailure) failure.reason else "network" }
                        }) { Text(uiText(R.string.ui_open_server_website_5cc079)) }
                    }
                    error?.let { Text(accountErrorText(it), color = if (it == "local_logout") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                    Text(uiText(R.string.ui_account_subscriptions_come_from_its_server_4cb70b), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text(uiText(R.string.ui_remove_saved_sign_ins_812055)) },
            text = { Text(uiText(R.string.ui_only_sign_in_data_on_this_device_will_be_r_167f36)) },
            confirmButton = { TextButton(onClick = { confirmReset = false; action { manager.resetStorage() } }) { Text(uiText(R.string.ui_remove_ffcdea)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(uiText(R.string.ui_cancel_28fb55)) } })
    }
}
