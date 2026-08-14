package com.axiominfratech.geostamp.forensics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionEvidenceValidatorTest {
    private fun record(): JSONObject = JSONObject().apply {
        put("evidenceId", "GST-001")
        put("imageSha256", "a".repeat(64))
        put("capturedAt", 1730000000000L)
        put("latitude", 25.0)
        put("longitude", 67.0)
        put("registryStatus", "PUBLIC_RECORD")
        put("captureSignedPayload", "")
    }

    @Test fun missingSignatureProducesReview() {
        val report = ProductionEvidenceValidator.validate(record(), "GST-001")
        assertEquals(ProductionEvidenceValidator.State.REVIEW, report.state)
        assertTrue(report.review > 0)
    }

    @Test fun wrongIdFails() {
        val report = ProductionEvidenceValidator.validate(record(), "GST-999")
        assertEquals(ProductionEvidenceValidator.State.FAIL, report.state)
        assertTrue(report.failed > 0)
    }
}
