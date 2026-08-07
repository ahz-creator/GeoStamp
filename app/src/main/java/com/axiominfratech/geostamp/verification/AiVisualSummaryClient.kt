package com.axiominfratech.geostamp.verification

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional visual-description service used only to describe what the evidence image appears to show.
 * It is NOT part of cryptographic authentication and must never influence PASS/FAIL integrity results.
 * Endpoint is remotely configured; credentials remain server-side.
 */
object AiVisualSummaryClient {
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/ahz-creator/GeoStamp-Config/main/ai.json"

    data class Summary(
        val summary: String,
        val purpose: String,
        val status: String,
        val provider: String
    )

    fun analyze(
        context: Context,
        thumbnailBase64: String,
        operator: String,
        siteId: String,
        capturedAt: Long
    ): Summary {
        if (thumbnailBase64.isBlank()) return Summary("", "", "NO_VISUAL", "")
        return runCatching {
            val cfgConn = URL(CONFIG_URL).openConnection() as HttpURLConnection
            cfgConn.connectTimeout = 5000
            cfgConn.readTimeout = 5000
            val cfgText = cfgConn.inputStream.bufferedReader().use { it.readText() }
            cfgConn.disconnect()
            val cfg = JSONObject(cfgText)
            if (!cfg.optBoolean("enabled", false)) return Summary("", "", "DISABLED", "")
            val endpoint = cfg.optString("endpoint").trim()
            if (!endpoint.startsWith("https://")) return Summary("", "", "NOT_CONFIGURED", "")

            val request = JSONObject().apply {
                put("thumbnailBase64", thumbnailBase64)
                put("operator", operator)
                put("siteId", siteId)
                put("capturedAt", capturedAt)
                put("instruction", "Identify only clearly visible object types and count each visible instance. Return a short comma-separated list in the form 'Object name: count'. Do not describe the scene, infer purpose, identify people, infer ownership, condition, compliance, activity, or any fact not directly visible. If an object cannot be counted reliably, omit it.")
            }
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(request.toString().toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return Summary("", "", "FAILED", cfg.optString("provider"))
            val json = JSONObject(body)
            Summary(
                summary = json.optString("summary").trim().take(420),
                purpose = "",
                status = if (json.optString("summary").isBlank()) "NO_RESULT" else "GENERATED",
                provider = json.optString("provider", cfg.optString("provider"))
            )
        }.getOrElse { Summary("", "", "FAILED", "") }
    }
}
