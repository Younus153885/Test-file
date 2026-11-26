package com.example.data.license

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import org.json.JSONArray
import org.json.JSONObject

const val SUPER_ADMIN_EMAIL = "younusali153885@gmail.com"

val SUPER_ADMIN_EMAILS = setOf(
    "younusali153885@gmail.com",
    "bdyounus691@gmail.com"
)

fun isAuthorizedAdminEmail(email: String?): Boolean {
    val normalized = email?.trim()?.lowercase(java.util.Locale.US).orEmpty()
    return normalized in SUPER_ADMIN_EMAILS
}

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Exception, operationType: OperationType, path: String?): String {
    val auth = try { FirebaseAuth.getInstance() } catch (e: Exception) { null }
    val currentUser = auth?.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", exception.message ?: exception.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@IgnoreExtraProperties
data class LicenseModel(
    @DocumentId val id: String = "",
    val key: String = "",
    val days: Int = 30,
    val price: Double = 0.0,
    val status: String = "inactive", // "active", "inactive", "expired", "suspended"
    val adminEmail: String = SUPER_ADMIN_EMAIL,
    val createdBy: String = "",
    val deviceId: String? = null,
    val activatedByUid: String? = null,
    val activatedAt: String? = null,
    val expiresAt: String? = null,
    val maxVideoDuration: Int = 0,
    val lastTransferredAt: String? = null,
    val note: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PackageModel(
    @DocumentId val id: String = "",
    val name: String = "",
    val durationDays: Int = 30,
    val price: Double = 0.0,
    val currency: String = "BDT",
    val active: Boolean = true,
    val maxVideoDuration: Int = 0,
    val description: String = "",
    val createdBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PublicSettings(
    @DocumentId val id: String = "global",
    val licenseSystemEnabled: Boolean = true,
    val appName: String = "GhostDevice",
    val supportNumber: String = "01717775962",
    val notificationEnabled: Boolean = false,
    val notificationTitle: String = "",
    val notificationMessage: String = "",
    val promoBadge: String = "",
    val promoButtonText: String = "",
    val promoActionUrl: String = "",
    val setupVideoEnabled: Boolean = false,
    val setupVideoTitle: String = "",
    val setupVideoUrl: String = "",
    val updatedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val promoTitle: String get() = promoBadge
    val promoUrl: String get() = promoActionUrl
}

sealed class LicenseVerificationResult {
    data object Valid : LicenseVerificationResult()
    data class Inactive(val license: LicenseModel) : LicenseVerificationResult()
    data class ActiveOnOtherDevice(val license: LicenseModel) : LicenseVerificationResult()
    data object Expired : LicenseVerificationResult()
    data object Suspended : LicenseVerificationResult()
    data object NotFound : LicenseVerificationResult()
    data class Error(val message: String) : LicenseVerificationResult()
}
