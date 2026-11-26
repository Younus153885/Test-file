package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TimerSettings
import com.example.ui.i18n.currentAppLang
import com.example.ui.i18n.tr
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

@Composable
fun TimerScreen(
    settings: TimerSettings,
    isTimerRunning: Boolean,
    remainingSeconds: Int,
    onUpdateMinutes: (Int) -> Unit,
    onUpdateSeconds: (Int) -> Unit,
    onToggleLoopMode: () -> Unit,
    onToggleStartWithSystem: () -> Unit,
    onToggleResetOption: (String) -> Unit,
    onStartTimer: () -> Unit,
    onStopTimer: () -> Unit,
    showFloatingBadge: Boolean,
    onToggleFloatingBadge: () -> Unit
) {
    val _lang = currentAppLang()
    val scrollState = rememberScrollState()
    var minutesText by remember(settings.minutes) { mutableStateOf(settings.minutes.toString()) }
    var secondsText by remember(settings.seconds) { mutableStateOf(settings.seconds.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState)
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // Title Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = tr("Scheduled Reset Timer", "সিডিউল রিসেট টাইমার", "शेड्यूल रीसेट टाइमर"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = tr(
                        "Automated background device identity randomization",
                        "স্বয়ংক্রিয় ব্যাকগ্রাউন্ড ডিভাইস আইডেন্টিটি পরিবর্তন",
                        "स्वचालित बैकग्राउंड डिवाइस आइडेंटिटी रैंडमाइजेशन"
                    ),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            if (isTimerRunning) {
                Box(
                    modifier = Modifier
                        .background(EmeraldGreen.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .cyberShimmerBorder(width = 1.2.dp, shape = RoundedCornerShape(12.dp), phaseOffset = 0.12f, accentColor = EmeraldGreen)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = tr("Running", "চালু আছে", "चालू है"),
                        color = EmeraldGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Active Timer Display (if running)
        if (isTimerRunning) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(16.dp), phaseOffset = 0.18f, accentColor = NeonCyan),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCardElevated)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = tr("COUNTDOWN ACTIVE", "কাউন্টডাউন সক্রিয়", "काउंटडाउन सक्रिय"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan,
                        letterSpacing = 1.5.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val mins = remainingSeconds / 60
                    val secs = remainingSeconds % 60
                    Text(
                        text = "%02d:%02d".format(mins, secs),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (settings.isLoopMode) {
                            tr("Loop Mode: Auto-repeats next purge", "লুপ মোড: পরবর্তী রিসেট স্বয়ংক্রিয়ভাবে চলবে", "लूप मोड: अगला रीसेट स्वतः दोहराया जाएगा")
                        } else {
                            tr("Single-run execution", "একবার রিসেট হবে", "एक बार रीसेट होगा")
                        },
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Set Interval Box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(width = 1.3.dp, shape = RoundedCornerShape(16.dp), phaseOffset = 0.30f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = tr("Set Interval", "সময় নির্ধারণ করুন", "अंतराल सेट करें"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = {
                            minutesText = it
                            it.toIntOrNull()?.let { m -> onUpdateMinutes(m) }
                        },
                        label = { Text(tr("Minutes", "মিনিট (Minutes)", "मिनट (Minutes)")) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("timer_minutes_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CyberBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = NeonCyan,
                            unfocusedLabelColor = TextMuted
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = {
                            secondsText = it
                            it.toIntOrNull()?.let { s -> onUpdateSeconds(s) }
                        },
                        label = { Text(tr("Seconds", "সেকেন্ড (Seconds)", "सेकंड (Seconds)")) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("timer_seconds_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CyberBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = NeonCyan,
                            unfocusedLabelColor = TextMuted
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Loop Mode & System Start Cards
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(width = 1.3.dp, shape = RoundedCornerShape(16.dp), phaseOffset = 0.45f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Loop Mode", "লুপ মোড (Loop Mode)", "लूप मोड (Loop Mode)"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = tr("Auto-repeat after each reset", "প্রতিবার রিসেটের পর স্বয়ংক্রিয়ভাবে পুনরায় চলবে", "प्रत्येक रीसेट के बाद स्वचालित रूप से दोहराएं"),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                    Switch(
                        checked = settings.isLoopMode,
                        onCheckedChange = { onToggleLoopMode() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberBg,
                            checkedTrackColor = NeonCyan,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = CyberCardElevated
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .height(0.5.dp)
                        .background(CyberBorder)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Start with System (Auto-start)", "সিস্টেম বুটের সাথে চালু (Auto-start)", "सिस्टम के साथ शुरू करें (Auto-start)"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = tr(
                                "Automatically launch in background on device boot",
                                "ডিভাইস চালু হলে ব্যাকগ্রাউন্ডে স্বয়ংক্রিয়ভাবে শুরু হবে",
                                "डिवाइस बूट होने पर बैकग्राउंड में स्वतः शुरू करें"
                            ),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                    Switch(
                        checked = settings.startWithSystem,
                        onCheckedChange = { onToggleStartWithSystem() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberBg,
                            checkedTrackColor = NeonCyan,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = CyberCardElevated
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .height(0.5.dp)
                        .background(CyberBorder)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Floating Countdown Bubble", "ফ্লোটিং কাউন্টডাউন বাবল", "फ्लोटिंग काउंटडाउन बबल"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = tr(
                                "Display overlay timer over other apps & game sessions",
                                "অন্যান্য অ্যাপ এবং গেম সেশনের উপর ওভারলে টাইমার দেখান",
                                "अन्य ऐप्स और गेम सेशन के ऊपर ओवरले टाइमर दिखाएं"
                            ),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                    Switch(
                        checked = showFloatingBadge,
                        onCheckedChange = { onToggleFloatingBadge() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberBg,
                            checkedTrackColor = EmeraldGreen,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = CyberCardElevated
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reset Options Header & Toggles (All 26 Device Identity Parameters)
        val activeOptionsCount = listOf(
            settings.resetDeviceModel,
            settings.resetManufacturer,
            settings.resetAndroidId,
            settings.resetAdvertisingId,
            settings.resetImei,
            settings.resetSerialNumber,
            settings.resetMacAddress,
            settings.resetBluetooth,
            settings.resetNetworkIp,
            settings.resetCarrierName,
            settings.resetSimImsi,
            settings.resetSimIccid,
            settings.resetSocHardware,
            settings.resetGpuRenderer,
            settings.resetDisplayResolution,
            settings.resetDeviceId,
            settings.resetBootloaderVersion,
            settings.resetOsVersion,
            settings.resetBuildNumber,
            settings.resetBuildFingerprint,
            settings.resetKernel,
            settings.resetBaseband,
            settings.resetWidevineDrmId,
            settings.resetLocation,
            settings.resetTimezone,
            settings.resetUserAgent
        ).count { it }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr("Reset Options", "রিসেট অপশনসমূহ", "रीसेट विकल्प"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = tr("$activeOptionsCount/26 Active", "$activeOptionsCount/২৬ টি সক্রিয়", "$activeOptionsCount/26 सक्रिय"),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = NeonCyan
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(width = 1.3.dp, shape = RoundedCornerShape(16.dp), phaseOffset = 0.62f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                ResetOptionToggleRow(tr("Device Model", "ডিভাইস মডেল (Device Model)", "डिवाइस मॉडल (Device Model)"), settings.resetDeviceModel) {
                    onToggleResetOption("Device Model")
                }
                ResetOptionToggleRow(tr("Manufacturer", "প্রস্তুতকারক (Manufacturer)", "निर्माता (Manufacturer)"), settings.resetManufacturer) {
                    onToggleResetOption("Manufacturer")
                }
                ResetOptionToggleRow(tr("Android ID", "অ্যান্ড্রয়েড আইডি (Android ID)", "एंड्रॉइड आईडी (Android ID)"), settings.resetAndroidId) {
                    onToggleResetOption("Android ID")
                }
                ResetOptionToggleRow(tr("Advertising ID", "অ্যাডভার্টাইজিং আইডি (Advertising ID)", "एडवरटाइजिंग आईडी (Advertising ID)"), settings.resetAdvertisingId) {
                    onToggleResetOption("Advertising ID")
                }
                ResetOptionToggleRow(tr("IMEI", "আইএমইআই (IMEI)", "आईएमईआई (IMEI)"), settings.resetImei) {
                    onToggleResetOption("IMEI")
                }
                ResetOptionToggleRow(tr("Serial Number", "সিরিয়াল নাম্বার (Serial Number)", "सीरियल नंबर (Serial Number)"), settings.resetSerialNumber) {
                    onToggleResetOption("Serial Number")
                }
                ResetOptionToggleRow(tr("Wi-Fi MAC Address", "ওয়াই-ফাই ম্যাক (Wi-Fi MAC Address)", "वाई-फाई मैक (Wi-Fi MAC Address)"), settings.resetMacAddress) {
                    onToggleResetOption("Wi-Fi MAC Address")
                }
                ResetOptionToggleRow(tr("Bluetooth Address", "ব্লুটুথ ম্যাক (Bluetooth Address)", "ब्लूटूथ पता (Bluetooth Address)"), settings.resetBluetooth) {
                    onToggleResetOption("Bluetooth Address")
                }
                ResetOptionToggleRow(tr("IP Address (Network/IP Reset)", "আইপি অ্যাড্রেস (Network/IP Reset)", "आईपी पता (Network/IP Reset)"), settings.resetNetworkIp) {
                    onToggleResetOption("IP Address")
                }
                ResetOptionToggleRow(tr("SIM Status & Carrier", "সিম ও অপারেটর (SIM & Carrier)", "सिम और कैरियर (SIM & Carrier)"), settings.resetCarrierName) {
                    onToggleResetOption("Carrier")
                }
                ResetOptionToggleRow(tr("IMSI", "সিম আইএমএসআই (IMSI)", "सिम IMSI"), settings.resetSimImsi) {
                    onToggleResetOption("SIM IMSI")
                }
                ResetOptionToggleRow(tr("ICCID", "সিম সিরিয়াল (ICCID)", "सिम सीरियल (ICCID)"), settings.resetSimIccid) {
                    onToggleResetOption("SIM Serial / ICCID")
                }
                ResetOptionToggleRow(tr("SoC Hardware", "প্রসেসর চিপসেট (SoC Hardware)", "प्रोसेसर चिपसेट (SoC Hardware)"), settings.resetSocHardware) {
                    onToggleResetOption("SoC Hardware")
                }
                ResetOptionToggleRow(tr("GPU Renderer", "জিপিইউ রেন্ডারার (GPU Renderer)", "जीपीयू रेंडरर (GPU Renderer)"), settings.resetGpuRenderer) {
                    onToggleResetOption("GPU Renderer")
                }
                ResetOptionToggleRow(tr("Display Resolution", "ডিসপ্লে রেজোলিউশন (Display Resolution)", "डिस्प्ले रेज़ोल्यूशन (Display Resolution)"), settings.resetDisplayResolution) {
                    onToggleResetOption("Display Resolution")
                }
                ResetOptionToggleRow(tr("Device ID / Codename", "ডিভাইস আইডি / কোডনেম", "डिवाइस आईडी / कोडनेम"), settings.resetDeviceId) {
                    onToggleResetOption("Device ID")
                }
                ResetOptionToggleRow(tr("Bootloader", "বুটলোডার ভার্সন (Bootloader)", "बूटलोडर वर्ज़न (Bootloader)"), settings.resetBootloaderVersion) {
                    onToggleResetOption("Bootloader Version")
                }
                ResetOptionToggleRow(tr("OS Version", "ওএস ভার্সন (OS Version)", "ओएस वर्ज़न (OS Version)"), settings.resetOsVersion) {
                    onToggleResetOption("OS Version")
                }
                ResetOptionToggleRow(tr("Build Number", "বিল্ড নাম্বার (Build Number)", "बिल्ड नंबर (Build Number)"), settings.resetBuildNumber) {
                    onToggleResetOption("Build Number")
                }
                ResetOptionToggleRow(tr("Build Fingerprint", "বিল্ড ফিঙ্গারপ্রিন্ট (Build Fingerprint)", "बिल्ड फ़िंगरप्रिंट (Build Fingerprint)"), settings.resetBuildFingerprint) {
                    onToggleResetOption("Build Fingerprint")
                }
                ResetOptionToggleRow(tr("Kernel Version", "কার্নেল ভার্সন (Kernel Version)", "कर्नेल वर्ज़न (Kernel Version)"), settings.resetKernel) {
                    onToggleResetOption("Kernel Version")
                }
                ResetOptionToggleRow(tr("Baseband", "বেসব্যান্ড ভার্সন (Baseband)", "बेसबैंड वर्ज़न (Baseband)"), settings.resetBaseband) {
                    onToggleResetOption("Baseband Version")
                }
                ResetOptionToggleRow(tr("Widevine ID", "ওয়াইডভাইন ডিআরএম (Widevine ID)", "वाइडवाइन आईडी (Widevine ID)"), settings.resetWidevineDrmId) {
                    onToggleResetOption("Widevine DRM ID")
                }
                ResetOptionToggleRow(tr("Location", "লোকেশন (Location)", "लोकेशन (Location)"), settings.resetLocation) {
                    onToggleResetOption("Region")
                }
                ResetOptionToggleRow(tr("Timezone", "টাইমজোন (Timezone)", "टाइमज़ोन (Timezone)"), settings.resetTimezone) {
                    onToggleResetOption("Timezone")
                }
                ResetOptionToggleRow(tr("User Agent", "ইউজার এজেন্ট (User Agent)", "यूज़र एजेंट (User Agent)"), settings.resetUserAgent, isLast = true) {
                    onToggleResetOption("User Agent")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Bottom Action Button (Save & Run in Background)
        if (!isTimerRunning) {
            Button(
                onClick = onStartTimer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_and_start_timer_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = CyberBg
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WaterDrop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr("Save & Run in Background", "সংরক্ষণ করুন ও ব্যাকগ্রাউন্ডে চালান", "सहेजें और बैकग्राउंड में चलाएं"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Button(
                onClick = onStopTimer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("stop_timer_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RedDanger,
                    contentColor = TextPrimary
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr("Disarm & Stop Background Timer", "ব্যাকগ্রাউন্ড টাইমার বন্ধ করুন", "बैकग्राउंड टाइमर बंद करें"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun ResetOptionToggleRow(
    title: String,
    checked: Boolean,
    isLast: Boolean = false,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = CyberBg,
                checkedTrackColor = NeonCyan,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = CyberCardElevated
            )
        )
    }
    if (!isLast) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(CyberBorder)
        )
    }
}
