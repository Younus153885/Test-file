package com.example.data.engine

import android.content.Context
import com.example.data.generator.IdentityGenerator
import com.example.data.model.DeviceIdentity
import com.example.data.model.ResetStepItem
import com.example.data.model.StepStatus
import com.example.data.model.TimerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

data class ResetProgressState(
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 26,
    val steps: List<ResetStepItem> = emptyList(),
    val isComplete: Boolean = false,
    val newIdentity: DeviceIdentity? = null,
    val totalElapsedMs: Long = 0L,
    val logMessage: String = "",
    val executedCount: Int = 26,
    val skippedCount: Int = 0,
    val currentStepName: String = "",
    val currentStepSubtitle: String = ""
)

object ResetExecutionEngine {

    @Volatile
    var isNetworkResetRunning: Boolean = false
        private set

    @Volatile
    private var networkResetGraceUntilElapsedMs: Long = 0L

    fun markNetworkResetGraceWindow(durationMs: Long = 35_000L) {
        val target = android.os.SystemClock.elapsedRealtime() + durationMs
        if (target > networkResetGraceUntilElapsedMs) {
            networkResetGraceUntilElapsedMs = target
        }
    }

    fun isNetworkResetGraceActive(): Boolean {
        return isNetworkResetRunning || android.os.SystemClock.elapsedRealtime() < networkResetGraceUntilElapsedMs
    }

    fun previewResetSteps(timerSettings: TimerSettings = TimerSettings()): List<ResetStepItem> {
        return createInitialSteps(timerSettings)
    }

    private data class IdentityVectorTask(
        val name: String,
        val initialSubtitle: String,
        val completedSubtitle: String,
        val durationMs: Long,
        val isEnabled: Boolean,
        val onExecute: suspend () -> Unit = {}
    )

    fun createInitialSteps(timerSettings: TimerSettings = TimerSettings()): List<ResetStepItem> {
        val enabledNames = mutableListOf<Pair<String, String>>()
        if (timerSettings.resetDeviceModel) enabledNames.add("Device Model" to "Synthesizing model identifier")
        if (timerSettings.resetManufacturer) enabledNames.add("Manufacturer" to "Updating OEM brand & manufacturer")
        if (timerSettings.resetAndroidId) enabledNames.add("Android ID" to "Regenerating 64-bit hardware token")
        if (timerSettings.resetAdvertisingId) enabledNames.add("Advertising ID" to "Zeroing GAID & ad tracker seeds")
        if (timerSettings.resetImei) enabledNames.add("IMEI" to "Randomizing cellular TAC & serial")
        if (timerSettings.resetSerialNumber) enabledNames.add("Serial Number" to "Spoofing hardware serial number")
        if (timerSettings.resetMacAddress) enabledNames.add("MAC Address" to "Randomizing hardware Wi-Fi MAC")
        if (timerSettings.resetBluetooth) enabledNames.add("Bluetooth Address" to "Randomizing Bluetooth MAC")
        if (timerSettings.resetNetworkIp) enabledNames.add("IP Address" to "Cycling connection & socket tables")
        if (timerSettings.resetCarrierName) enabledNames.add("SIM Status & Carrier" to "Spoofing SIM operator & network state")
        if (timerSettings.resetSimImsi) enabledNames.add("IMSI" to "Generating subscriber identity")
        if (timerSettings.resetSimIccid) enabledNames.add("ICCID" to "Generating SIM serial number")
        if (timerSettings.resetSocHardware) enabledNames.add("SoC Hardware" to "Spoofing processor platform")
        if (timerSettings.resetGpuRenderer) enabledNames.add("GPU Renderer" to "Spoofing OpenGL/Vulkan renderer")
        if (timerSettings.resetDisplayResolution) enabledNames.add("Display Resolution" to "Updating screen metrics & DPI")
        if (timerSettings.resetDeviceId) enabledNames.add("Device ID/Codename" to "Spoofing board & product codename")
        if (timerSettings.resetBootloaderVersion) enabledNames.add("Bootloader" to "Spoofing bootloader revision")
        if (timerSettings.resetOsVersion) enabledNames.add("OS Version" to "Updating Android release & patch")
        if (timerSettings.resetBuildNumber) enabledNames.add("Build Number" to "Spoofing ROM build ID")
        if (timerSettings.resetBuildFingerprint) enabledNames.add("Build Fingerprint" to "Applying system build fingerprint")
        if (timerSettings.resetKernel) enabledNames.add("Kernel Version" to "Spoofing Linux kernel signature")
        if (timerSettings.resetBaseband) enabledNames.add("Baseband" to "Spoofing radio baseband firmware")
        if (timerSettings.resetWidevineDrmId) enabledNames.add("Widevine ID" to "Rotating DRM unique client ID")
        if (timerSettings.resetLocation) enabledNames.add("Location" to "Injecting GPS lat/long & provider")
        if (timerSettings.resetTimezone) enabledNames.add("Timezone" to "Synchronizing local timezone & UTC")
        if (timerSettings.resetUserAgent) enabledNames.add("User Agent" to "Updating WebView & HTTP User-Agent")

        // 14 Additional Deep Hardware & System Uniqueness Vectors (Steps 27 to 40)
        enabledNames.add("GSF ID (Google Services)" to "Rotating 64-bit Google Services Framework token")
        enabledNames.add("AppSet & Firebase ID" to "Regenerating developer AppSet & Firebase FID")
        enabledNames.add("SoC Chip & Die Serial" to "Spoofing CPU silicon die & SoC serial number")
        enabledNames.add("Motherboard & PCB Serial" to "Rotating OEM board & RIL hardware serials")
        enabledNames.add("Kernel Boot UUID" to "Rotating /proc/sys/kernel/random/boot_id")
        enabledNames.add("Storage Flash CID & UUID" to "Spoofing UFS/eMMC flash CID & partition UUID")
        enabledNames.add("Wi-Fi Router BSSID & SSID" to "Rotating router hardware MAC & AP signature")
        enabledNames.add("Cell Tower CID & LAC" to "Updating cellular base station CID, TAC & PCI")
        enabledNames.add("eSIM EID & Subscription ID" to "Rotating 32-digit eUICC EID & SIM card ID")
        enabledNames.add("Sensor Calibration Hash" to "Injecting unique MEMS gyro/accel bias signature")
        enabledNames.add("Battery & Thermal Telemetry" to "Randomizing charge counter, voltage & temp")
        enabledNames.add("Uptime & Install Timestamp" to "Resetting boot elapsed clock & package install time")
        enabledNames.add("Audio DSP & Codec Profile" to "Rotating hardware audio session & DSP signature")
        enabledNames.add("Input Descriptor & WebGL Hash" to "Rotating touchscreen SHA-1 descriptor & WebGL seed")

        return enabledNames.mapIndexed { idx, (title, sub) ->
            ResetStepItem(idx + 1, title, sub, 85L)
        }
    }

    fun executeResetPipeline(
        targetAppName: String = "All Selected Target Apps",
        currentIdentity: DeviceIdentity,
        timerSettings: TimerSettings = TimerSettings(),
        forcedIdentity: DeviceIdentity? = null,
        targetPackageNames: List<String> = emptyList(),
        isGlobalSpoofEnabled: Boolean = true,
        context: Context? = null
    ): Flow<ResetProgressState> = flow {
        isNetworkResetRunning = true
        markNetworkResetGraceWindow(60_000L)
        try {
        val initialItems = previewResetSteps(timerSettings)
        emit(
            ResetProgressState(
                isComplete = false,
                currentStepIndex = 0,
                totalSteps = initialItems.size,
                currentStepName = "Resetting Identity...",
                currentStepSubtitle = "Executing LSPosed & kernel wipe pipeline",
                steps = initialItems,
                totalElapsedMs = 0L,
                executedCount = 0,
                skippedCount = 0
            )
        )

        // Calculate new identity strictly respecting Reset Options toggles
        val newIdentity = if (forcedIdentity != null) {
            forcedIdentity
        } else {
            val gen = IdentityGenerator.generateNewIdentity(
                excludeModel = currentIdentity.deviceModel,
                excludeSoc = currentIdentity.socHardware,
                excludeTimezone = currentIdentity.timezone,
                excludeLocation = currentIdentity.locationName
            )
            val syncLocAndTz = timerSettings.resetLocation || timerSettings.resetTimezone
            currentIdentity.copy(
                profileName = gen.profileName,
                androidId = if (timerSettings.resetAndroidId) gen.androidId else currentIdentity.androidId,
                advertisingId = if (timerSettings.resetAdvertisingId) gen.advertisingId else currentIdentity.advertisingId,
                imei = if (timerSettings.resetImei) gen.imei else currentIdentity.imei,
                macAddress = if (timerSettings.resetMacAddress) gen.macAddress.lowercase() else currentIdentity.macAddress.lowercase(),
                bluetoothAddress = if (timerSettings.resetBluetooth) gen.bluetoothAddress.lowercase() else currentIdentity.bluetoothAddress.lowercase(),
                ipAddress = if (timerSettings.resetNetworkIp || syncLocAndTz) gen.ipAddress else currentIdentity.ipAddress,
                deviceModel = if (timerSettings.resetDeviceModel) gen.deviceModel else currentIdentity.deviceModel,
                manufacturer = if (timerSettings.resetManufacturer) gen.manufacturer else currentIdentity.manufacturer,
                buildFingerprint = if (timerSettings.resetBuildFingerprint) gen.buildFingerprint else currentIdentity.buildFingerprint,
                buildNumber = if (timerSettings.resetBuildNumber) gen.buildNumber else currentIdentity.buildNumber,
                deviceId = if (timerSettings.resetDeviceId) gen.deviceId else currentIdentity.deviceId,
                kernelVersion = if (timerSettings.resetKernel) gen.kernelVersion else currentIdentity.kernelVersion,
                socHardware = if (timerSettings.resetSocHardware) gen.socHardware else currentIdentity.socHardware,
                gpuRenderer = if (timerSettings.resetGpuRenderer) gen.gpuRenderer else currentIdentity.gpuRenderer,
                basebandVersion = if (timerSettings.resetBaseband) gen.basebandVersion else currentIdentity.basebandVersion,
                simImsi = if (timerSettings.resetSimImsi || syncLocAndTz) gen.simImsi else currentIdentity.simImsi,
                simIccid = if (timerSettings.resetSimIccid || syncLocAndTz) gen.simIccid else currentIdentity.simIccid,
                widevineDrmId = if (timerSettings.resetWidevineDrmId) gen.widevineDrmId else currentIdentity.widevineDrmId,
                bootloaderVersion = if (timerSettings.resetBootloaderVersion) gen.bootloaderVersion else currentIdentity.bootloaderVersion,
                region = if (syncLocAndTz) gen.region else currentIdentity.region,
                localeTag = if (syncLocAndTz) gen.localeTag else currentIdentity.localeTag,
                osVersion = if (timerSettings.resetOsVersion) gen.osVersion else currentIdentity.osVersion,
                serialNumber = if (timerSettings.resetSerialNumber) gen.serialNumber else currentIdentity.serialNumber,
                display = if (timerSettings.resetDisplayResolution) gen.display else currentIdentity.display,
                userAgent = if (timerSettings.resetUserAgent) gen.userAgent else currentIdentity.userAgent,
                webviewHash = if (timerSettings.resetDeviceModel) gen.webviewHash else currentIdentity.webviewHash,
                locationName = if (syncLocAndTz) gen.locationName else currentIdentity.locationName,
                latitude = if (syncLocAndTz) gen.latitude else currentIdentity.latitude,
                longitude = if (syncLocAndTz) gen.longitude else currentIdentity.longitude,
                timezone = if (syncLocAndTz) gen.timezone else currentIdentity.timezone,
                carrier = if (timerSettings.resetCarrierName || syncLocAndTz) gen.carrier else currentIdentity.carrier
            )
        }

        val lspScopedPackages = if (context != null) {
            try {
                com.example.data.device.LspScopeSyncCli.getScopedTargetPackages(context)
            } catch (_: Throwable) {
                emptySet()
            }
        } else {
            emptySet()
        }

        val rootBypassPkgs = if (context != null) {
            try {
                context.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                    .getStringSet("root_bypass_packages", emptySet())
                    ?.toList() ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }

        // Only wipe/reset apps that are selected for Reset (not apps that ONLY have Root Restrict Bypass ON)
        val rootBypassOnlySet = rootBypassPkgs.toSet() - targetPackageNames.toSet()
        val allTargetPackages = ((targetPackageNames + lspScopedPackages) - rootBypassOnlySet).distinct()

        val safeTargetPackages = allTargetPackages.filter {
            it != "android" &&
            it != "system" &&
            it != context?.packageName &&
            !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) &&
            !com.example.data.device.DeviceAppScanner.isCriticalSystemNoWipePackage(it)
        }

        // Clear system clipboard so apps cannot recover previous account/device tokens from clipboard
        if (context != null) {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    cm?.clearPrimaryClip()
                } else {
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                }
            } catch (_: Throwable) {}
        }

        // Progressive identity that updates step-by-step as each vector completes its loading phase
        var workingIdentity = currentIdentity
        val isRooted = com.example.data.device.RootChecker.isDeviceRooted()
        val selfPkg = context?.packageName ?: "com.aistudio.phantomcore.qxvz"

        fun syncStepIdentityJson(identity: DeviceIdentity) {
            try {
                val resetTs = System.currentTimeMillis()
                val targetsArray = org.json.JSONArray()
                allTargetPackages.forEach { targetsArray.put(it) }
                val rootBypassArray = org.json.JSONArray()
                rootBypassPkgs.forEach { rootBypassArray.put(it) }

                val json = org.json.JSONObject().apply {
                    put("globalSpoofEnabled", isGlobalSpoofEnabled)
                    put("targetPackages", targetsArray)
                    put("rootBypassPackages", rootBypassArray)
                    put("deviceModel", identity.deviceModel)
                    put("manufacturer", identity.manufacturer)
                    put("buildFingerprint", identity.buildFingerprint)
                    put("buildNumber", identity.buildNumber)
                    put("deviceId", identity.deviceId)
                    put("kernelVersion", identity.kernelVersion)
                    put("socHardware", identity.socHardware)
                    put("gpuRenderer", identity.gpuRenderer)
                    put("gpuVersion", identity.gpuVersion)
                    put("basebandVersion", identity.basebandVersion)
                    put("simImsi", identity.simImsi)
                    put("simIccid", identity.simIccid)
                    put("widevineDrmId", identity.widevineDrmId)
                    put("bootloaderVersion", identity.bootloaderVersion)
                    put("serialNumber", identity.serialNumber)
                    put("androidId", identity.androidId)
                    put("advertisingId", identity.advertisingId)
                    put("imei", identity.imei)
                    put("carrier", identity.carrier)
                    put("macAddress", identity.macAddress)
                    put("bluetoothAddress", identity.bluetoothAddress)
                    put("ipAddress", identity.ipAddress)
                    put("osVersion", identity.osVersion)
                    put("timezone", identity.timezone)
                    put("latitude", identity.latitude)
                    put("longitude", identity.longitude)
                    put("locationName", identity.locationName)
                    put("region", identity.region)
                    put("localeTag", identity.localeTag)
                    put("display", identity.display)
                    put("userAgent", identity.userAgent)
                    put("resetTimestamp", resetTs)
                }.toString()

                com.example.provider.PhantomIdentityProvider.cachedIdentityJson = json
                if (context != null) {
                    try {
                        val internalFile = java.io.File(context.filesDir, "phantom_identity.json")
                        internalFile.writeText(json)
                        internalFile.setReadable(true, false)
                    } catch (_: Exception) {}
                }
                for (p in arrayOf("/data/local/tmp/phantom_identity.json", "/sdcard/phantom_identity.json")) {
                    try {
                        val f = java.io.File(p)
                        f.parentFile?.mkdirs()
                        f.writeText(json)
                        f.setReadable(true, false)
                        f.setWritable(true, false)
                    } catch (_: Exception) {}
                }
            } catch (_: Throwable) {}
        }

        fun runStepPropsScript(extraCommands: String) {
            if (!isRooted) return
            val script = """
                export PATH=/sbin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:/system/bin:/system/xbin:${'$'}PATH
                RP_BIN="setprop"
                for CANDIDATE in "/data/adb/magisk/resetprop" "/sbin/resetprop" "/data/adb/ksu/bin/resetprop" "resetprop"; do
                    if command -v "${'$'}CANDIDATE" >/dev/null 2>&1 || [ -x "${'$'}CANDIDATE" ]; then
                        RP_BIN="${'$'}CANDIDATE"
                        break
                    fi
                done
                $extraCommands
            """.trimIndent()
            com.example.data.device.RootChecker.executeRootCommand(script)
        }

        var targetSandboxesCleaned = false
        fun cleanTargetSandboxesOnce() {
            if (targetSandboxesCleaned || !isRooted) return
            targetSandboxesCleaned = true
            val cleanHexAid = newIdentity.androidId.lowercase().replace("[^0-9a-f]".toRegex(), "").take(16).padEnd(16, 'a')
            val sb = StringBuilder()
            sb.appendLine("""
                export PATH=/sbin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:/system/bin:/system/xbin:${'$'}PATH
                cmd clipboard clear-primary-clip 2>/dev/null || service call clipboard 2 i32 1 i32 0 2>/dev/null || true
                rm -rf /sdcard/.android /sdcard/.DataStorage /sdcard/.SystemConfig /sdcard/.UTSystemConfig /sdcard/.backups /sdcard/.turingdebug /sdcard/.um /sdcard/.uxx /sdcard/.device_id /sdcard/Documents/.system* /sdcard/Download/.* 2>/dev/null
                rm -rf /storage/emulated/0/.android /storage/emulated/0/.DataStorage /storage/emulated/0/.SystemConfig /storage/emulated/0/.UTSystemConfig /storage/emulated/0/.backups /storage/emulated/0/.turingdebug /storage/emulated/0/.um /storage/emulated/0/.uxx /storage/emulated/0/.device_id /storage/emulated/0/Documents/.system* /storage/emulated/0/Download/.* 2>/dev/null
                SQLITE_BIN=""
                for CANDIDATE in /system/bin/sqlite3 /system/xbin/sqlite3 /data/adb/magisk/sqlite3 /data/adb/ksu/bin/sqlite3 sqlite3; do
                    if command -v "${'$'}CANDIDATE" >/dev/null 2>&1 || [ -x "${'$'}CANDIDATE" ]; then
                        SQLITE_BIN="${'$'}CANDIDATE"
                        break
                    fi
                done
            """.trimIndent())
            for (pkg in safeTargetPackages) {
                sb.appendLine("""
                    am force-stop $pkg 2>/dev/null
                    am kill $pkg 2>/dev/null
                    cmd activity kill $pkg 2>/dev/null
                    killall -9 $pkg 2>/dev/null || true
                    pm clear --user 0 $pkg 2>/dev/null || pm clear $pkg 2>/dev/null || cmd package clear $pkg 2>/dev/null || true
                    for BASE in "/data/data/$pkg" "/data/user/0/$pkg" "/data/user/10/$pkg" "/data/user/999/$pkg" "/data/user_de/0/$pkg" "/data/user_de/10/$pkg" "/data/user_de/999/$pkg"; do
                        if [ -d "${'$'}BASE" ]; then
                            rm -rf "${'$'}BASE/cache/"* "${'$'}BASE/cache/".[!.]* "${'$'}BASE/code_cache/"* "${'$'}BASE/code_cache/".[!.]* 2>/dev/null
                            rm -rf "${'$'}BASE/shared_prefs/"* "${'$'}BASE/databases/"* "${'$'}BASE/files/"* "${'$'}BASE/files/".[!.]* "${'$'}BASE/no_backup/"* "${'$'}BASE/no_backup/".[!.]* 2>/dev/null
                            rm -rf "${'$'}BASE/app_"* "${'$'}BASE/mmkv"* "${'$'}BASE/datastore"* "${'$'}BASE/il2cpp" "${'$'}BASE/.phantom_identity.json" 2>/dev/null
                        fi
                    done
                    for SSAID_FILE in /data/system/users/0/settings_ssaid.xml /data/system/users/10/settings_ssaid.xml /data/system/users/999/settings_ssaid.xml; do
                        if [ -f "${'$'}SSAID_FILE" ] && grep -q "$pkg" "${'$'}SSAID_FILE" 2>/dev/null; then
                            sed -i "s/\(package=\"$pkg\"[^>]*value=\"\)[0-9a-fA-F]*/\1$cleanHexAid/g" "${'$'}SSAID_FILE" 2>/dev/null || true
                            sed -i "s/\(package=\"$pkg\"[^>]*defaultValue=\"\)[0-9a-fA-F]*/\1$cleanHexAid/g" "${'$'}SSAID_FILE" 2>/dev/null || true
                        fi
                    done
                    if [ -n "${'$'}SQLITE_BIN" ]; then
                        for ACC_DB in /data/system_ce/0/accounts_ce.db /data/system_de/0/accounts_de.db /data/system/users/0/accounts.db; do
                            if [ -f "${'$'}ACC_DB" ]; then
                                "${'$'}SQLITE_BIN" "${'$'}ACC_DB" "DELETE FROM accounts WHERE type LIKE '%$pkg%'; DELETE FROM grants WHERE accounts_id NOT IN (SELECT _id FROM accounts);" 2>/dev/null || true
                            fi
                        done
                    fi
                    rm -rf "/sdcard/Android/data/$pkg/cache/"* "/sdcard/Android/data/$pkg/files/"* "/storage/emulated/0/Android/data/$pkg/cache/"* "/storage/emulated/0/Android/data/$pkg/files/"* 2>/dev/null
                    rm -rf "/sdcard/Android/data/$pkg" "/storage/emulated/0/Android/data/$pkg" 2>/dev/null
                    rm -rf "/sdcard/Android/media/$pkg/"* "/storage/emulated/0/Android/media/$pkg/"* 2>/dev/null
                    rm -rf "/sdcard/Android/obb/$pkg/"*.tmp "/sdcard/Android/obb/$pkg/"*.cache "/sdcard/Android/obb/$pkg/"*.dat "/storage/emulated/0/Android/obb/$pkg/"*.tmp "/storage/emulated/0/Android/obb/$pkg/"*.cache "/storage/emulated/0/Android/obb/$pkg/"*.dat 2>/dev/null
                """.trimIndent())
            }
            com.example.data.device.RootChecker.executeRootCommand(sb.toString())
        }

        val forceAll = forcedIdentity != null

        val allVectorTasks = listOf(
            IdentityVectorTask(
                name = "Device Model",
                initialSubtitle = "Synthesizing model identifier...",
                completedSubtitle = newIdentity.deviceModel,
                durationMs = 110L,
                isEnabled = timerSettings.resetDeviceModel || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(
                        deviceModel = newIdentity.deviceModel,
                        webviewHash = newIdentity.webviewHash
                    )
                    syncStepIdentityJson(workingIdentity)
                    val globalCmd = if (isGlobalSpoofEnabled) {
                        """
                        ${'$'}RP_BIN ro.product.model "${newIdentity.deviceModel}" 2>/dev/null
                        ${'$'}RP_BIN ro.product.system.model "${newIdentity.deviceModel}" 2>/dev/null
                        ${'$'}RP_BIN ro.product.vendor.model "${newIdentity.deviceModel}" 2>/dev/null
                        ${'$'}RP_BIN ro.product.odm.model "${newIdentity.deviceModel}" 2>/dev/null
                        ${'$'}RP_BIN ro.product.product.model "${newIdentity.deviceModel}" 2>/dev/null
                        ${'$'}RP_BIN ro.product.system_ext.model "${newIdentity.deviceModel}" 2>/dev/null
                        settings put global device_name "${newIdentity.deviceModel}" 2>/dev/null
                        settings put secure bluetooth_name "${newIdentity.deviceModel}" 2>/dev/null
                        """.trimIndent()
                    } else ""
                    runStepPropsScript("""
                        setprop debug.phantom.mod "${newIdentity.deviceModel}" 2>/dev/null
                        setprop persist.sys.phantom.mod "${newIdentity.deviceModel}" 2>/dev/null
                        $globalCmd
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Manufacturer",
                initialSubtitle = "Updating OEM brand & manufacturer...",
                completedSubtitle = newIdentity.manufacturer,
                durationMs = 105L,
                isEnabled = timerSettings.resetManufacturer || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(manufacturer = newIdentity.manufacturer)
                    syncStepIdentityJson(workingIdentity)
                    val globalCmd = if (isGlobalSpoofEnabled) {
                        """
                        ${'$'}RP_BIN ro.product.manufacturer "${newIdentity.manufacturer}" 2>/dev/null
                        ${'$'}RP_BIN ro.product.brand "${newIdentity.manufacturer}" 2>/dev/null
                        """.trimIndent()
                    } else ""
                    runStepPropsScript("""
                        setprop debug.phantom.mfr "${newIdentity.manufacturer}" 2>/dev/null
                        setprop persist.sys.phantom.mfr "${newIdentity.manufacturer}" 2>/dev/null
                        $globalCmd
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Android ID",
                initialSubtitle = "Regenerating 64-bit hardware token...",
                completedSubtitle = "Android ID set to ${newIdentity.androidId}",
                durationMs = 135L,
                isEnabled = timerSettings.resetAndroidId || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(androidId = newIdentity.androidId)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        settings put secure android_id ${newIdentity.androidId} 2>/dev/null
                        setprop debug.phantom.aid "${newIdentity.androidId}" 2>/dev/null
                        setprop persist.sys.phantom.aid "${newIdentity.androidId}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Advertising ID",
                initialSubtitle = "Zeroing GAID & ad tracker seeds...",
                completedSubtitle = "Advertising ID set to (${newIdentity.advertisingId.take(12)}...)",
                durationMs = 115L,
                isEnabled = timerSettings.resetAdvertisingId || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(advertisingId = newIdentity.advertisingId)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.adv "${newIdentity.advertisingId}" 2>/dev/null
                        setprop persist.sys.phantom.adv "${newIdentity.advertisingId}" 2>/dev/null
                        rm -f /data/data/com.google.android.gms/shared_prefs/app_set_id_storage.xml /data/user_de/0/com.google.android.gms/shared_prefs/app_set_id_storage.xml 2>/dev/null
                        rm -rf /data/data/com.google.android.gms/files/blockstore* /data/data/com.google.android.gms/databases/*blockstore* /data/data/com.google.android.gms/databases/*games* 2>/dev/null
                        am force-stop com.google.android.play.games 2>/dev/null || true
                        pm clear --user 0 com.google.android.play.games 2>/dev/null || pm clear com.google.android.play.games 2>/dev/null || true
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "IMEI",
                initialSubtitle = "Randomizing cellular TAC & serial...",
                completedSubtitle = "IMEI: ${newIdentity.imei}",
                durationMs = 105L,
                isEnabled = timerSettings.resetImei || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(imei = newIdentity.imei)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.imei "${newIdentity.imei}" 2>/dev/null
                        setprop persist.sys.phantom.imei "${newIdentity.imei}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Serial Number",
                initialSubtitle = "Spoofing hardware serial number...",
                completedSubtitle = "Serial: ${newIdentity.serialNumber}",
                durationMs = 105L,
                isEnabled = timerSettings.resetSerialNumber || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(serialNumber = newIdentity.serialNumber)
                    syncStepIdentityJson(workingIdentity)
                    val globalCmd = if (isGlobalSpoofEnabled) {
                        """
                        ${'$'}RP_BIN ro.serialno "${newIdentity.serialNumber}" 2>/dev/null
                        ${'$'}RP_BIN ro.boot.serialno "${newIdentity.serialNumber}" 2>/dev/null
                        """.trimIndent()
                    } else ""
                    runStepPropsScript("""
                        setprop debug.phantom.ser "${newIdentity.serialNumber}" 2>/dev/null
                        setprop persist.sys.phantom.ser "${newIdentity.serialNumber}" 2>/dev/null
                        $globalCmd
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "MAC Address",
                initialSubtitle = "Randomizing hardware Wi-Fi MAC...",
                completedSubtitle = "MAC: ${newIdentity.macAddress.lowercase()}",
                durationMs = 120L,
                isEnabled = timerSettings.resetMacAddress || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(macAddress = newIdentity.macAddress)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.mac "${newIdentity.macAddress}" 2>/dev/null
                        setprop persist.sys.phantom.mac "${newIdentity.macAddress}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Bluetooth Address",
                initialSubtitle = "Randomizing Bluetooth MAC...",
                completedSubtitle = "BT: ${newIdentity.bluetoothAddress.lowercase()}",
                durationMs = 110L,
                isEnabled = timerSettings.resetBluetooth || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(bluetoothAddress = newIdentity.bluetoothAddress)
                    syncStepIdentityJson(workingIdentity)
                    val cleanBt = newIdentity.bluetoothAddress.lowercase()
                    runStepPropsScript("""
                        settings put secure bluetooth_address $cleanBt 2>/dev/null
                        setprop debug.phantom.bt "$cleanBt" 2>/dev/null
                        setprop persist.sys.phantom.bt "$cleanBt" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "IP Address",
                initialSubtitle = "Toggling Flight Mode ON/OFF & wiping network history...",
                completedSubtitle = "Flight Mode cycled & Network History wiped (${newIdentity.ipAddress})",
                durationMs = 180L,
                isEnabled = timerSettings.resetNetworkIp || forceAll,
                onExecute = {
                    markNetworkResetGraceWindow(45_000L)
                    workingIdentity = workingIdentity.copy(ipAddress = newIdentity.ipAddress)
                    syncStepIdentityJson(workingIdentity)
                    if (isRooted) {
                        try {
                            // Phase 1: Signal Flight Mode ON and wipe network usage stats without deleting routing tables or DNS servers
                            val flightOnAndCleanScript = """
                                export PATH=/sbin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:/system/bin:/system/xbin:${'$'}PATH
                                setprop debug.phantom.ip "${newIdentity.ipAddress}" 2>/dev/null
                                setprop persist.sys.phantom.ip "${newIdentity.ipAddress}" 2>/dev/null

                                # Signal Airplane Mode state briefly without killing routing tables or DNS configs
                                cmd connectivity airplane-mode enable 2>/dev/null || true
                                if [ "${'$'}(settings get global airplane_mode_on 2>/dev/null)" != "1" ]; then
                                    settings put global airplane_mode_on 1 2>/dev/null
                                    am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true -f 0x01000000 2>/dev/null || am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true 2>/dev/null || true
                                fi

                                # Wipe Android Network Usage & Traffic History (netstats) safely
                                rm -rf /data/system/netstats/* 2>/dev/null || true
                                dumpsys netstats --reset 2>/dev/null || true

                                # Flush route cache only (NEVER delete /data/misc/net/rt_tables or run clearnetdns which drops internet!)
                                ip route flush cache 2>/dev/null || true
                                ip -6 route flush cache 2>/dev/null || true

                                # Purge System Connectivity Event History Logs
                                rm -rf /data/system/dropbox/*net* /data/system/dropbox/*wifi* /data/system/dropbox/*data* 2>/dev/null || true
                                logcat -b events -c 2>/dev/null || true
                            """.trimIndent()
                            com.example.data.device.RootChecker.executeRootCommand(flightOnAndCleanScript)

                            // Brief pause so Flight Mode state cycles cleanly
                            delay(550L)
                        } finally {
                            markNetworkResetGraceWindow(40_000L)
                            // Phase 2: Guaranteed Turn Flight Mode OFF BEFORE forcing settings global = 0
                            // so cmd connectivity airplane-mode disable never no-ops, plus re-enable Wi-Fi & Data
                            val flightOffScript = """
                                export PATH=/sbin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:/system/bin:/system/xbin:${'$'}PATH
                                cmd connectivity airplane-mode disable 2>/dev/null || true
                                if [ "${'$'}(settings get global airplane_mode_on 2>/dev/null)" != "0" ]; then
                                    settings put global airplane_mode_on 0 2>/dev/null
                                    am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false -f 0x01000000 2>/dev/null || true
                                    am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false 2>/dev/null || true
                                fi
                                svc wifi enable 2>/dev/null || true
                                svc data enable 2>/dev/null || true
                                if [ "${'$'}(settings get global airplane_mode_on 2>/dev/null)" = "1" ]; then
                                    sleep 0.3 2>/dev/null || true
                                    cmd connectivity airplane-mode disable 2>/dev/null || true
                                    settings put global airplane_mode_on 0 2>/dev/null
                                    am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false 2>/dev/null || true
                                    svc wifi enable 2>/dev/null || true
                                    svc data enable 2>/dev/null || true
                                fi
                                if [ ! -f /data/misc/net/rt_tables ]; then
                                    mkdir -p /data/misc/net 2>/dev/null || true
                                    printf "255\tlocal\n254\tmain\n253\tdefault\n0\tunspec\n" > /data/misc/net/rt_tables 2>/dev/null || true
                                    chmod 644 /data/misc/net/rt_tables 2>/dev/null || true
                                fi
                            """.trimIndent()
                            com.example.data.device.RootChecker.executeRootCommand(flightOffScript)
                            delay(350L)
                        }
                    }
                }
            ),
            IdentityVectorTask(
                name = "SIM Status & Carrier",
                initialSubtitle = "Spoofing SIM operator & location phone number...",
                completedSubtitle = "Carrier: ${newIdentity.carrier} (${newIdentity.locationName})",
                durationMs = 105L,
                isEnabled = timerSettings.resetCarrierName || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(carrier = newIdentity.carrier)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.car "${newIdentity.carrier}" 2>/dev/null
                        setprop persist.sys.phantom.car "${newIdentity.carrier}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "IMSI",
                initialSubtitle = "Generating subscriber identity...",
                completedSubtitle = "IMSI: ${newIdentity.simImsi}",
                durationMs = 100L,
                isEnabled = timerSettings.resetSimImsi || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(simImsi = newIdentity.simImsi)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.imsi "${newIdentity.simImsi}" 2>/dev/null
                        setprop persist.sys.phantom.imsi "${newIdentity.simImsi}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "ICCID",
                initialSubtitle = "Generating SIM serial number...",
                completedSubtitle = "ICCID: ${newIdentity.simIccid}",
                durationMs = 100L,
                isEnabled = timerSettings.resetSimIccid || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(simIccid = newIdentity.simIccid)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.iccid "${newIdentity.simIccid}" 2>/dev/null
                        setprop persist.sys.phantom.iccid "${newIdentity.simIccid}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "SoC Hardware",
                initialSubtitle = "Spoofing processor platform...",
                completedSubtitle = "SoC: ${newIdentity.socHardware}",
                durationMs = 105L,
                isEnabled = timerSettings.resetSocHardware || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(socHardware = newIdentity.socHardware)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.soc "${newIdentity.socHardware}" 2>/dev/null
                        setprop persist.sys.phantom.soc "${newIdentity.socHardware}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "GPU Renderer",
                initialSubtitle = "Spoofing OpenGL/Vulkan renderer...",
                completedSubtitle = "GPU: ${newIdentity.gpuRenderer}",
                durationMs = 105L,
                isEnabled = timerSettings.resetGpuRenderer || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(
                        gpuRenderer = newIdentity.gpuRenderer,
                        gpuVersion = newIdentity.gpuVersion
                    )
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.gpu "${newIdentity.gpuRenderer}" 2>/dev/null
                        setprop persist.sys.phantom.gpu "${newIdentity.gpuRenderer}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Display Resolution",
                initialSubtitle = "Updating screen metrics & DPI...",
                completedSubtitle = "Display: ${newIdentity.display}",
                durationMs = 100L,
                isEnabled = timerSettings.resetDisplayResolution || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(display = newIdentity.display)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.disp "${newIdentity.display}" 2>/dev/null
                        setprop persist.sys.phantom.disp "${newIdentity.display}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Device ID/Codename",
                initialSubtitle = "Spoofing board & product codename...",
                completedSubtitle = "Codename: ${newIdentity.deviceId}",
                durationMs = 100L,
                isEnabled = timerSettings.resetDeviceId || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(deviceId = newIdentity.deviceId)
                    syncStepIdentityJson(workingIdentity)
                    val devCodename = newIdentity.deviceId.ifBlank { newIdentity.deviceModel.replace(" ", "_") }
                    runStepPropsScript("""
                        setprop debug.phantom.did "$devCodename" 2>/dev/null
                        setprop persist.sys.phantom.did "$devCodename" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Bootloader",
                initialSubtitle = "Spoofing bootloader revision...",
                completedSubtitle = "Bootloader: ${newIdentity.bootloaderVersion}",
                durationMs = 100L,
                isEnabled = timerSettings.resetBootloaderVersion || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(bootloaderVersion = newIdentity.bootloaderVersion)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.bl "${newIdentity.bootloaderVersion}" 2>/dev/null
                        setprop persist.sys.phantom.bl "${newIdentity.bootloaderVersion}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "OS Version",
                initialSubtitle = "Updating Android release & patch...",
                completedSubtitle = newIdentity.osVersion,
                durationMs = 105L,
                isEnabled = timerSettings.resetOsVersion || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(osVersion = newIdentity.osVersion)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.os "${newIdentity.osVersion}" 2>/dev/null
                        setprop persist.sys.phantom.os "${newIdentity.osVersion}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Build Number",
                initialSubtitle = "Spoofing ROM build ID...",
                completedSubtitle = "Build: ${newIdentity.buildNumber}",
                durationMs = 105L,
                isEnabled = timerSettings.resetBuildNumber || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(buildNumber = newIdentity.buildNumber)
                    syncStepIdentityJson(workingIdentity)
                    val globalCmd = if (isGlobalSpoofEnabled) {
                        """
                        ${'$'}RP_BIN ro.build.id "${newIdentity.buildNumber}" 2>/dev/null
                        ${'$'}RP_BIN ro.build.display.id "${newIdentity.buildNumber}" 2>/dev/null
                        """.trimIndent()
                    } else ""
                    runStepPropsScript("""
                        setprop debug.phantom.bid "${newIdentity.buildNumber}" 2>/dev/null
                        setprop persist.sys.phantom.bid "${newIdentity.buildNumber}" 2>/dev/null
                        $globalCmd
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Build Fingerprint",
                initialSubtitle = "Applying system build fingerprint...",
                completedSubtitle = newIdentity.buildFingerprint,
                durationMs = 110L,
                isEnabled = timerSettings.resetBuildFingerprint || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(buildFingerprint = newIdentity.buildFingerprint)
                    syncStepIdentityJson(workingIdentity)
                    val globalCmd = if (isGlobalSpoofEnabled) {
                        "${'$'}RP_BIN ro.build.fingerprint \"${newIdentity.buildFingerprint}\" 2>/dev/null"
                    } else ""
                    runStepPropsScript("""
                        setprop debug.phantom.fp "${newIdentity.buildFingerprint}" 2>/dev/null
                        setprop persist.sys.phantom.fp "${newIdentity.buildFingerprint}" 2>/dev/null
                        $globalCmd
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Kernel Version",
                initialSubtitle = "Spoofing Linux kernel signature...",
                completedSubtitle = newIdentity.kernelVersion,
                durationMs = 100L,
                isEnabled = timerSettings.resetKernel || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(kernelVersion = newIdentity.kernelVersion)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.kv "${newIdentity.kernelVersion}" 2>/dev/null
                        setprop persist.sys.phantom.kv "${newIdentity.kernelVersion}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Baseband",
                initialSubtitle = "Spoofing radio baseband firmware...",
                completedSubtitle = "Radio: ${newIdentity.basebandVersion}",
                durationMs = 100L,
                isEnabled = timerSettings.resetBaseband || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(basebandVersion = newIdentity.basebandVersion)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.bb "${newIdentity.basebandVersion}" 2>/dev/null
                        setprop persist.sys.phantom.bb "${newIdentity.basebandVersion}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Widevine ID",
                initialSubtitle = "Rotating DRM unique client ID...",
                completedSubtitle = "Widevine: ${newIdentity.widevineDrmId}",
                durationMs = 100L,
                isEnabled = timerSettings.resetWidevineDrmId || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(widevineDrmId = newIdentity.widevineDrmId)
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.drm "${newIdentity.widevineDrmId}" 2>/dev/null
                        setprop persist.sys.phantom.drm "${newIdentity.widevineDrmId}" 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Location",
                initialSubtitle = "Setting coordinates & GPS mock...",
                completedSubtitle = "Location set to ${newIdentity.locationName} (${newIdentity.latitude}, ${newIdentity.longitude})",
                durationMs = 125L,
                isEnabled = timerSettings.resetLocation || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(
                        locationName = newIdentity.locationName,
                        latitude = newIdentity.latitude,
                        longitude = newIdentity.longitude,
                        region = newIdentity.region,
                        localeTag = newIdentity.localeTag
                    )
                    syncStepIdentityJson(workingIdentity)
                    runStepPropsScript("""
                        setprop debug.phantom.loc "${newIdentity.locationName}" 2>/dev/null
                        setprop persist.sys.phantom.loc "${newIdentity.locationName}" 2>/dev/null
                        setprop debug.phantom.lat "${newIdentity.latitude}" 2>/dev/null
                        setprop persist.sys.phantom.lat "${newIdentity.latitude}" 2>/dev/null
                        setprop debug.phantom.lng "${newIdentity.longitude}" 2>/dev/null
                        setprop persist.sys.phantom.lng "${newIdentity.longitude}" 2>/dev/null
                        setprop debug.phantom.reg "${newIdentity.region}" 2>/dev/null
                        setprop persist.sys.phantom.reg "${newIdentity.region}" 2>/dev/null
                        setprop debug.phantom.ltag "${newIdentity.localeTag}" 2>/dev/null
                        setprop persist.sys.phantom.ltag "${newIdentity.localeTag}" 2>/dev/null
                        appops set $selfPkg android:mock_location allow 2>/dev/null
                        settings put secure mock_location 0 2>/dev/null
                    """.trimIndent())
                }
            ),
            IdentityVectorTask(
                name = "Timezone",
                initialSubtitle = "Updating system clock & timezone to ${newIdentity.timezone}...",
                completedSubtitle = run {
                    val formattedTime = try {
                        val sdf = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US)
                        sdf.timeZone = java.util.TimeZone.getTimeZone(newIdentity.timezone)
                        sdf.format(java.util.Date())
                    } catch (_: Throwable) { "" }
                    if (formattedTime.isNotBlank()) {
                        "Timezone set to ${newIdentity.timezone} (Clock: $formattedTime)"
                    } else {
                        "System Timezone set to ${newIdentity.timezone}"
                    }
                },
                durationMs = 140L,
                isEnabled = timerSettings.resetTimezone || forceAll,
                onExecute = {
                    val tzId = newIdentity.timezone
                    workingIdentity = workingIdentity.copy(timezone = tzId)
                    syncStepIdentityJson(workingIdentity)
                    try {
                        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(tzId))
                    } catch (_: Throwable) {}
                    runStepPropsScript("""
                        pm grant $selfPkg android.permission.SET_TIME_ZONE 2>/dev/null || true
                        settings put global auto_time 1 2>/dev/null
                        settings put system auto_time 1 2>/dev/null
                        settings put global auto_time_zone 0 2>/dev/null
                        settings put system auto_time_zone 0 2>/dev/null
                        cmd time_zone_detector set_auto_detection_enabled false 2>/dev/null || true
                        setprop debug.phantom.tz "$tzId" 2>/dev/null
                        setprop persist.sys.phantom.tz "$tzId" 2>/dev/null
                        setprop persist.sys.timezone "$tzId" 2>/dev/null
                        ${'$'}RP_BIN persist.sys.timezone "$tzId" 2>/dev/null || true
                        cmd alarm set-timezone "$tzId" 2>/dev/null || service call alarm 3 s16 "$tzId" 2>/dev/null || true
                        am broadcast -a android.intent.action.TIMEZONE_CHANGED --es time-zone "$tzId" -f 0x01000000 2>/dev/null || true
                        am broadcast -a android.intent.action.TIME_SET -f 0x01000000 2>/dev/null || true
                    """.trimIndent())
                    try {
                        val alarmManager = context?.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                        alarmManager?.setTimeZone(tzId)
                    } catch (_: Throwable) {}
                }
            ),
            IdentityVectorTask(
                name = "User Agent",
                initialSubtitle = "Updating WebView & HTTP User-Agent...",
                completedSubtitle = newIdentity.userAgent.take(56) + "...",
                durationMs = 110L,
                isEnabled = timerSettings.resetUserAgent || forceAll,
                onExecute = {
                    workingIdentity = workingIdentity.copy(userAgent = newIdentity.userAgent)
                    syncStepIdentityJson(workingIdentity)
                }
            ),
            // =========================================================================
            // 14 ADDITIONAL DEEP HARDWARE & KERNEL UNIQUENESS VECTORS (STEPS 27 TO 40)
            // =========================================================================
            run {
                val gsfSeed = kotlin.math.abs((newIdentity.androidId + newIdentity.serialNumber).hashCode().toLong())
                val gsfDec = (3500000000000000000L + (gsfSeed * 982451653L) % 500000000000000000L).toString()
                val gsfHex = java.lang.Long.toHexString(gsfDec.toLongOrNull() ?: 3849201948291048291L)
                IdentityVectorTask(
                    name = "GSF ID (Google Services)",
                    initialSubtitle = "Rotating 64-bit Google Services Framework token...",
                    completedSubtitle = "GSF ID: $gsfHex ($gsfDec)",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.gsf "$gsfDec" 2>/dev/null
                            setprop persist.sys.phantom.gsf "$gsfDec" 2>/dev/null
                        """.trimIndent())
                    }
                )
            },
            run {
                val appSetUuid = java.util.UUID.randomUUID().toString().lowercase()
                IdentityVectorTask(
                    name = "AppSet & Firebase ID",
                    initialSubtitle = "Regenerating developer AppSet & Firebase FID...",
                    completedSubtitle = "AppSet UUID: ${appSetUuid.take(18)}...",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.appset "$appSetUuid" 2>/dev/null
                            setprop persist.sys.phantom.appset "$appSetUuid" 2>/dev/null
                            rm -f /data/data/com.google.android.gms/shared_prefs/app_set_id_storage.xml /data/user_de/0/com.google.android.gms/shared_prefs/app_set_id_storage.xml 2>/dev/null || true
                        """.trimIndent())
                    }
                )
            },
            run {
                val hexChars = "0123456789abcdef"
                val chipDieSerial = "0x" + (1..16).map { hexChars.random() }.joinToString("")
                IdentityVectorTask(
                    name = "SoC Chip & Die Serial",
                    initialSubtitle = "Spoofing CPU silicon die & SoC serial number...",
                    completedSubtitle = "SoC Die Serial: $chipDieSerial",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        val globalCmd = if (isGlobalSpoofEnabled) {
                            "${'$'}RP_BIN ro.boot.cpuid \"$chipDieSerial\" 2>/dev/null || true"
                        } else ""
                        runStepPropsScript("""
                            setprop debug.phantom.chipid "$chipDieSerial" 2>/dev/null
                            setprop persist.sys.phantom.chipid "$chipDieSerial" 2>/dev/null
                            printf '%s\n' "$chipDieSerial" > /data/local/tmp/.phantom_soc_serial 2>/dev/null
                            chmod 644 /data/local/tmp/.phantom_soc_serial 2>/dev/null || true
                            $globalCmd
                        """.trimIndent())
                    }
                )
            },
            run {
                val alnum = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
                val boardSerial = "MB" + (1..12).map { alnum.random() }.joinToString("")
                val hwRev = "REV${(1..9).random()}.${(0..9).random()}"
                IdentityVectorTask(
                    name = "Motherboard & PCB Serial",
                    initialSubtitle = "Rotating OEM board & RIL hardware serials...",
                    completedSubtitle = "Board Serial: $boardSerial ($hwRev)",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        val globalCmd = if (isGlobalSpoofEnabled) {
                            """
                            ${'$'}RP_BIN ro.ril.oem.sno "$boardSerial" 2>/dev/null || true
                            ${'$'}RP_BIN ro.ril.oem.psno "$boardSerial" 2>/dev/null || true
                            ${'$'}RP_BIN ril.serialnumber "$boardSerial" 2>/dev/null || true
                            ${'$'}RP_BIN sys.serialnumber "$boardSerial" 2>/dev/null || true
                            ${'$'}RP_BIN ro.boot.hardware.revision "$hwRev" 2>/dev/null || true
                            ${'$'}RP_BIN ro.revision "$hwRev" 2>/dev/null || true
                            """.trimIndent()
                        } else ""
                        runStepPropsScript("""
                            setprop debug.phantom.board_ser "$boardSerial" 2>/dev/null
                            setprop persist.sys.phantom.board_ser "$boardSerial" 2>/dev/null
                            setprop debug.phantom.hw_rev "$hwRev" 2>/dev/null
                            setprop persist.sys.phantom.hw_rev "$hwRev" 2>/dev/null
                            $globalCmd
                        """.trimIndent())
                    }
                )
            },
            run {
                val bootUuid = java.util.UUID.randomUUID().toString().lowercase()
                IdentityVectorTask(
                    name = "Kernel Boot UUID",
                    initialSubtitle = "Rotating /proc/sys/kernel/random/boot_id...",
                    completedSubtitle = "Boot ID: $bootUuid",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.bootid "$bootUuid" 2>/dev/null
                            setprop persist.sys.phantom.bootid "$bootUuid" 2>/dev/null
                            printf '%s\n' "$bootUuid" > /data/local/tmp/.phantom_boot_id 2>/dev/null
                            chmod 644 /data/local/tmp/.phantom_boot_id 2>/dev/null || true
                        """.trimIndent())
                    }
                )
            },
            run {
                val hex = "0123456789abcdef"
                val flashCid = "150100" + (1..26).map { hex.random() }.joinToString("")
                val volUuid = (1..4).map { hex.uppercase().random() }.joinToString("") + "-" + (1..4).map { hex.uppercase().random() }.joinToString("")
                IdentityVectorTask(
                    name = "Storage Flash CID & UUID",
                    initialSubtitle = "Spoofing UFS/eMMC flash CID & partition UUID...",
                    completedSubtitle = "Flash CID: ${flashCid.take(16)}... (Vol: $volUuid)",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.diskid "$flashCid" 2>/dev/null
                            setprop persist.sys.phantom.diskid "$flashCid" 2>/dev/null
                            setprop debug.phantom.voluuid "$volUuid" 2>/dev/null
                            setprop persist.sys.phantom.voluuid "$volUuid" 2>/dev/null
                            printf '%s\n' "$flashCid" > /data/local/tmp/.phantom_mmc_cid 2>/dev/null
                            chmod 644 /data/local/tmp/.phantom_mmc_cid 2>/dev/null || true
                        """.trimIndent())
                    }
                )
            },
            run {
                val hex = "0123456789abcdef"
                val bssid = listOf("c8", "7f", "54", (1..2).map { hex.random() }.joinToString(""), (1..2).map { hex.random() }.joinToString(""), (1..2).map { hex.random() }.joinToString("")).joinToString(":")
                val routerPrefixes = listOf("TP-Link_5G", "ASUS_AX6000", "Netgear_Orbi", "Starlink_Mesh", "UniFi_Express", "HomeFiber_6E")
                val ssid = "${routerPrefixes.random()}_${(100..999).random()}"
                IdentityVectorTask(
                    name = "Wi-Fi Router BSSID & SSID",
                    initialSubtitle = "Rotating router hardware MAC & AP signature...",
                    completedSubtitle = "BSSID: $bssid (SSID: $ssid)",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.bssid "$bssid" 2>/dev/null
                            setprop persist.sys.phantom.bssid "$bssid" 2>/dev/null
                            setprop debug.phantom.ssid "$ssid" 2>/dev/null
                            setprop persist.sys.phantom.ssid "$ssid" 2>/dev/null
                        """.trimIndent())
                    }
                )
            },
            run {
                val cellCid = (1048576..268435455).random()
                val cellLac = (1000..65530).random()
                val cellPci = (1..503).random()
                IdentityVectorTask(
                    name = "Cell Tower CID & LAC",
                    initialSubtitle = "Updating cellular base station CID, TAC & PCI...",
                    completedSubtitle = "Tower CID: $cellCid | TAC/LAC: $cellLac | PCI: $cellPci",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        val cellSpec = "$cellCid,$cellLac,$cellPci"
                        runStepPropsScript("""
                            setprop debug.phantom.cell "$cellSpec" 2>/dev/null
                            setprop persist.sys.phantom.cell "$cellSpec" 2>/dev/null
                        """.trimIndent())
                    }
                )
            },
            run {
                val digits = "0123456789"
                val eid = "89049032" + (1..24).map { digits.random() }.joinToString("")
                val subId = (1..8).random()
                IdentityVectorTask(
                    name = "eSIM EID & Subscription ID",
                    initialSubtitle = "Rotating 32-digit eUICC EID & SIM card ID...",
                    completedSubtitle = "eSIM EID: ${eid.take(18)}... (SubID: $subId)",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.eid "$eid" 2>/dev/null
                            setprop persist.sys.phantom.eid "$eid" 2>/dev/null
                        """.trimIndent())
                    }
                )
            },
            run {
                val hex = "0123456789abcdef"
                val sensorSeed = (1..12).map { hex.random() }.joinToString("")
                IdentityVectorTask(
                    name = "Sensor Calibration Hash",
                    initialSubtitle = "Injecting unique MEMS gyro/accel bias signature...",
                    completedSubtitle = "MEMS Calibration Seed: 0x$sensorSeed",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.sensor "$sensorSeed" 2>/dev/null
                            setprop persist.sys.phantom.sensor "$sensorSeed" 2>/dev/null
                        """.trimIndent())
                    }
                )
            },
            run {
                val batPct = (62..94).random()
                val batMv = (3880..4330).random()
                val batTempTenths = (268..336).random()
                val batSpec = "$batPct,$batMv,$batTempTenths"
                IdentityVectorTask(
                    name = "Battery & Thermal Telemetry",
                    initialSubtitle = "Randomizing charge counter, voltage & temp...",
                    completedSubtitle = "Battery: $batPct% | ${batMv}mV | ${batTempTenths / 10.0}°C",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.bat "$batSpec" 2>/dev/null
                            setprop persist.sys.phantom.bat "$batSpec" 2>/dev/null
                        """.trimIndent())
                    }
                )
            },
            run {
                val fakeUptimeMins = (45..720).random()
                val fakeUptimeMs = fakeUptimeMins * 60_000L + (1000..59000).random()
                val installTs = System.currentTimeMillis() - (120_000L..900_000L).random()
                IdentityVectorTask(
                    name = "Uptime & Install Timestamp",
                    initialSubtitle = "Resetting boot elapsed clock & package install time...",
                    completedSubtitle = "Boot Uptime: ${fakeUptimeMins}m | Fresh Install Timestamp",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.uptime "$fakeUptimeMs,$installTs" 2>/dev/null
                            setprop persist.sys.phantom.uptime "$fakeUptimeMs,$installTs" 2>/dev/null
                        """.trimIndent())
                    }
                )
            },
            run {
                val hex = "0123456789abcdef"
                val audioSig = (1..12).map { hex.random() }.joinToString("")
                val framesPerBuf = listOf("192", "240", "256").random()
                IdentityVectorTask(
                    name = "Audio DSP & Codec Profile",
                    initialSubtitle = "Rotating hardware audio session & DSP signature...",
                    completedSubtitle = "Audio DSP: 48000Hz / ${framesPerBuf}f (ID: $audioSig)",
                    durationMs = 105L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.audio "$framesPerBuf,$audioSig" 2>/dev/null
                            setprop persist.sys.phantom.audio "$framesPerBuf,$audioSig" 2>/dev/null
                        """.trimIndent())
                    }
                )
            },
            run {
                val hex = "0123456789abcdef"
                val inputDescriptor = (1..40).map { hex.random() }.joinToString("")
                val bootCount = (12..140).random()
                IdentityVectorTask(
                    name = "Input Descriptor & WebGL Hash",
                    initialSubtitle = "Rotating touchscreen SHA-1 descriptor & WebGL seed...",
                    completedSubtitle = "Input SHA1: ${inputDescriptor.take(16)}... (Boot #$bootCount)",
                    durationMs = 110L,
                    isEnabled = true,
                    onExecute = {
                        runStepPropsScript("""
                            setprop debug.phantom.webgl "$inputDescriptor" 2>/dev/null
                            setprop persist.sys.phantom.webgl "$inputDescriptor" 2>/dev/null
                            settings put global boot_count $bootCount 2>/dev/null || true
                        """.trimIndent())
                    }
                )
            }
        )

        // Filter to ONLY the enabled Device Identity options so only active items are shown and executed
        val activeTasks = allVectorTasks.filter { it.isEnabled }
        val skippedCount = allVectorTasks.size - activeTasks.size
        val totalActive = activeTasks.size

        val steps = activeTasks.mapIndexed { idx, task ->
            ResetStepItem(
                stepNumber = idx + 1,
                name = task.name,
                subtitle = task.initialSubtitle,
                durationMs = task.durationMs,
                status = StepStatus.PENDING
            )
        }.toMutableList()

        var totalElapsed = 0L

        for (i in activeTasks.indices) {
            val task = activeTasks[i]
            val stepStartMs = System.currentTimeMillis()
            steps[i] = steps[i].copy(status = StepStatus.RUNNING)
            emit(
                ResetProgressState(
                    currentStepIndex = i,
                    totalSteps = totalActive,
                    steps = steps.map { it.copy() },
                    isComplete = false,
                    newIdentity = workingIdentity,
                    totalElapsedMs = totalElapsed,
                    logMessage = "Updating ${task.name}...",
                    executedCount = i,
                    skippedCount = skippedCount,
                    currentStepName = task.name,
                    currentStepSubtitle = task.initialSubtitle
                )
            )

            // Execute this step's real system/property/hook update while it is in RUNNING state
            task.onExecute()

            // Ensure visual loading pacing so each step visibly loads, completes, and only then moves to the next
            val stepElapsed = System.currentTimeMillis() - stepStartMs
            val minVisualMs = task.durationMs.coerceAtLeast(110L)
            if (stepElapsed < minVisualMs) {
                delay(minVisualMs - stepElapsed)
            }

            val actualDuration = (System.currentTimeMillis() - stepStartMs).coerceAtLeast(task.durationMs)
            totalElapsed += actualDuration
            steps[i] = steps[i].copy(
                status = StepStatus.SUCCESS,
                subtitle = task.completedSubtitle,
                durationMs = actualDuration
            )

            val isFinalStep = (i == activeTasks.size - 1)
            if (isFinalStep) {
                // 1. Clean all selected target app sandboxes FIRST (pm clear + wipe caches/prefs/databases/SSAID)
                cleanTargetSandboxesOnce()
                // 2. Write the brand-new .phantom_identity.json AFTER pm clear so it is never deleted by pm clear
                writeXposedIdentityFile(
                    identity = newIdentity,
                    context = context,
                    isGlobalSpoofEnabled = isGlobalSpoofEnabled,
                    targetPackageNames = allTargetPackages,
                    rootBypassPackageNames = rootBypassPkgs,
                    applyTimezoneBroadcast = false
                )
                // 3. Force-stop target apps again so they launch 100% cold with the new identity
                if (isRooted && safeTargetPackages.isNotEmpty()) {
                    val postKillCmd = safeTargetPackages.joinToString("\n") { pkg ->
                        "am force-stop $pkg 2>/dev/null; am kill $pkg 2>/dev/null; killall -9 $pkg 2>/dev/null || true"
                    }
                    com.example.data.device.RootChecker.executeRootCommand(postKillCmd)
                }
            }
            emit(
                ResetProgressState(
                    currentStepIndex = i,
                    totalSteps = totalActive,
                    steps = steps.map { it.copy() },
                    isComplete = isFinalStep,
                    newIdentity = if (isFinalStep) newIdentity else workingIdentity,
                    totalElapsedMs = totalElapsed,
                    logMessage = if (isFinalStep) "Reset complete ($totalActive/$totalActive OK)" else "Completed ${task.name}",
                    executedCount = i + 1,
                    skippedCount = skippedCount,
                    currentStepName = task.name,
                    currentStepSubtitle = task.completedSubtitle
                )
            )
        }

        val finalSuccessful = steps.count { it.status == StepStatus.SUCCESS }

        // If activeTasks was empty, ensure sandbox clean + identity write + isComplete = true are still executed
        if (activeTasks.isEmpty()) {
            cleanTargetSandboxesOnce()
            writeXposedIdentityFile(
                identity = newIdentity,
                context = context,
                isGlobalSpoofEnabled = isGlobalSpoofEnabled,
                targetPackageNames = allTargetPackages,
                rootBypassPackageNames = rootBypassPkgs,
                applyTimezoneBroadcast = false
            )
            if (isRooted && safeTargetPackages.isNotEmpty()) {
                val postKillCmd = safeTargetPackages.joinToString("\n") { pkg ->
                    "am force-stop $pkg 2>/dev/null; am kill $pkg 2>/dev/null; killall -9 $pkg 2>/dev/null || true"
                }
                com.example.data.device.RootChecker.executeRootCommand(postKillCmd)
            }
            emit(
                ResetProgressState(
                    currentStepIndex = 0,
                    totalSteps = totalActive,
                    steps = steps.map { it.copy() },
                    isComplete = true,
                    newIdentity = newIdentity,
                    totalElapsedMs = totalElapsed,
                    logMessage = "Reset complete ($finalSuccessful/$totalActive OK)",
                    executedCount = finalSuccessful,
                    skippedCount = skippedCount
                )
            )
        }
        } finally {
            isNetworkResetRunning = false
            markNetworkResetGraceWindow(35_000L)
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    fun writeXposedIdentityFile(
        identity: DeviceIdentity,
        context: Context? = null,
        isGlobalSpoofEnabled: Boolean = true,
        targetPackageNames: List<String> = emptyList(),
        removedPackageNames: List<String> = emptyList(),
        newlyAddedPackageNames: List<String> = emptyList(),
        rootBypassPackageNames: List<String>? = null,
        stepWalkPackageNames: List<String>? = null,
        speedHackPackageNames: List<String>? = null,
        adBypassPackageNames: List<String>? = null,
        applyTimezoneBroadcast: Boolean = true
    ) {
        try {
            val resetTs = System.currentTimeMillis()
            val resolvedRootBypassPkgs = rootBypassPackageNames ?: if (context != null) {
                try {
                    context.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                        .getStringSet("root_bypass_packages", emptySet())
                        ?.toList() ?: emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val resolvedStepWalkPkgs = stepWalkPackageNames ?: if (context != null) {
                try {
                    context.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                        .getStringSet("step_walk_packages", emptySet())
                        ?.toList() ?: emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val resolvedSpeedHackPkgs = speedHackPackageNames ?: if (context != null) {
                try {
                    context.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                        .getStringSet("speed_hack_packages", emptySet())
                        ?.toList() ?: emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val resolvedAdBypassPkgs = adBypassPackageNames ?: if (context != null) {
                try {
                    context.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                        .getStringSet("ad_bypass_packages", emptySet())
                        ?.toList() ?: emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val targetsArray = org.json.JSONArray()
            targetPackageNames.forEach { targetsArray.put(it) }

            val rootBypassArray = org.json.JSONArray()
            resolvedRootBypassPkgs.forEach { rootBypassArray.put(it) }

            val stepWalkArray = org.json.JSONArray()
            resolvedStepWalkPkgs.forEach { stepWalkArray.put(it) }

            val speedHackArray = org.json.JSONArray()
            resolvedSpeedHackPkgs.forEach { speedHackArray.put(it) }

            val adBypassArray = org.json.JSONArray()
            resolvedAdBypassPkgs.forEach { adBypassArray.put(it) }

            var licSystemEnabled = com.example.provider.PhantomIdentityProvider.liveLicenseSystemEnabled
            var licValid = com.example.provider.PhantomIdentityProvider.liveLicenseValid
            var licKey = com.example.provider.PhantomIdentityProvider.liveLicenseKey
            var licExpiresAtMs = com.example.provider.PhantomIdentityProvider.liveLicenseExpiresAtMs
            if (context != null) {
                try {
                    val licPrefs = context.getSharedPreferences("phantom_license_prefs", android.content.Context.MODE_PRIVATE)
                    licSystemEnabled = licPrefs.getBoolean("license_system_enabled", licSystemEnabled)
                    licValid = licPrefs.getBoolean("license_valid_state", licValid)
                    licKey = licPrefs.getString("license_key", licKey) ?: licKey
                    licExpiresAtMs = licPrefs.getLong("license_expiry_millis", licExpiresAtMs)
                } catch (_: Throwable) {}
            }

            val json = org.json.JSONObject().apply {
                put("globalSpoofEnabled", isGlobalSpoofEnabled)
                put("targetPackages", targetsArray)
                put("rootBypassPackages", rootBypassArray)
                put("stepWalkPackages", stepWalkArray)
                put("speedHackPackages", speedHackArray)
                put("adBypassPackages", adBypassArray)
                put("licenseSystemEnabled", licSystemEnabled)
                put("licenseValid", licValid)
                put("licenseKey", licKey)
                put("licenseExpiresAtMillis", licExpiresAtMs)
                put("deviceModel", identity.deviceModel)
                put("manufacturer", identity.manufacturer)
                put("buildFingerprint", identity.buildFingerprint)
                put("buildNumber", identity.buildNumber)
                put("deviceId", identity.deviceId)
                put("kernelVersion", identity.kernelVersion)
                put("socHardware", identity.socHardware)
                put("gpuRenderer", identity.gpuRenderer)
                put("gpuVersion", identity.gpuVersion)
                put("basebandVersion", identity.basebandVersion)
                put("simImsi", identity.simImsi)
                put("simIccid", identity.simIccid)
                put("widevineDrmId", identity.widevineDrmId)
                put("bootloaderVersion", identity.bootloaderVersion)
                put("serialNumber", identity.serialNumber)
                put("androidId", identity.androidId)
                put("advertisingId", identity.advertisingId)
                put("imei", identity.imei)
                put("carrier", identity.carrier)
                put("macAddress", identity.macAddress)
                put("bluetoothAddress", identity.bluetoothAddress)
                put("ipAddress", identity.ipAddress)
                put("osVersion", identity.osVersion)
                put("timezone", identity.timezone)
                put("latitude", identity.latitude)
                put("longitude", identity.longitude)
                put("locationName", identity.locationName)
                put("region", identity.region)
                put("localeTag", identity.localeTag)
                put("display", identity.display)
                put("userAgent", identity.userAgent)
                put("resetTimestamp", resetTs)
            }.toString()

            com.example.provider.PhantomIdentityProvider.cachedIdentityJson = json
            com.example.provider.PhantomIdentityProvider.syncGlobalIdentityToAllStandaloneVaults(context, json)

            val paths = arrayOf(
                "/data/local/tmp/phantom_identity.json",
                "/data/misc/phantom_identity.json",
                "/data/system/phantom_identity.json",
                "/data/adb/phantom_identity.json",
                "/sdcard/phantom_identity.json",
                "/sdcard/Download/phantom_identity.json"
            )
            for (p in paths) {
                try {
                    val f = java.io.File(p)
                    f.parentFile?.mkdirs()
                    f.writeText(json)
                    f.setReadable(true, false)
                    f.setWritable(true, false)
                } catch (_: Exception) {}
            }

            if (context != null) {
                try {
                    val internalFile = java.io.File(context.filesDir, "phantom_identity.json")
                    internalFile.writeText(json)
                    internalFile.setReadable(true, false)
                } catch (_: Exception) {}
            }

            val safeShellJson = json.replace("'", "'\\''")
            val newlyAddedCleanCmds = newlyAddedPackageNames
                .filter {
                    it.isNotBlank() && !it.contains(" ") && !it.contains(";") &&
                    it != "android" && it != "system" && it != context?.packageName &&
                    !com.example.data.device.DeviceAppScanner.isForbiddenTargetPackage(it) &&
                    !com.example.data.device.DeviceAppScanner.isCriticalSystemNoWipePackage(it)
                }
                .joinToString("\n") { pkg ->
                    """
                    am force-stop $pkg 2>/dev/null
                    am kill $pkg 2>/dev/null
                    pm clear --user 0 $pkg 2>/dev/null || pm clear $pkg 2>/dev/null || true
                    for BASE in "/data/data/$pkg" "/data/user/0/$pkg" "/data/user_de/0/$pkg"; do
                        if [ -d "${'$'}BASE" ]; then
                            rm -rf "${'$'}BASE/cache/"* "${'$'}BASE/cache/".[!.]* "${'$'}BASE/code_cache/"* "${'$'}BASE/code_cache/".[!.]* 2>/dev/null
                            rm -rf "${'$'}BASE/shared_prefs/"* "${'$'}BASE/databases/"* "${'$'}BASE/files/"* "${'$'}BASE/files/".[!.]* "${'$'}BASE/no_backup/"* "${'$'}BASE/no_backup/".[!.]* 2>/dev/null
                            rm -rf "${'$'}BASE/app_webview" "${'$'}BASE/app_chrome" "${'$'}BASE/app_textures" "${'$'}BASE/app_unity" "${'$'}BASE/il2cpp" "${'$'}BASE/.phantom_identity.json" 2>/dev/null
                        fi
                    done
                    rm -rf "/sdcard/Android/data/$pkg/cache/"* "/sdcard/Android/data/$pkg/files/"* "/storage/emulated/0/Android/data/$pkg/cache/"* "/storage/emulated/0/Android/data/$pkg/files/"* 2>/dev/null
                    rm -rf "/sdcard/Android/media/$pkg/"* "/storage/emulated/0/Android/media/$pkg/"* 2>/dev/null
                    """.trimIndent()
                }

            val allScopedPkgs = (targetPackageNames + resolvedRootBypassPkgs + resolvedStepWalkPkgs + resolvedSpeedHackPkgs + resolvedAdBypassPkgs).distinct()
            val rbSet = resolvedRootBypassPkgs.toSet()
            val tpSet = targetPackageNames.toSet()
            val wkSet = resolvedStepWalkPkgs.toSet()
            val spSet = resolvedSpeedHackPkgs.toSet()
            val adSet = resolvedAdBypassPkgs.toSet()
            val allKnownPkgs = (allScopedPkgs + removedPackageNames).distinct()

            // Compute consistent OEM model code and fingerprint tokens matching XposedEntry.resolveConsistentBuildIdentity
            val rawModel = identity.deviceModel.trim().ifBlank { "Pixel 8 Pro" }
            val lastModelToken = rawModel.substringAfterLast(" ").trim()
            val cleanModel = if (
                lastModelToken.startsWith("SM-", ignoreCase = true) ||
                lastModelToken.startsWith("CPH", ignoreCase = true) ||
                lastModelToken.startsWith("RMX", ignoreCase = true) ||
                lastModelToken.startsWith("V2", ignoreCase = true) ||
                lastModelToken.startsWith("XT", ignoreCase = true) ||
                (lastModelToken.length >= 8 && lastModelToken.any { it.isDigit() } && lastModelToken.any { it.isLetter() && !rawModel.startsWith("Pixel", ignoreCase = true) })
            ) {
                lastModelToken
            } else {
                rawModel
            }
            val runtimeOsRel = when (android.os.Build.VERSION.SDK_INT) {
                35 -> "15"
                34 -> "14"
                33 -> "13"
                32, 31 -> "12"
                30 -> "11"
                29 -> "10"
                else -> identity.osVersion.removePrefix("Android ").substringBefore(" ").trim().ifBlank { "14" }
            }
            val securityPatch = if (identity.osVersion.contains("Patch:")) {
                identity.osVersion.substringAfter("Patch:").trim().ifBlank { "2024-08-01" }
            } else {
                "2024-08-01"
            }
            var fpBrand = identity.manufacturer.trim().lowercase().ifBlank { "google" }
            var fpProduct = identity.deviceId.trim().ifBlank { cleanModel.replace(" ", "_").lowercase() }
            var fpDevice = fpProduct
            var fpBuildId = identity.buildNumber.trim().ifBlank { "UP1A.231005.007" }
            var fpIncremental = "11269751"
            val rawFp = identity.buildFingerprint.trim()
            if (rawFp.contains("/") && rawFp.contains(":")) {
                try {
                    val leftRight = rawFp.split(":")
                    val prefixParts = leftRight[0].split("/")
                    if (prefixParts.size >= 3) {
                        fpBrand = prefixParts[0].ifBlank { fpBrand }
                        fpProduct = prefixParts[1].ifBlank { fpProduct }
                        fpDevice = prefixParts[2].ifBlank { fpDevice }
                    }
                    if (leftRight.size >= 2) {
                        val midParts = leftRight[1].split("/")
                        if (midParts.size >= 3) {
                            fpBuildId = midParts[1].ifBlank { fpBuildId }
                            fpIncremental = midParts[2].ifBlank { fpIncremental }
                        }
                    }
                } catch (_: Throwable) {}
            }
            val consistentFp = "$fpBrand/$fpProduct/$fpDevice:$runtimeOsRel/$fpBuildId/$fpIncremental:user/release-keys"
            val buildSeed = kotlin.math.abs((cleanModel + fpBuildId + consistentFp).hashCode().toLong())
            val fakeBuildTimeSec = 1719792000L + ((buildSeed % 180L) * 86400L) + (buildSeed % 86400L)

            val perTargetRemoveCmds = removedPackageNames
                .filter { it.isNotBlank() && !it.contains(" ") && !it.contains(";") && !allScopedPkgs.contains(it) }
                .joinToString("\n") { pkg ->
                    val h36 = Integer.toUnsignedString(pkg.hashCode(), 36)
                    """
                    setprop debug.phantom.rb.$h36 "0" 2>/dev/null || true
                    setprop debug.phantom.tp.$h36 "0" 2>/dev/null || true
                    setprop debug.phantom.wk.$h36 "0" 2>/dev/null || true
                    setprop debug.phantom.sp.$h36 "0" 2>/dev/null || true
                    setprop debug.phantom.ad.$h36 "0" 2>/dev/null || true
                    setprop log.tag.phantom.rb.$h36 "0" 2>/dev/null || true
                    setprop log.tag.phantom.tp.$h36 "0" 2>/dev/null || true
                    setprop log.tag.phantom.wk.$h36 "0" 2>/dev/null || true
                    setprop log.tag.phantom.sp.$h36 "0" 2>/dev/null || true
                    setprop log.tag.phantom.ad.$h36 "0" 2>/dev/null || true
                    ${'$'}RP_BIN debug.phantom.rb.$h36 "0" 2>/dev/null || true
                    ${'$'}RP_BIN debug.phantom.tp.$h36 "0" 2>/dev/null || true
                    ${'$'}RP_BIN debug.phantom.wk.$h36 "0" 2>/dev/null || true
                    ${'$'}RP_BIN debug.phantom.sp.$h36 "0" 2>/dev/null || true
                    ${'$'}RP_BIN debug.phantom.ad.$h36 "0" 2>/dev/null || true
                    ${'$'}RP_BIN log.tag.phantom.rb.$h36 "0" 2>/dev/null || true
                    ${'$'}RP_BIN log.tag.phantom.tp.$h36 "0" 2>/dev/null || true
                    ${'$'}RP_BIN log.tag.phantom.wk.$h36 "0" 2>/dev/null || true
                    ${'$'}RP_BIN log.tag.phantom.sp.$h36 "0" 2>/dev/null || true
                    ${'$'}RP_BIN log.tag.phantom.ad.$h36 "0" 2>/dev/null || true
                    rm -f "/data/data/$pkg/.phantom_identity.json" "/data/user/0/$pkg/.phantom_identity.json" "/data/user_de/0/$pkg/.phantom_identity.json" 2>/dev/null
                    """.trimIndent()
                }

            val perTargetCopyCmds = allKnownPkgs
                .filter { it.isNotBlank() && !it.contains(" ") && !it.contains(";") }
                .joinToString("\n") { pkg ->
                    val h36 = Integer.toUnsignedString(pkg.hashCode(), 36)
                    val rbFlag = if (rbSet.contains(pkg)) "1" else "0"
                    val tpFlag = if (tpSet.contains(pkg)) "1" else "0"
                    val wkFlag = if (wkSet.contains(pkg)) "1" else "0"
                    val spFlag = if (spSet.contains(pkg)) "1" else "0"
                    val adFlag = if (adSet.contains(pkg)) "1" else "0"
                    val copyBlock = if (rbSet.contains(pkg) || tpSet.contains(pkg) || wkSet.contains(pkg) || spSet.contains(pkg) || adSet.contains(pkg)) {
                        """
                        for BASE in "/data/data/$pkg" "/data/user/0/$pkg" "/data/user/10/$pkg" "/data/user/999/$pkg" "/data/user_de/0/$pkg"; do
                            if [ -d "${'$'}BASE" ]; then
                                cp -f /data/local/tmp/phantom_identity.json "${'$'}BASE/.phantom_identity.json" 2>/dev/null || printf '%s' '$safeShellJson' > "${'$'}BASE/.phantom_identity.json" 2>/dev/null
                                OWN=${'$'}(stat -c '%u:%g' "${'$'}BASE" 2>/dev/null)
                                [ -n "${'$'}OWN" ] && chown "${'$'}OWN" "${'$'}BASE/.phantom_identity.json" 2>/dev/null
                                chmod 644 "${'$'}BASE/.phantom_identity.json" 2>/dev/null
                                chcon --reference="${'$'}BASE" "${'$'}BASE/.phantom_identity.json" 2>/dev/null || true
                            fi
                        done
                        """.trimIndent()
                    } else {
                        """
                        rm -f "/data/data/$pkg/.phantom_identity.json" "/data/user/0/$pkg/.phantom_identity.json" "/data/user_de/0/$pkg/.phantom_identity.json" 2>/dev/null
                        """.trimIndent()
                    }
                    """
                    setprop debug.phantom.rb.$h36 "$rbFlag" 2>/dev/null || true
                    setprop debug.phantom.tp.$h36 "$tpFlag" 2>/dev/null || true
                    setprop debug.phantom.wk.$h36 "$wkFlag" 2>/dev/null || true
                    setprop debug.phantom.sp.$h36 "$spFlag" 2>/dev/null || true
                    setprop debug.phantom.ad.$h36 "$adFlag" 2>/dev/null || true
                    setprop log.tag.phantom.rb.$h36 "$rbFlag" 2>/dev/null || true
                    setprop log.tag.phantom.tp.$h36 "$tpFlag" 2>/dev/null || true
                    setprop log.tag.phantom.wk.$h36 "$wkFlag" 2>/dev/null || true
                    setprop log.tag.phantom.sp.$h36 "$spFlag" 2>/dev/null || true
                    setprop log.tag.phantom.ad.$h36 "$adFlag" 2>/dev/null || true
                    ${'$'}RP_BIN debug.phantom.rb.$h36 "$rbFlag" 2>/dev/null || true
                    ${'$'}RP_BIN debug.phantom.tp.$h36 "$tpFlag" 2>/dev/null || true
                    ${'$'}RP_BIN debug.phantom.wk.$h36 "$wkFlag" 2>/dev/null || true
                    ${'$'}RP_BIN debug.phantom.sp.$h36 "$spFlag" 2>/dev/null || true
                    ${'$'}RP_BIN debug.phantom.ad.$h36 "$adFlag" 2>/dev/null || true
                    ${'$'}RP_BIN log.tag.phantom.rb.$h36 "$rbFlag" 2>/dev/null || true
                    ${'$'}RP_BIN log.tag.phantom.tp.$h36 "$tpFlag" 2>/dev/null || true
                    ${'$'}RP_BIN log.tag.phantom.wk.$h36 "$wkFlag" 2>/dev/null || true
                    ${'$'}RP_BIN log.tag.phantom.sp.$h36 "$spFlag" 2>/dev/null || true
                    ${'$'}RP_BIN log.tag.phantom.ad.$h36 "$adFlag" 2>/dev/null || true
                    $copyBlock
                    """.trimIndent()
                }

            val tz = identity.timezone
            val devCodename = identity.deviceId.ifBlank { cleanModel.replace(" ", "_") }
            val applyNativeBuildSpoof = isGlobalSpoofEnabled || targetPackageNames.isNotEmpty()
            val applyNativeRootCloak = isGlobalSpoofEnabled || resolvedRootBypassPkgs.isNotEmpty()
            val rootScript = """
                export PATH=/sbin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:/system/bin:/system/xbin:${'$'}PATH
                RP_BIN="setprop"
                for CANDIDATE in "/data/adb/magisk/resetprop" "/sbin/resetprop" "/data/adb/ksu/bin/resetprop" "resetprop"; do
                    if command -v "${'$'}CANDIDATE" >/dev/null 2>&1 || [ -x "${'$'}CANDIDATE" ]; then
                        RP_BIN="${'$'}CANDIDATE"
                        break
                    fi
                done

                # 1. Write shared JSON identity files FIRST so Xposed processes always read fresh data immediately
                chmod 755 /data/data/com.aistudio.phantomcore.qxvz /data/data/com.aistudio.phantomcore.qxvz/files /data/user/0/com.aistudio.phantomcore.qxvz /data/user/0/com.aistudio.phantomcore.qxvz/files 2>/dev/null || true
                chmod 644 /data/data/com.aistudio.phantomcore.qxvz/files/phantom_identity.json /data/user/0/com.aistudio.phantomcore.qxvz/files/phantom_identity.json 2>/dev/null || true
                mkdir -p /data/local/tmp 2>/dev/null
                chmod 777 /data/local/tmp 2>/dev/null || true
                printf '%s' '$safeShellJson' > /data/local/tmp/phantom_identity.json 2>/dev/null
                chmod 666 /data/local/tmp/phantom_identity.json 2>/dev/null
                chcon u:object_r:system_file:s0 /data/local/tmp/phantom_identity.json 2>/dev/null || true
                printf '%s' '$safeShellJson' > /data/misc/phantom_identity.json 2>/dev/null
                chmod 666 /data/misc/phantom_identity.json 2>/dev/null
                chcon u:object_r:system_file:s0 /data/misc/phantom_identity.json 2>/dev/null || true
                printf '%s' '$safeShellJson' > /data/system/phantom_identity.json 2>/dev/null
                chmod 666 /data/system/phantom_identity.json 2>/dev/null
                printf '%s' '$safeShellJson' > /data/adb/phantom_identity.json 2>/dev/null
                chmod 666 /data/adb/phantom_identity.json 2>/dev/null
                printf '%s' '$safeShellJson' > /sdcard/phantom_identity.json 2>/dev/null
                chmod 666 /sdcard/phantom_identity.json 2>/dev/null

                # 2. Ensure Flight Mode (Airplane Mode) is OFF without bouncing active connectivity, and self-heal rt_tables if needed
                if [ "${'$'}(settings get global airplane_mode_on 2>/dev/null)" = "1" ]; then
                    cmd connectivity airplane-mode disable 2>/dev/null || true
                    settings put global airplane_mode_on 0 2>/dev/null
                    am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false 2>/dev/null || true
                    svc wifi enable 2>/dev/null || true
                    svc data enable 2>/dev/null || true
                fi
                if [ ! -f /data/misc/net/rt_tables ]; then
                    mkdir -p /data/misc/net 2>/dev/null || true
                    printf "255\tlocal\n254\tmain\n253\tdefault\n0\tunspec\n" > /data/misc/net/rt_tables 2>/dev/null || true
                    chmod 644 /data/misc/net/rt_tables 2>/dev/null || true
                fi
                settings put secure android_id ${identity.androidId} 2>/dev/null
                settings put secure bluetooth_address ${identity.bluetoothAddress.lowercase()} 2>/dev/null
                settings put global adb_enabled 0 2>/dev/null
                settings put global development_settings_enabled 0 2>/dev/null
                settings put secure mock_location 0 2>/dev/null
                settings put global auto_time 1 2>/dev/null
                settings put system auto_time 1 2>/dev/null
                if [ -n "$tz" ] && [ "${if (applyTimezoneBroadcast) "1" else "0"}" = "1" ]; then
                    pm grant com.aistudio.phantomcore.qxvz android.permission.SET_TIME_ZONE 2>/dev/null || true
                    settings put global auto_time_zone 0 2>/dev/null
                    settings put system auto_time_zone 0 2>/dev/null
                    cmd time_zone_detector set_auto_detection_enabled false 2>/dev/null || true
                    setprop persist.sys.timezone "$tz" 2>/dev/null
                    ${'$'}RP_BIN persist.sys.timezone "$tz" 2>/dev/null || true
                    cmd alarm set-timezone "$tz" 2>/dev/null || service call alarm 3 s16 "$tz" 2>/dev/null || true
                    am broadcast -a android.intent.action.TIMEZONE_CHANGED --es time-zone "$tz" -f 0x01000000 2>/dev/null || true
                    am broadcast -a android.intent.action.TIME_SET -f 0x01000000 2>/dev/null || true
                fi

                # 3. Write all identity parameters to in-memory SystemProperties (including log.tag.phantom.* which is readable by all SELinux domains)
                for PFX in "debug.phantom" "persist.sys.phantom" "log.tag.phantom"; do
                    setprop ${'$'}{PFX}.aid "${identity.androidId}" 2>/dev/null
                    setprop ${'$'}{PFX}.adv "${identity.advertisingId}" 2>/dev/null
                    setprop ${'$'}{PFX}.imei "${identity.imei}" 2>/dev/null
                    setprop ${'$'}{PFX}.drm "${identity.widevineDrmId}" 2>/dev/null
                    setprop ${'$'}{PFX}.ser "${identity.serialNumber}" 2>/dev/null
                    setprop ${'$'}{PFX}.mac "${identity.macAddress}" 2>/dev/null
                    setprop ${'$'}{PFX}.bt "${identity.bluetoothAddress}" 2>/dev/null
                    setprop ${'$'}{PFX}.ip "${identity.ipAddress}" 2>/dev/null
                    setprop ${'$'}{PFX}.mod "${identity.deviceModel}" 2>/dev/null
                    setprop ${'$'}{PFX}.mfr "${identity.manufacturer}" 2>/dev/null
                    setprop ${'$'}{PFX}.fp "${identity.buildFingerprint}" 2>/dev/null
                    setprop ${'$'}{PFX}.bid "${identity.buildNumber}" 2>/dev/null
                    setprop ${'$'}{PFX}.did "$devCodename" 2>/dev/null
                    setprop ${'$'}{PFX}.kv "${identity.kernelVersion}" 2>/dev/null
                    setprop ${'$'}{PFX}.soc "${identity.socHardware}" 2>/dev/null
                    setprop ${'$'}{PFX}.gpu "${identity.gpuRenderer}" 2>/dev/null
                    setprop ${'$'}{PFX}.bb "${identity.basebandVersion}" 2>/dev/null
                    setprop ${'$'}{PFX}.bl "${identity.bootloaderVersion}" 2>/dev/null
                    setprop ${'$'}{PFX}.os "${identity.osVersion}" 2>/dev/null
                    setprop ${'$'}{PFX}.disp "${identity.display}" 2>/dev/null
                    setprop ${'$'}{PFX}.tz "${identity.timezone}" 2>/dev/null
                    setprop ${'$'}{PFX}.loc "${identity.locationName}" 2>/dev/null
                    setprop ${'$'}{PFX}.lat "${identity.latitude}" 2>/dev/null
                    setprop ${'$'}{PFX}.lng "${identity.longitude}" 2>/dev/null
                    setprop ${'$'}{PFX}.car "${identity.carrier}" 2>/dev/null
                    setprop ${'$'}{PFX}.imsi "${identity.simImsi}" 2>/dev/null
                    setprop ${'$'}{PFX}.iccid "${identity.simIccid}" 2>/dev/null
                    setprop ${'$'}{PFX}.reg "${identity.region}" 2>/dev/null
                    setprop ${'$'}{PFX}.ltag "${identity.localeTag}" 2>/dev/null
                    setprop ${'$'}{PFX}.global "${if (isGlobalSpoofEnabled) "1" else "0"}" 2>/dev/null
                    setprop ${'$'}{PFX}.rbpkgs "${resolvedRootBypassPkgs.take(15).joinToString(",").ifEmpty { "__NONE__" }}" 2>/dev/null
                    setprop ${'$'}{PFX}.tpkgs "${targetPackageNames.take(15).joinToString(",").ifEmpty { "__NONE__" }}" 2>/dev/null
                    setprop ${'$'}{PFX}.wkpkgs "${resolvedStepWalkPkgs.take(15).joinToString(",").ifEmpty { "__NONE__" }}" 2>/dev/null
                    setprop ${'$'}{PFX}.sppkgs "${resolvedSpeedHackPkgs.take(15).joinToString(",").ifEmpty { "__NONE__" }}" 2>/dev/null
                    setprop ${'$'}{PFX}.adpkgs "${resolvedAdBypassPkgs.take(15).joinToString(",").ifEmpty { "__NONE__" }}" 2>/dev/null
                    setprop ${'$'}{PFX}.lic_en "${if (licSystemEnabled) "1" else "0"}" 2>/dev/null
                    setprop ${'$'}{PFX}.lic_val "${if (licValid) "1" else "0"}" 2>/dev/null
                    setprop ${'$'}{PFX}.lic_exp "$licExpiresAtMs" 2>/dev/null
                    setprop ${'$'}{PFX}.ts "$resetTs" 2>/dev/null
                done

                # Clean newly added packages FIRST, then copy .phantom_identity.json into each scoped app's data dir so pm clear never deletes it!
                $newlyAddedCleanCmds
                $perTargetRemoveCmds
                $perTargetCopyCmds

                magisk --denylist disable 2>/dev/null || true
                magiskhide --disable 2>/dev/null || true

                # Restore real baseband property if previously altered so rild, com.android.phone, and wirelesssettings never fail
                REAL_BB=${'$'}(getprop ro.boot.baseband 2>/dev/null | tr -d '\n\r')
                [ -z "${'$'}REAL_BB" ] && REAL_BB=${'$'}(grep -h -m1 "^ro.baseband=" /vendor/build.prop /system/build.prop /odm/etc/build.prop 2>/dev/null | head -n1 | cut -d= -f2- | tr -d '\n\r')
                if [ -n "${'$'}REAL_BB" ]; then
                    CUR_BB=${'$'}(getprop gsm.version.baseband 2>/dev/null | tr -d '\n\r')
                    case "${'$'}CUR_BB" in
                        g5300*|g5400*|g520*|shannon*|q_g5*|m_g6*)
                            ${'$'}RP_BIN gsm.version.baseband "${'$'}REAL_BB" 2>/dev/null || true
                            ;;
                    esac
                fi

                if [ "${if (applyNativeRootCloak) "1" else "0"}" = "1" ]; then
                    ${'$'}RP_BIN ro.build.tags "release-keys" 2>/dev/null
                    ${'$'}RP_BIN ro.system.build.tags "release-keys" 2>/dev/null
                    ${'$'}RP_BIN ro.vendor.build.tags "release-keys" 2>/dev/null
                    ${'$'}RP_BIN ro.product.build.tags "release-keys" 2>/dev/null
                    ${'$'}RP_BIN ro.odm.build.tags "release-keys" 2>/dev/null
                    ${'$'}RP_BIN ro.build.type "user" 2>/dev/null
                    ${'$'}RP_BIN ro.system.build.type "user" 2>/dev/null
                    ${'$'}RP_BIN ro.vendor.build.type "user" 2>/dev/null
                    ${'$'}RP_BIN ro.product.build.type "user" 2>/dev/null
                    ${'$'}RP_BIN ro.odm.build.type "user" 2>/dev/null
                    ${'$'}RP_BIN ro.debuggable "0" 2>/dev/null
                    ${'$'}RP_BIN ro.secure "1" 2>/dev/null
                    ${'$'}RP_BIN ro.adb.secure "1" 2>/dev/null
                    ${'$'}RP_BIN ro.boot.verifiedbootstate "green" 2>/dev/null
                    ${'$'}RP_BIN ro.boot.flash.locked "1" 2>/dev/null
                    ${'$'}RP_BIN ro.boot.vbmeta.device_state "locked" 2>/dev/null
                    ${'$'}RP_BIN ro.boot.veritymode "enforcing" 2>/dev/null
                    ${'$'}RP_BIN ro.warranty_bit "0" 2>/dev/null
                    ${'$'}RP_BIN ro.boot.warranty_bit "0" 2>/dev/null
                    ${'$'}RP_BIN ro.kernel.qemu "0" 2>/dev/null
                    ${'$'}RP_BIN ro.boot.qemu "0" 2>/dev/null
                fi

                if [ "${if (applyNativeBuildSpoof) "1" else "0"}" = "1" ]; then
                    for PART in "" "system." "vendor." "odm." "product." "system_ext." "bootimage."; do
                        ${'$'}RP_BIN "ro.product.${'$'}{PART}model" "$cleanModel" 2>/dev/null
                        ${'$'}RP_BIN "ro.product.${'$'}{PART}manufacturer" "${identity.manufacturer}" 2>/dev/null
                        ${'$'}RP_BIN "ro.product.${'$'}{PART}brand" "$fpBrand" 2>/dev/null
                        ${'$'}RP_BIN "ro.product.${'$'}{PART}name" "$fpProduct" 2>/dev/null
                        ${'$'}RP_BIN "ro.product.${'$'}{PART}device" "$fpDevice" 2>/dev/null
                        ${'$'}RP_BIN "ro.${'$'}{PART}build.fingerprint" "$consistentFp" 2>/dev/null
                        ${'$'}RP_BIN "ro.${'$'}{PART}build.id" "$fpBuildId" 2>/dev/null
                        ${'$'}RP_BIN "ro.${'$'}{PART}build.display.id" "$fpBuildId.$fpIncremental" 2>/dev/null
                        ${'$'}RP_BIN "ro.${'$'}{PART}build.version.incremental" "$fpIncremental" 2>/dev/null
                        ${'$'}RP_BIN "ro.${'$'}{PART}build.version.release" "$runtimeOsRel" 2>/dev/null
                        ${'$'}RP_BIN "ro.${'$'}{PART}build.version.release_or_codename" "$runtimeOsRel" 2>/dev/null
                    done
                    ${'$'}RP_BIN ro.build.product "$fpProduct" 2>/dev/null
                    ${'$'}RP_BIN ro.build.version.security_patch "$securityPatch" 2>/dev/null
                    ${'$'}RP_BIN ro.build.user "android-build" 2>/dev/null
                    ${'$'}RP_BIN ro.build.host "abfarm-release-rbe-64-0001" 2>/dev/null
                    ${'$'}RP_BIN ro.build.date.utc "$fakeBuildTimeSec" 2>/dev/null
                    ${'$'}RP_BIN ro.serialno "${identity.serialNumber}" 2>/dev/null
                    ${'$'}RP_BIN ro.boot.serialno "${identity.serialNumber}" 2>/dev/null
                    if [ -n "${identity.bootloaderVersion}" ]; then
                        ${'$'}RP_BIN ro.bootloader "${identity.bootloaderVersion}" 2>/dev/null
                        ${'$'}RP_BIN ro.boot.bootloader "${identity.bootloaderVersion}" 2>/dev/null
                    fi
                    settings put global device_name "$cleanModel" 2>/dev/null
                    settings put secure bluetooth_name "$cleanModel" 2>/dev/null
                    killall -9 com.google.android.gms.unstable 2>/dev/null || true
                else
                    for PROP in ro.product.brand ro.product.manufacturer ro.product.device ro.product.model ro.product.name ro.build.product ro.soc.model; do
                        REAL_VAL=${'$'}(grep -h -m1 "^${'$'}{PROP}=" /odm/etc/build.prop /vendor/build.prop /product/etc/build.prop /system/build.prop 2>/dev/null | head -n1 | cut -d= -f2- | tr -d '\n\r')
                        if [ -n "${'$'}REAL_VAL" ]; then
                            ${'$'}RP_BIN "${'$'}PROP" "${'$'}REAL_VAL" 2>/dev/null || true
                        fi
                    done
                fi
                am force-stop com.android.settings 2>/dev/null || true
            """.trimIndent()
            com.example.data.device.RootChecker.executeRootCommand(rootScript)

            // Ensure LSPosed scope DB and in-memory cache always include required system scopes and selected target apps
            if (context != null) {
                com.example.data.device.LspScopeSyncCli.syncTargetScopeWithLsposed(
                    context = context,
                    selectedTargetPackages = allScopedPkgs,
                    removedTargetPackages = removedPackageNames,
                    newlyAddedTargetPackages = newlyAddedPackageNames
                )
            }
            
            // For local process TimeZone update if possible
            if (tz.isNotBlank() && applyTimezoneBroadcast) {
                try {
                    val alarmManager = context?.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                    alarmManager?.setTimeZone(tz)
                    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(tz))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun readSavedIdentity(context: Context?, fallback: DeviceIdentity): DeviceIdentity {
        val candidateFiles = mutableListOf<java.io.File>()
        if (context != null) {
            candidateFiles.add(java.io.File(context.filesDir, "phantom_identity.json"))
        }
        candidateFiles.addAll(
            listOf(
                java.io.File("/data/local/tmp/phantom_identity.json"),
                java.io.File("/data/misc/phantom_identity.json"),
                java.io.File("/data/system/phantom_identity.json"),
                java.io.File("/data/adb/phantom_identity.json"),
                java.io.File("/sdcard/phantom_identity.json"),
                java.io.File("/sdcard/Download/phantom_identity.json")
            )
        )
        for (file in candidateFiles) {
            try {
                if (file.exists() && file.canRead()) {
                    val text = file.readText()
                    if (text.isNotBlank()) {
                        val json = org.json.JSONObject(text)
                        val savedModel = json.optString("deviceModel", "")
                        if (savedModel.isNotBlank()) {
                            return fallback.copy(
                                deviceModel = json.optString("deviceModel", fallback.deviceModel),
                                manufacturer = json.optString("manufacturer", fallback.manufacturer),
                                buildFingerprint = json.optString("buildFingerprint", fallback.buildFingerprint),
                                buildNumber = json.optString("buildNumber", fallback.buildNumber),
                                deviceId = json.optString("deviceId", fallback.deviceId),
                                kernelVersion = json.optString("kernelVersion", fallback.kernelVersion),
                                socHardware = json.optString("socHardware", fallback.socHardware),
                                gpuRenderer = json.optString("gpuRenderer", fallback.gpuRenderer),
                                gpuVersion = json.optString("gpuVersion", fallback.gpuVersion),
                                basebandVersion = json.optString("basebandVersion", fallback.basebandVersion),
                                simImsi = json.optString("simImsi", fallback.simImsi),
                                simIccid = json.optString("simIccid", fallback.simIccid),
                                widevineDrmId = json.optString("widevineDrmId", fallback.widevineDrmId),
                                bootloaderVersion = json.optString("bootloaderVersion", fallback.bootloaderVersion),
                                serialNumber = json.optString("serialNumber", fallback.serialNumber),
                                androidId = json.optString("androidId", fallback.androidId),
                                advertisingId = json.optString("advertisingId", fallback.advertisingId),
                                imei = json.optString("imei", fallback.imei),
                                carrier = json.optString("carrier", fallback.carrier),
                                macAddress = json.optString("macAddress", fallback.macAddress),
                                bluetoothAddress = json.optString("bluetoothAddress", fallback.bluetoothAddress),
                                ipAddress = json.optString("ipAddress", fallback.ipAddress),
                                osVersion = json.optString("osVersion", fallback.osVersion),
                                timezone = json.optString("timezone", fallback.timezone),
                                latitude = json.optDouble("latitude", fallback.latitude),
                                longitude = json.optDouble("longitude", fallback.longitude),
                                locationName = json.optString("locationName", fallback.locationName),
                                region = json.optString("region", fallback.region),
                                localeTag = json.optString("localeTag", fallback.localeTag),
                                display = json.optString("display", fallback.display),
                                userAgent = json.optString("userAgent", fallback.userAgent)
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return fallback
    }
}
