package com.axiominfratech.geostamp.verification

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceForensicValidatorTest {
    private fun base(): JSONObject = JSONObject().apply {
        put("evidenceId", "GST-TEST-001")
        put("imageSha256", "a".repeat(64))
        put("captureSignedPayload", "")
    }

    @Test fun idMismatchFails() {
        val result = EvidenceForensicValidator.validate(base(), "GST-OTHER")
        assertEquals(EvidenceForensicValidator.State.FAIL, result.state)
        assertFalse(result.idConsistent)
    }

    @Test fun malformedHashFails() {
        val record = base().put("imageSha256", "abc")
        val result = EvidenceForensicValidator.validate(record, "GST-TEST-001")
        assertEquals(EvidenceForensicValidator.State.FAIL, result.state)
        assertFalse(result.hashFormatValid)
    }

    @Test fun missingSignatureIsReviewNotCryptographicPass() {
        val result = EvidenceForensicValidator.validate(base(), "GST-TEST-001")
        assertEquals(EvidenceForensicValidator.State.REVIEW, result.state)
        assertFalse(result.isCryptographicallyValid)
        assertTrue(result.signatureVerified == null)
    }

    @Test fun signedPayloadMismatchFails() {
        val record = base().put(
            "captureSignedPayload",
            "GEOSTAMP_CAPTURE_V1|GST-OTHER|${"a".repeat(64)}|1|25.0000000|67.0000000|10.00|FIELD|OP|SITE|false|DEVICE|FP"
        )
        val result = EvidenceForensicValidator.validate(record, "GST-TEST-001")
        assertEquals(EvidenceForensicValidator.State.FAIL, result.state)
        assertFalse(result.signedPayloadConsistent)
    }
}
