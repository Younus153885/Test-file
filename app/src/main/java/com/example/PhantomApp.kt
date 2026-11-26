package com.example

import android.app.Application
import android.util.Log

class PhantomApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.example.ui.i18n.LanguageManager.init(this)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("PhantomApp", "Caught exception on thread ${thread.name}: ${throwable.message}", throwable)
            } catch (_: Throwable) {}

            if (throwable is OutOfMemoryError) {
                defaultHandler?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }

            // CRITICAL: Never let the Main UI Thread terminate after an uncaught exception,
            // otherwise the process stays alive with a dead Looper and triggers "GhostDevice isn't responding" (ANR).
            if (thread === android.os.Looper.getMainLooper().thread) {
                while (true) {
                    try {
                        android.os.Looper.loop()
                    } catch (t: Throwable) {
                        try {
                            Log.e("PhantomApp", "Recovered main looper exception: ${t.message}", t)
                        } catch (_: Throwable) {}
                        if (t is OutOfMemoryError) {
                            defaultHandler?.uncaughtException(thread, t)
                            break
                        }
                    }
                }
            }
        }

        // Automatically sync LSPosed apk_path and 3rd-party user app scopes on startup so updates take effect without reboot
        Thread({
            try {
                com.example.data.device.LspScopeSyncCli.syncTargetScopeWithLsposed(
                    context = this@PhantomApp,
                    selectedTargetPackages = emptyList()
                )
            } catch (_: Throwable) {}
        }, "Phantom-StartupScopeSync").apply { isDaemon = true }.start()
    }
}
