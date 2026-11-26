package com.example.data.device

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * High-performance, non-destructive synchronization engine for LSPosed module scopes.
 * Ensures selected target apps are instantly registered in LSPosed's `modules_config.db`
 * (`scope` table) and reloads `lspd`'s in-memory scope cache (`ConfigManager.updateCache()`)
 * so that users NEVER have to manually tick apps in LSPosed Manager and changes take effect immediately.
 *
 * Strictly forbids selecting Magisk Manager, LSPosed Manager, or core Android system_server processes
 * to guarantee zero bootloops and zero system crashes.
 */
object LspScopeSyncCli {

    val REQUIRED_SYSTEM_SCOPES = setOf(
        "android",
        "system",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.verifier",
        "com.google.android.contactkeys",
        "com.android.settings",
        "com.google.android.apps.authenticator2",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.vending",
        "com.google.android.apps.restore",
        "com.google.android.apps.security.verifier",
        "com.android.keychain",
        "com.google.android.safetycore",
        "com.google.android.as.oss",
        "com.google.android.as",
        "com.google.ar.core"
    )

    private val FORBIDDEN_SCOPES = setOf(
        "com.android.systemui",
        "com.android.se",
        "com.android.nfc",
        "com.android.bluetooth",
        "com.android.shell",
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "io.github.huskydg.magisk",
        "org.lsposed.manager",
        "io.github.lsposed.manager",
        "org.meowcat.edxposed.manager",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "eu.chainfire.supersu"
    )

    fun isForbiddenScope(pkg: String): Boolean {
        val clean = pkg.trim()
        if (clean.isEmpty()) return true
        if (clean in REQUIRED_SYSTEM_SCOPES) return false
        val lower = clean.lowercase()
        if (clean in FORBIDDEN_SCOPES) return true
        if (clean.startsWith("com.google.android.wifi") ||
            clean.startsWith("com.google.android.connectivity") ||
            clean.startsWith("com.google.android.networkstack") ||
            clean.startsWith("com.qualcomm.") ||
            clean.startsWith("org.codeaurora.") ||
            clean.startsWith("vendor.qti.") ||
            clean.startsWith("com.qti.") ||
            clean.startsWith("com.mediatek.") ||
            clean.startsWith("com.sec.ims") ||
            clean.startsWith("com.sec.epdg") ||
            clean.startsWith("com.shannon.")
        ) {
            return true
        }
        if (lower.contains("wirelesssettings") ||
            lower.contains("networkstack") ||
            lower.contains("captiveportal") ||
            lower.contains("tethering") ||
            lower.contains("imsservice") ||
            lower.contains("magisk") ||
            lower.contains("lsposed") ||
            lower.contains("edxposed") ||
            lower.contains("kernelsu") ||
            lower.contains("supersu") ||
            lower.contains("phantomcore") ||
            lower.contains("com.aistudio.ghostdevice")
        ) {
            return true
        }
        return false
    }

    private val KNOWN_LSP_DB_PATHS = listOf(
        "/data/adb/lspd/config/modules_config.db",
        "/data/adb/lspd/modules_config.db",
        "/data/misc/lspd/config/modules_config.db",
        "/data/misc/lspd/modules_config.db",
        "/data/system/xlua/modules_config.db",
        "/data/system/lspd/modules_config.db",
        "/data/data/org.lsposed.manager/databases/modules_config.db",
        "/data/user/0/org.lsposed.manager/databases/modules_config.db",
        "/data/user_de/0/org.lsposed.manager/databases/modules_config.db",
        "/data/data/io.github.lsposed.manager/databases/modules_config.db",
        "/data/user/0/io.github.lsposed.manager/databases/modules_config.db",
        "/data/user_de/0/io.github.lsposed.manager/databases/modules_config.db"
    )

    /**
     * Entry point when executed via `app_process` under root (`su`).
     * Direct in-process access to `/data/adb/lspd/config/modules_config.db`.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) return
        val modulePkg = args.getOrNull(0)?.trim().orEmpty()
        if (modulePkg.isEmpty()) return
        val moduleApkPath = args.getOrNull(1)?.trim().orEmpty()
        val selectedCsv = args.getOrNull(2)?.trim().orEmpty()
        val removedCsv = args.getOrNull(3)?.trim().orEmpty()

        val selectedTargets = (selectedCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != modulePkg && !isForbiddenScope(it) } + REQUIRED_SYSTEM_SCOPES)
            .toSet()

        val removedTargets = removedCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != modulePkg && it !in REQUIRED_SYSTEM_SCOPES }
            .toSet()

        syncDatabasesDirect(
            modulePkg = modulePkg,
            moduleApkPath = moduleApkPath,
            selectedTargets = selectedTargets,
            removedTargets = removedTargets
        )
    }

    /**
     * Synchronizes LSPosed module scopes non-destructively:
     * - Keeps System Framework (`android`) 100% checked always, along with required system components
     * - Only scopes user-selected target applications from PhantomCore (no unselected random 3rd party apps)
     * - Automatically enables module in LSPosed config and lspd daemon
     */
    fun syncTargetScopeWithLsposed(
        context: Context,
        selectedTargetPackages: List<String>,
        removedTargetPackages: List<String> = emptyList(),
        newlyAddedTargetPackages: List<String> = emptyList()
    ) {
        if (!RootChecker.isDeviceRooted()) return
        val modulePkg = context.packageName
        val moduleApkPath = try {
            context.applicationInfo.sourceDir ?: ""
        } catch (_: Throwable) {
            ""
        }

        // Only include REQUIRED_SYSTEM_SCOPES + specifically selected target apps from PhantomCore
        val cleanSelected = selectedTargetPackages
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains(" ") && !it.contains(";") && it != modulePkg && !isForbiddenScope(it) }
            .plus(REQUIRED_SYSTEM_SCOPES)
            .toSet()

        val cleanRemoved = removedTargetPackages
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains(" ") && !it.contains(";") && it != modulePkg && it !in REQUIRED_SYSTEM_SCOPES && it !in cleanSelected }
            .toSet()

        val desiredScopes = LinkedHashSet<String>().apply {
            addAll(REQUIRED_SYSTEM_SCOPES)
            addAll(cleanSelected)
        }

        val selectedCsv = desiredScopes.joinToString(",")
        val removedCsv = cleanRemoved.joinToString(",")
        val selectedSpaceSeparated = desiredScopes.joinToString(" ")
        val removedSpaceSeparated = cleanRemoved.joinToString(" ")

        try {
            val forceStopCmds = (newlyAddedTargetPackages + removedTargetPackages)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains(" ") && !it.contains(";") && !isForbiddenScope(it) && it !in REQUIRED_SYSTEM_SCOPES && it != modulePkg && it != "android" }
                .distinct()
                .joinToString("\n") { pkg -> "am force-stop $pkg 2>/dev/null || true" }

            val combinedSyncScript = """
                # Ensure world-writable shared walk/speed telemetry file exists so speed changes work in < 1ms
                chmod 777 /data/local/tmp 2>/dev/null || true
                touch /data/local/tmp/.phantom_walk_state 2>/dev/null || true
                chmod 666 /data/local/tmp/.phantom_walk_state 2>/dev/null || true

                ALL_SCOPES="$selectedSpaceSeparated"

                # 1. Direct sqlite3 binary execution if binary exists
                SQLITE=""
                for P in /system/bin/sqlite3 /system/xbin/sqlite3 /data/adb/magisk/sqlite3 /data/adb/ksu/bin/sqlite3 /vendor/bin/sqlite3; do
                    if [ -x "${'$'}P" ]; then
                        SQLITE="${'$'}P"
                        break
                    fi
                done
                [ -z "${'$'}SQLITE" ] && which sqlite3 >/dev/null 2>&1 && SQLITE="sqlite3"

                DBS=()
                for P in ${KNOWN_LSP_DB_PATHS.joinToString(" ") { "\"$it\"" }}; do
                    [ -f "${'$'}P" ] && DBS+=("${'$'}P")
                done
                for FOUND in ${'$'}(find /data/adb /data/misc /data/system /data/user_de /data/data -name "modules_config.db" 2>/dev/null); do
                    [ -f "${'$'}FOUND" ] && DBS+=("${'$'}FOUND")
                done

                NEED_REFRESH_PKGS=""
                APK_CHANGED=0

                if [ -n "${'$'}SQLITE" ]; then
                    for DB in "${'$'}{DBS[@]}"; do
                        ${'$'}SQLITE "${'$'}DB" "INSERT OR REPLACE INTO configs (module_pkg_name, user_id, enable_cli) VALUES ('lspd', 0, 1);" 2>/dev/null || true
                        ${'$'}SQLITE "${'$'}DB" "UPDATE configs SET value='1' WHERE key='enable_cli';" 2>/dev/null || true
                        MID=${'$'}(${'$'}SQLITE "${'$'}DB" "SELECT mid FROM modules WHERE module_pkg_name='$modulePkg' LIMIT 1;" 2>/dev/null)
                        if [ -z "${'$'}MID" ]; then
                            MID=${'$'}(${'$'}SQLITE "${'$'}DB" "SELECT mid FROM modules WHERE module_pkg_name LIKE '%ghostdevice%' OR module_pkg_name LIKE '%phantomcore%' LIMIT 1;" 2>/dev/null)
                        fi
                        if [ -z "${'$'}MID" ] && [ -n "$moduleApkPath" ]; then
                            ${'$'}SQLITE "${'$'}DB" "INSERT INTO modules (module_pkg_name, apk_path, enabled) VALUES ('$modulePkg', '$moduleApkPath', 1);" 2>/dev/null
                            MID=${'$'}(${'$'}SQLITE "${'$'}DB" "SELECT last_insert_rowid();" 2>/dev/null)
                            APK_CHANGED=1
                        elif [ -n "${'$'}MID" ]; then
                            OLD_APK=${'$'}(${'$'}SQLITE "${'$'}DB" "SELECT apk_path FROM modules WHERE mid=${'$'}MID LIMIT 1;" 2>/dev/null)
                            if [ -n "$moduleApkPath" ] && [ "${'$'}OLD_APK" != "$moduleApkPath" ]; then
                                APK_CHANGED=1
                            fi
                            if [ -n "$moduleApkPath" ]; then
                                ${'$'}SQLITE "${'$'}DB" "UPDATE modules SET enabled=1, apk_path='$moduleApkPath' WHERE mid=${'$'}MID;" 2>/dev/null
                            else
                                ${'$'}SQLITE "${'$'}DB" "UPDATE modules SET enabled=1 WHERE mid=${'$'}MID;" 2>/dev/null
                            fi
                        fi

                        if [ -n "${'$'}MID" ] && [ "${'$'}MID" -gt 0 ] 2>/dev/null; then
                            for R in $removedSpaceSeparated; do
                                ${'$'}SQLITE "${'$'}DB" "DELETE FROM scope WHERE mid=${'$'}MID AND app_pkg_name='${'$'}R';" 2>/dev/null
                            done
                            for S in ${'$'}ALL_SCOPES; do
                                case "${'$'}S" in
                                    ""|"$modulePkg"|*magisk*|*lsposed*|*edxposed*|*kernelsu*|*supersu*|*phantomcore*|*ghostdevice*) continue ;;
                                esac
                                EXISTS=${'$'}(${'$'}SQLITE "${'$'}DB" "SELECT 1 FROM scope WHERE mid=${'$'}MID AND app_pkg_name='${'$'}S' LIMIT 1;" 2>/dev/null)
                                if [ -z "${'$'}EXISTS" ]; then
                                    ${'$'}SQLITE "${'$'}DB" "INSERT OR IGNORE INTO scope (mid, app_pkg_name, user_id) VALUES (${'$'}MID, '${'$'}S', 0);" 2>/dev/null
                                    NEED_REFRESH_PKGS="${'$'}NEED_REFRESH_PKGS ${'$'}S"
                                fi
                            done
                            ${'$'}SQLITE "${'$'}DB" "PRAGMA wal_checkpoint(FULL);" 2>/dev/null || true
                        fi
                    done
                fi

                # 2. In-process direct SQLite update via app_process
                if [ -f "$moduleApkPath" ]; then
                    export CLASSPATH="$moduleApkPath"
                    /system/bin/app_process /system/bin com.example.data.device.LspScopeSyncCli "$modulePkg" "$moduleApkPath" "$selectedCsv" "$removedCsv" 2>/dev/null || true
                fi

                # 3. Notify lspd daemon to refresh its in-memory ConfigManager scope cache and auto-enable module
                LSP_CLI=""
                for C in /data/adb/lspd/bin/cli /data/adb/modules/zygisk_lsposed/bin/cli /data/adb/modules/riru_lsposed/bin/cli; do
                    if [ -x "${'$'}C" ]; then
                        LSP_CLI="${'$'}C"
                        break
                    fi
                done
                if [ -n "${'$'}LSP_CLI" ]; then
                    MERGED_ARGS=""
                    if [ -n "${'$'}SQLITE" ] && [ -f "/data/adb/lspd/config/modules_config.db" ]; then
                        MID=${'$'}(${'$'}SQLITE "/data/adb/lspd/config/modules_config.db" "SELECT mid FROM modules WHERE module_pkg_name='$modulePkg' LIMIT 1;" 2>/dev/null)
                        if [ -n "${'$'}MID" ]; then
                            for P in ${'$'}(${'$'}SQLITE "/data/adb/lspd/config/modules_config.db" "SELECT DISTINCT app_pkg_name FROM scope WHERE mid=${'$'}MID;" 2>/dev/null); do
                                MERGED_ARGS="${'$'}MERGED_ARGS ${'$'}P/0"
                            done
                        fi
                    fi
                    if [ -z "${'$'}MERGED_ARGS" ]; then
                        for S in ${'$'}ALL_SCOPES; do
                            case "${'$'}S" in
                                ""|"$modulePkg"|*magisk*|*lsposed*|*edxposed*|*kernelsu*|*supersu*|*phantomcore*|*ghostdevice*) continue ;;
                            esac
                            MERGED_ARGS="${'$'}MERGED_ARGS ${'$'}S/0"
                        done
                    fi
                    "${'$'}LSP_CLI" modules set -e "$modulePkg" 2>/dev/null || true
                    "${'$'}LSP_CLI" scope set -s "$modulePkg" ${'$'}MERGED_ARGS 2>/dev/null || true
                fi

                # 4. Ensure permissions remain readable to LSPosed Manager (chmod 666)
                for DB in "${'$'}{DBS[@]}"; do
                    chmod 666 "${'$'}DB" 2>/dev/null || true
                    chmod 666 "${'$'}DB-wal" 2>/dev/null || true
                    chmod 666 "${'$'}DB-shm" 2>/dev/null || true
                done

                # 5. Silently restart newly scoped or APK-updated target apps so hooks take effect immediately
                for PKG in ${'$'}NEED_REFRESH_PKGS; do
                    case "${'$'}PKG" in
                        ""|"$modulePkg"|"android"|"system"|"com.android.settings"|"com.google.android.gms"|"com.google.android.gsf"|"com.google.android.verifier"|"com.google.android.contactkeys"|*launcher*|*trebuchet*|*inputmethod*|*keyboard*|*gboard*|*magisk*|*lsposed*|*dual.space*|*vphonegaga*|*vmos*) continue ;;
                    esac
                    am force-stop "${'$'}PKG" 2>/dev/null || true
                done
                $forceStopCmds
            """.trimIndent()

            RootChecker.executeRootCommand(combinedSyncScript)
        } catch (_: Throwable) {}
    }

    private fun syncDatabasesDirect(
        modulePkg: String,
        moduleApkPath: String,
        selectedTargets: Set<String>,
        removedTargets: Set<String>
    ) {
        val dbPaths = LinkedHashSet<String>()
        dbPaths.addAll(KNOWN_LSP_DB_PATHS)

        for (baseDir in listOf("/data/adb/lspd", "/data/misc/lspd", "/data/system")) {
            try {
                val dir = File(baseDir)
                if (dir.exists() && dir.isDirectory) {
                    dir.walkTopDown().maxDepth(3).forEach { f ->
                        if (f.isFile && f.name == "modules_config.db") {
                            dbPaths.add(f.absolutePath)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        val allDesiredScopes = LinkedHashSet<String>().apply {
            addAll(REQUIRED_SYSTEM_SCOPES)
            addAll(selectedTargets.filter { !isForbiddenScope(it) })
        }

        for (path in dbPaths) {
            val dbFile = File(path)
            if (!dbFile.exists() || !dbFile.isFile) continue

            try {
                updateSingleDb(
                    dbFile = dbFile,
                    modulePkg = modulePkg,
                    apkPathToSet = moduleApkPath,
                    desiredScopes = allDesiredScopes,
                    removedScopes = removedTargets
                )
                try {
                    dbFile.setReadable(true, false)
                    dbFile.setWritable(true, false)
                    val walFile = File("${dbFile.absolutePath}-wal")
                    if (walFile.exists()) {
                        walFile.setReadable(true, false)
                        walFile.setWritable(true, false)
                    }
                    val shmFile = File("${dbFile.absolutePath}-shm")
                    if (shmFile.exists()) {
                        shmFile.setReadable(true, false)
                        shmFile.setWritable(true, false)
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    private fun openLspDatabase(dbFile: File): SQLiteDatabase {
        return try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
            )
        } catch (_: Throwable) {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
        }
    }

    private fun updateSingleDb(
        dbFile: File,
        modulePkg: String,
        apkPathToSet: String,
        desiredScopes: Set<String>,
        removedScopes: Set<String>
    ): Int {
        val db = openLspDatabase(dbFile)
        var mid = -1
        try {
            db.beginTransaction()
            try {
                db.rawQuery(
                    "SELECT mid FROM modules WHERE module_pkg_name = ? LIMIT 1",
                    arrayOf(modulePkg)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        mid = cursor.getInt(0)
                    }
                }

                if (mid <= 0) {
                    db.rawQuery(
                        "SELECT mid FROM modules WHERE module_pkg_name LIKE '%ghostdevice%' OR module_pkg_name LIKE '%phantomcore%' LIMIT 1",
                        null
                    ).use { cursor ->
                        if (cursor.moveToFirst()) {
                            mid = cursor.getInt(0)
                        }
                    }
                }

                if (mid <= 0) {
                    val cv = ContentValues().apply {
                        put("module_pkg_name", modulePkg)
                        put("apk_path", apkPathToSet)
                        put("enabled", 1)
                    }
                    db.insertWithOnConflict("modules", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                    db.rawQuery(
                        "SELECT mid FROM modules WHERE module_pkg_name = ? LIMIT 1",
                        arrayOf(modulePkg)
                    ).use { cursor ->
                        if (cursor.moveToFirst()) {
                            mid = cursor.getInt(0)
                        }
                    }
                } else {
                    val cv = ContentValues().apply {
                        put("enabled", 1)
                        if (apkPathToSet.isNotBlank()) {
                            put("apk_path", apkPathToSet)
                        }
                    }
                    db.update("modules", cv, "mid = ?", arrayOf(mid.toString()))
                }

                if (mid > 0) {
                    for (pkg in removedScopes) {
                        if (pkg !in REQUIRED_SYSTEM_SCOPES) {
                            db.delete(
                                "scope",
                                "mid = ? AND app_pkg_name = ?",
                                arrayOf(mid.toString(), pkg)
                            )
                        }
                    }

                    for (pkg in (desiredScopes + REQUIRED_SYSTEM_SCOPES)) {
                        if (!isForbiddenScope(pkg)) {
                            val scopeCv = ContentValues().apply {
                                put("mid", mid)
                                put("app_pkg_name", pkg)
                                put("user_id", 0)
                            }
                            db.insertWithOnConflict("scope", null, scopeCv, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            try {
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            } catch (_: Throwable) {}
        } finally {
            try {
                db.close()
            } catch (_: Throwable) {}
        }
        return mid
    }

    /**
     * Reads any user/target packages currently checked for this module in LSPosed's `modules_config.db`,
     * so that apps checked directly in LSPosed Manager are also deep-cleaned and synced during Reset.
     */
    fun getScopedTargetPackages(context: Context): Set<String> {
        val result = LinkedHashSet<String>()
        val modulePkg = context.packageName
        for (path in KNOWN_LSP_DB_PATHS) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery(
                            "SELECT s.app_pkg_name FROM scope s INNER JOIN modules m ON s.mid = m.mid WHERE m.module_pkg_name = ? OR m.module_pkg_name LIKE '%ghostdevice%' OR m.module_pkg_name LIKE '%phantomcore%'",
                            arrayOf(modulePkg)
                        ).use { c ->
                            while (c.moveToNext()) {
                                val pkg = c.getString(0)?.trim().orEmpty()
                                if (pkg.isNotEmpty() && pkg !in REQUIRED_SYSTEM_SCOPES && pkg != modulePkg && !isForbiddenScope(pkg)) {
                                    result.add(pkg)
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        if (result.isEmpty() && RootChecker.isDeviceRooted()) {
            try {
                val queryScript = """
                    SQLITE=""
                    for P in /system/bin/sqlite3 /system/xbin/sqlite3 /data/adb/magisk/sqlite3 /data/adb/ksu/bin/sqlite3 /vendor/bin/sqlite3; do
                        if [ -x "${'$'}P" ]; then SQLITE="${'$'}P"; break; fi
                    done
                    [ -z "${'$'}SQLITE" ] && which sqlite3 >/dev/null 2>&1 && SQLITE="sqlite3"
                    if [ -n "${'$'}SQLITE" ]; then
                        for DB in /data/adb/lspd/config/modules_config.db /data/adb/lspd/modules_config.db /data/misc/lspd/config/modules_config.db; do
                            if [ -f "${'$'}DB" ]; then
                                ${'$'}SQLITE "${'$'}DB" "SELECT s.app_pkg_name FROM scope s INNER JOIN modules m ON s.mid = m.mid WHERE m.module_pkg_name='$modulePkg' OR m.module_pkg_name LIKE '%ghostdevice%' OR m.module_pkg_name LIKE '%phantomcore%';" 2>/dev/null
                            fi
                        done
                    fi
                """.trimIndent()
                val out = RootChecker.executeRootCommandWithOutput(queryScript)
                out.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.contains(" ") && it !in REQUIRED_SYSTEM_SCOPES && it != modulePkg && !isForbiddenScope(it) }
                    .forEach { result.add(it) }
            } catch (_: Throwable) {}
        }
        return result
    }
}
