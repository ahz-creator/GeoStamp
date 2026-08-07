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

class RemoteConfigManager(
    context: Context,
    private val siteRepository: SiteRepository
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "remote_config").also { it.mkdirs() }
    private val cacheFile = File(cacheDir, "config.json")

    data class OrganizationConfig(val id: String, val name: String, val active: Boolean)

    data class PolicyConfig(
        val siteDetectionRadiusM: Double = 1000.0,
        val operatorInactivityTimeoutMinutes: Int = 240,
        val allowPersonalCaptureOutsideSite: Boolean = true,
        val keepOperatorSessionWhenSwitchingSites: Boolean = true,
        val autoLockNearestSite: Boolean = true,
        val requireInsideRadiusForOrganizationCapture: Boolean = true
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
        val operators: List<OperatorConfig>,
        val policy: PolicyConfig = PolicyConfig()
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

    fun loadCached(): AppConfig = runCatching {
        if (cacheFile.exists()) parse(cacheFile.readText()) else null
    }.getOrNull() ?: fallbackConfig()

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val text = downloadText(CONFIG_URL)
            val parsed = parse(text)
            require(parsed.schemaVersion == SUPPORTED_SCHEMA) { "Unsupported config schema ${parsed.schemaVersion}" }
            require(parsed.activeOperators.isNotEmpty()) { "No active operators in config" }
            val temp = File(cacheDir, "config.tmp")
            temp.writeText(text)
            if (cacheFile.exists()) cacheFile.delete()
            temp.renameTo(cacheFile)
            var imported = 0
            val failures = mutableListOf<String>()
            parsed.activeOperators.forEach { operator ->
                if (operator.siteCsvUrl.isBlank()) return@forEach
                val result = siteRepository.syncOperatorCsv(operator.name, operator.aliases, operator.siteCsvUrl)
                if (result.success) imported += result.count else failures += operator.name
            }
            SyncResult(true, parsed.configVersion, parsed.activeOperators.size, imported,
                if (failures.isEmpty()) "Configuration updated" else "Configuration updated; site sync failed for ${failures.joinToString()}")
        } catch (e: Exception) {
            val cached = loadCached()
            SyncResult(false, cached.configVersion, cached.activeOperators.size, 0,
                "Offline/cached configuration: ${e.message ?: "sync failed"}")
        }
    }

    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "GeoStamp-Android/11")
        connection.setRequestProperty("Cache-Control", "no-cache")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun parse(text: String): AppConfig {
        val root = JSONObject(text)
        val orgJson = root.optJSONObject("organization") ?: JSONObject()
        val policyJson = root.optJSONObject("policy") ?: JSONObject()
        val operatorsJson = root.optJSONArray("operators") ?: JSONArray()
        val globalRadius = policyJson.optDouble("siteDetectionRadiusM", 1000.0).coerceIn(0.0, 1000.0)
        val operators = buildList {
            for (i in 0 until operatorsJson.length()) {
                val item = operatorsJson.optJSONObject(i) ?: continue
                val aliasesJson = item.optJSONArray("aliases") ?: JSONArray()
                val aliases = buildList {
                    for (j in 0 until aliasesJson.length()) aliasesJson.optString(j).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                add(OperatorConfig(
                    id = item.optString("id", slug(name)),
                    name = name,
                    code = item.optString("code", name.take(3).uppercase()),
                    aliases = (aliases + name).distinctBy { it.lowercase() },
                    active = item.optBoolean("active", true),
                    logoUrl = item.optString("logoUrl", ""),
                    siteCsvUrl = item.optString("siteCsvUrl", ""),
                    defaultRadiusM = item.optDouble("defaultRadiusM", globalRadius).coerceIn(0.0, 1000.0)
                ))
            }
        }
        return AppConfig(
            schemaVersion = root.optInt("schemaVersion", 1),
            configVersion = root.optString("configVersion", "local-fallback"),
            generatedAt = root.optLong("generatedAt", 0L),
            organization = OrganizationConfig(
                orgJson.optString("id", "axiom-infratech"),
                orgJson.optString("name", "Axiom InfraTech"),
                orgJson.optBoolean("active", true)
            ),
            operators = operators,
            policy = PolicyConfig(
                siteDetectionRadiusM = globalRadius,
                operatorInactivityTimeoutMinutes = policyJson.optInt("operatorInactivityTimeoutMinutes", 240).coerceIn(1, 1440),
                allowPersonalCaptureOutsideSite = policyJson.optBoolean("allowPersonalCaptureOutsideSite", true),
                keepOperatorSessionWhenSwitchingSites = policyJson.optBoolean("keepOperatorSessionWhenSwitchingSites", true),
                autoLockNearestSite = policyJson.optBoolean("autoLockNearestSite", true),
                requireInsideRadiusForOrganizationCapture = policyJson.optBoolean("requireInsideRadiusForOrganizationCapture", true)
            )
        )
    }

    private fun fallbackConfig(): AppConfig = AppConfig(
        schemaVersion = SUPPORTED_SCHEMA,
        configVersion = "bundled-2",
        generatedAt = 0L,
        organization = OrganizationConfig("axiom-infratech", "Axiom InfraTech", true),
        operators = listOf(
            fallbackOperator("jazz", "Jazz / Warid", "JZZ", listOf("Jazz", "Mobilink", "PMCL", "Warid")),
            fallbackOperator("zong", "Zong", "ZNG", listOf("CMPak", "China Mobile")),
            fallbackOperator("telenor", "Telenor", "TNR", listOf("Telenor Pakistan")),
            fallbackOperator("ufone", "Ufone", "UFN", listOf("PTML", "Ufone (PTCL)")),
            fallbackOperator("ptcl", "PTCL", "PTL", listOf("Pakistan Telecommunication")),
            fallbackOperator("sco", "SCO", "SCO", listOf("SCOM", "Special Communications"))
        ),
        policy = PolicyConfig()
    )

    private fun fallbackOperator(id: String, name: String, code: String, aliases: List<String>) = OperatorConfig(
        id, name, code, (aliases + name).distinct(), true, "", "", 1000.0
    )

    companion object {
        const val SUPPORTED_SCHEMA = 1
        const val CONFIG_URL = "https://raw.githubusercontent.com/ahz-creator/GeoStamp-Config/main/config.json"
        private fun slug(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}
