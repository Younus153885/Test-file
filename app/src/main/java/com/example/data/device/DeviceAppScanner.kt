package com.example.data.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.local.TargetAppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DeviceAppScanner {
    private val iconMemoryCache = LruCache<String, ImageBitmap>(240)


    fun isForbiddenTargetPackage(pkgName: String): Boolean {
        val lower = pkgName.trim().lowercase()
        return lower.isEmpty() ||
            lower == "android" ||
            lower == "system" ||
            lower == "com.topjohnwu.magisk" ||
            lower == "io.github.vvb2060.magisk" ||
            lower == "io.github.huskydg.magisk" ||
            lower == "org.lsposed.manager" ||
            lower == "io.github.lsposed.manager" ||
            lower.contains("magisk") ||
            lower.contains("lsposed") ||
            lower.contains("edxposed") ||
            lower.contains("kernelsu") ||
            lower.contains("supersu") ||
            lower.contains("phantomcore") ||
            lower.contains("com.aistudio.ghostdevice") ||
            lower.endsWith(".ghostdevice") ||
            lower.contains(".ghostdevice.id") ||
            lower.endsWith(".phantom") ||
            lower.contains(".phantom.id")
    }

    /**
     * Packages that CAN be selected as Target Apps (so Xposed hooks SIM/Phone Number/Identity inside them),
     * but must NEVER have `pm clear` (data wipe) or `am force-stop` executed on them to protect OS stability and Google logins.
     */
    fun isCriticalSystemNoWipePackage(pkgName: String): Boolean {
        val lower = pkgName.trim().lowercase()
        return isForbiddenTargetPackage(lower) ||
            lower == "com.android.systemui" ||
            lower == "com.android.phone" ||
            lower == "com.android.settings" ||
            lower == "com.android.shell" ||
            lower == "com.android.se" ||
            lower == "com.android.nfc" ||
            lower == "com.android.bluetooth" ||
            lower == "com.android.mms.service" ||
            lower == "com.android.server.telecom" ||
            lower == "com.google.android.gms" ||
            lower == "com.google.android.gsf" ||
            lower == "com.google.android.ext.services" ||
            lower == "com.android.vending" ||
            lower.startsWith("com.android.providers.") ||
            lower.contains("networkstack") ||
            lower.contains("captiveportallogin") ||
            lower.contains("tethering") ||
            lower.contains("cellbroadcast") ||
            lower.contains("imsservice")
    }

    fun scanInstalledApps(
        context: Context,
        existingSelections: Map<String, Boolean> = emptyMap()
    ): List<TargetAppEntity> {
        val pm = context.packageManager
        val foundMap = mutableMapOf<String, TargetAppEntity>()

        try {
            // 1. First scan all Launcher / user-facing applications (these are what user recognizes on phone)
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
            for (resolveInfo in resolveInfos) {
                val pkgName = resolveInfo.activityInfo.packageName
                if (pkgName == context.packageName || isForbiddenTargetPackage(pkgName)) continue

                val appName = resolveInfo.loadLabel(pm)?.toString() ?: pkgName
                val appInfo = try {
                    pm.getApplicationInfo(pkgName, 0)
                } catch (e: Exception) {
                    null
                }
                val isSystem = if (appInfo != null) (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 else false
                val versionName = try {
                    pm.getPackageInfo(pkgName, 0).versionName ?: "1.0.0"
                } catch (e: Exception) {
                    "1.0.0"
                }

                val isSelected = existingSelections[pkgName] ?: false

                foundMap[pkgName] = TargetAppEntity(
                    packageName = pkgName,
                    appName = appName,
                    versionName = versionName,
                    isSelected = isSelected,
                    isSystemApp = isSystem,
                    category = if (isSystem) "System App" else "User Installed"
                )
            }

            // 2. Also scan all installed packages to support system packages & background target apps
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getInstalledPackages(0)
            }

            for (pkg in packages) {
                val pkgName = pkg.packageName
                if (pkgName == context.packageName || foundMap.containsKey(pkgName) || isForbiddenTargetPackage(pkgName)) continue

                val appInfo = pkg.applicationInfo ?: continue
                val appName = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    pkgName
                }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val versionName = pkg.versionName ?: "1.0.0"
                val isSelected = existingSelections[pkgName] ?: false

                foundMap[pkgName] = TargetAppEntity(
                    packageName = pkgName,
                    appName = appName,
                    versionName = versionName,
                    isSelected = isSelected,
                    isSystemApp = isSystem,
                    category = if (isSystem) "System Framework" else "User Installed"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sort: selected first, then user installed apps, then alphabetically
        val sortedList = foundMap.values.sortedWith(
            compareByDescending<TargetAppEntity> { it.isSelected }
                .thenBy { it.isSystemApp }
                .thenBy { it.appName.lowercase() }
        )

        if (sortedList.isEmpty()) {
            val popularDefaults = listOf(
                Triple("WhatsApp", "com.whatsapp", "User Installed"),
                Triple("Telegram", "org.telegram.messenger", "User Installed"),
                Triple("Facebook", "com.facebook.katana", "User Installed"),
                Triple("Messenger", "com.facebook.orca", "User Installed"),
                Triple("Instagram", "com.instagram.android", "User Installed"),
                Triple("Chrome", "com.android.chrome", "System App"),
                Triple("YouTube", "com.google.android.youtube", "System App"),
                Triple("TikTok", "com.zhiliaoapp.musically", "User Installed"),
                Triple("Free Fire", "com.dts.freefireth", "User Installed")
            )
            val fallbackList = popularDefaults.map { (name, pkg, cat) ->
                val isSelected = existingSelections[pkg] ?: (pkg == "org.telegram.messenger")
                TargetAppEntity(
                    packageName = pkg,
                    appName = name,
                    versionName = "Latest",
                    isSelected = isSelected,
                    isSystemApp = cat == "System App",
                    category = cat
                )
            }
            return fallbackList.sortedWith(
                compareByDescending<TargetAppEntity> { it.isSelected }
                    .thenBy { it.isSystemApp }
                    .thenBy { it.appName.lowercase() }
            )
        }

        return sortedList
    }

    fun getCachedAppIconBitmap(packageName: String): ImageBitmap? {
        return iconMemoryCache.get(packageName)
    }

    suspend fun loadAppIconBitmap(context: Context, packageName: String): ImageBitmap? {
        iconMemoryCache.get(packageName)?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            getAppIconBitmap(context, packageName)
        }
        return loaded
    }

    fun getAppIconBitmap(context: Context, packageName: String): ImageBitmap? {
        iconMemoryCache.get(packageName)?.let { return it }
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val img = drawableToBitmap(drawable)?.asImageBitmap()
            if (img != null) {
                iconMemoryCache.put(packageName, img)
            }
            img
        } catch (e: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        return try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val bmp = drawable.bitmap
                if (bmp.width <= 96 && bmp.height <= 96) {
                    return bmp
                }
                return Bitmap.createScaledBitmap(bmp, 72, 72, true)
            }
            val rawW = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 72
            val rawH = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 72
            val width = rawW.coerceIn(48, 72)
            val height = rawH.coerceIn(48, 72)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
