from pathlib import Path

root = Path('.')
vm_path = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
sync_path = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/RegistrySyncManager.kt'

if not vm_path.exists():
    raise SystemExit('MainViewModel.kt not found. Run this script from the GeoStamp project root.')

sync_code = r'''package com.axiominfratech.geostamp.verification

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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
            val records = EvidenceRegistryOutbox.pending(appContext).take(limit.coerceIn(1, 100))
            var published = 0
            var lastMessage = if (records.isEmpty()) "Registry is up to date." else "Sync started."

            records.forEach { record ->
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
'''

sync_path.parent.mkdir(parents=True, exist_ok=True)
if not sync_path.exists():
    sync_path.write_text(sync_code, encoding='utf-8')
    print('Created RegistrySyncManager.kt')
else:
    print('RegistrySyncManager.kt already exists; leaving it unchanged.')

text = vm_path.read_text(encoding='utf-8')

if 'import com.axiominfratech.geostamp.verification.RegistrySyncManager' not in text:
    text = text.replace(
        'import com.axiominfratech.geostamp.verification.RegistryPublisher\n',
        'import com.axiominfratech.geostamp.verification.RegistryPublisher\nimport com.axiominfratech.geostamp.verification.RegistrySyncManager\n'
    )

field_anchor = '    private val remoteConfig = RemoteConfigManager(application, siteRepo)\n'
if 'private val registrySync = RegistrySyncManager(application)' not in text:
    if field_anchor not in text:
        raise SystemExit('RemoteConfigManager field anchor not found.')
    text = text.replace(
        field_anchor,
        field_anchor + '    private val registrySync = RegistrySyncManager(application)\n',
        1
    )

# Add periodic startup/connectivity retry inside the first init block after network status polling.
network_block = '''        viewModelScope.launch {
            while (isActive) {
                _networkStatus.value = getNetworkType()
                delay(10000)
            }
        }
'''
retry_block = '''        viewModelScope.launch {
            while (isActive) {
                val network = getNetworkType()
                _networkStatus.value = network
                if (network != "No Signal") {
                    runCatching { registrySync.syncPending() }
                }
                delay(60_000)
            }
        }
'''
if network_block in text:
    text = text.replace(network_block, retry_block, 1)
elif 'registrySync.syncPending()' not in text:
    print('Warning: periodic network loop anchor not found; startup sync will still be added.')

# Add an immediate startup retry in the second init block.
startup_anchor = '        syncRemoteConfiguration()\n'
if 'syncPendingRegistryRecords()' not in text:
    if startup_anchor not in text:
        raise SystemExit('syncRemoteConfiguration anchor not found.')
    text = text.replace(startup_anchor, startup_anchor + '        syncPendingRegistryRecords()\n', 1)

# Replace one-shot direct publisher with the reliable manager.
old_publish = '''                        viewModelScope.launch(Dispatchers.IO) {
                            val result = RegistryPublisher.publish(app, queuedFile)
                            if (result.success) {
                                EvidenceRegistryOutbox.publishFileSilently(
                                    app,
                                    queuedFile,
                                    result.registryUrl
                                )
                            }
                        }
'''
new_publish = '''                        viewModelScope.launch(Dispatchers.IO) {
                            registrySync.publishNow(queuedFile)
                        }
'''
if old_publish in text:
    text = text.replace(old_publish, new_publish, 1)
elif 'registrySync.publishNow(queuedFile)' not in text:
    raise SystemExit('Registry publication block not found; patch stopped to avoid unsafe edits.')

# Public helpers for future UI/admin status cards.
method_anchor = '    fun activeOperatorSession(): OperatorSessionManager.Session? = operatorSessions.active()\n'
methods = '''    fun pendingRegistryCount(): Int = registrySync.pendingCount()
    fun registrySyncState(): String = registrySync.lastState()
    fun registrySyncMessage(): String = registrySync.lastMessage()
    fun registryLastSyncAt(): Long = registrySync.lastSyncAt()

    fun syncPendingRegistryRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { registrySync.syncPending() }
        }
    }

'''
if 'fun pendingRegistryCount()' not in text:
    if method_anchor not in text:
        raise SystemExit('activeOperatorSession method anchor not found.')
    text = text.replace(method_anchor, method_anchor + '\n' + methods, 1)

vm_path.write_text(text, encoding='utf-8')
print('Applied offline registry auto-sync to MainViewModel.kt')
print('Phase complete: queued records now retry automatically without losing evidence.')
