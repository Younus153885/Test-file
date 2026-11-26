package com.example.data.license

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class LicenseRepository(
    private val context: Context,
    val apiClient: LicenseApiClient
) {
    constructor(context: Context) : this(
        context.applicationContext,
        LicenseApiClient(
            FirebaseFirestore.getInstance(
                LicenseApiClient.resolveFirestoreDatabaseId(context.applicationContext)
            ),
            Firebase.auth
        )
    )

    constructor(context: Context, db: FirebaseFirestore, auth: FirebaseAuth = Firebase.auth) : this(
        context.applicationContext,
        LicenseApiClient(db, auth)
    )

    companion object {
        private const val PREFS_NAME = "phantom_license_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LICENSE_KEY = "license_key"
        private const val KEY_LICENSE_EXPIRY = "license_expiry"
        private const val KEY_LICENSE_SIG = "license_sig"
        private const val KEY_ADMIN_TOKEN = "admin_token"
        private const val KEY_ADMIN_EMAIL = "admin_email"
        private const val SALT = "GhostDevice_AntiBypass_2026_#99"
        private const val TAG = "LicenseRepository"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (LicenseApiClient.localSetupVideoOverride == null &&
            (prefs.contains("setup_video_enabled") || prefs.contains("setup_video_url"))
        ) {
            val savedEnabled = prefs.getBoolean("setup_video_enabled", false)
            val savedTitle = prefs.getString("setup_video_title", "") ?: ""
            val savedUrl = prefs.getString("setup_video_url", "") ?: ""
            if (savedEnabled || savedTitle.isNotBlank() || savedUrl.isNotBlank()) {
                LicenseApiClient.localSetupVideoOverride = Triple(savedEnabled, savedTitle, savedUrl)
            }
        }
    }

    fun saveLocalSetupVideoSettings(enabled: Boolean, title: String, url: String) {
        val cleanTitle = title.trim().take(150)
        val cleanUrl = url.trim().take(500)
        prefs.edit()
            .putBoolean("setup_video_enabled", enabled)
            .putString("setup_video_title", cleanTitle)
            .putString("setup_video_url", cleanUrl)
            .apply()
        LicenseApiClient.localOverrideModifiedLocally = true
        LicenseApiClient.localSetupVideoOverride = Triple(enabled, cleanTitle, cleanUrl)
    }

    fun getDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            val hardwareAndroidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) {
                null
            }
            id = if (!hardwareAndroidId.isNullOrBlank() && hardwareAndroidId != "9774d56d682e549c") {
                hardwareAndroidId
            } else {
                UUID.randomUUID().toString().replace("-", "").take(16)
            }
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    private fun computeSignature(key: String, deviceId: String, expiry: String?): String {
        val raw = "${key.trim().uppercase(Locale.US)}|$deviceId|${expiry.orEmpty()}|$SALT"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getSavedLicenseKey(): String? {
        val key = prefs.getString(KEY_LICENSE_KEY, null) ?: return null
        val expiry = prefs.getString(KEY_LICENSE_EXPIRY, null)
        val sig = prefs.getString(KEY_LICENSE_SIG, null) ?: return null
        val expected = computeSignature(key, getDeviceId(), expiry)
        if (sig != expected) {
            clearLocalLicense()
            return null
        }
        return key
    }

    fun getSavedExpiry(): String? = prefs.getString(KEY_LICENSE_EXPIRY, null)

    fun getSavedExpiryMillis(): Long {
        val ms = prefs.getLong("license_expiry_millis", 0L)
        if (ms > 0L) return ms
        return parseIsoDateMillis(getSavedExpiry()) ?: 0L
    }

    fun isLicenseSystemEnabledLocal(): Boolean =
        prefs.getBoolean("license_system_enabled", true)

    fun setLicenseSystemEnabledLocal(enabled: Boolean) {
        prefs.edit().putBoolean("license_system_enabled", enabled).apply()
        com.example.provider.PhantomIdentityProvider.liveLicenseSystemEnabled = enabled
        if (!enabled) {
            com.example.provider.PhantomIdentityProvider.liveLicenseValid = true
        }
    }

    fun syncPublicSettingsToProvider(settings: PublicSettings) {
        setLicenseSystemEnabledLocal(settings.licenseSystemEnabled)
        val updatedSec = settings.updatedAt?.seconds?.toString() ?: ""
        val editor = prefs.edit()
            .putBoolean("admin_notif_enabled", settings.notificationEnabled)
            .putString("admin_notif_title", settings.notificationTitle)
            .putString("admin_notif_message", settings.notificationMessage)
            .putString("admin_promo_badge", settings.promoBadge)
            .putString("admin_promo_btn", settings.promoButtonText)
            .putString("admin_promo_url", settings.promoActionUrl)
            .putString("admin_notif_updated_at", updatedSec)
        if (settings.setupVideoUrl.isNotBlank() || LicenseApiClient.localSetupVideoOverride != null) {
            editor
                .putBoolean("setup_video_enabled", settings.setupVideoEnabled)
                .putString("setup_video_title", settings.setupVideoTitle)
                .putString("setup_video_url", settings.setupVideoUrl)
        }
        editor.apply()
        com.example.provider.PhantomIdentityProvider.liveAdminNotifEnabled = settings.notificationEnabled
        com.example.provider.PhantomIdentityProvider.liveAdminNotifTitle = settings.notificationTitle
        com.example.provider.PhantomIdentityProvider.liveAdminNotifMessage = settings.notificationMessage
        com.example.provider.PhantomIdentityProvider.liveAdminPromoBadge = settings.promoBadge
        com.example.provider.PhantomIdentityProvider.liveAdminPromoButtonText = settings.promoButtonText
        com.example.provider.PhantomIdentityProvider.liveAdminPromoUrl = settings.promoActionUrl
        com.example.provider.PhantomIdentityProvider.liveAdminNotifUpdatedAt = updatedSec
    }

    fun setLicenseValidStateLocal(valid: Boolean) {
        prefs.edit().putBoolean("license_valid_state", valid).apply()
        val sysEnabled = isLicenseSystemEnabledLocal()
        com.example.provider.PhantomIdentityProvider.liveLicenseValid = if (!sysEnabled) true else valid
    }

    fun isLicenseValidLocal(): Boolean {
        if (!isLicenseSystemEnabledLocal()) return true
        val key = getSavedLicenseKey()
        if (key.isNullOrBlank()) return false
        val validFlag = prefs.getBoolean("license_valid_state", false)
        if (!validFlag) return false
        val expMillis = getSavedExpiryMillis()
        if (expMillis > 0L && System.currentTimeMillis() > expMillis) return false
        return true
    }

    fun saveLocalLicense(key: String, expiry: String?, isValid: Boolean = true) {
        val cleanKey = LicenseApiClient.sanitizeKeyId(key)
        val sig = computeSignature(cleanKey, getDeviceId(), expiry)
        val expMillis = parseIsoDateMillis(expiry) ?: 0L
        prefs.edit()
            .putString(KEY_LICENSE_KEY, cleanKey)
            .putString(KEY_LICENSE_EXPIRY, expiry)
            .putLong("license_expiry_millis", expMillis)
            .putBoolean("license_valid_state", isValid)
            .putString(KEY_LICENSE_SIG, sig)
            .apply()
        com.example.provider.PhantomIdentityProvider.liveLicenseKey = cleanKey
        com.example.provider.PhantomIdentityProvider.liveLicenseExpiresAtMs = expMillis
        com.example.provider.PhantomIdentityProvider.liveLicenseValid =
            if (!isLicenseSystemEnabledLocal()) true else isValid
    }

    fun clearLocalLicense() {
        prefs.edit()
            .remove(KEY_LICENSE_KEY)
            .remove(KEY_LICENSE_EXPIRY)
            .remove(KEY_LICENSE_SIG)
            .putLong("license_expiry_millis", 0L)
            .putBoolean("license_valid_state", false)
            .apply()
        com.example.provider.PhantomIdentityProvider.liveLicenseKey = ""
        com.example.provider.PhantomIdentityProvider.liveLicenseExpiresAtMs = 0L
        com.example.provider.PhantomIdentityProvider.liveLicenseValid = !isLicenseSystemEnabledLocal()
    }

    fun getCachedLicenses(): List<LicenseModel> {
        val raw = prefs.getString("cached_admin_licenses", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<LicenseModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    LicenseModel(
                        id = obj.optString("id", "lic_$i"),
                        key = obj.optString("key", ""),
                        days = obj.optInt("days", 30),
                        price = obj.optDouble("price", 60.0),
                        status = obj.optString("status", "inactive"),
                        deviceId = if (obj.has("deviceId") && !obj.isNull("deviceId")) obj.getString("deviceId") else null,
                        activatedAt = if (obj.has("activatedAt") && !obj.isNull("activatedAt")) obj.getString("activatedAt") else null,
                        expiresAt = if (obj.has("expiresAt") && !obj.isNull("expiresAt")) obj.getString("expiresAt") else null,
                        note = if (obj.has("note") && !obj.isNull("note")) obj.getString("note") else ""
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedLicenses(licenses: List<LicenseModel>) {
        try {
            val arr = org.json.JSONArray()
            licenses.forEach { lic ->
                val obj = org.json.JSONObject().apply {
                    put("id", lic.id)
                    put("key", lic.key)
                    put("days", lic.days)
                    put("price", lic.price)
                    put("status", lic.status)
                    lic.deviceId?.let { put("deviceId", it) }
                    lic.activatedAt?.let { put("activatedAt", it) }
                    lic.expiresAt?.let { put("expiresAt", it) }
                    if (lic.note.isNotBlank()) put("note", lic.note)
                }
                arr.put(obj)
            }
            prefs.edit().putString("cached_admin_licenses", arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveCachedLicenses error", e)
        }
    }

    fun saveAdminToken(token: String, email: String) {
        prefs.edit()
            .putString(KEY_ADMIN_TOKEN, token)
            .putString(KEY_ADMIN_EMAIL, email)
            .apply()
    }

    fun getAdminToken(): String? = prefs.getString(KEY_ADMIN_TOKEN, null)
    fun getAdminEmail(): String? = prefs.getString(KEY_ADMIN_EMAIL, null)

    fun clearAdminToken() {
        prefs.edit()
            .remove(KEY_ADMIN_TOKEN)
            .remove(KEY_ADMIN_EMAIL)
            .apply()
    }

    fun parseIsoDateMillis(isoDate: String?): Long? {
        if (isoDate.isNullOrBlank()) return null
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd"
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = sdf.parse(isoDate)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun isDateExpired(isoDate: String?): Boolean {
        val expMillis = parseIsoDateMillis(isoDate) ?: return false
        return expMillis <= System.currentTimeMillis()
    }

    fun isSavedLicenseExpiredNow(): Boolean {
        val savedKey = getSavedLicenseKey()
        if (savedKey.isNullOrBlank()) return false
        val savedExpiry = getSavedExpiry()
        return isDateExpired(savedExpiry)
    }

    suspend fun markSavedLicenseExpired() = withContext(Dispatchers.IO) {
        val savedKey = getSavedLicenseKey()
        if (!savedKey.isNullOrBlank()) {
            // Check remote license first in case Admin just extended its duration!
            val remote = try {
                apiClient.getLicenseByKey(savedKey)
            } catch (_: Exception) {
                null
            }
            if (remote != null && remote.status == "active" && !isDateExpired(remote.expiresAt)) {
                saveLocalLicense(remote.key, remote.expiresAt, isValid = true)
                return@withContext
            }
            apiClient.markLicenseExpired(savedKey)
            // Keep savedKey in prefs with isValid = false so if Admin extends the key later, it auto-unlocks!
            saveLocalLicense(savedKey, remote?.expiresAt ?: getSavedExpiry(), isValid = false)
        } else {
            clearLocalLicense()
        }
    }

    fun evaluateLicense(license: LicenseModel?, deviceId: String = getDeviceId()): LicenseVerificationResult {
        if (license == null) {
            return LicenseVerificationResult.NotFound
        }
        if (license.status == "suspended" || license.status == "blocked") {
            if (license.deviceId == deviceId) {
                saveLocalLicense(license.key, license.expiresAt, isValid = false)
            }
            return LicenseVerificationResult.Suspended
        }
        // If never bound to a device yet (inactive, or active with empty deviceId), treat as ready for first-time activation
        if (license.status == "inactive" || (license.status == "active" && license.deviceId.isNullOrBlank())) {
            return LicenseVerificationResult.Inactive(license)
        }
        // Note: if Admin extended expiresAt in Firestore, !isDateExpired(license.expiresAt) is true!
        if (isDateExpired(license.expiresAt) || (license.status == "expired" && isDateExpired(license.expiresAt))) {
            if (license.deviceId == deviceId) {
                saveLocalLicense(license.key, license.expiresAt, isValid = false)
            }
            return LicenseVerificationResult.Expired
        }
        if (license.status == "active" || (license.status == "expired" && !isDateExpired(license.expiresAt))) {
            return if (license.deviceId == deviceId) {
                saveLocalLicense(license.key, license.expiresAt, isValid = true)
                LicenseVerificationResult.Valid
            } else {
                LicenseVerificationResult.ActiveOnOtherDevice(license)
            }
        }
        return LicenseVerificationResult.NotFound
    }

    fun observeSettings(): Flow<PublicSettings> = apiClient.observeSettings()

    fun observeActivePackages(): Flow<List<PackageModel>> = apiClient.observeActivePackages()

    fun observeLicenseByKey(key: String): Flow<LicenseModel?> = apiClient.observeLicenseByKey(key)

    fun observeSavedLicenseStatus(): Flow<LicenseVerificationResult> {
        val savedKey = getSavedLicenseKey().orEmpty()
        return kotlinx.coroutines.flow.flow {
            if (savedKey.isBlank()) {
                emit(LicenseVerificationResult.NotFound)
                return@flow
            }
            apiClient.observeLicenseByKey(savedKey).collect { license ->
                val result = evaluateLicense(license, getDeviceId())
                if (result is LicenseVerificationResult.Expired && license?.status == "active") {
                    apiClient.markLicenseExpired(savedKey)
                }
                emit(result)
            }
        }
    }

    suspend fun checkLicenseStatus(): Pair<PublicSettings, LicenseVerificationResult> =
        withContext(Dispatchers.IO) {
            val settings = apiClient.getSettings()
            setLicenseSystemEnabledLocal(settings.licenseSystemEnabled)
            if (!settings.licenseSystemEnabled) {
                return@withContext Pair(settings, LicenseVerificationResult.Valid)
            }

            val savedKey = getSavedLicenseKey()
            val deviceId = getDeviceId()

            if (savedKey.isNullOrBlank()) {
                setLicenseValidStateLocal(false)
                return@withContext Pair(settings, LicenseVerificationResult.NotFound)
            }

            // Always query Firestore FIRST so if Admin extended the license duration, we immediately pick up the new expiresAt!
            val license = try {
                apiClient.getLicenseByKey(savedKey)
            } catch (e: Exception) {
                // Offline fallback: evaluate locally saved expiry
                if (isDateExpired(getSavedExpiry())) {
                    setLicenseValidStateLocal(false)
                    return@withContext Pair(settings, LicenseVerificationResult.Expired)
                }
                return@withContext Pair(
                    settings,
                    LicenseVerificationResult.Error(
                        e.localizedMessage ?: com.example.ui.i18n.tr(
                            "Verification error",
                            "যাচাই ত্রুটি",
                            "सत्यापन त्रुटि"
                        )
                    )
                )
            }

            if (license == null) {
                clearLocalLicense()
                return@withContext Pair(settings, LicenseVerificationResult.NotFound)
            }

            // If admin reset device binding (status == inactive or deviceId == null), require re-activation
            if (license.status == "inactive" || license.deviceId.isNullOrBlank()) {
                clearLocalLicense()
                return@withContext Pair(settings, LicenseVerificationResult.NotFound)
            }

            val result = evaluateLicense(license, deviceId)
            when (result) {
                is LicenseVerificationResult.Expired -> {
                    if (license.status == "active") {
                        apiClient.markLicenseExpired(savedKey)
                    }
                    // Keep savedKey with isValid = false so Admin extensions to this key are detected automatically
                    saveLocalLicense(license.key, license.expiresAt, isValid = false)
                }
                is LicenseVerificationResult.Suspended -> {
                    saveLocalLicense(license.key, license.expiresAt, isValid = false)
                }
                is LicenseVerificationResult.NotFound -> {
                    clearLocalLicense()
                }
                else -> {}
            }
            Pair(settings, result)
        }

    suspend fun verifyEnteredKey(inputKey: String): LicenseVerificationResult =
        withContext(Dispatchers.IO) {
            val key = LicenseApiClient.sanitizeKeyId(inputKey)
            val deviceId = getDeviceId()

            val license = apiClient.getLicenseByKey(key) ?: return@withContext LicenseVerificationResult.NotFound
            evaluateLicense(license, deviceId)
        }

    suspend fun activateLicense(license: LicenseModel): Result<LicenseModel> =
        withContext(Dispatchers.IO) {
            val res = apiClient.activateLicense(license.key.ifBlank { license.id }, getDeviceId(), license.days)
            res.onSuccess { activated ->
                saveLocalLicense(activated.key, activated.expiresAt, isValid = true)
            }
            res
        }

    suspend fun transferLicense(license: LicenseModel): Result<Unit> =
        withContext(Dispatchers.IO) {
            val res = apiClient.transferLicense(license.key.ifBlank { license.id }, getDeviceId())
            res.onSuccess {
                saveLocalLicense(license.key, license.expiresAt, isValid = true)
            }
            res
        }
}
