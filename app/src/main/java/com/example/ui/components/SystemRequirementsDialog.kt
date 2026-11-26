package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardElevated
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RedDanger
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.cyberShimmerBorder
import java.io.File

@Composable
fun SystemRequirementsDialog(
    onDismiss: () -> Unit,
    onRequestRoot: ((Boolean) -> Unit) -> Unit = {},
    onForceBypassReset: (() -> Unit)? = null
) {
    val currentLang = com.example.ui.i18n.currentAppLang()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isRootDetected by remember { mutableStateOf(false) }
    var isLsposedActive by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var isBatteryOptIgnored by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(false) }

    fun refreshChecks() {
        isRootDetected = com.example.data.device.RootChecker.isDeviceRooted()
        isLsposedActive = com.example.data.device.LsposedChecker.isLsposedActive(context)
        hasOverlayPermission = Settings.canDrawOverlays(context)

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        isBatteryOptIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true

        hasNotificationPermission = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // Auto-refresh when dialog opens
    LaunchedEffect(Unit) {
        refreshChecks()
    }

    // Auto-refresh instantly whenever user returns from Settings screens
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshChecks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Direct Notification Permission Launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
        refreshChecks()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp)
                .cyberShimmerBorder(width = 1.45.dp, shape = RoundedCornerShape(24.dp), phaseOffset = 0.15f, accentColor = NeonCyan),
            shape = RoundedCornerShape(24.dp),
            color = CyberBg
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Header Row with Refresh
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.size(32.dp))

                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(CyberCardElevated, CircleShape)
                            .cyberShimmerBorder(width = 1.5.dp, shape = CircleShape, phaseOffset = 0.35f, accentColor = NeonCyan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Shield Icon",
                            tint = NeonCyan,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = { refreshChecks() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Checks",
                            tint = NeonCyan
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = com.example.ui.i18n.tr("System Requirements", "সিস্টেম রিকোয়ারমেন্টস", "सिस्टम आवश्यकताएँ", currentLang),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                    maxLines = 1
                )

                Text(
                    text = com.example.ui.i18n.tr(
                        "Live diagnostic check of system privileges & capabilities.",
                        "সিস্টেম প্রিভিলেজ ও ব্যাকগ্রাউন্ড সক্ষমতার লাইভ চেক।",
                        "सिस्टम विशेषाधिकारों और क्षमताओं की लाइव जांच।",
                        currentLang
                    ),
                    fontSize = 11.5.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 1. Root Access Check (Strictly Required - App only works on rooted phones)
                LiveRequirementItem(
                    icon = Icons.Default.Security,
                    title = com.example.ui.i18n.tr("Root Access (su)", "রুট অ্যাক্সেস (su)", "रूट एक्सेस (su)", currentLang),
                    subtitle = if (isRootDetected) {
                        com.example.ui.i18n.tr("Superuser active (UID: 0 granted)", "রুট সুপারইউজার সক্রিয় (UID: 0)", "रूट सुपरयूज़र सक्रिय (UID: 0)", currentLang)
                    } else {
                        com.example.ui.i18n.tr("Required (Magisk / KernelSU)", "রুটেড ফোন প্রয়োজন (Magisk/KernelSU)", "रूटेड फ़ोन आवश्यक (Magisk/KernelSU)", currentLang)
                    },
                    isGranted = isRootDetected,
                    isOptional = false,
                    statusBadge = com.example.ui.i18n.tr("REQUIRED", "আবশ্যক", "आवश्यक", currentLang),
                    actionText = if (!isRootDetected) com.example.ui.i18n.tr("Request su", "su অনুমতি", "su अनुरोध", currentLang) else null,
                    onAction = {
                        onRequestRoot { granted ->
                            if (granted) {
                                isRootDetected = true
                                Toast.makeText(
                                    context,
                                    com.example.ui.i18n.tr("Root access granted via Superuser!", "সুপারইউজারের মাধ্যমে রুট অ্যাক্সেস পাওয়া গেছে!", "सुपरयूज़र के माध्यम से रूट एक्सेस मिल गया!"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    com.example.ui.i18n.tr("Root not found! This app only works on rooted phones.", "রুট পাওয়া যায়নি! এই অ্যাপটি শুধুমাত্র রুটেড ফোনে কাজ করে।", "रूट नहीं मिला! यह ऐप केवल रूटेड फ़ोन पर काम करता है।"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            refreshChecks()
                        }
                    }
                )

                // 2. LSPosed / Xposed Framework Hook Check
                LiveRequirementItem(
                    icon = Icons.Default.Layers,
                    title = "LSPosed / Xposed",
                    subtitle = if (isLsposedActive) {
                        com.example.ui.i18n.tr("Zygisk / Xposed hook active", "LSPosed হুক সক্রিয় আছে", "LSPosed हुक सक्रिय है", currentLang)
                    } else {
                        com.example.ui.i18n.tr("Virtual engine active (Optional)", "ভার্চুয়াল ইঞ্জিন সক্রিয় (ঐচ্ছিক)", "वर्चुअल इंजन सक्रिय (वैकल्पिक)", currentLang)
                    },
                    isGranted = isLsposedActive,
                    isOptional = true,
                    statusBadge = if (isLsposedActive) com.example.ui.i18n.tr("Hook Active", "হুক সক্রিয়", "हुक सक्रिय", currentLang) else null,
                    actionText = if (isLsposedActive) com.example.ui.i18n.tr("Open", "খুলুন", "खोलें", currentLang) else null,
                    onAction = {
                        if (isLsposedActive) {
                            val opened = com.example.data.device.LsposedChecker.openLsposedManager(context)
                            if (!opened) {
                                Toast.makeText(
                                    context,
                                    com.example.ui.i18n.tr("LSPosed active! Make sure target apps are enabled in Scope.", "LSPosed সক্রিয়! নিশ্চিত করুন টার্গেট অ্যাপগুলো Scope-এ চালু আছে।", "LSPosed सक्रिय! सुनिश्चित करें कि टार्गेट ऐप्स Scope में सक्षम हैं।"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                com.example.ui.i18n.tr("LSPosed is optional. In-app virtual spoofing is active.", "LSPosed ঐচ্ছিক। ইন-অ্যাপ ভার্চুয়াল স্পুফিং সক্রিয় আছে।", "LSPosed वैकल्पिक है। इन-ऐप वर्चुअल स्पूफिंग सक्रिय है।"),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )

                // 3. Display over other apps (Draw Overlay)
                LiveRequirementItem(
                    icon = Icons.Default.Layers,
                    title = com.example.ui.i18n.tr("Display Over Apps", "অন্য অ্যাপের ওপর প্রদর্শন", "अन्य ऐप्स पर प्रदर्शन", currentLang),
                    subtitle = if (hasOverlayPermission) {
                        com.example.ui.i18n.tr("Overlay permission active", "ওভারলে পারমিশন সক্রিয়", "ओवरले अनुमति सक्रिय", currentLang)
                    } else {
                        com.example.ui.i18n.tr("Required for floating HUD & timer", "ফ্লোটিং বাটন ও টাইমারের জন্য প্রয়োজন", "फ्लोटिंग बटन के लिए आवश्यक", currentLang)
                    },
                    isGranted = hasOverlayPermission,
                    actionText = if (!hasOverlayPermission) com.example.ui.i18n.tr("Grant", "অনুমতি দিন", "अनुमति दें", currentLang) else null,
                    onAction = {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                context.startActivity(intent)
                            } catch (e2: Exception) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        }
                    }
                )

                // 4. Battery Optimization
                LiveRequirementItem(
                    icon = Icons.Default.BatteryAlert,
                    title = com.example.ui.i18n.tr("Battery Optimization", "ব্যাটারি অপ্টিমাইজেশন", "बैटरी अनुकूलन", currentLang),
                    subtitle = if (isBatteryOptIgnored) {
                        com.example.ui.i18n.tr("Unrestricted background mode", "নিরবচ্ছিন্ন ব্যাকগ্রাউন্ড চলাচল", "अप्रतिबंधित बैकग्राउंड मोड", currentLang)
                    } else {
                        com.example.ui.i18n.tr("Disable for background stability", "ব্যাকগ্রাউন্ডে সচল রাখতে বন্ধ করুন", "बैकग्राउंड स्थिरता के लिए बंद करें", currentLang)
                    },
                    isGranted = isBatteryOptIgnored,
                    actionText = if (!isBatteryOptIgnored) com.example.ui.i18n.tr("Disable", "বন্ধ করুন", "अक्षम करें", currentLang) else null,
                    onAction = {
                        try {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            } catch (e2: Exception) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        }
                    }
                )

                // 5. Notifications
                LiveRequirementItem(
                    icon = Icons.Default.Notifications,
                    title = com.example.ui.i18n.tr("Notifications", "নোটিফিকেশন", "नोटिफिकेशन", currentLang),
                    subtitle = if (hasNotificationPermission) {
                        com.example.ui.i18n.tr("Timer & daemon alerts active", "টাইমার ও ডেমন স্ট্যাটাস সক্রিয়", "टाइमर और स्टेटस अलर्ट सक्रिय", currentLang)
                    } else {
                        com.example.ui.i18n.tr("Enable for live status alerts", "লাইভ স্ট্যাটাস পেতে চালু করুন", "लाइव स्टेटस के लिए चालू करें", currentLang)
                    },
                    isGranted = hasNotificationPermission,
                    actionText = if (!hasNotificationPermission) com.example.ui.i18n.tr("Enable", "চালু করুন", "सक्षम करें", currentLang) else null,
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            try {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("continue_to_dashboard_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = CyberBg
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = com.example.ui.i18n.tr("Continue to Dashboard", "ড্যাশবোর্ডে যান", "डैशबोर्ड पर जाएं", currentLang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (onForceBypassReset != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            onDismiss()
                            onForceBypassReset()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .border(1.dp, NeonCyan, RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberBg,
                            contentColor = NeonCyan
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = com.example.ui.i18n.tr("Bypass & Run (Simulation Mode)", "বাইপাস করে চালান (সিমুলেশন মোড)", "बाईपास करें और चलाएं (सिमुलेशन मोड)", currentLang),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveRequirementItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    isOptional: Boolean = false,
    statusBadge: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val iconColor = when {
        isGranted -> EmeraldGreen
        isOptional -> NeonCyan
        else -> RedDanger
    }
    val iconBg = when {
        isGranted -> EmeraldGreen.copy(alpha = 0.12f)
        isOptional -> NeonCyan.copy(alpha = 0.12f)
        else -> RedDanger.copy(alpha = 0.12f)
    }
    val badgeColor = when {
        isGranted -> EmeraldGreen
        isOptional -> NeonCyan
        else -> RedDanger
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .cyberShimmerBorder(
                width = 1.0.dp,
                shape = RoundedCornerShape(13.dp),
                phaseOffset = (title.hashCode() * 0.13f) % 1f,
                accentColor = when {
                    isGranted -> EmeraldGreen
                    isOptional -> NeonCyan
                    else -> RedDanger
                }
            )
            .clickable(enabled = onAction != null) { onAction?.invoke() },
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = CyberCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(iconBg, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(19.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (statusBadge != null) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .border(0.6.dp, badgeColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = statusBadge,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = badgeColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 10.5.sp,
                    color = when {
                        isGranted -> TextMuted
                        isOptional -> TextSecondary
                        else -> RedDanger.copy(alpha = 0.9f)
                    },
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    lineHeight = 13.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (!isGranted && actionText != null && onAction != null) {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = CyberBg
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = actionText,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else if (isGranted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = EmeraldGreen,
                    modifier = Modifier.size(20.dp)
                )
            } else if (isOptional) {
                Box(
                    modifier = Modifier
                        .background(CyberCardElevated, RoundedCornerShape(6.dp))
                        .border(0.8.dp, CyberBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = com.example.ui.i18n.tr("Optional", "ঐচ্ছিক", "वैकल्पिक"),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "Missing",
                    tint = RedDanger,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun checkIsRooted(): Boolean {
    val paths = arrayOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su"
    )
    for (path in paths) {
        try {
            if (File(path).exists()) return true
        } catch (_: Exception) {}
    }
    return false
}
