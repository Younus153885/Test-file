package com.example

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.AppNavTab
import com.example.ui.MainViewModel
import com.example.ui.components.FloatingTimerBadge
import com.example.ui.components.ResetProgressDialog
import com.example.ui.components.SystemRequirementsDialog
import com.example.ui.i18n.LanguageManager
import com.example.ui.i18n.LanguageSelectorChip
import com.example.ui.i18n.currentAppLang
import com.example.ui.i18n.tr
import com.example.ui.screens.AiAnalysisScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.GhostDaemonScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.TargetAppsScreen
import com.example.ui.screens.TimerScreen
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBgDarker
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardElevated
import com.example.ui.theme.CyberShimmerPrefs
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RedDanger
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.cyberShimmerBorder
import kotlinx.coroutines.delay

fun isInternetAvailable(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        hasTransport && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) {
        false
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var isInitialResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageManager.init(this)
        CyberShimmerPrefs.init(this)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                PhantomCoreApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isInitialResume) {
            isInitialResume = false
            return
        }
        viewModel.refreshPermissionsStatus()
        if (isInternetAvailable(this)) {
            viewModel.checkLicenseStatus()
            viewModel.checkAppUpdate()
        }
    }
}

@Composable
fun PhantomCoreApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeLang = currentAppLang()

    val showResetModal by viewModel.showResetModal.collectAsState()
    val resetProgressState by viewModel.resetProgressState.collectAsState()
    val isResetting by viewModel.isResetting.collectAsState()

    val initialOnline = remember { isInternetAvailable(context) }
    var actualOnline by remember { mutableStateOf(initialOnline) }
    var hasPassedLaunchInternetGate by rememberSaveable { mutableStateOf(initialOnline) }
    var offlineSinceElapsedMs by remember { mutableLongStateOf(0L) }
    var nowElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val inNetworkResetFlow = isResetting ||
            showResetModal ||
            (resetProgressState != null && resetProgressState?.isComplete == false) ||
            com.example.data.engine.ResetExecutionEngine.isNetworkResetGraceActive()

    fun syncNetworkSnapshot() {
        val onlineNow = isInternetAvailable(context)
        val ts = SystemClock.elapsedRealtime()
        nowElapsedMs = ts
        actualOnline = onlineNow
        if (onlineNow) {
            hasPassedLaunchInternetGate = true
            offlineSinceElapsedMs = 0L
        } else {
            val resetActive = viewModel.isResetting.value ||
                    viewModel.showResetModal.value ||
                    (viewModel.resetProgressState.value?.isComplete == false) ||
                    com.example.data.engine.ResetExecutionEngine.isNetworkResetGraceActive()
            if (resetActive) {
                offlineSinceElapsedMs = 0L
            } else if (offlineSinceElapsedMs == 0L) {
                offlineSinceElapsedMs = ts
            }
        }
    }

    // Live NetworkCallback (dispatched safely to Main Looper) + 1s sync loop
    DisposableEffect(context, lifecycleOwner) {
        val mainHandler = Handler(Looper.getMainLooper())
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val postSync = Runnable { syncNetworkSnapshot() }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post(postSync)
            }

            override fun onLost(network: Network) {
                mainHandler.post(postSync)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                mainHandler.post(postSync)
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm?.registerNetworkCallback(request, callback)
        } catch (_: Throwable) {
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainHandler.post(postSync)
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            mainHandler.removeCallbacks(postSync)
            try {
                cm?.unregisterNetworkCallback(callback)
            } catch (_: Throwable) {
            }
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            syncNetworkSnapshot()
        }
    }

    LaunchedEffect(inNetworkResetFlow) {
        if (inNetworkResetFlow) {
            offlineSinceElapsedMs = 0L
        }
    }

    LaunchedEffect(actualOnline) {
        if (actualOnline) {
            viewModel.checkLicenseStatus()
            viewModel.checkAppUpdate()
        }
    }

    // Strict Internet Gate at launch, while never interrupting an active identity/network reset
    // or its 35-second reconnection window
    val shouldShowInternetGate = !actualOnline &&
            !inNetworkResetFlow &&
            (!hasPassedLaunchInternetGate || (offlineSinceElapsedMs > 0L && (nowElapsedMs - offlineSinceElapsedMs) >= 4500L))

    if (shouldShowInternetGate) {
        NoInternetConnectionGate(
            onRetry = {
                val online = isInternetAvailable(context)
                actualOnline = online
                nowElapsedMs = SystemClock.elapsedRealtime()
                if (online) {
                    hasPassedLaunchInternetGate = true
                    offlineSinceElapsedMs = 0L
                    viewModel.checkLicenseStatus()
                    viewModel.checkAppUpdate()
                } else {
                    Toast.makeText(
                        context,
                        tr(
                            "Still no internet connection! Please turn on Wi-Fi or Mobile Data.",
                            "এখনো ইন্টারনেট কানেকশন পাওয়া যায়নি! দয়া করে Wi-Fi বা মোবাইল ডাটা চালু করুন।",
                            "अभी भी इंटरनेट कनेक्शन नहीं मिला! कृपया Wi-Fi या मोबाइल डेटा चालू करें।"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
        return
    }

    val currentTab by viewModel.currentTab.collectAsState()
    val identity by viewModel.currentIdentity.collectAsState()
    val realIdentity by viewModel.realDeviceIdentity.collectAsState()
    val isShieldActive by viewModel.isShieldActive.collectAsState()
    val lastResetSummary by viewModel.lastResetSummary.collectAsState()
    val showSystemRequirements by viewModel.showSystemRequirements.collectAsState()

    val timerSettings by viewModel.timerSettings.collectAsState()
    val isTimerRunning by viewModel.isTimerRunning.collectAsState()
    val timerRemainingSeconds by viewModel.timerRemainingSeconds.collectAsState()
    val showFloatingBadge by viewModel.showFloatingBadge.collectAsState()

    val targetApps by viewModel.targetApps.collectAsState()
    val selectedAppsCount by viewModel.selectedAppsCount.collectAsState()
    val appsSearchQuery by viewModel.appsSearchQuery.collectAsState()
    val appFilter by viewModel.appFilter.collectAsState()
    val isScanningApps by viewModel.isScanningApps.collectAsState()

    val savedProfiles by viewModel.savedProfiles.collectAsState()
    val resetLogs by viewModel.resetLogs.collectAsState()
    val daemonState by viewModel.daemonState.collectAsState()

    val isAiAnalyzing by viewModel.isAiAnalyzing.collectAsState()
    val latestAiAudit by viewModel.latestAiAudit.collectAsState()
    val aiReports by viewModel.aiReports.collectAsState()
    val permissionsStatus by viewModel.permissionsStatus.collectAsState()
    val permissionWarningMessage by viewModel.permissionWarningMessage.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val isGlobalSpoofEnabled by viewModel.isGlobalSpoofEnabled.collectAsState()
    val rootBypassPackages by viewModel.rootBypassPackages.collectAsState()
    val stepWalkPackages by viewModel.stepWalkPackages.collectAsState()
    val speedHackPackages by viewModel.speedHackPackages.collectAsState()
    val adBypassPackages by viewModel.adBypassPackages.collectAsState()
    val standaloneApkPackages by viewModel.standaloneApkPackages.collectAsState()
    val multiInstallApkPackages by viewModel.multiInstallApkPackages.collectAsState()
    val replaceMainApkPackages by viewModel.replaceMainApkPackages.collectAsState()
    val standaloneArtifacts by viewModel.standaloneArtifacts.collectAsState()
    val walkJoystickState by viewModel.walkJoystickState.collectAsState()
    val isFloatingJoystickRunning by viewModel.isFloatingJoystickRunning.collectAsState()

    // License & Admin State
    val isLicenseValid by viewModel.isLicenseValid.collectAsState()
    val isCheckingLicense by viewModel.isCheckingLicense.collectAsState()
    val isAppInitialLoading by viewModel.isAppInitialLoading.collectAsState()
    val licenseErrorMessage by viewModel.licenseErrorMessage.collectAsState()
    val publicPackages by viewModel.publicPackages.collectAsState()
    val publicSettings by viewModel.publicSettings.collectAsState()
    val pendingTransferLicense by viewModel.pendingTransferLicense.collectAsState()
    val showAdminPanel by viewModel.showAdminPanel.collectAsState()
    val adminToken by viewModel.adminToken.collectAsState()
    var showSetupVideoDialog by rememberSaveable { mutableStateOf(false) }

    // Handle back button for Admin Panel or sub-tabs to return to Dashboard
    BackHandler(enabled = showAdminPanel) {
        viewModel.toggleAdminPanel(false)
    }

    BackHandler(enabled = !showAdminPanel && currentTab != AppNavTab.DASHBOARD) {
        viewModel.selectTab(AppNavTab.DASHBOARD)
    }

    Box(modifier = Modifier.fillMaxSize().background(CyberBg)) {
        if (!showAdminPanel && (!publicSettings.licenseSystemEnabled || isLicenseValid)) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets.statusBars,
                containerColor = CyberBg,
            bottomBar = {
                CyberBottomNavigationBar(
                    selectedTab = currentTab,
                    onTabSelected = { viewModel.selectTab(it) }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentTab) {
                    AppNavTab.DASHBOARD -> DashboardScreen(
                        currentIdentity = identity,
                        realIdentity = realIdentity,
                        isShieldActive = isShieldActive,
                        onToggleShield = { viewModel.toggleShield() },
                        selectedAppsCount = selectedAppsCount,
                        lastResetSummary = lastResetSummary,
                        timerRemainingSeconds = timerRemainingSeconds,
                        isTimerRunning = isTimerRunning,
                        onTriggerReset = { viewModel.triggerReset() },
                        onOpenSystemRequirements = { viewModel.toggleSystemRequirements(true) },
                        onOpenTimerTab = { viewModel.selectTab(AppNavTab.TIMER) },
                        onOpenAppsTab = { viewModel.selectTab(AppNavTab.APPS) },
                        onOpenDaemonTab = { viewModel.selectTab(AppNavTab.DAEMON) },
                        onOpenAiTab = { viewModel.selectTab(AppNavTab.AI_AUDIT) },
                        timerSettings = timerSettings,
                        onToggleResetOption = { viewModel.toggleResetOption(it) },
                        permissionsStatus = permissionsStatus,
                        onSelectDeviceModelPreset = { viewModel.setDeviceModelPreset(it) },
                        onApplyCustomDeviceModel = { model, mfr, buildNum, devId, kernel, soc, gpu, gpuVer, baseband, imsi, iccid, widevine, bootloader, region, locale ->
                            viewModel.updateCustomIdentityDetails(model, mfr, buildNum, devId, kernel, soc, gpu, gpuVer, baseband, imsi, iccid, widevine, bootloader, region, locale)
                        },
                        onRandomizeAdvertisingId = { viewModel.randomizeAdvertisingId(context) },
                        onOpenGoogleAdsSettings = { viewModel.openGoogleAdsSettings(context) },
                        onRefreshHardwareInfo = { viewModel.refreshLiveHardwareInfo() },
                        isGlobalSpoofEnabled = isGlobalSpoofEnabled,
                        onToggleGlobalSpoof = { viewModel.toggleGlobalSpoofMode() },
                        onUpdateSingleParameter = { k, v -> viewModel.updateSingleParameter(k, v) },
                        isResetting = isResetting,
                        onOpenAdminPanel = { viewModel.toggleAdminPanel(true) },
                        publicSettings = publicSettings,
                        onOpenSetupVideo = { showSetupVideoDialog = true }
                    )

                    AppNavTab.TIMER -> TimerScreen(
                        settings = timerSettings,
                        isTimerRunning = isTimerRunning,
                        remainingSeconds = timerRemainingSeconds,
                        onUpdateMinutes = { viewModel.updateTimerMinutes(it) },
                        onUpdateSeconds = { viewModel.updateTimerSeconds(it) },
                        onToggleLoopMode = { viewModel.toggleTimerLoopMode() },
                        onToggleStartWithSystem = { viewModel.toggleTimerStartWithSystem() },
                        onToggleResetOption = { viewModel.toggleResetOption(it) },
                        onStartTimer = { viewModel.startTimer() },
                        onStopTimer = { viewModel.stopTimer() },
                        showFloatingBadge = showFloatingBadge,
                        onToggleFloatingBadge = { viewModel.toggleFloatingBadge() }
                    )

                    AppNavTab.APPS -> TargetAppsScreen(
                        apps = targetApps,
                        selectedCount = selectedAppsCount,
                        searchQuery = appsSearchQuery,
                        onSearchQueryChange = { viewModel.setAppsSearchQuery(it) },
                        currentFilter = appFilter,
                        onFilterChange = { viewModel.setAppFilter(it) },
                        isScanning = isScanningApps,
                        onRefreshDeviceApps = { viewModel.syncDeviceInstalledApps(forceClearOld = true) },
                        onToggleAppSelection = { pkg, selected -> viewModel.toggleAppSelection(pkg, selected) },
                        onSelectAll = { viewModel.selectAllApps(it) },
                        onAddCustomApp = { name, pkg -> viewModel.addCustomTargetApp(name, pkg) },
                        isGlobalSpoofEnabled = isGlobalSpoofEnabled,
                        onToggleGlobalSpoof = { viewModel.toggleGlobalSpoofMode() },
                        rootBypassPackages = rootBypassPackages,
                        onToggleRootBypass = { pkg -> viewModel.toggleAppRootBypass(pkg) },
                        stepWalkPackages = stepWalkPackages,
                        onToggleStepWalk = { pkg -> viewModel.toggleAppStepWalk(pkg) },
                        speedHackPackages = speedHackPackages,
                        onToggleSpeedHack = { pkg -> viewModel.toggleAppSpeedHack(pkg) },
                        adBypassPackages = adBypassPackages,
                        onToggleAdBypass = { pkg -> viewModel.toggleAppAdBypass(pkg) },
                        standaloneApkPackages = standaloneApkPackages,
                        multiInstallApkPackages = multiInstallApkPackages,
                        replaceMainApkPackages = replaceMainApkPackages,
                        standaloneArtifacts = standaloneArtifacts,
                        onToggleStandaloneApk = { pkg -> viewModel.toggleAppStandaloneApk(pkg) },
                        onSelectStandaloneMode = { pkg, replaceMain -> viewModel.selectStandaloneInstallMode(pkg, replaceMain) },
                        onToggleMultiInstallApk = { pkg -> viewModel.toggleAppMultiInstallApk(pkg) },
                        onInstallStandaloneApk = { pkg -> viewModel.installStandaloneApk(pkg, context) },
                        onShareStandaloneApk = { pkg -> viewModel.shareStandaloneApk(pkg, context) },
                        onRebuildStandaloneApk = { pkg -> viewModel.buildStandaloneApksForPackages(listOf(pkg)) },
                        walkJoystickState = walkJoystickState,
                        onUpdateWalkJoystick = { nx, ny, dragging -> viewModel.updateWalkJoystickVector(nx, ny, dragging) },
                        onToggleWalkExpanded = { viewModel.toggleWalkPanelExpanded() },
                        onToggleWalkAutoHold = { viewModel.toggleWalkAutoHoldLock() },
                        onAddInstantSteps = { steps -> viewModel.addInstantSteps(steps) },
                        onResetWalkSteps = { viewModel.resetWalkStepsAndDistance() },
                        onToggleNftAutoValidation = { viewModel.toggleNftAutoValidation() },
                        onShiftValidationZone = { viewModel.shiftToFreshValidationZone() },
                        onToggleVolumeSpeedMode = { viewModel.toggleVolumeSpeedMode() },
                        onStepAppSpeed = { increase -> viewModel.stepAppSpeedMultiplier(increase) },
                        onSetAppSpeed = { mult -> viewModel.setAppSpeedMultiplier(mult) },
                        onResetAppSpeed = { viewModel.resetAppSpeedMultiplier() },
                        isFloatingJoystickRunning = isFloatingJoystickRunning,
                        onToggleFloatingJoystick = { viewModel.toggleFloatingJoystick() }
                    )

                    AppNavTab.PROFILES -> ProfilesScreen(
                        profiles = savedProfiles,
                        onSaveCurrentProfile = { viewModel.saveCurrentProfile(it) },
                        onApplyProfile = { viewModel.applyProfile(it) },
                        onDeleteProfile = { viewModel.deleteProfile(it) },
                        onRenameProfile = { id, name -> viewModel.renameProfile(id, name) },
                        onBackupOriginalDevice = { viewModel.saveOriginalDeviceBackup() },
                        onRestoreOriginalDevice = { viewModel.restoreOriginalDeviceIdentity() },
                        onExportBackupFile = {
                            viewModel.exportOriginalBackupToFile(context) { success, msg ->
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onSaveBackupToUri = { uri ->
                            viewModel.saveBackupToUri(context, uri) { success, msg ->
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onImportBackupFile = { uri ->
                            viewModel.importAndRestoreBackupJson(context, uri) { success, msg ->
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        backupFileName = viewModel.generateBackupFileName(),
                        currentIdentity = identity,
                        onExportSingleProfile = { profile ->
                            viewModel.exportProfile(context, profile) { success, msg ->
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onExportCurrentIdentity = {
                            viewModel.exportCurrentIdentity(context) { success, msg ->
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    )

                    AppNavTab.LOGS -> LogsScreen(
                        logs = resetLogs,
                        onClearAllLogs = { viewModel.clearAllLogs() }
                    )

                    AppNavTab.DAEMON -> GhostDaemonScreen(
                        daemonState = daemonState,
                        identity = identity,
                        onToggleDaemon = { viewModel.daemonEngine.toggleDaemon() }
                    )

                    AppNavTab.AI_AUDIT -> AiAnalysisScreen(
                        identity = identity,
                        isAnalyzing = isAiAnalyzing,
                        latestAudit = latestAiAudit,
                        historicalReports = aiReports,
                        onTriggerAudit = { viewModel.runAiPrivacyAnalysis(it) }
                    )
                }

                // Floating countdown bubble over screens (as shown in video)
                if (isTimerRunning && showFloatingBadge && currentTab != AppNavTab.TIMER) {
                    FloatingTimerBadge(
                        visible = true,
                        remainingSeconds = timerRemainingSeconds,
                        onClick = { viewModel.selectTab(AppNavTab.TIMER) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 16.dp, end = 16.dp)
                    )
                }
            }
        }

        // 15-step Reset Modal (Pipeline shown in video)
        if (showResetModal) {
            ResetProgressDialog(
                state = resetProgressState,
                onDismiss = { viewModel.dismissResetModal() }
            )
        }

        // System Requirements Modal (shown at 00:19 in video)
        if (showSystemRequirements) {
            SystemRequirementsDialog(
                onDismiss = {
                    viewModel.toggleSystemRequirements(false)
                    viewModel.refreshPermissionsStatus()
                },
                onRequestRoot = { callback ->
                    viewModel.requestRootAccess(callback)
                }
            )
        }

        } // end of main app content when !showAdminPanel && license valid

        // App Update Checker Modal (GhostDevice / QUiCKFB_UPDATE — shown on top of any screen when update is active)
        val updateInfoState = updateInfo
        var updateDismissed by remember(
            updateInfoState?.versionCode,
            updateInfoState?.versionName,
            updateInfoState?.updateMessage
        ) { mutableStateOf(false) }
        if (updateInfoState != null && !updateDismissed) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = {
                    if (!updateInfoState.isForceUpdate) {
                        updateDismissed = true
                    }
                },
                properties = androidx.compose.ui.window.DialogProperties(
                    dismissOnClickOutside = false,
                    dismissOnBackPress = !updateInfoState.isForceUpdate
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(18.dp), phaseOffset = 0.1f, accentColor = NeonCyan),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = CyberBgDarker)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Update Available",
                            tint = NeonCyan,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = tr(
                                "Update Available (v${updateInfoState.versionName})",
                                "নতুন আপডেট এসেছে (v${updateInfoState.versionName})",
                                "नया अपडेट उपलब्ध है (v${updateInfoState.versionName})"
                            ),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = updateInfoState.updateMessage,
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            if (!updateInfoState.isForceUpdate) {
                                TextButton(
                                    onClick = { updateDismissed = true }
                                ) {
                                    Text(tr("Cancel", "বাতিল", "रद्द करें"), color = TextMuted)
                                }
                            }
                            Button(
                                onClick = {
                                    try {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(updateInfoState.updateUrl)
                                        )
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            tr("Could not open link", "লিংক ওপেন করা যায়নি", "लिंक नहीं खोला जा सका"),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                            ) {
                                Text(
                                    tr("Update Now", "আপডেট করুন", "अभी अपडेट करें"),
                                    color = CyberBg,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Live In-App Notification & Promotion Popup (Cyber Dark Mode, controlled from Admin Panel — shown at most once per app open)
        var dismissedNotificationKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        var shownOnceInSession by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        val activeNotifKey = "${publicSettings.notificationTitle.trim()}|${publicSettings.notificationMessage.trim()}|${publicSettings.promoTitle.trim()}|${publicSettings.promoUrl.trim()}"
        if (publicSettings.notificationEnabled &&
            (publicSettings.notificationTitle.isNotBlank() || publicSettings.notificationMessage.isNotBlank()) &&
            !shownOnceInSession &&
            dismissedNotificationKey != activeNotifKey &&
            !showAdminPanel &&
            (!publicSettings.licenseSystemEnabled || isLicenseValid)
        ) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = {
                    shownOnceInSession = true
                    dismissedNotificationKey = activeNotifKey
                }
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 16.dp)
                        .cyberShimmerBorder(width = 1.5.dp, shape = RoundedCornerShape(22.dp), phaseOffset = 0.25f, accentColor = NeonCyan),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = CyberCard)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (publicSettings.promoTitle.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        com.example.ui.theme.CyberPurple.copy(alpha = 0.25f),
                                        RoundedCornerShape(50)
                                    )
                                    .cyberShimmerBorder(
                                        width = 1.1.dp,
                                        shape = RoundedCornerShape(50),
                                        phaseOffset = 0.45f,
                                        accentColor = NeonCyan
                                    )
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = publicSettings.promoTitle,
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        if (publicSettings.notificationTitle.isNotBlank()) {
                            Text(
                                text = publicSettings.notificationTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (publicSettings.notificationMessage.isNotBlank()) {
                            Text(
                                text = publicSettings.notificationMessage,
                                fontSize = 13.5.sp,
                                color = TextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                        }

                        val hasPromoUrl = publicSettings.promoUrl.isNotBlank()
                        val promoLabel = publicSettings.promoButtonText.ifBlank {
                            tr("View Details", "বিস্তারিত দেখুন", "विवरण देखें")
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    shownOnceInSession = true
                                    dismissedNotificationKey = activeNotifKey
                                },
                                modifier = Modifier
                                    .weight(if (hasPromoUrl) 0.85f else 1f)
                                    .defaultMinSize(minHeight = 46.dp)
                                    .fillMaxHeight(),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCardElevated)
                            ) {
                                Text(
                                    text = tr("Close", "বন্ধ করুন", "बंद करें"),
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }

                            if (hasPromoUrl) {
                                Button(
                                    onClick = {
                                        shownOnceInSession = true
                                        dismissedNotificationKey = activeNotifKey
                                        try {
                                            val intent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(publicSettings.promoUrl)
                                            )
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(
                                                context,
                                                tr(
                                                    "Could not open link: ${publicSettings.promoUrl}",
                                                    "লিংক ওপেন করা যায়নি: ${publicSettings.promoUrl}",
                                                    "लिंक नहीं खोला जा सका: ${publicSettings.promoUrl}"
                                                ),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1.35f)
                                        .defaultMinSize(minHeight = 46.dp)
                                        .fillMaxHeight(),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = com.example.ui.theme.CyberPurple)
                                ) {
                                    Text(
                                        text = promoLabel,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = if (promoLabel.length > 20) 11.sp else 12.sp,
                                        lineHeight = 15.sp,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // License Activation Guard Screen (shown when license is required and invalid/unverified after initial load)
        if (!isAppInitialLoading && publicSettings.licenseSystemEnabled && !isLicenseValid && !showAdminPanel) {
            com.example.ui.components.LicenseActivationDialog(
                packages = publicPackages,
                settings = publicSettings,
                isLoading = isCheckingLicense,
                errorMessage = licenseErrorMessage,
                pendingTransferLicense = pendingTransferLicense,
                onVerifyKey = { viewModel.verifyEnteredLicenseKey(it) },
                onConfirmTransfer = { viewModel.confirmLicenseTransfer(it) },
                onDismissTransfer = { viewModel.dismissLicenseTransfer() },
                onOpenAdminPanel = { viewModel.toggleAdminPanel(true) },
                onOpenSetupVideo = { showSetupVideoDialog = true }
            )
        }

        // Ultra-Premium Cyber Splash Screen (shown while app is verifying/loading initial state on startup)
        if (isAppInitialLoading && !isLicenseValid && !showAdminPanel) {
            com.example.ui.components.CyberSplashScreen(
                appName = publicSettings.appName.ifBlank { "GhostDevice" },
                statusMessage = if (isCheckingLicense) {
                    tr(
                        "Verifying license & system...",
                        "লাইসেন্স ও সিস্টেম যাচাই হচ্ছে...",
                        "लाइसेंस और सिस्टम सत्यापित किया जा रहा है..."
                    )
                } else {
                    tr(
                        "Loading secure system...",
                        "সুরক্ষিত সিস্টেম লোড হচ্ছে...",
                        "सुरक्षित सिस्टम लोड हो रहा है..."
                    )
                }
            )
        }

        // Admin Panel Screen (Full-Screen, isolated from Dashboard)
        if (showAdminPanel) {
            com.example.ui.screens.AdminPanelScreen(
                licenseRepository = viewModel.licenseRepository,
                adminToken = adminToken,
                onLoginSuccess = { token, email -> viewModel.onAdminLoginSuccess(token, email) },
                onLogout = { viewModel.onAdminLogout() },
                onClose = { viewModel.toggleAdminPanel(false) }
            )
        }

        // In-App Setup Video Player Popup Dialog
        if (showSetupVideoDialog && publicSettings.setupVideoUrl.isNotBlank()) {
            com.example.ui.components.SetupVideoDialog(
                videoUrl = publicSettings.setupVideoUrl,
                videoTitle = publicSettings.setupVideoTitle,
                onDismiss = { showSetupVideoDialog = false }
            )
        }
    }
}

@Composable
fun CyberBottomNavigationBar(
    selectedTab: AppNavTab,
    onTabSelected: (AppNavTab) -> Unit
) {
    currentAppLang()
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .cyberShimmerBorder(
                width = 1.25.dp,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                phaseOffset = 0.5f,
                accentColor = NeonCyan
            ),
        containerColor = CyberBgDarker,
        tonalElevation = 8.dp
    ) {
        val navItems = listOf(
            NavTabItem(AppNavTab.DASHBOARD, tr("Dashboard", "ড্যাশবোর্ড", "डैशबोर्ड"), Icons.Default.Dashboard, "dashboard_tab"),
            NavTabItem(AppNavTab.TIMER, tr("Timer", "টাইমার", "टाइमर"), Icons.Default.Schedule, "timer_tab"),
            NavTabItem(AppNavTab.APPS, tr("Apps", "অ্যাপস", "ऐप्स"), Icons.AutoMirrored.Filled.ViewList, "apps_tab"),
            NavTabItem(AppNavTab.PROFILES, tr("Profiles", "প্রোফাইল", "प्रोफ़ाइल"), Icons.Default.Person, "profiles_tab"),
            NavTabItem(AppNavTab.LOGS, tr("Logs", "লগস", "लॉग्स"), Icons.Default.History, "logs_tab")
        )

        navItems.forEach { item ->
            val isSelected = selectedTab == item.tab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(item.tab) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                modifier = Modifier.testTag(item.tag),
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NeonCyan,
                    unselectedIconColor = TextMuted,
                    selectedTextColor = NeonCyan,
                    unselectedTextColor = TextMuted,
                    indicatorColor = CyberCardElevated
                )
            )
        }
    }
}

data class NavTabItem(
    val tab: AppNavTab,
    val label: String,
    val icon: ImageVector,
    val tag: String
)

@Composable
fun NoInternetConnectionGate(
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    currentAppLang()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(width = 1.05.dp, shape = RoundedCornerShape(24.dp), phaseOffset = 0.1f, accentColor = RedDanger),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LanguageSelectorChip()
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(RedDanger.copy(alpha = 0.14f), CircleShape)
                        .cyberShimmerBorder(width = 1.05.dp, shape = CircleShape, phaseOffset = 0.35f, accentColor = RedDanger),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = "No Internet",
                        tint = RedDanger,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = tr(
                        "Internet Connection Required!",
                        "ইন্টারনেট কানেকশন প্রয়োজন!",
                        "इंटरनेट कनेक्शन आवश्यक है!"
                    ),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = tr(
                        "GhostDevice cannot open without an active internet connection. Please turn on Wi-Fi or Mobile Data to use the application.",
                        "নেট কানেকশন ছাড়া GhostDevice অ্যাপ্লিকেশনটি ওপেন হবে না। অ্যাপটি ব্যবহার করতে দয়া করে আপনার ফোনের Wi-Fi অথবা Mobile Data (নেট কানেকশন) চালু করুন।",
                        "इंटरनेट कनेक्शन के बिना GhostDevice ऐप नहीं खुलेगा। ऐप का उपयोग करने के लिए कृपया अपने फ़ोन का Wi-Fi या मोबाइल डेटा चालू करें।"
                    ),
                    fontSize = 13.5.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        try {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                            } else {
                                Intent(Settings.ACTION_WIFI_SETTINGS)
                            }
                            context.startActivity(intent)
                        } catch (_: Throwable) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                            } catch (_: Throwable) {
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_internet_settings_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = CyberBg
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr(
                            "Turn On Internet Connection",
                            "নেট কানেকশন চালু করুন",
                            "इंटरनेट कनेक्शन चालू करें"
                        ),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .cyberShimmerBorder(width = 0.95.dp, shape = RoundedCornerShape(14.dp), phaseOffset = 0.6f, accentColor = NeonCyan)
                        .testTag("retry_internet_connection_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCardElevated,
                        contentColor = NeonCyan
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr(
                            "Retry Connection",
                            "পুনরায় চেষ্টা করুন",
                            "पुनः प्रयास करें"
                        ),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                }
            }
        }
    }
}

