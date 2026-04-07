package com.akuras.pdfscanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
)

suspend fun fetchAvailableUpdate(
    currentVersionCode: Int,
    owner: String,
    repo: String,
): AvailableUpdate? = withContext(Dispatchers.IO) {
    val endpoint = "https://api.github.com/repos/$owner/$repo/releases/latest"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "PDFScanner-Update-Check")
    }

    try {
        if (connection.responseCode !in 200..299) {
            return@withContext null
        }

        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
        val release = JSONObject(responseText)

        val tagName = release.optString("tag_name")
        val latestCode = parseVersionCodeFromTag(tagName)
            ?: parseVersionCodeFromName(release.optString("name"))
            ?: return@withContext null

        if (latestCode <= currentVersionCode) {
            return@withContext null
        }

        val downloadUrl = release.findApkAssetUrl() ?: release.optString("html_url")
        if (downloadUrl.isBlank()) {
            return@withContext null
        }

        val versionName = tagName.ifBlank { release.optString("name", "Unknown") }
        AvailableUpdate(
            versionCode = latestCode,
            versionName = versionName,
            downloadUrl = downloadUrl,
        )
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun parseVersionCodeFromTag(tag: String): Int? {
    if (tag.isBlank()) return null
    return Regex("(\\d+)$").find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private fun parseVersionCodeFromName(name: String): Int? {
    if (name.isBlank()) return null
    return Regex("(\\d+)$").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private fun JSONObject.findApkAssetUrl(): String? {
    val assets = optJSONArray("assets") ?: JSONArray()
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name")
        val browserUrl = asset.optString("browser_download_url")
        if (name.endsWith(".apk", ignoreCase = true) && browserUrl.isNotBlank()) {
            return browserUrl
        }
    }
    return null
}
