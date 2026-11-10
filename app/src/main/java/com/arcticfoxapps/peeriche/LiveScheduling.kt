package com.arcticfoxapps.peeriche

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun supportsLiveSchedules(version: String): Boolean {
    val parts = Regex("^(\\d+)\\.(\\d+)").find(version)?.groupValues ?: return false
    val major = parts[1].toIntOrNull() ?: return false
    val minor = parts[2].toIntOrNull() ?: return false
    return major > 7 || major == 7 && minor >= 3
}
internal fun validateLiveSchedule(time: Long?, now: Long = System.currentTimeMillis()) {
    if (time != null && time <= now) throw AuthFailure("schedule_past")
}
internal fun liveSchedules(time: Long?): JsonArray = JsonArray().apply {
    time?.let {
        add(JsonObject().apply { addProperty("startAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it))) })
    }
}
internal fun serverTimestamp(value: String): Long? = runCatching {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?Z").matches(value)) return null
    SimpleDateFormat(if (value.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(value)?.time
}.getOrNull()
internal fun scheduledLiveTime(raw: JsonObject): Long? = runCatching {
    (raw.getAsJsonArray("schedules") ?: raw.getAsJsonArray("liveSchedules"))?.firstOrNull()?.asJsonObject?.stringAt("startAt")?.let(::serverTimestamp)
        ?: raw.objectAt("scheduledLive")?.stringAt("startAt")?.let(::serverTimestamp)
}.getOrNull()
internal fun scheduleLabel(time: Long): String = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(time))

@Composable internal fun LiveScheduleFields(supported: Boolean, value: Long?, onChange: (Long?) -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    if (!supported) {
        Text(uiText(R.string.live_schedule_unsupported), style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value != null, { onChange(if (it) (System.currentTimeMillis() / 60000 + 60) * 60000 else null) }, enabled = enabled)
        Text(uiText(R.string.live_schedule))
    }
    if (value != null) {
        OutlinedButton(enabled = enabled, onClick = {
            val selected = Calendar.getInstance().apply { timeInMillis = value }
            DatePickerDialog(context, { _, year, month, day ->
                TimePickerDialog(context, { _, hour, minute ->
                    selected.set(year, month, day, hour, minute, 0); selected.set(Calendar.MILLISECOND, 0)
                    onChange(selected.timeInMillis)
                }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
            }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = System.currentTimeMillis(); show()
            }
        }) { Text(scheduleLabel(value)) }
        Text(TimeZone.getDefault().id + " · " + uiText(R.string.live_schedule_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun EditLiveSchedule(account: String, uuid: String, repository: CreatorRepository, onSaved: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    var supported by remember { mutableStateOf(false) }; var time by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(account, uuid) {
        try {
            supported = repository.capabilities(account).scheduledLives
            time = repository.liveSchedule(account, uuid); ready = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = accountErrorCode(failure) }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(uiText(R.string.live_schedule_edit)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ready || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ready) LiveScheduleFields(supported, time, { time = it }, !busy)
            error?.let { Text(creatorErrorText(it), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = ready && supported && !busy, onClick = {
            busy = true; error = null
            scope.launch {
                try { repository.updateLiveSchedule(account, uuid, time); onSaved() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { error = accountErrorCode(failure) }
                finally { busy = false }
            }
        }) { Text(uiText(R.string.ui_done_76ab9d)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(uiText(R.string.ui_close_641636)) } })
}
