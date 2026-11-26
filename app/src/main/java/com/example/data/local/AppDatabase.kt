package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE device_profiles ADD COLUMN buildNumber TEXT NOT NULL DEFAULT 'UP1A.231005.007'")
        } catch (_: Throwable) {}
        try {
            db.execSQL("ALTER TABLE device_profiles ADD COLUMN deviceId TEXT NOT NULL DEFAULT 'marlin'")
        } catch (_: Throwable) {}
        try {
            db.execSQL("ALTER TABLE device_profiles ADD COLUMN kernelVersion TEXT NOT NULL DEFAULT '5.15.110-android14-11-g89a3f2b'")
        } catch (_: Throwable) {}
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try { db.execSQL("ALTER TABLE device_profiles ADD COLUMN simImsi TEXT NOT NULL DEFAULT '310260982314561'") } catch (_: Throwable) {}
        try { db.execSQL("ALTER TABLE device_profiles ADD COLUMN simIccid TEXT NOT NULL DEFAULT '8901410321111854920F'") } catch (_: Throwable) {}
        try { db.execSQL("ALTER TABLE device_profiles ADD COLUMN socHardware TEXT NOT NULL DEFAULT 'Snapdragon 8 Gen 3'") } catch (_: Throwable) {}
        try { db.execSQL("ALTER TABLE device_profiles ADD COLUMN basebandVersion TEXT NOT NULL DEFAULT 'g5300g-240301-240502'") } catch (_: Throwable) {}
        try { db.execSQL("ALTER TABLE device_profiles ADD COLUMN widevineDrmId TEXT NOT NULL DEFAULT '4912a870b9213e4f'") } catch (_: Throwable) {}
        try { db.execSQL("ALTER TABLE device_profiles ADD COLUMN gpuRenderer TEXT NOT NULL DEFAULT 'Adreno (TM) 750'") } catch (_: Throwable) {}
        try { db.execSQL("ALTER TABLE device_profiles ADD COLUMN bootloaderVersion TEXT NOT NULL DEFAULT 'b1-0.5-10808477'") } catch (_: Throwable) {}
        try { db.execSQL("ALTER TABLE device_profiles ADD COLUMN securityPatch TEXT NOT NULL DEFAULT '2024-08-01'") } catch (_: Throwable) {}
    }
}

@Database(
    entities = [
        DeviceProfileEntity::class,
        ResetLogEntity::class,
        AiSecurityReportEntity::class,
        TargetAppEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceProfileDao(): DeviceProfileDao
    abstract fun resetLogDao(): ResetLogDao
    abstract fun aiReportDao(): AiReportDao
    abstract fun targetAppDao(): TargetAppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = try {
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "phantomcore_database"
                    )
                        .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                        .fallbackToDestructiveMigration()
                        .fallbackToDestructiveMigrationOnDowngrade()
                        .build()
                } catch (_: Throwable) {
                    try {
                        context.applicationContext.deleteDatabase("phantomcore_database")
                    } catch (_: Throwable) {}
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "phantomcore_database"
                    )
                        .fallbackToDestructiveMigration()
                        .build()
                }
                INSTANCE = instance
                instance
            }
        }
    }
}
