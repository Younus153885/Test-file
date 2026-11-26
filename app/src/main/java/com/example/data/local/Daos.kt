package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceProfileDao {
    @Query("SELECT * FROM device_profiles ORDER BY isOriginalBackup DESC, createdAt DESC")
    fun getAllProfiles(): Flow<List<DeviceProfileEntity>>

    @Query("SELECT * FROM device_profiles ORDER BY isOriginalBackup DESC, createdAt DESC")
    suspend fun getAllProfilesList(): List<DeviceProfileEntity>

    @Query("SELECT * FROM device_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: Long): DeviceProfileEntity?

    @Query("SELECT * FROM device_profiles WHERE isOriginalBackup = 1 LIMIT 1")
    suspend fun getOriginalBackup(): DeviceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: DeviceProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<DeviceProfileEntity>)

    @Delete
    suspend fun deleteProfile(profile: DeviceProfileEntity)

    @Query("DELETE FROM device_profiles WHERE id = :id AND isOriginalBackup = 0")
    suspend fun deleteById(id: Long)

    @Query("UPDATE device_profiles SET profileName = :newName WHERE id = :id")
    suspend fun updateProfileName(id: Long, newName: String)
}

@Dao
interface ResetLogDao {
    @Query("SELECT * FROM reset_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<ResetLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ResetLogEntity): Long

    @Query("DELETE FROM reset_logs")
    suspend fun clearAllLogs()
}

@Dao
interface AiReportDao {
    @Query("SELECT * FROM ai_security_reports ORDER BY timestamp DESC")
    fun getAllReports(): Flow<List<AiSecurityReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: AiSecurityReportEntity): Long

    @Delete
    suspend fun deleteReport(report: AiSecurityReportEntity)

    @Query("DELETE FROM ai_security_reports")
    suspend fun clearAllReports()
}

@Dao
interface TargetAppDao {
    @Query("SELECT * FROM target_apps ORDER BY isSelected DESC, appName ASC")
    fun getAllTargetApps(): Flow<List<TargetAppEntity>>

    @Query("SELECT * FROM target_apps")
    suspend fun getAllTargetAppsList(): List<TargetAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(apps: List<TargetAppEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: TargetAppEntity)

    @Query("UPDATE target_apps SET isSelected = :isSelected WHERE packageName = :packageName")
    suspend fun updateSelection(packageName: String, isSelected: Boolean)

    @Query("UPDATE target_apps SET isSelected = :isSelected")
    suspend fun updateAllSelection(isSelected: Boolean)

    @Query("SELECT COUNT(*) FROM target_apps WHERE isSelected = 1")
    fun getSelectedCount(): Flow<Int>

    @Query("DELETE FROM target_apps")
    suspend fun deleteAll()
}
