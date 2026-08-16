package com.example.vr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Online subtitle search/download via the OpenSubtitles.com REST API v2.
 * An API key (free account) is required.
 */
class OnlineSubtitleSearch {

    data class SubtitleResult(
        val fileId: Int,
        val releaseName: String,
        val language: String
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun search(apiKey: String, query: String, language: String): List<SubtitleResult> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || query.isBlank()) return@withContext emptyList()
            try {
                val url = "https://api.opensubtitles.com/api/v1/subtitles?query=" +
                    URLEncoder.encode(query, "UTF-8") + "&languages=$language"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Api-Key", apiKey)
                    .addHeader("User-Agent", "GfaceVR v1.0 (Android)")
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("SubSearch", "search HTTP ${resp.code}")
                        return@use emptyList()
                    }
                    val body = resp.body?.string() ?: return@use emptyList()
                    val root = JSONObject(body)
                    val data = root.optJSONArray("data") ?: return@use emptyList()
                    val results = mutableListOf<SubtitleResult>()
                    for (i in 0 until data.length().coerceAtMost(10)) {
                        val item = data.getJSONObject(i)
                        val id = item.optInt("id")
                        val attrs = item.optJSONObject("attributes")
                        val name = attrs?.optString("release_name") ?: "字幕"
                        val lang = attrs?.optString("language") ?: language
                        if (id > 0) results.add(SubtitleResult(id, name, lang))
                    }
                    results
                }
            } catch (e: Exception) {
                Log.e("SubSearch", "search failed", e)
                emptyList()
            }
        }

    /** Downloads the subtitle file (srt directly or inside a zip) to outFile. */
    suspend fun download(apiKey: String, fileId: Int, outFile: File): File? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("file_id", fileId).toString()
                val req = Request.Builder()
                    .url("https://api.opensubtitles.com/api/v1/download")
                    .addHeader("Api-Key", apiKey)
                    .addHeader("User-Agent", "GfaceVR v1.0 (Android)")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val link = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("SubSearch", "download req HTTP ${resp.code}")
                        return@use null
                    }
                    val root = JSONObject(resp.body?.string() ?: return@use null)
                    root.optString("link").takeIf { it.isNotBlank() }
                } ?: return@withContext null

                val dlReq = Request.Builder()
                    .url(link)
                    .addHeader("User-Agent", "GfaceVR v1.0 (Android)")
                    .build()
                httpClient.newCall(dlReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bytes = resp.body?.bytes() ?: return@use null
                    if (bytes.isEmpty()) return@use null

                    // zip magic: PK\x03\x04
                    if (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                        ZipInputStream(bytes.inputStream()).use { zip ->
                            var entry = zip.nextEntry
                            var srtFile: File? = null
                            while (entry != null) {
                                if (entry.name.endsWith(".srt", ignoreCase = true) && !entry.isDirectory) {
                                    outFile.outputStream().use { zip.copyTo(it) }
                                    srtFile = outFile
                                    break
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                            return@use srtFile
                        }
                    } else {
                        outFile.writeBytes(bytes)
                        outFile
                    }
                }
            } catch (e: Exception) {
                Log.e("SubSearch", "download failed", e)
                null
            }
        }
}
