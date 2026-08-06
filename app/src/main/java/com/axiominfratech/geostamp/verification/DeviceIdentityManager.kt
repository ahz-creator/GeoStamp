package com.axiominfratech.geostamp.verification

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Locale

/**
 * Creates GeoStamp's strongest Play-Store-safe device identity and capture signature.
 *
 * Stable device identity:
 * - scoped to the Android device/user + GeoStamp signing certificate
 * - normally survives app updates, cache clearing and uninstall/reinstall
 * - may change after factory reset, Android user/profile change or signing-key change
 *
 * Capture key:
 * - generated inside Android Keystore
 * - hardware-backed where the handset supports TEE/StrongBox
 * - signs every sealed evidence record
 *
 * No IMEI, IMSI or SIM serial is accessed or fabricated.
 */
object DeviceIdentityManager {

    private const val KEY_ALIAS = "geostamp_capture_signing_v1"
    private const val PREFS = "geostamp_trusted_device"
    private const val KEY_FIRST_SEEN = "first_seen_ms"
    private const val KEY_LAST_KEY_FP = "last_capture_key_fingerprint"
    private const val KEY_PREVIOUS_KEY_FP = "previous_capture_key_fingerprint"
    private const val ID_VERSION = 1

    data class Snapshot(
        val fullDeviceIdentity: String,
        val maskedDeviceIdentity: String,
        val manufacturer: String,
        val brand: String,
        val model: String,
        val deviceCode: String,
        val androidVersion: String,
        val securityPatch: String,
        val signingCertificateSha256: String,
        val capturePublicKeyBase64: String,
        val captureKeyFingerprint: String,
        val previousCaptureKeyFingerprint: String?,
        val captureKeyHardwareBacked: Boolean,
        val captureKeySecurityLevel: String,
        val firstSeenMs: Long,
        val identityVersion: Int = ID_VERSION
    ) {
        fun toJson(includeFullIdentity: Boolean = true): JSONObject = JSONObject().apply {
            put("identityVersion", identityVersion)
            if (includeFullIdentity) put("geoStampDeviceIdentity", fullDeviceIdentity)
            put("maskedGeoStampDeviceIdentity", maskedDeviceIdentity)
            put("manufacturer", manufacturer)
            put("brand", brand)
            put("model", model)
            put("deviceCode", deviceCode)
            put("androidVersion", androidVersion)
            put("securityPatch", securityPatch)
            put("appSigningCertificateSha256", signingCertificateSha256)
            put("capturePublicKey", capturePublicKeyBase64)
            put("captureKeyFingerprint", captureKeyFingerprint)
            put("previousCaptureKeyFingerprint", previousCaptureKeyFingerprint ?: JSONObject.NULL)
            put("captureKeyHardwareBacked", captureKeyHardwareBacked)
            put("captureKeySecurityLevel", captureKeySecurityLevel)
            put("firstSeenMs", firstSeenMs)
        }
    }

    data class SignedEvidence(
        val canonicalPayload: String,
        val signatureBase64: String,
        val signatureAlgorithm: String,
        val snapshot: Snapshot
    )

    fun snapshot(context: Context): Snapshot {
        val appContext = context.applicationContext
        val keyStore = loadKeyStore()
        ensureCaptureKey(keyStore)
        val certificate = keyStore.getCertificate(KEY_ALIAS)
            ?: error("GeoStamp capture certificate unavailable")
        val publicKey = certificate.publicKey
        val publicKeyBytes = publicKey.encoded
        val keyFingerprint = sha256Hex(publicKeyBytes)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousStored = prefs.getString(KEY_LAST_KEY_FP, null)
        if (!previousStored.isNullOrBlank() && previousStored != keyFingerprint) {
            prefs.edit().putString(KEY_PREVIOUS_KEY_FP, previousStored).apply()
        }
        prefs.edit().putString(KEY_LAST_KEY_FP, keyFingerprint).apply()

        val firstSeen = prefs.getLong(KEY_FIRST_SEEN, 0L).let { existing ->
            if (existing > 0L) existing else System.currentTimeMillis().also {
                prefs.edit().putLong(KEY_FIRST_SEEN, it).apply()
            }
        }

        val appSigningFingerprint = appSigningCertificateSha256(appContext)
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()

        val identitySeed = listOf(
            "GEOSTAMP_DEVICE_ID_V$ID_VERSION",
            androidId,
            appContext.packageName,
            appSigningFingerprint,
            Build.MANUFACTURER.orEmpty(),
            Build.BRAND.orEmpty(),
            Build.MODEL.orEmpty(),
            Build.DEVICE.orEmpty()
        ).joinToString("|")
        val identityHash = sha256Hex(identitySeed.toByteArray(Charsets.UTF_8))
        val identity = "GDI-" + identityHash.take(20)
            .uppercase(Locale.ENGLISH)
            .chunked(4)
            .joinToString("-")

        val keySecurity = captureKeySecurity(keyStore)

        return Snapshot(
            fullDeviceIdentity = identity,
            maskedDeviceIdentity = maskIdentity(identity),
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Unavailable" },
            brand = Build.BRAND.orEmpty().ifBlank { "Unavailable" },
            model = Build.MODEL.orEmpty().ifBlank { "Unavailable" },
            deviceCode = Build.DEVICE.orEmpty().ifBlank { "Unavailable" },
            androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "Unavailable" },
            securityPatch = Build.VERSION.SECURITY_PATCH.orEmpty().ifBlank { "Unavailable" },
            signingCertificateSha256 = appSigningFingerprint,
            capturePublicKeyBase64 = Base64.encodeToString(
                publicKeyBytes,
                Base64.NO_WRAP
            ),
            captureKeyFingerprint = keyFingerprint,
            previousCaptureKeyFingerprint = prefs.getString(KEY_PREVIOUS_KEY_FP, null),
            captureKeyHardwareBacked = keySecurity.first,
            captureKeySecurityLevel = keySecurity.second,
            firstSeenMs = firstSeen
        )
    }

    fun signEvidence(
        context: Context,
        evidenceId: String,
        imageSha256: String,
        capturedAt: Long,
        latitude: Double,
        longitude: Double,
        accuracyM: Double,
        workspaceMode: String,
        primaryValue: String,
        secondaryValue: String,
        locationRisk: Boolean
    ): SignedEvidence {
        val device = snapshot(context)
        val canonical = listOf(
            "GEOSTAMP_CAPTURE_V1",
            evidenceId.trim(),
            imageSha256.lowercase(Locale.ENGLISH),
            capturedAt.toString(),
            "%.7f".format(Locale.US, latitude),
            "%.7f".format(Locale.US, longitude),
            "%.2f".format(Locale.US, accuracyM),
            workspaceMode.trim(),
            primaryValue.trim(),
            secondaryValue.trim(),
            locationRisk.toString(),
            device.fullDeviceIdentity,
            device.captureKeyFingerprint
        ).joinToString("|")

        val keyStore = loadKeyStore()
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
            ?: error("GeoStamp capture private key unavailable")
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey as java.security.PrivateKey)
            update(canonical.toByteArray(Charsets.UTF_8))
        }.sign()

        return SignedEvidence(
            canonicalPayload = canonical,
            signatureBase64 = Base64.encodeToString(signature, Base64.NO_WRAP),
            signatureAlgorithm = "SHA256withECDSA",
            snapshot = device
        )
    }

    fun verifySignature(
        canonicalPayload: String,
        signatureBase64: String,
        certificate: Certificate
    ): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").apply {
            initVerify(certificate.publicKey)
            update(canonicalPayload.toByteArray(Charsets.UTF_8))
        }.verify(Base64.decode(signatureBase64, Base64.DEFAULT))
    }.getOrDefault(false)

    private fun ensureCaptureKey(keyStore: KeyStore) {
        if (keyStore.containsAlias(KEY_ALIAS)) return

        fun generate(strongBox: Boolean) {
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBox) {
                builder.setIsStrongBoxBacked(true)
            }

            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            ).apply {
                initialize(builder.build())
                generateKeyPair()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { generate(true) }.getOrElse { generate(false) }
        } else {
            generate(false)
        }
    }

    private fun captureKeySecurity(keyStore: KeyStore): Pair<Boolean, String> = runCatching {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
        val factory = KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
        val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)
        val backed = keyInfo.isInsideSecureHardware
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Trusted Environment"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software"
                else -> "Unknown"
            }
        } else {
            if (backed) "Hardware-backed" else "Software"
        }
        backed to level
    }.getOrDefault(false to "Unavailable")

    private fun appSigningCertificateSha256(context: Context): String = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
        }

        val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()?.toByteArray()
        } ?: return@runCatching "unavailable"

        sha256Hex(certBytes)
    }.getOrDefault("unavailable")

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun maskIdentity(identity: String): String {
        val parts = identity.split("-")
        if (parts.size < 3) return "GDI-••••"
        return "${parts.first()}-••••-••••-${parts.last()}"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
