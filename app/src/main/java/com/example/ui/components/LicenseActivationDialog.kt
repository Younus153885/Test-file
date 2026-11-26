package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.license.LicenseApiClient
import com.example.data.license.LicenseModel
import com.example.data.license.PackageModel
import com.example.data.license.PublicSettings
import com.example.ui.i18n.LanguageSelectorChip
import com.example.ui.i18n.currentAppLang
import com.example.ui.i18n.tr
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBgDarker
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardElevated
import com.example.ui.theme.CyberPurple
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RedDanger
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.cyberShimmerBorder

private val AmberWarn = Color(0xFFF59E0B)

@Composable
fun LicenseActivationDialog(
    packages: List<PackageModel>,
    settings: PublicSettings,
    isLoading: Boolean,
    errorMessage: String?,
    pendingTransferLicense: LicenseModel?,
    onVerifyKey: (String) -> Unit,
    onConfirmTransfer: (LicenseModel) -> Unit,
    onDismissTransfer: () -> Unit,
    onOpenAdminPanel: () -> Unit,
    onOpenSetupVideo: () -> Unit = {}
) {
    val _lang = currentAppLang()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    var licenseKeyInput by remember { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }

    val handleActivateClick = {
        val cleanKey = licenseKeyInput.trim().uppercase()
        if (cleanKey.isNotEmpty() && !isLoading) {
            focusManager.clearFocus()
            onVerifyKey(cleanKey)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() },
        color = CyberBg
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .cyberShimmerBorder(width = 1.45.dp, shape = RoundedCornerShape(24.dp), phaseOffset = 0.1f, accentColor = NeonCyan),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Row: Language Selector + Shield Brand Icon + Admin Access Button
                    Box(modifier = Modifier.fillMaxWidth()) {
                        LanguageSelectorChip(
                            modifier = Modifier.align(Alignment.TopStart)
                        )

                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFF0B1320))
                                .cyberShimmerBorder(
                                    width = 2.0.dp,
                                    shape = RoundedCornerShape(18.dp),
                                    phaseOffset = 0.15f,
                                    accentColor = NeonCyan
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.ghost_device_logo),
                                contentDescription = "GhostDevice Logo",
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(RoundedCornerShape(15.dp))
                            )
                        }

                        // Admin Panel Entry Button (Top-Right)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(CyberCardElevated)
                                .cyberShimmerBorder(width = 1.2.dp, shape = RoundedCornerShape(11.dp), phaseOffset = 0.35f, accentColor = CyberPurple)
                                .clickable { onOpenAdminPanel() }
                                .testTag("open_admin_panel_from_license_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AdminPanelSettings,
                                contentDescription = "Admin Panel",
                                tint = CyberPurple,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val appTitle = settings.appName.ifBlank { "GhostDevice" }
                    Text(
                        text = tr(
                            "$appTitle Pro License",
                            "$appTitle প্রো লাইসেন্স",
                            "$appTitle प्रो लाइसेंस"
                        ),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = tr(
                            "Enter your license key below to activate and use the app",
                            "অ্যাপটি ব্যবহার করার জন্য আপনার লাইসেন্স কি লিখে অ্যাক্টিভেট করুন",
                            "ऐप का उपयोग करने के लिए अपनी लाइसेंस की दर्ज करें और एक्टिवेट करें"
                        ),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Error Message Banner
                    if (!errorMessage.isNullOrBlank()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = RedDanger.copy(alpha = 0.12f)
                            ),
                            border = BorderStroke(1.dp, RedDanger.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = RedDanger,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = errorMessage,
                                    color = Color(0xFFFF8A8A),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    // Label
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tr("LICENSE KEY", "লাইসেন্স কি (LICENSE KEY)", "लाइसेंस की (LICENSE KEY)"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = tr("Paste", "পেস্ট করুন", "पेस्ट करें"),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberPurple,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    val clipText = clipboardManager.getText()?.text?.trim().orEmpty()
                                    if (clipText.isNotEmpty()) {
                                        licenseKeyInput = clipText.uppercase()
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Dark Cyber License Key Input Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(CyberBgDarker)
                            .cyberShimmerBorder(
                                width = if (isInputFocused) 1.8.dp else 1.25.dp,
                                shape = RoundedCornerShape(14.dp),
                                phaseOffset = 0.28f,
                                accentColor = if (isInputFocused) NeonCyan else CyberPurple
                            )
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = if (isInputFocused) NeonCyan else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (licenseKeyInput.isEmpty()) {
                                    Text(
                                        text = "XXXX-XXXX-XXXX-XXXX",
                                        color = TextMuted,
                                        fontSize = 14.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 1.2.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = licenseKeyInput,
                                    onValueChange = { licenseKeyInput = it.uppercase() },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 1.4.sp
                                    ),
                                    cursorBrush = SolidColor(NeonCyan),
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Characters,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { handleActivateClick() }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { isInputFocused = it.isFocused }
                                        .testTag("license_key_input")
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(CyberCardElevated)
                                    .cyberShimmerBorder(width = 1.1.dp, shape = RoundedCornerShape(9.dp), phaseOffset = 0.55f, accentColor = NeonCyan)
                                    .clickable {
                                        val clipText = clipboardManager.getText()?.text?.trim().orEmpty()
                                        if (clipText.isNotEmpty()) {
                                            licenseKeyInput = clipText.uppercase()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste Key",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Activate Button
                    Button(
                        onClick = { handleActivateClick() },
                        enabled = !isLoading && licenseKeyInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("activate_license_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberPurple,
                            contentColor = TextPrimary,
                            disabledContainerColor = CyberPurple.copy(alpha = 0.35f),
                            disabledContentColor = TextPrimary.copy(alpha = 0.5f)
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 6.dp,
                            pressedElevation = 2.dp
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = NeonCyan,
                                strokeWidth = 2.4.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = tr("Verifying...", "যাচাই করা হচ্ছে...", "सत्यापित किया जा रहा है..."),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tr("Activate Now", "অ্যাক্টিভেট করুন", "एक्टिवेट करें"),
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                        }
                    }

                    if (settings.setupVideoEnabled && settings.setupVideoUrl.isNotBlank()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        SetupVideoGuideBanner(
                            title = settings.setupVideoTitle,
                            compact = true,
                            onClick = onOpenSetupVideo
                        )
                    }

                    Spacer(modifier = Modifier.height(22.dp))
                    HorizontalDivider(color = CyberBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Packages Section Header
                    Text(
                        text = tr("Available Packages", "উপলব্ধ প্যাকেজ সমূহ", "उपलब्ध पैकेज"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = NeonCyan,
                        letterSpacing = 0.3.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Packages List (Dark Cyber Cards, Zero Video Text)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        packages.forEach { pkg ->
                            DarkPackageItemCard(pkg = pkg)
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Contact Section
                    Text(
                        text = tr(
                            "Contact to Purchase License Key",
                            "লাইসেন্স কি কিনতে যোগাযোগ করুন",
                            "लाइसेंस की खरीदने के लिए संपर्क करें"
                        ),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    val rawNumber = settings.supportNumber.ifBlank { "01717775962" }
                    val cleanDigits = rawNumber.replace(Regex("[^0-9]"), "")
                    val waNumber = if (cleanDigits.startsWith("88")) cleanDigits else "88$cleanDigits"

                    // WhatsApp Contact Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .cyberShimmerBorder(width = 1.3.dp, shape = RoundedCornerShape(14.dp), phaseOffset = 0.62f, accentColor = EmeraldGreen)
                            .clickable {
                                try {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://wa.me/$waNumber")
                                    )
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    val dialIntent = Intent(
                                        Intent.ACTION_DIAL,
                                        Uri.parse("tel:$rawNumber")
                                    )
                                    context.startActivity(dialIntent)
                                }
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = EmeraldGreen.copy(alpha = 0.12f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldGreen.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = "WhatsApp Support",
                                    tint = EmeraldGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = tr("WhatsApp / Direct Call", "WhatsApp / সরাসরি কল", "WhatsApp / डायरेक्ट कॉल"),
                                    color = EmeraldGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = rawNumber,
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.6.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Device Transfer Confirmation Dialog (Dark Mode)
    if (pendingTransferLicense != null) {
        Dialog(
            onDismissRequest = onDismissTransfer,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(22.dp), phaseOffset = 0.3f, accentColor = AmberWarn),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(AmberWarn.copy(alpha = 0.15f))
                            .border(1.dp, AmberWarn.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AmberWarn,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = tr(
                            "Active on Another Device!",
                            "অন্য ডিভাইসে সক্রিয় আছে!",
                            "दूसरे डिवाइस पर सक्रिय है!"
                        ),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = tr(
                            "This license key is currently active on another device. Would you like to log out from the previous device and transfer it to this device?",
                            "এই লাইসেন্স কি-টি বর্তমানে অন্য একটি ডিভাইসে অ্যাক্টিভ রয়েছে। আপনি কি আগের ডিভাইস থেকে লগআউট করে এই ডিভাইসে ট্রান্সফার করতে চান?",
                            "यह लाइसेंस की वर्तमान में किसी अन्य डिवाइस पर सक्रिय है। क्या आप पिछले डिवाइस से लॉगआउट करके इसे इस डिवाइस पर ट्रांसफर करना चाहते हैं?"
                        ),
                        fontSize = 13.5.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismissTransfer,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CyberBorder)
                        ) {
                            Text(
                                tr("No, Cancel", "না, বাতিল", "नहीं, रद्द करें"),
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = { onConfirmTransfer(pendingTransferLicense) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberPurple)
                        ) {
                            Text(
                                tr("Yes, Transfer", "হ্যাঁ, ট্রান্সফার", "हाँ, ट्रांसफर करें"),
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DarkPackageItemCard(pkg: PackageModel) {
    val priceFormatted = if (pkg.price % 1.0 == 0.0) {
        pkg.price.toInt().toString()
    } else {
        pkg.price.toString()
    }

    val cleanDescription = LicenseApiClient.cleanPackageDescription(pkg.description)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .cyberShimmerBorder(width = 1.25.dp, shape = RoundedCornerShape(16.dp), phaseOffset = (pkg.name.hashCode() * 0.13f) % 1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberBgDarker)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pkg.name,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberPurple.copy(alpha = 0.18f))
                        .border(1.dp, CyberPurple.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$priceFormatted ${pkg.currency}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = NeonCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(EmeraldGreen.copy(alpha = 0.14f))
                        .border(1.dp, EmeraldGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = tr(
                            "Validity: ${pkg.durationDays} Days",
                            "মেয়াদ: ${pkg.durationDays} দিন",
                            "अवधि: ${pkg.durationDays} दिन"
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldGreen
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = cleanDescription,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
