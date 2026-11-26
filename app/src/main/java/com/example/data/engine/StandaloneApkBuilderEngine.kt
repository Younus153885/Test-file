package com.example.data.engine

import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StrictMode
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import android.util.Base64
import com.example.data.device.RootChecker
import com.example.data.model.DeviceIdentity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

data class StandaloneApkArtifact(
    val originalPackageName: String,
    val standalonePackageName: String,
    val appName: String,
    val outputFilePath: String,
    val publicDownloadPath: String,
    val mediaStoreUriString: String = "",
    val fileSizeBytes: Long,
    val builtTimestampMs: Long,
    val bakedDeviceModel: String,
    val bakedAndroidId: String,
    val bakedSpeedMultiplier: Float,
    val bakedSpeedEnabled: Boolean = true,
    val bakedStepWalkEnabled: Boolean = true,
    val bakedRootBypassEnabled: Boolean = true,
    val bakedAdBypassEnabled: Boolean = false,
    val bakedIdentitySpoofEnabled: Boolean = true,
    val bakedReplaceMainApk: Boolean = false,
    val bakedLanguageCode: String = com.example.ui.i18n.LanguageManager.currentLang.code,
    val isBuilding: Boolean = false,
    val progressPercent: Int = 0,
    val statusMessage: String = com.example.ui.i18n.tr(
        "Ready to Install",
        "প্রস্তুত (Ready to Install)",
        "इंस्टॉल के लिए तैयार (Ready to Install)"
    )
)

data class LiveBuildToggleSnapshot(
    val speedMultiplier: Float,
    val speedEnabled: Boolean,
    val stepWalkEnabled: Boolean,
    val rootBypassEnabled: Boolean,
    val identitySpoofEnabled: Boolean,
    val adBypassEnabled: Boolean,
    val liveLatitude: Double,
    val liveLongitude: Double,
    val locationName: String
)

data class StandaloneInstallUiState(
    val originalPackageName: String,
    val standalonePackageName: String,
    val appName: String,
    val fileSizeBytes: Long,
    val progressPercent: Int,
    val stageTitle: String,
    val stageDetail: String,
    val isInstalling: Boolean = true,
    val isCompleted: Boolean = false,
    val isError: Boolean = false,
    val bakedDeviceModel: String = "",
    val activeFeaturesSummary: String = "",
    val canSwitchToCloneMode: Boolean = false
)

object StandaloneApkBuilderEngine {

    const val BUILDER_VERSION = "v87"

    fun isYouTubePackage(pkg: String): Boolean =
        pkg.trim().equals("com.google.android.youtube", ignoreCase = true)

    fun isGoogleFirstPartyPackage(pkg: String): Boolean {
        val p = pkg.trim().lowercase(Locale.US)
        if (p.isEmpty()) return false
        return p == "com.google.android.youtube" || p.startsWith("com.google.android.youtube.") ||
            p == "com.android.vending" || p.startsWith("com.android.vending.") ||
            p == "com.google.android.gm" || p.startsWith("com.google.android.gm.") ||
            p.startsWith("com.google.android.apps.youtube.") ||
            p == "com.google.android.apps.maps" || p.startsWith("com.google.android.apps.maps.") ||
            p == "com.google.android.apps.photos" || p.startsWith("com.google.android.apps.photos.") ||
            p == "com.google.android.apps.docs" || p.startsWith("com.google.android.apps.docs.") ||
            p == "com.google.android.play.games" || p.startsWith("com.google.android.play.games.") ||
            p == "com.google.android.googlequicksearchbox" ||
            p == "com.google.android.calendar" ||
            p == "com.google.android.keep" ||
            p == "com.google.android.apps.meetings" ||
            p == "com.google.android.apps.tachyon" ||
            p == "com.google.android.apps.translate" ||
            p == "com.google.android.videos" ||
            p == "com.google.android.apps.books" ||
            p == "com.google.android.contacts" ||
            p == "com.google.android.apps.nbu.files"
    }

    private val _installUiState = kotlinx.coroutines.flow.MutableStateFlow<StandaloneInstallUiState?>(null)
    val installUiState: kotlinx.coroutines.flow.StateFlow<StandaloneInstallUiState?> = _installUiState

    @Volatile
    private var activeInstallMonitorToken: Long = 0L

    fun dismissInstallUiState() {
        activeInstallMonitorToken = 0L
        _installUiState.value = null
    }

    // Per-package lock to prevent concurrent rebuild coroutines from colliding on the same APK
    private val packageBuildLocks = ConcurrentHashMap<String, Any>()
    private val buildSequence = AtomicLong(1L)
    private val stalePurgedOnce = AtomicBoolean(false)

    // Deterministic 2048-bit RSA PKCS#8 Private Key & X.509 Certificate for V1 + V2 APK Signing
    private const val SIGNER_PRIVATE_KEY_B64 =
        "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC3BweE0PjT7MY8LF+dwwnQgAmzeZkfrhpaPcLm3MhinQVd4CauDgCYB7dG802C8EJvis8iEh25H3MYkD4K0p7J2kP7kret5GPQFWU0k15pWtGpxocQuUgb5efSVFMOoH/eW4E8E9WAwLiIWO4D3odd+1+DuadJZT43+3iD7zfaLbIEYz23tjbEuBNQW0m56HJmlaF7bvjmVvlmXsRR7dkrkT/vFAbNjVnCpgSw8un38Bnn8OWJ2UAxzJaOM0jPdWDC0tXuzInyy+O6A61U9V0qPWDu30n90GmYKrwjTRjBmZ1lMjpY7l0wJqpTxr+5k61s2HZMsiwGHGGLy46zVeVlAgMBAAECggEAFJ7CnpiKiBnDZbEcHqKEHwzlyuYWtcaAKo2NwUO7gbyuB5sC0JY8Q+SOpfwwTeGyom1xk6WV2q7r4ScTDlYXVKd2BEhN0FYMVruqWMeHmfgE6gs3WU9+i8IVoZElgF7duRf3EeUsJyLPR3E5c48lEhYJd0Y5zh+NaHI47ChN0xMlDYhYMX+ciqYB0CjL9BycIos0JwB3d+Uw8cDOMsn8eXIy4AsCRUFhsTgTara6CR8EY4c8s1Q1FqkDU1uU7+qVP7i+3+TmAYnimv5KaoU0I/J24euszJpNKq0Lr2OJEFweLhYB+mNkdvHDsajlhOEZSkIFjNYfzuC0jXK929oaCwKBgQDg0GhYi7vZV4IAFi3f2IOnClB2PyGfqwT7xETOSj1vSEFQJorTjtet1c0QhQIwSSDmxKllhLnqmTwfhsyMTfW23JpQH6OWFil5ha7YZwsWOQD9RrpJXxNLM2mYXpk+vI4uqlFjlAUd5xjtXFOzXqTfR5VdJ+0PHww7G27emyf+8wKBgQDQarIlTYwSfdogZZUzNL4nFGIzJqU5vLFlZAwg7ujeILo/NVKz0QmCk1C5IfeZW0huAlYs+n+n4IFHdqqqbK1xMz6fIdfOc/snEQWKTfyhXIZDgKsoYQ3O6LVWuJO60ZFi1Jkrflu4urex7R0xxiJftANKjlcB28Jz7OBB0TUQRwKBgQCvPEPZoTHPfBJtFtyzOwWD0tpdEWCYIKpFxPsNAXlZoKyoWSKlPRaI/bpWQhcupBHtTyXrZ8WzQ7ZFrMEMLEh3PQVbpw3QE35IpxdevxM5Qz2uLs9OGbnkSDLq/fHdoQQDA+dlAx/p6xTXD7x+eCtzj0yxzElQHBdgi0WkZrSKWQKBgQDMi+rLMZCHlXy+zvPfjaQSNq8qBIOkZsyz9bzxlYh2eShRYMQKNt5eiLZw/CVFHZG4ZhKtkiw3Vt+Kg6x8Fq0irGiJRW3H8YxMiygk1OVceLJNDbrtplMm/rz6n6xdbdCCbqTlSt46LwaaWVyNvSd/Rez0/z3lVQlDPPfboLezgQKBgFt5GQ/rj4Cj/ruhd6jfgRi4S7UoArbEQJNkuGYQB5UXjsf1ejEWCd7WRUUxjRuUOhLOyMGvBtSHk+At8jVFBAldKkEAkwd52jb/HbGymarBd8K0hNJ9lZOgeLi9guKotpSBAxtgkrm+zH37uZD+ULQOYNGu+15+dOG7nZpIHRqF"

    private const val SIGNER_CERT_B64 =
        "MIIDxzCCAq+gAwIBAgIUYnPMDY7i+WnK5RF+W1BAKfScsaYwDQYJKoZIhvcNAQELBQAwcjEfMB0GA1UEAwwWUGhhbnRvbUNvcmUgU3RhbmRhbG9uZTEPMA0GA1UECwwGTW9iaWxlMRQwEgYDVQQKDAtQaGFudG9tQ29yZTEOMAwGA1UEBwwFRGhha2ExCzAJBgNVBAgMAkJEMQswCQYDVQQGEwJCRDAgFw0yNjEwMjQxNTQ1MThaGA8yMDU0MDMxMTE1NDUxOFowcjEfMB0GA1UEAwwWUGhhbnRvbUNvcmUgU3RhbmRhbG9uZTEPMA0GA1UECwwGTW9iaWxlMRQwEgYDVQQKDAtQaGFudG9tQ29yZTEOMAwGA1UEBwwFRGhha2ExCzAJBgNVBAgMAkJEMQswCQYDVQQGEwJCRDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALcHB4TQ+NPsxjwsX53DCdCACbN5mR+uGlo9wubcyGKdBV3gJq4OAJgHt0bzTYLwQm+KzyISHbkfcxiQPgrSnsnaQ/uSt63kY9AVZTSTXmla0anGhxC5SBvl59JUUw6gf95bgTwT1YDAuIhY7gPeh137X4O5p0llPjf7eIPvN9otsgRjPbe2NsS4E1BbSbnocmaVoXtu+OZW+WZexFHt2SuRP+8UBs2NWcKmBLDy6ffwGefw5YnZQDHMlo4zSM91YMLS1e7MifLL47oDrVT1XSo9YO7fSf3QaZgqvCNNGMGZnWUyOljuXTAmqlPGv7mTrWzYdkyyLAYcYYvLjrNV5WUCAwEAAaNTMFEwHQYDVR0OBBYEFME0VyDgGcy7dyBFzHxXr7FRUgosMB8GA1UdIwQYMBaAFME0VyDgGcy7dyBFzHxXr7FRUgosMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAKG+trj8yoXD6kPK3CRXU8R3izJF70+kRP7P5QZtJ9rK9QleMtqKMYpg9tf9V6yzIdMtFm+qF8QVTOUFxKBa7L23Y9xGGyiNX4llBzbkUl2GYmygmkt8h/Mw95vh8TiVSsj6wYu3z3K04PSxrUHcFW+nsjxegMlwWw1oG6UbXp1DG2mCl87tKoNuh+gwIBXMv4LneCHxV2qmYOR+yQJKUvirXuCF+rn8WGb7p1z1qSbPdItZBvVxTB5VDvsDHY0lGCCwHlreBuB0wKf56KuaZrM02xf1BvnaVxfzuQhX9tMOMyBPEZeMG3xFEuuHRxfIF3/7KPyaaM77CYls9b5AQ5o="

    private val SIGNER_CERT_SHA256_HEX: String by lazy {
        try {
            val certBytes = Base64.decode(SIGNER_CERT_B64, Base64.DEFAULT)
            val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
            digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
        } catch (_: Throwable) {
            ""
        }
    }

    private const val ACTION_INSTALL_COMMIT = "com.example.standalone.ACTION_INSTALL_COMMIT"

    fun computeIdentityShortHash(identity: DeviceIdentity): String {
        val raw = buildString {
            append(identity.androidId).append('|')
            append(identity.imei).append('|')
            append(identity.serialNumber).append('|')
            append(identity.deviceModel).append('|')
            append(identity.buildFingerprint).append('|')
            append(identity.macAddress).append('|')
            append(identity.widevineDrmId).append('|')
            append(identity.advertisingId)
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
            val hex = StringBuilder(6)
            for (i in 0 until 3) {
                hex.append(String.format(Locale.US, "%02x", digest[i].toInt() and 0xFF))
            }
            hex.toString()
        } catch (_: Throwable) {
            val h = kotlin.math.abs(raw.hashCode())
            String.format(Locale.US, "%06x", h and 0xFFFFFF)
        }
    }

    fun deriveStandalonePackageName(
        originalPkg: String,
        identity: DeviceIdentity? = null,
        multiInstallEnabled: Boolean = false,
        replaceMainApkEnabled: Boolean = false
    ): String {
        val clean = originalPkg.trim()
        val unwrapped = clean
            .removeSuffix(".ghostdevice")
            .removeSuffix(".phantom")
        if (!multiInstallEnabled && replaceMainApkEnabled) {
            return unwrapped
        }
        val basePkg = "$unwrapped.ghostdevice"
        if (multiInstallEnabled && identity != null) {
            val idTag = computeIdentityShortHash(identity)
            return "$basePkg.id$idTag"
        }
        return basePkg
    }

    private fun buildVariantFileSuffix(
        multiInstallEnabled: Boolean,
        replaceMainApkEnabled: Boolean,
        identity: DeviceIdentity
    ): String = when {
        multiInstallEnabled -> "_id${computeIdentityShortHash(identity)}"
        replaceMainApkEnabled -> "_replace"
        else -> ""
    }

    fun getStandaloneOutputDir(context: Context): File {
        val dir = File(context.filesDir, "standalone_apks")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getWorkingTempDir(context: Context): File {
        val candidates = listOfNotNull(
            context.externalCacheDir,
            context.cacheDir,
            context.filesDir
        )
        var bestDir = context.cacheDir
        var maxSpace = -1L
        for (dir in candidates) {
            try {
                if (!dir.exists()) dir.mkdirs()
                val space = dir.usableSpace
                if (space > maxSpace && dir.canWrite()) {
                    maxSpace = space
                    bestDir = dir
                }
            } catch (_: Throwable) {}
        }
        return bestDir
    }

    /**
     * Deletes any cached or stale standalone APKs built by older builder versions
     * so the user never installs a stale package.
     */
    fun purgeStaleStandaloneApks(context: Context) {
        try {
            val outDir = getStandaloneOutputDir(context)
            outDir.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.contains("_${BUILDER_VERSION}_") && !f.name.endsWith(".tmp")) {
                    try { f.delete() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        try {
            val pubDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PhantomStandaloneAPKs"
            )
            pubDir.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.contains("_${BUILDER_VERSION}_") && !f.name.endsWith(".tmp")) {
                    try { f.delete() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Checks if a valid, non-empty standalone APK artifact for the current BUILDER_VERSION already
     * exists on disk so app scanning or startup restores it immediately without triggering an automatic rebuild.
     */
    fun findExistingBuiltArtifact(
        context: Context,
        targetPackageName: String,
        appName: String,
        identity: DeviceIdentity,
        speedMultiplier: Float,
        speedEnabled: Boolean,
        stepWalkEnabled: Boolean,
        rootBypassEnabled: Boolean,
        multiInstallEnabled: Boolean,
        adBypassEnabled: Boolean = false,
        identitySpoofEnabled: Boolean = true,
        replaceMainApkEnabled: Boolean = false
    ): StandaloneApkArtifact? {
        return try {
            val outDir = getStandaloneOutputDir(context)
            val effectiveReplaceMain = !multiInstallEnabled && replaceMainApkEnabled
            val idFileSuffix = buildVariantFileSuffix(multiInstallEnabled, effectiveReplaceMain, identity)
            val safeFileName = "${targetPackageName.replace('.', '_')}${idFileSuffix}_${BUILDER_VERSION}_standalone.apk"
            val internalFile = File(outDir, safeFileName)
            val cleanLabel = appName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { targetPackageName }
            val publicFileName = "${cleanLabel}${idFileSuffix}_GhostDevice_${BUILDER_VERSION}.apk"
            val legacyPublicFileName = "${cleanLabel}${idFileSuffix}_Phantom_${BUILDER_VERSION}.apk"
            val publicDownloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "GhostDeviceAPKs"
            )
            val legacyDownloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PhantomStandaloneAPKs"
            )
            val publicFile = File(publicDownloadDir, publicFileName)
            val legacyPublicFile = File(legacyDownloadDir, legacyPublicFileName)

            val validFile = when {
                internalFile.exists() && internalFile.length() > 10_000L -> internalFile
                publicFile.exists() && publicFile.length() > 10_000L -> publicFile
                legacyPublicFile.exists() && legacyPublicFile.length() > 10_000L -> legacyPublicFile
                else -> null
            } ?: return null

            // Read baked metadata from assets/phantom_standalone_config.json to verify ZIP integrity
            // without forcing automatic rebuilds whenever toggles, identity, or UI language change
            // (live toggles & identity sync directly from GhostDevice host provider at runtime).
            var bakedCfg: JSONObject? = null
            val hasValidConfig = try {
                ZipFile(validFile).use { zip ->
                    val cfgEntry = zip.getEntry("assets/phantom_standalone_config.json") ?: return@use false
                    val cfgStr = zip.getInputStream(cfgEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    bakedCfg = JSONObject(cfgStr)
                    true
                }
            } catch (_: Throwable) {
                false
            }
            if (!hasValidConfig || bakedCfg == null) {
                return null
            }

            val cfg = bakedCfg!!
            if (isGoogleFirstPartyPackage(targetPackageName) && cfg.optInt("ytEngineRev", 0) < 9) {
                return null
            }

            val standalonePkg = deriveStandalonePackageName(
                originalPkg = targetPackageName,
                identity = identity,
                multiInstallEnabled = multiInstallEnabled,
                replaceMainApkEnabled = effectiveReplaceMain
            )
            val resolvedSpeedMult = if (cfg.has("initialSpeedMultiplier")) {
                cfg.optDouble("initialSpeedMultiplier", speedMultiplier.toDouble()).toFloat()
            } else {
                cfg.optDouble("speedMultiplier", speedMultiplier.toDouble()).toFloat()
            }
            val resolvedSpeedEn = cfg.optBoolean("speedEnabled", speedEnabled)
            val resolvedStepEn = cfg.optBoolean("stepWalkEnabled", stepWalkEnabled)
            val resolvedRootEn = cfg.optBoolean("rootBypassEnabled", rootBypassEnabled)
            val resolvedAdEn = cfg.optBoolean("adBypassEnabled", adBypassEnabled)
            val resolvedIdEn = cfg.optBoolean("identitySpoofEnabled", identitySpoofEnabled)
            val resolvedLang = cfg.optString("appLanguage", com.example.ui.i18n.LanguageManager.currentLang.code)
            val resolvedModel = cfg.optString("deviceModel", identity.deviceModel)
            val resolvedAid = cfg.optString("androidId", identity.androidId)
            val resolvedStandalonePkg = cfg.optString(
                "standalonePackageName",
                cfg.optString("standalonePkg", standalonePkg)
            ).ifBlank { standalonePkg }
            val resolvedReplaceMain = cfg.optBoolean(
                "replaceMainApk",
                !multiInstallEnabled && (resolvedStandalonePkg == targetPackageName)
            )
            val resolvedBuiltTs = cfg.optLong("builtTimestamp", 0L).takeIf { it > 0L }
                ?: validFile.lastModified().takeIf { it > 0L }
                ?: System.currentTimeMillis()

            StandaloneApkArtifact(
                originalPackageName = targetPackageName,
                standalonePackageName = resolvedStandalonePkg,
                appName = appName,
                outputFilePath = internalFile.takeIf { it.exists() && it.length() > 10_000L }?.absolutePath ?: validFile.absolutePath,
                publicDownloadPath = publicFile.absolutePath,
                mediaStoreUriString = "",
                fileSizeBytes = validFile.length(),
                builtTimestampMs = resolvedBuiltTs,
                bakedDeviceModel = resolvedModel,
                bakedAndroidId = resolvedAid,
                bakedSpeedMultiplier = resolvedSpeedMult,
                bakedSpeedEnabled = resolvedSpeedEn,
                bakedStepWalkEnabled = resolvedStepEn,
                bakedRootBypassEnabled = resolvedRootEn,
                bakedAdBypassEnabled = resolvedAdEn,
                bakedIdentitySpoofEnabled = resolvedIdEn,
                bakedReplaceMainApk = resolvedReplaceMain,
                bakedLanguageCode = resolvedLang,
                isBuilding = false,
                progressPercent = 100,
                statusMessage = com.example.ui.i18n.tr(
                    "Ready (${formatFileSize(validFile.length())})",
                    "প্রস্তুত (${formatFileSize(validFile.length())})",
                    "तैयार (${formatFileSize(validFile.length())})"
                )
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves the real host OS package name that owns `Process.myUid()`.
     * When running inside Dual Space (`com.clone.android.dual.space`) or another virtual container,
     * `context.packageName` is virtualized (`com.aistudio.phantomcore.yrx`), while the kernel UID
     * belongs to the host container (`com.clone.android.dual.space`). System services like
     * `MediaProvider` and `PackageInstallerService` enforce `AppOpsManager.checkPackage(uid, pkg)`.
     */
    internal fun resolveHostCallingPackage(context: Context): String {
        val myUid = Process.myUid()
        val appOps = try {
            context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        } catch (_: Throwable) {
            null
        }

        val candidates = LinkedHashSet<String>()
        candidates.add(context.packageName)
        try {
            context.packageManager.getPackagesForUid(myUid)?.forEach { pkg ->
                if (!pkg.isNullOrBlank()) candidates.add(pkg)
            }
        } catch (_: Throwable) {}

        // Inspect /proc/self/maps and filesDir for virtual container host package names
        try {
            val pathRegex = Regex("(?:/data/app/(?:~~[^/]+/)?|/data/data/|/data/user/\\d+/)([a-zA-Z0-9_.]+?)(?:-[a-zA-Z0-9_=+-]+)?/")
            pathRegex.findAll(context.filesDir.absolutePath).forEach { m ->
                val p = m.groupValues.getOrNull(1)
                if (!p.isNullOrBlank()) candidates.add(p)
            }
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists() && mapsFile.canRead()) {
                mapsFile.useLines { lines ->
                    lines.take(400).forEach { line ->
                        if (line.contains("/data/")) {
                            pathRegex.findAll(line).forEach { m ->
                                val p = m.groupValues.getOrNull(1)
                                if (!p.isNullOrBlank()) candidates.add(p)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        candidates.add("com.clone.android.dual.space")
        candidates.add("com.clone.android.dual.space.arm64")
        candidates.add("com.clone.android.dual.space.arm32")
        candidates.add("com.ludashi.dualspace")
        candidates.add("com.ludashi.superboost")
        candidates.add("com.excelliance.dualaid")

        if (appOps != null) {
            for (pkg in candidates) {
                try {
                    appOps.checkPackage(myUid, pkg)
                    return pkg
                } catch (_: Throwable) {}
            }
        }
        return context.packageName
    }

    /**
     * Ensures `ContentResolver` uses the real host package name (`resolveHostCallingPackage`)
     * when communicating with host system providers like `MediaStore` from inside Dual Space.
     */
    private fun alignContentResolverWithHostUid(context: Context, resolver: ContentResolver) {
        val hostPkg = resolveHostCallingPackage(context)
        if (hostPkg == context.packageName) return
        try {
            var cls: Class<*>? = resolver.javaClass
            while (cls != null) {
                try {
                    val pkgField = cls.getDeclaredField("mPackageName")
                    pkgField.isAccessible = true
                    pkgField.set(resolver, hostPkg)
                    break
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
        } catch (_: Throwable) {}

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val attrSourceBuilderCls = Class.forName("android.content.AttributionSource\$Builder")
                val builder = attrSourceBuilderCls.getConstructor(Int::class.javaPrimitiveType)
                    .newInstance(Process.myUid())
                attrSourceBuilderCls.getMethod("setPackageName", String::class.java)
                    .invoke(builder, hostPkg)
                val attrSource = attrSourceBuilderCls.getMethod("build").invoke(builder)

                var cls: Class<*>? = resolver.javaClass
                while (cls != null) {
                    try {
                        val attrField = cls.getDeclaredField("mAttributionSource")
                        attrField.isAccessible = true
                        attrField.set(resolver, attrSource)
                        break
                    } catch (_: NoSuchFieldException) {
                        cls = cls.superclass
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Obtains the real unhooked OS IBinder for a system service directly from the kernel's
     * IServiceManager, completely bypassing any virtual container (Dual Space / Parallel Space)
     * hooks placed on ServiceManager.sCache, ActivityThread.sPackageManager, or IActivityTaskManagerSingleton.
     */
    private fun getRealUnhookedServiceBinder(serviceName: String): android.os.IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getISm = smClass.getDeclaredMethod("getIServiceManager").apply { isAccessible = true }
            val iSm = getISm.invoke(null)
            if (iSm != null) {
                val getSvc = iSm.javaClass.methods.firstOrNull {
                    it.name == "getService" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
                } ?: iSm.javaClass.methods.firstOrNull { it.name == "checkService" && it.parameterTypes.size == 1 }
                if (getSvc != null) {
                    getSvc.isAccessible = true
                    val b = getSvc.invoke(iSm, serviceName) as? android.os.IBinder
                    if (b != null) return b
                }
            }
            val getServiceMethod = smClass.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
            getServiceMethod.invoke(null, serviceName) as? android.os.IBinder
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Queries the real OS PackageManager service directly via unhooked Binder IPC so that
     * `splitSourceDirs` (`split_config.arm64_v8a.apk`, `split_config.xxhdpi.apk`, etc.) is ALWAYS
     * returned even when GhostDevice runs inside Dual Space whose virtual PackageManager strips `splitSourceDirs`.
     */
    private fun queryRealSystemApplicationInfo(targetPackageName: String): ApplicationInfo? {
        val cleanPkg = targetPackageName.trim()
        val rawPmBinder = getRealUnhookedServiceBinder("package") ?: return null
        return try {
            val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).apply { isAccessible = true }
            val realIpm = asInterface.invoke(null, rawPmBinder) ?: return null
            val userId = Process.myUid() / 100000
            val getAppInfoMethods = realIpm.javaClass.methods.filter { it.name == "getApplicationInfo" }
            val queryFlags = PackageManager.GET_SHARED_LIBRARY_FILES or PackageManager.GET_META_DATA
            for (flags in intArrayOf(queryFlags, 0)) {
                for (m in getAppInfoMethods) {
                    try {
                        m.isAccessible = true
                        val pTypes = m.parameterTypes
                        val res = when {
                            pTypes.size == 3 && pTypes[0] == String::class.java && pTypes[1] == Long::class.javaPrimitiveType && pTypes[2] == Int::class.javaPrimitiveType ->
                                m.invoke(realIpm, cleanPkg, flags.toLong(), userId)
                            pTypes.size == 3 && pTypes[0] == String::class.java && pTypes[1] == Int::class.javaPrimitiveType && pTypes[2] == Int::class.javaPrimitiveType ->
                                m.invoke(realIpm, cleanPkg, flags, userId)
                            else -> null
                        } as? ApplicationInfo
                        if (res != null && !res.sourceDir.isNullOrBlank()) {
                            return res
                        }
                    } catch (_: Throwable) {}
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Multi-layer ApplicationInfo resolver that locates any installed or virtualized app
     * across normal Android, Android 11+ package visibility, Dual Space virtual containers, and root `pm path`.
     */
    @Suppress("DEPRECATION")
    private fun resolveTargetApplicationInfo(context: Context, targetPackageName: String): ApplicationInfo? {
        val pm = context.packageManager
        val cleanPkg = targetPackageName.trim()

        // First query the real unhooked OS PackageManager so host-installed split APKs (splitSourceDirs) are never stripped by Dual Space
        val realSysAppInfo = queryRealSystemApplicationInfo(cleanPkg)
        if (realSysAppInfo != null && !realSysAppInfo.sourceDir.isNullOrBlank() && !realSysAppInfo.splitSourceDirs.isNullOrEmpty()) {
            return realSysAppInfo
        }

        val flagCandidates = intArrayOf(
            0,
            PackageManager.GET_META_DATA,
            PackageManager.MATCH_UNINSTALLED_PACKAGES,
            PackageManager.GET_SHARED_LIBRARY_FILES
        )
        for (flags in flagCandidates) {
            try {
                val ai = pm.getApplicationInfo(cleanPkg, flags)
                if (ai != null && !ai.sourceDir.isNullOrBlank()) {
                    if (ai.splitSourceDirs.isNullOrEmpty() && realSysAppInfo != null && !realSysAppInfo.splitSourceDirs.isNullOrEmpty()) {
                        ai.splitSourceDirs = realSysAppInfo.splitSourceDirs
                        ai.splitPublicSourceDirs = realSysAppInfo.splitPublicSourceDirs ?: realSysAppInfo.splitSourceDirs
                    }
                    return ai
                }
            } catch (_: Throwable) {}
        }

        if (realSysAppInfo != null && !realSysAppInfo.sourceDir.isNullOrBlank()) {
            return realSysAppInfo
        }

        try {
            val pi = pm.getPackageInfo(cleanPkg, 0)
            if (pi?.applicationInfo != null && !pi.applicationInfo!!.sourceDir.isNullOrBlank()) {
                return pi.applicationInfo
            }
        } catch (_: Throwable) {}

        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(cleanPkg)
            }
            val resolves = pm.queryIntentActivities(launcherIntent, 0)
            for (r in resolves) {
                val ai = r.activityInfo?.applicationInfo
                if (ai != null && !ai.sourceDir.isNullOrBlank()) return ai
            }
        } catch (_: Throwable) {}

        // Unfiltered launcher query (bypasses virtual container setPackage filtering)
        try {
            val allLauncherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolves = pm.queryIntentActivities(allLauncherIntent, 0)
            for (r in resolves) {
                val pkg = r.activityInfo?.packageName ?: continue
                val ai = r.activityInfo?.applicationInfo
                if (pkg.equals(cleanPkg, ignoreCase = true) && ai != null && !ai.sourceDir.isNullOrBlank()) {
                    return ai
                }
            }
        } catch (_: Throwable) {}

        try {
            val allApps = pm.getInstalledApplications(0)
            for (ai in allApps) {
                if (ai.packageName.equals(cleanPkg, ignoreCase = true) && !ai.sourceDir.isNullOrBlank()) {
                    return ai
                }
            }
        } catch (_: Throwable) {}

        try {
            val allPkgs = pm.getInstalledPackages(0)
            for (p in allPkgs) {
                if (p.packageName.equals(cleanPkg, ignoreCase = true) && p.applicationInfo != null && !p.applicationInfo!!.sourceDir.isNullOrBlank()) {
                    return p.applicationInfo
                }
            }
        } catch (_: Throwable) {}

        // Non-root shell `pm path <pkg>` fallback (works inside virtual containers even without root)
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "pm path $cleanPkg 2>/dev/null || cmd package path $cleanPkg 2>/dev/null")
            )
            val shellOut = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            val paths = shellOut.lines()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.endsWith(".apk") }
            if (paths.isNotEmpty()) {
                val basePath = paths.firstOrNull { it.endsWith("/base.apk") } ?: paths.first()
                val splitPaths = paths.filter { it != basePath }
                return ApplicationInfo().apply {
                    packageName = cleanPkg
                    sourceDir = basePath
                    publicSourceDir = basePath
                    if (splitPaths.isNotEmpty()) {
                        splitSourceDirs = splitPaths.toTypedArray()
                        splitPublicSourceDirs = splitPaths.toTypedArray()
                    }
                }
            }
        } catch (_: Throwable) {}

        // Root shell `pm path <pkg>` fallback for apps hidden by container or package visibility
        if (RootChecker.isDeviceRooted()) {
            try {
                val output = RootChecker.executeRootCommandWithOutput("pm path $cleanPkg 2>/dev/null || cmd package path $cleanPkg 2>/dev/null")
                val paths = output.lines()
                    .map { it.trim() }
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:").trim() }
                    .filter { it.endsWith(".apk") }
                if (paths.isNotEmpty()) {
                    val basePath = paths.firstOrNull { it.endsWith("/base.apk") } ?: paths.first()
                    val splitPaths = paths.filter { it != basePath }
                    return ApplicationInfo().apply {
                        packageName = cleanPkg
                        sourceDir = basePath
                        publicSourceDir = basePath
                        if (splitPaths.isNotEmpty()) {
                            splitSourceDirs = splitPaths.toTypedArray()
                            splitPublicSourceDirs = splitPaths.toTypedArray()
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        return null
    }

    fun buildStandaloneApk(
        context: Context,
        targetPackageName: String,
        appName: String,
        identity: DeviceIdentity,
        liveLatitude: Double,
        liveLongitude: Double,
        speedMultiplier: Float,
        speedEnabled: Boolean = true,
        stepWalkEnabled: Boolean = false,
        rootBypassEnabled: Boolean = false,
        identitySpoofEnabled: Boolean = false,
        multiInstallEnabled: Boolean = false,
        adBypassEnabled: Boolean = false,
        replaceMainApkEnabled: Boolean = false,
        liveToggleSnapshotProvider: (() -> LiveBuildToggleSnapshot)? = null,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): StandaloneApkArtifact? {
        if (stalePurgedOnce.compareAndSet(false, true)) {
            purgeStaleStandaloneApks(context)
        }

        val lock = packageBuildLocks.getOrPut(targetPackageName) { Any() }
        synchronized(lock) {
            val pm = context.packageManager
            val appInfo = resolveTargetApplicationInfo(context, targetPackageName) ?: return null
            val sourceApkPath = appInfo.sourceDir ?: return null

            val tempDir = getWorkingTempDir(context)
            val outDir = getStandaloneOutputDir(context)
            val buildSeq = buildSequence.getAndIncrement()
            val tempCopiedFiles = mutableListOf<File>()

            // Proactively clean any leftover .tmp files before starting a large build
            try {
                tempDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".tmp")) {
                        try { f.delete() } catch (_: Throwable) {}
                    }
                }
                outDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".tmp")) {
                        try { f.delete() } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}

            fun isZipReadable(file: File): Boolean {
                if (!file.exists() || !file.canRead() || file.length() <= 64L) return false
                return try {
                    ZipFile(file).use { z -> z.size() >= 0 }
                } catch (_: Throwable) {
                    false
                }
            }

            fun ensureReadableApk(rawPath: String, tag: String): File? {
                val f = File(rawPath)
                if (isZipReadable(f)) return f
                if (RootChecker.isDeviceRooted()) {
                    // Stage via /data/local/tmp first so both host root shell and Dual Space virtual guest see the exact same file
                    val sharedTmp = File("/data/local/tmp/phantom_src_${tag}_${buildSeq}_${ kotlin.math.abs(rawPath.hashCode()) }.apk")
                    RootChecker.executeRootCommand(
                        "cp \"$rawPath\" \"${sharedTmp.absolutePath}\" && chmod 644 \"${sharedTmp.absolutePath}\" 2>/dev/null || true"
                    )
                    if (isZipReadable(sharedTmp)) {
                        tempCopiedFiles.add(sharedTmp)
                        return sharedTmp
                    }
                    val localTmp = File(tempDir, "src_${tag}_${buildSeq}_${kotlin.math.abs(rawPath.hashCode())}.apk")
                    RootChecker.executeRootCommand(
                        "cp \"$rawPath\" \"${localTmp.absolutePath}\" && chmod 644 \"${localTmp.absolutePath}\" 2>/dev/null || true"
                    )
                    if (isZipReadable(localTmp)) {
                        tempCopiedFiles.add(localTmp)
                        return localTmp
                    }
                }
                return if (f.exists() && f.canRead()) f else null
            }

            onProgress(
                5,
                com.example.ui.i18n.tr(
                    "Analyzing app files...",
                    "অ্যাপ ফাইল বিশ্লেষণ হচ্ছে...",
                    "ऐप फ़ाइलों का विश्लेषण..."
                )
            )
            val sourceApkFile = ensureReadableApk(sourceApkPath, "base") ?: return null

            // Discover all split_*.apk files across real unhooked OS PackageManager, hooked PackageManager, virtual container dirs, shell `pm path`, and root directory listing
            val rawSplitList = LinkedHashSet<String>()
            appInfo.splitSourceDirs?.forEach { if (!it.isNullOrBlank()) rawSplitList.add(it) }
            try {
                val realSysAi = queryRealSystemApplicationInfo(targetPackageName)
                realSysAi?.splitSourceDirs?.forEach { if (!it.isNullOrBlank()) rawSplitList.add(it) }
            } catch (_: Throwable) {}
            try {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(targetPackageName, 0)
                pi?.applicationInfo?.splitSourceDirs?.forEach { if (!it.isNullOrBlank()) rawSplitList.add(it) }
            } catch (_: Throwable) {}
            try {
                File(sourceApkPath).parentFile?.listFiles()?.forEach { sibling ->
                    if (sibling.isFile && sibling.name.endsWith(".apk") && sibling.name != "base.apk" && sibling.absolutePath != sourceApkPath) {
                        rawSplitList.add(sibling.absolutePath)
                    }
                }
            } catch (_: Throwable) {}
            // Non-root shell `pm path <pkg>` discovery (critical inside Dual Space / Android 11+ where listFiles() is blocked on /data/app)
            try {
                val proc = Runtime.getRuntime().exec(
                    arrayOf("sh", "-c", "pm path $targetPackageName 2>/dev/null || cmd package path $targetPackageName 2>/dev/null")
                )
                val shellOut = proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                shellOut.lines()
                    .map { it.trim() }
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:").trim() }
                    .filter { it.endsWith(".apk") && !it.endsWith("/base.apk") && it != sourceApkPath }
                    .forEach { rawSplitList.add(it) }
            } catch (_: Throwable) {}
            // Root shell `pm path` + parent directory `.apk` scan
            if (RootChecker.isDeviceRooted()) {
                try {
                    val parentDirPath = File(sourceApkPath).parentFile?.absolutePath ?: ""
                    val rootOut = RootChecker.executeRootCommandWithOutput(
                        "(pm path $targetPackageName 2>/dev/null || cmd package path $targetPackageName 2>/dev/null; " +
                            (if (parentDirPath.isNotEmpty()) "ls -1 \"$parentDirPath\"/*.apk 2>/dev/null" else "") + ")"
                    )
                    rootOut.lines()
                        .map { it.trim().removePrefix("package:").trim() }
                        .filter { it.endsWith(".apk") && !it.endsWith("/base.apk") && it != sourceApkPath }
                        .forEach { rawSplitList.add(it) }
                } catch (_: Throwable) {}
            }

            // Discover static shared library APKs (e.g. TrichromeLibrary used by Chrome / Brave / Browsers)
            // and append them AFTER app splits so Chrome's libmonochrome*.so, DEX, and shared resources are bundled!
            val sharedLibApkSet = LinkedHashSet<String>()
            fun addCandidateSharedLibApk(path: String?) {
                val p = path?.trim().orEmpty()
                if (p.endsWith(".apk") && !p.startsWith("/system/framework/") && p != sourceApkPath && !rawSplitList.contains(p)) {
                    sharedLibApkSet.add(p)
                }
            }
            appInfo.sharedLibraryFiles?.forEach { addCandidateSharedLibApk(it) }
            try {
                val realSysAi = queryRealSystemApplicationInfo(targetPackageName)
                realSysAi?.sharedLibraryFiles?.forEach { addCandidateSharedLibApk(it) }
            } catch (_: Throwable) {}
            try {
                @Suppress("DEPRECATION")
                val aiWithLibs = pm.getApplicationInfo(targetPackageName, PackageManager.GET_SHARED_LIBRARY_FILES or PackageManager.GET_META_DATA)
                aiWithLibs.sharedLibraryFiles?.forEach { addCandidateSharedLibApk(it) }
            } catch (_: Throwable) {}

            val lowerTargetPkg = targetPackageName.lowercase(Locale.US)
            if (sharedLibApkSet.isEmpty() && (lowerTargetPkg.contains("chrome") || lowerTargetPkg.contains("webview") || lowerTargetPkg.contains("browser"))) {
                for (triPkg in listOf("com.google.android.trichromelibrary", "com.android.trichromelibrary")) {
                    try {
                        val triAi = resolveTargetApplicationInfo(context, triPkg)
                        addCandidateSharedLibApk(triAi?.sourceDir)
                        triAi?.splitSourceDirs?.forEach { addCandidateSharedLibApk(it) }
                    } catch (_: Throwable) {}
                }
                try {
                    val proc = Runtime.getRuntime().exec(
                        arrayOf("sh", "-c", "dumpsys package $targetPackageName 2>/dev/null | grep -E 'trichromelibrary.*\\.apk' || pm list packages -f trichromelibrary 2>/dev/null")
                    )
                    val out = proc.inputStream.bufferedReader().use { it.readText() }
                    proc.waitFor()
                    Regex("(/[^\\s:=]+\\.apk)").findAll(out).forEach { m ->
                        addCandidateSharedLibApk(m.groupValues[1])
                    }
                } catch (_: Throwable) {}
            }
            rawSplitList.addAll(sharedLibApkSet)

            val readableSplits = rawSplitList.mapIndexedNotNull { idx, sPath ->
                ensureReadableApk(sPath, "split$idx")?.absolutePath
            }.toTypedArray().takeIf { it.isNotEmpty() }

            val lowerSrcPath = sourceApkPath.lowercase(Locale.US)
            val isSystemSourceApp = ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) ||
                lowerSrcPath.startsWith("/system/") ||
                lowerSrcPath.startsWith("/product/") ||
                lowerSrcPath.startsWith("/system_ext/") ||
                lowerSrcPath.startsWith("/vendor/") ||
                lowerSrcPath.startsWith("/oem/")

            // Resolve nativeLibraryDir cleanly for both user apps and pre-installed system apps
            // (system apps in /product/app, /system_ext/priv-app, /system/app often symlink lib/arm64/*.so -> /product/lib64/*.so)
            var stagedNativeLibDir: File? = null
            val effectiveNativeLibDir: String? = run {
                val candDirs = LinkedHashSet<String>()
                if (!appInfo.nativeLibraryDir.isNullOrBlank()) candDirs.add(appInfo.nativeLibraryDir)
                try {
                    val realSysAi = queryRealSystemApplicationInfo(targetPackageName)
                    if (!realSysAi?.nativeLibraryDir.isNullOrBlank()) candDirs.add(realSysAi!!.nativeLibraryDir)
                } catch (_: Throwable) {}
                val parentDir = File(sourceApkPath).parentFile
                if (parentDir != null) {
                    candDirs.add(File(parentDir, "lib/arm64").absolutePath)
                    candDirs.add(File(parentDir, "lib/arm64-v8a").absolutePath)
                    candDirs.add(File(parentDir, "lib/arm").absolutePath)
                    candDirs.add(File(parentDir, "lib/armeabi-v7a").absolutePath)
                }
                var directlyReadableDir: String? = null
                for (cd in candDirs) {
                    try {
                        val d = File(cd)
                        if (d.exists() && d.isDirectory) {
                            val hasSo = d.listFiles()?.any { it.isFile && it.name.endsWith(".so") && it.canRead() && it.length() >= 20L } == true
                            if (hasSo) {
                                directlyReadableDir = cd
                                break
                            }
                        }
                    } catch (_: Throwable) {}
                }
                if (directlyReadableDir != null) {
                    directlyReadableDir
                } else if (RootChecker.isDeviceRooted() && candDirs.isNotEmpty()) {
                    try {
                        val stageDir = File(tempDir, "staged_libs_${buildSeq}")
                        stageDir.mkdirs()
                        for (cd in candDirs) {
                            RootChecker.executeRootCommand(
                                "if [ -d \"$cd\" ]; then cp -L \"$cd\"/*.so \"${stageDir.absolutePath}/\" 2>/dev/null || true; fi"
                            )
                            val copiedCount = stageDir.listFiles()?.count { it.isFile && it.name.endsWith(".so") && it.length() >= 20L } ?: 0
                            if (copiedCount > 0) {
                                RootChecker.executeRootCommand("chmod 644 \"${stageDir.absolutePath}\"/*.so 2>/dev/null || true")
                                stagedNativeLibDir = stageDir
                                break
                            }
                        }
                        if (stagedNativeLibDir != null) stagedNativeLibDir!!.absolutePath else appInfo.nativeLibraryDir
                    } catch (_: Throwable) {
                        appInfo.nativeLibraryDir
                    }
                } else {
                    appInfo.nativeLibraryDir
                }
            }

            // Pre-installed system apps compiled with WITH_DEXPREOPT often strip classes.dex from base.apk
            // and keep their bytecode inside <apkParent>/oat/<arch>/*.vdex — recover those DEXes automatically!
            val recoveredSystemDexes: List<ByteArray> = if (isSystemSourceApp) {
                extractDexesFromSystemVdexIfMissing(
                    originalSourceApkPath = sourceApkPath,
                    readableBaseApk = sourceApkFile,
                    readableSplits = readableSplits,
                    tempDir = tempDir,
                    buildSeq = buildSeq
                )
            } else {
                emptyList()
            }

            val effectiveIdentity = if (
                identity.deviceModel.isBlank() ||
                identity.deviceModel.equals(Build.MODEL, ignoreCase = true) ||
                identity.deviceModel.contains(Build.MODEL, ignoreCase = true) ||
                identity.buildFingerprint.equals(Build.FINGERPRINT, ignoreCase = true)
            ) {
                com.example.data.generator.IdentityGenerator.generateNewIdentity(excludeModel = Build.MODEL)
            } else {
                identity
            }

            val effectiveReplaceMain = !multiInstallEnabled && replaceMainApkEnabled
            val standalonePkg = deriveStandalonePackageName(
                originalPkg = targetPackageName,
                identity = effectiveIdentity,
                multiInstallEnabled = multiInstallEnabled,
                replaceMainApkEnabled = effectiveReplaceMain
            )
            val idFileSuffix = buildVariantFileSuffix(multiInstallEnabled, effectiveReplaceMain, effectiveIdentity)
            val originalSigHex = extractOriginalSignatureHex(pm, targetPackageName, sourceApkFile.absolutePath)

            var bakedSnapshot = LiveBuildToggleSnapshot(
                speedMultiplier = speedMultiplier,
                speedEnabled = speedEnabled,
                stepWalkEnabled = stepWalkEnabled,
                rootBypassEnabled = rootBypassEnabled,
                identitySpoofEnabled = identitySpoofEnabled,
                adBypassEnabled = adBypassEnabled,
                liveLatitude = if (liveLatitude != 0.0) liveLatitude else effectiveIdentity.latitude,
                liveLongitude = if (liveLongitude != 0.0) liveLongitude else effectiveIdentity.longitude,
                locationName = effectiveIdentity.locationName
            )
            var actualBuiltTsMs = System.currentTimeMillis()

            val configBytes = buildEmbeddedConfigJsonBytes(
                originalPkg = targetPackageName,
                standalonePkg = standalonePkg,
                originalSigHex = originalSigHex,
                identity = effectiveIdentity,
                liveLat = bakedSnapshot.liveLatitude,
                liveLng = bakedSnapshot.liveLongitude,
                speedMultiplier = bakedSnapshot.speedMultiplier,
                speedEnabled = bakedSnapshot.speedEnabled,
                stepWalkEnabled = bakedSnapshot.stepWalkEnabled,
                rootBypassEnabled = bakedSnapshot.rootBypassEnabled,
                identitySpoofEnabled = bakedSnapshot.identitySpoofEnabled,
                adBypassEnabled = bakedSnapshot.adBypassEnabled,
                context = context,
                builtTimestampOverride = actualBuiltTsMs
            )

            val safeFileName = "${targetPackageName.replace('.', '_')}${idFileSuffix}_${BUILDER_VERSION}_standalone.apk"
            val workingApkFile = File(outDir, "${safeFileName}.${buildSeq}.working.tmp")
            val finalInternalFile = File(outDir, safeFileName)

            // Free any previous built APK for this exact package/variant before writing a new one
            if (finalInternalFile.exists()) {
                try { finalInternalFile.delete() } catch (_: Throwable) {}
            }

            try {
                onProgress(
                    12,
                    com.example.ui.i18n.tr(
                        "Preparing runtime DEX & config...",
                        "রানটাইম DEX ও কনফিগ প্রস্তুত হচ্ছে...",
                        "रनटाइम DEX और कॉन्फ़िग तैयार हो रहा है..."
                    )
                )
                val injectedDexList = extractStandaloneRuntimeDexes(context)

                val patchingMsg = com.example.ui.i18n.tr(
                    "Patching resources, DEX & Stick...",
                    "রিসোর্স, DEX ও স্টিক যুক্ত হচ্ছে...",
                    "रिसोर्स, DEX और स्टिक जोड़े जा रहे हैं..."
                )
                onProgress(15, patchingMsg)
                repackageAndPatchZip(
                    sourceApkFile = sourceApkFile,
                    splitSourceDirs = readableSplits,
                    nativeLibraryDir = effectiveNativeLibDir,
                    originalPkg = targetPackageName,
                    standalonePkg = standalonePkg,
                    configJsonBytes = configBytes,
                    configJsonBytesProvider = { splitCount ->
                        val latestSnap = try {
                            liveToggleSnapshotProvider?.invoke() ?: bakedSnapshot
                        } catch (_: Throwable) {
                            bakedSnapshot
                        }
                        bakedSnapshot = latestSnap
                        actualBuiltTsMs = System.currentTimeMillis()
                        val latestId = if (latestSnap.locationName.isNotBlank()) {
                            effectiveIdentity.copy(
                                latitude = if (latestSnap.liveLatitude != 0.0) latestSnap.liveLatitude else effectiveIdentity.latitude,
                                longitude = if (latestSnap.liveLongitude != 0.0) latestSnap.liveLongitude else effectiveIdentity.longitude,
                                locationName = latestSnap.locationName
                            )
                        } else effectiveIdentity
                        buildEmbeddedConfigJsonBytes(
                            originalPkg = targetPackageName,
                            standalonePkg = standalonePkg,
                            originalSigHex = originalSigHex,
                            identity = latestId,
                            liveLat = if (latestSnap.liveLatitude != 0.0) latestSnap.liveLatitude else latestId.latitude,
                            liveLng = if (latestSnap.liveLongitude != 0.0) latestSnap.liveLongitude else latestId.longitude,
                            speedMultiplier = latestSnap.speedMultiplier,
                            speedEnabled = latestSnap.speedEnabled,
                            stepWalkEnabled = latestSnap.stepWalkEnabled,
                            rootBypassEnabled = latestSnap.rootBypassEnabled,
                            identitySpoofEnabled = latestSnap.identitySpoofEnabled,
                            adBypassEnabled = latestSnap.adBypassEnabled,
                            splitResCountOverride = splitCount,
                            builtTimestampOverride = actualBuiltTsMs,
                            context = context
                        )
                    },
                    injectedRuntimeDexes = injectedDexList,
                    unsignedOutFile = workingApkFile,
                    onProgressPercent = { pct ->
                        onProgress(pct.coerceIn(15, 80), patchingMsg)
                    },
                    fallbackRecoveredDexes = recoveredSystemDexes,
                    isSystemSourceApp = isSystemSourceApp
                )

                // Free any temporary root-copied source APKs & staged native libs immediately before V2 signing
                tempCopiedFiles.forEach { tmp ->
                    try {
                        if (!tmp.delete() && RootChecker.isDeviceRooted()) {
                            RootChecker.executeRootCommand("rm -f \"${tmp.absolutePath}\" 2>/dev/null || true")
                        }
                    } catch (_: Throwable) {}
                }
                tempCopiedFiles.clear()
                stagedNativeLibDir?.let { sDir ->
                    try { sDir.deleteRecursively() } catch (_: Throwable) {}
                }

                val signingMsg = com.example.ui.i18n.tr(
                    "Signing V1+V2 APK...",
                    "APK স্বাক্ষর (V1+V2) করা হচ্ছে...",
                    "V1+V2 APK साइन किया जा रहा है..."
                )
                onProgress(82, signingMsg)
                // Sign V2 in-place on workingApkFile so we NEVER duplicate a 1GB+ APK on disk!
                signApkV1AndV2(workingApkFile, workingApkFile) { signPct ->
                    onProgress(signPct.coerceIn(82, 95), signingMsg)
                }

                if (workingApkFile.exists() && workingApkFile.length() > 0L) {
                    if (finalInternalFile.exists()) {
                        try { finalInternalFile.delete() } catch (_: Throwable) {}
                    }
                    if (!workingApkFile.renameTo(finalInternalFile)) {
                        workingApkFile.copyTo(finalInternalFile, overwrite = true)
                        try { workingApkFile.delete() } catch (_: Throwable) {}
                    }
                }
            } finally {
                try { workingApkFile.delete() } catch (_: Throwable) {}
                tempCopiedFiles.forEach { tmp ->
                    try {
                        if (!tmp.delete() && RootChecker.isDeviceRooted()) {
                            RootChecker.executeRootCommand("rm -f \"${tmp.absolutePath}\" 2>/dev/null || true")
                        }
                    } catch (_: Throwable) {}
                }
            }

            if (!finalInternalFile.exists() || finalInternalFile.length() <= 0L) {
                return null
            }

            onProgress(
                97,
                com.example.ui.i18n.tr(
                    "Saving to Downloads...",
                    "ডাউনলোডে সেভ করা হচ্ছে...",
                    "डाउनलोड में सेव किया जा रहा है..."
                )
            )
            val cleanLabel = appName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { targetPackageName }
            val publicFileName = "${cleanLabel}${idFileSuffix}_GhostDevice_${BUILDER_VERSION}.apk"
            val publicDownloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "GhostDeviceAPKs"
            )
            val publicFile = File(publicDownloadDir, publicFileName)

            val mediaStoreUri = publishApkToMediaStoreDownloads(context, finalInternalFile, publicFileName)

            // Ensure publicFile is kept in sync if MediaStore didn't write it or size/timestamp changed
            if (mediaStoreUri == null &&
                (!publicFile.exists() || publicFile.length() != finalInternalFile.length() || publicFile.lastModified() < finalInternalFile.lastModified())
            ) {
                try {
                    if (!publicDownloadDir.exists()) publicDownloadDir.mkdirs()
                    finalInternalFile.copyTo(publicFile, overwrite = true)
                } catch (_: Throwable) {
                    if (RootChecker.isDeviceRooted()) {
                        RootChecker.executeRootCommand(
                            "mkdir -p \"${publicDownloadDir.absolutePath}\" && " +
                                "cp \"${finalInternalFile.absolutePath}\" \"${publicFile.absolutePath}\" && " +
                                "chmod 644 \"${publicFile.absolutePath}\" 2>/dev/null || true"
                        )
                    }
                }
            }

            onProgress(
                100,
                com.example.ui.i18n.tr(
                    "Ready (${formatFileSize(finalInternalFile.length())})",
                    "প্রস্তুত (${formatFileSize(finalInternalFile.length())})",
                    "तैयार (${formatFileSize(finalInternalFile.length())})"
                )
            )
            return StandaloneApkArtifact(
                originalPackageName = targetPackageName,
                standalonePackageName = standalonePkg,
                appName = appName,
                outputFilePath = finalInternalFile.absolutePath,
                publicDownloadPath = publicFile.absolutePath,
                mediaStoreUriString = mediaStoreUri?.toString() ?: "",
                fileSizeBytes = finalInternalFile.length(),
                builtTimestampMs = actualBuiltTsMs,
                bakedDeviceModel = effectiveIdentity.deviceModel,
                bakedAndroidId = effectiveIdentity.androidId,
                bakedSpeedMultiplier = bakedSnapshot.speedMultiplier,
                bakedSpeedEnabled = bakedSnapshot.speedEnabled,
                bakedStepWalkEnabled = bakedSnapshot.stepWalkEnabled,
                bakedRootBypassEnabled = bakedSnapshot.rootBypassEnabled,
                bakedAdBypassEnabled = bakedSnapshot.adBypassEnabled,
                bakedIdentitySpoofEnabled = bakedSnapshot.identitySpoofEnabled,
                bakedReplaceMainApk = effectiveReplaceMain,
                isBuilding = false,
                progressPercent = 100,
                statusMessage = com.example.ui.i18n.tr(
                    "Ready (${formatFileSize(finalInternalFile.length())})",
                    "প্রস্তুত (${formatFileSize(finalInternalFile.length())})",
                    "तैयार (${formatFileSize(finalInternalFile.length())})"
                )
            )
        }
    }

    /**
     * Fast Config-Only Refresh for any already-built standalone APK (10 MB to 2 GB+).
     * Streams existing entries with a 64 KB buffer while replacing `assets/phantom_standalone_config.json`
     * with the latest user toggles and re-signing cleanly with V1 + V2 APK signatures.
     */
    fun refreshConfigInExistingApk(
        context: Context,
        existingApkFile: File,
        targetPackageName: String,
        appName: String,
        identity: DeviceIdentity,
        liveLatitude: Double,
        liveLongitude: Double,
        speedMultiplier: Float,
        speedEnabled: Boolean,
        stepWalkEnabled: Boolean,
        rootBypassEnabled: Boolean,
        identitySpoofEnabled: Boolean,
        multiInstallEnabled: Boolean,
        adBypassEnabled: Boolean = false,
        replaceMainApkEnabled: Boolean = false,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): StandaloneApkArtifact? {
        if (!existingApkFile.exists() || !existingApkFile.canRead() || existingApkFile.length() <= 10_000L) {
            return null
        }

        val lock = packageBuildLocks.getOrPut(targetPackageName) { Any() }
        synchronized(lock) {
            val outDir = getStandaloneOutputDir(context)
            val pm = context.packageManager
            val syncSeq = buildSequence.getAndIncrement()

            val oldCfg: JSONObject? = try {
                ZipFile(existingApkFile).use { zip ->
                    val cfgEntry = zip.getEntry("assets/phantom_standalone_config.json") ?: return@use null
                    val cfgStr = zip.getInputStream(cfgEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    JSONObject(cfgStr)
                }
            } catch (_: Throwable) {
                null
            }

            if (isGoogleFirstPartyPackage(targetPackageName) && (oldCfg?.optInt("ytEngineRev", 0) ?: 0) < 9) {
                return null
            }

            val preservedSplitResCount = oldCfg?.optInt("splitResCount", 0) ?: 0
            val origInstalledSourceDir = try {
                pm.getApplicationInfo(targetPackageName, 0).sourceDir
            } catch (_: Throwable) {
                null
            }
            val preservedSigHex = oldCfg?.optString("originalSignatureHex", "")?.takeIf { it.isNotBlank() }
                ?: extractOriginalSignatureHex(pm, targetPackageName, origInstalledSourceDir ?: existingApkFile.absolutePath)

            val oldStandalonePkg = oldCfg?.optString("standalonePackageName", "")
                ?.ifBlank { oldCfg.optString("standalonePkg", "") }
                .orEmpty()
            val preserveMultiIdentity = multiInstallEnabled && oldStandalonePkg.contains(".id")

            val effectiveIdentity = if (preserveMultiIdentity && oldCfg != null) {
                identity.copy(
                    deviceModel = oldCfg.optString("deviceModel", identity.deviceModel).ifBlank { identity.deviceModel },
                    manufacturer = oldCfg.optString("manufacturer", identity.manufacturer).ifBlank { identity.manufacturer },
                    androidId = oldCfg.optString("androidId", identity.androidId).ifBlank { identity.androidId },
                    buildFingerprint = oldCfg.optString("buildFingerprint", identity.buildFingerprint).ifBlank { identity.buildFingerprint },
                    imei = oldCfg.optString("imei", identity.imei).ifBlank { identity.imei },
                    serialNumber = oldCfg.optString("serialNumber", identity.serialNumber).ifBlank { identity.serialNumber }
                )
            } else if (
                identity.deviceModel.isBlank() ||
                identity.deviceModel.equals(Build.MODEL, ignoreCase = true) ||
                identity.deviceModel.contains(Build.MODEL, ignoreCase = true) ||
                identity.buildFingerprint.equals(Build.FINGERPRINT, ignoreCase = true)
            ) {
                val oldModel = oldCfg?.optString("deviceModel", "") ?: ""
                val oldFp = oldCfg?.optString("buildFingerprint", "") ?: ""
                if (oldModel.isNotBlank() && !oldModel.equals(Build.MODEL, ignoreCase = true) && !oldFp.equals(Build.FINGERPRINT, ignoreCase = true)) {
                    identity.copy(
                        deviceModel = oldModel,
                        manufacturer = oldCfg?.optString("manufacturer", identity.manufacturer).orEmpty().ifBlank { identity.manufacturer },
                        androidId = oldCfg?.optString("androidId", identity.androidId).orEmpty().ifBlank { identity.androidId },
                        buildFingerprint = oldFp.ifBlank { identity.buildFingerprint },
                        imei = oldCfg?.optString("imei", identity.imei).orEmpty().ifBlank { identity.imei },
                        serialNumber = oldCfg?.optString("serialNumber", identity.serialNumber).orEmpty().ifBlank { identity.serialNumber }
                    )
                } else {
                    com.example.data.generator.IdentityGenerator.generateNewIdentity(excludeModel = Build.MODEL)
                }
            } else {
                identity
            }

            val effectiveReplaceMain = !multiInstallEnabled && replaceMainApkEnabled
            val standalonePkg = if (preserveMultiIdentity) {
                oldStandalonePkg
            } else {
                deriveStandalonePackageName(
                    originalPkg = targetPackageName,
                    identity = effectiveIdentity,
                    multiInstallEnabled = multiInstallEnabled,
                    replaceMainApkEnabled = effectiveReplaceMain
                )
            }
            if (oldStandalonePkg.isNotBlank() && oldStandalonePkg != standalonePkg) {
                return null
            }
            val idMatch = Regex("\\.id([a-zA-Z0-9]+)$").find(standalonePkg)
            val idFileSuffix = when {
                idMatch != null -> "_id${idMatch.groupValues[1]}"
                effectiveReplaceMain -> "_replace"
                else -> ""
            }
            val safeFileName = "${targetPackageName.replace('.', '_')}${idFileSuffix}_${BUILDER_VERSION}_standalone.apk"
            val finalInternalFile = File(outDir, safeFileName)
            val syncBaseDir = if (outDir.usableSpace >= existingApkFile.length() + 16_777_216L) {
                outDir
            } else {
                getWorkingTempDir(context)
            }
            val workingApkFile = File(syncBaseDir, "${safeFileName}.${syncSeq}.sync.tmp")

            val actualBuiltTsMs = System.currentTimeMillis()
            val preservedOrigAppCompFactory = oldCfg?.optString("originalAppComponentFactory", "").orEmpty()
            val rawFinalConfigBytes = buildEmbeddedConfigJsonBytes(
                originalPkg = targetPackageName,
                standalonePkg = standalonePkg,
                originalSigHex = preservedSigHex,
                identity = effectiveIdentity,
                liveLat = if (liveLatitude != 0.0) liveLatitude else effectiveIdentity.latitude,
                liveLng = if (liveLongitude != 0.0) liveLongitude else effectiveIdentity.longitude,
                speedMultiplier = speedMultiplier,
                speedEnabled = speedEnabled,
                stepWalkEnabled = stepWalkEnabled,
                rootBypassEnabled = rootBypassEnabled,
                identitySpoofEnabled = identitySpoofEnabled,
                adBypassEnabled = adBypassEnabled,
                splitResCountOverride = preservedSplitResCount,
                builtTimestampOverride = actualBuiltTsMs,
                context = context
            )
            val finalConfigBytes = if (preservedOrigAppCompFactory.isNotBlank()) {
                try {
                    val cfgJson = JSONObject(String(rawFinalConfigBytes, StandardCharsets.UTF_8))
                    cfgJson.put("originalAppComponentFactory", preservedOrigAppCompFactory)
                    cfgJson.toString().toByteArray(StandardCharsets.UTF_8)
                } catch (_: Throwable) {
                    rawFinalConfigBytes
                }
            } else {
                rawFinalConfigBytes
            }

            val syncMsg = com.example.ui.i18n.tr(
                "Syncing enabled systems into APK...",
                "চালু করা সিস্টেমগুলো APK-তে যুক্ত করা হচ্ছে...",
                "चालू सिस्टम APK में जोड़े जा रहे हैं..."
            )
            onProgress(20, syncMsg)

            try {
                val writtenNames = HashSet<String>(4096)
                val v1Digests = LinkedHashMap<String, String>(4096)
                val sharedBuf = ByteArray(65536)
                val sha256 = MessageDigest.getInstance("SHA-256")
                val crc32 = CRC32()

                val totalBytes = existingApkFile.length().coerceAtLeast(1L)
                var lastPct = 20

                ZipFile(existingApkFile).use { srcZip ->
                    CountingOutputStream(BufferedOutputStream(FileOutputStream(workingApkFile), 65536)).use { countingOut ->
                        ZipOutputStream(countingOut).use { zipOut ->

                            fun writeInMemoryEntry(name: String, data: ByteArray, storeUncompressed: Boolean) {
                                if (!writtenNames.add(name)) return
                                if (!name.startsWith("META-INF/")) {
                                    sha256.reset()
                                    v1Digests[name] = Base64.encodeToString(sha256.digest(data), Base64.NO_WRAP)
                                }
                                val entry = ZipEntry(name)
                                if (storeUncompressed) {
                                    entry.method = ZipEntry.STORED
                                    entry.size = data.size.toLong()
                                    entry.compressedSize = data.size.toLong()
                                    crc32.reset()
                                    crc32.update(data)
                                    entry.crc = crc32.value
                                    val nameByteLen = name.toByteArray(StandardCharsets.UTF_8).size
                                    val align = if (name.endsWith(".so")) 16384L else 4L
                                    val pad = ((align - ((countingOut.bytesWritten + 30L + nameByteLen) % align)) % align).toInt()
                                    if (pad > 0) entry.extra = ByteArray(pad)
                                } else {
                                    entry.method = ZipEntry.DEFLATED
                                }
                                zipOut.putNextEntry(entry)
                                zipOut.write(data)
                                zipOut.closeEntry()
                            }

                            fun streamEntry(srcEntry: ZipEntry, targetName: String, storeUncompressed: Boolean) {
                                if (!writtenNames.add(targetName)) return
                                val outEntry = ZipEntry(targetName)
                                var preDigest: String? = null
                                if (storeUncompressed) {
                                    crc32.reset()
                                    sha256.reset()
                                    var total = 0L
                                    srcZip.getInputStream(srcEntry).use { input ->
                                        var r: Int
                                        while (input.read(sharedBuf).also { r = it } != -1) {
                                            crc32.update(sharedBuf, 0, r)
                                            sha256.update(sharedBuf, 0, r)
                                            total += r
                                        }
                                    }
                                    if (!targetName.startsWith("META-INF/")) {
                                        preDigest = Base64.encodeToString(sha256.digest(), Base64.NO_WRAP)
                                    }
                                    outEntry.method = ZipEntry.STORED
                                    outEntry.size = total
                                    outEntry.compressedSize = total
                                    outEntry.crc = crc32.value
                                    val nameByteLen = targetName.toByteArray(StandardCharsets.UTF_8).size
                                    val align = if (targetName.endsWith(".so")) 16384L else 4L
                                    val pad = ((align - ((countingOut.bytesWritten + 30L + nameByteLen) % align)) % align).toInt()
                                    if (pad > 0) outEntry.extra = ByteArray(pad)
                                } else {
                                    outEntry.method = ZipEntry.DEFLATED
                                }
                                zipOut.putNextEntry(outEntry)
                                if (preDigest == null) sha256.reset()
                                srcZip.getInputStream(srcEntry).use { input ->
                                    var r: Int
                                    while (input.read(sharedBuf).also { r = it } != -1) {
                                        zipOut.write(sharedBuf, 0, r)
                                        if (preDigest == null) sha256.update(sharedBuf, 0, r)
                                    }
                                }
                                zipOut.closeEntry()
                                if (!targetName.startsWith("META-INF/")) {
                                    v1Digests[targetName] = preDigest
                                        ?: Base64.encodeToString(sha256.digest(), Base64.NO_WRAP)
                                }
                            }

                            val arscEntry = srcZip.getEntry("resources.arsc")
                            if (arscEntry != null && !arscEntry.isDirectory) {
                                streamEntry(arscEntry, "resources.arsc", storeUncompressed = true)
                            }

                            val latestRuntimeDexBytes = try {
                                extractStandaloneRuntimeDexes(context).firstOrNull()
                            } catch (_: Throwable) {
                                null
                            }
                            val runtimeDexNeedle = "Lcom/example/standalone/StandaloneRuntimeEngine;".toByteArray(StandardCharsets.UTF_8)

                            val entries = srcZip.entries()
                            while (entries.hasMoreElements()) {
                                val entry = try { entries.nextElement() } catch (_: Throwable) { break }
                                if (entry.isDirectory) continue
                                val name = entry.name
                                if (name == "resources.arsc" ||
                                    name == "assets/phantom_standalone_config.json" ||
                                    name == "stamp-cert-sha256" ||
                                    isMetaInfSignatureEntry(name)
                                ) {
                                    continue
                                }
                                try {
                                    val storeUncompressed = (entry.method == ZipEntry.STORED)
                                    if (latestRuntimeDexBytes != null &&
                                        name.startsWith("classes") &&
                                        name.endsWith(".dex") &&
                                        (entry.size in 1..2_500_000L || isGoogleFirstPartyPackage(targetPackageName))
                                    ) {
                                        val dexBytes = srcZip.getInputStream(entry).use { it.readBytes() }
                                        if (indexOfBytes(dexBytes, runtimeDexNeedle) >= 0) {
                                            writeInMemoryEntry(name, latestRuntimeDexBytes, storeUncompressed)
                                        } else {
                                            if (isGoogleFirstPartyPackage(targetPackageName)) {
                                                patchTargetDexBytecode(
                                                    dexBytes = dexBytes,
                                                    originalPkg = targetPackageName,
                                                    inPlace = true
                                                )
                                            }
                                            writeInMemoryEntry(name, dexBytes, storeUncompressed)
                                        }
                                    } else {
                                        streamEntry(entry, name, storeUncompressed)
                                    }
                                } catch (_: Throwable) {
                                    try { zipOut.closeEntry() } catch (_: Throwable) {}
                                }
                                val pct = (20 + ((countingOut.bytesWritten * 58L) / totalBytes)).toInt().coerceIn(20, 78)
                                if (pct - lastPct >= 5) {
                                    lastPct = pct
                                    onProgress(pct, syncMsg)
                                }
                            }

                            writeInMemoryEntry("assets/phantom_standalone_config.json", finalConfigBytes, storeUncompressed = false)
                            writtenNames.clear()
                            onProgress(80, syncMsg)
                            writeStreamingV1SignatureEntries(zipOut, v1Digests)
                            v1Digests.clear()
                        }
                    }
                }

                val signMsg = com.example.ui.i18n.tr(
                    "Re-signing APK (V1+V2)...",
                    "APK পুনরায় স্বাক্ষর (V1+V2) করা হচ্ছে...",
                    "APK पुनः साइन (V1+V2) किया जा रहा है..."
                )
                onProgress(82, signMsg)
                signApkV1AndV2(workingApkFile, workingApkFile) { signPct ->
                    onProgress(signPct.coerceIn(82, 95), signMsg)
                }

                if (!workingApkFile.exists() || workingApkFile.length() <= 10_000L) {
                    return null
                }

                val backupOldInternal = File(outDir, "${safeFileName}.bak_${syncSeq}.tmp")
                var movedOldToBackup = false
                if (finalInternalFile.exists()) {
                    movedOldToBackup = finalInternalFile.renameTo(backupOldInternal)
                    if (!movedOldToBackup) {
                        try { finalInternalFile.delete() } catch (_: Throwable) {}
                    }
                }
                if (!workingApkFile.renameTo(finalInternalFile)) {
                    workingApkFile.copyTo(finalInternalFile, overwrite = true)
                    try { workingApkFile.delete() } catch (_: Throwable) {}
                }
                if (movedOldToBackup) {
                    try { backupOldInternal.delete() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
                try { workingApkFile.delete() } catch (_: Throwable) {}
                return null
            } finally {
                try { workingApkFile.delete() } catch (_: Throwable) {}
            }

            if (!finalInternalFile.exists() || finalInternalFile.length() <= 10_000L) {
                return null
            }

            onProgress(97, syncMsg)
            val cleanLabel = appName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { targetPackageName }
            val publicFileName = "${cleanLabel}${idFileSuffix}_GhostDevice_${BUILDER_VERSION}.apk"
            val publicDownloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "GhostDeviceAPKs"
            )
            val publicFile = File(publicDownloadDir, publicFileName)

            val mediaStoreUri = publishApkToMediaStoreDownloads(context, finalInternalFile, publicFileName)
            if (!publicFile.exists() ||
                publicFile.length() != finalInternalFile.length() ||
                publicFile.lastModified() < finalInternalFile.lastModified()
            ) {
                try {
                    if (!publicDownloadDir.exists()) publicDownloadDir.mkdirs()
                    finalInternalFile.copyTo(publicFile, overwrite = true)
                } catch (_: Throwable) {
                    if (RootChecker.isDeviceRooted()) {
                        RootChecker.executeRootCommand(
                            "mkdir -p \"${publicDownloadDir.absolutePath}\" && " +
                                "cp \"${finalInternalFile.absolutePath}\" \"${publicFile.absolutePath}\" && " +
                                "chmod 644 \"${publicFile.absolutePath}\" 2>/dev/null || true"
                        )
                    }
                }
            }

            val rootDownloadFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${cleanLabel}${idFileSuffix}_GhostDevice.apk"
            )
            try {
                if (rootDownloadFile.exists()) {
                    finalInternalFile.copyTo(rootDownloadFile, overwrite = true)
                }
            } catch (_: Throwable) {}

            onProgress(
                100,
                com.example.ui.i18n.tr(
                    "Ready (${formatFileSize(finalInternalFile.length())})",
                    "প্রস্তুত (${formatFileSize(finalInternalFile.length())})",
                    "तैयार (${formatFileSize(finalInternalFile.length())})"
                )
            )
            return StandaloneApkArtifact(
                originalPackageName = targetPackageName,
                standalonePackageName = standalonePkg,
                appName = appName,
                outputFilePath = finalInternalFile.absolutePath,
                publicDownloadPath = publicFile.absolutePath,
                mediaStoreUriString = mediaStoreUri?.toString() ?: "",
                fileSizeBytes = finalInternalFile.length(),
                builtTimestampMs = actualBuiltTsMs,
                bakedDeviceModel = effectiveIdentity.deviceModel,
                bakedAndroidId = effectiveIdentity.androidId,
                bakedSpeedMultiplier = speedMultiplier,
                bakedSpeedEnabled = speedEnabled,
                bakedStepWalkEnabled = stepWalkEnabled,
                bakedRootBypassEnabled = rootBypassEnabled,
                bakedAdBypassEnabled = adBypassEnabled,
                bakedIdentitySpoofEnabled = identitySpoofEnabled,
                bakedReplaceMainApk = effectiveReplaceMain,
                isBuilding = false,
                progressPercent = 100,
                statusMessage = com.example.ui.i18n.tr(
                    "Ready (${formatFileSize(finalInternalFile.length())})",
                    "প্রস্তুত (${formatFileSize(finalInternalFile.length())})",
                    "तैयार (${formatFileSize(finalInternalFile.length())})"
                )
            )
        }
    }

    /**
     * Publishes the signed standalone APK into the host system's MediaStore.Downloads provider
     * (`content://media/external/downloads/...`) after aligning `ContentResolver` with the host UID.
     * Verifies that the written MediaStore entry has the exact expected byte size.
     */
    private fun publishApkToMediaStoreDownloads(
        context: Context,
        apkFile: File,
        displayFileName: String
    ): Uri? {
        if (Build.VERSION.SDK_INT < 29 || !apkFile.exists() || apkFile.length() <= 0L) return null
        val resolver = context.contentResolver
        alignContentResolverWithHostUid(context, resolver)

        val candidateNames = listOf(
            displayFileName,
            "${displayFileName.removeSuffix(".apk")}_${System.currentTimeMillis() % 100000}.apk"
        )

        for (name in candidateNames) {
            try {
                try {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(name)
                    )
                } catch (_: Throwable) {}

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/GhostDeviceAPKs"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                var bytesCopied = 0L
                resolver.openOutputStream(itemUri, "w")?.use { out ->
                    FileInputStream(apkFile).use { input ->
                        bytesCopied = input.copyTo(out, bufferSize = 65536)
                    }
                    out.flush()
                }
                if (bytesCopied != apkFile.length()) {
                    try { resolver.delete(itemUri, null, null) } catch (_: Throwable) {}
                    continue
                }

                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(itemUri, doneValues, null, null)

                val verifiedSize = try {
                    resolver.openFileDescriptor(itemUri, "r")?.use { it.statSize } ?: bytesCopied
                } catch (_: Throwable) {
                    bytesCopied
                }
                if (verifiedSize > 0L) {
                    return itemUri
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun buildIdFileSuffixForArtifact(artifact: StandaloneApkArtifact): String {
        val idMatch = Regex("\\.id([a-zA-Z0-9]+)$").find(artifact.standalonePackageName.trim())
        if (idMatch != null) return "_id${idMatch.groupValues[1]}"
        if (artifact.bakedReplaceMainApk ||
            artifact.standalonePackageName.trim() == artifact.originalPackageName.trim()
        ) {
            return "_replace"
        }
        return ""
    }

    private fun resolveExistingOrPublishMediaStoreUri(
        context: Context,
        artifact: StandaloneApkArtifact,
        apkFile: File
    ): Uri? {
        val resolver = context.contentResolver
        alignContentResolverWithHostUid(context, resolver)
        if (artifact.mediaStoreUriString.isNotBlank()) {
            try {
                val existingUri = Uri.parse(artifact.mediaStoreUriString)
                val size = resolver.openFileDescriptor(existingUri, "r")?.use { it.statSize } ?: -1L
                if (size == apkFile.length() && size > 0L) {
                    return existingUri
                }
            } catch (_: Throwable) {}
        }
        val cleanLabel = artifact.appName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { artifact.originalPackageName }
        val idFileSuffix = buildIdFileSuffixForArtifact(artifact)
        val publicFileName = "${cleanLabel}${idFileSuffix}_GhostDevice_${BUILDER_VERSION}.apk"
        val legacyPublicFileName = "${cleanLabel}${idFileSuffix}_Phantom_${BUILDER_VERSION}.apk"
        if (Build.VERSION.SDK_INT >= 29) {
            for (queryName in listOf(publicFileName, legacyPublicFileName)) {
                try {
                    resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(queryName),
                        "${MediaStore.MediaColumns._ID} DESC"
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            val rowUri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                            val size = try {
                                resolver.openFileDescriptor(rowUri, "r")?.use { it.statSize } ?: -1L
                            } catch (_: Throwable) {
                                -1L
                            }
                            if (size == apkFile.length() && size > 0L) {
                                return rowUri
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        return publishApkToMediaStoreDownloads(context, apkFile, publicFileName)
    }

    @Volatile
    private var activePermissionWatcherToken: Long = 0L

    private fun buildActiveFeaturesSummary(artifact: StandaloneApkArtifact): String {
        val list = mutableListOf<String>()
        if (artifact.bakedSpeedEnabled) {
            list.add(com.example.ui.i18n.tr("⚡ Speed Hold", "⚡ স্পিড হোল্ড", "⚡ स्पीड होल्ड"))
        }
        if (artifact.bakedStepWalkEnabled) {
            list.add(com.example.ui.i18n.tr("🚶 Location-Step Hold", "🚶 লোকেশন-স্টেপ হোল্ড", "🚶 लोकेशन-स्टेप होल्ड"))
        }
        if (artifact.bakedRootBypassEnabled) {
            list.add("🛡️ Root Hide")
        }
        if (artifact.bakedAdBypassEnabled) {
            list.add(com.example.ui.i18n.tr("🚫 Ad Skip", "🚫 অ্যাড স্কিপ", "🚫 ऐड स्किप"))
        }
        list.add(com.example.ui.i18n.tr("🔄 Clear & Unique Device", "🔄 ক্লিয়ার ও ইউনিক ডিভাইস", "🔄 क्लियर व यूनिक डिवाइस"))
        return list.joinToString(" • ")
    }

    private fun updateInstallUiProgress(
        artifact: StandaloneApkArtifact,
        percent: Int,
        stageTitle: String,
        stageDetail: String,
        isInstalling: Boolean = true,
        isCompleted: Boolean = false,
        isError: Boolean = false,
        canSwitchToCloneMode: Boolean = false
    ) {
        _installUiState.value = StandaloneInstallUiState(
            originalPackageName = artifact.originalPackageName,
            standalonePackageName = artifact.standalonePackageName,
            appName = artifact.appName,
            fileSizeBytes = artifact.fileSizeBytes,
            progressPercent = percent.coerceIn(0, 100),
            stageTitle = stageTitle,
            stageDetail = stageDetail,
            isInstalling = isInstalling,
            isCompleted = isCompleted,
            isError = isError,
            bakedDeviceModel = artifact.bakedDeviceModel,
            activeFeaturesSummary = buildActiveFeaturesSummary(artifact),
            canSwitchToCloneMode = canSwitchToCloneMode
        )
    }

    private fun markInstallationCompleted(
        context: Context,
        artifact: StandaloneApkArtifact,
        onStatus: (String) -> Unit
    ) {
        activeInstallMonitorToken = 0L
        val doneTitle = com.example.ui.i18n.tr(
            "Installation Complete! (100%)",
            "ইনস্টল সম্পূর্ণ হয়েছে! (১০০%)",
            "इंस्टॉलेशन पूरा हुआ! (100%)"
        )
        val doneDetail = com.example.ui.i18n.tr(
            "${artifact.appName} (${formatFileSize(artifact.fileSizeBytes)}) has been installed successfully with GhostDevice Engine.",
            "${artifact.appName} (${formatFileSize(artifact.fileSizeBytes)}) সফলভাবে GhostDevice সিস্টেমের সাথে ইনস্টল সম্পন্ন হয়েছে!",
            "${artifact.appName} (${formatFileSize(artifact.fileSizeBytes)}) सफलतापूर्वक GhostDevice सिस्टम के साथ इंस्टॉल हो गया है!"
        )
        updateInstallUiProgress(
            artifact = artifact,
            percent = 100,
            stageTitle = doneTitle,
            stageDetail = doneDetail,
            isInstalling = false,
            isCompleted = true,
            isError = false
        )
        Handler(Looper.getMainLooper()).post {
            onStatus(doneTitle)
            Toast.makeText(context, doneTitle, Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun queryInstalledSignatureSnapshot(context: Context, standalonePkg: String): Pair<Long, Long> {
        var lastUpdateMs = 0L
        var sourceModMs = 0L
        try {
            val pi = context.packageManager.getPackageInfo(standalonePkg, 0)
            if (pi != null) {
                lastUpdateMs = maxOf(lastUpdateMs, pi.lastUpdateTime)
                val src = pi.applicationInfo?.sourceDir
                if (!src.isNullOrBlank()) {
                    sourceModMs = maxOf(sourceModMs, File(src).lastModified())
                }
            }
        } catch (_: Throwable) {}
        try {
            val realAi = queryRealSystemApplicationInfo(standalonePkg)
            val src = realAi?.sourceDir
            if (!src.isNullOrBlank()) {
                val f = File(src)
                if (f.exists()) {
                    sourceModMs = maxOf(sourceModMs, f.lastModified())
                } else {
                    sourceModMs = maxOf(sourceModMs, 1L)
                }
            }
        } catch (_: Throwable) {}
        return Pair(lastUpdateMs, sourceModMs)
    }

    private enum class PackageConflictType {
        NONE,
        REMOVABLE_CONFLICT,
        READONLY_SYSTEM_CONFLICT
    }

    @Suppress("DEPRECATION")
    private fun checkInstalledPackageSignatureConflict(context: Context, targetPkg: String): PackageConflictType {
        val cleanPkg = targetPkg.trim()
        if (cleanPkg.isEmpty() || SIGNER_CERT_SHA256_HEX.isEmpty()) return PackageConflictType.NONE

        var installedCertSha256 = ""
        var appFlags = 0
        var isInstalled = false

        fun extractSigSha256(pi: android.content.pm.PackageInfo?): String {
            if (pi == null) return ""
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    val sInfo = pi.signingInfo
                    val sigs = sInfo?.apkContentsSigners ?: sInfo?.signingCertificateHistory
                    if (!sigs.isNullOrEmpty() && sigs[0] != null) {
                        val d = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray())
                        return d.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
                    }
                }
            } catch (_: Throwable) {}
            try {
                val sigs = pi.signatures
                if (!sigs.isNullOrEmpty() && sigs[0] != null) {
                    val d = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray())
                    return d.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
                }
            } catch (_: Throwable) {}
            return ""
        }

        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }

        // 1. Check real unhooked OS IPackageManager first (accurate even inside Dual Space)
        val rawPmBinder = getRealUnhookedServiceBinder("package")
        if (rawPmBinder != null) {
            try {
                val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
                val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).apply { isAccessible = true }
                val realIpm = asInterface.invoke(null, rawPmBinder)
                val userId = Process.myUid() / 100000
                val getPkgMethods = realIpm?.javaClass?.methods?.filter { it.name == "getPackageInfo" }.orEmpty()
                for (m in getPkgMethods) {
                    try {
                        m.isAccessible = true
                        val pTypes = m.parameterTypes
                        val pi = when {
                            pTypes.size == 3 && pTypes[0] == String::class.java && pTypes[1] == Long::class.javaPrimitiveType && pTypes[2] == Int::class.javaPrimitiveType ->
                                m.invoke(realIpm, cleanPkg, flags.toLong(), userId)
                            pTypes.size == 3 && pTypes[0] == String::class.java && pTypes[1] == Int::class.javaPrimitiveType && pTypes[2] == Int::class.javaPrimitiveType ->
                                m.invoke(realIpm, cleanPkg, flags, userId)
                            else -> null
                        } as? android.content.pm.PackageInfo
                        if (pi != null) {
                            isInstalled = true
                            appFlags = pi.applicationInfo?.flags ?: 0
                            installedCertSha256 = extractSigSha256(pi)
                            if (installedCertSha256.isNotEmpty()) break
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        // 2. Fallback to Context PackageManager
        if (!isInstalled || installedCertSha256.isEmpty()) {
            try {
                val pi = context.packageManager.getPackageInfo(cleanPkg, flags)
                if (pi != null) {
                    isInstalled = true
                    if (appFlags == 0) appFlags = pi.applicationInfo?.flags ?: 0
                    if (installedCertSha256.isEmpty()) {
                        installedCertSha256 = extractSigSha256(pi)
                    }
                }
            } catch (_: Throwable) {}
        }

        if (!isInstalled) return PackageConflictType.NONE
        if (installedCertSha256.equals(SIGNER_CERT_SHA256_HEX, ignoreCase = true)) {
            return PackageConflictType.NONE
        }

        val isSystem = (appFlags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (isSystem) {
            return if (RootChecker.isDeviceRooted()) {
                PackageConflictType.REMOVABLE_CONFLICT
            } else {
                PackageConflictType.READONLY_SYSTEM_CONFLICT
            }
        }
        return PackageConflictType.REMOVABLE_CONFLICT
    }

    private fun startInstallationCompletionWatcher(
        context: Context,
        artifact: StandaloneApkArtifact,
        baselineUpdateMs: Long,
        baselineSourceModMs: Long,
        monitorToken: Long,
        onStatus: (String) -> Unit
    ) {
        val standalonePkg = artifact.standalonePackageName
        val appCtx = context.applicationContext

        val pkgReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val pkg = intent?.data?.schemeSpecificPart ?: return
                if (pkg == standalonePkg && activeInstallMonitorToken == monitorToken) {
                    try { appCtx.unregisterReceiver(this) } catch (_: Throwable) {}
                    markInstallationCompleted(appCtx, artifact, onStatus)
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(pkgReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appCtx.registerReceiver(pkgReceiver, filter)
            }
        } catch (_: Throwable) {}

        Thread({
            val startMs = System.currentTimeMillis()
            var simulatedPct = 55
            try {
                while (System.currentTimeMillis() - startMs < 120_000L && activeInstallMonitorToken == monitorToken) {
                    try {
                        Thread.sleep(400L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (activeInstallMonitorToken != monitorToken) break

                    val (curUpdateMs, curSourceModMs) = queryInstalledSignatureSnapshot(appCtx, standalonePkg)
                    val newlyInstalledOrUpdated =
                        (curUpdateMs > 0L && (baselineUpdateMs <= 0L || curUpdateMs > baselineUpdateMs)) ||
                            (curSourceModMs > 0L && (baselineSourceModMs <= 0L || curSourceModMs > baselineSourceModMs))

                    if (newlyInstalledOrUpdated) {
                        markInstallationCompleted(appCtx, artifact, onStatus)
                        break
                    }

                    // Advance visual progress smoothly up to 95% while Android PackageInstaller is verifying & installing
                    val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
                    val targetPct = (55 + (elapsedSec * 3)).coerceAtMost(95)
                    if (targetPct > simulatedPct && activeInstallMonitorToken == monitorToken) {
                        simulatedPct = targetPct
                        val curState = _installUiState.value
                        if (curState != null && curState.isInstalling && !curState.isCompleted && curState.progressPercent < simulatedPct) {
                            val totalMb = formatFileSize(artifact.fileSizeBytes)
                            val copiedBytes = (artifact.fileSizeBytes * simulatedPct.toLong()) / 100L
                            val copiedMb = formatFileSize(copiedBytes)
                            updateInstallUiProgress(
                                artifact = artifact,
                                percent = simulatedPct,
                                stageTitle = com.example.ui.i18n.tr(
                                    "Installing ${artifact.appName}... ($simulatedPct%)",
                                    "${artifact.appName} ইনস্টল হচ্ছে... ($simulatedPct%)",
                                    "${artifact.appName} इंस्टॉल हो रहा है... ($simulatedPct%)"
                                ),
                                stageDetail = com.example.ui.i18n.tr(
                                    "Processed $copiedMb / $totalMb • Confirm 'Install' on the system prompt to finish",
                                    "প্রসেস সম্পন্ন: $copiedMb / $totalMb • স্ক্রিনে আসা 'Install' বাটনে চাপলেই ১০০% সম্পন্ন হবে",
                                    "प्रोसेस: $copiedMb / $totalMb • पूरा करने के लिए सिस्टम प्रॉम्प्ट पर 'Install' दबाएं"
                                ),
                                isInstalling = true,
                                isCompleted = false
                            )
                        }
                    }
                }
            } finally {
                try { appCtx.unregisterReceiver(pkgReceiver) } catch (_: Throwable) {}
            }
        }, "GhostDevice-Install-Watcher").start()
    }

    fun launchInstalledStandaloneApp(context: Context, standalonePkg: String): Boolean {
        val cleanPkg = standalonePkg.trim()
        if (cleanPkg.isEmpty()) return false
        val hostPkg = resolveHostCallingPackage(context)
        try {
            val normalLaunch = context.packageManager.getLaunchIntentForPackage(cleanPkg)
            if (normalLaunch != null) {
                normalLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (startActivityViaRealSystemServer(context, normalLaunch, hostPkg)) {
                    return true
                }
                context.startActivity(normalLaunch)
                return true
            }
        } catch (_: Throwable) {}

        // Query unhooked OS IPackageManager for the launcher Activity of `cleanPkg` (works inside Dual Space!)
        val rawPmBinder = getRealUnhookedServiceBinder("package")
        if (rawPmBinder != null) {
            try {
                val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
                val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).apply { isAccessible = true }
                val realIpm = asInterface.invoke(null, rawPmBinder)
                val userId = Process.myUid() / 100000
                for (cat in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_INFO, Intent.CATEGORY_DEFAULT)) {
                    val probe = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(cat)
                        setPackage(cleanPkg)
                    }
                    val queryMethods = realIpm?.javaClass?.methods?.filter { it.name == "queryIntentActivities" }.orEmpty()
                    for (qm in queryMethods) {
                        try {
                            qm.isAccessible = true
                            val pTypes = qm.parameterTypes
                            val rawRes = when {
                                pTypes.size == 4 && pTypes[0] == Intent::class.java && pTypes[1] == String::class.java && pTypes[2] == Long::class.javaPrimitiveType && pTypes[3] == Int::class.javaPrimitiveType ->
                                    qm.invoke(realIpm, probe, null, 0L, userId)
                                pTypes.size == 4 && pTypes[0] == Intent::class.java && pTypes[1] == String::class.java && pTypes[2] == Int::class.javaPrimitiveType && pTypes[3] == Int::class.javaPrimitiveType ->
                                    qm.invoke(realIpm, probe, null, 0, userId)
                                else -> null
                            } ?: continue
                            val list: List<*>? = when (rawRes) {
                                is List<*> -> rawRes
                                else -> try {
                                    rawRes.javaClass.getMethod("getList").apply { isAccessible = true }.invoke(rawRes) as? List<*>
                                } catch (_: Throwable) { null }
                            }
                            val firstRi = list?.firstOrNull() as? android.content.pm.ResolveInfo
                            val ai = firstRi?.activityInfo
                            if (ai != null && !ai.packageName.isNullOrBlank() && !ai.name.isNullOrBlank()) {
                                val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(cat)
                                    component = android.content.ComponentName(ai.packageName, ai.name)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                                }
                                if (startActivityViaRealSystemServer(context, launchIntent, hostPkg)) {
                                    return true
                                }
                                try {
                                    context.startActivity(launchIntent)
                                    return true
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
        return false
    }

    fun installStandaloneApk(context: Context, artifact: StandaloneApkArtifact, onStatus: (String) -> Unit = {}) {
        val mainHandler = Handler(Looper.getMainLooper())
        val cleanLabel = artifact.appName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { artifact.originalPackageName }
        val idFileSuffix = buildIdFileSuffixForArtifact(artifact)
        val publicDownloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GhostDeviceAPKs"
        )
        val legacyDownloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PhantomStandaloneAPKs"
        )
        val publicFile = File(publicDownloadDir, "${cleanLabel}${idFileSuffix}_GhostDevice_${BUILDER_VERSION}.apk")
        val legacyPublicFile = File(legacyDownloadDir, "${cleanLabel}${idFileSuffix}_Phantom_${BUILDER_VERSION}.apk")
        val rootDownloadFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "${cleanLabel}${idFileSuffix}_GhostDevice.apk"
        )

        val internalFile = File(artifact.outputFilePath)
        val secondaryFile = File(artifact.publicDownloadPath)
        val sourceFile = when {
            internalFile.exists() && internalFile.length() > 10_000L -> internalFile
            secondaryFile.exists() && secondaryFile.length() > 10_000L -> secondaryFile
            publicFile.exists() && publicFile.length() > 10_000L -> publicFile
            legacyPublicFile.exists() && legacyPublicFile.length() > 10_000L -> legacyPublicFile
            rootDownloadFile.exists() && rootDownloadFile.length() > 10_000L -> rootDownloadFile
            else -> null
        }

        if (sourceFile == null || !sourceFile.exists() || sourceFile.length() <= 0L) {
            val errMsg = com.example.ui.i18n.tr(
                "APK file not found, please tap the '🔄 Update' button.",
                "APK ফাইলটি পাওয়া যায়নি, অনুগ্রহ করে '🔄 আপডেট' বাটনে চাপুন।",
                "APK फ़ाइल नहीं मिली, कृपया '🔄 अपडेट' बटन दबाएं।"
            )
            updateInstallUiProgress(
                artifact = artifact,
                percent = 0,
                stageTitle = com.example.ui.i18n.tr("APK Not Found", "APK পাওয়া যায়নি", "APK नहीं मिला"),
                stageDetail = errMsg,
                isInstalling = false,
                isCompleted = false,
                isError = true
            )
            onStatus(errMsg)
            mainHandler.post {
                Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val monitorToken = System.currentTimeMillis()
        activeInstallMonitorToken = monitorToken
        var (baselineUpdateMs, baselineSourceModMs) = queryInstalledSignatureSnapshot(context, artifact.standalonePackageName)
        val totalSizeStr = formatFileSize(sourceFile.length())
        val isSamePackageReplace = artifact.standalonePackageName.trim() == artifact.originalPackageName.trim()
        val conflictType = if (isSamePackageReplace) {
            checkInstalledPackageSignatureConflict(context, artifact.standalonePackageName)
        } else {
            PackageConflictType.NONE
        }

        if (conflictType == PackageConflictType.READONLY_SYSTEM_CONFLICT) {
            val isYouTube = isYouTubePackage(artifact.originalPackageName)
            val sysErrTitle = com.example.ui.i18n.tr(
                if (isYouTube) "YouTube System Replace Blocked" else "System App Cannot Be Replaced",
                if (isYouTube) "YouTube মেইন অ্যাপ রিপ্লেস করা যাবে না" else "সিস্টেম অ্যাপ রিপ্লেস করা যাবে না",
                if (isYouTube) "YouTube मेन ऐप रिप्लेस नहीं हो सकता" else "सिस्टम ऐप को बदला नहीं जा सकता"
            )
            val sysErrDetail = com.example.ui.i18n.tr(
                if (isYouTube) "YouTube is a built-in system app with Google signature. Tap 'Without Main Apk Replace' below for 1-tap Ad-Free install!" else "${artifact.appName} is a built-in system app. Please select 'Without Main Apk Replace' to install it!",
                if (isYouTube) "YouTube ফোনের সিস্টেম অ্যাপ হওয়ায় নন-রুট ফোনে 'Main Apk Replace' ইনস্টল হয় না। নিচের 'Without Main Apk Replace' বাটনে চাপলেই অ্যাড-ফ্রি YouTube ইনস্টল হবে!" else "${artifact.appName} ফোনের সিস্টেম অ্যাপ হওয়ায় সরাসরি রিপ্লেস করা সম্ভব নয়। অনুগ্রহ করে 'Without Main Apk Replace' মোড সিলেক্ট করে বিল্ড ও ইনস্টল করুন!",
                if (isYouTube) "YouTube एक सिस्टम ऐप है। बिना रूट वाले फ़ोन में नीचे दिए गए 'Without Main Apk Replace' बटन से विज्ञापन-मुक्त YouTube इंस्टॉल करें!" else "${artifact.appName} एक सिस्टम ऐप है। कृपया इसे इंस्टॉल करने के लिए 'Without Main Apk Replace' चुनें!"
            )
            updateInstallUiProgress(
                artifact = artifact.copy(fileSizeBytes = sourceFile.length()),
                percent = 0,
                stageTitle = sysErrTitle,
                stageDetail = sysErrDetail,
                isInstalling = false,
                isCompleted = false,
                isError = true,
                canSwitchToCloneMode = true
            )
            mainHandler.post {
                onStatus(sysErrDetail)
                Toast.makeText(context, sysErrDetail, Toast.LENGTH_LONG).show()
            }
            return
        }

        updateInstallUiProgress(
            artifact = artifact.copy(fileSizeBytes = sourceFile.length()),
            percent = 18,
            stageTitle = com.example.ui.i18n.tr(
                "Preparing ${artifact.appName} Installer... (18%)",
                "${artifact.appName} ইনস্টল প্রস্তুত করা হচ্ছে... (18%)",
                "${artifact.appName} इंस्टॉलर तैयार किया जा रहा है... (18%)"
            ),
            stageDetail = com.example.ui.i18n.tr(
                "Verifying signed GhostDevice package ($totalSizeStr)...",
                "সাইন করা GhostDevice প্যাকেজ ($totalSizeStr) যাচাই করা হচ্ছে...",
                "साइन किया गया GhostDevice पैकेज ($totalSizeStr) जांचा जा रहा है..."
            ),
            isInstalling = true,
            isCompleted = false
        )

        if (conflictType != PackageConflictType.REMOVABLE_CONFLICT) {
            startInstallationCompletionWatcher(
                context = context,
                artifact = artifact.copy(fileSizeBytes = sourceFile.length()),
                baselineUpdateMs = baselineUpdateMs,
                baselineSourceModMs = baselineSourceModMs,
                monitorToken = monitorToken,
                onStatus = onStatus
            )
        }

        try {
            sourceFile.setReadable(true, false)
        } catch (_: Throwable) {}

        // Sync copies to public Download folder and root pm install asynchronously so we NEVER block the installer UI!
        Thread {
            try {
                if (!publicDownloadDir.exists()) {
                    try { publicDownloadDir.mkdirs() } catch (_: Throwable) {}
                }
                if (publicFile.absolutePath != sourceFile.absolutePath &&
                    (!publicFile.exists() || publicFile.length() != sourceFile.length() || publicFile.lastModified() < sourceFile.lastModified())
                ) {
                    sourceFile.copyTo(publicFile, overwrite = true)
                }
                publicFile.setReadable(true, false)
            } catch (_: Throwable) {}
            try {
                if (rootDownloadFile.absolutePath != sourceFile.absolutePath &&
                    (!rootDownloadFile.exists() || rootDownloadFile.length() != sourceFile.length() || rootDownloadFile.lastModified() < sourceFile.lastModified())
                ) {
                    sourceFile.copyTo(rootDownloadFile, overwrite = true)
                }
                rootDownloadFile.setReadable(true, false)
            } catch (_: Throwable) {}
            if (RootChecker.isDeviceRooted() && conflictType != PackageConflictType.REMOVABLE_CONFLICT) {
                try {
                    val tmpPath = "/data/local/tmp/${sourceFile.name}"
                    RootChecker.executeRootCommand(
                        "cp \"${sourceFile.absolutePath}\" \"$tmpPath\" && chmod 644 \"$tmpPath\" && pm install -r -d \"$tmpPath\" 2>/dev/null || true"
                    )
                } catch (_: Throwable) {}
            }
        }.start()

        val hostPkg = resolveHostCallingPackage(context)
        val isInsideVirtualContainer = (hostPkg != context.packageName) ||
            context.packageName.contains("dual.space") ||
            hostPkg.contains("dual.space") ||
            hostPkg.contains("clone", ignoreCase = true) ||
            hostPkg.contains("virtual", ignoreCase = true) ||
            hostPkg.contains("parallel", ignoreCase = true)

        // Prefer internalFile (in context.filesDir/standalone_apks) for FileProvider so Scoped Storage on Android 10-16 never blocks openFile!
        val fileProviderTarget = if (internalFile.exists() && internalFile.length() > 10_000L) internalFile else sourceFile
        val fileProviderUri: Uri? = try {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fileProviderTarget
            )
        } catch (_: Throwable) { null }

        val phantomProviderUri: Uri? = try {
            Uri.parse("content://com.aistudio.phantomcore.provider/standalone_apk/${fileProviderTarget.name}")
        } catch (_: Throwable) { null }

        val fileUri: Uri? = try {
            val builder = StrictMode.VmPolicy.Builder()
            StrictMode.setVmPolicy(builder.build())
            val readableExternal = if (publicFile.exists() && publicFile.length() == sourceFile.length()) publicFile else sourceFile
            Uri.fromFile(readableExternal)
        } catch (_: Throwable) { null }

        val executeLaunchWorker = Runnable {
            if (conflictType == PackageConflictType.REMOVABLE_CONFLICT && activeInstallMonitorToken == monitorToken) {
                updateInstallUiProgress(
                    artifact = artifact.copy(fileSizeBytes = sourceFile.length()),
                    percent = 26,
                    stageTitle = com.example.ui.i18n.tr(
                        "Removing Original ${artifact.appName}... (26%)",
                        "পূর্বের মূল ${artifact.appName} আনইনস্টল করা হচ্ছে... (26%)",
                        "पुराना ${artifact.appName} हटाया जा रहा है... (26%)"
                    ),
                    stageDetail = com.example.ui.i18n.tr(
                        "Tap 'OK' to remove conflicting Play Store signature — GhostDevice APK will install automatically right after!",
                        "Play Store সিগনেচার কনফ্লিক্ট এড়াতে 'OK' চাপুন — আনইনস্টল হতেই স্বয়ংক্রিয়ভাবে নতুন APK ইনস্টল শুরু হবে!",
                        "सिग्नेचर कॉन्फ़्लिक्ट हटाने के लिए 'OK' दबाएं — इसके तुरंत बाद नया APK अपने आप इंस्टॉल हो जाएगा!"
                    ),
                    isInstalling = true,
                    isCompleted = false
                )

                val isSystemPreinstalledApp = try {
                    val sysAi = queryRealSystemApplicationInfo(artifact.standalonePackageName)
                        ?: context.packageManager.getApplicationInfo(artifact.standalonePackageName, 0)
                    (sysAi.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                } catch (_: Throwable) {
                    false
                }

                var removedByRoot = false
                if (RootChecker.isDeviceRooted()) {
                    try {
                        val tmpPath = "/data/local/tmp/${sourceFile.name}"
                        val uninstallCmd =
                            "pm uninstall-system-updates ${artifact.standalonePackageName} 2>/dev/null || true; " +
                            "pm uninstall -k --user 0 ${artifact.standalonePackageName} 2>/dev/null || " +
                            "pm uninstall --user 0 ${artifact.standalonePackageName} 2>/dev/null || " +
                            "pm uninstall ${artifact.standalonePackageName} 2>/dev/null || true"
                        RootChecker.executeRootCommand(uninstallCmd)
                        removedByRoot = (checkInstalledPackageSignatureConflict(context, artifact.standalonePackageName) == PackageConflictType.NONE)
                        if (removedByRoot) {
                            RootChecker.executeRootCommand(
                                "cp \"${sourceFile.absolutePath}\" \"$tmpPath\" && chmod 644 \"$tmpPath\" && pm install -r -d \"$tmpPath\" 2>/dev/null || true"
                            )
                        }
                    } catch (_: Throwable) {}
                }

                if (!removedByRoot && !isSystemPreinstalledApp) {
                    val uninstallIntent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${artifact.standalonePackageName}")).apply {
                        putExtra(Intent.EXTRA_RETURN_RESULT, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    var launchedUninstall = false
                    if (isInsideVirtualContainer) {
                        launchedUninstall = startActivityViaRealSystemServer(context, uninstallIntent, hostPkg)
                    }
                    if (!launchedUninstall) {
                        if (!startActivityViaRealSystemServer(context, uninstallIntent, hostPkg)) {
                            mainHandler.post {
                                try { context.startActivity(uninstallIntent) } catch (_: Throwable) {}
                            }
                        }
                    }
                    val promptMsg = com.example.ui.i18n.tr(
                        "Please confirm uninstall of original ${artifact.appName} — new APK will install automatically!",
                        "অনুগ্রহ করে পূর্বের ${artifact.appName} আনইনস্টল নিশ্চিত করুন — সাথে সাথেই নতুন APK ইনস্টল ওপেন হবে!",
                        "कृपया पुराने ${artifact.appName} को अनइंस्टॉल करें — नया APK तुरंत अपने आप इंस्टॉल होगा!"
                    )
                    mainHandler.post {
                        onStatus(promptMsg)
                        Toast.makeText(context, promptMsg, Toast.LENGTH_LONG).show()
                    }

                    val waitStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - waitStart < 60_000L && activeInstallMonitorToken == monitorToken) {
                        try { Thread.sleep(350L) } catch (_: InterruptedException) { break }
                        if (checkInstalledPackageSignatureConflict(context, artifact.standalonePackageName) == PackageConflictType.NONE) {
                            break
                        }
                    }
                }

                if (activeInstallMonitorToken != monitorToken) return@Runnable
                if (checkInstalledPackageSignatureConflict(context, artifact.standalonePackageName) != PackageConflictType.NONE) {
                    val cancelTitle = com.example.ui.i18n.tr(
                        "Original App Still Installed",
                        "পূর্বের অ্যাপটি এখনো আনইনস্টল হয়নি",
                        "पुराना ऐप अभी भी इंस्टॉल है"
                    )
                    val cancelDetail = com.example.ui.i18n.tr(
                        "To use 'Main Apk Replace', uninstall the original ${artifact.appName} first, or switch to 'Without Main Apk Replace'.",
                        "'Main Apk Replace' ব্যবহার করতে পূর্বের ${artifact.appName} আনইনস্টল করুন, অথবা 'Without Main Apk Replace' সিলেক্ট করুন।",
                        "'Main Apk Replace' के लिए पहले पुराना ${artifact.appName} अनइंस्टॉल करें, या 'Without Main Apk Replace' चुनें।"
                    )
                    updateInstallUiProgress(
                        artifact = artifact.copy(fileSizeBytes = sourceFile.length()),
                        percent = 0,
                        stageTitle = cancelTitle,
                        stageDetail = cancelDetail,
                        isInstalling = false,
                        isCompleted = false,
                        isError = true,
                        canSwitchToCloneMode = isSamePackageReplace
                    )
                    mainHandler.post {
                        onStatus(cancelDetail)
                        Toast.makeText(context, cancelDetail, Toast.LENGTH_LONG).show()
                    }
                    return@Runnable
                }

                baselineUpdateMs = 0L
                baselineSourceModMs = 0L
                startInstallationCompletionWatcher(
                    context = context,
                    artifact = artifact.copy(fileSizeBytes = sourceFile.length()),
                    baselineUpdateMs = 0L,
                    baselineSourceModMs = 0L,
                    monitorToken = monitorToken,
                    onStatus = onStatus
                )
            }

            if (activeInstallMonitorToken == monitorToken) {
                updateInstallUiProgress(
                    artifact = artifact.copy(fileSizeBytes = sourceFile.length()),
                    percent = 38,
                    stageTitle = com.example.ui.i18n.tr(
                        "Staging APK Stream... (38%)",
                        "APK ফাইল ইনস্টলারে পাঠানো হচ্ছে... (38%)",
                        "APK फ़ाइल इंस्टॉलर को भेजी जा रही है... (38%)"
                    ),
                    stageDetail = com.example.ui.i18n.tr(
                        "Streaming ${formatFileSize(sourceFile.length() * 38L / 100L)} / $totalSizeStr to Android PackageInstaller",
                        "অ্যান্ড্রয়েড প্যাকেজ ইনস্টলারে পাঠানো হচ্ছে (${formatFileSize(sourceFile.length() * 38L / 100L)} / $totalSizeStr)",
                        "एंड्रॉइड पैकेज इंस्टॉलर को भेजा जा रहा है (${formatFileSize(sourceFile.length() * 38L / 100L)} / $totalSizeStr)"
                    ),
                    isInstalling = true,
                    isCompleted = false
                )
            }
            // Resolve or publish MediaStore URI off the main thread so large APKs (135MB+) never freeze the UI
            val mediaUri: Uri? = if (isInsideVirtualContainer || fileProviderUri == null) {
                resolveExistingOrPublishMediaStoreUri(context, artifact, sourceFile)
            } else if (artifact.mediaStoreUriString.isNotBlank()) {
                try { Uri.parse(artifact.mediaStoreUriString) } catch (_: Throwable) { null }
            } else {
                null
            }

            // Inside virtual containers (Dual Space), the real OS PackageInstaller cannot read virtual-only FileProvider URIs
            // (`content://com.aistudio.phantomcore.kxmpzq.fileprovider/...`), so we only pass MediaStore URIs (or file:// on older Android)
            // to `launchPackageInstallerIntent`, and use `installViaPackageInstallerSession` (which streams bytes over a pipe) as backup!
            val urisToTry = if (isInsideVirtualContainer) {
                listOfNotNull(mediaUri, fileUri).distinct()
            } else {
                listOfNotNull(fileProviderUri, mediaUri, phantomProviderUri, fileUri).distinct()
            }

            var installed = false
            for (uri in urisToTry) {
                if (launchPackageInstallerIntent(context, uri)) {
                    installed = true
                    break
                }
            }

            if (!installed) {
                try {
                    installed = installViaPackageInstallerSession(
                        context = context,
                        apkFile = sourceFile,
                        standalonePkg = artifact.standalonePackageName,
                        appName = artifact.appName,
                        fallbackUri = mediaUri ?: fileProviderUri ?: phantomProviderUri,
                        onStatus = onStatus
                    )
                } catch (_: Throwable) {}
            }

            if (!installed && !isInsideVirtualContainer && urisToTry.isNotEmpty()) {
                try {
                    val primaryUri = urisToTry.first()
                    val chooserIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(primaryUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    mainHandler.post {
                        try {
                            context.startActivity(
                                Intent.createChooser(
                                    chooserIntent,
                                    com.example.ui.i18n.tr(
                                        "Install ${artifact.appName}",
                                        "${artifact.appName} ইনস্টল করুন",
                                        "${artifact.appName} इंस्टॉल करें"
                                    )
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Throwable) {}
                    }
                    installed = true
                } catch (_: Throwable) {}
            }

            if (activeInstallMonitorToken == monitorToken && _installUiState.value?.isCompleted != true) {
                if (installed) {
                    updateInstallUiProgress(
                        artifact = artifact.copy(fileSizeBytes = sourceFile.length()),
                        percent = 55,
                        stageTitle = com.example.ui.i18n.tr(
                            "Installing ${artifact.appName}... (55%)",
                            "${artifact.appName} ইনস্টল হচ্ছে... (55%)",
                            "${artifact.appName} इंस्टॉल हो रहा है... (55%)"
                        ),
                        stageDetail = com.example.ui.i18n.tr(
                            "System Installer opened ($totalSizeStr) • Tap 'Install' on screen to complete 100%",
                            "সিস্টেম ইনস্টলার ওপেন হয়েছে ($totalSizeStr) • স্ক্রিনে 'Install' চাপলেই ১০০% সম্পন্ন হবে",
                            "सिस्टम इंस्टॉलर खुल गया है ($totalSizeStr) • 100% पूरा करने के लिए स्क्रीन पर 'Install' दबाएं"
                        ),
                        isInstalling = true,
                        isCompleted = false
                    )
                }
            }

            val statusMsg = if (installed) {
                com.example.ui.i18n.tr(
                    "${artifact.appName} installer opened! (Also saved in Download folder)",
                    "${artifact.appName} ইনস্টলার ওপেন করা হয়েছে! (Download ফোল্ডারেও সংরক্ষিত)",
                    "${artifact.appName} इंस्टॉलर खोला गया! (Download फ़ोल्डर में भी सहेजा गया)"
                )
            } else {
                com.example.ui.i18n.tr(
                    "${artifact.appName}: Please install the APK from Download folder.",
                    "${artifact.appName}: Download ফোল্ডার থেকে APK টি ইনস্টল করুন।",
                    "${artifact.appName}: कृपया Download फ़ोल्डर से APK इंस्टॉल करें।"
                )
            }
            mainHandler.post {
                onStatus(statusMsg)
                Toast.makeText(context, statusMsg, Toast.LENGTH_LONG).show()
            }
        }

        // Check Unknown Sources Permission on Android 8.0+ (API 26+)
        // IMPORTANT: Never launch ACTION_MANAGE_UNKNOWN_APP_SOURCES and ACTION_VIEW at the exact same time,
        // as the second startActivity covers the Settings screen and causes PackageInstaller to abort immediately!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInsideVirtualContainer) {
            val canInstall = try {
                context.packageManager.canRequestPackageInstalls()
            } catch (_: Throwable) {
                true
            }
            if (!canInstall) {
                val watchToken = System.currentTimeMillis()
                activePermissionWatcherToken = watchToken
                var openedSettings = false
                mainHandler.post {
                    try {
                        val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                            if (context !is android.app.Activity) {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        }
                        context.startActivity(permIntent)
                        openedSettings = true
                        val permMsg = com.example.ui.i18n.tr(
                            "Please turn ON 'Allow from this source' — installation will start automatically!",
                            "অনুগ্রহ করে 'Allow from this source' চালু করুন — চালু করলেই স্বয়ংক্রিয়ভাবে ইনস্টল শুরু হবে!",
                            "कृपया 'Allow from this source' चालू करें — चालू करते ही इंस्टॉलेशन अपने आप शुरू हो जाएगा!"
                        )
                        onStatus(permMsg)
                        Toast.makeText(context, permMsg, Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {
                        openedSettings = false
                    }
                    if (!openedSettings) {
                        Thread(executeLaunchWorker, "GhostDevice-Install-Launcher").start()
                    }
                }
                // Background watcher: the instant the user toggles "Allow from this source" ON, launch the installer!
                Thread {
                    val startMs = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startMs < 60_000L && activePermissionWatcherToken == watchToken) {
                        try {
                            Thread.sleep(350L)
                        } catch (_: InterruptedException) {
                            break
                        }
                        val grantedNow = try {
                            context.packageManager.canRequestPackageInstalls()
                        } catch (_: Throwable) {
                            false
                        }
                        if (grantedNow && activePermissionWatcherToken == watchToken) {
                            activePermissionWatcherToken = 0L
                            Thread(executeLaunchWorker, "GhostDevice-Install-Launcher").start()
                            break
                        }
                    }
                }.start()
                return
            }
        }

        Thread(executeLaunchWorker, "GhostDevice-Install-Launcher").start()
    }

    /**
     * Finds the real OS IBinder activity token (`mToken`) of the currently visible Activity
     * (e.g. `com.clone.android.dual.space.VMDisplayActivity` inside Dual Space or `MainActivity`).
     * Passing `resultTo = activityToken` with `requestCode = -1` ensures Android 10-16's
     * `BackgroundActivityStartController` treats the launch as a foreground-activity-initiated launch!
     */
    private fun findForegroundActivityToken(context: Context): android.os.IBinder? {
        try {
            var cur: Context? = context
            var depth = 0
            while (cur != null && depth++ < 8) {
                if (cur is android.app.Activity) {
                    val tokenField = android.app.Activity::class.java.getDeclaredField("mToken").apply { isAccessible = true }
                    val token = tokenField.get(cur) as? android.os.IBinder
                    if (token != null) return token
                }
                cur = if (cur is android.content.ContextWrapper) cur.baseContext else null
            }
        } catch (_: Throwable) {}
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val curAt = atClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null) ?: return null
            val mActivitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activitiesMap = mActivitiesField.get(curAt) as? Map<*, *> ?: return null
            var fallbackToken: android.os.IBinder? = null
            for ((key, record) in activitiesMap) {
                val token = key as? android.os.IBinder ?: continue
                if (fallbackToken == null) fallbackToken = token
                if (record != null) {
                    val paused = try {
                        record.javaClass.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(record)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!paused) return token
                }
            }
            fallbackToken
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Queries the real unhooked OS `IPackageManager` to discover the true system PackageInstaller
     * `ComponentName`s (`packageName` + `activityName`) on the device, bypassing Dual Space's
     * virtual PackageManager which redirects `application/vnd.android.package-archive` to itself.
     */
    private fun queryRealSystemInstallerComponents(apkUri: Uri, hostPkg: String, guestPkg: String): List<android.content.ComponentName> {
        val result = LinkedHashSet<android.content.ComponentName>()
        val rawPmBinder = getRealUnhookedServiceBinder("package") ?: return emptyList()
        try {
            val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).apply { isAccessible = true }
            val realIpm = asInterface.invoke(null, rawPmBinder) ?: return emptyList()
            val userId = Process.myUid() / 100000
            val probeIntents = listOf(
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                },
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                }
            )
            for (probe in probeIntents) {
                // 1. queryIntentActivities on real OS IPackageManager
                val queryMethods = realIpm.javaClass.methods.filter { it.name == "queryIntentActivities" }
                for (qm in queryMethods) {
                    try {
                        qm.isAccessible = true
                        val pTypes = qm.parameterTypes
                        val rawRes = when {
                            pTypes.size == 4 && pTypes[0] == Intent::class.java && pTypes[1] == String::class.java && pTypes[2] == Long::class.javaPrimitiveType && pTypes[3] == Int::class.javaPrimitiveType ->
                                qm.invoke(realIpm, probe, "application/vnd.android.package-archive", 0L, userId)
                            pTypes.size == 4 && pTypes[0] == Intent::class.java && pTypes[1] == String::class.java && pTypes[2] == Int::class.javaPrimitiveType && pTypes[3] == Int::class.javaPrimitiveType ->
                                qm.invoke(realIpm, probe, "application/vnd.android.package-archive", 0, userId)
                            else -> null
                        } ?: continue
                        val list: List<*>? = when (rawRes) {
                            is List<*> -> rawRes
                            else -> try {
                                rawRes.javaClass.getMethod("getList").apply { isAccessible = true }.invoke(rawRes) as? List<*>
                            } catch (_: Throwable) {
                                null
                            }
                        }
                        list?.forEach { item ->
                            val ri = item as? android.content.pm.ResolveInfo ?: return@forEach
                            val ai = ri.activityInfo ?: return@forEach
                            val pkg = ai.packageName ?: return@forEach
                            val cls = ai.name ?: return@forEach
                            if (pkg != hostPkg && pkg != guestPkg && !pkg.contains("dual.space", ignoreCase = true)) {
                                result.add(android.content.ComponentName(pkg, cls))
                            }
                        }
                    } catch (_: Throwable) {}
                }
                // 2. resolveIntent on real OS IPackageManager
                val resolveMethods = realIpm.javaClass.methods.filter { it.name == "resolveIntent" }
                for (rm in resolveMethods) {
                    try {
                        rm.isAccessible = true
                        val pTypes = rm.parameterTypes
                        val ri = when {
                            pTypes.size == 4 && pTypes[0] == Intent::class.java && pTypes[1] == String::class.java && pTypes[2] == Long::class.javaPrimitiveType && pTypes[3] == Int::class.javaPrimitiveType ->
                                rm.invoke(realIpm, probe, "application/vnd.android.package-archive", 0L, userId)
                            pTypes.size == 4 && pTypes[0] == Intent::class.java && pTypes[1] == String::class.java && pTypes[2] == Int::class.javaPrimitiveType && pTypes[3] == Int::class.javaPrimitiveType ->
                                rm.invoke(realIpm, probe, "application/vnd.android.package-archive", 0, userId)
                            else -> null
                        } as? android.content.pm.ResolveInfo
                        val ai = ri?.activityInfo
                        if (ai != null && !ai.packageName.isNullOrBlank() && !ai.name.isNullOrBlank() &&
                            ai.packageName != "android" && ai.packageName != hostPkg && ai.packageName != guestPkg &&
                            !ai.packageName.contains("dual.space", ignoreCase = true)
                        ) {
                            result.add(android.content.ComponentName(ai.packageName, ai.name))
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return result.toList()
    }

    /**
     * Grants URI read permission directly via the real unhooked OS `activity` service (`IActivityManager`)
     * so that even inside Dual Space, the real OS `PackageInstaller` is authorized to read `apkUri`.
     */
    private fun grantUriPermissionViaRealSystemServer(hostPkg: String, targetPkg: String, uri: Uri) {
        val rawAmBinder = getRealUnhookedServiceBinder("activity") ?: return
        try {
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).apply { isAccessible = true }
            val realAm = asInterface.invoke(null, rawAmBinder) ?: return
            val userId = Process.myUid() / 100000
            val appThread: Any? = try {
                val atClass = Class.forName("android.app.ActivityThread")
                val curAt = atClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
                curAt?.javaClass?.getDeclaredMethod("getApplicationThread")?.apply { isAccessible = true }?.invoke(curAt)
            } catch (_: Throwable) {
                null
            }
            val grantMethods = realAm.javaClass.methods.filter { it.name == "grantUriPermission" }
            for (gm in grantMethods) {
                try {
                    gm.isAccessible = true
                    val pTypes = gm.parameterTypes
                    if (pTypes.size == 6) {
                        gm.invoke(realAm, appThread, targetPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION, userId)
                    } else if (pTypes.size == 5) {
                        gm.invoke(realAm, appThread, targetPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION, userId)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * Launches an external system Activity (such as Android's PackageInstaller) by talking
     * directly to the real OS `activity_task` (API 29+) or `activity` (API 24-28) IBinder via
     * `ServiceManager.getIServiceManager().getService(...)`.
     *
     * CRITICAL AOSP INVARIANT:
     * In `IActivityTaskManager.startActivity`, `requestCode` (at `intentIdx + 4`) MUST be `-1`!
     * Passing `0` when `resultTo == null` causes AOSP `ActivityStarter.executeRequest` to abort
     * immediately with `ActivityManager.START_NOT_ACTIVITY` (`-3`).
     */
    private fun startActivityViaRealSystemServer(
        context: Context,
        intent: Intent,
        hostPkg: String
    ): Boolean {
        val resolvedType = intent.type ?: "application/vnd.android.package-archive"
        val userId = Process.myUid() / 100000
        val fgToken = findForegroundActivityToken(context)

        val appThread: Any? = try {
            val atClass = Class.forName("android.app.ActivityThread")
            val curAt = atClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            curAt?.javaClass?.getDeclaredMethod("getApplicationThread")?.apply { isAccessible = true }?.invoke(curAt)
        } catch (_: Throwable) {
            null
        }

        val serviceCandidates = if (Build.VERSION.SDK_INT >= 29) {
            listOf(
                "activity_task" to "android.app.IActivityTaskManager\$Stub",
                "activity" to "android.app.IActivityManager\$Stub"
            )
        } else {
            listOf("activity" to "android.app.IActivityManager\$Stub")
        }

        // Try first with the foreground Activity's mToken (so Android 10-16 BAL controller sees the
        // resumed top Activity `VMDisplayActivity`), then fallback to `resultTo = null` + NEW_TASK.
        val tokenCandidates = if (fgToken != null) listOf<android.os.IBinder?>(fgToken, null) else listOf<android.os.IBinder?>(null)

        for (resultToToken in tokenCandidates) {
            val launchIntent = Intent(intent).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (resultToToken == null) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (hostPkg != context.packageName) {
                    removeExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME)
                }
            }
            for ((svcName, stubClassName) in serviceCandidates) {
                val rawBinder = getRealUnhookedServiceBinder(svcName) ?: continue
                try {
                    val stubClass = Class.forName(stubClassName)
                    val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).apply { isAccessible = true }
                    val realService = asInterface.invoke(null, rawBinder) ?: continue
                    val startMethods = realService.javaClass.methods.filter { it.name == "startActivity" }
                    for (m in startMethods) {
                        val pTypes = m.parameterTypes
                        if (pTypes.size < 10) continue
                        // Standard AOSP startActivity signature:
                        // [0] IApplicationThread caller
                        // [1] String callingPackage
                        // [2] String callingFeatureId (API 30+, when intentIdx == 3)
                        // [intentIdx] Intent intent
                        // [intentIdx + 1] String resolvedType
                        // [intentIdx + 2] IBinder resultTo
                        // [intentIdx + 3] String resultWho
                        // [intentIdx + 4] int requestCode  <-- MUST BE -1 !
                        // [intentIdx + 5] int startFlags   <-- 0
                        // [intentIdx + 6] ProfilerInfo profilerInfo
                        // [intentIdx + 7] Bundle bOptions
                        val intentIdx = pTypes.indexOfFirst { it == Intent::class.java }
                        if (intentIdx < 2 || intentIdx + 4 >= pTypes.size || pTypes[intentIdx + 1] != String::class.java) continue
                        val args = arrayOfNulls<Any?>(pTypes.size)
                        for (i in pTypes.indices) {
                            val pt = pTypes[i]
                            args[i] = when {
                                i == 0 -> appThread
                                i == 1 && pt == String::class.java -> hostPkg
                                i == 2 && intentIdx == 3 && pt == String::class.java -> null
                                i == intentIdx -> launchIntent
                                i == intentIdx + 1 -> resolvedType
                                i == intentIdx + 2 -> resultToToken
                                i == intentIdx + 3 -> null
                                i == intentIdx + 4 && pt == Int::class.javaPrimitiveType -> -1 // requestCode = -1 !
                                i == intentIdx + 5 && pt == Int::class.javaPrimitiveType -> 0  // startFlags = 0
                                pt == Int::class.javaPrimitiveType -> if (i == pTypes.size - 1 && pTypes.size > 11) userId else 0
                                pt == Long::class.javaPrimitiveType -> 0L
                                pt == Boolean::class.javaPrimitiveType -> false
                                else -> null
                            }
                        }
                        try {
                            m.isAccessible = true
                            val result = m.invoke(realService, *args) as? Int ?: -99
                            // ActivityManager.START_SUCCESS == 0, START_RETURN_INTENT_TO_CALLER == 1,
                            // START_TASK_TO_FRONT == 2, START_DELIVERED_TO_TOP == 3
                            if (result >= 0) {
                                return true
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun launchPackageInstallerIntent(context: Context, apkUri: Uri): Boolean {
        val pm = context.packageManager
        val hostPkg = resolveHostCallingPackage(context)
        val isVirtualHost = hostPkg != context.packageName ||
            hostPkg.contains("dual", ignoreCase = true) ||
            hostPkg.contains("clone", ignoreCase = true) ||
            hostPkg.contains("virtual", ignoreCase = true) ||
            hostPkg.contains("parallel", ignoreCase = true)

        // When inside a virtual container (Dual Space), the real OS PackageInstaller cannot read a virtual guest's
        // FileProvider authority (`com.aistudio.phantomcore.kxmpzq.fileprovider`). Skip straight to MediaStore URI or Session!
        if (isVirtualHost && apkUri.scheme == "content") {
            val auth = apkUri.authority ?: ""
            if (auth.startsWith(context.packageName) || auth == "com.aistudio.phantomcore.provider") {
                return false
            }
        }

        val realInstallerComponents = queryRealSystemInstallerComponents(apkUri, hostPkg, context.packageName)

        val targetPkgs = LinkedHashSet<String>().apply {
            realInstallerComponents.forEach { add(it.packageName) }
            add("com.google.android.packageinstaller")
            add("com.android.packageinstaller")
            add("com.miui.packageinstaller")
            add("com.samsung.android.packageinstaller")
            add("com.coloros.phonemanager")
            add("com.oplus.appdetail")
            add("com.vivo.abe")
            add(hostPkg)
            add(context.packageName)
        }

        // Discover any additional system package installer packages via PackageManager
        try {
            val probe = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
            }
            val resolves = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            for (r in resolves) {
                val p = r.activityInfo?.packageName
                if (!p.isNullOrBlank()) targetPkgs.add(p)
            }
        } catch (_: Throwable) {}

        // Grant READ permission via both Context and unhooked OS IActivityManager
        if (apkUri.scheme == "content") {
            for (pkg in targetPkgs) {
                try {
                    context.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Throwable) {}
                if (isVirtualHost) {
                    grantUriPermissionViaRealSystemServer(hostPkg, pkg, apkUri)
                }
            }
        }

        val isActivityCtx = (context is android.app.Activity) && !context.isFinishing && !isVirtualHost

        fun tryStartInstallerIntent(intent: Intent): Boolean {
            // Inside Dual Space or any virtual container, ONLY trust `startActivityViaRealSystemServer`!
            // Never call `context.startActivity(intent)` for a package archive inside Dual Space because
            // Dual Space intercepts and swallows it silently without opening the installer UI!
            if (isVirtualHost) {
                return startActivityViaRealSystemServer(context, intent, hostPkg)
            }
            if (isActivityCtx) {
                try {
                    context.startActivity(intent)
                    return true
                } catch (_: Throwable) {}
            }
            if (startActivityViaRealSystemServer(context, intent, hostPkg)) {
                return true
            }
            return try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (_: Throwable) {
                false
            }
        }

        // Method 0: Exact system installer ComponentNames discovered from the real unhooked OS IPackageManager
        for (comp in realInstallerComponents) {
            try {
                val compIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    component = comp
                    clipData = ClipData.newRawUri("Standalone APK", apkUri)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    if (!isVirtualHost) {
                        putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, hostPkg)
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (tryStartInstallerIntent(compIntent)) {
                    return true
                }
            } catch (_: Throwable) {}
        }

        // Method 1: Direct explicit System PackageInstaller packages (prevents browsers/file-managers/DualSpace from hijacking ACTION_VIEW)
        val prioritySystemInstallers = listOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.oplus.appdetail",
            "com.coloros.phonemanager",
            "com.vivo.abe"
        )
        for (instPkg in prioritySystemInstallers) {
            try {
                val directIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    setPackage(instPkg)
                    clipData = ClipData.newRawUri("Standalone APK", apkUri)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    if (!isVirtualHost) {
                        putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, hostPkg)
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (isVirtualHost) {
                    if (startActivityViaRealSystemServer(context, directIntent, hostPkg)) {
                        return true
                    }
                } else if (directIntent.resolveActivity(pm) != null && tryStartInstallerIntent(directIntent)) {
                    return true
                }
            } catch (_: Throwable) {}
        }

        // Method 2: ACTION_INSTALL_PACKAGE (explicitly routed to system package installer across Android 7-16)
        try {
            val installPkgIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("Standalone APK", apkUri)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
                if (!isVirtualHost) {
                    putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, hostPkg)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (tryStartInstallerIntent(installPkgIntent)) {
                return true
            }
        } catch (_: Throwable) {}

        // Method 3: Standard ACTION_VIEW (read-only URI grant)
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("Standalone APK", apkUri)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                if (!isVirtualHost) {
                    putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, hostPkg)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (tryStartInstallerIntent(viewIntent)) {
                return true
            }
        } catch (_: Throwable) {}

        return false
    }

    /**
     * Replaces `PackageInstaller.mInstaller` with the real unhooked OS `IPackageInstaller` from
     * `ServiceManager.getIServiceManager().getService("package")` and aligns `mInstallerPackageName`
     * with `hostPkg` so that `PackageInstaller.Session` talks directly to the real Android OS
     * `PackageInstallerService` instead of Dual Space's virtual package installer!
     */
    private fun alignPackageInstallerWithHostUid(packageInstaller: PackageInstaller, hostPkg: String) {
        try {
            val rawPmBinder = getRealUnhookedServiceBinder("package")
            if (rawPmBinder != null) {
                val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
                val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).apply { isAccessible = true }
                val realIpm = asInterface.invoke(null, rawPmBinder)
                val realIpi = realIpm?.javaClass?.getMethod("getPackageInstaller")?.apply { isAccessible = true }?.invoke(realIpm)
                if (realIpi != null) {
                    val mInstallerField = packageInstaller.javaClass.getDeclaredField("mInstaller").apply { isAccessible = true }
                    mInstallerField.set(packageInstaller, realIpi)
                }
            }
        } catch (_: Throwable) {}
        try {
            val field = packageInstaller.javaClass.getDeclaredField("mInstallerPackageName")
            field.isAccessible = true
            field.set(packageInstaller, hostPkg)
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val attrField = packageInstaller.javaClass.getDeclaredField("mAttributionTag")
                attrField.isAccessible = true
                attrField.set(packageInstaller, null)
            } catch (_: Throwable) {}
        }
    }

    private fun installViaPackageInstallerSession(
        context: Context,
        apkFile: File,
        standalonePkg: String,
        appName: String,
        fallbackUri: Uri?,
        onStatus: (String) -> Unit
    ): Boolean {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val hostPkg = resolveHostCallingPackage(context)
            val isVirtualHost = hostPkg != context.packageName ||
                hostPkg.contains("dual", ignoreCase = true) ||
                hostPkg.contains("clone", ignoreCase = true) ||
                hostPkg.contains("virtual", ignoreCase = true) ||
                hostPkg.contains("parallel", ignoreCase = true)

            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setSize(apkFile.length())
                setAppPackageName(standalonePkg)
                setAppLabel(appName)
            }

            var effectiveInstallerPkg = if (isVirtualHost) {
                alignPackageInstallerWithHostUid(packageInstaller, hostPkg)
                hostPkg
            } else {
                context.packageName
            }

            val sessionId = try {
                packageInstaller.createSession(params)
            } catch (_: Throwable) {
                alignPackageInstallerWithHostUid(packageInstaller, hostPkg)
                effectiveInstallerPkg = hostPkg
                packageInstaller.createSession(params)
            }

            val session = packageInstaller.openSession(sessionId)
            session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    input.copyTo(out, bufferSize = 65536)
                }
                session.fsync(out)
            }

            val callbackHandled = AtomicBoolean(false)
            val action = "$ACTION_INSTALL_COMMIT.$sessionId"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent == null) return
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        callbackHandled.set(true)
                        @Suppress("DEPRECATION")
                        val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirmIntent != null) {
                            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (!startActivityViaRealSystemServer(context, confirmIntent, hostPkg)) {
                                try {
                                    context.startActivity(confirmIntent)
                                } catch (_: Throwable) {
                                    if (fallbackUri != null) {
                                        try { launchPackageInstallerIntent(context, fallbackUri) } catch (_: Throwable) {}
                                    }
                                }
                            }
                        }
                    } else if (status == PackageInstaller.STATUS_SUCCESS) {
                        callbackHandled.set(true)
                        val curState = _installUiState.value
                        if (curState != null && curState.standalonePackageName == standalonePkg) {
                            activeInstallMonitorToken = 0L
                            _installUiState.value = curState.copy(
                                progressPercent = 100,
                                stageTitle = com.example.ui.i18n.tr(
                                    "Installation Complete! (100%)",
                                    "ইনস্টল সম্পূর্ণ হয়েছে! (১০০%)",
                                    "इंस्टॉलेशन पूरा हुआ! (100%)"
                                ),
                                stageDetail = com.example.ui.i18n.tr(
                                    "$appName has been installed successfully with GhostDevice Engine.",
                                    "$appName সফলভাবে GhostDevice সিস্টেমের সাথে ইনস্টল সম্পন্ন হয়েছে!",
                                    "$appName सफलतापूर्वक GhostDevice सिस्टम के साथ इंस्टॉल हो गया है!"
                                ),
                                isInstalling = false,
                                isCompleted = true,
                                isError = false
                            )
                        }
                        Handler(Looper.getMainLooper()).post {
                            onStatus(
                                com.example.ui.i18n.tr(
                                    "$appName installed successfully!",
                                    "$appName সফলভাবে ইনস্টল হয়েছে!",
                                    "$appName सफलतापूर्वक इंस्टॉल हो गया!"
                                )
                            )
                        }
                    } else {
                        // Session reported failure -> fall back to MediaStore URI installer if not yet launched
                        if (callbackHandled.compareAndSet(false, true) && fallbackUri != null) {
                            Handler(Looper.getMainLooper()).post {
                                try { launchPackageInstallerIntent(context, fallbackUri) } catch (_: Throwable) {}
                            }
                        }
                    }
                    try {
                        context.applicationContext.unregisterReceiver(this)
                    } catch (_: Throwable) {}
                }
            }

            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) {
                context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.applicationContext.registerReceiver(receiver, filter)
            }

            val callbackIntent = Intent(action).apply {
                setPackage(effectiveInstallerPkg)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context.applicationContext, sessionId, callbackIntent, flags)
            session.commit(pendingIntent.intentSender)
            session.close()
            Handler(Looper.getMainLooper()).post {
                onStatus(
                    com.example.ui.i18n.tr(
                        "$appName installer opened!",
                        "$appName ইনস্টলার ওপেন করা হয়েছে!",
                        "$appName इंस्टॉलर खोला गया!"
                    )
                )
            }

            // Direct session confirmation safety net for Dual Space / Virtual Containers:
            // If Dual Space's virtual broadcast dispatcher swallows the PendingIntent broadcast callback,
            // directly launch PackageInstaller's CONFIRM_INSTALL activity for `sessionId` via the real OS Binder!
            if (isVirtualHost) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (callbackHandled.compareAndSet(false, true)) {
                        var launchedConfirm = false
                        val confirmActions = listOf(
                            "android.content.pm.action.CONFIRM_INSTALL",
                            "android.content.pm.action.CONFIRM_PERMISSIONS"
                        )
                        val installerPkgs = listOf(
                            "com.google.android.packageinstaller",
                            "com.android.packageinstaller",
                            "com.miui.packageinstaller",
                            "com.samsung.android.packageinstaller",
                            "com.oplus.appdetail",
                            "com.coloros.phonemanager"
                        )
                        outer@ for (act in confirmActions) {
                            for (instPkg in installerPkgs) {
                                val directConfirm = Intent(act).apply {
                                    setPackage(instPkg)
                                    putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                if (startActivityViaRealSystemServer(context, directConfirm, hostPkg)) {
                                    launchedConfirm = true
                                    break@outer
                                }
                            }
                        }
                        if (!launchedConfirm && fallbackUri != null) {
                            try {
                                launchPackageInstallerIntent(context, fallbackUri)
                            } catch (_: Throwable) {}
                        }
                    }
                }, 450L)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun shareStandaloneApk(context: Context, artifact: StandaloneApkArtifact) {
        val file = File(artifact.outputFilePath).let { f ->
            if (f.exists() && f.length() > 0L) f else File(artifact.publicDownloadPath)
        }
        if (!file.exists() || file.length() <= 0L) return
        try {
            val cleanLabel = artifact.appName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { artifact.originalPackageName }
            val idFileSuffix = buildIdFileSuffixForArtifact(artifact)
            val publicFileName = "${cleanLabel}${idFileSuffix}_GhostDevice_${BUILDER_VERSION}.apk"
            val uri = resolveExistingOrPublishMediaStoreUri(context, artifact, file)
                ?: publishApkToMediaStoreDownloads(context, file, publicFileName)
                ?: Uri.parse("content://com.aistudio.phantomcore.provider/standalone_apk/${file.name}")
            val displayPkg = artifact.standalonePackageName.replace(".phantom", ".ghostdevice")
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "${artifact.appName} ($displayPkg) - GhostDevice Standalone APK (Model: ${artifact.bakedDeviceModel}, Speed & Location Stick Built-in)"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(
                shareIntent,
                com.example.ui.i18n.tr(
                    "Share ${artifact.appName} APK",
                    "${artifact.appName} APK শেয়ার করুন",
                    "${artifact.appName} APK साझा करें"
                )
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Throwable) {}
    }

    fun formatFileSize(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractOriginalSignatureHex(pm: PackageManager, packageName: String, sourceApkPath: String? = null): String {
        // Method 0: Query real unhooked OS IPackageManager directly (bypasses Dual Space virtual PM & Android 11+ visibility for V2/V3-only split APKs like YouTube)
        try {
            val rawPmBinder = getRealUnhookedServiceBinder("package")
            if (rawPmBinder != null) {
                val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
                val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).apply { isAccessible = true }
                val realIpm = asInterface.invoke(null, rawPmBinder)
                if (realIpm != null) {
                    val userId = Process.myUid() / 100000
                    val getPiMethods = realIpm.javaClass.methods.filter { it.name == "getPackageInfo" }
                    val flagCandidates = if (Build.VERSION.SDK_INT >= 28) {
                        intArrayOf(PackageManager.GET_SIGNING_CERTIFICATES, PackageManager.GET_SIGNATURES)
                    } else {
                        intArrayOf(PackageManager.GET_SIGNATURES)
                    }
                    for (flags in flagCandidates) {
                        for (m in getPiMethods) {
                            try {
                                m.isAccessible = true
                                val pTypes = m.parameterTypes
                                val pi = when {
                                    pTypes.size == 3 && pTypes[0] == String::class.java && pTypes[1] == Long::class.javaPrimitiveType && pTypes[2] == Int::class.javaPrimitiveType ->
                                        m.invoke(realIpm, packageName, flags.toLong(), userId)
                                    pTypes.size == 3 && pTypes[0] == String::class.java && pTypes[1] == Int::class.javaPrimitiveType && pTypes[2] == Int::class.javaPrimitiveType ->
                                        m.invoke(realIpm, packageName, flags, userId)
                                    else -> null
                                } as? android.content.pm.PackageInfo
                                if (pi != null) {
                                    if (Build.VERSION.SDK_INT >= 28) {
                                        val sigs = pi.signingInfo?.apkContentsSigners ?: pi.signingInfo?.signingCertificateHistory
                                        if (!sigs.isNullOrEmpty()) return sigs[0].toCharsString()
                                    }
                                    val legacySigs = pi.signatures
                                    if (!legacySigs.isNullOrEmpty()) return legacySigs[0].toCharsString()
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // Method 1: Query PackageManager
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val sigs = pi.signingInfo?.apkContentsSigners
                    ?: pi.signingInfo?.signingCertificateHistory
                if (!sigs.isNullOrEmpty()) return sigs[0].toCharsString()
            }
            val pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            val sigs = pi.signatures
            if (!sigs.isNullOrEmpty()) return sigs[0].toCharsString()
        } catch (_: Throwable) {}

        // Method 2: Query archive info directly from APK file (bypasses Android 11+ package visibility)
        if (!sourceApkPath.isNullOrBlank()) {
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    val pi = pm.getPackageArchiveInfo(sourceApkPath, PackageManager.GET_SIGNING_CERTIFICATES)
                    val sigs = pi?.signingInfo?.apkContentsSigners
                        ?: pi?.signingInfo?.signingCertificateHistory
                    if (!sigs.isNullOrEmpty()) return sigs[0].toCharsString()
                }
                val piLegacy = pm.getPackageArchiveInfo(sourceApkPath, PackageManager.GET_SIGNATURES)
                val legacySigs = piLegacy?.signatures
                if (!legacySigs.isNullOrEmpty()) return legacySigs[0].toCharsString()
            } catch (_: Throwable) {}

            // Method 3: Extract X.509 certificate directly from META-INF/*.RSA or *.DSA or *.EC
            try {
                ZipFile(File(sourceApkPath)).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val name = e.name.uppercase(Locale.US)
                        if (name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                            val certFactory = CertificateFactory.getInstance("X.509")
                            zip.getInputStream(e).use { stream ->
                                val certs = certFactory.generateCertificates(stream)
                                for (cert in certs) {
                                    if (cert is X509Certificate) {
                                        val sig = android.content.pm.Signature(cert.encoded)
                                        val hex = sig.toCharsString()
                                        if (!hex.isNullOrBlank()) return hex
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // Method 4: Parse APK Signature Scheme V2 / V3 / V3.1 ("APK Sig Block 42") X.509 certificate directly from sourceApkPath
        // (Modern Google Play split APKs like YouTube only have V2/V3 signatures in base.apk and no META-INF/*.RSA V1 entries!)
        try {
            val certHex = extractV2V3CertificateHexFromApk(File(sourceApkPath))
            if (!certHex.isNullOrBlank() && isValidX509CertHex(certHex)) {
                return certHex
            }
        } catch (_: Throwable) {}

        return ""
    }

    private fun isValidX509CertHex(hex: String): Boolean {
        if (hex.length < 128 || (hex.length and 1) != 0) return false
        return try {
            val der = android.content.pm.Signature(hex).toByteArray()
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(java.io.ByteArrayInputStream(der))
            cert != null && cert.publicKey != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun extractV2V3CertificateHexFromApk(apkFile: File): String? {
        if (!apkFile.exists() || apkFile.length() < 64L) return null
        RandomAccessFile(apkFile, "r").use { raf ->
            val fileLen = raf.length()
            val searchLen = minOf(fileLen, 65557L).toInt()
            val tail = ByteArray(searchLen)
            raf.seek(fileLen - searchLen)
            raf.readFully(tail)
            var eocdOffset = -1
            for (i in searchLen - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                ) {
                    eocdOffset = i
                    break
                }
            }
            if (eocdOffset < 0) return null
            val tailBuf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val cdOffset = tailBuf.getInt(eocdOffset + 16).toLong() and 0xFFFFFFFFL
            if (cdOffset < 32L || cdOffset >= fileLen) return null
            val footer = ByteArray(24)
            raf.seek(cdOffset - 24L)
            raf.readFully(footer)
            val footerBuf = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN)
            val blockSize2 = footerBuf.getLong(0)
            val magicLo = footerBuf.getLong(8)
            val magicHi = footerBuf.getLong(16)
            if (magicLo != 0x20676953204b5041L || magicHi != 0x3234206b636f6c42L) return null
            val blockStart = cdOffset - (blockSize2 + 8L)
            if (blockStart < 0L || blockSize2 < 24L || blockSize2 > 20L * 1024L * 1024L) return null
            val pairsLen = (blockSize2 - 16L).toInt()
            val blockBytes = ByteArray(pairsLen)
            raf.seek(blockStart + 8L)
            raf.readFully(blockBytes)
            val pairs = ByteBuffer.wrap(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 0
            while (pos + 12 <= pairsLen) {
                val pairLen = pairs.getLong(pos)
                pos += 8
                if (pairLen < 4L || pos + pairLen > pairsLen) break
                val id = pairs.getInt(pos)
                // V2 (0x7109871a), V3 (0xf05368c0), V3.1 (0x1b93ad61)
                if (id == 0x7109871a || id == -0xfac9740 || id == 0x1b93ad61) {
                    val valStart = pos + 4
                    val valLen = (pairLen - 4L).toInt()
                    for (k in valStart until (valStart + valLen - 12)) {
                        if ((blockBytes[k].toInt() and 0xFF) == 0x30 &&
                            (blockBytes[k + 1].toInt() and 0xFF) == 0x82 &&
                            (blockBytes[k + 4].toInt() and 0xFF) == 0x30 &&
                            (blockBytes[k + 5].toInt() and 0xFF) == 0x82
                        ) {
                            val certBodyLen = ((blockBytes[k + 2].toInt() and 0xFF) shl 8) or (blockBytes[k + 3].toInt() and 0xFF)
                            val totalCertLen = certBodyLen + 4
                            if (certBodyLen in 256..8192 && k + totalCertLen <= valStart + valLen) {
                                if (k >= valStart + 4 && pairs.getInt(k - 4) == totalCertLen) {
                                    val cert = blockBytes.copyOfRange(k, k + totalCertLen)
                                    return android.content.pm.Signature(cert).toCharsString()
                                }
                            }
                        }
                    }
                }
                pos += pairLen.toInt()
            }
        }
        return null
    }

    internal fun buildEmbeddedConfigJsonBytes(
        originalPkg: String,
        standalonePkg: String,
        originalSigHex: String,
        identity: DeviceIdentity,
        liveLat: Double,
        liveLng: Double,
        speedMultiplier: Float,
        speedEnabled: Boolean = true,
        stepWalkEnabled: Boolean = false,
        rootBypassEnabled: Boolean = false,
        identitySpoofEnabled: Boolean = false,
        adBypassEnabled: Boolean = false,
        splitResCountOverride: Int? = null,
        builtTimestampOverride: Long? = null,
        context: Context? = null
    ): ByteArray {
        var licSystemEnabled = com.example.provider.PhantomIdentityProvider.liveLicenseSystemEnabled
        var licValid = com.example.provider.PhantomIdentityProvider.liveLicenseValid
        var licKey = com.example.provider.PhantomIdentityProvider.liveLicenseKey
        var licExpiresAtMs = com.example.provider.PhantomIdentityProvider.liveLicenseExpiresAtMs
        var firestoreDbId = com.example.provider.PhantomIdentityProvider.liveFirestoreDatabaseId
        if (context != null) {
            try {
                val prefs = context.getSharedPreferences("phantom_license_prefs", Context.MODE_PRIVATE)
                licSystemEnabled = prefs.getBoolean("license_system_enabled", licSystemEnabled)
                licValid = prefs.getBoolean("license_valid_state", licValid)
                licKey = prefs.getString("license_key", licKey) ?: licKey
                licExpiresAtMs = prefs.getLong("license_expiry_millis", licExpiresAtMs)
                firestoreDbId = com.example.data.license.LicenseApiClient.resolveFirestoreDatabaseId(context)
            } catch (_: Throwable) {}
        }

        val hostModel = (Build.MODEL ?: "").trim()
        val hostFp = (Build.FINGERPRINT ?: "").trim()
        val safeIdentity = if (
            identity.deviceModel.isBlank() ||
            identity.isOriginalBackup ||
            (hostModel.isNotEmpty() && (identity.deviceModel.equals(hostModel, ignoreCase = true) || identity.deviceModel.contains(hostModel, ignoreCase = true))) ||
            (hostFp.isNotEmpty() && identity.buildFingerprint.equals(hostFp, ignoreCase = true))
        ) {
            com.example.data.generator.IdentityGenerator.generateNewIdentity(
                excludeModel = hostModel
            )
        } else {
            identity
        }

        val json = JSONObject().apply {
            put("originalPackageName", originalPkg)
            put("standalonePackageName", standalonePkg)
            put("replaceMainApk", originalPkg.trim() == standalonePkg.trim())
            put("originalSignatureHex", originalSigHex)
            put("deviceModel", safeIdentity.deviceModel)
            put("manufacturer", safeIdentity.manufacturer)
            put("brand", safeIdentity.manufacturer.lowercase(Locale.US))
            put("deviceId", safeIdentity.deviceId)
            put("buildNumber", safeIdentity.buildNumber)
            put("buildFingerprint", safeIdentity.buildFingerprint)
            put("kernelVersion", safeIdentity.kernelVersion)
            put("socHardware", safeIdentity.socHardware)
            put("gpuRenderer", safeIdentity.gpuRenderer)
            put("gpuVendor", if (safeIdentity.gpuRenderer.contains("Mali", ignoreCase = true) || safeIdentity.gpuRenderer.contains("Immortalis", ignoreCase = true)) "ARM" else "Qualcomm")
            put("gpuVersion", safeIdentity.gpuVersion)
            put("basebandVersion", safeIdentity.basebandVersion)
            put("bootloaderVersion", safeIdentity.bootloaderVersion)
            put("securityPatch", safeIdentity.securityPatch)
            put("serialNumber", safeIdentity.serialNumber)
            put("androidId", safeIdentity.androidId)
            put("gsfId", safeIdentity.androidId.reversed())
            put("advertisingId", safeIdentity.advertisingId)
            put("appSetId", safeIdentity.advertisingId.reversed())
            put("imei", safeIdentity.imei)
            put("simImsi", safeIdentity.simImsi)
            put("simIccid", safeIdentity.simIccid)
            put("phoneNumber", "+88017" + safeIdentity.imei.takeLast(8))
            put("carrier", safeIdentity.carrier)
            put("macAddress", safeIdentity.macAddress)
            put("wifiBssid", safeIdentity.bluetoothAddress)
            put("wifiSsid", "\"GhostDevice_5G_${safeIdentity.androidId.take(4).uppercase(Locale.US)}\"")
            put("bluetoothAddress", safeIdentity.bluetoothAddress)
            put("bluetoothName", safeIdentity.deviceModel)
            put("ipAddress", safeIdentity.ipAddress)
            put("widevineDrmId", safeIdentity.widevineDrmId)
            put("osVersion", safeIdentity.osVersion)
            put("display", safeIdentity.display)
            val isBrowserTarget = originalPkg.lowercase(Locale.US).let { p ->
                p.contains("chrome") || p.contains("firefox") || p.contains("opera") ||
                    p.contains("brave") || p.contains("emmx") || p.contains("sbrowser") ||
                    p.contains("duckduckgo") || p.contains("kiwi") || p.contains("vivaldi") ||
                    p.contains("browser")
            }
            val rawUa = if (
                safeIdentity.userAgent.isBlank() ||
                !safeIdentity.userAgent.contains(safeIdentity.deviceModel) ||
                (hostModel.isNotEmpty() && safeIdentity.userAgent.contains(hostModel, ignoreCase = true))
            ) {
                val cleanOs = safeIdentity.osVersion.replace("Android ", "").substringBefore(" ").ifBlank { "14" }
                "Mozilla/5.0 (Linux; Android $cleanOs; ${safeIdentity.deviceModel} Build/${safeIdentity.buildNumber}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36"
            } else {
                safeIdentity.userAgent
            }
            val cleanUserAgent = if (isBrowserTarget) {
                rawUa.replace("; wv)", ")").replace("; wv;", ";").replace("; wv", "")
            } else {
                rawUa
            }
            put("userAgent", cleanUserAgent)
            put("webviewHash", safeIdentity.webviewHash)
            put("timezone", safeIdentity.timezone)
            put("region", safeIdentity.region)
            put("localeTag", safeIdentity.localeTag)
            put("locationName", safeIdentity.locationName)
            put("latitude", if (liveLat != 0.0) liveLat else 23.8103)
            put("longitude", if (liveLng != 0.0) liveLng else 90.4125)
            put("initialSpeedMultiplier", speedMultiplier.toDouble())
            put("speedEnabled", speedEnabled)
            put("stepWalkEnabled", stepWalkEnabled)
            put("rootBypassEnabled", rootBypassEnabled)
            put("identitySpoofEnabled", true)
            put("adBypassEnabled", adBypassEnabled || isYouTubePackage(originalPkg))
            put("ytEngineRev", 9)
            put("licenseSystemEnabled", licSystemEnabled)
            put("licenseValid", licValid)
            put("licenseKey", licKey)
            put("licenseExpiresAtMillis", licExpiresAtMs)
            put("firestoreProjectId", com.example.provider.PhantomIdentityProvider.liveFirestoreProjectId)
            put("firestoreDatabaseId", firestoreDbId)
            val activeLangCode = try {
                context?.applicationContext?.getSharedPreferences("phantom_language_prefs", Context.MODE_PRIVATE)
                    ?.getString("selected_app_language", com.example.ui.i18n.LanguageManager.currentLang.code)
                    ?: com.example.ui.i18n.LanguageManager.currentLang.code
            } catch (_: Throwable) {
                com.example.ui.i18n.LanguageManager.currentLang.code
            }
            put("appLanguage", activeLangCode)
            put("builtTimestamp", builtTimestampOverride ?: System.currentTimeMillis())
            val hostId = if (context != null) {
                com.example.provider.PhantomIdentityProvider.getOrCreateHostInstanceId(context)
            } else {
                com.example.provider.PhantomIdentityProvider.hostInstanceId
            }
            if (hostId.isNotBlank()) {
                put("hostInstanceId", hostId)
            }
            if (splitResCountOverride != null) {
                put("splitResCount", splitResCountOverride)
            }
        }
        val jsonStr = json.toString()
        if (context != null && standalonePkg.isNotBlank()) {
            try {
                com.example.provider.PhantomIdentityProvider.saveStandaloneIdentityForPackage(
                    context,
                    standalonePkg,
                    jsonStr
                )
            } catch (_: Throwable) {}
        }
        return jsonStr.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Loads our dedicated 43 KB pre-compiled `assets/phantom_runtime.dex` containing ONLY
     * `android.os.SystemClocl`, `com.example.standalone.StandaloneBootstrapProvider`, and
     * `com.example.standalone.StandaloneRuntimeEngine`.
     * Avoids injecting the host app's full Compose/Kotlin DEX into target APKs.
     */
    private fun hasDexClassDefinition(dexBytes: ByteArray, classDescriptor: String): Boolean {
        if (dexBytes.size < 112) return false
        return try {
            val buf = ByteBuffer.wrap(dexBytes).order(ByteOrder.LITTLE_ENDIAN)
            val stringIdsSize = buf.getInt(56)
            val stringIdsOff = buf.getInt(60)
            val typeIdsSize = buf.getInt(64)
            val typeIdsOff = buf.getInt(68)
            val classDefsSize = buf.getInt(96)
            val classDefsOff = buf.getInt(100)
            if (stringIdsSize <= 0 || typeIdsSize <= 0 || classDefsSize <= 0) return false
            for (i in 0 until classDefsSize) {
                val cDefOff = classDefsOff + i * 32
                if (cDefOff + 32 > dexBytes.size) break
                val typeIdx = buf.getInt(cDefOff)
                if (typeIdx in 0 until typeIdsSize) {
                    val strIdx = buf.getInt(typeIdsOff + typeIdx * 4)
                    val desc = readDexStringAscii(dexBytes, buf, stringIdsOff, stringIdsSize, strIdx)
                    if (desc == classDescriptor) return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun isCompleteRuntimeDex(dexBytes: ByteArray): Boolean {
        return dexBytes.size > 112 &&
            hasDexClassDefinition(dexBytes, "Lcom/example/standalone/StandaloneBootstrapProvider;") &&
            hasDexClassDefinition(dexBytes, "Lcom/example/standalone/StandaloneRuntimeEngine;") &&
            hasDexClassDefinition(dexBytes, "Lcom/example/standalone/EmbeddedGmsCore;") &&
            hasDexClassDefinition(dexBytes, "Landroid/os/SystemClocl;") &&
            hasDexClassDefinition(dexBytes, "Landroid/os/SystemPropertiet;") &&
            hasDexClassDefinition(dexBytes, "Landroid/os/Procest;") &&
            hasDexClassDefinition(dexBytes, "Landroid/view/animation/AnimationUtilt;")
    }

    private fun extractStandaloneRuntimeDexes(context: Context): List<ByteArray> {
        try {
            context.assets.open("phantom_runtime.dex").use { input ->
                val bytes = input.readBytes()
                if (isCompleteRuntimeDex(bytes)) {
                    return listOf(bytes)
                }
            }
        } catch (_: Throwable) {}

        val requiredClasses = listOf(
            "Lcom/example/standalone/StandaloneBootstrapProvider;",
            "Lcom/example/standalone/StandaloneRuntimeEngine;",
            "Landroid/os/SystemClocl;",
            "Landroid/os/SystemPropertiet;",
            "Landroid/view/animation/AnimationUtilt;"
        )
        val result = mutableListOf<ByteArray>()
        val apkPaths = mutableListOf<String>()
        context.applicationInfo?.sourceDir?.let { apkPaths.add(it) }
        context.applicationInfo?.splitSourceDirs?.let { apkPaths.addAll(it) }

        for (path in apkPaths) {
            val file = File(path)
            if (!file.exists()) continue
            try {
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == "assets/phantom_runtime.dex") {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (isCompleteRuntimeDex(bytes)) {
                                return listOf(bytes)
                            }
                        }
                        if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (requiredClasses.any { hasDexClassDefinition(bytes, it) }) {
                                result.add(bytes)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        return result
    }

    private fun isMetaInfSignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase(Locale.US)
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC") ||
            upper.startsWith("META-INF/SIG-") ||
            upper.contains("COM.ANDROID.STAMP")
    }

    /**
     * Streams a split APK's resources (AndroidManifest.xml, resources.arsc, res/) into
     * a temporary resource-only APK file on disk (excluding classes.dex and lib/) using a
     * 64 KB buffer so StandaloneRuntimeEngine can register it with AssetManager.addAssetPath /
     * ResourcesLoader and LoadedApk.mSplitResDirs at startup without allocating large ByteArrays in RAM.
     */
    private fun buildResourceOnlySplitApkToFile(
        splitZip: ZipFile,
        outFile: File,
        sharedBuf: ByteArray,
        originalPkg: String,
        standalonePkg: String
    ): Boolean {
        val arscEntry = splitZip.getEntry("resources.arsc") ?: return false
        val crc = CRC32()

        fun resolveEntrySizeAndCrc(entry: ZipEntry): Pair<Long, Long> {
            crc.reset()
            var total = 0L
            splitZip.getInputStream(entry).use { input ->
                var r: Int
                while (input.read(sharedBuf).also { r = it } != -1) {
                    crc.update(sharedBuf, 0, r)
                    total += r
                }
            }
            return Pair(total, crc.value)
        }

        val (arscSize, arscCrc) = resolveEntrySizeAndCrc(arscEntry)
        if (arscSize < 64L) return false

        val writtenSplitNames = HashSet<String>(1024)
        CountingOutputStream(BufferedOutputStream(FileOutputStream(outFile), 65536)).use { countingOut ->
            ZipOutputStream(countingOut).use { zipOut ->
                // 1. Stream resources.arsc first, STORED and 4-byte aligned (offset 44)
                writtenSplitNames.add("resources.arsc")
                val rEntry = ZipEntry("resources.arsc").apply {
                    method = ZipEntry.STORED
                    size = arscSize
                    compressedSize = arscSize
                    this.crc = arscCrc
                }
                zipOut.putNextEntry(rEntry)
                splitZip.getInputStream(arscEntry).use { input ->
                    var r: Int
                    while (input.read(sharedBuf).also { r = it } != -1) {
                        zipOut.write(sharedBuf, 0, r)
                    }
                }
                zipOut.closeEntry()

                // 2. Stream AndroidManifest.xml (patched with standalonePkg) and res/* entries
                val entries = splitZip.entries()
                while (entries.hasMoreElements()) {
                    val e = try {
                        entries.nextElement()
                    } catch (_: Throwable) {
                        break
                    }
                    if (e.isDirectory) continue
                    val n = e.name
                    if (n == "resources.arsc" || n == "res/xml/splits0.xml") continue
                    if (n != "AndroidManifest.xml" && !n.startsWith("res/")) continue
                    if (!writtenSplitNames.add(n)) continue

                    try {
                        if (n == "AndroidManifest.xml") {
                            val rawManifest = splitZip.getInputStream(e).use { it.readBytes() }
                            val patchedSplitManifest = try {
                                AxmlManifestPatcher.patchManifest(
                                    axmlBytes = rawManifest,
                                    originalPkg = originalPkg,
                                    standalonePkg = standalonePkg,
                                    resolveStringResource = null,
                                    injectBootstrapAndPermissions = false
                                )
                            } catch (_: Throwable) {
                                rawManifest
                            }
                            val zEntry = ZipEntry(n).apply { method = ZipEntry.DEFLATED }
                            zipOut.putNextEntry(zEntry)
                            zipOut.write(patchedSplitManifest)
                            zipOut.closeEntry()
                        } else {
                            val zEntry = ZipEntry(n)
                            if (e.method == ZipEntry.STORED) {
                                val (eSize, eCrc) = resolveEntrySizeAndCrc(e)
                                zEntry.method = ZipEntry.STORED
                                zEntry.size = eSize
                                zEntry.compressedSize = eSize
                                zEntry.crc = eCrc
                                val nameByteLen = n.toByteArray(StandardCharsets.UTF_8).size
                                val pad = ((4L - ((countingOut.bytesWritten + 30L + nameByteLen) % 4L)) % 4L).toInt()
                                if (pad > 0) zEntry.extra = ByteArray(pad)
                            } else {
                                zEntry.method = ZipEntry.DEFLATED
                            }
                            zipOut.putNextEntry(zEntry)
                            splitZip.getInputStream(e).use { input ->
                                var r: Int
                                while (input.read(sharedBuf).also { r = it } != -1) {
                                    zipOut.write(sharedBuf, 0, r)
                                }
                            }
                            zipOut.closeEntry()
                        }
                    } catch (_: Throwable) {
                        try { zipOut.closeEntry() } catch (_: Throwable) {}
                    }
                }
            }
        }
        return outFile.exists() && outFile.length() > 64L
    }

    /**
     * Recovers standard DEX bytecode from oat/<arch> .vdex files for pre-installed system APKs
     * whose classes.dex was stripped by WITH_DEXPREOPT at ROM build time.
     */
    private fun extractDexesFromSystemVdexIfMissing(
        originalSourceApkPath: String,
        readableBaseApk: File,
        readableSplits: Array<String>?,
        tempDir: File,
        buildSeq: Long
    ): List<ByteArray> {
        fun apkHasDex(f: File): Boolean {
            return try {
                ZipFile(f).use { z ->
                    val en = z.entries()
                    while (en.hasMoreElements()) {
                        val n = en.nextElement().name
                        if (!n.contains('/') && n.startsWith("classes") && n.endsWith(".dex")) return@use true
                    }
                    false
                }
            } catch (_: Throwable) {
                false
            }
        }
        if (apkHasDex(readableBaseApk)) return emptyList()
        readableSplits?.forEach { sp ->
            if (apkHasDex(File(sp))) return emptyList()
        }

        val apkFile = File(originalSourceApkPath)
        val parentDir = apkFile.parentFile ?: return emptyList()
        val baseStem = apkFile.nameWithoutExtension
        val candidateVdexPaths = LinkedHashSet<String>()
        for (arch in listOf("arm64", "arm", "x86_64", "x86")) {
            candidateVdexPaths.add(File(parentDir, "oat/$arch/$baseStem.vdex").absolutePath)
            candidateVdexPaths.add(File(parentDir, "oat/$arch/base.vdex").absolutePath)
        }
        try {
            File(parentDir, "oat").listFiles()?.forEach { archDir ->
                if (archDir.isDirectory) {
                    archDir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".vdex")) candidateVdexPaths.add(f.absolutePath)
                    }
                }
            }
        } catch (_: Throwable) {}

        var vdexBytes: ByteArray? = null
        for (vp in candidateVdexPaths) {
            val vf = File(vp)
            if (vf.exists() && vf.canRead() && vf.length() > 112L && vf.length() < 96L * 1024L * 1024L) {
                try {
                    vdexBytes = vf.readBytes()
                    break
                } catch (_: Throwable) {}
            }
        }
        if (vdexBytes == null && RootChecker.isDeviceRooted()) {
            val oatPath = File(parentDir, "oat").absolutePath
            val found = RootChecker.executeRootCommandWithOutput(
                "find \"$oatPath\" -maxdepth 2 -name '*.vdex' 2>/dev/null | head -n 1"
            ).trim()
            if (found.endsWith(".vdex")) {
                val tmpVdex = File(tempDir, "sys_vdex_${buildSeq}.vdex")
                try {
                    RootChecker.executeRootCommand(
                        "cp \"$found\" \"${tmpVdex.absolutePath}\" && chmod 644 \"${tmpVdex.absolutePath}\" 2>/dev/null || true"
                    )
                    if (tmpVdex.exists() && tmpVdex.canRead() && tmpVdex.length() > 112L && tmpVdex.length() < 96L * 1024L * 1024L) {
                        vdexBytes = tmpVdex.readBytes()
                    }
                } catch (_: Throwable) {
                } finally {
                    try { tmpVdex.delete() } catch (_: Throwable) {}
                }
            }
        }
        val raw = vdexBytes ?: return emptyList()
        val recovered = ArrayList<ByteArray>()
        var pos = 0
        val limit = raw.size - 112
        while (pos <= limit) {
            // Standard DEX magic: "dex\n0XX\0" (0x64 0x65 0x78 0x0A 0x30 .. 0x00)
            if (raw[pos] == 0x64.toByte() &&
                raw[pos + 1] == 0x65.toByte() &&
                raw[pos + 2] == 0x78.toByte() &&
                raw[pos + 3] == 0x0A.toByte() &&
                raw[pos + 4] == 0x30.toByte() &&
                raw[pos + 7] == 0x00.toByte()
            ) {
                val buf = ByteBuffer.wrap(raw, pos, 112).order(ByteOrder.LITTLE_ENDIAN)
                val fileSize = buf.getInt(32)
                val headerSize = buf.getInt(36)
                val endianTag = buf.getInt(40)
                if (headerSize == 0x70 && endianTag == 0x12345678 && fileSize >= 112 && pos + fileSize <= raw.size) {
                    val dexCopy = raw.copyOfRange(pos, pos + fileSize)
                    try {
                        // Ensure SHA-1 signature (bytes 12..31) and Adler-32 checksum (bytes 8..11) are valid
                        val sha1 = MessageDigest.getInstance("SHA-1")
                        sha1.update(dexCopy, 32, dexCopy.size - 32)
                        val digest = sha1.digest()
                        System.arraycopy(digest, 0, dexCopy, 12, 20)
                        val adler = java.util.zip.Adler32()
                        adler.update(dexCopy, 12, dexCopy.size - 12)
                        val csum = adler.value.toInt()
                        dexCopy[8] = (csum and 0xFF).toByte()
                        dexCopy[9] = ((csum ushr 8) and 0xFF).toByte()
                        dexCopy[10] = ((csum ushr 16) and 0xFF).toByte()
                        dexCopy[11] = ((csum ushr 24) and 0xFF).toByte()
                    } catch (_: Throwable) {}
                    recovered.add(dexCopy)
                    pos += fileSize
                    continue
                }
            }
            pos++
        }
        return recovered
    }

    internal fun repackageAndPatchZip(
        sourceApkFile: File,
        splitSourceDirs: Array<String>?,
        nativeLibraryDir: String? = null,
        originalPkg: String,
        standalonePkg: String,
        configJsonBytes: ByteArray,
        configJsonBytesProvider: ((Int) -> ByteArray)? = null,
        injectedRuntimeDexes: List<ByteArray>,
        unsignedOutFile: File,
        onProgressPercent: (Int) -> Unit = {},
        fallbackRecoveredDexes: List<ByteArray> = emptyList(),
        isSystemSourceApp: Boolean = false
    ) {
        val writtenEntryNames = HashSet<String>(4096)
        val v1Digests = LinkedHashMap<String, String>(4096)
        val sharedBuf = ByteArray(65536)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val crc32 = CRC32()
        var maxDexIndex = 1
        var splitResCount = 0

        fun nextFreeDexIndex(): Int {
            var idx = 1
            while (writtenEntryNames.contains(if (idx == 1) "classes.dex" else "classes${idx}.dex")) {
                idx++
            }
            if (idx > maxDexIndex) maxDexIndex = idx
            return idx
        }

        val totalEstimatedBytes = (sourceApkFile.length().coerceAtLeast(1L) +
            (splitSourceDirs?.sumOf { p -> try { File(p).length().coerceAtLeast(0L) } catch (_: Throwable) { 0L } } ?: 0L))
            .coerceAtLeast(1L)
        var lastEmittedPct = 15
        var lastEmitTimeMs = 0L
        fun reportRepackageProgress() {
            val now = android.os.SystemClock.elapsedRealtime()
            val curWritten = unsignedOutFile.length()
            val pct = (15 + ((curWritten * 63L) / totalEstimatedBytes)).toInt().coerceIn(15, 78)
            if (pct - lastEmittedPct >= 2 || (pct > lastEmittedPct && now - lastEmitTimeMs >= 250L)) {
                lastEmittedPct = pct
                lastEmitTimeMs = now
                onProgressPercent(pct)
            }
        }

        CountingOutputStream(BufferedOutputStream(FileOutputStream(unsignedOutFile), 65536)).use { countingOut ->
            ZipOutputStream(countingOut).use { zipOut ->

                fun writeInMemoryEntry(name: String, data: ByteArray, storeUncompressed: Boolean) {
                    if (!writtenEntryNames.add(name)) return
                    if (!name.startsWith("META-INF/")) {
                        sha256.reset()
                        v1Digests[name] = Base64.encodeToString(sha256.digest(data), Base64.NO_WRAP)
                    }
                    val entry = ZipEntry(name)
                    if (storeUncompressed) {
                        entry.method = ZipEntry.STORED
                        entry.size = data.size.toLong()
                        entry.compressedSize = data.size.toLong()
                        crc32.reset()
                        crc32.update(data)
                        entry.crc = crc32.value

                        val nameByteLen = name.toByteArray(StandardCharsets.UTF_8).size
                        val align = if (name.endsWith(".so")) 16384L else 4L
                        val pad = ((align - ((countingOut.bytesWritten + 30L + nameByteLen) % align)) % align).toInt()
                        if (pad > 0) {
                            entry.extra = ByteArray(pad)
                        }
                    } else {
                        entry.method = ZipEntry.DEFLATED
                    }
                    zipOut.putNextEntry(entry)
                    zipOut.write(data)
                    zipOut.closeEntry()
                }

                fun streamZipEntryToOut(
                    srcZip: ZipFile,
                    srcEntry: ZipEntry,
                    targetName: String,
                    storeUncompressed: Boolean
                ) {
                    if (writtenEntryNames.contains(targetName)) return
                    val outEntry = ZipEntry(targetName)
                    var precomputedDigestB64: String? = null
                    if (storeUncompressed) {
                        // Always compute actual uncompressed size & CRC32 from the stream because
                        // bundletool/zipflinger/Unity APKs often store crc=0 in local/CD headers,
                        // which causes ZipOutputStream.closeEntry() to throw ZipException!
                        crc32.reset()
                        sha256.reset()
                        var total = 0L
                        srcZip.getInputStream(srcEntry).use { input ->
                            var r: Int
                            while (input.read(sharedBuf).also { r = it } != -1) {
                                crc32.update(sharedBuf, 0, r)
                                sha256.update(sharedBuf, 0, r)
                                total += r
                            }
                        }
                        if (!targetName.startsWith("META-INF/")) {
                            precomputedDigestB64 = Base64.encodeToString(sha256.digest(), Base64.NO_WRAP)
                        }
                        outEntry.method = ZipEntry.STORED
                        outEntry.size = total
                        outEntry.compressedSize = total
                        outEntry.crc = crc32.value

                        val nameByteLen = targetName.toByteArray(StandardCharsets.UTF_8).size
                        val align = if (targetName.endsWith(".so")) 16384L else 4L
                        val pad = ((align - ((countingOut.bytesWritten + 30L + nameByteLen) % align)) % align).toInt()
                        if (pad > 0) {
                            outEntry.extra = ByteArray(pad)
                        }
                    } else {
                        outEntry.method = ZipEntry.DEFLATED
                    }

                    zipOut.putNextEntry(outEntry)
                    if (precomputedDigestB64 == null) {
                        sha256.reset()
                    }
                    srcZip.getInputStream(srcEntry).use { input ->
                        var r: Int
                        while (input.read(sharedBuf).also { r = it } != -1) {
                            zipOut.write(sharedBuf, 0, r)
                            if (precomputedDigestB64 == null) {
                                sha256.update(sharedBuf, 0, r)
                            }
                        }
                    }
                    zipOut.closeEntry()
                    writtenEntryNames.add(targetName)
                    if (!targetName.startsWith("META-INF/")) {
                        v1Digests[targetName] = precomputedDigestB64
                            ?: Base64.encodeToString(sha256.digest(), Base64.NO_WRAP)
                    }
                }

                fun streamFileToOut(
                    srcFile: File,
                    targetName: String,
                    storeUncompressed: Boolean
                ) {
                    if (!srcFile.exists() || writtenEntryNames.contains(targetName)) return
                    val outEntry = ZipEntry(targetName)
                    var precomputedDigestB64: String? = null
                    if (storeUncompressed) {
                        crc32.reset()
                        sha256.reset()
                        var total = 0L
                        BufferedInputStream(FileInputStream(srcFile), 65536).use { input ->
                            var r: Int
                            while (input.read(sharedBuf).also { r = it } != -1) {
                                crc32.update(sharedBuf, 0, r)
                                sha256.update(sharedBuf, 0, r)
                                total += r
                            }
                        }
                        if (!targetName.startsWith("META-INF/")) {
                            precomputedDigestB64 = Base64.encodeToString(sha256.digest(), Base64.NO_WRAP)
                        }
                        outEntry.method = ZipEntry.STORED
                        outEntry.size = total
                        outEntry.compressedSize = total
                        outEntry.crc = crc32.value

                        val nameByteLen = targetName.toByteArray(StandardCharsets.UTF_8).size
                        val align = if (targetName.endsWith(".so")) 16384L else 4L
                        val pad = ((align - ((countingOut.bytesWritten + 30L + nameByteLen) % align)) % align).toInt()
                        if (pad > 0) {
                            outEntry.extra = ByteArray(pad)
                        }
                    } else {
                        outEntry.method = ZipEntry.DEFLATED
                    }

                    zipOut.putNextEntry(outEntry)
                    if (precomputedDigestB64 == null) {
                        sha256.reset()
                    }
                    BufferedInputStream(FileInputStream(srcFile), 65536).use { input ->
                        var r: Int
                        while (input.read(sharedBuf).also { r = it } != -1) {
                            zipOut.write(sharedBuf, 0, r)
                            if (precomputedDigestB64 == null) {
                                sha256.update(sharedBuf, 0, r)
                            }
                        }
                    }
                    zipOut.closeEntry()
                    writtenEntryNames.add(targetName)
                    if (!targetName.startsWith("META-INF/")) {
                        v1Digests[targetName] = precomputedDigestB64
                            ?: Base64.encodeToString(sha256.digest(), Base64.NO_WRAP)
                    }
                }

                fun patchAndWriteDexEntry(
                    srcZip: ZipFile,
                    srcEntry: ZipEntry,
                    targetName: String
                ) {
                    val entrySize = srcEntry.size
                    val maxPatchBytes = 48L * 1024L * 1024L
                    if (entrySize == -1L || entrySize in 112L..maxPatchBytes) {
                        try {
                            val rawDex = srcZip.getInputStream(srcEntry).use { it.readBytes() }
                            if (rawDex.size in 112..maxPatchBytes.toInt()) {
                                val patchedDex = patchTargetDexBytecode(rawDex, originalPkg, inPlace = true)
                                writeInMemoryEntry(targetName, patchedDex, storeUncompressed = false)
                                return
                            }
                        } catch (_: Throwable) {}
                    }
                    streamZipEntryToOut(srcZip, srcEntry, targetName, storeUncompressed = false)
                }

                // Determine preferred ABI folder from actual ELF header of nativeLibraryDir .so files or available APK/split lib/<abi>/ folders
                var preferredAbiFolder: String? = if (!nativeLibraryDir.isNullOrBlank()) {
                    try {
                        val natDir = File(nativeLibraryDir)
                        val firstSo = if (natDir.exists() && natDir.isDirectory) {
                            natDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".so") && it.canRead() && it.length() >= 20L }
                        } else null
                        if (firstSo != null) {
                            var elfAbi: String? = null
                            try {
                                val hdr = ByteArray(20)
                                val readCount = firstSo.inputStream().use { it.read(hdr) }
                                if (readCount == 20 && hdr[0] == 0x7f.toByte() && hdr[1] == 'E'.code.toByte() &&
                                    hdr[2] == 'L'.code.toByte() && hdr[3] == 'F'.code.toByte()
                                ) {
                                    val eMachine = (hdr[18].toInt() and 0xFF) or ((hdr[19].toInt() and 0xFF) shl 8)
                                    elfAbi = when (eMachine) {
                                        0x00B7 -> "arm64-v8a"   // EM_AARCH64
                                        0x0028 -> "armeabi-v7a" // EM_ARM
                                        0x003E -> "x86_64"      // EM_X86_64
                                        0x0003 -> "x86"         // EM_386
                                        else -> null
                                    }
                                }
                            } catch (_: Throwable) {}
                            if (elfAbi != null) {
                                elfAbi
                            } else {
                                val dirName = natDir.name.lowercase(Locale.US)
                                when {
                                    dirName.contains("arm64") || dirName.contains("aarch64") -> "arm64-v8a"
                                    dirName.contains("x86_64") -> "x86_64"
                                    dirName.contains("x86") -> "x86"
                                    else -> "armeabi-v7a"
                                }
                            }
                        } else null
                    } catch (_: Throwable) {
                        null
                    }
                } else null

                if (preferredAbiFolder == null) {
                    try {
                        val availableAbis = HashSet<String>()
                        fun collectAbisFromZip(zf: ZipFile) {
                            val en = zf.entries()
                            while (en.hasMoreElements()) {
                                val e = try { en.nextElement() } catch (_: Throwable) { break }
                                val n = e.name
                                if (n.startsWith("lib/") && n.endsWith(".so")) {
                                    val secondSlash = n.indexOf('/', 4)
                                    if (secondSlash > 4) {
                                        availableAbis.add(n.substring(4, secondSlash))
                                    }
                                }
                            }
                        }
                        ZipFile(sourceApkFile).use { collectAbisFromZip(it) }
                        splitSourceDirs?.forEach { sp ->
                            val sf = File(sp)
                            if (sf.exists() && sf.canRead()) {
                                try { ZipFile(sf).use { collectAbisFromZip(it) } } catch (_: Throwable) {}
                            }
                        }
                        if (availableAbis.isNotEmpty()) {
                            for (devAbi in Build.SUPPORTED_ABIS) {
                                if (devAbi in availableAbis) {
                                    preferredAbiFolder = devAbi
                                    break
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }

                // Create a lightweight string resource resolver from base.apk + splits so @string/... provider
                // authorities in AndroidManifest.xml resolve to their exact original string values.
                val resolveApkStringRes: ((Int) -> String?) = try {
                    val assetMgr = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
                    val addPath = android.content.res.AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
                        isAccessible = true
                    }
                    addPath.invoke(assetMgr, sourceApkFile.absolutePath)
                    splitSourceDirs?.forEach { sp ->
                        if (sp.isNotBlank() && File(sp).exists()) {
                            try { addPath.invoke(assetMgr, sp) } catch (_: Throwable) {}
                        }
                    }
                    @Suppress("DEPRECATION")
                    val apkRes = android.content.res.Resources(
                        assetMgr,
                        android.util.DisplayMetrics(),
                        android.content.res.Configuration()
                    )
                    val fn: (Int) -> String? = { resId ->
                        try {
                            if (resId != 0) apkRes.getString(resId) else null
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    fn
                } catch (_: Throwable) {
                    { null }
                }

                var capturedOriginalAppComponentFactory: String? = null
                val step0FactoryOverride = if (isGoogleFirstPartyPackage(originalPkg) || isSystemSourceApp) {
                    "com.example.standalone.StandaloneAppComponentFactory"
                } else {
                    null
                }

                // 1. Process base.apk: stream "resources.arsc" FIRST (STORED at offset 44, 4-byte aligned)
                ZipFile(sourceApkFile).use { baseZip ->
                    val arscEntry = baseZip.getEntry("resources.arsc")
                    if (arscEntry != null && !arscEntry.isDirectory) {
                        streamZipEntryToOut(baseZip, arscEntry, "resources.arsc", storeUncompressed = true)
                    }

                    val manifestEntry = baseZip.getEntry("AndroidManifest.xml")
                    if (manifestEntry != null && !manifestEntry.isDirectory) {
                        val rawManifest = baseZip.getInputStream(manifestEntry).use { it.readBytes() }
                        val patchedManifest = try {
                            AxmlManifestPatcher.patchManifest(
                                axmlBytes = rawManifest,
                                originalPkg = originalPkg,
                                standalonePkg = standalonePkg,
                                resolveStringResource = resolveApkStringRes,
                                injectBootstrapAndPermissions = true,
                                overrideAppComponentFactory = step0FactoryOverride,
                                onOriginalAppComponentFactory = { origFactory ->
                                    if (origFactory.isNotBlank()) {
                                        capturedOriginalAppComponentFactory = origFactory
                                    }
                                }
                            )
                        } catch (_: Throwable) {
                            rawManifest
                        }
                        writeInMemoryEntry("AndroidManifest.xml", patchedManifest, storeUncompressed = false)
                    }

                    // Pre-populate active native libraries from appInfo.nativeLibraryDir so split/extracted
                    // .so binaries take precedence over any stub .so entries in base.apk
                    if (!nativeLibraryDir.isNullOrBlank() && preferredAbiFolder != null) {
                        try {
                            val natDir = File(nativeLibraryDir)
                            natDir.listFiles()?.forEach { soFile ->
                                if (soFile.isFile && soFile.name.endsWith(".so") && soFile.canRead()) {
                                    val entryName = "lib/$preferredAbiFolder/${soFile.name}"
                                    if (!writtenEntryNames.contains(entryName)) {
                                        try {
                                            streamFileToOut(soFile, entryName, storeUncompressed = true)
                                        } catch (_: Throwable) {
                                            try { zipOut.closeEntry() } catch (_: Throwable) {}
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }

                    val entries = baseZip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = try {
                            entries.nextElement()
                        } catch (_: Throwable) {
                            break
                        }
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (name == "resources.arsc" ||
                            name == "AndroidManifest.xml" ||
                            name == "res/xml/splits0.xml" ||
                            name == "stamp-cert-sha256" ||
                            isMetaInfSignatureEntry(name)
                        ) {
                            continue
                        }

                        try {
                            if (!name.contains('/') && name.startsWith("classes") && name.endsWith(".dex")) {
                                val idxStr = name.removePrefix("classes").removeSuffix(".dex")
                                val parsedIdx = if (idxStr.isEmpty()) 1 else idxStr.toIntOrNull()
                                if (parsedIdx != null && parsedIdx >= 1) {
                                    if (parsedIdx > maxDexIndex) maxDexIndex = parsedIdx
                                    patchAndWriteDexEntry(baseZip, entry, name)
                                } else {
                                    val freeIdx = nextFreeDexIndex()
                                    val targetDex = if (freeIdx == 1) "classes.dex" else "classes${freeIdx}.dex"
                                    patchAndWriteDexEntry(baseZip, entry, targetDex)
                                }
                            } else if (!writtenEntryNames.contains(name)) {
                                val storeUncompressed = (entry.method == ZipEntry.STORED) || name.endsWith(".so")
                                streamZipEntryToOut(baseZip, entry, name, storeUncompressed)
                            }
                        } catch (_: Throwable) {
                            try { zipOut.closeEntry() } catch (_: Throwable) {}
                        }
                        reportRepackageProgress()
                    }
                }

                // 2. Stream native libraries, DEX files, assets, and split resource tables from split APKs
                if (!splitSourceDirs.isNullOrEmpty()) {
                    for (splitPath in splitSourceDirs) {
                        val splitFile = File(splitPath)
                        if (!splitFile.exists() || !splitFile.canRead()) continue
                        try {
                            ZipFile(splitFile).use { splitZip ->
                                var hasSplitArsc = false
                                if (splitZip.getEntry("resources.arsc") != null) {
                                    hasSplitArsc = true
                                    val nextIdx = splitResCount + 1
                                    val tmpSplitFile = File(
                                        unsignedOutFile.parentFile,
                                        "tmp_split_res_${System.nanoTime()}_$nextIdx.apk"
                                    )
                                    try {
                                        val ok = try {
                                            buildResourceOnlySplitApkToFile(
                                                splitZip,
                                                tmpSplitFile,
                                                sharedBuf,
                                                originalPkg,
                                                standalonePkg
                                            )
                                        } catch (_: Throwable) {
                                            false
                                        }
                                        if (ok) {
                                            splitResCount = nextIdx
                                            streamFileToOut(
                                                tmpSplitFile,
                                                "assets/phantom_splits/split_res_${splitResCount}.apk",
                                                storeUncompressed = true
                                            )
                                        }
                                    } finally {
                                        try { tmpSplitFile.delete() } catch (_: Throwable) {}
                                    }
                                }

                                val entries = splitZip.entries()
                                while (entries.hasMoreElements()) {
                                    val entry = try {
                                        entries.nextElement()
                                    } catch (_: Throwable) {
                                        break
                                    }
                                    if (entry.isDirectory) continue
                                    val name = entry.name
                                    if (isMetaInfSignatureEntry(name) ||
                                        name == "AndroidManifest.xml" ||
                                        name == "resources.arsc" ||
                                        name == "res/xml/splits0.xml" ||
                                        name == "stamp-cert-sha256"
                                    ) {
                                        continue
                                    }
                                    try {
                                        if (!name.contains('/') && name.startsWith("classes") && name.endsWith(".dex")) {
                                            val freeIdx = nextFreeDexIndex()
                                            val targetDex = if (freeIdx == 1) "classes.dex" else "classes${freeIdx}.dex"
                                            patchAndWriteDexEntry(splitZip, entry, targetDex)
                                        } else if (!writtenEntryNames.contains(name)) {
                                            val storeUncompressed = (entry.method == ZipEntry.STORED) || name.endsWith(".so")
                                            streamZipEntryToOut(splitZip, entry, name, storeUncompressed)
                                        }
                                    } catch (_: Throwable) {
                                        try { zipOut.closeEntry() } catch (_: Throwable) {}
                                    }
                                    reportRepackageProgress()
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 3b. If base.apk & splits were DEX-stripped pre-installed system APKs, write recovered VDEX DEXes first!
                if (fallbackRecoveredDexes.isNotEmpty() && !writtenEntryNames.contains("classes.dex")) {
                    for (recDex in fallbackRecoveredDexes) {
                        val freeIdx = nextFreeDexIndex()
                        val dexEntryName = if (freeIdx == 1) "classes.dex" else "classes${freeIdx}.dex"
                        val patchedRec = try {
                            patchTargetDexBytecode(recDex, originalPkg, inPlace = true)
                        } catch (_: Throwable) {
                            recDex
                        }
                        writeInMemoryEntry(dexEntryName, patchedRec, storeUncompressed = false)
                    }
                }

                // 4. Inject our Standalone Runtime DEX(es) as the next contiguous classes<Next>.dex (zero gaps!)
                for (dexBytes in injectedRuntimeDexes) {
                    val freeIdx = nextFreeDexIndex()
                    val dexEntryName = if (freeIdx == 1) "classes.dex" else "classes${freeIdx}.dex"
                    writeInMemoryEntry(dexEntryName, dexBytes, storeUncompressed = false)
                }

                // 5. Inject baked-in configuration JSON (evaluating live toggles at finish + exact splitResCount)
                val providedBytes = try {
                    configJsonBytesProvider?.invoke(splitResCount) ?: configJsonBytes
                } catch (_: Throwable) {
                    configJsonBytes
                }
                val finalConfigBytes = try {
                    val cfgJson = JSONObject(String(providedBytes, StandardCharsets.UTF_8))
                    cfgJson.put("splitResCount", splitResCount)
                    if (!capturedOriginalAppComponentFactory.isNullOrBlank()) {
                        cfgJson.put("originalAppComponentFactory", capturedOriginalAppComponentFactory)
                    }
                    cfgJson.toString().toByteArray(StandardCharsets.UTF_8)
                } catch (_: Throwable) {
                    providedBytes
                }
                writeInMemoryEntry("assets/phantom_standalone_config.json", finalConfigBytes, storeUncompressed = false)

                // Release entry-set memory before generating V1 signatures for 40,000+ entry games
                writtenEntryNames.clear()

                // 6. Stream V1 Signature Block (MANIFEST.MF, CERT.SF, CERT.RSA) directly to zipOut with zero large ByteArray allocations
                onProgressPercent(79)
                writeStreamingV1SignatureEntries(zipOut, v1Digests)
                v1Digests.clear()
                onProgressPercent(80)
            }
        }
    }

    /**
     * Kept for unit-test compatibility if needed; repackageAndPatchZip preserves original resources.arsc bytes.
     */
    internal fun patchResourcesArscPackageName(
        arscBytes: ByteArray,
        originalPkg: String,
        standalonePkg: String
    ): ByteArray {
        if (arscBytes.size < 280 || standalonePkg.length > 127) return arscBytes
        val out = arscBytes.copyOf()
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        val tableType = buf.getShort(0).toInt() and 0xFFFF
        val tableHeaderSize = buf.getShort(2).toInt() and 0xFFFF
        val tableSize = buf.getInt(4)
        if (tableType != 0x0002 || tableHeaderSize < 12 || tableSize > out.size) return arscBytes

        var offset = tableHeaderSize
        while (offset + 8 <= out.size) {
            val chunkType = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize <= 8 || offset + chunkSize > out.size) break

            if (chunkType == 0x0200 && offset + 12 + 256 <= out.size) {
                val pkgId = buf.getInt(offset + 8)
                if (pkgId == 0x7F) {
                    val nameStart = offset + 12
                    val sb = StringBuilder()
                    for (i in 0 until 128) {
                        val ch = buf.getShort(nameStart + i * 2).toInt() and 0xFFFF
                        if (ch == 0) break
                        sb.append(ch.toChar())
                    }
                    if (sb.toString() == originalPkg) {
                        for (i in 0 until 128) {
                            val code = if (i < standalonePkg.length) standalonePkg[i].code.toShort() else 0.toShort()
                            buf.putShort(nameStart + i * 2, code)
                        }
                    }
                }
            }
            offset += chunkSize
        }
        return out
    }

    /**
     * Redirects target app DEX `string_data_item` entry `\u0018Landroid/os/SystemClock;\u0000`
     * to `\u0018Landroid/os/SystemClocl;\u0000` (exact 24-char descriptor) and recomputes the
     * DEX SHA-1 signature and Adler32 header checksum.
     * Skips DEX files that already contain `SystemClocl` or `StandaloneRuntimeEngine`.
     */
    internal fun patchTargetDexBytecode(
        dexBytes: ByteArray,
        originalPkg: String = "",
        inPlace: Boolean = false
    ): ByteArray {
        if (dexBytes.size < 112) return dexBytes
        // Verify DEX magic ("dex\n")
        if (dexBytes[0] != 0x64.toByte() ||
            dexBytes[1] != 0x65.toByte() ||
            dexBytes[2] != 0x78.toByte() ||
            dexBytes[3] != 0x0A.toByte()
        ) {
            return dexBytes
        }

        val alreadyPatchedNeedle = "Landroid/os/SystemClocl;".toByteArray(StandardCharsets.UTF_8)
        val alreadyPatchedAnimNeedle = "Landroid/view/animation/AnimationUtilt;".toByteArray(StandardCharsets.UTF_8)
        val alreadyPatchedPropNeedle = "Landroid/os/SystemPropertiet;".toByteArray(StandardCharsets.UTF_8)
        val alreadyPatchedProcNeedle = "Landroid/os/Procest;".toByteArray(StandardCharsets.UTF_8)
        val runtimeNeedle = "Lcom/example/standalone/StandaloneRuntimeEngine;".toByteArray(StandardCharsets.UTF_8)
        if (indexOfBytes(dexBytes, runtimeNeedle) >= 0) {
            return dexBytes
        }
        val isGoogle1p = isGoogleFirstPartyPackage(originalPkg)
        if (isGoogle1p) {
            if (indexOfBytes(dexBytes, alreadyPatchedProcNeedle) >= 0) {
                return dexBytes
            }
        } else if (indexOfBytes(dexBytes, alreadyPatchedNeedle) >= 0 ||
            indexOfBytes(dexBytes, alreadyPatchedAnimNeedle) >= 0 ||
            indexOfBytes(dexBytes, alreadyPatchedPropNeedle) >= 0
        ) {
            return dexBytes
        }

        val out = if (inPlace) dexBytes else dexBytes.copyOf()
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsSize = buf.getInt(56)
        val stringIdsOff = buf.getInt(60)
        if (stringIdsSize <= 0 || stringIdsOff <= 0 || stringIdsOff + stringIdsSize * 4 > out.size) {
            return dexBytes
        }

        // Safely mutates a DEX string_id entry ONLY if its lexicographical UTF-16/MUTF-8 sort order
        // relative to string_ids[idx - 1] and string_ids[idx + 1] remains strictly ascending!
        // Preserving strict ordering prevents breaking ART's binary search in DexFile::FindStringId.
        fun tryMutateDexStringIdPreservingOrder(targetStr: String, mutatedStr: String): Boolean {
            if (targetStr.length != mutatedStr.length) return false
            var lo = 0
            var hi = stringIdsSize - 1
            var foundIdx = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val cmp = compareDexStringAtIdxWithAscii(out, buf, stringIdsOff, stringIdsSize, mid, targetStr)
                if (cmp == 0) {
                    foundIdx = mid
                    break
                } else if (cmp < 0) {
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            if (foundIdx < 0) return false
            if (foundIdx > 0) {
                if (compareDexStringAtIdxWithAscii(out, buf, stringIdsOff, stringIdsSize, foundIdx - 1, mutatedStr) >= 0) {
                    return false
                }
            }
            if (foundIdx + 1 < stringIdsSize) {
                if (compareDexStringAtIdxWithAscii(out, buf, stringIdsOff, stringIdsSize, foundIdx + 1, mutatedStr) <= 0) {
                    return false
                }
            }
            val dataOff = buf.getInt(stringIdsOff + foundIdx * 4)
            if (dataOff <= 0 || dataOff >= out.size) return false
            val (utf16Len, start) = readUleb128(out, dataOff)
            if (utf16Len != targetStr.length || start + mutatedStr.length >= out.size) return false
            for (i in mutatedStr.indices) {
                out[start + i] = mutatedStr[i].code.toByte()
            }
            return true
        }

        var modified = false
        if (isGoogle1p && tryMutateDexStringIdPreservingOrder("Landroid/os/Process;", "Landroid/os/Procest;")) {
            modified = true
        }
        if (!isGoogle1p && tryMutateDexStringIdPreservingOrder("Landroid/os/SystemClock;", "Landroid/os/SystemClocl;")) {
            modified = true
        }
        if (!isGoogle1p && tryMutateDexStringIdPreservingOrder("Landroid/view/animation/AnimationUtils;", "Landroid/view/animation/AnimationUtilt;")) {
            modified = true
        }
        if (tryMutateDexStringIdPreservingOrder("Landroid/os/SystemProperties;", "Landroid/os/SystemPropertiet;")) {
            modified = true
        }
        if (tryMutateDexStringIdPreservingOrder("android.os.SystemProperties", "android.os.SystemPropertiet")) {
            modified = true
        }

        if (!isGoogle1p && redirectTargetAppSystemClockCalls(out, originalPkg)) {
            modified = true
        }

        if (modified) {
            try {
                val sha1 = MessageDigest.getInstance("SHA-1")
                sha1.update(out, 32, out.size - 32)
                val sigBytes = sha1.digest()
                System.arraycopy(sigBytes, 0, out, 12, 20)

                val adler = Adler32()
                adler.update(out, 12, out.size - 12)
                val checksum = adler.value.toInt()
                out[8] = (checksum and 0xFF).toByte()
                out[9] = ((checksum ushr 8) and 0xFF).toByte()
                out[10] = ((checksum ushr 16) and 0xFF).toByte()
                out[11] = ((checksum ushr 24) and 0xFF).toByte()
            } catch (_: Throwable) {
                return dexBytes
            }
        }
        return out
    }

    private fun compareDexStringAtIdxWithAscii(
        bytes: ByteArray,
        buf: ByteBuffer,
        stringIdsOff: Int,
        stringIdsSize: Int,
        strIdx: Int,
        targetAscii: String
    ): Int {
        if (strIdx < 0 || strIdx >= stringIdsSize) return -1
        val dataOff = buf.getInt(stringIdsOff + strIdx * 4)
        if (dataOff <= 0 || dataOff >= bytes.size) return -1
        val (_, start) = readUleb128(bytes, dataOff)
        var pos = start
        var tIdx = 0
        val tLen = targetAscii.length
        while (pos < bytes.size) {
            val b0 = bytes[pos].toInt() and 0xFF
            if (b0 == 0) {
                return if (tIdx == tLen) 0 else -1
            }
            val cp: Int
            if ((b0 and 0x80) == 0) {
                cp = b0
                pos += 1
            } else if ((b0 and 0xE0) == 0xC0 && pos + 1 < bytes.size) {
                val b1 = bytes[pos + 1].toInt() and 0x3F
                cp = ((b0 and 0x1F) shl 6) or b1
                pos += 2
            } else if ((b0 and 0xF0) == 0xE0 && pos + 2 < bytes.size) {
                val b1 = bytes[pos + 1].toInt() and 0x3F
                val b2 = bytes[pos + 2].toInt() and 0x3F
                cp = ((b0 and 0x0F) shl 12) or (b1 shl 6) or b2
                pos += 3
            } else {
                cp = b0
                pos += 1
            }
            if (tIdx >= tLen) return 1
            val targetCh = targetAscii[tIdx].code
            if (cp != targetCh) {
                return if (cp < targetCh) -1 else 1
            }
            tIdx++
        }
        return if (tIdx == tLen) 0 else -1
    }

    private fun readUleb128(bytes: ByteArray, startPos: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = startPos
        while (pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return Pair(result, pos)
    }

    private fun readDexStringAscii(bytes: ByteArray, buf: ByteBuffer, stringIdsOff: Int, stringIdsSize: Int, strIdx: Int): String {
        if (strIdx < 0 || strIdx >= stringIdsSize) return ""
        val dataOff = buf.getInt(stringIdsOff + strIdx * 4)
        if (dataOff <= 0 || dataOff >= bytes.size) return ""
        val (_, start) = readUleb128(bytes, dataOff)
        var end = start
        while (end < bytes.size && bytes[end].toInt() != 0) {
            end++
        }
        return String(bytes, start, end - start, StandardCharsets.UTF_8)
    }

    /**
     * Returns the exact instruction width in 16-bit code units for any Dalvik opcode (0x00..0xFF),
     * or -1 if `w0` is a variable-length switch/array payload pseudo-instruction (`0x0100`, `0x0200`, `0x0300`)
     * at the end of the method's instruction stream.
     */
    private fun getDalvikInsnWidthWords(w0: Int): Int {
        if (w0 == 0x0100 || w0 == 0x0200 || w0 == 0x0300) {
            return -1
        }
        return when (w0 and 0xFF) {
            0x00, 0x01, 0x04, 0x07, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x10, 0x11, 0x12, 0x1D, 0x1E, 0x21, 0x27, 0x28,
            in 0x3E..0x43,
            in 0x73..0x73,
            in 0x79..0x8F,
            in 0xB0..0xCF,
            in 0xEC..0xEF -> 1

            0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F,
            0x20, 0x22, 0x23, 0x29,
            in 0x2D..0x3D,
            in 0x44..0x6D,
            in 0x90..0xAF,
            in 0xD0..0xEB,
            in 0xF0..0xF9,
            0xFE, 0xFF -> 2

            0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A,
            0x2B, 0x2C,
            in 0x6E..0x72,
            in 0x74..0x78,
            0xFC, 0xFD -> 3

            0xFA, 0xFB -> 4
            0x18 -> 5
            else -> 1
        }
    }

    private fun isRedirectableClockCallerClass(classDesc: String, originalPkgPrefix: String): Boolean {
        if (classDesc.isEmpty()) return false
        // Exclude Auth, Network/TLS/DNS/QUIC stacks, Coroutines, Media, and standalone runtime infrastructure
        if (classDesc.startsWith("Lcom/google/android/gms/") ||
            classDesc.startsWith("Lcom/google/firebase/") ||
            classDesc.startsWith("Lcom/google/crypto/") ||
            classDesc.startsWith("Lcom/google/android/exoplayer2/") ||
            classDesc.startsWith("Lcom/google/android/libraries/youtube/") ||
            classDesc.startsWith("Lcom/google/android/apps/youtube/") ||
            classDesc.startsWith("Lcom/google/protobuf/") ||
            classDesc.startsWith("Lcom/google/common/") ||
            classDesc.startsWith("Lcom/google/net/") ||
            classDesc.startsWith("Lcom/android/") ||
            classDesc.startsWith("Landroid/") ||
            classDesc.startsWith("Landroidx/compose/ui/input/") ||
            classDesc.startsWith("Landroidx/concurrent/") ||
            classDesc.startsWith("Landroidx/credentials/") ||
            classDesc.startsWith("Landroidx/media3/") ||
            classDesc.startsWith("Landroidx/room/") ||
            classDesc.startsWith("Landroidx/work/") ||
            classDesc.startsWith("Lcom/facebook/proxygen/") ||
            classDesc.startsWith("Lcom/facebook/liger/") ||
            classDesc.startsWith("Lcom/facebook/tigon/") ||
            classDesc.startsWith("Lcom/facebook/http/") ||
            classDesc.startsWith("Lcom/facebook/mqtt/") ||
            classDesc.startsWith("Lcom/facebook/msys/") ||
            classDesc.startsWith("Lcom/facebook/zero/") ||
            classDesc.startsWith("Lcom/facebook/network/") ||
            classDesc.startsWith("Lcom/facebook/common/network/") ||
            classDesc.startsWith("Lokhttp3/") ||
            classDesc.startsWith("Lokio/") ||
            classDesc.startsWith("Lretrofit2/") ||
            classDesc.startsWith("Lio/grpc/") ||
            classDesc.startsWith("Lio/ktor/") ||
            classDesc.startsWith("Lio/netty/") ||
            classDesc.startsWith("Lorg/chromium/") ||
            classDesc.startsWith("Lorg/conscrypt/") ||
            classDesc.startsWith("Lorg/osmdroid/") ||
            classDesc.startsWith("Lcom/bumptech/glide/") ||
            classDesc.startsWith("Lcoil/") ||
            classDesc.startsWith("Lcoil3/") ||
            classDesc.startsWith("Lcom/squareup/picasso/") ||
            classDesc.startsWith("Lcom/pairip/") ||
            classDesc.startsWith("Lcom/example/standalone/") ||
            classDesc.startsWith("Lcom/clone/android/dual/space") ||
            classDesc.startsWith("Ldalvik/") ||
            classDesc.startsWith("Llibcore/") ||
            classDesc.startsWith("Lsun/") ||
            classDesc.startsWith("Ljava/") ||
            classDesc.startsWith("Ljavax/")
        ) {
            return false
        }
        if (originalPkgPrefix.isNotEmpty() && classDesc.startsWith(originalPkgPrefix)) {
            return true
        }
        return true
    }

    /**
     * Redirects 0-arg static `()J` clock invocations (`System.currentTimeMillis()`, `System.nanoTime()`)
     * inside target app classes to existing `SystemClocl` / `AnimationUtilt` / `SystemPropertiet` method_ids
     * WITHOUT ever modifying or adding any `method_id` slot in `method_ids` and using exact Dalvik
     * instruction width decoding.
     *
     * When the target DEX does not already contain a `SystemClocl.currentTimeMillis` or `SystemClocl.nanoTime`
     * `method_id`, this method synthesizes up to 2 tiny 6-word in-DEX static `()J` trampoline methods
     * (repurposing safe 0-arg static `()J` methods whose existing bodies are >= 6 code units and unused
     * as wall/nano targets) that invoke `SystemPropertiet.getLong("Landroid/os/SystemClocl;", 0L)` or
     * `SystemPropertiet.getLong("Landroid/os/SystemPropertiet;", 0L)` (or `SystemClocl.elapsedRealtime()`
     * / `uptimeMillis()`) so `System.currentTimeMillis()` and `System.nanoTime()` are scaled in 100% of
     * built APKs!
     */
    private fun redirectTargetAppSystemClockCalls(out: ByteArray, originalPkg: String): Boolean {
        if (out.size < 112) return false
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsSize = buf.getInt(56)
        val stringIdsOff = buf.getInt(60)
        val typeIdsSize = buf.getInt(64)
        val typeIdsOff = buf.getInt(68)
        val protoIdsSize = buf.getInt(72)
        val protoIdsOff = buf.getInt(76)
        val methodIdsSize = buf.getInt(88)
        val methodIdsOff = buf.getInt(92)
        val classDefsSize = buf.getInt(96)
        val classDefsOff = buf.getInt(100)

        if (stringIdsSize <= 0 || typeIdsSize <= 0 || protoIdsSize <= 0 ||
            methodIdsSize <= 0 || classDefsSize <= 0 || methodIdsSize > 65536
        ) {
            return false
        }

        var systemTypeIdx = -1
        var systemCloclTypeIdx = -1
        var animUtiltTypeIdx = -1
        var sysPropertietTypeIdx = -1
        var longPrimitiveTypeIdx = -1
        var stringTypeIdx = -1
        var cloclDescStrIdx = -1
        var propDescStrIdx = -1

        for (t in 0 until typeIdsSize) {
            val strIdx = buf.getInt(typeIdsOff + t * 4)
            val desc = readDexStringAscii(out, buf, stringIdsOff, stringIdsSize, strIdx)
            when (desc) {
                "Ljava/lang/System;" -> systemTypeIdx = t
                "Landroid/os/SystemClocl;" -> {
                    systemCloclTypeIdx = t
                    cloclDescStrIdx = strIdx
                }
                "Landroid/view/animation/AnimationUtilt;" -> animUtiltTypeIdx = t
                "Landroid/os/SystemPropertiet;" -> {
                    sysPropertietTypeIdx = t
                    propDescStrIdx = strIdx
                }
                "J" -> longPrimitiveTypeIdx = t
                "Ljava/lang/String;" -> stringTypeIdx = t
            }
        }
        if (longPrimitiveTypeIdx < 0 || systemTypeIdx < 0) {
            return false
        }

        var protoJIdx = -1
        for (p in 0 until protoIdsSize) {
            val pOff = protoIdsOff + p * 12
            val returnTypeIdx = buf.getInt(pOff + 4)
            val paramsOff = buf.getInt(pOff + 8)
            if (returnTypeIdx == longPrimitiveTypeIdx && paramsOff == 0) {
                protoJIdx = p
                break
            }
        }
        if (protoJIdx < 0) return false

        var sysCurTimeMethodIdx = -1
        var sysNanoTimeMethodIdx = -1
        var wallTargetMethodIdx = -1
        var nanoTargetMethodIdx = -1
        var cloclElapsedMethodIdx = -1
        var cloclUptimeMethodIdx = -1
        var animCurTimeMethodIdx = -1
        var propGetLongMethodIdx = -1

        for (m in 0 until methodIdsSize) {
            val mOff = methodIdsOff + m * 8
            val cIdx = buf.getShort(mOff).toInt() and 0xFFFF
            val pIdx = buf.getShort(mOff + 2).toInt() and 0xFFFF
            val nIdx = buf.getInt(mOff + 4)
            if (pIdx == protoJIdx) {
                if (cIdx == systemTypeIdx) {
                    val mName = readDexStringAscii(out, buf, stringIdsOff, stringIdsSize, nIdx)
                    if (mName == "currentTimeMillis") {
                        sysCurTimeMethodIdx = m
                    } else if (mName == "nanoTime") {
                        sysNanoTimeMethodIdx = m
                    }
                } else if (cIdx == systemCloclTypeIdx) {
                    val mName = readDexStringAscii(out, buf, stringIdsOff, stringIdsSize, nIdx)
                    when (mName) {
                        "currentTimeMillis", "currentNetworkTimeMillis" -> {
                            if (wallTargetMethodIdx < 0) wallTargetMethodIdx = m
                        }
                        "nanoTime", "elapsedRealtimeNanos", "uptimeNanos" -> {
                            if (nanoTargetMethodIdx < 0) nanoTargetMethodIdx = m
                        }
                        "elapsedRealtime" -> cloclElapsedMethodIdx = m
                        "uptimeMillis" -> cloclUptimeMethodIdx = m
                    }
                } else if (cIdx == animUtiltTypeIdx) {
                    val mName = readDexStringAscii(out, buf, stringIdsOff, stringIdsSize, nIdx)
                    when (mName) {
                        "currentTimeMillis" -> if (wallTargetMethodIdx < 0) wallTargetMethodIdx = m
                        "nanoTime" -> if (nanoTargetMethodIdx < 0) nanoTargetMethodIdx = m
                        "currentAnimationTimeMillis" -> animCurTimeMethodIdx = m
                    }
                }
            } else if (cIdx == sysPropertietTypeIdx && stringTypeIdx >= 0) {
                val pOff = protoIdsOff + pIdx * 12
                val retType = buf.getInt(pOff + 4)
                if (retType == longPrimitiveTypeIdx) {
                    val mName = readDexStringAscii(out, buf, stringIdsOff, stringIdsSize, nIdx)
                    if (mName == "getLong") {
                        propGetLongMethodIdx = m
                    }
                }
            }
        }

        // Notice: for `System.nanoTime()`, relative duration calculations (`System.nanoTime() - startNano`)
        // can also use `SystemClocl.elapsedRealtimeNanos()` or `SystemClocl.uptimeNanos()` directly.
        // If `wallTargetMethodIdx < 0` or `nanoTargetMethodIdx < 0` and `propGetLongMethodIdx >= 0`,
        // we can check if there is a `SystemPropertiet.getLong(String, long)` method_id in this DEX;
        // or even better: for `System.currentTimeMillis()` / `System.nanoTime()`, check if any class in the DEX
        // defines a static `()J` method that simply wraps `System.currentTimeMillis()` or `System.nanoTime()`!
        val originalPkgPrefix = if (originalPkg.isNotBlank()) {
            "L" + originalPkg.replace('.', '/') + "/"
        } else {
            ""
        }

        // Scan for static `()J` methods in the DEX to synthesize a clean trampoline when direct
        // `SystemClocl.currentTimeMillis()` / `SystemClocl.nanoTime()` method_ids are absent from the DEX:
        // - Tier 1: If `SystemPropertiet.getLong(String, long)` is in the DEX (`propGetLongMethodIdx >= 0`),
        //   any 9+ word static `()J` method that calls `System.currentTimeMillis()` or `System.nanoTime()`
        //   is patched to call `SystemPropertiet.getLong(wallStrIdx, 1L or 2L)`.
        // - Tier 2: If `propGetLongMethodIdx < 0`, for `System.nanoTime()`, if `cloclElapsedMethodIdx >= 0`
        //   or `cloclUptimeMethodIdx >= 0`, we can synthesize an 8-word trampoline:
        //   `invoke-static {}, SystemClocl.elapsedRealtime()J` (3 words)
        //   `move-result-wide v0` (1 word)
        //   `const-wide/32 v2, 1000000` (3 words: 0x0217, 0x4240, 0x000F)
        //   `mul-long/2addr v0, v2` (1 word: 0x209D)
        //   `return-wide v0` (1 word: 0x0010) -> total 9 words, 4 registers!
        //   And for `System.currentTimeMillis()`, if `cloclElapsedMethodIdx >= 0`, we can synthesize an
        //   11-word trampoline using a base epoch constant or `1732400000000L + SystemClocl.elapsedRealtime()`!
        if ((wallTargetMethodIdx < 0 && sysCurTimeMethodIdx >= 0) ||
            (nanoTargetMethodIdx < 0 && sysNanoTimeMethodIdx >= 0)
        ) {
            val wallStrIdx = if (cloclDescStrIdx in 0..0xFFFF) cloclDescStrIdx else if (propDescStrIdx in 0..0xFFFF) propDescStrIdx else -1
            val monoClockMethodIdx = if (cloclElapsedMethodIdx >= 0) cloclElapsedMethodIdx else if (cloclUptimeMethodIdx >= 0) cloclUptimeMethodIdx else animCurTimeMethodIdx

            if ((propGetLongMethodIdx >= 0 && wallStrIdx in 0..0xFFFF) || monoClockMethodIdx >= 0) {
                for (i in 0 until classDefsSize) {
                    if ((wallTargetMethodIdx >= 0 || sysCurTimeMethodIdx < 0) &&
                        (nanoTargetMethodIdx >= 0 || sysNanoTimeMethodIdx < 0)
                    ) {
                        break
                    }
                    val cDefOff = classDefsOff + i * 32
                    val cIdx = buf.getInt(cDefOff)
                    if (cIdx < 0 || cIdx >= typeIdsSize) continue
                    val classDataOff = buf.getInt(cDefOff + 24)
                    if (classDataOff <= 0 || classDataOff >= out.size) continue
                    var pos = classDataOff
                    val (sfSize, p1) = readUleb128(out, pos); pos = p1
                    val (ifSize, p2) = readUleb128(out, pos); pos = p2
                    val (dmSize, p3) = readUleb128(out, pos); pos = p3
                    val (vmSize, p4) = readUleb128(out, pos); pos = p4
                    for (f in 0 until (sfSize + ifSize)) {
                        val (_, fp1) = readUleb128(out, pos); pos = fp1
                        val (_, fp2) = readUleb128(out, pos); pos = fp2
                    }
                    var methodIdx = 0
                    for (m in 0 until dmSize) {
                        val (mDiff, mp1) = readUleb128(out, pos); pos = mp1
                        methodIdx += mDiff
                        val (accFlags, mp2) = readUleb128(out, pos); pos = mp2
                        val (codeOff, mp3) = readUleb128(out, pos); pos = mp3
                        // ACC_STATIC = 0x0008, ACC_PUBLIC = 0x0001
                        if ((accFlags and 0x0009) == 0x0009 &&
                            methodIdx in 0 until methodIdsSize &&
                            codeOff > 0 && codeOff + 34 <= out.size
                        ) {
                            val mOff = methodIdsOff + methodIdx * 8
                            val pIdx = buf.getShort(mOff + 2).toInt() and 0xFFFF
                            if (pIdx == protoJIdx) {
                                val regsSize = buf.getShort(codeOff).toInt() and 0xFFFF
                                val insSize = buf.getShort(codeOff + 2).toInt() and 0xFFFF
                                val triesSize = buf.getShort(codeOff + 6).toInt() and 0xFFFF
                                val insnsSize = buf.getInt(codeOff + 12)
                                val insnsStart = codeOff + 16
                                if (insSize == 0 && triesSize == 0 && regsSize >= 4 && insnsSize in 9..14 &&
                                    insnsStart + insnsSize * 2 <= out.size
                                ) {
                                    var callsCurTime = false
                                    var callsNanoTime = false
                                    var w = 0
                                    while (w < insnsSize) {
                                        val w0 = buf.getShort(insnsStart + w * 2).toInt() and 0xFFFF
                                        val iw = getDalvikInsnWidthWords(w0)
                                        if (iw <= 0 || w + iw > insnsSize) break
                                        if (iw == 3 && w0 == 0x0071) {
                                            val mRef = buf.getShort(insnsStart + (w + 1) * 2).toInt() and 0xFFFF
                                            if (mRef == sysCurTimeMethodIdx) callsCurTime = true
                                            if (mRef == sysNanoTimeMethodIdx) callsNanoTime = true
                                        }
                                        w += iw
                                    }
                                    if (callsCurTime && wallTargetMethodIdx < 0) {
                                        if (propGetLongMethodIdx >= 0 && wallStrIdx in 0..0xFFFF && insnsSize >= 9) {
                                            val curOuts = buf.getShort(codeOff + 4).toInt() and 0xFFFF
                                            if (curOuts < 3) buf.putShort(codeOff + 4, 3.toShort())
                                            buf.putShort(insnsStart + 0, 0x001A.toShort())
                                            buf.putShort(insnsStart + 2, wallStrIdx.toShort())
                                            buf.putShort(insnsStart + 4, 0x0116.toShort())
                                            buf.putShort(insnsStart + 6, 0x0001.toShort())
                                            buf.putShort(insnsStart + 8, 0x3071.toShort())
                                            buf.putShort(insnsStart + 10, propGetLongMethodIdx.toShort())
                                            buf.putShort(insnsStart + 12, 0x0210.toShort())
                                            buf.putShort(insnsStart + 14, 0x000B.toShort())
                                            buf.putShort(insnsStart + 16, 0x0010.toShort())
                                            for (pad in 9 until insnsSize) {
                                                buf.putShort(insnsStart + pad * 2, 0x0000.toShort())
                                            }
                                            wallTargetMethodIdx = methodIdx
                                        }
                                    } else if (callsNanoTime && nanoTargetMethodIdx < 0) {
                                        if (propGetLongMethodIdx >= 0 && wallStrIdx in 0..0xFFFF && insnsSize >= 9) {
                                            val curOuts = buf.getShort(codeOff + 4).toInt() and 0xFFFF
                                            if (curOuts < 3) buf.putShort(codeOff + 4, 3.toShort())
                                            buf.putShort(insnsStart + 0, 0x001A.toShort())
                                            buf.putShort(insnsStart + 2, wallStrIdx.toShort())
                                            buf.putShort(insnsStart + 4, 0x0116.toShort())
                                            buf.putShort(insnsStart + 6, 0x0002.toShort())
                                            buf.putShort(insnsStart + 8, 0x3071.toShort())
                                            buf.putShort(insnsStart + 10, propGetLongMethodIdx.toShort())
                                            buf.putShort(insnsStart + 12, 0x0210.toShort())
                                            buf.putShort(insnsStart + 14, 0x000B.toShort())
                                            buf.putShort(insnsStart + 16, 0x0010.toShort())
                                            for (pad in 9 until insnsSize) {
                                                buf.putShort(insnsStart + pad * 2, 0x0000.toShort())
                                            }
                                            nanoTargetMethodIdx = methodIdx
                                        } else if (monoClockMethodIdx >= 0 && insnsSize >= 9) {
                                            // invoke-static {}, monoClockMethodIdx (3 words: 0x0071, monoClockMethodIdx, 0x0000)
                                            // move-result-wide v0                  (1 word:  0x000B)
                                            // const-wide/32 v2, 1000000            (3 words: 0x0217, 0x4240, 0x000F)
                                            // mul-long/2addr v0, v2                (1 word:  0x209D)
                                            // return-wide v0                       (1 word:  0x0010)
                                            buf.putShort(insnsStart + 0, 0x0071.toShort())
                                            buf.putShort(insnsStart + 2, monoClockMethodIdx.toShort())
                                            buf.putShort(insnsStart + 4, 0x0000.toShort())
                                            buf.putShort(insnsStart + 6, 0x000B.toShort())
                                            buf.putShort(insnsStart + 8, 0x0217.toShort())
                                            buf.putShort(insnsStart + 10, 0x4240.toShort())
                                            buf.putShort(insnsStart + 12, 0x000F.toShort())
                                            buf.putShort(insnsStart + 14, 0x209D.toShort())
                                            buf.putShort(insnsStart + 16, 0x0010.toShort())
                                            for (pad in 9 until insnsSize) {
                                                buf.putShort(insnsStart + pad * 2, 0x0000.toShort())
                                            }
                                            nanoTargetMethodIdx = methodIdx
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val canRedirectWall = sysCurTimeMethodIdx >= 0 && wallTargetMethodIdx >= 0
        val canRedirectNano = sysNanoTimeMethodIdx >= 0 && nanoTargetMethodIdx >= 0
        if (!canRedirectWall && !canRedirectNano) {
            return false
        }

        var redirectedAny = false
        for (i in 0 until classDefsSize) {
            val cDefOff = classDefsOff + i * 32
            val cIdx = buf.getInt(cDefOff)
            if (cIdx < 0 || cIdx >= typeIdsSize) continue
            val strIdx = buf.getInt(typeIdsOff + cIdx * 4)
            val classDesc = readDexStringAscii(out, buf, stringIdsOff, stringIdsSize, strIdx)
            if (!isRedirectableClockCallerClass(classDesc, originalPkgPrefix)) continue

            val classDataOff = buf.getInt(cDefOff + 24)
            if (classDataOff <= 0 || classDataOff >= out.size) continue
            var pos = classDataOff
            val (sfSize, p1) = readUleb128(out, pos); pos = p1
            val (ifSize, p2) = readUleb128(out, pos); pos = p2
            val (dmSize, p3) = readUleb128(out, pos); pos = p3
            val (vmSize, p4) = readUleb128(out, pos); pos = p4
            for (f in 0 until (sfSize + ifSize)) {
                val (_, fp1) = readUleb128(out, pos); pos = fp1
                val (_, fp2) = readUleb128(out, pos); pos = fp2
            }
            var curMethodIdx = 0
            for (m in 0 until (dmSize + vmSize)) {
                if (m == dmSize) curMethodIdx = 0
                val (mDiff, mp1) = readUleb128(out, pos); pos = mp1
                curMethodIdx += mDiff
                val (_, mp2) = readUleb128(out, pos); pos = mp2
                val (codeOff, mp3) = readUleb128(out, pos); pos = mp3
                if (curMethodIdx == wallTargetMethodIdx || curMethodIdx == nanoTargetMethodIdx) {
                    continue
                }
                if (codeOff > 0 && codeOff + 16 <= out.size) {
                    val insnsSize = buf.getInt(codeOff + 12)
                    val insnsStart = codeOff + 16
                    if (insnsSize > 0 && insnsStart + insnsSize * 2 <= out.size) {
                        var w = 0
                        while (w < insnsSize) {
                            val w0 = buf.getShort(insnsStart + w * 2).toInt() and 0xFFFF
                            val insnWidth = getDalvikInsnWidthWords(w0)
                            if (insnWidth <= 0 || w + insnWidth > insnsSize) {
                                break
                            }
                            if (insnWidth == 3) {
                                val w2 = buf.getShort(insnsStart + (w + 2) * 2).toInt() and 0xFFFF
                                if (w0 == 0x0071 && w2 == 0x0000) {
                                    val mRef = buf.getShort(insnsStart + (w + 1) * 2).toInt() and 0xFFFF
                                    if (canRedirectWall && mRef == sysCurTimeMethodIdx) {
                                        buf.putShort(insnsStart + (w + 1) * 2, wallTargetMethodIdx.toShort())
                                        redirectedAny = true
                                    } else if (canRedirectNano && mRef == sysNanoTimeMethodIdx) {
                                        buf.putShort(insnsStart + (w + 1) * 2, nanoTargetMethodIdx.toShort())
                                        redirectedAny = true
                                    }
                                }
                            }
                            w += insnWidth
                        }
                    }
                }
            }
        }
        return redirectedAny
    }


    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, startIndex: Int = 0): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val limit = haystack.size - needle.size
        outer@ for (i in startIndex..limit) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun loadSigningCredentials(): Pair<PrivateKey, X509Certificate> {
        val keyBytes = Base64.decode(SIGNER_PRIVATE_KEY_B64, Base64.DEFAULT)
        val certBytes = Base64.decode(SIGNER_CERT_B64, Base64.DEFAULT)
        val kf = KeyFactory.getInstance("RSA")
        val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        return Pair(privateKey, cert)
    }

    /**
     * Streams APK Signature Scheme v1 (MANIFEST.MF, CERT.SF, CERT.RSA) directly into `zipOut`
     * without allocating large intermediate ByteArrays or duplicate Maps when signing 40,000+ entry games.
     */
    private fun writeStreamingV1SignatureEntries(
        zipOut: ZipOutputStream,
        entryDigestsB64: Map<String, String>
    ) {
        val (privateKey, cert) = loadSigningCredentials()
        val manifestFullSha256 = MessageDigest.getInstance("SHA-256")
        val sectionSha256 = MessageDigest.getInstance("SHA-256")

        // 1. Stream META-INF/MANIFEST.MF directly to zipOut while computing overall MANIFEST.MF SHA-256
        val mfEntry = ZipEntry("META-INF/MANIFEST.MF").apply { method = ZipEntry.DEFLATED }
        zipOut.putNextEntry(mfEntry)
        val mfHeader = "Manifest-Version: 1.0\r\nCreated-By: 1.0 (GhostDevice Standalone Builder)\r\n\r\n"
            .toByteArray(StandardCharsets.UTF_8)
        zipOut.write(mfHeader)
        manifestFullSha256.update(mfHeader)

        for ((name, digestB64) in entryDigestsB64) {
            if (name.startsWith("META-INF/")) continue
            val secBytes = "Name: $name\r\nSHA-256-Digest: $digestB64\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
            zipOut.write(secBytes)
            manifestFullSha256.update(secBytes)
        }
        zipOut.closeEntry()

        val manifestDigestB64 = Base64.encodeToString(manifestFullSha256.digest(), Base64.NO_WRAP)

        // 2. Stream META-INF/CERT.SF directly to zipOut while updating RSA Signature on the fly
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)

        val sfEntry = ZipEntry("META-INF/CERT.SF").apply { method = ZipEntry.DEFLATED }
        zipOut.putNextEntry(sfEntry)
        val sfHeader = "Signature-Version: 1.0\r\nCreated-By: 1.0 (GhostDevice Standalone Builder)\r\nSHA-256-Digest-Manifest: $manifestDigestB64\r\nX-Android-APK-Signed: 2\r\n\r\n"
            .toByteArray(StandardCharsets.UTF_8)
        zipOut.write(sfHeader)
        sig.update(sfHeader)

        for ((name, digestB64) in entryDigestsB64) {
            if (name.startsWith("META-INF/")) continue
            val mfSecBytes = "Name: $name\r\nSHA-256-Digest: $digestB64\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
            sectionSha256.reset()
            val secDigestB64 = Base64.encodeToString(sectionSha256.digest(mfSecBytes), Base64.NO_WRAP)
            val sfSecBytes = "Name: $name\r\nSHA-256-Digest: $secDigestB64\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
            zipOut.write(sfSecBytes)
            sig.update(sfSecBytes)
        }
        zipOut.closeEntry()

        // 3. Write META-INF/CERT.RSA
        val rsaSigBytes = sig.sign()
        val pkcs7Bytes = buildPkcs7SignedDataDer(cert.encoded, rsaSigBytes)
        val rsaEntry = ZipEntry("META-INF/CERT.RSA").apply { method = ZipEntry.DEFLATED }
        zipOut.putNextEntry(rsaEntry)
        zipOut.write(pkcs7Bytes)
        zipOut.closeEntry()
    }

    /**
     * Zero-OOM Streaming APK Signature Scheme v2 Signer ("APK Sig Block 42", ID 0x7109871a):
     * Supports true IN-PLACE signing when `unsignedZipFile == signedApkOutFile` so even 2 GB+
     * games never require a second 2 GB copy on disk (only a tiny temporary file for the Central Directory).
     */
    internal fun signApkV1AndV2(
        unsignedZipFile: File,
        signedApkOutFile: File,
        onProgressPercent: (Int) -> Unit = {}
    ) {
        val isInPlace = unsignedZipFile.absolutePath == signedApkOutFile.absolutePath
        RandomAccessFile(unsignedZipFile, if (isInPlace) "rw" else "r").use { raf ->
            val fileLen = raf.length()
            val minEocdSize = 22L
            if (fileLen < minEocdSize) {
                if (!isInPlace) unsignedZipFile.copyTo(signedApkOutFile, overwrite = true)
                return
            }

            val tailLen = minOf(fileLen, 65557L).toInt()
            val tailStart = fileLen - tailLen.toLong()
            val tailBytes = ByteArray(tailLen)
            raf.seek(tailStart)
            raf.readFully(tailBytes)

            val relEocd = findZipEocdOffset(tailBytes)
            if (relEocd < 0) {
                if (!isInPlace) unsignedZipFile.copyTo(signedApkOutFile, overwrite = true)
                return
            }

            val eocdOffset = tailStart + relEocd.toLong()
            val eocdSectionBytes = tailBytes.copyOfRange(relEocd, tailLen)
            val eocdBuf = ByteBuffer.wrap(eocdSectionBytes).order(ByteOrder.LITTLE_ENDIAN)
            val cdSize = eocdBuf.getInt(12).toLong() and 0xFFFFFFFFL
            val cdOffset = eocdBuf.getInt(16).toLong() and 0xFFFFFFFFL
            if (cdOffset <= 0L || cdOffset + cdSize > eocdOffset) {
                if (!isInPlace) unsignedZipFile.copyTo(signedApkOutFile, overwrite = true)
                return
            }

            // Ensure EOCD centralDirectoryOffset matches cdOffset for V2 digest calculation
            eocdBuf.putInt(16, cdOffset.toInt())

            onProgressPercent(85)
            // Compute 1 MB chunked SHA-256 digest via streaming RandomAccessFile reads
            val topLevelDigest = computeApkV2ContentDigestStreaming(
                raf = raf,
                cdOffset = cdOffset,
                eocdOffset = eocdOffset,
                eocdBytes = eocdSectionBytes
            )
            onProgressPercent(90)
            val (privateKey, cert) = loadSigningCredentials()

            val apkSigningBlock = buildApkSigningBlockV2(
                contentDigest = topLevelDigest,
                certDer = cert.encoded,
                privateKey = privateKey
            )

            // Update EOCD centralDirectoryOffset to cdOffset + apkSigningBlock.size
            val newCdOffset = cdOffset + apkSigningBlock.size.toLong()
            eocdBuf.putInt(16, newCdOffset.toInt())

            val ioBuf = ByteArray(65536)
            val cdTotalBytes = eocdOffset - cdOffset

            if (isInPlace) {
                // True In-Place V2 Insertion: save Central Directory (~1-3 MB) to a small temp file,
                // write apkSigningBlock at cdOffset, stream Central Directory after it, and write updated EOCD.
                val cdTmpFile = File(
                    unsignedZipFile.parentFile,
                    "${unsignedZipFile.name}.cd_${System.nanoTime()}.tmp"
                )
                try {
                    BufferedOutputStream(FileOutputStream(cdTmpFile), 65536).use { cdOut ->
                        raf.seek(cdOffset)
                        var remCd = cdTotalBytes
                        while (remCd > 0L) {
                            val toRead = minOf(ioBuf.size.toLong(), remCd).toInt()
                            val r = raf.read(ioBuf, 0, toRead)
                            if (r <= 0) break
                            cdOut.write(ioBuf, 0, r)
                            remCd -= r
                        }
                        cdOut.flush()
                    }

                    onProgressPercent(93)
                    raf.seek(cdOffset)
                    raf.write(apkSigningBlock)

                    BufferedInputStream(FileInputStream(cdTmpFile), 65536).use { cdIn ->
                        var r: Int
                        while (cdIn.read(ioBuf).also { r = it } != -1) {
                            raf.write(ioBuf, 0, r)
                        }
                    }

                    raf.write(eocdSectionBytes)
                    raf.setLength(newCdOffset + cdTotalBytes + eocdSectionBytes.size.toLong())
                    onProgressPercent(95)
                } finally {
                    try { cdTmpFile.delete() } catch (_: Throwable) {}
                }
            } else {
                BufferedOutputStream(FileOutputStream(signedApkOutFile), 65536).use { out ->
                    // 1. Stream Section 1: [0 .. cdOffset)
                    raf.seek(0L)
                    var rem1 = cdOffset
                    while (rem1 > 0L) {
                        val toRead = minOf(ioBuf.size.toLong(), rem1).toInt()
                        val r = raf.read(ioBuf, 0, toRead)
                        if (r <= 0) break
                        out.write(ioBuf, 0, r)
                        rem1 -= r
                    }

                    // 2. Write APK Signing Block v2
                    out.write(apkSigningBlock)

                    // 3. Stream Section 3 (Central Directory): [cdOffset .. eocdOffset)
                    raf.seek(cdOffset)
                    var rem3 = cdTotalBytes
                    while (rem3 > 0L) {
                        val toRead = minOf(ioBuf.size.toLong(), rem3).toInt()
                        val r = raf.read(ioBuf, 0, toRead)
                        if (r <= 0) break
                        out.write(ioBuf, 0, r)
                        rem3 -= r
                    }

                    // 4. Write Section 4 (Updated EOCD)
                    out.write(eocdSectionBytes)
                    out.flush()
                }
            }
        }
    }

    private fun findZipEocdOffset(bytes: ByteArray): Int {
        val minEocdSize = 22
        if (bytes.size < minEocdSize) return -1
        val maxComment = minOf(65535, bytes.size - minEocdSize)
        val eocdPosBase = bytes.size - minEocdSize
        for (commentLen in 0..maxComment) {
            val pos = eocdPosBase - commentLen
            if (bytes[pos] == 0x50.toByte() &&
                bytes[pos + 1] == 0x4B.toByte() &&
                bytes[pos + 2] == 0x05.toByte() &&
                bytes[pos + 3] == 0x06.toByte()
            ) {
                return pos
            }
        }
        return -1
    }

    private fun computeApkV2ContentDigestStreaming(
        raf: RandomAccessFile,
        cdOffset: Long,
        eocdOffset: Long,
        eocdBytes: ByteArray
    ): ByteArray {
        val chunkSize = 1048576L // 1 MB
        val cdLen = eocdOffset - cdOffset
        val chunks1 = ((cdOffset + chunkSize - 1L) / chunkSize).toInt()
        val chunks3 = ((cdLen + chunkSize - 1L) / chunkSize).toInt()
        val chunks4 = ((eocdBytes.size.toLong() + chunkSize - 1L) / chunkSize).toInt()
        val totalChunks = chunks1 + chunks3 + chunks4

        val sha256 = MessageDigest.getInstance("SHA-256")
        val allChunkDigests = ByteArrayOutputStream(totalChunks * 32 + 5)
        allChunkDigests.write(0x5A)
        allChunkDigests.write(intToLeBytes(totalChunks))

        val chunkHeader = ByteArray(5)
        chunkHeader[0] = 0xA5.toByte()
        val ioBuf = ByteArray(65536)

        fun digestFileSection(startOffset: Long, sectionLength: Long) {
            raf.seek(startOffset)
            var remSection = sectionLength
            while (remSection > 0L) {
                val curChunkLen = minOf(chunkSize, remSection).toInt()
                val lenBytes = intToLeBytes(curChunkLen)
                System.arraycopy(lenBytes, 0, chunkHeader, 1, 4)
                sha256.reset()
                sha256.update(chunkHeader, 0, 5)

                var remChunk = curChunkLen
                while (remChunk > 0) {
                    val toRead = minOf(ioBuf.size, remChunk)
                    val r = raf.read(ioBuf, 0, toRead)
                    if (r <= 0) break
                    sha256.update(ioBuf, 0, r)
                    remChunk -= r
                }
                allChunkDigests.write(sha256.digest())
                remSection -= curChunkLen.toLong()
            }
        }

        // Section 1: [0 .. cdOffset)
        digestFileSection(0L, cdOffset)
        // Section 3: [cdOffset .. eocdOffset)
        digestFileSection(cdOffset, cdLen)

        // Section 4: EOCD bytes in memory
        var offset4 = 0
        while (offset4 < eocdBytes.size) {
            val len = minOf(1048576, eocdBytes.size - offset4)
            val lenBytes = intToLeBytes(len)
            System.arraycopy(lenBytes, 0, chunkHeader, 1, 4)
            sha256.reset()
            sha256.update(chunkHeader, 0, 5)
            sha256.update(eocdBytes, offset4, len)
            allChunkDigests.write(sha256.digest())
            offset4 += len
        }

        sha256.reset()
        return sha256.digest(allChunkDigests.toByteArray())
    }

    private fun buildApkSigningBlockV2(
        contentDigest: ByteArray,
        certDer: ByteArray,
        privateKey: PrivateKey
    ): ByteArray {
        // Signature Algorithm ID 0x0103 = RSA_PKCS1_V1_5_WITH_SHA256
        val sigAlgoId = 0x0103

        // 1. digest entry: uint32(algoId) + lengthPrefixed(contentDigest)
        val digestEntry = ByteBuffer.allocate(4 + 4 + contentDigest.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sigAlgoId)
            .putInt(contentDigest.size)
            .put(contentDigest)
            .array()
        val digestsSeq = lengthPrefixedBytes(lengthPrefixedBytes(digestEntry))

        // 2. certificates sequence
        val certsSeq = lengthPrefixedBytes(lengthPrefixedBytes(certDer))

        // 3. additional attributes (empty length-prefixed sequence = uint32(0), 4 bytes)
        val emptyAttrs = ByteArray(4)

        // Exact APK Signature Scheme v2 signedData format: digests + certificates + additionalAttributes
        val signedDataPayload = concatBytes(digestsSeq, certsSeq, emptyAttrs)
        val signedDataLP = lengthPrefixedBytes(signedDataPayload)

        // Sign signedDataPayload with SHA256withRSA
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(signedDataPayload)
        val signatureBytes = signer.sign()

        val sigEntry = ByteBuffer.allocate(4 + 4 + signatureBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sigAlgoId)
            .putInt(signatureBytes.size)
            .put(signatureBytes)
            .array()
        val signaturesSeq = lengthPrefixedBytes(lengthPrefixedBytes(sigEntry))

        val publicKeyBytes = privateKeyToPublicKeySpki(certDer)
        val pubKeyLP = lengthPrefixedBytes(publicKeyBytes)

        val singleSigner = lengthPrefixedBytes(concatBytes(signedDataLP, signaturesSeq, pubKeyLP))
        val signersList = lengthPrefixedBytes(singleSigner)

        // ID-Value pair for V2 block (ID = 0x7109871a)
        val pairPayloadSize = 4L + signersList.size.toLong()
        val v2Pair = ByteBuffer.allocate(8 + 4 + signersList.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(pairPayloadSize)
            .putInt(0x7109871a)
            .put(signersList)
            .array()

        // Pad to 4096-byte multiple using Android's VERITY_PADDING_BLOCK_ID (0x42726577)
        val magic = "APK Sig Block 42".toByteArray(StandardCharsets.UTF_8)
        val baseBlockSize = 8 + v2Pair.size + 8 + 16
        val rem = baseBlockSize % 4096
        val paddingPair = if (rem != 0) {
            val neededTotal = 4096 - rem
            if (neededTotal >= 12) {
                val padDataLen = neededTotal - 12
                ByteBuffer.allocate(neededTotal)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong((4 + padDataLen).toLong())
                    .putInt(0x42726577)
                    .put(ByteArray(padDataLen))
                    .array()
            } else {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }

        val allPairs = concatBytes(v2Pair, paddingPair)
        val sizeInHeaderAndFooter = (allPairs.size + 8 + 16).toLong()

        return ByteBuffer.allocate(8 + allPairs.size + 8 + 16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(sizeInHeaderAndFooter)
            .put(allPairs)
            .putLong(sizeInHeaderAndFooter)
            .put(magic)
            .array()
    }

    private fun privateKeyToPublicKeySpki(certDer: ByteArray): ByteArray {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        return cert.publicKey.encoded
    }

    private fun lengthPrefixedBytes(data: ByteArray): ByteArray {
        return ByteBuffer.allocate(4 + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(data.size)
            .put(data)
            .array()
    }

    private fun intToLeBytes(v: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    }

    private fun concatBytes(vararg arrays: ByteArray): ByteArray {
        var total = 0
        for (a in arrays) total += a.size
        val out = ByteArray(total)
        var pos = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, pos, a.size)
            pos += a.size
        }
        return out
    }

    /**
     * Builds a minimal valid PKCS#7 SignedData DER structure for META-INF/CERT.RSA
     */
    private fun buildPkcs7SignedDataDer(certDer: ByteArray, signatureBytes: ByteArray): ByteArray {
        val cf = CertificateFactory.getInstance("X.509")
        val x509 = cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        val issuerDer = x509.issuerX500Principal.encoded
        val serialBytes = x509.serialNumber.toByteArray()
        val serialDer = derTag(0x02, serialBytes)

        val version1 = derTag(0x02, byteArrayOf(0x01))
        val oidSha256 = byteArrayOf(0x06, 0x09, 0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01)
        val nullDer = byteArrayOf(0x05, 0x00)
        val digestAlgoSeq = derTag(0x30, concatBytes(oidSha256, nullDer))
        val digestAlgosSet = derTag(0x31, digestAlgoSeq)

        val oidData = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x01)
        val contentInfo = derTag(0x30, oidData)

        val certsImplicit = derTag(0xA0, certDer)

        val issuerAndSerial = derTag(0x30, concatBytes(issuerDer, serialDer))
        val oidRsa = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01)
        val encAlgoSeq = derTag(0x30, concatBytes(oidRsa, nullDer))
        val encryptedDigest = derTag(0x04, signatureBytes)
        val signerInfo = derTag(0x30, concatBytes(version1, issuerAndSerial, digestAlgoSeq, encAlgoSeq, encryptedDigest))
        val signerInfosSet = derTag(0x31, signerInfo)

        val signedData = derTag(0x30, concatBytes(version1, digestAlgosSet, contentInfo, certsImplicit, signerInfosSet))
        val oidSignedData = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02)
        return derTag(0x30, concatBytes(oidSignedData, derTag(0xA0, signedData)))
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val lenBytes = when {
            len < 128 -> byteArrayOf(len.toByte())
            len < 256 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 65536 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
            else -> byteArrayOf(
                0x83.toByte(),
                (len shr 16).toByte(),
                ((len shr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
        }
        val out = ByteArray(1 + lenBytes.size + len)
        out[0] = tag.toByte()
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.size)
        System.arraycopy(content, 0, out, 1 + lenBytes.size, len)
        return out
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten += 1L
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            bytesWritten += b.size
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}

/**
 * Binary AndroidManifest.xml (AXML / ResXMLTree) Patcher:
 * - Preserves strict ResXMLResourceMap invariant (inserts missing attribute resource IDs at
 *   `origResCount = resIds.size` without zero-padding and remaps string indices >= origResCount).
 * - Completely removes Split APK / App Bundle enforcement attributes (`isSplitRequired`,
 *   `requiredSplitTypes`, `splitTypes`) and strips `<meta-data android:name="com.android.vending.splits*"/>`
 *   elements so `ApkLiteParseUtils` and `ParsingPackageUtils` treat the APK as a clean monolithic APK.
 * - Expands relative component names (`.MainActivity` -> `<origPkg>.MainActivity`).
 * - Updates manifest package, all `<provider>` authorities (including `@string/` references),
 *   and custom `<permission>` declarations to `<origPkg>.phantom` to prevent conflict with the original app.
 * - Appends `<provider android:name="com.example.standalone.StandaloneBootstrapProvider" ... />`
 *   right before `</application>`.
 */
object AxmlManifestPatcher {

    private const val ATTR_NAME_RES_ID = 0x01010003
    private const val ATTR_MANAGE_SPACE_ACTIVITY_RES_ID = 0x01010004
    private const val ATTR_PERMISSION_RES_ID = 0x01010006
    private const val ATTR_READ_PERMISSION_RES_ID = 0x01010007
    private const val ATTR_WRITE_PERMISSION_RES_ID = 0x01010008
    private const val ATTR_PROTECTION_LEVEL_RES_ID = 0x01010009
    private const val ATTR_SHARED_USER_ID_RES_ID = 0x0101000B
    private const val ATTR_PERSISTENT_RES_ID = 0x0101000D
    private const val ATTR_EXPORTED_RES_ID = 0x01010010
    private const val ATTR_PROCESS_RES_ID = 0x01010011
    private const val ATTR_TASK_AFFINITY_RES_ID = 0x01010012
    private const val ATTR_AUTHORITIES_RES_ID = 0x01010018
    private const val ATTR_TARGET_ACTIVITY_RES_ID = 0x01010202
    private const val ATTR_BACKUP_AGENT_RES_ID = 0x0101027F
    private const val ATTR_ALLOW_BACKUP_RES_ID = 0x01010280
    private const val ATTR_REQUIRED_RES_ID = 0x0101028E
    private const val ATTR_PARENT_ACTIVITY_NAME_RES_ID = 0x010103A7
    private const val ATTR_INIT_ORDER_RES_ID = 0x01010427
    private const val ATTR_EXTRACT_NATIVE_LIBS_RES_ID = 0x010104EA
    private const val ATTR_FULL_BACKUP_CONTENT_RES_ID = 0x010104EB
    private const val ATTR_DEFAULT_TO_DEVICE_PROTECTED_STORAGE_RES_ID = 0x01010504
    private const val ATTR_SPLIT_NAME_RES_ID = 0x01010549
    private const val ATTR_ISOLATED_SPLITS_RES_ID = 0x0101054B
    private const val ATTR_APP_COMPONENT_FACTORY_RES_ID = 0x0101057A
    private const val ATTR_IS_SPLIT_REQUIRED_RES_ID = 0x01010591
    private const val ATTR_USE_EMBEDDED_DEX_RES_ID = 0x01010598
    private const val ATTR_ZYGOTE_PRELOAD_NAME_RES_ID = 0x0101059E
    private const val ATTR_DATA_EXTRACTION_RULES_RES_ID = 0x0101063E
    private const val ATTR_SHARED_USER_MAX_SDK_RES_ID = 0x01010646
    private const val ATTR_REQUIRED_SPLIT_TYPES_RES_ID = 0x0101064E
    private const val ATTR_SPLIT_TYPES_RES_ID = 0x0101064F

    fun patchManifest(
        axmlBytes: ByteArray,
        originalPkg: String,
        standalonePkg: String,
        resolveStringResource: ((Int) -> String?)? = null,
        injectBootstrapAndPermissions: Boolean = true,
        overrideAppComponentFactory: String? = null,
        onOriginalAppComponentFactory: ((String) -> Unit)? = null
    ): ByteArray {
        val buf = ByteBuffer.wrap(axmlBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (axmlBytes.size < 36) return axmlBytes
        val fileType = buf.getShort(0).toInt() and 0xFFFF
        val fileHeaderSize = buf.getShort(2).toInt() and 0xFFFF
        if (fileType != 0x0003 || fileHeaderSize != 8) return axmlBytes

        // 1. Parse ResStringPool at offset 8
        val spOffset = 8
        val spType = buf.getShort(spOffset).toInt() and 0xFFFF
        val spHeaderSize = buf.getShort(spOffset + 2).toInt() and 0xFFFF
        val spChunkSize = buf.getInt(spOffset + 4)
        if (spType != 0x0001) return axmlBytes

        val stringCount = buf.getInt(spOffset + 8)
        val spFlags = buf.getInt(spOffset + 16)
        val stringsStart = buf.getInt(spOffset + 20)
        val isUtf8 = (spFlags and 0x0100) != 0

        val strings = ArrayList<String>(stringCount + 32)
        for (i in 0 until stringCount) {
            val strRelOff = buf.getInt(spOffset + spHeaderSize + i * 4)
            val absOff = spOffset + stringsStart + strRelOff
            strings.add(if (isUtf8) readUtf8String(axmlBytes, absOff) else readUtf16String(buf, absOff))
        }

        // 2. Parse ResXMLResourceMap (if present) right after ResStringPool
        var nextChunkOffset = spOffset + spChunkSize
        val resIds = ArrayList<Int>()
        if (nextChunkOffset + 8 <= axmlBytes.size) {
            val rmType = buf.getShort(nextChunkOffset).toInt() and 0xFFFF
            val rmHeaderSize = buf.getShort(nextChunkOffset + 2).toInt() and 0xFFFF
            val rmChunkSize = buf.getInt(nextChunkOffset + 4)
            if (rmType == 0x0180) {
                val count = (rmChunkSize - rmHeaderSize) / 4
                for (i in 0 until count) {
                    resIds.add(buf.getInt(nextChunkOffset + rmHeaderSize + i * 4))
                }
                nextChunkOffset += rmChunkSize
            }
        }

        // 3. Insert any missing attribute resource IDs at origResCount (end of resIds) without zero-padding!
        val origResCount = resIds.size
        var insertedAttrCount = 0

        fun ensureAttrResId(attrName: String, attrResId: Int): Int {
            for (i in resIds.indices) {
                if (resIds[i] == attrResId) return i
            }
            val insertIdx = resIds.size
            strings.add(insertIdx, attrName)
            resIds.add(insertIdx, attrResId)
            insertedAttrCount++
            return insertIdx
        }

        val idxAttrName = ensureAttrResId("name", ATTR_NAME_RES_ID)
        val idxAttrRequired = if (injectBootstrapAndPermissions) ensureAttrResId("required", ATTR_REQUIRED_RES_ID) else -1
        val idxAttrExported = if (injectBootstrapAndPermissions) ensureAttrResId("exported", ATTR_EXPORTED_RES_ID) else -1
        val idxAttrAuthorities = if (injectBootstrapAndPermissions) ensureAttrResId("authorities", ATTR_AUTHORITIES_RES_ID) else -1
        val idxAttrAllowBackup = if (injectBootstrapAndPermissions) ensureAttrResId("allowBackup", ATTR_ALLOW_BACKUP_RES_ID) else -1
        val idxAttrInitOrder = if (injectBootstrapAndPermissions) ensureAttrResId("initOrder", ATTR_INIT_ORDER_RES_ID) else -1
        val idxAttrExtractNativeLibs = if (injectBootstrapAndPermissions) ensureAttrResId("extractNativeLibs", ATTR_EXTRACT_NATIVE_LIBS_RES_ID) else -1
        val idxAttrAppComponentFactory = if (injectBootstrapAndPermissions && !overrideAppComponentFactory.isNullOrBlank()) {
            ensureAttrResId("appComponentFactory", ATTR_APP_COMPONENT_FACTORY_RES_ID)
        } else -1

        fun findOrAddPlainString(value: String): Int {
            for (i in resIds.size until strings.size) {
                if (strings[i] == value) return i
            }
            val newIdx = strings.size
            strings.add(value)
            return newIdx
        }

        // Find android namespace URI string index ("http://schemas.android.com/apk/res/android")
        var idxAndroidNs = -1
        for (i in strings.indices) {
            if (strings[i] == "http://schemas.android.com/apk/res/android") {
                idxAndroidNs = i
                break
            }
        }
        if (idxAndroidNs < 0) {
            idxAndroidNs = findOrAddPlainString("http://schemas.android.com/apk/res/android")
        }

        val idxStandalonePkg = findOrAddPlainString(standalonePkg)
        val idxProviderTag = if (injectBootstrapAndPermissions) findOrAddPlainString("provider") else -1
        val idxProviderClass = if (injectBootstrapAndPermissions) findOrAddPlainString("com.example.standalone.StandaloneBootstrapProvider") else -1
        val idxOverrideAppCompFactory = if (injectBootstrapAndPermissions && !overrideAppComponentFactory.isNullOrBlank()) {
            findOrAddPlainString(overrideAppComponentFactory)
        } else -1
        val idxProviderAuth = if (injectBootstrapAndPermissions) findOrAddPlainString("$standalonePkg.phantom_standalone_init") else -1
        val idxUsesPermissionTag = if (injectBootstrapAndPermissions) findOrAddPlainString("uses-permission") else -1
        val idxPermInternet = if (injectBootstrapAndPermissions) findOrAddPlainString("android.permission.INTERNET") else -1
        val idxPermNetworkState = if (injectBootstrapAndPermissions) findOrAddPlainString("android.permission.ACCESS_NETWORK_STATE") else -1
        val idxPermFineLoc = if (injectBootstrapAndPermissions) findOrAddPlainString("android.permission.ACCESS_FINE_LOCATION") else -1
        val idxPermCoarseLoc = if (injectBootstrapAndPermissions) findOrAddPlainString("android.permission.ACCESS_COARSE_LOCATION") else -1
        val idxPermQueryAll = if (injectBootstrapAndPermissions) findOrAddPlainString("android.permission.QUERY_ALL_PACKAGES") else -1

        val componentTags = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")
        val permissionTags = setOf("permission", "permission-group", "permission-tree")
        val isSamePackageReplace = (standalonePkg == originalPkg)
        val uniquePkgSuffix = if (isSamePackageReplace) "" else (standalonePkg.removePrefix(originalPkg).takeIf { it.startsWith(".") } ?: ".ghostdevice")
        val strippedMetaDataPrefixes = listOf(
            "com.android.vending.splits",
            "com.android.vending.derived.apk.id",
            "com.android.stamp."
        )

        // 4. Pre-scan all custom <permission android:name="..."> declarations in the manifest so we can
        // rewrite matching <uses-permission> and component android:permission/readPermission/writePermission
        // attributes consistently even when the permission name doesn't start with originalPkg (e.g. Meta/Instagram).
        val declaredCustomPermissions = HashSet<String>()
        var scanPos = nextChunkOffset
        while (scanPos + 8 <= axmlBytes.size) {
            val cType = buf.getShort(scanPos).toInt() and 0xFFFF
            val cSize = buf.getInt(scanPos + 4)
            if (cSize <= 0 || scanPos + cSize > axmlBytes.size) break
            if (cType == 0x0102 && cSize >= 36) {
                var eNameIdx = buf.getInt(scanPos + 20)
                if (eNameIdx >= origResCount) eNameIdx += insertedAttrCount
                val eName = strings.getOrNull(eNameIdx) ?: ""
                if (eName in permissionTags) {
                    val aStart = buf.getShort(scanPos + 24).toInt() and 0xFFFF
                    val aSize = buf.getShort(scanPos + 26).toInt() and 0xFFFF
                    val aCount = buf.getShort(scanPos + 28).toInt() and 0xFFFF
                    for (a in 0 until aCount) {
                        val aOff = scanPos + 16 + aStart + a * aSize
                        if (aOff + 20 > scanPos + cSize) break
                        var aNameIdx = buf.getInt(aOff + 4)
                        if (aNameIdx >= origResCount) aNameIdx += insertedAttrCount
                        var aRawIdx = buf.getInt(aOff + 8)
                        if (aRawIdx >= origResCount) aRawIdx += insertedAttrCount
                        val aDType = buf.get(aOff + 15).toInt() and 0xFF
                        var aDVal = buf.getInt(aOff + 16)
                        if (aDType == 0x03 && aDVal >= origResCount) aDVal += insertedAttrCount
                        val aResId = resIds.getOrNull(aNameIdx) ?: 0
                        val aNameStr = strings.getOrNull(aNameIdx) ?: ""
                        if (aResId == ATTR_NAME_RES_ID || aNameStr == "name") {
                            val pVal = if (aDType == 0x03) strings.getOrNull(aDVal) else strings.getOrNull(aRawIdx)
                            if (!pVal.isNullOrBlank()) {
                                declaredCustomPermissions.add(pVal)
                            }
                        }
                    }
                }
            }
            scanPos += cSize
        }

        fun rewriteCustomPermissionName(perm: String): String = when {
            isSamePackageReplace -> perm
            perm.contains(standalonePkg) -> perm
            perm.contains(originalPkg) -> perm.replace(originalPkg, standalonePkg)
            else -> "$perm$uniquePkgSuffix"
        }

        // 5. Walk XML chunks, remap old string indices >= origResCount, patch/strip attributes, and inject <provider>
        val xmlBodyOut = ByteArrayOutputStream(axmlBytes.size - nextChunkOffset + 512)
        var pos = nextChunkOffset
        var providerInjected = false
        var permissionsInjected = false
        val existingPermissions = HashSet<String>()
        var dynamicProviderCounter = 0
        var skipElementDepth = 0

        while (pos + 8 <= axmlBytes.size) {
            val chunkType = buf.getShort(pos).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > axmlBytes.size) break

            if (skipElementDepth > 0) {
                if (chunkType == 0x0102) {
                    skipElementDepth++
                } else if (chunkType == 0x0103) {
                    skipElementDepth--
                }
                pos += chunkSize
                continue
            }

            var chunkBytes = axmlBytes.copyOfRange(pos, pos + chunkSize)
            val cBuf = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

            if (insertedAttrCount > 0) {
                remapXmlChunkStringIndices(cBuf, chunkType, chunkBytes.size, origResCount, insertedAttrCount)
            }

            var injectProviderAfterThisChunk = false

            if (chunkType == 0x0102 && chunkSize >= 36) {
                // RES_XML_START_ELEMENT_TYPE
                val elemNameIdx = cBuf.getInt(20)
                val elemName = strings.getOrNull(elemNameIdx) ?: ""
                val attrStart = cBuf.getShort(24).toInt() and 0xFFFF
                val attrSize = cBuf.getShort(26).toInt() and 0xFFFF
                val attrCount = cBuf.getShort(28).toInt() and 0xFFFF

                // Strip <uses-static-library> and <uses-split> because we bundle static library & split
                // DEX/native/resources directly into the standalone monolithic APK!
                if (elemName == "uses-static-library" || elemName == "uses-split") {
                    skipElementDepth = 1
                    pos += chunkSize
                    continue
                }

                // Pre-scan android:name on <meta-data> or PairIP licensecheck components
                var elemAttrNameVal = ""
                if (elemName == "meta-data" || elemName in componentTags) {
                    for (a in 0 until attrCount) {
                        val aOff = 16 + attrStart + a * attrSize
                        if (aOff + 20 > chunkBytes.size) break
                        val aNameIdx = cBuf.getInt(aOff + 4)
                        val aDataType = cBuf.get(aOff + 15).toInt() and 0xFF
                        val aData = cBuf.getInt(aOff + 16)
                        val aResId = resIds.getOrNull(aNameIdx) ?: 0
                        if ((aResId == ATTR_NAME_RES_ID || strings.getOrNull(aNameIdx) == "name") && aDataType == 0x03) {
                            elemAttrNameVal = strings.getOrNull(aData) ?: ""
                        }
                    }
                    if (elemName == "meta-data" && strippedMetaDataPrefixes.any { elemAttrNameVal.startsWith(it) }) {
                        skipElementDepth = 1
                        pos += chunkSize
                        continue
                    }
                    if (elemName == "activity" &&
                        elemAttrNameVal == "com.google.android.play.core.missingsplits.PlayCoreMissingSplitsActivity"
                    ) {
                        skipElementDepth = 1
                        pos += chunkSize
                        continue
                    }
                }

                val indicesToRemove = HashSet<Int>()
                var hasExtractNativeLibs = false
                var hasAllowBackup = false
                var hasRequiredAttr = false
                var hasAppComponentFactory = false

                for (a in 0 until attrCount) {
                    val aOff = 16 + attrStart + a * attrSize
                    if (aOff + 20 > chunkBytes.size) break
                    val aNameIdx = cBuf.getInt(aOff + 4)
                    val aRawValIdx = cBuf.getInt(aOff + 8)
                    val aDataType = cBuf.get(aOff + 15).toInt() and 0xFF
                    val aData = cBuf.getInt(aOff + 16)

                    val aName = strings.getOrNull(aNameIdx) ?: ""
                    val aResId = resIds.getOrNull(aNameIdx) ?: 0
                    val strVal = if (aDataType == 0x03) strings.getOrNull(aData) else strings.getOrNull(aRawValIdx)

                    // 1) Update <manifest package="..."> and remove split / isolatedSplits / sharedUserId / coreApp / persistent / backup rules attributes cleanly
                    if (elemName == "manifest" && aName == "package") {
                        if (aRawValIdx >= 0) cBuf.putInt(aOff + 8, idxStandalonePkg)
                        if (aDataType == 0x03) cBuf.putInt(aOff + 16, idxStandalonePkg)
                    } else if ((elemName == "manifest" && aName == "coreApp") ||
                        (elemName == "application" && (aResId == ATTR_PERSISTENT_RES_ID || aName == "persistent")) ||
                        aResId == ATTR_SPLIT_NAME_RES_ID || aName == "splitName" ||
                        aResId == ATTR_ISOLATED_SPLITS_RES_ID || aName == "isolatedSplits" ||
                        aResId == ATTR_IS_SPLIT_REQUIRED_RES_ID || aName == "isSplitRequired" ||
                        aResId == ATTR_USE_EMBEDDED_DEX_RES_ID || aName == "useEmbeddedDex" ||
                        aResId == ATTR_ZYGOTE_PRELOAD_NAME_RES_ID || aName == "zygotePreloadName" ||
                        aResId == ATTR_REQUIRED_SPLIT_TYPES_RES_ID || aName == "requiredSplitTypes" ||
                        aResId == ATTR_SPLIT_TYPES_RES_ID || aName == "splitTypes" ||
                        aResId == ATTR_SHARED_USER_ID_RES_ID || aName == "sharedUserId" ||
                        aResId == ATTR_SHARED_USER_MAX_SDK_RES_ID || aName == "sharedUserMaxSdkVersion" ||
                        aResId == ATTR_FULL_BACKUP_CONTENT_RES_ID || aName == "fullBackupContent" ||
                        aResId == ATTR_DATA_EXTRACTION_RULES_RES_ID || aName == "dataExtractionRules" ||
                        aResId == ATTR_DEFAULT_TO_DEVICE_PROTECTED_STORAGE_RES_ID || aName == "defaultToDeviceProtectedStorage"
                    ) {
                        indicesToRemove.add(a)
                    } else if (injectBootstrapAndPermissions &&
                        (elemName == "uses-library" || elemName == "uses-native-library") &&
                        (aResId == ATTR_REQUIRED_RES_ID || aName == "required")
                    ) {
                        // Force android:required="false" on system/vendor shared libraries so built APKs install on ANY device
                        hasRequiredAttr = true
                        cBuf.putInt(aOff + 8, -1)
                        cBuf.put(aOff + 15, 0x12.toByte())
                        cBuf.putInt(aOff + 16, 0)
                    } else if (injectBootstrapAndPermissions && elemName == "application" && (aResId == ATTR_ALLOW_BACKUP_RES_ID || aName == "allowBackup")) {
                        // Force android:allowBackup="false" so Android Auto-Backup NEVER restores host accounts onto a new install!
                        hasAllowBackup = true
                        cBuf.putInt(aOff + 8, -1)
                        cBuf.put(aOff + 15, 0x12.toByte())
                        cBuf.putInt(aOff + 16, 0)
                    } else if (injectBootstrapAndPermissions && (aResId == ATTR_EXTRACT_NATIVE_LIBS_RES_ID || aName == "extractNativeLibs")) {
                        // Force android:extractNativeLibs="true"
                        hasExtractNativeLibs = true
                        cBuf.putInt(aOff + 8, -1)
                        cBuf.put(aOff + 15, 0x12.toByte())
                        cBuf.putInt(aOff + 16, -1)
                    } else if (elemName == "provider" && (aResId == ATTR_INIT_ORDER_RES_ID || aName == "initOrder")) {
                        // Clamp any existing provider initOrder to <= 2147483600 so StandaloneBootstrapProvider (2147483647) ALWAYS runs first!
                        if (aData > 2147483600) {
                            cBuf.putInt(aOff + 8, -1)
                            cBuf.put(aOff + 15, 0x10.toByte())
                            cBuf.putInt(aOff + 16, 2147483600)
                        }
                    } else if (elemName in componentTags &&
                        (aResId == ATTR_NAME_RES_ID ||
                            aResId == ATTR_APP_COMPONENT_FACTORY_RES_ID ||
                            aResId == ATTR_TARGET_ACTIVITY_RES_ID ||
                            aResId == ATTR_PARENT_ACTIVITY_NAME_RES_ID ||
                            aResId == ATTR_BACKUP_AGENT_RES_ID ||
                            aResId == ATTR_MANAGE_SPACE_ACTIVITY_RES_ID ||
                            aName == "name" || aName == "appComponentFactory" ||
                            aName == "targetActivity" || aName == "parentActivityName" ||
                            aName == "backupAgent" || aName == "manageSpaceActivity") &&
                        !strVal.isNullOrBlank()
                    ) {
                        // 2) Expand relative component class names (".MainActivity" -> "<origPkg>.MainActivity")
                        // Keep com.pairip.application.Application intact so VMRunner initializes the real app's Application
                        val fullClassName = when {
                            strVal.startsWith(".") -> "$originalPkg$strVal"
                            !strVal.contains(".") -> "$originalPkg.$strVal"
                            else -> null
                        }
                        val resolvedClassName = fullClassName ?: strVal
                        if (elemName == "application" &&
                            (aResId == ATTR_APP_COMPONENT_FACTORY_RES_ID || aName == "appComponentFactory") &&
                            idxOverrideAppCompFactory >= 0
                        ) {
                            hasAppComponentFactory = true
                            onOriginalAppComponentFactory?.invoke(resolvedClassName)
                            cBuf.putInt(aOff + 8, idxOverrideAppCompFactory)
                            cBuf.put(aOff + 15, 0x03.toByte())
                            cBuf.putInt(aOff + 16, idxOverrideAppCompFactory)
                        } else if (fullClassName != null) {
                            if (aResId == ATTR_NAME_RES_ID || aName == "name") {
                                elemAttrNameVal = fullClassName
                            }
                            val fullIdx = findOrAddPlainString(fullClassName)
                            if (aRawValIdx >= 0) cBuf.putInt(aOff + 8, fullIdx)
                            if (aDataType == 0x03) cBuf.putInt(aOff + 16, fullIdx)
                        }
                    } else if (!isSamePackageReplace && elemName == "provider" && (aResId == ATTR_AUTHORITIES_RES_ID || aName == "authorities")) {
                        // 3) Rewrite ALL <provider android:authorities="..."> (including @string references resolved via AssetManager)
                        // so side-by-side install never conflicts while preserving exact expected authority names!
                        dynamicProviderCounter++
                        val effectiveAuthStr = if (!strVal.isNullOrBlank()) {
                            strVal
                        } else if (aDataType == 0x01 && aData != 0) {
                            resolveStringResource?.invoke(aData)
                        } else {
                            null
                        }
                        val newAuth = if (!effectiveAuthStr.isNullOrBlank()) {
                            effectiveAuthStr.split(";").joinToString(";") { token ->
                                val t = token.trim()
                                when {
                                    t.contains(standalonePkg) -> t
                                    t.contains(originalPkg) -> t.replace(originalPkg, standalonePkg)
                                    t.isNotEmpty() -> "$t$uniquePkgSuffix"
                                    else -> "$standalonePkg.provider.$dynamicProviderCounter"
                                }
                            }
                        } else {
                            // Fallback when @string/... resource could not be resolved: assign a SINGLE clean authority (no semicolons!)
                            val clsSimple = elemAttrNameVal.substringAfterLast('.').lowercase(Locale.US)
                            when {
                                elemAttrNameVal.contains("InitializationProvider", ignoreCase = true) ->
                                    "$standalonePkg.androidx-startup"
                                elemAttrNameVal.contains("FirebaseInitProvider", ignoreCase = true) ->
                                    "$standalonePkg.firebaseinitprovider"
                                elemAttrNameVal.contains("FileProvider", ignoreCase = true) ->
                                    "$standalonePkg.fileprovider"
                                elemAttrNameVal.contains("WorkManagerInitializer", ignoreCase = true) ->
                                    "$standalonePkg.workmanager-init"
                                elemAttrNameVal.contains("MobileAdsInitProvider", ignoreCase = true) ->
                                    "$standalonePkg.mobileadsinitprovider"
                                elemAttrNameVal.contains("PicassoProvider", ignoreCase = true) ->
                                    "$standalonePkg.com.squareup.picasso"
                                clsSimple.isNotEmpty() ->
                                    "$standalonePkg.$clsSimple.$dynamicProviderCounter"
                                else -> "$standalonePkg.provider.$dynamicProviderCounter"
                            }
                        }
                        val updatedIdx = findOrAddPlainString(newAuth)
                        cBuf.putInt(aOff + 8, updatedIdx)
                        cBuf.put(aOff + 15, 0x03.toByte())
                        cBuf.putInt(aOff + 16, updatedIdx)
                    } else if (!isSamePackageReplace && elemName in permissionTags && (aResId == ATTR_NAME_RES_ID || aName == "name") && !strVal.isNullOrBlank()) {
                        // 4) Scope custom <permission> definitions to standalonePkg to prevent INSTALL_FAILED_DUPLICATE_PERMISSION
                        val newPerm = rewriteCustomPermissionName(strVal)
                        val updatedIdx = findOrAddPlainString(newPerm)
                        if (aRawValIdx >= 0) cBuf.putInt(aOff + 8, updatedIdx)
                        if (aDataType == 0x03) cBuf.putInt(aOff + 16, updatedIdx)
                    } else if (!isSamePackageReplace && elemName == "permission" &&
                        (aResId == ATTR_PROTECTION_LEVEL_RES_ID || aName == "protectionLevel") &&
                        (aDataType == 0x10 || aDataType == 0x11)
                    ) {
                        // Strip privileged/system protectionLevel flags (0x10 / 0x03 signatureOrSystem) on cloned system app permissions
                        val baseLevel = aData and 0x0F
                        val sanitizedLevel = if (baseLevel == 0x03) 0x02 else baseLevel
                        if (sanitizedLevel != aData) {
                            cBuf.putInt(aOff + 16, sanitizedLevel)
                        }
                    } else if ((elemName == "uses-permission" || elemName == "uses-permission-sdk-23") &&
                        !strVal.isNullOrBlank()
                    ) {
                        existingPermissions.add(strVal)
                        if (!isSamePackageReplace && (strVal.contains(originalPkg) || strVal in declaredCustomPermissions)) {
                            val updatedVal = rewriteCustomPermissionName(strVal)
                            val updatedIdx = findOrAddPlainString(updatedVal)
                            if (aRawValIdx >= 0) cBuf.putInt(aOff + 8, updatedIdx)
                            if (aDataType == 0x03) cBuf.putInt(aOff + 16, updatedIdx)
                        }
                    } else if (!isSamePackageReplace && !strVal.isNullOrBlank() &&
                        (aResId == ATTR_PERMISSION_RES_ID ||
                            aResId == ATTR_READ_PERMISSION_RES_ID ||
                            aResId == ATTR_WRITE_PERMISSION_RES_ID ||
                            aName == "permission" || aName == "readPermission" || aName == "writePermission")
                    ) {
                        if (strVal.contains(originalPkg) || strVal in declaredCustomPermissions) {
                            val updatedVal = rewriteCustomPermissionName(strVal)
                            val updatedIdx = findOrAddPlainString(updatedVal)
                            if (aRawValIdx >= 0) cBuf.putInt(aOff + 8, updatedIdx)
                            if (aDataType == 0x03) cBuf.putInt(aOff + 16, updatedIdx)
                        }
                    } else if (!isSamePackageReplace && !strVal.isNullOrBlank() &&
                        (aResId == ATTR_PROCESS_RES_ID || aResId == ATTR_TASK_AFFINITY_RES_ID ||
                            aName == "process" || aName == "taskAffinity")
                    ) {
                        val updatedVal = when {
                            strVal.contains(standalonePkg) -> strVal
                            strVal.contains(originalPkg) -> strVal.replace(originalPkg, standalonePkg)
                            !strVal.startsWith(":") && (aResId == ATTR_PROCESS_RES_ID || aName == "process") ->
                                "$strVal$uniquePkgSuffix"
                            else -> null
                        }
                        if (updatedVal != null) {
                            val updatedIdx = findOrAddPlainString(updatedVal)
                            if (aRawValIdx >= 0) cBuf.putInt(aOff + 8, updatedIdx)
                            if (aDataType == 0x03) cBuf.putInt(aOff + 16, updatedIdx)
                        }
                    }
                }

                // Before <application> starts, inject INTERNET and LOCATION <uses-permission> tags if missing
                if (injectBootstrapAndPermissions && elemName == "application" && !permissionsInjected) {
                    permissionsInjected = true
                    val reqPerms = listOf(
                        "android.permission.INTERNET" to idxPermInternet,
                        "android.permission.ACCESS_NETWORK_STATE" to idxPermNetworkState,
                        "android.permission.ACCESS_FINE_LOCATION" to idxPermFineLoc,
                        "android.permission.ACCESS_COARSE_LOCATION" to idxPermCoarseLoc,
                        "android.permission.QUERY_ALL_PACKAGES" to idxPermQueryAll
                    )
                    for ((permStr, permIdx) in reqPerms) {
                        if (permStr !in existingPermissions) {
                            xmlBodyOut.write(
                                buildUsesPermissionElementChunks(
                                    idxAndroidNs = idxAndroidNs,
                                    idxUsesPermissionTag = idxUsesPermissionTag,
                                    idxAttrName = idxAttrName,
                                    idxPermValue = permIdx
                                )
                            )
                        }
                    }
                }

                if (injectBootstrapAndPermissions && elemName == "application" && !providerInjected) {
                    injectProviderAfterThisChunk = true
                }

                val needInsertAllowBackup = (injectBootstrapAndPermissions && elemName == "application" && !hasAllowBackup)
                val needInsertExtractNativeLibs = (injectBootstrapAndPermissions && elemName == "application" && !hasExtractNativeLibs)
                val needInsertRequiredFalse = (injectBootstrapAndPermissions &&
                    (elemName == "uses-library" || elemName == "uses-native-library") &&
                    !hasRequiredAttr && idxAttrRequired >= 0)
                val needInsertAppCompFactory = (injectBootstrapAndPermissions && elemName == "application" &&
                    !hasAppComponentFactory && idxAttrAppComponentFactory >= 0 && idxOverrideAppCompFactory >= 0)
                if ((indicesToRemove.isNotEmpty() || needInsertAllowBackup || needInsertExtractNativeLibs ||
                        needInsertRequiredFalse || needInsertAppCompFactory) &&
                    attrStart == 20 && attrSize == 20
                ) {
                    val keptAttrs = ArrayList<ByteArray>(attrCount + 4)
                    var insertedAllowBackupAttr = !needInsertAllowBackup
                    var insertedRequiredAttr = !needInsertRequiredFalse
                    var insertedExtractAttr = !needInsertExtractNativeLibs
                    var insertedAppCompFactoryAttr = !needInsertAppCompFactory

                    val allowBackupAttrBytes = if (needInsertAllowBackup) {
                        ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
                            putInt(idxAndroidNs)
                            putInt(idxAttrAllowBackup)
                            putInt(-1)
                            putShort(0x0008.toShort())
                            put(0.toByte())
                            put(0x12.toByte()) // TYPE_INT_BOOLEAN
                            putInt(0) // false
                        }.array()
                    } else null

                    val requiredFalseAttrBytes = if (needInsertRequiredFalse) {
                        ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
                            putInt(idxAndroidNs)
                            putInt(idxAttrRequired)
                            putInt(-1)
                            putShort(0x0008.toShort())
                            put(0.toByte())
                            put(0x12.toByte()) // TYPE_INT_BOOLEAN
                            putInt(0) // false
                        }.array()
                    } else null

                    val extractAttrBytes = if (needInsertExtractNativeLibs) {
                        ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
                            putInt(idxAndroidNs)
                            putInt(idxAttrExtractNativeLibs)
                            putInt(-1)
                            putShort(0x0008.toShort())
                            put(0.toByte())
                            put(0x12.toByte()) // TYPE_INT_BOOLEAN
                            putInt(-1) // true
                        }.array()
                    } else null

                    val appCompFactoryAttrBytes = if (needInsertAppCompFactory) {
                        ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
                            putInt(idxAndroidNs)
                            putInt(idxAttrAppComponentFactory)
                            putInt(idxOverrideAppCompFactory)
                            putShort(0x0008.toShort())
                            put(0.toByte())
                            put(0x03.toByte()) // TYPE_STRING
                            putInt(idxOverrideAppCompFactory)
                        }.array()
                    } else null

                    for (a in 0 until attrCount) {
                        if (a in indicesToRemove) continue
                        val srcOff = 36 + a * 20
                        if (srcOff + 20 <= chunkBytes.size) {
                            val aNameIdx = cBuf.getInt(srcOff + 4)
                            val rawResId = resIds.getOrNull(aNameIdx) ?: 0
                            val sortResId = if (rawResId != 0) rawResId.toLong() and 0xFFFFFFFFL else Long.MAX_VALUE
                            if (!insertedAllowBackupAttr && sortResId > ATTR_ALLOW_BACKUP_RES_ID.toLong()) {
                                allowBackupAttrBytes?.let { keptAttrs.add(it) }
                                insertedAllowBackupAttr = true
                            }
                            if (!insertedRequiredAttr && sortResId > ATTR_REQUIRED_RES_ID.toLong()) {
                                requiredFalseAttrBytes?.let { keptAttrs.add(it) }
                                insertedRequiredAttr = true
                            }
                            if (!insertedExtractAttr && sortResId > ATTR_EXTRACT_NATIVE_LIBS_RES_ID.toLong()) {
                                extractAttrBytes?.let { keptAttrs.add(it) }
                                insertedExtractAttr = true
                            }
                            if (!insertedAppCompFactoryAttr && sortResId > ATTR_APP_COMPONENT_FACTORY_RES_ID.toLong()) {
                                appCompFactoryAttrBytes?.let { keptAttrs.add(it) }
                                insertedAppCompFactoryAttr = true
                            }
                            keptAttrs.add(chunkBytes.copyOfRange(srcOff, srcOff + 20))
                        }
                    }
                    if (!insertedAllowBackupAttr) {
                        allowBackupAttrBytes?.let { keptAttrs.add(it) }
                    }
                    if (!insertedRequiredAttr) {
                        requiredFalseAttrBytes?.let { keptAttrs.add(it) }
                    }
                    if (!insertedExtractAttr) {
                        extractAttrBytes?.let { keptAttrs.add(it) }
                    }
                    if (!insertedAppCompFactoryAttr) {
                        appCompFactoryAttrBytes?.let { keptAttrs.add(it) }
                    }

                    var newIdIdx = 0
                    var newClassIdx = 0
                    var newStyleIdx = 0
                    for (k in keptAttrs.indices) {
                        val kBuf = ByteBuffer.wrap(keptAttrs[k]).order(ByteOrder.LITTLE_ENDIAN)
                        val kNs = kBuf.getInt(0)
                        val kNameIdx = kBuf.getInt(4)
                        if (kNs == -1) {
                            when (strings.getOrNull(kNameIdx)) {
                                "id" -> newIdIdx = k + 1
                                "class" -> newClassIdx = k + 1
                                "style" -> newStyleIdx = k + 1
                            }
                        }
                    }

                    val newChunkSize = 36 + keptAttrs.size * 20
                    val rebuilt = ByteArray(newChunkSize)
                    System.arraycopy(chunkBytes, 0, rebuilt, 0, 36)
                    val rBuf = ByteBuffer.wrap(rebuilt).order(ByteOrder.LITTLE_ENDIAN)
                    rBuf.putInt(4, newChunkSize)
                    rBuf.putShort(28, keptAttrs.size.toShort())
                    rBuf.putShort(30, newIdIdx.toShort()) // idIndex
                    rBuf.putShort(32, newClassIdx.toShort()) // classIndex
                    rBuf.putShort(34, newStyleIdx.toShort()) // styleIndex
                    var dstOff = 36
                    for (attrArr in keptAttrs) {
                        System.arraycopy(attrArr, 0, rebuilt, dstOff, 20)
                        dstOff += 20
                    }
                    chunkBytes = rebuilt
                }
            } else if (injectBootstrapAndPermissions && chunkType == 0x0103 && chunkSize >= 24) {
                val endNameIdx = cBuf.getInt(20)
                val endName = strings.getOrNull(endNameIdx) ?: ""
                if (endName == "application" && !providerInjected) {
                    providerInjected = true
                    xmlBodyOut.write(
                        buildProviderElementChunks(
                            idxAndroidNs = idxAndroidNs,
                            idxProviderTag = idxProviderTag,
                            idxAttrName = idxAttrName,
                            idxAttrExported = idxAttrExported,
                            idxAttrAuthorities = idxAttrAuthorities,
                            idxAttrInitOrder = idxAttrInitOrder,
                            idxProviderClass = idxProviderClass,
                            idxProviderAuth = idxProviderAuth
                        )
                    )
                }
            }

            xmlBodyOut.write(chunkBytes)
            if (injectBootstrapAndPermissions && injectProviderAfterThisChunk && !providerInjected) {
                providerInjected = true
                xmlBodyOut.write(
                    buildProviderElementChunks(
                        idxAndroidNs = idxAndroidNs,
                        idxProviderTag = idxProviderTag,
                        idxAttrName = idxAttrName,
                        idxAttrExported = idxAttrExported,
                        idxAttrAuthorities = idxAttrAuthorities,
                        idxAttrInitOrder = idxAttrInitOrder,
                        idxProviderClass = idxProviderClass,
                        idxProviderAuth = idxProviderAuth
                    )
                )
            }
            pos += chunkSize
        }

        // 5. Rebuild StringPool (UTF-16LE for universal AXML compatibility) and ResourceMap
        val newSpBytes = buildUtf16StringPoolChunk(strings)
        val newRmBytes = buildResourceMapChunk(resIds)
        val xmlBodyBytes = xmlBodyOut.toByteArray()

        val totalSize = 8 + newSpBytes.size + newRmBytes.size + xmlBodyBytes.size
        val finalOut = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        finalOut.putShort(0x0003.toShort())
        finalOut.putShort(0x0008.toShort())
        finalOut.putInt(totalSize)
        finalOut.put(newSpBytes)
        finalOut.put(newRmBytes)
        finalOut.put(xmlBodyBytes)
        return finalOut.array()
    }

    private fun remapXmlChunkStringIndices(
        cBuf: ByteBuffer,
        chunkType: Int,
        chunkSize: Int,
        origResCount: Int,
        delta: Int
    ) {
        fun remapAt(offset: Int) {
            if (offset + 4 > chunkSize) return
            val v = cBuf.getInt(offset)
            if (v >= origResCount) {
                cBuf.putInt(offset, v + delta)
            }
        }

        // Common ResXMLTree_node header comment index at offset 12
        if (chunkSize >= 16) {
            remapAt(12)
        }

        when (chunkType) {
            0x0100, 0x0101 -> {
                // START_NAMESPACE / END_NAMESPACE: prefix (16), uri (20)
                if (chunkSize >= 24) {
                    remapAt(16)
                    remapAt(20)
                }
            }
            0x0102 -> {
                // START_ELEMENT: ns (16), name (20), attributes
                if (chunkSize >= 36) {
                    remapAt(16)
                    remapAt(20)
                    val attrStart = cBuf.getShort(24).toInt() and 0xFFFF
                    val attrSize = cBuf.getShort(26).toInt() and 0xFFFF
                    val attrCount = cBuf.getShort(28).toInt() and 0xFFFF
                    for (a in 0 until attrCount) {
                        val aOff = 16 + attrStart + a * attrSize
                        if (aOff + 20 > chunkSize) break
                        remapAt(aOff + 0) // aNs
                        remapAt(aOff + 4) // aName
                        remapAt(aOff + 8) // aRawValue
                        val aDataType = cBuf.get(aOff + 15).toInt() and 0xFF
                        if (aDataType == 0x03) {
                            remapAt(aOff + 16) // aData (TYPE_STRING)
                        }
                    }
                }
            }
            0x0103 -> {
                // END_ELEMENT: ns (16), name (20)
                if (chunkSize >= 24) {
                    remapAt(16)
                    remapAt(20)
                }
            }
            0x0104 -> {
                // CDATA: data (16)
                if (chunkSize >= 28) {
                    remapAt(16)
                    val dataType = cBuf.get(23).toInt() and 0xFF
                    if (dataType == 0x03) {
                        remapAt(24)
                    }
                }
            }
        }
    }

    private fun buildProviderElementChunks(
        idxAndroidNs: Int,
        idxProviderTag: Int,
        idxAttrName: Int,
        idxAttrExported: Int,
        idxAttrAuthorities: Int,
        idxAttrInitOrder: Int,
        idxProviderClass: Int,
        idxProviderAuth: Int
    ): ByteArray {
        // 4 attributes sorted by resource ID ascending:
        // 1. android:name (0x01010003) -> string (type 0x03)
        // 2. android:exported (0x01010010) -> boolean false (type 0x12, data 0)
        // 3. android:authorities (0x01010018) -> string (type 0x03)
        // 4. android:initOrder (0x01010427) -> int 2147483647 (type 0x10) so we initialize BEFORE Firebase/Startup providers!
        val startChunkSize = 36 + 4 * 20
        val endChunkSize = 24
        val buf = ByteBuffer.allocate(startChunkSize + endChunkSize).order(ByteOrder.LITTLE_ENDIAN)

        // START_ELEMENT
        buf.putShort(0x0102.toShort())
        buf.putShort(0x0010.toShort())
        buf.putInt(startChunkSize)
        buf.putInt(1) // lineNumber
        buf.putInt(-1) // comment
        buf.putInt(-1) // ns
        buf.putInt(idxProviderTag) // name
        buf.putShort(0x0014.toShort()) // attributeStart (20 bytes from offset 16)
        buf.putShort(0x0014.toShort()) // attributeSize (20 bytes each)
        buf.putShort(4.toShort()) // attributeCount
        buf.putShort(0.toShort()) // idIndex
        buf.putShort(0.toShort()) // classIndex
        buf.putShort(0.toShort()) // styleIndex

        // Attr 1: android:name="com.example.standalone.StandaloneBootstrapProvider"
        buf.putInt(idxAndroidNs)
        buf.putInt(idxAttrName)
        buf.putInt(idxProviderClass)
        buf.putShort(0x0008.toShort())
        buf.put(0.toByte())
        buf.put(0x03.toByte()) // TYPE_STRING
        buf.putInt(idxProviderClass)

        // Attr 2: android:exported="false"
        buf.putInt(idxAndroidNs)
        buf.putInt(idxAttrExported)
        buf.putInt(-1)
        buf.putShort(0x0008.toShort())
        buf.put(0.toByte())
        buf.put(0x12.toByte()) // TYPE_INT_BOOLEAN
        buf.putInt(0) // false

        // Attr 3: android:authorities="<pkg>.phantom_standalone_init"
        buf.putInt(idxAndroidNs)
        buf.putInt(idxAttrAuthorities)
        buf.putInt(idxProviderAuth)
        buf.putShort(0x0008.toShort())
        buf.put(0.toByte())
        buf.put(0x03.toByte()) // TYPE_STRING
        buf.putInt(idxProviderAuth)

        // Attr 4: android:initOrder="2147483647"
        buf.putInt(idxAndroidNs)
        buf.putInt(idxAttrInitOrder)
        buf.putInt(-1)
        buf.putShort(0x0008.toShort())
        buf.put(0.toByte())
        buf.put(0x10.toByte()) // TYPE_INT_DEC
        buf.putInt(Int.MAX_VALUE)

        // END_ELEMENT
        buf.putShort(0x0103.toShort())
        buf.putShort(0x0010.toShort())
        buf.putInt(endChunkSize)
        buf.putInt(1) // lineNumber
        buf.putInt(-1) // comment
        buf.putInt(-1) // ns
        buf.putInt(idxProviderTag) // name

        return buf.array()
    }

    private fun buildUsesPermissionElementChunks(
        idxAndroidNs: Int,
        idxUsesPermissionTag: Int,
        idxAttrName: Int,
        idxPermValue: Int
    ): ByteArray {
        val startChunkSize = 36 + 1 * 20
        val endChunkSize = 24
        val buf = ByteBuffer.allocate(startChunkSize + endChunkSize).order(ByteOrder.LITTLE_ENDIAN)

        // START_ELEMENT
        buf.putShort(0x0102.toShort())
        buf.putShort(0x0010.toShort())
        buf.putInt(startChunkSize)
        buf.putInt(1) // lineNumber
        buf.putInt(-1) // comment
        buf.putInt(-1) // ns
        buf.putInt(idxUsesPermissionTag) // name
        buf.putShort(0x0014.toShort()) // attributeStart
        buf.putShort(0x0014.toShort()) // attributeSize
        buf.putShort(1.toShort()) // attributeCount
        buf.putShort(0.toShort()) // idIndex
        buf.putShort(0.toShort()) // classIndex
        buf.putShort(0.toShort()) // styleIndex

        // Attr 1: android:name="<permission>"
        buf.putInt(idxAndroidNs)
        buf.putInt(idxAttrName)
        buf.putInt(idxPermValue)
        buf.putShort(0x0008.toShort())
        buf.put(0.toByte())
        buf.put(0x03.toByte()) // TYPE_STRING
        buf.putInt(idxPermValue)

        // END_ELEMENT
        buf.putShort(0x0103.toShort())
        buf.putShort(0x0010.toShort())
        buf.putInt(endChunkSize)
        buf.putInt(1) // lineNumber
        buf.putInt(-1) // comment
        buf.putInt(-1) // ns
        buf.putInt(idxUsesPermissionTag) // name

        return buf.array()
    }

    private fun buildUtf16StringPoolChunk(strings: List<String>): ByteArray {
        val offsets = IntArray(strings.size)
        val dataBos = ByteArrayOutputStream(strings.size * 32)

        for (i in strings.indices) {
            offsets[i] = dataBos.size()
            val s = strings[i]
            val len = s.length
            if (len < 0x8000) {
                dataBos.write(len and 0xFF)
                dataBos.write((len shr 8) and 0xFF)
            } else {
                val hi = 0x8000 or ((len shr 16) and 0x7FFF)
                val lo = len and 0xFFFF
                dataBos.write(hi and 0xFF)
                dataBos.write((hi shr 8) and 0xFF)
                dataBos.write(lo and 0xFF)
                dataBos.write((lo shr 8) and 0xFF)
            }
            for (c in s) {
                val code = c.code
                dataBos.write(code and 0xFF)
                dataBos.write((code shr 8) and 0xFF)
            }
            // Null terminator (2 bytes)
            dataBos.write(0)
            dataBos.write(0)
        }

        // Pad to 4-byte boundary
        while (dataBos.size() % 4 != 0) {
            dataBos.write(0)
        }

        val strData = dataBos.toByteArray()
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val chunkSize = stringsStart + strData.size

        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0001.toShort())
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(strings.size)
        buf.putInt(0) // styleCount = 0
        buf.putInt(0) // flags = 0 (UTF-16LE)
        buf.putInt(stringsStart)
        buf.putInt(0) // stylesStart = 0
        for (off in offsets) {
            buf.putInt(off)
        }
        buf.put(strData)
        return buf.array()
    }

    private fun buildResourceMapChunk(resIds: List<Int>): ByteArray {
        val chunkSize = 8 + resIds.size * 4
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0180.toShort())
        buf.putShort(0x0008.toShort())
        buf.putInt(chunkSize)
        for (id in resIds) {
            buf.putInt(id)
        }
        return buf.array()
    }

    private fun readUtf8String(bytes: ByteArray, offset: Int): String {
        var p = offset
        if (p >= bytes.size) return ""
        val b0 = bytes[p].toInt() and 0xFF
        p += if ((b0 and 0x80) != 0) 2 else 1
        if (p >= bytes.size) return ""
        val l0 = bytes[p].toInt() and 0xFF
        val byteLen: Int
        if ((l0 and 0x80) != 0) {
            val l1 = bytes.getOrElse(p + 1) { 0 }.toInt() and 0xFF
            byteLen = ((l0 and 0x7F) shl 8) or l1
            p += 2
        } else {
            byteLen = l0
            p += 1
        }
        if (p + byteLen > bytes.size) return ""
        return String(bytes, p, byteLen, StandardCharsets.UTF_8)
    }

    private fun readUtf16String(buf: ByteBuffer, offset: Int): String {
        var p = offset
        if (p + 2 > buf.limit()) return ""
        val u0 = buf.getShort(p).toInt() and 0xFFFF
        val charLen: Int
        if ((u0 and 0x8000) != 0) {
            val u1 = buf.getShort(p + 2).toInt() and 0xFFFF
            charLen = ((u0 and 0x7FFF) shl 16) or u1
            p += 4
        } else {
            charLen = u0
            p += 2
        }
        val sb = StringBuilder(charLen)
        for (i in 0 until charLen) {
            if (p + 2 > buf.limit()) break
            sb.append((buf.getShort(p).toInt() and 0xFFFF).toChar())
            p += 2
        }
        return sb.toString()
    }
}
