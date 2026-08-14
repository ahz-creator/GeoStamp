package com.axiominfratech.geostamp.verification

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import com.axiominfratech.geostamp.forensics.EvidenceAuditTrail

/**
 * Reliable offline-first registry synchronization.
 *
 * Evidence is always queued locally first. This manager retries queued records
 * whenever the app starts, connectivity returns, or a new capture is created.
 * A failed upload never deletes the local record.
 */
class RegistrySyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("geostamp_registry_sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    data class SyncSummary(
        val attempted: Int,
        val published: Int,
        val remaining: Int,
        val lastMessage: String
    )

    suspend fun publishNow(file: File): RegistryPublisher.PublishResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) {
                return@withLock RegistryPublisher.PublishResult(false, "Queued registry record is missing.")
            }
            val result = RegistryPublisher.publish(appContext, file)
            if (result.success) {
                EvidenceRegistryOutbox.publishFileSilently(appContext, file, result.registryUrl)
                saveStatus("REGISTERED", result.message)
            } else {
                saveStatus("PENDING", result.message)
            }
            result
        }
    }

    suspend fun syncPending(limit: Int = 25): SyncSummary = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val records = EvidenceRegistryOutbox.pending(appContext).filter {
                val json = runCatching { org.json.JSONObject(it.file.readText()) }.getOrNull()
                json?.optLong("nextRetryAt", 0L)?.let { retryAt -> retryAt <= now } ?: true
            }.take(limit.coerceIn(1, 100))
            var published = 0
            var lastMessage = if (records.isEmpty()) "Registry is up to date." else "Sync started."

            records.forEach { record ->
                val json = runCatching { org.json.JSONObject(record.file.readText()) }.getOrNull()
                val attempt = (json?.optInt("syncAttemptCount", 0) ?: 0) + 1
                val result = RegistryPublisher.publish(appContext, record.file)
                lastMessage = result.message
                if (result.success) {
                    EvidenceRegistryOutbox.publishFileSilently(
                        appContext,
                        record.file,
                        result.registryUrl
                    )
                    published++
                } else {
                    val backoffMinutes = (1L shl attempt.coerceIn(0, 6))
                    EvidenceRegistryOutbox.recordRetry(record.file, attempt, System.currentTimeMillis() + backoffMinutes * 60_000L)
                    runCatching { EvidenceAuditTrail.append(appContext, EvidenceAuditTrail.Event(
                        evidenceId = record.verificationId,
                        type = EvidenceAuditTrail.EventType.REVIEWED,
                        timestamp = System.currentTimeMillis(),
                        details = org.json.JSONObject().put("syncAttempt", attempt).put("message", result.message)
                    )) }
                    // Stop after the first network/server failure. Remaining files stay safe locally.
                    return@forEach
                }
            }

            val remaining = EvidenceRegistryOutbox.pending(appContext).size
            val state = if (remaining == 0) "REGISTERED" else "PENDING"
            saveStatus(state, lastMessage, remaining)
            SyncSummary(records.size, published, remaining, lastMessage)
        }
    }

    fun pendingCount(): Int = EvidenceRegistryOutbox.pending(appContext).size
    fun lastState(): String = prefs.getString("state", "UNKNOWN") ?: "UNKNOWN"
    fun lastMessage(): String = prefs.getString("message", "") ?: ""
    fun lastSyncAt(): Long = prefs.getLong("last_sync_at", 0L)

    private fun saveStatus(state: String, message: String, pending: Int = pendingCount()) {
        prefs.edit()
            .putString("state", state)
            .putString("message", message)
            .putInt("pending", pending)
            .putLong("last_sync_at", System.currentTimeMillis())
            .apply()
    }
}
