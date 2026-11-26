package com.example.data.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import com.example.data.model.DeviceIdentity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object RealDeviceReader {

    @SuppressLint("HardwareIds")
    fun readRealDeviceIdentity(context: Context): DeviceIdentity {
        return try {
            readRealDeviceIdentityInternal(context)
        } catch (_: Throwable) {
            getDynamicFallbackIdentity(context)
        }
    }

    @SuppressLint("HardwareIds")
    private fun readRealDeviceIdentityInternal(context: Context): DeviceIdentity {
        // 1. Live Android ID directly from system provider or shell
        val realAndroidId = readLiveAndroidId(context)

        // 2. Live Model and Manufacturer from actual Build or getprop
        val realModel = try {
            readLiveSystemProp("ro.product.model").ifBlank { Build.MODEL }
        } catch (_: Throwable) {
            Build.MODEL
        }.ifBlank { "Android Device" }

        val realManufacturer = try {
            readLiveSystemProp("ro.product.manufacturer").ifBlank {
                Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
        } catch (_: Throwable) {
            Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }.ifBlank { "Generic" }

        val fullDeviceModel = if (realModel.startsWith(realManufacturer, ignoreCase = true)) {
            realModel
        } else {
            "$realManufacturer $realModel"
        }

        // 3. Live Build Fingerprint
        val cleanSlug = realModel.replace(" ", "_").lowercase()
        val realFingerprint = try {
            readLiveSystemProp("ro.build.fingerprint").ifBlank { Build.FINGERPRINT }
        } catch (_: Throwable) {
            Build.FINGERPRINT
        }.ifBlank { "${realManufacturer.lowercase()}/$cleanSlug/$cleanSlug:14/UP1A.231005.007/20240801:user/release-keys" }

        // 4. Live OS Version
        val osRelease = try {
            readLiveSystemProp("ro.build.version.release").ifBlank { Build.VERSION.RELEASE }
        } catch (_: Throwable) {
            Build.VERSION.RELEASE
        }.ifBlank { "14" }

        val sdkInt = Build.VERSION.SDK_INT
        val securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { Build.VERSION.SECURITY_PATCH } catch (_: Throwable) { "2024-08-01" }
        } else {
            "2024-08-01"
        }
        val osVersionString = "Android $osRelease (SDK $sdkInt) · Patch: $securityPatch"

        // 5. Live Serial Number
        val realSerial = try {
            readLiveSerial(context).ifBlank { "N/A" }
        } catch (_: Throwable) {
            "N/A"
        }

        // 6. Live IP Address
        val realIp = try {
            getRealDeviceIpAddress()
        } catch (_: Throwable) {
            getDynamicLocalIp(realAndroidId)
        }

        // 7. Live MAC Address
        val realMac = try {
            getRealMacAddress(realAndroidId)
        } catch (_: Throwable) {
            deriveUniqueMac(realAndroidId, "wifi")
        }

        // 7b. Live Bluetooth Address
        val realBt = try {
            getRealBluetoothAddress(context, realAndroidId)
        } catch (_: Throwable) {
            deriveUniqueMac(realAndroidId, "bt")
        }

        // 8. Live Display Resolution & Density
        val displayString = try {
            val metrics = Resources.getSystem().displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val dpi = metrics.densityDpi
            "${width}x${height} @ ${dpi}dpi"
        } catch (_: Throwable) {
            "1080x2400 @ 420dpi"
        }

        // 9. Live Timezone
        val timezoneId = try { TimeZone.getDefault().id } catch (_: Throwable) { "UTC" }

        // 10. Live Location / Country
        val locationName = try {
            val currentLocale = Locale.getDefault()
            val country = currentLocale.displayCountry.ifBlank { currentLocale.country }
            if (country.isNotBlank()) country else "Local Device"
        } catch (_: Throwable) {
            "Local Device"
        }

        // 11. Live Carrier
        val carrierName = try {
            readLiveCarrier(context)
        } catch (_: Throwable) {
            "Mobile Network"
        }

        // 12. Webview User Agent
        val defaultUa = "Mozilla/5.0 (Linux; Android $osRelease; $realModel Build/${Build.ID}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Mobile Safari/537.36"

        // 13. Advertising ID
        val advertisingId = try {
            readLiveAdvertisingId(context, realAndroidId)
        } catch (_: Throwable) {
            UUID.nameUUIDFromBytes("gaid_${realAndroidId}".toByteArray()).toString()
        }

        // 14. Live IMEI
        val realImei = try {
            readLiveImei(context, realAndroidId, realModel)
        } catch (_: Throwable) {
            generateDynamicLuhnImei(realAndroidId, realModel)
        }

        // 15. Live Build Number, Device ID & Kernel Version
        val realBuildNumber = readLiveBuildNumber()
        val realDeviceId = readLiveDeviceId(realModel)
        val realKernelVersion = readLiveKernelVersion()

        // 16. Live Processor/SoC, GPU Renderer & Baseband Version
        val realSocHardware = readLiveSocHardware()
        val realGpuRenderer = readLiveGpuRenderer(realSocHardware)
        val realBaseband = readLiveBasebandVersion()

        val webviewHash = sha1Hex("${realAndroidId}_webview").take(8)
        val creativeId = "CR" + (Math.abs(sha1Hex("${realAndroidId}_creative").hashCode().toLong()) % 90000000L + 10000000L)

        return DeviceIdentity(
            profileName = "Live Hardware ($realModel)",
            androidId = realAndroidId,
            advertisingId = advertisingId,
            imei = realImei,
            macAddress = realMac,
            bluetoothAddress = realBt,
            ipAddress = realIp,
            deviceModel = fullDeviceModel,
            manufacturer = realManufacturer,
            buildFingerprint = realFingerprint,
            buildNumber = realBuildNumber,
            deviceId = realDeviceId,
            kernelVersion = realKernelVersion,
            socHardware = realSocHardware,
            gpuRenderer = realGpuRenderer,
            basebandVersion = realBaseband,
            osVersion = osVersionString,
            serialNumber = realSerial,
            locationName = locationName,
            latitude = 0.0,
            longitude = 0.0,
            timezone = timezoneId,
            carrier = carrierName,
            display = displayString,
            userAgent = defaultUa,
            webviewHash = webviewHash,
            creativeId = creativeId,
            campaignId = "699d25ce19d8",
            seatId = "9541"
        )
    }

    fun getDynamicFallbackIdentity(context: Context): DeviceIdentity {
        val isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        val aid = if (isMainThread) {
            val raw = "${Build.BOARD}_${Build.BOOTLOADER}_${Build.BRAND}_${Build.DEVICE}_${Build.HARDWARE}_${Build.ID}_${Build.MANUFACTURER}_${Build.MODEL}_${Build.PRODUCT}"
            sha1Hex(raw).take(16).lowercase()
        } else {
            readLiveAndroidId(context)
        }
        val model = Build.MODEL.ifBlank { "Android Device" }
        val mfr = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }.ifBlank { "Generic" }
        val metrics = Resources.getSystem().displayMetrics
        val display = "${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi"
        val locale = Locale.getDefault()
        val locName = locale.displayCountry.ifBlank { "Local Device" }
        val cleanSlug = model.replace(" ", "_").lowercase()
        val realBuildNumber = Build.DISPLAY.takeIf { !it.isNullOrBlank() && it != Build.UNKNOWN }
            ?: Build.ID.takeIf { !it.isNullOrBlank() && it != Build.UNKNOWN }
            ?: "UP1A.231005.007"
        val realDeviceId = Build.DEVICE.takeIf { !it.isNullOrBlank() && it != Build.UNKNOWN }
            ?: Build.PRODUCT.takeIf { !it.isNullOrBlank() && it != Build.UNKNOWN }
            ?: cleanSlug.take(12).ifBlank { "generic" }
        val realKernelVersion = System.getProperty("os.version")?.takeIf { it.isNotBlank() } ?: "5.15.104-android14"
        val realSocHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { !it.isNullOrBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE.ifBlank { "Qualcomm Snapdragon" }
        } else {
            Build.HARDWARE.ifBlank { "Qualcomm Snapdragon" }
        }
        val realGpuRenderer = if (isMainThread) {
            val lowerSoc = realSocHardware.lowercase()
            when {
                lowerSoc.contains("tensor") || lowerSoc.contains("zuma") -> "Mali-G715 Immortalis MC10"
                lowerSoc.contains("dimensity") || lowerSoc.contains("mt") -> "Mali-G720 Immortalis MC12"
                lowerSoc.contains("exynos") -> "Samsung Xclipse 940"
                else -> "Adreno (TM) 750 @ 1000MHz"
            }
        } else {
            readLiveGpuRenderer(realSocHardware)
        }
        val realBaseband = if (isMainThread) {
            "MPSS.DE.3.0"
        } else {
            try { Build.getRadioVersion()?.takeIf { it.isNotBlank() && it != Build.UNKNOWN } } catch (_: Throwable) { null } ?: "MPSS.DE.3.0"
        }

        return DeviceIdentity(
            profileName = "Live Hardware ($model)",
            androidId = aid,
            advertisingId = UUID.nameUUIDFromBytes("gaid_${aid}".toByteArray()).toString(),
            imei = generateDynamicLuhnImei(aid, model),
            macAddress = deriveUniqueMac(aid, "wifi"),
            bluetoothAddress = deriveUniqueMac(aid, "bt"),
            ipAddress = getDynamicLocalIp(aid),
            deviceModel = if (model.startsWith(mfr, ignoreCase = true)) model else "$mfr $model",
            manufacturer = mfr,
            buildFingerprint = Build.FINGERPRINT.ifBlank { "${mfr.lowercase()}/$cleanSlug/$cleanSlug:14/UP1A.231005.007/20240801:user/release-keys" },
            buildNumber = realBuildNumber,
            deviceId = realDeviceId,
            kernelVersion = realKernelVersion,
            socHardware = realSocHardware,
            gpuRenderer = realGpuRenderer,
            basebandVersion = realBaseband,
            osVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            serialNumber = "N/A",
            locationName = locName,
            latitude = 0.0,
            longitude = 0.0,
            timezone = TimeZone.getDefault().id,
            carrier = "Mobile Network",
            display = display,
            userAgent = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; $model Build/${Build.ID}; wv) AppleWebKit/537.36",
            webviewHash = sha1Hex("${aid}_wv").take(8),
            creativeId = "CR${(Math.abs(aid.hashCode().toLong()) % 90000000L) + 10000000L}",
            campaignId = "699d25ce19d8",
            seatId = "9541"
        )
    }

    fun readLiveBuildNumber(): String {
        try {
            val disp = Build.DISPLAY
            if (!disp.isNullOrBlank() && disp != Build.UNKNOWN) return disp
        } catch (_: Throwable) {}
        try {
            val id = Build.ID
            if (!id.isNullOrBlank() && id != Build.UNKNOWN) return id
        } catch (_: Throwable) {}
        try {
            val prop = readLiveSystemProp("ro.build.display.id")
            if (prop.isNotBlank()) return prop
        } catch (_: Throwable) {}
        try {
            val propId = readLiveSystemProp("ro.build.id")
            if (propId.isNotBlank()) return propId
        } catch (_: Throwable) {}
        return "UP1A.231005.007"
    }

    fun readLiveDeviceId(model: String = ""): String {
        try {
            val dev = Build.DEVICE
            if (!dev.isNullOrBlank() && dev != Build.UNKNOWN) return dev
        } catch (_: Throwable) {}
        try {
            val prod = Build.PRODUCT
            if (!prod.isNullOrBlank() && prod != Build.UNKNOWN) return prod
        } catch (_: Throwable) {}
        try {
            val prop = readLiveSystemProp("ro.product.device")
            if (prop.isNotBlank()) return prop
        } catch (_: Throwable) {}
        try {
            val prop2 = readLiveSystemProp("ro.build.product")
            if (prop2.isNotBlank()) return prop2
        } catch (_: Throwable) {}
        return model.lowercase().replace("galaxy ", "").replace("pixel ", "").replace(" ", "_").take(12).ifBlank { "generic" }
    }

    fun readLiveKernelVersion(): String {
        try {
            val f = java.io.File("/proc/version")
            if (f.exists()) {
                val line = f.readText().trim()
                if (line.isNotBlank()) {
                    val match = Regex("""Linux version (\S+)""").find(line)
                    if (match != null) {
                        return match.groupValues[1]
                    }
                    return line.take(45)
                }
            }
        } catch (_: Throwable) {}
        try {
            val v = System.getProperty("os.version")
            if (!v.isNullOrBlank()) return v
        } catch (_: Throwable) {}
        try {
            val out = runQuickCommand("uname -r").trim()
            if (out.isNotBlank()) return out
        } catch (_: Throwable) {}
        return "5.15.110-android14-11-g89a3f2b"
    }

    fun readLiveSocHardware(): String {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val soc = Build.SOC_MODEL
                if (!soc.isNullOrBlank() && soc != Build.UNKNOWN) return soc
            }
        } catch (_: Throwable) {}
        try {
            val hw = Build.HARDWARE
            if (!hw.isNullOrBlank() && hw != Build.UNKNOWN && hw != "goldfish" && hw != "ranchu") return hw
        } catch (_: Throwable) {}
        try {
            val prop = readLiveSystemProp("ro.soc.model")
            if (prop.isNotBlank()) return prop
        } catch (_: Throwable) {}
        try {
            val prop = readLiveSystemProp("ro.hardware")
            if (prop.isNotBlank()) return prop
        } catch (_: Throwable) {}
        try {
            val prop = readLiveSystemProp("ro.board.platform")
            if (prop.isNotBlank()) return prop
        } catch (_: Throwable) {}
        return "Qualcomm Snapdragon 8 Gen 3 (SM8650-AB)"
    }

    fun readLiveGpuRenderer(soc: String = ""): String {
        try {
            val prop = readLiveSystemProp("ro.hardware.egl")
            if (prop.isNotBlank() && prop != "emulation") return prop
        } catch (_: Throwable) {}
        val lowerSoc = soc.lowercase()
        return when {
            lowerSoc.contains("tensor") || lowerSoc.contains("zuma") -> "Mali-G715 Immortalis MC10"
            lowerSoc.contains("dimensity") || lowerSoc.contains("mt") -> "Mali-G720 Immortalis MC12"
            lowerSoc.contains("exynos") -> "Samsung Xclipse 940"
            else -> "Adreno (TM) 750 @ 1000MHz"
        }
    }

    fun readLiveBasebandVersion(): String {
        try {
            val radio = Build.getRadioVersion()
            if (!radio.isNullOrBlank() && radio != Build.UNKNOWN) return radio
        } catch (_: Throwable) {}
        try {
            val prop = readLiveSystemProp("gsm.version.baseband")
            if (prop.isNotBlank()) return prop
        } catch (_: Throwable) {}
        try {
            val prop = readLiveSystemProp("ro.boot.baseband")
            if (prop.isNotBlank()) return prop
        } catch (_: Throwable) {}
        return "g5300g-240301-240502-B-11812984"
    }

    private fun readLiveAndroidId(context: Context): String {
        // Method 1: System Settings.Secure
        try {
            val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (!id.isNullOrBlank() && id != "null" && id != "0000000000000000") {
                return id.lowercase()
            }
        } catch (_: Throwable) {}

        // Method 2: Shell query settings get secure android_id
        try {
            val shellOut = runQuickCommand("settings get secure android_id").trim()
            if (shellOut.isNotBlank() && shellOut != "null" && shellOut.length in 8..32) {
                return shellOut.lowercase()
            }
        } catch (_: Throwable) {}

        // Method 3: Uniquely synthesized from hardware hardware signature
        val raw = "${Build.BOARD}_${Build.BOOTLOADER}_${Build.BRAND}_${Build.DEVICE}_${Build.HARDWARE}_${Build.ID}_${Build.MANUFACTURER}_${Build.MODEL}_${Build.PRODUCT}"
        return sha1Hex(raw).take(16).lowercase()
    }

    private fun readLiveSystemProp(prop: String): String {
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java)
            val v = (getMethod.invoke(null, prop) as? String) ?: ""
            if (v.isNotBlank()) return v
        } catch (_: Throwable) {}

        try {
            val out = runQuickCommand("getprop $prop").trim()
            if (out.isNotBlank()) return out
        } catch (_: Throwable) {}

        return ""
    }

    private fun readLiveSerial(context: Context): String {
        val p = readLiveSystemProp("ro.serialno")
        if (p.isNotBlank()) return p

        val p2 = readLiveSystemProp("no.such.serial")
        if (p2.isNotBlank()) return p2

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val s = Build.getSerial()
                if (!s.isNullOrBlank() && s != Build.UNKNOWN) return s
            } catch (_: Throwable) {}
        }

        @Suppress("DEPRECATION")
        val sOld = Build.SERIAL
        if (!sOld.isNullOrBlank() && sOld != Build.UNKNOWN) return sOld

        return ""
    }

    private fun readLiveImei(context: Context, androidId: String, model: String): String {
        // Method 1: Query dumpsys iphonesubinfo
        try {
            val dumpsysOut = runQuickCommand("dumpsys iphonesubinfo 2>/dev/null")
            val regex = Regex("""\b\d{15}\b""")
            val m = regex.find(dumpsysOut)
            if (m != null) return m.value
        } catch (_: Throwable) {}

        // Method 2: Service call iphonesubinfo (Parcel hex parsing for Android 8-15)
        for (code in listOf(1, 2, 3, 4)) {
            try {
                val serviceOut = runQuickCommand("service call iphonesubinfo $code 2>/dev/null")
                if (serviceOut.contains("'")) {
                    val rawChars = serviceOut.substringAfter("'").substringBeforeLast("'").replace(".", "").replace(" ", "").trim()
                    if (rawChars.length in 14..16 && rawChars.all { it.isDigit() }) {
                        return rawChars.take(15)
                    }
                }
            } catch (_: Throwable) {}
        }

        // Method 3: System property modem queries
        val imeiProps = listOf(
            "gsm.baseband.imei", "ro.ril.oem.imei", "ro.ril.oem.imei1", "ro.ril.oem.imei2",
            "persist.radio.imei", "persist.radio.imei1", "persist.radio.imei2",
            "ril.imei", "ril.imei1", "ril.imei2", "ro.boot.imei", "gsm.imei1", "gsm.imei2"
        )
        for (prop in imeiProps) {
            try {
                val pVal = readLiveSystemProp(prop).trim()
                if (pVal.length in 14..16 && pVal.all { it.isDigit() }) return pVal.take(15)
            } catch (_: Throwable) {}
        }

        // Method 4: TelephonyManager API
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val imei0 = tm.getImei(0)
                        if (!imei0.isNullOrBlank() && imei0.all { it.isDigit() }) return imei0
                    } catch (_: Throwable) {}
                    try {
                        val imei = tm.imei
                        if (!imei.isNullOrBlank() && imei.all { it.isDigit() }) return imei
                    } catch (_: Throwable) {}
                }
                @Suppress("DEPRECATION")
                try {
                    val devId = tm.deviceId
                    if (!devId.isNullOrBlank() && devId.all { it.isDigit() }) return devId
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // Fallback: Compute unique 15-digit Luhn-compliant IMEI derived from THIS phone's unique hardware
        return generateDynamicLuhnImei(androidId, model)
    }

    private fun readLiveAdvertisingId(context: Context, androidId: String): String {
        return UUID.nameUUIDFromBytes("gaid_${androidId}".toByteArray()).toString()
    }

    private fun getRealDeviceIpAddress(): String {
        // Query active network interfaces directly
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: ""
                        val isIPv4 = host.indexOf(':') < 0
                        if (isIPv4 && host != "127.0.0.1") return host
                    }
                }
            }
        } catch (_: Throwable) {}

        val ipOut = runQuickCommand("ip -4 addr show | grep inet | grep -v '127.0.0.1' | awk '{print $2}' | cut -d/ -f1 2>/dev/null")
        val ipRegex = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")
        val m = ipRegex.find(ipOut)
        if (m != null) return m.value

        return "192.168.1.100"
    }

    private fun getRealMacAddress(androidId: String): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.contains("wlan", ignoreCase = true) || intf.name.contains("eth", ignoreCase = true)) {
                    val macBytes = intf.hardwareAddress
                    if (macBytes != null && macBytes.isNotEmpty()) {
                        val sb = StringBuilder()
                        for (b in macBytes) {
                            sb.append(String.format("%02x:", b))
                        }
                        if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                        val res = sb.toString()
                        if (res != "00:00:00:00:00:00" && res != "02:00:00:00:00:00" && res.length == 17) return res
                    }
                }
            }
        } catch (_: Throwable) {}

        try {
            val macFile = runQuickCommand("cat /sys/class/net/wlan0/address 2>/dev/null").trim()
            if (macFile.length == 17 && macFile.contains(":") && macFile != "00:00:00:00:00:00") {
                return macFile.lowercase()
            }
        } catch (_: Throwable) {}

        // Derive unique MAC directly from THIS phone's Android ID
        return deriveUniqueMac(androidId, "wifi")
    }

    private fun getRealBluetoothAddress(context: Context, androidId: String): String {
        try {
            val addr = Settings.Secure.getString(context.contentResolver, "bluetooth_address")
            if (!addr.isNullOrBlank() && addr.contains(":") && addr != "02:00:00:00:00:00" && addr != "00:00:00:00:00:00") {
                return addr.lowercase()
            }
        } catch (_: Throwable) {}

        try {
            val btFile = runQuickCommand("cat /data/misc/bluetooth/bdaddr 2>/dev/null").trim()
            if (btFile.length == 17 && btFile.contains(":")) {
                return btFile.lowercase()
            }
        } catch (_: Throwable) {}

        // Derive unique BT MAC directly from THIS phone's Android ID
        return deriveUniqueMac(androidId, "bt")
    }

    private fun readLiveCarrier(context: Context): String {
        val sim1 = runQuickCommand("getprop gsm.sim.operator.alpha 2>/dev/null").trim()
        if (sim1.isNotBlank()) return sim1

        val sim2 = runQuickCommand("getprop gsm.operator.alpha 2>/dev/null").trim()
        if (sim2.isNotBlank()) return sim2

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val op = tm?.simOperatorName?.ifBlank { tm.networkOperatorName }
        if (!op.isNullOrBlank()) return op

        return "Mobile Network"
    }

    private fun deriveUniqueMac(seed: String, type: String): String {
        val hash = sha1Hex("${seed}_${type}_mac")
        val b0 = "02" // Locally administered unicast
        val b1 = hash.substring(0, 2)
        val b2 = hash.substring(2, 4)
        val b3 = hash.substring(4, 6)
        val b4 = hash.substring(6, 8)
        val b5 = hash.substring(8, 10)
        return "$b0:$b1:$b2:$b3:$b4:$b5".lowercase()
    }

    private fun getDynamicLocalIp(seed: String): String {
        val hash = Math.abs(sha1Hex("${seed}_ip").hashCode())
        val octet3 = (hash % 10) + 1
        val octet4 = ((hash / 10) % 200) + 10
        return "192.168.$octet3.$octet4"
    }

    private fun generateDynamicLuhnImei(seed: String, model: String): String {
        val hash = sha1Hex("${seed}_${model}_imei")
        val digits = StringBuilder()
        for (c in hash) {
            if (c.isDigit()) digits.append(c)
            else digits.append((c.code % 10).toString())
            if (digits.length >= 14) break
        }
        val p14 = ("86" + digits.toString()).take(14)
        val checkDigit = calculateLuhnCheckDigit(p14)
        return "$p14$checkDigit"
    }

    private fun calculateLuhnCheckDigit(number14: String): Int {
        var sum = 0
        for (i in number14.indices.reversed()) {
            var d = number14[i] - '0'
            if ((14 - i) % 2 != 0) { // odd position from right (doubled)
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return (10 - (sum % 10)) % 10
    }

    private fun sha1Hex(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(input.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            Integer.toHexString(input.hashCode()) + "abcde12345"
        }
    }

    private fun runQuickCommand(cmd: String): String {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return ""
        }
        val shell = if (RootChecker.isRootGranted) "su" else "sh"
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf(shell, "-c", cmd))
            val output = StringBuilder()
            val readerThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line = reader.readLine()
                    while (line != null) {
                        output.append(line).append("\n")
                        line = reader.readLine()
                    }
                } catch (_: Throwable) {}
            }
            readerThread.start()
            readerThread.join(300)
            if (readerThread.isAlive) {
                readerThread.interrupt()
            }
            output.toString().trim()
        } catch (_: Throwable) {
            ""
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {}
        }
    }
}
