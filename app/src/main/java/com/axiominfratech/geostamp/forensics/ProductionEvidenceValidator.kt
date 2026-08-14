package com.axiominfratech.geostamp.forensics

import com.axiominfratech.geostamp.verification.EvidenceForensicValidator
import org.json.JSONObject
import java.util.Locale

/** Deterministic production gate for a registered evidence record. */
object ProductionEvidenceValidator {
    enum class State { PASS, REVIEW, FAIL }

    data class Check(val name: String, val state: State, val message: String)
    data class Report(val state: State, val checks: List<Check>) {
        val passed = checks.count { it.state == State.PASS }
        val failed = checks.count { it.state == State.FAIL }
        val review = checks.count { it.state == State.REVIEW }
    }

    fun validate(record: JSONObject, requestedId: String? = null): Report {
        val checks = mutableListOf<Check>()
        val forensic = EvidenceForensicValidator.validate(record, requestedId)
        checks += Check("Evidence ID", if (forensic.idConsistent) State.PASS else State.FAIL,
            if (forensic.idConsistent) "Evidence identity is consistent" else "Evidence identity mismatch")
        checks += Check("Image SHA-256", if (forensic.hashFormatValid) State.PASS else State.FAIL,
            if (forensic.hashFormatValid) "Valid SHA-256 fingerprint recorded" else "SHA-256 is missing or malformed")
        checks += Check("Capture signature", when (forensic.signatureVerified) {
            true -> State.PASS
            false -> State.FAIL
            null -> State.REVIEW
        }, when (forensic.signatureVerified) {
            true -> "ECDSA capture signature verified"
            false -> "Capture signature failed"
            null -> "Signature material is incomplete"
        })
        checks += Check("Signed payload", if (forensic.signedPayloadConsistent) State.PASS else State.FAIL,
            if (forensic.signedPayloadConsistent) "Signed fields match evidence record" else "Signed fields do not match evidence record")
        val registry = record.optString("registryStatus").uppercase(Locale.ENGLISH)
        checks += Check("Registry", when (registry) {
            "PUBLIC_RECORD", "REGISTERED" -> State.PASS
            "", "PENDING" -> State.REVIEW
            else -> State.REVIEW
        }, when (registry) {
            "PUBLIC_RECORD", "REGISTERED" -> "Public registry record confirmed"
            "PENDING" -> "Waiting for registry publication"
            else -> "Registry confirmation unavailable"
        })
        checks += Check("Capture timestamp", if (record.optLong("capturedAt", record.optLong("timestamp", 0L)) > 0L) State.PASS else State.FAIL,
            if (record.optLong("capturedAt", record.optLong("timestamp", 0L)) > 0L) "Capture timestamp recorded" else "Capture timestamp missing")
        checks += Check("Location", if (record.has("latitude") || record.has("lat")) State.PASS else State.REVIEW,
            if (record.has("latitude") || record.has("lat")) "Location coordinates recorded" else "Location unavailable")
        val state = when {
            checks.any { it.state == State.FAIL } -> State.FAIL
            checks.any { it.state == State.REVIEW } -> State.REVIEW
            else -> State.PASS
        }
        return Report(state, checks)
    }
}
