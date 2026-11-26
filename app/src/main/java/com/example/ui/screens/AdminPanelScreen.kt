package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.license.LicenseApiClient
import com.example.data.license.LicenseModel
import com.example.data.license.LicenseRepository
import com.example.data.license.PackageModel
import com.example.data.license.PublicSettings
import com.example.ui.components.SetupVideoDialog
import com.example.ui.components.extractYouTubeVideoId
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
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private val AmberWarn = Color(0xFFF59E0B)

private val AdminDarkTextFieldColors
    @Composable
    get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedContainerColor = CyberBgDarker,
        unfocusedContainerColor = CyberBgDarker,
        focusedBorderColor = NeonCyan,
        unfocusedBorderColor = CyberBorder,
        focusedLabelColor = NeonCyan,
        unfocusedLabelColor = TextSecondary,
        cursorColor = NeonCyan
    )

@Composable
fun AdminPanelScreen(
    licenseRepository: LicenseRepository,
    adminToken: String?,
    onLoginSuccess: (String, String) -> Unit,
    onLogout: () -> Unit,
    onClose: () -> Unit
) {
    val _lang = currentAppLang()
    BackHandler {
        onClose()
    }

    val context = LocalContext.current
    val auth = remember { Firebase.auth }
    var currentUser by remember { mutableStateOf(auth.currentUser) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    val isSuperAdmin = LicenseApiClient.isAuthorizedAdminEmail(currentUser?.email)

    // Full-screen opaque dark Surface consuming all touches so nothing underneath can ever be clicked
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        color = CyberBg
    ) {
        if (currentUser == null || !isSuperAdmin) {
            AdminGoogleLoginGateScreen(
                currentUser = currentUser,
                onLoginSuccess = { user ->
                    onLoginSuccess(user.uid, user.email ?: LicenseApiClient.SUPER_ADMIN_EMAIL)
                },
                onSignOutWrongAccount = {
                    auth.signOut()
                    onLogout()
                },
                onClose = onClose
            )
        } else {
            val user = currentUser!!
            LaunchedEffect(user.uid) {
                licenseRepository.apiClient.ensureAdminBootstrapped()
                licenseRepository.apiClient.seedDefaultsIfNeeded()
                if (adminToken.isNullOrBlank()) {
                    onLoginSuccess(user.uid, user.email ?: LicenseApiClient.SUPER_ADMIN_EMAIL)
                }
            }
            AdminDashboardMain(
                licenseRepository = licenseRepository,
                currentUser = user,
                adminToken = adminToken ?: user.uid,
                onLogout = {
                    auth.signOut()
                    onLogout()
                },
                onClose = onClose
            )
        }
    }
}

@Composable
private fun AdminGoogleLoginGateScreen(
    currentUser: FirebaseUser?,
    onLoginSuccess: (FirebaseUser) -> Unit,
    onSignOutWrongAccount: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(width = 1.6.dp, shape = RoundedCornerShape(24.dp), phaseOffset = 0.1f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LanguageSelectorChip()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(CyberCardElevated)
                            .cyberShimmerBorder(1.2.dp, CircleShape, phaseOffset = 0.35f)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    CyberPurple.copy(alpha = 0.28f),
                                    NeonCyan.copy(alpha = 0.2f)
                                )
                            )
                        )
                        .cyberShimmerBorder(1.6.dp, RoundedCornerShape(20.dp), phaseOffset = 0.5f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AdminPanelSettings,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = tr("GhostDevice Admin Panel", "GhostDevice এডমিন প্যানেল", "GhostDevice एडमिन पैनल"),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = tr(
                        "Sign in with the authorized Super Admin Google account to manage the Admin Panel",
                        "এডমিন প্যানেল নিয়ন্ত্রণ করতে অনুমোদিত সুপার এডমিন জিমেইল দিয়ে গুগল লগইন করুন",
                        "एडमिन पैनल नियंत्रित करने के लिए अधिकृत सुपर एडमिन जीमेल से गूगल लॉगिन करें"
                    ),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )

                Spacer(modifier = Modifier.height(18.dp))

                if (currentUser != null && !LicenseApiClient.isAuthorizedAdminEmail(currentUser.email)) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = RedDanger.copy(alpha = 0.14f)),
                        border = BorderStroke(1.dp, RedDanger.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = tr("Access Denied!", "অ্যাক্সেস অনুমোদিত নয়!", "एक्सेस की अनुमति नहीं है!"),
                                color = RedDanger,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val allowedList = LicenseApiClient.SUPER_ADMIN_EMAILS.joinToString(" / ")
                            Text(
                                text = tr(
                                    "Current account (${currentUser.email}) is not an admin. Only $allowedList can access the Admin Panel.",
                                    "বর্তমান জিমেইল (${currentUser.email}) এডমিন নয়। শুধুমাত্র $allowedList দিয়ে এডমিন প্যানেলে প্রবেশ করা যাবে।",
                                    "वर्तमान जीमेल (${currentUser.email}) एडमिन नहीं है। केवल $allowedList से एडमिन पैनल में प्रवेश किया जा सकता है।"
                                ),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 17.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onSignOutWrongAccount,
                                border = BorderStroke(1.dp, RedDanger),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = tr("Select Another Account", "অন্য জিমেইল নির্বাচন করুন", "दूसरा जीमेल चुनें"),
                                    color = RedDanger,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (!errorText.isNullOrBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = RedDanger.copy(alpha = 0.14f)),
                        border = BorderStroke(1.dp, RedDanger.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = errorText!!,
                            color = Color(0xFFFF8A8A),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Button(
                    onClick = {
                        isLoading = true
                        errorText = null
                        scope.launch {
                            val result = com.example.data.license.onGoogleSignInClicked(context)
                            isLoading = false
                            result.fold(
                                onSuccess = { email ->
                                    val user = Firebase.auth.currentUser
                                    if (user != null && LicenseApiClient.isAuthorizedAdminEmail(email)) {
                                        onLoginSuccess(user)
                                    } else {
                                        errorText = tr(
                                            "Not an authorized Admin Gmail: $email",
                                            "অনুমোদিত এডমিন জিমেইল নয়: $email",
                                            "अधिकृत एडमिन जीमेल नहीं है: $email"
                                        )
                                    }
                                },
                                onFailure = { err ->
                                    errorText = err.localizedMessage ?: tr(
                                        "Google sign-in failed",
                                        "গুগল লগইন ব্যর্থ হয়েছে",
                                        "गूगल लॉगिन विफल रहा"
                                    )
                                }
                            )
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("admin_google_sign_in_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberPurple,
                        contentColor = TextPrimary
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = NeonCyan,
                            strokeWidth = 2.2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            tr("Verifying Google Account...", "গুগল যাচাই করা হচ্ছে...", "गूगल सत्यापित किया जा रहा है..."),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = tr("Admin Sign-In with Google", "Google দিয়ে এডমিন লগইন করুন", "Google से एडमिन लॉगिन करें"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminDashboardMain(
    licenseRepository: LicenseRepository,
    currentUser: FirebaseUser,
    adminToken: String,
    onLogout: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiClient = licenseRepository.apiClient

    var selectedTab by remember { mutableIntStateOf(0) } // 0: ড্যাশবোর্ড, 1: লাইসেন্স কি, 2: প্যাকেজ সমূহ, 3: নোটিফিকেশন ও প্রোমো
    var isLoading by remember { mutableStateOf(true) }

    var licenses by remember { mutableStateOf<List<LicenseModel>>(emptyList()) }
    var packages by remember { mutableStateOf<List<PackageModel>>(emptyList()) }
    var settings by remember { mutableStateOf(PublicSettings()) }

    LaunchedEffect(currentUser.uid) {
        isLoading = true
        launch {
            apiClient.observeSettings()
                .catch { e ->
                    Toast.makeText(context, "Settings Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                .collect { s ->
                    settings = s
                }
        }
        launch {
            apiClient.observeActivePackages()
                .catch { e ->
                    Toast.makeText(context, "Packages Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                .collect { p ->
                    packages = p
                }
        }
        launch {
            apiClient.observeAllLicensesForAdmin()
                .catch { e ->
                    isLoading = false
                    Toast.makeText(context, "Licenses Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                .collect { l ->
                    licenses = l
                    licenseRepository.saveCachedLicenses(l)
                    isLoading = false
                }
        }
    }

    val refreshAll = {
        scope.launch {
            isLoading = true
            try {
                settings = apiClient.getSettings()
                packages = apiClient.getPackages()
                licenses = apiClient.getAllLicenses(adminToken)
                licenseRepository.saveCachedLicenses(licenses)
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    tr("Refresh Error: ${e.message}", "রিফ্রেশ ত্রুটি: ${e.message}", "रीफ़्रेश त्रुटि: ${e.message}"),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Admin Bar (Protected from Status/Network Bar overlap via statusBarsPadding)
        Surface(
            color = CyberCard,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
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
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(NeonCyan.copy(alpha = 0.14f))
                                .cyberShimmerBorder(1.3.dp, RoundedCornerShape(11.dp), phaseOffset = 0.12f),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = tr("GhostDevice Admin", "GhostDevice এডমিন", "GhostDevice एडमिन"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val dispName = currentUser.displayName ?: "Younus Ali"
                            Text(
                                text = tr("$dispName • Super Admin", "$dispName • সুপার এডমিন", "$dispName • सुपर एडमिन"),
                                fontSize = 11.sp,
                                color = NeonCyan,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LanguageSelectorChip()
                        AdminHeaderIconBtn(
                            icon = Icons.Default.Refresh,
                            tint = NeonCyan,
                            contentDescription = "Refresh",
                            onClick = { refreshAll() }
                        )
                        AdminHeaderIconBtn(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            tint = TextPrimary,
                            contentDescription = "Back to App",
                            onClick = onClose
                        )
                        AdminHeaderIconBtn(
                            icon = Icons.AutoMirrored.Filled.Logout,
                            tint = RedDanger,
                            contentDescription = "Logout",
                            onClick = onLogout
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Modern Dark Navigation Pill Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabs = listOf(
                        Triple(0, tr("Dashboard", "ড্যাশবোর্ড", "डैशबोर्ड"), Icons.Default.Dashboard),
                        Triple(1, tr("License Keys (${licenses.size})", "লাইসেন্স কি (${licenses.size})", "लाइसेंस की (${licenses.size})"), Icons.Default.Key),
                        Triple(2, tr("Packages (${packages.size})", "প্যাকেজ (${packages.size})", "पैकेज (${packages.size})"), Icons.Default.Inventory2),
                        Triple(3, tr("Notifications & Promo", "নোটিফিকেশন ও প্রোমো", "नोटिफिकेशन और प्रोमो"), Icons.Default.NotificationsActive),
                        Triple(4, tr("Setup Video", "সেটআপ ভিডিও", "सेटअप वीडियो"), Icons.Default.OndemandVideo)
                    )
                    tabs.forEach { (idx, label, icon) ->
                        val isSelected = selectedTab == idx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (isSelected) CyberPurple else CyberBgDarker
                                )
                                .cyberShimmerBorder(
                                    width = if (isSelected) 1.5.dp else 1.1.dp,
                                    shape = RoundedCornerShape(11.dp),
                                    phaseOffset = idx * 0.19f,
                                    accentColor = if (isSelected) NeonCyan else null
                                )
                                .clickable { selectedTab = idx }
                                .testTag("admin_tab_$idx")
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isSelected) NeonCyan else TextSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = label,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = CyberBorder, thickness = 1.dp)

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonCyan)
            }
        } else {
            when (selectedTab) {
                0 -> OverviewTabContent(
                    licenses = licenses,
                    packages = packages,
                    settings = settings,
                    onToggleLicenseSystem = { enabled ->
                        scope.launch {
                            val updated = settings.copy(licenseSystemEnabled = enabled)
                            settings = updated
                            val res = apiClient.saveFullSettings(updated)
                            if (res.isSuccess) {
                                Toast.makeText(
                                    context,
                                    if (enabled) {
                                        tr("License system enabled", "লাইসেন্স সিস্টেম চালু করা হয়েছে", "लाइसेंस सिस्टम चालू किया गया")
                                    } else {
                                        tr("License system disabled (Free access for all)", "লাইসেন্স সিস্টেম বন্ধ করা হয়েছে (সবার জন্য ফ্রি অ্যাক্সেস)", "लाइसेंस सिस्टम बंद किया गया (सभी के लिए फ्री एक्सेस)")
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    tr("Error: ${res.exceptionOrNull()?.message}", "ত্রুটি: ${res.exceptionOrNull()?.message}", "त्रुटि: ${res.exceptionOrNull()?.message}"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onSaveGeneralSettings = { appName, supportNum ->
                        scope.launch {
                            val updated = settings.copy(
                                appName = appName,
                                supportNumber = supportNum
                            )
                            val res = apiClient.saveFullSettings(updated)
                            if (res.isSuccess) {
                                settings = updated
                                Toast.makeText(
                                    context,
                                    tr("Settings saved", "সেটিংস সংরক্ষিত হয়েছে", "सेटिंग्स सहेजी गईं"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    tr("Failed to save settings: ${res.exceptionOrNull()?.message}", "সেটিংস সংরক্ষণ ব্যর্থ: ${res.exceptionOrNull()?.message}", "सेटिंग्स सहेजने में विफल: ${res.exceptionOrNull()?.message}"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onToggleSetupVideo = { enabled ->
                        if (enabled && settings.setupVideoUrl.isBlank()) {
                            Toast.makeText(
                                context,
                                tr(
                                    "Please add a YouTube video link first",
                                    "প্রথমে ইউটিউব ভিডিও লিংক যোগ করুন",
                                    "कृपया पहले YouTube वीडियो लिंक जोड़ें"
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                            selectedTab = 4
                        } else {
                            scope.launch {
                                val updated = settings.copy(setupVideoEnabled = enabled)
                                licenseRepository.saveLocalSetupVideoSettings(
                                    enabled = updated.setupVideoEnabled,
                                    title = updated.setupVideoTitle,
                                    url = updated.setupVideoUrl
                                )
                                settings = updated
                                apiClient.saveFullSettings(updated)
                                Toast.makeText(
                                    context,
                                    if (enabled) {
                                        tr("Setup video enabled", "সেটআপ ভিডিও চালু করা হয়েছে", "सेटअप वीडियो चालू किया गया")
                                    } else {
                                        tr("Setup video hidden", "সেটআপ ভিডিও বন্ধ করা হয়েছে", "सेटअप वीडियो बंद किया गया")
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    onNavigateToSetupVideo = { selectedTab = 4 },
                    onNavigateToLicenses = { selectedTab = 1 }
                )

                1 -> LicensesTabContent(
                    licenses = licenses,
                    packages = packages,
                    licenseRepository = licenseRepository,
                    adminToken = adminToken,
                    onRefresh = { refreshAll() }
                )

                2 -> PackagesTabContent(
                    packages = packages,
                    apiClient = apiClient,
                    adminToken = adminToken,
                    onRefresh = { refreshAll() }
                )

                3 -> NotificationsPromoTabContent(
                    settings = settings,
                    onSavePromoSettings = { updated ->
                        scope.launch {
                            val res = apiClient.saveFullSettings(updated)
                            if (res.isSuccess) {
                                settings = updated
                                Toast.makeText(
                                    context,
                                    tr("Notifications & Promo updated!", "নোটিফিকেশন ও প্রোমো আপডেট হয়েছে!", "नोटिफिकेशन और प्रोमो अपडेट किए गए!"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    tr("Update failed: ${res.exceptionOrNull()?.message}", "আপডেট ব্যর্থ: ${res.exceptionOrNull()?.message}", "अपडेट विफल: ${res.exceptionOrNull()?.message}"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )

                4 -> SetupVideoTabContent(
                    settings = settings,
                    onSaveSetupVideo = { updated ->
                        scope.launch {
                            licenseRepository.saveLocalSetupVideoSettings(
                                enabled = updated.setupVideoEnabled,
                                title = updated.setupVideoTitle,
                                url = updated.setupVideoUrl
                            )
                            settings = updated
                            apiClient.saveFullSettings(updated)
                            Toast.makeText(
                                context,
                                if (updated.setupVideoEnabled) {
                                    tr("Setup video enabled & saved!", "সেটআপ ভিডিও চালু ও সংরক্ষিত হয়েছে!", "सेटअप वीडियो चालू और सहेजा गया!")
                                } else {
                                    tr("Setup video settings saved!", "সেটআপ ভিডিও সেটিংস সংরক্ষিত হয়েছে!", "सेटअप वीडियो सेटिंग्स सहेजी गईं!")
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AdminHeaderIconBtn(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CyberBgDarker)
            .cyberShimmerBorder(1.1.dp, RoundedCornerShape(10.dp), phaseOffset = 0.3f, accentColor = tint)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun OverviewTabContent(
    licenses: List<LicenseModel>,
    packages: List<PackageModel>,
    settings: PublicSettings,
    onToggleLicenseSystem: (Boolean) -> Unit,
    onSaveGeneralSettings: (String, String) -> Unit,
    onToggleSetupVideo: (Boolean) -> Unit,
    onNavigateToSetupVideo: () -> Unit,
    onNavigateToLicenses: () -> Unit
) {
    var appNameInput by remember(settings.appName) { mutableStateOf(settings.appName) }
    var supportNumberInput by remember(settings.supportNumber) { mutableStateOf(settings.supportNumber) }

    val totalCount = licenses.size
    val activeDeviceBoundCount = licenses.count {
        it.status == "active" && !it.deviceId.isNullOrBlank()
    }
    val unusedCount = licenses.count {
        it.status == "inactive" || (it.status == "active" && it.deviceId.isNullOrBlank())
    }
    val suspendedCount = licenses.count { it.status == "suspended" || it.status == "expired" }
    val totalRevenue = licenses.filter { it.status == "active" && !it.deviceId.isNullOrBlank() }.sumOf { it.price }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Master Switch Card: License System Enable / Disable
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(
                    width = 1.4.dp,
                    shape = RoundedCornerShape(18.dp),
                    phaseOffset = 0.1f,
                    accentColor = if (settings.licenseSystemEnabled) EmeraldGreen else AmberWarn
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (settings.licenseSystemEnabled) EmeraldGreen else AmberWarn)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (settings.licenseSystemEnabled) {
                                tr("License Key System Active (ON)", "লাইসেন্স কি সিস্টেম সক্রিয় (ON)", "लाइसेंस की सिस्टम सक्रिय (ON)")
                            } else {
                                tr("License System Disabled (OFF)", "লাইসেন্স সিস্টেম ডিএক্টিভ (OFF)", "लाइसेंस सिस्टम निष्क्रिय (OFF)")
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = if (settings.licenseSystemEnabled) {
                            tr("A valid license key is required to access the app.", "অ্যাপে প্রবেশ করতে বৈধ লাইসেন্স কি প্রয়োজন হবে।", "ऐप में प्रवेश करने के लिए वैध लाइसेंस की आवश्यक होगी।")
                        } else {
                            tr("Anyone can currently access the app without a license key.", "বর্তমানে কোনো লাইসেন্স কি ছাড়াই সবাই সরাসরি অ্যাপে ঢুকতে পারবে।", "वर्तमान में कोई भी बिना लाइसेंस की के सीधे ऐप में प्रवेश कर सकता है।")
                        },
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 17.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = settings.licenseSystemEnabled,
                    onCheckedChange = onToggleLicenseSystem,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyberBg,
                        checkedTrackColor = EmeraldGreen,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = CyberBgDarker
                    )
                )
            }
        }

        // Quick Control Card: Setup Video Guide ON/OFF
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(
                    width = 1.4.dp,
                    shape = RoundedCornerShape(18.dp),
                    phaseOffset = 0.28f,
                    accentColor = if (settings.setupVideoEnabled && settings.setupVideoUrl.isNotBlank()) NeonCyan else null
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.OndemandVideo,
                            contentDescription = null,
                            tint = if (settings.setupVideoEnabled) NeonCyan else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (settings.setupVideoEnabled) {
                                tr("Setup Video Guide (ON)", "সেটআপ ভিডিও গাইড (ON)", "सेटअप वीडियो गाइड (ON)")
                            } else {
                                tr("Setup Video Guide (OFF)", "সেটআপ ভিডিও গাইড (OFF)", "सेटअप वीडियो गाइड (OFF)")
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = if (settings.setupVideoUrl.isNotBlank()) {
                            settings.setupVideoTitle.ifBlank { settings.setupVideoUrl }
                        } else {
                            tr(
                                "No YouTube link added yet. Tap Configure to set video.",
                                "এখনো কোনো ইউটিউব লিংক যোগ করা হয়নি। সেট করতে Configure চাপুন।",
                                "अभी कोई YouTube लिंक नहीं जोड़ा गया। सेट करने के लिए Configure टैप करें।"
                            )
                        },
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CyberPurple.copy(alpha = 0.22f),
                        modifier = Modifier
                            .cyberShimmerBorder(width = 1.1.dp, shape = RoundedCornerShape(8.dp), phaseOffset = 0.42f)
                            .clickable { onNavigateToSetupVideo() }
                            .testTag("overview_configure_setup_video_btn")
                    ) {
                        Text(
                            text = tr("Configure Video", "ভিডিও সেট করুন", "वीडियो सेट करें"),
                            color = NeonCyan,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = settings.setupVideoEnabled,
                    onCheckedChange = onToggleSetupVideo,
                    modifier = Modifier.testTag("overview_setup_video_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyberBg,
                        checkedTrackColor = NeonCyan,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = CyberBgDarker
                    )
                )
            }
        }

        // Stats Grid (2x2 Dark Cyber Cards)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                title = tr("Total Licenses", "মোট লাইসেন্স", "कुल लाइसेंस"),
                value = "$totalCount",
                accent = NeonCyan,
                onClick = onNavigateToLicenses
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = tr("Active Devices", "সক্রিয় ডিভাইস", "सक्रिय डिवाइस"),
                value = "$activeDeviceBoundCount",
                accent = EmeraldGreen,
                onClick = onNavigateToLicenses
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                title = tr("Unused Keys", "অব্যবহৃত কি", "अप्रयुक्त की"),
                value = "$unusedCount",
                accent = CyberPurple,
                onClick = onNavigateToLicenses
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = tr("Blocked / Expired", "ব্লকড / মেয়াদ শেষ", "ब्लॉक / समाप्त"),
                value = "$suspendedCount",
                accent = RedDanger,
                onClick = onNavigateToLicenses
            )
        }

        // Active Revenue & Packages Summary Banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(18.dp), phaseOffset = 0.45f, accentColor = CyberPurple),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardElevated)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = tr("Active License Value", "সক্রিয় লাইসেন্স মূল্যমান", "सक्रिय लाइसेंस मूल्य"),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${totalRevenue.toInt()} BDT",
                        color = NeonCyan,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(CyberPurple.copy(alpha = 0.2f))
                        .cyberShimmerBorder(1.1.dp, RoundedCornerShape(10.dp), phaseOffset = 0.6f, accentColor = CyberPurple)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = tr("Packages: ${packages.size}", "প্যাকেজ: ${packages.size} টি", "पैकेज: ${packages.size}"),
                        color = TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // General App & Support Contact Settings
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(18.dp), phaseOffset = 0.65f),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr("App & Support Configuration", "অ্যাপ ও সাপোর্ট কনফিগারেশন", "ऐप और सपोर्ट कॉन्फ़िगरेशन"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = appNameInput,
                    onValueChange = { appNameInput = it },
                    label = { Text(tr("App Title", "অ্যাপের নাম (App Title)", "ऐप का नाम (App Title)")) },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = supportNumberInput,
                    onValueChange = { supportNumberInput = it },
                    label = { Text(tr("WhatsApp / Support Number", "হোয়াটসঅ্যাপ / সাপোর্ট নাম্বার", "व्हाट्सएप / सपोर्ट नंबर")) },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = { onSaveGeneralSettings(appNameInput.trim(), supportNumberInput.trim()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberPurple)
                ) {
                    Text(
                        tr("Save Configuration", "কনফিগারেশন সংরক্ষণ করুন", "कॉन्फ़िगरेशन सहेजें"),
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    accent: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .cyberShimmerBorder(
                width = 1.4.dp,
                shape = RoundedCornerShape(16.dp),
                phaseOffset = ((title.hashCode() and 0xFF) / 255f),
                accentColor = accent
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberCard)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accent
            )
        }
    }
}

@Composable
private fun LicensesTabContent(
    licenses: List<LicenseModel>,
    packages: List<PackageModel>,
    licenseRepository: LicenseRepository,
    adminToken: String,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiClient = licenseRepository.apiClient

    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") } // ALL, ACTIVE, UNUSED, SUSPENDED
    var showCreateDialog by remember { mutableStateOf(false) }

    val filteredLicenses = remember(licenses, searchQuery, statusFilter) {
        licenses.filter { lic ->
            val isDeviceBoundActive = lic.status == "active" && !lic.deviceId.isNullOrBlank() && !licenseRepository.isDateExpired(lic.expiresAt)
            val isUnused = lic.status == "inactive" || (lic.status == "active" && lic.deviceId.isNullOrBlank())
            val isBlockedOrExpired = lic.status == "suspended" || lic.status == "expired" || (lic.status == "active" && !lic.deviceId.isNullOrBlank() && licenseRepository.isDateExpired(lic.expiresAt))

            val matchesFilter = when (statusFilter) {
                "ACTIVE" -> isDeviceBoundActive
                "UNUSED" -> isUnused
                "SUSPENDED" -> isBlockedOrExpired
                else -> true
            }
            val matchesQuery = searchQuery.isBlank() ||
                    lic.key.contains(searchQuery, ignoreCase = true) ||
                    (lic.deviceId?.contains(searchQuery, ignoreCase = true) == true) ||
                    lic.note.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    val activeCount = licenses.count { it.status == "active" && !it.deviceId.isNullOrBlank() && !licenseRepository.isDateExpired(it.expiresAt) }
    val unusedCount = licenses.count { it.status == "inactive" || (it.status == "active" && it.deviceId.isNullOrBlank()) }
    val blockedCount = licenses.count { it.status == "suspended" || it.status == "expired" || (it.status == "active" && !it.deviceId.isNullOrBlank() && licenseRepository.isDateExpired(it.expiresAt)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // Search Bar + Create Key Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        tr("Search key, device or note...", "কি, ডিভাইস বা নোট খুঁজুন...", "की, डिवाइस या नोट खोजें..."),
                        fontSize = 12.5.sp,
                        color = TextMuted
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                },
                singleLine = true,
                colors = AdminDarkTextFieldColors,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            )

            Button(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyberPurple),
                modifier = Modifier.height(52.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    tr("Create Key", "কি তৈরি", "की बनाएं"),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filter Chips Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filters = listOf(
                "ALL" to tr("All (${licenses.size})", "সকল (${licenses.size})", "सभी (${licenses.size})"),
                "ACTIVE" to tr("Active ($activeCount)", "সক্রিয় ($activeCount)", "सक्रिय ($activeCount)"),
                "UNUSED" to tr("Unused ($unusedCount)", "অব্যবহৃত ($unusedCount)", "अप्रयुक्त ($unusedCount)"),
                "SUSPENDED" to tr("Blocked/Expired ($blockedCount)", "ব্লক/মেয়াদ শেষ ($blockedCount)", "ब्लॉक/समाप्त ($blockedCount)")
            )
            filters.forEach { (code, label) ->
                val selected = statusFilter == code
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) NeonCyan.copy(alpha = 0.18f) else CyberCard,
                    border = BorderStroke(1.dp, if (selected) NeonCyan else CyberBorder),
                    modifier = Modifier.clickable { statusFilter = code }
                ) {
                    Text(
                        text = label,
                        color = if (selected) NeonCyan else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredLicenses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = tr("No license keys found", "কোনো লাইসেন্স কি পাওয়া যায়নি", "कोई लाइसेंस की नहीं मिली"),
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredLicenses, key = { it.id }) { lic ->
                    AdminLicenseItemCard(
                        license = lic,
                        licenseRepository = licenseRepository,
                        onCopyKey = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("License Key", lic.key))
                            Toast.makeText(
                                context,
                                tr("License key copied: ${lic.key}", "লাইসেন্স কি কপি হয়েছে: ${lic.key}", "लाइसेंस की कॉपी की गई: ${lic.key}"),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onToggleBlock = {
                            scope.launch {
                                val nextStatus = if (lic.status == "suspended") {
                                    if (lic.deviceId.isNullOrBlank()) "inactive" else "active"
                                } else {
                                    "suspended"
                                }
                                val res = apiClient.updateLicenseStatus(lic.id, nextStatus, adminToken)
                                if (res.isSuccess) {
                                    Toast.makeText(
                                        context,
                                        if (nextStatus == "suspended") {
                                            tr("License blocked", "লাইসেন্স ব্লক করা হয়েছে", "लाइसेंस ब्लॉक किया गया")
                                        } else {
                                            tr("License unblocked", "লাইসেন্স আনব্লক করা হয়েছে", "लाइसेंस अनब्लॉक किया गया")
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        tr("Error: ${res.exceptionOrNull()?.message}", "ত্রুটি: ${res.exceptionOrNull()?.message}", "त्रुटि: ${res.exceptionOrNull()?.message}"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onResetDevice = {
                            scope.launch {
                                val res = apiClient.resetLicenseDevice(lic.id)
                                if (res.isSuccess) {
                                    Toast.makeText(
                                        context,
                                        tr("Device unbound. Key can now be used on a new device.", "ডিভাইস আনবাইন্ড করা হয়েছে। কি-টি এখন নতুন ডিভাইসে ব্যবহারযোগ্য।", "डिवाइस अनबाइंड किया गया। की अब नए डिवाइस पर उपयोग के लिए तैयार है।"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        tr("Reset failed: ${res.exceptionOrNull()?.message}", "রিসেট ব্যর্থ: ${res.exceptionOrNull()?.message}", "रीसेट विफल: ${res.exceptionOrNull()?.message}"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onExtendDays = { extraDays ->
                            scope.launch {
                                val res = apiClient.extendLicenseDays(lic.id, extraDays, lic.expiresAt, adminToken)
                                if (res.isSuccess) {
                                    Toast.makeText(
                                        context,
                                        tr("Extended by +$extraDays days", "+$extraDays দিন মেয়াদ বাড়ানো হয়েছে", "+$extraDays दिन की अवधि बढ़ाई गई"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        tr("Extension failed: ${res.exceptionOrNull()?.message}", "মেয়াদ বৃদ্ধি ব্যর্থ: ${res.exceptionOrNull()?.message}", "अवधि बढ़ाने में विफल: ${res.exceptionOrNull()?.message}"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                val res = apiClient.deleteLicense(lic.id, adminToken)
                                if (res.isSuccess) {
                                    Toast.makeText(
                                        context,
                                        tr("License deleted", "লাইসেন্স ডিলিট করা হয়েছে", "लाइसेंस हटाया गया"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        tr("Delete failed: ${res.exceptionOrNull()?.message}", "ডিলিট ব্যর্থ: ${res.exceptionOrNull()?.message}", "हटाने में विफल: ${res.exceptionOrNull()?.message}"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateLicenseKeyDialog(
            packages = packages,
            onDismiss = { showCreateDialog = false },
            onCreate = { keysList, days, price, note ->
                scope.launch {
                    val res = apiClient.createLicenses(
                        keys = keysList,
                        days = days,
                        price = price,
                        maxVideoDuration = 0,
                        adminToken = adminToken,
                        note = note
                    )
                    if (res.isSuccess) {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("License Key", keysList.joinToString("\n")))
                        Toast.makeText(
                            context,
                            tr("${keysList.size} license(s) created & copied!", "${keysList.size} টি লাইসেন্স তৈরি ও কপি হয়েছে!", "${keysList.size} लाइसेंस बनाए और कॉपी किए गए!"),
                            Toast.LENGTH_SHORT
                        ).show()
                        showCreateDialog = false
                    } else {
                        Toast.makeText(
                            context,
                            tr("Failed to create license: ${res.exceptionOrNull()?.message}", "লাইসেন্স তৈরি ব্যর্থ: ${res.exceptionOrNull()?.message}", "लाइसेंस बनाने में विफल: ${res.exceptionOrNull()?.message}"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }
}

private fun formatExpiryAndRemaining(isoDate: String?, repo: LicenseRepository): String {
    val expMillis = repo.parseIsoDateMillis(isoDate) ?: return tr("Not set", "নির্ধারিত নয়", "निर्धारित नहीं")
    val outFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
    val dateStr = outFmt.format(Date(expMillis))
    val diff = expMillis - System.currentTimeMillis()
    if (diff <= 0L) {
        return "$dateStr (${tr("Expired", "মেয়াদ শেষ", "समाप्त")})"
    }
    val totalHours = diff / (1000L * 60L * 60L)
    val days = totalHours / 24L
    val remHours = totalHours % 24L
    val remMins = (diff / (1000L * 60L)) % 60L
    val remainingLabel = when {
        days > 0 -> tr("Left: ${days}d ${remHours}h", "বাকি: ${days} দিন ${remHours} ঘণ্টা", "शेष: ${days} दिन ${remHours} घंटे")
        remHours > 0 -> tr("Left: ${remHours}h ${remMins}m", "বাকি: ${remHours} ঘণ্টা ${remMins} মি.", "शेष: ${remHours} घंटे ${remMins} मि.")
        else -> tr("Left: ${remMins.coerceAtLeast(1)} mins", "বাকি: ${remMins.coerceAtLeast(1)} মিনিট", "शेष: ${remMins.coerceAtLeast(1)} मिनट")
    }
    return "$dateStr ($remainingLabel)"
}

@Composable
private fun AdminLicenseItemCard(
    license: LicenseModel,
    licenseRepository: LicenseRepository,
    onCopyKey: () -> Unit,
    onToggleBlock: () -> Unit,
    onResetDevice: () -> Unit,
    onExtendDays: (Int) -> Unit,
    onDelete: () -> Unit
) {
    val isSuspended = license.status == "suspended" || license.status == "blocked"
    val isUnused = !isSuspended && (license.status == "inactive" || license.deviceId.isNullOrBlank())
    val isExpired = !isSuspended && !isUnused && (license.status == "expired" || licenseRepository.isDateExpired(license.expiresAt))

    val statusColor = when {
        isSuspended -> RedDanger
        isExpired -> AmberWarn
        isUnused -> NeonCyan
        else -> EmeraldGreen
    }
    val statusLabel = when {
        isSuspended -> tr("Blocked", "ব্লকড (Blocked)", "ब्लॉक (Blocked)")
        isExpired -> tr("Expired", "মেয়াদ শেষ (Expired)", "समाप्त (Expired)")
        isUnused -> tr("Unused", "অব্যবহৃত (Unused)", "अप्रयुक्त (Unused)")
        else -> tr("Active", "সক্রিয় (Active)", "सक्रिय (Active)")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .cyberShimmerBorder(
                width = 1.4.dp,
                shape = RoundedCornerShape(16.dp),
                phaseOffset = ((license.id.hashCode() and 0xFF) / 255f),
                accentColor = statusColor
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberCard)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = license.key,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(CyberBgDarker)
                            .cyberShimmerBorder(1.dp, RoundedCornerShape(7.dp), phaseOffset = 0.25f)
                            .clickable { onCopyKey() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Key",
                            tint = NeonCyan,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .cyberShimmerBorder(1.1.dp, RoundedCornerShape(8.dp), phaseOffset = 0.45f, accentColor = statusColor)
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = statusLabel,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr(
                        "Validity: ${license.days} Days • Price: ${license.price.toInt()} BDT",
                        "মেয়াদ: ${license.days} দিন • মূল্য: ${license.price.toInt()} BDT",
                        "अवधि: ${license.days} दिन • मूल्य: ${license.price.toInt()} BDT"
                    ),
                    fontSize = 12.5.sp,
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        tint = if (license.deviceId.isNullOrBlank()) TextMuted else EmeraldGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (license.deviceId.isNullOrBlank()) {
                            tr("No Device Bound", "ডিভাইস যুক্ত নেই", "कोई डिवाइस जुड़ा नहीं")
                        } else {
                            "ID: ${license.deviceId.take(10)}..."
                        },
                        fontSize = 11.5.sp,
                        color = if (license.deviceId.isNullOrBlank()) TextSecondary else EmeraldGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = if (isUnused) {
                    tr(
                        "Will automatically run for ${license.days} days after activation",
                        "অ্যাক্টিভেশনের পর থেকে স্বয়ংক্রিয়ভাবে ${license.days} দিন চলবে",
                        "एक्टिवेशन के बाद स्वचालित रूप से ${license.days} दिनों तक चलेगा"
                    )
                } else {
                    tr(
                        "Expires: ${formatExpiryAndRemaining(license.expiresAt, licenseRepository)}",
                        "মেয়াদ শেষ: ${formatExpiryAndRemaining(license.expiresAt, licenseRepository)}",
                        "समाप्ति: ${formatExpiryAndRemaining(license.expiresAt, licenseRepository)}"
                    )
                },
                fontSize = 11.5.sp,
                color = if (isExpired) AmberWarn else TextSecondary,
                fontWeight = FontWeight.Medium
            )

            if (license.note.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tr("Note: ${license.note}", "নোট: ${license.note}", "नोट: ${license.note}"),
                    fontSize = 11.5.sp,
                    color = CyberPurple,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = CyberBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            var showCustomExtendDialog by remember { mutableStateOf(false) }
            var customExtendDaysInput by remember { mutableStateOf("15") }

            // Action Buttons Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // +1 Day Chip
                AdminActionChip(
                    text = tr("+1 Day", "+১ দিন", "+1 दिन"),
                    color = EmeraldGreen,
                    onClick = { onExtendDays(1) }
                )

                // +7 Days Chip
                AdminActionChip(
                    text = tr("+7 Days", "+৭ দিন", "+7 दिन"),
                    color = NeonCyan,
                    onClick = { onExtendDays(7) }
                )

                // +30 Days Chip
                AdminActionChip(
                    text = tr("+30 Days", "+৩০ দিন", "+30 दिन"),
                    color = CyberPurple,
                    onClick = { onExtendDays(30) }
                )

                // Custom Extend Days Chip
                AdminActionChip(
                    text = tr("Extend", "মেয়াদ বাড়ান", "अवधि बढ़ाएं"),
                    icon = Icons.Default.Add,
                    color = NeonCyan,
                    onClick = { showCustomExtendDialog = true }
                )

                // Device Unbind Chip (if bound to a device)
                if (!license.deviceId.isNullOrBlank()) {
                    AdminActionChip(
                        text = tr("Reset Device", "ডিভাইস রিসেট", "डिवाइस रीसेट"),
                        icon = Icons.Default.PhonelinkErase,
                        color = AmberWarn,
                        onClick = onResetDevice
                    )
                }

                // Block / Unblock Chip
                AdminActionChip(
                    text = if (isSuspended) {
                        tr("Unblock", "আনব্লক করুন", "अनब्लॉक करें")
                    } else {
                        tr("Deactivate / Block", "ডিএক্টিভ / ব্লক", "डीएकटिव / ब्लॉक")
                    },
                    icon = if (isSuspended) Icons.Default.CheckCircle else Icons.Default.Block,
                    color = if (isSuspended) EmeraldGreen else RedDanger,
                    onClick = onToggleBlock
                )

                // Delete Button
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RedDanger.copy(alpha = 0.14f))
                        .border(1.dp, RedDanger.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete License",
                        tint = RedDanger,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (showCustomExtendDialog) {
                Dialog(onDismissRequest = { showCustomExtendDialog = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = CyberCard),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = tr("Extend License Validity", "লাইসেন্সের মেয়াদ বাড়ান", "लाइसेंस की अवधि बढ़ाएं"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = tr(
                                    "Key: ${license.key} (All linked APKs and target apps will automatically extend)",
                                    "কি: ${license.key} (সংযুক্ত সকল APK ও টার্গেট অ্যাপের মেয়াদ স্বয়ংক্রিয়ভাবে বাড়বে)",
                                    "की: ${license.key} (सभी जुड़े APK और टार्गेट ऐप्स की अवधि स्वतः बढ़ जाएगी)"
                                ),
                                fontSize = 11.5.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = customExtendDaysInput,
                                onValueChange = { customExtendDaysInput = it.filter { c -> c.isDigit() } },
                                label = { Text(tr("How many days to add?", "কত দিন বাড়াতে চান?", "कितने दिन बढ़ाना चाहते हैं?")) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = AdminDarkTextFieldColors,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showCustomExtendDialog = false },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, CyberBorder)
                                ) {
                                    Text(tr("Cancel", "বাতিল", "रद्द करें"), color = TextSecondary)
                                }
                                Button(
                                    onClick = {
                                        val d = customExtendDaysInput.toIntOrNull()?.coerceIn(1, 36500) ?: 7
                                        showCustomExtendDialog = false
                                        onExtendDays(d)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberPurple)
                                ) {
                                    Text(
                                        tr("Extend", "মেয়াদ বাড়ান", "अवधि बढ़ाएं"),
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
    }
}

@Composable
private fun AdminActionChip(
    text: String,
    color: Color,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.14f))
            .cyberShimmerBorder(
                width = 1.1.dp,
                shape = RoundedCornerShape(9.dp),
                phaseOffset = ((text.hashCode() and 0xFF) / 255f),
                accentColor = color
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                color = color,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CreateLicenseKeyDialog(
    packages: List<PackageModel>,
    onDismiss: () -> Unit,
    onCreate: (List<String>, Int, Double, String?) -> Unit
) {
    var customKey by remember {
        mutableStateOf("GD-" + UUID.randomUUID().toString().uppercase().take(8))
    }
    var daysInput by remember { mutableStateOf("30") }
    var priceInput by remember { mutableStateOf("60") }
    var countInput by remember { mutableStateOf("1") }
    var noteInput by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .cyberShimmerBorder(width = 1.6.dp, shape = RoundedCornerShape(22.dp), phaseOffset = 0.2f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    text = tr("Create New License Key", "নতুন লাইসেন্স কি তৈরি করুন", "नई लाइसेंस की बनाएं"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Duration Presets
                Text(
                    text = tr("Quick Duration Select:", "দ্রুত মেয়াদ নির্বাচন:", "त्वरित अवधि चुनें:"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val dayPresets = listOf(
                        Triple(tr("1 Day", "১ দিন", "1 दिन"), 1, 20),
                        Triple(tr("7 Days", "৭ দিন", "7 दिन"), 7, 30),
                        Triple(tr("15 Days", "১৫ দিন", "15 दिन"), 15, 45),
                        Triple(tr("30 Days", "৩০ দিন", "30 दिन"), 30, 60),
                        Triple(tr("90 Days", "৯০ দিন", "90 दिन"), 90, 150),
                        Triple(tr("365 Days", "৩৬৫ দিন", "365 दिन"), 365, 500)
                    )
                    dayPresets.forEach { (label, d, p) ->
                        val isSelected = daysInput == d.toString()
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) CyberPurple else CyberBgDarker,
                            border = BorderStroke(1.dp, if (isSelected) NeonCyan else CyberBorder),
                            modifier = Modifier.clickable {
                                daysInput = d.toString()
                                priceInput = p.toString()
                            }
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) TextPrimary else TextSecondary,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (packages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = tr("Or Select from Package:", "অথবা প্যাকেজ থেকে সিলেক্ট করুন:", "या पैकेज से चुनें:"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        packages.forEach { pkg ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = CyberBgDarker,
                                border = BorderStroke(1.dp, CyberPurple.copy(alpha = 0.5f)),
                                modifier = Modifier.clickable {
                                    daysInput = pkg.durationDays.toString()
                                    priceInput = pkg.price.toInt().toString()
                                }
                            ) {
                                Text(
                                    text = "${pkg.name} (${pkg.durationDays}d • ${pkg.price.toInt()}৳)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NeonCyan,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = customKey,
                    onValueChange = { customKey = it.uppercase() },
                    label = { Text(tr("Custom License Key (Custom Name or Code)", "কাস্টম লাইসেন্স কি (ইচ্ছামতো নাম বা কোড)", "कस्टम लाइसेंस की (मनचाहा नाम या कोड)")) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Generate Random Key",
                            tint = NeonCyan,
                            modifier = Modifier.clickable {
                                customKey = "GD-" + UUID.randomUUID().toString().uppercase().take(8)
                            }
                        )
                    },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = daysInput,
                        onValueChange = { daysInput = it.filter { c -> c.isDigit() } },
                        label = { Text(tr("Validity (Days)", "মেয়াদ (দিন)", "अवधि (दिन)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = AdminDarkTextFieldColors,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = priceInput,
                        onValueChange = { priceInput = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text(tr("Price (BDT)", "মূল্য (BDT)", "मूल्य (BDT)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = AdminDarkTextFieldColors,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = countInput,
                        onValueChange = { countInput = it.filter { c -> c.isDigit() } },
                        label = { Text(tr("Quantity (1-50)", "কতটি কি (১-৫০)", "कितनी की (1-50)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = AdminDarkTextFieldColors,
                        modifier = Modifier.weight(0.45f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text(tr("Customer Name / Note (Optional)", "কাস্টমারের নাম / নোট (ঐচ্ছিক)", "ग्राहक का नाम / नोट (वैकल्पिक)")) },
                        singleLine = true,
                        colors = AdminDarkTextFieldColors,
                        modifier = Modifier.weight(0.55f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CyberBorder)
                    ) {
                        Text(tr("Cancel", "বাতিল", "रद्द करें"), color = TextSecondary, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            val days = (daysInput.toIntOrNull() ?: 30).coerceAtLeast(1)
                            val price = priceInput.toDoubleOrNull() ?: 0.0
                            val count = (countInput.toIntOrNull() ?: 1).coerceIn(1, 50)
                            val keysList = if (count == 1 && customKey.isNotBlank()) {
                                listOf(customKey.trim().uppercase())
                            } else {
                                (1..count).map {
                                    "GD-" + UUID.randomUUID().toString().uppercase().take(8)
                                }
                            }
                            onCreate(keysList, days, price, noteInput.trim().ifBlank { null })
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberPurple)
                    ) {
                        Text(
                            tr("Create & Copy", "তৈরি ও কপি করুন", "बनाएं और कॉपी करें"),
                            color = TextPrimary,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackagesTabContent(
    packages: List<PackageModel>,
    apiClient: LicenseApiClient,
    adminToken: String,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editingPkg by remember { mutableStateOf<PackageModel?>(null) }
    var pkgName by remember { mutableStateOf("") }
    var pkgDays by remember { mutableStateOf("30") }
    var pkgPrice by remember { mutableStateOf("60") }
    val defaultDesc = tr(
        "Full Device Protection & VIP Access",
        "ফুল ডিভাইস প্রোটেকশন ও ভিআইপি অ্যাক্সেস",
        "फुल डिवाइस प्रोटेक्शन और वीआईपी एक्सेस"
    )
    var pkgDesc by remember { mutableStateOf(defaultDesc) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Add / Edit Package Card (Zero Video References!)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(18.dp), phaseOffset = 0.18f),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (editingPkg == null) {
                        tr("Create New Package", "নতুন প্যাকেজ তৈরি করুন", "नया पैकेज बनाएं")
                    } else {
                        tr("Edit Package (${editingPkg?.name})", "প্যাকেজ এডিট করুন (${editingPkg?.name})", "पैकेज एडिट करें (${editingPkg?.name})")
                    },
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = NeonCyan
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = pkgName,
                    onValueChange = { pkgName = it },
                    label = { Text(tr("Package Name (e.g. VIP Monthly, Weekly Pack)", "প্যাকেজের নাম (যেমন: VIP Monthly, Weekly Pack)", "पैकेज का नाम (जैसे: VIP Monthly, Weekly Pack)")) },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pkgDays,
                        onValueChange = { pkgDays = it.filter { c -> c.isDigit() } },
                        label = { Text(tr("Validity (Days)", "মেয়াদ (দিন)", "अवधि (दिन)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = AdminDarkTextFieldColors,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = pkgPrice,
                        onValueChange = { pkgPrice = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text(tr("Price (BDT)", "মূল্য (BDT)", "मूल्य (BDT)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = AdminDarkTextFieldColors,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = pkgDesc,
                    onValueChange = { pkgDesc = it },
                    label = { Text(tr("Description (e.g. Full Device Protection & VIP Access)", "বিবরণ (যেমন: ফুল ডিভাইস প্রোটেকশন ও ভিআইপি অ্যাক্সেস)", "विवरण (जैसे: फुल डिवाइस प्रोटेक्शन और वीआईपी एक्सेस)")) },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (editingPkg != null) {
                        OutlinedButton(
                            onClick = {
                                editingPkg = null
                                pkgName = ""
                                pkgDays = "30"
                                pkgPrice = "60"
                                pkgDesc = defaultDesc
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CyberBorder)
                        ) {
                            Text(tr("Cancel", "বাতিল", "रद्द करें"), color = TextSecondary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            if (pkgName.isBlank()) {
                                Toast.makeText(
                                    context,
                                    tr("Enter package name", "প্যাকেজের নাম লিখুন", "पैकेज का नाम दर्ज करें"),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }
                            scope.launch {
                                val model = PackageModel(
                                    id = editingPkg?.id ?: "pkg_${System.currentTimeMillis()}",
                                    name = pkgName.trim(),
                                    durationDays = (pkgDays.toIntOrNull() ?: 30).coerceAtLeast(1),
                                    price = pkgPrice.toDoubleOrNull() ?: 0.0,
                                    currency = "BDT",
                                    maxVideoDuration = 0,
                                    description = LicenseApiClient.cleanPackageDescription(pkgDesc),
                                    active = true
                                )
                                val res = apiClient.savePackage(model, adminToken)
                                if (res.isSuccess) {
                                    Toast.makeText(
                                        context,
                                        tr("Package saved", "প্যাকেজ সংরক্ষিত হয়েছে", "पैकेज सहेजा गया"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    editingPkg = null
                                    pkgName = ""
                                    pkgDays = "30"
                                    pkgPrice = "60"
                                    pkgDesc = defaultDesc
                                    onRefresh()
                                } else {
                                    Toast.makeText(
                                        context,
                                        tr("Error: ${res.exceptionOrNull()?.message}", "ত্রুটি: ${res.exceptionOrNull()?.message}", "त्रुटि: ${res.exceptionOrNull()?.message}"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberPurple)
                    ) {
                        Text(
                            text = if (editingPkg == null) {
                                tr("+ Add Package", "+ প্যাকেজ যোগ করুন", "+ पैकेज जोड़ें")
                            } else {
                                tr("Update Package", "প্যাকেজ আপডেট করুন", "पैकेज अपडेट करें")
                            },
                            color = TextPrimary,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }

        Text(
            text = tr("Current Package List (${packages.size})", "বর্তমান প্যাকেজ তালিকা (${packages.size})", "वर्तमान पैकेज सूची (${packages.size})"),
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary
        )

        packages.forEach { pkg ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(16.dp), phaseOffset = ((pkg.id.hashCode() and 0xFF) / 255f)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pkg.name,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tr(
                                "${pkg.durationDays} Days • ${pkg.price.toInt()} ${pkg.currency}",
                                "${pkg.durationDays} দিন • ${pkg.price.toInt()} ${pkg.currency}",
                                "${pkg.durationDays} दिन • ${pkg.price.toInt()} ${pkg.currency}"
                            ),
                            fontSize = 13.sp,
                            color = NeonCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = LicenseApiClient.cleanPackageDescription(pkg.description),
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AdminHeaderIconBtn(
                            icon = Icons.Default.Edit,
                            tint = NeonCyan,
                            contentDescription = "Edit Package",
                            onClick = {
                                editingPkg = pkg
                                pkgName = pkg.name
                                pkgDays = pkg.durationDays.toString()
                                pkgPrice = pkg.price.toInt().toString()
                                pkgDesc = LicenseApiClient.cleanPackageDescription(pkg.description)
                            }
                        )
                        AdminHeaderIconBtn(
                            icon = Icons.Default.Delete,
                            tint = RedDanger,
                            contentDescription = "Delete Package",
                            onClick = {
                                scope.launch {
                                    val res = apiClient.deletePackage(pkg.id, adminToken)
                                    if (res.isSuccess) {
                                        Toast.makeText(
                                            context,
                                            tr("Package deleted", "প্যাকেজ মুছে ফেলা হয়েছে", "पैकेज हटाया गया"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onRefresh()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            tr("Delete failed: ${res.exceptionOrNull()?.message}", "ডিলিট ব্যর্থ: ${res.exceptionOrNull()?.message}", "हटाने में विफल: ${res.exceptionOrNull()?.message}"),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsPromoTabContent(
    settings: PublicSettings,
    onSavePromoSettings: (PublicSettings) -> Unit
) {
    var notifEnabled by remember(settings.notificationEnabled) { mutableStateOf(settings.notificationEnabled) }
    var notifTitle by remember(settings.notificationTitle) { mutableStateOf(settings.notificationTitle) }
    var notifMessage by remember(settings.notificationMessage) { mutableStateOf(settings.notificationMessage) }
    var promoBadge by remember(settings.promoBadge) { mutableStateOf(settings.promoBadge) }
    var promoBtnText by remember(settings.promoButtonText) { mutableStateOf(settings.promoButtonText) }
    var promoUrl by remember(settings.promoActionUrl) { mutableStateOf(settings.promoActionUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(18.dp), phaseOffset = 0.22f),
            shape = RoundedCornerShape(18.dp),
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
                            text = tr("In-App Notification & Promo Popup", "অ্যাপ নোটিফিকেশন ও প্রোমো পপআপ", "ऐप नोटिफिकेशन और प्रोमो पॉपअप"),
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = tr(
                                "When enabled, users will see your message and offer popup when opening the app",
                                "চালু থাকলে ব্যবহারকারীরা অ্যাপে ঢুকলে আপনার মেসেজ ও অফার দেখতে পাবে",
                                "चालू होने पर, उपयोगकर्ता ऐप खोलने पर आपका संदेश और ऑफ़र देखेंगे"
                            ),
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = notifEnabled,
                        onCheckedChange = { notifEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberBg,
                            checkedTrackColor = NeonCyan
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = promoBadge,
                    onValueChange = { promoBadge = it },
                    label = { Text(tr("Promo Badge (e.g. 50% OFF / Special Offer)", "প্রোমো ব্যাজ (যেমন: 50% ছাড় / ঈদ অফার)", "प्रोमो बैज (जैसे: 50% छूट / विशेष ऑफ़र)")) },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = notifTitle,
                    onValueChange = { notifTitle = it },
                    label = { Text(tr("Notification Title", "নোটিফিকেশন শিরোনাম (Title)", "नोटिफिकेशन शीर्षक (Title)")) },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = notifMessage,
                    onValueChange = { notifMessage = it },
                    label = { Text(tr("Notification Message", "নোটিফিকেশন বিস্তারিত বার্তা (Message)", "नोटिफिकेशन विस्तृत संदेश (Message)")) },
                    minLines = 3,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = promoBtnText,
                    onValueChange = { promoBtnText = it },
                    label = { Text(tr("Action Button Text (e.g. Buy Now)", "অ্যাকশন বাটন টেক্সট (যেমন: এখনই কিনুন)", "एक्शन बटन टेक्स्ट (जैसे: अभी खरीदें)")) },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = promoUrl,
                    onValueChange = { promoUrl = it },
                    label = { Text(tr("Action Link (e.g. https://wa.me/88017...)", "অ্যাকশন লিংক (যেমন: https://wa.me/88017...)", "एक्शन लिंक (जैसे: https://wa.me/88017...)")) },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        onSavePromoSettings(
                            settings.copy(
                                notificationEnabled = notifEnabled,
                                notificationTitle = notifTitle.trim(),
                                notificationMessage = notifMessage.trim(),
                                promoBadge = promoBadge.trim(),
                                promoButtonText = promoBtnText.trim(),
                                promoActionUrl = promoUrl.trim()
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberPurple)
                ) {
                    Text(
                        text = tr("Save Notification & Promo", "নোটিফিকেশন ও প্রোমো সংরক্ষণ করুন", "नोटिफिकेशन और प्रोमो सहेजें"),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.5.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupVideoTabContent(
    settings: PublicSettings,
    onSaveSetupVideo: (PublicSettings) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var videoEnabled by remember(settings.setupVideoEnabled) { mutableStateOf(settings.setupVideoEnabled) }
    var videoTitle by remember(settings.setupVideoTitle) { mutableStateOf(settings.setupVideoTitle) }
    var videoUrl by remember(settings.setupVideoUrl) { mutableStateOf(settings.setupVideoUrl) }
    var showPreviewDialog by remember { mutableStateOf(false) }

    val parsedVideoId = remember(videoUrl) { extractYouTubeVideoId(videoUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .cyberShimmerBorder(
                    width = 1.5.dp,
                    shape = RoundedCornerShape(18.dp),
                    phaseOffset = 0.35f,
                    accentColor = if (videoEnabled) NeonCyan else null
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.OndemandVideo,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tr(
                                    "Setup Video Guide (ON/OFF)",
                                    "সেটআপ ভিডিও গাইড (ON/OFF)",
                                    "सेटअप वीडियो गाइड (ON/OFF)"
                                ),
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tr(
                                "Show a prominent Setup Video button inside the app so users can watch the full tutorial in a popup",
                                "চালু থাকলে ব্যবহারকারীরা অ্যাপে ঢুকলেই সেটআপ ভিডিও বাটন দেখতে পাবে ও পপআপের মাধ্যমে পুরো ভিডিও দেখতে পারবে",
                                "चालू होने पर उपयोगकर्ताओं को ऐप के अंदर सेटअप वीडियो बटन दिखेगा और वे पॉपअप में पूरा वीडियो देख पाएंगे"
                            ),
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 17.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = videoEnabled,
                        onCheckedChange = { wantEnabled ->
                            val cleanUrl = videoUrl.trim()
                            val cleanTitle = videoTitle.trim()
                            if (wantEnabled && cleanUrl.isBlank()) {
                                Toast.makeText(
                                    context,
                                    tr(
                                        "Please paste a YouTube video link first",
                                        "প্রথমে ইউটিউব ভিডিও লিংক দিন",
                                        "कृपया पहले YouTube वीडियो लिंक दर्ज करें"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else if (wantEnabled && extractYouTubeVideoId(cleanUrl) == null) {
                                Toast.makeText(
                                    context,
                                    tr(
                                        "Please enter a valid YouTube video URL",
                                        "দয়া করে সঠিক ইউটিউব ভিডিও লিংক দিন",
                                        "कृपया सही YouTube वीडियो लिंक दर्ज करें"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                videoEnabled = wantEnabled
                                onSaveSetupVideo(
                                    settings.copy(
                                        setupVideoEnabled = wantEnabled,
                                        setupVideoTitle = cleanTitle,
                                        setupVideoUrl = cleanUrl
                                    )
                                )
                            }
                        },
                        modifier = Modifier.testTag("setup_video_enable_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberBg,
                            checkedTrackColor = NeonCyan,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = CyberBgDarker
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = videoTitle,
                    onValueChange = { videoTitle = it },
                    label = {
                        Text(
                            tr(
                                "Video Title (e.g. App Setup Tutorial)",
                                "ভিডিও শিরোনাম (যেমন: অ্যাপ সেটআপ গাইড)",
                                "वीडियो शीर्षक (जैसे: ऐप सेटअप गाइड)"
                            )
                        )
                    },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setup_video_title_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = videoUrl,
                    onValueChange = { newUrl ->
                        val wasBlank = videoUrl.isBlank()
                        videoUrl = newUrl
                        if (wasBlank && extractYouTubeVideoId(newUrl.trim()) != null) {
                            videoEnabled = true
                        }
                    },
                    label = {
                        Text(
                            tr(
                                "YouTube Video Link / URL",
                                "ইউটিউব ভিডিও লিংক (YouTube URL)",
                                "YouTube वीडियो लिंक (URL)"
                            )
                        )
                    },
                    placeholder = {
                        Text(
                            text = "https://youtu.be/... or https://youtube.com/watch?v=...",
                            color = TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                    if (clip.isNotBlank()) {
                                        videoUrl = clip
                                        if (extractYouTubeVideoId(clip) != null) {
                                            videoEnabled = true
                                        }
                                    } else {
                                        Toast.makeText(
                                            context,
                                            tr("Clipboard is empty", "ক্লিপবোর্ড খালি", "क्लिपबोर्ड खाली है"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste URL",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (videoUrl.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        videoUrl = ""
                                        videoEnabled = false
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear URL",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    },
                    singleLine = true,
                    colors = AdminDarkTextFieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setup_video_url_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Live YouTube Link Validation Badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (parsedVideoId != null) {
                        EmeraldGreen.copy(alpha = 0.13f)
                    } else {
                        CyberBgDarker
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .cyberShimmerBorder(
                            width = 1.2.dp,
                            shape = RoundedCornerShape(10.dp),
                            phaseOffset = 0.52f,
                            accentColor = if (parsedVideoId != null) EmeraldGreen else null
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (parsedVideoId != null) Icons.Default.CheckCircle else Icons.Default.OndemandVideo,
                            contentDescription = null,
                            tint = if (parsedVideoId != null) EmeraldGreen else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (parsedVideoId != null) {
                                tr(
                                    "Valid YouTube ID: $parsedVideoId",
                                    "সঠিক ইউটিউব ভিডিও আইডি: $parsedVideoId",
                                    "वैध YouTube ID: $parsedVideoId"
                                )
                            } else {
                                tr(
                                    "Supports youtube.com/watch?v=..., youtu.be/..., & shorts/...",
                                    "youtube.com/watch?v=..., youtu.be/... এবং shorts/... লিংক সাপোর্ট করে",
                                    "youtube.com/watch?v=..., youtu.be/... और shorts/... लिंक समर्थित हैं"
                                )
                            },
                            color = if (parsedVideoId != null) EmeraldGreen else TextSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (videoUrl.isBlank()) {
                                Toast.makeText(
                                    context,
                                    tr("Enter a YouTube link first", "প্রথমে ইউটিউব লিংক দিন", "पहले YouTube लिंक दर्ज करें"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                showPreviewDialog = true
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("preview_setup_video_btn"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = tr("Preview", "প্রিভিউ দেখুন", "प्रीव्यू"),
                            color = NeonCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = {
                            val cleanUrl = videoUrl.trim()
                            val cleanTitle = videoTitle.trim()
                            if (cleanUrl.isBlank()) {
                                Toast.makeText(
                                    context,
                                    tr(
                                        "Please paste a YouTube video link first",
                                        "প্রথমে ইউটিউব ভিডিও লিংক দিন",
                                        "कृपया पहले YouTube वीडियो लिंक दर्ज करें"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }
                            if (extractYouTubeVideoId(cleanUrl) == null) {
                                Toast.makeText(
                                    context,
                                    tr(
                                        "Please enter a valid YouTube video URL",
                                        "দয়া করে সঠিক ইউটিউব ভিডিও লিংক দিন",
                                        "कृपया सही YouTube वीडियो लिंक दर्ज करें"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }
                            // If a valid YouTube URL is provided and Save Video is clicked while switch was still OFF,
                            // automatically turn it ON so the Setup Video immediately activates!
                            val effectiveEnabled = if (!videoEnabled && !settings.setupVideoEnabled) {
                                videoEnabled = true
                                true
                            } else {
                                videoEnabled
                            }
                            onSaveSetupVideo(
                                settings.copy(
                                    setupVideoEnabled = effectiveEnabled,
                                    setupVideoTitle = cleanTitle,
                                    setupVideoUrl = cleanUrl
                                )
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("save_setup_video_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberPurple),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text(
                            text = tr("Save Video", "সংরক্ষণ করুন", "सहेजें"),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.5.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    if (showPreviewDialog && videoUrl.isNotBlank()) {
        SetupVideoDialog(
            videoUrl = videoUrl,
            videoTitle = videoTitle,
            onDismiss = { showPreviewDialog = false }
        )
    }
}
