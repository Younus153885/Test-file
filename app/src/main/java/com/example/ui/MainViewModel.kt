package com.example.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.engine.GhostDaemonEngine
import com.example.data.engine.ResetExecutionEngine
import com.example.data.engine.ResetProgressState
import com.example.data.generator.IdentityGenerator
import com.example.data.local.AiSecurityReportEntity
import com.example.data.local.AppDatabase
import com.example.data.local.DeviceProfileEntity
import com.example.data.local.ResetLogEntity
import com.example.data.local.TargetAppEntity
import com.example.data.model.DeviceIdentity
import com.example.data.model.SystemPermissionsStatus
import com.example.data.model.TimerSettings
import com.example.data.remote.AiAuditResult
import com.example.data.remote.GeminiService
import com.google.firebase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppNavTab {
    DASHBOARD,
    TIMER,
    APPS,
    PROFILES,
    LOGS,
    DAEMON,
    AI_AUDIT
}

enum class TargetAppFilter {
    ALL,
    USER_APPS,
    SYSTEM_APPS,
    SELECTED_ONLY
}

data class WalkJoystickState(
    val isExpanded: Boolean = false,
    val isAutoHoldLocked: Boolean = false,
    val isDragging: Boolean = false,
    val knobX: Float = 0f,
    val knobY: Float = 0f,
    val magnitude: Float = 0f,
    val bearingDegrees: Float = 0f,
    val speedMps: Float = 0f,
    val stepsCount: Int = 0,
    val distanceMeters: Double = 0.0,
    val liveLatitude: Double = 23.8103,
    val liveLongitude: Double = 90.4125,
    val isVolumeSpeedMode: Boolean = false,
    val appSpeedMultiplier: Float = 1.0f,
    val currentZoneLabel: String = "",
    val isNftValidationRunning: Boolean = false,
    val nftValidationPhaseLabel: String = "",
    val nftSpot1Seconds: Int = 0,
    val nftSpot2Seconds: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val profileDao = db.deviceProfileDao()
    private val resetLogDao = db.resetLogDao()
    private val aiReportDao = db.aiReportDao()
    private val targetAppDao = db.targetAppDao()

    val daemonEngine = GhostDaemonEngine(viewModelScope)
    val daemonState = daemonEngine.daemonState

    // Navigation & Modals
    private val _currentTab = MutableStateFlow(AppNavTab.DASHBOARD)
    val currentTab: StateFlow<AppNavTab> = _currentTab.asStateFlow()

    private val _showSystemRequirements = MutableStateFlow(false)
    val showSystemRequirements: StateFlow<Boolean> = _showSystemRequirements.asStateFlow()

    private val _showResetModal = MutableStateFlow(false)
    val showResetModal: StateFlow<Boolean> = _showResetModal.asStateFlow()

    private val _showFloatingBadge = MutableStateFlow(false)
    val showFloatingBadge: StateFlow<Boolean> = _showFloatingBadge.asStateFlow()

    // License & Admin Panel System (Strictly locked by default to prevent any bypass)
    val licenseRepository = com.example.data.license.LicenseRepository(application)

    private val isInitiallyValidLocal: Boolean = run {
        try {
            licenseRepository.isLicenseValidLocal()
        } catch (_: Throwable) {
            false
        }
    }

    private val _isLicenseValid = MutableStateFlow(isInitiallyValidLocal)
    val isLicenseValid: StateFlow<Boolean> = _isLicenseValid.asStateFlow()

    private val _isCheckingLicense = MutableStateFlow(true)
    val isCheckingLicense: StateFlow<Boolean> = _isCheckingLicense.asStateFlow()

    private val _isAppInitialLoading = MutableStateFlow(true)
    val isAppInitialLoading: StateFlow<Boolean> = _isAppInitialLoading.asStateFlow()

    private val _licenseErrorMessage = MutableStateFlow<String?>(null)
    val licenseErrorMessage: StateFlow<String?> = _licenseErrorMessage.asStateFlow()

    private val _publicPackages = MutableStateFlow<List<com.example.data.license.PackageModel>>(
        com.example.data.license.LicenseApiClient.defaultPackages()
    )
    val publicPackages: StateFlow<List<com.example.data.license.PackageModel>> = _publicPackages.asStateFlow()

    private val _publicSettings = MutableStateFlow(com.example.data.license.PublicSettings())
    val publicSettings: StateFlow<com.example.data.license.PublicSettings> = _publicSettings.asStateFlow()

    private val _pendingTransferLicense = MutableStateFlow<com.example.data.license.LicenseModel?>(null)
    val pendingTransferLicense: StateFlow<com.example.data.license.LicenseModel?> = _pendingTransferLicense.asStateFlow()

    private val _showAdminPanel = MutableStateFlow(false)
    val showAdminPanel: StateFlow<Boolean> = _showAdminPanel.asStateFlow()

    private val _adminToken = MutableStateFlow<String?>(licenseRepository.getAdminToken())
    val adminToken: StateFlow<String?> = _adminToken.asStateFlow()

    private var realtimeSettingsJob: Job? = null
    private var realtimePackagesJob: Job? = null
    private var realtimeLicenseJob: Job? = null
    private var licenseExpiryTickerJob: Job? = null
    private var checkLicenseInFlightJob: Job? = null
    private var checkUpdateInFlightJob: Job? = null

    private fun syncLicenseStateToSystem() {
        val sysEnabled = _publicSettings.value.licenseSystemEnabled
        val isValid = !sysEnabled || _isLicenseValid.value
        licenseRepository.setLicenseSystemEnabledLocal(sysEnabled)
        licenseRepository.setLicenseValidStateLocal(isValid)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val allApps = try {
                    targetAppDao.getAllTargetAppsList()
                } catch (_: Throwable) {
                    emptyList()
                }
                val selectedPkgs = allApps
                    .filter { it.isSelected && !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it.packageName) }
                    .map { it.packageName }
                ResetExecutionEngine.writeXposedIdentityFile(
                    identity = _currentIdentity.value,
                    context = app,
                    isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                    targetPackageNames = selectedPkgs,
                    removedPackageNames = emptyList(),
                    newlyAddedPackageNames = emptyList(),
                    rootBypassPackageNames = _rootBypassPackages.value.toList(),
                    stepWalkPackageNames = _stepWalkPackages.value.toList(),
                    speedHackPackageNames = _speedHackPackages.value.toList(),
                    adBypassPackageNames = _adBypassPackages.value.toList(),
                    applyTimezoneBroadcast = false
                )
            } catch (_: Throwable) {}
        }
    }

    private fun applyVerificationResult(result: com.example.data.license.LicenseVerificationResult) {
        if (!_publicSettings.value.licenseSystemEnabled) {
            _isLicenseValid.value = true
            _licenseErrorMessage.value = null
            syncLicenseStateToSystem()
            return
        }
        when (result) {
            is com.example.data.license.LicenseVerificationResult.Valid -> {
                _isLicenseValid.value = true
                _licenseErrorMessage.value = null
                startLicenseExpiryTicker()
            }
            is com.example.data.license.LicenseVerificationResult.ActiveOnOtherDevice -> {
                _isLicenseValid.value = false
                _pendingTransferLicense.value = result.license
            }
            is com.example.data.license.LicenseVerificationResult.Expired -> {
                _isLicenseValid.value = false
                _licenseErrorMessage.value = com.example.ui.i18n.tr(
                    "Your license has expired. Please activate with a new license key.",
                    "আপনার লাইসেন্সের মেয়াদ শেষ হয়ে গেছে। নতুন লাইসেন্স কি দিয়ে অ্যাক্টিভেট করুন।",
                    "आपका लाइसेंस समाप्त हो गया है। कृपया नई लाइसेंस कुंजी से सक्रिय करें।"
                )
            }
            is com.example.data.license.LicenseVerificationResult.Suspended -> {
                _isLicenseValid.value = false
                _licenseErrorMessage.value = com.example.ui.i18n.tr(
                    "This license has been deactivated / blocked by Admin.",
                    "এই লাইসেন্সটি এডমিন কর্তৃক ডিএক্টিভ / ব্লক করা হয়েছে।",
                    "यह लाइसेंस एडमिन द्वारा निष्क्रिय / ब्लॉक कर दिया गया है।"
                )
            }
            is com.example.data.license.LicenseVerificationResult.NotFound -> {
                _isLicenseValid.value = false
            }
            is com.example.data.license.LicenseVerificationResult.Inactive -> {
                _isLicenseValid.value = false
            }
            is com.example.data.license.LicenseVerificationResult.Error -> {
                _isLicenseValid.value = false
                _licenseErrorMessage.value = result.message
            }
        }
        syncLicenseStateToSystem()
    }

    private fun startLicenseExpiryTicker() {
        if (licenseExpiryTickerJob?.isActive == true) return
        licenseExpiryTickerJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var tickCount = 0
            while (true) {
                kotlinx.coroutines.delay(3_000L)
                tickCount++
                if (tickCount % 5 == 0) {
                    checkAppUpdate()
                }
                // Always pull fresh settings (including promo notifications) directly from server every 3 seconds
                try {
                    val freshSettings = licenseRepository.apiClient.getFreshSettingsFromServer()
                    if (freshSettings != null) {
                        val prevLicenseEnabled = _publicSettings.value.licenseSystemEnabled
                        _publicSettings.value = freshSettings
                        licenseRepository.syncPublicSettingsToProvider(freshSettings)
                        if (prevLicenseEnabled != freshSettings.licenseSystemEnabled && !freshSettings.licenseSystemEnabled) {
                            _isLicenseValid.value = true
                            _licenseErrorMessage.value = null
                            syncLicenseStateToSystem()
                        }
                    }
                } catch (_: Exception) {}

                if (!_publicSettings.value.licenseSystemEnabled) continue
                if (_isLicenseValid.value && licenseRepository.isSavedLicenseExpiredNow()) {
                    licenseRepository.markSavedLicenseExpired()
                    if (licenseRepository.isSavedLicenseExpiredNow()) {
                        _isLicenseValid.value = false
                        _licenseErrorMessage.value = com.example.ui.i18n.tr(
                            "Your license has expired. Please activate with a new license key.",
                            "আপনার লাইসেন্সের মেয়াদ শেষ হয়ে গেছে। নতুন লাইসেন্স কি দিয়ে অ্যাক্টিভেট করুন।",
                            "आपका लाइसेंस समाप्त हो गया है। कृपया नई लाइसेंस कुंजी से सक्रिय करें।"
                        )
                        syncLicenseStateToSystem()
                    } else {
                        // Admin extended the license duration on Firestore!
                        _isLicenseValid.value = true
                        _licenseErrorMessage.value = null
                        syncLicenseStateToSystem()
                    }
                } else if (!_isLicenseValid.value && !licenseRepository.getSavedLicenseKey().isNullOrBlank()) {
                    // Periodically check if Admin extended or unblocked the license key in Firestore
                    val (_, res) = licenseRepository.checkLicenseStatus()
                    if (res is com.example.data.license.LicenseVerificationResult.Valid) {
                        applyVerificationResult(res)
                    }
                }
            }
        }
    }

    private fun startRealtimeLicenseWatchers() {
        startLicenseExpiryTicker()

        if (realtimeSettingsJob == null || realtimeSettingsJob?.isActive != true) {
            realtimeSettingsJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                while (true) {
                    try {
                        licenseRepository.observeSettings().collect { settings ->
                            val prevEnabled = _publicSettings.value.licenseSystemEnabled
                            _publicSettings.value = settings
                            licenseRepository.syncPublicSettingsToProvider(settings)
                            if (!settings.licenseSystemEnabled) {
                                _isLicenseValid.value = true
                                _licenseErrorMessage.value = null
                                syncLicenseStateToSystem()
                            } else if (!prevEnabled || !_isLicenseValid.value) {
                                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val (_, result) = licenseRepository.checkLicenseStatus()
                                    applyVerificationResult(result)
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "observeSettings stream closed, retrying: ${e.message}")
                    }
                    kotlinx.coroutines.delay(2_000L)
                }
            }
        }

        if (realtimePackagesJob == null || realtimePackagesJob?.isActive != true) {
            realtimePackagesJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                while (true) {
                    try {
                        licenseRepository.observeActivePackages().collect { pkgs ->
                            if (pkgs.isNotEmpty()) {
                                _publicPackages.value = pkgs
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "observeActivePackages stream closed, retrying: ${e.message}")
                    }
                    kotlinx.coroutines.delay(3_000L)
                }
            }
        }

        restartActiveKeyWatcher()
    }

    private fun restartActiveKeyWatcher() {
        realtimeLicenseJob?.cancel()
        val savedKey = licenseRepository.getSavedLicenseKey() ?: return
        if (savedKey.isBlank()) return

        realtimeLicenseJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                licenseRepository.observeSavedLicenseStatus().collect { result ->
                    applyVerificationResult(result)
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "observeSavedLicenseStatus stream closed: ${e.message}")
            }
        }
    }

    fun checkLicenseStatus() {
        if (checkLicenseInFlightJob?.isActive == true) return
        checkLicenseInFlightJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isCheckingLicense.value = true
            try {
                startRealtimeLicenseWatchers()
                val (settings, result) = licenseRepository.checkLicenseStatus()
                _publicSettings.value = settings
                licenseRepository.syncPublicSettingsToProvider(settings)
                val pkgs = licenseRepository.apiClient.getPackages().filter { it.active }
                if (pkgs.isNotEmpty()) {
                    _publicPackages.value = pkgs
                }
                applyVerificationResult(result)
            } catch (e: Exception) {
                Log.e("MainViewModel", "checkLicenseStatus error: ${e.message}", e)
                if (isInitiallyValidLocal) {
                    _isLicenseValid.value = true
                } else {
                    _isLicenseValid.value = false
                }
            } finally {
                _isCheckingLicense.value = false
                kotlinx.coroutines.delay(550L)
                _isAppInitialLoading.value = false
            }
        }
    }

    fun verifyEnteredLicenseKey(key: String) {
        viewModelScope.launch {
            _isCheckingLicense.value = true
            _licenseErrorMessage.value = null
            try {
                startRealtimeLicenseWatchers()
                when (val result = licenseRepository.verifyEnteredKey(key)) {
                    is com.example.data.license.LicenseVerificationResult.Valid -> {
                        _isLicenseValid.value = true
                        _licenseErrorMessage.value = null
                        syncLicenseStateToSystem()
                        restartActiveKeyWatcher()
                        startLicenseExpiryTicker()
                    }
                    is com.example.data.license.LicenseVerificationResult.Inactive -> {
                        val actRes = licenseRepository.activateLicense(result.license)
                        actRes.onSuccess {
                            _isLicenseValid.value = true
                            _licenseErrorMessage.value = null
                            syncLicenseStateToSystem()
                            restartActiveKeyWatcher()
                            startLicenseExpiryTicker()
                        }.onFailure { e ->
                            _isLicenseValid.value = false
                            _licenseErrorMessage.value = e.message ?: com.example.ui.i18n.tr(
                                "Activation failed",
                                "অ্যাক্টিভেশন ব্যর্থ হয়েছে",
                                "सक्रियण विफल रहा"
                            )
                            syncLicenseStateToSystem()
                        }
                    }
                    is com.example.data.license.LicenseVerificationResult.ActiveOnOtherDevice -> {
                        _isLicenseValid.value = false
                        _pendingTransferLicense.value = result.license
                        syncLicenseStateToSystem()
                    }
                    is com.example.data.license.LicenseVerificationResult.Expired -> {
                        _isLicenseValid.value = false
                        _licenseErrorMessage.value = com.example.ui.i18n.tr(
                            "This license has expired.",
                            "এই লাইসেন্সটির মেয়াদ শেষ হয়ে গেছে।",
                            "इस लाइसेंस की अवधि समाप्त हो गई है।"
                        )
                        syncLicenseStateToSystem()
                    }
                    is com.example.data.license.LicenseVerificationResult.Suspended -> {
                        _isLicenseValid.value = false
                        _licenseErrorMessage.value = com.example.ui.i18n.tr(
                            "This license has been deactivated / blocked by Admin.",
                            "এই লাইসেন্সটি এডমিন কর্তৃক ডিএক্টিভ / ব্লক করা হয়েছে।",
                            "यह लाइसेंस एडमिन द्वारा निष्क्रिय / ब्लॉक कर दिया गया है।"
                        )
                        syncLicenseStateToSystem()
                    }
                    is com.example.data.license.LicenseVerificationResult.NotFound -> {
                        _isLicenseValid.value = false
                        _licenseErrorMessage.value = com.example.ui.i18n.tr(
                            "Invalid license key! Please enter a valid key.",
                            "ভুল লাইসেন্স কি! সঠিক কি লিখুন।",
                            "अमान्य लाइसेंस कुंजी! कृपया सही कुंजी दर्ज करें।"
                        )
                        syncLicenseStateToSystem()
                    }
                    is com.example.data.license.LicenseVerificationResult.Error -> {
                        _isLicenseValid.value = false
                        _licenseErrorMessage.value = result.message
                        syncLicenseStateToSystem()
                    }
                }
            } catch (e: Exception) {
                _isLicenseValid.value = false
                _licenseErrorMessage.value = com.example.ui.i18n.tr(
                    "Verification error: ${e.message}",
                    "যাচাই ত্রুটি: ${e.message}",
                    "सत्यापन त्रुटि: ${e.message}"
                )
                syncLicenseStateToSystem()
            } finally {
                _isCheckingLicense.value = false
            }
        }
    }

    fun confirmLicenseTransfer(license: com.example.data.license.LicenseModel) {
        viewModelScope.launch {
            _isCheckingLicense.value = true
            _pendingTransferLicense.value = null
            try {
                val res = licenseRepository.transferLicense(license)
                res.onSuccess {
                    _isLicenseValid.value = true
                    _licenseErrorMessage.value = null
                    syncLicenseStateToSystem()
                    restartActiveKeyWatcher()
                    startLicenseExpiryTicker()
                }.onFailure { e ->
                    _isLicenseValid.value = false
                    _licenseErrorMessage.value = e.message ?: com.example.ui.i18n.tr(
                        "Transfer failed",
                        "ট্রান্সফার ব্যর্থ হয়েছে",
                        "स्थानांतरण विफल रहा"
                    )
                    syncLicenseStateToSystem()
                }
            } finally {
                _isCheckingLicense.value = false
            }
        }
    }

    fun dismissLicenseTransfer() {
        _pendingTransferLicense.value = null
    }

    fun toggleAdminPanel(show: Boolean) {
        _showAdminPanel.value = show
        if (!show) {
            checkLicenseStatus()
        }
    }

    fun onAdminLoginSuccess(token: String, email: String) {
        licenseRepository.saveAdminToken(token, email)
        _adminToken.value = token
        startRealtimeLicenseWatchers()
    }

    fun onAdminLogout() {
        licenseRepository.clearAdminToken()
        _adminToken.value = null
    }


    // Permissions & Prerequisites
    private val _permissionsStatus = MutableStateFlow(SystemPermissionsStatus())
    val permissionsStatus: StateFlow<SystemPermissionsStatus> = _permissionsStatus.asStateFlow()

    private val _permissionWarningMessage = MutableStateFlow<String?>(null)
    val permissionWarningMessage: StateFlow<String?> = _permissionWarningMessage.asStateFlow()

    fun dismissPermissionWarning() {
        _permissionWarningMessage.value = null
    }

    // Identity & Shield State (initialized non-blocking; live root/prop read runs in init on Dispatchers.IO)
    private val _realDeviceIdentity = MutableStateFlow<DeviceIdentity>(com.example.data.device.RealDeviceReader.getDynamicFallbackIdentity(application))
    val realDeviceIdentity: StateFlow<DeviceIdentity> = _realDeviceIdentity.asStateFlow()

    private val _currentIdentity = MutableStateFlow<DeviceIdentity>(_realDeviceIdentity.value)
    val currentIdentity: StateFlow<DeviceIdentity> = _currentIdentity.asStateFlow()

    private val _isShieldActive = MutableStateFlow(false)
    val isShieldActive: StateFlow<Boolean> = _isShieldActive.asStateFlow()

    private val appPrefs = application.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)

    // Full Phone (Global Spoof) Mode state controlled from the top header button
    private val _isGlobalSpoofEnabled = MutableStateFlow(
        appPrefs.getBoolean("global_spoof_enabled", false)
    )
    val isGlobalSpoofEnabled: StateFlow<Boolean> = _isGlobalSpoofEnabled.asStateFlow()

    // Per-App Root Restriction Bypass packages (allows using root-restricted apps on a rooted phone)
    private val _rootBypassPackages = MutableStateFlow<Set<String>>(
        appPrefs.getStringSet("root_bypass_packages", emptySet())?.toSet() ?: emptySet()
    )
    val rootBypassPackages: StateFlow<Set<String>> = _rootBypassPackages.asStateFlow()

    // Per-App Fake Location & Step Walk Counting packages
    private val _stepWalkPackages = MutableStateFlow<Set<String>>(
        appPrefs.getStringSet("step_walk_packages", emptySet())?.toSet() ?: emptySet()
    )
    val stepWalkPackages: StateFlow<Set<String>> = _stepWalkPackages.asStateFlow()

    // Per-App Speed Control (Fast / Slow Time-Scale Multiplier) packages
    private val _speedHackPackages = MutableStateFlow<Set<String>>(
        appPrefs.getStringSet("speed_hack_packages", emptySet())?.toSet() ?: emptySet()
    )
    val speedHackPackages: StateFlow<Set<String>> = _speedHackPackages.asStateFlow()

    // Per-App Instant Ad Complete & No-Ad Bypass packages
    private val _adBypassPackages = MutableStateFlow<Set<String>>(
        appPrefs.getStringSet("ad_bypass_packages", emptySet())?.toSet() ?: emptySet()
    )
    val adBypassPackages: StateFlow<Set<String>> = _adBypassPackages.asStateFlow()

    // Per-App Standalone APK Builder packages (builds a standalone installable APK only when enabled for an app)
    private val _standaloneApkPackages = MutableStateFlow<Set<String>>(
        appPrefs.getStringSet("standalone_apk_packages", emptySet())?.toSet() ?: emptySet()
    )
    val standaloneApkPackages: StateFlow<Set<String>> = _standaloneApkPackages.asStateFlow()

    // Per-App Multi-Install ("আলাদা APK / Separate APK Per Identity") toggle packages
    private val _multiInstallApkPackages = MutableStateFlow<Set<String>>(
        appPrefs.getStringSet("multi_install_apk_packages", emptySet())?.toSet() ?: emptySet()
    )
    val multiInstallApkPackages: StateFlow<Set<String>> = _multiInstallApkPackages.asStateFlow()

    // Per-App Install Mode when Multi-Install is OFF ("Main Apk Replace" vs "Without Main Apk Replace")
    private val _replaceMainApkPackages = MutableStateFlow<Set<String>>(
        appPrefs.getStringSet("replace_main_apk_packages", emptySet())?.toSet() ?: emptySet()
    )
    val replaceMainApkPackages: StateFlow<Set<String>> = _replaceMainApkPackages.asStateFlow()

    private val _standaloneArtifacts = MutableStateFlow<Map<String, com.example.data.engine.StandaloneApkArtifact>>(emptyMap())
    val standaloneArtifacts: StateFlow<Map<String, com.example.data.engine.StandaloneApkArtifact>> = _standaloneArtifacts.asStateFlow()

    private var standaloneRebuildJob: Job? = null
    private val standaloneBuildMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var cachedSelectedTargetPackages: Set<String> = emptySet()

    // Smart 360° Joystick & Step Walk Controller State (synced with WalkBridge for on-screen floating overlay)
    private val _walkJoystickState = com.example.service.WalkBridge.mutableState
    val walkJoystickState: StateFlow<WalkJoystickState> = _walkJoystickState.asStateFlow()
    val isFloatingJoystickRunning: StateFlow<Boolean> = com.example.service.WalkBridge.isFloatingServiceRunning

    fun toggleFloatingJoystick() {
        val app = getApplication<android.app.Application>()
        if (com.example.service.WalkBridge.isFloatingServiceRunning.value) {
            com.example.service.WalkBridge.stopFloatingService(app)
        } else {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val hasOverlay = try {
                    android.provider.Settings.canDrawOverlays(app)
                } catch (_: Throwable) {
                    false
                }
                if (!hasOverlay && com.example.data.device.RootChecker.isDeviceRooted()) {
                    com.example.data.device.RootChecker.executeRootCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow 2>/dev/null || true")
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.example.service.WalkBridge.startFloatingService(app)
                }
            }
        }
    }

    private var walkSimulationJob: Job? = null
    private var nftValidationJob: Job? = null
    private var walkPropSyncInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private var walkStepFractionAccumulator = 0.0

    private val _lastResetSummary = MutableStateFlow("Ready for Reset")
    val lastResetSummary: StateFlow<String> = _lastResetSummary.asStateFlow()

    // Reset Progress State
    private val _resetProgressState = MutableStateFlow<ResetProgressState?>(null)
    val resetProgressState: StateFlow<ResetProgressState?> = _resetProgressState.asStateFlow()

    private val _isResetting = MutableStateFlow(false)
    val isResetting: StateFlow<Boolean> = _isResetting.asStateFlow()

    // Timer State
    private val _timerSettings = MutableStateFlow(TimerSettings())
    val timerSettings: StateFlow<TimerSettings> = _timerSettings.asStateFlow()

    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning: StateFlow<Boolean> = _isTimerRunning.asStateFlow()

    private val _timerRemainingSeconds = MutableStateFlow(0)
    val timerRemainingSeconds: StateFlow<Int> = _timerRemainingSeconds.asStateFlow()

    private var timerJob: Job? = null

    // Target Apps
    private val _appsSearchQuery = MutableStateFlow("")
    val appsSearchQuery: StateFlow<String> = _appsSearchQuery.asStateFlow()

    private val _appFilter = MutableStateFlow(TargetAppFilter.ALL)
    val appFilter: StateFlow<TargetAppFilter> = _appFilter.asStateFlow()

    private val _isScanningApps = MutableStateFlow(false)
    val isScanningApps: StateFlow<Boolean> = _isScanningApps.asStateFlow()

    val targetApps: StateFlow<List<TargetAppEntity>> = combine(
        targetAppDao.getAllTargetApps(),
        _appsSearchQuery,
        _appFilter
    ) { apps, query, filter ->
        apps.filter { app ->
            val matchesQuery = query.isEmpty() ||
                    app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                TargetAppFilter.ALL -> true
                TargetAppFilter.USER_APPS -> !app.isSystemApp
                TargetAppFilter.SYSTEM_APPS -> app.isSystemApp
                TargetAppFilter.SELECTED_ONLY -> app.isSelected
            }
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedAppsCount: StateFlow<Int> = targetAppDao.getSelectedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    // Profiles & Logs
    val savedProfiles: StateFlow<List<DeviceProfileEntity>> = profileDao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val resetLogs: StateFlow<List<ResetLogEntity>> = resetLogDao.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aiReports: StateFlow<List<AiSecurityReportEntity>> = aiReportDao.getAllReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // AI Analysis State
    private val _isAiAnalyzing = MutableStateFlow(false)
    val isAiAnalyzing: StateFlow<Boolean> = _isAiAnalyzing.asStateFlow()

    private val _latestAiAudit = MutableStateFlow<AiAuditResult?>(null)
    val latestAiAudit: StateFlow<AiAuditResult?> = _latestAiAudit.asStateFlow()

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

    // Update Checker State
    private val _updateInfo = MutableStateFlow<com.example.data.remote.UpdateInfo?>(null)
    val updateInfo: StateFlow<com.example.data.remote.UpdateInfo?> = _updateInfo.asStateFlow()

    init {
        com.example.service.WalkBridge.onCustomMapLocationApplied = { lat, lng, label ->
            _currentIdentity.value = _currentIdentity.value.copy(
                latitude = lat,
                longitude = lng,
                locationName = label
            )
            daemonEngine.addManualLog(
                "WORLD_MAP_GPS >> Selected Map Location: $label (${String.format(Locale.US, "%.5f, %.5f", lat, lng)})",
                "CYAN"
            )
        }

        checkAppUpdate()

        checkLicenseStatus()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Self-heal network routing table or stuck airplane mode if affected by any prior session
            if (com.example.data.device.RootChecker.isDeviceRooted()) {
                com.example.data.device.RootChecker.executeRootCommand(
                    """
                    if [ ! -f /data/misc/net/rt_tables ]; then
                        mkdir -p /data/misc/net 2>/dev/null || true
                        printf "255\tlocal\n254\tmain\n253\tdefault\n0\tunspec\n" > /data/misc/net/rt_tables 2>/dev/null || true
                        chmod 644 /data/misc/net/rt_tables 2>/dev/null || true
                    fi
                    if [ "${'$'}(settings get global airplane_mode_on 2>/dev/null)" = "1" ]; then
                        cmd connectivity airplane-mode disable 2>/dev/null || true
                        settings put global airplane_mode_on 0 2>/dev/null
                        am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false 2>/dev/null || true
                        svc wifi enable 2>/dev/null || true
                        svc data enable 2>/dev/null || true
                    fi
                    """.trimIndent()
                )
            }
            refreshPermissionsStatusAsync()
            // Read live factory hardware identity from the device OS for backup/reference
            val live = com.example.data.device.RealDeviceReader.readRealDeviceIdentity(getApplication())
            val origBackup = try { profileDao.getOriginalBackup() } catch (_: Throwable) { null }
            val resolvedReal = if (origBackup != null) {
                live.copy(
                    deviceModel = origBackup.deviceModel.ifBlank { live.deviceModel },
                    manufacturer = origBackup.manufacturer.ifBlank { live.manufacturer },
                    androidId = origBackup.androidId.ifBlank { live.androidId },
                    imei = origBackup.imei.ifBlank { live.imei },
                    serialNumber = origBackup.serialNumber.ifBlank { live.serialNumber },
                    macAddress = origBackup.macAddress.ifBlank { live.macAddress },
                    widevineDrmId = origBackup.widevineDrmId.ifBlank { live.widevineDrmId },
                    advertisingId = origBackup.advertisingId.ifBlank { live.advertisingId },
                    bluetoothAddress = origBackup.bluetoothAddress.ifBlank { live.bluetoothAddress },
                    carrier = origBackup.carrier.ifBlank { live.carrier },
                    buildFingerprint = origBackup.buildFingerprint.ifBlank { live.buildFingerprint }
                )
            } else {
                live
            }
            _realDeviceIdentity.value = resolvedReal

            // Restore previously saved/modified active identity so it persists across phone reboots;
            // if none was saved yet (or if any core identifier still matches the real host phone), generate a 100% spoofed identity immediately!
            val diskIdentity = ResetExecutionEngine.readSavedIdentity(getApplication(), resolvedReal)
            val savedIdentity = if (
                diskIdentity.deviceModel.isBlank() ||
                diskIdentity.deviceModel.equals(resolvedReal.deviceModel, ignoreCase = true) ||
                diskIdentity.androidId == resolvedReal.androidId ||
                diskIdentity.imei == resolvedReal.imei ||
                diskIdentity.serialNumber == resolvedReal.serialNumber
            ) {
                val freshSpoofed = IdentityGenerator.generateNewIdentity(
                    excludeModel = resolvedReal.deviceModel,
                    excludeSoc = resolvedReal.socHardware
                )
                try {
                    ResetExecutionEngine.writeXposedIdentityFile(
                        identity = freshSpoofed,
                        context = getApplication(),
                        isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                        applyTimezoneBroadcast = false
                    )
                } catch (_: Throwable) {}
                freshSpoofed
            } else {
                diskIdentity
            }
            _currentIdentity.value = savedIdentity
            com.example.provider.PhantomIdentityProvider.activeStepWalkPackagesCsv =
                _stepWalkPackages.value.joinToString(",")
            com.example.provider.PhantomIdentityProvider.activeSpeedHackPackagesCsv =
                _speedHackPackages.value.joinToString(",")
            com.example.provider.PhantomIdentityProvider.activeRootBypassPackagesCsv =
                _rootBypassPackages.value.joinToString(",")
            com.example.provider.PhantomIdentityProvider.activeAdBypassPackagesCsv =
                _adBypassPackages.value.joinToString(",")
            com.example.provider.PhantomIdentityProvider.activeStandalonePackagesCsv =
                _standaloneApkPackages.value.joinToString(",")
            com.example.provider.PhantomIdentityProvider.liveGlobalSpoofEnabled =
                _isGlobalSpoofEnabled.value
            com.example.provider.PhantomIdentityProvider.getOrCreateHostInstanceId(getApplication())
            com.example.provider.PhantomIdentityProvider.toggleSeqTimestampMs = try {
                getApplication<Application>()
                    .getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                    .getLong("toggle_seq_timestamp_ms", 0L)
            } catch (_: Throwable) { 0L }
            val initLat = if (com.example.service.WalkBridge.hasCapturedRealPhoneGps) {
                com.example.service.WalkBridge.defaultLatitude
            } else if (savedIdentity.latitude != 0.0) {
                savedIdentity.latitude
            } else {
                23.8103
            }
            val initLng = if (com.example.service.WalkBridge.hasCapturedRealPhoneGps) {
                com.example.service.WalkBridge.defaultLongitude
            } else if (savedIdentity.longitude != 0.0) {
                savedIdentity.longitude
            } else {
                90.4125
            }
            com.example.service.WalkBridge.defaultLatitude = initLat
            com.example.service.WalkBridge.defaultLongitude = initLng
            _walkJoystickState.value = _walkJoystickState.value.copy(
                liveLatitude = initLat,
                liveLongitude = initLng,
                isExpanded = false
            )
            if (_stepWalkPackages.value.isNotEmpty()) {
                com.example.service.WalkBridge.pushLiveWalkTelemetryBlocking(
                    lat = initLat,
                    lng = initLng,
                    speedMps = 0f,
                    bearingDeg = 0f,
                    steps = _walkJoystickState.value.stepsCount,
                    isActive = false
                )
            }

            initData(savedIdentity)
        }

        // Collect from background FloatingBubbleService if active to sync remaining time and states
        viewModelScope.launch {
            com.example.service.FloatingBubbleService.remainingSecondsFlow.collect { seconds ->
                if (com.example.service.FloatingBubbleService.isServiceRunning) {
                    _timerRemainingSeconds.value = seconds
                }
            }
        }
        viewModelScope.launch {
            com.example.service.FloatingBubbleService.isRunningFlow.collect { running ->
                if (com.example.service.FloatingBubbleService.isServiceRunning) {
                    _isTimerRunning.value = running
                    if (!running) {
                        timerJob?.cancel()
                    }
                }
            }
        }
    }

    fun checkAppUpdate() {
        if (checkUpdateInFlightJob?.isActive == true) return
        checkUpdateInFlightJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val installedVersionCode = try {
                    val pkgInfo = app.packageManager.getPackageInfo(app.packageName, 0)
                    androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pkgInfo).toInt()
                } catch (_: Throwable) {
                    com.example.BuildConfig.VERSION_CODE
                }
                val remoteUpdate = com.example.data.remote.UpdateChecker.checkUpdate()
                if (remoteUpdate != null) {
                    if (remoteUpdate.versionCode > installedVersionCode) {
                        _updateInfo.value = remoteUpdate
                    } else {
                        _updateInfo.value = null
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Update check failed: ${e.message}", e)
            }
        }
    }

    fun refreshPermissionsStatus() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            refreshPermissionsStatusAsync()
        }
    }

    private fun refreshPermissionsStatusAsync() {
        val app = getApplication<Application>()
        val hasOverlay = try { Settings.canDrawOverlays(app) } catch (_: Throwable) { false }
        val pm = try { app.getSystemService(Context.POWER_SERVICE) as? PowerManager } catch (_: Throwable) { null }
        val batteryIgnored = try { pm?.isIgnoringBatteryOptimizations(app.packageName) == true } catch (_: Throwable) { false }
        val notifications = try { NotificationManagerCompat.from(app).areNotificationsEnabled() } catch (_: Throwable) { true }
        val root = try { com.example.data.device.RootChecker.isDeviceRooted() } catch (_: Throwable) { false }
        val lsposed = try { com.example.data.device.LsposedChecker.isLsposedActive(app) } catch (_: Throwable) { false }

        val status = SystemPermissionsStatus(
            hasOverlayPermission = hasOverlay,
            isBatteryOptimizationIgnored = batteryIgnored,
            hasNotificationPermission = notifications,
            isRootDetected = root,
            isLsposedActive = lsposed
        )
        _permissionsStatus.value = status

        // Auto-activate shield when configured successfully
        if (status.isConfigured && !_isShieldActive.value) {
            _isShieldActive.value = true
            daemonEngine.addManualLog("SHIELD_STATE >> ACTIVE (AUTO-ENGAGED)", "GREEN")
        }
    }

    fun requestRootAccess(onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val success = com.example.data.device.RootChecker.requestRootAccess()
            refreshPermissionsStatus()
            launch(kotlinx.coroutines.Dispatchers.Main) {
                if (success) {
                    daemonEngine.addManualLog("ROOT_SECURITY >> ROOT (SU) ACCESS GRANTED SUCCESSFULLY", "GREEN")
                } else {
                    daemonEngine.addManualLog("ROOT_SECURITY >> ROOT (SU) NOT FOUND / DENIED BY SUPERUSER", "RED")
                }
                onComplete(success)
            }
        }
    }

    fun refreshLiveHardwareInfo() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Force a deep scan of the current hardware properties from /proc, /sys, getprop, and factory backup
            val live = com.example.data.device.RealDeviceReader.readRealDeviceIdentity(getApplication())
            val origBackup = try { profileDao.getOriginalBackup() } catch (_: Throwable) { null }
            val resolvedReal = if (origBackup != null) {
                live.copy(
                    deviceModel = origBackup.deviceModel.ifBlank { live.deviceModel },
                    manufacturer = origBackup.manufacturer.ifBlank { live.manufacturer },
                    androidId = origBackup.androidId.ifBlank { live.androidId },
                    imei = origBackup.imei.ifBlank { live.imei },
                    serialNumber = origBackup.serialNumber.ifBlank { live.serialNumber },
                    macAddress = origBackup.macAddress.ifBlank { live.macAddress },
                    widevineDrmId = origBackup.widevineDrmId.ifBlank { live.widevineDrmId },
                    advertisingId = origBackup.advertisingId.ifBlank { live.advertisingId },
                    bluetoothAddress = origBackup.bluetoothAddress.ifBlank { live.bluetoothAddress },
                    carrier = origBackup.carrier.ifBlank { live.carrier },
                    buildFingerprint = origBackup.buildFingerprint.ifBlank { live.buildFingerprint }
                )
            } else {
                live
            }
            _realDeviceIdentity.value = resolvedReal
            daemonEngine.addManualLog("HARDWARE_SCAN >> Live real hardware refreshed: ${resolvedReal.deviceModel}", "GREEN")
        }
    }

    private fun initData(real: DeviceIdentity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Purge stale APK artifacts from older builder versions
            com.example.data.engine.StandaloneApkBuilderEngine.purgeStaleStandaloneApks(getApplication())

            // Sync actual real device applications
            syncDeviceInstalledApps(forceClearOld = true)

            // Run initial AI analysis
            runAiPrivacyAnalysis()
        }
    }

    fun syncDeviceInstalledApps(forceClearOld: Boolean = false) {
        if (_isScanningApps.value) return
        _isScanningApps.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val existingList = targetAppDao.getAllTargetAppsList()
                val existingMap = existingList.associate { it.packageName to it.isSelected }

                val scannedApps = com.example.data.device.DeviceAppScanner.scanInstalledApps(context, existingMap)
                if (scannedApps.isNotEmpty()) {
                    if (forceClearOld) {
                        targetAppDao.deleteAll()
                    }
                    val finalApps = scannedApps.map { app ->
                        if (com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(app.packageName)) {
                            app.copy(isSelected = false)
                        } else {
                            app
                        }
                    }
                    targetAppDao.insertOrUpdateAll(finalApps)
                    com.example.provider.PhantomIdentityProvider.activeTargetPackagesCsv = finalApps
                        .filter { it.isSelected && !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it.packageName) }
                        .joinToString(",") { it.packageName }
                    com.example.provider.PhantomIdentityProvider.liveGlobalSpoofEnabled = _isGlobalSpoofEnabled.value
                    syncXposedConfigWithCurrentSelection()
                    daemonEngine.addManualLog("DEVICE_SCANNER >> ${finalApps.size} REAL APPS LOADED FROM PHONE", "GREEN")
                    if (_standaloneApkPackages.value.isNotEmpty()) {
                        restoreExistingStandaloneArtifacts(finalApps)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanningApps.value = false
            }
        }
    }

    fun selectTab(tab: AppNavTab) {
        _currentTab.value = tab
    }

    fun toggleShield() {
        viewModelScope.launch {
            // Run the check on IO thread and wait for it!
            val updatedStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val app = getApplication<Application>()
                val hasOverlay = try { Settings.canDrawOverlays(app) } catch (_: Throwable) { false }
                val pm = try { app.getSystemService(Context.POWER_SERVICE) as? PowerManager } catch (_: Throwable) { null }
                val batteryIgnored = try { pm?.isIgnoringBatteryOptimizations(app.packageName) == true } catch (_: Throwable) { false }
                val notifications = try { NotificationManagerCompat.from(app).areNotificationsEnabled() } catch (_: Throwable) { true }
                val root = try { com.example.data.device.RootChecker.isDeviceRooted() } catch (_: Throwable) { false }
                val lsposed = try { com.example.data.device.LsposedChecker.isLsposedActive(app) } catch (_: Throwable) { false }

                SystemPermissionsStatus(
                    hasOverlayPermission = hasOverlay,
                    isBatteryOptimizationIgnored = batteryIgnored,
                    hasNotificationPermission = notifications,
                    isRootDetected = root,
                    isLsposedActive = lsposed
                )
            }
            _permissionsStatus.value = updatedStatus

            if (updatedStatus.isConfigured && updatedStatus.isRootDetected) {
                // When all system requirements (5/5) are complete, protection is PERMANENTLY ACTIVE and must NEVER deactivate on click
                _isShieldActive.value = true
                daemonEngine.addManualLog("SHIELD_STATE >> ACTIVE (FULL SYSTEM PROTECTION LOCKED IN)", "GREEN")
                return@launch
            }
            if (!updatedStatus.isRootDetected) {
                _showSystemRequirements.value = true
                daemonEngine.addManualLog("ROOT_SECURITY >> SHIELD BLOCKED: ROOT ACCESS (SU) REQUIRED", "RED")
                return@launch
            }
            if (!updatedStatus.isConfigured) {
                _showSystemRequirements.value = true
                daemonEngine.addManualLog("REQUIREMENTS_CHECK >> Please complete System Requirements to activate protection", "YELLOW")
                return@launch
            }
            _isShieldActive.value = true
            daemonEngine.addManualLog("SHIELD_STATE >> ACTIVE (HOOKS RE-ENGAGED)", "GREEN")
        }
    }

    fun toggleSystemRequirements(show: Boolean) {
        _showSystemRequirements.value = show
        if (show) {
            refreshPermissionsStatus()
        }
    }

    fun toggleFloatingBadge() {
        if (!_showFloatingBadge.value && !_permissionsStatus.value.isConfigured) {
            _permissionWarningMessage.value = com.example.ui.i18n.tr(
                "All requirements (Root, Overlay, Battery, Notification) must be completed to enable Floating Countdown Bubble!",
                "Floating Countdown Bubble চালু করার জন্য সব রিকোয়ারমেন্ট (Root, Overlay, Battery, Notification) সম্পূর্ণ থাকতে হবে!",
                "Floating Countdown Bubble चालू करने के लिए सभी आवश्यकताएँ (Root, Overlay, Battery, Notification) पूरी होनी चाहिए!"
            )
            _showSystemRequirements.value = true
            return
        }

        val app = getApplication<Application>()
        val hasOverlay = try { Settings.canDrawOverlays(app) } catch (_: Throwable) { false }
        if (!hasOverlay && !_showFloatingBadge.value) {
            try {
                val intent = android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${app.packageName}")
                ).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(intent)
            } catch (_: Throwable) {}
            return
        }

        _showFloatingBadge.value = !_showFloatingBadge.value

        // If timer is running, reflect the change in the FloatingBubbleService
        if (_isTimerRunning.value) {
            if (_showFloatingBadge.value) {
                val totalSecs = _timerRemainingSeconds.value
                com.example.service.FloatingBubbleService.start(app, totalSecs, _timerSettings.value.isLoopMode)
            } else {
                com.example.service.FloatingBubbleService.stop(app)
            }
        }
    }

    fun updateSingleParameter(key: String, newValue: String) {
        val current = _currentIdentity.value
        val updated = when (key) {
            "device_model" -> current.copy(deviceModel = newValue)
            "manufacturer" -> current.copy(manufacturer = newValue)
            "android_id" -> current.copy(androidId = newValue)
            "advertising_id" -> current.copy(advertisingId = newValue)
            "imei" -> current.copy(imei = newValue)
            "serial_number" -> current.copy(serialNumber = newValue)
            "mac_address" -> current.copy(macAddress = newValue)
            "bluetooth_address" -> current.copy(bluetoothAddress = newValue)
            "ip_address" -> current.copy(ipAddress = newValue)
            "sim_carrier" -> current.copy(carrier = newValue)
            "imsi" -> current.copy(simImsi = newValue)
            "iccid" -> current.copy(simIccid = newValue)
            "soc_hardware" -> current.copy(socHardware = newValue)
            "gpu_renderer" -> current.copy(gpuRenderer = newValue)
            "display_res" -> current.copy(display = newValue)
            "device_id" -> current.copy(deviceId = newValue)
            "bootloader" -> current.copy(bootloaderVersion = newValue)
            "os_version" -> current.copy(osVersion = newValue)
            "build_number" -> current.copy(buildNumber = newValue)
            "build_fingerprint" -> current.copy(buildFingerprint = newValue)
            "kernel_version" -> current.copy(kernelVersion = newValue)
            "baseband" -> current.copy(basebandVersion = newValue)
            "widevine_id" -> current.copy(widevineDrmId = newValue)
            "location" -> current.copy(locationName = newValue)
            "timezone" -> current.copy(timezone = newValue)
            "user_agent" -> current.copy(userAgent = newValue)
            else -> current
        }
        _currentIdentity.value = updated
        syncXposedConfigWithCurrentSelection()
    }

    fun setDeviceModelPreset(preset: IdentityGenerator.DevicePreset) {
        val current = _currentIdentity.value
        val updated = IdentityGenerator.applyPresetToIdentity(preset, current)
        _currentIdentity.value = updated
        syncXposedConfigWithCurrentSelection()
        daemonEngine.addManualLog("BUILD_PROPS >> Device Model spoofed to ${preset.model} (${preset.manufacturer})", "CYAN")
    }

    fun setCustomDeviceModel(model: String, manufacturer: String) {
        val current = _currentIdentity.value
        val cleanModel = model.trim()
        val cleanMfr = manufacturer.trim().ifEmpty { cleanModel.substringBefore(" ").ifEmpty { "Generic" } }
        val cleanSlug = cleanModel.replace(" ", "_").lowercase()
        val updated = current.copy(
            deviceModel = cleanModel,
            manufacturer = cleanMfr,
            buildFingerprint = "${cleanMfr.lowercase()}/$cleanSlug/$cleanSlug:14/UKQ1.231003.002/240801001:user/release-keys",
            userAgent = "Mozilla/5.0 (Linux; Android 14; $cleanModel Build/UP1A.240801.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Mobile Safari/537.36"
        )
        _currentIdentity.value = updated
        syncXposedConfigWithCurrentSelection()
        daemonEngine.addManualLog("BUILD_PROPS >> Custom Device Model spoofed to $cleanModel ($cleanMfr)", "CYAN")
    }

    fun setCustomBuildNumber(buildNum: String) {
        val current = _currentIdentity.value
        val clean = buildNum.trim()
        if (clean.isNotBlank()) {
            _currentIdentity.value = current.copy(buildNumber = clean)
            syncXposedConfigWithCurrentSelection()
            daemonEngine.addManualLog("BUILD_PROPS >> Build Number customized: $clean", "CYAN")
        }
    }

    fun setCustomDeviceId(devId: String) {
        val current = _currentIdentity.value
        val clean = devId.trim()
        if (clean.isNotBlank()) {
            _currentIdentity.value = current.copy(deviceId = clean)
            syncXposedConfigWithCurrentSelection()
            daemonEngine.addManualLog("BUILD_PROPS >> Device ID/Codename customized: $clean", "CYAN")
        }
    }

    fun setCustomKernelVersion(kernelVer: String) {
        val current = _currentIdentity.value
        val clean = kernelVer.trim()
        if (clean.isNotBlank()) {
            _currentIdentity.value = current.copy(kernelVersion = clean)
            syncXposedConfigWithCurrentSelection()
            daemonEngine.addManualLog("BUILD_PROPS >> Kernel Version customized: $clean", "CYAN")
        }
    }

    fun updateCustomIdentityDetails(
        model: String,
        manufacturer: String,
        buildNumber: String,
        deviceId: String,
        kernelVersion: String,
        socHardware: String,
        gpuRenderer: String,
        gpuVersion: String,
        basebandVersion: String,
        simImsi: String,
        simIccid: String,
        widevineDrmId: String,
        bootloaderVersion: String,
        region: String,
        localeTag: String
    ) {
        val current = _currentIdentity.value
        val cleanModel = model.trim().ifEmpty { current.deviceModel }
        val cleanMfr = manufacturer.trim().ifEmpty { current.manufacturer }
        val cleanBuild = buildNumber.trim().ifEmpty { current.buildNumber }
        val cleanDevId = deviceId.trim().ifEmpty { current.deviceId }
        val cleanKernel = kernelVersion.trim().ifEmpty { current.kernelVersion }
        val cleanSoc = socHardware.trim().ifEmpty { current.socHardware }
        val cleanGpu = gpuRenderer.trim().ifEmpty { current.gpuRenderer }
        val cleanGpuVer = gpuVersion.trim().ifEmpty { current.gpuVersion }
        val cleanBaseband = basebandVersion.trim().ifEmpty { current.basebandVersion }
        val cleanImsi = simImsi.trim().ifEmpty { current.simImsi }
        val cleanIccid = simIccid.trim().ifEmpty { current.simIccid }
        val cleanWidevine = widevineDrmId.trim().ifEmpty { current.widevineDrmId }
        val cleanBootloader = bootloaderVersion.trim().ifEmpty { current.bootloaderVersion }
        val cleanRegion = region.trim().ifEmpty { current.region }
        val cleanLocale = localeTag.trim().ifEmpty { current.localeTag }

        val updated = current.copy(
            deviceModel = cleanModel,
            manufacturer = cleanMfr,
            buildNumber = cleanBuild,
            deviceId = cleanDevId,
            kernelVersion = cleanKernel,
            socHardware = cleanSoc,
            gpuRenderer = cleanGpu,
            gpuVersion = cleanGpuVer,
            basebandVersion = cleanBaseband,
            simImsi = cleanImsi,
            simIccid = cleanIccid,
            widevineDrmId = cleanWidevine,
            bootloaderVersion = cleanBootloader,
            region = cleanRegion,
            localeTag = cleanLocale,
            buildFingerprint = "${cleanMfr.lowercase()}/${cleanDevId.lowercase()}/${cleanDevId.lowercase()}:14/$cleanBuild/240801001:user/release-keys"
        )
        _currentIdentity.value = updated
        syncXposedConfigWithCurrentSelection()
        daemonEngine.addManualLog("BUILD_PROPS >> Identity updated: Model=$cleanModel, Build=$cleanBuild, DevID=$cleanDevId, Region=$cleanRegion", "CYAN")
    }

    fun updateSingleParameter(key: String, newValue: String, disableAutoRandomize: Boolean = true) {
        val current = _currentIdentity.value
        val clean = newValue.trim()
        val updated = when (key) {
            "device_model" -> {
                val cleanMfr = current.manufacturer.ifEmpty { clean.substringBefore(" ").ifEmpty { "Generic" } }
                val cleanSlug = clean.replace(" ", "_").lowercase()
                current.copy(
                    deviceModel = clean,
                    buildFingerprint = "${cleanMfr.lowercase()}/$cleanSlug/$cleanSlug:14/UKQ1.231003.002/240801001:user/release-keys",
                    userAgent = "Mozilla/5.0 (Linux; Android 14; $clean Build/UP1A.240801.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Mobile Safari/537.36"
                )
            }
            "manufacturer" -> current.copy(manufacturer = clean)
            "android_id" -> current.copy(androidId = clean)
            "advertising_id" -> current.copy(advertisingId = clean)
            "imei" -> current.copy(imei = clean)
            "serial_number" -> current.copy(serialNumber = clean)
            "mac_address" -> current.copy(macAddress = clean.lowercase())
            "bluetooth_address" -> current.copy(bluetoothAddress = clean.lowercase())
            "ip_address" -> current.copy(ipAddress = clean)
            "sim_carrier" -> current.copy(carrier = clean)
            "imsi" -> current.copy(simImsi = clean)
            "iccid" -> current.copy(simIccid = clean)
            "soc_hardware" -> current.copy(socHardware = clean)
            "gpu_renderer" -> current.copy(gpuRenderer = clean)
            "display_res" -> current.copy(display = clean)
            "device_id" -> current.copy(deviceId = clean)
            "bootloader" -> current.copy(bootloaderVersion = clean)
            "os_version" -> current.copy(osVersion = clean)
            "build_number" -> current.copy(buildNumber = clean)
            "build_fingerprint" -> current.copy(buildFingerprint = clean)
            "kernel_version" -> current.copy(kernelVersion = clean)
            "baseband" -> current.copy(basebandVersion = clean)
            "widevine_id" -> current.copy(widevineDrmId = clean)
            "location" -> IdentityGenerator.syncIdentityWithLocationOrTimezone(
                current = current,
                newLocationOrTz = clean,
                isTimezoneEdit = false
            )
            "timezone" -> IdentityGenerator.syncIdentityWithLocationOrTimezone(
                current = current,
                newLocationOrTz = clean,
                isTimezoneEdit = true
            )
            "user_agent" -> current.copy(userAgent = clean)
            else -> current
        }
        _currentIdentity.value = updated

        if (disableAutoRandomize) {
            val ts = _timerSettings.value
            val newTs = when (key) {
                "device_model" -> ts.copy(resetDeviceModel = false)
                "manufacturer" -> ts.copy(resetManufacturer = false)
                "android_id" -> ts.copy(resetAndroidId = false)
                "advertising_id" -> ts.copy(resetAdvertisingId = false)
                "imei" -> ts.copy(resetImei = false)
                "serial_number" -> ts.copy(resetSerialNumber = false)
                "mac_address" -> ts.copy(resetMacAddress = false)
                "bluetooth_address" -> ts.copy(resetBluetooth = false)
                "ip_address" -> ts.copy(resetNetworkIp = false)
                "sim_carrier" -> ts.copy(resetCarrierName = false)
                "imsi" -> ts.copy(resetSimImsi = false)
                "iccid" -> ts.copy(resetSimIccid = false)
                "soc_hardware" -> ts.copy(resetSocHardware = false)
                "gpu_renderer" -> ts.copy(resetGpuRenderer = false)
                "display_res" -> ts.copy(resetDisplayResolution = false)
                "device_id" -> ts.copy(resetDeviceId = false)
                "bootloader" -> ts.copy(resetBootloaderVersion = false)
                "os_version" -> ts.copy(resetOsVersion = false)
                "build_number" -> ts.copy(resetBuildNumber = false)
                "build_fingerprint" -> ts.copy(resetBuildFingerprint = false)
                "kernel_version" -> ts.copy(resetKernel = false)
                "baseband" -> ts.copy(resetBaseband = false)
                "widevine_id" -> ts.copy(resetWidevineDrmId = false)
                "location" -> ts.copy(resetLocation = false)
                "timezone" -> ts.copy(resetTimezone = false)
                "user_agent" -> ts.copy(resetUserAgent = false)
                else -> ts
            }
            _timerSettings.value = newTs
        }

        syncXposedConfigWithCurrentSelection()
        daemonEngine.addManualLog("MANUAL_PARAM >> $key manually customized to: $clean (Auto-Randomize: ${if (disableAutoRandomize) "OFF" else "ON"})", "CYAN")
    }

    fun triggerReset(forcedIdentity: DeviceIdentity? = null) {
        if (_isResetting.value) return
        _isResetting.value = true
        _showResetModal.value = true

        val previewSteps = ResetExecutionEngine.previewResetSteps(_timerSettings.value)
        _resetProgressState.value = ResetProgressState(
            isComplete = false,
            currentStepIndex = 0,
            totalSteps = previewSteps.size,
            currentStepName = "Resetting Identity...",
            currentStepSubtitle = "Executing LSPosed & kernel wipe pipeline",
            steps = previewSteps,
            totalElapsedMs = 0L,
            executedCount = 0,
            skippedCount = 0
        )

        viewModelScope.launch {
            try {
                // Check root access in background (if not rooted, still run identity generation & virtual/standalone reset pipeline!)
                if (!_permissionsStatus.value.isRootDetected) {
                    val granted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.example.data.device.RootChecker.requestRootAccess()
                    }
                    refreshPermissionsStatus()
                    if (!granted) {
                        daemonEngine.addManualLog("RESET_PIPELINE >> RUNNING IN STANDALONE / VIRTUAL IDENTITY MODE (NO ROOT)", "CYAN")
                    } else {
                        daemonEngine.addManualLog("RESET_PIPELINE >> FULL ROOT ACCESS GRANTED - COMMENCING IDENTITY PURGE", "GREEN")
                    }
                } else {
                    daemonEngine.addManualLog("RESET_PIPELINE >> FULL ROOT ACCESS GRANTED - COMMENCING IDENTITY PURGE", "GREEN")
                }

                val oldId = _currentIdentity.value.androidId

                val targetAppsList = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    targetAppDao.getAllTargetAppsList()
                        .filter { it.isSelected && !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it.packageName) }
                        .map { it.packageName }
                }

                val appSummary = if (targetAppsList.isNotEmpty()) "${targetAppsList.size} Selected Apps" else "Target Sandboxes"

                ResetExecutionEngine.executeResetPipeline(
                    targetAppName = appSummary,
                    currentIdentity = _currentIdentity.value,
                    timerSettings = _timerSettings.value,
                    forcedIdentity = forcedIdentity,
                    targetPackageNames = targetAppsList,
                    isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                    context = getApplication()
                ).collect { progress ->
                    _resetProgressState.value = progress
                    if (progress.newIdentity != null) {
                        _currentIdentity.value = progress.newIdentity
                    }

                    if (progress.isComplete && progress.newIdentity != null) {
                        val appliedIdentity = progress.newIdentity
                        _currentIdentity.value = appliedIdentity
                        _walkJoystickState.value = _walkJoystickState.value.copy(
                            liveLatitude = if (appliedIdentity.latitude != 0.0) appliedIdentity.latitude else _walkJoystickState.value.liveLatitude,
                            liveLongitude = if (appliedIdentity.longitude != 0.0) appliedIdentity.longitude else _walkJoystickState.value.liveLongitude
                        )
                        _lastResetSummary.value = "${progress.executedCount}/${progress.totalSteps} OK Last Reset"

                        // Record in Room ResetLog
                        val log = ResetLogEntity(
                            formattedDate = dateFormat.format(Date()),
                            totalDurationMs = progress.totalElapsedMs,
                            successfulSteps = progress.executedCount,
                            totalSteps = progress.totalSteps,
                            status = "OK",
                            oldAndroidId = oldId,
                            newAndroidId = appliedIdentity.androidId,
                            deviceModel = appliedIdentity.deviceModel,
                            location = appliedIdentity.locationName,
                            ipAddress = appliedIdentity.ipAddress,
                            stepsSummary = "${progress.executedCount}/${progress.totalSteps} verified OK, ${progress.skippedCount} skipped by config"
                        )
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            resetLogDao.insertLog(log)
                        }

                        daemonEngine.addManualLog("PURGE_COMPLETE >> 100% DEEP OVERWRITE: OLD TRACES ERASED. ACTIVE: ${appliedIdentity.deviceModel} (ID: ${appliedIdentity.androidId.take(8)}...)", "GREEN")

                        val isRestoringOriginal = forcedIdentity?.isOriginalBackup == true || forcedIdentity?.profileName?.contains("Original", ignoreCase = true) == true
                        val prefs = getApplication<Application>().getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putLong("active_profile_id", if (isRestoringOriginal) -1L else 0L).apply()

                        // Auto trigger AI evaluation on new identity
                        runAiPrivacyAnalysis()
                        scheduleFastConfigSyncForBuiltPackages()
                    }
                }
            } catch (e: Exception) {
                _isResetting.value = false
            } finally {
                _isResetting.value = false
            }
        }
    }

    fun dismissResetModal() {
        _showResetModal.value = false
        _resetProgressState.value = null
    }

    // Timer Controls
    fun updateTimerMinutes(minutes: Int) {
        _timerSettings.value = _timerSettings.value.copy(minutes = minutes.coerceIn(1, 120))
    }

    fun updateTimerSeconds(seconds: Int) {
        _timerSettings.value = _timerSettings.value.copy(seconds = seconds.coerceIn(0, 59))
    }

    fun toggleTimerLoopMode() {
        _timerSettings.value = _timerSettings.value.copy(isLoopMode = !_timerSettings.value.isLoopMode)
    }

    fun toggleTimerStartWithSystem() {
        _timerSettings.value = _timerSettings.value.copy(startWithSystem = !_timerSettings.value.startWithSystem)
    }

    fun toggleResetOption(option: String) {
        val cur = _timerSettings.value
        _timerSettings.value = when (option) {
            "ALL_ON" -> cur.copy(
                resetAndroidId = true,
                resetAdvertisingId = true,
                resetImei = true,
                resetMacAddress = true,
                resetBluetooth = true,
                resetNetworkIp = true,
                resetDeviceModel = true,
                resetManufacturer = true,
                resetDisplayResolution = true,
                resetUserAgent = true,
                resetDeviceId = true,
                resetBuildFingerprint = true,
                resetBuildNumber = true,
                resetKernel = true,
                resetSocHardware = true,
                resetGpuRenderer = true,
                resetBaseband = true,
                resetSimImsi = true,
                resetSimIccid = true,
                resetWidevineDrmId = true,
                resetBootloaderVersion = true,
                resetOsVersion = true,
                resetSerialNumber = true,
                resetLocation = true,
                resetTimezone = true,
                resetCarrierName = true
            )
            "ALL_OFF" -> cur.copy(
                resetAndroidId = false,
                resetAdvertisingId = false,
                resetImei = false,
                resetMacAddress = false,
                resetBluetooth = false,
                resetNetworkIp = false,
                resetDeviceModel = false,
                resetManufacturer = false,
                resetDisplayResolution = false,
                resetUserAgent = false,
                resetDeviceId = false,
                resetBuildFingerprint = false,
                resetBuildNumber = false,
                resetKernel = false,
                resetSocHardware = false,
                resetGpuRenderer = false,
                resetBaseband = false,
                resetSimImsi = false,
                resetSimIccid = false,
                resetWidevineDrmId = false,
                resetBootloaderVersion = false,
                resetOsVersion = false,
                resetSerialNumber = false,
                resetLocation = false,
                resetTimezone = false,
                resetCarrierName = false
            )
            "Android ID" -> cur.copy(resetAndroidId = !cur.resetAndroidId)
            "Advertising ID" -> cur.copy(resetAdvertisingId = !cur.resetAdvertisingId)
            "IMEI" -> cur.copy(resetImei = !cur.resetImei)
            "Wi-Fi MAC Address", "MAC Address" -> cur.copy(resetMacAddress = !cur.resetMacAddress)
            "Bluetooth Address", "Bluetooth" -> cur.copy(resetBluetooth = !cur.resetBluetooth)
            "IP Address", "Network/IP Reset" -> cur.copy(resetNetworkIp = !cur.resetNetworkIp)
            "Device Model" -> cur.copy(resetDeviceModel = !cur.resetDeviceModel)
            "Manufacturer" -> cur.copy(resetManufacturer = !cur.resetManufacturer)
            "Display Resolution" -> cur.copy(resetDisplayResolution = !cur.resetDisplayResolution)
            "User Agent" -> cur.copy(resetUserAgent = !cur.resetUserAgent)
            "Device ID" -> cur.copy(resetDeviceId = !cur.resetDeviceId)
            "Build Fingerprint" -> cur.copy(resetBuildFingerprint = !cur.resetBuildFingerprint)
            "Build Number" -> cur.copy(resetBuildNumber = !cur.resetBuildNumber)
            "Kernel Version", "Kernel" -> cur.copy(resetKernel = !cur.resetKernel)
            "SoC Hardware" -> cur.copy(resetSocHardware = !cur.resetSocHardware)
            "GPU Renderer" -> cur.copy(resetGpuRenderer = !cur.resetGpuRenderer)
            "Baseband Version" -> cur.copy(resetBaseband = !cur.resetBaseband)
            "SIM IMSI" -> cur.copy(resetSimImsi = !cur.resetSimImsi)
            "SIM Serial / ICCID" -> cur.copy(resetSimIccid = !cur.resetSimIccid)
            "Widevine DRM ID" -> cur.copy(resetWidevineDrmId = !cur.resetWidevineDrmId)
            "Bootloader Version" -> cur.copy(resetBootloaderVersion = !cur.resetBootloaderVersion)
            "OS Version" -> cur.copy(resetOsVersion = !cur.resetOsVersion)
            "Serial Number" -> cur.copy(resetSerialNumber = !cur.resetSerialNumber)
            "Region", "Location" -> cur.copy(resetLocation = !cur.resetLocation)
            "Timezone" -> cur.copy(resetTimezone = !cur.resetTimezone)
            "Carrier" -> cur.copy(resetCarrierName = !cur.resetCarrierName)
            else -> cur
        }
    }

    fun startTimer() {
        if (!_permissionsStatus.value.isConfigured) {
            _permissionWarningMessage.value = com.example.ui.i18n.tr(
                "All requirements (Root, Overlay, Battery, Notification) must be completed to start the Timer!",
                "টাইমার চালু করার জন্য সব রিকোয়ারমেন্ট (Root, Overlay, Battery, Notification) সম্পূর্ণ থাকতে হবে!",
                "टाइमर शुरू करने के लिए सभी आवश्यकताएँ (Root, Overlay, Battery, Notification) पूरी होनी चाहिए!"
            )
            _showSystemRequirements.value = true
            daemonEngine.addManualLog("TIMER_BLOCKED >> Requirements incomplete", "RED")
            return
        }

        val totalSecs = (_timerSettings.value.minutes * 60) + _timerSettings.value.seconds
        if (totalSecs <= 0) return

        timerJob?.cancel()
        _isTimerRunning.value = true
        _timerRemainingSeconds.value = totalSecs

        val app = getApplication<Application>()
        if (_showFloatingBadge.value) {
            com.example.service.FloatingBubbleService.start(app, totalSecs, _timerSettings.value.isLoopMode)
        }

        daemonEngine.addManualLog("AUTO_RESET_TIMER >> ARMED FOR ${totalSecs}s (LOOP=${_timerSettings.value.isLoopMode})", "CYAN")

        timerJob = viewModelScope.launch {
            while (_isTimerRunning.value) {
                while (_timerRemainingSeconds.value > 0 && _isTimerRunning.value) {
                    delay(1000L)
                    _timerRemainingSeconds.value -= 1
                }

                if (_timerRemainingSeconds.value <= 0 && _isTimerRunning.value) {
                    daemonEngine.addManualLog("TIMER_EXPIRED >> TRIGGERING AUTO-RESET PURGE", "YELLOW")
                    triggerReset()

                    if (_timerSettings.value.isLoopMode) {
                        _timerRemainingSeconds.value = totalSecs
                    } else {
                        _isTimerRunning.value = false
                        com.example.service.FloatingBubbleService.stop(app)
                        break
                    }
                }
            }
        }
    }

    fun stopTimer() {
        timerJob?.cancel()
        _isTimerRunning.value = false
        _timerRemainingSeconds.value = 0
        val app = getApplication<Application>()
        com.example.service.FloatingBubbleService.stop(app)
        daemonEngine.addManualLog("AUTO_RESET_TIMER >> CANCELLED / DISARMED", "YELLOW")
    }

    // Target Apps Actions
    fun setAppsSearchQuery(q: String) {
        _appsSearchQuery.value = q
    }

    fun setAppFilter(filter: TargetAppFilter) {
        _appFilter.value = filter
    }

    fun refreshRealDeviceIdentity() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val app = getApplication<Application>()
            _realDeviceIdentity.value = com.example.data.device.RealDeviceReader.readRealDeviceIdentity(app)
        }
    }

    fun toggleGlobalSpoofMode() {
        val newValue = !_isGlobalSpoofEnabled.value
        _isGlobalSpoofEnabled.value = newValue
        com.example.provider.PhantomIdentityProvider.liveGlobalSpoofEnabled = newValue
        com.example.provider.PhantomIdentityProvider.markUserToggleChanged(getApplication())
        val app = getApplication<Application>()
        app.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("global_spoof_enabled", newValue)
            .commit()
        syncXposedConfigWithCurrentSelection()
        scheduleFastConfigSyncForBuiltPackages()
        daemonEngine.addManualLog(
            if (newValue) "SPOOF_SCOPE >> GLOBAL MODE ON (ALL APPS & SYSTEM)"
            else "SPOOF_SCOPE >> TARGETED APPS ONLY MODE (OFF = ONLY SELECTED APPS)",
            if (newValue) "CYAN" else "GREEN"
        )
    }

    private var xposedSyncJob: kotlinx.coroutines.Job? = null
    private val pendingRemovedPkgs = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val pendingAddedPkgs = java.util.concurrent.CopyOnWriteArraySet<String>()

    private fun syncXposedConfigWithCurrentSelection(
        removedPackageNames: List<String> = emptyList(),
        newlyAddedPackageNames: List<String> = emptyList()
    ) {
        val cleanAdded = newlyAddedPackageNames.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
        pendingRemovedPkgs.addAll(removedPackageNames)
        pendingAddedPkgs.addAll(cleanAdded)
        pendingRemovedPkgs.removeAll(cleanAdded.toSet())
        pendingAddedPkgs.removeAll(removedPackageNames.toSet())

        xposedSyncJob?.cancel()
        xposedSyncJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(180L)
            try {
                val removedToApply = pendingRemovedPkgs.toList()
                val addedToApply = pendingAddedPkgs.toList()
                pendingRemovedPkgs.clear()
                pendingAddedPkgs.clear()

                val selectedPkgs = targetAppDao.getAllTargetAppsList()
                    .filter { it.isSelected && !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it.packageName) }
                    .map { it.packageName }
                cachedSelectedTargetPackages = selectedPkgs.toSet()
                val rootBypassList = _rootBypassPackages.value
                    .filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
                val stepWalkList = _stepWalkPackages.value
                    .filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
                val speedHackList = _speedHackPackages.value
                    .filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
                val adBypassList = _adBypassPackages.value
                    .filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
                com.example.provider.PhantomIdentityProvider.activeTargetPackagesCsv = selectedPkgs.joinToString(",")
                com.example.provider.PhantomIdentityProvider.liveGlobalSpoofEnabled = _isGlobalSpoofEnabled.value
                com.example.provider.PhantomIdentityProvider.activeRootBypassPackagesCsv = rootBypassList.joinToString(",")
                com.example.provider.PhantomIdentityProvider.activeStepWalkPackagesCsv = stepWalkList.joinToString(",")
                com.example.provider.PhantomIdentityProvider.activeSpeedHackPackagesCsv = speedHackList.joinToString(",")
                com.example.provider.PhantomIdentityProvider.activeAdBypassPackagesCsv = adBypassList.joinToString(",")
                ResetExecutionEngine.writeXposedIdentityFile(
                    identity = _currentIdentity.value,
                    context = getApplication(),
                    isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                    targetPackageNames = selectedPkgs,
                    removedPackageNames = removedToApply,
                    newlyAddedPackageNames = addedToApply,
                    rootBypassPackageNames = rootBypassList,
                    stepWalkPackageNames = stepWalkList,
                    speedHackPackageNames = speedHackList,
                    adBypassPackageNames = adBypassList
                )
                val pkgsToRestart = (selectedPkgs + rootBypassList + stepWalkList + removedToApply)
                    .distinct()
                    .filter { !com.example.data.device.DeviceAppScanner.isCriticalSystemNoWipePackage(it) }
                if (com.example.data.device.RootChecker.isDeviceRooted() && pkgsToRestart.isNotEmpty()) {
                    val restartCmd = pkgsToRestart
                        .filter { it.isNotBlank() && !it.contains(" ") && !it.contains(";") }
                        .joinToString("\n") { pkg -> "am force-stop $pkg 2>/dev/null || true" }
                    com.example.data.device.RootChecker.executeRootCommand(restartCmd)
                }
            } catch (_: Exception) {}
        }
    }

    fun toggleAppRootBypass(packageName: String) {
        viewModelScope.launch {
            if (com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(packageName)) {
                daemonEngine.addManualLog("SECURITY_GUARD >> $packageName CANNOT USE ROOT BYPASS", "YELLOW")
                return@launch
            }
            val currentSet = _rootBypassPackages.value.toMutableSet()
            val isEnabling = !currentSet.contains(packageName)
            if (isEnabling) {
                currentSet.add(packageName)
            } else {
                currentSet.remove(packageName)
            }
            _rootBypassPackages.value = currentSet.toSet()

            val app = getApplication<Application>()
            app.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                .edit()
                .putStringSet("root_bypass_packages", currentSet.toSet())
                .commit()

            val allApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                targetAppDao.getAllTargetAppsList()
            }
            val selectedPkgs = allApps
                .filter { it.isSelected && !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it.packageName) }
                .map { it.packageName }
            val isTargetSelected = selectedPkgs.contains(packageName)
            val isStepWalkSelected = _stepWalkPackages.value.contains(packageName)
            val isSpeedSelected = _speedHackPackages.value.contains(packageName)
            val isAdSelected = _adBypassPackages.value.contains(packageName)
            val rootBypassList = currentSet.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            com.example.provider.PhantomIdentityProvider.activeRootBypassPackagesCsv = rootBypassList.joinToString(",")
            com.example.provider.PhantomIdentityProvider.markUserToggleChanged(app)
            val stepWalkList = _stepWalkPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val speedHackList = _speedHackPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val adBypassList = _adBypassPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val allActiveSet = (selectedPkgs + rootBypassList + stepWalkList + speedHackList + adBypassList).toSet()
            val removedList = allApps.map { it.packageName }.filter { !allActiveSet.contains(it) }

            val pkgHash = Integer.toUnsignedString(packageName.hashCode(), 36)
            xposedSyncJob?.cancel()
            if (isEnabling) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.data.device.RootChecker.executeRootCommand(
                        "setprop debug.phantom.rb.$pkgHash \"1\" 2>/dev/null || true; " +
                        "setprop log.tag.phantom.rb.$pkgHash \"1\" 2>/dev/null || true; " +
                        "magiskhide --rm $packageName 2>/dev/null || true; " +
                        "magisk --denylist rm $packageName 2>/dev/null || true"
                    )
                    com.example.data.device.LspScopeSyncCli.syncTargetScopeWithLsposed(
                        context = app,
                        selectedTargetPackages = allActiveSet.toList(),
                        removedTargetPackages = emptyList(),
                        newlyAddedTargetPackages = listOf(packageName)
                    )
                    ResetExecutionEngine.writeXposedIdentityFile(
                        identity = _currentIdentity.value,
                        context = app,
                        isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                        targetPackageNames = selectedPkgs,
                        removedPackageNames = removedList,
                        newlyAddedPackageNames = emptyList(),
                        rootBypassPackageNames = rootBypassList,
                        stepWalkPackageNames = stepWalkList,
                        speedHackPackageNames = speedHackList,
                        adBypassPackageNames = adBypassList,
                        applyTimezoneBroadcast = false
                    )
                    if (!com.example.data.device.DeviceAppScanner.isCriticalSystemNoWipePackage(packageName)) {
                        com.example.data.device.RootChecker.executeRootCommand(
                            "rm -rf /data/data/$packageName/cache/* /data/data/$packageName/code_cache/* /data/user/0/$packageName/cache/* /data/user/0/$packageName/code_cache/* 2>/dev/null || true; " +
                            "am force-stop $packageName 2>/dev/null || true; " +
                            "am kill $packageName 2>/dev/null || true"
                        )
                    }
                }
                daemonEngine.addManualLog("ROOT_RESTRICT_BYPASS >> ENABLED FOR $packageName (ROOT & CLONE CLOAK ACTIVE)", "GREEN")
            } else {
                val disableRemoved = if (!isTargetSelected && !isStepWalkSelected && !isSpeedSelected && !isAdSelected) {
                    (removedList + packageName).distinct()
                } else removedList
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.data.device.RootChecker.executeRootCommand(
                        "setprop debug.phantom.rb.$pkgHash \"0\" 2>/dev/null || true; " +
                        "setprop log.tag.phantom.rb.$pkgHash \"0\" 2>/dev/null || true"
                    )
                    ResetExecutionEngine.writeXposedIdentityFile(
                        identity = _currentIdentity.value,
                        context = app,
                        isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                        targetPackageNames = selectedPkgs,
                        removedPackageNames = disableRemoved,
                        newlyAddedPackageNames = emptyList(),
                        rootBypassPackageNames = rootBypassList,
                        stepWalkPackageNames = stepWalkList,
                        speedHackPackageNames = speedHackList,
                        adBypassPackageNames = adBypassList,
                        applyTimezoneBroadcast = false
                    )
                    if (!com.example.data.device.DeviceAppScanner.isCriticalSystemNoWipePackage(packageName)) {
                        com.example.data.device.RootChecker.executeRootCommand(
                            "am force-stop $packageName 2>/dev/null || true; " +
                            "am kill $packageName 2>/dev/null || true"
                        )
                    }
                }
                daemonEngine.addManualLog("ROOT_RESTRICT_BYPASS >> DISABLED FOR $packageName", "YELLOW")
            }
            scheduleFastConfigSyncForPackage(packageName)
        }
    }

    fun toggleAppStepWalk(packageName: String) {
        viewModelScope.launch {
            if (com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(packageName)) {
                daemonEngine.addManualLog("SECURITY_GUARD >> $packageName CANNOT USE STEP CONTROLLER", "YELLOW")
                return@launch
            }
            val currentSet = _stepWalkPackages.value.toMutableSet()
            val isEnabling = !currentSet.contains(packageName)
            if (isEnabling) {
                currentSet.add(packageName)
            } else {
                currentSet.remove(packageName)
            }
            _stepWalkPackages.value = currentSet.toSet()

            if (isEnabling) {
                val curId = _currentIdentity.value
                _walkJoystickState.value = _walkJoystickState.value.copy(
                    liveLatitude = if (_walkJoystickState.value.liveLatitude != 0.0) _walkJoystickState.value.liveLatitude else (curId.latitude.takeIf { it != 0.0 } ?: 23.8103),
                    liveLongitude = if (_walkJoystickState.value.liveLongitude != 0.0) _walkJoystickState.value.liveLongitude else (curId.longitude.takeIf { it != 0.0 } ?: 90.4125)
                )
            }

            val app = getApplication<Application>()
            app.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                .edit()
                .putStringSet("step_walk_packages", currentSet.toSet())
                .commit()

            val stepWalkList = currentSet.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            com.example.provider.PhantomIdentityProvider.activeStepWalkPackagesCsv = stepWalkList.joinToString(",")
            com.example.provider.PhantomIdentityProvider.markUserToggleChanged(app)

            val allApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                targetAppDao.getAllTargetAppsList()
            }
            val selectedPkgs = allApps
                .filter { it.isSelected && !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it.packageName) }
                .map { it.packageName }
            val isTargetSelected = selectedPkgs.contains(packageName)
            val isRootSelected = _rootBypassPackages.value.contains(packageName)
            val isSpeedSelected = _speedHackPackages.value.contains(packageName)
            val isAdSelected = _adBypassPackages.value.contains(packageName)
            val rootBypassList = _rootBypassPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val speedHackList = _speedHackPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val adBypassList = _adBypassPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val allActiveSet = (selectedPkgs + rootBypassList + stepWalkList + speedHackList + adBypassList).toSet()
            val removedList = allApps.map { it.packageName }.filter { !allActiveSet.contains(it) }

            val pkgHash = Integer.toUnsignedString(packageName.hashCode(), 36)
            xposedSyncJob?.cancel()
            if (isEnabling) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val curWalk = _walkJoystickState.value
                    com.example.data.device.RootChecker.executeRootCommand(
                        "setprop debug.phantom.wk.$pkgHash \"1\" 2>/dev/null || true; " +
                        "setprop log.tag.phantom.wk.$pkgHash \"1\" 2>/dev/null || true; " +
                        "appops set $packageName android:mock_location allow 2>/dev/null || true; " +
                        "pm grant $packageName android.permission.ACTIVITY_RECOGNITION 2>/dev/null || true; " +
                        "magiskhide --rm $packageName 2>/dev/null || true; " +
                        "magisk --denylist rm $packageName 2>/dev/null || true"
                    )
                    pushLiveWalkTelemetryBlocking(
                        lat = curWalk.liveLatitude,
                        lng = curWalk.liveLongitude,
                        speedMps = curWalk.speedMps,
                        bearingDeg = curWalk.bearingDegrees,
                        steps = curWalk.stepsCount,
                        isActive = curWalk.magnitude > 0.03f
                    )
                    com.example.data.device.LspScopeSyncCli.syncTargetScopeWithLsposed(
                        context = app,
                        selectedTargetPackages = allActiveSet.toList(),
                        removedTargetPackages = emptyList(),
                        newlyAddedTargetPackages = listOf(packageName)
                    )
                    ResetExecutionEngine.writeXposedIdentityFile(
                        identity = _currentIdentity.value,
                        context = app,
                        isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                        targetPackageNames = selectedPkgs,
                        removedPackageNames = removedList,
                        newlyAddedPackageNames = emptyList(),
                        rootBypassPackageNames = rootBypassList,
                        stepWalkPackageNames = stepWalkList,
                        speedHackPackageNames = speedHackList,
                        adBypassPackageNames = adBypassList,
                        applyTimezoneBroadcast = false
                    )
                    if (!com.example.data.device.DeviceAppScanner.isCriticalSystemNoWipePackage(packageName)) {
                        com.example.data.device.RootChecker.executeRootCommand(
                            "am force-stop $packageName 2>/dev/null || true; " +
                            "am kill $packageName 2>/dev/null || true"
                        )
                    }
                }
                daemonEngine.addManualLog("STEP_WALK_CTRL >> ENABLED FOR $packageName (LIVE GPS & STEP SIMULATOR ACTIVE)", "CYAN")
            } else {
                if (stepWalkList.isEmpty()) {
                    com.example.service.WalkBridge.updateJoystickVector(0f, 0f, false)
                }
                val disableRemoved = if (!isTargetSelected && !isRootSelected && !isSpeedSelected && !isAdSelected) {
                    (removedList + packageName).distinct()
                } else removedList
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val clearWalkCmd = if (stepWalkList.isEmpty()) {
                        "rm -f /data/local/tmp/.phantom_walk_state 2>/dev/null || true; " +
                        "setprop persist.sys.phantom.wact \"0\" 2>/dev/null || true; " +
                        "setprop debug.phantom.wact \"0\" 2>/dev/null || true; " +
                        "setprop log.tag.phantom.wact \"0\" 2>/dev/null || true; "
                    } else ""
                    com.example.data.device.RootChecker.executeRootCommand(
                        clearWalkCmd +
                        "setprop debug.phantom.wk.$pkgHash \"0\" 2>/dev/null || true; " +
                        "setprop log.tag.phantom.wk.$pkgHash \"0\" 2>/dev/null || true"
                    )
                    ResetExecutionEngine.writeXposedIdentityFile(
                        identity = _currentIdentity.value,
                        context = app,
                        isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                        targetPackageNames = selectedPkgs,
                        removedPackageNames = disableRemoved,
                        newlyAddedPackageNames = emptyList(),
                        rootBypassPackageNames = rootBypassList,
                        stepWalkPackageNames = stepWalkList,
                        speedHackPackageNames = speedHackList,
                        adBypassPackageNames = adBypassList,
                        applyTimezoneBroadcast = false
                    )
                    if (!com.example.data.device.DeviceAppScanner.isCriticalSystemNoWipePackage(packageName)) {
                        com.example.data.device.RootChecker.executeRootCommand(
                            "am force-stop $packageName 2>/dev/null || true; " +
                            "am kill $packageName 2>/dev/null || true"
                        )
                    }
                }
                daemonEngine.addManualLog("STEP_WALK_CTRL >> DISABLED FOR $packageName (REAL PHONE LOCATION RESTORED)", "YELLOW")
            }
            scheduleFastConfigSyncForPackage(packageName)
        }
    }

    fun toggleAppSpeedHack(packageName: String) {
        val current = _speedHackPackages.value.toMutableSet()
        val enabledNow = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        _speedHackPackages.value = current.toSet()
        val app = getApplication<Application>()
        app.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("speed_hack_packages", current.toSet())
            .commit()
        val speedHackList = current.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
        com.example.provider.PhantomIdentityProvider.activeSpeedHackPackagesCsv = speedHackList.joinToString(",")
        com.example.provider.PhantomIdentityProvider.markUserToggleChanged(app)

        viewModelScope.launch {
            val allApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                targetAppDao.getAllTargetAppsList()
            }
            val selectedPkgs = allApps
                .filter { it.isSelected && !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it.packageName) }
                .map { it.packageName }
            val rootBypassList = _rootBypassPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val stepWalkList = _stepWalkPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val adBypassList = _adBypassPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val allActiveSet = (selectedPkgs + rootBypassList + stepWalkList + speedHackList + adBypassList).toSet()
            val removedList = allApps.map { it.packageName }.filter { !allActiveSet.contains(it) }
            val pkgHash = Integer.toUnsignedString(packageName.hashCode(), 36)

            if (enabledNow) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.data.device.RootChecker.executeRootCommand(
                        "setprop debug.phantom.sp.$pkgHash \"1\" 2>/dev/null || true; " +
                        "setprop log.tag.phantom.sp.$pkgHash \"1\" 2>/dev/null || true; " +
                        "magiskhide --rm $packageName 2>/dev/null || true; " +
                        "magisk --denylist rm $packageName 2>/dev/null || true"
                    )
                    com.example.data.device.LspScopeSyncCli.syncTargetScopeWithLsposed(
                        context = app,
                        selectedTargetPackages = allActiveSet.toList(),
                        removedTargetPackages = emptyList(),
                        newlyAddedTargetPackages = listOf(packageName)
                    )
                    ResetExecutionEngine.writeXposedIdentityFile(
                        identity = _currentIdentity.value,
                        context = app,
                        isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                        targetPackageNames = selectedPkgs,
                        removedPackageNames = removedList,
                        newlyAddedPackageNames = listOf(packageName),
                        rootBypassPackageNames = rootBypassList,
                        stepWalkPackageNames = stepWalkList,
                        speedHackPackageNames = speedHackList,
                        adBypassPackageNames = adBypassList,
                        applyTimezoneBroadcast = false
                    )
                }
                daemonEngine.addManualLog("APP_SPEED_CTRL >> SPEED CONTROLLER ENABLED FOR $packageName (${com.example.service.WalkBridge.formatSpeedMultiplier(_walkJoystickState.value.appSpeedMultiplier)})", "GREEN")
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.data.device.RootChecker.executeRootCommand(
                        "setprop debug.phantom.sp.$pkgHash \"0\" 2>/dev/null || true; " +
                        "setprop log.tag.phantom.sp.$pkgHash \"0\" 2>/dev/null || true"
                    )
                    ResetExecutionEngine.writeXposedIdentityFile(
                        identity = _currentIdentity.value,
                        context = app,
                        isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                        targetPackageNames = selectedPkgs,
                        removedPackageNames = removedList,
                        newlyAddedPackageNames = emptyList(),
                        rootBypassPackageNames = rootBypassList,
                        stepWalkPackageNames = stepWalkList,
                        speedHackPackageNames = speedHackList,
                        adBypassPackageNames = adBypassList,
                        applyTimezoneBroadcast = false
                    )
                }
                daemonEngine.addManualLog("APP_SPEED_CTRL >> SPEED CONTROLLER DISABLED FOR $packageName (1.0x NORMAL RESTORED)", "YELLOW")
            }
            scheduleFastConfigSyncForPackage(packageName)
        }
    }

    fun toggleAppAdBypass(packageName: String) {
        if (com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(packageName)) {
            daemonEngine.addManualLog("SECURITY_GUARD >> $packageName CANNOT USE AD BYPASS", "YELLOW")
            return
        }
        val current = _adBypassPackages.value.toMutableSet()
        val enabledNow = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        _adBypassPackages.value = current.toSet()
        val app = getApplication<Application>()
        app.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("ad_bypass_packages", current.toSet())
            .commit()
        val adBypassList = current.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
        com.example.provider.PhantomIdentityProvider.activeAdBypassPackagesCsv = adBypassList.joinToString(",")
        com.example.provider.PhantomIdentityProvider.markUserToggleChanged(app)

        viewModelScope.launch {
            val allApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                targetAppDao.getAllTargetAppsList()
            }
            val selectedPkgs = allApps
                .filter { it.isSelected && !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it.packageName) }
                .map { it.packageName }
            val rootBypassList = _rootBypassPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val stepWalkList = _stepWalkPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val speedHackList = _speedHackPackages.value.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }
            val allActiveSet = (selectedPkgs + rootBypassList + stepWalkList + speedHackList + adBypassList).toSet()
            val removedList = allApps.map { it.packageName }.filter { !allActiveSet.contains(it) }
            val pkgHash = Integer.toUnsignedString(packageName.hashCode(), 36)

            if (enabledNow) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.data.device.RootChecker.executeRootCommand(
                        "setprop debug.phantom.ad.$pkgHash \"1\" 2>/dev/null || true; " +
                        "setprop log.tag.phantom.ad.$pkgHash \"1\" 2>/dev/null || true; " +
                        "magiskhide --rm $packageName 2>/dev/null || true; " +
                        "magisk --denylist rm $packageName 2>/dev/null || true"
                    )
                    com.example.data.device.LspScopeSyncCli.syncTargetScopeWithLsposed(
                        context = app,
                        selectedTargetPackages = allActiveSet.toList(),
                        removedTargetPackages = emptyList(),
                        newlyAddedTargetPackages = listOf(packageName)
                    )
                    ResetExecutionEngine.writeXposedIdentityFile(
                        identity = _currentIdentity.value,
                        context = app,
                        isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                        targetPackageNames = selectedPkgs,
                        removedPackageNames = removedList,
                        newlyAddedPackageNames = emptyList(),
                        rootBypassPackageNames = rootBypassList,
                        stepWalkPackageNames = stepWalkList,
                        speedHackPackageNames = speedHackList,
                        adBypassPackageNames = adBypassList,
                        applyTimezoneBroadcast = false
                    )
                    if (!com.example.data.device.DeviceAppScanner.isCriticalSystemNoWipePackage(packageName)) {
                        com.example.data.device.RootChecker.executeRootCommand(
                            "am force-stop $packageName 2>/dev/null || true; " +
                            "am kill $packageName 2>/dev/null || true"
                        )
                    }
                }
                daemonEngine.addManualLog("AD_BYPASS_CTRL >> INSTANT 1s AD COMPLETE & NO-AD ENABLED FOR $packageName", "GREEN")
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.data.device.RootChecker.executeRootCommand(
                        "setprop debug.phantom.ad.$pkgHash \"0\" 2>/dev/null || true; " +
                        "setprop log.tag.phantom.ad.$pkgHash \"0\" 2>/dev/null || true"
                    )
                    ResetExecutionEngine.writeXposedIdentityFile(
                        identity = _currentIdentity.value,
                        context = app,
                        isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                        targetPackageNames = selectedPkgs,
                        removedPackageNames = removedList,
                        newlyAddedPackageNames = emptyList(),
                        rootBypassPackageNames = rootBypassList,
                        stepWalkPackageNames = stepWalkList,
                        speedHackPackageNames = speedHackList,
                        adBypassPackageNames = adBypassList,
                        applyTimezoneBroadcast = false
                    )
                    if (!com.example.data.device.DeviceAppScanner.isCriticalSystemNoWipePackage(packageName)) {
                        com.example.data.device.RootChecker.executeRootCommand(
                            "am force-stop $packageName 2>/dev/null || true; " +
                            "am kill $packageName 2>/dev/null || true"
                        )
                    }
                }
                daemonEngine.addManualLog("AD_BYPASS_CTRL >> INSTANT AD BYPASS DISABLED FOR $packageName", "YELLOW")
            }
            scheduleFastConfigSyncForPackage(packageName)
        }
    }

    fun toggleVolumeSpeedMode() {
        com.example.service.WalkBridge.toggleVolumeSpeedMode()
        val isVol = _walkJoystickState.value.isVolumeSpeedMode
        daemonEngine.addManualLog(
            if (isVol) "APP_SPEED_CTRL >> STICK CONVERTED TO VOLUME SPEED DIAL (${com.example.service.WalkBridge.formatSpeedMultiplier(_walkJoystickState.value.appSpeedMultiplier)})"
            else "STEP_WALK_CTRL >> STICK RESTORED TO 360° WALK RADAR",
            "CYAN"
        )
    }

    fun stepAppSpeedMultiplier(increase: Boolean) {
        val app = getApplication<Application>()
        com.example.service.WalkBridge.stepAppSpeedMultiplier(increase, app)
        daemonEngine.addManualLog(
            "APP_SPEED_CTRL >> TARGET APP SPEED SET TO ${com.example.service.WalkBridge.formatSpeedMultiplier(_walkJoystickState.value.appSpeedMultiplier)}",
            "GREEN"
        )
    }

    fun setAppSpeedMultiplier(mult: Float) {
        val app = getApplication<Application>()
        com.example.service.WalkBridge.setAppSpeedMultiplier(mult, app)
    }

    fun resetAppSpeedMultiplier() {
        val app = getApplication<Application>()
        com.example.service.WalkBridge.resetAppSpeedMultiplier(app)
        daemonEngine.addManualLog("APP_SPEED_CTRL >> SPEED RESET TO 1.0x NORMAL", "YELLOW")
    }

    private fun isEffectiveAdBypassPackage(packageName: String): Boolean =
        _adBypassPackages.value.contains(packageName) ||
            com.example.data.engine.StandaloneApkBuilderEngine.isYouTubePackage(packageName)

    private fun ensureYouTubeAdBypassEnabled(packageName: String) {
        if (!com.example.data.engine.StandaloneApkBuilderEngine.isYouTubePackage(packageName)) return
        if (_adBypassPackages.value.contains(packageName)) return
        val updated = _adBypassPackages.value.toMutableSet().apply { add(packageName) }.toSet()
        _adBypassPackages.value = updated
        val app = getApplication<Application>()
        app.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("ad_bypass_packages", updated)
            .commit()
        com.example.provider.PhantomIdentityProvider.activeAdBypassPackagesCsv =
            updated.filter { !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) }.joinToString(",")
    }

    fun toggleAppStandaloneApk(packageName: String) {
        if (com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(packageName)) {
            daemonEngine.addManualLog("SECURITY_GUARD >> $packageName CANNOT BE PACKAGED AS STANDALONE APK", "YELLOW")
            return
        }
        val current = _standaloneApkPackages.value.toMutableSet()
        val enabledNow = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        _standaloneApkPackages.value = current.toSet()
        val app = getApplication<Application>()
        app.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("standalone_apk_packages", current.toSet())
            .commit()
        com.example.provider.PhantomIdentityProvider.activeStandalonePackagesCsv = current.joinToString(",")

        if (enabledNow) {
            ensureYouTubeAdBypassEnabled(packageName)
            val isMultiOn = _multiInstallApkPackages.value.contains(packageName)
            val isReplaceMainOn = !isMultiOn && _replaceMainApkPackages.value.contains(packageName)
            daemonEngine.addManualLog(
                "STANDALONE_APK >> ENABLED FOR $packageName (Multi=$isMultiOn, ReplaceMain=$isReplaceMainOn) - BUILDING EMBEDDED APK PACKAGE",
                "CYAN"
            )
            buildStandaloneApksForPackages(listOf(packageName), forceRebuild = isMultiOn)
        } else {
            val map = _standaloneArtifacts.value.toMutableMap()
            map.remove(packageName)
            _standaloneArtifacts.value = map
            daemonEngine.addManualLog("STANDALONE_APK >> DISABLED FOR $packageName", "YELLOW")
        }
    }

    fun selectStandaloneInstallMode(packageName: String, replaceMainApk: Boolean) {
        if (com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(packageName)) {
            daemonEngine.addManualLog("SECURITY_GUARD >> $packageName CANNOT BE PACKAGED AS STANDALONE APK", "YELLOW")
            return
        }
        val app = getApplication<Application>()

        val replaceSet = _replaceMainApkPackages.value.toMutableSet()
        val prevReplace = replaceSet.contains(packageName)
        if (replaceMainApk) {
            replaceSet.add(packageName)
        } else {
            replaceSet.remove(packageName)
        }
        _replaceMainApkPackages.value = replaceSet.toSet()
        app.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("replace_main_apk_packages", replaceSet.toSet())
            .commit()

        val standaloneSet = _standaloneApkPackages.value.toMutableSet()
        val wasStandaloneOff = !standaloneSet.contains(packageName)
        if (wasStandaloneOff) {
            standaloneSet.add(packageName)
            _standaloneApkPackages.value = standaloneSet.toSet()
            app.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                .edit()
                .putStringSet("standalone_apk_packages", standaloneSet.toSet())
                .commit()
            com.example.provider.PhantomIdentityProvider.activeStandalonePackagesCsv = standaloneSet.joinToString(",")
        }

        ensureYouTubeAdBypassEnabled(packageName)
        val modeLabel = if (replaceMainApk) "Main Apk Replace" else "Without Main Apk Replace"
        daemonEngine.addManualLog("STANDALONE_APK >> [$modeLabel] SELECTED FOR $packageName", "CYAN")

        val isMultiOn = _multiInstallApkPackages.value.contains(packageName)
        val existingArt = _standaloneArtifacts.value[packageName]
        val needsModeSwitch = wasStandaloneOff ||
            prevReplace != replaceMainApk ||
            existingArt == null ||
            (!isMultiOn && existingArt.bakedReplaceMainApk != replaceMainApk) ||
            (!isMultiOn && replaceMainApk && existingArt.standalonePackageName != packageName) ||
            (!isMultiOn && !replaceMainApk && existingArt.standalonePackageName == packageName)

        if (needsModeSwitch) {
            buildStandaloneApksForPackages(listOf(packageName), forceRebuild = false)
        }
    }

    fun toggleAppMultiInstallApk(packageName: String) {
        if (com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(packageName)) return
        val current = _multiInstallApkPackages.value.toMutableSet()
        val enabledNow = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        _multiInstallApkPackages.value = current.toSet()
        val app = getApplication<Application>()
        app.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("multi_install_apk_packages", current.toSet())
            .apply()

        daemonEngine.addManualLog(
            "MULTI_INSTALL_APK >> ${
                if (enabledNow) {
                    com.example.ui.i18n.tr(
                        "ENABLED (Separate APK per Identity)",
                        "ENABLED (আলাদা আইডেন্টিটিতে আলাদা APK)",
                        "ENABLED (अलग पहचान के लिए अलग APK)"
                    )
                } else {
                    com.example.ui.i18n.tr(
                        "DISABLED (Standard APK)",
                        "DISABLED (স্বাভাবিক APK)",
                        "DISABLED (सामान्य APK)"
                    )
                }
            } FOR $packageName",
            if (enabledNow) "GREEN" else "YELLOW"
        )
        if (_standaloneApkPackages.value.contains(packageName)) {
            buildStandaloneApksForPackages(listOf(packageName), forceRebuild = true)
        }
    }

    private val activeBuildingPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val perPackageConfigSyncJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var globalConfigSyncJob: Job? = null

    private fun scheduleFastConfigSyncForPackage(packageName: String) {
        if (!_standaloneApkPackages.value.contains(packageName)) return
        perPackageConfigSyncJobs[packageName]?.cancel()
        perPackageConfigSyncJobs[packageName] = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(550L)
            syncBuiltApkConfigIfPresent(listOf(packageName))
        }
    }

    private fun scheduleFastConfigSyncForBuiltPackages() {
        val pkgs = _standaloneApkPackages.value.toList()
        if (pkgs.isEmpty()) return
        globalConfigSyncJob?.cancel()
        globalConfigSyncJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(700L)
            syncBuiltApkConfigIfPresent(_standaloneApkPackages.value.toList())
        }
    }

    /**
     * Lightweight config-only sync (no full APK rebuild) that updates
     * `assets/phantom_standalone_config.json` and Downloads copies for any already-built APK
     * after the user flips toggles, so transferring via File Manager to a non-rooted phone
     * always includes every enabled system (Speed, Step, Root Hide, Ad Skip, Identity).
     */
    private suspend fun syncBuiltApkConfigIfPresent(packages: List<String>) {
        val targetPkgs = packages.distinct().filter { _standaloneApkPackages.value.contains(it) }
        if (targetPkgs.isEmpty()) return

        standaloneBuildMutex.lock()
        try {
            val app = getApplication<Application>()
            val allApps = try { targetAppDao.getAllTargetAppsList() } catch (_: Throwable) { emptyList() }
            val nameMap = allApps.associate { it.packageName to it.appName }
            val selectedSet = allApps.filter { it.isSelected }.map { it.packageName }.toSet()
            val curIdentity = _currentIdentity.value
            val curWalk = _walkJoystickState.value

            for (pkg in targetPkgs) {
                if (!_standaloneApkPackages.value.contains(pkg)) continue
                if (activeBuildingPackages.contains(pkg)) continue

                val label = nameMap[pkg] ?: pkg.substringAfterLast('.')
                val isMulti = _multiInstallApkPackages.value.contains(pkg)
                val isReplaceMain = !isMulti && _replaceMainApkPackages.value.contains(pkg)
                val isSpeed = _speedHackPackages.value.contains(pkg)
                val isStep = _stepWalkPackages.value.contains(pkg)
                val isRoot = _rootBypassPackages.value.contains(pkg)
                val isAdBypass = isEffectiveAdBypassPackage(pkg)
                val isIdentityOn = true

                val currentArt = _standaloneArtifacts.value[pkg]
                    ?: com.example.data.engine.StandaloneApkBuilderEngine.findExistingBuiltArtifact(
                        context = app,
                        targetPackageName = pkg,
                        appName = label,
                        identity = curIdentity,
                        speedMultiplier = curWalk.appSpeedMultiplier,
                        speedEnabled = isSpeed,
                        stepWalkEnabled = isStep,
                        rootBypassEnabled = isRoot,
                        multiInstallEnabled = isMulti,
                        adBypassEnabled = isAdBypass,
                        identitySpoofEnabled = isIdentityOn,
                        replaceMainApkEnabled = isReplaceMain
                    )
                    ?: continue

                if (!doesArtifactNeedConfigSync(pkg, currentArt, selectedSet)) {
                    if (_standaloneArtifacts.value[pkg] != currentArt) {
                        val map = _standaloneArtifacts.value.toMutableMap()
                        map[pkg] = currentArt
                        _standaloneArtifacts.value = map
                    }
                    continue
                }

                val existingFile = java.io.File(currentArt.outputFilePath).let { f ->
                    if (f.exists() && f.length() > 10_000L) f else java.io.File(currentArt.publicDownloadPath)
                }
                if (!existingFile.exists() || existingFile.length() <= 10_000L) continue

                activeBuildingPackages.add(pkg)
                try {
                    val syncMap = _standaloneArtifacts.value.toMutableMap()
                    syncMap[pkg] = currentArt.copy(
                        isBuilding = true,
                        progressPercent = 12,
                        statusMessage = com.example.ui.i18n.tr(
                            "Syncing enabled systems into APK...",
                            "চালু করা সিস্টেমগুলো APK-তে যুক্ত করা হচ্ছে...",
                            "चालू सिस्टम APK में जोड़े जा रहे हैं..."
                        )
                    )
                    _standaloneArtifacts.value = syncMap

                    val refreshed = com.example.data.engine.StandaloneApkBuilderEngine.refreshConfigInExistingApk(
                        context = app,
                        existingApkFile = existingFile,
                        targetPackageName = pkg,
                        appName = label,
                        identity = curIdentity.copy(
                            latitude = curWalk.liveLatitude,
                            longitude = curWalk.liveLongitude,
                            locationName = curWalk.currentZoneLabel.ifBlank { curIdentity.locationName }
                        ),
                        liveLatitude = curWalk.liveLatitude,
                        liveLongitude = curWalk.liveLongitude,
                        speedMultiplier = curWalk.appSpeedMultiplier,
                        speedEnabled = isSpeed,
                        stepWalkEnabled = isStep,
                        rootBypassEnabled = isRoot,
                        identitySpoofEnabled = isIdentityOn,
                        multiInstallEnabled = isMulti,
                        adBypassEnabled = isAdBypass,
                        replaceMainApkEnabled = isReplaceMain,
                        onProgress = { progressPct, progressMsg ->
                            val curMap = _standaloneArtifacts.value.toMutableMap()
                            val curArt = curMap[pkg]
                            if (curArt != null && curArt.isBuilding) {
                                curMap[pkg] = curArt.copy(
                                    progressPercent = progressPct.coerceIn(0, 100),
                                    statusMessage = progressMsg
                                )
                                _standaloneArtifacts.value = curMap
                            }
                        }
                    )
                    val doneMap = _standaloneArtifacts.value.toMutableMap()
                    if (refreshed != null) {
                        doneMap[pkg] = refreshed
                        daemonEngine.addManualLog(
                            "STANDALONE_APK >> AUTO-SYNCED TOGGLES: ${refreshed.appName} [Spd=$isSpeed, Stp=$isStep, Root=$isRoot, AdSkip=$isAdBypass, ID=$isIdentityOn]",
                            "GREEN"
                        )
                    } else {
                        doneMap[pkg] = currentArt.copy(isBuilding = false, progressPercent = 100)
                    }
                    _standaloneArtifacts.value = doneMap
                } finally {
                    activeBuildingPackages.remove(pkg)
                }
            }
        } finally {
            standaloneBuildMutex.unlock()
        }
    }

    fun scheduleStandaloneApkRebuildForEnabledApps(immediate: Boolean = false, forceRebuild: Boolean = false) {
        val enabledPkgs = _standaloneApkPackages.value.toList()
        if (enabledPkgs.isEmpty()) return
        standaloneRebuildJob?.cancel()
        standaloneRebuildJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!immediate) {
                kotlinx.coroutines.delay(1200L)
            }
            buildStandaloneApksInternal(enabledPkgs, forceRebuild = forceRebuild)
        }
    }

    private val perPackageBuildJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun buildStandaloneApksForPackages(packages: List<String>, forceRebuild: Boolean = true) {
        val validPkgs = packages.distinct().filter { _standaloneApkPackages.value.contains(it) }
        if (validPkgs.isEmpty()) return

        // Immediately reflect building state (2%) on the UI before acquiring any mutex so user gets instant response
        val curIdentityNow = _currentIdentity.value
        val curWalkNow = _walkJoystickState.value
        val immediateMap = _standaloneArtifacts.value.toMutableMap()
        var markedImmediate = false
        for (pkg in validPkgs) {
            perPackageConfigSyncJobs[pkg]?.cancel()
            if (forceRebuild) {
                perPackageBuildJobs[pkg]?.cancel()
                activeBuildingPackages.remove(pkg)
            }
            val isMultiNow = _multiInstallApkPackages.value.contains(pkg)
            val isReplaceMainNow = !isMultiNow && _replaceMainApkPackages.value.contains(pkg)
            val prevArt = immediateMap[pkg]
            immediateMap[pkg] = com.example.data.engine.StandaloneApkArtifact(
                originalPackageName = pkg,
                standalonePackageName = com.example.data.engine.StandaloneApkBuilderEngine.deriveStandalonePackageName(
                    originalPkg = pkg,
                    identity = curIdentityNow,
                    multiInstallEnabled = isMultiNow,
                    replaceMainApkEnabled = isReplaceMainNow
                ),
                appName = prevArt?.appName ?: pkg.substringAfterLast('.'),
                outputFilePath = prevArt?.outputFilePath ?: "",
                publicDownloadPath = prevArt?.publicDownloadPath ?: "",
                fileSizeBytes = prevArt?.fileSizeBytes ?: 0L,
                builtTimestampMs = System.currentTimeMillis(),
                bakedDeviceModel = curIdentityNow.deviceModel,
                bakedAndroidId = curIdentityNow.androidId,
                bakedSpeedMultiplier = curWalkNow.appSpeedMultiplier,
                bakedSpeedEnabled = _speedHackPackages.value.contains(pkg),
                bakedStepWalkEnabled = _stepWalkPackages.value.contains(pkg),
                bakedRootBypassEnabled = _rootBypassPackages.value.contains(pkg),
                bakedAdBypassEnabled = isEffectiveAdBypassPackage(pkg),
                bakedIdentitySpoofEnabled = true,
                bakedReplaceMainApk = isReplaceMainNow,
                isBuilding = true,
                progressPercent = 2,
                statusMessage = com.example.ui.i18n.tr(
                    "Starting build...",
                    "বিল্ড শুরু হচ্ছে...",
                    "बिल्ड शुरू हो रहा है..."
                )
            )
            markedImmediate = true
        }
        if (markedImmediate) {
            _standaloneArtifacts.value = immediateMap
        }

        val job = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            buildStandaloneApksInternal(validPkgs, forceRebuild = forceRebuild)
        }
        for (pkg in validPkgs) {
            perPackageBuildJobs[pkg] = job
        }
    }

    private suspend fun restoreExistingStandaloneArtifacts(appsSnapshot: List<TargetAppEntity>) {
        val enabledPkgs = _standaloneApkPackages.value.toList()
        if (enabledPkgs.isEmpty()) return
        val app = getApplication<Application>()
        val nameMap = appsSnapshot.associate { it.packageName to it.appName }
        val selectedSet = appsSnapshot.filter { it.isSelected }.map { it.packageName }.toSet()
        val curIdentity = _currentIdentity.value
        val curWalk = _walkJoystickState.value

        val updatedMap = _standaloneArtifacts.value.toMutableMap()
        var restoredAny = false
        val missingBuildPkgs = mutableListOf<String>()
        for (pkg in enabledPkgs) {
            if (activeBuildingPackages.contains(pkg)) continue
            val isMulti = _multiInstallApkPackages.value.contains(pkg)
            val isReplaceMain = !isMulti && _replaceMainApkPackages.value.contains(pkg)
            val existing = com.example.data.engine.StandaloneApkBuilderEngine.findExistingBuiltArtifact(
                context = app,
                targetPackageName = pkg,
                appName = nameMap[pkg] ?: pkg.substringAfterLast('.'),
                identity = curIdentity,
                speedMultiplier = curWalk.appSpeedMultiplier,
                speedEnabled = _speedHackPackages.value.contains(pkg),
                stepWalkEnabled = _stepWalkPackages.value.contains(pkg),
                rootBypassEnabled = _rootBypassPackages.value.contains(pkg),
                multiInstallEnabled = isMulti,
                adBypassEnabled = isEffectiveAdBypassPackage(pkg),
                identitySpoofEnabled = true,
                replaceMainApkEnabled = isReplaceMain
            )
            if (existing != null) {
                updatedMap[pkg] = existing
                restoredAny = true
            } else {
                missingBuildPkgs.add(pkg)
            }
        }
        if (restoredAny) {
            _standaloneArtifacts.value = updatedMap
        }
        if (missingBuildPkgs.isNotEmpty()) {
            buildStandaloneApksForPackages(missingBuildPkgs, forceRebuild = false)
        }
    }

    private fun doesArtifactNeedConfigSync(
        pkg: String,
        artifact: com.example.data.engine.StandaloneApkArtifact?,
        selectedSet: Set<String>
    ): Boolean {
        if (artifact == null) return true
        val curIdentity = _currentIdentity.value
        val isMulti = _multiInstallApkPackages.value.contains(pkg)
        val isReplaceMain = !isMulti && _replaceMainApkPackages.value.contains(pkg)
        val isHostModel = curIdentity.deviceModel.isBlank() ||
            curIdentity.deviceModel.equals(android.os.Build.MODEL, ignoreCase = true) ||
            curIdentity.deviceModel.contains(android.os.Build.MODEL, ignoreCase = true)
        val effectiveIdentityForPkg = if (isHostModel && artifact.bakedAndroidId.isNotBlank()) {
            curIdentity.copy(
                deviceModel = artifact.bakedDeviceModel.ifBlank { curIdentity.deviceModel },
                androidId = artifact.bakedAndroidId
            )
        } else {
            curIdentity
        }
        val expectedStandalonePkg = com.example.data.engine.StandaloneApkBuilderEngine.deriveStandalonePackageName(
            originalPkg = pkg,
            identity = effectiveIdentityForPkg,
            multiInstallEnabled = isMulti,
            replaceMainApkEnabled = isReplaceMain
        )
        val isSpeedOn = _speedHackPackages.value.contains(pkg)
        val isStepOn = _stepWalkPackages.value.contains(pkg)
        val isRootOn = _rootBypassPackages.value.contains(pkg)
        val isAdOn = isEffectiveAdBypassPackage(pkg)
        val isIdOn = true
        val f1 = java.io.File(artifact.outputFilePath)
        val f2 = java.io.File(artifact.publicDownloadPath)
        val hasFile = (f1.exists() && f1.length() > 10_000L) || (f2.exists() && f2.length() > 10_000L)
        if (!hasFile) return true
        val hasDistinctMultiPkg = isMulti && artifact.standalonePackageName.contains(".id")
        if (!hasDistinctMultiPkg && artifact.standalonePackageName != expectedStandalonePkg) return true
        if (isMulti && !artifact.standalonePackageName.contains(".id")) return true
        if (!isMulti && artifact.standalonePackageName.contains(".id")) return true
        if (!isMulti && artifact.bakedReplaceMainApk != isReplaceMain) return true
        if (artifact.bakedSpeedEnabled != isSpeedOn) return true
        if (artifact.bakedStepWalkEnabled != isStepOn) return true
        if (artifact.bakedRootBypassEnabled != isRootOn) return true
        if (artifact.bakedAdBypassEnabled != isAdOn) return true
        if (artifact.bakedIdentitySpoofEnabled != isIdOn) return true
        if (!isHostModel && !hasDistinctMultiPkg) {
            if (artifact.bakedDeviceModel != curIdentity.deviceModel) return true
            if (curIdentity.androidId.isNotBlank() && artifact.bakedAndroidId != curIdentity.androidId) return true
        }
        return false
    }

    private suspend fun buildStandaloneApksInternal(packages: List<String>, forceRebuild: Boolean = false) {
        standaloneBuildMutex.lock()
        try {
            buildStandaloneApksCore(packages, forceRebuild)
        } finally {
            standaloneBuildMutex.unlock()
        }
    }

    /**
     * Must only be called while holding `standaloneBuildMutex`.
     */
    private suspend fun buildStandaloneApksCore(packages: List<String>, forceRebuild: Boolean = false) {
        val queuedPkgs = packages.distinct().filter { _standaloneApkPackages.value.contains(it) }
        if (queuedPkgs.isEmpty()) return

        val app = getApplication<Application>()
        val allAppsSnapshot = try {
            targetAppDao.getAllTargetAppsList()
        } catch (_: Throwable) {
            emptyList()
        }
        val nameMapPre = allAppsSnapshot.associate { it.packageName to it.appName }
        val selectedSetPre = allAppsSnapshot.filter { it.isSelected }.map { it.packageName }.toSet()
        val curIdentityPre = _currentIdentity.value
        val curWalkPre = _walkJoystickState.value

        // Pre-mark truly unbuilt (or force-rebuilt) packages as Queued (0%) so UI never shows static stall text
        val preQueueMap = _standaloneArtifacts.value.toMutableMap()
        var preUpdated = false
        for (pkg in queuedPkgs) {
            if (activeBuildingPackages.contains(pkg)) continue
            val label = nameMapPre[pkg] ?: pkg.substringAfterLast('.')
            val isMultiInstallOn = _multiInstallApkPackages.value.contains(pkg)
            val isReplaceMainOn = !isMultiInstallOn && _replaceMainApkPackages.value.contains(pkg)
            val isSpeedOn = _speedHackPackages.value.contains(pkg)
            val isStepOn = _stepWalkPackages.value.contains(pkg)
            val isRootOn = _rootBypassPackages.value.contains(pkg)
            val isAdBypassOn = isEffectiveAdBypassPackage(pkg)
            val isIdentityOn = true

            if (!forceRebuild) {
                val existingOnDisk = com.example.data.engine.StandaloneApkBuilderEngine.findExistingBuiltArtifact(
                    context = app,
                    targetPackageName = pkg,
                    appName = label,
                    identity = curIdentityPre,
                    speedMultiplier = curWalkPre.appSpeedMultiplier,
                    speedEnabled = isSpeedOn,
                    stepWalkEnabled = isStepOn,
                    rootBypassEnabled = isRootOn,
                    multiInstallEnabled = isMultiInstallOn,
                    adBypassEnabled = isAdBypassOn,
                    identitySpoofEnabled = isIdentityOn,
                    replaceMainApkEnabled = isReplaceMainOn
                )
                if (existingOnDisk != null && !doesArtifactNeedConfigSync(pkg, existingOnDisk, selectedSetPre)) {
                    preQueueMap[pkg] = existingOnDisk
                    preUpdated = true
                    continue
                }
            }
            val prev = preQueueMap[pkg]
            if (prev?.isBuilding != true) {
                preQueueMap[pkg] = com.example.data.engine.StandaloneApkArtifact(
                    originalPackageName = pkg,
                    standalonePackageName = com.example.data.engine.StandaloneApkBuilderEngine.deriveStandalonePackageName(
                        originalPkg = pkg,
                        identity = curIdentityPre,
                        multiInstallEnabled = isMultiInstallOn,
                        replaceMainApkEnabled = isReplaceMainOn
                    ),
                    appName = label,
                    outputFilePath = prev?.outputFilePath ?: "",
                    publicDownloadPath = prev?.publicDownloadPath ?: "",
                    fileSizeBytes = prev?.fileSizeBytes ?: 0L,
                    builtTimestampMs = System.currentTimeMillis(),
                    bakedDeviceModel = curIdentityPre.deviceModel,
                    bakedAndroidId = curIdentityPre.androidId,
                    bakedSpeedMultiplier = curWalkPre.appSpeedMultiplier,
                    bakedSpeedEnabled = isSpeedOn,
                    bakedStepWalkEnabled = isStepOn,
                    bakedRootBypassEnabled = isRootOn,
                    bakedAdBypassEnabled = isAdBypassOn,
                    bakedIdentitySpoofEnabled = isIdentityOn,
                    bakedReplaceMainApk = isReplaceMainOn,
                    isBuilding = true,
                    progressPercent = 0,
                    statusMessage = com.example.ui.i18n.tr(
                        "Queued for build...",
                        "বিল্ডের জন্য অপেক্ষমাণ...",
                        "बिल्ड के लिए कतार में..."
                    )
                )
                preUpdated = true
            }
        }
        if (preUpdated) {
            _standaloneArtifacts.value = preQueueMap
        }

        val allApps = try {
            targetAppDao.getAllTargetAppsList()
        } catch (_: Throwable) {
            emptyList()
        }
        val nameMap = allApps.associate { it.packageName to it.appName }
        val selectedSet = allApps.filter { it.isSelected }.map { it.packageName }.toSet()
        val curIdentity = _currentIdentity.value
        val curWalk = _walkJoystickState.value

        for (pkg in queuedPkgs) {
            if (!_standaloneApkPackages.value.contains(pkg)) continue
            if (!activeBuildingPackages.add(pkg)) continue
            try {
                val loopApps = try { targetAppDao.getAllTargetAppsList() } catch (_: Throwable) { allApps }
                val loopSelectedSet = loopApps.filter { it.isSelected }.map { it.packageName }.toSet()
                cachedSelectedTargetPackages = loopSelectedSet
                val baseIdentity = _currentIdentity.value
                val loopWalk = _walkJoystickState.value
                val label = loopApps.find { it.packageName == pkg }?.appName ?: nameMap[pkg] ?: pkg.substringAfterLast('.')
                val isMultiInstallOn = _multiInstallApkPackages.value.contains(pkg)
                val isReplaceMainOn = !isMultiInstallOn && _replaceMainApkPackages.value.contains(pkg)
                val previousArtifact = _standaloneArtifacts.value[pkg]
                // When Multi-Install is ON and user triggers a build/rebuild, generate a brand-new unique DeviceIdentity
                // so EVERY built APK gets a unique package name (.ghostdevice.id<6hex>) and installs side-by-side!
                val loopIdentity = if (isMultiInstallOn && forceRebuild) {
                    val prevStandalonePkg = previousArtifact?.standalonePackageName.orEmpty()
                    val excludedModel = previousArtifact?.bakedDeviceModel?.ifBlank { baseIdentity.deviceModel }
                        ?: baseIdentity.deviceModel
                    var freshId = com.example.data.generator.IdentityGenerator.generateNewIdentity(
                        excludeModel = excludedModel
                    ).copy(
                        latitude = if (loopWalk.liveLatitude != 0.0) loopWalk.liveLatitude else baseIdentity.latitude,
                        longitude = if (loopWalk.liveLongitude != 0.0) loopWalk.liveLongitude else baseIdentity.longitude,
                        locationName = loopWalk.currentZoneLabel.ifBlank { baseIdentity.locationName }
                    )
                    var attempt = 0
                    while (attempt < 5 &&
                        com.example.data.engine.StandaloneApkBuilderEngine.deriveStandalonePackageName(pkg, freshId, true) == prevStandalonePkg
                    ) {
                        freshId = com.example.data.generator.IdentityGenerator.generateNewIdentity(
                            excludeModel = freshId.deviceModel
                        ).copy(
                            latitude = if (loopWalk.liveLatitude != 0.0) loopWalk.liveLatitude else baseIdentity.latitude,
                            longitude = if (loopWalk.liveLongitude != 0.0) loopWalk.liveLongitude else baseIdentity.longitude,
                            locationName = loopWalk.currentZoneLabel.ifBlank { baseIdentity.locationName }
                        )
                        attempt++
                    }
                    _currentIdentity.value = freshId
                    try {
                        val allActiveSet = (loopSelectedSet + _rootBypassPackages.value + _stepWalkPackages.value + _speedHackPackages.value + _adBypassPackages.value).toSet()
                        val removedList = loopApps.map { it.packageName }.filter { !allActiveSet.contains(it) }
                        ResetExecutionEngine.writeXposedIdentityFile(
                            identity = freshId,
                            context = app,
                            isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                            targetPackageNames = loopSelectedSet.toList(),
                            removedPackageNames = removedList,
                            newlyAddedPackageNames = emptyList(),
                            rootBypassPackageNames = _rootBypassPackages.value.toList(),
                            stepWalkPackageNames = _stepWalkPackages.value.toList(),
                            speedHackPackageNames = _speedHackPackages.value.toList(),
                            adBypassPackageNames = _adBypassPackages.value.toList(),
                            applyTimezoneBroadcast = false
                        )
                    } catch (_: Throwable) {}
                    freshId
                } else {
                    baseIdentity
                }
                val standalonePkg = com.example.data.engine.StandaloneApkBuilderEngine.deriveStandalonePackageName(
                    originalPkg = pkg,
                    identity = loopIdentity,
                    multiInstallEnabled = isMultiInstallOn,
                    replaceMainApkEnabled = isReplaceMainOn
                )
                val isSpeedOn = _speedHackPackages.value.contains(pkg)
                val isStepOn = _stepWalkPackages.value.contains(pkg)
                val isRootOn = _rootBypassPackages.value.contains(pkg)
                val isAdBypassOn = isEffectiveAdBypassPackage(pkg)
                val isIdentityOn = true

                // If not forced to rebuild, check if a built APK for this package & builder version already exists on disk
                var fastRefreshed: com.example.data.engine.StandaloneApkArtifact? = null
                if (!forceRebuild) {
                    val existingOnDisk = com.example.data.engine.StandaloneApkBuilderEngine.findExistingBuiltArtifact(
                        context = app,
                        targetPackageName = pkg,
                        appName = label,
                        identity = loopIdentity,
                        speedMultiplier = loopWalk.appSpeedMultiplier,
                        speedEnabled = isSpeedOn,
                        stepWalkEnabled = isStepOn,
                        rootBypassEnabled = isRootOn,
                        multiInstallEnabled = isMultiInstallOn,
                        adBypassEnabled = isAdBypassOn,
                        identitySpoofEnabled = isIdentityOn,
                        replaceMainApkEnabled = isReplaceMainOn
                    )
                    if (existingOnDisk != null) {
                        if (!doesArtifactNeedConfigSync(pkg, existingOnDisk, loopSelectedSet)) {
                            val restoredMap = _standaloneArtifacts.value.toMutableMap()
                            restoredMap[pkg] = existingOnDisk
                            _standaloneArtifacts.value = restoredMap
                            continue
                        }
                        val existingFile = java.io.File(existingOnDisk.outputFilePath).let { f ->
                            if (f.exists() && f.length() > 10_000L) f else java.io.File(existingOnDisk.publicDownloadPath)
                        }
                        if (existingFile.exists() && existingFile.length() > 10_000L) {
                            fastRefreshed = com.example.data.engine.StandaloneApkBuilderEngine.refreshConfigInExistingApk(
                                context = app,
                                existingApkFile = existingFile,
                                targetPackageName = pkg,
                                appName = label,
                                identity = loopIdentity.copy(
                                    latitude = loopWalk.liveLatitude,
                                    longitude = loopWalk.liveLongitude,
                                    locationName = loopWalk.currentZoneLabel.ifBlank { loopIdentity.locationName }
                                ),
                                liveLatitude = loopWalk.liveLatitude,
                                liveLongitude = loopWalk.liveLongitude,
                                speedMultiplier = loopWalk.appSpeedMultiplier,
                                speedEnabled = isSpeedOn,
                                stepWalkEnabled = isStepOn,
                                rootBypassEnabled = isRootOn,
                                identitySpoofEnabled = isIdentityOn,
                                multiInstallEnabled = isMultiInstallOn,
                                adBypassEnabled = isAdBypassOn,
                                replaceMainApkEnabled = isReplaceMainOn,
                                onProgress = { progressPct, progressMsg ->
                                    val curMap = _standaloneArtifacts.value.toMutableMap()
                                    val curArt = curMap[pkg]
                                    if (curArt != null && curArt.isBuilding) {
                                        curMap[pkg] = curArt.copy(
                                            progressPercent = progressPct.coerceIn(0, 100),
                                            statusMessage = progressMsg
                                        )
                                        _standaloneArtifacts.value = curMap
                                    }
                                }
                            )
                            if (fastRefreshed != null) {
                                val restoredMap = _standaloneArtifacts.value.toMutableMap()
                                restoredMap[pkg] = fastRefreshed
                                _standaloneArtifacts.value = restoredMap
                                continue
                            }
                        }
                    }
                }

                // Mark building state while preserving any previous outputFilePath until replacement completes
                val buildingMap = _standaloneArtifacts.value.toMutableMap()
                buildingMap[pkg] = com.example.data.engine.StandaloneApkArtifact(
                    originalPackageName = pkg,
                    standalonePackageName = standalonePkg,
                    appName = label,
                    outputFilePath = previousArtifact?.outputFilePath ?: "",
                    publicDownloadPath = previousArtifact?.publicDownloadPath ?: "",
                    fileSizeBytes = previousArtifact?.fileSizeBytes ?: 0L,
                    builtTimestampMs = System.currentTimeMillis(),
                    bakedDeviceModel = loopIdentity.deviceModel,
                    bakedAndroidId = loopIdentity.androidId,
                    bakedSpeedMultiplier = loopWalk.appSpeedMultiplier,
                    bakedSpeedEnabled = isSpeedOn,
                    bakedStepWalkEnabled = isStepOn,
                    bakedRootBypassEnabled = isRootOn,
                    bakedAdBypassEnabled = isAdBypassOn,
                    bakedIdentitySpoofEnabled = isIdentityOn,
                    bakedReplaceMainApk = isReplaceMainOn,
                    isBuilding = true,
                    progressPercent = 2,
                    statusMessage = com.example.ui.i18n.tr(
                        "Starting build...",
                        "বিল্ড শুরু হচ্ছে...",
                        "बिल्ड शुरू हो रहा है..."
                    )
                )
                _standaloneArtifacts.value = buildingMap

                var lastErr: String? = null
                var built = try {
                    com.example.data.engine.StandaloneApkBuilderEngine.buildStandaloneApk(
                        context = app,
                        targetPackageName = pkg,
                        appName = label,
                        identity = loopIdentity.copy(
                            latitude = loopWalk.liveLatitude,
                            longitude = loopWalk.liveLongitude,
                            locationName = loopWalk.currentZoneLabel.ifBlank { loopIdentity.locationName }
                        ),
                        liveLatitude = loopWalk.liveLatitude,
                        liveLongitude = loopWalk.liveLongitude,
                        speedMultiplier = loopWalk.appSpeedMultiplier,
                        speedEnabled = isSpeedOn,
                        stepWalkEnabled = isStepOn,
                        rootBypassEnabled = isRootOn,
                        identitySpoofEnabled = isIdentityOn,
                        multiInstallEnabled = isMultiInstallOn,
                        adBypassEnabled = isAdBypassOn,
                        replaceMainApkEnabled = isReplaceMainOn,
                        liveToggleSnapshotProvider = {
                            val liveSelected = cachedSelectedTargetPackages.contains(pkg)
                            val liveWalk = _walkJoystickState.value
                            com.example.data.engine.LiveBuildToggleSnapshot(
                                speedMultiplier = liveWalk.appSpeedMultiplier,
                                speedEnabled = _speedHackPackages.value.contains(pkg),
                                stepWalkEnabled = _stepWalkPackages.value.contains(pkg),
                                rootBypassEnabled = _rootBypassPackages.value.contains(pkg),
                                identitySpoofEnabled = true,
                                adBypassEnabled = isEffectiveAdBypassPackage(pkg),
                                liveLatitude = liveWalk.liveLatitude,
                                liveLongitude = liveWalk.liveLongitude,
                                locationName = liveWalk.currentZoneLabel.ifBlank { loopIdentity.locationName }
                            )
                        },
                        onProgress = { progressPct, progressMsg ->
                            val curMap = _standaloneArtifacts.value.toMutableMap()
                            val curArt = curMap[pkg]
                            if (curArt != null && curArt.isBuilding) {
                                curMap[pkg] = curArt.copy(
                                    progressPercent = progressPct.coerceIn(0, 100),
                                    statusMessage = progressMsg
                                )
                                _standaloneArtifacts.value = curMap
                            }
                        }
                    )
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    lastErr = "${e.javaClass.simpleName}: ${e.message}"
                    null
                }

                // Final safety net: if the user toggled any system during the final V2 signing / Downloads copy stage
                // of a huge APK, immediately refresh its config JSON before marking Ready.
                if (built != null) {
                    val postApps = try { targetAppDao.getAllTargetAppsList() } catch (_: Throwable) { loopApps }
                    val postSelectedSet = postApps.filter { it.isSelected }.map { it.packageName }.toSet()
                    if (doesArtifactNeedConfigSync(pkg, built, postSelectedSet) &&
                        _multiInstallApkPackages.value.contains(pkg) == isMultiInstallOn &&
                        (isMultiInstallOn || _replaceMainApkPackages.value.contains(pkg) == isReplaceMainOn)
                    ) {
                        val builtFile = java.io.File(built.outputFilePath)
                        if (builtFile.exists() && builtFile.length() > 10_000L) {
                            val postIdentity = _currentIdentity.value
                            val postWalk = _walkJoystickState.value
                            val postSpeed = _speedHackPackages.value.contains(pkg)
                            val postStep = _stepWalkPackages.value.contains(pkg)
                            val postRoot = _rootBypassPackages.value.contains(pkg)
                            val postAd = isEffectiveAdBypassPackage(pkg)
                            val postIdOn = true
                            built = com.example.data.engine.StandaloneApkBuilderEngine.refreshConfigInExistingApk(
                                context = app,
                                existingApkFile = builtFile,
                                targetPackageName = pkg,
                                appName = label,
                                identity = postIdentity.copy(
                                    latitude = postWalk.liveLatitude,
                                    longitude = postWalk.liveLongitude,
                                    locationName = postWalk.currentZoneLabel.ifBlank { postIdentity.locationName }
                                ),
                                liveLatitude = postWalk.liveLatitude,
                                liveLongitude = postWalk.liveLongitude,
                                speedMultiplier = postWalk.appSpeedMultiplier,
                                speedEnabled = postSpeed,
                                stepWalkEnabled = postStep,
                                rootBypassEnabled = postRoot,
                                identitySpoofEnabled = postIdOn,
                                multiInstallEnabled = isMultiInstallOn,
                                adBypassEnabled = postAd,
                                replaceMainApkEnabled = isReplaceMainOn,
                                onProgress = { progressPct, progressMsg ->
                                    val curMap = _standaloneArtifacts.value.toMutableMap()
                                    val curArt = curMap[pkg]
                                    if (curArt != null && curArt.isBuilding) {
                                        curMap[pkg] = curArt.copy(
                                            progressPercent = progressPct.coerceIn(0, 100),
                                            statusMessage = progressMsg
                                        )
                                        _standaloneArtifacts.value = curMap
                                    }
                                }
                            ) ?: built
                        }
                    }
                }

                val updatedMap = _standaloneArtifacts.value.toMutableMap()
                if (built != null) {
                    updatedMap[pkg] = built
                    daemonEngine.addManualLog(
                        "STANDALONE_APK >> READY: ${built.appName} (${built.standalonePackageName}) [Multi=$isMultiInstallOn, ReplaceMain=$isReplaceMainOn, Spd=${built.bakedSpeedEnabled}, Stp=${built.bakedStepWalkEnabled}, Root=${built.bakedRootBypassEnabled}, AdSkip=${built.bakedAdBypassEnabled}, ID=${built.bakedIdentitySpoofEnabled}] -> ${built.publicDownloadPath}",
                        "GREEN"
                    )
                } else {
                    if (lastErr != null) {
                        daemonEngine.addManualLog("STANDALONE_APK >> ERROR ($pkg): $lastErr", "RED")
                    }
                    updatedMap[pkg] = com.example.data.engine.StandaloneApkArtifact(
                        originalPackageName = pkg,
                        standalonePackageName = standalonePkg,
                        appName = label,
                        outputFilePath = "",
                        publicDownloadPath = "",
                        fileSizeBytes = 0L,
                        builtTimestampMs = System.currentTimeMillis(),
                        bakedDeviceModel = loopIdentity.deviceModel,
                        bakedAndroidId = loopIdentity.androidId,
                        bakedSpeedMultiplier = loopWalk.appSpeedMultiplier,
                        bakedSpeedEnabled = isSpeedOn,
                        bakedStepWalkEnabled = isStepOn,
                        bakedRootBypassEnabled = isRootOn,
                        bakedAdBypassEnabled = isAdBypassOn,
                        bakedIdentitySpoofEnabled = isIdentityOn,
                        bakedReplaceMainApk = isReplaceMainOn,
                        isBuilding = false,
                        statusMessage = com.example.ui.i18n.tr(
                            "Build failed (Please try again)",
                            "বিল্ড ব্যর্থ হয়েছে (আবার চেষ্টা করুন)",
                            "बिल्ड विफल रहा (कृपया पुनः प्रयास करें)"
                        )
                    )
                }
                _standaloneArtifacts.value = updatedMap
            } finally {
                activeBuildingPackages.remove(pkg)
            }
        }
    }

    private suspend fun ensureStandaloneArtifactSyncedForExport(
        packageName: String,
        launchContext: android.content.Context
    ): com.example.data.engine.StandaloneApkArtifact? {
        val app = getApplication<Application>()
        val allAppsSnapshot = try { targetAppDao.getAllTargetAppsList() } catch (_: Throwable) { emptyList() }
        val selectedSet = allAppsSnapshot.filter { it.isSelected }.map { it.packageName }.toSet()
        val label = allAppsSnapshot.find { it.packageName == packageName }?.appName ?: packageName.substringAfterLast('.')

        val preArtifact = _standaloneArtifacts.value[packageName] ?: run {
            val curId = _currentIdentity.value
            val curWalk = _walkJoystickState.value
            val isMultiPre = _multiInstallApkPackages.value.contains(packageName)
            val isReplacePre = !isMultiPre && _replaceMainApkPackages.value.contains(packageName)
            com.example.data.engine.StandaloneApkBuilderEngine.findExistingBuiltArtifact(
                context = app,
                targetPackageName = packageName,
                appName = label,
                identity = curId,
                speedMultiplier = curWalk.appSpeedMultiplier,
                speedEnabled = _speedHackPackages.value.contains(packageName),
                stepWalkEnabled = _stepWalkPackages.value.contains(packageName),
                rootBypassEnabled = _rootBypassPackages.value.contains(packageName),
                multiInstallEnabled = isMultiPre,
                adBypassEnabled = isEffectiveAdBypassPackage(packageName),
                identitySpoofEnabled = true,
                replaceMainApkEnabled = isReplacePre
            )
        }

        if (preArtifact != null && !preArtifact.isBuilding && !doesArtifactNeedConfigSync(packageName, preArtifact, selectedSet)) {
            return preArtifact
        }

        // If another app's build is currently holding standaloneBuildMutex, don't block Install/Share if a valid built file already exists on disk!
        if (preArtifact != null && standaloneBuildMutex.isLocked) {
            val f1 = java.io.File(preArtifact.outputFilePath)
            val f2 = java.io.File(preArtifact.publicDownloadPath)
            if ((f1.exists() && f1.length() > 10_000L) || (f2.exists() && f2.length() > 10_000L)) {
                return preArtifact
            }
        }

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(
                launchContext,
                com.example.ui.i18n.tr(
                    "Syncing enabled systems into APK...",
                    "চালু করা সব সিস্টেম APK-তে যুক্ত করা হচ্ছে...",
                    "चालू सिस्टम APK में जोड़े जा रहे हैं..."
                ),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        standaloneBuildMutex.lock()
        try {
            val freshApps = try { targetAppDao.getAllTargetAppsList() } catch (_: Throwable) { emptyList() }
            val freshSelectedSet = freshApps.filter { it.isSelected }.map { it.packageName }.toSet()
            val freshLabel = freshApps.find { it.packageName == packageName }?.appName ?: label
            val curIdentity = _currentIdentity.value
            val curWalk = _walkJoystickState.value
            val isMulti = _multiInstallApkPackages.value.contains(packageName)
            val isReplaceMain = !isMulti && _replaceMainApkPackages.value.contains(packageName)
            val isSpeed = _speedHackPackages.value.contains(packageName)
            val isStep = _stepWalkPackages.value.contains(packageName)
            val isRoot = _rootBypassPackages.value.contains(packageName)
            val isAdBypass = isEffectiveAdBypassPackage(packageName)
            val isIdentityOn = true

            val currentArt = _standaloneArtifacts.value[packageName]
                ?: com.example.data.engine.StandaloneApkBuilderEngine.findExistingBuiltArtifact(
                    context = app,
                    targetPackageName = packageName,
                    appName = freshLabel,
                    identity = curIdentity,
                    speedMultiplier = curWalk.appSpeedMultiplier,
                    speedEnabled = isSpeed,
                    stepWalkEnabled = isStep,
                    rootBypassEnabled = isRoot,
                    multiInstallEnabled = isMulti,
                    adBypassEnabled = isAdBypass,
                    identitySpoofEnabled = isIdentityOn,
                    replaceMainApkEnabled = isReplaceMain
                )

            if (currentArt != null && !doesArtifactNeedConfigSync(packageName, currentArt, freshSelectedSet)) {
                val curMap = _standaloneArtifacts.value.toMutableMap()
                curMap[packageName] = currentArt
                _standaloneArtifacts.value = curMap
                return currentArt
            }

            if (currentArt != null) {
                val existingFile = java.io.File(currentArt.outputFilePath).let { f ->
                    if (f.exists() && f.length() > 10_000L) f else java.io.File(currentArt.publicDownloadPath)
                }
                if (existingFile.exists() && existingFile.length() > 10_000L) {
                    val syncMap = _standaloneArtifacts.value.toMutableMap()
                    syncMap[packageName] = currentArt.copy(
                        isBuilding = true,
                        progressPercent = 10,
                        statusMessage = com.example.ui.i18n.tr(
                            "Syncing enabled systems into APK...",
                            "চালু করা সিস্টেমগুলো APK-তে যুক্ত করা হচ্ছে...",
                            "चालू सिस्टम APK में जोड़े जा रहे हैं..."
                        )
                    )
                    _standaloneArtifacts.value = syncMap

                    val refreshed = com.example.data.engine.StandaloneApkBuilderEngine.refreshConfigInExistingApk(
                        context = app,
                        existingApkFile = existingFile,
                        targetPackageName = packageName,
                        appName = freshLabel,
                        identity = curIdentity.copy(
                            latitude = curWalk.liveLatitude,
                            longitude = curWalk.liveLongitude,
                            locationName = curWalk.currentZoneLabel.ifBlank { curIdentity.locationName }
                        ),
                        liveLatitude = curWalk.liveLatitude,
                        liveLongitude = curWalk.liveLongitude,
                        speedMultiplier = curWalk.appSpeedMultiplier,
                        speedEnabled = isSpeed,
                        stepWalkEnabled = isStep,
                        rootBypassEnabled = isRoot,
                        identitySpoofEnabled = isIdentityOn,
                        multiInstallEnabled = isMulti,
                        adBypassEnabled = isAdBypass,
                        replaceMainApkEnabled = isReplaceMain,
                        onProgress = { progressPct, progressMsg ->
                            val curMap = _standaloneArtifacts.value.toMutableMap()
                            val curArt = curMap[packageName]
                            if (curArt != null && curArt.isBuilding) {
                                curMap[packageName] = curArt.copy(
                                    progressPercent = progressPct.coerceIn(0, 100),
                                    statusMessage = progressMsg
                                )
                                _standaloneArtifacts.value = curMap
                            }
                        }
                    )
                    if (refreshed != null) {
                        val doneMap = _standaloneArtifacts.value.toMutableMap()
                        doneMap[packageName] = refreshed
                        _standaloneArtifacts.value = doneMap
                        daemonEngine.addManualLog(
                            "STANDALONE_APK >> SYNCED FOR EXPORT: ${refreshed.appName} [Spd=$isSpeed, Stp=$isStep, Root=$isRoot, AdSkip=$isAdBypass, ID=$isIdentityOn]",
                            "GREEN"
                        )
                        return refreshed
                    }
                }
            }

            // Fallback: build fresh APK if none exists on disk or package name / version changed
            if (_standaloneApkPackages.value.contains(packageName)) {
                buildStandaloneApksCore(listOf(packageName), forceRebuild = true)
            }
            return _standaloneArtifacts.value[packageName]
        } finally {
            standaloneBuildMutex.unlock()
        }
    }

    fun installStandaloneApk(packageName: String, callerContext: android.content.Context? = null) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val app = getApplication<Application>()
            val launchContext = callerContext ?: app
            val readyArtifact = ensureStandaloneArtifactSyncedForExport(packageName, launchContext)
            val validFile = readyArtifact?.let { art ->
                java.io.File(art.outputFilePath).takeIf { it.exists() && it.length() > 10_000L }
                    ?: java.io.File(art.publicDownloadPath).takeIf { it.exists() && it.length() > 10_000L }
            }

            if (readyArtifact == null || validFile == null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        launchContext,
                        com.example.ui.i18n.tr(
                            "Could not build APK file, please tap the '🔄 Update' button.",
                            "APK ফাইল তৈরি করা যায়নি, অনুগ্রহ করে '🔄 আপডেট' বাটনে চাপুন।",
                            "APK फ़ाइल नहीं बनाई जा सकी, कृपया '🔄 अपडेट' बटन दबाएं।"
                        ),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            com.example.data.engine.StandaloneApkBuilderEngine.installStandaloneApk(launchContext, readyArtifact) { msg ->
                daemonEngine.addManualLog("STANDALONE_APK >> $msg", "GREEN")
                val curMap = _standaloneArtifacts.value.toMutableMap()
                val curArt = curMap[packageName]
                if (curArt != null) {
                    curMap[packageName] = curArt.copy(statusMessage = msg)
                    _standaloneArtifacts.value = curMap
                }
            }
        }
    }

    fun shareStandaloneApk(packageName: String, callerContext: android.content.Context? = null) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val app = getApplication<Application>()
            val launchContext = callerContext ?: app
            val readyArtifact = ensureStandaloneArtifactSyncedForExport(packageName, launchContext)
            val validFile = readyArtifact?.let { art ->
                java.io.File(art.outputFilePath).takeIf { it.exists() && it.length() > 10_000L }
                    ?: java.io.File(art.publicDownloadPath).takeIf { it.exists() && it.length() > 10_000L }
            }
            if (readyArtifact == null || validFile == null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        launchContext,
                        com.example.ui.i18n.tr(
                            "Could not prepare APK for sharing, please tap '🔄 Update'.",
                            "শেয়ার করার জন্য APK প্রস্তুত করা যায়নি, অনুগ্রহ করে '🔄 আপডেট' বাটনে চাপুন।",
                            "शेयर करने के लिए APK तैयार नहीं हो सका, कृपया '🔄 अपडेट' दबाएं।"
                        ),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                com.example.data.engine.StandaloneApkBuilderEngine.shareStandaloneApk(launchContext, readyArtifact)
            }
        }
    }

    fun toggleWalkPanelExpanded() {
        _walkJoystickState.value = _walkJoystickState.value.copy(
            isExpanded = !_walkJoystickState.value.isExpanded
        )
    }

    fun toggleWalkAutoHoldLock() {
        com.example.service.WalkBridge.toggleAutoHoldLock()
    }

    fun resetWalkStepsAndDistance() {
        com.example.service.WalkBridge.resetWalkStepsAndDistance()
        daemonEngine.addManualLog("STEP_WALK_CTRL >> STEP COUNTER & DISTANCE RESET TO 0", "YELLOW")
    }

    fun addInstantSteps(extraSteps: Int = 100) {
        if (extraSteps <= 0) return
        com.example.service.WalkBridge.addInstantSteps(extraSteps)
        val updated = _walkJoystickState.value
        _currentIdentity.value = _currentIdentity.value.copy(
            latitude = updated.liveLatitude,
            longitude = updated.liveLongitude
        )
        daemonEngine.addManualLog("STEP_WALK_CTRL >> +$extraSteps STEPS INJECTED (TOTAL: ${updated.stepsCount} STEPS)", "GREEN")
    }

    fun shiftToFreshValidationZone() {
        com.example.service.WalkBridge.shiftToFreshValidationZone()
        val updated = _walkJoystickState.value
        val newIdentity = _currentIdentity.value.copy(
            latitude = updated.liveLatitude,
            longitude = updated.liveLongitude,
            locationName = updated.currentZoneLabel.ifBlank { _currentIdentity.value.locationName }
        )
        _currentIdentity.value = newIdentity
        val app = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val allApps = targetAppDao.getAllTargetAppsList()
                val selectedPkgs = allApps.filter { it.isSelected }.map { it.packageName }
                val allActiveSet = (selectedPkgs + _rootBypassPackages.value + _stepWalkPackages.value + _speedHackPackages.value + _adBypassPackages.value).toSet()
                val removedList = allApps.map { it.packageName }.filter { !allActiveSet.contains(it) }
                ResetExecutionEngine.writeXposedIdentityFile(
                    identity = newIdentity,
                    context = app,
                    isGlobalSpoofEnabled = _isGlobalSpoofEnabled.value,
                    targetPackageNames = selectedPkgs,
                    removedPackageNames = removedList,
                    newlyAddedPackageNames = emptyList(),
                    rootBypassPackageNames = _rootBypassPackages.value.toList(),
                    stepWalkPackageNames = _stepWalkPackages.value.toList(),
                    speedHackPackageNames = _speedHackPackages.value.toList(),
                    adBypassPackageNames = _adBypassPackages.value.toList(),
                    applyTimezoneBroadcast = false
                )
                scheduleFastConfigSyncForBuiltPackages()
            } catch (_: Throwable) {}
        }
        daemonEngine.addManualLog(
            "PROOF_OF_LOCATION >> SHIFTED TO FRESH UNCONGESTED ZONE: ${updated.currentZoneLabel} (${String.format(Locale.US, "%.5f", updated.liveLatitude)}, ${String.format(Locale.US, "%.5f", updated.liveLongitude)})",
            "GREEN"
        )
    }

    fun toggleNftAutoValidation() {
        val cur = _walkJoystickState.value
        if (cur.isNftValidationRunning) {
            nftValidationJob?.cancel()
            nftValidationJob = null
            com.example.service.WalkBridge.toggleNftAutoValidation()
            daemonEngine.addManualLog("PROOF_OF_LOCATION >> NFT AUTO-VALIDATION STOPPED", "YELLOW")
            return
        }

        walkSimulationJob?.cancel()
        nftValidationJob?.cancel()

        nftValidationJob = viewModelScope.launch {
            val allApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                targetAppDao.getAllTargetAppsList()
            }
            val autoTargetPkgs = allApps.filter {
                it.packageName.contains("tokenrun", ignoreCase = true) ||
                it.appName.contains("tokenrun", ignoreCase = true) ||
                it.packageName == "com.clone.android.dual.space" ||
                it.packageName.contains("dual.space", ignoreCase = true) ||
                it.appName.contains("dual space", ignoreCase = true)
            }.map { it.packageName }.toMutableSet()
            autoTargetPkgs.add("com.clone.android.dual.space")

            for (pkg in autoTargetPkgs) {
                if (!_stepWalkPackages.value.contains(pkg)) {
                    toggleAppStepWalk(pkg)
                }
            }

            daemonEngine.addManualLog("PROOF_OF_LOCATION >> STARTING AUTO NFT VALIDATION (SPOT 1 [8s] -> 27m WALK -> SPOT 2 [8s])", "CYAN")
            com.example.service.WalkBridge.toggleNftAutoValidation()
        }
    }

    fun updateWalkJoystickVector(nx: Float, ny: Float, isDragging: Boolean) {
        com.example.service.WalkBridge.updateJoystickVector(nx, ny, isDragging)
        val updated = _walkJoystickState.value
        if (updated.liveLatitude != 0.0 && updated.liveLongitude != 0.0) {
            _currentIdentity.value = _currentIdentity.value.copy(
                latitude = updated.liveLatitude,
                longitude = updated.liveLongitude
            )
        }
    }

    private fun pushLiveWalkTelemetryAsync(
        lat: Double,
        lng: Double,
        speedMps: Float,
        bearingDeg: Float,
        steps: Int,
        isActive: Boolean,
        force: Boolean = false
    ) {
        com.example.service.WalkBridge.pushLiveWalkTelemetryAsync(
            lat = lat,
            lng = lng,
            speedMps = speedMps,
            bearingDeg = bearingDeg,
            steps = steps,
            isActive = isActive,
            force = force
        )
    }

    private fun pushLiveWalkTelemetryBlocking(
        lat: Double,
        lng: Double,
        speedMps: Float,
        bearingDeg: Float,
        steps: Int,
        isActive: Boolean
    ) {
        com.example.service.WalkBridge.pushLiveWalkTelemetryBlocking(
            lat = lat,
            lng = lng,
            speedMps = speedMps,
            bearingDeg = bearingDeg,
            steps = steps,
            isActive = isActive
        )
    }

    fun toggleAppSelection(packageName: String, currentSelected: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(packageName)) {
                targetAppDao.updateSelection(packageName, false)
                syncXposedConfigWithCurrentSelection(removedPackageNames = listOf(packageName))
                daemonEngine.addManualLog("SECURITY_GUARD >> $packageName (Magisk/LSPosed) CANNOT BE SELECTED", "YELLOW")
                return@launch
            }
            val newSelected = !currentSelected
            cachedSelectedTargetPackages = if (newSelected) {
                cachedSelectedTargetPackages + packageName
            } else {
                cachedSelectedTargetPackages - packageName
            }
            targetAppDao.updateSelection(packageName, newSelected)
            if (newSelected) {
                syncXposedConfigWithCurrentSelection(
                    newlyAddedPackageNames = listOf(packageName)
                )
                daemonEngine.addManualLog("LSPOSED_SCOPE >> AUTO-SELECTED $packageName IN LSPOSED", "GREEN")
            } else {
                val stillInOtherScope = _rootBypassPackages.value.contains(packageName) ||
                    _stepWalkPackages.value.contains(packageName) ||
                    _speedHackPackages.value.contains(packageName) ||
                    _adBypassPackages.value.contains(packageName)
                syncXposedConfigWithCurrentSelection(
                    removedPackageNames = if (stillInOtherScope) emptyList() else listOf(packageName)
                )
                daemonEngine.addManualLog("LSPOSED_SCOPE >> REMOVED $packageName FROM TARGET RESET", "YELLOW")
            }
            com.example.provider.PhantomIdentityProvider.markUserToggleChanged(getApplication())
            scheduleFastConfigSyncForPackage(packageName)
        }
    }

    fun selectAllApps(select: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val allApps = targetAppDao.getAllTargetAppsList()
            val keepScoped = _rootBypassPackages.value + _stepWalkPackages.value + _speedHackPackages.value +
                _adBypassPackages.value + com.example.data.device.LspScopeSyncCli.REQUIRED_SYSTEM_SCOPES
            val currentFilter = _appFilter.value
            val query = _appsSearchQuery.value.trim()

            fun matchesCurrentView(app: TargetAppEntity): Boolean {
                val matchesQuery = query.isEmpty() ||
                    app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
                val matchesFilter = when (currentFilter) {
                    TargetAppFilter.ALL -> true
                    TargetAppFilter.USER_APPS -> !app.isSystemApp
                    TargetAppFilter.SYSTEM_APPS -> app.isSystemApp
                    TargetAppFilter.SELECTED_ONLY -> app.isSelected
                }
                return matchesQuery && matchesFilter
            }

            if (select) {
                val newlyAdded = mutableListOf<String>()
                for (app in allApps) {
                    if (com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(app.packageName)) {
                        targetAppDao.updateSelection(app.packageName, false)
                        continue
                    }
                    if (matchesCurrentView(app)) {
                        targetAppDao.updateSelection(app.packageName, true)
                        newlyAdded.add(app.packageName)
                    }
                }
                syncXposedConfigWithCurrentSelection(
                    newlyAddedPackageNames = newlyAdded
                )
            } else {
                val removedPkgs = mutableListOf<String>()
                if (currentFilter == TargetAppFilter.ALL && query.isEmpty()) {
                    targetAppDao.updateAllSelection(false)
                    removedPkgs.addAll(allApps.map { it.packageName } - keepScoped)
                } else {
                    for (app in allApps) {
                        if (matchesCurrentView(app) && app.isSelected) {
                            targetAppDao.updateSelection(app.packageName, false)
                            if (!keepScoped.contains(app.packageName)) {
                                removedPkgs.add(app.packageName)
                            }
                        }
                    }
                }
                syncXposedConfigWithCurrentSelection(removedPackageNames = removedPkgs)
            }
            com.example.provider.PhantomIdentityProvider.markUserToggleChanged(getApplication())
            scheduleFastConfigSyncForBuiltPackages()
        }
    }

    fun addCustomTargetApp(name: String, pkg: String) {
        if (name.isBlank() || pkg.isBlank()) return
        val cleanPkg = pkg.trim()
        viewModelScope.launch {
            targetAppDao.insert(
                TargetAppEntity(
                    packageName = cleanPkg,
                    appName = name.trim(),
                    versionName = "1.0.0",
                    isSelected = true,
                    isSystemApp = false,
                    category = "Custom Target"
                )
            )
            syncXposedConfigWithCurrentSelection(newlyAddedPackageNames = listOf(cleanPkg))
        }
    }

    // Profiles Actions
    fun saveCurrentProfile(customName: String? = null) {
        val identity = _currentIdentity.value
        val name = if (!customName.isNullOrBlank()) customName else identity.deviceModel
        viewModelScope.launch {
            val entity = DeviceProfileEntity(
                profileName = name,
                androidId = identity.androidId,
                advertisingId = identity.advertisingId,
                imei = identity.imei,
                macAddress = identity.macAddress,
                bluetoothAddress = identity.bluetoothAddress,
                ipAddress = identity.ipAddress,
                deviceModel = identity.deviceModel,
                manufacturer = identity.manufacturer,
                buildFingerprint = identity.buildFingerprint,
                buildNumber = identity.buildNumber,
                deviceId = identity.deviceId,
                kernelVersion = identity.kernelVersion,
                socHardware = identity.socHardware,
                gpuRenderer = identity.gpuRenderer,
                basebandVersion = identity.basebandVersion,
                simImsi = identity.simImsi,
                simIccid = identity.simIccid,
                widevineDrmId = identity.widevineDrmId,
                bootloaderVersion = identity.bootloaderVersion,
                osVersion = identity.osVersion,
                serialNumber = identity.serialNumber,
                locationName = identity.locationName,
                latitude = identity.latitude,
                longitude = identity.longitude,
                timezone = identity.timezone,
                carrier = identity.carrier,
                display = identity.display,
                userAgent = identity.userAgent,
                webviewHash = identity.webviewHash,
                creativeId = identity.creativeId,
                isPreset = false
            )
            profileDao.insertProfile(entity)
            daemonEngine.addManualLog("PROFILE_SAVED >> $name STORED TO SECURE VAULT", "GREEN")
        }
    }

    fun applyProfile(profile: DeviceProfileEntity) {
        if (profile.isOriginalBackup || profile.profileName.contains("Original", ignoreCase = true)) {
            restoreOriginalDeviceIdentity()
            return
        }
        val newIdentity = DeviceIdentity(
            id = profile.id,
            profileName = profile.profileName,
            androidId = profile.androidId,
            advertisingId = profile.advertisingId,
            imei = profile.imei,
            macAddress = profile.macAddress,
            bluetoothAddress = profile.bluetoothAddress,
            ipAddress = profile.ipAddress,
            deviceModel = profile.deviceModel,
            manufacturer = profile.manufacturer,
            buildFingerprint = profile.buildFingerprint,
            buildNumber = profile.buildNumber,
            deviceId = profile.deviceId,
            kernelVersion = profile.kernelVersion,
            socHardware = profile.socHardware,
            gpuRenderer = profile.gpuRenderer,
            basebandVersion = profile.basebandVersion,
            simImsi = profile.simImsi,
            simIccid = profile.simIccid,
            widevineDrmId = profile.widevineDrmId,
            bootloaderVersion = profile.bootloaderVersion,
            osVersion = profile.osVersion,
            serialNumber = profile.serialNumber,
            locationName = profile.locationName,
            latitude = profile.latitude,
            longitude = profile.longitude,
            timezone = profile.timezone,
            carrier = profile.carrier,
            display = profile.display,
            userAgent = profile.userAgent,
            webviewHash = profile.webviewHash,
            creativeId = profile.creativeId,
            campaignId = "699d25ce19d8",
            seatId = "9541",
            isOriginalBackup = profile.isOriginalBackup
        )
        val prefs = getApplication<Application>().getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("active_profile_id", profile.id).apply()
        triggerReset(forcedIdentity = newIdentity)
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            val profile = profileDao.getProfileById(id)
            if (profile?.isOriginalBackup == true || profile?.profileName?.contains("Original", ignoreCase = true) == true) {
                daemonEngine.addManualLog("VAULT_SECURITY >> ORIGINAL DEVICE BACKUP CANNOT BE DELETED (LOCKED)", "RED")
                return@launch
            }
            profileDao.deleteById(id)
            daemonEngine.addManualLog("VAULT >> PROFILE REMOVED FROM SECURE STORAGE", "YELLOW")
        }
    }

    fun renameProfile(id: Long, newName: String) {
        viewModelScope.launch {
            profileDao.updateProfileName(id, newName)
            daemonEngine.addManualLog("VAULT >> PROFILE RENAMED TO: $newName", "CYAN")
        }
    }

    fun saveOriginalDeviceBackup() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val real = _realDeviceIdentity.value
            val existing = profileDao.getOriginalBackup()
            val backup = DeviceProfileEntity(
                id = existing?.id ?: 0,
                profileName = "Original Device Backup (${real.deviceModel})",
                androidId = real.androidId,
                advertisingId = real.advertisingId,
                imei = real.imei,
                macAddress = real.macAddress,
                bluetoothAddress = real.bluetoothAddress,
                ipAddress = real.ipAddress,
                deviceModel = real.deviceModel,
                manufacturer = real.manufacturer,
                buildFingerprint = real.buildFingerprint,
                buildNumber = real.buildNumber,
                deviceId = real.deviceId,
                kernelVersion = real.kernelVersion,
                socHardware = real.socHardware,
                gpuRenderer = real.gpuRenderer,
                basebandVersion = real.basebandVersion,
                simImsi = real.simImsi,
                simIccid = real.simIccid,
                widevineDrmId = real.widevineDrmId,
                bootloaderVersion = real.bootloaderVersion,
                osVersion = real.osVersion,
                serialNumber = real.serialNumber,
                locationName = real.locationName,
                latitude = real.latitude,
                longitude = real.longitude,
                timezone = real.timezone,
                carrier = real.carrier,
                display = real.display,
                userAgent = real.userAgent,
                webviewHash = real.webviewHash,
                creativeId = real.creativeId,
                isPreset = false,
                isOriginalBackup = true
            )
            profileDao.insertProfile(backup)
            daemonEngine.addManualLog("BACKUP >> ORIGINAL HARDWARE BACKUP SECURED IN VAULT", "GREEN")
        }
    }

    fun restoreOriginalDeviceIdentity() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val original = profileDao.getOriginalBackup()
            val restoreIdentity = if (original != null) {
                DeviceIdentity(
                    id = original.id,
                    profileName = original.profileName,
                    androidId = original.androidId,
                    advertisingId = original.advertisingId,
                    imei = original.imei,
                    macAddress = original.macAddress,
                    bluetoothAddress = original.bluetoothAddress,
                    ipAddress = original.ipAddress,
                    deviceModel = original.deviceModel,
                    manufacturer = original.manufacturer,
                    buildFingerprint = original.buildFingerprint,
                    buildNumber = original.buildNumber,
                    deviceId = original.deviceId,
                    kernelVersion = original.kernelVersion,
                    socHardware = original.socHardware,
                    gpuRenderer = original.gpuRenderer,
                    basebandVersion = original.basebandVersion,
                    simImsi = original.simImsi,
                    simIccid = original.simIccid,
                    widevineDrmId = original.widevineDrmId,
                    bootloaderVersion = original.bootloaderVersion,
                    osVersion = original.osVersion,
                    serialNumber = original.serialNumber,
                    locationName = original.locationName,
                    latitude = original.latitude,
                    longitude = original.longitude,
                    timezone = original.timezone,
                    carrier = original.carrier,
                    display = original.display,
                    userAgent = original.userAgent,
                    webviewHash = original.webviewHash,
                    creativeId = original.creativeId,
                    campaignId = "699d25ce19d8",
                    seatId = "9541"
                )
            } else {
                _realDeviceIdentity.value
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                triggerReset(forcedIdentity = restoreIdentity)
                daemonEngine.addManualLog("RESTORE >> ORIGINAL HARDWARE BACKUP RESTORED SUCCESSFULLY", "CYAN")
            }
        }
    }

    fun generateBackupFileName(): String {
        val model = _realDeviceIdentity.value.deviceModel.replace("[^a-zA-Z0-9]".toRegex(), "_").ifBlank { "phone" }
        return "ghostdevice_original_${model}_backup.json"
    }

    suspend fun getOriginalBackupJsonString(): String {
        val original = profileDao.getOriginalBackup()
        val identity = if (original != null) {
            DeviceIdentity(
                id = original.id,
                profileName = original.profileName,
                androidId = original.androidId,
                advertisingId = original.advertisingId,
                imei = original.imei,
                macAddress = original.macAddress,
                bluetoothAddress = original.bluetoothAddress,
                ipAddress = original.ipAddress,
                deviceModel = original.deviceModel,
                manufacturer = original.manufacturer,
                buildFingerprint = original.buildFingerprint,
                buildNumber = original.buildNumber,
                deviceId = original.deviceId,
                kernelVersion = original.kernelVersion,
                socHardware = original.socHardware,
                gpuRenderer = original.gpuRenderer,
                basebandVersion = original.basebandVersion,
                simImsi = original.simImsi,
                simIccid = original.simIccid,
                widevineDrmId = original.widevineDrmId,
                bootloaderVersion = original.bootloaderVersion,
                osVersion = original.osVersion,
                serialNumber = original.serialNumber,
                locationName = original.locationName,
                latitude = original.latitude,
                longitude = original.longitude,
                timezone = original.timezone,
                carrier = original.carrier,
                display = original.display,
                userAgent = original.userAgent,
                webviewHash = original.webviewHash,
                creativeId = original.creativeId,
                campaignId = "699d25ce19d8",
                seatId = "9541",
                isOriginalBackup = true
            )
        } else {
            _realDeviceIdentity.value
        }

        val jsonObject = org.json.JSONObject().apply {
            put("backupType", "PHANTOMCORE_ORIGINAL_HARDWARE_BACKUP")
            put("version", "1.0")
            put("exportedAt", System.currentTimeMillis())
            put("profileName", identity.profileName)
            put("androidId", identity.androidId)
            put("advertisingId", identity.advertisingId)
            put("imei", identity.imei)
            put("macAddress", identity.macAddress)
            put("bluetoothAddress", identity.bluetoothAddress)
            put("ipAddress", identity.ipAddress)
            put("deviceModel", identity.deviceModel)
            put("manufacturer", identity.manufacturer)
            put("buildFingerprint", identity.buildFingerprint)
            put("buildNumber", identity.buildNumber)
            put("deviceId", identity.deviceId)
            put("kernelVersion", identity.kernelVersion)
            put("socHardware", identity.socHardware)
            put("gpuRenderer", identity.gpuRenderer)
            put("basebandVersion", identity.basebandVersion)
            put("simImsi", identity.simImsi)
            put("simIccid", identity.simIccid)
            put("widevineDrmId", identity.widevineDrmId)
            put("bootloaderVersion", identity.bootloaderVersion)
            put("osVersion", identity.osVersion)
            put("serialNumber", identity.serialNumber)
            put("locationName", identity.locationName)
            put("latitude", identity.latitude)
            put("longitude", identity.longitude)
            put("timezone", identity.timezone)
            put("carrier", identity.carrier)
            put("display", identity.display)
            put("userAgent", identity.userAgent)
            put("webviewHash", identity.webviewHash)
            put("creativeId", identity.creativeId)
            put("isOriginalBackup", true)
        }
        return jsonObject.toString(2)
    }

    fun saveBackupToUri(context: Context, uri: android.net.Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val jsonString = getOriginalBackupJsonString()
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(jsonString.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    daemonEngine.addManualLog("VAULT_BACKUP >> BACKUP SAVED TO CHOSEN FILE", "GREEN")
                    onResult(true, "Backup file saved successfully!")
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    daemonEngine.addManualLog("VAULT_BACKUP_ERROR >> Write failed: ${e.message}", "RED")
                    onResult(false, "Failed to save: ${e.message}")
                }
            }
        }
    }

    fun exportOriginalBackupToFile(context: Context, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val jsonString = getOriginalBackupJsonString()
                val fileName = generateBackupFileName()
                var savedUri: android.net.Uri? = null

                // 1. Android 10+ (Q+) MediaStore Download API
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { stream ->
                            stream.write(jsonString.toByteArray(Charsets.UTF_8))
                            stream.flush()
                        }
                        savedUri = uri
                    }
                }

                // 2. Direct Downloads File fallback
                try {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (downloadsDir != null) {
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val targetFile = java.io.File(downloadsDir, fileName)
                        targetFile.writeText(jsonString)
                    }
                } catch (_: Exception) {}

                // 3. Internal app storage copy
                try {
                    val internalFile = java.io.File(context.filesDir, fileName)
                    internalFile.writeText(jsonString)
                } catch (_: Exception) {}

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    daemonEngine.addManualLog("VAULT_BACKUP >> HARDWARE BACKUP EXPORTED: $fileName", "GREEN")
                    onResult(true, "Backup downloaded to Downloads/$fileName")
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    daemonEngine.addManualLog("VAULT_BACKUP_ERROR >> Export failed: ${e.message}", "RED")
                    onResult(false, e.message ?: "Export failed")
                }
            }
        }
    }

    fun exportProfile(context: Context, profile: DeviceProfileEntity, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val json = org.json.JSONObject().apply {
                    put("profileName", profile.profileName)
                    put("androidId", profile.androidId)
                    put("advertisingId", profile.advertisingId)
                    put("imei", profile.imei)
                    put("macAddress", profile.macAddress)
                    put("bluetoothAddress", profile.bluetoothAddress)
                    put("ipAddress", profile.ipAddress)
                    put("deviceModel", profile.deviceModel)
                    put("manufacturer", profile.manufacturer)
                    put("buildFingerprint", profile.buildFingerprint)
                    put("buildNumber", profile.buildNumber)
                    put("deviceId", profile.deviceId)
                    put("kernelVersion", profile.kernelVersion)
                    put("socHardware", profile.socHardware)
                    put("gpuRenderer", profile.gpuRenderer)
                    put("basebandVersion", profile.basebandVersion)
                    put("simImsi", profile.simImsi)
                    put("simIccid", profile.simIccid)
                    put("widevineDrmId", profile.widevineDrmId)
                    put("bootloaderVersion", profile.bootloaderVersion)
                    put("securityPatch", profile.securityPatch)
                    put("osVersion", profile.osVersion)
                    put("serialNumber", profile.serialNumber)
                    put("locationName", profile.locationName)
                    put("latitude", profile.latitude)
                    put("longitude", profile.longitude)
                    put("timezone", profile.timezone)
                    put("carrier", profile.carrier)
                    put("display", profile.display)
                    put("userAgent", profile.userAgent)
                    put("webviewHash", profile.webviewHash)
                    put("creativeId", profile.creativeId)
                    put("exportedAt", System.currentTimeMillis())
                }

                val jsonString = json.toString(4)
                val cleanName = profile.profileName.replace("\\s+".toRegex(), "_")
                val fileName = "GhostDeviceProfile_${cleanName}_${System.currentTimeMillis() % 100000}.json"

                // 1. Android 10+ (Q+) MediaStore Download API
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { stream ->
                            stream.write(jsonString.toByteArray(Charsets.UTF_8))
                            stream.flush()
                        }
                    }
                }

                // 2. Direct Downloads File fallback
                try {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (downloadsDir != null) {
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val targetFile = java.io.File(downloadsDir, fileName)
                        targetFile.writeText(jsonString)
                    }
                } catch (_: Exception) {}

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    daemonEngine.addManualLog("PROFILE_EXPORT >> EXPORTED TO DOWNLOADS: $fileName", "GREEN")
                    onResult(
                        true,
                        com.example.ui.i18n.tr(
                            "Profile saved to Downloads folder: Downloads/$fileName",
                            "প্রোফাইল ডাউনলোড ফোল্ডারে সেভ হয়েছে: Downloads/$fileName",
                            "प्रोफ़ाइल Downloads फ़ोल्डर में सहेजा गया: Downloads/$fileName"
                        )
                    )
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, "Export failed: ${e.message}")
                }
            }
        }
    }

    fun exportCurrentIdentity(context: Context, onResult: (Boolean, String) -> Unit) {
        val identity = _currentIdentity.value
        val tempProfile = DeviceProfileEntity(
            profileName = "Active_Config_" + identity.deviceModel,
            androidId = identity.androidId,
            advertisingId = identity.advertisingId,
            imei = identity.imei,
            macAddress = identity.macAddress,
            bluetoothAddress = identity.bluetoothAddress,
            ipAddress = identity.ipAddress,
            deviceModel = identity.deviceModel,
            manufacturer = identity.manufacturer,
            buildFingerprint = identity.buildFingerprint,
            buildNumber = identity.buildNumber,
            deviceId = identity.deviceId,
            kernelVersion = identity.kernelVersion,
            socHardware = identity.socHardware,
            gpuRenderer = identity.gpuRenderer,
            basebandVersion = identity.basebandVersion,
            simImsi = identity.simImsi,
            simIccid = identity.simIccid,
            widevineDrmId = identity.widevineDrmId,
            bootloaderVersion = identity.bootloaderVersion,
            securityPatch = identity.securityPatch,
            osVersion = identity.osVersion,
            serialNumber = identity.serialNumber,
            locationName = identity.locationName,
            latitude = identity.latitude,
            longitude = identity.longitude,
            timezone = identity.timezone,
            carrier = identity.carrier,
            display = identity.display,
            userAgent = identity.userAgent,
            webviewHash = identity.webviewHash,
            creativeId = identity.creativeId
        )
        exportProfile(context, tempProfile, onResult)
    }

    fun importAndRestoreBackupJson(context: Context, uri: android.net.Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val jsonString = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw IllegalArgumentException("Could not read backup file")

                val json = org.json.JSONObject(jsonString)
                val isOriginal = json.optBoolean("isOriginalBackup", false) || 
                                 json.optString("profileName", "").contains("original", ignoreCase = true) ||
                                 json.optString("profileName", "").contains("backup", ignoreCase = true)

                val restoredIdentity = DeviceIdentity(
                    id = 0,
                    profileName = json.optString("profileName", if (isOriginal) "Restored Original Hardware Backup" else json.optString("deviceModel", "Imported Profile")),
                    androidId = json.optString("androidId", ""),
                    advertisingId = json.optString("advertisingId", ""),
                    imei = json.optString("imei", ""),
                    macAddress = json.optString("macAddress", ""),
                    bluetoothAddress = json.optString("bluetoothAddress", ""),
                    ipAddress = json.optString("ipAddress", ""),
                    deviceModel = json.optString("deviceModel", "Restored Device"),
                    manufacturer = json.optString("manufacturer", "Android"),
                    buildFingerprint = json.optString("buildFingerprint", ""),
                    buildNumber = json.optString("buildNumber", "UP1A.231005.007"),
                    deviceId = json.optString("deviceId", "marlin"),
                    kernelVersion = json.optString("kernelVersion", "5.15.110-android14-11-g89a3f2b"),
                    socHardware = json.optString("socHardware", "Snapdragon 8 Gen 3 (SM8650-AB)"),
                    gpuRenderer = json.optString("gpuRenderer", "Adreno (TM) 750 @ 1000MHz"),
                    basebandVersion = json.optString("basebandVersion", "g5300g-240301-240502-B-11812984"),
                    simImsi = json.optString("simImsi", "310260982314561"),
                    simIccid = json.optString("simIccid", "8901410321111854920F"),
                    widevineDrmId = json.optString("widevineDrmId", "4912a870b9213e4f"),
                    bootloaderVersion = json.optString("bootloaderVersion", "b1-0.5-10808477"),
                    osVersion = json.optString("osVersion", "Android 14"),
                    serialNumber = json.optString("serialNumber", ""),
                    locationName = json.optString("locationName", "Default"),
                    latitude = json.optDouble("latitude", 0.0),
                    longitude = json.optDouble("longitude", 0.0),
                    timezone = json.optString("timezone", "UTC"),
                    carrier = json.optString("carrier", "Default"),
                    display = json.optString("display", "1080x2400"),
                    userAgent = json.optString("userAgent", ""),
                    webviewHash = json.optString("webviewHash", ""),
                    creativeId = json.optString("creativeId", ""),
                    campaignId = "699d25ce19d8",
                    seatId = "9541",
                    isOriginalBackup = isOriginal
                )

                val targetId = if (isOriginal) {
                    profileDao.getOriginalBackup()?.id ?: 0
                } else {
                    0 // New profile row in Database
                }

                val backupEntity = DeviceProfileEntity(
                    id = targetId,
                    profileName = restoredIdentity.profileName,
                    androidId = restoredIdentity.androidId,
                    advertisingId = restoredIdentity.advertisingId,
                    imei = restoredIdentity.imei,
                    macAddress = restoredIdentity.macAddress,
                    bluetoothAddress = restoredIdentity.bluetoothAddress,
                    ipAddress = restoredIdentity.ipAddress,
                    deviceModel = restoredIdentity.deviceModel,
                    manufacturer = restoredIdentity.manufacturer,
                    buildFingerprint = restoredIdentity.buildFingerprint,
                    buildNumber = restoredIdentity.buildNumber,
                    deviceId = restoredIdentity.deviceId,
                    kernelVersion = restoredIdentity.kernelVersion,
                    socHardware = restoredIdentity.socHardware,
                    gpuRenderer = restoredIdentity.gpuRenderer,
                    basebandVersion = restoredIdentity.basebandVersion,
                    simImsi = restoredIdentity.simImsi,
                    simIccid = restoredIdentity.simIccid,
                    widevineDrmId = restoredIdentity.widevineDrmId,
                    bootloaderVersion = restoredIdentity.bootloaderVersion,
                    osVersion = restoredIdentity.osVersion,
                    serialNumber = restoredIdentity.serialNumber,
                    locationName = restoredIdentity.locationName,
                    latitude = restoredIdentity.latitude,
                    longitude = restoredIdentity.longitude,
                    timezone = restoredIdentity.timezone,
                    carrier = restoredIdentity.carrier,
                    display = restoredIdentity.display,
                    userAgent = restoredIdentity.userAgent,
                    webviewHash = restoredIdentity.webviewHash,
                    creativeId = restoredIdentity.creativeId,
                    isPreset = false,
                    isOriginalBackup = isOriginal
                )
                profileDao.insertProfile(backupEntity)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    triggerReset(forcedIdentity = restoredIdentity)
                    if (isOriginal) {
                        daemonEngine.addManualLog("RESTORE_JSON >> ORIGINAL HARDWARE RESTORED FROM FILE: ${restoredIdentity.deviceModel}", "GREEN")
                        onResult(true, "Original device successfully restored from backup file!")
                    } else {
                        daemonEngine.addManualLog("RESTORE_JSON >> PROFILE IMPORTED & APPLIED: ${restoredIdentity.profileName}", "GREEN")
                        onResult(
                            true,
                            com.example.ui.i18n.tr(
                                "Profile successfully installed and added to profile list!",
                                "প্রোফাইলটি সফলভাবে ইন্সটল করা হয়েছে এবং প্রোফাইল লিস্টে যুক্ত করা হয়েছে!",
                                "प्रोफ़ाइल सफलतापूर्वक इंस्टॉल किया गया और प्रोफ़ाइल सूची में जोड़ा गया!"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    daemonEngine.addManualLog("RESTORE_JSON_ERROR >> Import failed: ${e.message}", "RED")
                    onResult(false, "Failed to parse backup: ${e.message}")
                }
            }
        }
    }

    // Logs Actions
    fun clearAllLogs() {
        viewModelScope.launch {
            resetLogDao.clearAllLogs()
            daemonEngine.addManualLog("RESET_LOGS >> AUDIT TRAIL PURGED", "YELLOW")
        }
    }

    // Advertising ID Actions (Root Hook & Virtual Space System 3)
    fun randomizeAdvertisingId(context: Context) {
        val newAdId = java.util.UUID.randomUUID().toString()
        val isRoot = com.example.data.device.RootChecker.isDeviceRooted()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var rootSuccess = false
            if (isRoot) {
                try {
                    val cmd = "mkdir -p /data/data/com.google.android.gms/shared_prefs 2>/dev/null && " +
                            "echo '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map><string name=\"adid_key\">$newAdId</string><boolean name=\"enable_limit_ad_tracking\" value=\"false\" /></map>' > /data/data/com.google.android.gms/shared_prefs/adid_settings.xml && " +
                            "chmod 660 /data/data/com.google.android.gms/shared_prefs/adid_settings.xml && " +
                            "am force-stop com.google.android.gms 2>/dev/null"
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                    val exitCode = process.waitFor()
                    rootSuccess = (exitCode == 0)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Update current identity
            _currentIdentity.value = _currentIdentity.value.copy(advertisingId = newAdId)
            scheduleFastConfigSyncForBuiltPackages()

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isRoot && rootSuccess) {
                    daemonEngine.addManualLog("ROOT_HOOK >> Google Play Services Advertising ID changed to $newAdId", "GREEN")
                    android.widget.Toast.makeText(context, "Root Hook: Advertising ID successfully changed in Google Play Services!", android.widget.Toast.LENGTH_SHORT).show()
                } else if (isRoot) {
                    daemonEngine.addManualLog("ROOT_HOOK >> Ad ID updated ($newAdId)", "YELLOW")
                    android.widget.Toast.makeText(context, "Advertising ID randomized (Root command executed)", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    daemonEngine.addManualLog("VIRTUAL_SPACE >> Advertising ID spoofed to $newAdId", "CYAN")
                    android.widget.Toast.makeText(context, "Advertising ID randomized via Virtual Space!", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openGoogleAdsSettings(context: Context) {
        try {
            val intent = android.content.Intent("com.google.android.gms.settings.ADS_PRIVACY").apply {
                setPackage("com.google.android.gms")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = android.content.Intent(android.provider.Settings.ACTION_PRIVACY_SETTINGS).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Could not open Google Ads Settings", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // AI Analysis & Reporting Actions
    fun runAiPrivacyAnalysis(userQuery: String? = null) {
        if (_isAiAnalyzing.value) return
        _isAiAnalyzing.value = true

        viewModelScope.launch {
            daemonEngine.addManualLog("AI_SECURITY_AGENT >> INITIATING REAL-TIME PRIVACY AUDIT", "CYAN")

            val result = GeminiService.analyzePrivacyAndGenerateReport(
                identity = _currentIdentity.value,
                targetAppCount = selectedAppsCount.value,
                lastResetTime = _currentIdentity.value.timestamp,
                activeTimerMinutes = _timerSettings.value.minutes,
                userQuery = userQuery
            )
            _latestAiAudit.value = result

            // Persist report to Room
            val entity = AiSecurityReportEntity(
                title = result.title,
                privacyScore = result.privacyScore,
                threatLevel = result.threatLevel,
                deviceModel = _currentIdentity.value.deviceModel,
                androidIdMasked = "${_currentIdentity.value.androidId.take(4)}...${_currentIdentity.value.androidId.takeLast(4)}",
                detectedVectors = result.detectedVectors.joinToString("; "),
                aiAnalysisText = result.executiveSummary,
                actionableHardening = result.hardeningRecommendations.joinToString("\n• ")
            )
            aiReportDao.insertReport(entity)

            daemonEngine.addManualLog("AI_AUDIT_COMPLETE >> SCORE: ${result.privacyScore}/100 (THREAT: ${result.threatLevel})", "GREEN")
            _isAiAnalyzing.value = false
        }
    }
}
