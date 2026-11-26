package com.example.ui.components

import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.generator.IdentityGenerator
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardElevated
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.cyberShimmerBorder
import kotlin.random.Random

@Composable
fun DeviceModelSelectorDialog(
    currentModel: String,
    currentBuildNumber: String = "UP1A.231005.007",
    currentDeviceId: String = "marlin",
    currentKernelVersion: String = "5.15.110-android14-11-g89a3f2b",
    currentSocHardware: String = "Snapdragon 8 Gen 3 (SM8650-AB)",
    currentGpuRenderer: String = "Adreno (TM) 750 @ 1000MHz",
    currentGpuVersion: String = "Vulkan 1.3.275 (Adreno Driver 512.752.0)",
    currentBasebandVersion: String = "g5300g-240301-240502-B-11812984",
    currentSimImsi: String = "310260982314561",
    currentSimIccid: String = "8901410321111854920F",
    currentWidevineDrmId: String = "4912a870b9213e4f",
    currentBootloaderVersion: String = "b1-0.5-10808477",
    currentRegion: String = "United States",
    currentLocaleTag: String = "en-US",
    initialTab: Int = 0,
    onSelectPreset: (IdentityGenerator.DevicePreset) -> Unit,
    onApplyCustom: (model: String, manufacturer: String, buildNumber: String, deviceId: String, kernelVersion: String, socHardware: String, gpuRenderer: String, gpuVersion: String, basebandVersion: String, simImsi: String, simIccid: String, widevineDrmId: String, bootloaderVersion: String, region: String, localeTag: String) -> Unit,
    onDismiss: () -> Unit
) {
    val currentLang = com.example.ui.i18n.currentAppLang()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(initialTab) }
    var searchQuery by remember { mutableStateOf("") }

    var customModelInput by remember(currentModel) { mutableStateOf(currentModel) }
    var customManufacturerInput by remember(currentModel) { mutableStateOf(currentModel.substringBefore(" ").ifBlank { "Generic" }) }
    var customBuildNumberInput by remember(currentBuildNumber) { mutableStateOf(currentBuildNumber) }
    var customDeviceIdInput by remember(currentDeviceId) { mutableStateOf(currentDeviceId) }
    var customKernelVersionInput by remember(currentKernelVersion) { mutableStateOf(currentKernelVersion) }
    var customSocHardwareInput by remember(currentSocHardware) { mutableStateOf(currentSocHardware) }
    var customGpuRendererInput by remember(currentGpuRenderer) { mutableStateOf(currentGpuRenderer) }
    var customGpuVersionInput by remember(currentGpuVersion) { mutableStateOf(currentGpuVersion) }
    var customBasebandVersionInput by remember(currentBasebandVersion) { mutableStateOf(currentBasebandVersion) }
    var customSimImsiInput by remember(currentSimImsi) { mutableStateOf(currentSimImsi) }
    var customSimIccidInput by remember(currentSimIccid) { mutableStateOf(currentSimIccid) }
    var customWidevineDrmIdInput by remember(currentWidevineDrmId) { mutableStateOf(currentWidevineDrmId) }
    var customBootloaderVersionInput by remember(currentBootloaderVersion) { mutableStateOf(currentBootloaderVersion) }
    var customRegionInput by remember(currentRegion) { mutableStateOf(currentRegion) }
    var customLocaleTagInput by remember(currentLocaleTag) { mutableStateOf(currentLocaleTag) }

    val presets = remember { IdentityGenerator.devicePresets }
    val filteredPresets = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            presets
        } else {
            presets.filter {
                it.model.contains(searchQuery, ignoreCase = true) ||
                        it.manufacturer.contains(searchQuery, ignoreCase = true) ||
                        it.deviceId.contains(searchQuery, ignoreCase = true) ||
                        it.buildNumber.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(22.dp), phaseOffset = 0.12f, accentColor = NeonCyan),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(NeonCyan.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .cyberShimmerBorder(width = 1.15.dp, shape = RoundedCornerShape(10.dp), phaseOffset = 0.35f, accentColor = NeonCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = com.example.ui.i18n.tr("Device Identity & Hardware", "ডিভাইস আইডেন্টিটি ও হার্ডওয়্যার", "डिवाइस पहचान और हार्डवेयर", currentLang),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = com.example.ui.i18n.tr("Build Number · Device ID · Kernel", "বিল্ড নম্বর · ডিভাইস আইডি · কার্নেল", "बिल्ड नंबर · डिवाइस आईडी · कर्नेल", currentLang),
                                fontSize = 11.sp,
                                color = NeonCyan,
                                maxLines = 1
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Tabs: Presets vs Custom
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = CyberCardElevated,
                    contentColor = NeonCyan,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = NeonCyan
                        )
                    },
                    modifier = Modifier.cyberShimmerBorder(width = 1.15.dp, shape = RoundedCornerShape(10.dp), phaseOffset = 0.48f)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                com.example.ui.i18n.tr("Popular Flagships", "জনপ্রিয় ফ্ল্যাগশিপ", "लोकप्रिय फ्लैगशिप", currentLang),
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                com.example.ui.i18n.tr("Custom Hardware", "কাস্টম হার্ডওয়্যার", "कस्टम हार्डवेयर", currentLang),
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedTab == 0) {
                    // Search bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(com.example.ui.i18n.tr("Search Samsung, Pixel, OnePlus, Xiaomi...", "খুঁজুন: Samsung, Pixel, OnePlus, Xiaomi...", "खोजें: Samsung, Pixel, OnePlus, Xiaomi...", currentLang), fontSize = 12.sp, color = TextMuted) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CyberBorder,
                            focusedContainerColor = CyberBg,
                            unfocusedContainerColor = CyberBg,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Preset List
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredPresets) { preset ->
                            val isSelected = preset.model.equals(currentModel, ignoreCase = true)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .cyberShimmerBorder(
                                        width = if (isSelected) 1.5.dp else 1.15.dp,
                                        shape = RoundedCornerShape(12.dp),
                                        phaseOffset = (preset.model.hashCode() * 0.11f) % 1f,
                                        accentColor = if (isSelected) NeonCyan else null
                                    )
                                    .clickable {
                                        onSelectPreset(preset)
                                        Toast.makeText(context, "Identity updated: ${preset.model}", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                    .testTag("preset_model_${preset.manufacturer.lowercase()}"),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) NeonCyan.copy(alpha = 0.12f) else CyberCardElevated
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        NeonCyan.copy(alpha = 0.2f),
                                                        RoundedCornerShape(4.dp)
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = preset.manufacturer.uppercase(),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeonCyan
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = preset.model,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Build: ${preset.buildNumber} · DeviceID: ${preset.deviceId}",
                                            fontSize = 10.sp,
                                            color = TextSecondary,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "Kernel: ${preset.kernelVersion.take(28)}",
                                            fontSize = 9.sp,
                                            color = TextMuted,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1
                                        )
                                    }

                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .background(EmeraldGreen, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Active",
                                                tint = CyberBg,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Custom Identity Inputs (Model, Manufacturer, Build Number, Device ID, Kernel Version)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = customModelInput,
                            onValueChange = { customModelInput = it },
                            label = { Text("Model Name (e.g. Galaxy S24 Ultra SM-S928B)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        OutlinedTextField(
                            value = customManufacturerInput,
                            onValueChange = { customManufacturerInput = it },
                            label = { Text("Manufacturer (e.g. Samsung, Google)", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 1. Build Number Input
                        OutlinedTextField(
                            value = customBuildNumberInput,
                            onValueChange = { customBuildNumberInput = it },
                            label = { Text("Build Number (e.g. UP1A.231005.007, S928BXXU1AXB5)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    customBuildNumberInput = "UP1A.${Random.nextInt(231001, 240830)}.${Random.nextInt(100, 999)}"
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 2. Device ID / Codename Input
                        OutlinedTextField(
                            value = customDeviceIdInput,
                            onValueChange = { customDeviceIdInput = it },
                            label = { Text("Device ID / Codename (e.g. e3q, husky, komodo)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val chars = "abcdefghijklmnopqrstuvwxyz"
                                    customDeviceIdInput = (1..6).map { chars.random() }.joinToString("")
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 3. Kernel Version Input
                        OutlinedTextField(
                            value = customKernelVersionInput,
                            onValueChange = { customKernelVersionInput = it },
                            label = { Text("Kernel Version (e.g. 6.1.75-android14-11-g7c82e91)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val chars = "0123456789abcdef"
                                    val gitHash = (1..8).map { chars.random() }.joinToString("")
                                    customKernelVersionInput = "${Random.nextInt(5, 6)}.${Random.nextInt(10, 19)}.${Random.nextInt(50, 180)}-android14-${Random.nextInt(1, 15)}-g$gitHash"
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 4. SoC Hardware Input
                        OutlinedTextField(
                            value = customSocHardwareInput,
                            onValueChange = { customSocHardwareInput = it },
                            label = { Text("Processor / SoC Hardware (e.g. Snapdragon 8 Gen 3)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val processors = listOf("Snapdragon 8 Gen 3 (SM8650-AB)", "Dimensity 9300", "Tensor G4", "Exynos 2400")
                                    customSocHardwareInput = processors.random()
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 5. GPU Renderer Input
                        OutlinedTextField(
                            value = customGpuRendererInput,
                            onValueChange = { customGpuRendererInput = it },
                            label = { Text("GPU Renderer (e.g. Adreno (TM) 750)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Tv, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val gpus = listOf("Adreno (TM) 750 @ 1000MHz", "Adreno (TM) 740 @ 710MHz", "Immortalis-G720 MC12", "Xclipse 940")
                                    customGpuRendererInput = gpus.random()
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 5b. GPU Version Input
                        OutlinedTextField(
                            value = customGpuVersionInput,
                            onValueChange = { customGpuVersionInput = it },
                            label = { Text("GPU API / Driver Version (e.g. Vulkan 1.3)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Tv, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val gpuVers = listOf(
                                        "Vulkan 1.3.275 (Adreno Driver 512.752.0)",
                                        "OpenGL ES 3.2 V@0752.0 (GIT@7bc6689)",
                                        "OpenGL ES 3.2 v1.r45p0-01eace, Vulkan 1.3",
                                        "OpenGL ES 3.2 v1.r44p0-01dace, Vulkan 1.3"
                                    )
                                    customGpuVersionInput = gpuVers.random()
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 6. Baseband Version Input
                        OutlinedTextField(
                            value = customBasebandVersionInput,
                            onValueChange = { customBasebandVersionInput = it },
                            label = { Text("Baseband / Radio Version (e.g. g5300g-240301-240502)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.SignalCellularAlt, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val basebands = listOf(
                                        "g5300g-240301-240502-B-11812984",
                                        "g5300i-230910-231120-B-10931284",
                                        "M8998_g78300g_43.10",
                                        "S928BXXU1AXB5_MODEM"
                                    )
                                    customBasebandVersionInput = basebands.random()
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 7. Subscriber ID (IMSI) Input
                        OutlinedTextField(
                            value = customSimImsiInput,
                            onValueChange = { customSimImsiInput = it },
                            label = { Text("Subscriber ID / IMSI (e.g. 310260982314561)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.SimCard, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val imsiPrefixes = listOf("310260", "40445", "50501", "23430")
                                    val digits = (1..9).map { (0..9).random() }.joinToString("")
                                    customSimImsiInput = "${imsiPrefixes.random()}$digits"
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 8. SIM Serial (ICCID) Input
                        OutlinedTextField(
                            value = customSimIccidInput,
                            onValueChange = { customSimIccidInput = it },
                            label = { Text("SIM Serial / ICCID (e.g. 8901410321111854920F)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.SimCard, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val digits = (1..18).map { (0..9).random() }.joinToString("")
                                    customSimIccidInput = "89$digits" + listOf("F", "0").random()
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 9. Widevine DRM ID Input
                        OutlinedTextField(
                            value = customWidevineDrmIdInput,
                            onValueChange = { customWidevineDrmIdInput = it },
                            label = { Text("Widevine DRM ID (16-char hex ID)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Fingerprint, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val hexChars = "0123456789abcdef"
                                    customWidevineDrmIdInput = (1..16).map { hexChars.random() }.joinToString("")
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 10. Bootloader Version Input
                        OutlinedTextField(
                            value = customBootloaderVersionInput,
                            onValueChange = { customBootloaderVersionInput = it },
                            label = { Text("Bootloader Version (e.g. b1-0.5-10808477)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val types = listOf("b1-0.5", "s928bxxu1", "huskymod_33")
                                    customBootloaderVersionInput = "${types.random()}-${Random.nextInt(1000000, 9999999)}"
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 11. Custom Region Input
                        OutlinedTextField(
                            value = customRegionInput,
                            onValueChange = { customRegionInput = it },
                            label = { Text("System Region (e.g. United States, Indonesia)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val regions = listOf("United States", "Indonesia", "Germany", "Japan", "United Kingdom", "Singapore", "Canada", "Australia", "France")
                                    customRegionInput = regions.random()
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // 12. Custom Locale Tag Input
                        OutlinedTextField(
                            value = customLocaleTagInput,
                            onValueChange = { customLocaleTagInput = it },
                            label = { Text("Locale Tag / Language (e.g. en-US, id-ID, de-DE)", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val tags = listOf("en-US", "id-ID", "de-DE", "ja-JP", "en-GB", "en-SG", "en-CA", "en-AU", "fr-FR")
                                    customLocaleTagInput = tags.random()
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Randomize", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberBg,
                                unfocusedContainerColor = CyberBg,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = {
                                if (customModelInput.isNotBlank()) {
                                    onApplyCustom(
                                        customModelInput,
                                        customManufacturerInput,
                                        customBuildNumberInput,
                                        customDeviceIdInput,
                                        customKernelVersionInput,
                                        customSocHardwareInput,
                                        customGpuRendererInput,
                                        customGpuVersionInput,
                                        customBasebandVersionInput,
                                        customSimImsiInput,
                                        customSimIccidInput,
                                        customWidevineDrmIdInput,
                                        customBootloaderVersionInput,
                                        customRegionInput,
                                        customLocaleTagInput
                                    )
                                    Toast.makeText(
                                        context,
                                        com.example.ui.i18n.tr(
                                            "Hardware Identity updated: $customModelInput",
                                            "হার্ডওয়্যার আইডেন্টিটি আপডেট হয়েছে: $customModelInput",
                                            "हार्डवेयर पहचान अपडेट की गई: $customModelInput"
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onDismiss()
                                } else {
                                    Toast.makeText(
                                        context,
                                        com.example.ui.i18n.tr("Please enter a model name", "অনুগ্রহ করে মডেলের নাম লিখুন", "कृपया मॉडल का नाम दर्ज करें"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("apply_custom_model_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = CyberBg),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                com.example.ui.i18n.tr("Apply Identity Changes", "আইডেন্টিটি পরিবর্তন প্রয়োগ করুন", "पहचान परिवर्तन लागू करें", currentLang),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
