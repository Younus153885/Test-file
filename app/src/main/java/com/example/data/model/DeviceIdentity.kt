package com.example.data.model

data class DeviceIdentity(
    val id: Long = 0,
    val profileName: String = "Current Active Identity",
    val androidId: String,
    val advertisingId: String,
    val imei: String,
    val macAddress: String,
    val bluetoothAddress: String = "3c:5a:41:02:03:5e",
    val ipAddress: String,
    val deviceModel: String,
    val manufacturer: String,
    val buildFingerprint: String,
    val buildNumber: String = "UP1A.231005.007",
    val deviceId: String = "marlin",
    val kernelVersion: String = "5.15.110-android14-11-g89a3f2b",
    val osVersion: String = "Android 14 (SDK 34) · Patch: 2024-08-01",
    val simImsi: String = "310260982314561",
    val simIccid: String = "8901410321111854920F",
    val socHardware: String = "Snapdragon 8 Gen 3 (SM8650-AB)",
    val basebandVersion: String = "g5300g-240301-240502-B-11812984",
    val widevineDrmId: String = "4912a870b9213e4f",
    val gpuRenderer: String = "Adreno (TM) 750 @ 1000MHz",
    val gpuVersion: String = "Vulkan 1.3.275 (Adreno Driver 512.752.0)",
    val bootloaderVersion: String = "b1-0.5-10808477",
    val region: String = "United States",
    val localeTag: String = "en-US",
    val securityPatch: String = "2024-08-01",
    val serialNumber: String,
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val carrier: String,
    val display: String = "1080x2412 @ 440dpi",
    val userAgent: String,
    val webviewHash: String,
    val creativeId: String,
    val campaignId: String,
    val seatId: String,
    val isOriginalBackup: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class ResetStepItem(
    val stepNumber: Int,
    val name: String,
    val subtitle: String,
    val durationMs: Long,
    var status: StepStatus = StepStatus.PENDING
)

enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    SKIPPED,
    FAILED
}

data class TimerSettings(
    val minutes: Int = 5,
    val seconds: Int = 0,
    val isLoopMode: Boolean = true,
    val startWithSystem: Boolean = true,
    val resetAndroidId: Boolean = true,
    val resetAdvertisingId: Boolean = true,
    val resetImei: Boolean = true,
    val resetMacAddress: Boolean = true,
    val resetBluetooth: Boolean = true,
    val resetNetworkIp: Boolean = true,
    val resetDeviceModel: Boolean = true,
    val resetDeviceId: Boolean = true,
    val resetBuildFingerprint: Boolean = true,
    val resetBuildNumber: Boolean = true,
    val resetKernel: Boolean = true,
    val resetSocHardware: Boolean = true,
    val resetGpuRenderer: Boolean = true,
    val resetBaseband: Boolean = true,
    val resetSimImsi: Boolean = true,
    val resetSimIccid: Boolean = true,
    val resetWidevineDrmId: Boolean = true,
    val resetBootloaderVersion: Boolean = true,
    val resetOsVersion: Boolean = true,
    val resetSerialNumber: Boolean = true,
    val resetLocation: Boolean = true,
    val resetTimezone: Boolean = true,
    val resetCarrierName: Boolean = true,
    val resetManufacturer: Boolean = true,
    val resetDisplayResolution: Boolean = true,
    val resetUserAgent: Boolean = true
)

data class TargetApp(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val isSelected: Boolean = false,
    val isSystemApp: Boolean = false,
    val category: String = "Application"
)

data class SystemPermissionsStatus(
    val hasOverlayPermission: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val isRootDetected: Boolean = false,
    val isLsposedActive: Boolean = false
) {
    val missingCount: Int
        get() {
            var count = 0
            if (!isRootDetected) count++
            if (!hasOverlayPermission) count++
            if (!isBatteryOptimizationIgnored) count++
            if (!hasNotificationPermission) count++
            return count
        }

    val isConfigured: Boolean
        get() = isRootDetected && hasOverlayPermission && isBatteryOptimizationIgnored && hasNotificationPermission

    val isRootMissing: Boolean
        get() = !isRootDetected
}

