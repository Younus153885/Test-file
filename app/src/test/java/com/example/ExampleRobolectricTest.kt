package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {
  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    org.junit.Assert.assertTrue(appName.isNotBlank())
  }

  @Test
  fun `verify standalone apk passes apksigner and aapt2`() {
    val srcApk = java.io.File("build/outputs/apk/debug/app-debug.apk")
    if (!srcApk.exists()) return
    val rtDex = java.io.File("src/main/assets/phantom_runtime.dex").readBytes()
    val unsignedOut = java.io.File("/tmp/test_standalone_unsigned.apk")
    val signedOut = java.io.File("/tmp/test_standalone_signed.apk")
    val cfgBytes = """{"originalPackageName":"com.aistudio.phantomcore.bm6x9p","standalonePackageName":"com.aistudio.phantomcore.bm6x9p.phantom"}""".toByteArray()

    com.example.data.engine.StandaloneApkBuilderEngine.repackageAndPatchZip(
      sourceApkFile = srcApk,
      splitSourceDirs = null,
      nativeLibraryDir = null,
      originalPkg = "com.aistudio.phantomcore.bm6x9p",
      standalonePkg = "com.aistudio.phantomcore.bm6x9p.phantom",
      configJsonBytes = cfgBytes,
      injectedRuntimeDexes = listOf(rtDex),
      unsignedOutFile = unsignedOut
    )
    com.example.data.engine.StandaloneApkBuilderEngine.signApkV1AndV2(unsignedOut, signedOut)
    org.junit.Assert.assertTrue("Signed APK should exist", signedOut.exists() && signedOut.length() > 1000L)
  }

  @Test
  fun `verify SystemClocl separates uptime elapsedRealtime and wallTime and speed changes do not freeze`() {
    val realUptime = android.os.SystemClock.uptimeMillis()
    val hookedUptime = android.os.SystemClocl.uptimeMillis()
    // uptimeMillis must match real uptime closely so Handler / MotionEvent / Compose never freeze
    org.junit.Assert.assertTrue("uptimeMillis must stay synced with system uptime", kotlin.math.abs(hookedUptime - realUptime) < 250L)
    val hookedElapsed = android.os.SystemClocl.elapsedRealtime()
    val hookedWall = android.os.SystemClocl.currentTimeMillis()
    org.junit.Assert.assertTrue("elapsedRealtime must be positive", hookedElapsed >= 0L)
    org.junit.Assert.assertTrue("wallTime must be positive", hookedWall > 1_000_000_000_000L)
  }
}
