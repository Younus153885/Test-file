package com.example.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import java.io.File

class PhantomIdentityProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.aistudio.phantomcore.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/identity")
        const val METHOD_GET_IDENTITY = "get_identity"
        const val EXTRA_IDENTITY_JSON = "identity_json"
        const val METHOD_GET_WALK_TELEMETRY = "get_walk_telemetry"
        const val EXTRA_WALK_TELEMETRY = "walk_telemetry_payload"
        const val EXTRA_STEP_PACKAGES = "step_walk_packages"
        const val EXTRA_SPEED_PACKAGES = "speed_hack_packages"
        const val EXTRA_ROOT_PACKAGES = "root_bypass_packages"
        const val EXTRA_AD_BYPASS_PACKAGES = "ad_bypass_packages"
        const val EXTRA_STANDALONE_PACKAGES = "standalone_apk_packages"
        const val EXTRA_APP_SPEED_MULT = "app_speed_multiplier"
        const val METHOD_REPORT_REAL_GPS = "report_real_gps"
        const val METHOD_GET_LICENSE_STATUS = "get_license_status"
        const val EXTRA_LICENSE_SYSTEM_ENABLED = "license_system_enabled"
        const val EXTRA_LICENSE_VALID = "license_valid"
        const val EXTRA_LICENSE_EXPIRES_AT_MS = "license_expires_at_ms"
        const val EXTRA_LICENSE_KEY = "license_key"
        const val EXTRA_FIRESTORE_PROJECT_ID = "firestore_project_id"
        const val EXTRA_FIRESTORE_DATABASE_ID = "firestore_database_id"
        const val METHOD_SAVE_STANDALONE_IDENTITY = "save_standalone_identity"
        const val METHOD_GET_STANDALONE_IDENTITY = "get_standalone_identity"
        const val EXTRA_STANDALONE_IDENTITY_JSON = "standalone_identity_json"

        @Volatile
        var cachedIdentityJson: String = ""

        val standalonePackageIdentities = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun syncGlobalIdentityToAllStandaloneVaults(context: android.content.Context?, globalIdentityJson: String) {
            if (globalIdentityJson.isBlank()) return
            standalonePackageIdentities.clear()
            if (context != null) {
                try {
                    val sp = context.getSharedPreferences("phantom_standalone_identities", android.content.Context.MODE_PRIVATE)
                    val keys = sp.all.keys.toList()
                    val editor = sp.edit()
                    for (k in keys) {
                        editor.putString(k, globalIdentityJson)
                        standalonePackageIdentities[k] = globalIdentityJson
                    }
                    editor.commit()
                } catch (_: Throwable) {}
                try {
                    val dir = File(context.filesDir, "standalone_identities")
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { f ->
                            if (f.isFile && f.name.endsWith(".json")) {
                                try { f.writeText(globalIdentityJson, Charsets.UTF_8) } catch (_: Throwable) {}
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
            val extDirs = arrayOf("/sdcard/Download", "/sdcard/Documents", "/data/local/tmp")
            for (dPath in extDirs) {
                try {
                    val d = File(dPath)
                    if (d.exists() && d.isDirectory) {
                        d.listFiles()?.forEach { f ->
                            if (f.isFile && f.name.startsWith(".phantom_standalone_id_") && f.name.endsWith(".json")) {
                                try { f.writeText(globalIdentityJson, Charsets.UTF_8) } catch (_: Throwable) {}
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        fun saveStandaloneIdentityForPackage(context: android.content.Context?, pkg: String, identityJson: String) {
            val cleanPkg = pkg.trim()
            if (cleanPkg.isEmpty() || identityJson.isBlank()) return
            standalonePackageIdentities[cleanPkg] = identityJson
            val safeFileName = cleanPkg.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            if (context != null) {
                try {
                    context.getSharedPreferences("phantom_standalone_identities", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString(cleanPkg, identityJson)
                        .commit()
                } catch (_: Throwable) {}
                try {
                    val dir = File(context.filesDir, "standalone_identities")
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, "$safeFileName.json").writeText(identityJson, Charsets.UTF_8)
                } catch (_: Throwable) {}
            }
            val extPaths = arrayOf(
                "/sdcard/Download/.phantom_standalone_id_$safeFileName.json",
                "/sdcard/Documents/.phantom_standalone_id_$safeFileName.json",
                "/data/local/tmp/.phantom_standalone_id_$safeFileName.json"
            )
            for (p in extPaths) {
                try {
                    val f = File(p)
                    f.parentFile?.mkdirs()
                    f.writeText(identityJson, Charsets.UTF_8)
                } catch (_: Throwable) {}
            }
        }

        fun loadStandaloneIdentityForPackage(context: android.content.Context?, pkg: String): String {
            if (cachedIdentityJson.isNotBlank() && cachedIdentityJson.contains("deviceModel")) {
                return cachedIdentityJson
            }
            val cleanPkg = pkg.trim()
            if (cleanPkg.isEmpty()) return ""
            val mem = standalonePackageIdentities[cleanPkg]
            if (!mem.isNullOrBlank()) return mem
            val safeFileName = cleanPkg.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            if (context != null) {
                try {
                    val mainFile = File(context.filesDir, "phantom_identity.json")
                    if (mainFile.exists()) {
                        val mainContent = mainFile.readText(Charsets.UTF_8)
                        if (mainContent.isNotBlank() && mainContent.contains("deviceModel")) {
                            return mainContent
                        }
                    }
                } catch (_: Throwable) {}
                try {
                    val file = File(File(context.filesDir, "standalone_identities"), "$safeFileName.json")
                    if (file.exists()) {
                        val content = file.readText(Charsets.UTF_8)
                        if (content.isNotBlank()) {
                            standalonePackageIdentities[cleanPkg] = content
                            return content
                        }
                    }
                } catch (_: Throwable) {}
                try {
                    val prefVal = context.getSharedPreferences("phantom_standalone_identities", android.content.Context.MODE_PRIVATE)
                        .getString(cleanPkg, "") ?: ""
                    if (prefVal.isNotBlank()) {
                        standalonePackageIdentities[cleanPkg] = prefVal
                        return prefVal
                    }
                } catch (_: Throwable) {}
            }
            val extPaths = arrayOf(
                "/sdcard/Download/.phantom_standalone_id_$safeFileName.json",
                "/sdcard/Documents/.phantom_standalone_id_$safeFileName.json",
                "/data/local/tmp/.phantom_standalone_id_$safeFileName.json"
            )
            for (p in extPaths) {
                try {
                    val f = File(p)
                    if (f.exists()) {
                        val content = f.readText(Charsets.UTF_8)
                        if (content.isNotBlank()) {
                            standalonePackageIdentities[cleanPkg] = content
                            return content
                        }
                    }
                } catch (_: Throwable) {}
            }
            return ""
        }

        @Volatile
        var liveWalkTelemetryPayload: String = ""

        @Volatile
        var activeStepWalkPackagesCsv: String = ""

        @Volatile
        var activeSpeedHackPackagesCsv: String = ""

        @Volatile
        var activeRootBypassPackagesCsv: String = ""

        @Volatile
        var activeAdBypassPackagesCsv: String = ""

        @Volatile
        var activeStandalonePackagesCsv: String = ""

        @Volatile
        var activeTargetPackagesCsv: String = ""

        @Volatile
        var liveGlobalSpoofEnabled: Boolean = false

        @Volatile
        var toggleSeqTimestampMs: Long = 0L

        @Volatile
        var hostInstanceId: String = ""

        fun getOrCreateHostInstanceId(context: android.content.Context?): String {
            val cached = hostInstanceId
            if (cached.isNotBlank()) return cached
            if (context == null) return ""
            return try {
                val prefs = context.getSharedPreferences("phantomcore_prefs", android.content.Context.MODE_PRIVATE)
                val saved = prefs.getString("host_instance_id", "") ?: ""
                if (saved.isNotBlank()) {
                    hostInstanceId = saved
                    saved
                } else {
                    val generated = java.util.UUID.randomUUID().toString()
                    prefs.edit().putString("host_instance_id", generated).apply()
                    hostInstanceId = generated
                    generated
                }
            } catch (_: Throwable) {
                ""
            }
        }

        fun markUserToggleChanged(context: android.content.Context?) {
            val now = System.currentTimeMillis()
            toggleSeqTimestampMs = now
            if (context != null) {
                try {
                    context.getSharedPreferences("phantomcore_prefs", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putLong("toggle_seq_timestamp_ms", now)
                        .apply()
                } catch (_: Throwable) {}
            }
        }

        @Volatile
        var liveAppSpeedMultiplier: Float = 1.0f

        @Volatile
        var liveLicenseSystemEnabled: Boolean = true

        @Volatile
        var liveLicenseValid: Boolean = true

        @Volatile
        var liveLicenseExpiresAtMs: Long = 0L

        @Volatile
        var liveLicenseKey: String = ""

        @Volatile
        var liveFirestoreProjectId: String = "gen-lang-client-0352583088"

        @Volatile
        var liveFirestoreDatabaseId: String = "ai-studio-android-ghostdev-713d8000-a5cb-4f19-b4d6-b1c7c00b6595"

        @Volatile
        var liveAdminNotifEnabled: Boolean = false

        @Volatile
        var liveAdminNotifTitle: String = ""

        @Volatile
        var liveAdminNotifMessage: String = ""

        @Volatile
        var liveAdminPromoBadge: String = ""

        @Volatile
        var liveAdminPromoButtonText: String = ""

        @Volatile
        var liveAdminPromoUrl: String = ""

        @Volatile
        var liveAdminNotifUpdatedAt: String = ""

        @Volatile
        private var prefsInitialized: Boolean = false
    }

    override fun onCreate(): Boolean {
        Thread {
            try {
                loadIdentityFromDisk()
                ensurePrefsLoadedOnce()
            } catch (_: Throwable) {}
        }.start()
        return true
    }

    private fun refreshLicensePrefs() {
        val ctx = context ?: return
        try {
            val licPrefs = ctx.getSharedPreferences("phantom_license_prefs", android.content.Context.MODE_PRIVATE)
            liveLicenseSystemEnabled = licPrefs.getBoolean("license_system_enabled", true)
            liveLicenseKey = licPrefs.getString("license_key", "") ?: ""
            liveLicenseExpiresAtMs = licPrefs.getLong("license_expiry_millis", 0L)
            val savedValid = licPrefs.getBoolean("license_valid_state", false)
            val notExpired = liveLicenseExpiresAtMs <= 0L || liveLicenseExpiresAtMs > System.currentTimeMillis()
            liveLicenseValid = if (!liveLicenseSystemEnabled) {
                true
            } else {
                savedValid && liveLicenseKey.isNotBlank() && notExpired
            }
            liveAdminNotifEnabled = licPrefs.getBoolean("admin_notif_enabled", liveAdminNotifEnabled)
            liveAdminNotifTitle = licPrefs.getString("admin_notif_title", liveAdminNotifTitle) ?: ""
            liveAdminNotifMessage = licPrefs.getString("admin_notif_message", liveAdminNotifMessage) ?: ""
            liveAdminPromoBadge = licPrefs.getString("admin_promo_badge", liveAdminPromoBadge) ?: ""
            liveAdminPromoButtonText = licPrefs.getString("admin_promo_btn", liveAdminPromoButtonText) ?: ""
            liveAdminPromoUrl = licPrefs.getString("admin_promo_url", liveAdminPromoUrl) ?: ""
            liveAdminNotifUpdatedAt = licPrefs.getString("admin_notif_updated_at", liveAdminNotifUpdatedAt) ?: ""
        } catch (_: Throwable) {}
    }

    private fun ensurePrefsLoadedOnce() {
        refreshLicensePrefs()
        if (prefsInitialized) return
        val ctx = context ?: return
        synchronized(PhantomIdentityProvider::class.java) {
            if (prefsInitialized) return
            try {
                val prefs = ctx.getSharedPreferences("phantomcore_prefs", android.content.Context.MODE_PRIVATE)
                if (activeStepWalkPackagesCsv.isBlank()) {
                    val stepSet = prefs.getStringSet("step_walk_packages", emptySet()) ?: emptySet()
                    activeStepWalkPackagesCsv = stepSet.joinToString(",")
                }
                if (activeSpeedHackPackagesCsv.isBlank()) {
                    val speedSet = prefs.getStringSet("speed_hack_packages", emptySet()) ?: emptySet()
                    activeSpeedHackPackagesCsv = speedSet.joinToString(",")
                }
                if (activeRootBypassPackagesCsv.isBlank()) {
                    val rootSet = prefs.getStringSet("root_bypass_packages", emptySet()) ?: emptySet()
                    activeRootBypassPackagesCsv = rootSet.joinToString(",")
                }
                if (activeAdBypassPackagesCsv.isBlank()) {
                    val adSet = prefs.getStringSet("ad_bypass_packages", emptySet()) ?: emptySet()
                    activeAdBypassPackagesCsv = adSet.joinToString(",")
                }
                if (activeStandalonePackagesCsv.isBlank()) {
                    val standaloneSet = prefs.getStringSet("standalone_apk_packages", emptySet()) ?: emptySet()
                    activeStandalonePackagesCsv = standaloneSet.joinToString(",")
                }
                liveGlobalSpoofEnabled = prefs.getBoolean("global_spoof_enabled", liveGlobalSpoofEnabled)
                if (toggleSeqTimestampMs <= 0L) {
                    toggleSeqTimestampMs = prefs.getLong("toggle_seq_timestamp_ms", 0L)
                }
                if (hostInstanceId.isBlank()) {
                    val savedHostId = prefs.getString("host_instance_id", "") ?: ""
                    if (savedHostId.isNotBlank()) {
                        hostInstanceId = savedHostId
                    } else {
                        val gen = java.util.UUID.randomUUID().toString()
                        prefs.edit().putString("host_instance_id", gen).apply()
                        hostInstanceId = gen
                    }
                }
                if (kotlin.math.abs(liveAppSpeedMultiplier - 1.0f) < 0.0005f) {
                    liveAppSpeedMultiplier = prefs.getFloat("app_speed_multiplier", 1.0f).coerceIn(0.001f, 1000.0f)
                }
                prefsInitialized = true
            } catch (_: Throwable) {}
        }
    }

    private fun loadStepPackagesFromPrefs(): String {
        ensurePrefsLoadedOnce()
        return activeStepWalkPackagesCsv
    }

    private fun loadSpeedPackagesFromPrefs(): String {
        ensurePrefsLoadedOnce()
        return activeSpeedHackPackagesCsv
    }

    private fun loadRootPackagesFromPrefs(): String {
        ensurePrefsLoadedOnce()
        return activeRootBypassPackagesCsv
    }

    private fun loadAdBypassPackagesFromPrefs(): String {
        ensurePrefsLoadedOnce()
        return activeAdBypassPackagesCsv
    }

    private fun loadStandalonePackagesFromPrefs(): String {
        ensurePrefsLoadedOnce()
        return activeStandalonePackagesCsv
    }

    private fun loadIdentityFromDisk(): String {
        var bestJson = cachedIdentityJson
        var bestTs = try {
            if (bestJson.isNotBlank()) org.json.JSONObject(bestJson).optLong("resetTimestamp", 0L) else 0L
        } catch (_: Throwable) { 0L }

        val ctx = context
        if (ctx != null) {
            try {
                val file = File(ctx.filesDir, "phantom_identity.json")
                if (file.exists()) {
                    val content = file.readText(Charsets.UTF_8)
                    if (content.isNotBlank()) {
                        val ts = try { org.json.JSONObject(content).optLong("resetTimestamp", 0L) } catch (_: Throwable) { 0L }
                        if (bestJson.isBlank() || ts >= bestTs) {
                            bestJson = content
                            bestTs = ts
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

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
                val f = File(p)
                if (f.exists()) {
                    val content = f.readText(Charsets.UTF_8)
                    if (content.isNotBlank()) {
                        val ts = try { org.json.JSONObject(content).optLong("resetTimestamp", 0L) } catch (_: Throwable) { 0L }
                        if (bestJson.isBlank() || ts >= bestTs) {
                            bestJson = content
                            bestTs = ts
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        if (bestJson.isNotBlank()) {
            cachedIdentityJson = bestJson
        }
        return bestJson
    }

    private fun resolveStandaloneApkFile(fileName: String): File? {
        if (fileName.contains("..") || fileName.contains("/")) return null
        val ctx = context ?: return null
        val internalFile = File(File(ctx.filesDir, "standalone_apks"), fileName)
        if (internalFile.exists() && internalFile.length() > 0L) return internalFile
        val downloadDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "PhantomStandaloneAPKs"
        )
        val pubFile = File(downloadDir, fileName)
        if (pubFile.exists() && pubFile.length() > 0L) return pubFile
        return internalFile
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (uri.path?.startsWith("/standalone_apk/") == true) {
            val fileName = uri.lastPathSegment ?: "standalone.apk"
            val apkFile = resolveStandaloneApkFile(fileName)
            val size = if (apkFile != null && apkFile.exists()) apkFile.length() else 0L
            val cols = projection ?: arrayOf(
                android.provider.OpenableColumns.DISPLAY_NAME,
                android.provider.OpenableColumns.SIZE,
                "_data"
            )
            val cursor = MatrixCursor(cols)
            val row = Array<Any?>(cols.size) { idx ->
                when (cols[idx]) {
                    android.provider.OpenableColumns.DISPLAY_NAME -> fileName
                    android.provider.OpenableColumns.SIZE -> size
                    "_data" -> apkFile?.absolutePath
                    else -> null
                }
            }
            cursor.addRow(row)
            return cursor
        }
        if (uri.path?.contains("walk_telemetry") == true) {
            val stepPkgs = loadStepPackagesFromPrefs()
            val speedPkgs = loadSpeedPackagesFromPrefs()
            val cursor = MatrixCursor(arrayOf("walk_telemetry_payload", "step_walk_packages", "speed_hack_packages", "app_speed_multiplier"))
            cursor.addRow(arrayOf(liveWalkTelemetryPayload, stepPkgs, speedPkgs, liveAppSpeedMultiplier.toString()))
            return cursor
        }
        val json = loadIdentityFromDisk()
        val cursor = MatrixCursor(arrayOf("identity_json"))
        cursor.addRow(arrayOf(json))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        if (uri.path?.startsWith("/standalone_apk/") == true) {
            val fileName = uri.lastPathSegment ?: return null
            val file = resolveStandaloneApkFile(fileName)
            if (file != null && file.exists()) {
                return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        return super.openFile(uri, mode)
    }

    override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor? {
        if (uri.path?.startsWith("/standalone_apk/") == true) {
            val pfd = openFile(uri, mode) ?: return null
            return android.content.res.AssetFileDescriptor(pfd, 0, android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        return super.openAssetFile(uri, mode)
    }

    private fun putLicenseExtras(bundle: Bundle) {
        refreshLicensePrefs()
        bundle.putBoolean(EXTRA_LICENSE_SYSTEM_ENABLED, liveLicenseSystemEnabled)
        bundle.putBoolean(EXTRA_LICENSE_VALID, liveLicenseValid)
        bundle.putLong(EXTRA_LICENSE_EXPIRES_AT_MS, liveLicenseExpiresAtMs)
        bundle.putString(EXTRA_LICENSE_KEY, liveLicenseKey)
        bundle.putString(EXTRA_FIRESTORE_PROJECT_ID, liveFirestoreProjectId)
        bundle.putString(EXTRA_FIRESTORE_DATABASE_ID, liveFirestoreDatabaseId)
        bundle.putBoolean("admin_notif_enabled", liveAdminNotifEnabled)
        bundle.putString("admin_notif_title", liveAdminNotifTitle)
        bundle.putString("admin_notif_message", liveAdminNotifMessage)
        bundle.putString("admin_promo_badge", liveAdminPromoBadge)
        bundle.putString("admin_promo_btn", liveAdminPromoButtonText)
        bundle.putString("admin_promo_url", liveAdminPromoUrl)
        bundle.putString("admin_notif_updated_at", liveAdminNotifUpdatedAt)
        val langCode = try {
            context?.getSharedPreferences("phantom_language_prefs", android.content.Context.MODE_PRIVATE)
                ?.getString("selected_app_language", com.example.ui.i18n.LanguageManager.currentLang.code)
                ?: com.example.ui.i18n.LanguageManager.currentLang.code
        } catch (_: Throwable) {
            com.example.ui.i18n.LanguageManager.currentLang.code
        }
        bundle.putString("app_language", langCode)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_GET_IDENTITY) {
            val json = loadIdentityFromDisk()
            val standaloneJson = if (!arg.isNullOrBlank()) loadStandaloneIdentityForPackage(context, arg) else ""
            return Bundle().apply {
                putString(EXTRA_IDENTITY_JSON, json)
                if (standaloneJson.isNotEmpty()) {
                    putString(EXTRA_STANDALONE_IDENTITY_JSON, standaloneJson)
                }
                putString(EXTRA_STEP_PACKAGES, loadStepPackagesFromPrefs())
                putString(EXTRA_SPEED_PACKAGES, loadSpeedPackagesFromPrefs())
                putString(EXTRA_ROOT_PACKAGES, loadRootPackagesFromPrefs())
                putString(EXTRA_AD_BYPASS_PACKAGES, loadAdBypassPackagesFromPrefs())
                putString(EXTRA_STANDALONE_PACKAGES, loadStandalonePackagesFromPrefs())
                putString("target_packages", activeTargetPackagesCsv)
                putBoolean("global_spoof_enabled", liveGlobalSpoofEnabled)
                putLong("toggle_ts", toggleSeqTimestampMs)
                putString("host_instance_id", getOrCreateHostInstanceId(context))
                putFloat(EXTRA_APP_SPEED_MULT, liveAppSpeedMultiplier)
                putString("location_label", com.example.service.WalkBridge.mutableState.value.currentZoneLabel)
                putLicenseExtras(this)
            }
        } else if (method == METHOD_GET_WALK_TELEMETRY) {
            val json = loadIdentityFromDisk()
            val standaloneJson = if (!arg.isNullOrBlank()) loadStandaloneIdentityForPackage(context, arg) else ""
            return Bundle().apply {
                if (json.isNotEmpty()) {
                    putString(EXTRA_IDENTITY_JSON, json)
                }
                putString(EXTRA_WALK_TELEMETRY, liveWalkTelemetryPayload)
                if (standaloneJson.isNotEmpty()) {
                    putString(EXTRA_STANDALONE_IDENTITY_JSON, standaloneJson)
                }
                putString(EXTRA_STEP_PACKAGES, loadStepPackagesFromPrefs())
                putString(EXTRA_SPEED_PACKAGES, loadSpeedPackagesFromPrefs())
                putString(EXTRA_ROOT_PACKAGES, loadRootPackagesFromPrefs())
                putString(EXTRA_AD_BYPASS_PACKAGES, loadAdBypassPackagesFromPrefs())
                putString(EXTRA_STANDALONE_PACKAGES, loadStandalonePackagesFromPrefs())
                putString("target_packages", activeTargetPackagesCsv)
                putBoolean("global_spoof_enabled", liveGlobalSpoofEnabled)
                putLong("toggle_ts", toggleSeqTimestampMs)
                putString("host_instance_id", getOrCreateHostInstanceId(context))
                putFloat(EXTRA_APP_SPEED_MULT, liveAppSpeedMultiplier)
                putString("location_label", com.example.service.WalkBridge.mutableState.value.currentZoneLabel)
                putLicenseExtras(this)
            }
        } else if (method == METHOD_SAVE_STANDALONE_IDENTITY) {
            val pkg = arg ?: extras?.getString("package") ?: ""
            val idJson = extras?.getString(EXTRA_STANDALONE_IDENTITY_JSON) ?: extras?.getString("json") ?: ""
            if (pkg.isNotEmpty() && idJson.isNotEmpty()) {
                saveStandaloneIdentityForPackage(context, pkg, idJson)
            }
            return Bundle().apply {
                putBoolean("saved", pkg.isNotEmpty() && idJson.isNotEmpty())
            }
        } else if (method == METHOD_GET_STANDALONE_IDENTITY) {
            val pkg = arg ?: extras?.getString("package") ?: ""
            val hostJson = loadIdentityFromDisk()
            val savedJson = if (hostJson.isNotBlank() && hostJson.contains("deviceModel")) hostJson else loadStandaloneIdentityForPackage(context, pkg)
            return Bundle().apply {
                if (hostJson.isNotEmpty()) {
                    putString(EXTRA_IDENTITY_JSON, hostJson)
                }
                putString(EXTRA_STANDALONE_IDENTITY_JSON, savedJson)
                putLicenseExtras(this)
            }
        } else if (method == METHOD_GET_LICENSE_STATUS) {
            return Bundle().apply {
                putLicenseExtras(this)
            }
        } else if (method == METHOD_REPORT_REAL_GPS) {
            val parts = arg?.split("|")
            val rLat = parts?.getOrNull(0)?.toDoubleOrNull() ?: extras?.getDouble("lat", 0.0) ?: 0.0
            val rLng = parts?.getOrNull(1)?.toDoubleOrNull() ?: extras?.getDouble("lng", 0.0) ?: 0.0
            if (rLat != 0.0 && rLng != 0.0) {
                com.example.service.WalkBridge.onRealPhoneLocationDetected(rLat, rLng)
            }
            return Bundle()
        }
        return super.call(method, arg, extras)
    }

    override fun getType(uri: Uri): String {
        if (uri.path?.startsWith("/standalone_apk/") == true) {
            return "application/vnd.android.package-archive"
        }
        return "vnd.android.cursor.item/vnd.phantomcore.identity"
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
