package com.axiominfratech.geostamp.verification

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Publishes public-safe evidence records to the free GeoStamp registry endpoint.
 *
 * The endpoint is loaded remotely from:
 * https://raw.githubusercontent.com/ahz-creator/GeoStamp-Config/main/registry.json
 *
 * This avoids storing GitHub credentials or private tokens inside the Android app.
 */
object RegistryPublisher {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/ahz-creator/GeoStamp-Config/main/registry.json"

    data class PublishResult(
        val success: Boolean,
        val message: String,
        val registryUrl: String? = null
    )

    suspend fun publish(context: Context, recordFile: File): PublishResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = resolveEndpoint(context)
                    ?: return@withContext PublishResult(
                        false,
                        "Registry endpoint is not configured."
                    )

                val body = recordFile.readText()
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "GeoStamp-Android")
                }

                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val responseText = runCatching {
                    val stream = if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")

                if (code !in 200..299) {
                    return@withContext PublishResult(
                        false,
                        "Registry returned HTTP $code."
                    )
                }

                val response = runCatching { JSONObject(responseText) }.getOrNull()
                val ok = response?.optBoolean("ok", true) ?: true
                if (!ok) {
                    return@withContext PublishResult(
                        false,
                        response?.optString("error", "Registry rejected the record.")
                            ?: "Registry rejected the record."
                    )
                }

                PublishResult(
                    success = true,
                    message = response?.optString("message", "Registered")
                        ?: "Registered",
                    registryUrl = response?.optString("registryUrl")
                        ?.takeIf { it.isNotBlank() }
                )
            }.getOrElse {
                PublishResult(false, it.message ?: "Registry publication failed.")
            }
        }

    suspend fun lookup(context: Context, evidenceId: String): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = resolveEndpoint(context) ?: return@withContext null
                val url = "$endpoint?id=${java.net.URLEncoder.encode(evidenceId, "UTF-8")}"
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode !in 200..299) return@withContext null
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                if (!json.optBoolean("ok", true)) null
                else json.optJSONObject("record") ?: json
            }.getOrNull()
        }

    private fun resolveEndpoint(context: Context): String? {
        val prefs = context.getSharedPreferences("geostamp_registry", Context.MODE_PRIVATE)
        val cached = prefs.getString("endpoint", null)

        val remote = runCatching {
            val connection = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7_000
                readTimeout = 7_000
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (connection.responseCode !in 200..299) return@runCatching null
            val json = JSONObject(
                connection.inputStream.bufferedReader().use { it.readText() }
            )
            if (!json.optBoolean("enabled", true)) return@runCatching null
            json.optString("endpoint").trim().takeIf {
                it.startsWith("https://")
            }
        }.getOrNull()

        if (!remote.isNullOrBlank()) {
            prefs.edit().putString("endpoint", remote).apply()
            return remote
        }
        return cached?.takeIf { it.startsWith("https://") }
    }
}
