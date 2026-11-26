package com.example.data.device

import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootChecker {

    @Volatile
    var isRootGranted: Boolean = false
        private set

    fun isDeviceRooted(): Boolean {
        if (isRootGranted) return true
        return try {
            checkRootBinaries() || checkTestKeys()
        } catch (_: Throwable) {
            false
        }
    }

    fun checkRootBinaries(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
            "/data/adb/ap/bin/su"
        )
        for (path in paths) {
            try {
                val file = File(path)
                if (file.exists()) return true
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun checkSuBinaryCommand(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            var line: String? = null
            val t = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    line = reader.readLine()
                } catch (_: Throwable) {}
            }
            t.start()
            t.join(300)
            if (t.isAlive) t.interrupt()
            line != null && line!!.isNotBlank()
        } catch (_: Throwable) {
            false
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {}
        }
    }

    private fun checkTestKeys(): Boolean {
        return try {
            val buildTags = Build.TAGS
            buildTags != null && buildTags.contains("test-keys")
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Attempts to execute `su -c id` to prompt Magisk / KernelSU / APatch / SuperSU.
     * Returns true if root execution succeeded (UID: 0).
     */
    fun requestRootAccess(): Boolean {
        if (isRootGranted) return true
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            var output: String? = null
            val t = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    output = reader.readLine()
                } catch (_: Throwable) {}
            }
            t.start()
            t.join(1200) // Fast 1.2s timeout so su prompt doesn't block UI
            if (t.isAlive) t.interrupt()

            val granted = output != null && output!!.contains("uid=0")
            if (granted) {
                isRootGranted = true
            }
            granted
        } catch (_: Throwable) {
            false
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Runs a superuser command with full root privilege with timeout protection.
     */
    fun executeRootCommand(command: String): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            java.io.DataOutputStream(process.outputStream).use { os ->
                os.write(command.toByteArray(Charsets.UTF_8))
                os.write("\nexit\n".toByteArray(Charsets.UTF_8))
                os.flush()
            }
            var completed = false
            val t = Thread {
                try {
                    process.waitFor()
                    completed = true
                } catch (_: Throwable) {}
            }
            t.start()
            t.join(5000)
            if (t.isAlive) {
                t.interrupt()
            }
            completed && process.exitValue() == 0
        } catch (_: Throwable) {
            try {
                val fb = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                fb.waitFor()
                fb.exitValue() == 0
            } catch (_: Throwable) {
                false
            }
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Runs a superuser command and returns its standard output as a String.
     */
    fun executeRootCommandWithOutput(command: String): String {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            java.io.DataOutputStream(process.outputStream).use { os ->
                os.write(command.toByteArray(Charsets.UTF_8))
                os.write("\nexit\n".toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val sb = StringBuilder()
            val t = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            sb.appendLine(line)
                        }
                    }
                } catch (_: Throwable) {}
            }
            t.start()
            t.join(3500)
            if (t.isAlive) {
                t.interrupt()
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {}
        }
    }
}


