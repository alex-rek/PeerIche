package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

internal fun serverCatalogUiFixture(instrumentation: Instrumentation) {
    val context = instrumentation.targetContext
    val result = Bundle()
    var code = Activity.RESULT_OK
    var activity: Activity? = null
    val marker = File(context.cacheDir, "catalog-ui-finish")
    val keys = listOf(stringPreferencesKey("servers"), stringPreferencesKey("activeServer"), catalogKey)
    val previous = runBlocking { context.peerIcheDataStore.data.first() }
    try {
        check(android.os.Build.HARDWARE.contains("ranchu"))
        runBlocking {
            val accounts = PeerTubeAccounts.get(context)
            accounts.initialize()
            check(accounts.state.value.active == null) { "Use an emulator without an account" }
            context.peerIcheDataStore.edit { settings -> keys.forEach { settings.remove(it) } }
        }
        marker.delete()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runBlocking { repeat(180) { if (marker.exists()) return@runBlocking; delay(1000) } }
        result.putString("stream", "Catalog UI finished; connection settings restored\n")
    } catch (failure: Throwable) {
        code = Activity.RESULT_CANCELED
        result.putString("stream", "FAIL catalog UI: ${failure.message}\n")
    } finally {
        instrumentation.runOnMainSync { activity?.finish() }
        runBlocking {
            context.peerIcheDataStore.edit { settings ->
                keys.forEach { key ->
                    val value = previous[key]
                    if (value == null) settings.remove(key) else settings[key] = value
                }
            }
        }
        marker.delete()
    }
    instrumentation.finish(code, result)
}
