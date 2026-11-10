package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class LiveSchedulingTest {
    private fun json(value: String) = Gson().fromJson(value, JsonObject::class.java)
    @Test fun schedulesUseUtcAndAcceptApiListAndDetailShapes() {
        val time = 1893456000123L
        assertEquals("2030-01-01T00:00:00.123Z", liveSchedules(time)[0].asJsonObject.get("startAt").asString)
        assertEquals(time, scheduledLiveTime(json("""{"liveSchedules":[{"startAt":"2030-01-01T00:00:00.123Z"}]}""")))
        assertEquals(time - 123, scheduledLiveTime(json("""{"schedules":[{"startAt":"2030-01-01T00:00:00Z"}]}""")))
        assertNull(scheduledLiveTime(json("""{"schedules":[]}""")))
        assertNull(serverTimestamp("2030-99-99T00:00:00Z"))
    }
    @Test fun schedulingRequiresFutureTimeAndSupportedVersion() {
        for (version in listOf("7.3.0", "7.3.0-rc.1", "8.0.0", "10.1.0")) assertTrue(version, supportsLiveSchedules(version))
        for (version in listOf("", "7.2.9", "6.3.0", "invalid")) assertFalse(version, supportsLiveSchedules(version))
        validateLiveSchedule(null, 1000); validateLiveSchedule(1001, 1000)
        try { validateLiveSchedule(1000, 1000); fail() } catch (error: AuthFailure) { assertEquals("schedule_past", error.reason) }
    }
    @Test fun importSourceMetadataIsOptionalButSuppliedValuesRemainValidated() {
        val data = importMetadata(CreatorMetadata("", "", 7, 3))
        assertFalse(data.has("name")); assertFalse(data.has("description")); assertEquals(3, data.get("privacy").asInt)
        assertEquals("My title", importMetadata(CreatorMetadata("My title", "My description", 7, 2)).get("name").asString)
        try { importMetadata(CreatorMetadata("ab", "", 7, 3)); fail() } catch (error: AuthFailure) { assertEquals("invalid_publication", error.reason) }
        assertNull(importedUuid(json("""{"id":1,"targetUrl":"https://media.example/video.mp4","state":{"id":1}}""")))
        try { importedUuid(json("{}")); fail() } catch (error: AuthFailure) { assertEquals("invalid_response", error.reason) }
    }
}
