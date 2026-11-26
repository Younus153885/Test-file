package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.device.DeviceAppScanner
import com.example.data.device.RootChecker
import com.example.data.local.TargetAppEntity
import com.example.ui.TargetAppFilter
import com.example.ui.WalkJoystickState
import com.example.ui.i18n.currentAppLang
import com.example.ui.i18n.tr
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardElevated
import com.example.ui.theme.CyberMagenta
import com.example.ui.theme.CyberPurple
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.LocalCyberShimmerPhaseState
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.buildCometColors
import com.example.ui.theme.cyberShimmerBorder
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TargetAppsScreen(
    apps: List<TargetAppEntity>,
    selectedCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    currentFilter: TargetAppFilter,
    onFilterChange: (TargetAppFilter) -> Unit,
    isScanning: Boolean,
    onRefreshDeviceApps: () -> Unit,
    onToggleAppSelection: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onAddCustomApp: (String, String) -> Unit,
    isGlobalSpoofEnabled: Boolean = false,
    onToggleGlobalSpoof: () -> Unit = {},
    rootBypassPackages: Set<String> = emptySet(),
    onToggleRootBypass: (String) -> Unit = {},
    stepWalkPackages: Set<String> = emptySet(),
    onToggleStepWalk: (String) -> Unit = {},
    speedHackPackages: Set<String> = emptySet(),
    onToggleSpeedHack: (String) -> Unit = {},
    adBypassPackages: Set<String> = emptySet(),
    onToggleAdBypass: (String) -> Unit = {},
    standaloneApkPackages: Set<String> = emptySet(),
    multiInstallApkPackages: Set<String> = emptySet(),
    replaceMainApkPackages: Set<String> = emptySet(),
    standaloneArtifacts: Map<String, com.example.data.engine.StandaloneApkArtifact> = emptyMap(),
    onToggleStandaloneApk: (String) -> Unit = {},
    onSelectStandaloneMode: (String, Boolean) -> Unit = { _, _ -> },
    onToggleMultiInstallApk: (String) -> Unit = {},
    onInstallStandaloneApk: (String) -> Unit = {},
    onShareStandaloneApk: (String) -> Unit = {},
    onRebuildStandaloneApk: (String) -> Unit = {},
    walkJoystickState: WalkJoystickState = WalkJoystickState(),
    onUpdateWalkJoystick: (Float, Float, Boolean) -> Unit = { _, _, _ -> },
    onToggleWalkExpanded: () -> Unit = {},
    onToggleWalkAutoHold: () -> Unit = {},
    onAddInstantSteps: (Int) -> Unit = {},
    onResetWalkSteps: () -> Unit = {},
    onToggleNftAutoValidation: () -> Unit = {},
    onShiftValidationZone: () -> Unit = {},
    onToggleVolumeSpeedMode: () -> Unit = {},
    onStepAppSpeed: (Boolean) -> Unit = {},
    onSetAppSpeed: (Float) -> Unit = {},
    onResetAppSpeed: () -> Unit = {},
    isFloatingJoystickRunning: Boolean = false,
    onToggleFloatingJoystick: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingStandaloneModeApp by remember { mutableStateOf<TargetAppEntity?>(null) }
    val installUiState by com.example.data.engine.StandaloneApkBuilderEngine.installUiState.collectAsState()
    currentAppLang()

    fun deepCleanPackages(packages: List<String>, label: String) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val safePackages = packages.filter {
                    it != "android" && it != context.packageName
                }
                if (safePackages.isNotEmpty() && RootChecker.isDeviceRooted()) {
                    for (pkg in safePackages) {
                        RootChecker.executeRootCommand(
                            """
                            am force-stop $pkg 2>/dev/null
                            am kill $pkg 2>/dev/null
                            killall -9 $pkg 2>/dev/null || true
                            pm clear --user 0 $pkg 2>/dev/null || pm clear $pkg 2>/dev/null
                            for BASE in "/data/data/$pkg" "/data/user/0/$pkg" "/data/user_de/0/$pkg"; do
                                if [ -d "${'$'}BASE" ]; then
                                    rm -rf "${'$'}BASE/cache" "${'$'}BASE/code_cache" "${'$'}BASE/shared_prefs" "${'$'}BASE/databases" "${'$'}BASE/files" "${'$'}BASE/no_backup" "${'$'}BASE/app_webview" "${'$'}BASE/app_chrome" "${'$'}BASE/app_textures" "${'$'}BASE/app_unity" "${'$'}BASE/il2cpp" "${'$'}BASE/.phantom_identity.json" 2>/dev/null
                                    find "${'$'}BASE" -mindepth 1 -maxdepth 1 ! -name "lib" -exec rm -rf {} + 2>/dev/null
                                    if [ -f /data/local/tmp/phantom_identity.json ]; then
                                        PKG_UID=${'$'}(stat -c '%u:%g' "${'$'}BASE" 2>/dev/null)
                                        cp -f /data/local/tmp/phantom_identity.json "${'$'}BASE/.phantom_identity.json" 2>/dev/null
                                        if [ -n "${'$'}PKG_UID" ]; then
                                            chown "${'$'}PKG_UID" "${'$'}BASE/.phantom_identity.json" 2>/dev/null
                                        fi
                                        chmod 644 "${'$'}BASE/.phantom_identity.json" 2>/dev/null
                                        chcon u:object_r:app_data_file:s0 "${'$'}BASE/.phantom_identity.json" 2>/dev/null || true
                                    fi
                                fi
                            done
                            rm -rf "/sdcard/Android/data/$pkg" "/storage/emulated/0/Android/data/$pkg" "/data/media/0/Android/data/$pkg" 2>/dev/null
                            rm -rf "/sdcard/Android/media/$pkg" "/storage/emulated/0/Android/media/$pkg" "/data/media/0/Android/media/$pkg" 2>/dev/null
                            am force-stop $pkg 2>/dev/null
                            am kill $pkg 2>/dev/null
                            """.trimIndent()
                        )
                    }
                    val savedIdentity = com.example.data.engine.ResetExecutionEngine.readSavedIdentity(
                        context,
                        com.example.data.device.RealDeviceReader.getDynamicFallbackIdentity(context)
                    )
                    val allSelectedPkgs = apps.filter { it.isSelected }.map { it.packageName }
                    com.example.data.engine.ResetExecutionEngine.writeXposedIdentityFile(
                        identity = savedIdentity,
                        context = context,
                        isGlobalSpoofEnabled = isGlobalSpoofEnabled,
                        targetPackageNames = allSelectedPkgs,
                        rootBypassPackageNames = rootBypassPackages.toList(),
                        stepWalkPackageNames = stepWalkPackages.toList(),
                        speedHackPackageNames = speedHackPackages.toList(),
                        adBypassPackageNames = adBypassPackages.toList(),
                        applyTimezoneBroadcast = false
                    )
                }
            }
            Toast.makeText(
                context,
                tr(
                    "$label previous data wiped & fresh identity injected (Deep Cleaned)!",
                    "$label এর সমস্ত আগের ডাটা মুছে নতুন আইডেন্টিটি যুক্ত করা হয়েছে (Deep Cleaned)!",
                    "$label का सारा पुराना डेटा मिटाकर नई पहचान जोड़ी गई (Deep Cleaned)!"
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(horizontal = 14.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Compact, neatly aligned Top Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr("Target Applications", "টার্গেট অ্যাপ্লিকেশন", "टारगेट ऐप्स"),
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Uniform 34.dp Refresh Icon Button
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(CyberCardElevated, RoundedCornerShape(8.dp))
                        .cyberShimmerBorder(1.2.dp, RoundedCornerShape(8.dp), phaseOffset = 0.1f)
                        .clickable { onRefreshDeviceApps() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = NeonCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Scan Phone Apps",
                            tint = NeonCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Uniform 34.dp "+ Add" Button
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .background(CyberCardElevated, RoundedCornerShape(8.dp))
                        .cyberShimmerBorder(1.2.dp, RoundedCornerShape(8.dp), phaseOffset = 0.28f)
                        .clickable { showAddDialog = true }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = tr("Add", "যুক্ত করুন", "जोड़ें"),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        // Full-width single-line subtitle so it never breaks awkwardly
        Text(
            text = tr(
                "Real apps detected on this phone for sandboxed reset",
                "এই ফোনের আসল অ্যাপগুলোর জন্য স্যান্ডবক্স রিসেট ও সুরক্ষা",
                "इस फ़ोन पर सैंडबॉक्स रीसेट के लिए पाए गए असली ऐप्स"
            ),
            fontSize = 11.5.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Compact Search Bar (42.dp height)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(CyberCard, RoundedCornerShape(12.dp))
                .cyberShimmerBorder(1.3.dp, RoundedCornerShape(12.dp), phaseOffset = 0.4f)
                .padding(horizontal = 12.dp)
                .testTag("search_target_apps_input"),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = tr("Search apps...", "অ্যাপ খুঁজুন...", "ऐप्स खोजें..."),
                            fontSize = 12.5.sp,
                            color = TextMuted
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 12.5.sp
                        ),
                        cursorBrush = SolidColor(NeonCyan),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = TextMuted,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onSearchQueryChange("") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(9.dp))

        // Compact Filter Chips Row
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChipItem(
                    label = tr("System Apps", "সিস্টেম অ্যাপ", "सिस्टम ऐप्स"),
                    isSelected = currentFilter == TargetAppFilter.SYSTEM_APPS,
                    onClick = {
                        onFilterChange(
                            if (currentFilter == TargetAppFilter.SYSTEM_APPS) TargetAppFilter.ALL else TargetAppFilter.SYSTEM_APPS
                        )
                    }
                )
            }
            item {
                FilterChipItem(
                    label = tr("User Apps", "ইউজার অ্যাপ", "यूज़र ऐप्स"),
                    isSelected = currentFilter == TargetAppFilter.USER_APPS,
                    onClick = {
                        onFilterChange(
                            if (currentFilter == TargetAppFilter.USER_APPS) TargetAppFilter.ALL else TargetAppFilter.USER_APPS
                        )
                    }
                )
            }
            item {
                FilterChipItem(
                    label = tr("Selected ($selectedCount)", "সিলেক্টেড ($selectedCount)", "चयनित ($selectedCount)"),
                    isSelected = currentFilter == TargetAppFilter.SELECTED_ONLY,
                    onClick = {
                        onFilterChange(
                            if (currentFilter == TargetAppFilter.SELECTED_ONLY) TargetAppFilter.ALL else TargetAppFilter.SELECTED_ONLY
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status & Select/Deselect All Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr(
                    "$selectedCount Selected / ${apps.size} apps",
                    "$selectedCount সিলেক্টেড / ${apps.size}টি অ্যাপ",
                    "$selectedCount चयनित / ${apps.size} ऐप्स"
                ),
                fontSize = 11.5.sp,
                color = TextMuted,
                fontWeight = FontWeight.Medium
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tr("Select All", "সব সিলেক্ট", "सभी चुनें"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan,
                    modifier = Modifier
                        .clickable { onSelectAll(true) }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .testTag("select_all_apps_button")
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tr("Deselect All", "সব বাদ দিন", "सभी हटाएं"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberMagenta,
                    modifier = Modifier
                        .clickable { onSelectAll(false) }
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                        .testTag("deselect_all_apps_button")
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Compact Deep Clean Banner Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(35.dp)
                .background(Color(0xFF331438), RoundedCornerShape(9.dp))
                .cyberShimmerBorder(1.3.dp, RoundedCornerShape(9.dp), phaseOffset = 0.55f, accentColor = CyberMagenta)
                .clickable {
                    val selectedPkgs = apps.filter { it.isSelected }.map { it.packageName }
                    deepCleanPackages(
                        selectedPkgs,
                        tr("$selectedCount apps", "${selectedCount}টি অ্যাপ", "$selectedCount ऐप्स")
                    )
                }
                .padding(horizontal = 10.dp)
                .testTag("deep_clean_selected_apps_button"),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Deep Clean Selected Apps",
                    tint = Color(0xFFE066FF),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tr(
                        "Deep Clean (Wipe all previous data of $selectedCount apps)",
                        "ডিপ ক্লিন (${selectedCount}টি অ্যাপের সমস্ত আগের ডাটা মুছুন)",
                        "डीप क्लीन ($selectedCount ऐप्स का सारा पुराना डेटा मिटाएं)"
                    ),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF3C6F1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Target Apps List
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(
                items = apps,
                key = { it.packageName },
                contentType = { "target_app_row" }
            ) { app ->
                val isRootRestrictedBypass = rootBypassPackages.contains(app.packageName)
                val isStepWalkActive = stepWalkPackages.contains(app.packageName)
                val isSpeedHackActive = speedHackPackages.contains(app.packageName)
                val isAdBypassActive = adBypassPackages.contains(app.packageName)
                val isStandaloneApkActive = standaloneApkPackages.contains(app.packageName)
                val isMultiInstallActive = multiInstallApkPackages.contains(app.packageName)
                val isReplaceMainApkActive = replaceMainApkPackages.contains(app.packageName)
                val standaloneArtifact = standaloneArtifacts[app.packageName]
                RealTargetAppRow(
                    app = app,
                    isRootBypassEnabled = isRootRestrictedBypass,
                    isStepWalkEnabled = isStepWalkActive,
                    isSpeedHackEnabled = isSpeedHackActive,
                    isAdBypassEnabled = isAdBypassActive,
                    isStandaloneApkEnabled = isStandaloneApkActive,
                    isMultiInstallEnabled = isMultiInstallActive,
                    isReplaceMainApkSelected = isReplaceMainApkActive,
                    isIdentitySpoofActive = app.isSelected || isGlobalSpoofEnabled,
                    standaloneArtifact = standaloneArtifact,
                    onToggle = { onToggleAppSelection(app.packageName, app.isSelected) },
                    onToggleRootBypass = {
                        val willEnable = !isRootRestrictedBypass
                        onToggleRootBypass(app.packageName)
                        Toast.makeText(
                            context,
                            if (willEnable) {
                                tr(
                                    "${app.appName}: Root & Clone Restriction Bypass enabled!",
                                    "${app.appName}: রুট ও ক্লোন রেস্ট্রিক্ট বাইপাস চালু করা হয়েছে!",
                                    "${app.appName}: रूट और क्लोन प्रतिबंध बाईपास चालू किया गया!"
                                )
                            } else {
                                tr(
                                    "${app.appName}: Root Restriction Bypass disabled.",
                                    "${app.appName}: রুট রেস্ট্রিক্ট বাইপাস বন্ধ করা হয়েছে।",
                                    "${app.appName}: रूट प्रतिबंध बाईपास बंद किया गया।"
                                )
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onToggleStepWalk = {
                        val willEnable = !isStepWalkActive
                        onToggleStepWalk(app.packageName)
                        Toast.makeText(
                            context,
                            if (willEnable) {
                                tr(
                                    "${app.appName}: Fake Location & Step Counter enabled!",
                                    "${app.appName}: ফেক লোকেশন ও স্টেপ কাউন্টার চালু করা হয়েছে!",
                                    "${app.appName}: फेक लोकेशन और स्टेप काउंटर चालू किया गया!"
                                )
                            } else {
                                tr(
                                    "${app.appName}: Fake Location & Step Counter disabled.",
                                    "${app.appName}: ফেক লোকেশন ও স্টেপ কাউন্টার বন্ধ করা হয়েছে।",
                                    "${app.appName}: फेक लोकेशन और स्टेप काउंटर बंद किया गया।"
                                )
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onToggleSpeedHack = {
                        val willEnable = !isSpeedHackActive
                        onToggleSpeedHack(app.packageName)
                        Toast.makeText(
                            context,
                            if (willEnable) {
                                tr(
                                    "${app.appName}: App & Game Speed Controller enabled!",
                                    "${app.appName}: অ্যাপ ও গেম স্পিড কন্ট্রোলার চালু করা হয়েছে!",
                                    "${app.appName}: ऐप और गेम स्पीड कंट्रोलर चालू किया गया!"
                                )
                            } else {
                                tr(
                                    "${app.appName}: App Speed Controller disabled (1.0x Normal).",
                                    "${app.appName}: অ্যাপ স্পিড কন্ট্রোলার বন্ধ করা হয়েছে (1.0x নরমাল)।",
                                    "${app.appName}: ऐप स्पीड कंट्रोलर बंद किया गया (1.0x सामान्य)।"
                                )
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onToggleAdBypass = {
                        val willEnable = !isAdBypassActive
                        onToggleAdBypass(app.packageName)
                        Toast.makeText(
                            context,
                            if (willEnable) {
                                tr(
                                    "${app.appName}: Instant 1s Ad Complete & No-Ad Bypass enabled! Ads will complete silently in 1s without showing.",
                                    "${app.appName}: ইনস্ট্যান্ট ১ সেকেন্ড অ্যাড কমপ্লিট ও অ্যাড স্কিপ চালু! কোনো অ্যাড না দেখিয়েই ১ সেকেন্ডে অ্যাড কমপ্লিট হবে।",
                                    "${app.appName}: इंस्टेंट 1 सेकंड ऐड कंप्लीट और नो-ऐड बाईपास चालू! बिना कोई विज्ञापन दिखाए 1 सेकंड में ऐड पूरा होगा।"
                                )
                            } else {
                                tr(
                                    "${app.appName}: Instant Ad Bypass disabled.",
                                    "${app.appName}: ইনস্ট্যান্ট অ্যাড স্কিপ বন্ধ করা হয়েছে।",
                                    "${app.appName}: इंस्टेंट ऐड बाईपास बंद किया गया।"
                                )
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onToggleStandaloneApk = {
                        val willEnable = !isStandaloneApkActive
                        if (willEnable && !isMultiInstallActive) {
                            pendingStandaloneModeApp = app
                        } else {
                            onToggleStandaloneApk(app.packageName)
                            Toast.makeText(
                                context,
                                if (willEnable) {
                                    tr(
                                        "${app.appName}: Standalone APK Builder enabled! Building package with identity & joystick...",
                                        "${app.appName}: স্ট্যান্ডঅ্যালোন APK বিল্ডার চালু! সব পরিবর্তন ও স্টিকসহ নতুন প্যাকেজ তৈরি হচ্ছে...",
                                        "${app.appName}: स्टैंडअलोन APK बिल्डर चालू! सभी बदलाव और स्टिक के साथ नया पैकेज बन रहा है..."
                                    )
                                } else {
                                    tr(
                                        "${app.appName}: Standalone APK Builder disabled.",
                                        "${app.appName}: স্ট্যান্ডঅ্যালোন APK বিল্ডার বন্ধ করা হয়েছে।",
                                        "${app.appName}: स्टैंडअलोन APK बिल्डर बंद किया गया।"
                                    )
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onOpenStandaloneModeSelector = {
                        pendingStandaloneModeApp = app
                    },
                    onToggleMultiInstallApk = {
                        val willEnable = !isMultiInstallActive
                        onToggleMultiInstallApk(app.packageName)
                        Toast.makeText(
                            context,
                            if (willEnable) {
                                tr(
                                    "${app.appName}: Separate APK Mode (Multi-Install) enabled! Each identity installs as a separate app.",
                                    "${app.appName}: আলাদা APK মোড (Multi-Install) চালু! প্রতিটি ভিন্ন আইডেন্টিটিতে আলাদা APK হিসেবে ইনস্টল হবে।",
                                    "${app.appName}: अलग APK मोड (Multi-Install) चालू! प्रत्येक नई पहचान अलग APK के रूप में इंस्टॉल होगी।"
                                )
                            } else {
                                tr(
                                    "${app.appName}: Separate APK Mode disabled (Standard mode).",
                                    "${app.appName}: আলাদা APK মোড বন্ধ করা হয়েছে (স্বাভাবিক মোড)।",
                                    "${app.appName}: अलग APK मोड बंद किया गया (सामान्य मोड)।"
                                )
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onInstallStandaloneApk = { onInstallStandaloneApk(app.packageName) },
                    onShareStandaloneApk = { onShareStandaloneApk(app.packageName) },
                    onRebuildStandaloneApk = {
                        onRebuildStandaloneApk(app.packageName)
                        Toast.makeText(
                            context,
                            tr(
                                "${app.appName}: Rebuilding APK with latest changes...",
                                "${app.appName}: নতুন পরিবর্তনগুলোসহ পুনরায় APK তৈরি করা হচ্ছে...",
                                "${app.appName}: नए बदलावों के साथ फिर से APK बनाया जा रहा है..."
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onDeepCleanSingle = {
                        deepCleanPackages(listOf(app.packageName), app.appName)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Smart Bottom Circular 360° Joystick & Volume Speed Controller System
        SmartWalkJoystickPanel(
            stepWalkAppsCount = stepWalkPackages.size,
            speedHackAppsCount = speedHackPackages.size,
            state = walkJoystickState,
            onUpdateJoystick = onUpdateWalkJoystick,
            onToggleExpanded = onToggleWalkExpanded,
            onToggleAutoHold = onToggleWalkAutoHold,
            onAddInstantSteps = {
                onAddInstantSteps(100)
                Toast.makeText(
                    context,
                    tr(
                        "+100 steps & location movement added!",
                        "+100 স্টেপ ও লোকেশন মুভমেন্ট যুক্ত করা হয়েছে!",
                        "+100 स्टेप्स और लोकेशन मूवमेंट जोड़ा गया!"
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onResetSteps = {
                onResetWalkSteps()
                Toast.makeText(
                    context,
                    tr(
                        "Step counter reset!",
                        "স্টেপ কাউন্টার রিসেট করা হয়েছে!",
                        "स्टेप काउंटर रीसेट किया गया!"
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onShiftValidationZone = {
                com.example.service.WorldMapPickerOverlay.show(context)
            },
            onToggleVolumeSpeedMode = onToggleVolumeSpeedMode,
            onStepAppSpeed = onStepAppSpeed,
            onSetAppSpeed = onSetAppSpeed,
            onResetAppSpeed = onResetAppSpeed,
            isFloatingJoystickRunning = isFloatingJoystickRunning,
            onToggleFloatingJoystick = onToggleFloatingJoystick
        )

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showAddDialog) {
        AddCustomAppDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, pkg ->
                onAddCustomApp(name, pkg)
                showAddDialog = false
            }
        )
    }

    val modeDialogApp = pendingStandaloneModeApp
    if (modeDialogApp != null) {
        val isActive = standaloneApkPackages.contains(modeDialogApp.packageName)
        val isReplaceSelected = replaceMainApkPackages.contains(modeDialogApp.packageName)
        StandaloneInstallModeDialog(
            app = modeDialogApp,
            isStandaloneActive = isActive,
            isReplaceMainApkSelected = isReplaceSelected,
            onDismiss = { pendingStandaloneModeApp = null },
            onSelectMode = { replaceMain ->
                pendingStandaloneModeApp = null
                onSelectStandaloneMode(modeDialogApp.packageName, replaceMain)
                val modeName = if (replaceMain) "Main Apk Replace" else "Without Main Apk Replace"
                Toast.makeText(
                    context,
                    tr(
                        "${modeDialogApp.appName}: $modeName selected — preparing APK...",
                        "${modeDialogApp.appName}: $modeName সিলেক্ট হয়েছে — APK তৈরি হচ্ছে...",
                        "${modeDialogApp.appName}: $modeName चुना गया — APK तैयार हो रहा है..."
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    val currentInstallState = installUiState
    if (currentInstallState != null) {
        StandaloneInstallStatusDialog(
            state = currentInstallState,
            onDismiss = {
                com.example.data.engine.StandaloneApkBuilderEngine.dismissInstallUiState()
            },
            onOpenInstalledApp = {
                val pkg = currentInstallState.standalonePackageName
                com.example.data.engine.StandaloneApkBuilderEngine.dismissInstallUiState()
                val launched = com.example.data.engine.StandaloneApkBuilderEngine.launchInstalledStandaloneApp(context, pkg)
                if (!launched) {
                    Toast.makeText(
                        context,
                        tr(
                            "Please open ${currentInstallState.appName} from your phone home screen.",
                            "আপনার ফোনের হোম স্ক্রিন থেকে ${currentInstallState.appName} অ্যাপটি ওপেন করুন।",
                            "कृपया अपने फ़ोन की होम स्क्रीन से ${currentInstallState.appName} ऐप खोलें।"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onSwitchToCloneMode = {
                val origPkg = currentInstallState.originalPackageName
                com.example.data.engine.StandaloneApkBuilderEngine.dismissInstallUiState()
                onSelectStandaloneMode(origPkg, false)
                Toast.makeText(
                    context,
                    tr(
                        "${currentInstallState.appName}: Switching to Without Main Apk Replace...",
                        "${currentInstallState.appName}: Without Main Apk Replace মোডে পরিবর্তন হচ্ছে...",
                        "${currentInstallState.appName}: Without Main Apk Replace मोड में बदला जा रहा है..."
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@Composable
private fun SmartWalkJoystickPanel(
    stepWalkAppsCount: Int,
    speedHackAppsCount: Int = 0,
    state: WalkJoystickState,
    onUpdateJoystick: (Float, Float, Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onToggleAutoHold: () -> Unit,
    onAddInstantSteps: () -> Unit,
    onResetSteps: () -> Unit,
    onShiftValidationZone: () -> Unit = {},
    onToggleVolumeSpeedMode: () -> Unit = {},
    onStepAppSpeed: (Boolean) -> Unit = {},
    onSetAppSpeed: (Float) -> Unit = {},
    onResetAppSpeed: () -> Unit = {},
    isFloatingJoystickRunning: Boolean = false,
    onToggleFloatingJoystick: () -> Unit = {}
) {
    val speedKmh = state.speedMps * 3.6f
    val isMoving = state.magnitude > 0.03f && (state.isDragging || state.isAutoHoldLocked)
    val compassLabel = remember(state.bearingDegrees) {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((state.bearingDegrees + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
        "${dirs[idx]} ${state.bearingDegrees.roundToInt()}°"
    }
    val speedMultText = remember(state.appSpeedMultiplier) {
        com.example.service.WalkBridge.formatSpeedMultiplier(state.appSpeedMultiplier)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .cyberShimmerBorder(
                width = 1.5.dp,
                shape = RoundedCornerShape(14.dp),
                phaseOffset = 0.16f,
                accentColor = if (state.isVolumeSpeedMode) CyberMagenta else if (isMoving) NeonCyan else if (stepWalkAppsCount > 0 || speedHackAppsCount > 0) EmeraldGreen else null
            )
            .testTag("smart_walk_joystick_panel"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isVolumeSpeedMode) Color(0xFF1F122B) else if (isMoving) Color(0xFF0E212B) else Color(0xFF0E1522)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 8.dp)
        ) {
            // Compact Header Row (Always visible at bottom of Target Apps screen)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(
                                if (state.isVolumeSpeedMode) CyberMagenta.copy(alpha = 0.24f)
                                else if (isMoving) NeonCyan.copy(alpha = 0.22f)
                                else if (stepWalkAppsCount > 0) EmeraldGreen.copy(alpha = 0.18f)
                                else CyberCardElevated,
                                CircleShape
                            )
                            .border(
                                1.dp,
                                if (state.isVolumeSpeedMode) CyberMagenta else if (isMoving) NeonCyan else if (stepWalkAppsCount > 0) EmeraldGreen else CyberBorder,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                            contentDescription = "Step Walk & Speed Controller",
                            tint = if (state.isVolumeSpeedMode) CyberMagenta else if (isMoving) NeonCyan else if (stepWalkAppsCount > 0) EmeraldGreen else TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (state.isVolumeSpeedMode) {
                                    tr("Volume Speed & Location Controller", "ভলিউম স্পিড ও লোকেশন কন্ট্রোলার", "वॉल्यूम स्पीड व लोकेशन कंट्रोलर")
                                } else {
                                    tr("Fake Location & Step Controller", "ফেক লোকেশন ও স্টেপ কন্ট্রোলার", "फेक लोकेशन व स्टेप कंट्रोलर")
                                },
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (stepWalkAppsCount > 0) EmeraldGreen.copy(alpha = 0.2f) else CyberCardElevated,
                                        RoundedCornerShape(5.dp)
                                    )
                                    .border(
                                        0.5.dp,
                                        if (stepWalkAppsCount > 0) EmeraldGreen else CyberBorder,
                                        RoundedCornerShape(5.dp)
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "$stepWalkAppsCount Step",
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (stepWalkAppsCount > 0) EmeraldGreen else TextMuted,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (speedHackAppsCount > 0 || state.isVolumeSpeedMode) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            CyberMagenta.copy(alpha = 0.22f),
                                            RoundedCornerShape(5.dp)
                                        )
                                        .border(0.5.dp, CyberMagenta, RoundedCornerShape(5.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "$speedMultText ($speedHackAppsCount)",
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFB8F0),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (state.isVolumeSpeedMode) {
                                tr(
                                    "Volume Speed Mode: $speedMultText • Turn dial or tap +/- for Fast/Slow",
                                    "ভলিউম স্পিড মোড: $speedMultText • চাকা ঘুরিয়ে বা +/- চেপে ফাস্ট/স্লো করুন",
                                    "वॉल्यूम स्पीड मोड: $speedMultText • डायल घुमाएं या +/- दबाकर फास्ट/स्लो करें"
                                )
                            } else if (isMoving) {
                                tr(
                                    "Moving: $compassLabel • ${String.format(Locale.US, "%.1f", speedKmh)} km/h • Steps: ${state.stepsCount}",
                                    "চলমান: $compassLabel • ${String.format(Locale.US, "%.1f", speedKmh)} km/h • স্টেপ: ${state.stepsCount}",
                                    "गतिशील: $compassLabel • ${String.format(Locale.US, "%.1f", speedKmh)} km/h • स्टेप्स: ${state.stepsCount}"
                                )
                            } else {
                                tr(
                                    "Steps: ${state.stepsCount} • Dist: ${String.format(Locale.US, "%.0f", state.distanceMeters)}m • Drag stick to walk",
                                    "স্টেপ: ${state.stepsCount} • দূরত্ব: ${String.format(Locale.US, "%.0f", state.distanceMeters)}m • স্টিক টেনে হাঁটুন",
                                    "स्टेप्स: ${state.stepsCount} • दूरी: ${String.format(Locale.US, "%.0f", state.distanceMeters)}m • चलने के लिए स्टिक खींचें"
                                )
                            },
                            fontSize = 9.5.sp,
                            color = if (state.isVolumeSpeedMode) Color(0xFFFFB8F0) else if (isMoving) NeonCyan else TextSecondary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .height(25.dp)
                            .background(CyberCardElevated, RoundedCornerShape(7.dp))
                            .cyberShimmerBorder(
                                width = 1.2.dp,
                                shape = RoundedCornerShape(7.dp),
                                phaseOffset = 0.22f,
                                accentColor = if (state.isVolumeSpeedMode) CyberMagenta else NeonCyan
                            )
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (state.isExpanded) {
                                    tr("Collapse", "ছোট করুন", "छोटा करें")
                                } else {
                                    tr("Expand", "বড় করুন", "बड़ा करें")
                                },
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = if (state.isExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                contentDescription = "Toggle Joystick Panel",
                                tint = NeonCyan,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = state.isExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Floating Joystick Overlay Toggle Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isFloatingJoystickRunning) Color(0xFF132832) else Color(0xFF101724),
                                RoundedCornerShape(10.dp)
                            )
                            .cyberShimmerBorder(
                                width = 1.2.dp,
                                shape = RoundedCornerShape(10.dp),
                                phaseOffset = 0.34f,
                                accentColor = if (isFloatingJoystickRunning) EmeraldGreen else null
                            )
                            .clickable { onToggleFloatingJoystick() }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .testTag("toggle_floating_joystick_button")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            if (isFloatingJoystickRunning) EmeraldGreen.copy(alpha = 0.25f) else CyberCardElevated,
                                            CircleShape
                                        )
                                        .border(
                                            1.dp,
                                            if (isFloatingJoystickRunning) EmeraldGreen else CyberBorder,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "🖥️",
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = if (isFloatingJoystickRunning) {
                                            tr(
                                                "Floating Joystick & Volume Speed Active (On-Screen)",
                                                "ফ্লোটিং জয়স্টিক ও ভলিউম স্পিড সক্রিয় (অন-স্ক্রিন)",
                                                "फ़्लोटिंग जॉयस्टिक व वॉल्यूम स्पीड सक्रिय (ऑन-स्क्रीन)"
                                            )
                                        } else {
                                            tr(
                                                "Enable Floating Joystick & Volume Speed",
                                                "ফ্লোটিং জয়স্টিক ও ভলিউম স্পিড চালু করুন",
                                                "फ़्लोटिंग जॉयस्टिक व वॉल्यूम स्पीड चालू करें"
                                            )
                                        },
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isFloatingJoystickRunning) EmeraldGreen else TextPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = tr(
                                            "Use floating stick & volume speed dial over any app or game",
                                            "যেকোনো অ্যাপ বা গেমের ওপর ভাসমান স্টিক ও ভলিউম স্পিড ডায়াল ব্যবহার করুন",
                                            "किसी भी ऐप या गेम के ऊपर फ़्लोटिंग स्टिक और वॉल्यूम स्पीड डायल का उपयोग करें"
                                        ),
                                        fontSize = 9.sp,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isFloatingJoystickRunning) EmeraldGreen.copy(alpha = 0.22f) else NeonCyan.copy(alpha = 0.18f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        0.5.dp,
                                        if (isFloatingJoystickRunning) EmeraldGreen else NeonCyan,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (isFloatingJoystickRunning) {
                                        tr("Turn OFF", "বন্ধ করুন", "बंद करें")
                                    } else {
                                        tr("Turn ON", "চালু করুন", "चालू करें")
                                    },
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isFloatingJoystickRunning) EmeraldGreen else NeonCyan
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(7.dp))

                    // Empty Slot Area Switcher + Dual Mode Switcher (Stick Mode ⇄ Volume Speed Mode)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Prominent World Map & Satellite Picker Button
                        Box(
                            modifier = Modifier
                                .weight(1.12f)
                                .height(34.dp)
                                .background(Color(0xFF0E2536), RoundedCornerShape(9.dp))
                                .cyberShimmerBorder(1.2.dp, RoundedCornerShape(9.dp), phaseOffset = 0.45f)
                                .clickable { onShiftValidationZone() }
                                .padding(horizontal = 8.dp)
                                .testTag("shift_empty_slot_zone_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = tr("🗺️ Change Map Location", "🗺️ ম্যাপ পরিবর্তন করুন", "🗺️ मैप लोकेशन बदलें"),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeonCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Mode Switcher Toggle Button (Stick ⇄ Volume Speed)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .background(
                                    if (state.isVolumeSpeedMode) Color(0xFF3A1242) else Color(0xFF141E30),
                                    RoundedCornerShape(9.dp)
                                )
                                .cyberShimmerBorder(
                                    width = 1.2.dp,
                                    shape = RoundedCornerShape(9.dp),
                                    phaseOffset = 0.62f,
                                    accentColor = if (state.isVolumeSpeedMode) CyberMagenta else null
                                )
                                .clickable { onToggleVolumeSpeedMode() }
                                .padding(horizontal = 8.dp)
                                .testTag("toggle_volume_speed_mode_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (state.isVolumeSpeedMode) {
                                    tr("🔊 Volume Speed ON ($speedMultText)", "🔊 ভলিউম স্পিড ON ($speedMultText)", "🔊 वॉल्यूम स्पीड ON ($speedMultText)")
                                } else {
                                    tr("🔊 Stick ➔ Volume Mode", "🔊 স্টিক ➔ ভলিউম মোড", "🔊 स्टिक ➔ वॉल्यूम मोड")
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (state.isVolumeSpeedMode) Color(0xFFFFC4F4) else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Left: Dual-Mode 360° Circular Radar Joystick OR Rotary Volume Speed Dial
                        CircularWalkJoystickPad(
                            knobX = state.knobX,
                            knobY = state.knobY,
                            magnitude = state.magnitude,
                            isAutoHoldLocked = state.isAutoHoldLocked,
                            isVolumeSpeedMode = state.isVolumeSpeedMode,
                            appSpeedMultiplier = state.appSpeedMultiplier,
                            onVectorChange = onUpdateJoystick
                        )

                        // Right: Telemetry Readouts & Smart Controls
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (state.isVolumeSpeedMode) {
                                // Volume Speed Mode Readout & Plus/Minus/Slider Controls
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(Color(0xFF170B22), RoundedCornerShape(8.dp))
                                            .cyberShimmerBorder(0.9.dp, RoundedCornerShape(8.dp), phaseOffset = 0.25f, accentColor = CyberMagenta)
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = tr("APP/GAME SPEED", "অ্যাপ/গেম স্পিড (SPEED)", "ऐप/गेम स्पीड (SPEED)"),
                                                fontSize = 8.5.sp,
                                                color = Color(0xFFE5A4DB),
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = speedMultText,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFFFF66E5),
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(Color(0xFF0A101A), RoundedCornerShape(8.dp))
                                            .cyberShimmerBorder(0.9.dp, RoundedCornerShape(8.dp), phaseOffset = 0.55f, accentColor = NeonCyan)
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = tr("ACTIVE SPEED APPS", "সক্রিয় স্পিড অ্যাপ", "सक्रिय स्पीड ऐप्स"),
                                                fontSize = 8.5.sp,
                                                color = TextMuted,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "$speedHackAppsCount App",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = NeonCyan,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }

                                // Interactive Volume Speed Bar (-1000x Slowest -> +1000x Fastest)
                                val volProgress = remember(state.appSpeedMultiplier) {
                                    com.example.service.WalkBridge.speedMultiplierToProgress(state.appSpeedMultiplier)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0A101A), RoundedCornerShape(8.dp))
                                        .cyberShimmerBorder(0.9.dp, RoundedCornerShape(8.dp), phaseOffset = 0.72f)
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = tr("-1000x (Slow)", "-1000x (স্লো)", "-1000x (स्लो)"),
                                                fontSize = 8.sp,
                                                color = NeonCyan,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                            Text(
                                                text = tr("Tap +/- or drag bar", "+/- বা বার টানুন", "+/- या बार खींचें"),
                                                fontSize = 8.sp,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = tr("+1000x (Fast)", "+1000x (ফাস্ট)", "+1000x (फास्ट)"),
                                                fontSize = 8.sp,
                                                color = CyberMagenta,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(5.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(14.dp)
                                                .background(Color(0xFF192436), RoundedCornerShape(7.dp))
                                                .pointerInput(Unit) {
                                                    awaitEachGesture {
                                                        val down = awaitFirstDown(requireUnconsumed = false)
                                                        val w = size.width.coerceAtLeast(1).toFloat()
                                                        fun updateFromX(xPos: Float) {
                                                            val p = (xPos / w).coerceIn(0f, 1f)
                                                            val mult = com.example.service.WalkBridge.progressToSpeedMultiplier(p)
                                                            onSetAppSpeed(mult)
                                                        }
                                                        updateFromX(down.position.x)
                                                        var pointerId = down.id
                                                        while (true) {
                                                            val ev = awaitPointerEvent()
                                                            val ch = ev.changes.firstOrNull { it.id == pointerId }
                                                                ?: ev.changes.firstOrNull()
                                                            if (ch == null || !ch.pressed) break
                                                            pointerId = ch.id
                                                            ch.consume()
                                                            updateFromX(ch.position.x)
                                                        }
                                                    }
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(volProgress.coerceIn(0.04f, 1f))
                                                    .height(14.dp)
                                                    .background(
                                                        Brush.horizontalGradient(
                                                            listOf(NeonCyan, EmeraldGreen, CyberMagenta)
                                                        ),
                                                        RoundedCornerShape(7.dp)
                                                    )
                                            )
                                        }
                                    }
                                }

                                // Plus (+1) / Minus (-1) / 1.0x Normal Speed Buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(28.dp)
                                            .background(Color(0xFF102436), RoundedCornerShape(7.dp))
                                            .cyberShimmerBorder(1.1.dp, RoundedCornerShape(7.dp), phaseOffset = 0.15f, accentColor = NeonCyan)
                                            .clickable { onStepAppSpeed(false) }
                                            .testTag("speed_minus_slow_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = tr("➖ Slow (-1)", "➖ স্লো (-1)", "➖ स्लो (-1)"),
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = NeonCyan,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(0.95f)
                                            .height(28.dp)
                                            .background(CyberCardElevated, RoundedCornerShape(7.dp))
                                            .cyberShimmerBorder(1.1.dp, RoundedCornerShape(7.dp), phaseOffset = 0.45f, accentColor = EmeraldGreen)
                                            .clickable { onResetAppSpeed() }
                                            .testTag("speed_reset_normal_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = tr("1.0x Normal", "1.0x নরমাল", "1.0x सामान्य"),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = EmeraldGreen,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(28.dp)
                                            .background(Color(0xFF35123D), RoundedCornerShape(7.dp))
                                            .cyberShimmerBorder(1.1.dp, RoundedCornerShape(7.dp), phaseOffset = 0.75f, accentColor = CyberMagenta)
                                            .clickable { onStepAppSpeed(true) }
                                            .testTag("speed_plus_fast_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = tr("➕ Fast (+1)", "➕ ফাস্ট (+1)", "➕ फास्ट (+1)"),
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFFFF85EC),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            } else {
                                // Step & Distance Telemetry Box (Normal Walk Radar Mode)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(Color(0xFF0A101A), RoundedCornerShape(8.dp))
                                            .cyberShimmerBorder(0.9.dp, RoundedCornerShape(8.dp), phaseOffset = 0.28f, accentColor = NeonCyan)
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = tr("TOTAL STEPS", "মোট স্টেপ (STEPS)", "कुल स्टेप्स (STEPS)"),
                                                fontSize = 8.5.sp,
                                                color = TextMuted,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${state.stepsCount}",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = NeonCyan,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(Color(0xFF0A101A), RoundedCornerShape(8.dp))
                                            .cyberShimmerBorder(0.9.dp, RoundedCornerShape(8.dp), phaseOffset = 0.58f, accentColor = EmeraldGreen)
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = tr("SPEED & PACE", "গতি ও দিক (SPEED)", "गति व दिशा (SPEED)"),
                                                fontSize = 8.5.sp,
                                                color = TextMuted,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${String.format(Locale.US, "%.1f", speedKmh)} km/h",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = EmeraldGreen,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                // Live Coordinates & Zone Readout
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0A101A), RoundedCornerShape(8.dp))
                                        .cyberShimmerBorder(0.9.dp, RoundedCornerShape(8.dp), phaseOffset = 0.82f)
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.MyLocation,
                                                    contentDescription = null,
                                                    tint = NeonCyan,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = tr("Heading: $compassLabel", "দিক: $compassLabel", "दिशा: $compassLabel"),
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                            }
                                            Text(
                                                text = "${String.format(Locale.US, "%.2f", state.distanceMeters / 1000.0)} km",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EmeraldGreen,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "GPS: ${String.format(Locale.US, "%.5f", state.liveLatitude)}, ${String.format(Locale.US, "%.5f", state.liveLongitude)}",
                                            fontSize = 9.5.sp,
                                            color = TextSecondary,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Smart Action Buttons: Auto-Hold Lock | +100 Steps | Reset
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Auto-Hold Lock Toggle
                                    Box(
                                        modifier = Modifier
                                            .weight(1.15f)
                                            .height(28.dp)
                                            .background(
                                                if (state.isAutoHoldLocked) CyberMagenta.copy(alpha = 0.22f) else CyberCardElevated,
                                                RoundedCornerShape(7.dp)
                                            )
                                            .cyberShimmerBorder(
                                                width = 1.2.dp,
                                                shape = RoundedCornerShape(7.dp),
                                                phaseOffset = 0.18f,
                                                accentColor = if (state.isAutoHoldLocked) CyberMagenta else NeonCyan
                                            )
                                            .clickable { onToggleAutoHold() }
                                            .padding(horizontal = 6.dp)
                                            .testTag("walk_auto_hold_lock_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (state.isAutoHoldLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                                contentDescription = "Auto Hold Lock",
                                                tint = if (state.isAutoHoldLocked) CyberMagenta else NeonCyan,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = if (state.isAutoHoldLocked) {
                                                    tr("Lock ON", "লক ON", "लॉक ON")
                                                } else {
                                                    tr("Hold Lock", "হোল্ড লক", "होल्ड लॉक")
                                                },
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (state.isAutoHoldLocked) CyberMagenta else TextPrimary,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    // +100 Steps Quick Boost
                                    Box(
                                        modifier = Modifier
                                            .weight(0.95f)
                                            .height(28.dp)
                                            .background(EmeraldGreen.copy(alpha = 0.18f), RoundedCornerShape(7.dp))
                                            .cyberShimmerBorder(
                                                width = 1.1.dp,
                                                shape = RoundedCornerShape(7.dp),
                                                phaseOffset = 0.48f,
                                                accentColor = EmeraldGreen
                                            )
                                            .clickable { onAddInstantSteps() }
                                            .padding(horizontal = 5.dp)
                                            .testTag("walk_add_100_steps_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = tr("+100 Steps", "+100 স্টেপ", "+100 स्टेप्स"),
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = EmeraldGreen,
                                            maxLines = 1
                                        )
                                    }

                                    // Reset Counter Button
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(CyberCardElevated, RoundedCornerShape(7.dp))
                                            .cyberShimmerBorder(
                                                width = 1.1.dp,
                                                shape = RoundedCornerShape(7.dp),
                                                phaseOffset = 0.78f
                                            )
                                            .clickable { onResetSteps() }
                                            .testTag("walk_reset_steps_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RestartAlt,
                                            contentDescription = "Reset Steps",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CircularWalkJoystickPad(
    knobX: Float,
    knobY: Float,
    magnitude: Float,
    isAutoHoldLocked: Boolean,
    isVolumeSpeedMode: Boolean = false,
    appSpeedMultiplier: Float = 1.0f,
    onVectorChange: (Float, Float, Boolean) -> Unit
) {
    val currentOnVectorChange by rememberUpdatedState(onVectorChange)
    val phaseState = LocalCyberShimmerPhaseState.current
    val ringAccentArgb = when {
        isVolumeSpeedMode -> CyberMagenta
        isAutoHoldLocked || magnitude > 0.7f -> CyberMagenta
        else -> NeonCyan
    }.toArgb()
    val ringCometData = remember(ringAccentArgb) {
        val colors = buildCometColors(0.92f, Color(ringAccentArgb))
        val stops = floatArrayOf(0f, 0.825f, 0.865f, 0.905f, 0.940f, 0.968f, 0.986f, 0.995f, 1f)
        val sweep = android.graphics.SweepGradient(0f, 0f, colors, stops)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            shader = sweep
        }
        Triple(paint, sweep, android.graphics.Matrix())
    }
    val volumeBgBrush = remember {
        Brush.radialGradient(colors = listOf(Color(0xFF2B1136), Color(0xFF0A0F1A)))
    }
    val walkBgBrush = remember {
        Brush.radialGradient(colors = listOf(Color(0xFF142838), Color(0xFF09111B)))
    }
    val speedRingDashEffect = remember {
        PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    Box(
        modifier = Modifier
            .size(124.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val radiusPx = (size.width.coerceAtMost(size.height) / 2f).coerceAtLeast(1f)
                    val center = Offset(size.width / 2f, size.height / 2f)

                    fun emitOffset(pos: Offset, dragging: Boolean) {
                        val dx = (pos.x - center.x) / (radiusPx * 0.82f)
                        val dy = (pos.y - center.y) / (radiusPx * 0.82f)
                        currentOnVectorChange(dx, dy, dragging)
                    }

                    emitOffset(down.position, true)
                    var pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) {
                            emitOffset(center, false)
                            break
                        }
                        pointerId = change.id
                        change.consume()
                        emitOffset(change.position, true)
                    }
                }
            }
            .testTag("circular_walk_joystick_pad"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h / 2f)
            val outerRadius = (w.coerceAtMost(h) / 2f) - 4.dp.toPx()
            val maxKnobRadius = outerRadius * 0.78f

            val (ringPaint, ringSweep, ringMatrix) = ringCometData
            val phase = ((phaseState.value + 0.12f) % 1f + 1f) % 1f
            ringPaint.strokeWidth = 1.4.dp.toPx()
            ringMatrix.setRotate(phase * 360f)
            ringMatrix.postTranslate(center.x, center.y)
            ringSweep.setLocalMatrix(ringMatrix)

            if (isVolumeSpeedMode) {
                // Volume Speed Rotary Dial Mode
                drawCircle(
                    brush = volumeBgBrush,
                    radius = outerRadius,
                    center = center
                )

                // Shimmering outer ring for Volume Speed Dial (draw-phase only)
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawCircle(center.x, center.y, outerRadius, ringPaint)
                }

                val progress = com.example.service.WalkBridge.speedMultiplierToProgress(appSpeedMultiplier)
                val arcRadius = outerRadius * 0.82f
                val arcSize = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f)
                val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)

                // Background 270° track
                drawArc(
                    color = Color(0xFF283A52),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                )

                val dialColor = when {
                    appSpeedMultiplier < 0.95f -> NeonCyan
                    appSpeedMultiplier > 1.05f -> CyberMagenta
                    else -> EmeraldGreen
                }
                // Active volume sweep
                drawArc(
                    color = dialColor,
                    startAngle = 135f,
                    sweepAngle = (270f * progress).coerceIn(4f, 270f),
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                )

                val angleDeg = 135f + 270f * progress
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val knobPos = Offset(
                    x = center.x + (kotlin.math.cos(angleRad) * arcRadius).toFloat(),
                    y = center.y + (kotlin.math.sin(angleRad) * arcRadius).toFloat()
                )
                drawCircle(
                    color = dialColor.copy(alpha = 0.3f),
                    radius = 13.dp.toPx(),
                    center = knobPos
                )
                drawCircle(
                    color = dialColor,
                    radius = 8.5.dp.toPx(),
                    center = knobPos
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = knobPos
                )
            } else {
                // Outer circular radar background
                drawCircle(
                    brush = walkBgBrush,
                    radius = outerRadius,
                    center = center
                )

                // Shimmering outer border ring for 360° Walk Radar (draw-phase only)
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawCircle(center.x, center.y, outerRadius, ringPaint)
                }

                // Concentric speed rings (33% Walk, 66% Jog, 100% Fast Run)
                drawCircle(
                    color = NeonCyan.copy(alpha = 0.22f),
                    radius = maxKnobRadius * 0.68f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = speedRingDashEffect)
                )
                drawCircle(
                    color = EmeraldGreen.copy(alpha = 0.22f),
                    radius = maxKnobRadius * 0.36f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // Crosshair axis lines
                drawLine(
                    color = Color(0xFF1E3248),
                    start = Offset(center.x, center.y - outerRadius + 6.dp.toPx()),
                    end = Offset(center.x, center.y + outerRadius - 6.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color(0xFF1E3248),
                    start = Offset(center.x - outerRadius + 6.dp.toPx(), center.y),
                    end = Offset(center.x + outerRadius - 6.dp.toPx(), center.y),
                    strokeWidth = 1.dp.toPx()
                )

                // Knob position
                val knobOffset = Offset(
                    x = center.x + (knobX * maxKnobRadius),
                    y = center.y + (knobY * maxKnobRadius)
                )

                // Directional vector line from center to knob
                if (magnitude > 0.02f) {
                    drawLine(
                        color = if (magnitude > 0.7f) CyberMagenta else NeonCyan,
                        start = center,
                        end = knobOffset,
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Center anchor dot
                drawCircle(
                    color = TextSecondary.copy(alpha = 0.6f),
                    radius = 3.dp.toPx(),
                    center = center
                )

                // Outer glow around draggable thumb knob
                val thumbColor = when {
                    magnitude > 0.72f -> CyberMagenta
                    magnitude > 0.03f -> NeonCyan
                    else -> EmeraldGreen
                }
                drawCircle(
                    color = thumbColor.copy(alpha = 0.28f),
                    radius = 15.dp.toPx(),
                    center = knobOffset
                )
                // Solid thumb knob
                drawCircle(
                    color = thumbColor,
                    radius = 10.dp.toPx(),
                    center = knobOffset
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.5.dp.toPx(),
                    center = knobOffset
                )
            }
        }

        if (isVolumeSpeedMode) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = com.example.service.WalkBridge.formatSpeedMultiplier(appSpeedMultiplier),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = when {
                        appSpeedMultiplier < 0.95f -> "SLOW VOL"
                        appSpeedMultiplier > 1.05f -> "FAST VOL"
                        else -> "NORMAL"
                    },
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFB5EE)
                )
            }
        } else {
            // Cardinal N / S / E / W indicators around the circle edge
            Text(
                text = "N",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NeonCyan,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 5.dp)
            )
            Text(
                text = "S",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
            )
            Text(
                text = "W",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
            )
            Text(
                text = "E",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
            )
        }
    }
}

@Composable
private fun FilterChipItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .background(
                if (isSelected) NeonCyan.copy(alpha = 0.2f) else CyberCard,
                RoundedCornerShape(8.dp)
            )
            .cyberShimmerBorder(
                width = if (isSelected) 1.4.dp else 1.1.dp,
                shape = RoundedCornerShape(8.dp),
                phaseOffset = ((label.hashCode() and 0xFF) / 255f),
                accentColor = if (isSelected) NeonCyan else null
            )
            .clickable { onClick() }
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) NeonCyan else TextPrimary
        )
    }
}

@Composable
private fun RealTargetAppRow(
    app: TargetAppEntity,
    isRootBypassEnabled: Boolean,
    isStepWalkEnabled: Boolean = false,
    isSpeedHackEnabled: Boolean = false,
    isAdBypassEnabled: Boolean = false,
    isStandaloneApkEnabled: Boolean = false,
    isMultiInstallEnabled: Boolean = false,
    isReplaceMainApkSelected: Boolean = false,
    isIdentitySpoofActive: Boolean = false,
    standaloneArtifact: com.example.data.engine.StandaloneApkArtifact? = null,
    onToggle: () -> Unit,
    onToggleRootBypass: () -> Unit,
    onToggleStepWalk: () -> Unit = {},
    onToggleSpeedHack: () -> Unit = {},
    onToggleAdBypass: () -> Unit = {},
    onToggleStandaloneApk: () -> Unit = {},
    onOpenStandaloneModeSelector: () -> Unit = {},
    onToggleMultiInstallApk: () -> Unit = {},
    onInstallStandaloneApk: () -> Unit = {},
    onShareStandaloneApk: () -> Unit = {},
    onRebuildStandaloneApk: () -> Unit = {},
    onDeepCleanSingle: () -> Unit
) {
    val context = LocalContext.current
    val appIcon by produceState<ImageBitmap?>(
        initialValue = remember(app.packageName) { DeviceAppScanner.getCachedAppIconBitmap(app.packageName) },
        key1 = app.packageName
    ) {
        if (value == null) {
            value = DeviceAppScanner.loadAppIconBitmap(context, app.packageName)
        }
    }
    val goldAccent = Color(0xFFFFB300)
    val adSkipAccent = Color(0xFFFF6D00)
    val appPhase = remember(app.packageName) { ((app.packageName.hashCode() and 0xFF) / 255f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .cyberShimmerBorder(
                width = 1.4.dp,
                shape = RoundedCornerShape(12.dp),
                phaseOffset = appPhase,
                accentColor = when {
                    isStandaloneApkEnabled -> goldAccent
                    app.isSelected -> NeonCyan
                    isAdBypassEnabled -> adSkipAccent
                    isSpeedHackEnabled -> CyberMagenta
                    isRootBypassEnabled -> EmeraldGreen
                    isStepWalkEnabled -> NeonCyan
                    else -> null
                }
            )
            .clickable { onToggle() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isStandaloneApkEnabled -> Color(0xFF231D10)
                app.isSelected -> Color(0xFF13222E)
                isAdBypassEnabled -> Color(0xFF241710)
                isSpeedHackEnabled -> Color(0xFF211229)
                isRootBypassEnabled -> Color(0xFF10261F)
                isStepWalkEnabled -> Color(0xFF10202E)
                else -> CyberCard
            }
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Row 1: App Icon + Full App Title & Package Name + Deep Clean Icon + Selection Checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 9.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App Icon Box (36.dp) with vivid rotating comet border
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            when {
                                isStandaloneApkEnabled -> Color(0xFF382C12)
                                app.isSelected -> Color(0xFF1B3B3E)
                                isSpeedHackEnabled -> Color(0xFF33153D)
                                isRootBypassEnabled -> Color(0xFF15382B)
                                isStepWalkEnabled -> Color(0xFF152E3E)
                                else -> CyberBg
                            },
                            RoundedCornerShape(9.dp)
                        )
                        .cyberShimmerBorder(
                            width = 1.8.dp,
                            shape = RoundedCornerShape(9.dp),
                            phaseOffset = appPhase + 0.15f,
                            accentColor = NeonCyan
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val iconBmp = appIcon
                    if (iconBmp != null) {
                        Image(
                            bitmap = iconBmp,
                            contentDescription = app.appName,
                            modifier = Modifier
                                .size(25.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else {
                        Icon(
                            imageVector = if (app.isSystemApp) Icons.Default.Android else Icons.Default.Apps,
                            contentDescription = null,
                            tint = if (isStandaloneApkEnabled) goldAccent else if (app.isSelected || isStepWalkEnabled) NeonCyan else if (isSpeedHackEnabled) CyberMagenta else if (isRootBypassEnabled) EmeraldGreen else TextSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(9.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.appName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (app.isSystemApp) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF0A0E16), RoundedCornerShape(4.dp))
                                    .border(0.5.dp, CyberBorder, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "SYS",
                                    fontSize = 7.5.sp,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(1.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.packageName,
                            fontSize = 10.sp,
                            color = TextMuted,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "v${app.versionName}",
                            fontSize = 9.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Compact Single-App Deep Clean Icon Button
                IconButton(
                    onClick = onDeepCleanSingle,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Deep Clean ${app.appName}",
                        tint = Color(0xFFD84B9E),
                        modifier = Modifier.size(16.dp)
                    )
                }

                Checkbox(
                    checked = app.isSelected,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.scale(0.85f),
                    colors = CheckboxDefaults.colors(
                        checkedColor = NeonCyan,
                        uncheckedColor = TextMuted,
                        checkmarkColor = CyberBg
                    )
                )
            }

            // Row 2: Dedicated Action Chips Row (📦APK, ➕ Multi-Install, ⚡Spd, 🚶Stp, 🛡️ Root Hide)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Per-App Standalone APK Builder Toggle Button ("📦APK" / "📦APK ON")
                Box(
                    modifier = Modifier
                        .height(27.dp)
                        .background(
                            if (isStandaloneApkEnabled) goldAccent.copy(alpha = 0.25f) else Color(0xFF141B29),
                            RoundedCornerShape(6.dp)
                        )
                        .cyberShimmerBorder(
                            width = 0.9.dp,
                            shape = RoundedCornerShape(6.dp),
                            phaseOffset = appPhase + 0.08f,
                            accentColor = if (isStandaloneApkEnabled) goldAccent else null
                        )
                        .clickable { onToggleStandaloneApk() }
                        .padding(horizontal = 7.dp)
                        .testTag("standalone_apk_button_${app.packageName}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isStandaloneApkEnabled) "📦 APK ON" else "📦 APK",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isStandaloneApkEnabled) Color(0xFFFFD54F) else TextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // 2. Per-App Multi-Install Toggle Button ("➕ Multi-Install" / "➕ Multi-Install ON")
                Box(
                    modifier = Modifier
                        .height(27.dp)
                        .background(
                            if (isMultiInstallEnabled) EmeraldGreen.copy(alpha = 0.26f) else Color(0xFF141B29),
                            RoundedCornerShape(6.dp)
                        )
                        .cyberShimmerBorder(
                            width = 0.9.dp,
                            shape = RoundedCornerShape(6.dp),
                            phaseOffset = appPhase + 0.2f,
                            accentColor = if (isMultiInstallEnabled) EmeraldGreen else null
                        )
                        .clickable { onToggleMultiInstallApk() }
                        .padding(horizontal = 7.dp)
                        .testTag("multi_install_apk_button_${app.packageName}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isMultiInstallEnabled) "➕ Multi-Install ON" else "➕ Multi-Install",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isMultiInstallEnabled) EmeraldGreen else TextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // 3. Per-App Speed Controller Toggle Button (Fast / Slow Volume Speed)
                Box(
                    modifier = Modifier
                        .height(27.dp)
                        .background(
                            if (isSpeedHackEnabled) CyberMagenta.copy(alpha = 0.24f) else Color(0xFF141B29),
                            RoundedCornerShape(6.dp)
                        )
                        .cyberShimmerBorder(
                            width = 0.9.dp,
                            shape = RoundedCornerShape(6.dp),
                            phaseOffset = appPhase + 0.34f,
                            accentColor = if (isSpeedHackEnabled) CyberMagenta else null
                        )
                        .clickable { onToggleSpeedHack() }
                        .padding(horizontal = 7.dp)
                        .testTag("speed_hack_button_${app.packageName}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSpeedHackEnabled) "⚡ ON" else "⚡ Spd",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isSpeedHackEnabled) Color(0xFFFF85EC) else TextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // 4. Per-App Fake Location & Step Walk Toggle Button
                Box(
                    modifier = Modifier
                        .height(27.dp)
                        .background(
                            if (isStepWalkEnabled) NeonCyan.copy(alpha = 0.22f) else Color(0xFF141B29),
                            RoundedCornerShape(6.dp)
                        )
                        .cyberShimmerBorder(
                            width = 0.9.dp,
                            shape = RoundedCornerShape(6.dp),
                            phaseOffset = appPhase + 0.48f,
                            accentColor = if (isStepWalkEnabled) NeonCyan else null
                        )
                        .clickable { onToggleStepWalk() }
                        .padding(horizontal = 7.dp)
                        .testTag("step_walk_button_${app.packageName}"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                            contentDescription = "Fake Location & Step Counter",
                            tint = if (isStepWalkEnabled) NeonCyan else TextMuted,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = if (isStepWalkEnabled) "ON" else "Stp",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isStepWalkEnabled) NeonCyan else TextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // 5. Per-App "Root Hide" Toggle Button
                Box(
                    modifier = Modifier
                        .height(27.dp)
                        .background(
                            if (isRootBypassEnabled) EmeraldGreen.copy(alpha = 0.22f) else Color(0xFF141B29),
                            RoundedCornerShape(6.dp)
                        )
                        .cyberShimmerBorder(
                            width = 0.9.dp,
                            shape = RoundedCornerShape(6.dp),
                            phaseOffset = appPhase + 0.62f,
                            accentColor = if (isRootBypassEnabled) EmeraldGreen else null
                        )
                        .clickable { onToggleRootBypass() }
                        .padding(horizontal = 7.dp)
                        .testTag("root_bypass_button_${app.packageName}"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Root Hide Bypass",
                            tint = if (isRootBypassEnabled) EmeraldGreen else TextMuted,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (isRootBypassEnabled) "Root Hide ON" else "Root Hide",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRootBypassEnabled) EmeraldGreen else TextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // 6. Per-App Instant 1s Ad Complete & No-Ad Bypass Toggle Button ("🚫 Ad Skip" / "🚫 Ad Skip ON")
                Box(
                    modifier = Modifier
                        .height(27.dp)
                        .background(
                            if (isAdBypassEnabled) adSkipAccent.copy(alpha = 0.25f) else Color(0xFF141B29),
                            RoundedCornerShape(6.dp)
                        )
                        .cyberShimmerBorder(
                            width = 0.9.dp,
                            shape = RoundedCornerShape(6.dp),
                            phaseOffset = appPhase + 0.76f,
                            accentColor = if (isAdBypassEnabled) adSkipAccent else null
                        )
                        .clickable { onToggleAdBypass() }
                        .padding(horizontal = 7.dp)
                        .testTag("ad_bypass_button_${app.packageName}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isAdBypassEnabled) {
                            tr("🚫 Ad Skip ON", "🚫 অ্যাড স্কিপ চালু", "🚫 ऐड स्किप चालू")
                        } else {
                            tr("🚫 Ad Skip", "🚫 অ্যাড স্কিপ", "🚫 ऐड स्किप")
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isAdBypassEnabled) Color(0xFFFFAB40) else TextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Expandable Standalone APK Builder Panel (Shown ONLY when 📦APK ON is enabled for this app)
            AnimatedVisibility(visible = isStandaloneApkEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF17130B))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    val isBuilding = standaloneArtifact?.isBuilding == true
                    val isReady = standaloneArtifact != null && !isBuilding && standaloneArtifact.fileSizeBytes > 0L
                    val buildPercent = (standaloneArtifact?.progressPercent ?: 0).coerceIn(0, 100)
                    val displayStandalonePkg = remember(
                        standaloneArtifact?.standalonePackageName,
                        app.packageName,
                        isMultiInstallEnabled,
                        isReplaceMainApkSelected
                    ) {
                        val fallbackPkg = if (!isMultiInstallEnabled && isReplaceMainApkSelected) {
                            app.packageName
                        } else {
                            "${app.packageName}.ghostdevice"
                        }
                        (standaloneArtifact?.standalonePackageName?.ifBlank { fallbackPkg } ?: fallbackPkg)
                            .replace(".phantom", ".ghostdevice")
                    }
                    val activeInstallState by com.example.data.engine.StandaloneApkBuilderEngine.installUiState.collectAsState()
                    val cardInstallState = activeInstallState?.takeIf {
                        it.originalPackageName == app.packageName
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isBuilding || cardInstallState?.isInstalling == true) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 2.dp,
                                    color = if (cardInstallState?.isInstalling == true) EmeraldGreen else goldAccent
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = when {
                                            cardInstallState?.isCompleted == true -> tr(
                                                "🎉 Installed (100%): $displayStandalonePkg",
                                                "🎉 ইনস্টল সম্পূর্ণ (১০০%): $displayStandalonePkg",
                                                "🎉 इंस्टॉल पूरा (100%): $displayStandalonePkg"
                                            )
                                            cardInstallState?.isInstalling == true -> cardInstallState.stageTitle
                                            isBuilding -> standaloneArtifact?.statusMessage?.takeIf { it.isNotBlank() } ?: tr(
                                                "📦 Building package...",
                                                "📦 নতুন প্যাকেজ তৈরি হচ্ছে...",
                                                "📦 नया पैकेज बन रहा है..."
                                            )
                                            isReady -> tr(
                                                "✅ Package Ready: $displayStandalonePkg (${com.example.data.engine.StandaloneApkBuilderEngine.formatFileSize(standaloneArtifact?.fileSizeBytes ?: 0L)})",
                                                "✅ নতুন প্যাকেজ প্রস্তুত: $displayStandalonePkg (${com.example.data.engine.StandaloneApkBuilderEngine.formatFileSize(standaloneArtifact?.fileSizeBytes ?: 0L)})",
                                                "✅ नया पैकेज तैयार: $displayStandalonePkg (${com.example.data.engine.StandaloneApkBuilderEngine.formatFileSize(standaloneArtifact?.fileSizeBytes ?: 0L)})"
                                            )
                                            else -> standaloneArtifact?.statusMessage ?: tr(
                                                "📦 Tap Update to build Standalone APK",
                                                "📦 স্ট্যান্ডঅ্যালোন APK তৈরি করতে আপডেট চাপুন",
                                                "📦 स्टैंडअलोन APK बनाने के लिए अपडेट दबाएं"
                                            )
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = when {
                                            cardInstallState?.isCompleted == true -> EmeraldGreen
                                            cardInstallState?.isInstalling == true -> NeonCyan
                                            isReady -> Color(0xFFFFD54F)
                                            else -> TextPrimary
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isBuilding || cardInstallState != null) {
                                        val shownPct = cardInstallState?.progressPercent ?: buildPercent
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "$shownPct%",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = when {
                                                cardInstallState?.isCompleted == true -> EmeraldGreen
                                                cardInstallState?.isInstalling == true -> NeonCyan
                                                else -> goldAccent
                                            },
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    } else if (!isReady) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(EmeraldGreen.copy(alpha = 0.22f), RoundedCornerShape(5.dp))
                                                .border(0.9.dp, EmeraldGreen, RoundedCornerShape(5.dp))
                                                .clickable { onRebuildStandaloneApk() }
                                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                                .testTag("header_confirm_build_${app.packageName}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = tr("⚡ Build Now", "⚡ বিল্ড করুন", "⚡ अभी बिल्ड करें"),
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = EmeraldGreen,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                                if (isBuilding || cardInstallState != null) {
                                    val shownPct = (cardInstallState?.progressPercent ?: buildPercent).coerceIn(0, 100)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { shownPct / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = when {
                                            cardInstallState?.isCompleted == true -> EmeraldGreen
                                            cardInstallState?.isInstalling == true -> NeonCyan
                                            else -> goldAccent
                                        },
                                        trackColor = Color(0xFF2B2416)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                val needsSyncWarning = isReady && standaloneArtifact != null && (
                                    standaloneArtifact.bakedSpeedEnabled != isSpeedHackEnabled ||
                                        standaloneArtifact.bakedStepWalkEnabled != isStepWalkEnabled ||
                                        standaloneArtifact.bakedRootBypassEnabled != isRootBypassEnabled ||
                                        standaloneArtifact.bakedAdBypassEnabled != isAdBypassEnabled ||
                                        standaloneArtifact.bakedIdentitySpoofEnabled != isIdentitySpoofActive ||
                                        standaloneArtifact.bakedLanguageCode != currentAppLang().code
                                )
                                val activeHoldModes = buildList {
                                    if (standaloneArtifact?.bakedSpeedEnabled == true) add(tr("⚡Speed Hold", "⚡স্পিড হোল্ড", "⚡स्पीड होल्ड"))
                                    if (standaloneArtifact?.bakedStepWalkEnabled == true) add(tr("🚶Location-Step Hold", "🚶লোকেশন-স্টেপ হোল্ড", "🚶लोकेशन-स्टेप होल्ड"))
                                    if (standaloneArtifact?.bakedRootBypassEnabled == true) add("🛡️Root Hide")
                                    if (standaloneArtifact?.bakedAdBypassEnabled == true) add(tr("🚫1s Ad Skip", "🚫১ সেকেন্ড অ্যাড স্কিপ", "🚫1s ऐड स्किप"))
                                }.joinToString(" + ").ifBlank {
                                    tr("🔄 Clear & Unique Identity Mode", "🔄 ক্লিয়ার ও ইউনিক আইডেন্টিটি মোড", "🔄 क्लियर और यूनिक पहचान मोड")
                                }
                                Text(
                                    text = when {
                                        needsSyncWarning -> tr(
                                            "⚠️ New toggles not yet baked into APK — tap 🔄 Rebuild or 📤 Share",
                                            "⚠️ নতুন চালু সিস্টেম APK-তে বাকি আছে — 🔄 আপডেট বা 📤 শেয়ার চাপুন",
                                            "⚠️ नए चालू सिस्टम APK में बाकी हैं — 🔄 अपडेट या 📤 शेयर दबाएं"
                                        )
                                        isReady -> tr(
                                            "Hold Mode: [$activeHoldModes] • Built-in floating 🔄 Clear & License linked",
                                            "হোল্ডিং মোড: [$activeHoldModes] • অ্যাপের ভেতরে গোল বাটনে 🔄 ক্লিয়ার ও লাইসেন্স সংযুক্ত",
                                            "होल्डिंग मोड: [$activeHoldModes] • ऐप के अंदर गोल बटन में 🔄 क्लियर और लाइसेंस जुड़ा है"
                                        )
                                        else -> tr(
                                            "Even with ⚡Spd / 🚶Stp OFF, built-in floating button gives 🔄 Clear & Unique Device",
                                            "⚡Spd / 🚶Stp বন্ধ থাকলেও অ্যাপের ভেতরে গোল বাটনে 🔄 ক্লিয়ার ও ইউনিক ডিভাইস পাবেন",
                                            "⚡Spd / 🚶Stp बंद होने पर भी ऐप के अंदर गोल बटन से 🔄 क्लियर व यूनिक डिवाइस मिलेगा"
                                        )
                                    },
                                    fontSize = 8.5.sp,
                                    fontWeight = if (needsSyncWarning) FontWeight.Bold else FontWeight.Normal,
                                    color = if (needsSyncWarning) Color(0xFFFFB74D) else TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Selected Install Mode Indicator & Switcher when Multi-Install is OFF
                    if (!isMultiInstallEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val modeAccent = if (isReplaceMainApkSelected) NeonCyan else goldAccent
                        val activeModeTitle = if (isReplaceMainApkSelected) {
                            "Main Apk Replace"
                        } else {
                            "Without Main Apk Replace"
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    modeAccent.copy(alpha = 0.13f),
                                    RoundedCornerShape(7.dp)
                                )
                                .border(
                                    1.dp,
                                    modeAccent.copy(alpha = 0.72f),
                                    RoundedCornerShape(7.dp)
                                )
                                .clickable { onOpenStandaloneModeSelector() }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .testTag("standalone_mode_indicator_${app.packageName}")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = if (isReplaceMainApkSelected) "🔄" else "📦",
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = activeModeTitle,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = modeAccent,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF141E30), RoundedCornerShape(5.dp))
                                        .border(0.7.dp, modeAccent.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = tr("⇄ Change", "⇄ পরিবর্তন", "⇄ बदलें"),
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    // Multi-Install ("Separate APK per Identity") Toggle Row inside Builder Panel
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isMultiInstallEnabled) EmeraldGreen.copy(alpha = 0.16f) else Color(0xFF1D1A12),
                                RoundedCornerShape(7.dp)
                            )
                            .border(
                                1.dp,
                                if (isMultiInstallEnabled) EmeraldGreen else CyberBorder,
                                RoundedCornerShape(7.dp)
                            )
                            .clickable { onToggleMultiInstallApk() }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("multi_install_panel_toggle_${app.packageName}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isMultiInstallEnabled) {
                                    tr(
                                        "➕ Multi-Install (Separate APK per Identity): ON",
                                        "➕ Multi-Install (আলাদা আইডেন্টিটিতে আলাদা APK): চালু",
                                        "➕ Multi-Install (अलग पहचान पर अलग APK): चालू"
                                    )
                                } else {
                                    tr(
                                        "➕ Multi-Install (Separate APK per Identity): OFF",
                                        "➕ Multi-Install (আলাদা আইডেন্টিটিতে আলাদা APK): বন্ধ",
                                        "➕ Multi-Install (अलग पहचान पर अलग APK): बंद"
                                    )
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isMultiInstallEnabled) EmeraldGreen else TextSecondary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isMultiInstallEnabled) "ON ✅" else "OFF",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isMultiInstallEnabled) EmeraldGreen else TextMuted,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    if (isReady) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Install Standalone APK Button
                            Box(
                                modifier = Modifier
                                    .weight(1.1f)
                                    .height(28.dp)
                                    .background(EmeraldGreen.copy(alpha = 0.22f), RoundedCornerShape(7.dp))
                                    .border(1.dp, EmeraldGreen, RoundedCornerShape(7.dp))
                                    .clickable {
                                        Toast.makeText(
                                            context,
                                            tr(
                                                "Starting installation for ${app.appName}...",
                                                "${app.appName} ইনস্টল প্রক্রিয়া শুরু হচ্ছে...",
                                                "${app.appName} इंस्टॉलेशन शुरू हो रहा है..."
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onInstallStandaloneApk()
                                    }
                                    .testTag("install_standalone_apk_${app.packageName}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tr("📲 Install", "📲 ইনস্টল করুন", "📲 इंस्टॉल करें"),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = EmeraldGreen,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 2. Share Standalone APK Button (for installing on any other phone)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp)
                                    .background(NeonCyan.copy(alpha = 0.18f), RoundedCornerShape(7.dp))
                                    .border(1.dp, NeonCyan, RoundedCornerShape(7.dp))
                                    .clickable { onShareStandaloneApk() }
                                    .testTag("share_standalone_apk_${app.packageName}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tr("📤 Share APK", "📤 শেয়ার (অন্য ফোনে)", "📤 शेयर (दूसरे फ़ोन पर)"),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeonCyan,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 3. Rebuild APK Button
                            Box(
                                modifier = Modifier
                                    .height(28.dp)
                                    .background(goldAccent.copy(alpha = 0.2f), RoundedCornerShape(7.dp))
                                    .border(1.dp, goldAccent, RoundedCornerShape(7.dp))
                                    .clickable { onRebuildStandaloneApk() }
                                    .padding(horizontal = 8.dp)
                                    .testTag("rebuild_standalone_apk_${app.packageName}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tr("🔄 Rebuild", "🔄 আপডেট", "🔄 अपडेट"),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFD54F),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(6.dp))
                        val btnAccent = if (isBuilding) NeonCyan else goldAccent
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(30.dp)
                                .background(
                                    if (isBuilding) NeonCyan.copy(alpha = 0.18f) else goldAccent.copy(alpha = 0.22f),
                                    RoundedCornerShape(7.dp)
                                )
                                .cyberShimmerBorder(
                                    width = 1.1.dp,
                                    shape = RoundedCornerShape(7.dp),
                                    phaseOffset = appPhase + 0.18f,
                                    accentColor = btnAccent
                                )
                                .clickable { onRebuildStandaloneApk() }
                                .padding(horizontal = 10.dp)
                                .testTag("confirm_reload_build_apk_${app.packageName}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isBuilding) {
                                    tr(
                                        "🔄 Reload / Restart Build Now",
                                        "🔄 রিলোড / এখনই নতুন করে বিল্ড শুরু করুন",
                                        "🔄 रीलोड / अभी फिर से बिल्ड शुरू करें"
                                    )
                                } else {
                                    tr(
                                        "⚡ Confirm & Start APK Build (🔄 Reload)",
                                        "⚡ কনফার্ম ও বিল্ড শুরু করুন (🔄 রিলোড)",
                                        "⚡ कन्फर्म और APK बिल्ड शुरू करें (🔄 रीलोड)"
                                    )
                                },
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isBuilding) NeonCyan else Color(0xFFFFD54F),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCustomAppDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var appName by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(
                    width = 1.6.dp,
                    shape = RoundedCornerShape(20.dp),
                    phaseOffset = 0.25f
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CyberBg)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = tr("Add Target Application", "টার্গেট অ্যাপ যুক্ত করুন", "टारगेट ऐप जोड़ें"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = tr(
                        "Specify custom package to hook and wipe",
                        "হুক ও ক্লিয়ার করার জন্য কাস্টম প্যাকেজ উল্লেখ করুন",
                        "हुक और क्लीन करने के लिए कस्टम पैकेज दर्ज करें"
                    ),
                    fontSize = 12.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text(tr("Application Name", "অ্যাপের নাম", "ऐप का नाम")) },
                    placeholder = { Text("e.g. My Protected App") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text(tr("Package Name", "প্যাকেজ নেম", "पैकेज नाम")) },
                    placeholder = { Text("e.g. com.example.target") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = tr("Cancel", "বাতিল", "रद्द करें"), color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onAdd(appName, packageName) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = CyberBg
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(text = tr("Add App", "অ্যাপ যুক্ত করুন", "ऐप जोड़ें"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StandaloneInstallStatusDialog(
    state: com.example.data.engine.StandaloneInstallUiState,
    onDismiss: () -> Unit,
    onOpenInstalledApp: () -> Unit,
    onSwitchToCloneMode: (() -> Unit)? = null
) {
    val pct = state.progressPercent.coerceIn(0, 100)
    val totalMbStr = remember(state.fileSizeBytes) {
        com.example.data.engine.StandaloneApkBuilderEngine.formatFileSize(state.fileSizeBytes)
    }
    val completedMbStr = remember(state.fileSizeBytes, pct) {
        com.example.data.engine.StandaloneApkBuilderEngine.formatFileSize(
            (state.fileSizeBytes * pct.toLong()) / 100L
        )
    }
    val displayPkg = remember(state.standalonePackageName) {
        state.standalonePackageName.replace(".phantom", ".ghostdevice")
    }
    val accent = when {
        state.isCompleted -> EmeraldGreen
        state.isError -> CyberMagenta
        else -> NeonCyan
    }
    val featureTags = remember(state.activeFeaturesSummary) {
        state.activeFeaturesSummary
            .split("•")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 310.dp)
                .cyberShimmerBorder(
                    width = 1.6.dp,
                    shape = RoundedCornerShape(20.dp),
                    phaseOffset = 0.15f,
                    accentColor = accent
                )
                .testTag("standalone_install_status_dialog"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (state.isCompleted) Color(0xFF081713) else Color(0xFF0C1320)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            if (state.isCompleted) {
                                listOf(Color(0xFF0C221A), Color(0xFF07120F))
                            } else {
                                listOf(Color(0xFF111C2F), Color(0xFF0A101B))
                            }
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Compact Glowing Circular Status Badge
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.34f),
                                    accent.copy(alpha = 0.06f)
                                )
                            ),
                            shape = CircleShape
                        )
                        .border(1.8.dp, accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isInstalling && !state.isCompleted) {
                        CircularProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.size(44.dp),
                            color = accent,
                            trackColor = Color(0xFF1A2639),
                            strokeWidth = 3.dp
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (state.isCompleted) "✓" else if (state.isError) "!" else "$pct%",
                            fontSize = if (state.isCompleted) 20.sp else 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (state.isCompleted) EmeraldGreen else Color.White,
                            maxLines = 1,
                            softWrap = false
                        )
                        if (state.isCompleted) {
                            Text(
                                text = "100%",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = EmeraldGreen,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Main Title (Strictly 1 line)
                Text(
                    text = if (state.isCompleted) {
                        tr(
                            "🎉 Installation Complete!",
                            "🎉 ইনস্টল সম্পূর্ণ!",
                            "🎉 इंस्टॉल पूरा हुआ!"
                        )
                    } else {
                        state.stageTitle
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (state.isCompleted) EmeraldGreen else TextPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // App Name + GhostDevice Package Name (Strictly 1 line)
                Text(
                    text = "${state.appName} • $displayPkg",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD54F),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Compact Glass Progress & Status Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111B2B), RoundedCornerShape(12.dp))
                        .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 11.dp, vertical = 9.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.isCompleted) {
                                tr("Installed", "ইনস্টল সম্পন্ন", "इंस्टॉल पूरा")
                            } else {
                                tr("Progress", "প্রগ্রেস", "प्रगति")
                            },
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = "$completedMbStr / $totalMbStr ($pct%)",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = accent,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        color = accent,
                        trackColor = Color(0xFF1E2C42)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val conciseDetail = remember(state.isCompleted, state.appName, totalMbStr, state.stageDetail) {
                        if (state.isCompleted) {
                            tr(
                                "${state.appName} ($totalMbStr) ready • GhostDevice Engine",
                                "${state.appName} ($totalMbStr) ইনস্টল সম্পন্ন • GhostDevice",
                                "${state.appName} ($totalMbStr) इंस्टॉल पूरा • GhostDevice"
                            )
                        } else {
                            state.stageDetail
                        }
                    }
                    Text(
                        text = conciseDetail,
                        fontSize = 10.sp,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (state.isError) 3 else 1,
                        softWrap = state.isError,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Active GhostDevice Identity & Feature Badges (Strictly 1 line each)
                if (state.bakedDeviceModel.isNotBlank() || featureTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0E1724), RoundedCornerShape(11.dp))
                            .border(1.dp, CyberBorder, RoundedCornerShape(11.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (state.bakedDeviceModel.isNotBlank()) {
                            Text(
                                text = "📱 ${state.bakedDeviceModel}",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = NeonCyan,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (featureTags.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                featureTags.forEach { tag ->
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFF152538), RoundedCornerShape(6.dp))
                                            .border(0.8.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = tag,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFB9F6CA),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (state.isCompleted) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1A2636),
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = tr("✓ Done", "✓ ঠিক আছে", "✓ ठीक है"),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = onOpenInstalledApp,
                            modifier = Modifier
                                .weight(1.3f)
                                .height(38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldGreen,
                                contentColor = Color(0xFF04140E)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = tr("🚀 Open App", "🚀 ওপেন করুন", "🚀 खोलें"),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    if (state.isError && state.canSwitchToCloneMode && onSwitchToCloneMode != null) {
                        Button(
                            onClick = onSwitchToCloneMode,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .testTag("switch_without_main_apk_button"),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldGreen,
                                contentColor = Color(0xFF04140E)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "📦 Without Main Apk Replace",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(7.dp))
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1A2636),
                            contentColor = if (state.isError) TextPrimary else NeonCyan
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (state.isError) {
                                tr("Close", "বন্ধ করুন", "बंद करें")
                            } else {
                                tr(
                                    "Hide Window (Background)",
                                    "উইন্ডো লুকান (ব্যাকগ্রাউন্ড)",
                                    "विंडो छुपाएं (बैकग्राउंड)"
                                )
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StandaloneInstallModeDialog(
    app: TargetAppEntity,
    isStandaloneActive: Boolean,
    isReplaceMainApkSelected: Boolean,
    onDismiss: () -> Unit,
    onSelectMode: (Boolean) -> Unit
) {
    val goldAccent = Color(0xFFFFD54F)

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 310.dp)
                .cyberShimmerBorder(
                    width = 1.6.dp,
                    shape = RoundedCornerShape(18.dp),
                    phaseOffset = 0.22f,
                    accentColor = NeonCyan
                )
                .testTag("standalone_install_mode_dialog"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1322))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF101B30), Color(0xFF080E18))
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                // Header Row (Compact, Strictly 1 line per text)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(
                                    Brush.radialGradient(
                                        listOf(NeonCyan.copy(alpha = 0.30f), NeonCyan.copy(alpha = 0.08f))
                                    ),
                                    CircleShape
                                )
                                .border(1.2.dp, NeonCyan, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "📦", fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr("APK Install Mode", "APK ইনস্টল মোড", "APK इंस्टॉल मोड"),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${app.appName} • v${app.versionName}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NeonCyan,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(Color(0xFF152033), CircleShape)
                            .border(0.8.dp, CyberBorder, CircleShape)
                            .clickable { onDismiss() }
                            .testTag("close_standalone_mode_dialog"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(11.dp))

                val isYouTubeApp = remember(app.packageName) {
                    com.example.data.engine.StandaloneApkBuilderEngine.isYouTubePackage(app.packageName)
                }
                if (isYouTubeApp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A1F18), RoundedCornerShape(11.dp))
                            .border(1.dp, EmeraldGreen.copy(alpha = 0.55f), RoundedCornerShape(11.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("youtube_adfree_banner")
                    ) {
                        Text(
                            text = tr(
                                "🛡️ YouTube Ad-Free System",
                                "🛡️ YouTube অ্যাড-ফ্রি সিস্টেম",
                                "🛡️ YouTube विज्ञापन-मुक्त सिस्टम"
                            ),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = EmeraldGreen,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = tr(
                                "Blocks video & feed ads automatically. Use 'Without Main Apk Replace' on non-root phones.",
                                "ভিডিও এবং অ্যাপের সব অ্যাড স্বয়ংক্রিয়ভাবে ব্লক হবে। নন-রুট ফোনে 'Without Main Apk Replace' ব্যবহার করুন।",
                                "वीडियो और ऐप के सभी विज्ञापन अपने आप ब्लॉक होंगे। बिना रूट फ़ोन में 'Without Main Apk Replace' चुनें।"
                            ),
                            fontSize = 9.5.sp,
                            color = Color(0xFFB9F6CA),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Option 1: Main Apk Replace
                val replaceSelectedNow = isStandaloneActive && isReplaceMainApkSelected
                StandaloneModeOptionButton(
                    icon = "🔄",
                    title = "Main Apk Replace",
                    subtitle = if (isYouTubeApp) {
                        tr(
                            "Replace main (Root recommended for YouTube)",
                            "মেইন APK রিপ্লেস (YouTube-এ রুট প্রয়োজন)",
                            "मेन APK रिप्लेस (YouTube के लिए रूट ज़रूरी)"
                        )
                    } else {
                        tr(
                            "Keep exact package & replace main",
                            "মূল প্যাকেজ রেখে মেইন APK রিপ্লেস",
                            "मूल पैकेज रखकर मेन APK रिप्लेस"
                        )
                    },
                    accentColor = NeonCyan,
                    isSelected = replaceSelectedNow,
                    testTagName = "mode_main_apk_replace",
                    onClick = { onSelectMode(true) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Option 2: Without Main Apk Replace
                val withoutSelectedNow = isStandaloneActive && !isReplaceMainApkSelected
                StandaloneModeOptionButton(
                    icon = "📦",
                    title = if (isYouTubeApp) "Without Main Apk Replace ★" else "Without Main Apk Replace",
                    subtitle = if (isYouTubeApp) {
                        tr(
                            "Recommended • Ad-Free install without conflict",
                            "সুপারিশকৃত • কনফ্লিক্ট ছাড়াই অ্যাড-ফ্রি ইনস্টল",
                            "अनुशंसित • बिना किसी समस्या के विज्ञापन-मुक्त इंस्टॉल"
                        )
                    } else {
                        tr(
                            "Side-by-side .ghostdevice install",
                            "মেইন অ্যাপ রেখে আলাদা ইনস্টল",
                            "मेन ऐप रखे बिना अलग से इंस्टॉल"
                        )
                    },
                    accentColor = if (isYouTubeApp) EmeraldGreen else goldAccent,
                    isSelected = withoutSelectedNow,
                    testTagName = "mode_without_main_apk_replace",
                    onClick = { onSelectMode(false) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = tr(
                        "You can switch modes anytime from app panel",
                        "অ্যাপ প্যানেল থেকে যেকোনো সময় পরিবর্তন করতে পারবেন",
                        "आप ऐप पैनल से कभी भी मोड बदल सकते हैं"
                    ),
                    fontSize = 9.sp,
                    color = TextMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun StandaloneModeOptionButton(
    icon: String,
    title: String,
    subtitle: String,
    accentColor: Color,
    isSelected: Boolean,
    testTagName: String,
    onClick: () -> Unit
) {
    val activeAccent = if (isSelected) EmeraldGreen else accentColor
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    if (isSelected) {
                        listOf(EmeraldGreen.copy(alpha = 0.22f), Color(0xFF0E1B28))
                    } else {
                        listOf(accentColor.copy(alpha = 0.13f), Color(0xFF111A2B))
                    }
                ),
                RoundedCornerShape(12.dp)
            )
            .cyberShimmerBorder(
                width = if (isSelected) 1.5.dp else 1.1.dp,
                shape = RoundedCornerShape(12.dp),
                phaseOffset = if (isSelected) 0.10f else 0.45f,
                accentColor = activeAccent
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .testTag(testTagName)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(activeAccent.copy(alpha = 0.18f), CircleShape)
                        .border(1.dp, activeAccent.copy(alpha = 0.75f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = icon, fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isSelected) EmeraldGreen else TextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        fontSize = 9.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) EmeraldGreen else activeAccent.copy(alpha = 0.18f),
                        RoundedCornerShape(7.dp)
                    )
                    .border(
                        0.8.dp,
                        activeAccent,
                        RoundedCornerShape(7.dp)
                    )
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isSelected) "✓ Active" else tr("Select", "সিলেক্ট", "चुनें"),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isSelected) Color(0xFF04140E) else activeAccent,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

