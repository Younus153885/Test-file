package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.ui.components.EditParameterDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.generator.IdentityGenerator
import com.example.data.license.PublicSettings
import com.example.data.model.DeviceIdentity
import com.example.data.model.SystemPermissionsStatus
import com.example.data.model.TimerSettings
import com.example.ui.components.DeviceModelSelectorDialog
import com.example.ui.components.SetupVideoGuideBanner
import com.example.ui.i18n.LanguageManager
import com.example.ui.i18n.LanguageSelectorChip
import com.example.ui.i18n.currentAppLang
import com.example.ui.i18n.tr
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBgDarker
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardElevated
import com.example.ui.theme.CyberMagenta
import com.example.ui.theme.CyberPurple
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RedDanger
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.cyberShimmerBorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

private data class DashboardParamItem(
    val key: String,
    val label: String,
    val category: String,
    val value: String,
    val icon: ImageVector,
    val timerOptionKey: String,
    val isIpField: Boolean = false
)

private fun formatLocalizedDigits(number: Int): String {
    return LanguageManager.formatDigits(number)
}

@Composable
fun DashboardScreen(
    currentIdentity: DeviceIdentity,
    realIdentity: DeviceIdentity,
    isShieldActive: Boolean,
    onToggleShield: () -> Unit,
    selectedAppsCount: Int,
    lastResetSummary: String,
    timerRemainingSeconds: Int,
    isTimerRunning: Boolean,
    onTriggerReset: () -> Unit,
    onOpenSystemRequirements: () -> Unit,
    onOpenTimerTab: () -> Unit,
    onOpenAppsTab: () -> Unit,
    onOpenDaemonTab: () -> Unit,
    onOpenAiTab: () -> Unit,
    timerSettings: TimerSettings = TimerSettings(),
    onToggleResetOption: (String) -> Unit = {},
    permissionsStatus: SystemPermissionsStatus = SystemPermissionsStatus(),
    onSelectDeviceModelPreset: (IdentityGenerator.DevicePreset) -> Unit = {},
    onApplyCustomDeviceModel: (String, String, String, String, String, String, String, String, String, String, String, String, String, String, String) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onRandomizeAdvertisingId: () -> Unit = {},
    onOpenGoogleAdsSettings: () -> Unit = {},
    onRefreshHardwareInfo: () -> Unit = {},
    isGlobalSpoofEnabled: Boolean = false,
    onToggleGlobalSpoof: () -> Unit = {},
    onUpdateSingleParameter: (String, String) -> Unit = { _, _ -> },
    isResetting: Boolean = false,
    onOpenAdminPanel: () -> Unit = {},
    publicSettings: PublicSettings = PublicSettings(),
    onOpenSetupVideo: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()
    currentAppLang()

    var showRealHw by remember { mutableStateOf(false) }
    var isIpMasked by remember { mutableStateOf(false) }
    var showDeviceModelDialog by remember { mutableStateOf(false) }
    var dialogInitialTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var editingParamItem by remember { mutableStateOf<DashboardParamItem?>(null) }
    var showGlobalSpoofNoticeDialog by remember { mutableStateOf(false) }

    // Local overrides for single-parameter instant randomization ("ক্লিক করুন ⟳")
    val localValueOverrides = remember { mutableStateMapOf<String, String>() }

    // Clear local single-field overrides whenever a full identity reset or profile switch happens
    LaunchedEffect(currentIdentity) {
        localValueOverrides.clear()
    }

    // Track individual enable/disable state for all 26 parameters synced with TimerSettings
    val paramEnabledMap = remember {
        mutableStateMapOf(
            "device_model" to timerSettings.resetDeviceModel,
            "manufacturer" to timerSettings.resetManufacturer,
            "android_id" to timerSettings.resetAndroidId,
            "advertising_id" to timerSettings.resetAdvertisingId,
            "imei" to timerSettings.resetImei,
            "serial_number" to timerSettings.resetSerialNumber,
            "mac_address" to timerSettings.resetMacAddress,
            "bluetooth_address" to timerSettings.resetBluetooth,
            "ip_address" to timerSettings.resetNetworkIp,
            "sim_carrier" to timerSettings.resetCarrierName,
            "imsi" to timerSettings.resetSimImsi,
            "iccid" to timerSettings.resetSimIccid,
            "soc_hardware" to timerSettings.resetSocHardware,
            "gpu_renderer" to timerSettings.resetGpuRenderer,
            "display_res" to timerSettings.resetDisplayResolution,
            "device_id" to timerSettings.resetDeviceId,
            "bootloader" to timerSettings.resetBootloaderVersion,
            "os_version" to timerSettings.resetOsVersion,
            "build_number" to timerSettings.resetBuildNumber,
            "build_fingerprint" to timerSettings.resetBuildFingerprint,
            "kernel_version" to timerSettings.resetKernel,
            "baseband" to timerSettings.resetBaseband,
            "widevine_id" to timerSettings.resetWidevineDrmId,
            "location" to timerSettings.resetLocation,
            "timezone" to timerSettings.resetTimezone,
            "user_agent" to timerSettings.resetUserAgent
        )
    }

    LaunchedEffect(timerSettings) {
        paramEnabledMap["device_model"] = timerSettings.resetDeviceModel
        paramEnabledMap["manufacturer"] = timerSettings.resetManufacturer
        paramEnabledMap["android_id"] = timerSettings.resetAndroidId
        paramEnabledMap["advertising_id"] = timerSettings.resetAdvertisingId
        paramEnabledMap["imei"] = timerSettings.resetImei
        paramEnabledMap["serial_number"] = timerSettings.resetSerialNumber
        paramEnabledMap["mac_address"] = timerSettings.resetMacAddress
        paramEnabledMap["bluetooth_address"] = timerSettings.resetBluetooth
        paramEnabledMap["ip_address"] = timerSettings.resetNetworkIp
        paramEnabledMap["sim_carrier"] = timerSettings.resetCarrierName
        paramEnabledMap["imsi"] = timerSettings.resetSimImsi
        paramEnabledMap["iccid"] = timerSettings.resetSimIccid
        paramEnabledMap["soc_hardware"] = timerSettings.resetSocHardware
        paramEnabledMap["gpu_renderer"] = timerSettings.resetGpuRenderer
        paramEnabledMap["display_res"] = timerSettings.resetDisplayResolution
        paramEnabledMap["device_id"] = timerSettings.resetDeviceId
        paramEnabledMap["bootloader"] = timerSettings.resetBootloaderVersion
        paramEnabledMap["os_version"] = timerSettings.resetOsVersion
        paramEnabledMap["build_number"] = timerSettings.resetBuildNumber
        paramEnabledMap["build_fingerprint"] = timerSettings.resetBuildFingerprint
        paramEnabledMap["kernel_version"] = timerSettings.resetKernel
        paramEnabledMap["baseband"] = timerSettings.resetBaseband
        paramEnabledMap["widevine_id"] = timerSettings.resetWidevineDrmId
        paramEnabledMap["location"] = timerSettings.resetLocation
        paramEnabledMap["timezone"] = timerSettings.resetTimezone
        paramEnabledMap["user_agent"] = timerSettings.resetUserAgent
    }

    val baseIdentity = if (showRealHw) realIdentity else currentIdentity

    fun resolveValue(key: String, defaultVal: String): String {
        return if (showRealHw) defaultVal else (localValueOverrides[key] ?: defaultVal)
    }

    fun randomizeSingleParam(key: String, label: String) {
        val hex = "0123456789abcdef"
        val digits = "0123456789"
        val upperAlnum = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        when (key) {
            "device_model", "manufacturer" -> {
                val preset = IdentityGenerator.devicePresets.random()
                onSelectDeviceModelPreset(preset)
                localValueOverrides.remove("device_model")
                localValueOverrides.remove("manufacturer")
            }
            "android_id" -> {
                localValueOverrides[key] = (1..16).map { hex.random() }.joinToString("")
            }
            "advertising_id" -> {
                localValueOverrides[key] = UUID.randomUUID().toString()
                onRandomizeAdvertisingId()
            }
            "imei" -> {
                val tac = listOf("35498018", "35841209", "35290611", "35672810", "35914508").random()
                localValueOverrides[key] = tac + (1..7).map { digits.random() }.joinToString("")
            }
            "serial_number" -> {
                localValueOverrides[key] = "R3CW" + (1..7).map { upperAlnum.random() }.joinToString("")
            }
            "mac_address" -> {
                localValueOverrides[key] = (1..6).joinToString(":") {
                    "%02x".format(Random.nextInt(0, 256))
                }
            }
            "bluetooth_address" -> {
                localValueOverrides[key] = (1..6).joinToString(":") {
                    "%02x".format(Random.nextInt(0, 256))
                }
            }
            "ip_address" -> {
                localValueOverrides[key] = "${Random.nextInt(11, 223)}.${Random.nextInt(1, 254)}.${Random.nextInt(1, 254)}.${Random.nextInt(2, 253)}"
            }
            "sim_carrier" -> {
                val carriers = listOf(
                    "KPN • +31 6 ${Random.nextInt(1000, 9999)} ${Random.nextInt(1000, 9999)} • -${Random.nextInt(65, 92)} dBm ${Random.nextInt(45, 75)} asu (5G UW (C-Band))",
                    "T-Mobile • +1 ${Random.nextInt(201, 989)} ${Random.nextInt(100, 999)} ${Random.nextInt(1000, 9999)} • -${Random.nextInt(62, 88)} dBm 68 asu (5G NR)",
                    "Vodafone DE • +49 152 ${Random.nextInt(1000000, 9999999)} • -${Random.nextInt(64, 90)} dBm 60 asu (5G SA)",
                    "NTT Docomo • +81 90 ${Random.nextInt(1000, 9999)} ${Random.nextInt(1000, 9999)} • -${Random.nextInt(60, 85)} dBm 72 asu (5G Sub6)"
                )
                localValueOverrides[key] = carriers.random()
            }
            "imsi" -> {
                localValueOverrides[key] = (1..15).map { digits.random() }.joinToString("")
            }
            "iccid" -> {
                localValueOverrides[key] = "89" + (1..16).map { digits.random() }.joinToString("") + "F"
            }
            "soc_hardware" -> {
                val socs = listOf(
                    "Snapdragon 8 Gen 3",
                    "Snapdragon 8 Gen 2",
                    "Google Tensor G3",
                    "MediaTek Dimensity 9300",
                    "Exynos 2400"
                )
                localValueOverrides[key] = socs.random()
            }
            "gpu_renderer" -> {
                val gpus = listOf(
                    "Adreno 750",
                    "Adreno 740",
                    "Adreno 730",
                    "Mali-G715 Immortalis MC11",
                    "Samsung Xclipse 940"
                )
                localValueOverrides[key] = gpus.random()
            }
            "display_res" -> {
                val displays = listOf(
                    "1812x2176 @ 373dpi",
                    "1440x3120 @ 505dpi",
                    "1080x2400 @ 420dpi",
                    "1344x2992 @ 489dpi",
                    "1260x2800 @ 453dpi"
                )
                localValueOverrides[key] = displays.random()
            }
            "device_id" -> {
                val codenames = listOf("q5q", "husky", "dm3q", "shiba", "kalama", "lynx", "caiman")
                localValueOverrides[key] = codenames.random()
            }
            "bootloader" -> {
                val bootloaders = listOf(
                    "F946BXXS255CXF1",
                    "S928BXXU1AXCB",
                    "ripcurrent-14.0-11492831",
                    "b1-0.5-${Random.nextInt(10000000, 99999999)}"
                )
                localValueOverrides[key] = bootloaders.random()
            }
            "os_version" -> {
                val versions = listOf(
                    "Android 14 (SDK 34) · Patch: 2024-07-01",
                    "Android 14 (SDK 34) · Patch: 2024-09-05",
                    "Android 15 (SDK 35) · Patch: 2024-10-05",
                    "Android 13 (SDK 33) · Patch: 2024-05-01"
                )
                localValueOverrides[key] = versions.random()
            }
            "build_number" -> {
                val builds = listOf(
                    "UP1A.231005.007.F946BXXS255CXF1",
                    "AP2A.240805.005",
                    "UQ1A.240205.004",
                    "TP1A.220624.014.S918BXXS3AWF7"
                )
                localValueOverrides[key] = builds.random()
            }
            "build_fingerprint" -> {
                val prints = listOf(
                    "samsung/q5qxxx/q5q:14/UP1A.231005.007/F946BXXS255CXF1:user/release-keys",
                    "google/husky/husky:14/AP2A.240805.005/12025142:user/release-keys",
                    "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXU1AXCB:user/release-keys",
                    "OnePlus/CPH2581/OP594DL1:14/UKQ1.230924.001/U.1592831:user/release-keys"
                )
                localValueOverrides[key] = prints.random()
            }
            "kernel_version" -> {
                val hash = (1..7).map { hex.random() }.joinToString("")
                localValueOverrides[key] = "5.15.${Random.nextInt(110, 255)}-android14-9-$hash"
            }
            "baseband" -> {
                val basebands = listOf(
                    "F946BXXS255CXF1",
                    "S928BXXU1AXCB",
                    "g5300g-240301-240502-B-${Random.nextInt(10000000, 99999999)}"
                )
                localValueOverrides[key] = basebands.random()
            }
            "widevine_id" -> {
                localValueOverrides[key] = (1..16).map { hex.random() }.joinToString("")
            }
            "location" -> {
                val locs = listOf(
                    "Amsterdam, Netherlands (52.3565, 4.8999)",
                    "New York, United States (40.7128, -74.0060)",
                    "Frankfurt, Germany (50.1109, 8.6821)",
                    "Tokyo, Japan (35.6762, 139.6503)",
                    "Singapore (1.3521, 103.8198)",
                    "London, United Kingdom (51.5072, -0.1276)"
                )
                localValueOverrides[key] = locs.random()
            }
            "timezone" -> {
                val tzs = listOf(
                    "Europe/Amsterdam",
                    "America/New_York",
                    "Europe/Berlin",
                    "Asia/Tokyo",
                    "Asia/Singapore",
                    "Europe/London"
                )
                localValueOverrides[key] = tzs.random()
            }
            "user_agent" -> {
                val chromeMinor = Random.nextInt(6400, 6750)
                val chromePatch = Random.nextInt(40, 190)
                localValueOverrides[key] = "Mozilla/5.0 (Linux; Android 14; ${baseIdentity.deviceModel} Build/${baseIdentity.buildNumber}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.$chromeMinor.$chromePatch Mobile Safari/537.36"
            }
        }
        localValueOverrides[key]?.let { newVal ->
            onUpdateSingleParameter(key, newVal)
        }
        Toast.makeText(
            context,
            tr(
                "$label updated with new randomized value",
                "$label নতুন তথ্য দিয়ে আপডেট হয়েছে",
                "$label नई जानकारी के साथ अपडेट किया गया"
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    val allParams = listOf(
        // Identifiers (6)
        DashboardParamItem(
            key = "device_model",
            label = "Device Model",
            category = "Identifiers",
            value = resolveValue("device_model", baseIdentity.deviceModel),
            icon = Icons.Default.PhoneAndroid,
            timerOptionKey = "Device Model"
        ),
        DashboardParamItem(
            key = "manufacturer",
            label = "Manufacturer",
            category = "Identifiers",
            value = resolveValue("manufacturer", baseIdentity.manufacturer),
            icon = Icons.Default.Android,
            timerOptionKey = "Manufacturer"
        ),
        DashboardParamItem(
            key = "android_id",
            label = "Android ID",
            category = "Identifiers",
            value = resolveValue("android_id", baseIdentity.androidId),
            icon = Icons.Default.Fingerprint,
            timerOptionKey = "Android ID"
        ),
        DashboardParamItem(
            key = "advertising_id",
            label = "Advertising ID",
            category = "Identifiers",
            value = resolveValue("advertising_id", baseIdentity.advertisingId),
            icon = Icons.Default.Language,
            timerOptionKey = "Advertising ID"
        ),
        DashboardParamItem(
            key = "imei",
            label = "IMEI",
            category = "Identifiers",
            value = resolveValue("imei", baseIdentity.imei),
            icon = Icons.Default.SimCard,
            timerOptionKey = "IMEI"
        ),
        DashboardParamItem(
            key = "serial_number",
            label = "Serial Number",
            category = "Identifiers",
            value = resolveValue("serial_number", baseIdentity.serialNumber),
            icon = Icons.Default.Tag,
            timerOptionKey = "Serial Number"
        ),

        // Network (6)
        DashboardParamItem(
            key = "mac_address",
            label = "MAC Address",
            category = "Network",
            value = resolveValue("mac_address", baseIdentity.macAddress),
            icon = Icons.Default.Wifi,
            timerOptionKey = "Wi-Fi MAC Address"
        ),
        DashboardParamItem(
            key = "bluetooth_address",
            label = "Bluetooth Address",
            category = "Network",
            value = resolveValue("bluetooth_address", baseIdentity.bluetoothAddress),
            icon = Icons.Default.Bluetooth,
            timerOptionKey = "Bluetooth Address"
        ),
        DashboardParamItem(
            key = "ip_address",
            label = "IP Address",
            category = "Network",
            value = if (isIpMasked) "•••.•••.•••.•••" else resolveValue("ip_address", baseIdentity.ipAddress),
            icon = Icons.Default.Public,
            timerOptionKey = "IP Address",
            isIpField = true
        ),
        DashboardParamItem(
            key = "sim_carrier",
            label = "SIM Status & Carrier",
            category = "Network",
            value = com.example.data.generator.IdentityGenerator.ensureCarrierWithPhone(
                resolveValue("sim_carrier", baseIdentity.carrier),
                baseIdentity.timezone,
                baseIdentity.locationName,
                baseIdentity.androidId + baseIdentity.imei + baseIdentity.serialNumber
            ),
            icon = Icons.Default.SignalCellularAlt,
            timerOptionKey = "Carrier"
        ),
        DashboardParamItem(
            key = "imsi",
            label = "IMSI",
            category = "Network",
            value = resolveValue("imsi", baseIdentity.simImsi),
            icon = Icons.Default.SimCard,
            timerOptionKey = "SIM IMSI"
        ),
        DashboardParamItem(
            key = "iccid",
            label = "ICCID",
            category = "Network",
            value = resolveValue("iccid", baseIdentity.simIccid),
            icon = Icons.Default.Memory,
            timerOptionKey = "SIM Serial / ICCID"
        ),

        // Hardware (5)
        DashboardParamItem(
            key = "soc_hardware",
            label = "SoC Hardware",
            category = "Hardware",
            value = resolveValue("soc_hardware", baseIdentity.socHardware),
            icon = Icons.Default.DeveloperBoard,
            timerOptionKey = "SoC Hardware"
        ),
        DashboardParamItem(
            key = "gpu_renderer",
            label = "GPU Renderer",
            category = "Hardware",
            value = resolveValue("gpu_renderer", baseIdentity.gpuRenderer),
            icon = Icons.Default.Tv,
            timerOptionKey = "GPU Renderer"
        ),
        DashboardParamItem(
            key = "display_res",
            label = "Display Resolution",
            category = "Hardware",
            value = resolveValue("display_res", baseIdentity.display),
            icon = Icons.Default.Tv,
            timerOptionKey = "Display Resolution"
        ),
        DashboardParamItem(
            key = "device_id",
            label = "Device ID/Codename",
            category = "Hardware",
            value = resolveValue("device_id", baseIdentity.deviceId),
            icon = Icons.Default.Code,
            timerOptionKey = "Device ID"
        ),
        DashboardParamItem(
            key = "bootloader",
            label = "Bootloader",
            category = "Hardware",
            value = resolveValue("bootloader", baseIdentity.bootloaderVersion),
            icon = Icons.Default.Shield,
            timerOptionKey = "Bootloader Version"
        ),

        // System & Kernel (5)
        DashboardParamItem(
            key = "os_version",
            label = "OS Version",
            category = "System & Kernel",
            value = resolveValue("os_version", baseIdentity.osVersion),
            icon = Icons.Default.Android,
            timerOptionKey = "OS Version"
        ),
        DashboardParamItem(
            key = "build_number",
            label = "Build Number",
            category = "System & Kernel",
            value = resolveValue("build_number", baseIdentity.buildNumber),
            icon = Icons.Default.Code,
            timerOptionKey = "Build Number"
        ),
        DashboardParamItem(
            key = "build_fingerprint",
            label = "Build Fingerprint",
            category = "System & Kernel",
            value = resolveValue("build_fingerprint", baseIdentity.buildFingerprint),
            icon = Icons.Default.Terminal,
            timerOptionKey = "Build Fingerprint"
        ),
        DashboardParamItem(
            key = "kernel_version",
            label = "Kernel Version",
            category = "System & Kernel",
            value = resolveValue("kernel_version", baseIdentity.kernelVersion),
            icon = Icons.Default.Terminal,
            timerOptionKey = "Kernel Version"
        ),
        DashboardParamItem(
            key = "baseband",
            label = "Baseband",
            category = "System & Kernel",
            value = resolveValue("baseband", baseIdentity.basebandVersion),
            icon = Icons.Default.SignalCellularAlt,
            timerOptionKey = "Baseband Version"
        ),

        // DRM & Environment (4)
        DashboardParamItem(
            key = "widevine_id",
            label = "Widevine ID",
            category = "DRM & Environment",
            value = resolveValue("widevine_id", baseIdentity.widevineDrmId),
            icon = Icons.Default.Key,
            timerOptionKey = "Widevine DRM ID"
        ),
        DashboardParamItem(
            key = "location",
            label = "Location",
            category = "DRM & Environment",
            value = resolveValue(
                "location",
                "${baseIdentity.locationName} (${baseIdentity.latitude}, ${baseIdentity.longitude})"
            ),
            icon = Icons.Default.LocationOn,
            timerOptionKey = "Region"
        ),
        DashboardParamItem(
            key = "timezone",
            label = "Timezone",
            category = "DRM & Environment",
            value = resolveValue("timezone", baseIdentity.timezone),
            icon = Icons.Default.Schedule,
            timerOptionKey = "Timezone"
        ),
        DashboardParamItem(
            key = "user_agent",
            label = "User Agent",
            category = "DRM & Environment",
            value = resolveValue("user_agent", baseIdentity.userAgent),
            icon = Icons.Default.Language,
            timerOptionKey = "User Agent"
        )
    )

    val activeRandomizeCount = allParams.count { paramEnabledMap[it.key] != false }

    val filteredParams = allParams.filter { item ->
        val matchesCategory = selectedCategory == "All" || item.category == selectedCategory
        val matchesSearch = searchQuery.isBlank() ||
                item.label.contains(searchQuery, ignoreCase = true) ||
                item.category.contains(searchQuery, ignoreCase = true) ||
                item.value.contains(searchQuery, ignoreCase = true)
        matchesCategory && matchesSearch
    }

    val completedReqsCount = listOf(
        permissionsStatus.isRootDetected,
        permissionsStatus.isLsposedActive,
        permissionsStatus.hasOverlayPermission,
        permissionsStatus.isBatteryOptimizationIgnored,
        permissionsStatus.hasNotificationPermission
    ).count { it }

    val timeNow = remember {
        SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(horizontal = 14.dp)
            .verticalScroll(scrollState)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 1. Top Header Bar matching video frame_001 (Red 'i' dot removed & layout fitted so right Active/Locked button is never cut off)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            NeonCyan.copy(alpha = 0.14f),
                            RoundedCornerShape(8.dp)
                        )
                        .cyberShimmerBorder(1.8.dp, RoundedCornerShape(8.dp), phaseOffset = 0.05f, accentColor = NeonCyan)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.img_ghostdevice_logo_1793522543890),
                        contentDescription = "GhostDevice Logo",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .cyberShimmerBorder(
                            width = 1.2.dp,
                            shape = RoundedCornerShape(10.dp),
                            phaseOffset = 0.14f,
                            accentColor = NeonCyan
                        )
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF0E1D2D),
                                    Color(0xFF151930)
                                )
                            )
                        )
                        .clickable {
                            Toast.makeText(
                                context,
                                "GhostDevice v3.0 • Active Protection",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .testTag("header_ghostdevice_badge_button")
                ) {
                    Text(
                        text = "GhostDevice",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = NeonCyan,
                        fontSize = 12.5.sp,
                        letterSpacing = 0.2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Language Selector Chip (EN / বাং / हिं)
                LanguageSelectorChip()

                // Compact Comet Shimmer ON/OFF Button (GhostDevice app only — helps low-end devices run smoothly)
                val cometEnabled = com.example.ui.theme.CyberShimmerPrefs.enabled
                Box(
                    modifier = Modifier
                        .size(27.dp)
                        .background(
                            if (cometEnabled) CyberMagenta.copy(alpha = 0.20f) else Color(0xFF171C29),
                            RoundedCornerShape(8.dp)
                        )
                        .cyberShimmerBorder(
                            width = 1.0.dp,
                            shape = RoundedCornerShape(8.dp),
                            phaseOffset = 0.14f,
                            accentColor = if (cometEnabled) CyberMagenta else null
                        )
                        .clickable {
                            val nowOn = com.example.ui.theme.CyberShimmerPrefs.toggle(context)
                            Toast.makeText(
                                context,
                                if (nowOn) {
                                    tr(
                                        "✨ Comet Effect: ON",
                                        "✨ ধূমকেতু ইফেক্ট: চালু (ON)",
                                        "✨ धूमकेतु इफेक्ट: चालू (ON)"
                                    )
                                } else {
                                    tr(
                                        "⚡ Comet Effect: OFF (Smooth Mode)",
                                        "⚡ ধূমকেতু ইফেক্ট: বন্ধ (স্মুথ মোড)",
                                        "⚡ धूमकेतु इफेक्ट: बंद (स्मूथ मोड)"
                                    )
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .testTag("header_comet_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = if (cometEnabled) "Comet Effect ON" else "Comet Effect OFF",
                        tint = if (cometEnabled) NeonCyan else TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Full Phone Visibility Toggle Button (marked location in top header)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isGlobalSpoofEnabled) NeonCyan.copy(alpha = 0.16f) else Color(0xFF171C29),
                    modifier = Modifier
                        .cyberShimmerBorder(
                            width = 1.0.dp,
                            shape = RoundedCornerShape(20.dp),
                            phaseOffset = 0.22f,
                            accentColor = if (isGlobalSpoofEnabled) NeonCyan else null
                        )
                        .clickable {
                            if (!isGlobalSpoofEnabled) {
                                showGlobalSpoofNoticeDialog = true
                            } else {
                                onToggleGlobalSpoof()
                                Toast.makeText(
                                    context,
                                    tr(
                                        "Full Phone Spoof disabled (active on selected Target Apps only)",
                                        "পুরো ফোনে পরিবর্তন বন্ধ করা হয়েছে (শুধুমাত্র টার্গেট অ্যাপে কার্যকর)",
                                        "पूरे फ़ोन में बदलाव बंद किया गया (केवल टारगेट ऐप्स पर लागू)"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .testTag("header_global_spoof_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = "Full Phone Spoof",
                            tint = if (isGlobalSpoofEnabled) NeonCyan else TextSecondary,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (isGlobalSpoofEnabled) {
                                tr("Full Phone: ON", "পুরো ফোন: ON", "पूरा फ़ोन: ON")
                            } else {
                                tr("Full Phone: OFF", "পুরো ফোন: OFF", "पूरा फ़ोन: OFF")
                            },
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isGlobalSpoofEnabled) NeonCyan else TextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Admin Panel Compact Button (no 48dp Material IconButton overflow)
                Box(
                    modifier = Modifier
                        .size(27.dp)
                        .background(CyberPurple.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                        .cyberShimmerBorder(1.0.dp, RoundedCornerShape(8.dp), phaseOffset = 0.38f, accentColor = CyberPurple)
                        .clickable { onOpenAdminPanel() }
                        .testTag("header_admin_panel_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AdminPanelSettings,
                        contentDescription = "Admin Panel",
                        tint = CyberPurple,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Right Locked / Active Status Pill
                // When 5/5 requirements are complete, protection is permanently Active and never locks on click
                val isLocked = completedReqsCount < 5
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isLocked) Color(0xFF231117) else EmeraldGreen.copy(alpha = 0.14f),
                    modifier = Modifier
                        .wrapContentWidth()
                        .cyberShimmerBorder(
                            width = 1.0.dp,
                            shape = RoundedCornerShape(20.dp),
                            phaseOffset = 0.52f,
                            accentColor = if (isLocked) Color(0xFFFF5252) else EmeraldGreen
                        )
                        .clickable {
                            if (completedReqsCount >= 5) {
                                Toast.makeText(
                                    context,
                                    tr(
                                        "System requirements complete (5/5) • Protection is active & locked-in",
                                        "সিস্টেম রিকোয়ারমেন্ট সম্পূর্ণ (৫/৫) • সুরক্ষা সর্বদা সক্রিয় ও লকড-ইন",
                                        "सिस्टम आवश्यकताएं पूर्ण (5/5) • सुरक्षा हमेशा सक्रिय और लॉक-इन है"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                onToggleShield()
                            }
                        }
                        .testTag("shield_active_switch")
                ) {
                    Row(
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (isLocked) Color(0xFFFF5252) else EmeraldGreen,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (isLocked) {
                                tr("Locked ($completedReqsCount/5)", "লকড ($completedReqsCount/5)", "लॉक्ड ($completedReqsCount/5)")
                            } else {
                                tr("Active ($completedReqsCount/5)", "সক্রিয় ($completedReqsCount/5)", "सक्रिय ($completedReqsCount/5)")
                            },
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isLocked) Color(0xFFFF5252) else EmeraldGreen,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        if (showGlobalSpoofNoticeDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showGlobalSpoofNoticeDialog = false },
                containerColor = CyberCard,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(NeonCyan.copy(alpha = 0.14f), CircleShape)
                            .border(1.dp, NeonCyan.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = tr(
                            "Enable Full Phone Spoofing?",
                            "পুরো ফোনে পরিবর্তন চালু করবেন?",
                            "पूरे फ़ोन में बदलाव चालू करें?"
                        ),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )
                },
                text = {
                    Column {
                        Text(
                            text = tr(
                                "Enabling this option will apply all Dashboard identity changes across the entire phone so they are visible everywhere.",
                                "এই অপশনটি চালু করলে আপনার ড্যাশবোর্ডের সকল পরিবর্তন পুরো ফোনে কার্যকর হবে এবং ফোনের সব জায়গায় পরিবর্তনগুলো দেখা যাবে।",
                                "इस विकल्प को चालू करने से आपके डैशबोर्ड के सभी बदलाव पूरे फ़ोन पर लागू होंगे और हर जगह दिखाई देंगे।"
                            ),
                            fontSize = 13.5.sp,
                            color = TextPrimary,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = tr(
                                "• You can view the new Device Model, Android ID, IMEI, and other changes directly in phone Settings (About Phone) and any app.\n• Turning it OFF again keeps changes scoped only to your selected Target Apps.",
                                "• ফোনের Settings (About Phone) সহ যেকোনো জায়গা ও যেকোনো অ্যাপ থেকে নতুন Device Model, Android ID, IMEI ও অন্যান্য পরিবর্তন সরাসরি দেখতে পাবেন।\n• এটি আবার বন্ধ করলে শুধুমাত্র আপনার সিলেক্ট করা টার্গেট অ্যাপেই পরিবর্তনগুলো কার্যকর থাকবে।",
                                "• फ़ोन की Settings (About Phone) सहित किसी भी ऐप में नया Device Model, Android ID, IMEI और अन्य बदलाव सीधे दिखेंगे।\n• इसे फिर से बंद करने पर बदलाव केवल आपके चुने गए टारगेट ऐप्स पर ही लागू रहेंगे।"
                            ),
                            fontSize = 12.5.sp,
                            color = TextSecondary,
                            lineHeight = 18.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showGlobalSpoofNoticeDialog = false
                            if (!isGlobalSpoofEnabled) {
                                onToggleGlobalSpoof()
                            }
                            Toast.makeText(
                                context,
                                tr(
                                    "Full Phone Spoofing enabled! Changes are now visible across the entire phone.",
                                    "পুরো ফোনে পরিবর্তন চালু করা হয়েছে! এখন ফোনের সব জায়গায় পরিবর্তন দেখা যাবে।",
                                    "पूरे फ़ोन में बदलाव चालू किया गया! अब पूरे फ़ोन में बदलाव दिखाई देंगे।"
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = CyberBg
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("confirm_global_spoof_button")
                    ) {
                        Text(
                            tr("Yes, Enable", "হ্যাঁ, চালু করুন", "हाँ, चालू करें"),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showGlobalSpoofNoticeDialog = false },
                        modifier = Modifier.testTag("cancel_global_spoof_button")
                    ) {
                        Text(
                            tr("Cancel", "বাতিল", "रद्द करें"),
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            )
        }

        // 2. System Requirements Banner Card (matching video frame_001)
        if (completedReqsCount < 5) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(14.dp), phaseOffset = 0.12f, accentColor = Color(0xFFFF5252))
                    .clickable { onOpenSystemRequirements() }
                    .testTag("root_required_banner"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1116))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFFFF5252).copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "System Requirements",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr(
                                "System Requirements ($completedReqsCount/5) • Locked",
                                "সিস্টেম রিকোয়ারমেন্টস ($completedReqsCount/5) • লকড",
                                "सिस्टम आवश्यकताएं ($completedReqsCount/5) • लॉक्ड"
                            ),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            color = Color(0xFFFF5252),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = tr(
                                "Root, LSPosed & permissions required for real spoofing",
                                "আসল স্পুফিংয়ের জন্য Root, LSPosed ও পারমিশন প্রয়োজন",
                                "असली स्पूफिंग के लिए Root, LSPosed और अनुमतियां आवश्यक हैं"
                            ),
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onOpenSystemRequirements,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF5252),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            tr("Checklist", "চেকলিস্ট", "चेकलिस्ट"),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Next Reset Card when timer is actively running
        if (isTimerRunning) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(14.dp), phaseOffset = 0.25f)
                    .clickable { onOpenTimerTab() }
                    .testTag("next_reset_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = timeNow,
                            fontSize = 11.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = tr("Next Reset", "পরবর্তী রিসেট", "अगला रीसेट"),
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            val mins = timerRemainingSeconds / 60
                            val secs = timerRemainingSeconds % 60
                            val timerText = tr(
                                "%d:%02d remaining".format(mins, secs),
                                "%d:%02d বাকি".format(mins, secs),
                                "%d:%02d शेष".format(mins, secs)
                            )
                            Text(
                                text = timerText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(EmeraldGreen, CircleShape)
                    )
                }
            }
        }

        // Prominent Setup Video Guide Banner (Admin-controlled ON/OFF)
        if (publicSettings.setupVideoEnabled && publicSettings.setupVideoUrl.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            SetupVideoGuideBanner(
                title = publicSettings.setupVideoTitle,
                onClick = onOpenSetupVideo
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. Active Device Identity Title Row + Spoofed/Real HW Toggle + Copy All
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = tr("Active Device Identity", "সক্রিয় ডিভাইস আইডেন্টিটি", "सक्रिय डिवाइस पहचान"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tr(
                        "$activeRandomizeCount/26 Active to Randomize",
                        "$activeRandomizeCount/26 রেন্ডম পরিবর্তনের জন্য সক্রিয়",
                        "$activeRandomizeCount/26 रैंडम बदलाव के लिए सक्रिय"
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeonCyan
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Spoofed / Real HW Toggle Button (matching frame_001 and frame_055)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (showRealHw) CyberPurple.copy(alpha = 0.22f) else NeonCyan.copy(alpha = 0.15f),
                    modifier = Modifier
                        .cyberShimmerBorder(
                            width = 1.2.dp,
                            shape = RoundedCornerShape(10.dp),
                            phaseOffset = 0.33f,
                            accentColor = if (showRealHw) CyberPurple else NeonCyan
                        )
                        .clickable {
                            showRealHw = !showRealHw
                            try {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            } catch (e: Exception) {}
                            if (showRealHw) {
                                onRefreshHardwareInfo()
                                Toast.makeText(
                                    context,
                                    tr(
                                        "📱 Real Device Hardware view active",
                                        "📱 আসল ডিভাইস হার্ডওয়্যার তথ্য ভিউ সক্রিয়",
                                        "📱 असली डिवाइस हार्डवेयर दृश्य सक्रिय"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    tr(
                                        "🎭 Spoofed Identity view active",
                                        "🎭 স্পুফড আইডেন্টিটি ভিউ সক্রিয়",
                                        "🎭 स्पूफेड पहचान दृश्य सक्रिय"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .testTag("spoofed_toggle_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showRealHw) {
                                tr("📱 Real HW", "📱 আসল HW", "📱 असली HW")
                            } else {
                                tr("🎭 Spoofed", "🎭 স্পুফড", "🎭 स्पूफेड")
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (showRealHw) Color(0xFFD8B4FE) else NeonCyan
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Copy All Button
                Row(
                    modifier = Modifier
                        .background(NeonCyan.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                        .cyberShimmerBorder(1.2.dp, RoundedCornerShape(10.dp), phaseOffset = 0.48f)
                        .clickable {
                            val textToCopy = buildString {
                                appendLine("--- ACTIVE DEVICE IDENTITY ---")
                                allParams.forEach { param ->
                                    appendLine("${param.label}: ${param.value}")
                                }
                                append("-----------------------------")
                            }
                            clipboardManager.setText(AnnotatedString(textToCopy))
                            Toast.makeText(
                                context,
                                tr(
                                    "All identity details copied to clipboard!",
                                    "সব তথ্য একসাথে কপি করা হয়েছে!",
                                    "सभी जानकारी एक साथ कॉपी हो गई!"
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy All Identity Info",
                        tint = NeonCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = tr("Copy All", "সব কপি", "सभी कॉपी"),
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Zero-Repetition Guarantee & All ON / All OFF Card (matching frame_001 & frame_040)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(14.dp), phaseOffset = 0.18f, accentColor = EmeraldGreen)
                .testTag("dashboard_reset_options_card"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(EmeraldGreen.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = EmeraldGreen,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tr(
                                "Zero-Repetition Guarantee: 100% unique identity every time",
                                "জিরো-রিপিটেশন গ্যারান্টি: প্রতিবার ১০০% নতুন তথ্য",
                                "ज़ीरो-रिपीटेशन गारंटी: हर बार 100% नई जानकारी"
                            ),
                            fontSize = 10.5.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldGreen,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    val isAllOff = activeRandomizeCount == 0
                    Box(
                        modifier = Modifier
                            .background(
                                if (isAllOff) Color(0xFFFF5252).copy(alpha = 0.14f) else EmeraldGreen.copy(alpha = 0.14f),
                                RoundedCornerShape(6.dp)
                            )
                            .cyberShimmerBorder(
                                1.1.dp,
                                RoundedCornerShape(6.dp),
                                phaseOffset = 0.35f,
                                accentColor = if (isAllOff) Color(0xFFFF5252) else EmeraldGreen
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isAllOff) {
                                tr("All Fixed (OFF)", "সব স্থির (OFF)", "सभी स्थिर (OFF)")
                            } else {
                                tr(
                                    "$activeRandomizeCount/26 Random",
                                    "${formatLocalizedDigits(activeRandomizeCount)}/২৬ রেন্ডম",
                                    "${formatLocalizedDigits(activeRandomizeCount)}/२६ रैंडम"
                                )
                            },
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAllOff) Color(0xFFFF5252) else EmeraldGreen,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val isAllOn = activeRandomizeCount == 26
                val isAllOff = activeRandomizeCount == 0

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // "All ON" Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .background(
                                if (isAllOn) NeonCyan else CyberBg,
                                RoundedCornerShape(9.dp)
                            )
                            .cyberShimmerBorder(
                                1.2.dp,
                                RoundedCornerShape(9.dp),
                                phaseOffset = 0.25f,
                                accentColor = NeonCyan
                            )
                            .clickable {
                                allParams.forEach { param ->
                                    paramEnabledMap[param.key] = true
                                }
                                onToggleResetOption("ALL_ON")
                                Toast.makeText(
                                    context,
                                    tr(
                                        "All 26 options enabled (randomization active)",
                                        "সবগুলো ২৬টি অপশন চালু করা হয়েছে (রেন্ডম পরিবর্তন সক্রিয়)",
                                        "सभी 26 विकल्प चालू किए गए (रैंडम बदलाव सक्रिय)"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isAllOn) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = CyberBg,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = tr("All ON", "সব চালু (All ON)", "सभी चालू (All ON)"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAllOn) CyberBg else NeonCyan,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // "All OFF" Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .background(
                                if (isAllOff) Color(0xFFFF5252).copy(alpha = 0.20f) else CyberCardElevated,
                                RoundedCornerShape(9.dp)
                            )
                            .cyberShimmerBorder(
                                1.2.dp,
                                RoundedCornerShape(9.dp),
                                phaseOffset = 0.55f,
                                accentColor = if (isAllOff) Color(0xFFFF5252) else null
                            )
                            .clickable {
                                allParams.forEach { param ->
                                    paramEnabledMap[param.key] = false
                                }
                                onToggleResetOption("ALL_OFF")
                                Toast.makeText(
                                    context,
                                    tr(
                                        "All options disabled (values will remain fixed)",
                                        "সবগুলো অপশন বন্ধ করা হয়েছে (সব তথ্য অপরিবর্তিত থাকবে)",
                                        "सभी विकल्प बंद किए गए (सभी मान अपरिवर्तित रहेंगे)"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isAllOff) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = tr("All OFF", "সব বন্ধ (All OFF)", "सभी बंद (All OFF)"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAllOff) Color(0xFFFF5252) else TextSecondary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5. Search 26 parameters Bar (matching video frame_001)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(CyberCard, RoundedCornerShape(10.dp))
                .cyberShimmerBorder(1.2.dp, RoundedCornerShape(10.dp), phaseOffset = 0.42f)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search parameters",
                    tint = TextMuted,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = tr(
                                "Search 26 parameters (IMEI, MAC, OS, DRM...)",
                                "২৬টি প্যারামিটার খুঁজুন (IMEI, MAC, OS, DRM...)",
                                "26 पैरामीटर खोजें (IMEI, MAC, OS, DRM...)"
                            ),
                            fontSize = 12.5.sp,
                            color = TextMuted,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 12.5.sp
                        ),
                        cursorBrush = SolidColor(NeonCyan),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = TextMuted,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { searchQuery = "" }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 6. Category Filter Chips Row (matching video frame_001 & frame_040)
        val categories = listOf(
            "All" to tr("All (26)", "সব (২৬)", "सभी (26)"),
            "Identifiers" to tr("Identifiers (6)", "আইডেন্টিফায়ার (৬)", "पहचानकर्ता (6)"),
            "Network" to tr("Network (6)", "নেটওয়ার্ক (৬)", "नेटवर्क (6)"),
            "Hardware" to tr("Hardware (5)", "হার্ডওয়্যার (৫)", "हार्डवेयर (5)"),
            "System & Kernel" to tr("System & Kernel (5)", "সিস্টেম ও কার্নেল (৫)", "सिस्टम व कर्नेल (5)"),
            "DRM & Environment" to tr("DRM & Environment (4)", "ডিআরএম ও এনভায়রনমেন্ট (৪)", "डीआरएम व वातावरण (4)")
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { (catKey, catLabel) ->
                val isSelected = selectedCategory == catKey
                Box(
                    modifier = Modifier
                        .background(
                            if (isSelected) NeonCyan.copy(alpha = 0.16f) else CyberCard,
                            RoundedCornerShape(8.dp)
                        )
                        .cyberShimmerBorder(
                            width = if (isSelected) 1.4.dp else 1.dp,
                            shape = RoundedCornerShape(8.dp),
                            phaseOffset = ((catKey.hashCode() and 0xFF) / 255f),
                            accentColor = if (isSelected) NeonCyan else null
                        )
                        .clickable { selectedCategory = catKey }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = catLabel,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) NeonCyan else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 7. 26 Parameter Cards List
        filteredParams.forEach { item ->
            val isEnabled = paramEnabledMap[item.key] != false
            DashboardParameterCard(
                label = item.label,
                category = item.category,
                value = item.value,
                icon = item.icon,
                enabled = isEnabled,
                isIpField = item.isIpField,
                isIpMasked = isIpMasked,
                onToggleIpMask = { isIpMasked = !isIpMasked },
                onRandomizeClick = {
                    randomizeSingleParam(item.key, item.label)
                },
                onEditClick = {
                    editingParamItem = item
                },
                onCopyClick = {
                    val rawCopyVal = if (item.isIpField) resolveValue("ip_address", baseIdentity.ipAddress) else item.value
                    clipboardManager.setText(AnnotatedString(rawCopyVal))
                    Toast.makeText(
                        context,
                        tr("${item.label} copied", "${item.label} কপি হয়েছে", "${item.label} कॉपी किया गया"),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onToggleSwitch = { checked ->
                    paramEnabledMap[item.key] = checked
                    onToggleResetOption(item.timerOptionKey)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 8. Bottom Action & Stats Row (matching video frame_024)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Selected Apps Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(14.dp), phaseOffset = 0.15f)
                    .clickable { onOpenAppsTab() }
                    .testTag("selected_apps_quick_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCard)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(NeonCyan.copy(alpha = 0.14f), RoundedCornerShape(9.dp))
                            .cyberShimmerBorder(1.dp, RoundedCornerShape(9.dp), phaseOffset = 0.28f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "$selectedAppsCount",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = tr("Selected", "সিলেক্টেড", "चयनित"),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            val fabInteractionSource = remember { MutableInteractionSource() }
            val isFabPressed by fabInteractionSource.collectIsPressedAsState()
            val fabScale by animateFloatAsState(
                targetValue = if (isFabPressed) 0.88f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                label = "fab_press_scale"
            )

            val isSystemIncomplete = completedReqsCount < 5

            // Center: Circular Reset FAB ("Reset / পরিবর্তন" Button with centered transparent red lock overlay)
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .graphicsLayer {
                        scaleX = fabScale
                        scaleY = fabScale
                    }
                    .background(
                        brush = Brush.radialGradient(listOf(Color(0xFF00E5FF), Color(0xFF0088FF))),
                        shape = CircleShape
                    )
                    .border(
                        width = 2.dp,
                        color = if (isSystemIncomplete) Color(0xFFFF334B) else Color(0xFF00E5FF),
                        shape = CircleShape
                    )
                    .clickable(
                        enabled = !isResetting,
                        interactionSource = fabInteractionSource,
                        indication = ripple(bounded = false, radius = 38.dp, color = Color.White),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTriggerReset()
                        }
                    )
                    .testTag("reset_identity_fab"),
                contentAlignment = Alignment.Center
            ) {
                if (isResetting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = CyberBg,
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = tr("Reset Identity", "পরিবর্তন", "पहचान बदलें"),
                        tint = CyberBg,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Centered Transparent Red Lock Overlay when system requirements are incomplete
                if (isSystemIncomplete) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            .border(2.dp, Color(0xFFFF334B), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "System Requirements Locked",
                            tint = Color(0xFFFF334B),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Right: Active / 26 Count Card (matching video frame_024)
            Card(
                modifier = Modifier
                    .weight(1f)
                    .cyberShimmerBorder(
                        width = 1.4.dp,
                        shape = RoundedCornerShape(14.dp),
                        phaseOffset = 0.62f,
                        accentColor = if (activeRandomizeCount > 0) EmeraldGreen else null
                    ),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCard)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(
                                if (activeRandomizeCount > 0) EmeraldGreen.copy(alpha = 0.15f) else TextMuted.copy(alpha = 0.15f),
                                RoundedCornerShape(9.dp)
                            )
                            .cyberShimmerBorder(1.dp, RoundedCornerShape(9.dp), phaseOffset = 0.72f, accentColor = EmeraldGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (activeRandomizeCount > 0) EmeraldGreen else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "$activeRandomizeCount",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeRandomizeCount > 0) EmeraldGreen else TextMuted
                            )
                            Text(
                                text = " /26",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted,
                                modifier = Modifier.padding(bottom = 1.dp)
                            )
                        }
                        Text(
                            text = tr("Active", "সক্রিয়", "सक्रिय"),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }

    if (showDeviceModelDialog) {
        DeviceModelSelectorDialog(
            currentModel = baseIdentity.deviceModel,
            currentBuildNumber = baseIdentity.buildNumber,
            currentDeviceId = baseIdentity.deviceId,
            currentKernelVersion = baseIdentity.kernelVersion,
            currentSocHardware = baseIdentity.socHardware,
            currentGpuRenderer = baseIdentity.gpuRenderer,
            currentBasebandVersion = baseIdentity.basebandVersion,
            currentSimImsi = baseIdentity.simImsi,
            currentSimIccid = baseIdentity.simIccid,
            currentWidevineDrmId = baseIdentity.widevineDrmId,
            currentBootloaderVersion = baseIdentity.bootloaderVersion,
            initialTab = dialogInitialTab,
            onSelectPreset = { preset ->
                onSelectDeviceModelPreset(preset)
            },
            onApplyCustom = { model, manufacturer, buildNumber, deviceId, kernelVersion, soc, gpu, gpuVersion, baseband, imsi, iccid, widevine, bootloader, region, locale ->
                onApplyCustomDeviceModel(model, manufacturer, buildNumber, deviceId, kernelVersion, soc, gpu, gpuVersion, baseband, imsi, iccid, widevine, bootloader, region, locale)
            },
            onDismiss = { showDeviceModelDialog = false }
        )
    }

    if (editingParamItem != null) {
        val item = editingParamItem!!
        val isEnabled = paramEnabledMap[item.key] != false
        EditParameterDialog(
            paramKey = item.key,
            paramLabel = item.label,
            paramCategory = item.category,
            currentValue = if (item.isIpField && isIpMasked) resolveValue("ip_address", baseIdentity.ipAddress) else item.value,
            icon = item.icon,
            isCurrentlyEnabled = isEnabled,
            onDismiss = { editingParamItem = null },
            onSave = { newVal, disableAutoRandomize ->
                localValueOverrides[item.key] = newVal
                onUpdateSingleParameter(item.key, newVal)
                if (disableAutoRandomize) {
                    paramEnabledMap[item.key] = false
                    if (isEnabled) {
                        onToggleResetOption(item.timerOptionKey)
                    }
                }
                Toast.makeText(
                    context,
                    if (disableAutoRandomize)
                        tr(
                            "${item.label} custom value saved & switch locked OFF (will not change on reset)!",
                            "${item.label} কাস্টম মান সেট হয়েছে ও সুইচ বন্ধ রাখা হয়েছে (রিসেট করলেও এটি অপরিবর্তিত থাকবে)!",
                            "${item.label} कस्टम मान सेव किया गया और स्विच बंद रखा गया (रीसेट करने पर भी यह नहीं बदलेगा)!"
                        )
                    else
                        tr(
                            "${item.label} updated!",
                            "${item.label} আপডেট হয়েছে!",
                            "${item.label} अपडेट किया गया!"
                        ),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}

/**
 * Individual Parameter Card matching the exact design in the video:
 * - Outer card with cyan border when active (`চালু`) and muted border when off (`বন্ধ`)
 * - Top row: Left icon box + Title + Category badge; Right refresh icon + (optional eye icon) + copy icon + edit pencil icon + bordered `চালু`/`বন্ধ` switch box
 * - Bottom row: Dark inset value container with monospace text on the left and `এডিট ✏️` on the right (clickable for manual editing)
 */
private fun translateParamLabel(label: String): String = when (label) {
    "Device Model" -> tr("Device Model", "ডিভাইস মডেল", "डिवाइस मॉडल")
    "Manufacturer" -> tr("Manufacturer", "প্রস্তুতকারক (Manufacturer)", "निर्माता (Manufacturer)")
    "Android ID" -> tr("Android ID", "অ্যান্ড্রয়েড আইডি", "एंड्रॉइड आईडी")
    "Advertising ID" -> tr("Advertising ID", "অ্যাডভার্টাইজিং আইডি", "विज्ञापन आईडी")
    "IMEI" -> tr("IMEI", "আইএমইআই (IMEI)", "IMEI")
    "Serial Number" -> tr("Serial Number", "সিরিয়াল নম্বর", "सीरियल नंबर")
    "MAC Address" -> tr("MAC Address", "ম্যাক অ্যাড্রেস (MAC)", "मैक पता (MAC)")
    "Bluetooth Address" -> tr("Bluetooth Address", "ব্লুটুথ অ্যাড্রেস", "ब्लूटूथ पता")
    "IP Address" -> tr("IP Address", "আইপি অ্যাড্রেস (IP)", "आईपी पता (IP)")
    "SIM Status & Carrier" -> tr("SIM Status & Carrier", "সিম ও অপারেটর", "सिम और ऑपरेटर")
    "IMSI" -> tr("IMSI", "সিম IMSI", "सिम IMSI")
    "ICCID" -> tr("ICCID", "সিম ICCID", "सिम ICCID")
    "SoC Hardware" -> tr("SoC Hardware", "প্রসেসর (SoC)", "प्रोसेसर (SoC)")
    "GPU Renderer" -> tr("GPU Renderer", "জিপিইউ (GPU)", "जीपीयू (GPU)")
    "Display Resolution" -> tr("Display Resolution", "ডিসপ্লে রেজোলিউশন", "डिस्प्ले रेज़ोल्यूशन")
    "Device ID/Codename" -> tr("Device ID/Codename", "ডিভাইস কোডনেম", "डिवाइस कोडनेम")
    "Bootloader" -> tr("Bootloader", "বুটলোডার", "बूटलोडर")
    "OS Version" -> tr("OS Version", "ওএস ভার্সন", "ओएस संस्करण")
    "Build Number" -> tr("Build Number", "বিল্ড নম্বর", "बिल्ड नंबर")
    "Build Fingerprint" -> tr("Build Fingerprint", "বিল্ড ফিঙ্গারপ্রিন্ট", "बिल्ड फ़िंगरप्रिंट")
    "Kernel Version" -> tr("Kernel Version", "কার্নেল ভার্সন", "कर्नेल संस्करण")
    "Baseband" -> tr("Baseband", "বেসব্যান্ড", "बेसबैंड")
    "Widevine ID" -> tr("Widevine ID", "ওয়াইডভাইন আইডি", "वाइडवाइन आईडी")
    "Location" -> tr("Location", "লোকেশন (GPS)", "लोकेशन (GPS)")
    "Timezone" -> tr("Timezone", "টাইমজোন", "टाइमज़ोन")
    "User Agent" -> tr("User Agent", "ইউজার এজেন্ট", "यूज़र एजेंट")
    else -> label
}

private fun translateParamCategory(category: String): String = when (category) {
    "Identifiers" -> tr("Identifiers", "আইডেন্টিফায়ার", "पहचानकर्ता")
    "Network" -> tr("Network", "নেটওয়ার্ক", "नेटवर्क")
    "Hardware" -> tr("Hardware", "হার্ডওয়্যার", "हार्डवेयर")
    "System & Kernel" -> tr("System & Kernel", "সিস্টেম ও কার্নেল", "सिस्टम व कर्नेल")
    "DRM & Environment" -> tr("DRM & Environment", "ডিআরএম ও এনভায়রনমেন্ট", "डीआरएम व वातावरण")
    else -> category
}

@Composable
private fun DashboardParameterCard(
    label: String,
    category: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
    isIpField: Boolean = false,
    isIpMasked: Boolean = false,
    onToggleIpMask: () -> Unit = {},
    onRandomizeClick: () -> Unit,
    onEditClick: () -> Unit = {},
    onCopyClick: () -> Unit,
    onToggleSwitch: (Boolean) -> Unit
) {
    val localizedLabel = translateParamLabel(label)
    val localizedCategory = translateParamCategory(category)
    val itemPhase = ((label.hashCode() and 0xFF) / 255f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .cyberShimmerBorder(
                width = if (enabled) 1.4.dp else 1.1.dp,
                shape = RoundedCornerShape(16.dp),
                phaseOffset = itemPhase,
                alpha = if (enabled) 0.95f else 0.7f,
                accentColor = if (enabled) NeonCyan else null
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberCard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Top Row: Icon + Label + Category Pill | Refresh + (Eye) + Edit + Copy + Switch Box
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(
                                if (enabled) NeonCyan.copy(alpha = 0.14f) else CyberCardElevated.copy(alpha = 0.5f),
                                RoundedCornerShape(9.dp)
                            )
                            .cyberShimmerBorder(
                                width = 0.9.dp,
                                shape = RoundedCornerShape(9.dp),
                                phaseOffset = itemPhase + 0.15f,
                                accentColor = if (enabled) NeonCyan else null
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = localizedLabel,
                            tint = if (enabled) NeonCyan else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = localizedLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) TextPrimary else TextSecondary,
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .background(CyberCardElevated, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = localizedCategory,
                            fontSize = 10.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Refresh Icon
                    IconButton(
                        onClick = onRandomizeClick,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Randomize $label",
                            tint = NeonCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Eye Icon for IP Address
                    if (isIpField) {
                        IconButton(
                            onClick = onToggleIpMask,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isIpMasked) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle IP visibility",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Edit (Pencil) Icon
                    IconButton(
                        onClick = onEditClick,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit $label",
                            tint = NeonCyan,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Copy Icon
                    IconButton(
                        onClick = onCopyClick,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy $label",
                            tint = TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Outlined Switch Capsule with ON / চালু / चालू
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .background(
                                if (enabled) NeonCyan.copy(alpha = 0.10f) else CyberCardElevated.copy(alpha = 0.35f),
                                RoundedCornerShape(10.dp)
                            )
                            .cyberShimmerBorder(
                                width = 1.dp,
                                shape = RoundedCornerShape(10.dp),
                                phaseOffset = itemPhase + 0.3f,
                                accentColor = if (enabled) NeonCyan else null
                            )
                            .clickable { onToggleSwitch(!enabled) }
                            .padding(start = 8.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (enabled) tr("ON", "চালু", "चालू") else tr("OFF", "বন্ধ", "बंद"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled) NeonCyan else TextMuted
                        )
                        Switch(
                            checked = enabled,
                            onCheckedChange = onToggleSwitch,
                            modifier = Modifier
                                .scale(0.68f)
                                .testTag("switch_${label.lowercase().replace(" ", "_")}"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberBg,
                                checkedTrackColor = NeonCyan,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = CyberCardElevated
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Inset Value Box with "Edit / এডিট / एडिट ✏️"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberBgDarker, RoundedCornerShape(9.dp))
                    .cyberShimmerBorder(
                        width = 0.9.dp,
                        shape = RoundedCornerShape(9.dp),
                        phaseOffset = itemPhase + 0.48f,
                        alpha = 0.85f,
                        accentColor = if (enabled) NeonCyan else null
                    )
                    .clickable { onEditClick() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) NeonCyan else TextMuted,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(NeonCyan.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = tr("Edit", "এডিট", "एडिट"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                }
            }
        }
    }
}
