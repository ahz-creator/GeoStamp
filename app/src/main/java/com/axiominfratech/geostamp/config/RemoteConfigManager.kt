package com.axiominfratech.geostamp.config

import android.content.Context
import com.axiominfratech.geostamp.database.SiteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zero-cost configuration client.
 *
 * The administrator publishes config.json and operator CSV files to GitHub.
 * Android downloads them, validates the schema, stores a local cache and keeps
 * using the last valid copy when offline.
 */
class RemoteConfigManager(
    context: Context,
    private val siteRepository: SiteRepository
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "remote_config").also { it.mkdirs() }
    private val cacheFile = File(cacheDir, "config.json")

    data class OrganizationConfig(
        val id: String,
        val name: String,
        val active: Boolean
    )

    data class OperatorConfig(
        val id: String,
        val name: String,
        val code: String,
        val aliases: List<String>,
        val active: Boolean,
        val logoUrl: String,
        val siteCsvUrl: String,
        val defaultRadiusM: Double
    )

    data class AppConfig(
        val schemaVersion: Int,
        val configVersion: String,
        val generatedAt: Long,
        val organization: OrganizationConfig,
        val operators: List<OperatorConfig>
    ) {
        val activeOperators: List<OperatorConfig>
            get() = operators.filter { it.active }.sortedBy { it.name.lowercase() }
    }

    data class SyncResult(
        val success: Boolean,
        val configVersion: String,
        val operators: Int,
        val sitesImported: Int,
        val message: String
    )

    fun loadCached(): AppConfig {
        val cached = runCatching {
            if (cacheFile.exists()) parse(cacheFile.readText()) else null
        }.getOrNull()
        return cached ?: fallbackConfig()
    }

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val text = downloadText(CONFIG_URL)
            val parsed = parse(text)
            require(parsed.schemaVersion == SUPPORTED_SCHEMA) {
                "Unsupported config schema ${parsed.schemaVersion}"
            }
            require(parsed.activeOperators.isNotEmpty()) { "No active operators in config" }

            val temp = File(cacheDir, "config.tmp")
            temp.writeText(text)
            if (cacheFile.exists()) cacheFile.delete()
            temp.renameTo(cacheFile)

            var imported = 0
            val failures = mutableListOf<String>()
            parsed.activeOperators.forEach { operator ->
                if (operator.siteCsvUrl.isBlank()) return@forEach
                val result = siteRepository.syncOperatorCsv(
                    operatorName = operator.name,
                    aliases = operator.aliases,
                    csvUrl = operator.siteCsvUrl
                )
                if (result.success) imported += result.count else failures += operator.name
            }

            SyncResult(
                success = true,
                configVersion = parsed.configVersion,
                operators = parsed.activeOperators.size,
                sitesImported = imported,
                message = if (failures.isEmpty()) {
                    "Configuration updated"
                } else {
                    "Configuration updated; site sync failed for ${failures.joinToString()}"
                }
            )
        } catch (e: Exception) {
            val cached = loadCached()
            SyncResult(
                success = false,
                configVersion = cached.configVersion,
                operators = cached.activeOperators.size,
                sitesImported = 0,
                message = "Offline/cached configuration: ${e.message ?: "sync failed"}"
            )
        }
    }

    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "GeoStamp-Android/10")
        connection.setRequestProperty("Cache-Control", "no-cache")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(text: String): AppConfig {
        val root = JSONObject(text)
        val orgJson = root.optJSONObject("organization") ?: JSONObject()
        val operatorsJson = root.optJSONArray("operators") ?: JSONArray()
        val operators = buildList {
            for (i in 0 until operatorsJson.length()) {
                val item = operatorsJson.optJSONObject(i) ?: continue
                val aliasesJson = item.optJSONArray("aliases") ?: JSONArray()
                val aliases = buildList {
                    for (j in 0 until aliasesJson.length()) {
                        aliasesJson.optString(j).trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                add(
                    OperatorConfig(
                        id = item.optString("id", slug(name)),
                        name = name,
                        code = item.optString("code", name.take(3).uppercase()),
                        aliases = (aliases + name).distinctBy { it.lowercase() },
                        active = item.optBoolean("active", true),
                        logoUrl = item.optString("logoUrl", ""),
                        siteCsvUrl = item.optString("siteCsvUrl", ""),
                        defaultRadiusM = item.optDouble("defaultRadiusM", 25.0).coerceIn(1.0, 5_000.0)
                    )
                )
            }
        }
        return AppConfig(
            schemaVersion = root.optInt("schemaVersion", 1),
            configVersion = root.optString("configVersion", "local-fallback"),
            generatedAt = root.optLong("generatedAt", 0L),
            organization = OrganizationConfig(
                id = orgJson.optString("id", "axiom-infratech"),
                name = orgJson.optString("name", "Axiom InfraTech"),
                active = orgJson.optBoolean("active", true)
            ),
            operators = operators
        )
    }

    private fun fallbackConfig(): AppConfig = AppConfig(
        schemaVersion = SUPPORTED_SCHEMA,
        configVersion = "bundled-1",
        generatedAt = 0L,
        organization = OrganizationConfig("axiom-infratech", "Axiom InfraTech", true),
        operators = listOf(
            fallbackOperator("jazz", "Jazz / Warid", "JZZ", listOf("Jazz", "Mobilink", "PMCL", "Warid")),
            fallbackOperator("zong", "Zong", "ZNG", listOf("CMPak", "China Mobile")),
            fallbackOperator("telenor", "Telenor", "TNR", listOf("Telenor Pakistan")),
            fallbackOperator("ufone", "Ufone", "UFN", listOf("PTML", "Ufone (PTCL)")),
            fallbackOperator("ptcl", "PTCL", "PTL", listOf("Pakistan Telecommunication")),
            fallbackOperator("sco", "SCO", "SCO", listOf("SCOM", "Special Communications"))
        )
    )

    private fun fallbackOperator(
        id: String,
        name: String,
        code: String,
        aliases: List<String>
    ) = OperatorConfig(
        id = id,
        name = name,
        code = code,
        aliases = (aliases + name).distinct(),
        active = true,
        logoUrl = "",
        siteCsvUrl = "",
        defaultRadiusM = 25.0
    )

    companion object {
        const val SUPPORTED_SCHEMA = 1
        const val CONFIG_URL =
            "https://raw.githubusercontent.com/ahz-creator/GeoStamp-Config/main/config.json"

        private fun slug(value: String): String = value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }
}
