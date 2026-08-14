package com.axiominfratech.geostamp.forensics

import android.content.Context
import com.axiominfratech.geostamp.verification.EvidenceRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Local append-only audit trail for evidence lifecycle events.
 * Each event contains the hash of the previous event, creating a tamper-evident chain.
 */
object EvidenceAuditTrail {
    enum class EventType { CAPTURED, SEALED, STORED, REGISTERED, VERIFIED, REVIEWED, FAILED }

    data class Event(
        val evidenceId: String,
        val type: EventType,
        val timestamp: Long,
        val actor: String = "SYSTEM",
        val reference: String = "",
        val details: JSONObject = JSONObject()
    )

    fun append(context: Context, event: Event): String {
        val file = auditFile(context, event.evidenceId)
        file.parentFile?.mkdirs()
        val previousHash = lastHash(file)
        val body = JSONObject().apply {
            put("schemaVersion", 1)
            put("evidenceId", event.evidenceId)
            put("eventType", event.type.name)
            put("timestamp", event.timestamp)
            put("actor", event.actor)
            put("reference", event.reference)
            put("details", event.details)
            put("previousEventHash", previousHash)
        }
        val eventHash = EvidenceRecord.sha256(body.toString().toByteArray(Charsets.UTF_8))
        body.put("eventHash", eventHash)
        file.appendText(body.toString() + "\n", Charsets.UTF_8)
        return eventHash
    }

    fun read(context: Context, evidenceId: String): List<JSONObject> =
        auditFile(context, evidenceId).takeIf { it.exists() }
            ?.readLines(Charsets.UTF_8)
            ?.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            ?: emptyList()

    fun verifyChain(context: Context, evidenceId: String): ChainResult {
        val events = read(context, evidenceId)
        if (events.isEmpty()) return ChainResult(false, 0, "No audit events found")
        var previous = ""
        events.forEachIndexed { index, event ->
            val stored = event.optString("eventHash")
            val body = JSONObject(event.toString()).apply { remove("eventHash") }
            val calculated = EvidenceRecord.sha256(body.toString().toByteArray(Charsets.UTF_8))
            if (!stored.equals(calculated, true)) return ChainResult(false, index, "Event hash mismatch")
            if (event.optString("previousEventHash") != previous) return ChainResult(false, index, "Previous-event link mismatch")
            previous = stored
        }
        return ChainResult(true, events.size, "Audit chain verified")
    }

    data class ChainResult(val valid: Boolean, val eventCount: Int, val message: String)

    fun toJsonArray(context: Context, evidenceId: String): JSONArray = JSONArray(read(context, evidenceId))

    private fun auditFile(context: Context, evidenceId: String): File =
        File(File(context.filesDir, "evidence_audit"), "${safe(evidenceId)}.jsonl")

    private fun lastHash(file: File): String =
        file.takeIf { it.exists() }
            ?.readLines(Charsets.UTF_8)
            ?.asReversed()
            ?.firstNotNullOfOrNull { runCatching { JSONObject(it).optString("eventHash") }.getOrNull() }
            .orEmpty()

    private fun safe(value: String): String = value.trim().lowercase(Locale.ENGLISH)
        .replace(Regex("[^a-z0-9._-]"), "-").replace(Regex("-+"), "-").trim('-')
}
