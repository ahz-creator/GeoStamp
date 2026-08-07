package com.axiominfratech.geostamp.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import org.json.JSONObject
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.axiominfratech.geostamp.GeoStampApp
import com.axiominfratech.geostamp.camera.CameraManager
import com.axiominfratech.geostamp.database.Operator
import com.axiominfratech.geostamp.database.SiteRepository
import com.axiominfratech.geostamp.location.LocationEngine
import com.axiominfratech.geostamp.overlay.OverlayRenderer
import com.axiominfratech.geostamp.overlay.StampConfig
import com.axiominfratech.geostamp.overlay.LiveInfoMode
import com.axiominfratech.geostamp.security.AntiSpoofManager
import com.axiominfratech.geostamp.verification.IntegrityStatus
import com.axiominfratech.geostamp.verification.VerificationEngine
import com.axiominfratech.geostamp.verification.EvidenceHistoryEngine
import com.axiominfratech.geostamp.verification.DeviceProfileCollector
import com.axiominfratech.geostamp.verification.DeviceIdentityManager
import com.axiominfratech.geostamp.verification.EvidenceRegistryOutbox
import com.axiominfratech.geostamp.verification.RegistryPublisher
import com.axiominfratech.geostamp.verification.RegistrySyncManager
import com.axiominfratech.geostamp.verification.EvidenceSlipMetadata
import com.axiominfratech.geostamp.verification.EvidenceVisualCache
import com.axiominfratech.geostamp.verification.AiVisualSummaryClient
import com.axiominfratech.geostamp.core.OperatorSessionManager
import com.axiominfratech.geostamp.config.RemoteConfigManager
import org.json.JSONArray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app            = application as GeoStampApp
    private val locationEngine = LocationEngine(application)
    private val siteRepo       = app.siteRepository
    private val prefs          = application.getSharedPreferences("geostamp_prefs", Context.MODE_PRIVATE)
    private val operatorSessions = OperatorSessionManager(application)
    private val remoteConfig = RemoteConfigManager(application, siteRepo)
    private val registrySync = RegistrySyncManager(application)
    private val _remoteAppConfig = MutableStateFlow(remoteConfig.loadCached())
    val remoteAppConfig: StateFlow<RemoteConfigManager.AppConfig> = _remoteAppConfig.asStateFlow()

    private val _uiState = MutableStateFlow(UiState(operatorSession = operatorSessions.active()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _batteryLevel = MutableStateFlow("–")
    private val _networkStatus = MutableStateFlow("–")

    init {
        val filter = IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        application.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    _batteryLevel.value = "${(level * 100 / scale.toFloat()).toInt()}%"
                }
            }
        }, filter)

        viewModelScope.launch {
            while (isActive) {
                val network = getNetworkType()
                _networkStatus.value = network
                if (network != "No Signal") {
                    runCatching { registrySync.syncPending() }
                }
                delay(60_000)
            }
        }
    }

    private fun getNetworkType(): String {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "–"
        val nw = cm.activeNetwork ?: return "No Signal"
        val actNw = cm.getNetworkCapabilities(nw) ?: return "–"
        return when {
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                val opName = tm?.networkOperatorName ?: "Cellular"
                "$opName 4G" // Simplifying for Enterprise UI look
            }
            else -> "Connected"
        }
    }

    private val _captureEvent = MutableSharedFlow<CaptureEvent>()
    val captureEvent: SharedFlow<CaptureEvent> = _captureEvent.asSharedFlow()

    private val _stampConfig = MutableStateFlow(
        StampConfig(
            username         = prefs.getString("profile_display_name", null)
                ?: prefs.getString("profile_email", null)
                ?: "Guest",
            overlayAlpha     = prefs.getFloat("overlay_alpha", 0.6f),
            overlayScale     = prefs.getFloat("overlay_scale", 1.0f),
            savedOverlayHeightFraction = prefs.getFloat("saved_overlay_height_fraction", 0.25f).coerceIn(0.20f, 0.30f),
            liveInfoMode     = runCatching {
                LiveInfoMode.valueOf(prefs.getString("live_info_mode", null) ?: "")
            }.getOrElse {
                when {
                    prefs.getBoolean("show_info_card", true) -> LiveInfoMode.FLOATING
                    prefs.getBoolean("show_info_strip", false) -> LiveInfoMode.BOTTOM
                    else -> LiveInfoMode.OFF
                }
            },
            showInfoCard     = false,
            showInfoStrip    = false,
            selectedOperator = Operator.ALL,
            colorScheme   = try {
                com.axiominfratech.geostamp.overlay.OverlayColorScheme
                    .valueOf(prefs.getString("color_scheme", "BLACK") ?: "BLACK")
            } catch (_: Exception) { com.axiominfratech.geostamp.overlay.OverlayColorScheme.BLACK },
            customColorRgb = prefs.getInt("custom_color_rgb", 0x000000)
        )
    )
    val stampConfig: StateFlow<StampConfig> = _stampConfig.asStateFlow()

    private val _datasetOperators = MutableStateFlow<List<String>>(emptyList())
    val datasetOperators: StateFlow<List<String>> = _datasetOperators.asStateFlow()

    init {
        runSecurityAudit()
        startLocationUpdates()
        loadDatabaseStats()
        loadDatasetOperators()
        syncRemoteConfiguration()
        syncPendingRegistryRecords()
    }

    private fun runSecurityAudit() {
        viewModelScope.launch {
            val w = locationEngine.getSecurityAudit()
            if (w.isNotEmpty()) _uiState.update { it.copy(securityWarnings = w) }
        }
    }

    private fun startLocationUpdates() {
        viewModelScope.launch {
            locationEngine.locationFlow().collect { result ->
                result.fold(
                    onSuccess = { geo ->
                        val activeSession = operatorSessions.active()
                        val match = if (activeSession != null) {
                            siteRepo.findMatchingSiteByAliases(
                                geo.latitude,
                                geo.longitude,
                                activeSession.operatorName,
                                activeSession.aliases,
                                _remoteAppConfig.value.operators.firstOrNull { it.id == activeSession.operatorId }?.defaultRadiusM ?: 500.0
                            )
                        } else {
                            SiteRepository.SiteMatchResult(false, fallbackText = "Clock in to an operator")
                        }
                        operatorSessions.updateSiteLock(match.site?.siteId)
                        _uiState.update {
                            it.copy(
                                currentLocation = geo,
                                siteMatch       = match,
                                locationError   = null,
                                gpsStatus       = when {
                                    geo.mockLocationDetected -> GpsStatus.SPOOFED
                                    geo.isVerified -> GpsStatus.LOCKED
                                    else -> GpsStatus.SEARCHING
                                }
                            )
                        }
                        precomputeNearestForAllOperators(geo.latitude, geo.longitude)
                        (app as? com.axiominfratech.geostamp.GeoStampApp)
                            ?.onGpsAvailable(geo.latitude, geo.longitude)
                        if (_uiState.value.dbSiteCount <= 50 && !_uiState.value.isSyncing) {
                            syncAndRecompute()
                        }
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(
                                locationError = err.message,
                                gpsStatus = if (err.message?.contains("Mock") == true ||
                                    err.message?.contains("spoof") == true)
                                    GpsStatus.SPOOFED else GpsStatus.SEARCHING
                            )
                        }
                    }
                )
            }
        }
    }

    private fun precomputeNearestForAllOperators(lat: Double, lng: Double) {
        viewModelScope.launch {
            val results = siteRepo.findNearestPerOperator(lat, lng)
            _uiState.update { it.copy(nearestPerOperator = results) }
        }
    }

    fun updateOverlayState(state: OverlayState) {
        _uiState.update { it.copy(overlayState = state) }
    }

    fun availableOperators(): List<RemoteConfigManager.OperatorConfig> =
        _remoteAppConfig.value.activeOperators

    fun startOperatorSession(operator: RemoteConfigManager.OperatorConfig): Result<OperatorSessionManager.Session> = runCatching {
        val session = operatorSessions.start(
            operator,
            _remoteAppConfig.value.policy.operatorInactivityTimeoutMinutes
        )
        prefs.edit().putString("workspace_mode", "organization").apply()
        _uiState.update { it.copy(operatorSession = session, siteMatch = null) }
        recomputeSiteMatchForActiveSession()
        session
    }

    fun endOperatorSession(): OperatorSessionManager.Session? {
        val ended = operatorSessions.end()
        _uiState.update { it.copy(operatorSession = null, siteMatch = null) }
        _stampConfig.update { it.copy(selectedOperator = Operator.ALL) }
        return ended
    }

    fun activeOperatorSession(): OperatorSessionManager.Session? = operatorSessions.active()

    fun pendingRegistryCount(): Int = registrySync.pendingCount()
    fun registrySyncState(): String = registrySync.lastState()
    fun registrySyncMessage(): String = registrySync.lastMessage()
    fun registryLastSyncAt(): Long = registrySync.lastSyncAt()

    fun syncPendingRegistryRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { registrySync.syncPending() }
        }
    }


    fun operatorSessionCurrentSite(): String? = operatorSessions.currentSiteId()
    fun operatorSessionSiteVisitOrder(): List<String> = operatorSessions.siteVisitOrder()
    fun operatorSessionSiteFirstSeenAt(siteId: String): Long = operatorSessions.siteFirstSeenAt(siteId)

    fun syncRemoteConfiguration() {
        viewModelScope.launch {
            val result = remoteConfig.sync()
            _remoteAppConfig.value = remoteConfig.loadCached()
            _stampConfig.update { current ->
                current.copy(matchRadiusM = _remoteAppConfig.value.policy.siteDetectionRadiusM)
            }
            _uiState.update { it.copy(lastImportResult = result.message) }
            recomputeSiteMatchForActiveSession()
        }
    }

    private fun recomputeSiteMatchForActiveSession() {
        viewModelScope.launch {
            val session = operatorSessions.active() ?: return@launch
            val loc = _uiState.value.currentLocation ?: return@launch
            val operator = _remoteAppConfig.value.operators.firstOrNull { it.id == session.operatorId }
            val match = siteRepo.findMatchingSiteByAliases(
                loc.latitude,
                loc.longitude,
                session.operatorName,
                session.aliases,
                operator?.defaultRadiusM ?: 500.0
            )
            operatorSessions.updateSiteLock(match.site?.siteId)
            _uiState.update { it.copy(siteMatch = match, operatorSession = operatorSessions.active()) }
        }
    }

    fun onCaptureRequested(cameraManager: CameraManager, outputDir: File) {
        viewModelScope.launch {
            val state   = _uiState.value
            val config  = _stampConfig.value
            val overlay = state.overlayState

            val location = state.currentLocation ?: run {
                _captureEvent.emit(CaptureEvent.Error("Waiting for GPS fix… Move outdoors."))
                return@launch
            }
            val resolvedOverlay = overlay ?: OverlayState(
                x = 0f, y = 0f, width = 0f, height = 0f,
                previewW = 1080, previewH = 1920, isCompact = false
            )

            _uiState.update { it.copy(isCapturing = true) }
            try {
                val cal         = Calendar.getInstance().apply { timeInMillis = location.timestampMs }
                val site        = state.siteMatch?.site
                val workspaceMode = prefs.getString("workspace_mode", "organization") ?: "organization"
                val isPersonal = workspaceMode == "personal"
                val operatorSession = if (isPersonal) null else operatorSessions.active()
                if (!isPersonal && operatorSession == null) {
                    _captureEvent.emit(CaptureEvent.Error("Start an operator session before capturing."))
                    return@launch
                }
                val personalTitle = prefs.getString("personal_title", "Personal")
                    ?.trim().orEmpty().ifBlank { "Personal" }
                val personalReference = prefs.getString("personal_reference", "New Evidence")
                    ?.trim().orEmpty().ifBlank { "New Evidence" }

                val operatorStr = if (isPersonal) personalTitle else (
                    operatorSession?.operatorName
                        ?: site?.operator
                        ?: "–"
                )
                val siteIdStr = if (isPersonal) personalReference else when {
                    site != null && state.siteMatch?.fallbackText?.startsWith("~") == true -> "~${site.siteId}"
                    site != null -> site.siteId
                    else -> "–"
                }

                val addrLine = when {
                    location.city.isNotBlank() && location.province.isNotBlank() ->
                        "Near ${location.city}, ${location.province}, Pakistan"
                    location.city.isNotBlank() -> location.city
                    location.addressLine.isNotBlank() -> location.addressLine.take(40)
                    else -> ""
                }

                val bearing  = location.bearing
                val dirLabel = when {
                    bearing < 0     -> "–"
                    bearing < 22.5 || bearing >= 337.5 -> "N"
                    bearing < 67.5  -> "NE"; bearing < 112.5 -> "E"
                    bearing < 157.5 -> "SE"; bearing < 202.5 -> "S"
                    bearing < 247.5 -> "SW"; bearing < 292.5 -> "W"
                    else            -> "NW"
                }
                val dirStr = if (bearing >= 0) "$dirLabel (${bearing.toInt()}°)" else "–"

                val altitudeLine = if (location.altitudeM != null) "%.1fm".format(location.altitudeM) else "–"

                val operatorFullName = when {
                    operatorStr.contains("Telenor", ignoreCase = true) -> "Telenor Pakistan"
                    operatorStr.contains("Jazz",    ignoreCase = true) -> "Jazz / Warid"
                    operatorStr.contains("Zong",    ignoreCase = true) -> "Zong Pakistan"
                    operatorStr.contains("Ufone",   ignoreCase = true) -> "Ufone (PTCL)"
                    else -> operatorStr
                }

                val evidenceId = VerificationEngine.newEvidenceId(location.timestampMs)
                val deviceIdentity = runCatching {
                    DeviceIdentityManager.snapshot(app)
                }.getOrNull()
                val locationRisk = location.mockLocationDetected || state.gpsStatus == GpsStatus.SPOOFED
                val integrityWarning = !location.isVerified
                val evidenceStatus = when {
                    locationRisk -> "LOCATION RISK"
                    integrityWarning -> "REGISTERED • WARNING"
                    else -> "REGISTERED"
                }
                val verificationPayload = VerificationEngine.qrPayload(
                    evidenceId = evidenceId,
                    capturedAt = location.timestampMs,
                    locationRisk = locationRisk,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyM = location.accuracyM.toDouble(),
                    workspaceMode = workspaceMode,
                    primaryValue = operatorStr,
                    secondaryValue = siteIdStr,
                    maskedDeviceIdentity = deviceIdentity?.maskedDeviceIdentity.orEmpty(),
                    deviceBrand = deviceIdentity?.brand.orEmpty(),
                    deviceModel = deviceIdentity?.model.orEmpty(),
                    captureKeyFingerprint = deviceIdentity?.captureKeyFingerprint.orEmpty()
                )

                val overlayData = OverlayRenderer.OverlayData(
                    locationLine     = "%.6f° %s, %.6f° %s".format(
                        Math.abs(location.latitude),  if (location.latitude  >= 0) "N" else "S",
                        Math.abs(location.longitude), if (location.longitude >= 0) "E" else "W"
                    ),
                    addressLine      = addrLine,
                    dateTimeLine     = SimpleDateFormat("dd MMM yyyy|EEEE|hh:mm a", Locale.ENGLISH).format(cal.time),
                    operatorLine     = operatorStr,
                    operatorFullName = operatorFullName,
                    siteIdLine       = siteIdStr,
                    workspaceMode    = workspaceMode,
                    primaryLabel     = if (isPersonal) "PROJECT / TITLE" else "ORGANIZATION",
                    secondaryLabel   = if (isPersonal) "REFERENCE" else "SITE ID",
                    accuracyLine     = "±%.0fm".format(location.accuracyM),
                    altitudeLine     = altitudeLine,
                    directionLine    = dirStr,
                    username         = config.username,
                    batteryPercentage = _batteryLevel.value,
                    networkType      = _networkStatus.value,
                    overlayScale     = config.overlayScale,
                    savedOverlayHeightFraction = config.savedOverlayHeightFraction,
                    overlayX         = resolvedOverlay.x, overlayY = resolvedOverlay.y,
                    overlayW         = resolvedOverlay.width, overlayH = resolvedOverlay.height,
                    previewW         = resolvedOverlay.previewW, previewH = resolvedOverlay.previewH,
                    backgroundAlpha  = config.overlayAlpha,
                    locationIntegrityRisk = locationRisk,
                    verificationId = evidenceId,
                    verificationPayload = verificationPayload,
                    evidenceStatus = evidenceStatus
                )

                val stampedFile = cameraManager.captureAndStamp(overlayData, outputDir)
                val slipThumbnailBase64 = withContext(Dispatchers.IO) {
                    EvidenceSlipMetadata.thumbnailBase64(stampedFile)
                }
                if (slipThumbnailBase64.isBlank()) {
                    _captureEvent.emit(CaptureEvent.Error("Visual evidence thumbnail could not be generated. Evidence was not registered."))
                    return@launch
                }
                val thumbnailBytes = android.util.Base64.decode(slipThumbnailBase64, android.util.Base64.DEFAULT)
                val thumbnailSha256 = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(thumbnailBytes).joinToString("") { "%02x".format(it) }
                if (!EvidenceVisualCache.save(app, evidenceId, slipThumbnailBase64, thumbnailSha256)) {
                    _captureEvent.emit(CaptureEvent.Error("Mandatory visual evidence could not be persisted. Evidence was not registered."))
                    return@launch
                }
                val aiVisual = withContext(Dispatchers.IO) {
                    AiVisualSummaryClient.analyze(
                        app,
                        slipThumbnailBase64,
                        operatorStr,
                        siteIdStr,
                        location.timestampMs
                    )
                }
                val slipSession = EvidenceSlipMetadata.sessionSnapshot(operatorSession, siteIdStr)

                // Enhancement happens once inside CameraManager before the overlay is drawn.
                // Never process the stamped JPEG again: that would alter the QR, text and logos.

                val imageSha256 = runCatching { VerificationEngine.sha256(stampedFile) }.getOrDefault("")
                val signedEvidence = runCatching {
                    DeviceIdentityManager.signEvidence(
                        context = app,
                        evidenceId = evidenceId,
                        imageSha256 = imageSha256,
                        capturedAt = location.timestampMs,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyM = location.accuracyM.toDouble(),
                        workspaceMode = workspaceMode,
                        primaryValue = operatorStr,
                        secondaryValue = siteIdStr,
                        locationRisk = locationRisk
                    )
                }.getOrNull()
                val integrityStatus = VerificationEngine.evidenceStatus(
                    gpsVerified = location.isVerified,
                    locationRisk = locationRisk || integrityWarning,
                    hashPresent = imageSha256.isNotBlank()
                )

                val historyCheckedAt = System.currentTimeMillis()
                val history = withContext(Dispatchers.IO) {
                    EvidenceHistoryEngine.analyze(
                        metadataDir = File(app.filesDir, "gallery_meta").also { it.mkdirs() },
                        latitude = location.latitude,
                        longitude = location.longitude,
                        captureTimestampMs = location.timestampMs,
                        workspaceMode = workspaceMode,
                        primaryEntity = if (isPersonal) personalTitle else operatorStr,
                        secondaryEntity = if (isPersonal) personalReference else (state.siteMatch?.site?.siteId ?: "")
                    )
                }
                val deviceProfile = DeviceProfileCollector.collect(app)
                val timeline = JSONArray().apply {
                    put(JSONObject().put("event", "GPS_ACCEPTED").put("at", location.timestampMs))
                    put(JSONObject().put("event", "LOCATION_HISTORY_CHECKED").put("at", historyCheckedAt))
                    put(JSONObject().put("event", "PHOTO_CAPTURED_AND_STAMPED").put("at", stampedFile.lastModified()))
                    put(JSONObject().put("event", "SHA256_GENERATED").put("at", System.currentTimeMillis()))
                    if (signedEvidence != null) {
                        put(JSONObject().put("event", "DEVICE_SIGNATURE_GENERATED").put("at", System.currentTimeMillis()))
                    }
                }

                try {
                    val siteId   = if (isPersonal) personalReference else (state.siteMatch?.site?.siteId ?: "")
                    val operator = if (isPersonal) personalTitle else (site?.operator ?: "")
                    val distM    = if (isPersonal) 0.0 else (state.siteMatch?.distanceM ?: 0.0)
                    val meta = JSONObject().apply {
                        put("lat",         location.latitude)
                        put("lon",         location.longitude)
                        put("accuracy",    location.accuracyM)
                        put("siteId",      siteId)
                        put("operator",    operator)
                        put("operatorSessionId", operatorSession?.id ?: "")
                        put("operatorSessionStartedAt", operatorSession?.startedAt ?: 0L)
                        put("operatorSessionOperatorId", operatorSession?.operatorId ?: "")
                        put("operatorSessionOperatorName", operatorSession?.operatorName ?: "")
                        put("thumbnailBase64", slipThumbnailBase64)
                        val slipKeys = slipSession.keys()
                        while (slipKeys.hasNext()) {
                            val slipKey = slipKeys.next()
                            put(slipKey, slipSession.opt(slipKey))
                        }
                        put("distanceM",   distM)
                        put("timestamp",   location.timestampMs)
                        put("username",    config.username)
                        put("city",        location.city)
                        put("province",    location.province)
                        put("gpsVerified", location.isVerified)
                        put("workspaceMode", workspaceMode)
                        put("primaryLabel", if (isPersonal) "Project / Title" else "Organization")
                        put("primaryValue", operator)
                        put("secondaryLabel", if (isPersonal) "Reference" else "Site ID")
                        put("secondaryValue", siteId)
                        put("locationIntegrityRisk", locationRisk)
                        put("locationIntegrityWarning", integrityWarning)
                        put("locationClaim", if (locationRisk) "Coordinates supplied to GeoStamp; physical location not independently established" else "Location fix accepted")
                        put("locationProvider", location.providerName)
                        put("locationIntegrityReason", location.integrityReason)
                        put("evidenceId", evidenceId)
                        put("verificationId", evidenceId) // compatibility alias; same value
                        put("verificationPayload", verificationPayload)
                        put("identityLabel", "Evidence ID")
                        put("imageSha256", imageSha256)
                        put("evidenceStatus", integrityStatus.name)
                        put("markerVersion", 3)
                        put("appVersion", runCatching {
                            app.packageManager.getPackageInfo(app.packageName, 0).versionName
                        }.getOrNull() ?: "unknown")
                        put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                        put("deviceManufacturer", Build.MANUFACTURER ?: "Unavailable")
                        put("deviceBrand", Build.BRAND ?: "Unavailable")
                        put("deviceHardwareModel", Build.MODEL ?: "Unavailable")
                        if (signedEvidence != null) {
                            put("geoStampDeviceIdentity", signedEvidence.snapshot.fullDeviceIdentity)
                            put("maskedGeoStampDeviceIdentity", signedEvidence.snapshot.maskedDeviceIdentity)
                            put("deviceIdentityVersion", signedEvidence.snapshot.identityVersion)
                            put("capturePublicKey", signedEvidence.snapshot.capturePublicKeyBase64)
                            put("captureKeyFingerprint", signedEvidence.snapshot.captureKeyFingerprint)
                            put("previousCaptureKeyFingerprint", signedEvidence.snapshot.previousCaptureKeyFingerprint ?: JSONObject.NULL)
                            put("captureKeyHardwareBacked", signedEvidence.snapshot.captureKeyHardwareBacked)
                            put("captureKeySecurityLevel", signedEvidence.snapshot.captureKeySecurityLevel)
                            put("captureSignature", signedEvidence.signatureBase64)
                            put("captureSignatureAlgorithm", signedEvidence.signatureAlgorithm)
                            put("captureSignedPayload", signedEvidence.canonicalPayload)
                            put("deviceContinuityFinding", "RECORDED_FOR_INTERNAL_COMPARISON")
                        }
                        put("deviceProfile", deviceProfile)
                        put("visitHistory", history.toJson())
                        put("sameDayPriorPhotos", history.sameDayPriorPhotos)
                        put("photosInLast90Days", history.photosInWindow)
                        put("visitSessionsLast90Days", history.distinctVisitSessions)
                        put("lastPreviousVisitMs", history.lastPreviousVisitMs ?: JSONObject.NULL)
                        put("firstVisitInWindowMs", history.firstVisitInWindowMs ?: JSONObject.NULL)
                        put("previousVerificationId", history.previousVerificationId ?: JSONObject.NULL)
                        put("trustedHistoryPhotos", history.trustedPhotos)
                        put("warningHistoryPhotos", history.warningPhotos)
                        put("riskHistoryPhotos", history.riskPhotos)
                        put("historyRadiusM", history.radiusM)
                        put("historyWindowDays", history.windowDays)
                        put("captureTimeline", timeline)
                    }
                    val metaFile = File(outputDir, stampedFile.nameWithoutExtension + ".meta")
                    metaFile.writeText(meta.toString())
                    val metaDir = File(app.filesDir, "gallery_meta").also { it.mkdirs() }
                    File(metaDir, stampedFile.nameWithoutExtension + ".meta").writeText(meta.toString())
                    runCatching {
                        val queuedFile = EvidenceRegistryOutbox.enqueue(app, meta)
                        viewModelScope.launch(Dispatchers.IO) {
                            registrySync.publishNow(queuedFile)
                        }
                    }
                } catch (_: Exception) {}

                val saved = saveToGalleryInternal(stampedFile, operatorStr, siteIdStr, isPersonal)
                if (saved) {
                    if (!isPersonal) {
                        operatorSessions.recordCapture(siteIdStr)
                        _uiState.update { it.copy(operatorSession = operatorSessions.active()) }
                    }
                    stampedFile.delete()
                    _captureEvent.emit(CaptureEvent.SavedToGallery)
                } else {
                    _captureEvent.emit(CaptureEvent.SavedPreview(stampedFile))
                }
            } catch (e: Exception) {
                _captureEvent.emit(CaptureEvent.Error("Capture failed: ${e.message}"))
            } finally {
                _uiState.update { it.copy(isCapturing = false) }
            }
        }
    }

    private suspend fun saveToGalleryInternal(
        file: File,
        operatorName: String,
        siteId: String,
        isPersonal: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = app.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    val safeOperator = operatorName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Organization" }
                    val safeSite = siteId.removePrefix("~").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Unassigned" }
                    val relativeFolder = if (isPersonal)
                        "Pictures/GeoStamp/Personal"
                    else
                        "Pictures/GeoStamp/$safeOperator/$safeSite"
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativeFolder)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val pics   = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val safeOperator = operatorName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Organization" }
                val safeSite = siteId.removePrefix("~").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Unassigned" }
                val folder = if (isPersonal)
                    File(pics, "GeoStamp/Personal")
                else
                    File(pics, "GeoStamp/$safeOperator/$safeSite")
                if (!folder.exists() && !folder.mkdirs()) return@withContext false
                val dest = File(folder, file.name)
                file.copyTo(dest, overwrite = true)
                @Suppress("DEPRECATION")
                app.sendBroadcast(
                    android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                        .apply { data = android.net.Uri.fromFile(dest) })
                true
            }
        } catch (_: Exception) { false }
    }

    @Deprecated("Identity now comes from the verified sign-in profile")
    fun updateUsername(name: String) { /* intentionally ignored until account sign-in is connected */ }
    fun updateStampConfig(config: StampConfig) { _stampConfig.update { config } }

    fun updateOverlayAlpha(alpha: Float) {
        val c = alpha.coerceIn(0.2f, 0.9f)
        prefs.edit().putFloat("overlay_alpha", c).apply()
        _stampConfig.update { it.copy(overlayAlpha = c) }
    }

    fun updateOverlayScale(scale: Float) {
        val s = (Math.round(scale / 0.05f) * 0.05f).coerceIn(0.8f, 1.2f)
        prefs.edit().putFloat("overlay_scale", s).apply()
        _stampConfig.update { it.copy(overlayScale = s) }
    }

    fun updateSavedOverlayHeight(percent: Float) {
        val fraction = (percent / 100f).coerceIn(0.20f, 0.30f)
        prefs.edit().putFloat("saved_overlay_height_fraction", fraction).apply()
        _stampConfig.update { it.copy(savedOverlayHeightFraction = fraction) }
    }

    fun updateLiveInfoMode(mode: LiveInfoMode) {
        prefs.edit()
            .putString("live_info_mode", mode.name)
            .putBoolean("show_info_card", mode == LiveInfoMode.FLOATING)
            .putBoolean("show_info_strip", mode == LiveInfoMode.BOTTOM)
            .apply()
        _stampConfig.update { it.copy(liveInfoMode = mode, showInfoCard = false, showInfoStrip = false) }
    }

    @Deprecated("Use updateLiveInfoMode")
    fun updateShowInfoCard(show: Boolean) = updateLiveInfoMode(if (show) LiveInfoMode.FLOATING else LiveInfoMode.OFF)

    @Deprecated("Use updateLiveInfoMode")
    fun updateShowInfoStrip(show: Boolean) = updateLiveInfoMode(if (show) LiveInfoMode.BOTTOM else LiveInfoMode.OFF)

    fun updateColorScheme(scheme: com.axiominfratech.geostamp.overlay.OverlayColorScheme) {
        prefs.edit().putString("color_scheme", scheme.name).apply()
        _stampConfig.update { it.copy(colorScheme = scheme) }
    }

    fun updateCustomColor(rgb: Int) {
        prefs.edit().putInt("custom_color_rgb", rgb).apply()
        _stampConfig.update { it.copy(
            customColorRgb = rgb,
            colorScheme = com.axiominfratech.geostamp.overlay.OverlayColorScheme.CUSTOM
        ) }
    }

    fun clearImportResult() { _uiState.update { it.copy(lastImportResult = null) } }

    fun toggleGrid() { _uiState.update { it.copy(showGrid = !it.showGrid) } }

    fun cycleTimer(): Int {
        val next = when (_uiState.value.timerSeconds) {
            0  -> 3
            3  -> 5
            5  -> 10
            else -> 0
        }
        _uiState.update { it.copy(timerSeconds = next) }
        return next
    }

    fun syncAndRecompute() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = siteRepo.syncFromGitHub()
            val total  = siteRepo.getCount()
            _uiState.update { it.copy(
                isSyncing = false, dbSiteCount = total,
                lastImportResult = result.message
            )}
            loadDatasetOperators()
            val loc = _uiState.value.currentLocation
            if (loc != null) precomputeNearestForAllOperators(loc.latitude, loc.longitude)
        }
    }

    private fun loadDatabaseStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(dbSiteCount = siteRepo.getCount()) }
        }
    }

    private fun loadDatasetOperators() {
        viewModelScope.launch {
            _datasetOperators.value = siteRepo.getDistinctOperators()
        }
    }

    fun syncFromGitHub() {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, lastImportResult = null) }
            val result = siteRepo.syncFromGitHub()
            val total  = siteRepo.getCount()
            _uiState.update {
                it.copy(isImporting = false, dbSiteCount = total, lastImportResult = result.message)
            }
            loadDatasetOperators()
        }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            val (success, errors) = siteRepo.importFromCsv(uri)
            val total = siteRepo.getCount()
            _uiState.update {
                it.copy(isImporting = false, dbSiteCount = total,
                    lastImportResult = "Imported $success sites ($errors errors)")
            }
            loadDatasetOperators()
        }
    }

    data class OverlayState(
        val x: Float, val y: Float,
        val width: Float, val height: Float,
        val previewW: Int, val previewH: Int,
        val isCompact: Boolean
    )

    data class UiState(
        val currentLocation:     LocationEngine.GeoStampLocation? = null,
        val siteMatch:           SiteRepository.SiteMatchResult?  = null,
        val nearestPerOperator:  Map<Operator, SiteRepository.SiteMatchResult> = emptyMap(),
        val gpsStatus:           GpsStatus       = GpsStatus.SEARCHING,
        val locationError:       String?          = null,
        val isCapturing:         Boolean          = false,
        val isImporting:         Boolean          = false,
        val securityWarnings:    List<AntiSpoofManager.SecurityWarning> = emptyList(),
        val dbSiteCount:         Int              = 0,
        val lastImportResult:    String?          = null,
        val overlayState:        OverlayState?    = null,
        val showGrid:            Boolean          = false,
        val timerSeconds:        Int              = 0,
        val isSyncing:           Boolean          = false,
        val operatorSession:     OperatorSessionManager.Session? = null
    )

    enum class GpsStatus { SEARCHING, LOCKED, SPOOFED }

    sealed class CaptureEvent {
        object SavedToGallery : CaptureEvent()
        data class SavedPreview(val file: File) : CaptureEvent()
        data class Error(val message: String)   : CaptureEvent()
    }
}
