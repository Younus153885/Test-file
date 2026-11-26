package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_profiles")
data class DeviceProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val profileName: String,
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
    val socHardware: String = "Snapdragon 8 Gen 3 (SM8650-AB)",
    val gpuRenderer: String = "Adreno (TM) 750",
    val basebandVersion: String = "g5300g-240301-240502-B-11812984",
    val simImsi: String = "310260982314561",
    val simIccid: String = "8901410321111854920F",
    val widevineDrmId: String = "4912a870b9213e4f",
    val bootloaderVersion: String = "b1-0.5-10808477",
    val securityPatch: String = "2024-08-01",
    val osVersion: String,
    val serialNumber: String,
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val carrier: String,
    val display: String,
    val userAgent: String,
    val webviewHash: String,
    val creativeId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isPreset: Boolean = false,
    val isOriginalBackup: Boolean = false
)

@Entity(tableName = "reset_logs")
data class ResetLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val formattedDate: String,
    val totalDurationMs: Long,
    val successfulSteps: Int,
    val totalSteps: Int = 15,
    val status: String = "OK",
    val oldAndroidId: String,
    val newAndroidId: String,
    val deviceModel: String,
    val location: String,
    val ipAddress: String,
    val stepsSummary: String
)

@Entity(tableName = "ai_security_reports")
data class AiSecurityReportEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val privacyScore: Int,
    val threatLevel: String,
    val deviceModel: String,
    val androidIdMasked: String,
    val detectedVectors: String,
    val aiAnalysisText: String,
    val actionableHardening: String
)

@Entity(tableName = "target_apps")
data class TargetAppEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val versionName: String,
    val isSelected: Boolean,
    val isSystemApp: Boolean,
    val category: String
)
