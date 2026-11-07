package com.arcticfoxapps.peeriche

import com.google.gson.Gson
import com.google.gson.JsonParser

internal class NotPeerTubeServerException : Exception("The address does not expose a compatible PeerTube video API")

/** A 200 HTML page or unrelated JSON must not count as a connected PeerTube server. */
internal fun parsePeerTubeVideoPage(body: String): VideoPage = try {
    val root = JsonParser.parseString(body)
    require(root.isJsonObject)
    val data = root.asJsonObject.get("data")
    val total = root.asJsonObject.get("total")
    require(data?.isJsonArray == true && total?.isJsonPrimitive == true && total.asJsonPrimitive.isNumber && total.asLong >= 0)
    require(data.asJsonArray.all { row -> row.isJsonObject &&
        row.asJsonObject.get("uuid")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() } == true &&
        row.asJsonObject.get("name")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true })
    Gson().fromJson(root, VideoPage::class.java)
} catch (_: Exception) { throw NotPeerTubeServerException() }
