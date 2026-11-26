package com.example.hook

import android.location.Location
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TimeZone

class XposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private val speedControllerHookedInProcess = java.util.concurrent.atomic.AtomicBoolean(false)
        private val stepSimulationHookedInProcess = java.util.concurrent.atomic.AtomicBoolean(false)
        private val locationHookedInProcess = java.util.concurrent.atomic.AtomicBoolean(false)
        private val adBypassHookedInProcess = java.util.concurrent.atomic.AtomicBoolean(false)
        private val deoptimizedAppClasses = java.util.Collections.synchronizedSet(HashSet<String>())

        @Volatile
        private var liveAdBypassState: Boolean? = null

        @Volatile
        private var liveRootBypassState: Boolean? = null

        @Volatile
        private var livePollerPkg: String = ""

        private fun isVirtualCloneContainerPkg(name: String): Boolean {
            if (name.isBlank()) return false
            return name == "com.clone.android.dual.space" ||
                name.contains("dual.space", ignoreCase = true) ||
                name == "com.vphonegaga.titan" ||
                name == "com.vmos.pro" ||
                name == "com.vmos.glb" ||
                name.startsWith("com.lbe.parallel") ||
                name.startsWith("com.parallel.space") ||
                name.startsWith("com.excelliance.") ||
                name.startsWith("com.ludashi.") ||
                name.startsWith("com.polestar.") ||
                name.startsWith("io.va.") ||
                name.startsWith("io.virtualapp")
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        XposedBridge.log("PhantomCore Xposed: Zygote Initialized")
    }

    private val isInternalFileAccess = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    @Volatile
    private var activeIdentityResetTs: Long = 0L

    private fun readFreshSysProp(key: String, fallbackVal: String = ""): String {
        val propVal = readSysPropInternal(key)
        if (propVal.isBlank()) return fallbackVal
        if (fallbackVal.isBlank()) return propVal
        val propTs = readSysPropInternal("persist.sys.phantom.ts").toLongOrNull() ?: 0L
        return if (activeIdentityResetTs <= 0L || (propTs > 0L && propTs >= activeIdentityResetTs)) {
            propVal
        } else {
            fallbackVal
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        val procName = lpparam.processName ?: pkg
        val uid = android.os.Process.myUid()
        val appId = uid % 100000

        // 0. NEVER hook system_server (procName == "system" or "android") or any networking/radio/wifi process,
        // even when System Framework is checked in LSPosed and sub-packages (e.g. com.android.location.fused, com.oplus.nhs) load inside system_server!
        if (procName == "system" || procName == "android" ||
            procName.contains("wirelesssettings", ignoreCase = true) ||
            procName.contains("network", ignoreCase = true) ||
            procName.contains("connectivity", ignoreCase = true) ||
            procName.contains("wifi", ignoreCase = true) ||
            procName.contains("phone", ignoreCase = true) ||
            procName.contains("telephony", ignoreCase = true) ||
            procName.contains("telecom", ignoreCase = true) ||
            procName.contains("ril", ignoreCase = true) ||
            procName.contains("ims", ignoreCase = true) ||
            procName.contains("tethering", ignoreCase = true) ||
            procName.contains("captiveportal", ignoreCase = true) ||
            procName.contains("wpa", ignoreCase = true) ||
            procName.contains("netd", ignoreCase = true) ||
            procName.contains("bluetooth", ignoreCase = true)
        ) {
            return
        }

        // 1. NEVER hook our own app, built standalone .phantom APKs, root managers, core OS system_server, or Play Integrity hardware attestation unstable process
        if (pkg == "com.example" || pkg.startsWith("com.example.") ||
            pkg.contains("phantomcore", ignoreCase = true) ||
            pkg.contains("com.aistudio.ghostdevice", ignoreCase = true) ||
            pkg.startsWith("com.aistudio.") ||
            pkg.endsWith(".ghostdevice") || pkg.contains(".ghostdevice.") ||
            pkg.endsWith(".phantom") || pkg.contains(".phantom.") ||
            procName == "com.example" || procName.startsWith("com.example.") ||
            procName.contains("phantomcore", ignoreCase = true) ||
            procName.contains("com.aistudio.ghostdevice", ignoreCase = true) ||
            procName.startsWith("com.aistudio.") ||
            procName.endsWith(".ghostdevice") || procName.contains(".ghostdevice.") ||
            procName.endsWith(".phantom") || procName.contains(".phantom.") ||
            pkg == "android" || pkg == "system" ||
            pkg == "com.android.systemui" ||
            pkg == "com.android.se" ||
            pkg == "com.android.nfc" ||
            pkg == "com.android.bluetooth" ||
            pkg == "com.android.shell" ||
            pkg.contains("wirelesssettings", ignoreCase = true) ||
            pkg.contains("networkstack", ignoreCase = true) ||
            pkg.contains("captiveportal", ignoreCase = true) ||
            pkg.contains("tethering", ignoreCase = true) ||
            pkg.contains("imsservice", ignoreCase = true) ||
            pkg.startsWith("com.google.android.wifi") ||
            pkg.startsWith("com.google.android.connectivity") ||
            pkg.startsWith("com.google.android.networkstack") ||
            pkg.startsWith("com.qualcomm.") ||
            pkg.startsWith("org.codeaurora.") ||
            pkg.startsWith("vendor.qti.") ||
            pkg.startsWith("com.qti.") ||
            pkg.startsWith("com.mediatek.") ||
            pkg.startsWith("com.sec.ims") ||
            pkg.startsWith("com.sec.epdg") ||
            pkg.startsWith("com.shannon.") ||
            procName.contains("unstable", ignoreCase = true) ||
            pkg.contains("magisk", ignoreCase = true) ||
            pkg.contains("lsposed", ignoreCase = true) ||
            pkg.contains("edxposed", ignoreCase = true) ||
            pkg.contains("kernelsu", ignoreCase = true) ||
            pkg.contains("supersu", ignoreCase = true)
        ) {
            return
        }

        // 1b. Skip double-hooking any Main Apk Replace standalone APK that already runs StandaloneRuntimeEngine internally
        if (pkg != "com.android.settings" && pkg != "com.google.android.gms" && pkg != "com.google.android.gsf") {
            val isEmbeddedStandaloneApk = try {
                val srcDir = lpparam.appInfo?.sourceDir
                !srcDir.isNullOrBlank() && java.util.zip.ZipFile(srcDir).use { zip ->
                    zip.getEntry("assets/phantom_standalone_config.json") != null
                }
            } catch (_: Throwable) {
                false
            }
            if (isEmbeddedStandaloneApk) {
                return
            }
        }

        val isSettingsPackage = (pkg == "com.android.settings" && procName == "com.android.settings")
        val isGmsPackage = (pkg == "com.google.android.gms" || pkg == "com.google.android.gsf")

        // Load active spoofed identity from shared config (fallback to empty JSONObject so Root & Clone Cloak always works!)
        val identity = loadIdentity(pkg) ?: JSONObject()
        val isGlobalPhoneMode = identity.optBoolean("globalSpoofEnabled", false) ||
            readSysPropInternal("persist.sys.phantom.global") == "1"

        // Determine if this app ONLY has the "Root" (Root Restrict) button turned ON without being selected for identity reset
        val rootBypassSet = HashSet<String>()
        val rbArr = identity.optJSONArray("rootBypassPackages")
        if (rbArr != null) {
            for (i in 0 until rbArr.length()) {
                val p = rbArr.optString(i)
                if (p.isNotBlank()) rootBypassSet.add(p)
            }
        }
        val rbProp = readSysPropInternal("persist.sys.phantom.rbpkgs")
        if (rbProp.isNotBlank()) {
            rbProp.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "__NONE__" }.forEach { rootBypassSet.add(it) }
        }

        val targetPkgSet = HashSet<String>()
        val tpArr = identity.optJSONArray("targetPackages")
        if (tpArr != null) {
            for (i in 0 until tpArr.length()) {
                val p = tpArr.optString(i)
                if (p.isNotBlank()) targetPkgSet.add(p)
            }
        }
        val tpProp = readSysPropInternal("persist.sys.phantom.tpkgs")
        if (tpProp.isNotBlank()) {
            tpProp.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "__NONE__" }.forEach { targetPkgSet.add(it) }
        }

        val stepWalkSet = HashSet<String>()
        val wkArr = identity.optJSONArray("stepWalkPackages")
        if (wkArr != null) {
            for (i in 0 until wkArr.length()) {
                val p = wkArr.optString(i)
                if (p.isNotBlank()) stepWalkSet.add(p)
            }
        }
        val wkProp = readSysPropInternal("persist.sys.phantom.wkpkgs")
        if (wkProp.isNotBlank()) {
            wkProp.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "__NONE__" }.forEach { stepWalkSet.add(it) }
        }

        val speedHackSet = HashSet<String>()
        val spArr = identity.optJSONArray("speedHackPackages")
        if (spArr != null) {
            for (i in 0 until spArr.length()) {
                val p = spArr.optString(i)
                if (p.isNotBlank()) speedHackSet.add(p)
            }
        }
        val spProp = readSysPropInternal("persist.sys.phantom.sppkgs")
        if (spProp.isNotBlank()) {
            spProp.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "__NONE__" }.forEach { speedHackSet.add(it) }
        }

        val adBypassSet = HashSet<String>()
        val adArr = identity.optJSONArray("adBypassPackages")
        if (adArr != null) {
            for (i in 0 until adArr.length()) {
                val p = adArr.optString(i)
                if (p.isNotBlank()) adBypassSet.add(p)
            }
        }
        val adProp = readSysPropInternal("persist.sys.phantom.adpkgs")
        if (adProp.isNotBlank()) {
            adProp.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "__NONE__" }.forEach { adBypassSet.add(it) }
        }

        val pkgHash = Integer.toUnsignedString(pkg.hashCode(), 36)
        val rbFlagProp = readSysPropInternal("debug.phantom.rb.$pkgHash").ifBlank {
            readSysPropInternal("log.tag.phantom.rb.$pkgHash")
        }
        val tpFlagProp = readSysPropInternal("debug.phantom.tp.$pkgHash").ifBlank {
            readSysPropInternal("log.tag.phantom.tp.$pkgHash")
        }
        val wkFlagProp = readSysPropInternal("debug.phantom.wk.$pkgHash").ifBlank {
            readSysPropInternal("log.tag.phantom.wk.$pkgHash")
        }
        val spFlagProp = readSysPropInternal("debug.phantom.sp.$pkgHash").ifBlank {
            readSysPropInternal("log.tag.phantom.sp.$pkgHash")
        }
        val adFlagProp = readSysPropInternal("debug.phantom.ad.$pkgHash").ifBlank {
            readSysPropInternal("log.tag.phantom.ad.$pkgHash")
        }

        val fromLiveProvider = identity.optBoolean("fromLiveProvider", false)
        val isTargetApp = if (fromLiveProvider && identity.has("targetPackages")) {
            targetPkgSet.contains(pkg)
        } else when (tpFlagProp) {
            "1" -> true
            "0" -> false
            else -> targetPkgSet.contains(pkg)
        }
        val isRootBypassApp = if (fromLiveProvider && identity.has("rootBypassPackages")) {
            rootBypassSet.contains(pkg)
        } else when (rbFlagProp) {
            "1" -> true
            "0" -> false
            else -> rootBypassSet.contains(pkg)
        }
        val isStepWalkApp = if (fromLiveProvider && identity.has("stepWalkPackages")) {
            stepWalkSet.contains(pkg)
        } else when (wkFlagProp) {
            "1" -> true
            "0" -> false
            else -> stepWalkSet.contains(pkg)
        }
        val isSpeedHackApp = if (fromLiveProvider && identity.has("speedHackPackages")) {
            speedHackSet.contains(pkg)
        } else when (spFlagProp) {
            "1" -> true
            "0" -> false
            else -> speedHackSet.contains(pkg)
        }
        val isAdBypassApp = if (fromLiveProvider && identity.has("adBypassPackages")) {
            adBypassSet.contains(pkg)
        } else when (adFlagProp) {
            "1" -> true
            "0" -> false
            else -> adBypassSet.contains(pkg)
        }
        val isPotentialWalkTarget = isStepWalkApp || isSpeedHackApp
        val hasDeviceIdentity = identity.optString("deviceModel", "").isNotBlank() ||
            identity.optString("androidId", "").isNotBlank()

        // Never hook low-level system UIDs (< 10000) unless it is Settings or explicitly selected by the user
        if (!isSettingsPackage && appId < 10000 && !isTargetApp && !isRootBypassApp && !isStepWalkApp && !isSpeedHackApp && !isAdBypassApp) {
            return
        }

        // For Google Play Services (when not explicitly selected as a full Target App), only hook Telephony/SIM & Phone Number
        // when Global Mode is active so GMS Phone Verification sees active SIM without touching unselected apps.
        if (isGmsPackage && !isTargetApp && !isRootBypassApp && !isStepWalkApp && !isSpeedHackApp && !isAdBypassApp) {
            if (isGlobalPhoneMode && hasDeviceIdentity) {
                try {
                    val simVals = computeSimStatusValues(identity)
                    hookTelephony(lpparam.classLoader, identity.optString("imei"), simVals, isSettingsPackage = false)
                } catch (_: Throwable) {}
            }
            return
        }

        // If this is Settings (About Phone), apply spoofing only when Global mode or Settings itself is selected as target
        if (isSettingsPackage && !isGlobalPhoneMode && !isTargetApp) {
            return
        }

        // Check connected License Key validity & expiration for scoped Target Apps
        val licSysProp = readSysPropInternal("persist.sys.phantom.lic_en").ifBlank {
            readSysPropInternal("debug.phantom.lic_en").ifBlank { readSysPropInternal("log.tag.phantom.lic_en") }
        }
        val licValProp = readSysPropInternal("persist.sys.phantom.lic_val").ifBlank {
            readSysPropInternal("debug.phantom.lic_val").ifBlank { readSysPropInternal("log.tag.phantom.lic_val") }
        }
        val licExpProp = readSysPropInternal("persist.sys.phantom.lic_exp").ifBlank {
            readSysPropInternal("debug.phantom.lic_exp").ifBlank { readSysPropInternal("log.tag.phantom.lic_exp") }
        }
        val licSystemEnabled = when (licSysProp) {
            "1" -> true
            "0" -> false
            else -> identity.optBoolean("licenseSystemEnabled", false)
        }
        val licValidFlag = when (licValProp) {
            "1" -> true
            "0" -> false
            else -> identity.optBoolean("licenseValid", true)
        }
        val licExpiresAtMs = licExpProp.toLongOrNull() ?: identity.optLong("licenseExpiresAtMillis", 0L)
        val isLicenseCurrentlyExpired = licSystemEnabled &&
            (!licValidFlag || (licExpiresAtMs > 0L && System.currentTimeMillis() > licExpiresAtMs))

        if (!isSettingsPackage && !isGmsPackage && isLicenseCurrentlyExpired &&
            (isTargetApp || isRootBypassApp || isStepWalkApp || isSpeedHackApp || isAdBypassApp || isGlobalPhoneMode)
        ) {
            hookLicenseExpiredDialogForTargetApp(identity.optString("licenseKey", ""))
            return
        }

        // Always attach the live Speed Controller and Step/Motion hooks ONCE per process to all 3rd-party user apps (appId >= 10000)
        // BEFORE checking target spoofing filters! When speed is 1.0x and walk is idle, these hooks are a 100% transparent no-op,
        // and when the user adjusts speed in the Floating Controller HUD on ANY app (e.g. Simple Stopwatch, point counters, games),
        // it responds immediately without requiring manual checkbox selection in the Apps tab.
        // Always preserve the real target package (`lpparam.packageName`) inside Dual Space / Virtual Containers
        // (`com.clone.android.dual.space:p0` etc.) instead of overwriting `pkg` with the container host package.
        val hostPkg = try {
            val rawProc = procName.substringBefore(":").trim()
            if (pkg.isNotBlank() && !isVirtualCloneContainerPkg(pkg)) {
                pkg
            } else if (rawProc.isNotBlank() && rawProc != "system" && rawProc != "android" && rawProc.contains(".") && !isVirtualCloneContainerPkg(rawProc)) {
                rawProc
            } else {
                pkg
            }
        } catch (_: Throwable) { pkg }
        if (!isSettingsPackage && !isGmsPackage && appId >= 10000) {
            if (locationHookedInProcess.compareAndSet(false, true)) {
                try {
                    hookLocation(
                        lpparam.classLoader,
                        identity.optDouble("latitude", 0.0),
                        identity.optDouble("longitude", 0.0),
                        isStepWalkEnabled = isStepWalkApp,
                        pkg = hostPkg
                    )
                } catch (_: Throwable) {}
            }
            if (stepSimulationHookedInProcess.compareAndSet(false, true)) {
                try {
                    hookStepAndMotionSimulation(lpparam.classLoader, pkg = hostPkg, initialStepEnabled = isStepWalkApp)
                } catch (_: Throwable) {}
            }
            if (speedControllerHookedInProcess.compareAndSet(false, true)) {
                try {
                    hookAppSpeedController(lpparam.classLoader, pkg = hostPkg, initialSpeedEnabled = isSpeedHackApp)
                } catch (_: Throwable) {}
            }
            if (adBypassHookedInProcess.compareAndSet(false, true)) {
                try {
                    hookInstantAdBypassAndCompletion(lpparam.classLoader, pkg = hostPkg, initialAdBypassEnabled = isAdBypassApp)
                } catch (_: Throwable) {}
            }
        }

        val hasKnownTargetConfig = (tpArr != null) || tpProp.isNotBlank() || tpFlagProp.isNotBlank()
        val shouldSpoofIdentity = isGlobalPhoneMode || isTargetApp || (!isSettingsPackage && !hasKnownTargetConfig && !isRootBypassApp && !isPotentialWalkTarget && !isAdBypassApp)
        val enableRootBypass = !isSettingsPackage && (isRootBypassApp || isGlobalPhoneMode)

        // If ALL toggles + checkbox are OFF and Global Mode is OFF, do not hook Device Identity or Root Cloak at all.
        if (!isSettingsPackage && !shouldSpoofIdentity && !enableRootBypass) {
            return
        }

        try {
            val simVals = computeSimStatusValues(identity)
            val cpuVals = computeCpuHardwareValues(identity, simVals.seed)

            // Deterministic realistic fallback identifiers if identity JSON could not be read across SELinux/VM boundaries
            val effectiveAndroidId = identity.optString("androidId").ifBlank {
                "%08x%08x".format(simVals.seed xor 0x5a3c9e12, (simVals.seed * 31 + pkg.hashCode()))
            }
            val effectiveImei = identity.optString("imei").ifBlank {
                "35482910${1000000 + (simVals.seed % 8999999)}"
            }
            val effectiveAdvId = identity.optString("advertisingId").ifBlank {
                java.util.UUID(simVals.seed.toLong() xor 0x6c8e9cf570932bd5L, pkg.hashCode().toLong() xor 0x4b1d7f9a2e6c8a13L).toString()
            }
            val effectiveModel = identity.optString("deviceModel").ifBlank { "Pixel 8 Pro" }
            val effectiveMac = identity.optString("macAddress").ifBlank {
                val b = simVals.seed
                "02:%02x:%02x:%02x:%02x:%02x".format((b shr 2) and 0xFF, (b shr 6) and 0xFF, (b shr 10) and 0xFF, (b shr 14) and 0xFF, (b shr 18) and 0xFF)
            }
            val effectiveBt = identity.optString("bluetoothAddress").ifBlank {
                val b = simVals.seed xor 0x374921
                "02:%02x:%02x:%02x:%02x:%02x".format((b shr 3) and 0xFF, (b shr 7) and 0xFF, (b shr 11) and 0xFF, (b shr 15) and 0xFF, (b shr 19) and 0xFF)
            }

            if (enableRootBypass) {
                hookRootAndEnvironmentCloak(
                    lpparam.classLoader,
                    pkg,
                    cpuVals,
                    identity.optLong("resetTimestamp", 0L),
                    enableRootBypass = true
                )
            }

            if (shouldSpoofIdentity) {
                // 1. Hook Build fields (Model, Manufacturer, Fingerprint, Serial, OS Version, Display, SOC_MODEL, SOC_MANUFACTURER, HARDWARE)
                hookBuildProperties(identity, cpuVals, isSettingsPackage, enableRootBypass, pkg)

                // 1b. Hook CPU /proc/cpuinfo, /sys/devices/system/cpu frequencies, governors, cores & Device Info CPU views
                hookCpuAndProcInfo(lpparam.classLoader, cpuVals, pkg, isSettingsPackage)

                // 2. Hook Settings.Secure / Global / System for android_id and hiding adb_enabled / development_settings_enabled
                hookAndroidId(
                    lpparam.classLoader,
                    effectiveAndroidId,
                    enableRootBypass,
                    effectiveAdvId,
                    effectiveModel,
                    effectiveBt
                )

                // 3. Hook TelephonyManager for IMEI, Carrier, Phone Number, IMSI, and ICCID (without touching Settings data/network state switches)
                hookTelephony(
                    lpparam.classLoader,
                    effectiveImei,
                    simVals,
                    isSettingsPackage
                )

                // 4. Only hook WifiInfo, NetworkInterface, Timezone, Locale, Location & WebView inside non-Settings target apps
                // so Settings Wi-Fi picker, Mobile Data switch, and regulatory country code are never disrupted
                if (!isSettingsPackage) {
                    hookWifi(
                        lpparam.classLoader,
                        effectiveMac,
                        identity.optString("ipAddress"),
                        simVals.seed
                    )
                    hookNetworkInterface(lpparam.classLoader)
                    hookBluetooth(lpparam.classLoader, effectiveBt, effectiveModel)
                    hookTimezone(lpparam.classLoader, identity.optString("timezone"))
                    val countryCode = simVals.countryCode
                    hookLocale(lpparam.classLoader, countryCode)
                    hookAdvertisingId(
                        lpparam.classLoader,
                        effectiveAdvId,
                        identity.optString("widevineDrmId", ""),
                        effectiveAndroidId,
                        pkg
                    )
                    hookGpuRenderer(lpparam.classLoader, cpuVals)
                    if (locationHookedInProcess.compareAndSet(false, true)) {
                        hookLocation(
                            lpparam.classLoader,
                            identity.optDouble("latitude", 0.0),
                            identity.optDouble("longitude", 0.0),
                            isStepWalkEnabled = isStepWalkApp,
                            pkg = pkg
                        )
                    }
                    if (stepSimulationHookedInProcess.compareAndSet(false, true)) {
                        hookStepAndMotionSimulation(lpparam.classLoader, pkg = hostPkg, initialStepEnabled = isStepWalkApp)
                    }
                    if (speedControllerHookedInProcess.compareAndSet(false, true)) {
                        hookAppSpeedController(lpparam.classLoader, pkg = hostPkg, initialSpeedEnabled = isSpeedHackApp)
                    }
                    hookWebviewUserAgent(
                        lpparam.classLoader,
                        identity.optString("userAgent"),
                        identity.optString("kernelVersion"),
                        enableRootBypass,
                        identity,
                        cpuVals,
                        countryCode,
                        pkg
                    )
                    hookDeepHardwareUniqueness(lpparam.classLoader, identity, simVals.seed, pkg)
                }

                // 7. Hook SystemProperties.get / getInt / getBoolean
                hookSystemProperties(
                    lpparam.classLoader,
                    identity,
                    cpuVals,
                    isSettingsPackage,
                    onlySecurityProps = false,
                    enableRootBypass = enableRootBypass
                )

                // 8. Explicitly hook Settings app About Phone preference controllers & SIM status dialog
                if (isSettingsPackage) {
                    hookSettingsControllers(lpparam.classLoader, identity, simVals)
                }
            } else if (enableRootBypass) {
                // Only Root Hide is ON (Target Identity checkbox is OFF): cloak security/emulator SystemProperties only
                hookSystemProperties(
                    lpparam.classLoader,
                    identity,
                    cpuVals,
                    isSettingsPackage = false,
                    onlySecurityProps = true,
                    enableRootBypass = true
                )
            }

            XposedBridge.log("PhantomCore: Hooked $pkg (Identity=$shouldSpoofIdentity, RootBypass=$enableRootBypass, Stp=$isStepWalkApp, Spd=$isSpeedHackApp, AdSkip=$isAdBypassApp)")

            // 9. Extra Hiding: ServiceManager, ActivityManager (only when Root Hide or Identity Spoof is active)
            if (!isSettingsPackage && enableRootBypass) {
                hookServiceManager(lpparam.classLoader)
                hookActivityManager(lpparam.classLoader, pkg, enableRootBypass = true)
            }
        } catch (t: Throwable) {
            XposedBridge.log("PhantomCore hook error on $pkg: ${t.message}")
        }
    }

    private fun hookAppOps(classLoader: ClassLoader) {
        try {
            val appOpsClass = XposedHelpers.findClass("android.app.AppOpsManager", classLoader) ?: return
            val opHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val m = param.method as? java.lang.reflect.Method ?: return
                    if (m.returnType != Int::class.javaPrimitiveType && m.returnType != Int::class.javaObjectType) return
                    val op = param.args.getOrNull(0)
                    // op 58 is OP_MOCK_LOCATION ("android:mock_location"). Anti-cheat expects MODE_ERRORED or MODE_IGNORED.
                    if (op == 58 || op == "android:mock_location" || op == "mock_location") {
                        param.result = android.app.AppOpsManager.MODE_ERRORED
                    }
                }
            }
            hookAllMethodsCompat(appOpsClass, "checkOp", opHook)
            hookAllMethodsCompat(appOpsClass, "checkOpNoThrow", opHook)
            hookAllMethodsCompat(appOpsClass, "noteOp", opHook)
            hookAllMethodsCompat(appOpsClass, "noteOpNoThrow", opHook)
        } catch (_: Throwable) {}
    }

    private fun hookServiceManager(classLoader: ClassLoader) {
        try {
            val smClass = XposedHelpers.findClass("android.os.ServiceManager", classLoader)
            XposedHelpers.findAndHookMethod(smClass, "getService", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args[0] as? String ?: return
                    if (name.contains("magisk") || name.contains("lsposed") || name.contains("xposed")) {
                        param.result = null
                    }
                }
            })
            XposedHelpers.findAndHookMethod(smClass, "checkService", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args[0] as? String ?: return
                    if (name.contains("magisk") || name.contains("lsposed") || name.contains("xposed")) {
                        param.result = null
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookActivityManager(classLoader: ClassLoader, currentPkg: String, enableRootBypass: Boolean = true) {
        try {
            val amClass = XposedHelpers.findClass("android.app.ActivityManager", classLoader)
            
            hookAllMethodsCompat(amClass, "getRunningAppProcesses", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? List<*> ?: return
                    val filtered = list.filter { item ->
                        val procInfo = item as? android.app.ActivityManager.RunningAppProcessInfo
                        val name = procInfo?.processName
                        !isHiddenPackageName(name, currentPkg, enableRootBypass)
                    }
                    param.result = filtered
                }
            })

            hookAllMethodsCompat(amClass, "getRecentTasks", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? List<*> ?: return
                    val filtered = list.filter { item ->
                        val taskInfo = item as? android.app.ActivityManager.RecentTaskInfo
                        val baseIntent = taskInfo?.baseIntent
                        val pkgName = baseIntent?.component?.packageName
                        !isHiddenPackageName(pkgName, currentPkg, enableRootBypass)
                    }
                    param.result = filtered
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookStackTrace(classLoader: ClassLoader) {
        // Intentionally no-op: hooking Throwable.getStackTrace causes ART runtime stack overflows and crashes
    }

    private data class CpuHardwareRandomValues(
        val processorFull: String,
        val socManufacturer: String,
        val socModelShort: String,
        val cpuHardware: String,
        val boardPlatform: String,
        val governor: String,
        val coresCount: Int,
        val supportedAbis: Array<String>,
        val coreMinKhz: IntArray,
        val coreMaxKhz: IntArray,
        val coreCurKhz: IntArray,
        val archClusterSummary: String,
        val freqRangeSummary: String,
        val procCpuInfoText: String,
        val topCardSpecSummary: String,
        val gpuRenderer: String,
        val gpuVendor: String,
        val gpuVersion: String
    )

    private fun computeCpuHardwareValues(identity: JSONObject, seed: Int): CpuHardwareRandomValues {
        data class SocSpecTemplate(
            val mfr: String,
            val modelShort: String,
            val hwCode: String,
            val board: String,
            val fabNm: String,
            val archDesc: String,
            val cacheDesc: String,
            val ramDesc: String,
            val defaultGpu: String,
            val clusterCounts: IntArray,
            val clusterMinMhz: IntArray,
            val clusterMaxMhz: IntArray,
            val cpuParts: IntArray
        )

        val templates = listOf(
            SocSpecTemplate(
                mfr = "Qualcomm",
                modelShort = "Snapdragon 8 Gen 3 (SM8650-AB)",
                hwCode = "SM8650-AB",
                board = "pineapple",
                fabNm = "4 nm (TSMC N4P)",
                archDesc = "64-Bit ARMv9.2-A (1+3+2+2)",
                cacheDesc = "L2 4 MB / L3 12 MB",
                ramDesc = "LPDDR5X 4800 MHz",
                defaultGpu = "Adreno (TM) 750 @ 903MHz",
                clusterCounts = intArrayOf(1, 3, 2, 2),
                clusterMinMhz = intArrayOf(614, 556, 499, 384),
                clusterMaxMhz = intArrayOf(3302, 3148, 2956, 2265),
                cpuParts = intArrayOf(0xd81, 0xd80, 0xd80, 0xd46)
            ),
            SocSpecTemplate(
                mfr = "Google",
                modelShort = "Tensor G4 (GS401)",
                hwCode = "GS401-ZumaPro",
                board = "zuma_pro",
                fabNm = "4 nm (Samsung 4LPP+)",
                archDesc = "64-Bit ARMv9.2-A (1+3+4)",
                cacheDesc = "L2 3.5 MB / SLC 8 MB",
                ramDesc = "LPDDR5X 4200 MHz",
                defaultGpu = "Mali-G715 MC10 @ 940MHz",
                clusterCounts = intArrayOf(1, 3, 4),
                clusterMinMhz = intArrayOf(700, 520, 324),
                clusterMaxMhz = intArrayOf(3105, 2600, 1950),
                cpuParts = intArrayOf(0xd81, 0xd80, 0xd46)
            ),
            SocSpecTemplate(
                mfr = "MediaTek",
                modelShort = "Dimensity 9300+ (MT6989)",
                hwCode = "MT6989Z/CZA",
                board = "mt6989",
                fabNm = "4 nm (TSMC N4P)",
                archDesc = "64-Bit ARMv9.2-A All-Big (1+3+4)",
                cacheDesc = "L2 6 MB / L3 10 MB / SLC 8 MB",
                ramDesc = "LPDDR5T 4800 MHz",
                defaultGpu = "Immortalis-G720 MC12 @ 1300MHz",
                clusterCounts = intArrayOf(1, 3, 4),
                clusterMinMhz = intArrayOf(800, 650, 500),
                clusterMaxMhz = intArrayOf(3400, 2850, 2000),
                cpuParts = intArrayOf(0xd81, 0xd81, 0xd80)
            ),
            SocSpecTemplate(
                mfr = "Samsung",
                modelShort = "Exynos 2400 (S5E9945)",
                hwCode = "S5E9945-Erd",
                board = "s5e9945",
                fabNm = "4 nm (Samsung 4LPP+)",
                archDesc = "64-Bit ARMv9.2-A (1+2+3+2)",
                cacheDesc = "L2 5 MB / L3 8 MB",
                ramDesc = "LPDDR5X 4266 MHz",
                defaultGpu = "Xclipse 940 (AMD RDNA 3) @ 1095MHz",
                clusterCounts = intArrayOf(1, 2, 3, 2),
                clusterMinMhz = intArrayOf(750, 650, 550, 400),
                clusterMaxMhz = intArrayOf(3206, 2900, 2592, 1958),
                cpuParts = intArrayOf(0xd81, 0xd80, 0xd80, 0xd46)
            ),
            SocSpecTemplate(
                mfr = "Qualcomm",
                modelShort = "Snapdragon 8s Gen 3 (SM8635)",
                hwCode = "SM8635-Cliffs",
                board = "cliff",
                fabNm = "4 nm (TSMC N4)",
                archDesc = "64-Bit ARMv9-A (1+4+3)",
                cacheDesc = "L2 3.5 MB / L3 8 MB",
                ramDesc = "LPDDR5X 4200 MHz",
                defaultGpu = "Adreno (TM) 735 @ 1100MHz",
                clusterCounts = intArrayOf(1, 4, 3),
                clusterMinMhz = intArrayOf(595, 499, 380),
                clusterMaxMhz = intArrayOf(3072, 2841, 2054),
                cpuParts = intArrayOf(0xd81, 0xd80, 0xd46)
            ),
            SocSpecTemplate(
                mfr = "MediaTek",
                modelShort = "Dimensity 8300 Ultra (MT6897)",
                hwCode = "MT6897Z/CZA",
                board = "mt6897",
                fabNm = "4 nm (TSMC N4)",
                archDesc = "64-Bit ARMv9-A (1+3+4)",
                cacheDesc = "L2 3 MB / L3 4 MB",
                ramDesc = "LPDDR5X 4266 MHz",
                defaultGpu = "Mali-G615 MC6 @ 1400MHz",
                clusterCounts = intArrayOf(1, 3, 4),
                clusterMinMhz = intArrayOf(720, 600, 420),
                clusterMaxMhz = intArrayOf(3350, 3000, 2200),
                cpuParts = intArrayOf(0xd47, 0xd47, 0xd46)
            ),
            SocSpecTemplate(
                mfr = "Google",
                modelShort = "Tensor G3 (GS301)",
                hwCode = "GS301-Zuma",
                board = "zuma",
                fabNm = "4 nm (Samsung 4LPP)",
                archDesc = "64-Bit ARMv9-A (1+4+4)",
                cacheDesc = "L2 3 MB / SLC 8 MB",
                ramDesc = "LPDDR5X 4200 MHz",
                defaultGpu = "Mali-G715s MC10 @ 890MHz",
                clusterCounts = intArrayOf(1, 4, 3),
                clusterMinMhz = intArrayOf(680, 500, 340),
                clusterMaxMhz = intArrayOf(2910, 2370, 1700),
                cpuParts = intArrayOf(0xd4e, 0xd47, 0xd46)
            ),
            SocSpecTemplate(
                mfr = "Qualcomm",
                modelShort = "Snapdragon 8+ Gen 2 (SM8550-AC)",
                hwCode = "SM8550-Kalama",
                board = "kalama",
                fabNm = "4 nm (TSMC N4)",
                archDesc = "64-Bit ARMv9-A (1+4+3)",
                cacheDesc = "L2 4 MB / L3 8 MB",
                ramDesc = "LPDDR5X 4200 MHz",
                defaultGpu = "Adreno (TM) 740 @ 719MHz",
                clusterCounts = intArrayOf(1, 4, 3),
                clusterMinMhz = intArrayOf(576, 480, 364),
                clusterMaxMhz = intArrayOf(3360, 2820, 2040),
                cpuParts = intArrayOf(0xd4e, 0xd47, 0xd46)
            )
        )

        val rawSoc = identity.optString("socHardware", "").trim()
        val lowerSoc = rawSoc.lowercase()
        val matchedTpl = when {
            lowerSoc.contains("tensor g4") || lowerSoc.contains("g5400") -> templates[1]
            lowerSoc.contains("tensor g3") || lowerSoc.contains("g5300") || lowerSoc.contains("tensor g2") -> templates[6]
            lowerSoc.contains("dimensity 9300") || lowerSoc.contains("mt6989") || lowerSoc.contains("dimensity 9200") -> templates[2]
            lowerSoc.contains("dimensity") || lowerSoc.contains("mt68") -> templates[5]
            lowerSoc.contains("exynos") || lowerSoc.contains("s5e") -> templates[3]
            lowerSoc.contains("8s gen 3") || lowerSoc.contains("7+ gen") || lowerSoc.contains("sm7") -> templates[4]
            lowerSoc.contains("8 gen 2") || lowerSoc.contains("sm8550") -> templates[7]
            lowerSoc.contains("8 gen 3") || lowerSoc.contains("sm8650") -> templates[(seed % 2).let { if (it == 0) 0 else 4 }]
            else -> templates[seed % templates.size]
        }

        // Apply deterministic per-identity frequency jitter so every reset produces unique MHz / GHz numbers
        val jitterStepMhz = ((seed % 9) - 4) * 14
        val revSuffix = (10 + (seed % 89)).toString()
        val socMfr = matchedTpl.mfr
        val socShort = if (rawSoc.isNotBlank() && !rawSoc.startsWith(socMfr, ignoreCase = true)) {
            rawSoc
        } else {
            matchedTpl.modelShort
        }
        val processorFull = "$socMfr $socShort"
        val cpuHardware = "${matchedTpl.hwCode}-R$revSuffix"
        val boardPlatform = matchedTpl.board

        val governors = listOf("schedutil", "energy_aware", "interactive", "performance", "ondemand", "conservative")
        val governor = governors[seed % governors.size]

        val coreMinKhz = IntArray(8)
        val coreMaxKhz = IntArray(8)
        val coreCurKhz = IntArray(8)
        val coreParts = IntArray(8)

        val archLines = mutableListOf<String>()
        val freqLines = mutableListOf<String>()

        // Populate clusters from highest-frequency cluster to lowest-frequency cluster
        var coreIdx = 7
        for (c in matchedTpl.clusterCounts.indices) {
            val count = matchedTpl.clusterCounts[c]
            val minMhz = (matchedTpl.clusterMinMhz[c] + (jitterStepMhz / 2)).coerceIn(300, 900)
            val maxMhz = (matchedTpl.clusterMaxMhz[c] + jitterStepMhz).coerceIn(1750, 3650)
            val minKhz = minMhz * 1000
            val maxKhz = maxMhz * 1000
            val curKhz = ((minMhz + ((maxMhz - minMhz) * (35 + (seed % 45)) / 100)) * 1000)
            val part = matchedTpl.cpuParts.getOrElse(c) { 0xd80 }

            val ghzStr = String.format(Locale.US, "%.2f GHz", maxMhz / 1000.0)
            archLines.add("$count x $ghzStr")
            freqLines.add("$count x $minMhz MHz - $maxMhz MHz")

            for (k in 0 until count) {
                if (coreIdx >= 0) {
                    coreMinKhz[coreIdx] = minKhz
                    coreMaxKhz[coreIdx] = maxKhz
                    coreCurKhz[coreIdx] = curKhz
                    coreParts[coreIdx] = part
                    coreIdx--
                }
            }
        }

        val archClusterSummary = archLines.joinToString("\n")
        val freqRangeSummary = freqLines.joinToString("\n")

        val bogoBase = 38 + (seed % 24)
        val bogoFrac = 10 + ((seed / 3) % 88)
        val cpuInfoSb = StringBuilder()
        cpuInfoSb.append("Processor\t: AArch64 Processor rev ${seed % 8} (aarch64)\n")
        for (i in 0 until 8) {
            cpuInfoSb.append("processor\t: $i\n")
            cpuInfoSb.append("model name\t: $processorFull\n")
            cpuInfoSb.append("BogoMIPS\t: $bogoBase.$bogoFrac\n")
            cpuInfoSb.append("Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid\n")
            cpuInfoSb.append("CPU implementer\t: 0x41\n")
            cpuInfoSb.append("CPU architecture: 8\n")
            cpuInfoSb.append("CPU variant\t: 0x${(seed + i) % 4}\n")
            cpuInfoSb.append("CPU part\t: 0x${Integer.toHexString(coreParts[i])}\n")
            cpuInfoSb.append("CPU revision\t: ${(seed + i) % 6}\n\n")
        }
        cpuInfoSb.append("Hardware\t: $cpuHardware\n")
        cpuInfoSb.append("Revision\t: r${1 + (seed % 9)}p${seed % 5}\n")
        cpuInfoSb.append("SoC Model\t: $processorFull\n")

        val topCardSpecSummary = buildString {
            append(processorFull)
            append("\nProcess: ${matchedTpl.fabNm}  •  Arch: ${matchedTpl.archDesc}")
            append("\nCache: ${matchedTpl.cacheDesc}  •  Memory: ${matchedTpl.ramDesc}")
        }

        val rawGpu = identity.optString("gpuRenderer", "").trim()
        val gpuClockJitter = 680 + ((seed % 9) * 45)
        val gpuRenderer = when {
            rawGpu.isBlank() -> matchedTpl.defaultGpu
            rawGpu.contains("Adreno (TM) 750") && (seed % 3 != 0) -> {
                val gPool = listOf(
                    "Adreno (TM) 750 @ ${903 + (seed % 5) * 24}MHz",
                    matchedTpl.defaultGpu,
                    "Adreno (TM) 740 @ ${680 + (seed % 4) * 20}MHz",
                    "Mali-G715 MC10 @ ${890 + (seed % 5) * 15}MHz",
                    "Immortalis-G720 MC12 @ ${1250 + (seed % 4) * 25}MHz",
                    "Xclipse 940 (AMD RDNA 3) @ ${1050 + (seed % 4) * 25}MHz"
                )
                gPool[seed % gPool.size]
            }
            !rawGpu.contains("@") -> "$rawGpu @ ${gpuClockJitter}MHz"
            else -> rawGpu
        }

        val lowerGpu = gpuRenderer.lowercase()
        val gpuVendor = when {
            lowerGpu.contains("adreno") -> "Qualcomm"
            lowerGpu.contains("mali") || lowerGpu.contains("immortalis") -> "ARM"
            lowerGpu.contains("xclipse") || lowerGpu.contains("sgx") -> "Samsung"
            lowerGpu.contains("powervr") -> "Imagination Technologies"
            else -> matchedTpl.mfr
        }

        val gitHash1 = Integer.toHexString(0x10000000 + (seed % 0x6fffffff))
        val gitHash2 = Integer.toHexString(0x20000000 + ((seed / 3) % 0x5fffffff))
        val drvVer = 612 + (seed % 185)
        val drvSub = 10 + ((seed / 5) % 85)
        val month = 1 + (seed % 12)
        val day = 1 + ((seed / 7) % 28)
        val gpuVersion = when {
            lowerGpu.contains("mali") || lowerGpu.contains("immortalis") ->
                "OpenGL ES 3.2 v1.r${32 + (seed % 18)}p0-01rel0.${gitHash1}${gitHash2}"
            lowerGpu.contains("xclipse") ->
                "OpenGL ES 3.2 v2.r${8 + (seed % 9)}p0-01rel0.${gitHash1} (Date:%02d/%02d/25)".format(month, day)
            else ->
                "OpenGL ES 3.2 V@0$drvVer.$drvSub (GIT@$gitHash1, I$gitHash2, ${1710000000L + (seed % 50000000L)}) (Date:%02d/%02d/25)".format(month, day)
        }

        return CpuHardwareRandomValues(
            processorFull = processorFull,
            socManufacturer = socMfr,
            socModelShort = socShort,
            cpuHardware = cpuHardware,
            boardPlatform = boardPlatform,
            governor = governor,
            coresCount = 8,
            supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"),
            coreMinKhz = coreMinKhz,
            coreMaxKhz = coreMaxKhz,
            coreCurKhz = coreCurKhz,
            archClusterSummary = archClusterSummary,
            freqRangeSummary = freqRangeSummary,
            procCpuInfoText = cpuInfoSb.toString(),
            topCardSpecSummary = topCardSpecSummary,
            gpuRenderer = gpuRenderer,
            gpuVendor = gpuVendor,
            gpuVersion = gpuVersion
        )
    }

    private data class SimStatusRandomValues(
        val seed: Int,
        val countryCode: String,
        val mccMnc: String,
        val carrier: String,
        val phoneNumber: String,
        val dataState: String,
        val serviceState: String,
        val dbm: Int,
        val asu: Int,
        val signalStrength: String,
        val voiceNetType: String,
        val dataNetType: String,
        val roamingState: String,
        val imsi: String,
        val iccid: String,
        val eid: String,
        val imeiSv: String,
        val uptime: String
    )

    private fun computeSimStatusValues(identity: JSONObject): SimStatusRandomValues {
        val rawSeedStr = identity.optString("androidId", "") +
                identity.optString("imei", "") +
                identity.optString("simImsi", "") +
                identity.optString("simIccid", "") +
                identity.optString("deviceModel", "") +
                identity.optString("ipAddress", "") +
                identity.optString("timezone", "") +
                identity.optString("locationName", "")
        val hash = if (rawSeedStr.isNotBlank()) rawSeedStr.hashCode() else (System.currentTimeMillis() / 1000L).toInt()
        val seed = if (hash == Int.MIN_VALUE) 48271 else kotlin.math.abs(hash)

        val tz = identity.optString("timezone", "")
        val loc = identity.optString("locationName", "")
        val country = getCountryCode(tz, loc)

        val defaultCarrierForCountry = when (country) {
            "BD" -> "Grameenphone 4G"
            "IN" -> "Jio True5G"
            "ID" -> "Telkomsel 5G"
            "GB" -> "Vodafone UK"
            "JP" -> "NTT Docomo"
            "SG" -> "Singtel 5G"
            "DE" -> "Telekom.de"
            "FR" -> "Orange F"
            "CA" -> "Rogers Wireless"
            "AU" -> "Telstra Mobile"
            "AE" -> "Etisalat 5G"
            "SA" -> "STC 5G"
            "KR" -> "SK Telecom 5G"
            "BR" -> "Vivo 5G"
            "EG" -> "Vodafone EG"
            "NZ" -> "Spark NZ 5G"
            else -> "T-Mobile 5G"
        }
        val rawCarrier = identity.optString("carrier", "").trim()
        val carrierPart = if (rawCarrier.contains("•")) {
            rawCarrier.substringBefore("•").trim()
        } else if (rawCarrier.contains("+")) {
            rawCarrier.substringBefore("+").trim()
        } else {
            rawCarrier
        }
        val extractedPhoneFromCarrier = if (rawCarrier.contains("+")) {
            "+" + rawCarrier.substringAfter("+").filter { it.isDigit() }
        } else {
            ""
        }
        val carrier = if (carrierPart.isNotBlank() &&
            !carrierPart.equals("Default", ignoreCase = true) &&
            !carrierPart.equals("Unknown", ignoreCase = true)
        ) {
            carrierPart
        } else {
            defaultCarrierForCountry
        }

        val defaultMccMnc = when (country) {
            "BD" -> "47001"
            "IN" -> "405874"
            "ID" -> "51010"
            "GB" -> "23415"
            "JP" -> "44010"
            "SG" -> "52501"
            "DE" -> "26201"
            "FR" -> "20801"
            "CA" -> "302720"
            "AU" -> "50501"
            "AE" -> "42402"
            "SA" -> "42001"
            "KR" -> "45005"
            "BR" -> "72406"
            "EG" -> "60202"
            "NZ" -> "53005"
            else -> "310260"
        }

        val p1 = 201 + (seed % 780)
        val p2 = 200 + ((seed / 7) % 780)
        val p3 = 1000 + ((seed / 13) % 8999)
        val p4 = 100000 + ((seed / 17) % 899999)

        val generatedE164Phone = when (country) {
            "BD" -> "+88017${11 + (seed % 88)}$p4"
            "IN" -> "+9198${10 + (seed % 89)}$p4"
            "ID" -> "+62812${1000 + (seed % 8999)}$p3"
            "GB" -> "+4477${10 + (seed % 89)}$p4"
            "JP" -> "+8190${1000 + (seed % 8999)}$p3"
            "SG" -> "+658${100 + (seed % 899)}$p3"
            "DE" -> "+49151${100 + (seed % 899)}$p3"
            "FR" -> "+336${10 + (seed % 89)}${10 + ((seed / 3) % 89)}${10 + ((seed / 7) % 89)}${10 + ((seed / 11) % 89)}"
            "CA" -> "+1${listOf(416, 647, 437, 905, 604, 514)[seed % 6]}$p2$p3"
            "AU" -> "+614${10 + (seed % 89)}${100 + ((seed / 5) % 899)}${100 + ((seed / 9) % 899)}"
            "AE" -> "+97150${100 + (seed % 899)}$p3"
            "SA" -> "+96650${100 + (seed % 899)}$p3"
            "KR" -> "+8210${1000 + (seed % 8999)}$p3"
            "BR" -> "+55119${1000 + (seed % 8999)}$p3"
            "EG" -> "+2010${1000 + (seed % 8999)}$p3"
            "NZ" -> "+6427${100 + (seed % 899)}$p3"
            else -> "+1$p1$p2$p3"
        }
        val phoneNumber = if (extractedPhoneFromCarrier.length >= 9) extractedPhoneFromCarrier else generatedE164Phone

        val dataStates = listOf(
            "Connected",
            "Connected (5G Active)",
            "Connected (LTE+)",
            "Connected (High Speed)",
            "Connected (VoLTE Active)"
        )
        val dataState = dataStates[seed % dataStates.size]

        val serviceStates = listOf(
            "In Service (Voice & Data)",
            "In Service (5G NR Ready)",
            "In Service (VoLTE Active)",
            "In Service (High Priority)",
            "In Service"
        )
        val serviceState = serviceStates[(seed / 3) % serviceStates.size]

        val dbm = -(64 + (seed % 31)) // -64 dBm to -94 dBm
        val asu = (113 + dbm).coerceIn(38, 76)
        val signalStrength = "$dbm dBm $asu asu"

        val voiceNetTypes = listOf(
            "5G NR (VoNR)",
            "VoLTE (4G+)",
            "5G NSA / LTE",
            "LTE (HD Voice)",
            "5G NR",
            "LTE+"
        )
        val voiceNetType = voiceNetTypes[(seed / 5) % voiceNetTypes.size]

        val dataNetTypes = listOf(
            "5G NR",
            "LTE+ (4G+)",
            "5G NR (NSA)",
            "LTE_CA",
            "5G Standalone (SA)",
            "LTE Advanced"
        )
        val dataNetType = dataNetTypes[(seed / 11) % dataNetTypes.size]

        val roamingStates = listOf(
            "Not Roaming (Home Network)",
            "Not Roaming (Domestic)",
            "Not Roaming (Native Carrier)",
            "Roaming (Partner Network)"
        )
        val roamingState = roamingStates[(seed / 13) % roamingStates.size]

        val rawImsi = identity.optString("simImsi", "")
        val imsi = if (rawImsi.length >= 14 && rawImsi.startsWith(defaultMccMnc.take(3))) {
            rawImsi
        } else {
            val remLen = (15 - defaultMccMnc.length).coerceAtLeast(8)
            val tail = (1000000000L + (seed % 8999999999L)).toString().take(remLen)
            "$defaultMccMnc$tail"
        }
        val mccMnc = imsi.take(defaultMccMnc.length).ifBlank { defaultMccMnc }
        val iccid = identity.optString("simIccid", "").ifBlank {
            "890141032111${1000000 + (seed % 8999999)}F"
        }
        val eid = "8904903200500888260000${1000000000L + (seed % 8999999999L)}"
        val imeiSv = "%02d".format(1 + (seed % 98))
        val uptime = "%d:%02d:%02d".format(1 + (seed % 36), 10 + ((seed / 7) % 49), 10 + ((seed / 11) % 49))

        return SimStatusRandomValues(
            seed = seed,
            countryCode = country,
            mccMnc = mccMnc,
            carrier = carrier,
            phoneNumber = phoneNumber,
            dataState = dataState,
            serviceState = serviceState,
            dbm = dbm,
            asu = asu,
            signalStrength = signalStrength,
            voiceNetType = voiceNetType,
            dataNetType = dataNetType,
            roamingState = roamingState,
            imsi = imsi,
            iccid = iccid,
            eid = eid,
            imeiSv = imeiSv,
            uptime = uptime
        )
    }

    private val mapsLinePrefixRegex = Regex("""^([0-9a-fA-F]+-[0-9a-fA-F]+\s+[rwxps-]{4}\s+[0-9a-fA-F]+\s+[0-9a-fA-F]+:[0-9a-fA-F]+\s+\d+\s+)(.*)$""")

    private fun sanitizeMapsOrMountsLine(rawLine: String, targetPkg: String): String {
        var line = rawLine
        val lower = line.lowercase()

        // If this is a /proc/self/maps memory mapping line, NEVER rewrite foreign /data/app/<pkg>/base.apk paths
        // to targetPkg (which causes Unity/Free Fire and Flutter/TopNod to open the wrong base.apk and crash!).
        // Instead, disguise any hooking/module/cloner mapping as a standard Android system library or framework jar.
        val mapsMatch = mapsLinePrefixRegex.find(line)
        if (mapsMatch != null) {
            val prefix = mapsMatch.groupValues[1]
            val mappedPath = mapsMatch.groupValues[2]
            val lowPath = mappedPath.lowercase()
            if (lowPath.contains("phantomcore") || lowPath.contains("ghostdevice") || lowPath.contains("xposed") ||
                lowPath.contains("lsposed") || lowPath.contains("lspd") ||
                lowPath.contains("edxposed") || lowPath.contains("zygisk") ||
                lowPath.contains("riru") || lowPath.contains("magisk") ||
                lowPath.contains("supersu") || lowPath.contains("kernelsu") ||
                lowPath.contains("libsandhook") || lowPath.contains("libpine") ||
                lowPath.contains("libdobby") || lowPath.contains("libwhale") ||
                lowPath.contains("libsubstrate") || lowPath.contains("libva++") ||
                lowPath.contains("libvirtual") || lowPath.contains("libepic") ||
                lowPath.contains("dual.space") || lowPath.contains("dualspace") ||
                lowPath.contains("virtualapp") || lowPath.contains("vphonegaga") ||
                lowPath.contains("vmos")
            ) {
                val replacementPath = if (lowPath.endsWith(".so")) {
                    "/system/lib64/libc++.so"
                } else {
                    "/system/framework/framework.jar"
                }
                return prefix + replacementPath
            }
            return line
        }

        if (lower.contains("magisk") || lower.contains("lsposed") || lower.contains("lspd") ||
            lower.contains("edxposed") || lower.contains("xposed") || lower.contains("phantomcore") ||
            lower.contains("ghostdevice") ||
            lower.contains("zygisk") || lower.contains("riru") || lower.contains("supersu") ||
            lower.contains("kernelsu")
        ) {
            line = line
                .replace("magisk", "system", ignoreCase = true)
                .replace("lsposed", "android", ignoreCase = true)
                .replace("lspd", "artd", ignoreCase = true)
                .replace("edxposed", "android", ignoreCase = true)
                .replace("xposedbridge", "framework", ignoreCase = true)
                .replace("xposed", "dalvik", ignoreCase = true)
                .replace("com.aistudio.ghostdevice.qxvz", "android", ignoreCase = true)
                .replace("com.aistudio.phantomcore.qxvz", "android", ignoreCase = true)
                .replace("ghostdevice", "framework", ignoreCase = true)
                .replace("phantomcore", "framework", ignoreCase = true)
                .replace("zygisk", "runtime", ignoreCase = true)
                .replace("riru", "core", ignoreCase = true)
                .replace("supersu", "system", ignoreCase = true)
                .replace("kernelsu", "kernel", ignoreCase = true)
        }
        return line
    }

    private fun stageCleanMountsFile(baseDir: File, targetPkg: String = ""): File? {
        isInternalFileAccess.set(true)
        return try {
            val realMounts = File("/proc/self/mounts")
            if (!realMounts.exists()) return null
            val lines = realMounts.readLines()
            val filteredLines = lines.filter { line ->
                val lower = line.lowercase()
                !lower.contains("magisk") && 
                !lower.contains("lsposed") && 
                !lower.contains("lspd") &&
                !lower.contains("mirror") && 
                !lower.contains("phantomcore") &&
                !lower.contains("ghostdevice") &&
                !lower.contains("su") &&
                !lower.contains("tmpfs /data/adb") &&
                !lower.contains("xposed") &&
                !lower.contains("dual.space") &&
                !lower.contains("virtual")
            }.map { sanitizeMapsOrMountsLine(it, targetPkg) }
            val f = File(baseDir, "mounts")
            f.writeText(filteredLines.joinToString("\n") + "\n")
            f
        } catch (_: Exception) {
            null
        } finally {
            isInternalFileAccess.set(false)
        }
    }

    private fun stageCleanMapsFile(baseDir: File, targetPkg: String = ""): File? {
        isInternalFileAccess.set(true)
        return try {
            val realMaps = File("/proc/self/maps")
            if (!realMaps.exists()) return null
            val lines = realMaps.readLines()
            val filteredLines = lines.filter { line ->
                val lower = line.lowercase()
                !lower.contains("xposed") && 
                !lower.contains("lsposed") && 
                !lower.contains("lspd") &&
                !lower.contains("magisk") &&
                !lower.contains("zygisk") &&
                !lower.contains("riru") &&
                !lower.contains("phantomcore") &&
                !lower.contains("ghostdevice") &&
                !lower.contains("com.example") &&
                !lower.contains("dual.space")
            }.map { sanitizeMapsOrMountsLine(it, targetPkg) }
            val f = File(baseDir, "maps")
            f.writeText(filteredLines.joinToString("\n") + "\n")
            f
        } catch (_: Exception) {
            null
        } finally {
            isInternalFileAccess.set(false)
        }
    }

    private fun hookSettingsControllers(
        classLoader: ClassLoader,
        identity: JSONObject,
        simVals: SimStatusRandomValues
    ) {
        val model = identity.optString("deviceModel", "")
        val imei = identity.optString("imei", "")
        val carrier = simVals.carrier
        val mac = identity.optString("macAddress", "").lowercase()
        val bt = identity.optString("bluetoothAddress", "").lowercase()
        val ip = identity.optString("ipAddress", "")
        val osVersion = identity.optString("osVersion", "Android 14")
        val osRel = osVersion.removePrefix("Android ").trim()
        val buildDisplay = identity.optString("display", "")
        val buildNum = identity.optString("buildNumber", "").ifBlank { buildDisplay }
        val kernelVer = identity.optString("kernelVersion", "")
        val basebandVer = identity.optString("basebandVersion", "")

        val controllerClasses = arrayOf(
            "com.android.settings.deviceinfo.DeviceModelPreferenceController",
            "com.android.settings.deviceinfo.HardwareInfoPreferenceController",
            "com.android.settings.deviceinfo.imei.ImeiInfoPreferenceController",
            "com.android.settings.deviceinfo.simstatus.SimStatusPreferenceController",
            "com.android.settings.deviceinfo.WifiMacAddressPreferenceController",
            "com.android.settings.deviceinfo.aboutphone.SavedAccessPointsWifiMacAddressPreferenceController",
            "com.android.settingslib.deviceinfo.AbstractWifiMacAddressPreferenceController",
            "com.android.settings.deviceinfo.BluetoothAddressPreferenceController",
            "com.android.settingslib.deviceinfo.AbstractBluetoothAddressPreferenceController",
            "com.android.settings.deviceinfo.IpAddressPreferenceController",
            "com.android.settingslib.deviceinfo.AbstractIpAddressPreferenceController",
            "com.android.settings.deviceinfo.UptimePreferenceController",
            "com.android.settingslib.deviceinfo.AbstractUptimePreferenceController",
            "com.android.settings.deviceinfo.firmwareversion.FirmwareVersionDetailPreferenceController",
            "com.android.settings.deviceinfo.firmwareversion.FirmwareVersionPreferenceController",
            "com.android.settingslib.deviceinfo.AbstractFirmwareVersionPreferenceController",
            "com.android.settings.deviceinfo.firmwareversion.BasebandVersionPreferenceController",
            "com.android.settings.deviceinfo.firmwareversion.SecurityPatchLevelPreferenceController",
            "com.android.settings.deviceinfo.firmwareversion.SimpleBuildNumberPreferenceController",
            "com.android.settings.deviceinfo.BuildNumberPreferenceController",
            "com.android.settings.deviceinfo.firmwareversion.KernelVersionPreferenceController"
        )

        for (clsName in controllerClasses) {
            try {
                val cls = XposedHelpers.findClass(clsName, classLoader) ?: continue

                // 1. Hook getSummary()
                try {
                    XposedHelpers.findAndHookMethod(cls, "getSummary", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            when {
                                clsName.contains("DeviceModel") || clsName.contains("HardwareInfo") ->
                                    if (model.isNotBlank()) param.result = model
                                clsName.contains("ImeiInfo") ->
                                    if (imei.isNotBlank()) param.result = imei
                                clsName.contains("SimStatus") ->
                                    param.result = carrier
                                clsName.contains("WifiMacAddress") ->
                                    if (mac.isNotBlank()) param.result = mac
                                clsName.contains("BluetoothAddress") ->
                                    if (bt.isNotBlank()) param.result = bt
                                clsName.contains("IpAddress") ->
                                    if (ip.isNotBlank()) param.result = ip
                                clsName.contains("Uptime") ->
                                    param.result = simVals.uptime
                                clsName.contains("FirmwareVersion") ->
                                    if (osRel.isNotBlank()) param.result = osRel
                                clsName.contains("BasebandVersion") ->
                                    if (basebandVer.isNotBlank()) param.result = basebandVer
                                clsName.contains("BuildNumber") ->
                                    if (buildNum.isNotBlank()) param.result = buildNum
                                clsName.contains("KernelVersion") ->
                                    if (kernelVer.isNotBlank()) param.result = kernelVer
                            }
                        }
                    })
                } catch (_: Throwable) {}

                // 2. Hook updateState(Preference)
                try {
                    val prefClass = XposedHelpers.findClass("androidx.preference.Preference", classLoader)
                    if (prefClass != null) {
                        XposedHelpers.findAndHookMethod(cls, "updateState", prefClass, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val pref = param.args[0] ?: return
                                when {
                                    clsName.contains("WifiMacAddress") ->
                                        if (mac.isNotBlank()) setPreferenceSummary(pref, mac)
                                    clsName.contains("BluetoothAddress") ->
                                        if (bt.isNotBlank()) setPreferenceSummary(pref, bt)
                                    clsName.contains("DeviceModel") || clsName.contains("HardwareInfo") ->
                                        if (model.isNotBlank()) setPreferenceSummary(pref, model)
                                    clsName.contains("ImeiInfo") ->
                                        if (imei.isNotBlank()) setPreferenceSummary(pref, imei)
                                    clsName.contains("SimStatus") ->
                                        setPreferenceSummary(pref, carrier)
                                    clsName.contains("IpAddress") ->
                                        if (ip.isNotBlank()) setPreferenceSummary(pref, ip)
                                    clsName.contains("Uptime") ->
                                        setPreferenceSummary(pref, simVals.uptime)
                                    clsName.contains("FirmwareVersion") ->
                                        if (osRel.isNotBlank()) setPreferenceSummary(pref, osRel)
                                    clsName.contains("BasebandVersion") ->
                                        if (basebandVer.isNotBlank()) setPreferenceSummary(pref, basebandVer)
                                    clsName.contains("BuildNumber") ->
                                        if (buildNum.isNotBlank()) setPreferenceSummary(pref, buildNum)
                                    clsName.contains("KernelVersion") ->
                                        if (kernelVer.isNotBlank()) setPreferenceSummary(pref, kernelVer)
                                }
                            }
                        })
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }

        // 3. Hook androidx.preference.Preference.setSummary & getSummary by Preference key
        try {
            val prefClass = XposedHelpers.findClass("androidx.preference.Preference", classLoader)
            if (prefClass != null) {
                val resolveSummaryForKey = fun(keyRaw: String?): CharSequence? {
                    val key = keyRaw?.lowercase() ?: return null
                    return when {
                        key.contains("sim_status") -> carrier
                        key.contains("wifi_mac") || key.contains("factory_mac") || key.contains("saved_accesspoints_wifi_mac") ->
                            if (mac.isNotBlank()) mac else null
                        key.contains("bt_address") || key.contains("bluetooth_address") ->
                            if (bt.isNotBlank()) bt else null
                        key.contains("wifi_ip") || key.contains("ip_address") ->
                            if (ip.isNotBlank()) ip else null
                        key == "device_model" || key == "branded_device_name" || key == "device_name" ->
                            if (model.isNotBlank()) model else null
                        key.contains("imei_info") ->
                            if (imei.isNotBlank()) imei else null
                        key.contains("build_number") || key == "os_build_number" ->
                            if (buildNum.isNotBlank()) buildNum else null
                        key.contains("up_time") -> simVals.uptime
                        key.contains("base_band") || key.contains("baseband") ->
                            if (basebandVer.isNotBlank()) basebandVer else null
                        key.contains("kernel_version") ->
                            if (kernelVer.isNotBlank()) kernelVer else null
                        else -> null
                    }
                }

                XposedHelpers.findAndHookMethod(
                    prefClass,
                    "setSummary",
                    CharSequence::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val key = callMethodCompat(param.thisObject, "getKey") as? String
                            val overrideVal = resolveSummaryForKey(key)
                            if (overrideVal != null) {
                                param.args[0] = overrideVal
                            }
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    prefClass,
                    "getSummary",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val key = callMethodCompat(param.thisObject, "getKey") as? String
                            val overrideVal = resolveSummaryForKey(key)
                            if (overrideVal != null) {
                                param.result = overrideVal
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {}

        // 4. Hook SimStatusDialogFragment.setText(int viewId, CharSequence text)
        val mapViewEntryToRandomValue = fun(entryNameRaw: String): String? {
            val entry = entryNameRaw.lowercase()
            return when {
                entry.contains("operator_name") || entry.contains("network_provider") || entry == "carrier_name_value" ->
                    simVals.carrier
                entry.contains("number_value") || entry.contains("phone_number") || entry == "sim_number_value" ->
                    simVals.phoneNumber
                entry.contains("data_state") ->
                    simVals.dataState
                entry.contains("service_state") ->
                    simVals.serviceState
                entry.contains("signal_strength") ->
                    simVals.signalStrength
                entry.contains("voice_network_type") ->
                    simVals.voiceNetType
                entry.contains("data_network_type") ->
                    simVals.dataNetType
                entry.contains("roaming_state") ->
                    simVals.roamingState
                entry.contains("icc_id") ->
                    simVals.iccid
                entry.contains("eid_value") || entry == "esim_id_value" ->
                    simVals.eid
                entry.contains("ims_reg_state") ->
                    "Registered (VoLTE / VoWiFi)"
                entry.contains("imei_sv") ->
                    simVals.imeiSv
                entry.contains("imei_value") || entry == "meid_number_value" ->
                    if (imei.isNotBlank()) imei else null
                else -> null
            }
        }

        val dialogFragmentClasses = arrayOf(
            "com.android.settings.deviceinfo.simstatus.SimStatusDialogFragment",
            "com.android.settings.deviceinfo.imei.ImeiInfoDialogFragment"
        )
        for (dlgClsName in dialogFragmentClasses) {
            try {
                val dlgCls = XposedHelpers.findClass(dlgClsName, classLoader) ?: continue
                XposedHelpers.findAndHookMethod(
                    dlgCls,
                    "setText",
                    Int::class.javaPrimitiveType,
                    CharSequence::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val viewId = param.args[0] as? Int ?: return
                            val entryName = try {
                                val res = callMethodCompat(param.thisObject, "getResources") as? android.content.res.Resources
                                res?.getResourceEntryName(viewId) ?: ""
                            } catch (_: Throwable) { "" }
                            val mapped = mapViewEntryToRandomValue(entryName)
                            if (mapped != null) {
                                param.args[1] = mapped
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // 5. Hook SimStatusDialogController initialize / update methods so all fields are forcibly populated even if skipped by controller
        try {
            val ctrlCls = XposedHelpers.findClass(
                "com.android.settings.deviceinfo.simstatus.SimStatusDialogController",
                classLoader
            )
            if (ctrlCls != null) {
                val forcePopulateDialog = fun(controllerObj: Any) {
                    try {
                        val mDialog = getObjectFieldCompat(controllerObj, "mDialog") ?: return
                        val res = callMethodCompat(mDialog, "getResources") as? android.content.res.Resources ?: return
                        val idNames = arrayOf(
                            "operator_name_value",
                            "number_value",
                            "data_state_value",
                            "service_state_value",
                            "signal_strength_value",
                            "voice_network_type_value",
                            "data_network_type_value",
                            "roaming_state_value",
                            "icc_id_value",
                            "eid_value",
                            "ims_reg_state_value"
                        )
                        for (idName in idNames) {
                            val id = res.getIdentifier(idName, "id", "com.android.settings")
                            if (id != 0) {
                                val value = mapViewEntryToRandomValue(idName)
                                if (value != null) {
                                    try {
                                        callMethodCompat(mDialog, "setText", id, value)
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }

                for (methodName in arrayOf("initialize", "updateNetworkProvider", "updatePhoneNumber", "updateDataState", "updateServiceState", "updateSignalStrength", "updateNetworkType", "updateRoamingStatus", "updateIccidNumber", "updateEid")) {
                    try {
                        hookAllMethodsCompat(ctrlCls, methodName, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val obj = param.thisObject ?: return
                                forcePopulateDialog(obj)
                            }
                        })
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // 6. Targeted TextView.setText hook inside Settings ONLY for SIM status & About phone resource viewIds
        try {
            val idNameCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
            XposedHelpers.findAndHookMethod(
                android.widget.TextView::class.java,
                "setText",
                CharSequence::class.java,
                android.widget.TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? android.widget.TextView ?: return
                        val viewId = tv.id
                        if (viewId > 0) {
                            val entryName = idNameCache.getOrPut(viewId) {
                                try {
                                    tv.resources.getResourceEntryName(viewId) ?: ""
                                } catch (_: Throwable) { "" }
                            }
                            if (entryName.isNotEmpty()) {
                                val mapped = mapViewEntryToRandomValue(entryName)
                                if (mapped != null) {
                                    param.args[0] = mapped
                                    return
                                }
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookAllMethodsCompat(hookClass: Class<*>, methodName: String, callback: XC_MethodHook) {
        try {
            val m = XposedBridge::class.java.getMethod(
                "hookAllMethods",
                Class::class.java,
                String::class.java,
                XC_MethodHook::class.java
            )
            m.invoke(null, hookClass, methodName, callback)
            return
        } catch (_: Throwable) {}

        for (method in hookClass.declaredMethods) {
            if (method.name == methodName) {
                try {
                    val paramTypes = method.parameterTypes
                    val args = arrayOfNulls<Any>(paramTypes.size + 1)
                    for (i in paramTypes.indices) {
                        args[i] = paramTypes[i]
                    }
                    args[paramTypes.size] = callback
                    XposedHelpers.findAndHookMethod(hookClass, methodName, *args)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun callMethodCompat(obj: Any?, methodName: String, vararg args: Any?): Any? {
        if (obj == null) return null
        try {
            val m = XposedHelpers::class.java.getMethod(
                "callMethod",
                Any::class.java,
                String::class.java,
                Array<Any>::class.java
            )
            return m.invoke(null, obj, methodName, args)
        } catch (_: Throwable) {}

        try {
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                for (method in cls.declaredMethods) {
                    if (method.name == methodName && method.parameterTypes.size == args.size) {
                        method.isAccessible = true
                        return method.invoke(obj, *args)
                    }
                }
                cls = cls.superclass
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun getObjectFieldCompat(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        try {
            val m = XposedHelpers::class.java.getMethod(
                "getObjectField",
                Any::class.java,
                String::class.java
            )
            return m.invoke(null, obj, fieldName)
        } catch (_: Throwable) {}

        try {
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField(fieldName)
                    f.isAccessible = true
                    return f.get(obj)
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun setPreferenceSummary(pref: Any, summary: CharSequence) {
        try {
            val method = pref.javaClass.getMethod("setSummary", CharSequence::class.java)
            method.invoke(pref, summary)
        } catch (_: Throwable) {
            try {
                val method = pref.javaClass.getMethod("setSummary", String::class.java)
                method.invoke(pref, summary.toString())
            } catch (_: Throwable) {}
        }
    }

    private fun sanitizeRootBuildFieldsOnly() {
        try {
            XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
            XposedHelpers.setStaticObjectField(Build::class.java, "TYPE", "user")
            try { XposedHelpers.setStaticObjectField(Build::class.java, "IS_EMULATOR", false) } catch (_: Throwable) {}
            try { XposedHelpers.setStaticObjectField(Build::class.java, "IS_DEBUGGABLE", false) } catch (_: Throwable) {}
            try { XposedHelpers.setStaticObjectField(Build::class.java, "IS_ENG", false) } catch (_: Throwable) {}
            try { XposedHelpers.setStaticObjectField(Build::class.java, "IS_USERDEBUG", false) } catch (_: Throwable) {}
            try { XposedHelpers.setStaticObjectField(Build::class.java, "IS_USER", true) } catch (_: Throwable) {}
            val currentFp = Build.FINGERPRINT ?: ""
            if (currentFp.contains("test-keys") || currentFp.contains("dev-keys") || currentFp.contains("userdebug") || currentFp.contains(":eng/") || currentFp.contains("sdk_gphone") || currentFp.contains("generic") || currentFp.contains("vbox") || currentFp.contains("emulator")) {
                val sanitizedFp = currentFp
                    .replace("test-keys", "release-keys")
                    .replace("dev-keys", "release-keys")
                    .replace(":userdebug/", ":user/")
                    .replace(":eng/", ":user/")
                    .replace("sdk_gphone64_x86_64", "husky")
                    .replace("sdk_gphone_x86_64", "husky")
                    .replace("sdk_gphone", "husky")
                    .replace("generic_x86_64", "husky")
                    .replace("generic_x86", "husky")
                    .replace("generic", "google")
                    .replace("vbox86p", "husky")
                XposedHelpers.setStaticObjectField(Build::class.java, "FINGERPRINT", sanitizedFp)
            }
            val curHw = (Build.HARDWARE ?: "").lowercase()
            if (curHw.isEmpty() || curHw.contains("goldfish") || curHw.contains("ranchu") || curHw.contains("vbox") || curHw.contains("nox") || curHw.contains("ttvm") || curHw.contains("unknown") || curHw.contains("x86") || curHw.contains("cancro")) {
                XposedHelpers.setStaticObjectField(Build::class.java, "HARDWARE", "qcom")
            }
            val curBoard = (Build.BOARD ?: "").lowercase()
            if (curBoard.isEmpty() || curBoard.contains("goldfish") || curBoard.contains("ranchu") || curBoard.contains("vbox") || curBoard.contains("nox") || curBoard.contains("unknown") || curBoard.contains("x86")) {
                XposedHelpers.setStaticObjectField(Build::class.java, "BOARD", "pineapple")
            }
            try {
                val is64BitProc = try { android.os.Process.is64Bit() } catch (_: Throwable) { true }
                XposedHelpers.setStaticObjectField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"))
                XposedHelpers.setStaticObjectField(Build::class.java, "SUPPORTED_64_BIT_ABIS", arrayOf("arm64-v8a"))
                XposedHelpers.setStaticObjectField(Build::class.java, "SUPPORTED_32_BIT_ABIS", arrayOf("armeabi-v7a", "armeabi"))
                XposedHelpers.setStaticObjectField(Build::class.java, "CPU_ABI", if (is64BitProc) "arm64-v8a" else "armeabi-v7a")
                XposedHelpers.setStaticObjectField(Build::class.java, "CPU_ABI2", if (is64BitProc) "" else "armeabi")
                XposedHelpers.setStaticObjectField(Build::class.java, "FLAVOR", "husky-user")
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private data class ParsedBuildIdentity(
        val cleanModel: String,
        val manufacturer: String,
        val brand: String,
        val product: String,
        val device: String,
        val release: String,
        val buildId: String,
        val incremental: String,
        val fingerprint: String,
        val securityPatch: String
    )

    private fun extractHardwareModelCode(rawModel: String, isSettingsPackage: Boolean): String {
        if (isSettingsPackage || rawModel.isBlank()) return rawModel
        // In Settings About Phone we show the full marketing + model name (e.g. "Galaxy S24 Ultra SM-S928B"),
        // whereas in target apps Build.MODEL / User-Agent must be the exact OEM model token (e.g. "SM-S928B", "CPH2581", "24030PN60G")
        val lastToken = rawModel.substringAfterLast(" ").trim()
        if (lastToken.startsWith("SM-", ignoreCase = true) ||
            lastToken.startsWith("CPH", ignoreCase = true) ||
            lastToken.startsWith("RMX", ignoreCase = true) ||
            lastToken.startsWith("V2", ignoreCase = true) ||
            lastToken.startsWith("XT", ignoreCase = true) ||
            (lastToken.length >= 8 && lastToken.any { it.isDigit() } && lastToken.any { it.isLetter() } && !rawModel.startsWith("Pixel", ignoreCase = true))
        ) {
            return lastToken
        }
        return rawModel
    }

    private fun resolveConsistentBuildIdentity(
        identity: JSONObject,
        cpuVals: CpuHardwareRandomValues,
        isSettingsPackage: Boolean,
        SanitizeReleaseKeys: Boolean
    ): ParsedBuildIdentity {
        val idTs = identity.optLong("resetTimestamp", 0L)
        if (idTs > activeIdentityResetTs) activeIdentityResetTs = idTs
        val rawModel = readFreshSysProp("persist.sys.phantom.mod", identity.optString("deviceModel", "Pixel 8 Pro"))
        val cleanModel = extractHardwareModelCode(rawModel, isSettingsPackage)
        val mfr = readFreshSysProp("persist.sys.phantom.mfr", identity.optString("manufacturer", "Google"))
        val rawFp = readFreshSysProp("persist.sys.phantom.fp", identity.optString("buildFingerprint", ""))
        val rawOs = readFreshSysProp("persist.sys.phantom.os", identity.optString("osVersion", "Android 14"))
        val rawBuildNum = readFreshSysProp("persist.sys.phantom.bid", identity.optString("buildNumber", "UP1A.231005.007"))
        val rawDevId = readFreshSysProp("persist.sys.phantom.did", identity.optString("deviceId", "husky"))

        val profileOsRel = rawOs.removePrefix("Android ").substringBefore(" ").trim().ifBlank { "14" }
        // In target apps, Build.VERSION.SDK_INT is fixed by the runtime OS. Keep RELEASE aligned with SDK_INT
        // so Play Integrity / X / DroidGuard never sees an impossible SDK_INT vs RELEASE mismatch!
        val runtimeOsRel = when (Build.VERSION.SDK_INT) {
            35 -> "15"
            34 -> "14"
            33 -> "13"
            32, 31 -> "12"
            30 -> "11"
            29 -> "10"
            else -> profileOsRel
        }
        val effectiveRel = if (isSettingsPackage) profileOsRel else runtimeOsRel

        val securityPatch = if (rawOs.contains("Patch:")) {
            rawOs.substringAfter("Patch:").trim().ifBlank { "2024-08-01" }
        } else {
            "2024-08-01"
        }

        // Parse standard fingerprint: brand/product/device:release/id/incremental:type/tags
        var fpBrand = if (mfr.isNotBlank()) mfr.lowercase() else "google"
        var fpProduct = if (rawDevId.isNotBlank()) rawDevId else cleanModel.replace(" ", "_").lowercase()
        var fpDevice = fpProduct
        var fpBuildId = if (rawBuildNum.isNotBlank()) rawBuildNum else "UP1A.231005.007"
        var fpIncremental = "11269751"
        var fpType = "user"
        var fpTags = "release-keys"

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
                if (leftRight.size >= 3) {
                    val suffixParts = leftRight[2].split("/")
                    if (suffixParts.size >= 2) {
                        fpType = suffixParts[0].ifBlank { "user" }
                        fpTags = suffixParts[1].ifBlank { "release-keys" }
                    }
                }
            } catch (_: Throwable) {}
        }

        if (SanitizeReleaseKeys || !isSettingsPackage) {
            fpType = "user"
            fpTags = "release-keys"
        }

        val consistentFp = "$fpBrand/$fpProduct/$fpDevice:$effectiveRel/$fpBuildId/$fpIncremental:$fpType/$fpTags"
        return ParsedBuildIdentity(
            cleanModel = cleanModel,
            manufacturer = mfr,
            brand = fpBrand,
            product = fpProduct,
            device = fpDevice,
            release = effectiveRel,
            buildId = fpBuildId,
            incremental = fpIncremental,
            fingerprint = consistentFp,
            securityPatch = securityPatch
        )
    }

    private fun hookBuildProperties(
        identity: JSONObject,
        cpuVals: CpuHardwareRandomValues,
        isSettingsPackage: Boolean = false,
        enableRootBypass: Boolean = true,
        pkg: String = ""
    ) {
        val parsed = resolveConsistentBuildIdentity(identity, cpuVals, isSettingsPackage, enableRootBypass)
        val serial = identity.optString("serialNumber", "").ifBlank { "39141FDJH0048X" }
        val display = identity.optString("display", "")

        try {
            XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
            XposedHelpers.setStaticObjectField(Build::class.java, "TYPE", "user")
            XposedHelpers.setStaticObjectField(Build::class.java, "USER", "android-build")
            XposedHelpers.setStaticObjectField(Build::class.java, "HOST", "abfarm-release-rbe-64-0001")
            try { XposedHelpers.setStaticObjectField(Build::class.java, "IS_EMULATOR", false) } catch (_: Throwable) {}
            try { XposedHelpers.setStaticObjectField(Build::class.java, "IS_DEBUGGABLE", false) } catch (_: Throwable) {}
            try { XposedHelpers.setStaticObjectField(Build::class.java, "IS_ENG", false) } catch (_: Throwable) {}
            try { XposedHelpers.setStaticObjectField(Build::class.java, "IS_USERDEBUG", false) } catch (_: Throwable) {}
            try { XposedHelpers.setStaticObjectField(Build::class.java, "IS_USER", true) } catch (_: Throwable) {}
            try { XposedHelpers.setStaticObjectField(Build::class.java, "PERMISSIONS_REVIEW_REQUIRED", true) } catch (_: Throwable) {}

            // Always spoof Build.HARDWARE, Build.BOARD, Build.SOC_MODEL, Build.SOC_MANUFACTURER, and ABIs across all hooked apps
            // so target apps never see emulator values ("goldfish", "ranchu", "vbox86", "nox", "unknown", "x86_64")
            // and Java Build.* fields match SystemProperties (ro.hardware, ro.board.platform, ro.product.board) 100%.
            XposedHelpers.setStaticObjectField(Build::class.java, "HARDWARE", cpuVals.cpuHardware)
            XposedHelpers.setStaticObjectField(Build::class.java, "BOARD", cpuVals.boardPlatform)
            try {
                XposedHelpers.setStaticObjectField(Build::class.java, "SOC_MANUFACTURER", cpuVals.socManufacturer)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.setStaticObjectField(Build::class.java, "SOC_MODEL", cpuVals.socModelShort)
            } catch (_: Throwable) {}
            try {
                val is64BitProc = try { android.os.Process.is64Bit() } catch (_: Throwable) { true }
                XposedHelpers.setStaticObjectField(Build::class.java, "SUPPORTED_ABIS", cpuVals.supportedAbis)
                XposedHelpers.setStaticObjectField(Build::class.java, "SUPPORTED_64_BIT_ABIS", arrayOf("arm64-v8a"))
                XposedHelpers.setStaticObjectField(Build::class.java, "SUPPORTED_32_BIT_ABIS", arrayOf("armeabi-v7a", "armeabi"))
                XposedHelpers.setStaticObjectField(Build::class.java, "CPU_ABI", if (is64BitProc) "arm64-v8a" else "armeabi-v7a")
                XposedHelpers.setStaticObjectField(Build::class.java, "CPU_ABI2", if (is64BitProc) "" else "armeabi")
            } catch (_: Throwable) {}

            if (parsed.cleanModel.isNotBlank()) {
                XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", parsed.cleanModel)
                XposedHelpers.setStaticObjectField(Build::class.java, "PRODUCT", parsed.product)
                XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", parsed.device)
                try {
                    XposedHelpers.setStaticObjectField(Build::class.java, "FLAVOR", "${parsed.product}-user")
                } catch (_: Throwable) {}
            }
            if (parsed.manufacturer.isNotBlank()) {
                XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", parsed.manufacturer)
                XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", parsed.brand)
            }
            XposedHelpers.setStaticObjectField(Build::class.java, "FINGERPRINT", parsed.fingerprint)
            XposedHelpers.setStaticObjectField(Build::class.java, "ID", parsed.buildId)
            XposedHelpers.setStaticObjectField(
                Build::class.java,
                "DISPLAY",
                if (isSettingsPackage && display.isNotBlank()) display else "${parsed.buildId}.${parsed.incremental}"
            )

            val bootloader = identity.optString("bootloaderVersion", "").ifBlank { "${parsed.product}-1.0-984210" }
            XposedHelpers.setStaticObjectField(Build::class.java, "BOOTLOADER", bootloader)

            val baseband = identity.optString("basebandVersion", "").ifBlank { "g5300q-230927-231102-B-11040898" }
            try {
                XposedHelpers.setStaticObjectField(Build::class.java, "RADIO", baseband)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.findAndHookMethod(Build::class.java, "getRadioVersion", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = baseband
                    }
                })
            } catch (_: Throwable) {}

            try {
                val buildSeed = kotlin.math.abs((parsed.cleanModel + parsed.buildId + parsed.fingerprint).hashCode().toLong())
                val fakeBuildTimeSec = 1719792000L + ((buildSeed % 180L) * 86400L) + (buildSeed % 86400L)
                XposedHelpers.setStaticObjectField(Build::class.java, "TIME", fakeBuildTimeSec * 1000L)
            } catch (_: Throwable) {}
            if (serial.isNotBlank()) {
                XposedHelpers.setStaticObjectField(Build::class.java, "SERIAL", serial)
                try {
                    XposedHelpers.findAndHookMethod(Build::class.java, "getSerial", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            param.result = serial
                        }
                    })
                } catch (_: Throwable) {}
            }

            // Hook Build.VERSION fields safely and consistently with Build.FINGERPRINT
            if (parsed.release.isNotBlank()) {
                try {
                    XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", parsed.release)
                } catch (_: Throwable) {}
                try {
                    XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE_OR_CODENAME", parsed.release)
                } catch (_: Throwable) {}
                try {
                    XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "INCREMENTAL", parsed.incremental)
                } catch (_: Throwable) {}
                try {
                    XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "SECURITY_PATCH", parsed.securityPatch)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun hookCpuAndProcInfo(
        classLoader: ClassLoader,
        cpuVals: CpuHardwareRandomValues,
        pkg: String = "",
        isSettingsPackage: Boolean = false
    ) {
        // Safe in-memory readLine sanitization in hookRootAndEnvironmentCloak handles /proc/cpuinfo safely
        // without redirecting to /data/local/tmp (which untrusted target apps cannot read due to SELinux).
        // without redirecting to /data/local/tmp (which untrusted target apps cannot read due to SELinux).

        // 3. In diagnostic apps (e.g. Device Info, CPU-Z), replace "Unable to retrieve CPU information. Please try again"
        // with randomized CPU/SoC specifications, hide its "Retry" button, and ensure all CPU UI rows reflect random CPU values
        val isDeviceInfoApp = pkg.contains("cpu", ignoreCase = true) ||
                              pkg.contains("deviceinfo", ignoreCase = true) ||
                              pkg.contains("aida64", ignoreCase = true) ||
                              pkg.contains("antutu", ignoreCase = true) ||
                              pkg.contains("geekbench", ignoreCase = true)
        if (!isSettingsPackage && isDeviceInfoApp) {
            try {
                XposedHelpers.findAndHookMethod(
                    android.widget.TextView::class.java,
                    "setText",
                    CharSequence::class.java,
                    android.widget.TextView.BufferType::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val orig = param.args[0]?.toString() ?: return
                            val trimmed = orig.trim()
                            if (trimmed.isEmpty()) return

                            when {
                                trimmed.contains("Unable to retrieve CPU information", ignoreCase = true) -> {
                                    param.args[0] = cpuVals.topCardSpecSummary
                                    val tv = param.thisObject as? android.widget.TextView
                                    tv?.post {
                                        try {
                                            val parentGrp = tv.parent as? android.view.ViewGroup
                                            if (parentGrp != null) {
                                                for (i in 0 until parentGrp.childCount) {
                                                    val child = parentGrp.getChildAt(i)
                                                    if (child !== tv) {
                                                        child.visibility = android.view.View.GONE
                                                    }
                                                }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                                trimmed.equals("RMX3888", ignoreCase = true) ||
                                    trimmed.equals("qcom", ignoreCase = true) -> {
                                    param.args[0] = cpuVals.cpuHardware
                                }
                                trimmed.contains("Snapdragon 8 Gen 2", ignoreCase = true) -> {
                                    param.args[0] = cpuVals.processorFull
                                }
                                trimmed.contains("3.01 GHz") && trimmed.contains("2.80 GHz") -> {
                                    param.args[0] = cpuVals.archClusterSummary
                                }
                                trimmed.contains("3014 MHz") && trimmed.contains("2803 MHz") -> {
                                    param.args[0] = cpuVals.freqRangeSummary
                                }
                                trimmed.equals("walt", ignoreCase = true) -> {
                                    param.args[0] = cpuVals.governor
                                }
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }
    }

    private val sysPropGetMethod: java.lang.reflect.Method? by lazy {
        try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readSysPropInternal(key: String): String {
        val m = sysPropGetMethod ?: return ""
        val prev = isInternalFileAccess.get() == true
        isInternalFileAccess.set(true)
        return try {
            var v = (m.invoke(null, key, "") as? String)?.trim().orEmpty()
            if (v.isEmpty() && key.startsWith("persist.sys.phantom.")) {
                val suffix = key.removePrefix("persist.sys.phantom.")
                v = (m.invoke(null, "debug.phantom.$suffix", "") as? String)?.trim().orEmpty()
                if (v.isEmpty()) {
                    v = (m.invoke(null, "log.tag.phantom.$suffix", "") as? String)?.trim().orEmpty()
                }
            } else if (v.isEmpty() && key.startsWith("debug.phantom.")) {
                val suffix = key.removePrefix("debug.phantom.")
                v = (m.invoke(null, "log.tag.phantom.$suffix", "") as? String)?.trim().orEmpty()
                if (v.isEmpty()) {
                    v = (m.invoke(null, "persist.sys.phantom.$suffix", "") as? String)?.trim().orEmpty()
                }
            }
            v
        } catch (_: Throwable) {
            ""
        } finally {
            isInternalFileAccess.set(prev)
        }
    }

    private fun hookAndroidId(
        classLoader: ClassLoader,
        newAndroidId: String,
        hideDevAndAdb: Boolean = true,
        newAdvId: String = "",
        newDeviceModel: String = "",
        newBtAddress: String = ""
    ) {
        val settingClasses = arrayOf(
            "android.provider.Settings\$Secure",
            "android.provider.Settings\$Global",
            "android.provider.Settings\$System"
        )
        for (clsName in settingClasses) {
            try {
                val cls = XposedHelpers.findClass(clsName, classLoader) ?: continue

                XposedHelpers.findAndHookMethod(
                    cls,
                    "getString",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val name = (param.args[1] as? String)?.lowercase() ?: return
                            when {
                                name == "android_id" && newAndroidId.isNotBlank() -> {
                                    val liveAid = readFreshSysProp("persist.sys.phantom.aid", newAndroidId)
                                    if (liveAid.isNotBlank()) param.result = liveAid
                                }
                                name == "advertising_id" && newAdvId.isNotBlank() -> {
                                    val liveAdv = readFreshSysProp("persist.sys.phantom.adv", newAdvId)
                                    if (liveAdv.isNotBlank()) param.result = liveAdv
                                }
                                name == "limit_ad_tracking" && newAdvId.isNotBlank() -> param.result = "0"
                                (name == "device_name" || name == "bluetooth_name") && newDeviceModel.isNotBlank() -> {
                                    val liveMod = readFreshSysProp("persist.sys.phantom.mod", newDeviceModel)
                                    if (liveMod.isNotBlank()) param.result = liveMod
                                }
                                name == "bluetooth_address" && newBtAddress.isNotBlank() -> {
                                    val liveBt = readFreshSysProp("persist.sys.phantom.bt", newBtAddress)
                                    if (liveBt.isNotBlank()) param.result = liveBt.uppercase()
                                }
                                name == "location_providers_allowed" -> param.result = "gps,network"
                                name == "location_mode" -> param.result = "3"
                                name == "mock_location" || name == "allow_mock_location" -> param.result = "0"
                                hideDevAndAdb && (name == "adb_enabled" || name == "development_settings_enabled") -> param.result = "0"
                            }
                        }
                    }
                )

                try {
                    XposedHelpers.findAndHookMethod(
                        cls,
                        "getStringForUser",
                        android.content.ContentResolver::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (param.throwable != null) return
                                val name = (param.args[1] as? String)?.lowercase() ?: return
                                when {
                                    name == "android_id" && newAndroidId.isNotBlank() -> {
                                        val liveAid = readFreshSysProp("persist.sys.phantom.aid", newAndroidId)
                                        if (liveAid.isNotBlank()) param.result = liveAid
                                    }
                                    name == "advertising_id" && newAdvId.isNotBlank() -> {
                                        val liveAdv = readFreshSysProp("persist.sys.phantom.adv", newAdvId)
                                        if (liveAdv.isNotBlank()) param.result = liveAdv
                                    }
                                    name == "limit_ad_tracking" && newAdvId.isNotBlank() -> param.result = "0"
                                    (name == "device_name" || name == "bluetooth_name") && newDeviceModel.isNotBlank() -> {
                                        val liveMod = readFreshSysProp("persist.sys.phantom.mod", newDeviceModel)
                                        if (liveMod.isNotBlank()) param.result = liveMod
                                    }
                                    name == "bluetooth_address" && newBtAddress.isNotBlank() -> {
                                        val liveBt = readFreshSysProp("persist.sys.phantom.bt", newBtAddress)
                                        if (liveBt.isNotBlank()) param.result = liveBt.uppercase()
                                    }
                                    name == "location_providers_allowed" -> param.result = "gps,network"
                                    name == "location_mode" -> param.result = "3"
                                    name == "mock_location" || name == "allow_mock_location" -> param.result = "0"
                                    hideDevAndAdb && (name == "adb_enabled" || name == "development_settings_enabled") -> param.result = "0"
                                }
                            }
                        }
                    )
                } catch (_: Throwable) {}

                XposedHelpers.findAndHookMethod(
                    cls,
                    "getInt",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val name = (param.args[1] as? String)?.lowercase() ?: return
                            if (name == "location_mode") {
                                param.result = 3
                            } else if (name == "mock_location" || name == "allow_mock_location") {
                                param.result = 0
                            } else if (hideDevAndAdb && (name == "adb_enabled" || name == "development_settings_enabled")) {
                                param.result = 0
                            }
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    cls,
                    "getLong",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val name = (param.args[1] as? String)?.lowercase() ?: return
                            if (name == "mock_location" || name == "allow_mock_location") {
                                param.result = 0L
                            } else if (hideDevAndAdb && (name == "adb_enabled" || name == "development_settings_enabled")) {
                                param.result = 0L
                            }
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    cls,
                    "getFloat",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val name = (param.args[1] as? String)?.lowercase() ?: return
                            if (name == "mock_location" || name == "allow_mock_location") {
                                param.result = 0.0f
                            } else if (hideDevAndAdb && (name == "adb_enabled" || name == "development_settings_enabled")) {
                                param.result = 0.0f
                            }
                        }
                    }
                )

                if (hideDevAndAdb) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            cls,
                            "getInt",
                            android.content.ContentResolver::class.java,
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (param.throwable != null) return
                                    val name = (param.args[1] as? String)?.lowercase() ?: return
                                    if (name == "adb_enabled" || name == "development_settings_enabled" || name == "mock_location" || name == "allow_mock_location") {
                                        param.result = 0
                                    }
                                }
                            }
                        )
                    } catch (_: Throwable) {}

                    try {
                        XposedHelpers.findAndHookMethod(
                            cls,
                            "getInt",
                            android.content.ContentResolver::class.java,
                            String::class.java,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (param.throwable != null) return
                                    val name = (param.args[1] as? String)?.lowercase() ?: return
                                    if (name == "adb_enabled" || name == "development_settings_enabled" || name == "mock_location" || name == "allow_mock_location") {
                                        param.result = 0
                                    }
                                }
                            }
                        )
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        // Also hook Settings$NameValueCache.getStringForUser & ContentResolver.call / query (used by Unity native / low-level callers)
        try {
            val nvcCls = XposedHelpers.findClass("android.provider.Settings\$NameValueCache", classLoader)
            if (nvcCls != null) {
                hookAllMethodsCompat(nvcCls, "getStringForUser", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val name = (param.args.getOrNull(1) as? String)?.lowercase() ?: return
                        val liveAid = readFreshSysProp("persist.sys.phantom.aid", newAndroidId)
                        val liveAdv = readFreshSysProp("persist.sys.phantom.adv", newAdvId)
                        val liveMod = readFreshSysProp("persist.sys.phantom.mod", newDeviceModel)
                        when {
                            name == "android_id" && liveAid.isNotBlank() -> param.result = liveAid
                            name == "advertising_id" && liveAdv.isNotBlank() -> param.result = liveAdv
                            (name == "device_name" || name == "bluetooth_name") && liveMod.isNotBlank() -> param.result = liveMod
                            hideDevAndAdb && (name == "adb_enabled" || name == "development_settings_enabled" || name == "mock_location") -> param.result = "0"
                        }
                    }
                })
            }
        } catch (_: Throwable) {}

        try {
            hookAllMethodsCompat(android.content.ContentResolver::class.java, "call", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val method = param.args.getOrNull(1) as? String ?: return
                    val arg = (param.args.getOrNull(2) as? String)?.lowercase() ?: return
                    if (method == "GET_secure" || method == "GET_global" || method == "GET_system") {
                        val liveAid = readFreshSysProp("persist.sys.phantom.aid", newAndroidId)
                        val liveAdv = readFreshSysProp("persist.sys.phantom.adv", newAdvId)
                        val liveMod = readFreshSysProp("persist.sys.phantom.mod", newDeviceModel)
                        when {
                            arg == "android_id" && liveAid.isNotBlank() -> {
                                val b = (param.result as? android.os.Bundle) ?: android.os.Bundle()
                                b.putString("value", liveAid)
                                param.result = b
                            }
                            arg == "advertising_id" && liveAdv.isNotBlank() -> {
                                val b = (param.result as? android.os.Bundle) ?: android.os.Bundle()
                                b.putString("value", liveAdv)
                                param.result = b
                            }
                            (arg == "device_name" || arg == "bluetooth_name") && liveMod.isNotBlank() -> {
                                val b = (param.result as? android.os.Bundle) ?: android.os.Bundle()
                                b.putString("value", liveMod)
                                param.result = b
                            }
                        }
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            hookAllMethodsCompat(android.content.ContentResolver::class.java, "query", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val uri = param.args.firstOrNull() as? android.net.Uri ?: return
                    val uriStr = uri.toString().lowercase()
                    if (uriStr.startsWith("content://settings/secure") ||
                        uriStr.startsWith("content://settings/global") ||
                        uriStr.startsWith("content://settings/system")
                    ) {
                        val liveAid = readFreshSysProp("persist.sys.phantom.aid", newAndroidId)
                        val liveAdv = readFreshSysProp("persist.sys.phantom.adv", newAdvId)
                        val liveMod = readFreshSysProp("persist.sys.phantom.mod", newDeviceModel)
                        val lastSeg = uri.lastPathSegment?.lowercase() ?: ""
                        val selArgs = (param.args.getOrNull(3) as? Array<*>)?.mapNotNull { it?.toString()?.lowercase() } ?: emptyList()
                        val sel = (param.args.getOrNull(2) as? String)?.lowercase() ?: ""
                        val targetKey = when {
                            lastSeg == "android_id" || selArgs.contains("android_id") || sel.contains("android_id") -> "android_id"
                            lastSeg == "advertising_id" || selArgs.contains("advertising_id") || sel.contains("advertising_id") -> "advertising_id"
                            lastSeg == "device_name" || selArgs.contains("device_name") || sel.contains("device_name") -> "device_name"
                            lastSeg == "bluetooth_name" || selArgs.contains("bluetooth_name") || sel.contains("bluetooth_name") -> "bluetooth_name"
                            else -> ""
                        }
                        val spoofVal = when (targetKey) {
                            "android_id" -> liveAid
                            "advertising_id" -> liveAdv
                            "device_name", "bluetooth_name" -> liveMod
                            else -> ""
                        }
                        if (targetKey.isNotEmpty() && spoofVal.isNotBlank()) {
                            val origCursor = param.result as? android.database.Cursor
                            val cols = origCursor?.columnNames ?: arrayOf("name", "value")
                            val mc = android.database.MatrixCursor(cols)
                            val row = Array<Any?>(cols.size) { idx ->
                                when (cols[idx].lowercase()) {
                                    "_id" -> 1
                                    "name" -> targetKey
                                    "value" -> spoofVal
                                    else -> spoofVal
                                }
                            }
                            mc.addRow(row)
                            try { origCursor?.close() } catch (_: Throwable) {}
                            param.result = mc
                        }
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookTelephony(
        classLoader: ClassLoader,
        newImei: String,
        simVals: SimStatusRandomValues,
        isSettingsPackage: Boolean = false
    ) {
        val newCarrier = simVals.carrier
        val newImsi = simVals.imsi
        val newIccid = simVals.iccid
        val newPhone = simVals.phoneNumber

        try {
            val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader)
            if (tmClass != null) {
                if (newImei.isNotBlank()) {
                    hookAllMethodsCompat(tmClass, "getDeviceId", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isSettingsPackage && param.throwable != null) return
                            param.result = newImei
                        }
                    })
                    hookAllMethodsCompat(tmClass, "getImei", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isSettingsPackage && param.throwable != null) return
                            param.result = newImei
                        }
                    })
                    hookAllMethodsCompat(tmClass, "getDeviceSoftwareVersion", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isSettingsPackage && param.throwable != null) return
                            param.result = simVals.imeiSv
                        }
                    })
                }

                val isoLower = simVals.countryCode.lowercase()

                hookAllMethodsCompat(tmClass, "getSimOperatorName", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = newCarrier
                    }
                })
                hookAllMethodsCompat(tmClass, "getSimOperatorNameForPhone", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = newCarrier
                    }
                })
                hookAllMethodsCompat(tmClass, "getNetworkOperatorName", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = newCarrier
                    }
                })
                hookAllMethodsCompat(tmClass, "getSimCarrierIdName", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = newCarrier
                    }
                })
                hookAllMethodsCompat(tmClass, "getSimSpecificCarrierIdName", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = newCarrier
                    }
                })

                hookAllMethodsCompat(tmClass, "getLine1Number", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = newPhone
                    }
                })
                hookAllMethodsCompat(tmClass, "getMsisdn", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = newPhone
                    }
                })
                hookAllMethodsCompat(tmClass, "getVoiceMailNumber", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = newPhone
                    }
                })

                hookAllMethodsCompat(tmClass, "getSubscriberId", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = newImsi
                    }
                })
                hookAllMethodsCompat(tmClass, "getSimSerialNumber", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = newIccid
                    }
                })
                hookAllMethodsCompat(tmClass, "getSimCountryIso", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = isoLower
                    }
                })
                hookAllMethodsCompat(tmClass, "getNetworkCountryIso", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = isoLower
                    }
                })
                hookAllMethodsCompat(tmClass, "getSimOperator", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = simVals.mccMnc
                    }
                })
                hookAllMethodsCompat(tmClass, "getSimOperatorNumeric", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = simVals.mccMnc
                    }
                })
                hookAllMethodsCompat(tmClass, "getNetworkOperator", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = simVals.mccMnc
                    }
                })

                // Report active SIM / eSIM ready state so apps & Google Play Services never fail with "Ensure that you have an active eSIM or SIM card"
                hookAllMethodsCompat(tmClass, "getSimState", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = 5 // TelephonyManager.SIM_STATE_READY
                    }
                })
                hookAllMethodsCompat(tmClass, "hasIccCard", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = true
                    }
                })
                hookAllMethodsCompat(tmClass, "getSimCardState", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = 11 // TelephonyManager.SIM_STATE_PRESENT
                    }
                })
                hookAllMethodsCompat(tmClass, "getSimApplicationState", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = 10 // TelephonyManager.SIM_STATE_LOADED
                    }
                })
                hookAllMethodsCompat(tmClass, "getPhoneType", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = 1 // TelephonyManager.PHONE_TYPE_GSM
                    }
                })
                hookAllMethodsCompat(tmClass, "getDataState", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = 2 // TelephonyManager.DATA_CONNECTED
                    }
                })
                hookAllMethodsCompat(tmClass, "getNetworkType", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = 13 // TelephonyManager.NETWORK_TYPE_LTE
                    }
                })
                hookAllMethodsCompat(tmClass, "getDataNetworkType", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = 13 // TelephonyManager.NETWORK_TYPE_LTE
                    }
                })
                hookAllMethodsCompat(tmClass, "isNetworkRoaming", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isSettingsPackage && param.throwable != null) return
                        param.result = false
                    }
                })
            }
        } catch (_: Throwable) {}

        // Also hook SubscriptionInfo & SubscriptionManager so apps and Android 13+/14+ getPhoneNumber() / getActiveSubscriptionInfoList()
        // always see an active SIM/eSIM and the spoofed E.164 phone number even on SIM-less devices or inside Dual Space
        try {
            val subInfoCls = XposedHelpers.findClass("android.telephony.SubscriptionInfo", classLoader)
            val mccInt = simVals.mccMnc.take(3).toIntOrNull() ?: 310
            val mncInt = simVals.mccMnc.drop(3).toIntOrNull() ?: 260
            val mccStr = simVals.mccMnc.take(3).ifBlank { "310" }
            val mncStr = simVals.mccMnc.drop(3).ifBlank { "260" }
            val isoLower = simVals.countryCode.lowercase()

            if (subInfoCls != null) {
                hookAllMethodsCompat(subInfoCls, "getNumber", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = newPhone
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getCarrierName", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = newCarrier
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getDisplayName", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = newCarrier
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getCountryIso", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = isoLower
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getIccId", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = newIccid
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getCardString", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = newIccid
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getMcc", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = mccInt
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getMnc", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = mncInt
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getMccString", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = mccStr
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getMncString", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = mncStr
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getSubscriptionId", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val cur = param.result as? Int ?: -1
                        if (cur <= 0) param.result = 1
                    }
                })
                hookAllMethodsCompat(subInfoCls, "getSimSlotIndex", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val cur = param.result as? Int ?: -1
                        if (cur < 0) param.result = 0
                    }
                })
            }

            val subMgrCls = XposedHelpers.findClass("android.telephony.SubscriptionManager", classLoader)
            if (subMgrCls != null) {
                hookAllMethodsCompat(subMgrCls, "getPhoneNumber", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = newPhone
                    }
                })
                hookAllMethodsCompat(subMgrCls, "getActiveSubscriptionInfoCount", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cur = param.result as? Int ?: 0
                        if (cur <= 0) param.result = 1
                    }
                })
                hookAllMethodsCompat(subMgrCls, "getActiveSubscriptionInfoCountMax", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cur = param.result as? Int ?: 0
                        if (cur <= 0) param.result = 2
                    }
                })
                for (mName in listOf(
                    "getDefaultSubscriptionId",
                    "getDefaultDataSubscriptionId",
                    "getDefaultVoiceSubscriptionId",
                    "getDefaultSmsSubscriptionId"
                )) {
                    hookAllMethodsCompat(subMgrCls, mName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val cur = param.result as? Int ?: -1
                            if (cur <= 0) param.result = 1
                        }
                    })
                }
                hookAllMethodsCompat(subMgrCls, "getActiveSubscriptionIdList", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val arr = param.result as? IntArray
                        if (arr == null || arr.isEmpty()) {
                            param.result = intArrayOf(1)
                        }
                    }
                })

                val createSyntheticSubInfo = fun(): Any? {
                    if (subInfoCls == null) return null
                    return try {
                        val unsafeCls = Class.forName("sun.misc.Unsafe")
                        val theUnsafeField = unsafeCls.getDeclaredField("theUnsafe").apply { isAccessible = true }
                        val unsafe = theUnsafeField.get(null)
                        val allocateInstance = unsafeCls.getMethod("allocateInstance", Class::class.java)
                        val inst = allocateInstance.invoke(unsafe, subInfoCls)
                        fun setFieldSafe(name: String, value: Any?) {
                            try {
                                val f = subInfoCls.getDeclaredField(name)
                                f.isAccessible = true
                                f.set(inst, value)
                            } catch (_: Throwable) {}
                        }
                        setFieldSafe("mId", 1)
                        setFieldSafe("mIccId", newIccid)
                        setFieldSafe("mSimSlotIndex", 0)
                        setFieldSafe("mDisplayName", newCarrier)
                        setFieldSafe("mCarrierName", newCarrier)
                        setFieldSafe("mNumber", newPhone)
                        setFieldSafe("mMcc", mccInt)
                        setFieldSafe("mMnc", mncInt)
                        setFieldSafe("mMccString", mccStr)
                        setFieldSafe("mMncString", mncStr)
                        setFieldSafe("mCountryIso", isoLower)
                        setFieldSafe("mIsEmbedded", true)
                        setFieldSafe("mCardString", newIccid)
                        inst
                    } catch (_: Throwable) {
                        null
                    }
                }

                for (listMethod in listOf(
                    "getActiveSubscriptionInfoList",
                    "getCompleteActiveSubscriptionInfoList",
                    "getAccessibleSubscriptionInfoList",
                    "getAvailableSubscriptionInfoList"
                )) {
                    hookAllMethodsCompat(subMgrCls, listMethod, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val list = param.result as? List<*>
                            if (list.isNullOrEmpty()) {
                                val syn = createSyntheticSubInfo()
                                if (syn != null) {
                                    param.result = java.util.Collections.singletonList(syn)
                                }
                            }
                        }
                    })
                }

                for (singleMethod in listOf(
                    "getActiveSubscriptionInfo",
                    "getActiveSubscriptionInfoForSimSlotIndex",
                    "getActiveSubscriptionInfoForIccIndex"
                )) {
                    hookAllMethodsCompat(subMgrCls, singleMethod, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.result == null) {
                                val syn = createSyntheticSubInfo()
                                if (syn != null) {
                                    param.result = syn
                                }
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // Hook EuiccManager so eSIM checks report enabled/active
        try {
            val euiccCls = XposedHelpers.findClass("android.telephony.euicc.EuiccManager", classLoader)
            if (euiccCls != null) {
                hookAllMethodsCompat(euiccCls, "isEnabled", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = true
                    }
                })
            }
        } catch (_: Throwable) {}

        // Hook TelecomManager.getLine1Number so apps checking phone number via Telecom service see the spoofed number
        try {
            val telecomCls = XposedHelpers.findClass("android.telecom.TelecomManager", classLoader)
            if (telecomCls != null) {
                hookAllMethodsCompat(telecomCls, "getLine1Number", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = newPhone
                    }
                })
            }
        } catch (_: Throwable) {}
    }

    private fun hookWifi(classLoader: ClassLoader, newMac: String, newIp: String = "", seed: Int = 1001) {
        val cleanMac = newMac.lowercase()
        // Derive a deterministic spoofed router BSSID and SSID per reset so router/network history never links accounts across resets
        val bssidBytes = ByteArray(6) { i ->
            val v = ((seed shr (i * 4)) xor (seed / (i + 1))) and 0xFF
            (if (i == 0) (v and 0xFE) or 0x02 else v).toByte()
        }
        val fakeBssid = readSysPropInternal("persist.sys.phantom.bssid")
            .ifBlank { readSysPropInternal("debug.phantom.bssid") }
            .ifBlank { bssidBytes.joinToString(":") { "%02x".format(it) } }
        val ssidNames = arrayOf("TP-Link_5G", "Netgear_AX", "ASUS_RT_5G", "HomeFiber_5G", "Starlink_WiFi", "UniFi_Pro", "Linksys_Mesh", "Eero_Pro_6E")
        val propSsid = readSysPropInternal("persist.sys.phantom.ssid").ifBlank { readSysPropInternal("debug.phantom.ssid") }
        val fakeSsid = if (propSsid.isNotBlank()) "\"${propSsid.removeSurrounding("\"")}\"" else "\"${ssidNames[seed % ssidNames.size]}_${100 + (seed % 899)}\""

        try {
            val wmClass = XposedHelpers.findClass("android.net.wifi.WifiManager", classLoader)
            if (wmClass != null) {
                if (cleanMac.isNotBlank()) {
                    XposedHelpers.findAndHookMethod(wmClass, "getFactoryMacAddresses", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            param.result = arrayOf(cleanMac)
                        }
                    })
                }
                // Hide nearby Wi-Fi scan history so apps cannot fingerprint physical router BSSIDs
                hookAllMethodsCompat(wmClass, "getScanResults", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = java.util.Collections.emptyList<Any>()
                    }
                })
                hookAllMethodsCompat(wmClass, "getConfiguredNetworks", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = java.util.Collections.emptyList<Any>()
                    }
                })
            }
        } catch (_: Throwable) {}

        try {
            val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
            if (wifiInfoClass != null) {
                if (cleanMac.isNotBlank()) {
                    XposedHelpers.findAndHookMethod(wifiInfoClass, "getMacAddress", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            param.result = cleanMac
                        }
                    })
                }
                XposedHelpers.findAndHookMethod(wifiInfoClass, "getBSSID", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = fakeBssid
                    }
                })
                XposedHelpers.findAndHookMethod(wifiInfoClass, "getSSID", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = fakeSsid
                    }
                })
                XposedHelpers.findAndHookMethod(wifiInfoClass, "getNetworkId", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = 1 + (seed % 50)
                    }
                })
                if (newIp.isNotBlank()) {
                    val ipParts = newIp.split(".")
                    if (ipParts.size == 4) {
                        val ipInt = try {
                            (ipParts[0].toInt() and 0xFF) or
                                ((ipParts[1].toInt() and 0xFF) shl 8) or
                                ((ipParts[2].toInt() and 0xFF) shl 16) or
                                ((ipParts[3].toInt() and 0xFF) shl 24)
                        } catch (_: Throwable) { 0 }
                        if (ipInt != 0) {
                            XposedHelpers.findAndHookMethod(wifiInfoClass, "getIpAddress", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (param.throwable != null) return
                                    param.result = ipInt
                                }
                            })
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        try {
            val niClass = XposedHelpers.findClass("java.net.NetworkInterface", classLoader)
            if (niClass != null && cleanMac.isNotBlank()) {
                val macBytes = parseMacToBytes(cleanMac)
                if (macBytes != null) {
                    XposedHelpers.findAndHookMethod(niClass, "getHardwareAddress", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val ni = param.thisObject as? java.net.NetworkInterface ?: return
                            val name = ni.name ?: ""
                            if (name.contains("wlan", ignoreCase = true) || name.contains("eth", ignoreCase = true)) {
                                param.result = macBytes
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun parseMacToBytes(mac: String): ByteArray? {
        return try {
            val parts = mac.split(":")
            if (parts.size != 6) return null
            ByteArray(6) { i -> parts[i].toInt(16).toByte() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookBluetooth(classLoader: ClassLoader, newBt: String, newDeviceModel: String = "") {
        val cleanBt = newBt.lowercase()

        try {
            val btAdapterClass = XposedHelpers.findClass("android.bluetooth.BluetoothAdapter", classLoader)
            if (btAdapterClass != null) {
                if (cleanBt.isNotBlank()) {
                    XposedHelpers.findAndHookMethod(btAdapterClass, "getAddress", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            param.result = cleanBt.uppercase()
                        }
                    })
                }
                if (newDeviceModel.isNotBlank()) {
                    XposedHelpers.findAndHookMethod(btAdapterClass, "getName", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            param.result = newDeviceModel
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookTimezone(classLoader: ClassLoader, newTz: String) {
        val initialTz = readSysPropInternal("persist.sys.phantom.tz").ifBlank { newTz }
        if (initialTz.isBlank()) return
        try {
            val resolveLiveTzId = fun(): String {
                return readSysPropInternal("persist.sys.phantom.tz").ifBlank { initialTz }
            }
            val initialTzObj = TimeZone.getTimeZone(initialTz)
            try {
                TimeZone.setDefault(initialTzObj)
            } catch (_: Throwable) {}

            // Hook java.util.TimeZone.getDefault()
            XposedHelpers.findAndHookMethod(
                TimeZone::class.java,
                "getDefault",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = TimeZone.getTimeZone(resolveLiveTzId())
                    }
                }
            )

            // Hook java.util.TimeZone.getDefaultRef() (Internal package-private method)
            try {
                XposedHelpers.findAndHookMethod(
                    TimeZone::class.java,
                    "getDefaultRef",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            param.result = TimeZone.getTimeZone(resolveLiveTzId())
                        }
                    }
                )
            } catch (_: Throwable) {}

            // Hook android.icu.util.TimeZone.getDefault()
            try {
                val icuTzClass = XposedHelpers.findClass("android.icu.util.TimeZone", classLoader)
                if (icuTzClass != null) {
                    val getIcuTzMethod = icuTzClass.getMethod("getTimeZone", String::class.java)
                    XposedHelpers.findAndHookMethod(icuTzClass, "getDefault", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val liveId = resolveLiveTzId()
                            param.result = getIcuTzMethod.invoke(null, liveId)
                        }
                    })
                }
            } catch (_: Throwable) {}

            // Hook java.time.ZoneId.systemDefault() for Java 8+ & Kotlin time APIs
            try {
                val zoneIdCls = XposedHelpers.findClass("java.time.ZoneId", classLoader)
                if (zoneIdCls != null) {
                    XposedHelpers.findAndHookMethod(zoneIdCls, "systemDefault", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val liveId = resolveLiveTzId()
                            param.result = java.time.ZoneId.of(liveId)
                        }
                    })
                }
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private data class LiveWalkSnapshot(
        val lat: Double,
        val lng: Double,
        val speedMps: Float,
        val bearingDeg: Float,
        val totalSteps: Int,
        val isMoving: Boolean,
        val isStepEnabled: Boolean,
        val isSpeedEnabled: Boolean = false,
        val appSpeedMultiplier: Float = 1.0f,
        val timestampMs: Long
    )

    @Volatile
    private var cachedWalkSnapshot: LiveWalkSnapshot? = null
    @Volatile
    private var lastWalkReadMs: Long = 0L
    @Volatile
    private var lastAcceptedWalkPayloadTs: Long = 0L
    @Volatile
    private var lastAcceptedLat: Double = 0.0
    @Volatile
    private var lastAcceptedLng: Double = 0.0
    @Volatile
    private var lastReportedRealGpsMs: Long = 0L

    private val isModifyingLocationInternally = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private fun getDoubleFieldSafe(obj: Any?, fieldName: String): Double {
        if (obj == null) return 0.0
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.getDouble(obj)
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
        return 0.0
    }

    private fun callMethodSafe(obj: Any?, methodName: String, vararg args: Any?): Any? {
        if (obj == null) return null
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val m = cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }
                if (m != null) {
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                }
            } catch (_: Throwable) {}
            cls = cls.superclass
        }
        try {
            val m = obj.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }
            if (m != null) {
                m.isAccessible = true
                return m.invoke(obj, *args)
            }
        } catch (_: Throwable) {}
        return null
    }

    @Volatile
    private var walkPollerStarted: Boolean = false

    @Volatile
    private var onLiveSpeedMultiplierUpdated: ((Float) -> Unit)? = null

    private fun reportRealPhoneLocationIfNeeded(realLat: Double, realLng: Double) {
        if (realLat == 0.0 && realLng == 0.0) return
        if (kotlin.math.abs(realLat) > 85.0 || kotlin.math.abs(realLng) > 180.0) return
        val now = System.currentTimeMillis()
        if ((now - lastReportedRealGpsMs) < 3000L) return
        lastReportedRealGpsMs = now
        Thread {
            try {
                val helperClass = Class.forName("de.robv.android.xposed.AndroidAppHelper")
                val ctx = helperClass.getMethod("currentApplication").invoke(null) as? android.content.Context
                if (ctx != null) {
                    val uri = android.net.Uri.parse("content://com.aistudio.phantomcore.provider/walk_telemetry")
                    ctx.contentResolver.call(uri, "report_real_gps", "$realLat|$realLng", null)
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
    }

    private fun startBackgroundWalkPollerIfNeeded(
        fallbackLat: Double,
        fallbackLng: Double,
        pkg: String,
        initialStepEnabled: Boolean,
        initialSpeedEnabled: Boolean
    ) {
        if (walkPollerStarted) return
        synchronized(this) {
            if (walkPollerStarted) return
            walkPollerStarted = true
            Thread({
                try {
                    Thread.sleep(60L)
                } catch (_: Throwable) {}
                while (true) {
                    try {
                        pollLiveWalkStateInternal(fallbackLat, fallbackLng, pkg, initialStepEnabled, initialSpeedEnabled)
                    } catch (_: Throwable) {}
                    try {
                        Thread.sleep(50L)
                    } catch (_: Throwable) {}
                }
            }, "Phantom-Telemetry-Poller").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }.start()
        }
    }

    private fun readLiveWalkState(
        fallbackLat: Double,
        fallbackLng: Double,
        pkg: String = "",
        initialStepEnabled: Boolean = false,
        initialSpeedEnabled: Boolean = false
    ): LiveWalkSnapshot {
        // Pure lock-free in-memory read on caller threads (SystemClock / SensorEventQueue / RenderThread)
        // so high-frequency game loops and Flutter engines never block on Binder IPC or disk I/O!
        startBackgroundWalkPollerIfNeeded(fallbackLat, fallbackLng, pkg, initialStepEnabled, initialSpeedEnabled)
        val cached = cachedWalkSnapshot
        if (cached != null) {
            return cached
        }
        val initMult = com.example.provider.PhantomIdentityProvider.liveAppSpeedMultiplier.coerceIn(0.001f, 1000.0f)
        val isSelfOrBuiltPkg = pkg.contains("phantomcore", ignoreCase = true) ||
            pkg.contains("com.aistudio.ghostdevice", ignoreCase = true) ||
            pkg.startsWith("com.aistudio.") ||
            pkg.startsWith("com.example") ||
            pkg.endsWith(".ghostdevice") ||
            pkg.contains(".ghostdevice.") ||
            pkg.endsWith(".phantom") ||
            pkg.contains(".phantom.") ||
            pkg == "com.clone.android.dual.space" ||
            pkg.contains("dual.space", ignoreCase = true)
        val speedActive = !isSelfOrBuiltPkg && initialSpeedEnabled
        val initialSnap = LiveWalkSnapshot(
            lat = if (lastAcceptedLat != 0.0) lastAcceptedLat else fallbackLat,
            lng = if (lastAcceptedLng != 0.0) lastAcceptedLng else fallbackLng,
            speedMps = 0f,
            bearingDeg = 0f,
            totalSteps = 0,
            isMoving = false,
            isStepEnabled = !isSelfOrBuiltPkg && initialStepEnabled,
            isSpeedEnabled = speedActive,
            appSpeedMultiplier = if (speedActive) initMult else 1.0f,
            timestampMs = System.currentTimeMillis()
        )
        cachedWalkSnapshot = initialSnap
        return initialSnap
    }

    private fun pollLiveWalkStateInternal(
        fallbackLat: Double,
        fallbackLng: Double,
        pkg: String = "",
        initialStepEnabled: Boolean = false,
        initialSpeedEnabled: Boolean = false
    ): LiveWalkSnapshot {
        val now = System.currentTimeMillis()
        lastWalkReadMs = now
        var lat = if (lastAcceptedLat != 0.0) lastAcceptedLat else fallbackLat
        var lng = if (lastAcceptedLng != 0.0) lastAcceptedLng else fallbackLng
        var spd = 0f
        var brg = 0f
        var steps = 0
        var active = false
        var isStepEnabled: Boolean? = null
        var isSpeedEnabled: Boolean? = null
        var appSpeedMult = com.example.provider.PhantomIdentityProvider.liveAppSpeedMultiplier
        var rawPayload = com.example.provider.PhantomIdentityProvider.liveWalkTelemetryPayload
        var bestSpeedTs = 0L

        if (pkg.contains("phantomcore", ignoreCase = true) ||
            pkg.contains("com.aistudio.ghostdevice", ignoreCase = true) ||
            pkg.startsWith("com.aistudio.") ||
            pkg.startsWith("com.example") ||
            pkg.endsWith(".ghostdevice") ||
            pkg.contains(".ghostdevice.") ||
            pkg.endsWith(".phantom") ||
            pkg.contains(".phantom.")
        ) {
            val disabledSnap = LiveWalkSnapshot(
                lat = lat,
                lng = lng,
                speedMps = 0f,
                bearingDeg = 0f,
                totalSteps = 0,
                isMoving = false,
                isStepEnabled = false,
                isSpeedEnabled = false,
                appSpeedMultiplier = 1.0f,
                timestampMs = now
            )
            cachedWalkSnapshot = disabledSnap
            return disabledSnap
        }

        var effectiveTargetPkg = pkg
        // 1. Query ContentProvider for real-time in-memory walk coordinates, Step state, and Speed state
        try {
            val helperClass = Class.forName("de.robv.android.xposed.AndroidAppHelper")
            var ctx = helperClass.getMethod("currentApplication").invoke(null) as? android.content.Context
            if (ctx == null) {
                try {
                    val atClass = Class.forName("android.app.ActivityThread")
                    ctx = atClass.getMethod("currentApplication").invoke(null) as? android.content.Context
                } catch (_: Throwable) {}
            }
            if (ctx != null) {
                val appPkg = try { ctx.packageName?.trim().orEmpty() } catch (_: Throwable) { "" }
                val appCls = try { ctx.javaClass.name } catch (_: Throwable) { "" }
                if (appPkg.contains("phantomcore", ignoreCase = true) ||
                    appPkg.contains("com.aistudio.ghostdevice", ignoreCase = true) ||
                    appPkg.startsWith("com.aistudio.") ||
                    appPkg.startsWith("com.example") ||
                    appPkg.endsWith(".ghostdevice") ||
                    appPkg.contains(".ghostdevice.") ||
                    appPkg.endsWith(".phantom") ||
                    appPkg.contains(".phantom.") ||
                    appCls.contains("PhantomApp")
                ) {
                    val disabledSnap = LiveWalkSnapshot(
                        lat = lat,
                        lng = lng,
                        speedMps = 0f,
                        bearingDeg = 0f,
                        totalSteps = 0,
                        isMoving = false,
                        isStepEnabled = false,
                        isSpeedEnabled = false,
                        appSpeedMultiplier = 1.0f,
                        timestampMs = now
                    )
                    cachedWalkSnapshot = disabledSnap
                    return disabledSnap
                }
                if (appPkg.isNotBlank() && !isVirtualCloneContainerPkg(appPkg)) {
                    effectiveTargetPkg = appPkg
                }
                val isUnresolvedCloneContainer = isVirtualCloneContainerPkg(effectiveTargetPkg)
                if (!isUnresolvedCloneContainer && effectiveTargetPkg.isNotBlank()) {
                    livePollerPkg = effectiveTargetPkg
                }

                val uri = android.net.Uri.parse("content://com.aistudio.phantomcore.provider/walk_telemetry")
                val bundle = ctx.contentResolver.call(uri, "get_walk_telemetry", null, null)
                if (bundle != null) {
                    val providerPayload = bundle.getString("walk_telemetry_payload") ?: ""
                    if (providerPayload.isNotBlank()) {
                        rawPayload = providerPayload
                        val p = if (providerPayload.contains("|")) providerPayload.split("|") else providerPayload.split(",")
                        bestSpeedTs = p.getOrNull(6)?.toLongOrNull() ?: 0L
                    }
                    val stepPkgsCsv = bundle.getString("step_walk_packages")
                    if (stepPkgsCsv != null && effectiveTargetPkg.isNotBlank()) {
                        val stepSet = stepPkgsCsv.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && it != "__NONE__" }
                            .toSet()
                        isStepEnabled = !isUnresolvedCloneContainer && stepSet.contains(effectiveTargetPkg)
                    }
                    val speedPkgsCsv = bundle.getString("speed_hack_packages")
                    if (speedPkgsCsv != null && effectiveTargetPkg.isNotBlank()) {
                        val speedSet = speedPkgsCsv.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && it != "__NONE__" }
                            .toSet()
                        isSpeedEnabled = !isUnresolvedCloneContainer && speedSet.contains(effectiveTargetPkg)
                    }
                    val adPkgsCsv = bundle.getString("ad_bypass_packages")
                    if (adPkgsCsv != null && effectiveTargetPkg.isNotBlank()) {
                        val adSet = adPkgsCsv.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && it != "__NONE__" }
                            .toSet()
                        liveAdBypassState = !isUnresolvedCloneContainer && adSet.contains(effectiveTargetPkg)
                    }
                    val rootPkgsCsv = bundle.getString("root_bypass_packages")
                    if (rootPkgsCsv != null && effectiveTargetPkg.isNotBlank()) {
                        val rootSet = rootPkgsCsv.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && it != "__NONE__" }
                            .toSet()
                        val globalSpoof = bundle.getBoolean("global_spoof_enabled", false)
                        liveRootBypassState = !isUnresolvedCloneContainer && (rootSet.contains(effectiveTargetPkg) || globalSpoof)
                    }
                    if (bundle.containsKey("app_speed_multiplier")) {
                        val m = bundle.getFloat("app_speed_multiplier", 1.0f)
                        if (m in 0.0005f..2000.0f) {
                            appSpeedMult = m
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        val isCloneContainerFallback = isVirtualCloneContainerPkg(effectiveTargetPkg)

        // 2. Fallback check for Step and Speed button states via system properties if ContentProvider wasn't reachable
        if (isStepEnabled == null) {
            if (isCloneContainerFallback) {
                isStepEnabled = false
            } else if (effectiveTargetPkg.isNotBlank()) {
                val pkgHash = Integer.toUnsignedString(effectiveTargetPkg.hashCode(), 36)
                val wkFlag = readSysPropInternal("debug.phantom.wk.$pkgHash").ifBlank {
                    readSysPropInternal("log.tag.phantom.wk.$pkgHash")
                }
                when (wkFlag) {
                    "1" -> isStepEnabled = true
                    "0" -> isStepEnabled = false
                    else -> {
                        val wkPkgs = readSysPropInternal("persist.sys.phantom.wkpkgs")
                        if (wkPkgs == "__NONE__") {
                            isStepEnabled = false
                        } else if (wkPkgs.isNotBlank()) {
                            val set = wkPkgs.split(",").map { it.trim() }.filter { it != "__NONE__" }.toSet()
                            isStepEnabled = set.contains(effectiveTargetPkg)
                        } else {
                            isStepEnabled = initialStepEnabled
                        }
                    }
                }
            } else {
                isStepEnabled = initialStepEnabled
            }
        }

        if (isSpeedEnabled == null) {
            if (isCloneContainerFallback) {
                isSpeedEnabled = false
            } else if (effectiveTargetPkg.isNotBlank()) {
                val pkgHash = Integer.toUnsignedString(effectiveTargetPkg.hashCode(), 36)
                val spFlag = readSysPropInternal("debug.phantom.sp.$pkgHash").ifBlank {
                    readSysPropInternal("log.tag.phantom.sp.$pkgHash")
                }
                when (spFlag) {
                    "1" -> isSpeedEnabled = true
                    "0" -> isSpeedEnabled = false
                    else -> {
                        val spPkgs = readSysPropInternal("persist.sys.phantom.sppkgs")
                        if (spPkgs == "__NONE__") {
                            isSpeedEnabled = false
                        } else if (spPkgs.isNotBlank()) {
                            val set = spPkgs.split(",").map { it.trim() }.filter { it != "__NONE__" }.toSet()
                            isSpeedEnabled = set.contains(effectiveTargetPkg)
                        } else {
                            isSpeedEnabled = initialSpeedEnabled
                        }
                    }
                }
            } else {
                isSpeedEnabled = initialSpeedEnabled
            }
        }

        // 3. Also check shared telemetry files if ContentProvider was blocked or if a file has a newer timestamp
        val prevGuard = isInternalFileAccess.get() ?: false
        isInternalFileAccess.set(true)
        val candidateFiles = arrayOf(
            "/data/local/tmp/.phantom_walk_state",
            "/sdcard/phantom_walk_state.txt",
            "/sdcard/Download/phantom_walk_state.txt"
        )
        try {
            var bestTs = bestSpeedTs
            if (bestTs == 0L && rawPayload.isNotBlank()) {
                val p = if (rawPayload.contains("|")) rawPayload.split("|") else rawPayload.split(",")
                bestTs = p.getOrNull(6)?.toLongOrNull() ?: 0L
            }
            for (path in candidateFiles) {
                try {
                    val f = File(path)
                    if (f.exists() && f.canRead()) {
                        val txt = f.readText().trim()
                        if (txt.isNotBlank()) {
                            val p = if (txt.contains("|")) txt.split("|") else txt.split(",")
                            val fileTs = p.getOrNull(6)?.toLongOrNull() ?: 0L
                            if (rawPayload.isBlank() || fileTs > bestTs) {
                                rawPayload = txt
                                bestTs = fileTs
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
        } finally {
            isInternalFileAccess.set(prevGuard)
        }

        if (rawPayload.isNotBlank()) {
            val parts = if (rawPayload.contains("|")) rawPayload.split("|") else rawPayload.split(",")
            if (parts.size >= 6) {
                val parsedLat = parts[0].toDoubleOrNull() ?: 0.0
                val parsedLng = parts[1].toDoubleOrNull() ?: 0.0
                val parsedSpd = parts[2].toFloatOrNull() ?: 0f
                val parsedBrg = parts[3].toFloatOrNull() ?: 0f
                val parsedSteps = parts[4].toIntOrNull() ?: 0
                val parsedActive = parts[5] == "1"
                val parsedTs = if (parts.size >= 7) (parts[6].toLongOrNull() ?: 0L) else 0L
                if (parts.size >= 8 && parsedTs >= bestSpeedTs) {
                    parts[7].toFloatOrNull()?.let {
                        if (it in 0.0005f..2000.0f) {
                            appSpeedMult = it
                            bestSpeedTs = parsedTs
                        }
                    }
                }

                // Monotonic timestamp guard: never accept an out-of-order older coordinate
                if (parsedTs == 0L || parsedTs >= lastAcceptedWalkPayloadTs || (lastAcceptedWalkPayloadTs - parsedTs) > 10000L) {
                    if (parsedTs > 0L) lastAcceptedWalkPayloadTs = parsedTs
                    if (parsedLat != 0.0 && parsedLng != 0.0) {
                        lat = parsedLat
                        lng = parsedLng
                        lastAcceptedLat = parsedLat
                        lastAcceptedLng = parsedLng
                    }
                    spd = parsedSpd
                    brg = parsedBrg
                    steps = parsedSteps
                    active = parsedActive
                }
            }
        } else {
            // 4. Fallback to SystemProperties when both ContentProvider and file were blank!
            readSysPropInternal("persist.sys.phantom.lat").toDoubleOrNull()?.let { if (it != 0.0) lat = it }
            readSysPropInternal("persist.sys.phantom.lng").toDoubleOrNull()?.let { if (it != 0.0) lng = it }
            readSysPropInternal("persist.sys.phantom.spd").toFloatOrNull()?.let { if (it >= 0f) spd = it }
            readSysPropInternal("persist.sys.phantom.brg").toFloatOrNull()?.let { if (it >= 0f) brg = it }
            readSysPropInternal("persist.sys.phantom.steps").toIntOrNull()?.let { if (it > steps) steps = it }
            val wact = readSysPropInternal("persist.sys.phantom.wact")
            if (wact == "1") active = true else if (wact == "0" && spd <= 0.05f) active = false
        }

        // Always check timestamped system properties (log.tag.phantom.aspd / debug.phantom.aspd / persist.sys.phantom.aspd)
        // ONLY if their timestamp is newer than ContentProvider / file (or when bestSpeedTs == 0L)!
        for (propKey in arrayOf("log.tag.phantom.aspd", "debug.phantom.aspd", "persist.sys.phantom.aspd")) {
            val rawProp = readSysPropInternal(propKey).trim()
            if (rawProp.isNotEmpty()) {
                val sepIdx = rawProp.indexOf('|')
                if (sepIdx > 0) {
                    val multVal = rawProp.substring(0, sepIdx).toFloatOrNull()
                    val tsVal = rawProp.substring(sepIdx + 1).toLongOrNull() ?: 0L
                    if (multVal != null && multVal in 0.0005f..2000.0f && (bestSpeedTs == 0L || tsVal > bestSpeedTs)) {
                        appSpeedMult = multVal
                        bestSpeedTs = tsVal
                    }
                } else if (bestSpeedTs == 0L) {
                    rawProp.toFloatOrNull()?.let {
                        if (it in 0.0005f..2000.0f) {
                            appSpeedMult = it
                        }
                    }
                }
            }
        }

        val stepActiveFlag = !isCloneContainerFallback && (isStepEnabled == true)
        // Activate speed controller ONLY when Speed toggle is explicitly ON for this target package!
        val speedActiveFlag = !isCloneContainerFallback && (isSpeedEnabled == true)
        val snap = LiveWalkSnapshot(
            lat = lat,
            lng = lng,
            speedMps = if (stepActiveFlag && active) spd else 0f,
            bearingDeg = brg,
            totalSteps = steps,
            isMoving = stepActiveFlag && active && spd > 0.05f,
            isStepEnabled = stepActiveFlag,
            isSpeedEnabled = speedActiveFlag,
            appSpeedMultiplier = if (speedActiveFlag) appSpeedMult.coerceIn(0.001f, 1000.0f) else 1.0f,
            timestampMs = now
        )
        cachedWalkSnapshot = snap
        try {
            onLiveSpeedMultiplierUpdated?.invoke(snap.appSpeedMultiplier)
        } catch (_: Throwable) {}
        return snap
    }

    private fun hookLocation(
        classLoader: ClassLoader,
        lat: Double,
        lng: Double,
        isStepWalkEnabled: Boolean = false,
        pkg: String = ""
    ) {
        try {
            val resolveState = fun(): LiveWalkSnapshot {
                return readLiveWalkState(lat, lng, pkg, isStepWalkEnabled)
            }

            // Hide mock location flags only when Stp is enabled for this package
            try {
                XposedHelpers.findAndHookMethod(Location::class.java, "isFromMockProvider", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (resolveState().isStepEnabled) {
                            param.result = false
                        }
                    }
                })
            } catch (_: Throwable) {}
            try {
                XposedHelpers.findAndHookMethod(Location::class.java, "isMock", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (resolveState().isStepEnabled) {
                            param.result = false
                        }
                    }
                })
            } catch (_: Throwable) {}
            try {
                XposedHelpers.findAndHookMethod(Location::class.java, "getExtras", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val live = resolveState()
                        if (!live.isStepEnabled) return
                        var b = param.result as? android.os.Bundle
                        if (b == null) {
                            b = android.os.Bundle()
                            param.result = b
                        }
                        b.remove("mockLocation")
                        b.putBoolean("mockLocation", false)
                        b.putInt("satellites", 24)
                        b.putInt("satellites_in_view", 24)
                        b.putInt("satellites_used", 18)
                        b.putFloat("meanCn0", 43.5f)
                        b.putFloat("maxCn0", 47.0f)
                        b.putString("rtk_status", "FIX")
                        b.putInt("rtk_solution", 1)
                    }
                })
            } catch (_: Throwable) {}

            val stampLocationInPlace = fun(loc: Location, live: LiveWalkSnapshot) {
                if (live.lat == 0.0 && live.lng == 0.0) return
                val prev = isModifyingLocationInternally.get() ?: false
                isModifyingLocationInternally.set(true)
                try {
                    loc.latitude = live.lat
                    loc.longitude = live.lng
                    loc.altitude = 18.5
                    loc.accuracy = 0.22f
                    loc.speed = live.speedMps
                    loc.bearing = live.bearingDeg
                    loc.time = System.currentTimeMillis()
                    try {
                        loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    } catch (_: Throwable) {}
                    try {
                        loc.verticalAccuracyMeters = 0.28f
                        loc.speedAccuracyMetersPerSecond = 0.04f
                        loc.bearingAccuracyDegrees = 0.6f
                    } catch (_: Throwable) {}
                } catch (_: Throwable) {
                } finally {
                    isModifyingLocationInternally.set(prev)
                }
            }

            // 1. Hook Location getters — ONLY override when Step button is ON!
            // When Step button is OFF, pass real phone GPS location through untouched and report it to WalkBridge.
            XposedHelpers.findAndHookMethod(Location::class.java, "getLatitude", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isModifyingLocationInternally.get() == true) return
                    val live = resolveState()
                    if (!live.isStepEnabled) {
                        val realLat = param.result as? Double ?: 0.0
                        val locObj = param.thisObject as? Location
                        if (realLat != 0.0 && locObj != null) {
                            try {
                                val realLng = getDoubleFieldSafe(locObj, "mLongitude")
                                reportRealPhoneLocationIfNeeded(realLat, realLng)
                            } catch (_: Throwable) {}
                        }
                        return
                    }
                    if (live.lat != 0.0) {
                        param.result = live.lat
                    }
                }
            })
            XposedHelpers.findAndHookMethod(Location::class.java, "getLongitude", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isModifyingLocationInternally.get() == true) return
                    val live = resolveState()
                    if (!live.isStepEnabled) {
                        return
                    }
                    if (live.lng != 0.0) {
                        param.result = live.lng
                    }
                }
            })

            try {
                XposedHelpers.findAndHookMethod(Location::class.java, "getAccuracy", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = 0.22f // Centimeter RTK accuracy required for Proof of Location
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "hasAccuracy", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = true
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "getAltitude", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = 18.5
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "hasAltitude", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = true
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "hasCarrierFrequencyHz", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = true
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "getCarrierFrequencyHz", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = 1575420000.0f // L1 / L5 Dual-Frequency GNSS
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "hasElapsedRealtimeUncertaintyNanos", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = true
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "getElapsedRealtimeUncertaintyNanos", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = 15.0
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "hasSpeedAccuracy", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = true
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "hasBearingAccuracy", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = true
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "hasVerticalAccuracy", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = true
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "getSpeed", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val live = resolveState()
                        if (!live.isStepEnabled) return
                        param.result = live.speedMps
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "hasSpeed", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = true
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "getBearing", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val live = resolveState()
                        if (!live.isStepEnabled) return
                        param.result = live.bearingDeg
                    }
                })
                XposedHelpers.findAndHookMethod(Location::class.java, "hasBearing", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!resolveState().isStepEnabled) return
                        param.result = true
                    }
                })
                try {
                    XposedHelpers.findAndHookMethod(Location::class.java, "getVerticalAccuracyMeters", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!resolveState().isStepEnabled) return
                            param.result = 0.28f
                        }
                    })
                    XposedHelpers.findAndHookMethod(Location::class.java, "getSpeedAccuracyMetersPerSecond", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!resolveState().isStepEnabled) return
                            param.result = 0.04f
                        }
                    })
                    XposedHelpers.findAndHookMethod(Location::class.java, "getBearingAccuracyDegrees", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!resolveState().isStepEnabled) return
                            param.result = 0.6f
                        }
                    })
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}

            val buildSyntheticLocation = fun(provider: String): Location {
                val live = resolveState()
                val prev = isModifyingLocationInternally.get() ?: false
                isModifyingLocationInternally.set(true)
                return try {
                    Location(provider).apply {
                        latitude = if (live.lat != 0.0) live.lat else 23.8103
                        longitude = if (live.lng != 0.0) live.lng else 90.4125
                        altitude = 18.5
                        accuracy = 0.22f // Centimeter RTK accuracy
                        speed = live.speedMps
                        bearing = live.bearingDeg
                        time = System.currentTimeMillis()
                        try {
                            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        } catch (_: Throwable) {}
                        try {
                            val b = android.os.Bundle().apply {
                                putBoolean("mockLocation", false)
                                putInt("satellites", 24)
                                putInt("satellites_in_view", 24)
                                putInt("satellites_used", 18)
                                putFloat("meanCn0", 43.5f)
                                putFloat("maxCn0", 47.0f)
                                putString("rtk_status", "FIX")
                                putInt("rtk_solution", 1)
                                putFloat("hDop", 0.7f)
                                putFloat("pDop", 0.9f)
                                putFloat("vDop", 0.8f)
                            }
                            extras = b
                        } catch (_: Throwable) {}
                        try {
                            verticalAccuracyMeters = 0.28f
                            speedAccuracyMetersPerSecond = 0.04f
                            bearingAccuracyDegrees = 0.6f
                        } catch (_: Throwable) {}
                    }
                } finally {
                    isModifyingLocationInternally.set(prev)
                }
            }

            // 2. Hook LocationManager
            val lmClass = XposedHelpers.findClass("android.location.LocationManager", classLoader)
            if (lmClass != null) {
                try {
                    XposedHelpers.findAndHookMethod(lmClass, "isLocationEnabled", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (resolveState().isStepEnabled) param.result = true
                        }
                    })
                } catch (_: Throwable) {}

                try {
                    XposedHelpers.findAndHookMethod(lmClass, "isProviderEnabled", String::class.java, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!resolveState().isStepEnabled) return
                            val prov = (param.args[0] as? String)?.lowercase() ?: return
                            if (prov == "gps" || prov == "network" || prov == "fused" || prov == "passive") {
                                param.result = true
                            }
                        }
                    })
                } catch (_: Throwable) {}

                try {
                    XposedHelpers.findAndHookMethod(lmClass, "getProviders", Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!resolveState().isStepEnabled) return
                            param.result = mutableListOf("gps", "network", "passive", "fused")
                        }
                    })
                    XposedHelpers.findAndHookMethod(lmClass, "getAllProviders", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!resolveState().isStepEnabled) return
                            param.result = mutableListOf("gps", "network", "passive", "fused")
                        }
                    })
                    XposedHelpers.findAndHookMethod(
                        lmClass,
                        "getBestProvider",
                        android.location.Criteria::class.java,
                        Boolean::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!resolveState().isStepEnabled) return
                                param.result = "gps"
                            }
                        }
                    )
                } catch (_: Throwable) {}

                XposedHelpers.findAndHookMethod(
                    lmClass,
                    "getLastKnownLocation",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val live = resolveState()
                            if (!live.isStepEnabled) {
                                val realLoc = param.result as? Location
                                if (realLoc != null) {
                                    try {
                                        val rLat = getDoubleFieldSafe(realLoc, "mLatitude")
                                        val rLng = getDoubleFieldSafe(realLoc, "mLongitude")
                                        reportRealPhoneLocationIfNeeded(rLat, rLng)
                                    } catch (_: Throwable) {}
                                }
                                return
                            }
                            val prov = (param.args[0] as? String) ?: "gps"
                            param.result = buildSyntheticLocation(prov)
                        }
                    }
                )

                try {
                    hookAllMethodsCompat(lmClass, "getCurrentLocation", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!resolveState().isStepEnabled) return
                            for (arg in param.args) {
                                if (arg != null && (arg.javaClass.name.contains("Consumer") || arg.javaClass.name.contains("Function"))) {
                                    try {
                                        val acceptMethod = arg.javaClass.methods.firstOrNull { it.name == "accept" && it.parameterTypes.size == 1 }
                                        acceptMethod?.isAccessible = true
                                        acceptMethod?.invoke(arg, buildSyntheticLocation("gps"))
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                    })
                } catch (_: Throwable) {}

                // Hook GnssStatus, GnssMeasurements, and RTK satellite telemetry
                try {
                    val gnssStatusClass = XposedHelpers.findClass("android.location.GnssStatus", classLoader)
                    if (gnssStatusClass != null) {
                        try {
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "getSatelliteCount", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 24
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "getConstellationType", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    val i = param.args[0] as Int
                                    param.result = if (i < 12) 1 else if (i < 18) 6 else 5 // 1=GPS, 6=GALILEO, 5=BEIDOU
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "getSvid", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    val i = param.args[0] as Int
                                    param.result = (i % 32) + 1
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "getCn0DbHz", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    val i = param.args[0] as Int
                                    param.result = 43.5f + (i % 5) * 0.8f
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "getElevationDegrees", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    val i = param.args[0] as Int
                                    param.result = 52.0f + (i * 1.5f) % 35f
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "getAzimuthDegrees", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    val i = param.args[0] as Int
                                    param.result = (i * 15.0f) % 360f
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "usedInFix", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    val i = param.args[0] as Int
                                    param.result = i < 18 // 18 satellites used in fix!
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "hasCarrierFrequencyHz", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "getCarrierFrequencyHz", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    val i = param.args[0] as Int
                                    param.result = if (i % 2 == 0) 1575420000.0f else 1176450000.0f // L1 / L5 Dual-Frequency
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "hasEphemerisData", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssStatusClass, "hasAlmanacData", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                        } catch (_: Throwable) {}
                    }

                    // Hook Raw GNSS Measurements & Clock (Essential for GEODNET / TokenRun Proof of Location RTK engine)
                    // CRITICAL: getCn0DbHz, getAccumulatedDeltaRangeUncertaintyMeters, and getPseudorangeRateUncertaintyMetersPerSecond return primitive DOUBLE (not float)!
                    val gnssMeasureClass = XposedHelpers.findClass("android.location.GnssMeasurement", classLoader)
                    if (gnssMeasureClass != null) {
                        try {
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getState", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 81935
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getAccumulatedDeltaRangeState", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 25
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getAccumulatedDeltaRangeMeters", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 1250.450
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getAccumulatedDeltaRangeUncertaintyMeters", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 0.004 // Double (4 mm uncertainty)
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "hasCarrierFrequencyHz", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getCarrierFrequencyHz", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 1575420000.0f
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getCn0DbHz", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 43.8 // Double!
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "hasSnrInDb", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getSnrInDb", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 43.8 // Double!
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getMultipathIndicator", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 1 // MULTIPATH_INDICATOR_NOT_DETECTED
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "hasCarrierPhase", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getCarrierPhase", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 10000.25
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "hasCarrierCycles", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getCarrierCycles", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 100000000L
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getPseudorangeRateMetersPerSecond", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 0.0
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssMeasureClass, "getPseudorangeRateUncertaintyMetersPerSecond", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 0.04 // Double!
                                }
                            })
                        } catch (_: Throwable) {}
                    }

                    val gnssClockClass = XposedHelpers.findClass("android.location.GnssClock", classLoader)
                    if (gnssClockClass != null) {
                        try {
                            XposedHelpers.findAndHookMethod(gnssClockClass, "hasBiasNanos", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssClockClass, "getBiasNanos", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 0.0
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssClockClass, "hasFullBiasNanos", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssClockClass, "getFullBiasNanos", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = -1234567890123456789L
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssClockClass, "hasBiasUncertaintyNanos", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssClockClass, "getBiasUncertaintyNanos", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 1.0
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssClockClass, "hasDriftNanosPerSecond", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = true
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssClockClass, "getDriftNanosPerSecond", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 0.0
                                }
                            })
                            XposedHelpers.findAndHookMethod(gnssClockClass, "getHardwareClockDiscontinuityCount", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!resolveState().isStepEnabled) return
                                    param.result = 0
                                }
                            })
                        } catch (_: Throwable) {}
                    }

                    val gnssCallbacks = java.util.Collections.synchronizedSet(HashSet<Any>())
                    val gnssMeasureCallbacks = java.util.Collections.synchronizedSet(HashSet<Any>())
                    var gnssLoopStarted = false
                    val startGnssLoop = fun() {
                        if (gnssLoopStarted) return
                        gnssLoopStarted = true
                        val handler = android.os.Handler(android.os.Looper.getMainLooper())
                        val runnable = object : Runnable {
                            override fun run() {
                                try {
                                    val live = resolveState()
                                    if (live.isStepEnabled) {
                                        // 1. Dispatch GnssStatus (Dual-frequency RTK fixed, 24 sats, 18 used in fix)
                                        var statusObj: Any? = null
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                            try {
                                                val builderClass = Class.forName("android.location.GnssStatus\$Builder")
                                                val builderObj = builderClass.getConstructor().newInstance()
                                                val addSatMethod = builderClass.methods.firstOrNull { it.name == "addSatellite" }
                                                if (addSatMethod != null) {
                                                    addSatMethod.isAccessible = true
                                                    val paramCount = addSatMethod.parameterTypes.size
                                                    for (i in 1..24) {
                                                        val constType = if (i <= 12) 1 else if (i <= 18) 6 else 5 // GPS, Galileo, BeiDou
                                                        val cn0 = 42.5f + (i % 5) * 0.9f
                                                        val elev = 50f + (i * 1.5f)
                                                        val azim = (i * 15f) % 360f
                                                        val used = i <= 18
                                                        val freq = if (i % 2 == 0) 1575420000.0f else 1176450000.0f
                                                        if (paramCount >= 12) {
                                                            addSatMethod.invoke(builderObj, constType, i, cn0, elev, azim, true, true, used, true, freq, true, 39.0f)
                                                        } else if (paramCount == 10) {
                                                            addSatMethod.invoke(builderObj, constType, i, cn0, elev, azim, true, true, used, true, freq)
                                                        } else if (paramCount == 8) {
                                                            addSatMethod.invoke(builderObj, constType, i, cn0, elev, azim, true, true, used)
                                                        }
                                                    }
                                                    val buildMethod = builderClass.getMethod("build")
                                                    buildMethod.isAccessible = true
                                                    statusObj = buildMethod.invoke(builderObj)
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                        if (statusObj == null && gnssStatusClass != null) {
                                            try {
                                                for (ctor in gnssStatusClass.declaredConstructors) {
                                                    try {
                                                        ctor.isAccessible = true
                                                        val pts = ctor.parameterTypes
                                                        val dummyArgs = arrayOfNulls<Any>(pts.size)
                                                        for (idx in pts.indices) {
                                                            val pt = pts[idx]
                                                            dummyArgs[idx] = when {
                                                                pt == Int::class.javaPrimitiveType -> 24
                                                                pt == Float::class.javaPrimitiveType -> 43.5f
                                                                pt == IntArray::class.java -> IntArray(24) { it + 1 }
                                                                pt == FloatArray::class.java -> FloatArray(24) { 43.5f }
                                                                else -> null
                                                            }
                                                        }
                                                        statusObj = ctor.newInstance(*dummyArgs)
                                                        if (statusObj != null) break
                                                    } catch (_: Throwable) {}
                                                }
                                            } catch (_: Throwable) {}
                                        }

                                        if (statusObj != null) {
                                            val cbs = synchronized(gnssCallbacks) { gnssCallbacks.toList() }
                                            for (cb in cbs) {
                                                try {
                                                    callMethodSafe(cb, "onSatelliteStatusChanged", statusObj)
                                                } catch (_: Throwable) {
                                                    try {
                                                        val onStatusMethod = cb.javaClass.methods.firstOrNull { it.name == "onSatelliteStatusChanged" && it.parameterTypes.size == 1 }
                                                        onStatusMethod?.isAccessible = true
                                                        onStatusMethod?.invoke(cb, statusObj)
                                                    } catch (_: Throwable) {}
                                                }
                                            }
                                        }

                                        // 2. Dispatch GnssMeasurementsEvent (Raw carrier phase ADR data for Proof of Location RTK engine)
                                        val measureCbs = synchronized(gnssMeasureCallbacks) { gnssMeasureCallbacks.toList() }
                                        if (measureCbs.isNotEmpty()) {
                                            try {
                                                val eventClass = XposedHelpers.findClass("android.location.GnssMeasurementsEvent", classLoader)
                                                val clockClass = XposedHelpers.findClass("android.location.GnssClock", classLoader)
                                                val measureClass = XposedHelpers.findClass("android.location.GnssMeasurement", classLoader)
                                                if (eventClass != null && clockClass != null && measureClass != null) {
                                                    val clockCtor = clockClass.declaredConstructors.firstOrNull()?.apply { isAccessible = true }
                                                    val clockObj = clockCtor?.newInstance()
                                                    if (clockObj != null) {
                                                        try {
                                                            callMethodSafe(clockObj, "setTimeNanos", android.os.SystemClock.elapsedRealtimeNanos())
                                                            callMethodSafe(clockObj, "setFullBiasNanos", -1234567890123456789L)
                                                            callMethodSafe(clockObj, "setBiasNanos", 0.0)
                                                            callMethodSafe(clockObj, "setBiasUncertaintyNanos", 1.0)
                                                            callMethodSafe(clockObj, "setDriftNanosPerSecond", 0.0)
                                                            callMethodSafe(clockObj, "setHardwareClockDiscontinuityCount", 0)
                                                        } catch (_: Throwable) {}
                                                    }
                                                    val measureCtor = measureClass.declaredConstructors.firstOrNull()?.apply { isAccessible = true }
                                                    val measurementsList = mutableListOf<Any>()
                                                    if (clockObj != null && measureCtor != null) {
                                                        val nowNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                                        for (i in 1..18) {
                                                            try {
                                                                val m = measureCtor.newInstance()
                                                                if (m != null) {
                                                                    try {
                                                                        callMethodSafe(m, "setSvid", i)
                                                                        callMethodSafe(m, "setConstellationType", if (i <= 10) 1 else if (i <= 15) 6 else 5)
                                                                        callMethodSafe(m, "setCn0DbHz", 42.5 + (i % 5) * 0.8)
                                                                        callMethodSafe(m, "setState", 81935)
                                                                        callMethodSafe(m, "setReceivedSvTimeNanos", nowNanos - 68000000L)
                                                                        callMethodSafe(m, "setReceivedSvTimeUncertaintyNanos", 12L)
                                                                        callMethodSafe(m, "setAccumulatedDeltaRangeState", 25)
                                                                        callMethodSafe(m, "setAccumulatedDeltaRangeMeters", 20200000.0 + i * 1250.45)
                                                                        callMethodSafe(m, "setAccumulatedDeltaRangeUncertaintyMeters", 0.004)
                                                                        callMethodSafe(m, "setPseudorangeRateMetersPerSecond", -120.0 + i * 14.5)
                                                                        callMethodSafe(m, "setPseudorangeRateUncertaintyMetersPerSecond", 0.04)
                                                                        callMethodSafe(m, "setCarrierFrequencyHz", if (i % 2 == 0) 1575420000.0f else 1176450000.0f)
                                                                        callMethodSafe(m, "setMultipathIndicator", 1)
                                                                    } catch (_: Throwable) {}
                                                                    measurementsList.add(m)
                                                                }
                                                            } catch (_: Throwable) {}
                                                        }
                                                    }
                                                    var eventObj: Any? = null
                                                    if (clockObj != null) {
                                                        for (ctor in eventClass.declaredConstructors) {
                                                            try {
                                                                ctor.isAccessible = true
                                                                val pts = ctor.parameterTypes
                                                                if (pts.size >= 2 && pts[0].isAssignableFrom(clockClass)) {
                                                                    if (java.util.Collection::class.java.isAssignableFrom(pts[1])) {
                                                                        eventObj = ctor.newInstance(clockObj, measurementsList)
                                                                        break
                                                                    } else if (pts[1].isArray) {
                                                                        val arr = java.lang.reflect.Array.newInstance(measureClass, measurementsList.size)
                                                                        for (idx in measurementsList.indices) {
                                                                            java.lang.reflect.Array.set(arr, idx, measurementsList[idx])
                                                                        }
                                                                        eventObj = ctor.newInstance(clockObj, arr)
                                                                        break
                                                                    }
                                                                }
                                                            } catch (_: Throwable) {}
                                                        }
                                                        if (eventObj != null) {
                                                            for (mcb in measureCbs) {
                                                                try {
                                                                    callMethodSafe(mcb, "onGnssMeasurementsReceived", eventObj)
                                                                } catch (_: Throwable) {
                                                                    try {
                                                                        val onMeasureMethod = mcb.javaClass.methods.firstOrNull { it.name == "onGnssMeasurementsReceived" && it.parameterTypes.size == 1 }
                                                                        onMeasureMethod?.isAccessible = true
                                                                        onMeasureMethod?.invoke(mcb, eventObj)
                                                                    } catch (_: Throwable) {}
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                } catch (_: Throwable) {}
                                handler.postDelayed(this, 850L)
                            }
                        }
                        handler.postDelayed(runnable, 350L)
                    }

                    val gnssCallbackClass = XposedHelpers.findClass("android.location.GnssStatus\$Callback", classLoader)
                    hookAllMethodsCompat(lmClass, "registerGnssStatusCallback", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val live = resolveState()
                            if (live.isStepEnabled) {
                                param.result = true
                            }
                            for (arg in param.args) {
                                if (arg != null && (gnssCallbackClass?.isAssignableFrom(arg.javaClass) == true || arg.javaClass.name.contains("GnssStatus"))) {
                                    gnssCallbacks.add(arg)
                                    if (live.isStepEnabled) {
                                        try {
                                            callMethodSafe(arg, "onStarted")
                                            callMethodSafe(arg, "onFirstFix", 180)
                                        } catch (_: Throwable) {}
                                    }
                                    startGnssLoop()
                                }
                            }
                        }
                    })

                    hookAllMethodsCompat(lmClass, "unregisterGnssStatusCallback", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            for (arg in param.args) {
                                if (arg != null) gnssCallbacks.remove(arg)
                            }
                        }
                    })

                    val gnssMeasureCallbackClass = XposedHelpers.findClass("android.location.GnssMeasurementsEvent\$Callback", classLoader)
                    if (gnssMeasureCallbackClass != null) {
                        hookAllMethodsCompat(lmClass, "registerGnssMeasurementsCallback", object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val live = resolveState()
                                if (live.isStepEnabled) {
                                    param.result = true
                                }
                                for (arg in param.args) {
                                    if (arg != null && (gnssMeasureCallbackClass.isAssignableFrom(arg.javaClass) || arg.javaClass.name.contains("GnssMeasurements"))) {
                                        gnssMeasureCallbacks.add(arg)
                                        if (live.isStepEnabled) {
                                            try {
                                                callMethodSafe(arg, "onStatusChanged", 1) // 1 = STATUS_READY
                                            } catch (_: Throwable) {}
                                        }
                                        startGnssLoop()
                                    }
                                }
                            }
                        })
                        hookAllMethodsCompat(lmClass, "unregisterGnssMeasurementsCallback", object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                for (arg in param.args) {
                                    if (arg != null) gnssMeasureCallbacks.remove(arg)
                                }
                            }
                        })
                    }

                    hookAllMethodsCompat(lmClass, "addGpsStatusListener", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val live = resolveState()
                            if (live.isStepEnabled) {
                                param.result = true
                                for (arg in param.args) {
                                    if (arg is android.location.GpsStatus.Listener) {
                                        try {
                                            arg.onGpsStatusChanged(1)
                                            arg.onGpsStatusChanged(3)
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                        }
                    })

                    // Ensure PackageManager reports GNSS hardware and step sensor capability when Stp is ON
                    val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
                    if (pmClass != null) {
                        hookAllMethodsCompat(pmClass, "hasSystemFeature", object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val feat = param.args.getOrNull(0) as? String ?: return
                                if (feat == "android.hardware.location.gps" ||
                                    feat == "android.hardware.location.network" ||
                                    feat == "android.hardware.sensor.stepcounter" ||
                                    feat == "android.hardware.sensor.stepdetector" ||
                                    feat == "android.hardware.sensor.accelerometer" ||
                                    feat == "android.hardware.sensor.gyroscope" ||
                                    feat == "android.hardware.telephony"
                                ) {
                                    if (resolveState().isStepEnabled) {
                                        param.result = true
                                    }
                                }
                            }
                        })
                    }
                } catch (_: Throwable) {}

                val registeredListeners = java.util.Collections.synchronizedSet(HashSet<android.location.LocationListener>())
                val hookedListenerClasses = java.util.Collections.synchronizedSet(HashSet<String>())
                val isSyntheticPushDispatch = object : ThreadLocal<Boolean>() {
                    override fun initialValue(): Boolean = false
                }
                var loopStarted = false
                val startLocationPushLoop = fun() {
                    if (loopStarted) return
                    loopStarted = true
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    val runnable = object : Runnable {
                        override fun run() {
                            try {
                                val live = resolveState()
                                if (live.isStepEnabled && (live.lat != 0.0 || live.lng != 0.0)) {
                                    val loc = buildSyntheticLocation("gps")
                                    val snapshotListeners = synchronized(registeredListeners) { registeredListeners.toList() }
                                    isSyntheticPushDispatch.set(true)
                                    try {
                                        for (listener in snapshotListeners) {
                                            try {
                                                listener.onLocationChanged(loc)
                                            } catch (_: Throwable) {}
                                        }
                                    } finally {
                                        isSyntheticPushDispatch.set(false)
                                    }
                                }
                            } catch (_: Throwable) {}
                            handler.postDelayed(this, 100L)
                        }
                    }
                    handler.postDelayed(runnable, 100L)
                }

                hookAllMethodsCompat(lmClass, "requestLocationUpdates", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        for (arg in param.args) {
                            if (arg is android.location.LocationListener) {
                                registeredListeners.add(arg)
                                val listenerClass = arg.javaClass
                                val className = listenerClass.name
                                if (hookedListenerClasses.add(className)) {
                                    try {
                                        XposedHelpers.findAndHookMethod(listenerClass, "onLocationChanged", Location::class.java, object : XC_MethodHook() {
                                            override fun beforeHookedMethod(p: MethodHookParam) {
                                                if (isSyntheticPushDispatch.get() == true) return
                                                val live = resolveState()
                                                val incomingLoc = p.args.getOrNull(0) as? Location
                                                if (!live.isStepEnabled) {
                                                    // Step button is OFF: allow real phone GPS location through untouched, and report it to WalkBridge
                                                    if (incomingLoc != null) {
                                                        try {
                                                            val rLat = getDoubleFieldSafe(incomingLoc, "mLatitude")
                                                            val rLng = getDoubleFieldSafe(incomingLoc, "mLongitude")
                                                            reportRealPhoneLocationIfNeeded(rLat, rLng)
                                                        } catch (_: Throwable) {}
                                                    }
                                                    return
                                                }
                                                // Step button is ON: stamp incoming OS location in-place or suppress conflicting raw OS jumps
                                                if (incomingLoc != null) {
                                                    stampLocationInPlace(incomingLoc, live)
                                                }
                                                p.result = null
                                            }
                                        })
                                    } catch (_: Throwable) {}
                                    try {
                                        XposedHelpers.findAndHookMethod(listenerClass, "onProviderDisabled", String::class.java, object : XC_MethodHook() {
                                            override fun beforeHookedMethod(p: MethodHookParam) {
                                                if (resolveState().isStepEnabled) {
                                                    p.result = null
                                                }
                                            }
                                        })
                                    } catch (_: Throwable) {}
                                }
                                val live = resolveState()
                                if (live.isStepEnabled) {
                                    try {
                                        arg.onProviderEnabled("gps")
                                        arg.onProviderEnabled("network")
                                        isSyntheticPushDispatch.set(true)
                                        try {
                                            arg.onLocationChanged(buildSyntheticLocation("gps"))
                                        } finally {
                                            isSyntheticPushDispatch.set(false)
                                        }
                                    } catch (_: Throwable) {}
                                }
                                startLocationPushLoop()
                            }
                        }
                    }
                })

                // Also hook Google Play Services FusedLocationProviderClient if present in target app
                val flpCallbackClass = XposedHelpers.findClass("com.google.android.gms.location.LocationCallback", classLoader)
                val locResultClass = XposedHelpers.findClass("com.google.android.gms.location.LocationResult", classLoader)
                val flpClientClass = XposedHelpers.findClass("com.google.android.gms.location.FusedLocationProviderClient", classLoader)
                val tasksClass = XposedHelpers.findClass("com.google.android.gms.tasks.Tasks", classLoader)
                if (flpCallbackClass != null && locResultClass != null) {
                    val gmsCallbacks = java.util.Collections.synchronizedSet(HashSet<Any>())
                    val hookedGmsCallbackClasses = java.util.Collections.synchronizedSet(HashSet<String>())
                    var gmsLoopStarted = false
                    val startGmsPushLoop = fun() {
                        if (gmsLoopStarted) return
                        gmsLoopStarted = true
                        val handler = android.os.Handler(android.os.Looper.getMainLooper())
                        val runnable = object : Runnable {
                            override fun run() {
                                try {
                                    val live = resolveState()
                                    if (live.isStepEnabled && (live.lat != 0.0 || live.lng != 0.0)) {
                                        val loc = buildSyntheticLocation("fused")
                                        val createMethod = locResultClass.getMethod("create", java.util.List::class.java)
                                        val resultObj = createMethod.invoke(null, java.util.Collections.singletonList(loc))
                                        val onResultMethod = flpCallbackClass.getMethod("onLocationResult", locResultClass)
                                        onResultMethod.isAccessible = true
                                        val cbs = synchronized(gmsCallbacks) { gmsCallbacks.toList() }
                                        isSyntheticPushDispatch.set(true)
                                        try {
                                            for (cb in cbs) {
                                                try {
                                                    onResultMethod.invoke(cb, resultObj)
                                                } catch (_: Throwable) {}
                                            }
                                        } finally {
                                            isSyntheticPushDispatch.set(false)
                                        }
                                    }
                                } catch (_: Throwable) {}
                                handler.postDelayed(this, 100L)
                            }
                        }
                        handler.postDelayed(runnable, 100L)
                    }

                    val hookGmsCallbackClassIfNeeded = fun(cbInstance: Any) {
                        val cbClass = cbInstance.javaClass
                        if (hookedGmsCallbackClasses.add(cbClass.name)) {
                            try {
                                XposedHelpers.findAndHookMethod(cbClass, "onLocationResult", locResultClass, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(p: MethodHookParam) {
                                        if (isSyntheticPushDispatch.get() == true) return
                                        val live = resolveState()
                                        if (!live.isStepEnabled) {
                                            // Step is OFF: let real GMS LocationResult pass through untouched and capture real GPS
                                            try {
                                                val resObj = p.args.getOrNull(0)
                                                if (resObj != null) {
                                                    val lastLoc = callMethodSafe(resObj, "getLastLocation") as? Location
                                                    if (lastLoc != null) {
                                                        val rLat = getDoubleFieldSafe(lastLoc, "mLatitude")
                                                        val rLng = getDoubleFieldSafe(lastLoc, "mLongitude")
                                                        reportRealPhoneLocationIfNeeded(rLat, rLng)
                                                    }
                                                }
                                            } catch (_: Throwable) {}
                                            return
                                        }
                                        // Step is ON: block conflicting raw GMS callback so only our smooth 100ms stream updates the app
                                        p.result = null
                                    }
                                })
                            } catch (_: Throwable) {}
                        }
                    }

                    try {
                        for (ctor in flpCallbackClass.declaredConstructors) {
                            de.robv.android.xposed.XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    val instance = param.thisObject
                                    if (instance != null) {
                                        gmsCallbacks.add(instance)
                                        hookGmsCallbackClassIfNeeded(instance)
                                        startGmsPushLoop()
                                    }
                                }
                            })
                        }
                    } catch (_: Throwable) {}

                    if (flpClientClass != null) {
                        hookAllMethodsCompat(flpClientClass, "requestLocationUpdates", object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                for (arg in param.args) {
                                    if (arg != null && flpCallbackClass.isAssignableFrom(arg.javaClass)) {
                                        gmsCallbacks.add(arg)
                                        hookGmsCallbackClassIfNeeded(arg)
                                        startGmsPushLoop()
                                    }
                                }
                            }
                        })

                        if (tasksClass != null) {
                            try {
                                val forResultMethod = tasksClass.getMethod("forResult", Any::class.java)
                                hookAllMethodsCompat(flpClientClass, "getLastLocation", object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        if (!resolveState().isStepEnabled) return
                                        param.result = forResultMethod.invoke(null, buildSyntheticLocation("fused"))
                                    }
                                })
                                hookAllMethodsCompat(flpClientClass, "getCurrentLocation", object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        if (!resolveState().isStepEnabled) return
                                        param.result = forResultMethod.invoke(null, buildSyntheticLocation("fused"))
                                    }
                                })
                            } catch (_: Throwable) {}
                        }
                    }

                    val locAvailClass = XposedHelpers.findClass("com.google.android.gms.location.LocationAvailability", classLoader)
                    if (locAvailClass != null) {
                        try {
                            XposedHelpers.findAndHookMethod(locAvailClass, "isLocationAvailable", object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (resolveState().isStepEnabled) {
                                        param.result = true
                                    }
                                }
                            })
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookStepAndMotionSimulation(
        classLoader: ClassLoader,
        pkg: String = "",
        initialStepEnabled: Boolean = false
    ) {
        try {
            val sensorEventClass = android.hardware.SensorEvent::class.java
            val sensorManagerClass = XposedHelpers.findClass("android.hardware.SensorManager", classLoader) ?: return

            data class RegisteredSensorTarget(
                val listener: android.hardware.SensorEventListener,
                val sensor: android.hardware.Sensor,
                val sensorType: Int,
                val forceDispatchAlways: Boolean = false
            )

            val activeSensorTargets = java.util.Collections.synchronizedList(ArrayList<RegisteredSensorTarget>())
            val synthesizedSensorsByType = java.util.concurrent.ConcurrentHashMap<Int, android.hardware.Sensor>()
            var lastInjectedStepCount = -1
            var cadencePhase = 0.0
            var handTremorPhase = 0.0
            var stepLoopStarted = false

            val buildSyntheticHardwareSensor = fun(sensorType: Int): android.hardware.Sensor? {
                synthesizedSensorsByType[sensorType]?.let { return it }
                return try {
                    val sCls = android.hardware.Sensor::class.java
                    val ctor = sCls.getDeclaredConstructor()
                    ctor.isAccessible = true
                    val sensor = ctor.newInstance()
                    fun setField(name: String, value: Any?) {
                        try {
                            val f = sCls.getDeclaredField(name)
                            f.isAccessible = true
                            f.set(sensor, value)
                        } catch (_: Throwable) {}
                    }
                    setField("mType", sensorType)
                    setField("mVersion", 1)
                    setField("mHandle", 100 + sensorType)
                    when (sensorType) {
                        1 -> { // TYPE_ACCELEROMETER
                            setField("mName", "BMI260 Accelerometer")
                            setField("mVendor", "Bosch Sensortec")
                            setField("mStringType", "android.sensor.accelerometer")
                            setField("mMaxRange", 156.9064f)
                            setField("mResolution", 0.0023956299f)
                            setField("mPower", 0.18f)
                            setField("mMinDelay", 5000)
                        }
                        2 -> { // TYPE_MAGNETIC_FIELD
                            setField("mName", "AK09918 Magnetometer")
                            setField("mVendor", "AKM")
                            setField("mStringType", "android.sensor.magnetic_field")
                            setField("mMaxRange", 4912.0f)
                            setField("mResolution", 0.15f)
                            setField("mPower", 1.1f)
                            setField("mMinDelay", 10000)
                        }
                        4 -> { // TYPE_GYROSCOPE
                            setField("mName", "BMI260 Gyroscope")
                            setField("mVendor", "Bosch Sensortec")
                            setField("mStringType", "android.sensor.gyroscope")
                            setField("mMaxRange", 34.906586f)
                            setField("mResolution", 0.0010681152f)
                            setField("mPower", 0.85f)
                            setField("mMinDelay", 5000)
                        }
                        5 -> { // TYPE_LIGHT
                            setField("mName", "STK33502 Ambient Light Sensor")
                            setField("mVendor", "Sensortek")
                            setField("mStringType", "android.sensor.light")
                            setField("mMaxRange", 65535.0f)
                            setField("mResolution", 1.0f)
                            setField("mPower", 0.15f)
                            setField("mMinDelay", 200000)
                        }
                        6 -> { // TYPE_PRESSURE
                            setField("mName", "LPS22HH Pressure Sensor")
                            setField("mVendor", "STMicroelectronics")
                            setField("mStringType", "android.sensor.pressure")
                            setField("mMaxRange", 1260.0f)
                            setField("mResolution", 0.00024414062f)
                            setField("mPower", 0.04f)
                            setField("mMinDelay", 40000)
                        }
                        8 -> { // TYPE_PROXIMITY
                            setField("mName", "STK33502 Proximity Sensor")
                            setField("mVendor", "Sensortek")
                            setField("mStringType", "android.sensor.proximity")
                            setField("mMaxRange", 5.0f)
                            setField("mResolution", 5.0f)
                            setField("mPower", 0.15f)
                            setField("mMinDelay", 200000)
                        }
                        9 -> { // TYPE_GRAVITY
                            setField("mName", "BMI260 Gravity")
                            setField("mVendor", "Bosch Sensortec")
                            setField("mStringType", "android.sensor.gravity")
                            setField("mMaxRange", 19.6133f)
                            setField("mResolution", 0.0023956299f)
                            setField("mPower", 0.25f)
                            setField("mMinDelay", 5000)
                        }
                        10 -> { // TYPE_LINEAR_ACCELERATION
                            setField("mName", "BMI260 Linear Acceleration")
                            setField("mVendor", "Bosch Sensortec")
                            setField("mStringType", "android.sensor.linear_acceleration")
                            setField("mMaxRange", 156.9064f)
                            setField("mResolution", 0.0023956299f)
                            setField("mPower", 0.25f)
                            setField("mMinDelay", 5000)
                        }
                        11 -> { // TYPE_ROTATION_VECTOR
                            setField("mName", "BMI260 Rotation Vector")
                            setField("mVendor", "Bosch Sensortec")
                            setField("mStringType", "android.sensor.rotation_vector")
                            setField("mMaxRange", 1.0f)
                            setField("mResolution", 5.9604645E-8f)
                            setField("mPower", 1.2f)
                            setField("mMinDelay", 5000)
                        }
                        18 -> { // TYPE_STEP_DETECTOR
                            setField("mName", "BMI260 Step Detector")
                            setField("mVendor", "Bosch Sensortec")
                            setField("mStringType", "android.sensor.step_detector")
                            setField("mMaxRange", 1.0f)
                            setField("mResolution", 1.0f)
                            setField("mPower", 0.12f)
                            setField("mMinDelay", 0)
                        }
                        19 -> { // TYPE_STEP_COUNTER
                            setField("mName", "BMI260 Step Counter")
                            setField("mVendor", "Bosch Sensortec")
                            setField("mStringType", "android.sensor.step_counter")
                            setField("mMaxRange", 200000.0f)
                            setField("mResolution", 1.0f)
                            setField("mPower", 0.12f)
                            setField("mMinDelay", 0)
                        }
                        else -> return null
                    }
                    synthesizedSensorsByType[sensorType] = sensor
                    sensor
                } catch (_: Throwable) {
                    null
                }
            }

            // Only expose synthesized hardware sensors when Step Simulation (Stp) is enabled for this app
            try {
                hookAllMethodsCompat(sensorManagerClass, "getDefaultSensor", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || param.result != null) return
                        if (!readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled).isStepEnabled) return
                        val requestedType = param.args.firstOrNull() as? Int ?: return
                        val synth = buildSyntheticHardwareSensor(requestedType)
                        if (synth != null) {
                            param.result = synth
                        }
                    }
                })
                hookAllMethodsCompat(sensorManagerClass, "getSensorList", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        if (!readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled).isStepEnabled) return
                        val requestedType = param.args.firstOrNull() as? Int ?: return
                        val origList = (param.result as? List<*>)?.filterIsInstance<android.hardware.Sensor>() ?: emptyList()
                        val coreTypes = intArrayOf(1, 2, 4, 5, 6, 8, 9, 10, 11, 18, 19)
                        if (requestedType == -1) { // Sensor.TYPE_ALL
                            if (origList.size >= 8) return
                            val merged = ArrayList<android.hardware.Sensor>(origList)
                            val existingTypes = origList.map { it.type }.toSet()
                            for (t in coreTypes) {
                                if (t !in existingTypes) {
                                    buildSyntheticHardwareSensor(t)?.let { merged.add(it) }
                                }
                            }
                            param.result = java.util.Collections.unmodifiableList(merged)
                        } else if (origList.isEmpty()) {
                            val synth = buildSyntheticHardwareSensor(requestedType)
                            if (synth != null) {
                                param.result = java.util.Collections.singletonList(synth)
                            }
                        }
                    }
                })
            } catch (_: Throwable) {}

            val createSyntheticSensorEvent = fun(targetSensor: android.hardware.Sensor, values: FloatArray): android.hardware.SensorEvent? {
                return try {
                    val ctor = sensorEventClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    ctor.isAccessible = true
                    val ev = ctor.newInstance(values.size)
                    ev.sensor = targetSensor
                    ev.accuracy = android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                    ev.timestamp = android.os.SystemClock.elapsedRealtimeNanos()
                    val evValues = ev.values
                    if (evValues != null) {
                        for (i in values.indices) {
                            if (i < evValues.size) evValues[i] = values[i]
                        }
                    }
                    ev
                } catch (_: Throwable) {
                    null
                }
            }

            val startStepDispatcherLoop = fun() {
                if (stepLoopStarted) return
                stepLoopStarted = true
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val runnable = object : Runnable {
                    override fun run() {
                        try {
                            val live = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled)
                            val targets = synchronized(activeSensorTargets) { activeSensorTargets.toList() }
                            if (targets.isNotEmpty()) {
                                val stepDelta = if (lastInjectedStepCount >= 0 && live.totalSteps > lastInjectedStepCount) {
                                    live.totalSteps - lastInjectedStepCount
                                } else 0
                                lastInjectedStepCount = live.totalSteps

                                handTremorPhase += 0.42
                                if (live.isStepEnabled && live.isMoving) {
                                    cadencePhase += (0.52 + (live.speedMps * 0.22))
                                } else {
                                    cadencePhase += 0.14
                                }

                                // Natural handheld viewing posture (~37 deg pitch, ~10 deg roll) + organic human hand micro-tremor
                                val handGx = 1.35f + (kotlin.math.sin(handTremorPhase * 0.7) * 0.16).toFloat()
                                val handGy = 5.82f + (kotlin.math.cos(handTremorPhase * 0.5) * 0.18).toFloat()
                                val handGz = 7.74f + (kotlin.math.sin(handTremorPhase * 0.9) * 0.15).toFloat()

                                for (t in targets) {
                                    if (!live.isStepEnabled) continue
                                    when (t.sensorType) {
                                        19 -> { // Sensor.TYPE_STEP_COUNTER
                                            if (live.totalSteps > 0 || t.forceDispatchAlways) {
                                                val ev = createSyntheticSensorEvent(
                                                    t.sensor,
                                                    floatArrayOf(live.totalSteps.coerceAtLeast(124).toFloat())
                                                )
                                                if (ev != null) t.listener.onSensorChanged(ev)
                                            }
                                        }
                                        18 -> { // Sensor.TYPE_STEP_DETECTOR
                                            if (stepDelta > 0) {
                                                val ev = createSyntheticSensorEvent(t.sensor, floatArrayOf(1.0f))
                                                if (ev != null) t.listener.onSensorChanged(ev)
                                            }
                                        }
                                        1 -> { // Sensor.TYPE_ACCELEROMETER (1)
                                            val values = if (live.isStepEnabled && live.isMoving) {
                                                val amp = (live.speedMps * 0.95f).coerceIn(0.95f, 3.2f)
                                                val swayX = handGx + (kotlin.math.sin(cadencePhase * 0.5) * 0.58 * amp).toFloat()
                                                val bounceY = handGy + (kotlin.math.sin(cadencePhase) * 1.18 * amp).toFloat()
                                                val forwardZ = handGz + (kotlin.math.cos(cadencePhase) * 0.85 * amp).toFloat()
                                                floatArrayOf(swayX, bounceY, forwardZ)
                                            } else {
                                                floatArrayOf(handGx, handGy, handGz)
                                            }
                                            val ev = createSyntheticSensorEvent(t.sensor, values)
                                            if (ev != null) t.listener.onSensorChanged(ev)
                                        }
                                        2 -> { // Sensor.TYPE_MAGNETIC_FIELD (2)
                                            val ev = createSyntheticSensorEvent(
                                                t.sensor,
                                                floatArrayOf(
                                                    21.4f + (kotlin.math.sin(handTremorPhase * 0.4) * 0.35).toFloat(),
                                                    -14.8f + (kotlin.math.cos(handTremorPhase * 0.6) * 0.35).toFloat(),
                                                    -38.2f + (kotlin.math.sin(handTremorPhase * 0.5) * 0.40).toFloat()
                                                )
                                            )
                                            if (ev != null) t.listener.onSensorChanged(ev)
                                        }
                                        5 -> { // Sensor.TYPE_LIGHT (5)
                                            val ev = createSyntheticSensorEvent(
                                                t.sensor,
                                                floatArrayOf(285.0f + (kotlin.math.sin(handTremorPhase * 0.2) * 6.0).toFloat())
                                            )
                                            if (ev != null) t.listener.onSensorChanged(ev)
                                        }
                                        6 -> { // Sensor.TYPE_PRESSURE (6)
                                            val ev = createSyntheticSensorEvent(
                                                t.sensor,
                                                floatArrayOf(1012.8f + (kotlin.math.sin(handTremorPhase * 0.15) * 0.08).toFloat())
                                            )
                                            if (ev != null) t.listener.onSensorChanged(ev)
                                        }
                                        8 -> { // Sensor.TYPE_PROXIMITY (8)
                                            val ev = createSyntheticSensorEvent(t.sensor, floatArrayOf(5.0f))
                                            if (ev != null) t.listener.onSensorChanged(ev)
                                        }
                                        9 -> { // Sensor.TYPE_GRAVITY (9)
                                            val ev = createSyntheticSensorEvent(t.sensor, floatArrayOf(handGx, handGy, handGz))
                                            if (ev != null) t.listener.onSensorChanged(ev)
                                        }
                                        10 -> { // Sensor.TYPE_LINEAR_ACCELERATION (10)
                                            val values = if (live.isStepEnabled && live.isMoving) {
                                                val amp = (live.speedMps * 0.95f).coerceIn(0.95f, 3.2f)
                                                floatArrayOf(
                                                    (kotlin.math.sin(cadencePhase * 0.5) * 0.58 * amp).toFloat(),
                                                    (kotlin.math.sin(cadencePhase) * 1.18 * amp).toFloat(),
                                                    (kotlin.math.cos(cadencePhase) * 0.85 * amp).toFloat()
                                                )
                                            } else {
                                                floatArrayOf(
                                                    (kotlin.math.sin(handTremorPhase * 1.1) * 0.14).toFloat(),
                                                    (kotlin.math.cos(handTremorPhase * 0.9) * 0.16).toFloat(),
                                                    (kotlin.math.sin(handTremorPhase * 1.3) * 0.14).toFloat()
                                                )
                                            }
                                            val ev = createSyntheticSensorEvent(t.sensor, values)
                                            if (ev != null) t.listener.onSensorChanged(ev)
                                        }
                                        4 -> { // Sensor.TYPE_GYROSCOPE (4)
                                            val gyroAmp = if (live.isStepEnabled && live.isMoving) 0.22f else 0.045f
                                            val ev = createSyntheticSensorEvent(
                                                t.sensor,
                                                floatArrayOf(
                                                    (kotlin.math.sin(cadencePhase) * gyroAmp).toFloat(),
                                                    (kotlin.math.cos(cadencePhase * 0.5) * gyroAmp * 0.8f).toFloat(),
                                                    (kotlin.math.sin(handTremorPhase * 0.8) * gyroAmp * 0.6f).toFloat()
                                                )
                                            )
                                            if (ev != null) t.listener.onSensorChanged(ev)
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        handler.postDelayed(this, 120L)
                    }
                }
                handler.postDelayed(runnable, 120L)
            }

            hookAllMethodsCompat(sensorManagerClass, "registerListener", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val nativeRegistered = param.result as? Boolean ?: false
                    var listenerObj: android.hardware.SensorEventListener? = null
                    var sensorObj: android.hardware.Sensor? = null
                    for (arg in param.args) {
                        if (arg is android.hardware.SensorEventListener) {
                            listenerObj = arg
                        } else if (arg is android.hardware.Sensor) {
                            sensorObj = arg
                        }
                    }
                    val stepOnNow = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled).isStepEnabled
                    if (!nativeRegistered && listenerObj != null && sensorObj != null && stepOnNow) {
                        param.result = true
                    }
                    if (listenerObj != null && sensorObj != null) {
                        val sType = sensorObj.type
                        val isSynthesizedSensor = !nativeRegistered || synthesizedSensorsByType[sType] === sensorObj
                        if (isSynthesizedSensor || sType == 19 || sType == 18 || sType == 1 || sType == 9 || sType == 10 || sType == 4) {
                            synchronized(activeSensorTargets) {
                                if (activeSensorTargets.none { it.listener === listenerObj && it.sensorType == sType }) {
                                    activeSensorTargets.add(RegisteredSensorTarget(listenerObj, sensorObj, sType, forceDispatchAlways = isSynthesizedSensor))
                                }
                            }
                            if (stepOnNow) {
                                startStepDispatcherLoop()
                            }
                        }
                    }
                }
            })

            hookAllMethodsCompat(sensorManagerClass, "unregisterListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listenerObj = param.args.firstOrNull() ?: return
                    synchronized(activeSensorTargets) {
                        activeSensorTargets.removeAll { it.listener === listenerObj }
                    }
                }
            })

            val queueClass = XposedHelpers.findClass("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            if (queueClass != null) {
                hookAllMethodsCompat(queueClass, "dispatchSensorEvent", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size < 2) return
                        val values = param.args[1] as? FloatArray ?: return
                        val live = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled)
                        if (!live.isStepEnabled) {
                            return
                        }

                        if (values.size == 1 && live.totalSteps > 0) {
                            values[0] = values[0] + live.totalSteps.toFloat()
                        } else if (values.size >= 3) {
                            handTremorPhase += 0.18
                            // If phone is lying flat on a table (x≈0, y≈0, z≈9.8), tilt it to natural handheld viewing angle
                            if (kotlin.math.abs(values[0]) < 1.0f && kotlin.math.abs(values[1]) < 1.5f && values[2] > 8.8f) {
                                values[0] = 1.35f + (kotlin.math.sin(handTremorPhase * 0.7) * 0.16).toFloat()
                                values[1] = 5.82f + (kotlin.math.cos(handTremorPhase * 0.5) * 0.18).toFloat()
                                values[2] = 7.74f + (kotlin.math.sin(handTremorPhase * 0.9) * 0.15).toFloat()
                            } else {
                                values[0] += (kotlin.math.sin(handTremorPhase * 0.7) * 0.12).toFloat()
                                values[1] += (kotlin.math.cos(handTremorPhase * 0.5) * 0.14).toFloat()
                                values[2] += (kotlin.math.sin(handTremorPhase * 0.9) * 0.12).toFloat()
                            }
                            if (live.isMoving) {
                                cadencePhase += 0.28
                                val amp = (live.speedMps * 0.85f).coerceIn(0.8f, 3.2f)
                                values[0] += (kotlin.math.sin(cadencePhase * 0.5) * 0.48 * amp).toFloat()
                                values[1] += (kotlin.math.sin(cadencePhase) * 1.05 * amp).toFloat()
                                values[2] += (kotlin.math.cos(cadencePhase) * 0.72 * amp).toFloat()
                            }
                        }
                    }
                })
            }
        } catch (_: Throwable) {}
    }

    private fun hookWebviewUserAgent(
        classLoader: ClassLoader,
        userAgent: String,
        kernelVer: String = "",
        enableRootBypass: Boolean = true,
        identity: JSONObject = JSONObject(),
        cpuVals: CpuHardwareRandomValues? = null,
        countryCode: String = "US",
        pkg: String = ""
    ) {
        try {
            val resolveDeviceTokens = fun(): Triple<String, String, String> {
                val rawMod = readSysPropInternal("persist.sys.phantom.mod").ifBlank { identity.optString("deviceModel", "Pixel 8 Pro") }
                val cleanMod = extractHardwareModelCode(rawMod, isSettingsPackage = false)
                val rawFp = readSysPropInternal("persist.sys.phantom.fp").ifBlank { identity.optString("buildFingerprint", "") }
                var buildId = readSysPropInternal("persist.sys.phantom.bid").ifBlank { identity.optString("buildNumber", "UP1A.231005.007") }
                if (rawFp.contains(":") && rawFp.contains("/")) {
                    val afterColon = rawFp.substringAfter(":", "")
                    val parts = afterColon.split("/")
                    if (parts.size >= 2 && parts[1].isNotBlank()) {
                        buildId = parts[1]
                    }
                }
                val runtimeOsRel = when (Build.VERSION.SDK_INT) {
                    35 -> "15"
                    34 -> "14"
                    33 -> "13"
                    32, 31 -> "12"
                    30 -> "11"
                    29 -> "10"
                    else -> "14"
                }
                return Triple(runtimeOsRel, cleanMod, buildId)
            }

            // Dalvik VM User-Agent for System.getProperty("http.agent"):
            // Official native Android apps (X / Twitter, Instagram, Play Services, OkHttp) require Dalvik/2.1.0 format,
            // NOT a Mozilla/5.0 WebView User-Agent!
            val resolveDalvikUa = fun(): String {
                val (osNum, cleanMod, buildId) = resolveDeviceTokens()
                return "Dalvik/2.1.0 (Linux; U; Android $osNum; $cleanMod Build/$buildId)"
            }

            // Mutate WebView User-Agent in-place so the device's real Chrome/<version> (matching Sec-CH-UA headers)
            // and any official app suffix (e.g. "TwitterAndroid/10.x...") are 100% preserved!
            val rewriteWebViewUa = fun(origUa: String?): String {
                val (osNum, cleanMod, buildId) = resolveDeviceTokens()
                if (!origUa.isNullOrBlank() && origUa.contains("Android", ignoreCase = true)) {
                    val replaced = origUa.replace(
                        Regex("""Linux;\s*(?:U;\s*)?Android\s+[^;)]+;\s*[^;)]+"""),
                        "Linux; Android $osNum; $cleanMod Build/$buildId"
                    )
                    if (replaced != origUa) return replaced
                }
                return if (cleanMod.isNotBlank()) {
                    "Mozilla/5.0 (Linux; Android $osNum; $cleanMod Build/$buildId; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36"
                } else {
                    userAgent
                }
            }

            // 1. Hook Java System.getProperty("http.agent"), "os.arch", "os.version" & proxy checks
            val sysPropHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val prop = param.args[0] as? String ?: return
                    when (prop) {
                        "http.agent" -> {
                            param.result = resolveDalvikUa()
                        }
                        "os.arch" -> {
                            val is64 = try { android.os.Process.is64Bit() } catch (_: Throwable) { true }
                            param.result = if (is64) "aarch64" else "armv8l"
                        }
                        "os.name" -> {
                            param.result = "Linux"
                        }
                        "os.version" -> {
                            val kv = readSysPropInternal("persist.sys.phantom.kv").ifBlank { kernelVer }.ifBlank { "6.1.75-android14-11-g8a91b2d4c1" }
                            param.result = kv
                        }
                        "user.region", "user.country" -> if (countryCode.isNotBlank()) param.result = countryCode
                        "user.timezone" -> {
                            val tz = readSysPropInternal("persist.sys.phantom.tz").ifBlank { identity.optString("timezone", "") }
                            if (tz.isNotBlank()) param.result = tz
                        }
                        "http.proxyHost", "https.proxyHost", "socksProxyHost",
                        "http.proxyPort", "https.proxyPort", "socksProxyPort" -> {
                            param.result = param.args.getOrNull(1) as? String
                        }
                        "ro.build.tags" -> param.result = "release-keys"
                        "ro.debuggable" -> param.result = "0"
                        "ro.secure" -> param.result = "1"
                        "ro.kernel.qemu", "ro.boot.qemu" -> param.result = "0"
                    }
                }
            }
            XposedHelpers.findAndHookMethod(System::class.java, "getProperty", String::class.java, sysPropHook)
            try {
                XposedHelpers.findAndHookMethod(System::class.java, "getProperty", String::class.java, String::class.java, sysPropHook)
            } catch (_: Throwable) {}

            // 2. Hook WebSettings User-Agent
            val webSettingsClass = XposedHelpers.findClass("android.webkit.WebSettings", classLoader)
            if (webSettingsClass != null) {
                XposedHelpers.findAndHookMethod(
                    webSettingsClass,
                    "getDefaultUserAgent",
                    android.content.Context::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val orig = param.result as? String
                            val ua = rewriteWebViewUa(orig)
                            if (ua.isNotBlank()) param.result = ua
                        }
                    }
                )
                XposedHelpers.findAndHookMethod(
                    webSettingsClass,
                    "getUserAgentString",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val orig = param.result as? String
                            val ua = rewriteWebViewUa(orig)
                            if (ua.isNotBlank()) param.result = ua
                        }
                    }
                )
            }

            // Only inject JS WebGL/Navigator override in standalone web browsers (never in native apps like X / Twitter,
            // because X's ui_metrics / js_inst WebView checks Date.prototype.getTimezoneOffset.toString() for native code integrity,
            // and Java TimeZone.getDefault() / Locale.getDefault() already spoof WebView's native C++ ICU timezone & locale!).
            val isStandaloneBrowser = pkg.contains("chrome", ignoreCase = true) ||
                pkg.contains("browser", ignoreCase = true) ||
                pkg.contains("firefox", ignoreCase = true) ||
                pkg.contains("brave", ignoreCase = true) ||
                pkg.contains("duckduckgo", ignoreCase = true) ||
                pkg.contains("opera", ignoreCase = true) ||
                pkg.contains("kiwi", ignoreCase = true) ||
                pkg.contains("emmx", ignoreCase = true)
            if (isStandaloneBrowser) {
                try {
                    val webViewClass = XposedHelpers.findClass("android.webkit.WebView", classLoader)
                    if (webViewClass != null) {
                        val injectJsToWebView = fun(wv: Any) {
                            try {
                                val liveGpuVendor = cpuVals?.gpuVendor ?: "Qualcomm"
                                val liveGpuRenderer = readSysPropInternal("persist.sys.phantom.gpu").ifBlank {
                                    cpuVals?.gpuRenderer ?: identity.optString("gpuRenderer", "Adreno (TM) 750")
                                }
                                val js = """
                                    (function(){
                                        try {
                                            const hookWebGL = function(proto) {
                                                if (!proto || !proto.getParameter || proto.getParameter.__p) return;
                                                const origGetParam = proto.getParameter;
                                                const wrapped = function(p) {
                                                    if (p === 37445) return '$liveGpuVendor';
                                                    if (p === 37446) return '$liveGpuRenderer';
                                                    return origGetParam.apply(this, arguments);
                                                };
                                                Object.defineProperty(wrapped, '__p', { value: true, enumerable: false });
                                                Object.defineProperty(wrapped, 'toString', {
                                                    value: function() { return 'function getParameter() { [native code] }'; },
                                                    enumerable: false
                                                });
                                                proto.getParameter = wrapped;
                                            };
                                            if (window.WebGLRenderingContext) hookWebGL(WebGLRenderingContext.prototype);
                                            if (window.WebGL2RenderingContext) hookWebGL(WebGL2RenderingContext.prototype);
                                        } catch(e) {}
                                    })();
                                """.trimIndent()
                                val evalMethod = webViewClass.getMethod("evaluateJavascript", String::class.java, android.webkit.ValueCallback::class.java)
                                evalMethod.invoke(wv, js, null)
                            } catch (_: Throwable) {}
                        }

                        for (mName in arrayOf("loadUrl", "loadData", "loadDataWithBaseURL")) {
                            hookAllMethodsCompat(webViewClass, mName, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    val wv = param.thisObject ?: return
                                    val firstArg = param.args.firstOrNull() as? String ?: ""
                                    if (firstArg.startsWith("javascript:", ignoreCase = true) || firstArg.startsWith("about:", ignoreCase = true)) {
                                        return
                                    }
                                    injectJsToWebView(wv)
                                }
                            })
                        }
                    }
                } catch (_: Throwable) {}
            }

            // 3. Hook Chromium Content & Chromium Base (Chrome / Brave / Webview)
            try {
                val chromiumBuild = XposedHelpers.findClass("org.chromium.base.BuildInfo", classLoader)
                if (chromiumBuild != null) {
                    val fields = arrayOf("MODEL", "BRAND", "MANUFACTURER", "DEVICE", "PRODUCT")
                    for (f in fields) {
                        try {
                            val field = chromiumBuild.getDeclaredField(f)
                            field.isAccessible = true
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun hookSystemProperties(
        classLoader: ClassLoader,
        identity: JSONObject,
        cpuVals: CpuHardwareRandomValues,
        isSettingsPackage: Boolean = false,
        onlySecurityProps: Boolean = false,
        enableRootBypass: Boolean = true
    ) {
        val parsed = resolveConsistentBuildIdentity(identity, cpuVals, isSettingsPackage, enableRootBypass)
        val sdkInt = when (parsed.release) {
            "15" -> "35"
            "14" -> "34"
            "13" -> "33"
            "12" -> "31"
            "11" -> "30"
            "10" -> "29"
            else -> Build.VERSION.SDK_INT.toString()
        }
        val tz = identity.optString("timezone", "")
        val serial = identity.optString("serialNumber", "").ifBlank { "39141FDJH0048X" }
        val basebandVersion = identity.optString("basebandVersion", "").ifBlank { "g5300q-230927-231102-B-11040898" }
        val bootloaderVersion = identity.optString("bootloaderVersion", "").ifBlank { "${parsed.product}-1.0-984210" }
        val gpuRenderer = identity.optString("gpuRenderer", "")
        val locationName = identity.optString("locationName", "")
        val countryCode = getCountryCode(tz, locationName)

        try {
            val spClass = XposedHelpers.findClass("android.os.SystemProperties", classLoader) ?: return
            val handler = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    if (isInternalFileAccess.get() == true) return
                    val key = (param.args[0] as? String)?.lowercase() ?: return
                    val callerDef = (param.args.getOrNull(1) as? String) ?: ""
                    if (key.startsWith("persist.sys.phantom.") || key.startsWith("debug.phantom.") || key.startsWith("log.tag.phantom.")) {
                        param.result = callerDef
                        return
                    }
                    // Safe fallback for emulator-only properties that do not exist on real physical phones:
                    // returns "" for 1-arg get(key) and returns callerDef (if safe) for 2-arg get(key, def).
                    val safeEmptyOrDef = if (callerDef.isNotEmpty() &&
                        callerDef != "1" &&
                        !callerDef.contains("qemu", true) &&
                        !callerDef.contains("goldfish", true) &&
                        !callerDef.contains("ranchu", true) &&
                        !callerDef.contains("vbox", true) &&
                        !callerDef.contains("houdini", true) &&
                        !callerDef.contains("ndk_translation", true)
                    ) callerDef else ""

                    val spoofed = when {
                        // 1. Cloak QEMU, Goldfish, Ranchu, VirtualBox, Nox, BlueStacks, LDPlayer, MEmu, Droid4X & Virtual Space properties ONLY when Root Hide is ON
                        enableRootBypass && (key == "ro.kernel.qemu" || key == "ro.boot.qemu" || key == "ro.kernel.android.qemud" ||
                            key.startsWith("ro.kernel.qemu.") || key.startsWith("qemu.") ||
                            key.startsWith("init.svc.qemu") || key.startsWith("init.svc.goldfish") ||
                            key.startsWith("init.svc.vbox") || key.startsWith("init.svc.nox") ||
                            key.startsWith("init.svc.ttvm") || key.startsWith("init.svc.droid4x") ||
                            key.startsWith("init.svc.microvirt") || key.startsWith("init.svc.bst") ||
                            key.startsWith("init.svc.ld") || key.startsWith("init.svc.nemud") ||
                            key.startsWith("vbox.") || key.startsWith("androvm.") ||
                            key.startsWith("nox.") || key.startsWith("bst.") ||
                            key == "ro.hardware.virtual_device" || key.startsWith("ro.vmos") ||
                            key.startsWith("ro.vphone") || key.startsWith("persist.vmos") ||
                            key.startsWith("ro.clone")) -> safeEmptyOrDef

                        // 2. Enforce real-phone security, verified boot, release-keys & locked bootloader properties ONLY when Root Hide is ON
                        enableRootBypass && (key == "ro.build.tags" || key.endsWith(".build.tags")) -> "release-keys"
                        enableRootBypass && (key == "ro.build.type" || key.endsWith(".build.type")) -> "user"
                        enableRootBypass && key == "ro.build.flavor" -> "${parsed.product}-user"
                        enableRootBypass && key == "ro.build.description" -> "${parsed.product}-user ${parsed.release} ${parsed.buildId} ${parsed.incremental} release-keys"
                        enableRootBypass && key == "ro.build.characteristics" -> "nosdcard"
                        enableRootBypass && key == "ro.build.user" -> "android-build"
                        enableRootBypass && key == "ro.build.host" -> "abfarm-release-rbe-64-0001"
                        enableRootBypass && (key == "ro.debuggable" || key == "service.adb.root" || key == "ro.adb.root") -> "0"
                        enableRootBypass && (key == "ro.secure" || key == "ro.adb.secure" || key == "ro.build.selinux") -> "1"
                        enableRootBypass && (key == "ro.boot.verifiedbootstate" || key == "ro.boot.veritymode" || key == "partition.system.verified") -> "green"
                        enableRootBypass && key == "ro.boot.flash.locked" -> "1"
                        enableRootBypass && (key == "ro.boot.vbmeta.device_state" || key == "ro.boot.secureboot") -> "locked"
                        enableRootBypass && (key == "ro.boot.warranty_bit" || key == "ro.warranty_bit") -> "0"
                        enableRootBypass && (key == "sys.oem_unlock_allowed" || key == "ro.oem_unlock_supported") -> "0"
                        enableRootBypass && (key == "init.svc.su_daemon" || key == "init.svc.daemonsu" || key == "init.svc.magiskd") -> ""
                        enableRootBypass && key == "init.svc.adbd" -> "stopped"
                        enableRootBypass && (key == "sys.usb.state" || key == "sys.usb.config" || key == "persist.sys.usb.config") -> "mtp"
                        enableRootBypass && (key == "ro.boot.mode" || key == "ro.bootmode") -> "normal"
                        key == "ro.product.cpu.abi" || key == "ro.cpu.abi" -> if (enableRootBypass || !onlySecurityProps) "arm64-v8a" else null
                        key == "ro.product.cpu.abi2" -> if (enableRootBypass || !onlySecurityProps) "" else null
                        key == "ro.product.cpu.abilist" -> if (enableRootBypass || !onlySecurityProps) "arm64-v8a,armeabi-v7a,armeabi" else null
                        key == "ro.product.cpu.abilist64" -> if (enableRootBypass || !onlySecurityProps) "arm64-v8a" else null
                        key == "ro.product.cpu.abilist32" -> if (enableRootBypass || !onlySecurityProps) "armeabi-v7a,armeabi" else null
                        key == "ro.arch" || key == "ro.bionic.arch" -> if (enableRootBypass || !onlySecurityProps) "arm64" else null
                        onlySecurityProps -> null

                        // 3. Spoofed Device Identity & CPU/SoC Properties strictly matching Build.* and Build.FINGERPRINT
                        key.contains("product.model") -> if (parsed.cleanModel.isNotBlank()) parsed.cleanModel else null
                        key.contains("product.manufacturer") -> if (parsed.manufacturer.isNotBlank()) parsed.manufacturer else null
                        key.contains("product.brand") -> if (parsed.brand.isNotBlank()) parsed.brand else null
                        key.contains("product.name") || key == "ro.build.product" -> if (parsed.product.isNotBlank()) parsed.product else null
                        key.contains("product.device") -> if (parsed.device.isNotBlank()) parsed.device else null
                        key.contains("product.board") -> cpuVals.boardPlatform
                        key.contains("build.version.release") -> if (parsed.release.isNotBlank()) parsed.release else null
                        key.contains("build.version.incremental") -> if (parsed.incremental.isNotBlank()) parsed.incremental else null
                        key.contains("build.version.security_patch") -> if (parsed.securityPatch.isNotBlank()) parsed.securityPatch else null
                        // Only spoof build.version.sdk in Settings so target apps on Android 12 never crash calling Android 14 APIs
                        key.contains("build.version.sdk") -> if (isSettingsPackage) sdkInt else null
                        key.contains("sys.timezone") || key.contains("persist.sys.timezone") -> if (tz.isNotBlank()) tz else null
                        key.contains("build.fingerprint") -> parsed.fingerprint
                        key.contains("serialno") -> serial
                        key == "ro.ril.oem.sno" || key == "ro.ril.oem.psno" || key == "ril.serialnumber" || key == "sys.serialnumber" ->
                            readSysPropInternal("persist.sys.phantom.board_ser").ifBlank { serial }
                        key == "ro.boot.hardware.revision" || key == "ro.revision" ->
                            readSysPropInternal("persist.sys.phantom.hw_rev").ifBlank { "REV1.0" }
                        key == "ro.boot.cpuid" || key == "ro.boot.ap_serial" ->
                            readSysPropInternal("persist.sys.phantom.chipid").ifBlank { "0x" + Integer.toHexString(serial.hashCode()) }
                        key == "ro.boot.boot_uuid" ->
                            readSysPropInternal("persist.sys.phantom.bootid").ifBlank { null }
                        key.contains("build.display.id") -> "${parsed.buildId}.${parsed.incremental}"
                        key.contains("ro.build.id") || key.endsWith(".build.id") -> parsed.buildId
                        key == "ro.bootloader" || key == "ro.boot.bootloader" -> bootloaderVersion
                        // CPU & SoC properties
                        key == "ro.soc.manufacturer" -> cpuVals.socManufacturer
                        key == "ro.soc.model" || key.contains("soc.model") -> cpuVals.socModelShort
                        key == "ro.board.platform" -> cpuVals.boardPlatform
                        key == "ro.hardware" || key == "ro.boot.hardware" -> cpuVals.cpuHardware
                        key == "ro.hardware.chipname" || key == "ro.chipname" || key == "ro.mediatek.platform" -> cpuVals.processorFull
                        key.contains("baseband") -> basebandVersion
                        key == "gsm.version.ril-impl" -> "Qualcomm RIL 1.0"
                        key.contains("persist.sys.locale") || key.contains("ro.product.locale") -> if (countryCode.isNotBlank()) "en-$countryCode" else null
                        key.contains("persist.sys.country") || key.contains("ro.product.locale.region") -> if (countryCode.isNotBlank()) countryCode else null
                        key.contains("gsm.sim.operator.iso-country") || key.contains("gsm.operator.iso-country") -> if (countryCode.isNotBlank()) countryCode.lowercase() else null
                        key == "gsm.sim.state" || key.startsWith("gsm.sim.state.") -> "READY"
                        isSettingsPackage && key.contains("ro.hardware.egl") -> if (gpuRenderer.isNotBlank()) gpuRenderer else null
                        else -> null
                    }
                    if (spoofed != null) {
                        param.result = spoofed
                    } else if (!key.contains("native.bridge") && !key.contains("nativebridge") && !key.startsWith("ro.dalvik.vm.isa.")) {
                        // Catch-all sanitizer: if any unmapped system property returns an emulator/virtual token, scrub it to real hardware
                        val origVal = param.result as? String
                        if (!origVal.isNullOrEmpty()) {
                            val lowVal = origVal.lowercase()
                            if (lowVal.contains("goldfish") || lowVal.contains("ranchu") || lowVal.contains("vbox") ||
                                lowVal.contains("qemu") || lowVal.contains("sdk_gphone") || lowVal.contains("emulator") ||
                                lowVal.contains("nox") || lowVal.contains("ttvm") || lowVal.contains("bluestacks") ||
                                lowVal.contains("ldplayer") || lowVal.contains("microvirt") || lowVal.contains("droid4x") ||
                                lowVal.contains("genymotion")
                            ) {
                                param.result = cpuVals.boardPlatform
                            }
                        }
                    }
                }
            }
            XposedHelpers.findAndHookMethod(spClass, "get", String::class.java, handler)
            try {
                XposedHelpers.findAndHookMethod(spClass, "get", String::class.java, String::class.java, handler)
            } catch (_: Throwable) {}

            // Hook getInt(String, int) for ro.debuggable, ro.secure, ro.boot.flash.locked, ro.kernel.qemu
            try {
                XposedHelpers.findAndHookMethod(
                    spClass,
                    "getInt",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val key = (param.args[0] as? String)?.lowercase() ?: return
                            when {
                                key == "ro.debuggable" || key == "service.adb.root" || key == "ro.adb.root" || key == "ro.boot.warranty_bit" || key == "ro.warranty_bit" || key == "ro.kernel.qemu" || key == "ro.boot.qemu" || key.startsWith("qemu.") || key == "sys.oem_unlock_allowed" ->
                                    param.result = 0
                                key == "ro.secure" || key == "ro.adb.secure" || key == "ro.boot.flash.locked" || key == "ro.build.selinux" ->
                                    param.result = 1
                                isSettingsPackage && key.contains("build.version.sdk") ->
                                    param.result = sdkInt.toIntOrNull() ?: 34
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            // Hook getBoolean(String, boolean) for ro.debuggable, ro.secure, ro.boot.flash.locked, ro.kernel.qemu
            try {
                XposedHelpers.findAndHookMethod(
                    spClass,
                    "getBoolean",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val key = (param.args[0] as? String)?.lowercase() ?: return
                            when {
                                key == "ro.debuggable" || key == "service.adb.root" || key == "ro.adb.root" || key == "ro.kernel.qemu" || key == "ro.boot.qemu" || key.startsWith("qemu.") || key == "sys.oem_unlock_allowed" || key == "ro.hardware.virtual_device" ->
                                    param.result = false
                                key == "ro.secure" || key == "ro.adb.secure" || key == "ro.boot.flash.locked" || key == "ro.build.selinux" ->
                                    param.result = true
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun isReadOnlySystemPath(pathRaw: String?): Boolean {
        val p = pathRaw?.trim()?.lowercase() ?: return false
        if (p.isEmpty()) return false
        if (p == "/" || p == "/system" || p == "/vendor" || p == "/product" ||
            p == "/odm" || p == "/sbin" || p == "/etc" || p == "/proc" || p == "/sys" ||
            p == "/system_ext" || p == "/apex"
        ) {
            return true
        }
        if (p.startsWith("/system/") || p.startsWith("/vendor/") || p.startsWith("/product/") ||
            p.startsWith("/odm/") || p.startsWith("/sbin/") || p.startsWith("/etc/") ||
            p.startsWith("/system_ext/") || p.startsWith("/apex/")
        ) {
            return true
        }
        return false
    }

    private fun isBlockedEnvPath(pathRaw: String?, enableRootBypass: Boolean = true): Boolean {
        if (isInternalFileAccess.get() == true) return false
        val rawLower = pathRaw?.lowercase()?.trim() ?: return false
        if (rawLower.isEmpty()) return false

        // Normalize path tricks used by root/emulator detectors (e.g. "/system/xbin/su/", "/system/bin/./su", "//system//xbin//su")
        var p = rawLower.replace("//", "/").replace("/./", "/")
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.trimEnd('/')
        if (p.length > 2 && p.endsWith("/.")) p = p.removeSuffix("/.")

        // Always hide PhantomCore identity config file and app directories
        // IMPORTANT: Do NOT block virtual container or multi-user data directories (e.g. Dual Space, Parallel Space, /data/user/999)
        // because target apps hosted inside virtual/dual containers store their own APK, libs, databases, and files there!
        if (p.contains("phantom_identity.json") || 
            p.contains(".phantom_walk_state") ||
            p == "/data/data/com.aistudio.ghostdevice.qxvz" ||
            p.startsWith("/data/data/com.aistudio.ghostdevice.qxvz/") ||
            p == "/data/user/0/com.aistudio.ghostdevice.qxvz" ||
            p.startsWith("/data/user/0/com.aistudio.ghostdevice.qxvz/") ||
            p == "/data/data/com.aistudio.phantomcore.qxvz" ||
            p.startsWith("/data/data/com.aistudio.phantomcore.qxvz/") ||
            p == "/data/user/0/com.aistudio.phantomcore.qxvz" ||
            p.startsWith("/data/user/0/com.aistudio.phantomcore.qxvz/")
        ) return true

        // Always block emulator pipes, sockets, drivers, and virtual device markers
        // Note: Do NOT block /system/bin/houdini, libhoudini.so, or libndk_translation.so so ARM native libraries load cleanly on x86_64 hosts!
        if (p == "/dev/socket/qemud" || p == "/dev/qemu_pipe" || p == "/dev/goldfish_pipe" ||
            p == "/dev/goldfish_address_space" || p == "/dev/goldfish_sync" ||
            p == "/dev/vboxguest" || p == "/dev/vboxuser" || p == "/dev/vboxdrv" ||
            p == "/sys/qemu_trace" || p == "/dev/socket/genyd" || p == "/dev/socket/baseband_genyd" ||
            p.startsWith("/dev/socket/windroyed-") || p == "/dev/bst_gps" || p == "/dev/bst_ime" ||
            p == "/dev/bstmods" || p == "/dev/nemuser" || p == "/data/youwave_id" ||
            p == "/system/etc/binfmt_misc" || p.startsWith("/system/etc/binfmt_misc/") ||
            p == "/system/bin/qemu-props" || p == "/system/bin/qemud" ||
            p == "/system/bin/androvm-prop" || p == "/system/bin/microvirt-prop" ||
            p == "/system/bin/nox-prop" || p == "/system/bin/ttvm-prop" ||
            p == "/system/bin/droid4x-prop" || p == "/system/bin/windroyed" ||
            p == "/system/bin/nemud" || p == "/system/bin/bstfolderd" ||
            p.contains("qemu") || p.contains("goldfish") || p.contains("ranchu") ||
            p.contains("/vbox") || p.contains("vboxguest") || p.contains("vboxsf") ||
            p.contains("vbox86") || p.contains("ttvm_x86") || p.contains("android_x86") ||
            p == "/x86.prop" || p.endsWith(".nox.rc") || p == "/fstab.nox" ||
            p.endsWith(".andy.rc") || p == "/fstab.andy"
        ) {
            return true
        }

        // CRITICAL: NEVER block regular app DEX, APK, ODEX, VDEX or SO library loading by ART runtime/classloaders!
        if (p.endsWith(".apk") || p.endsWith(".dex") || p.endsWith(".odex") || p.endsWith(".vdex") || p.endsWith(".so")) {
            if (p.contains("xposedbridge.jar") || p.contains("superuser.apk") || p.contains("supersu.apk") ||
                p.contains("magisk.apk") || p.contains("kinguser.apk") ||
                p.contains("libc_malloc_debug_qemu.so") || p.contains("libdroid4x.so") ||
                p.contains("libnoxspeedup.so") || p.contains("libmemuguest.so") ||
                p.contains("libbstfolder.so")
            ) {
                return true
            }
            return false
        }

        if (!enableRootBypass) return false

        // Hide /sbin, /debug_ramdisk, /su, /dev/su
        if (p == "/sbin" || p.startsWith("/sbin/") ||
            p == "/debug_ramdisk" || p.startsWith("/debug_ramdisk/") ||
            p == "/su" || p.startsWith("/su/") ||
            p == "/dev/su" || p.startsWith("/dev/su/")
        ) {
            return true
        }

        val fileName = p.substringAfterLast('/')
        if (fileName == "su" || fileName == "daemonsu" || fileName == "busybox" ||
            fileName == "magisk" || fileName == "magiskinit" || fileName == "magiskpolicy" ||
            fileName == "magiskhide" || fileName == "resetprop" || fileName == "supolicy" ||
            fileName == "ku.sud" || fileName == "suhide" || fileName == "sugote" ||
            fileName == "su-backup" || fileName == ".su" || fileName == "ksud" ||
            fileName == "apd" || fileName == "bstk"
        ) {
            return true
        }

        // Root APKs, VPhoneGaGa / Superuser daemons & directories
        if (p.contains("/superuser.apk") || p.contains("/supersu.apk") ||
            p.contains("/kinguser.apk") || p.contains("/magisk.apk") ||
            p.contains("/system/app/superuser") || p.contains("/system/priv-app/superuser") ||
            p.contains("/system/etc/init/superuser") || p.contains("/dev/com.koushikdutta.superuser") ||
            p.contains("/data/adb") || p.contains("/dev/.magisk") ||
            p.contains("/cache/magisk") || p.contains("/data/magisk") ||
            p.contains("/magisk/") || p.contains("/system/bin/.ext") ||
            p.contains("/system/usr/we-need-root") || p.contains("/system/bin/failsafe") ||
            p.contains("/system/sd/xbin") || p.contains("/data/local/xbin") ||
            p.contains("/data/local/bin/su") || p.contains("/data/local/su") ||
            p.contains("99supersudaemon") || p.contains(".has_su_daemon") ||
            p.contains(".installed_su_daemon") || p.contains("com.koushikdutta.superuser") ||
            p.contains("com.topjohnwu.magisk") || p.contains("eu.chainfire.supersu") ||
            p.contains("me.weishu.kernelsu") || p.contains("xposedbridge.jar") ||
            p.contains("edxp") || p.contains("lspd")
        ) {
            return true
        }

        return false
    }

    private fun isHiddenPackageName(
        pkgRaw: String?,
        currentTargetPkg: String,
        enableRootBypass: Boolean = true,
        includeCloners: Boolean = true
    ): Boolean {
        val p = pkgRaw?.lowercase()?.trim() ?: return false
        if (p.isEmpty() || p == currentTargetPkg.lowercase()) return false

        val phantomAndEmulatorPkgs = arrayOf(
            "com.aistudio.ghostdevice",
            "com.aistudio.phantomcore",
            "com.example.phantomcore",
            "com.bluestacks",
            "com.bignox.app",
            "com.vphone.launcher",
            "com.microvirt.installer",
            "com.microvirt.launcher",
            "com.microvirt.market",
            "com.microvirt.memuime",
            "com.kaopu001.tiantianserver",
            "com.kpzs.filemanager",
            "com.genymotion.superuser",
            "com.genymotion.genyd",
            "com.android.ld.appstore",
            "com.ldmnq.launcher3",
            "com.google.android.launcher.layouts.genymotion"
        )
        for (item in phantomAndEmulatorPkgs) {
            if (p == item || p.startsWith("$item.")) return true
        }

        if (includeCloners) {
            val clonerPkgs = arrayOf(
                "com.clone.android.dual.space",
                "com.lbe.parallel.intl",
                "com.lbe.parallel",
                "com.parallel.space.lite",
                "com.parallel.space.pro",
                "com.excelliance.dualaid",
                "com.excelliance.multiaccounts",
                "com.ludashi.dualspace",
                "com.ludashi.superboost",
                "com.polestar.super.clone",
                "com.polestar.multiaccount",
                "com.applisto.appcloner",
                "io.va.exposed",
                "io.virtualapp",
                "com.oasisfeng.island",
                "net.typeblog.shelter",
                "com.vphonegaga.titan",
                "com.vmos.pro",
                "com.vmos.glb",
                "com.multi.parallel.dualspace.cloner"
            )
            for (item in clonerPkgs) {
                if (p == item || p.startsWith("$item.")) return true
            }
        }

        if (!enableRootBypass) return false

        val rootPackages = arrayOf(
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
            "io.github.huskydg.magisk",
            "me.weishu.kernelsu",
            "me.bmax.apatch",
            "eu.chainfire.supersu",
            "com.noshufou.android.su",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.smedialink.oneclickroot",
            "com.zhiqupk.root.global",
            "com.alephzain.framaroot",
            "de.robv.android.xposed",
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            "org.meowcat.edxposed.manager",
            "com.solohsu.android.edxp.manager",
            "com.saurik.substrate",
            "com.devadvance.rootcloak",
            "com.zachspong.temprootremovejb",
            "com.amphoras.hidemyroot",
            "com.formyhm.hideroot",
            "me.phh.superuser",
            "com.tsng.hidemyapplist",
            "com.chelpus.lackypatch",
            "com.dimonvideo.luckypatcher",
            "com.forpda.lp",
            "com.scottyab.rootbeer",
            "com.topjohnwu.magisk.manager",
            "de.robv.android.xposed.installer",
            "com.noshufou.android.su.installer",
            "com.koushikdutta.superuser.installer",
            "com.thirdparty.superuser.installer",
            "com.yellowes.su.installer",
            "com.jrummy.root.browserfree",
            "com.jrummy.libnode",
            "com.speedsoftware.rootexplorer",
            "com.joeykrim.rootcheck",
            "com.farproc.wifi.analyzer",
            "com.metago.astro",
            "com.cgollner.flashify",
            "com.keramidas.TitaniumBackup",
            "com.stericson.busybox",
            "com.stericson.busybox.gold",
            "com.stericson.roottools",
            "com.dvtonder.chronus",
            "com.cleanmaster.mguard",
            "com.m0narx.tweaks",
            "com.citrusui.citrus"
        )
        for (item in rootPackages) {
            if (p == item || p.startsWith("$item.")) return true
        }
        if (p.contains("magisk") || p.contains("supersu") || p.contains("superuser") ||
            p.contains("xposed") || p.contains("lsposed") || p.contains("kernelsu") ||
            p.contains("rootcloak") || p.contains("luckypatcher") || p.contains("bluestacks") ||
            p.contains("bignox") || p.contains("genymotion") || p.contains("microvirt")
        ) {
            return true
        }
        return false
    }

    private fun hookRootAndEnvironmentCloak(
        classLoader: ClassLoader,
        targetPkg: String,
        cpuVals: CpuHardwareRandomValues,
        resetTimestamp: Long = 0L,
        enableRootBypass: Boolean = true
    ) {
        val fakeNonExistentPath = "/system/bin/nonexistent_phantom_000"

        // Do NOT redirect /proc/self/maps, /proc/self/status, or /proc/self/cmdline via static cache files:
        // static snapshots miss dynamically loaded native libraries (Unity, Flutter, WebView) and break native crash/thread readers.
        // BufferedReader.readLine() hooks below sanitize /proc/self/maps and /proc/self/mounts lines safely in memory.

        // 1. Hook java.io.File methods (exists, canExecute, canRead, canWrite, isFile, isDirectory, length, list, listFiles)
        try {
            val falseHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val file = param.thisObject as? File ?: return
                    if (isBlockedEnvPath(file.path, enableRootBypass)) {
                        param.result = false
                    }
                }
            }
            XposedHelpers.findAndHookMethod(File::class.java, "exists", falseHook)
            XposedHelpers.findAndHookMethod(File::class.java, "canExecute", falseHook)
            XposedHelpers.findAndHookMethod(File::class.java, "canRead", falseHook)
            XposedHelpers.findAndHookMethod(File::class.java, "isFile", falseHook)
            XposedHelpers.findAndHookMethod(File::class.java, "isDirectory", falseHook)

            XposedHelpers.findAndHookMethod(File::class.java, "canWrite", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val file = param.thisObject as? File ?: return
                    if (isBlockedEnvPath(file.path, enableRootBypass) || (enableRootBypass && isReadOnlySystemPath(file.path))) {
                        param.result = false
                    }
                }
            })

            XposedHelpers.findAndHookMethod(File::class.java, "length", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val file = param.thisObject as? File ?: return
                    if (isBlockedEnvPath(file.path, enableRootBypass)) {
                        param.result = 0L
                    }
                }
            })

            XposedHelpers.findAndHookMethod(File::class.java, "list", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val dir = param.thisObject as? File ?: return
                    if (isBlockedEnvPath(dir.path, enableRootBypass)) {
                        param.result = null
                        return
                    }
                    val arr = param.result as? Array<*> ?: return
                    val filtered = arr.filterIsInstance<String>().filter { name ->
                        !isBlockedEnvPath("${dir.path}/$name", enableRootBypass)
                    }.toTypedArray()
                    param.result = filtered
                }
            })

            XposedHelpers.findAndHookMethod(File::class.java, "listFiles", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val dir = param.thisObject as? File ?: return
                    if (isBlockedEnvPath(dir.path, enableRootBypass)) {
                        param.result = null
                        return
                    }
                    val arr = param.result as? Array<*> ?: return
                    val filtered = arr.filterIsInstance<File>().filter { f ->
                        !isBlockedEnvPath(f.path, enableRootBypass)
                    }.toTypedArray()
                    param.result = filtered
                }
            })
        } catch (_: Throwable) {}

        // 1b. Hook java.io.UnixFileSystem (lowest Java File layer)
        try {
            val ufsCls = XposedHelpers.findClass("java.io.UnixFileSystem", classLoader)
            if (ufsCls != null) {
                hookAllMethodsCompat(ufsCls, "checkAccess", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val f = param.args.getOrNull(0) as? File ?: return
                        val access = param.args.getOrNull(1) as? Int ?: 0
                        if (isBlockedEnvPath(f.path, enableRootBypass)) {
                            param.result = false
                        } else if ((access and 2) != 0 && isReadOnlySystemPath(f.path)) {
                            param.result = false
                        }
                    }
                })
                hookAllMethodsCompat(ufsCls, "getBooleanAttributes", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val f = param.args.getOrNull(0) as? File ?: return
                        if (isBlockedEnvPath(f.path, enableRootBypass)) {
                            param.result = 0
                        }
                    }
                })
            }
        } catch (_: Throwable) {}

        // 1c. Hook java.nio.file.Files (for Kotlin Path.exists() & Java NIO checks)
        try {
            val nioFilesCls = XposedHelpers.findClass("java.nio.file.Files", classLoader)
            if (nioFilesCls != null) {
                for (mName in arrayOf("exists", "isExecutable", "isReadable", "isRegularFile", "isDirectory")) {
                    hookAllMethodsCompat(nioFilesCls, mName, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val pathObj = param.args.firstOrNull() ?: return
                            if (isBlockedEnvPath(pathObj.toString(), enableRootBypass)) {
                                param.result = false
                            }
                        }
                    })
                }
                hookAllMethodsCompat(nioFilesCls, "isWritable", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pathObj = param.args.firstOrNull() ?: return
                        val pathStr = pathObj.toString()
                        if (isBlockedEnvPath(pathStr, enableRootBypass) || isReadOnlySystemPath(pathStr)) {
                            param.result = false
                        }
                    }
                })
            }
        } catch (_: Throwable) {}

        // 2. Hook FileInputStream constructors for blocked root/su/emulator files
        try {
            val fisFileCtor = java.io.FileInputStream::class.java.getDeclaredConstructor(File::class.java)
            XposedBridge.hookMethod(
                fisFileCtor,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInternalFileAccess.get() == true) return
                        val file = param.args[0] as? File ?: return
                        val normPath = file.path.trim().replace("//", "/")
                        if (normPath == "/proc/sys/kernel/random/boot_id" || normPath == "/proc/sys/kernel/random/uuid") {
                            val staged = File("/data/local/tmp/.phantom_boot_id")
                            if (staged.exists() && staged.canRead()) {
                                param.args[0] = staged
                                return
                            }
                        } else if (normPath.endsWith("/mmcblk0/device/cid") || normPath.endsWith("/mmcblk0/device/serial") || normPath.endsWith("/sda/device/serial")) {
                            val staged = File("/data/local/tmp/.phantom_mmc_cid")
                            if (staged.exists() && staged.canRead()) {
                                param.args[0] = staged
                                return
                            }
                        } else if (normPath == "/sys/devices/soc0/serial_number") {
                            val staged = File("/data/local/tmp/.phantom_soc_serial")
                            if (staged.exists() && staged.canRead()) {
                                param.args[0] = staged
                                return
                            }
                        }
                        if (isBlockedEnvPath(normPath, enableRootBypass)) {
                            param.args[0] = File(fakeNonExistentPath)
                        }
                    }
                }
            )
            val fisStrCtor = java.io.FileInputStream::class.java.getDeclaredConstructor(String::class.java)
            XposedBridge.hookMethod(
                fisStrCtor,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInternalFileAccess.get() == true) return
                        val path = param.args[0] as? String ?: return
                        val normPath = path.trim().replace("//", "/")
                        if (normPath == "/proc/sys/kernel/random/boot_id" || normPath == "/proc/sys/kernel/random/uuid") {
                            val staged = File("/data/local/tmp/.phantom_boot_id")
                            if (staged.exists() && staged.canRead()) {
                                param.args[0] = staged.path
                                return
                            }
                        } else if (normPath.endsWith("/mmcblk0/device/cid") || normPath.endsWith("/mmcblk0/device/serial") || normPath.endsWith("/sda/device/serial")) {
                            val staged = File("/data/local/tmp/.phantom_mmc_cid")
                            if (staged.exists() && staged.canRead()) {
                                param.args[0] = staged.path
                                return
                            }
                        } else if (normPath == "/sys/devices/soc0/serial_number") {
                            val staged = File("/data/local/tmp/.phantom_soc_serial")
                            if (staged.exists() && staged.canRead()) {
                                param.args[0] = staged.path
                                return
                            }
                        }
                        if (isBlockedEnvPath(normPath, enableRootBypass)) {
                            param.args[0] = fakeNonExistentPath
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 3. Hook android.system.Os, libcore.io.Linux, and libcore.io.BlockGuardOs (access, stat, lstat, readlink) safely without hooking open
        val osClassNames = arrayOf(
            "android.system.Os",
            "libcore.io.Linux",
            "libcore.io.BlockGuardOs",
            "libcore.io.ForwardingOs"
        )
        for (osClsName in osClassNames) {
            try {
                val osCls = XposedHelpers.findClass(osClsName, classLoader) ?: continue
                hookAllMethodsCompat(osCls, "access", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = param.args.getOrNull(0) as? String ?: return
                        val mode = param.args.getOrNull(1) as? Int ?: 0
                        if (isBlockedEnvPath(path, enableRootBypass) || ((mode and 2) != 0 && isReadOnlySystemPath(path))) {
                            param.result = false
                        }
                    }
                })
                for (methodName in arrayOf("stat", "lstat", "readlink")) {
                    hookAllMethodsCompat(osCls, methodName, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val path = param.args.getOrNull(0) as? String ?: return
                            if (isBlockedEnvPath(path, enableRootBypass)) {
                                param.args[0] = fakeNonExistentPath
                            }
                        }
                    })
                }
            } catch (_: Throwable) {}
        }

        // 4. Hook ProcessBuilder.start & Runtime.exec safely for root & emulator detection cloaking
        try {
            val isDirectRootCommand = fun(cmdList: List<String>): Boolean {
                if (cmdList.isEmpty()) return false
                val first = cmdList[0].trim().lowercase()
                val fullCmd = cmdList.joinToString(" ").lowercase()

                // Direct root / emulator binaries
                if (first == "su" || first.endsWith("/su") ||
                    first == "magisk" || first.endsWith("/magisk") ||
                    first == "busybox" || first.endsWith("/busybox") ||
                    first == "daemonsu" || first.endsWith("/daemonsu") ||
                    first == "magiskhide" || first.endsWith("/magiskhide") ||
                    first == "resetprop" || first.endsWith("/resetprop") ||
                    first == "ksud" || first.endsWith("/ksud") ||
                    first == "apd" || first.endsWith("/apd") ||
                    first == "qemu-props" || first.endsWith("/qemu-props") ||
                    first == "qemud" || first.endsWith("/qemud") ||
                    first == "nox-prop" || first.endsWith("/nox-prop") ||
                    first == "microvirt-prop" || first.endsWith("/microvirt-prop") ||
                    first == "androvm-prop" || first.endsWith("/androvm-prop")
                ) return true

                // Shell wrappers trying to run su
                if (first == "sh" || first.endsWith("/sh")) {
                    if (fullCmd.contains(" su") || fullCmd.contains("su ") ||
                        fullCmd.contains("magisk") || fullCmd.contains("busybox")
                    ) return true
                }

                return false
            }

            val isWhichRootLookup = fun(cmdList: List<String>): Boolean {
                if (cmdList.isEmpty()) return false
                val first = cmdList[0].trim().lowercase()
                if (first == "which" || first.endsWith("/which") || first == "whereis" || first == "type") {
                    val target = cmdList.drop(1).joinToString(" ").lowercase()
                    if (target.contains("su") || target.contains("busybox") || target.contains("magisk") || target.contains("ksud") || target.contains("daemonsu")) {
                        return true
                    }
                }
                return false
            }

            hookAllMethodsCompat(Runtime::class.java, "exec", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val arg0 = param.args.firstOrNull() ?: return
                    val parts = when (arg0) {
                        is String -> arg0.trim().split("\\s+".toRegex())
                        is Array<*> -> arg0.filterIsInstance<String>()
                        else -> return
                    }
                    if (isWhichRootLookup(parts)) {
                        if (arg0 is String) {
                            param.args[0] = "which nonexistent_phantom_000"
                        } else if (arg0 is Array<*>) {
                            param.args[0] = arrayOf(parts[0], "nonexistent_phantom_000")
                        }
                        return
                    }
                    if (isDirectRootCommand(parts)) {
                        param.throwable = java.io.IOException("Cannot run program \"${parts.firstOrNull() ?: "su"}\": error=2, No such file or directory")
                    }
                }
            })

            hookAllMethodsCompat(ProcessBuilder::class.java, "start", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pb = param.thisObject as? ProcessBuilder ?: return
                    val cmd = pb.command() ?: return
                    if (isWhichRootLookup(cmd)) {
                        val firstCmd = cmd.firstOrNull() ?: "which"
                        pb.command(listOf(firstCmd, "nonexistent_phantom_000"))
                        return
                    }
                    if (isDirectRootCommand(cmd)) {
                        param.throwable = java.io.IOException("Cannot run program \"${cmd.firstOrNull() ?: "su"}\": error=2, No such file or directory")
                    }
                }
            })
        } catch (_: Throwable) {}

        // 5. Hook ApplicationPackageManager to hide root/xposed/emulator/spoof apps, clear FLAG_DEBUGGABLE, & report real phone hardware features
        try {
            val apmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            if (apmClass != null) {
                val falseEmulatorFeatures = setOf(
                    "android.hardware.type.pc",
                    "android.hardware.type.embedded",
                    "android.hardware.type.television",
                    "android.hardware.type.watch",
                    "android.hardware.type.automotive",
                    "android.software.leanback",
                    "android.software.leanback_only",
                    "com.google.android.play.feature.hpe_experience",
                    "org.chromium.arc",
                    "org.chromium.arc.device_management"
                )
                val trueRealPhoneFeatures = setOf(
                    "android.hardware.telephony",
                    "android.hardware.telephony.gsm",
                    "android.hardware.telephony.ims",
                    "android.hardware.camera",
                    "android.hardware.camera.any",
                    "android.hardware.camera.front",
                    "android.hardware.camera.autofocus",
                    "android.hardware.camera.flash",
                    "android.hardware.bluetooth",
                    "android.hardware.bluetooth_le",
                    "android.hardware.wifi",
                    "android.hardware.wifi.direct",
                    "android.hardware.location",
                    "android.hardware.location.gps",
                    "android.hardware.location.network",
                    "android.hardware.sensor.accelerometer",
                    "android.hardware.sensor.gyroscope",
                    "android.hardware.sensor.compass",
                    "android.hardware.sensor.light",
                    "android.hardware.sensor.proximity",
                    "android.hardware.sensor.barometer",
                    "android.hardware.sensor.stepcounter",
                    "android.hardware.sensor.stepdetector",
                    "android.hardware.touchscreen",
                    "android.hardware.touchscreen.multitouch",
                    "android.hardware.touchscreen.multitouch.distinct",
                    "android.hardware.touchscreen.multitouch.jazzhand",
                    "android.hardware.faketouch",
                    "android.hardware.fingerprint",
                    "android.hardware.microphone",
                    "android.hardware.usb.host",
                    "android.hardware.usb.accessory",
                    "android.hardware.nfc",
                    "android.software.webview"
                )
                hookAllMethodsCompat(apmClass, "hasSystemFeature", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val feat = (param.args.firstOrNull() as? String)?.lowercase()?.trim() ?: return
                        if (feat in falseEmulatorFeatures) {
                            param.result = false
                        } else if (feat in trueRealPhoneFeatures) {
                            param.result = true
                        }
                    }
                })

                hookAllMethodsCompat(apmClass, "getPackageInfo", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args.firstOrNull() as? String ?: return
                        if (isHiddenPackageName(pkgName, targetPkg, enableRootBypass, includeCloners = false)) {
                            param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkgName)
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val pkgInfo = param.result as? android.content.pm.PackageInfo ?: return
                        pkgInfo.applicationInfo?.let { appInfo ->
                            appInfo.flags = appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
                        }
                    }
                })

                hookAllMethodsCompat(apmClass, "getApplicationInfo", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args.firstOrNull() as? String ?: return
                        if (isHiddenPackageName(pkgName, targetPkg, enableRootBypass, includeCloners = false)) {
                            param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkgName)
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val appInfo = param.result as? android.content.pm.ApplicationInfo ?: return
                        appInfo.flags = appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
                    }
                })

                hookAllMethodsCompat(apmClass, "getPackageGids", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args.firstOrNull() as? String ?: return
                        if (isHiddenPackageName(pkgName, targetPkg, enableRootBypass, includeCloners = false)) {
                            param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkgName)
                        }
                    }
                })

                hookAllMethodsCompat(apmClass, "getLaunchIntentForPackage", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args.firstOrNull() as? String ?: return
                        if (isHiddenPackageName(pkgName, targetPkg, enableRootBypass, includeCloners = false)) {
                            param.result = null
                        }
                    }
                })

                hookAllMethodsCompat(apmClass, "getInstallerPackageName", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = "com.android.vending"
                    }
                })

                // Also hook Android 11+ getInstallSourceInfo so apps checking InstallSourceInfo always see official Google Play Store installation
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val isiClass = XposedHelpers.findClass("android.content.pm.InstallSourceInfo", classLoader)
                        if (isiClass != null) {
                            for (mName in arrayOf("getInstallingPackageName", "getInitiatingPackageName", "getOriginatingPackageName")) {
                                hookAllMethodsCompat(isiClass, mName, object : XC_MethodHook() {
                                    override fun afterHookedMethod(param: MethodHookParam) {
                                        if (param.throwable != null) return
                                        param.result = "com.android.vending"
                                    }
                                })
                            }
                        }
                    }
                } catch (_: Throwable) {}

                hookAllMethodsCompat(apmClass, "getInstalledPackages", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val list = param.result as? List<*> ?: return
                        param.result = ArrayList(list.filter { item ->
                            val pkgInfo = item as? android.content.pm.PackageInfo
                            isHiddenPackageName(pkgInfo?.packageName, targetPkg, enableRootBypass) == false
                        })
                    }
                })

                hookAllMethodsCompat(apmClass, "getInstalledApplications", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val list = param.result as? List<*> ?: return
                        val filtered = ArrayList<Any?>()
                        for (item in list) {
                            val appInfo = item as? android.content.pm.ApplicationInfo
                            if (appInfo != null) {
                                if (!isHiddenPackageName(appInfo.packageName, targetPkg, enableRootBypass)) {
                                    appInfo.flags = appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
                                    filtered.add(appInfo)
                                }
                            } else {
                                filtered.add(item)
                            }
                        }
                        param.result = filtered
                    }
                })

                hookAllMethodsCompat(apmClass, "queryIntentActivities", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val list = param.result as? List<*> ?: return
                        param.result = ArrayList(list.filter { item ->
                            val resInfo = item as? android.content.pm.ResolveInfo
                            isHiddenPackageName(resInfo?.activityInfo?.packageName, targetPkg, enableRootBypass) == false
                        })
                    }
                })
            }
        } catch (_: Throwable) {}

        // 6. Hook BufferedReader.readLine() safely with in-place sanitization and /proc/cpuinfo, /proc/tty/drivers, /proc/self/status & getprop spoofing
        try {
            XposedHelpers.findAndHookMethod(
                java.io.BufferedReader::class.java,
                "readLine",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        var line = param.result as? String ?: return
                        if (line.isEmpty()) return
                        val lower = line.lowercase()
                        if (lower.startsWith("tracerpid:")) {
                            param.result = "TracerPid:\t0"
                            return
                        }
                        if (lower.startsWith("hardware\t:") || lower.startsWith("hardware :")) {
                            param.result = "Hardware\t: ${cpuVals.cpuHardware}"
                            return
                        }
                        if (lower.startsWith("serial\t:") || lower.startsWith("serial :")) {
                            val chipSer = readSysPropInternal("persist.sys.phantom.chipid").removePrefix("0x").ifBlank { "a8f49102c7e319b4" }
                            param.result = "Serial\t\t: $chipSer"
                            return
                        }
                        if (lower.startsWith("model name\t:") || lower.startsWith("model name :") || lower.startsWith("processor\t:") || lower.startsWith("processor :")) {
                            if (lower.startsWith("processor") && !lower.contains(": 0") && !lower.contains(": 1") && !lower.contains(": 2") && !lower.contains(": 3") && !lower.contains(": 4") && !lower.contains(": 5") && !lower.contains(": 6") && !lower.contains(": 7")) {
                                param.result = "Processor\t: AArch64 Processor rev 1 (aarch64)"
                                return
                            } else if (lower.startsWith("model name")) {
                                param.result = "model name\t: ${cpuVals.processorFull}"
                                return
                            }
                        }
                        if (lower.startsWith("vendor_id\t:") || lower.startsWith("vendor_id :") ||
                            lower.startsWith("cpu family\t:") || lower.startsWith("clflush size\t:") ||
                            lower.startsWith("address sizes\t:")
                        ) {
                            param.result = "CPU implementer\t: 0x41"
                            return
                        }
                        if (lower.startsWith("flags\t:") || lower.startsWith("flags :") ||
                            ((lower.startsWith("features\t:") || lower.startsWith("features :")) &&
                                (lower.contains("hypervisor") || lower.contains("qemu") || lower.contains("vbox") || lower.contains("sse2") || lower.contains("avx")))
                        ) {
                            param.result = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid"
                            return
                        }
                        if (lower.contains("[ro.soc.model]:")) {
                            param.result = "[ro.soc.model]: [${cpuVals.socModelShort}]"
                            return
                        }
                        if (lower.contains("[ro.soc.manufacturer]:")) {
                            param.result = "[ro.soc.manufacturer]: [${cpuVals.socManufacturer}]"
                            return
                        }
                        if (lower.contains("[ro.hardware]:")) {
                            param.result = "[ro.hardware]: [${cpuVals.cpuHardware}]"
                            return
                        }
                        if (lower.contains("[ro.board.platform]:")) {
                            param.result = "[ro.board.platform]: [${cpuVals.boardPlatform}]"
                            return
                        }
                        // Sanitize /proc/self/maps lines safely without corrupting foreign base.apk or /data/data/ paths
                        if (mapsLinePrefixRegex.matches(line)) {
                            param.result = sanitizeMapsOrMountsLine(line, targetPkg)
                            return
                        }
                        // Sanitize emulator tokens in /proc/tty/drivers, /proc/cpuinfo, /proc/iomem, /proc/version
                        if (!line.contains("/data/") && (
                            lower.contains("goldfish") || lower.contains("ranchu") || lower.contains("qemu") ||
                            lower.contains("vbox86") || lower.contains("vboxguest") || lower.contains("virtualbox") ||
                            lower.contains("genymotion") || lower.contains("bluestacks") || lower.contains("ttvm") ||
                            lower.contains("bignox") || lower.contains("nox_") || lower.contains("ldplayer") ||
                            lower.contains("microvirt") || lower.contains("droid4x") || lower.contains("hypervisor")
                            )
                        ) {
                            line = line
                                .replace("goldfish_tty", "ttyMSM", ignoreCase = true)
                                .replace("goldfish", cpuVals.boardPlatform, ignoreCase = true)
                                .replace("ranchu", cpuVals.boardPlatform, ignoreCase = true)
                                .replace("qemu", cpuVals.cpuHardware, ignoreCase = true)
                                .replace("vbox86", cpuVals.boardPlatform, ignoreCase = true)
                                .replace("vboxguest", cpuVals.boardPlatform, ignoreCase = true)
                                .replace("virtualbox", cpuVals.socManufacturer, ignoreCase = true)
                                .replace("genymotion", cpuVals.socManufacturer, ignoreCase = true)
                                .replace("bluestacks", cpuVals.socManufacturer, ignoreCase = true)
                                .replace("ttvm_x86", cpuVals.boardPlatform, ignoreCase = true)
                                .replace("ttvm", cpuVals.boardPlatform, ignoreCase = true)
                                .replace("bignox", cpuVals.boardPlatform, ignoreCase = true)
                                .replace("ldplayer", cpuVals.boardPlatform, ignoreCase = true)
                                .replace("microvirt", cpuVals.boardPlatform, ignoreCase = true)
                                .replace("droid4x", cpuVals.boardPlatform, ignoreCase = true)
                                .replace("hypervisor", "asimd", ignoreCase = true)
                            param.result = line
                        }
                        if (enableRootBypass) {
                            if (lower.contains("magisk") || lower.contains("lsposed") || lower.contains("lspd") ||
                                lower.contains("edxposed") || lower.contains("xposed") || lower.contains("phantomcore") ||
                                lower.contains("/sbin/su") || lower.contains("/system/bin/su") ||
                                lower.contains("/system/xbin/su") || lower.contains("supersu") ||
                                lower.contains("kernelsu") || lower.contains("zygisk") || lower.contains("riru") ||
                                lower.contains("test-keys")
                            ) {
                                line = sanitizeMapsOrMountsLine(line, targetPkg)
                                    .replace("/sbin/su", "/system/bin/sh", ignoreCase = true)
                                    .replace("/system/bin/su", "/system/bin/sh", ignoreCase = true)
                                    .replace("/system/xbin/su", "/system/bin/sh", ignoreCase = true)
                                    .replace("test-keys", "release-keys", ignoreCase = true)
                                param.result = line
                            }
                            if (line.contains(" /system") || line.contains(" /vendor") || line.contains(" /product") || line.contains(" /apex") || line.contains(" / ") || line.contains(" /system_ext")) {
                                if (line.contains("rw,") || line.contains(",rw") || line.contains(" rw ") || line.contains(" rw,")) {
                                    line = line.replace("rw,", "ro,").replace(",rw,", ",ro,").replace(",rw ", ",ro ").replace(" rw ", " ro ")
                                    param.result = line
                                }
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 7. Hook System.getenv() to strip /sbin, /su, /data/adb, magisk, XposedBridge from PATH / CLASSPATH
        if (enableRootBypass) {
            try {
                XposedHelpers.findAndHookMethod(
                    System::class.java,
                    "getenv",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val name = (param.args[0] as? String)?.uppercase() ?: return
                            val res = param.result as? String ?: return
                            if (name == "PATH") {
                                val cleanParts = res.split(":").filter { part ->
                                    val lp = part.lowercase()
                                    !lp.contains("su") && !lp.contains("magisk") && !lp.contains("ksu") && !lp.contains("/sbin") && !lp.contains("/data/adb")
                                }
                                param.result = cleanParts.joinToString(":")
                            } else if (name == "CLASSPATH" || name == "LD_PRELOAD") {
                                if (res.contains("xposed", true) || res.contains("lspd", true) || res.contains("riru", true) || res.contains("zygisk", true)) {
                                    param.result = ""
                                }
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // 8. Omit Throwable.getStackTrace() hook as hooking ART stack trace unwinding causes native aborts and crashes.

        // 9. Hook Debug.isDebuggerConnected(), waitingForDebugger(), & SELinux enforcement checks
        if (enableRootBypass) {
            try {
                XposedHelpers.findAndHookMethod(android.os.Debug::class.java, "isDebuggerConnected", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                })
                XposedHelpers.findAndHookMethod(android.os.Debug::class.java, "waitingForDebugger", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                })
            } catch (_: Throwable) {}

            try {
                val selinuxCls = XposedHelpers.findClass("android.os.SELinux", classLoader)
                if (selinuxCls != null) {
                    hookAllMethodsCompat(selinuxCls, "isSELinuxEnabled", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    })
                    hookAllMethodsCompat(selinuxCls, "isSELinuxEnforced", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    })
                }
            } catch (_: Throwable) {}
        }

        // 10. Hook RootBeer, RootBeerNative, Scanner (mount/getprop reader), System.loadLibrary("tool-checker"), & Flutter root channels
        if (enableRootBypass) {
            val rootBeerClasses = arrayOf(
                "com.scottyab.rootbeer.RootBeer",
                "com.kimchangyoun.rootbeerFresh.RootBeer"
            )
            for (rbClsName in rootBeerClasses) {
                try {
                    val rbCls = XposedHelpers.findClass(rbClsName, classLoader) ?: continue
                    for (mName in arrayOf(
                        "isRooted",
                        "isRootedWithoutBusyBoxCheck",
                        "isRootedWithBusyBoxCheck",
                        "detectRootManagementApps",
                        "detectPotentiallyDangerousApps",
                        "detectTestKeys",
                        "checkForBusyBoxBinary",
                        "checkForSuBinary",
                        "checkSuExists",
                        "checkForRWPaths",
                        "checkForDangerousProps",
                        "checkForRootNative",
                        "detectRootCloakingApps",
                        "checkForMagiskBinary",
                        "canLoadNativeLibrary"
                    )) {
                        hookAllMethodsCompat(rbCls, mName, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.result = false
                            }
                        })
                    }
                } catch (_: Throwable) {}
            }
            for (rbnClsName in arrayOf("com.scottyab.rootbeer.RootBeerNative", "com.kimchangyoun.rootbeerFresh.RootBeerNative")) {
                try {
                    val rbnCls = XposedHelpers.findClass(rbnClsName, classLoader) ?: continue
                    hookAllMethodsCompat(rbnCls, "checkForRoot", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = 0
                        }
                    })
                    hookAllMethodsCompat(rbnCls, "wasNativeLibraryLoaded", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = false
                        }
                    })
                    hookAllMethodsCompat(rbnCls, "setLogDebugMessages", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = 0
                        }
                    })
                } catch (_: Throwable) {}
            }

            // Note: Do NOT hook Runtime.loadLibrary0 as it disrupts ART caller-sensitive stack walking during System.loadLibrary in Unity/Flutter.

            // Sanitize java.util.Scanner.next() output used by RootBeer.propsReader(), RootBeer.mountReader(), and /proc/cpuinfo / /proc/tty/drivers scanners
            try {
                XposedHelpers.findAndHookMethod(java.util.Scanner::class.java, "next", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val res = param.result as? String ?: return
                        if (res.length > 32 && (res.contains("[ro.build.") || res.contains(" /system ") || res.contains("magisk") || res.contains("test-keys") || res.contains("goldfish", true) || res.contains("ranchu", true) || res.contains("qemu", true) || res.contains("vbox", true))) {
                            val cleaned = res.lineSequence()
                                .filter { line ->
                                    val l = line.lowercase()
                                    !l.contains("magisk") && !l.contains("lsposed") && !l.contains("xposed") &&
                                        !l.contains("supersu") && !l.contains("kernelsu") && !l.contains("/sbin/su") &&
                                        !l.contains("/system/bin/su") && !l.contains("/system/xbin/su")
                                }
                                .map { line ->
                                    var s = line
                                        .replace("test-keys", "release-keys", ignoreCase = true)
                                        .replace("dev-keys", "release-keys", ignoreCase = true)
                                        .replace("goldfish", cpuVals.boardPlatform, ignoreCase = true)
                                        .replace("ranchu", cpuVals.boardPlatform, ignoreCase = true)
                                        .replace("qemu", cpuVals.cpuHardware, ignoreCase = true)
                                        .replace("vbox", cpuVals.boardPlatform, ignoreCase = true)
                                        .replace("[ro.debuggable]: [1]", "[ro.debuggable]: [0]")
                                        .replace("[ro.secure]: [0]", "[ro.secure]: [1]")
                                        .replace("[ro.build.type]: [userdebug]", "[ro.build.type]: [user]")
                                        .replace("[ro.build.type]: [eng]", "[ro.build.type]: [user]")
                                    if (s.contains(" /system") || s.contains(" /vendor") || s.contains(" /product") || s.contains(" / ")) {
                                        s = s.replace("rw,", "ro,").replace(",rw,", ",ro,").replace(" rw ", " ro ")
                                    }
                                    s
                                }
                                .joinToString("\n")
                            param.result = cleaned
                        }
                    }
                })
            } catch (_: Throwable) {}

            // Hook Flutter MethodChannel root/jailbreak plugins (used by TopNod & Flutter fintech apps)
            try {
                val mcClass = XposedHelpers.findClass("io.flutter.plugin.common.MethodChannel\$IncomingMethodCallHandler", classLoader)
                if (mcClass != null) {
                    hookAllMethodsCompat(mcClass, "onMessage", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // Handled at MethodCall / Result level below
                        }
                    })
                }
            } catch (_: Throwable) {}
        }

        // 10b. Anti-Clone & Multi-User / Dual-App / VirtualApkCheckUtil Cloaking:
        // Note: Do NOT spoof Process.myUid() or UserHandle.getUserId(uid), as Android Binder / AttributionSource / ContentProvider
        // validates calling UID in system_server and throws SecurityException if Process.myUid() is altered.
        try {
            XposedHelpers.findAndHookMethod(android.os.UserHandle::class.java, "myUserId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = 0
                }
            })
        } catch (_: Throwable) {}

        try {
            val umCls = XposedHelpers.findClass("android.os.UserManager", classLoader)
            if (umCls != null) {
                for (mName in arrayOf("isSystemUser", "isPrimaryUser", "isMainUser")) {
                    hookAllMethodsCompat(umCls, mName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    })
                }
                for (mName in arrayOf("isManagedProfile", "isCloneProfile", "isProfile", "isDualAppProfile", "isRestrictedProfile", "isGuestUser", "isDemoUser")) {
                    hookAllMethodsCompat(umCls, mName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = false
                        }
                    })
                }
                hookAllMethodsCompat(umCls, "getUserCount", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = 1
                    }
                })
                hookAllMethodsCompat(umCls, "getUserProfiles", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.result as? List<*> ?: return
                        if (list.size > 1) {
                            param.result = list.take(1)
                        }
                    }
                })
            }
        } catch (_: Throwable) {}

        // Randomize LocalServerSocket name when clone detectors (VirtualApkCheckUtil.checkByCreateLocalServerSocket)
        // try to detect a duplicate instance by binding a static LocalServerSocket
        try {
            val lssCtor = android.net.LocalServerSocket::class.java.getDeclaredConstructor(String::class.java)
            val seenSockets = java.util.Collections.synchronizedSet(HashSet<String>())
            XposedBridge.hookMethod(lssCtor, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args.firstOrNull() as? String ?: return
                    if (!seenSockets.add(name)) {
                        param.args[0] = "${name}_${android.os.Process.myPid()}_${System.nanoTime() % 10000}"
                    }
                }
            })
        } catch (_: Throwable) {}

        // Spoof hardware sensor vendor/name if running inside a virtual/emulator container so anti-fake checks see genuine Bosch/AKM/Sensortek sensors
        try {
            val sensorCls = XposedHelpers.findClass("android.hardware.Sensor", classLoader)
            if (sensorCls != null) {
                XposedHelpers.findAndHookMethod(sensorCls, "getVendor", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = (param.result as? String) ?: ""
                        if (v.isBlank() || v.contains("Goldfish", true) || v.contains("Ranchu", true) ||
                            v.contains("QEMU", true) || v.contains("AOSP", true) ||
                            v.contains("Android", true) || v.contains("Virtual", true) ||
                            v.contains("Genymotion", true) || v.contains("Nox", true) ||
                            v.contains("BlueStacks", true) || v.contains("VBox", true) ||
                            v.contains("Microvirt", true) || v.contains("Tencent", true) ||
                            v.contains("Google", true) || v.contains("Unknown", true)
                        ) {
                            val s = param.thisObject as? android.hardware.Sensor
                            param.result = when (s?.type) {
                                2, 14 -> "AKM" // Magnetometer
                                5, 8 -> "Sensortek" // Light / Proximity
                                6 -> "STMicroelectronics" // Barometer
                                else -> "Bosch Sensortec"
                            }
                        }
                    }
                })
                XposedHelpers.findAndHookMethod(sensorCls, "getName", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val n = (param.result as? String) ?: ""
                        if (n.isBlank() || n.contains("Goldfish", true) || n.contains("Ranchu", true) ||
                            n.contains("QEMU", true) || n.contains("Virtual", true) ||
                            n.contains("Emulated", true) || n.contains("AOSP", true) ||
                            n.contains("Nox", true) || n.contains("VBox", true) ||
                            n.contains("BlueStacks", true)
                        ) {
                            val s = param.thisObject as? android.hardware.Sensor
                            param.result = when (s?.type) {
                                1, 35 -> "BMI260 Accelerometer"
                                2, 14 -> "AK09918 Magnetometer"
                                4, 16 -> "BMI260 Gyroscope"
                                5 -> "STK33502 Ambient Light Sensor"
                                6 -> "LPS22HH Pressure Sensor"
                                8 -> "STK33502 Proximity Sensor"
                                18 -> "BMI260 Step Detector"
                                19 -> "BMI260 Step Counter"
                                else -> n.replace("Goldfish", "BMI260", ignoreCase = true)
                                    .replace("Ranchu", "BMI260", ignoreCase = true)
                                    .replace("QEMU", "QTI", ignoreCase = true)
                                    .replace("Virtual", "QTI", ignoreCase = true)
                                    .replace("Emulated", "Hardware", ignoreCase = true)
                                    .replace("AOSP", "QTI", ignoreCase = true)
                                    .trim()
                                    .ifBlank { "BMI260 Inertial Sensor" }
                            }
                        }
                    }
                })
            }
        } catch (_: Throwable) {}

        val hookedClassLoaders = java.util.Collections.synchronizedSet(HashSet<Int>())
        val hookDynamicAppClasses = fun(loader: ClassLoader?) {
            if (loader == null) return
            val loaderId = System.identityHashCode(loader)
            if (!hookedClassLoaders.add(loaderId)) return

            // Hook known VirtualApkCheckUtil / EasyProtectorLib / CheckVirtual classes if present in target or guest ClassLoader
            val virtualCheckClasses = arrayOf(
                "com.lahm.library.VirtualApkCheckUtil",
                "com.lahm.library.EasyProtectorLib",
                "com.lahm.library.EmulatorCheckUtil"
            )
            for (vcClsName in virtualCheckClasses) {
                try {
                    val vcCls = XposedHelpers.findClass(vcClsName, loader) ?: continue
                    for (mName in arrayOf(
                        "checkByPrivateFilePath",
                        "checkByOriginApkPackageName",
                        "checkByMultiApkPackageName",
                        "checkByHasSameUid",
                        "checkByCreateLocalServerSocket",
                        "checkIsRunningInVirtualApk",
                        "checkIsRoot",
                        "checkIsDebug",
                        "checkIsPortUsing",
                        "checkIsXposedExist",
                        "checkIsRunningInEmulator",
                        "readSysProperty"
                    )) {
                        hookAllMethodsCompat(vcCls, mName, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.result = false
                            }
                        })
                    }
                } catch (_: Throwable) {}
            }

            if (enableRootBypass) {
                for (rbClsName in arrayOf("com.scottyab.rootbeer.RootBeer", "com.kimchangyoun.rootbeerFresh.RootBeer")) {
                    try {
                        val rbCls = XposedHelpers.findClass(rbClsName, loader) ?: continue
                        for (mName in arrayOf(
                            "isRooted",
                            "isRootedWithoutBusyBoxCheck",
                            "isRootedWithBusyBoxCheck",
                            "detectRootManagementApps",
                            "detectPotentiallyDangerousApps",
                            "detectTestKeys",
                            "checkForBusyBoxBinary",
                            "checkForSuBinary",
                            "checkSuExists",
                            "checkForRWPaths",
                            "checkForDangerousProps",
                            "checkForRootNative",
                            "detectRootCloakingApps",
                            "checkForMagiskBinary",
                            "canLoadNativeLibrary"
                        )) {
                            hookAllMethodsCompat(rbCls, mName, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    param.result = false
                                }
                            })
                        }
                    } catch (_: Throwable) {}
                }
                for (rbnClsName in arrayOf("com.scottyab.rootbeer.RootBeerNative", "com.kimchangyoun.rootbeerFresh.RootBeerNative")) {
                    try {
                        val rbnCls = XposedHelpers.findClass(rbnClsName, loader) ?: continue
                        hookAllMethodsCompat(rbnCls, "checkForRoot", object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.result = 0
                            }
                        })
                        hookAllMethodsCompat(rbnCls, "wasNativeLibraryLoaded", object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.result = false
                            }
                        })
                    } catch (_: Throwable) {}
                }
            }

            // Hook Flutter MethodChannel root/jailbreak/emulator/developerMode/clone plugins in target or dynamic guest ClassLoader
            try {
                val mcCls = XposedHelpers.findClass("io.flutter.plugin.common.MethodChannel", loader)
                if (mcCls != null) {
                    val hookedHandlers = java.util.Collections.synchronizedSet(HashSet<String>())
                    val falseFlutterMethods = setOf(
                        "isrooted", "isdevicerooted", "jailbroken", "isjailbroken", "isroot",
                        "checkroot", "checkforroot", "isrootchecker", "isaccessgiven", "rootcheck",
                        "detectroot", "hasroot", "developermode", "isdevelopermode", "isdevmode",
                        "isdevelopmentmodeenable", "isadbenabled", "usbdebuggingcheck", "ismocklocation",
                        "canmocklocation", "isemulator", "checkemulator", "isvirtual", "isvirtualdevice",
                        "ishooked", "isxposed", "isonexternalstorage", "isnottrust",
                        "iscloned", "isclonedapp", "isappcloned", "isdualapp", "ismultispace",
                        "isparallelspace", "checkcloned", "checkvirtual", "isrunninginvirtual"
                    )
                    val trueFlutterMethods = setOf(
                        "isrealdevice", "isphysicaldevice", "issafedevice"
                    )
                    hookAllMethodsCompat(mcCls, "setMethodCallHandler", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val handler = param.args.firstOrNull() ?: return
                            val handlerCls = handler.javaClass
                            if (!hookedHandlers.add(handlerCls.name)) return
                            hookAllMethodsCompat(handlerCls, "onMethodCall", object : XC_MethodHook() {
                                override fun beforeHookedMethod(callParam: MethodHookParam) {
                                    try {
                                        val callObj = callParam.args.getOrNull(0) ?: return
                                        val resultObj = callParam.args.getOrNull(1) ?: return
                                        val mField = callObj.javaClass.getDeclaredField("method").apply { isAccessible = true }
                                        val mName = (mField.get(callObj) as? String)?.lowercase()?.trim() ?: return
                                        if (mName in falseFlutterMethods) {
                                            val successMethod = resultObj.javaClass.getMethod("success", Any::class.java).apply { isAccessible = true }
                                            successMethod.invoke(resultObj, java.lang.Boolean.FALSE)
                                            callParam.result = null
                                        } else if (mName in trueFlutterMethods) {
                                            val successMethod = resultObj.javaClass.getMethod("success", Any::class.java).apply { isAccessible = true }
                                            successMethod.invoke(resultObj, java.lang.Boolean.TRUE)
                                            callParam.result = null
                                        } else if (mName == "checkforissues") {
                                            val successMethod = resultObj.javaClass.getMethod("success", Any::class.java).apply { isAccessible = true }
                                            successMethod.invoke(resultObj, ArrayList<String>())
                                            callParam.result = null
                                        }
                                    } catch (_: Throwable) {}
                                }
                            })
                        }
                    })
                }
            } catch (_: Throwable) {}
        }

        // Apply immediately to initial classLoader AND hook Application.attach(Context) so guest apps loaded dynamically
        // inside Dual Space (com.clone.android.dual.space / VMDisplayActivity) also get their dynamic ClassLoader hooked!
        hookDynamicAppClasses(classLoader)
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Application::class.java,
                "attach",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args.firstOrNull() as? android.content.Context
                        val app = param.thisObject as? android.app.Application
                        hookDynamicAppClasses(ctx?.classLoader)
                        hookDynamicAppClasses(app?.javaClass?.classLoader)
                    }
                }
            )
        } catch (_: Throwable) {}

        // 11b. Universal BootClassLoader Map & JSONObject Device-Integrity Sanitizer:
        // Works even when Flutter plugins (device_info_plus, safe_device, flutter_jailbreak_detection) or native SDKs
        // are R8-obfuscated or loaded inside a virtual container's dynamic ClassLoader!
        try {
            val forceTrueKeys = setOf(
                "isPhysicalDevice", "isRealDevice", "isSafeDevice", "isGenuineDevice"
            )
            val forceFalseKeys = setOf(
                "isEmulator", "isVirtual", "isVirtualDevice", "isVirtualPhone",
                "isRooted", "isDeviceRooted", "isJailBroken", "isJailbroken",
                "isCloned", "isClonedApp", "isDualApp", "isHooked", "isXposed",
                "isMockLocation", "canMockLocation", "isDevelopmentModeEnable",
                "isDevMode", "isAdbEnabled", "isOnExternalStorage", "isRunningInVirtualApk"
            )
            XposedHelpers.findAndHookMethod(
                java.util.HashMap::class.java,
                "put",
                Any::class.java,
                Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val k = param.args.getOrNull(0) as? String ?: return
                        val v = param.args.getOrNull(1)
                        if (v is Boolean) {
                            if (k in forceTrueKeys && !v) {
                                param.args[1] = java.lang.Boolean.TRUE
                            } else if (k in forceFalseKeys && v) {
                                param.args[1] = java.lang.Boolean.FALSE
                            }
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "put",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val k = param.args.getOrNull(0) as? String ?: return
                        val v = param.args.getOrNull(1) as? Boolean ?: return
                        if (k in forceTrueKeys && !v) {
                            param.args[1] = true
                        } else if (k in forceFalseKeys && v) {
                            param.args[1] = false
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 12. Non-null Toast & Activity Exit Protection:
        // If an app attempts to display a "Rooted device detected" / "Emulator detected" / "Cloned environment detected" Toast and exit,
        // keep Toast.makeText non-null (so .show() never throws NullPointerException), suppress .show() on that Toast,
        // and block Activity.finish / Process.killProcess / System.exit triggered by the warning!
        try {
            val suppressedToasts = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>()))
            var blockExitUntilMs = 0L

            val isRootOrCloneToastText = fun(rawText: String?): Boolean {
                val textArg = rawText?.lowercase()?.trim() ?: return false
                if (textArg.isEmpty()) return false
                val isCloneOrEmulatorMsg = textArg.contains("cloned") || textArg.contains("clone environment") ||
                    textArg.contains("virtual environment") || textArg.contains("virtual device") ||
                    textArg.contains("virtual phone") || textArg.contains("emulator") ||
                    textArg.contains("simulator") || textArg.contains("dual space") ||
                    textArg.contains("parallel space") || textArg.contains("multiple accounts")
                val isRootMsg = textArg.contains("rooted") || textArg.contains("cannot run in this environment") ||
                    textArg.contains("jailbreak") || textArg.contains("jailbroken") ||
                    textArg.contains("root detected") || textArg.contains("unsafe environment")
                return isCloneOrEmulatorMsg || isRootMsg
            }

            hookAllMethodsCompat(android.widget.Toast::class.java, "makeText", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val ctxArg = param.args.firstOrNull() as? android.content.Context
                    val secondArg = param.args.getOrNull(1)
                    val resolvedText = when (secondArg) {
                        is CharSequence -> secondArg.toString()
                        is Int -> try { ctxArg?.getString(secondArg) } catch (_: Throwable) { null }
                        else -> secondArg?.toString()
                    }
                    if (isRootOrCloneToastText(resolvedText)) {
                        val toastObj = param.result
                        if (toastObj != null) {
                            suppressedToasts.add(toastObj)
                        }
                        blockExitUntilMs = System.currentTimeMillis() + 10000L
                    }
                }
            })

            hookAllMethodsCompat(android.widget.Toast::class.java, "setText", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val toastObj = param.thisObject ?: return
                    val firstArg = param.args.firstOrNull()
                    val resolvedText = when (firstArg) {
                        is CharSequence -> firstArg.toString()
                        else -> firstArg?.toString()
                    }
                    if (isRootOrCloneToastText(resolvedText)) {
                        suppressedToasts.add(toastObj)
                        blockExitUntilMs = System.currentTimeMillis() + 10000L
                        param.args[0] = ""
                    }
                }
            })

            XposedHelpers.findAndHookMethod(android.widget.Toast::class.java, "show", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val toastObj = param.thisObject ?: return
                    if (suppressedToasts.contains(toastObj)) {
                        param.result = null
                    }
                }
            })

            val blockFinishHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (System.currentTimeMillis() < blockExitUntilMs) {
                        param.result = null
                    }
                }
            }
            for (finishMethod in arrayOf("finish", "finishAffinity", "finishAndRemoveTask")) {
                hookAllMethodsCompat(android.app.Activity::class.java, finishMethod, blockFinishHook)
            }

            XposedHelpers.findAndHookMethod(
                android.os.Process::class.java,
                "killProcess",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pid = param.args.firstOrNull() as? Int ?: return
                        if (pid == android.os.Process.myPid() && System.currentTimeMillis() < blockExitUntilMs) {
                            param.result = null
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                System::class.java,
                "exit",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (System.currentTimeMillis() < blockExitUntilMs) {
                            param.result = null
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Runtime::class.java,
                "exit",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (System.currentTimeMillis() < blockExitUntilMs) {
                            param.result = null
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookGpuRenderer(classLoader: ClassLoader, cpuVals: CpuHardwareRandomValues) {
        val glClasses = arrayOf(
            "android.opengl.GLES10",
            "android.opengl.GLES20",
            "android.opengl.GLES30",
            "com.google.android.gles_jni.GLImpl"
        )
        for (clsName in glClasses) {
            try {
                val cls = XposedHelpers.findClass(clsName, classLoader) ?: continue
                XposedHelpers.findAndHookMethod(cls, "glGetString", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val name = param.args[0] as Int
                        when (name) {
                            7937 -> param.result = cpuVals.gpuRenderer // GL_RENDERER (0x1F01)
                            7936 -> param.result = cpuVals.gpuVendor   // GL_VENDOR (0x1F00)
                            7938 -> param.result = cpuVals.gpuVersion  // GL_VERSION (0x1F02)
                        }
                    }
                })
            } catch (_: Throwable) {}
        }
    }

    private fun getCountryCode(tz: String, location: String): String {
        val cleanTz = tz.uppercase()
        val cleanLoc = location.uppercase()
        return when {
            cleanTz.contains("DHAKA") || cleanLoc.contains("BANGLADESH") || cleanLoc.contains("DHAKA") || cleanLoc.endsWith(", BD") -> "BD"
            cleanTz.contains("JAKARTA") || cleanLoc.contains("INDONESIA") || cleanLoc.contains("JAKARTA") || cleanLoc.endsWith(", ID") -> "ID"
            cleanTz.contains("KOLKATA") || cleanLoc.contains("INDIA") || cleanLoc.contains("MUMBAI") || cleanLoc.endsWith(", IN") -> "IN"
            cleanTz.contains("TOKYO") || cleanLoc.contains("JAPAN") || cleanLoc.contains("TOKYO") || cleanLoc.endsWith(", JP") -> "JP"
            cleanTz.contains("LONDON") || cleanLoc.contains("UNITED KINGDOM") || cleanLoc.contains("LONDON") || cleanLoc.endsWith(", UK") || cleanLoc.endsWith(", GB") -> "GB"
            cleanTz.contains("TORONTO") || cleanTz.contains("VANCOUVER") || cleanLoc.contains("CANADA") || cleanLoc.contains("TORONTO") || cleanLoc.endsWith(", CA") -> "CA"
            cleanTz.contains("NEW_YORK") || cleanTz.contains("CHICAGO") || cleanTz.contains("LOS_ANGELES") || cleanTz.contains("DENVER") || cleanTz.contains("HONOLULU") || cleanLoc.contains("USA") || cleanLoc.contains("UNITED STATES") || cleanLoc.endsWith(", US") -> "US"
            cleanTz.contains("SAO_PAULO") || cleanLoc.contains("BRAZIL") || cleanLoc.contains("PAULO") || cleanLoc.endsWith(", BR") -> "BR"
            cleanTz.contains("CAIRO") || cleanLoc.contains("EGYPT") || cleanLoc.contains("CAIRO") || cleanLoc.endsWith(", EG") -> "EG"
            cleanTz.contains("AUCKLAND") || cleanLoc.contains("ZEALAND") || cleanLoc.contains("AUCKLAND") || cleanLoc.endsWith(", NZ") -> "NZ"
            cleanTz.contains("SEOUL") || cleanLoc.contains("KOREA") || cleanLoc.contains("SEOUL") || cleanLoc.endsWith(", KR") -> "KR"
            cleanTz.contains("BERLIN") || cleanLoc.contains("GERMANY") || cleanLoc.contains("BERLIN") || cleanLoc.endsWith(", DE") -> "DE"
            cleanTz.contains("PARIS") || cleanLoc.contains("FRANCE") || cleanLoc.contains("PARIS") || cleanLoc.endsWith(", FR") -> "FR"
            cleanTz.contains("MOSCOW") || cleanLoc.contains("RUSSIA") || cleanLoc.endsWith(", RU") -> "RU"
            cleanTz.contains("SINGAPORE") || cleanLoc.contains("SINGAPORE") || cleanLoc.endsWith(", SG") -> "SG"
            cleanTz.contains("SYDNEY") || cleanLoc.contains("AUSTRALIA") || cleanLoc.contains("SYDNEY") || cleanLoc.endsWith(", AU") -> "AU"
            cleanTz.contains("DUBAI") || cleanLoc.contains("EMIRATES") || cleanLoc.contains("DUBAI") || cleanLoc.endsWith(", AE") -> "AE"
            cleanTz.contains("RIYADH") || cleanLoc.contains("SAUDI") || cleanLoc.contains("RIYADH") || cleanLoc.endsWith(", SA") -> "SA"
            else -> "US"
        }
    }

    private fun hookLocale(classLoader: ClassLoader, countryCode: String) {
        if (countryCode.isBlank()) return
        try {
            val spoofedLocale = Locale("en", countryCode)

            XposedHelpers.findAndHookMethod(
                Locale::class.java,
                "getDefault",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = spoofedLocale
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Locale::class.java,
                "getDefault",
                Locale.Category::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        param.result = spoofedLocale
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookAdvertisingId(
        classLoader: ClassLoader,
        advId: String,
        widevineDrmId: String = "",
        androidId: String = "",
        targetPkg: String = ""
    ) {
        try {
            val infoClass = XposedHelpers.findClass("com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info", classLoader)
            if (infoClass != null) {
                XposedHelpers.findAndHookMethod(infoClass, "getId", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val liveAdv = readFreshSysProp("persist.sys.phantom.adv", advId)
                        if (liveAdv.isNotBlank()) param.result = liveAdv
                    }
                })
            }
        } catch (_: Throwable) {}

        try {
            val jetpackInfo = XposedHelpers.findClass("androidx.ads.identifier.AdvertisingIdInfo", classLoader)
            if (jetpackInfo != null) {
                XposedHelpers.findAndHookMethod(jetpackInfo, "getId", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val liveAdv = readFreshSysProp("persist.sys.phantom.adv", advId)
                        if (liveAdv.isNotBlank()) param.result = liveAdv
                    }
                })
            }
        } catch (_: Throwable) {}

        try {
            val appSetClass = XposedHelpers.findClass("com.google.android.gms.appset.AppSetIdInfo", classLoader)
            if (appSetClass != null) {
                XposedHelpers.findAndHookMethod(appSetClass, "getId", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val liveAppSet = readFreshSysProp(
                            "persist.sys.phantom.appset",
                            readFreshSysProp("persist.sys.phantom.adv", advId)
                        )
                        if (liveAppSet.isNotBlank()) param.result = liveAppSet
                    }
                })
            }
        } catch (_: Throwable) {}

        // Isolate AccountManager so cross-app/Google accounts never leak across resets,
        // while preserving the target app's own internal AccountAuthenticator accounts (e.g. com.twitter.android.auth.login)
        try {
            val amCls = XposedHelpers.findClass("android.accounts.AccountManager", classLoader)
            if (amCls != null) {
                val pkgCoreToken = targetPkg.split(".").getOrNull(1)?.lowercase() ?: ""
                for (mName in arrayOf("getAccounts", "getAccountsByType", "getAccountsByTypeForPackage")) {
                    hookAllMethodsCompat(amCls, mName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val orig = param.result as? Array<*> ?: return
                            val kept = orig.filterIsInstance<android.accounts.Account>().filter { acc ->
                                val t = acc.type?.lowercase() ?: ""
                                (targetPkg.isNotBlank() && t.contains(targetPkg.lowercase())) ||
                                    (pkgCoreToken.length >= 4 && pkgCoreToken != "android" && pkgCoreToken != "google" && t.contains(pkgCoreToken))
                            }.toTypedArray()
                            param.result = kept
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // Hook Widevine MediaDrm.getPropertyByteArray("deviceUniqueId") so hardware DRM ID is never leaked (used by Unity SystemInfo.deviceUniqueIdentifier)
        try {
            XposedHelpers.findAndHookMethod(
                android.media.MediaDrm::class.java,
                "getPropertyByteArray",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val propName = param.args.firstOrNull() as? String ?: return
                        if (propName.equals("deviceUniqueId", ignoreCase = true)) {
                            val liveDrm = readFreshSysProp(
                                "persist.sys.phantom.drm",
                                widevineDrmId.ifBlank {
                                    readFreshSysProp("persist.sys.phantom.aid", androidId.ifBlank { advId })
                                }
                            )
                            if (liveDrm.isNotBlank()) {
                                val drmBytes = java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(liveDrm.toByteArray(Charsets.UTF_8))
                                param.result = drmBytes.copyOf()
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // Note: Do NOT spoof com.google.android.gsf.gservices ("android_id" GSF ID) via ContentResolver.query,
        // because Google Play Services (com.google.android.gms) validates the app's GSF ID when issuing
        // FCM / Firebase Installations / Play Integrity / DroidGuard attestation tokens (required by X / Twitter).
    }

    private fun loadIdentity(pkg: String = ""): JSONObject? {
        isInternalFileAccess.set(true)
        try {
            var fileJson: JSONObject? = null
            // Method 1: Read per-package chowned config file & shared world-readable config files first
            val candidatePaths = mutableListOf<String>()
            if (pkg.isNotBlank()) {
                candidatePaths.add("/data/data/$pkg/.phantom_identity.json")
                candidatePaths.add("/data/user/0/$pkg/.phantom_identity.json")
                candidatePaths.add("/data/user/10/$pkg/.phantom_identity.json")
                candidatePaths.add("/data/user/999/$pkg/.phantom_identity.json")
                candidatePaths.add("/data/user_de/0/$pkg/.phantom_identity.json")
            }
            candidatePaths.addAll(
                listOf(
                    "/data/data/com.aistudio.ghostdevice.qxvz/files/phantom_identity.json",
                    "/data/user/0/com.aistudio.ghostdevice.qxvz/files/phantom_identity.json",
                    "/data/data/com.aistudio.phantomcore.qxvz/files/phantom_identity.json",
                    "/data/user/0/com.aistudio.phantomcore.qxvz/files/phantom_identity.json",
                    "/data/local/tmp/phantom_identity.json",
                    "/data/misc/phantom_identity.json",
                    "/data/system/phantom_identity.json",
                    "/data/adb/phantom_identity.json",
                    "/sdcard/phantom_identity.json",
                    "/sdcard/Download/phantom_identity.json"
                )
            )

            for (p in candidatePaths) {
                try {
                    val f = File(p)
                    if (f.exists()) {
                        val content = java.io.FileInputStream(f).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        if (content.isNotBlank()) {
                            val parsed = JSONObject(content)
                            if (fileJson == null || parsed.optLong("resetTimestamp", 0L) >= fileJson.optLong("resetTimestamp", 0L)) {
                                fileJson = parsed
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            // Method 2: Always also check ContentProvider (in-memory live identity from GhostDevice) and keep whichever has the newer resetTimestamp
            try {
                val helperClass = Class.forName("de.robv.android.xposed.AndroidAppHelper")
                var ctx = helperClass.getMethod("currentApplication").invoke(null) as? android.content.Context
                if (ctx == null) {
                    try {
                        val atClass = Class.forName("android.app.ActivityThread")
                        val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
                        if (currentAt != null) {
                            val sysCtx = atClass.getMethod("getSystemContext").invoke(currentAt) as? android.content.Context
                            ctx = if (sysCtx != null && pkg.isNotBlank()) {
                                try {
                                    sysCtx.createPackageContext(pkg, 0)
                                } catch (_: Throwable) {
                                    sysCtx
                                }
                            } else {
                                sysCtx
                            }
                        }
                    } catch (_: Throwable) {}
                }
                if (ctx != null) {
                    val uri = android.net.Uri.parse("content://com.aistudio.phantomcore.provider/identity")
                    var providerJson: JSONObject? = null
                    var providerBundle: android.os.Bundle? = null
                    try {
                        val bundle = ctx.contentResolver.call(uri, "get_identity", null, null)
                        providerBundle = bundle
                        val jsonStr = bundle?.getString("identity_json")
                        if (!jsonStr.isNullOrBlank()) {
                            providerJson = JSONObject(jsonStr)
                        }
                    } catch (_: Throwable) {}

                    if (providerJson == null) {
                        val cursor = ctx.contentResolver.query(uri, null, null, null, null)
                        if (cursor != null) {
                            if (cursor.moveToFirst()) {
                                val colIndex = cursor.getColumnIndex("identity_json")
                                if (colIndex >= 0) {
                                    val jsonStr = cursor.getString(colIndex)
                                    if (!jsonStr.isNullOrBlank()) {
                                        providerJson = JSONObject(jsonStr)
                                    }
                                }
                            }
                            cursor.close()
                        }
                    }
                    if (providerJson != null) {
                        if (fileJson == null || providerJson.optLong("resetTimestamp", 0L) >= fileJson.optLong("resetTimestamp", 0L)) {
                            fileJson = providerJson
                        }
                    }
                    if (providerBundle != null) {
                        val merged = fileJson ?: JSONObject()
                        fun putCsvArray(extraKey: String, jsonKey: String) {
                            val csv = providerBundle.getString(extraKey) ?: return
                            val arr = org.json.JSONArray()
                            csv.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "__NONE__" }.forEach { arr.put(it) }
                            merged.put(jsonKey, arr)
                        }
                        putCsvArray("root_bypass_packages", "rootBypassPackages")
                        putCsvArray("step_walk_packages", "stepWalkPackages")
                        putCsvArray("speed_hack_packages", "speedHackPackages")
                        putCsvArray("ad_bypass_packages", "adBypassPackages")
                        if (providerBundle.containsKey("target_packages")) {
                            val tCsv = providerBundle.getString("target_packages") ?: ""
                            if (tCsv.isNotEmpty() || providerBundle.getLong("toggle_ts", 0L) > 0L) {
                                val arr = org.json.JSONArray()
                                tCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "__NONE__" }.forEach { arr.put(it) }
                                merged.put("targetPackages", arr)
                            }
                        }
                        if (providerBundle.containsKey("global_spoof_enabled")) {
                            merged.put("globalSpoofEnabled", providerBundle.getBoolean("global_spoof_enabled", false))
                        }
                        merged.put("fromLiveProvider", true)
                        fileJson = merged
                    }
                }
            } catch (_: Throwable) {}

            // Method 3: Read in-memory SystemProperties (persist.sys.phantom.* / debug.phantom.* / log.tag.phantom.*)
            val propAid = readSysPropInternal("persist.sys.phantom.aid")
            val propMod = readSysPropInternal("persist.sys.phantom.mod")
            val propTs = readSysPropInternal("persist.sys.phantom.ts").toLongOrNull() ?: 0L
            val propRbPkgs = readSysPropInternal("persist.sys.phantom.rbpkgs")
            val propTpPkgs = readSysPropInternal("persist.sys.phantom.tpkgs")
            val propWkPkgs = readSysPropInternal("persist.sys.phantom.wkpkgs")
            if (propAid.isNotBlank() || propMod.isNotBlank() || propRbPkgs.isNotBlank() || propTpPkgs.isNotBlank() || propWkPkgs.isNotBlank()) {
                val base = fileJson ?: JSONObject()
                val baseTs = base.optLong("resetTimestamp", 0L)
                if (fileJson == null || (propTs > 0L && propTs >= baseTs)) {
                    if (propAid.isNotBlank()) base.put("androidId", propAid)
                    val propAdv = readSysPropInternal("persist.sys.phantom.adv")
                    if (propAdv.isNotBlank()) base.put("advertisingId", propAdv)
                    val propImei = readSysPropInternal("persist.sys.phantom.imei")
                    if (propImei.isNotBlank()) base.put("imei", propImei)
                    val propDrm = readSysPropInternal("persist.sys.phantom.drm")
                    if (propDrm.isNotBlank()) base.put("widevineDrmId", propDrm)
                    val propSer = readSysPropInternal("persist.sys.phantom.ser")
                    if (propSer.isNotBlank()) base.put("serialNumber", propSer)
                    val propMac = readSysPropInternal("persist.sys.phantom.mac")
                    if (propMac.isNotBlank()) base.put("macAddress", propMac)
                    val propBt = readSysPropInternal("persist.sys.phantom.bt")
                    if (propBt.isNotBlank()) base.put("bluetoothAddress", propBt)
                    val propIp = readSysPropInternal("persist.sys.phantom.ip")
                    if (propIp.isNotBlank()) base.put("ipAddress", propIp)
                    if (propMod.isNotBlank()) base.put("deviceModel", propMod)
                    val propMfr = readSysPropInternal("persist.sys.phantom.mfr")
                    if (propMfr.isNotBlank()) base.put("manufacturer", propMfr)
                    val propFp = readSysPropInternal("persist.sys.phantom.fp")
                    if (propFp.isNotBlank()) base.put("buildFingerprint", propFp)
                    val propBid = readSysPropInternal("persist.sys.phantom.bid")
                    if (propBid.isNotBlank()) base.put("buildNumber", propBid)
                    val propDid = readSysPropInternal("persist.sys.phantom.did")
                    if (propDid.isNotBlank()) base.put("deviceId", propDid)
                    val propKv = readSysPropInternal("persist.sys.phantom.kv")
                    if (propKv.isNotBlank()) base.put("kernelVersion", propKv)
                    val propSoc = readSysPropInternal("persist.sys.phantom.soc")
                    if (propSoc.isNotBlank()) base.put("socHardware", propSoc)
                    val propGpu = readSysPropInternal("persist.sys.phantom.gpu")
                    if (propGpu.isNotBlank()) base.put("gpuRenderer", propGpu)
                    val propBb = readSysPropInternal("persist.sys.phantom.bb")
                    if (propBb.isNotBlank()) base.put("basebandVersion", propBb)
                    val propBl = readSysPropInternal("persist.sys.phantom.bl")
                    if (propBl.isNotBlank()) base.put("bootloaderVersion", propBl)
                    val propOs = readSysPropInternal("persist.sys.phantom.os")
                    if (propOs.isNotBlank()) base.put("osVersion", propOs)
                    val propDisp = readSysPropInternal("persist.sys.phantom.disp")
                    if (propDisp.isNotBlank()) base.put("display", propDisp)
                    val propTz = readSysPropInternal("persist.sys.phantom.tz")
                    if (propTz.isNotBlank()) base.put("timezone", propTz)
                    val propLoc = readSysPropInternal("persist.sys.phantom.loc")
                    if (propLoc.isNotBlank()) base.put("locationName", propLoc)
                    val propLat = readSysPropInternal("persist.sys.phantom.lat").toDoubleOrNull()
                    if (propLat != null) base.put("latitude", propLat)
                    val propLng = readSysPropInternal("persist.sys.phantom.lng").toDoubleOrNull()
                    if (propLng != null) base.put("longitude", propLng)
                    val propCar = readSysPropInternal("persist.sys.phantom.car")
                    if (propCar.isNotBlank()) base.put("carrier", propCar)
                    val propImsi = readSysPropInternal("persist.sys.phantom.imsi")
                    if (propImsi.isNotBlank()) base.put("simImsi", propImsi)
                    val propIccid = readSysPropInternal("persist.sys.phantom.iccid")
                    if (propIccid.isNotBlank()) base.put("simIccid", propIccid)
                    val propReg = readSysPropInternal("persist.sys.phantom.reg")
                    if (propReg.isNotBlank()) base.put("region", propReg)
                    val propLtag = readSysPropInternal("persist.sys.phantom.ltag")
                    if (propLtag.isNotBlank()) base.put("localeTag", propLtag)
                    if (base.optString("userAgent", "").isBlank() && propMod.isNotBlank()) {
                        val osRelNum = propOs.removePrefix("Android ").substringBefore(" ").trim().ifBlank { "14" }
                        val bidStr = propBid.ifBlank { "UP1A.231005.007" }
                        base.put(
                            "userAgent",
                            "Mozilla/5.0 (Linux; Android $osRelNum; $propMod Build/$bidStr; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36"
                        )
                    }
                    if (propRbPkgs.isNotBlank() && base.optJSONArray("rootBypassPackages") == null) {
                        val arr = org.json.JSONArray()
                        propRbPkgs.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
                        base.put("rootBypassPackages", arr)
                    }
                    if (propTpPkgs.isNotBlank() && base.optJSONArray("targetPackages") == null) {
                        val arr = org.json.JSONArray()
                        propTpPkgs.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
                        base.put("targetPackages", arr)
                    }
                    if (propWkPkgs.isNotBlank() && base.optJSONArray("stepWalkPackages") == null) {
                        val arr = org.json.JSONArray()
                        propWkPkgs.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
                        base.put("stepWalkPackages", arr)
                    }
                    val propGlobal = readSysPropInternal("persist.sys.phantom.global")
                    if (propGlobal.isNotBlank()) base.put("globalSpoofEnabled", propGlobal == "1" || propGlobal.equals("true", ignoreCase = true))
                    if (propTs > 0L) base.put("resetTimestamp", propTs)
                    activeIdentityResetTs = base.optLong("resetTimestamp", 0L)
                    return base
                }
            }

            if (fileJson != null) {
                activeIdentityResetTs = fileJson.optLong("resetTimestamp", 0L)
                return fileJson
            }
        } finally {
            isInternalFileAccess.set(false)
        }

        return null
    }
    private fun hookNetworkInterface(classLoader: ClassLoader) {
        try {
            val niClass = XposedHelpers.findClass("java.net.NetworkInterface", classLoader)
            XposedHelpers.findAndHookMethod(niClass, "getNetworkInterfaces", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val enumeration = param.result as? java.util.Enumeration<*> ?: return
                    val list = java.util.Collections.list(enumeration)
                    val filtered = list.filter { item ->
                        val ni = item as? java.net.NetworkInterface ?: return@filter true
                        val name = ni.name?.lowercase() ?: ""
                        !name.contains("tun") && !name.contains("ppp") && !name.contains("tap") && !name.contains("wg")
                    }
                    val hasNonLoopback = filtered.any { item ->
                        val ni = item as? java.net.NetworkInterface
                        ni != null && !ni.name.equals("lo", ignoreCase = true)
                    }
                    param.result = java.util.Collections.enumeration(if (hasNonLoopback) filtered else list)
                }
            })
        } catch (_: Throwable) {}

        // Cloak virtual VM / TUN bridge TRANSPORT_VPN (4) in NetworkCapabilities so apps inside virtual spaces see a direct physical connection
        try {
            val ncClass = XposedHelpers.findClass("android.net.NetworkCapabilities", classLoader)
            if (ncClass != null) {
                XposedHelpers.findAndHookMethod(ncClass, "hasTransport", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val transport = param.args.firstOrNull() as? Int ?: return
                        if (transport == 4) { // NetworkCapabilities.TRANSPORT_VPN
                            param.result = false
                        }
                    }
                })
                XposedHelpers.findAndHookMethod(ncClass, "hasCapability", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val cap = param.args.firstOrNull() as? Int ?: return
                        if (cap == 15) { // NetworkCapabilities.NET_CAPABILITY_NOT_VPN
                            param.result = true
                        }
                    }
                })
            }
        } catch (_: Throwable) {}
    }

    private fun hookDeepHardwareUniqueness(
        classLoader: ClassLoader,
        identity: JSONObject,
        seed: Int,
        targetPkg: String
    ) {
        // 1. Cell Tower CID, LAC/TAC & PCI spoofing
        try {
            val cellProp = readSysPropInternal("persist.sys.phantom.cell").ifBlank {
                "${1048576 + (seed * 73) % 9000000},${1000 + (seed * 19) % 50000},${1 + seed % 500}"
            }
            val parts = cellProp.split(",")
            val fakeCid = parts.getOrNull(0)?.toIntOrNull() ?: (1048576 + seed % 500000)
            val fakeLac = parts.getOrNull(1)?.toIntOrNull() ?: (1000 + seed % 40000)
            val fakePci = parts.getOrNull(2)?.toIntOrNull() ?: (1 + seed % 500)

            val gsmLocCls = XposedHelpers.findClass("android.telephony.gsm.GsmCellLocation", classLoader)
            if (gsmLocCls != null) {
                XposedHelpers.findAndHookMethod(gsmLocCls, "getCid", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null) param.result = fakeCid
                    }
                })
                XposedHelpers.findAndHookMethod(gsmLocCls, "getLac", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null) param.result = fakeLac
                    }
                })
            }

            val lteCls = XposedHelpers.findClass("android.telephony.CellIdentityLte", classLoader)
            if (lteCls != null) {
                XposedHelpers.findAndHookMethod(lteCls, "getCi", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null) param.result = fakeCid
                    }
                })
                XposedHelpers.findAndHookMethod(lteCls, "getTac", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null) param.result = fakeLac
                    }
                })
                XposedHelpers.findAndHookMethod(lteCls, "getPci", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null) param.result = fakePci
                    }
                })
            }
        } catch (_: Throwable) {}

        // 2. eSIM EID (EuiccManager) & SubscriptionInfo Card ID
        try {
            val fakeEid = readSysPropInternal("persist.sys.phantom.eid").ifBlank {
                "890490320010018829300" + (10000000000L + (seed.toLong() * 31337L) % 89999999999L).toString()
            }
            val euiccCls = XposedHelpers.findClass("android.telephony.euicc.EuiccManager", classLoader)
            if (euiccCls != null) {
                XposedHelpers.findAndHookMethod(euiccCls, "getEid", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null) param.result = fakeEid
                    }
                })
            }
        } catch (_: Throwable) {}

        // 3. Battery & Thermal Telemetry (BatteryManager & ACTION_BATTERY_CHANGED Sticky Intent)
        try {
            val batProp = readSysPropInternal("persist.sys.phantom.bat")
            val batParts = batProp.split(",")
            val fakeBatPct = batParts.getOrNull(0)?.toIntOrNull() ?: (65 + (seed % 28))
            val fakeBatVoltage = 4120 + (seed % 190) // 4120..4310 mV (realistic Li-poly voltage)
            val fakeBatTemp = 302 + (seed % 38) // 30.2°C..34.0°C (realistic phone battery temp in tenths of °C)
            val bmCls = XposedHelpers.findClass("android.os.BatteryManager", classLoader)
            if (bmCls != null) {
                XposedHelpers.findAndHookMethod(bmCls, "getIntProperty", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        when (param.args[0] as? Int) {
                            4 -> param.result = fakeBatPct // BATTERY_PROPERTY_CAPACITY
                            1 -> param.result = fakeBatPct * 45000 // BATTERY_PROPERTY_CHARGE_COUNTER (uAh)
                            2 -> param.result = -(210000 + (seed % 180000)) // BATTERY_PROPERTY_CURRENT_NOW (uA)
                            3 -> param.result = -(240000 + (seed % 150000)) // BATTERY_PROPERTY_CURRENT_AVERAGE (uA)
                            6 -> param.result = 3 // BATTERY_STATUS_DISCHARGING
                        }
                    }
                })
                hookAllMethodsCompat(bmCls, "isCharging", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null) param.result = false
                    }
                })
            }

            // Sanitize Intent.ACTION_BATTERY_CHANGED sticky broadcast & Intent extra getters so emulator battery signatures
            // (e.g. voltage=0 or 5000mV, temperature=0 or 250, AC/USB constantly plugged, technology="Unknown")
            // always report a realistic unplugged physical Li-poly battery.
            val ctxImplCls = XposedHelpers.findClass("android.app.ContextImpl", classLoader)
            if (ctxImplCls != null) {
                hookAllMethodsCompat(ctxImplCls, "registerReceiver", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val intent = param.result as? android.content.Intent ?: return
                        if (intent.action == android.content.Intent.ACTION_BATTERY_CHANGED) {
                            intent.putExtra("level", fakeBatPct)
                            intent.putExtra("scale", 100)
                            intent.putExtra("status", 3) // BATTERY_STATUS_DISCHARGING
                            intent.putExtra("plugged", 0) // Unplugged (not AC/USB like an emulator)
                            intent.putExtra("health", 2) // BATTERY_HEALTH_GOOD
                            intent.putExtra("present", true)
                            intent.putExtra("voltage", fakeBatVoltage)
                            intent.putExtra("temperature", fakeBatTemp)
                            intent.putExtra("technology", "Li-poly")
                        }
                    }
                })
            }
            XposedHelpers.findAndHookMethod(
                android.content.Intent::class.java,
                "getIntExtra",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val intent = param.thisObject as? android.content.Intent ?: return
                        if (intent.action != android.content.Intent.ACTION_BATTERY_CHANGED) return
                        when (param.args.getOrNull(0) as? String) {
                            "level" -> param.result = fakeBatPct
                            "scale" -> param.result = 100
                            "status" -> param.result = 3
                            "plugged" -> param.result = 0
                            "health" -> param.result = 2
                            "voltage" -> param.result = fakeBatVoltage
                            "temperature" -> param.result = fakeBatTemp
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                android.content.Intent::class.java,
                "getStringExtra",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val intent = param.thisObject as? android.content.Intent ?: return
                        if (intent.action == android.content.Intent.ACTION_BATTERY_CHANGED &&
                            (param.args.getOrNull(0) as? String) == "technology"
                        ) {
                            param.result = "Li-poly"
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 4. Do NOT alter PackageInfo.firstInstallTime / lastUpdateTime on target apps so Play Integrity / GMS
        // package metadata verification in X / Twitter matches com.google.android.gms 100%.

        // 5. InputDevice Descriptor SHA-1, Emulator Input Name Masking & Storage Volume Free-Space Micro-Jitter
        try {
            val inputDesc = readSysPropInternal("persist.sys.phantom.webgl").ifBlank {
                java.security.MessageDigest.getInstance("SHA-1")
                    .digest(("input_${identity.optString("androidId")}_$seed").toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
            XposedHelpers.findAndHookMethod(android.view.InputDevice::class.java, "getDescriptor", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val dev = param.thisObject as? android.view.InputDevice
                    val id = dev?.id ?: 0
                    val mixed = inputDesc.dropLast(2) + "%02x".format((id and 0xFF))
                    param.result = mixed
                }
            })
            XposedHelpers.findAndHookMethod(android.view.InputDevice::class.java, "getName", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val name = (param.result as? String) ?: return
                    val lower = name.lowercase()
                    if (lower.contains("qwerty") || lower.contains("goldfish") || lower.contains("ranchu") ||
                        lower.contains("virtio") || lower.contains("vbox") || lower.contains("virtual") ||
                        lower.contains("emulator") || lower.contains("android_input") || lower.contains("windroy") ||
                        lower.contains("nox") || lower.contains("bluestacks")
                    ) {
                        param.result = "fts_ts"
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            val jitterBytes = ((seed % 512) + 64).toLong() * 4096L
            XposedHelpers.findAndHookMethod(android.os.StatFs::class.java, "getAvailableBytes", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val orig = param.result as? Long ?: return
                    if (orig > jitterBytes * 4) param.result = orig - jitterBytes
                }
            })
            XposedHelpers.findAndHookMethod(android.os.StatFs::class.java, "getFreeBytes", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val orig = param.result as? Long ?: return
                    if (orig > jitterBytes * 4) param.result = orig - jitterBytes
                }
            })
        } catch (_: Throwable) {}

        // 6. Audio DSP Buffer & Session Signature
        try {
            val audioProp = readSysPropInternal("persist.sys.phantom.audio")
            val framesBuf = audioProp.substringBefore(",").ifBlank { "240" }
            val amCls = XposedHelpers.findClass("android.media.AudioManager", classLoader)
            if (amCls != null) {
                XposedHelpers.findAndHookMethod(amCls, "getProperty", String::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val prop = param.args.firstOrNull() as? String ?: return
                        if (prop == "android.media.property.OUTPUT_FRAMES_PER_BUFFER") {
                            param.result = framesBuf
                        } else if (prop == "android.media.property.OUTPUT_SAMPLE_RATE") {
                            param.result = "48000"
                        }
                    }
                })
            }
        } catch (_: Throwable) {}

        // 7. Camera Presence Guarantee (Camera.getNumberOfCameras & safe Camera.getCameraInfo)
        // Note: Do NOT inject fake IDs into CameraManager.getCameraIdList() because getCameraCharacteristics("0")
        // will throw IllegalArgumentException("Unknown camera ID 0") and crash TopNod and Free Fire!
        try {
            val legacyCamCls = XposedHelpers.findClass("android.hardware.Camera", classLoader)
            val camInfoCls = XposedHelpers.findClass("android.hardware.Camera\$CameraInfo", classLoader)
            if (legacyCamCls != null) {
                XposedHelpers.findAndHookMethod(legacyCamCls, "getNumberOfCameras", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val count = param.result as? Int ?: 0
                        if (count < 2) {
                            param.result = 2
                        }
                    }
                })
                if (camInfoCls != null) {
                    XposedHelpers.findAndHookMethod(
                        legacyCamCls,
                        "getCameraInfo",
                        Int::class.javaPrimitiveType,
                        camInfoCls,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (param.throwable != null) {
                                    param.throwable = null
                                    val camId = param.args.getOrNull(0) as? Int ?: 0
                                    val infoObj = param.args.getOrNull(1)
                                    if (infoObj != null) {
                                        try {
                                            infoObj.javaClass.getField("facing").setInt(infoObj, if (camId == 1) 1 else 0)
                                            infoObj.javaClass.getField("orientation").setInt(infoObj, if (camId == 1) 270 else 90)
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                        }
                    )
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookAppSpeedController(
        classLoader: ClassLoader,
        pkg: String,
        initialSpeedEnabled: Boolean
    ) {
        val speedLock = Any()
        var lastAppliedMultiplier = 1.0f
        var realWallAnchor = 0L
        var virtualWallBaseDouble = 0.0
        var realNanoAnchor = 0L
        var virtualNanoBaseDouble = 0.0
        var realElapsedAnchor = 0L
        var virtualElapsedBaseDouble = 0.0
        var realElapsedNanosAnchor = 0L
        var virtualElapsedNanosBaseDouble = 0.0
        var realUptimeAnchor = 0L
        var virtualUptimeBaseDouble = 0.0

        var lastAppClockReadRealMs = 0L

        val inSpeedHook = object : ThreadLocal<Boolean>() {
            override fun initialValue(): Boolean = false
        }
        val inHandlerInternal = object : ThreadLocal<Boolean>() {
            override fun initialValue(): Boolean = false
        }
        val appExecutionDepth = object : ThreadLocal<Int>() {
            override fun initialValue(): Int = 0
        }
        val currentExecutingCallback = object : ThreadLocal<Any?>() {
            override fun initialValue(): Any? = null
        }

        // Track active delayed Messages (storing Pair<rawUnscaledDelayMs, appliedMultiplier>), repeating TimerTasks,
        // Choreographer FrameCallbacks, clock-reading callbacks, and WebViews so live speed changes apply immediately
        val trackedMessages = java.util.Collections.synchronizedMap(java.util.WeakHashMap<android.os.Message, Pair<Long, Float>>())
        val trackedTimerTasks = java.util.Collections.synchronizedMap(java.util.WeakHashMap<java.util.TimerTask, Long>())
        val trackedFrameCallbacks = java.util.Collections.synchronizedMap(java.util.WeakHashMap<android.view.Choreographer.FrameCallback, android.view.Choreographer.FrameCallback>())
        val ourFrameWrapperSet = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.view.Choreographer.FrameCallback, Boolean>())
        )
        val clockReadingCallbacks = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
        val trackedWebViews = java.util.Collections.synchronizedMap(java.util.WeakHashMap<android.webkit.WebView, Boolean>())

        fun isInternalPhantomThread(): Boolean {
            val tName = Thread.currentThread().name ?: ""
            return tName.startsWith("Phantom-")
        }

        // Reflection handle to XposedBridge.deoptimizeMethod(Member) to prevent ART AOT/JIT from inlining
        // System.currentTimeMillis(), System.nanoTime(), SystemClock.uptimeMillis(), and SystemClock.elapsedRealtime()
        val deoptimizeMethodRef: java.lang.reflect.Method? = try {
            XposedBridge::class.java.getDeclaredMethod("deoptimizeMethod", java.lang.reflect.Member::class.java).apply {
                isAccessible = true
            }
        } catch (_: Throwable) {
            null
        }

        fun deoptimizeMemberSafe(member: java.lang.reflect.Member?) {
            if (member == null) return
            try {
                deoptimizeMethodRef?.invoke(null, member)
            } catch (_: Throwable) {}
        }

        val appMethodExecutionHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                appExecutionDepth.set((appExecutionDepth.get() ?: 0) + 1)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val d = (appExecutionDepth.get() ?: 1) - 1
                appExecutionDepth.set(if (d < 0) 0 else d)
            }
        }

        fun isFrameworkOrLibraryClassName(name: String): Boolean {
            return name.startsWith("android.") ||
                name.startsWith("androidx.") ||
                name.startsWith("com.android.") ||
                name.startsWith("java.") ||
                name.startsWith("javax.") ||
                name.startsWith("kotlin.") ||
                name.startsWith("kotlinx.") ||
                name.startsWith("dalvik.") ||
                name.startsWith("libcore.") ||
                name.startsWith("sun.") ||
                name.startsWith("com.google.android.gms.") ||
                name.startsWith("com.google.android.play.") ||
                name.startsWith("com.google.firebase.") ||
                name.startsWith("de.robv.android.xposed.") ||
                name.startsWith("org.lsposed.") ||
                name.startsWith("com.example.") ||
                name.startsWith("com.aistudio.")
        }

        fun ensureAppClassDeoptimized(cls: Class<*>?) {
            if (cls == null) return
            val clsName = cls.name ?: return
            if (clsName.isEmpty() || cls.isInterface || cls.isArray || cls.isPrimitive || isFrameworkOrLibraryClassName(clsName)) {
                return
            }
            if (!deoptimizedAppClasses.add(clsName)) return
            try {
                for (ctor in cls.declaredConstructors) {
                    deoptimizeMemberSafe(ctor)
                }
            } catch (_: Throwable) {}
            try {
                for (m in cls.declaredMethods) {
                    deoptimizeMemberSafe(m)
                    val mods = m.modifiers
                    if (!java.lang.reflect.Modifier.isAbstract(mods) && !java.lang.reflect.Modifier.isNative(mods)) {
                        try {
                            XposedBridge.hookMethod(m, appMethodExecutionHook)
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
            // Also deoptimize enclosing/outer class (e.g. MainActivity when cls is MainActivity$1 or MainActivity$$ExternalSyntheticLambda0)
            try {
                ensureAppClassDeoptimized(cls.enclosingClass)
            } catch (_: Throwable) {}
            try {
                ensureAppClassDeoptimized(cls.declaringClass)
            } catch (_: Throwable) {}
            if (clsName.contains('$')) {
                val outerName = clsName.substringBefore('$')
                if (outerName.isNotEmpty() && outerName != clsName && !isFrameworkOrLibraryClassName(outerName)) {
                    try {
                        val outerCls = Class.forName(outerName, false, cls.classLoader)
                        ensureAppClassDeoptimized(outerCls)
                    } catch (_: Throwable) {}
                }
            }
            val superCls = cls.superclass
            if (superCls != null && !isFrameworkOrLibraryClassName(superCls.name ?: "")) {
                ensureAppClassDeoptimized(superCls)
            }
        }

        fun ensureAppObjectDeoptimized(obj: Any?, depth: Int = 0) {
            if (obj == null || depth > 2) return
            val cls = obj.javaClass
            val clsName = cls.name ?: return
            if (isFrameworkOrLibraryClassName(clsName)) return
            val isNewClass = !deoptimizedAppClasses.contains(clsName)
            ensureAppClassDeoptimized(cls)
            if (isNewClass && depth < 2) {
                try {
                    for (field in cls.declaredFields) {
                        val fType = field.type
                        if (!fType.isPrimitive && !fType.isArray) {
                            field.isAccessible = true
                            val fieldVal = field.get(obj)
                            if (fieldVal != null && fieldVal !== obj) {
                                ensureAppObjectDeoptimized(fieldVal, depth + 1)
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        // Deoptimize framework Chronometer & CountDownTimer once so boot.oat AOT inlining of SystemClock.elapsedRealtime() is bypassed
        try {
            for (fwClsName in arrayOf("android.widget.Chronometer", "android.os.CountDownTimer", "android.os.CountDownTimer\$1")) {
                try {
                    val fwCls = XposedHelpers.findClass(fwClsName, classLoader) ?: continue
                    for (ctor in fwCls.declaredConstructors) deoptimizeMemberSafe(ctor)
                    for (m in fwCls.declaredMethods) deoptimizeMemberSafe(m)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // Automatically deoptimize target app Activities as soon as they are created or resumed
        try {
            val activityHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val act = param.thisObject ?: return
                    ensureAppObjectDeoptimized(act, depth = 0)
                }
            }
            XposedHelpers.findAndHookMethod(android.app.Activity::class.java, "onCreate", android.os.Bundle::class.java, activityHook)
            XposedHelpers.findAndHookMethod(android.app.Activity::class.java, "onResume", activityHook)
        } catch (_: Throwable) {}

        fun advanceAllVirtualClocksLocked(activeMult: Float, forceAnchor: Boolean) {
            val isNonNormal = kotlin.math.abs(activeMult - 1.0f) > 0.005f
            if (forceAnchor || isNonNormal || realWallAnchor != 0L) {
                val realWall = System.currentTimeMillis()
                if (realWallAnchor == 0L || realWall < realWallAnchor) {
                    if (realWallAnchor == 0L) virtualWallBaseDouble = realWall.toDouble()
                    realWallAnchor = realWall
                } else {
                    val delta = realWall - realWallAnchor
                    virtualWallBaseDouble += delta.toDouble() * activeMult.toDouble()
                    realWallAnchor = realWall
                }
            }
            if (forceAnchor || isNonNormal || realNanoAnchor != 0L) {
                val realNano = System.nanoTime()
                if (realNanoAnchor == 0L || realNano < realNanoAnchor) {
                    if (realNanoAnchor == 0L) virtualNanoBaseDouble = realNano.toDouble()
                    realNanoAnchor = realNano
                } else {
                    val delta = realNano - realNanoAnchor
                    virtualNanoBaseDouble += delta.toDouble() * activeMult.toDouble()
                    realNanoAnchor = realNano
                }
            }
            if (forceAnchor || isNonNormal || realElapsedAnchor != 0L) {
                val realEl = android.os.SystemClock.elapsedRealtime()
                if (realElapsedAnchor == 0L || realEl < realElapsedAnchor) {
                    if (realElapsedAnchor == 0L) virtualElapsedBaseDouble = realEl.toDouble()
                    realElapsedAnchor = realEl
                } else {
                    val delta = realEl - realElapsedAnchor
                    virtualElapsedBaseDouble += delta.toDouble() * activeMult.toDouble()
                    realElapsedAnchor = realEl
                }
            }
            if (forceAnchor || isNonNormal || realElapsedNanosAnchor != 0L) {
                val realElNanos = android.os.SystemClock.elapsedRealtimeNanos()
                if (realElapsedNanosAnchor == 0L || realElNanos < realElapsedNanosAnchor) {
                    if (realElapsedNanosAnchor == 0L) virtualElapsedNanosBaseDouble = realElNanos.toDouble()
                    realElapsedNanosAnchor = realElNanos
                } else {
                    val delta = realElNanos - realElapsedNanosAnchor
                    virtualElapsedNanosBaseDouble += delta.toDouble() * activeMult.toDouble()
                    realElapsedNanosAnchor = realElNanos
                }
            }
            if (forceAnchor || isNonNormal || realUptimeAnchor != 0L) {
                val realUp = android.os.SystemClock.uptimeMillis()
                if (realUptimeAnchor == 0L || realUp < realUptimeAnchor) {
                    if (realUptimeAnchor == 0L) virtualUptimeBaseDouble = realUp.toDouble()
                    realUptimeAnchor = realUp
                } else {
                    val delta = realUp - realUptimeAnchor
                    virtualUptimeBaseDouble += delta.toDouble() * activeMult.toDouble()
                    realUptimeAnchor = realUp
                }
            }
        }

        fun computeEffectiveScaledDelay(rawDelay: Long, mult: Float, cbKey: Any?, realNowUptime: Long): Long {
            if (kotlin.math.abs(mult - 1.0f) <= 0.01f) return rawDelay
            if (mult > 1.0f) {
                return (rawDelay.toDouble() / mult.toDouble()).toLong().coerceIn(1L, 3_600_000L)
            }
            // Slow mode (mult < 1.0f, i.e. -2x to -1000x):
            // If this is a high-frequency (<= 100ms) UI redraw loop of a clock-reading stopwatch (like Simple Stopwatch),
            // keep the UI refresh rate smooth (20-30 FPS, <= 45ms) so the screen never freezes while the 5 virtual clocks
            // tick down in exact slow motion! For pure delay-based point counters, scale the full delay by 1/mult.
            val isClockBasedRefresh = rawDelay <= 100L && (
                (cbKey != null && clockReadingCallbacks[cbKey] == true) ||
                    (realNowUptime - lastAppClockReadRealMs) in 0L..2000L
                )
            return if (isClockBasedRefresh) {
                rawDelay.coerceIn(16L, 45L)
            } else {
                (rawDelay.toDouble() / mult.toDouble()).toLong().coerceIn(1L, 3_600_000L)
            }
        }

        fun rescheduleMainLooperMessages(oldMult: Float, newMult: Float) {
            if (kotlin.math.abs(newMult - oldMult) <= 0.005f) return
            try {
                val mainLooper = android.os.Looper.getMainLooper() ?: return
                val queue = mainLooper.queue ?: return
                val mMessagesField = android.os.MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }
                val nextField = android.os.Message::class.java.getDeclaredField("next").apply { isAccessible = true }
                val whenField = android.os.Message::class.java.getDeclaredField("when").apply { isAccessible = true }
                val realNow = android.os.SystemClock.uptimeMillis()
                synchronized(queue) {
                    var msg = mMessagesField.get(queue) as? android.os.Message
                    var anyModified = false
                    var hasSyncBarrier = false
                    val allNodes = ArrayList<android.os.Message>()
                    while (msg != null) {
                        allNodes.add(msg)
                        if (msg.target == null) {
                            hasSyncBarrier = true
                        } else {
                            val msgWhen = whenField.getLong(msg)
                            val remaining = msgWhen - realNow
                            val tracked = trackedMessages[msg]
                            if (tracked != null && remaining > 2L) {
                                val origRawDelay = tracked.first
                                val prevMsgMult = tracked.second
                                val cbKey = msg.callback ?: msg.target
                                val unscaledRemaining = (remaining.toDouble() * prevMsgMult.toDouble()).toLong().coerceIn(1L, origRawDelay.coerceAtLeast(1L))
                                val newRemaining = computeEffectiveScaledDelay(unscaledRemaining, newMult, cbKey, realNow)
                                whenField.setLong(msg, realNow + newRemaining)
                                trackedMessages[msg] = Pair(origRawDelay, newMult)
                                anyModified = true
                            } else if (newMult > oldMult * 1.05f && remaining > 40L) {
                                val ratio = (oldMult.toDouble() / newMult.toDouble()).coerceIn(0.000001, 1.0)
                                val newRemaining = (remaining.toDouble() * ratio).toLong().coerceAtLeast(1L)
                                whenField.setLong(msg, realNow + newRemaining)
                                anyModified = true
                            }
                        }
                        msg = nextField.get(msg) as? android.os.Message
                    }
                    // Do not re-link Message.next pointers as it risks severing the active queue or causing pointer cycles during concurrent Looper dispatch
                }
                android.os.Handler(mainLooper).post {}
            } catch (_: Throwable) {}
        }

        fun updateLiveTimerTasksAndAnimators(newMult: Float) {
            // 1. Dynamically rescale period of already-running java.util.TimerTask instances
            try {
                val periodField = java.util.TimerTask::class.java.getDeclaredField("period").apply { isAccessible = true }
                val realNow = android.os.SystemClock.uptimeMillis()
                synchronized(trackedTimerTasks) {
                    for ((task, origPeriod) in trackedTimerTasks.entries) {
                        if (task != null && origPeriod != 0L) {
                            val sign = if (origPeriod < 0L) -1L else 1L
                            val absOrig = kotlin.math.abs(origPeriod)
                            val scaledAbs = computeEffectiveScaledDelay(absOrig, newMult, task, realNow)
                            periodField.setLong(task, sign * scaledAbs)
                        }
                    }
                }
            } catch (_: Throwable) {}

            // 2. Dynamically update ValueAnimator global duration scale (1.0f / mult) on main thread
            try {
                val mainLooper = android.os.Looper.getMainLooper()
                if (mainLooper != null) {
                    android.os.Handler(mainLooper).post {
                        try {
                            val vaClass = android.animation.ValueAnimator::class.java
                            val durScale = if (kotlin.math.abs(newMult - 1.0f) <= 0.01f) 1.0f else (1.0f / newMult).coerceIn(0.001f, 1000.0f)
                            val setScaleMethod = vaClass.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
                            setScaleMethod.isAccessible = true
                            setScaleMethod.invoke(null, durScale)
                        } catch (_: Throwable) {}
                        // 3. Push updated multiplier to any active WebViews
                        try {
                            val wvs = synchronized(trackedWebViews) { trackedWebViews.keys.toList() }
                            for (wv in wvs) {
                                try {
                                    wv?.evaluateJavascript("window.__phantomSpeed = $newMult;", null)
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        fun resolveCurrentSpeedMultiplier(): Float {
            val snap = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled = false, initialSpeedEnabled = initialSpeedEnabled)
            val mult = if (snap.isSpeedEnabled) snap.appSpeedMultiplier.coerceIn(0.001f, 1000.0f) else 1.0f
            val prev = lastAppliedMultiplier
            if (kotlin.math.abs(mult - prev) > 0.005f) {
                val prevHookGuard = inSpeedHook.get() ?: false
                inSpeedHook.set(true)
                try {
                    var old = 1.0f
                    var changed = false
                    synchronized(speedLock) {
                        if (kotlin.math.abs(mult - lastAppliedMultiplier) > 0.005f) {
                            old = lastAppliedMultiplier
                            advanceAllVirtualClocksLocked(old, forceAnchor = true)
                            lastAppliedMultiplier = mult
                            changed = true
                        }
                    }
                    if (changed) {
                        rescheduleMainLooperMessages(old, mult)
                        updateLiveTimerTasksAndAnimators(mult)
                    }
                } finally {
                    inSpeedHook.set(prevHookGuard)
                }
            }
            return mult
        }

        fun markClockReadByCurrentCallback() {
            val prevGuard = inSpeedHook.get() ?: false
            inSpeedHook.set(true)
            try {
                lastAppClockReadRealMs = android.os.SystemClock.uptimeMillis()
            } catch (_: Throwable) {
            } finally {
                inSpeedHook.set(prevGuard)
            }
            val cb = currentExecutingCallback.get()
            if (cb != null) {
                clockReadingCallbacks[cb] = true
            }
        }

        // Start background telemetry poller immediately and register live speed update listener
        // so turning the rotary dial in the Floating Controller HUD rescales timers within 50ms!
        onLiveSpeedMultiplierUpdated = { _ ->
            resolveCurrentSpeedMultiplier()
        }
        startBackgroundWalkPollerIfNeeded(0.0, 0.0, pkg, initialStepEnabled = false, initialSpeedEnabled = initialSpeedEnabled)

        // 1. Handler.dispatchMessage, Handler.sendMessageDelayed & Handler.sendMessageAtTime scaling:
        // Scales ALL Handler.postDelayed, sendMessageDelayed, sendEmptyMessageDelayed, View.postDelayed,
        // CountDownTimer, Chronometer, and Kotlin Coroutines delay() on Dispatchers.Main, while deoptimizing callbacks!
        try {
            val handlerClass = XposedHelpers.findClass("android.os.Handler", classLoader)
            if (handlerClass != null) {
                XposedHelpers.findAndHookMethod(
                    handlerClass,
                    "dispatchMessage",
                    android.os.Message::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val msg = param.args.getOrNull(0) as? android.os.Message
                            val cb = msg?.callback
                            val target = param.thisObject
                            if (cb != null) ensureAppObjectDeoptimized(cb, depth = 0)
                            if (target != null) ensureAppObjectDeoptimized(target, depth = 0)
                            currentExecutingCallback.set(cb ?: target)
                            appExecutionDepth.set((appExecutionDepth.get() ?: 0) + 1)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val d = (appExecutionDepth.get() ?: 1) - 1
                            appExecutionDepth.set(if (d < 0) 0 else d)
                            if (d <= 0) {
                                currentExecutingCallback.set(null)
                            }
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    handlerClass,
                    "sendMessageDelayed",
                    android.os.Message::class.java,
                    Long::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            inHandlerInternal.set(true)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            inHandlerInternal.set(false)
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    handlerClass,
                    "sendMessageAtTime",
                    android.os.Message::class.java,
                    Long::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                            val msg = param.args.getOrNull(0) as? android.os.Message
                            val targetUptime = param.args.getOrNull(1) as? Long ?: return
                            val cb = msg?.callback
                            val target = param.thisObject
                            if (cb != null) ensureAppObjectDeoptimized(cb, depth = 0)
                            if (target != null) ensureAppObjectDeoptimized(target, depth = 0)

                            inSpeedHook.set(true)
                            try {
                                val mult = resolveCurrentSpeedMultiplier()
                                val realNow = android.os.SystemClock.uptimeMillis()
                                val virtualUptimeNow = synchronized(speedLock) {
                                    if (realUptimeAnchor == 0L) realNow else virtualUptimeBaseDouble.toLong()
                                }
                                // Handle callers that computed targetUptime from virtual SystemClock.uptimeMillis() directly (e.g. postAtTime)
                                val rawDelay = if (realUptimeAnchor != 0L &&
                                    kotlin.math.abs(targetUptime - realNow) > 4_000L &&
                                    targetUptime >= (virtualUptimeNow - 50L) &&
                                    (targetUptime - virtualUptimeNow) <= 600_000L
                                ) {
                                    targetUptime - virtualUptimeNow
                                } else {
                                    targetUptime - realNow
                                }
                                // Only scale delayed messages (> 2ms); keep immediate UI/input posts (<= 2ms) instant!
                                if (rawDelay > 2L) {
                                    val cbKey = cb ?: target
                                    if (msg != null && rawDelay <= 600_000L) {
                                        trackedMessages[msg] = Pair(rawDelay, mult)
                                    }
                                    if (kotlin.math.abs(mult - 1.0f) > 0.01f) {
                                        val scaledDelay = computeEffectiveScaledDelay(rawDelay, mult, cbKey, realNow)
                                        param.args[1] = realNow + scaledDelay
                                    }
                                }
                            } finally {
                                inSpeedHook.set(false)
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {}

        // 2a. Monotonic System.currentTimeMillis() & System.nanoTime() scaling:
        // Essential for stopwatches (including Simple Stopwatch), delta-time counters, Kotlin TimeSource.Monotonic, and game loops!
        try {
            XposedHelpers.findAndHookMethod(System::class.java, "currentTimeMillis", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                    if ((appExecutionDepth.get() ?: 0) > 0) markClockReadByCurrentCallback()
                    val snap = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled = false, initialSpeedEnabled = initialSpeedEnabled)
                    if (!snap.isSpeedEnabled && realWallAnchor == 0L) return
                    val realNow = param.result as? Long ?: return
                    inSpeedHook.set(true)
                    try {
                        val mult = resolveCurrentSpeedMultiplier()
                        synchronized(speedLock) {
                            if (realWallAnchor == 0L || realNow < realWallAnchor) {
                                if (realWallAnchor == 0L) virtualWallBaseDouble = realNow.toDouble()
                                realWallAnchor = realNow
                            } else {
                                val delta = realNow - realWallAnchor
                                virtualWallBaseDouble += delta.toDouble() * mult.toDouble()
                                realWallAnchor = realNow
                            }
                            val virtualWallLong = virtualWallBaseDouble.toLong()
                            if (kotlin.math.abs(mult - 1.0f) > 0.01f || virtualWallLong != realNow) {
                                param.result = virtualWallLong
                            }
                        }
                    } finally {
                        inSpeedHook.set(false)
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(System::class.java, "nanoTime", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                    if ((appExecutionDepth.get() ?: 0) > 0) markClockReadByCurrentCallback()
                    val snap = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled = false, initialSpeedEnabled = initialSpeedEnabled)
                    if (!snap.isSpeedEnabled && realNanoAnchor == 0L) return
                    val realNow = param.result as? Long ?: return
                    inSpeedHook.set(true)
                    try {
                        val mult = resolveCurrentSpeedMultiplier()
                        synchronized(speedLock) {
                            if (realNanoAnchor == 0L || realNow < realNanoAnchor) {
                                if (realNanoAnchor == 0L) virtualNanoBaseDouble = realNow.toDouble()
                                realNanoAnchor = realNow
                            } else {
                                val delta = realNow - realNanoAnchor
                                virtualNanoBaseDouble += delta.toDouble() * mult.toDouble()
                                realNanoAnchor = realNow
                            }
                            val virtualNanoLong = virtualNanoBaseDouble.toLong()
                            if (kotlin.math.abs(mult - 1.0f) > 0.01f || virtualNanoLong != realNow) {
                                param.result = virtualNanoLong
                            }
                        }
                    } finally {
                        inSpeedHook.set(false)
                    }
                }
            })
        } catch (_: Throwable) {}

        // 2b. Monotonic SystemClock scaling (elapsedRealtime, elapsedRealtimeNanos, and app-level uptimeMillis)
        // Used by CountDownTimer, Chronometer, stopwatches, and delta-time counters
        try {
            val sysClockClass = XposedHelpers.findClass("android.os.SystemClock", classLoader)
            if (sysClockClass != null) {
                XposedHelpers.findAndHookMethod(sysClockClass, "elapsedRealtime", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                        if ((appExecutionDepth.get() ?: 0) > 0) markClockReadByCurrentCallback()
                        val snap = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled = false, initialSpeedEnabled = initialSpeedEnabled)
                        if (!snap.isSpeedEnabled && realElapsedAnchor == 0L) return
                        val realNow = param.result as? Long ?: return
                        inSpeedHook.set(true)
                        try {
                            val mult = resolveCurrentSpeedMultiplier()
                            synchronized(speedLock) {
                                if (realElapsedAnchor == 0L || realNow < realElapsedAnchor) {
                                    if (realElapsedAnchor == 0L) virtualElapsedBaseDouble = realNow.toDouble()
                                    realElapsedAnchor = realNow
                                } else {
                                    val delta = realNow - realElapsedAnchor
                                    virtualElapsedBaseDouble += delta.toDouble() * mult.toDouble()
                                    realElapsedAnchor = realNow
                                }
                                val virtualElapsedLong = virtualElapsedBaseDouble.toLong()
                                if (kotlin.math.abs(mult - 1.0f) > 0.01f || virtualElapsedLong != realNow) {
                                    param.result = virtualElapsedLong
                                }
                            }
                        } finally {
                            inSpeedHook.set(false)
                        }
                    }
                })

                XposedHelpers.findAndHookMethod(sysClockClass, "elapsedRealtimeNanos", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                        if ((appExecutionDepth.get() ?: 0) > 0) markClockReadByCurrentCallback()
                        val snap = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled = false, initialSpeedEnabled = initialSpeedEnabled)
                        if (!snap.isSpeedEnabled && realElapsedNanosAnchor == 0L) return
                        val realNow = param.result as? Long ?: return
                        inSpeedHook.set(true)
                        try {
                            val mult = resolveCurrentSpeedMultiplier()
                            synchronized(speedLock) {
                                if (realElapsedNanosAnchor == 0L || realNow < realElapsedNanosAnchor) {
                                    if (realElapsedNanosAnchor == 0L) virtualElapsedNanosBaseDouble = realNow.toDouble()
                                    realElapsedNanosAnchor = realNow
                                } else {
                                    val delta = realNow - realElapsedNanosAnchor
                                    virtualElapsedNanosBaseDouble += delta.toDouble() * mult.toDouble()
                                    realElapsedNanosAnchor = realNow
                                }
                                val virtualElapsedNanosLong = virtualElapsedNanosBaseDouble.toLong()
                                if (kotlin.math.abs(mult - 1.0f) > 0.01f || virtualElapsedNanosLong != realNow) {
                                    param.result = virtualElapsedNanosLong
                                }
                            }
                        } finally {
                            inSpeedHook.set(false)
                        }
                    }
                })

                // Scale SystemClock.uptimeMillis() ONLY when called from target app callbacks (outside Handler.sendMessageDelayed & MessageQueue)
                XposedHelpers.findAndHookMethod(sysClockClass, "uptimeMillis", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (inSpeedHook.get() == true || inHandlerInternal.get() == true || isInternalPhantomThread()) return
                        if ((appExecutionDepth.get() ?: 0) <= 0) return
                        markClockReadByCurrentCallback()
                        val snap = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled = false, initialSpeedEnabled = initialSpeedEnabled)
                        if (!snap.isSpeedEnabled && realUptimeAnchor == 0L) return
                        val realNow = param.result as? Long ?: return
                        inSpeedHook.set(true)
                        try {
                            val mult = resolveCurrentSpeedMultiplier()
                            synchronized(speedLock) {
                                if (realUptimeAnchor == 0L || realNow < realUptimeAnchor) {
                                    if (realUptimeAnchor == 0L) virtualUptimeBaseDouble = realNow.toDouble()
                                    realUptimeAnchor = realNow
                                } else {
                                    val delta = realNow - realUptimeAnchor
                                    virtualUptimeBaseDouble += delta.toDouble() * mult.toDouble()
                                    realUptimeAnchor = realNow
                                }
                                val virtualUptimeLong = virtualUptimeBaseDouble.toLong()
                                if (kotlin.math.abs(mult - 1.0f) > 0.01f || virtualUptimeLong != realNow) {
                                    param.result = virtualUptimeLong
                                }
                            }
                        } finally {
                            inSpeedHook.set(false)
                        }
                    }
                })
            }
        } catch (_: Throwable) {}

        // 2c. Choreographer FrameCallback & getFrameTime/getFrameTimeNanos scaling for 60fps VSYNC stopwatches, Compose frame clocks, and animations
        try {
            val choreoClass = XposedHelpers.findClass("android.view.Choreographer", classLoader)
            if (choreoClass != null) {
                fun wrapFrameCallbackIfNeeded(orig: android.view.Choreographer.FrameCallback): android.view.Choreographer.FrameCallback {
                    // Never double-wrap our own wrapper (postFrameCallback delegates to postFrameCallbackDelayed in Android!)
                    if (ourFrameWrapperSet.contains(orig)) return orig
                    val clsName = orig.javaClass.name
                    if (clsName.startsWith("android.view.ViewRootImpl") || clsName.startsWith("com.example.")) {
                        return orig
                    }
                    ensureAppObjectDeoptimized(orig, depth = 0)
                    synchronized(trackedFrameCallbacks) {
                        val existing = trackedFrameCallbacks[orig]
                        if (existing != null) return existing
                        val wrapper = android.view.Choreographer.FrameCallback { realFrameTimeNanos ->
                            val prevCb = currentExecutingCallback.get()
                            currentExecutingCallback.set(orig)
                            appExecutionDepth.set((appExecutionDepth.get() ?: 0) + 1)
                            try {
                                val snap = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled = false, initialSpeedEnabled = initialSpeedEnabled)
                                if (!snap.isSpeedEnabled && realNanoAnchor == 0L) {
                                    orig.doFrame(realFrameTimeNanos)
                                } else {
                                    var virtualNanos = realFrameTimeNanos
                                    val prevGuard = inSpeedHook.get() ?: false
                                    inSpeedHook.set(true)
                                    try {
                                        val mult = resolveCurrentSpeedMultiplier()
                                        synchronized(speedLock) {
                                            val realNano = System.nanoTime()
                                            if (realNanoAnchor == 0L || realNano < realNanoAnchor) {
                                                if (realNanoAnchor == 0L) virtualNanoBaseDouble = realNano.toDouble()
                                                realNanoAnchor = realNano
                                            } else {
                                                val delta = realNano - realNanoAnchor
                                                virtualNanoBaseDouble += delta.toDouble() * mult.toDouble()
                                                realNanoAnchor = realNano
                                            }
                                            virtualNanos = virtualNanoBaseDouble.toLong()
                                        }
                                    } finally {
                                        inSpeedHook.set(prevGuard)
                                    }
                                    orig.doFrame(virtualNanos)
                                }
                            } finally {
                                val d = (appExecutionDepth.get() ?: 1) - 1
                                appExecutionDepth.set(if (d < 0) 0 else d)
                                currentExecutingCallback.set(prevCb)
                            }
                        }
                        ourFrameWrapperSet.add(wrapper)
                        trackedFrameCallbacks[orig] = wrapper
                        return wrapper
                    }
                }

                XposedHelpers.findAndHookMethod(
                    choreoClass,
                    "postFrameCallback",
                    android.view.Choreographer.FrameCallback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val cb = param.args.getOrNull(0) as? android.view.Choreographer.FrameCallback ?: return
                            param.args[0] = wrapFrameCallbackIfNeeded(cb)
                        }
                    }
                )
                XposedHelpers.findAndHookMethod(
                    choreoClass,
                    "postFrameCallbackDelayed",
                    android.view.Choreographer.FrameCallback::class.java,
                    Long::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val cb = param.args.getOrNull(0) as? android.view.Choreographer.FrameCallback ?: return
                            param.args[0] = wrapFrameCallbackIfNeeded(cb)
                            val delay = param.args.getOrNull(1) as? Long ?: 0L
                            if (delay > 0L && inSpeedHook.get() != true) {
                                inSpeedHook.set(true)
                                try {
                                    val mult = resolveCurrentSpeedMultiplier()
                                    if (kotlin.math.abs(mult - 1.0f) > 0.01f) {
                                        val realNow = android.os.SystemClock.uptimeMillis()
                                        param.args[1] = computeEffectiveScaledDelay(delay, mult, cb, realNow)
                                    }
                                } finally {
                                    inSpeedHook.set(false)
                                }
                            }
                        }
                    }
                )
                XposedHelpers.findAndHookMethod(
                    choreoClass,
                    "removeFrameCallback",
                    android.view.Choreographer.FrameCallback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val cb = param.args.getOrNull(0) as? android.view.Choreographer.FrameCallback ?: return
                            val wrapped = synchronized(trackedFrameCallbacks) { trackedFrameCallbacks[cb] }
                            if (wrapped != null) {
                                param.args[0] = wrapped
                            }
                        }
                    }
                )
                try {
                    XposedHelpers.findAndHookMethod(choreoClass, "getFrameTimeNanos", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                            val snap = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled = false, initialSpeedEnabled = initialSpeedEnabled)
                            if (!snap.isSpeedEnabled && realNanoAnchor == 0L) return
                            inSpeedHook.set(true)
                            try {
                                val mult = resolveCurrentSpeedMultiplier()
                                synchronized(speedLock) {
                                    val realNano = System.nanoTime()
                                    if (realNanoAnchor == 0L || realNano < realNanoAnchor) {
                                        if (realNanoAnchor == 0L) virtualNanoBaseDouble = realNano.toDouble()
                                        realNanoAnchor = realNano
                                    } else {
                                        val delta = realNano - realNanoAnchor
                                        virtualNanoBaseDouble += delta.toDouble() * mult.toDouble()
                                        realNanoAnchor = realNano
                                    }
                                    param.result = virtualNanoBaseDouble.toLong()
                                }
                            } finally {
                                inSpeedHook.set(false)
                            }
                        }
                    })
                    XposedHelpers.findAndHookMethod(choreoClass, "getFrameTime", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                            val snap = readLiveWalkState(0.0, 0.0, pkg, initialStepEnabled = false, initialSpeedEnabled = initialSpeedEnabled)
                            if (!snap.isSpeedEnabled && realUptimeAnchor == 0L) return
                            inSpeedHook.set(true)
                            try {
                                val mult = resolveCurrentSpeedMultiplier()
                                synchronized(speedLock) {
                                    val realUp = android.os.SystemClock.uptimeMillis()
                                    if (realUptimeAnchor == 0L || realUp < realUptimeAnchor) {
                                        if (realUptimeAnchor == 0L) virtualUptimeBaseDouble = realUp.toDouble()
                                        realUptimeAnchor = realUp
                                    } else {
                                        val delta = realUp - realUptimeAnchor
                                        virtualUptimeBaseDouble += delta.toDouble() * mult.toDouble()
                                        realUptimeAnchor = realUp
                                    }
                                    param.result = virtualUptimeBaseDouble.toLong()
                                }
                            } finally {
                                inSpeedHook.set(false)
                            }
                        }
                    })
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 3. Thread.sleep(long), Thread.sleep(long, int) & LockSupport.parkNanos scaling for background thread loops & Coroutines DefaultExecutor
        try {
            val sleepHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                    val origMillis = param.args.getOrNull(0) as? Long ?: return
                    if (origMillis <= 0L) return
                    inSpeedHook.set(true)
                    try {
                        val mult = resolveCurrentSpeedMultiplier()
                        if (mult > 1.01f) {
                            val scaled = (origMillis.toDouble() / mult.toDouble()).toLong().coerceAtLeast(1L)
                            param.args[0] = scaled
                        } else if (mult < 0.99f) {
                            // Responsive chunked sleep in Slow mode so switching back to Fast wakes the thread within 80ms
                            var virtualSlept = 0.0
                            val targetVirtual = origMillis.toDouble()
                            while (virtualSlept < targetVirtual) {
                                val curMult = resolveCurrentSpeedMultiplier().toDouble()
                                val remainingVirtual = (targetVirtual - virtualSlept).coerceAtLeast(0.0)
                                val realWaitNeeded = (remainingVirtual / curMult).toLong()
                                if (realWaitNeeded <= 90L) {
                                    param.args[0] = realWaitNeeded.coerceAtLeast(1L)
                                    return
                                }
                                Thread.sleep(70L)
                                virtualSlept += 70.0 * curMult
                            }
                            param.args[0] = 0L
                        }
                    } finally {
                        inSpeedHook.set(false)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(Thread::class.java, "sleep", Long::class.javaPrimitiveType, sleepHook)
            XposedHelpers.findAndHookMethod(Thread::class.java, "sleep", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, sleepHook)
        } catch (_: Throwable) {}

        try {
            val lockSupportClass = java.util.concurrent.locks.LockSupport::class.java
            val parkNanosHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                    val nanoIdx = if (param.args.size == 1) 0 else 1
                    val origNanos = param.args.getOrNull(nanoIdx) as? Long ?: return
                    // Only scale timed parkNanos (> 1ms and < 1 hour, e.g. kotlinx.coroutines.DefaultExecutor)
                    if (origNanos !in 1_000_000L..3_600_000_000_000L) return
                    inSpeedHook.set(true)
                    try {
                        val mult = resolveCurrentSpeedMultiplier()
                        if (mult > 1.01f) {
                            param.args[nanoIdx] = (origNanos.toDouble() / mult.toDouble()).toLong().coerceAtLeast(500_000L)
                        }
                    } finally {
                        inSpeedHook.set(false)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(lockSupportClass, "parkNanos", Long::class.javaPrimitiveType, parkNanosHook)
            XposedHelpers.findAndHookMethod(lockSupportClass, "parkNanos", Any::class.java, Long::class.javaPrimitiveType, parkNanosHook)
        } catch (_: Throwable) {}

        // 4. java.util.Timer (schedule & scheduleAtFixedRate) scaling + live TimerTask period tracking & deoptimization
        try {
            val timerClass = java.util.Timer::class.java
            val timerHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                    val task = param.args.getOrNull(0) as? java.util.TimerTask ?: return
                    ensureAppObjectDeoptimized(task, depth = 0)
                    inSpeedHook.set(true)
                    try {
                        val mult = resolveCurrentSpeedMultiplier()
                        val realNow = android.os.SystemClock.uptimeMillis()
                        if (param.args.size == 2 && param.args[1] is Long) {
                            val origDelay = param.args[1] as Long
                            if (origDelay > 0L && kotlin.math.abs(mult - 1.0f) > 0.01f) {
                                param.args[1] = computeEffectiveScaledDelay(origDelay, mult, task, realNow)
                            }
                        } else if (param.args.size == 3 && param.args[1] is Long && param.args[2] is Long) {
                            val origDelay = param.args[1] as Long
                            val origPeriod = param.args[2] as Long
                            if (origPeriod > 0L) {
                                val signedOrig = if (param.method.name == "scheduleAtFixedRate") origPeriod else -origPeriod
                                trackedTimerTasks[task] = signedOrig
                            }
                            if (kotlin.math.abs(mult - 1.0f) > 0.01f) {
                                if (origDelay > 0L) {
                                    param.args[1] = computeEffectiveScaledDelay(origDelay, mult, task, realNow)
                                }
                                if (origPeriod > 0L) {
                                    param.args[2] = computeEffectiveScaledDelay(origPeriod, mult, task, realNow)
                                }
                            }
                        }
                    } finally {
                        inSpeedHook.set(false)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(timerClass, "schedule", java.util.TimerTask::class.java, Long::class.javaPrimitiveType, timerHook)
            XposedHelpers.findAndHookMethod(timerClass, "schedule", java.util.TimerTask::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, timerHook)
            XposedHelpers.findAndHookMethod(timerClass, "scheduleAtFixedRate", java.util.TimerTask::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, timerHook)
        } catch (_: Throwable) {}

        // 5. ScheduledThreadPoolExecutor (schedule, scheduleAtFixedRate, scheduleWithFixedDelay) scaling & deoptimization
        try {
            val stpeClass = java.util.concurrent.ScheduledThreadPoolExecutor::class.java
            val stpeSingleHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                    val taskObj = param.args.getOrNull(0)
                    if (taskObj != null) ensureAppObjectDeoptimized(taskObj, depth = 0)
                    val delay = param.args.getOrNull(1) as? Long ?: return
                    val unit = param.args.getOrNull(2) as? java.util.concurrent.TimeUnit ?: return
                    if (delay <= 0L) return
                    inSpeedHook.set(true)
                    try {
                        val mult = resolveCurrentSpeedMultiplier()
                        if (kotlin.math.abs(mult - 1.0f) > 0.01f) {
                            val nanos = unit.toNanos(delay)
                            val scaledNanos = (nanos.toDouble() / mult.toDouble()).toLong().coerceIn(100_000L, 3_600_000_000_000L)
                            param.args[1] = scaledNanos
                            param.args[2] = java.util.concurrent.TimeUnit.NANOSECONDS
                        }
                    } finally {
                        inSpeedHook.set(false)
                    }
                }
            }
            val stpePeriodicHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (inSpeedHook.get() == true || isInternalPhantomThread()) return
                    val taskObj = param.args.getOrNull(0)
                    if (taskObj != null) ensureAppObjectDeoptimized(taskObj, depth = 0)
                    val initDelay = param.args.getOrNull(1) as? Long ?: return
                    val period = param.args.getOrNull(2) as? Long ?: return
                    val unit = param.args.getOrNull(3) as? java.util.concurrent.TimeUnit ?: return
                    inSpeedHook.set(true)
                    try {
                        val mult = resolveCurrentSpeedMultiplier()
                        if (kotlin.math.abs(mult - 1.0f) > 0.01f) {
                            val initNanos = unit.toNanos(initDelay.coerceAtLeast(0L))
                            val periodNanos = unit.toNanos(period.coerceAtLeast(1L))
                            val scaledInit = if (initNanos > 0L) (initNanos.toDouble() / mult.toDouble()).toLong().coerceAtLeast(100_000L) else 0L
                            val scaledPeriod = (periodNanos.toDouble() / mult.toDouble()).toLong().coerceIn(500_000L, 3_600_000_000_000L)
                            param.args[1] = scaledInit
                            param.args[2] = scaledPeriod
                            param.args[3] = java.util.concurrent.TimeUnit.NANOSECONDS
                        }
                    } finally {
                        inSpeedHook.set(false)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(stpeClass, "schedule", Runnable::class.java, Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java, stpeSingleHook)
            XposedHelpers.findAndHookMethod(stpeClass, "schedule", java.util.concurrent.Callable::class.java, Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java, stpeSingleHook)
            XposedHelpers.findAndHookMethod(stpeClass, "scheduleAtFixedRate", Runnable::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java, stpePeriodicHook)
            XposedHelpers.findAndHookMethod(stpeClass, "scheduleWithFixedDelay", Runnable::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java, stpePeriodicHook)
        } catch (_: Throwable) {}

        // 6. ValueAnimator / ObjectAnimator duration scaling for smooth UI/counter animation speed-up & slow-down
        try {
            val vaClass = XposedHelpers.findClass("android.animation.ValueAnimator", classLoader)
            if (vaClass != null) {
                XposedHelpers.findAndHookMethod(vaClass, "setDuration", Long::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (inSpeedHook.get() == true) return
                        val origDuration = param.args.getOrNull(0) as? Long ?: return
                        if (origDuration <= 0L) return
                        inSpeedHook.set(true)
                        try {
                            val mult = resolveCurrentSpeedMultiplier()
                            if (kotlin.math.abs(mult - 1.0f) > 0.01f) {
                                val scaled = (origDuration.toDouble() / mult.toDouble()).toLong().coerceIn(1L, 86_400_000L)
                                param.args[0] = scaled
                            }
                        } finally {
                            inSpeedHook.set(false)
                        }
                    }
                })
                try {
                    XposedHelpers.findAndHookMethod(
                        vaClass,
                        "addUpdateListener",
                        android.animation.ValueAnimator.AnimatorUpdateListener::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val listener = param.args.getOrNull(0) ?: return
                                ensureAppObjectDeoptimized(listener, depth = 0)
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 7. WebView HTML5 / JS App & Point Counter Speed Scaling
        // Uses a dynamic recursive setTimeout loop for setInterval so changing speed in the Floating HUD
        // immediately accelerates or slows down ALREADY-RUNNING JS intervals without reloading the page!
        try {
            val wvClass = XposedHelpers.findClass("android.webkit.WebView", classLoader)
            if (wvClass != null) {
                val injectJsSpeedHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (inSpeedHook.get() == true) return
                        val wv = param.thisObject as? android.webkit.WebView ?: return
                        trackedWebViews[wv] = true
                        inSpeedHook.set(true)
                        try {
                            val mult = resolveCurrentSpeedMultiplier()
                            val js = """
                                (function() {
                                    window.__phantomSpeed = $mult;
                                    if (window.__phantomSpeedInstalled) return;
                                    window.__phantomSpeedInstalled = true;
                                    const origSetTimeout = window.setTimeout.bind(window);
                                    const origClearTimeout = window.clearTimeout.bind(window);
                                    const origSetInterval = window.setInterval.bind(window);
                                    const origClearInterval = window.clearInterval.bind(window);
                                    const activeIntervals = new Map();
                                    let nextIntervalId = 900000;

                                    window.setTimeout = function(fn, delay, ...args) {
                                        const m = window.__phantomSpeed || 1.0;
                                        const d = (typeof delay === 'number' && delay > 0 && m >= 0.0005) ? Math.max(1, delay / m) : delay;
                                        return origSetTimeout(fn, d, ...args);
                                    };

                                    window.setInterval = function(fn, delay, ...args) {
                                        const id = ++nextIntervalId;
                                        const baseDelay = (typeof delay === 'number' && delay > 0) ? delay : 16;
                                        function tick() {
                                            if (!activeIntervals.has(id)) return;
                                            try {
                                                if (typeof fn === 'function') fn(...args);
                                                else if (typeof fn === 'string') (0, eval)(fn);
                                            } catch (e) {}
                                            if (!activeIntervals.has(id)) return;
                                            const m = window.__phantomSpeed || 1.0;
                                            const nextD = Math.max(1, baseDelay / Math.max(0.001, m));
                                            activeIntervals.set(id, origSetTimeout(tick, nextD));
                                        }
                                        const initM = window.__phantomSpeed || 1.0;
                                        const firstD = Math.max(1, baseDelay / Math.max(0.001, initM));
                                        activeIntervals.set(id, origSetTimeout(tick, firstD));
                                        return id;
                                    };

                                    window.clearInterval = function(id) {
                                        if (activeIntervals.has(id)) {
                                            origClearTimeout(activeIntervals.get(id));
                                            activeIntervals.delete(id);
                                        } else {
                                            origClearInterval(id);
                                        }
                                    };

                                    if (window.performance && window.performance.now) {
                                        const origPerfNow = window.performance.now.bind(window.performance);
                                        let lastReal = origPerfNow();
                                        let virtNow = lastReal;
                                        window.performance.now = function() {
                                            const r = origPerfNow();
                                            const m = window.__phantomSpeed || 1.0;
                                            virtNow += Math.max(0, r - lastReal) * m;
                                            lastReal = r;
                                            return virtNow;
                                        };
                                    }

                                    const origDateNow = Date.now.bind(Date);
                                    let lastRealDate = origDateNow();
                                    let virtDate = lastRealDate;
                                    Date.now = function() {
                                        const r = origDateNow();
                                        const m = window.__phantomSpeed || 1.0;
                                        virtDate += Math.max(0, r - lastRealDate) * m;
                                        lastRealDate = r;
                                        return Math.floor(virtDate);
                                    };
                                })();
                            """.trimIndent()
                            wv.evaluateJavascript(js, null)
                        } catch (_: Throwable) {
                        } finally {
                            inSpeedHook.set(false)
                        }
                    }
                }
                hookAllMethodsCompat(wvClass, "loadUrl", injectJsSpeedHook)
                hookAllMethodsCompat(wvClass, "loadData", injectJsSpeedHook)
                hookAllMethodsCompat(wvClass, "loadDataWithBaseURL", injectJsSpeedHook)
                hookAllMethodsCompat(wvClass, "onWindowFocusChanged", injectJsSpeedHook)
            }
        } catch (_: Throwable) {}
    }

    private fun isLiveProviderLicenseStillExpired(context: android.content.Context): Boolean {
        try {
            val res = context.contentResolver.call(
                android.net.Uri.parse("content://com.aistudio.phantomcore.provider/identity"),
                "get_license_status",
                null,
                null
            )
            if (res != null) {
                val sysEnabled = res.getBoolean("license_system_enabled", true)
                if (!sysEnabled) return false
                val valid = res.getBoolean("license_valid", false)
                val expMs = res.getLong("license_expires_at_ms", 0L)
                if (valid && (expMs <= 0L || System.currentTimeMillis() <= expMs)) {
                    return false
                }
            }
        } catch (_: Throwable) {}
        return true
    }

    private fun hookLicenseExpiredDialogForTargetApp(licenseKey: String) {
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? android.app.Activity ?: return
                        if (act.isFinishing) return
                        if (!isLiveProviderLicenseStillExpired(act)) return

                        act.window?.decorView?.post {
                            if (act.isFinishing) return@post
                            try {
                                val dialog = android.app.Dialog(act)
                                dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                                dialog.setCancelable(false)
                                dialog.setCanceledOnTouchOutside(false)

                                val dp = act.resources.displayMetrics.density
                                val pad = (20f * dp).toInt()
                                val root = android.widget.LinearLayout(act).apply {
                                    orientation = android.widget.LinearLayout.VERTICAL
                                    setPadding(pad, pad, pad, pad)
                                    background = android.graphics.drawable.GradientDrawable().apply {
                                        setColor(android.graphics.Color.parseColor("#0D1322"))
                                        cornerRadius = 18f * dp
                                        setStroke((1.8f * dp).toInt(), android.graphics.Color.parseColor("#EF4444"))
                                    }
                                }

                                val titleTv = android.widget.TextView(act).apply {
                                    text = com.example.ui.i18n.tr(
                                        "🔒 License Expired",
                                        "🔒 লাইসেন্সের মেয়াদ শেষ হয়েছে",
                                        "🔒 लाइसेंस की अवधि समाप्त हो गई है"
                                    )
                                    setTextColor(android.graphics.Color.parseColor("#F87171"))
                                    textSize = 17f
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    gravity = android.view.Gravity.CENTER
                                }
                                root.addView(titleTv)

                                if (licenseKey.isNotBlank()) {
                                    val keyTv = android.widget.TextView(act).apply {
                                        text = "KEY: $licenseKey"
                                        setTextColor(android.graphics.Color.parseColor("#38BDF8"))
                                        textSize = 12f
                                        typeface = android.graphics.Typeface.MONOSPACE
                                        gravity = android.view.Gravity.CENTER
                                    }
                                    root.addView(keyTv)
                                }

                                val msgTv = android.widget.TextView(act).apply {
                                    text = com.example.ui.i18n.tr(
                                        "\nThis target app is linked to your GhostDevice license key. Because the license has expired, the app's operation is paused.\n\nExtend the validity from the Admin Panel or activate a new license key to resume.\n",
                                        "\nএই টার্গেট অ্যাপটি আপনার GhostDevice লাইসেন্স কি-এর সাথে সংযুক্ত। লাইসেন্সের মেয়াদ শেষ হয়ে যাওয়ায় অ্যাপটির কার্যক্রম বন্ধ রয়েছে।\n\nঅ্যাডমিন প্যানেল থেকে মেয়াদ বাড়ালে অথবা নতুন লাইসেন্স অ্যাক্টিভেট করলে এটি আবার কাজ করবে।\n",
                                        "\nयह टार्गेट ऐप आपकी GhostDevice लाइसेंस कुंजी से जुड़ा है। लाइसेंस समाप्त होने के कारण ऐप की गतिविधि रोक दी गई है।\n\nएडमिन पैनल से वैधता बढ़ाएं या फिर से शुरू करने के लिए नया लाइसेंस सक्रिय करें।\n"
                                    )
                                    setTextColor(android.graphics.Color.parseColor("#CBD5E1"))
                                    textSize = 13f
                                    gravity = android.view.Gravity.CENTER
                                }
                                root.addView(msgTv)

                                val openHostBtn = android.widget.TextView(act).apply {
                                    text = com.example.ui.i18n.tr(
                                        "🔑 Open GhostDevice",
                                        "🔑 GhostDevice ওপেন করুন",
                                        "🔑 GhostDevice खोलें"
                                    )
                                    setTextColor(android.graphics.Color.WHITE)
                                    textSize = 13f
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    gravity = android.view.Gravity.CENTER
                                    setPadding(pad, (11f * dp).toInt(), pad, (11f * dp).toInt())
                                    background = android.graphics.drawable.GradientDrawable().apply {
                                        setColor(android.graphics.Color.parseColor("#7C3AED"))
                                        cornerRadius = 10f * dp
                                    }
                                    setOnClickListener {
                                        try {
                                            val launch = act.packageManager.getLaunchIntentForPackage("com.aistudio.ghostdevice.qxvz")
                                                ?: act.packageManager.getLaunchIntentForPackage("com.aistudio.phantomcore.qxvz")
                                            if (launch != null) {
                                                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                act.startActivity(launch)
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                                root.addView(openHostBtn)

                                dialog.setContentView(root)
                                dialog.window?.setBackgroundDrawable(
                                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                                )
                                dialog.show()
                            } catch (_: Throwable) {}
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // INSTANT 1-SECOND AD COMPLETE & NO-AD BYPASS ENGINE (PER TARGET APP)
    // =========================================================================
    private fun hookInstantAdBypassAndCompletion(
        classLoader: ClassLoader,
        pkg: String,
        initialAdBypassEnabled: Boolean
    ) {
        val pkgHash = Integer.toUnsignedString(pkg.hashCode(), 36)
        var cachedEnabled = initialAdBypassEnabled
        var lastCheckMs = 0L

        startBackgroundWalkPollerIfNeeded(0.0, 0.0, pkg, false, false)

        fun isAdBypassEnabledNow(): Boolean {
            if (pkg.isBlank() || isVirtualCloneContainerPkg(pkg)) return false
            val pollerLive = liveAdBypassState
            if (pollerLive != null && livePollerPkg == pkg && (System.currentTimeMillis() - lastWalkReadMs) < 1200L) {
                cachedEnabled = pollerLive
                return pollerLive
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCheckMs < 350L) return cachedEnabled
            lastCheckMs = now
            try {
                // 1. Live GhostDevice ContentProvider (authoritative, updated synchronously on toggle)
                try {
                    val helperClass = Class.forName("de.robv.android.xposed.AndroidAppHelper")
                    val ctx = helperClass.getMethod("currentApplication").invoke(null) as? android.content.Context
                    if (ctx != null) {
                        val uri = android.net.Uri.parse("content://com.aistudio.phantomcore.provider/walk_telemetry")
                        val bundle = ctx.contentResolver.call(uri, "get_walk_telemetry", null, null)
                        val providerAdCsv = bundle?.getString("ad_bypass_packages")
                        if (providerAdCsv != null) {
                            val active = providerAdCsv.split(",").any { it.trim() == pkg }
                            liveAdBypassState = active
                            livePollerPkg = pkg
                            cachedEnabled = active
                            return active
                        }
                    }
                } catch (_: Throwable) {}

                // 2. Fresh JSON identity config
                val idJson = loadIdentity(pkg)
                val arr = idJson?.optJSONArray("adBypassPackages")
                if (arr != null) {
                    var found = false
                    for (i in 0 until arr.length()) {
                        if (arr.optString(i) == pkg) {
                            found = true
                            break
                        }
                    }
                    cachedEnabled = found
                    return found
                }

                // 3. System property fallback when Provider and JSON were both unreachable
                val propFlag = readSysPropInternal("debug.phantom.ad.$pkgHash").ifBlank {
                    readSysPropInternal("log.tag.phantom.ad.$pkgHash")
                }
                if (propFlag == "1") {
                    cachedEnabled = true
                    return true
                } else if (propFlag == "0") {
                    cachedEnabled = false
                    return false
                }
                val adProp = readSysPropInternal("persist.sys.phantom.adpkgs")
                if (adProp == "__NONE__") {
                    cachedEnabled = false
                    return false
                }
                if (adProp.isNotBlank()) {
                    val active = adProp.split(",").any { it.trim() == pkg }
                    cachedEnabled = active
                    return active
                }
            } catch (_: Throwable) {}
            return cachedEnabled
        }

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val hookedAdClasses = java.util.Collections.synchronizedSet(HashSet<String>())
        val adFullScreenCallbacks = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<Any, Any>()
        )
        var lastGlobalFullScreenCallback: Any? = null
        val cachedAdMobInstances = java.util.concurrent.ConcurrentHashMap<String, Any>()
        val concreteAdSubclasses = java.util.concurrent.ConcurrentHashMap<String, Class<*>>()
        val callbackToBaseAdClass = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<Any, String>()
        )
        val fastDeliveringCallbacks = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
        )
        val deliveredCallbackTimestamps = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<Any, Long>()
        )
        val webViewLastInjectedMs = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<android.webkit.WebView, Long>()
        )
        val completedAdActivities = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.app.Activity, Boolean>())
        )
        val activeLoadCallbacksByClass = java.util.concurrent.ConcurrentHashMap<String, Any>()

        val modernAdBaseClasses = listOf(
            "com.google.android.gms.ads.interstitial.InterstitialAd",
            "com.google.android.gms.ads.rewarded.RewardedAd",
            "com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd",
            "com.google.android.gms.ads.appopen.AppOpenAd"
        )

        fun findClassSafe(name: String, loader: ClassLoader): Class<*>? {
            return try {
                Class.forName(name, false, loader)
            } catch (_: Throwable) {
                try {
                    XposedHelpers.findClass(name, loader)
                } catch (_: Throwable) {
                    null
                }
            }
        }

        fun hasMethodInHierarchy(cls: Class<*>?, methodName: String): Boolean {
            var cur = cls
            var depth = 0
            while (cur != null && cur != Any::class.java && depth++ < 6) {
                try {
                    for (m in cur.declaredMethods) {
                        if (m.name == methodName) return true
                    }
                } catch (_: Throwable) {}
                cur = cur.superclass
            }
            return false
        }

        fun resolveModernAdBaseClassName(cls: Class<*>?): String? {
            var cur = cls
            var depth = 0
            while (cur != null && cur != Any::class.java && depth++ < 6) {
                val n = cur.name
                if (n in modernAdBaseClasses) return n
                if (n == "com.google.android.gms.ads.admanager.AdManagerInterstitialAd") {
                    return "com.google.android.gms.ads.interstitial.InterstitialAd"
                }
                cur = cur.superclass
            }
            return null
        }

        fun isAdLoadCallbackObject(obj: Any?): Boolean {
            if (obj == null) return false
            if (obj is android.content.Context || obj is CharSequence || obj is Number || obj is Boolean) {
                return false
            }
            val cls = obj.javaClass
            val n = cls.name
            if (n.startsWith("android.") || n.startsWith("java.") ||
                n.contains("AdRequest") || n.contains("AdManagerAdRequest")
            ) {
                return false
            }
            var cur: Class<*>? = cls
            var depth = 0
            while (cur != null && cur != Any::class.java && depth++ < 6) {
                val cn = cur.name
                if (cn.endsWith("AdLoadCallback") ||
                    cn.endsWith("InterstitialAdLoadCallback") ||
                    cn.endsWith("RewardedAdLoadCallback") ||
                    cn.endsWith("RewardedInterstitialAdLoadCallback") ||
                    cn.endsWith("AppOpenAdLoadCallback")
                ) {
                    return true
                }
                try {
                    for (m in cur.declaredMethods) {
                        if (m.name == "onAdLoaded" || m.name == "onAdFailedToLoad") return true
                    }
                } catch (_: Throwable) {}
                cur = cur.superclass
            }
            return false
        }

        fun createR8SafeInterfaceProxy(targetIfc: Class<*>, loader: ClassLoader): Any? {
            if (!targetIfc.isInterface) return null
            return try {
                java.lang.reflect.Proxy.newProxyInstance(
                    targetIfc.classLoader ?: loader,
                    arrayOf<Class<*>>(targetIfc)
                ) { _, method, _ ->
                    val mn = method.name
                    val rt = method.returnType
                    when {
                        mn == "getAmount" -> 10
                        mn == "getType" || mn == "getLabel" -> "reward"
                        mn == "getAdUnitId" -> "rewarded_unit"
                        mn == "getNetworkName" -> "AdMob"
                        mn == "getRevenue" -> 0.01
                        mn == "toString" -> "RewardItem{amount=10, type=reward}"
                        mn == "hashCode" -> 10
                        mn == "equals" -> false
                        rt == Int::class.javaPrimitiveType || rt == Int::class.java -> 10
                        rt == Long::class.javaPrimitiveType || rt == Long::class.java -> 10L
                        rt == Double::class.javaPrimitiveType || rt == Double::class.java -> 0.01
                        rt == Float::class.javaPrimitiveType || rt == Float::class.java -> 1.0f
                        rt == Boolean::class.javaPrimitiveType || rt == Boolean::class.java -> true
                        rt == Byte::class.javaPrimitiveType || rt == Byte::class.java -> 0.toByte()
                        rt == Short::class.javaPrimitiveType || rt == Short::class.java -> 10.toShort()
                        rt == Char::class.javaPrimitiveType || rt == Char::class.java -> 'r'
                        rt == String::class.java || rt == CharSequence::class.java -> "reward"
                        else -> null
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun createAdMobRewardItemProxy(loader: ClassLoader): Any? {
            val rewardCls = findClassSafe(
                "com.google.android.gms.ads.rewarded.RewardItem",
                loader
            ) ?: return null
            return createR8SafeInterfaceProxy(rewardCls, loader)
        }

        fun invokeRewardListenerSafely(rewardListener: Any?, loader: ClassLoader): Boolean {
            if (rewardListener == null) return false
            try {
                val rewardProxy = createAdMobRewardItemProxy(loader)
                if (rewardProxy != null && hasMethodInHierarchy(rewardListener.javaClass, "onUserEarnedReward")) {
                    callMethodCompat(rewardListener, "onUserEarnedReward", rewardProxy)
                    return true
                }
            } catch (_: Throwable) {}
            // R8-resilient fallback: find any 1-parameter method taking an interface (RewardItem)
            try {
                for (m in rewardListener.javaClass.methods) {
                    if (m.declaringClass == Any::class.java) continue
                    val pTypes = m.parameterTypes
                    if (pTypes.size == 1 && (m.name == "onUserEarnedReward" || pTypes[0].isInterface)) {
                        val pType = pTypes[0]
                        val pProxy = if (pType.isInterface) {
                            createR8SafeInterfaceProxy(pType, pType.classLoader ?: loader)
                        } else {
                            createAdMobRewardItemProxy(loader)
                        }
                        if (pProxy != null) {
                            m.isAccessible = true
                            m.invoke(rewardListener, pProxy)
                            return true
                        }
                    }
                }
            } catch (_: Throwable) {}
            return false
        }

        fun createAidlRewardItemBinder(): android.os.IBinder {
            return object : android.os.Binder() {
                init {
                    attachInterface(null, "com.google.android.gms.ads.internal.rewarded.client.IRewardItem")
                }

                override fun getInterfaceDescriptor(): String {
                    return "com.google.android.gms.ads.internal.rewarded.client.IRewardItem"
                }

                override fun onTransact(
                    code: Int,
                    data: android.os.Parcel,
                    reply: android.os.Parcel?,
                    flags: Int
                ): Boolean {
                    return when (code) {
                        1 -> { // getType(): String
                            reply?.writeNoException()
                            reply?.writeString("reward")
                            true
                        }
                        2 -> { // getAmount(): int
                            reply?.writeNoException()
                            reply?.writeInt(10)
                            true
                        }
                        else -> super.onTransact(code, data, reply, flags)
                    }
                }
            }
        }

        fun transactAidlBinderNoArgs(binder: android.os.IBinder, descriptor: String, code: Int) {
            val data = android.os.Parcel.obtain()
            val reply = android.os.Parcel.obtain()
            try {
                data.writeInterfaceToken(descriptor)
                binder.transact(code, data, reply, 0)
            } catch (_: Throwable) {
            } finally {
                try { data.recycle() } catch (_: Throwable) {}
                try { reply.recycle() } catch (_: Throwable) {}
            }
        }

        fun transactAidlRewardedEarned(binder: android.os.IBinder) {
            val descriptor = "com.google.android.gms.ads.internal.rewarded.client.IRewardedAdCallback"
            val data = android.os.Parcel.obtain()
            val reply = android.os.Parcel.obtain()
            try {
                data.writeInterfaceToken(descriptor)
                data.writeStrongBinder(createAidlRewardItemBinder())
                binder.transact(3, data, reply, 0) // Transaction 3 = onUserEarnedReward(IRewardItem)
            } catch (_: Throwable) {
            } finally {
                try { data.recycle() } catch (_: Throwable) {}
                try { reply.recycle() } catch (_: Throwable) {}
            }
        }

        fun findFullScreenCallbackFromAdObject(adObj: Any?): Any? {
            if (adObj == null) return lastGlobalFullScreenCallback
            adFullScreenCallbacks[adObj]?.let { return it }
            var cur: Class<*>? = adObj.javaClass
            var depth = 0
            while (cur != null && cur != Any::class.java && depth++ < 6) {
                for (f in cur.declaredFields) {
                    try {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        f.isAccessible = true
                        val v = f.get(adObj) ?: continue
                        val vCls = v.javaClass
                        val vClsName = vCls.name
                        val fTypeName = f.type.name
                        if (fTypeName.contains("FullScreenContentCallback") ||
                            vClsName.contains("FullScreenContentCallback") ||
                            vCls.superclass?.name?.contains("FullScreenContentCallback") == true ||
                            hasMethodInHierarchy(vCls, "onAdDismissedFullScreenContent") ||
                            hasMethodInHierarchy(vCls, "onAdShowedFullScreenContent")
                        ) {
                            return v
                        }
                        // Also check 1 level deeper inside internal callback holders (e.g. zzbxz)
                        if (!vClsName.startsWith("android.") && !vClsName.startsWith("java.")) {
                            for (subF in vCls.declaredFields) {
                                try {
                                    if (java.lang.reflect.Modifier.isStatic(subF.modifiers)) continue
                                    subF.isAccessible = true
                                    val subV = subF.get(v) ?: continue
                                    val subCls = subV.javaClass
                                    if (subF.type.name.contains("FullScreenContentCallback") ||
                                        subCls.name.contains("FullScreenContentCallback") ||
                                        hasMethodInHierarchy(subCls, "onAdDismissedFullScreenContent")
                                    ) {
                                        return subV
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                }
                cur = cur.superclass
            }
            return lastGlobalFullScreenCallback
        }

        fun findInternalAidlCallbackBinders(rootObj: Any?): List<Pair<android.os.IBinder, String>> {
            if (rootObj == null) return emptyList()
            val out = ArrayList<Pair<android.os.IBinder, String>>()
            val visited = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
            fun scan(obj: Any?, depth: Int) {
                if (obj == null || depth > 3 || !visited.add(obj)) return
                try {
                    val b: android.os.IBinder? = when (obj) {
                        is android.os.IBinder -> obj
                        is android.os.IInterface -> obj.asBinder()
                        else -> null
                    }
                    if (b != null) {
                        val desc = try { b.interfaceDescriptor ?: "" } catch (_: Throwable) { "" }
                        if (desc.isNotEmpty()) {
                            out.add(b to desc)
                        }
                    }
                } catch (_: Throwable) {}
                val cls = obj.javaClass
                if (cls.name.startsWith("java.") || cls.name.startsWith("android.widget.") || cls.name.startsWith("android.view.")) return
                var cur: Class<*>? = cls
                var lvl = 0
                while (cur != null && cur != Any::class.java && lvl++ < 4) {
                    try {
                        for (f in cur.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            if (f.type.isPrimitive || f.type == String::class.java) continue
                            f.isAccessible = true
                            val v = f.get(obj) ?: continue
                            scan(v, depth + 1)
                        }
                    } catch (_: Throwable) {}
                    cur = cur.superclass
                }
            }
            scan(rootObj, 0)
            return out
        }

        // Chained causal execution: Step 1 (Show/Impression @ 15ms) -> Step 2 (Reward @ +35ms) -> Step 3 (Dismiss @ +40ms)
        // Guarantees any runOnUiThread / Handler.post / evaluateJavascript queued by the app inside Step 1 or Step 2
        // ALWAYS executes before Step 3 runs!
        fun simulateAdMobFullScreenShowAndComplete(
            adObj: Any?,
            rewardListener: Any?,
            loader: ClassLoader
        ) {
            val binders = findInternalAidlCallbackBinders(adObj)
            mainHandler.postDelayed({
                // Step 1: Ad Showed FullScreen + Impression
                try {
                    val cb = findFullScreenCallbackFromAdObject(adObj)
                    if (cb != null) {
                        try { callMethodCompat(cb, "onAdShowedFullScreenContent") } catch (_: Throwable) {}
                        try { callMethodCompat(cb, "onAdImpression") } catch (_: Throwable) {}
                    } else {
                        for ((b, desc) in binders) {
                            if (desc == "com.google.android.gms.ads.internal.rewarded.client.IRewardedAdCallback") {
                                transactAidlBinderNoArgs(b, desc, 1) // onRewardedAdOpened
                                transactAidlBinderNoArgs(b, desc, 6) // onAdImpression
                            } else if (desc == "com.google.android.gms.ads.internal.client.IFullScreenContentCallback") {
                                transactAidlBinderNoArgs(b, desc, 2) // onAdShowedFullScreenContent
                                transactAidlBinderNoArgs(b, desc, 4) // onAdImpression
                            }
                        }
                    }
                } catch (_: Throwable) {}

                // Step 2: User Earned Reward (chained +35ms after Show completes)
                mainHandler.postDelayed({
                    try {
                        var rewarded = false
                        if (rewardListener != null) {
                            rewarded = invokeRewardListenerSafely(rewardListener, loader)
                        }
                        if (!rewarded) {
                            for ((b, desc) in binders) {
                                if (desc == "com.google.android.gms.ads.internal.rewarded.client.IRewardedAdCallback") {
                                    transactAidlRewardedEarned(b)
                                }
                            }
                        }
                    } catch (_: Throwable) {}

                    // Step 3: Ad Dismissed FullScreen Content (chained +40ms after Reward completes)
                    mainHandler.postDelayed({
                        try {
                            val cb = findFullScreenCallbackFromAdObject(adObj)
                            if (cb != null) {
                                callMethodCompat(cb, "onAdDismissedFullScreenContent")
                            } else {
                                for ((b, desc) in binders) {
                                    if (desc == "com.google.android.gms.ads.internal.rewarded.client.IRewardedAdCallback") {
                                        transactAidlBinderNoArgs(b, desc, 2) // onRewardedAdClosed
                                    } else if (desc == "com.google.android.gms.ads.internal.client.IFullScreenContentCallback") {
                                        transactAidlBinderNoArgs(b, desc, 3) // onAdDismissedFullScreenContent
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }, 40L)
                }, 35L)
            }, 15L)
        }

        fun hookMethodInHierarchyIfConcrete(
            targetCls: Class<*>,
            methodName: String,
            hook: XC_MethodHook
        ) {
            var cur: Class<*>? = targetCls
            var depth = 0
            while (cur != null && cur != Any::class.java && depth++ < 5) {
                try {
                    for (m in cur.declaredMethods) {
                        if (m.name == methodName && !java.lang.reflect.Modifier.isAbstract(m.modifiers)) {
                            XposedBridge.hookMethod(m, hook)
                        }
                    }
                } catch (_: Throwable) {}
                cur = cur.superclass
            }
        }

        // Forward declaration for mutual recursion
        lateinit var deliverFastAdLoadedRef: (Any, Any, ClassLoader) -> Unit
        lateinit var hookLoadCallbackClassRef: (Class<*>, String, ClassLoader) -> Unit

        fun hookAdMobAdInstanceClass(adClass: Class<*>, loader: ClassLoader) {
            val clsName = adClass.name
            if (!hookedAdClasses.add("admob_inst:$clsName@${loader.hashCode()}")) return

            val baseAdName = resolveModernAdBaseClassName(adClass)
            if (baseAdName != null) {
                // Hook base AND concrete constructors so the moment new zzbxt / zzbmp / zzbyj / zzbav runs
                // (even when resolved directly by ART's C++ ClassLinker without calling ClassLoader.loadClass),
                // we immediately capture the concrete subclass and instance!
                try {
                    val ctorHook = object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val instance = param.thisObject ?: return
                            val runtimeCls = instance.javaClass
                            if (!java.lang.reflect.Modifier.isAbstract(runtimeCls.modifiers)) {
                                concreteAdSubclasses[baseAdName] = runtimeCls
                                cachedAdMobInstances[baseAdName] = instance
                                if (runtimeCls != adClass) {
                                    hookAdMobAdInstanceClass(runtimeCls, runtimeCls.classLoader ?: loader)
                                }
                                if (isAdBypassEnabledNow()) {
                                    val pendingCb = activeLoadCallbacksByClass.remove(baseAdName)
                                    if (pendingCb != null) {
                                        deliverFastAdLoadedRef(pendingCb, instance, loader)
                                    }
                                }
                            }
                        }
                    }
                    for (ctor in adClass.declaredConstructors) {
                        XposedBridge.hookMethod(ctor, ctorHook)
                    }
                } catch (_: Throwable) {}
            }

            if (baseAdName != null && !java.lang.reflect.Modifier.isAbstract(adClass.modifiers)) {
                concreteAdSubclasses[baseAdName] = adClass

                // Hook the internal 2-arg load method on the concrete class (e.g. zza(zzdx, AdLoadCallback))
                // so the very first load() call short-circuits before touching the network!
                try {
                    for (m in adClass.declaredMethods) {
                        if (java.lang.reflect.Modifier.isStatic(m.modifiers) ||
                            java.lang.reflect.Modifier.isAbstract(m.modifiers)
                        ) continue
                        val pTypes = m.parameterTypes
                        if (pTypes.size == 2 && m.returnType == Void.TYPE &&
                            !pTypes[0].isPrimitive && !pTypes[1].isPrimitive
                        ) {
                            XposedBridge.hookMethod(
                                m,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val thiz = param.thisObject ?: return
                                        cachedAdMobInstances[baseAdName] = thiz
                                        val cbArg = param.args.getOrNull(1)?.takeIf { isAdLoadCallbackObject(it) }
                                            ?: param.args.getOrNull(0)?.takeIf { isAdLoadCallbackObject(it) }
                                        if (cbArg != null) {
                                            callbackToBaseAdClass[cbArg] = baseAdName
                                            hookLoadCallbackClassRef(
                                                cbArg.javaClass,
                                                baseAdName,
                                                cbArg.javaClass.classLoader ?: loader
                                            )
                                            if (isAdBypassEnabledNow()) {
                                                activeLoadCallbacksByClass.remove(baseAdName)
                                                param.result = null
                                                deliverFastAdLoadedRef(cbArg, thiz, loader)
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                } catch (_: Throwable) {}
            }

            hookMethodInHierarchyIfConcrete(
                adClass,
                "setFullScreenContentCallback",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val thiz = param.thisObject ?: return
                        val cb = param.args.firstOrNull()
                        if (cb != null) {
                            adFullScreenCallbacks[thiz] = cb
                            lastGlobalFullScreenCallback = cb
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null && isAdBypassEnabledNow()) {
                            param.throwable = null
                        }
                    }
                }
            )

            hookMethodInHierarchyIfConcrete(
                adClass,
                "getFullScreenContentCallback",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isAdBypassEnabledNow()) return
                        val thiz = param.thisObject ?: return
                        val cb = adFullScreenCallbacks[thiz]
                        if (cb != null) param.result = cb
                    }
                }
            )

            for (safeSetter in listOf(
                "setImmersiveMode",
                "setOnPaidEventListener",
                "setServerSideVerificationOptions",
                "setOnAdMetadataChangedListener"
            )) {
                hookMethodInHierarchyIfConcrete(
                    adClass,
                    safeSetter,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null && isAdBypassEnabledNow()) {
                                param.throwable = null
                            }
                        }
                    }
                )
            }

            hookMethodInHierarchyIfConcrete(
                adClass,
                "getRewardItem",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isAdBypassEnabledNow()) {
                            val proxy = createAdMobRewardItemProxy(loader)
                            if (proxy != null) param.result = proxy
                        }
                    }
                }
            )

            hookMethodInHierarchyIfConcrete(
                adClass,
                "getAdUnitId",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isAdBypassEnabledNow()) return
                        if (param.throwable != null || param.result == null) {
                            param.throwable = null
                            param.result = "ca-app-pub-3940256099942544/5224354917"
                        }
                    }
                }
            )

            hookMethodInHierarchyIfConcrete(
                adClass,
                "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isAdBypassEnabledNow()) return
                        val thiz = param.thisObject
                        var rewardListener: Any? = null
                        for (arg in param.args) {
                            if (arg != null && arg !is android.app.Activity && arg !is android.content.Context && arg !is Boolean) {
                                rewardListener = arg
                                break
                            }
                        }
                        param.result = null
                        simulateAdMobFullScreenShowAndComplete(thiz, rewardListener, loader)
                    }
                }
            )
        }

        fun instantiateConcreteAdMobObject(
            baseClsName: String,
            contextArg: android.content.Context?,
            adUnitIdArg: String?,
            loader: ClassLoader
        ): Any? {
            // Always create a FRESH concrete Ad instance first so each load() / Activity session gets
            // its own independent FullScreenContentCallback and clean state!
            val concreteCls = concreteAdSubclasses[baseClsName]
                ?: cachedAdMobInstances[baseClsName]?.javaClass?.takeIf {
                    !java.lang.reflect.Modifier.isAbstract(it.modifiers)
                }
            if (concreteCls != null) {
                try {
                    val ctx = contextArg ?: runCatching {
                        Class.forName("android.app.ActivityThread")
                            .getDeclaredMethod("currentApplication")
                            .invoke(null) as? android.content.Context
                    }.getOrNull()
                    val unitId = adUnitIdArg ?: "ca-app-pub-3940256099942544/5224354917"
                    for (ctor in concreteCls.declaredConstructors) {
                        ctor.isAccessible = true
                        val pTypes = ctor.parameterTypes
                        if (pTypes.size == 2 &&
                            android.content.Context::class.java.isAssignableFrom(pTypes[0]) &&
                            pTypes[1] == String::class.java &&
                            ctx != null
                        ) {
                            val inst = ctor.newInstance(ctx, unitId)
                            cachedAdMobInstances[baseClsName] = inst
                            hookAdMobAdInstanceClass(concreteCls, loader)
                            return inst
                        } else if (pTypes.isEmpty()) {
                            val inst = ctor.newInstance()
                            cachedAdMobInstances[baseClsName] = inst
                            hookAdMobAdInstanceClass(concreteCls, loader)
                            return inst
                        }
                    }
                } catch (_: Throwable) {}
                try {
                    val unsafeCls = Class.forName("sun.misc.Unsafe")
                    val f = unsafeCls.getDeclaredField("theUnsafe")
                    f.isAccessible = true
                    val unsafe = f.get(null)
                    val allocMethod = unsafeCls.getMethod("allocateInstance", Class::class.java)
                    val inst = allocMethod.invoke(unsafe, concreteCls)
                    if (inst != null) {
                        cachedAdMobInstances[baseClsName] = inst
                        hookAdMobAdInstanceClass(concreteCls, loader)
                        return inst
                    }
                } catch (_: Throwable) {}
            }
            return cachedAdMobInstances[baseClsName]
        }

        fun deliverFastAdLoaded(callbackObj: Any, adObj: Any, loader: ClassLoader) {
            val now = android.os.SystemClock.elapsedRealtime()
            val prevTs = deliveredCallbackTimestamps[callbackObj] ?: 0L
            // Only deduplicate within a 120ms window for the same load() call so reusing the same
            // callbackObj for subsequent ads or after re-entering the app ALWAYS fires onAdLoaded!
            if (now - prevTs < 120L) return
            deliveredCallbackTimestamps[callbackObj] = now
            hookAdMobAdInstanceClass(adObj.javaClass, adObj.javaClass.classLoader ?: loader)
            mainHandler.postDelayed({
                fastDeliveringCallbacks.add(callbackObj)
                try {
                    callMethodCompat(callbackObj, "onAdLoaded", adObj)
                } catch (_: Throwable) {
                    try {
                        for (m in callbackObj.javaClass.methods) {
                            if (m.name == "onAdLoaded" && m.parameterTypes.size == 1) {
                                m.isAccessible = true
                                m.invoke(callbackObj, adObj)
                                break
                            }
                        }
                    } catch (_: Throwable) {}
                } finally {
                    fastDeliveringCallbacks.remove(callbackObj)
                }
            }, 15L)
        }
        deliverFastAdLoadedRef = ::deliverFastAdLoaded

        fun hookLoadCallbackClass(cbClass: Class<*>, baseAdClassName: String, loader: ClassLoader) {
            val cbClsName = cbClass.name
            if (!hookedAdClasses.add("admob_loadcb:$cbClsName@${loader.hashCode()}")) return

            hookMethodInHierarchyIfConcrete(
                cbClass,
                "onAdLoaded",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cbInstance = param.thisObject
                        val loadedAd = param.args.firstOrNull() ?: return
                        val resolvedBase = (if (cbInstance != null) callbackToBaseAdClass[cbInstance] else null)
                            ?: resolveModernAdBaseClassName(loadedAd.javaClass)
                            ?: baseAdClassName
                        if (resolvedBase.isNotEmpty()) {
                            cachedAdMobInstances[resolvedBase] = loadedAd
                            if (!java.lang.reflect.Modifier.isAbstract(loadedAd.javaClass.modifiers)) {
                                concreteAdSubclasses[resolvedBase] = loadedAd.javaClass
                            }
                        }
                        // Always hook loadedAd's concrete show() class even if Ad Skip is currently OFF,
                        // so toggling Ad Skip ON later immediately skips showing this already-loaded ad!
                        hookAdMobAdInstanceClass(loadedAd.javaClass, loadedAd.javaClass.classLoader ?: loader)
                        if (!isAdBypassEnabledNow()) return
                        if (cbInstance != null) {
                            if (fastDeliveringCallbacks.contains(cbInstance)) {
                                // This is our own fast-delivered onAdLoaded call — let it execute!
                                return
                            }
                            val now = android.os.SystemClock.elapsedRealtime()
                            val prevTs = deliveredCallbackTimestamps[cbInstance] ?: 0L
                            if (now - prevTs < 800L) {
                                // Already delivered our fast onAdLoaded for this load request
                                param.result = null
                                return
                            }
                            deliveredCallbackTimestamps[cbInstance] = now
                        }
                    }
                }
            )

            // Intercept onAdFailedToLoad so rate-limits / no-fill / offline never fail when Ad Skip is ON.
            hookMethodInHierarchyIfConcrete(
                cbClass,
                "onAdFailedToLoad",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isAdBypassEnabledNow()) return
                        val cbInstance = param.thisObject ?: return
                        val now = android.os.SystemClock.elapsedRealtime()
                        val prevTs = deliveredCallbackTimestamps[cbInstance] ?: 0L
                        if (now - prevTs < 800L) {
                            param.result = null
                            return
                        }
                        val resolvedBase = callbackToBaseAdClass[cbInstance]
                            ?.takeIf { it.isNotEmpty() }
                            ?: baseAdClassName.takeIf { it.isNotEmpty() }
                            ?: concreteAdSubclasses.keys.firstOrNull()
                            ?: cachedAdMobInstances.keys.firstOrNull()
                            ?: ""
                        val freshAd = if (resolvedBase.isNotEmpty()) {
                            instantiateConcreteAdMobObject(resolvedBase, null, null, loader)
                        } else null
                        if (freshAd != null) {
                            param.result = null
                            deliverFastAdLoaded(cbInstance, freshAd, loader)
                        }
                    }
                }
            )
        }
        hookLoadCallbackClassRef = ::hookLoadCallbackClass

        // Hook Binder.attachInterface once so whenever AdMob creates zzbya (IRewardedAdLoadCallback)
        // or zzbma (IAdLoadCallback), we immediately capture the concrete zzbxt/zzbmp/zzbyj Ad instance
        // and fire onAdLoaded in 15ms even on the very first load!
        if (hookedAdClasses.add("android_os_binder_attach_admob")) {
            try {
                XposedHelpers.findAndHookMethod(
                    android.os.Binder::class.java,
                    "attachInterface",
                    android.os.IInterface::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val desc = param.args.getOrNull(1) as? String ?: return
                            if (desc != "com.google.android.gms.ads.internal.rewarded.client.IRewardedAdLoadCallback" &&
                                desc != "com.google.android.gms.ads.internal.client.IAdLoadCallback"
                            ) {
                                return
                            }
                            val binderObj = param.thisObject as? android.os.IBinder ?: return
                            mainHandler.postDelayed({
                                try {
                                    var foundAd: Any? = null
                                    var foundCb: Any? = null
                                    for (f in binderObj.javaClass.declaredFields) {
                                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                        f.isAccessible = true
                                        val v = f.get(binderObj) ?: continue
                                        val baseName = resolveModernAdBaseClassName(v.javaClass)
                                        if (baseName != null) {
                                            foundAd = v
                                            cachedAdMobInstances[baseName] = v
                                            if (!java.lang.reflect.Modifier.isAbstract(v.javaClass.modifiers)) {
                                                concreteAdSubclasses[baseName] = v.javaClass
                                            }
                                            hookAdMobAdInstanceClass(v.javaClass, v.javaClass.classLoader ?: classLoader)
                                        } else if (isAdLoadCallbackObject(v)) {
                                            foundCb = v
                                        }
                                    }
                                    if (isAdBypassEnabledNow()) {
                                        if (foundCb != null && foundAd != null) {
                                            deliverFastAdLoaded(foundCb, foundAd, foundAd.javaClass.classLoader ?: classLoader)
                                        } else {
                                            transactAidlBinderNoArgs(binderObj, desc, 1)
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }, 15L)
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        fun hookAdMobClassesInLoader(loader: ClassLoader) {
            // 0. Fast-complete MobileAds.initialize so games waiting on SDK initialization never hang
            val mobileAdsInitCls = findClassSafe("com.google.android.gms.ads.MobileAds", loader)
            if (mobileAdsInitCls != null && hookedAdClasses.add("admob_init:${mobileAdsInitCls.name}@${loader.hashCode()}")) {
                try {
                    hookAllMethodsCompat(
                        mobileAdsInitCls,
                        "initialize",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                for (arg in param.args) {
                                    if (arg != null && arg !is android.content.Context && arg !is String) {
                                        val initStatusIfc = findClassSafe(
                                            "com.google.android.gms.ads.initialization.InitializationStatus",
                                            loader
                                        )
                                        val statusProxy = if (initStatusIfc != null && initStatusIfc.isInterface) {
                                            java.lang.reflect.Proxy.newProxyInstance(
                                                loader,
                                                arrayOf<Class<*>>(initStatusIfc)
                                            ) { _, m, _ ->
                                                if (m.name == "getAdapterStatusMap") emptyMap<String, Any>() else null
                                            }
                                        } else null
                                        mainHandler.postDelayed({
                                            try {
                                                callMethodCompat(arg, "onInitializationComplete", statusProxy)
                                            } catch (_: Throwable) {}
                                        }, 10L)
                                    }
                                }
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }

            // 1. Modern AdMob Fullscreen Ads: InterstitialAd, RewardedAd, RewardedInterstitialAd, AppOpenAd
            for (clsName in modernAdBaseClasses) {
                val cls = findClassSafe(clsName, loader) ?: continue
                hookAdMobAdInstanceClass(cls, loader)
                if (hookedAdClasses.add("admob_static_load:$clsName@${loader.hashCode()}")) {
                    try {
                        hookAllMethodsCompat(
                            cls,
                            "load",
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    var foundCallback: Any? = null
                                    var contextArg: android.content.Context? = null
                                    var unitIdArg: String? = null
                                    for (arg in param.args) {
                                        if (arg == null) continue
                                        if (arg is android.content.Context && contextArg == null) {
                                            contextArg = arg
                                        } else if (arg is String && unitIdArg == null) {
                                            unitIdArg = arg
                                        } else if (isAdLoadCallbackObject(arg)) {
                                            foundCallback = arg
                                        }
                                    }
                                    // Fallback if callback class was heavily obfuscated
                                    if (foundCallback == null) {
                                        foundCallback = param.args.lastOrNull {
                                            it != null &&
                                                it !is android.content.Context &&
                                                it !is String &&
                                                it !is Number &&
                                                it !is Boolean &&
                                                !it.javaClass.name.contains("AdRequest")
                                        }
                                    }
                                    if (foundCallback != null) {
                                        callbackToBaseAdClass[foundCallback] = clsName
                                        hookLoadCallbackClass(
                                            foundCallback.javaClass,
                                            clsName,
                                            foundCallback.javaClass.classLoader ?: loader
                                        )
                                    }
                                    if (isAdBypassEnabledNow() && foundCallback != null) {
                                        val cbRef = foundCallback
                                        val freshAd = instantiateConcreteAdMobObject(
                                            clsName,
                                            contextArg,
                                            unitIdArg,
                                            loader
                                        )
                                        if (freshAd != null) {
                                            // Short-circuit network load completely for instant millisecond load
                                            param.result = null
                                            deliverFastAdLoaded(cbRef, freshAd, loader)
                                        } else {
                                            // First load: let load() instantiate the concrete subclass (e.g. zzbmp / zzbxt),
                                            // which will be intercepted immediately by the base constructor hook!
                                            activeLoadCallbacksByClass[clsName] = cbRef
                                            mainHandler.postDelayed({
                                                val prevTs = deliveredCallbackTimestamps[cbRef] ?: 0L
                                                if (android.os.SystemClock.elapsedRealtime() - prevTs >= 250L && isAdBypassEnabledNow()) {
                                                    val lateAd = instantiateConcreteAdMobObject(
                                                        clsName,
                                                        contextArg,
                                                        unitIdArg,
                                                        loader
                                                    )
                                                    if (lateAd != null) {
                                                        deliverFastAdLoaded(cbRef, lateAd, loader)
                                                    }
                                                }
                                            }, 250L)
                                        }
                                    }
                                }
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }

            // 2. Legacy com.google.android.gms.ads.InterstitialAd
            val legacyInterstitialCls = findClassSafe(
                "com.google.android.gms.ads.InterstitialAd",
                loader
            )
            if (legacyInterstitialCls != null && hookedAdClasses.add("admob_legacy_inter:${legacyInterstitialCls.name}@${loader.hashCode()}")) {
                val legacyListeners = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Any>())
                try {
                    hookAllMethodsCompat(
                        legacyInterstitialCls,
                        "setAdListener",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val thiz = param.thisObject ?: return
                                val l = param.args.firstOrNull()
                                if (l != null) legacyListeners[thiz] = l
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        legacyInterstitialCls,
                        "isLoaded",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isAdBypassEnabledNow()) {
                                    param.result = true
                                }
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        legacyInterstitialCls,
                        "loadAd",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                val thiz = param.thisObject ?: return
                                param.result = null
                                mainHandler.postDelayed({
                                    try {
                                        val l = legacyListeners[thiz]
                                        if (l != null) callMethodCompat(l, "onAdLoaded")
                                    } catch (_: Throwable) {}
                                }, 15L)
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        legacyInterstitialCls,
                        "show",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                val thiz = param.thisObject ?: return
                                param.result = null
                                val l = legacyListeners[thiz]
                                mainHandler.postDelayed({
                                    try {
                                        if (l != null) {
                                            try { callMethodCompat(l, "onAdOpened") } catch (_: Throwable) {}
                                            try { callMethodCompat(l, "onAdImpression") } catch (_: Throwable) {}
                                        }
                                    } catch (_: Throwable) {}
                                    mainHandler.postDelayed({
                                        try {
                                            if (l != null) callMethodCompat(l, "onAdClosed")
                                        } catch (_: Throwable) {}
                                    }, 50L)
                                }, 15L)
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }

            // 3. Legacy RewardedVideoAd (com.google.android.gms.ads.reward.RewardedVideoAd)
            if (mobileAdsInitCls != null && hookedAdClasses.add("admob_mobileads:${mobileAdsInitCls.name}@${loader.hashCode()}")) {
                val legacyRewardListeners = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Any>())
                try {
                    hookAllMethodsCompat(
                        mobileAdsInitCls,
                        "getRewardedVideoAdInstance",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val rva = param.result ?: return
                                val rvaCls = rva.javaClass
                                if (!hookedAdClasses.add("admob_rva_inst:${rvaCls.name}@${loader.hashCode()}")) return
                                hookAllMethodsCompat(
                                    rvaCls,
                                    "setRewardedVideoAdListener",
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(p: MethodHookParam) {
                                            val thiz = p.thisObject ?: return
                                            val l = p.args.firstOrNull()
                                            if (l != null) legacyRewardListeners[thiz] = l
                                        }
                                    }
                                )
                                hookAllMethodsCompat(
                                    rvaCls,
                                    "isLoaded",
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(p: MethodHookParam) {
                                            if (isAdBypassEnabledNow()) p.result = true
                                        }
                                    }
                                )
                                hookAllMethodsCompat(
                                    rvaCls,
                                    "loadAd",
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(p: MethodHookParam) {
                                            if (!isAdBypassEnabledNow()) return
                                            val thiz = p.thisObject ?: return
                                            p.result = null
                                            mainHandler.postDelayed({
                                                try {
                                                    val l = legacyRewardListeners[thiz]
                                                    if (l != null) callMethodCompat(l, "onRewardedVideoAdLoaded")
                                                } catch (_: Throwable) {}
                                            }, 15L)
                                        }
                                    }
                                )
                                hookAllMethodsCompat(
                                    rvaCls,
                                    "show",
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(p: MethodHookParam) {
                                            if (!isAdBypassEnabledNow()) return
                                            val thiz = p.thisObject ?: return
                                            p.result = null
                                            val l = legacyRewardListeners[thiz]
                                            mainHandler.postDelayed({
                                                try {
                                                    if (l != null) {
                                                        try { callMethodCompat(l, "onRewardedVideoAdOpened") } catch (_: Throwable) {}
                                                        try { callMethodCompat(l, "onRewardedVideoStarted") } catch (_: Throwable) {}
                                                    }
                                                } catch (_: Throwable) {}
                                                mainHandler.postDelayed({
                                                    try {
                                                        if (l != null) {
                                                            val legacyRewardIfc = findClassSafe(
                                                                "com.google.android.gms.ads.reward.RewardItem",
                                                                loader
                                                            )
                                                            if (legacyRewardIfc != null) {
                                                                val rProxy = createR8SafeInterfaceProxy(legacyRewardIfc, loader)
                                                                if (rProxy != null) {
                                                                    callMethodCompat(l, "onRewarded", rProxy)
                                                                }
                                                            }
                                                        }
                                                    } catch (_: Throwable) {}
                                                    mainHandler.postDelayed({
                                                        try {
                                                            if (l != null) {
                                                                try { callMethodCompat(l, "onRewardedVideoCompleted") } catch (_: Throwable) {}
                                                                try { callMethodCompat(l, "onRewardedVideoAdClosed") } catch (_: Throwable) {}
                                                            }
                                                        } catch (_: Throwable) {}
                                                    }, 40L)
                                                }, 35L)
                                            }, 15L)
                                        }
                                    }
                                )
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }

            // 4. Banner & Native AdView (com.google.android.gms.ads.BaseAdView / AdView)
            val baseAdViewCls = findClassSafe(
                "com.google.android.gms.ads.BaseAdView",
                loader
            ) ?: findClassSafe("com.google.android.gms.ads.AdView", loader)
            if (baseAdViewCls != null && hookedAdClasses.add("admob_banner:${baseAdViewCls.name}@${loader.hashCode()}")) {
                val bannerListeners = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Any>())
                try {
                    hookAllMethodsCompat(
                        baseAdViewCls,
                        "setAdListener",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val thiz = param.thisObject ?: return
                                val l = param.args.firstOrNull()
                                if (l != null) bannerListeners[thiz] = l
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        baseAdViewCls,
                        "loadAd",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                val thiz = param.thisObject
                                if (thiz is android.view.View) {
                                    mainHandler.post {
                                        try {
                                            thiz.alpha = 0f
                                            val lp = thiz.layoutParams
                                            if (lp != null) {
                                                lp.height = 0
                                                thiz.layoutParams = lp
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                                param.result = null
                                mainHandler.postDelayed({
                                    try {
                                        val l = bannerListeners[thiz] ?: try {
                                            callMethodCompat(thiz, "getAdListener")
                                        } catch (_: Throwable) { null }
                                        if (l != null) {
                                            callMethodCompat(l, "onAdLoaded")
                                        }
                                    } catch (_: Throwable) {}
                                }, 15L)
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }
        }

        fun hookUnityAdsInLoader(loader: ClassLoader) {
            val unityCls = findClassSafe("com.unity3d.ads.UnityAds", loader) ?: return
            if (!hookedAdClasses.add("unity:${unityCls.name}@${loader.hashCode()}")) return
            val legacyUnityListeners = java.util.Collections.synchronizedSet(HashSet<Any>())
            try {
                hookAllMethodsCompat(
                    unityCls,
                    "initialize",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            for (arg in param.args) {
                                if (arg != null && arg !is android.content.Context && arg !is String && arg !is Boolean) {
                                    if (hasMethodInHierarchy(arg.javaClass, "onInitializationComplete") ||
                                        arg.javaClass.interfaces.any { it.name.contains("IUnityAdsInitializationListener") }
                                    ) {
                                        if (isAdBypassEnabledNow()) {
                                            mainHandler.postDelayed({
                                                try { callMethodCompat(arg, "onInitializationComplete") } catch (_: Throwable) {}
                                            }, 15L)
                                        }
                                    } else {
                                        legacyUnityListeners.add(arg)
                                    }
                                }
                            }
                        }
                    }
                )
                hookAllMethodsCompat(
                    unityCls,
                    "addListener",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args.firstOrNull()?.let { legacyUnityListeners.add(it) }
                        }
                    }
                )
                hookAllMethodsCompat(
                    unityCls,
                    "setListener",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args.firstOrNull()?.let { legacyUnityListeners.add(it) }
                        }
                    }
                )
                hookAllMethodsCompat(
                    unityCls,
                    "isReady",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isAdBypassEnabledNow()) param.result = true
                        }
                    }
                )
                hookAllMethodsCompat(
                    unityCls,
                    "isInitialized",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isAdBypassEnabledNow()) param.result = true
                        }
                    }
                )
                hookAllMethodsCompat(
                    unityCls,
                    "load",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isAdBypassEnabledNow()) return
                            val placementId = param.args.firstOrNull { it is String } as? String ?: "rewardedVideo"
                            val loadListener = param.args.firstOrNull {
                                it != null && it !is String && hasMethodInHierarchy(it.javaClass, "onUnityAdsAdLoaded")
                            } ?: param.args.lastOrNull {
                                it != null && it !is String && !it.javaClass.name.contains("UnityAdsLoadOptions")
                            }
                            param.result = null
                            mainHandler.postDelayed({
                                try {
                                    if (loadListener != null) {
                                        callMethodCompat(loadListener, "onUnityAdsAdLoaded", placementId)
                                    }
                                    for (l in legacyUnityListeners.toList()) {
                                        try { callMethodCompat(l, "onUnityAdsReady", placementId) } catch (_: Throwable) {}
                                    }
                                } catch (_: Throwable) {}
                            }, 15L)
                        }
                    }
                )
                hookAllMethodsCompat(
                    unityCls,
                    "show",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isAdBypassEnabledNow()) return
                            val placementId = param.args.firstOrNull { it is String } as? String ?: "rewardedVideo"
                            val showListener = param.args.firstOrNull {
                                it != null && it !is android.app.Activity && it !is String &&
                                    hasMethodInHierarchy(it.javaClass, "onUnityAdsShowComplete")
                            } ?: param.args.lastOrNull {
                                it != null &&
                                    it !is android.app.Activity &&
                                    it !is String &&
                                    !it.javaClass.name.contains("UnityAdsShowOptions")
                            }
                            param.result = null
                            val stateCls = findClassSafe(
                                "com.unity3d.ads.UnityAds\$UnityAdsShowCompletionState",
                                loader
                            )
                            val completedConst = stateCls?.enumConstants?.firstOrNull { c ->
                                c.toString().equals("COMPLETED", ignoreCase = true)
                            }
                            val finishStateCls = findClassSafe(
                                "com.unity3d.ads.UnityAds\$FinishState",
                                loader
                            )
                            val legacyCompletedConst = finishStateCls?.enumConstants?.firstOrNull { c ->
                                c.toString().equals("COMPLETED", ignoreCase = true)
                            }
                            mainHandler.postDelayed({
                                try {
                                    if (showListener != null) {
                                        callMethodCompat(showListener, "onUnityAdsShowStart", placementId)
                                    }
                                    for (l in legacyUnityListeners.toList()) {
                                        try { callMethodCompat(l, "onUnityAdsStart", placementId) } catch (_: Throwable) {}
                                    }
                                } catch (_: Throwable) {}
                                mainHandler.postDelayed({
                                    try {
                                        if (showListener != null && completedConst != null) {
                                            callMethodCompat(
                                                showListener,
                                                "onUnityAdsShowComplete",
                                                placementId,
                                                completedConst
                                            )
                                        }
                                        if (legacyCompletedConst != null) {
                                            for (l in legacyUnityListeners.toList()) {
                                                try {
                                                    callMethodCompat(l, "onUnityAdsFinish", placementId, legacyCompletedConst)
                                                } catch (_: Throwable) {}
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }, 45L)
                            }, 15L)
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        fun hookAppLovinMaxInLoader(loader: ClassLoader) {
            val maxClasses = listOf(
                "com.applovin.mediation.ads.MaxRewardedAd",
                "com.applovin.mediation.ads.MaxInterstitialAd",
                "com.applovin.mediation.ads.MaxAppOpenAd"
            )
            val maxListeners = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Any>())

            fun isMaxListenerObj(obj: Any?): Boolean {
                if (obj == null) return false
                val cls = obj.javaClass
                if (cls.interfaces.any { it.name.contains("MaxAdListener") || it.name.contains("MaxRewardedAdListener") }) {
                    return true
                }
                return hasMethodInHierarchy(cls, "onUserRewarded") ||
                    (hasMethodInHierarchy(cls, "onAdDisplayed") && hasMethodInHierarchy(cls, "onAdHidden"))
            }

            fun findMaxListener(thiz: Any): Any? {
                maxListeners[thiz]?.let { return it }
                var cur: Class<*>? = thiz.javaClass
                while (cur != null && cur != Any::class.java) {
                    for (f in cur.declaredFields) {
                        try {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            f.isAccessible = true
                            val v = f.get(thiz) ?: continue
                            if (isMaxListenerObj(v)) return v
                            // Check 1 level deeper inside MaxFullscreenAdImpl delegate
                            if (!v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("android.")) {
                                var subCur: Class<*>? = v.javaClass
                                while (subCur != null && subCur != Any::class.java) {
                                    for (sf in subCur.declaredFields) {
                                        try {
                                            if (java.lang.reflect.Modifier.isStatic(sf.modifiers)) continue
                                            sf.isAccessible = true
                                            val sv = sf.get(v) ?: continue
                                            if (isMaxListenerObj(sv)) return sv
                                        } catch (_: Throwable) {}
                                    }
                                    subCur = subCur.superclass
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    cur = cur.superclass
                }
                return null
            }

            for (clsName in maxClasses) {
                val cls = findClassSafe(clsName, loader) ?: continue
                if (!hookedAdClasses.add("applovin:$clsName@${loader.hashCode()}")) continue
                try {
                    hookAllMethodsCompat(
                        cls,
                        "setListener",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val thiz = param.thisObject ?: return
                                val l = param.args.firstOrNull()
                                if (l != null) maxListeners[thiz] = l
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        cls,
                        "isReady",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isAdBypassEnabledNow()) param.result = true
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        cls,
                        "loadAd",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                val thiz = param.thisObject ?: return
                                val maxAdIfc = findClassSafe("com.applovin.mediation.MaxAd", loader) ?: return
                                val fakeMaxAd = createR8SafeInterfaceProxy(maxAdIfc, loader) ?: return
                                param.result = null
                                mainHandler.postDelayed({
                                    try {
                                        val l = findMaxListener(thiz)
                                        if (l != null) callMethodCompat(l, "onAdLoaded", fakeMaxAd)
                                    } catch (_: Throwable) {}
                                }, 15L)
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        cls,
                        "showAd",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                val thiz = param.thisObject ?: return
                                param.result = null
                                val l = findMaxListener(thiz) ?: return
                                val maxAdIfc = findClassSafe("com.applovin.mediation.MaxAd", loader) ?: return
                                val fakeMaxAd = createR8SafeInterfaceProxy(maxAdIfc, loader) ?: return
                                mainHandler.postDelayed({
                                    try {
                                        callMethodCompat(l, "onAdDisplayed", fakeMaxAd)
                                    } catch (_: Throwable) {}
                                    mainHandler.postDelayed({
                                        if (clsName.contains("Rewarded")) {
                                            try {
                                                val rewardIfc = findClassSafe(
                                                    "com.applovin.mediation.MaxReward",
                                                    loader
                                                )
                                                if (rewardIfc != null) {
                                                    val fakeReward = createR8SafeInterfaceProxy(rewardIfc, loader)
                                                    if (fakeReward != null) {
                                                        callMethodCompat(l, "onUserRewarded", fakeMaxAd, fakeReward)
                                                    }
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                        mainHandler.postDelayed({
                                            try {
                                                callMethodCompat(l, "onAdHidden", fakeMaxAd)
                                            } catch (_: Throwable) {}
                                        }, 40L)
                                    }, 35L)
                                }, 15L)
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }
        }

        fun hookIronSourceAndFacebookInLoader(loader: ClassLoader) {
            // IronSource / LevelPlay
            val ironCls = findClassSafe("com.ironsource.mediationsdk.IronSource", loader)
            if (ironCls != null && hookedAdClasses.add("ironsource:${ironCls.name}@${loader.hashCode()}")) {
                var rewardListener: Any? = null
                var levelPlayRewardListener: Any? = null
                var interstitialListener: Any? = null
                var levelPlayInterstitialListener: Any? = null
                try {
                    hookAllMethodsCompat(
                        ironCls,
                        "setRewardedVideoListener",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                rewardListener = param.args.firstOrNull()
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        ironCls,
                        "setLevelPlayRewardedVideoListener",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                levelPlayRewardListener = param.args.firstOrNull()
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        ironCls,
                        "setInterstitialListener",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                interstitialListener = param.args.firstOrNull()
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        ironCls,
                        "setLevelPlayInterstitialListener",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                levelPlayInterstitialListener = param.args.firstOrNull()
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        ironCls,
                        "isRewardedVideoAvailable",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isAdBypassEnabledNow()) param.result = true
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        ironCls,
                        "isInterstitialReady",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isAdBypassEnabledNow()) param.result = true
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        ironCls,
                        "loadInterstitial",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                param.result = null
                                mainHandler.postDelayed({
                                    try {
                                        val il = interstitialListener
                                        if (il != null) callMethodCompat(il, "onInterstitialAdReady")
                                        val lpil = levelPlayInterstitialListener
                                        if (lpil != null) callMethodCompat(lpil, "onAdReady", null)
                                    } catch (_: Throwable) {}
                                }, 15L)
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        ironCls,
                        "showRewardedVideo",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                param.result = null
                                mainHandler.postDelayed({
                                    try {
                                        val rl = rewardListener
                                        if (rl != null) {
                                            callMethodCompat(rl, "onRewardedVideoAdOpened")
                                            callMethodCompat(rl, "onRewardedVideoAdStarted")
                                        }
                                        val lpl = levelPlayRewardListener
                                        if (lpl != null) {
                                            try { callMethodCompat(lpl, "onAdOpened", null) } catch (_: Throwable) {}
                                        }
                                    } catch (_: Throwable) {}
                                    mainHandler.postDelayed({
                                        try {
                                            val placementCls = findClassSafe(
                                                "com.ironsource.mediationsdk.model.Placement",
                                                loader
                                            )
                                            var placementObj: Any? = null
                                            try {
                                                val c = placementCls?.constructors?.firstOrNull()
                                                if (c != null) {
                                                    c.isAccessible = true
                                                    val pTypes = c.parameterTypes
                                                    val cArgs = Array<Any?>(pTypes.size) { idx ->
                                                        when (pTypes[idx]) {
                                                            Int::class.javaPrimitiveType, Int::class.java -> 10
                                                            String::class.java -> "DefaultRewardedVideo"
                                                            Boolean::class.javaPrimitiveType, Boolean::class.java -> true
                                                            else -> null
                                                        }
                                                    }
                                                    placementObj = c.newInstance(*cArgs)
                                                }
                                            } catch (_: Throwable) {}
                                            val rl = rewardListener
                                            if (rl != null) {
                                                callMethodCompat(rl, "onRewardedVideoAdRewarded", placementObj)
                                            }
                                            val lpl = levelPlayRewardListener
                                            if (lpl != null) {
                                                try {
                                                    callMethodCompat(lpl, "onAdRewarded", placementObj, null)
                                                } catch (_: Throwable) {}
                                            }
                                        } catch (_: Throwable) {}
                                        mainHandler.postDelayed({
                                            try {
                                                val rl = rewardListener
                                                if (rl != null) {
                                                    callMethodCompat(rl, "onRewardedVideoAdEnded")
                                                    callMethodCompat(rl, "onRewardedVideoAdClosed")
                                                }
                                                val lpl = levelPlayRewardListener
                                                if (lpl != null) {
                                                    try { callMethodCompat(lpl, "onAdClosed", null) } catch (_: Throwable) {}
                                                }
                                            } catch (_: Throwable) {}
                                        }, 40L)
                                    }, 35L)
                                }, 15L)
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        ironCls,
                        "showInterstitial",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                param.result = null
                                mainHandler.postDelayed({
                                    try {
                                        val il = interstitialListener
                                        if (il != null) {
                                            callMethodCompat(il, "onInterstitialAdOpened")
                                            callMethodCompat(il, "onInterstitialAdShowSucceeded")
                                        }
                                        val lpil = levelPlayInterstitialListener
                                        if (lpil != null) {
                                            try { callMethodCompat(lpil, "onAdOpened", null) } catch (_: Throwable) {}
                                            try { callMethodCompat(lpil, "onAdShowSucceeded", null) } catch (_: Throwable) {}
                                        }
                                    } catch (_: Throwable) {}
                                    mainHandler.postDelayed({
                                        try {
                                            val il = interstitialListener
                                            if (il != null) {
                                                callMethodCompat(il, "onInterstitialAdClosed")
                                            }
                                            val lpil = levelPlayInterstitialListener
                                            if (lpil != null) {
                                                try { callMethodCompat(lpil, "onAdClosed", null) } catch (_: Throwable) {}
                                            }
                                        } catch (_: Throwable) {}
                                    }, 50L)
                                }, 15L)
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }

            // Facebook Audience Network
            val fbClasses = listOf(
                "com.facebook.ads.RewardedVideoAd",
                "com.facebook.ads.RewardedInterstitialAd",
                "com.facebook.ads.InterstitialAd"
            )
            val fbListeners = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Any>())

            fun findFbListener(thiz: Any, args: Array<Any?>? = null): Any? {
                fbListeners[thiz]?.let { return it }
                if (args != null) {
                    for (a in args) {
                        if (a == null) continue
                        if (a.javaClass.interfaces.any { it.name.contains("AdListener") }) {
                            fbListeners[thiz] = a
                            return a
                        }
                        for (f in a.javaClass.declaredFields) {
                            try {
                                f.isAccessible = true
                                val fv = f.get(a) ?: continue
                                if (fv.javaClass.interfaces.any { it.name.contains("AdListener") }) {
                                    fbListeners[thiz] = fv
                                    return fv
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                for (f in thiz.javaClass.declaredFields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(thiz) ?: continue
                        if (v.javaClass.interfaces.any { it.name.contains("AdListener") }) {
                            fbListeners[thiz] = v
                            return v
                        }
                        for (subF in v.javaClass.declaredFields) {
                            try {
                                subF.isAccessible = true
                                val sv = subF.get(v) ?: continue
                                if (sv.javaClass.interfaces.any { it.name.contains("AdListener") }) {
                                    fbListeners[thiz] = sv
                                    return sv
                                }
                            } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }
                return null
            }

            for (clsName in fbClasses) {
                val cls = findClassSafe(clsName, loader) ?: continue
                if (!hookedAdClasses.add("fb:$clsName@${loader.hashCode()}")) continue
                try {
                    hookAllMethodsCompat(
                        cls,
                        "isAdLoaded",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isAdBypassEnabledNow()) param.result = true
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        cls,
                        "isAdInvalidated",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isAdBypassEnabledNow()) param.result = false
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        cls,
                        "loadAd",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                val thiz = param.thisObject ?: return
                                val listenerObj = findFbListener(thiz, param.args)
                                param.result = null
                                mainHandler.postDelayed({
                                    try {
                                        val l = listenerObj ?: findFbListener(thiz)
                                        if (l != null) {
                                            callMethodCompat(l, "onAdLoaded", thiz)
                                        }
                                    } catch (_: Throwable) {}
                                }, 15L)
                            }
                        }
                    )
                    hookAllMethodsCompat(
                        cls,
                        "show",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isAdBypassEnabledNow()) return
                                val thiz = param.thisObject ?: return
                                param.result = true
                                val listenerObj = findFbListener(thiz, param.args)
                                mainHandler.postDelayed({
                                    try {
                                        if (listenerObj != null) {
                                            try { callMethodCompat(listenerObj, "onLoggingImpression", thiz) } catch (_: Throwable) {}
                                            try { callMethodCompat(listenerObj, "onInterstitialDisplayed", thiz) } catch (_: Throwable) {}
                                        }
                                    } catch (_: Throwable) {}
                                    mainHandler.postDelayed({
                                        try {
                                            if (listenerObj != null) {
                                                try { callMethodCompat(listenerObj, "onRewardedVideoCompleted") } catch (_: Throwable) {}
                                                try { callMethodCompat(listenerObj, "onRewardedInterstitialCompleted") } catch (_: Throwable) {}
                                            }
                                        } catch (_: Throwable) {}
                                        mainHandler.postDelayed({
                                            try {
                                                if (listenerObj != null) {
                                                    try { callMethodCompat(listenerObj, "onRewardedVideoClosed") } catch (_: Throwable) {}
                                                    try { callMethodCompat(listenerObj, "onRewardedInterstitialClosed") } catch (_: Throwable) {}
                                                    try { callMethodCompat(listenerObj, "onInterstitialDismissed", thiz) } catch (_: Throwable) {}
                                                }
                                            } catch (_: Throwable) {}
                                        }, 40L)
                                    }, 35L)
                                }, 15L)
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }
        }

        fun isKnownAdActivityName(name: String): Boolean {
            return name == "com.google.android.gms.ads.AdActivity" ||
                name.startsWith("com.unity3d.services.ads.adunit.") ||
                name.startsWith("com.applovin.adview.AppLovinFullscreen") ||
                name == "com.applovin.adview.AppLovinInterstitialActivity" ||
                name.startsWith("com.ironsource.sdk.controller.") ||
                name == "com.facebook.ads.AudienceNetworkActivity" ||
                name.startsWith("com.vungle.warren.ui.") ||
                name.startsWith("com.vungle.ads.internal.ui.") ||
                name.startsWith("com.bytedance.sdk.openadsdk.activity.") ||
                name.startsWith("com.bytedance.sdk.openadsdk.stub.activity.") ||
                name.startsWith("com.mbridge.msdk.reward.player.") ||
                name.startsWith("com.mbridge.msdk.activity.") ||
                name.startsWith("com.startapp.sdk.adsbase.activities.") ||
                name.startsWith("com.chartboost.sdk.view.") ||
                name.startsWith("com.inmobi.ads.rendering.") ||
                name.startsWith("sg.bigo.ads.ad.") ||
                name.startsWith("com.fyber.inneractive.sdk.activities.") ||
                name.startsWith("com.yandex.mobile.ads.common.")
        }

        fun triggerRewardOnObjectGraphDeep(rootObj: Any?, maxDepth: Int) {
            if (rootObj == null) return
            val visited = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
            fun walk(obj: Any?, depth: Int) {
                if (obj == null || depth > maxDepth || !visited.add(obj)) return
                val cls = obj.javaClass
                val cn = cls.name
                if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("kotlin.")) return

                // Check AIDL binder descriptors first
                try {
                    val b: android.os.IBinder? = when (obj) {
                        is android.os.IBinder -> obj
                        is android.os.IInterface -> obj.asBinder()
                        else -> null
                    }
                    if (b != null) {
                        val desc = try { b.interfaceDescriptor ?: "" } catch (_: Throwable) { "" }
                        if (desc == "com.google.android.gms.ads.internal.rewarded.client.IRewardedAdCallback") {
                            transactAidlBinderNoArgs(b, desc, 1) // opened
                            transactAidlBinderNoArgs(b, desc, 6) // impression
                            transactAidlRewardedEarned(b)        // reward
                        } else if (desc == "com.google.android.gms.ads.internal.client.IFullScreenContentCallback") {
                            transactAidlBinderNoArgs(b, desc, 2) // showed
                            transactAidlBinderNoArgs(b, desc, 4) // impression
                        }
                    }
                } catch (_: Throwable) {}

                // Check Java listener methods (including 1-parameter onUserEarnedReward!)
                try {
                    for (m in cls.methods) {
                        val mn = m.name
                        val pTypes = m.parameterTypes
                        if (mn == "onUserEarnedReward" && pTypes.size == 1) {
                            invokeRewardListenerSafely(obj, cls.classLoader ?: classLoader)
                        } else if ((mn == "onRewarded" || mn == "onAdRewarded") && pTypes.size == 1 && pTypes[0].isInterface) {
                            val rProxy = createR8SafeInterfaceProxy(pTypes[0], pTypes[0].classLoader ?: classLoader)
                            if (rProxy != null) {
                                m.isAccessible = true
                                m.invoke(obj, rProxy)
                            }
                        } else if ((mn == "onRewardedVideoCompleted" || mn == "onRewardedInterstitialCompleted" ||
                                mn == "onVideoCompleted" || mn == "onAdRewarded") && pTypes.isEmpty()
                        ) {
                            m.isAccessible = true
                            m.invoke(obj)
                        }
                    }
                } catch (_: Throwable) {}

                var cur: Class<*>? = cls
                var lvl = 0
                while (cur != null && cur != Any::class.java && lvl++ < 4) {
                    try {
                        for (f in cur.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            if (f.type.isPrimitive || f.type == String::class.java) continue
                            val fn = f.type.name
                            if (fn.startsWith("android.widget.") || fn.startsWith("android.graphics.") || fn.startsWith("android.view.")) continue
                            f.isAccessible = true
                            val v = f.get(obj) ?: continue
                            walk(v, depth + 1)
                        }
                    } catch (_: Throwable) {}
                    cur = cur.superclass
                }
            }
            walk(rootObj, 0)
        }

        fun fastForwardAndFinishAdActivity(act: android.app.Activity) {
            try {
                act.window?.let { w ->
                    val lp = w.attributes
                    lp.alpha = 0.01f
                    w.attributes = lp
                    w.addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    )
                }
            } catch (_: Throwable) {}

            if (!completedAdActivities.add(act)) return

            mainHandler.postDelayed({
                try {
                    triggerRewardOnObjectGraphDeep(act, 2)
                    for (cachedAd in cachedAdMobInstances.values) {
                        triggerRewardOnObjectGraphDeep(cachedAd, 2)
                    }
                } catch (_: Throwable) {}
                mainHandler.postDelayed({
                    try {
                        val cb = lastGlobalFullScreenCallback
                        if (cb != null) {
                            try { callMethodCompat(cb, "onAdDismissedFullScreenContent") } catch (_: Throwable) {}
                        }
                        if (!act.isFinishing) {
                            act.finish()
                            act.overridePendingTransition(0, 0)
                        }
                    } catch (_: Throwable) {}
                }, 45L)
            }, 25L)
        }

        // Hook initial classLoader
        hookAdMobClassesInLoader(classLoader)
        hookUnityAdsInLoader(classLoader)
        hookAppLovinMaxInLoader(classLoader)
        hookIronSourceAndFacebookInLoader(classLoader)

        // Lightweight ClassLoader.loadClass(String, Boolean) hook — skips 99.9% of classes in O(1)
        // without walking superclasses on every GMS class!
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args.firstOrNull() as? String ?: return
                        if (name.startsWith("com.google.android.gms.ads.")) {
                            val cls = param.result as? Class<*> ?: return
                            val cl = cls.classLoader ?: classLoader
                            if (name.endsWith("InterstitialAd") ||
                                name.endsWith("RewardedAd") ||
                                name.endsWith("RewardedInterstitialAd") ||
                                name.endsWith("AppOpenAd") ||
                                name.endsWith("BaseAdView") ||
                                name.endsWith("AdView") ||
                                name.endsWith("MobileAds")
                            ) {
                                hookAdMobClassesInLoader(cl)
                            } else if (cls.superclass?.name?.endsWith("AdLoadCallback") == true) {
                                hookLoadCallbackClass(cls, "", cl)
                            }
                        } else if (name.startsWith("com.google.android.gms.internal.ads.zzb")) {
                            val cls = param.result as? Class<*> ?: return
                            val supName = cls.superclass?.name ?: return
                            if (supName in modernAdBaseClasses ||
                                supName == "com.google.android.gms.ads.admanager.AdManagerInterstitialAd"
                            ) {
                                hookAdMobAdInstanceClass(cls, cls.classLoader ?: classLoader)
                            }
                        } else if (name == "com.unity3d.ads.UnityAds") {
                            val cls = param.result as? Class<*> ?: return
                            hookUnityAdsInLoader(cls.classLoader ?: classLoader)
                        } else if (name.startsWith("com.applovin.mediation.ads.Max")) {
                            val cls = param.result as? Class<*> ?: return
                            hookAppLovinMaxInLoader(cls.classLoader ?: classLoader)
                        } else if (name == "com.ironsource.mediationsdk.IronSource" ||
                            name.startsWith("com.facebook.ads.Rewarded") ||
                            name == "com.facebook.ads.InterstitialAd"
                        ) {
                            val cls = param.result as? Class<*> ?: return
                            hookIronSourceAndFacebookInLoader(cls.classLoader ?: classLoader)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // Hook WebView H5 AdBreak auto-completion (throttled per WebView, ignoring javascript: calls)
        try {
            val wvClass = findClassSafe("android.webkit.WebView", classLoader)
            if (wvClass != null && hookedAdClasses.add("webview_ad_bypass:${wvClass.name}@${classLoader.hashCode()}")) {
                val injectWebViewAdBypass = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isAdBypassEnabledNow()) return
                        val firstArg = param.args.firstOrNull() as? String
                        if (firstArg != null && firstArg.startsWith("javascript:", ignoreCase = true)) return
                        val wv = param.thisObject as? android.webkit.WebView ?: return
                        val now = android.os.SystemClock.elapsedRealtime()
                        val lastMs = webViewLastInjectedMs[wv] ?: 0L
                        if (now - lastMs < 4000L) return
                        webViewLastInjectedMs[wv] = now
                        mainHandler.postDelayed({
                            try {
                                val js = """
                                    (function(){
                                        try {
                                            if (window.__phantomAdBreakHooked) return;
                                            window.__phantomAdBreakHooked = true;
                                            var wrapAdBreak = function(orig) {
                                                return function(o) {
                                                    if (!o) return;
                                                    setTimeout(function() {
                                                        try { if (typeof o.beforeAd === 'function') o.beforeAd(); } catch(e){}
                                                        try {
                                                            if (typeof o.beforeReward === 'function') {
                                                                o.beforeReward(function(){});
                                                            }
                                                        } catch(e){}
                                                        setTimeout(function() {
                                                            try { if (typeof o.adViewed === 'function') o.adViewed(); } catch(e){}
                                                            try { if (typeof o.afterAd === 'function') o.afterAd(); } catch(e){}
                                                            try {
                                                                if (typeof o.adBreakDone === 'function') {
                                                                    o.adBreakDone({
                                                                        breakType: o.type || 'reward',
                                                                        breakName: o.name || 'default',
                                                                        breakFormat: (o.type === 'reward') ? 'reward' : 'interstitial',
                                                                        breakStatus: 'viewed'
                                                                    });
                                                                }
                                                            } catch(e){}
                                                        }, 30);
                                                    }, 15);
                                                };
                                            };
                                            var curAdBreak = window.adBreak;
                                            window.adBreak = wrapAdBreak(curAdBreak);
                                            try {
                                                Object.defineProperty(window, 'adBreak', {
                                                    configurable: true,
                                                    get: function() { return wrapAdBreak(curAdBreak); },
                                                    set: function(v) { curAdBreak = v; }
                                                });
                                            } catch(e){}
                                        } catch(e){}
                                    })();
                                """.trimIndent()
                                wv.evaluateJavascript(js, null)
                            } catch (_: Throwable) {}
                        }, 20L)
                    }
                }
                hookAllMethodsCompat(wvClass, "loadUrl", injectWebViewAdBypass)
                hookAllMethodsCompat(wvClass, "loadDataWithBaseURL", injectWebViewAdBypass)
            }
        } catch (_: Throwable) {}

        // Hook Activity.onCreate & onResume to catch any AdActivity and scan dynamic SDK classLoaders
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Activity::class.java,
                "onCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? android.app.Activity ?: return
                        val actLoader = act.classLoader
                        if (actLoader != null && hookedAdClasses.add("scanned_act_loader:${actLoader.hashCode()}")) {
                            hookAdMobClassesInLoader(actLoader)
                            hookUnityAdsInLoader(actLoader)
                            hookAppLovinMaxInLoader(actLoader)
                            hookIronSourceAndFacebookInLoader(actLoader)
                        }
                        if (!isAdBypassEnabledNow()) return
                        val clsName = act.javaClass.name
                        if (isKnownAdActivityName(clsName)) {
                            fastForwardAndFinishAdActivity(act)
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                android.app.Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? android.app.Activity ?: return
                        val actLoader = act.classLoader
                        if (actLoader != null && hookedAdClasses.add("scanned_act_loader:${actLoader.hashCode()}")) {
                            hookAdMobClassesInLoader(actLoader)
                            hookUnityAdsInLoader(actLoader)
                            hookAppLovinMaxInLoader(actLoader)
                            hookIronSourceAndFacebookInLoader(actLoader)
                        }
                        if (!isAdBypassEnabledNow()) return
                        val clsName = act.javaClass.name
                        if (isKnownAdActivityName(clsName)) {
                            fastForwardAndFinishAdActivity(act)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }
}

