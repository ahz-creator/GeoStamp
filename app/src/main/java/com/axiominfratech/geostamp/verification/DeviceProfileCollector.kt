package com.axiominfratech.geostamp.verification

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

/** Collects only values available to an ordinary Android app. */
object DeviceProfileCollector {
    fun collect(context: Context): JSONObject {
        val dm = context.resources.displayMetrics
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val connection = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Unavailable"
        }
        val androidIdHash = runCatching {
            val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
        }.getOrDefault("unavailable")

        val trustedDevice = DeviceIdentityManager.snapshot(context)

        return JSONObject().apply {
            put("geoStampDeviceIdentity", trustedDevice.fullDeviceIdentity)
            put("maskedGeoStampDeviceIdentity", trustedDevice.maskedDeviceIdentity)
            put("deviceIdentityVersion", trustedDevice.identityVersion)
            put("captureKeyFingerprint", trustedDevice.captureKeyFingerprint)
            put("capturePublicKey", trustedDevice.capturePublicKeyBase64)
            put("captureKeyHardwareBacked", trustedDevice.captureKeyHardwareBacked)
            put("captureKeySecurityLevel", trustedDevice.captureKeySecurityLevel)
            put("deviceIdentityFirstSeenMs", trustedDevice.firstSeenMs)
            put("manufacturer", Build.MANUFACTURER ?: "Unavailable")
            put("brand", Build.BRAND ?: "Unavailable")
            put("model", Build.MODEL ?: "Unavailable")
            put("device", Build.DEVICE ?: "Unavailable")
            put("androidVersion", Build.VERSION.RELEASE ?: "Unavailable")
            put("sdk", Build.VERSION.SDK_INT)
            put("securityPatch", Build.VERSION.SECURITY_PATCH ?: "Unavailable")
            put("cpuAbis", Build.SUPPORTED_ABIS?.joinToString(",") ?: "Unavailable")
            put("ramClassMb", am?.memoryClass ?: -1)
            put("screenPx", "${dm.widthPixels}x${dm.heightPixels}")
            put("screenDensityDpi", dm.densityDpi)
            put("locale", Locale.getDefault().toLanguageTag())
            put("timezone", TimeZone.getDefault().id)
            put("connectionType", connection)
            put("carrier", runCatching { tm?.networkOperatorName }.getOrNull().orEmpty().ifBlank { "Unavailable" })
            put("simCountry", runCatching { tm?.simCountryIso }.getOrNull().orEmpty().ifBlank { "Unavailable" })
            put("networkCountry", runCatching { tm?.networkCountryIso }.getOrNull().orEmpty().ifBlank { "Unavailable" })
            put("roaming", runCatching { tm?.isNetworkRoaming }.getOrNull() ?: false)
            put("androidIdHash", androidIdHash)
            put("imei", "Unavailable - restricted by Android")
            put("imsi", "Unavailable - restricted by Android")
            put("simSerial", "Unavailable - restricted by Android")
        }
    }
}
