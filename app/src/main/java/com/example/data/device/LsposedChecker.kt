package com.example.data.device

import android.content.Context
import android.content.Intent
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object LsposedChecker {

    @Volatile
    var isLsposedDetected: Boolean = false
        private set

    fun isLsposedActive(context: Context): Boolean {
        // 1. Direct class check (if hooked directly into our process)
        if (checkXposedBridgeClass()) {
            isLsposedDetected = true
            return true
        }

        // 2. PackageManager check for LSPosed / EdXposed manager
        if (checkManagerPackage(context)) {
            isLsposedDetected = true
            return true
        }

        // 3. Known module directories & daemon paths
        if (checkKnownFiles()) {
            isLsposedDetected = true
            return true
        }

        // 4. Root inspection (LSPosed runs in /data/adb/lspd or Zygisk module directory)
        if (checkViaRoot()) {
            isLsposedDetected = true
            return true
        }

        return isLsposedDetected
    }

    private fun checkXposedBridgeClass(): Boolean {
        return try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun checkManagerPackage(context: Context): Boolean {
        val managerPackages = listOf(
            "org.lsposed.manager",
            "org.meowcat.edxposed.manager",
            "de.robv.android.xposed.installer"
        )
        val pm = context.packageManager
        for (pkg in managerPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun checkKnownFiles(): Boolean {
        val paths = arrayOf(
            "/data/adb/lspd",
            "/data/adb/lspd/bin/lspd",
            "/data/adb/modules/zygisk_lsposed",
            "/data/adb/modules/riru_lsposed",
            "/data/adb/modules/lsposed",
            "/data/misc/lspd",
            "/data/system/xlua"
        )
        for (path in paths) {
            try {
                val f = File(path)
                if (f.exists()) return true
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun checkViaRoot(): Boolean {
        val paths = arrayOf(
            "/data/adb/lspd",
            "/data/adb/modules/zygisk_lsposed",
            "/data/adb/modules/riru_lsposed",
            "/data/adb/modules/lsposed"
        )
        for (p in paths) {
            try {
                if (File(p).exists()) return true
            } catch (_: Throwable) {}
        }
        return false
    }

    fun openLsposedManager(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }
}
