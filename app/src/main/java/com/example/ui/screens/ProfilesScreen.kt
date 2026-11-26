package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.DeviceProfileEntity
import com.example.ui.i18n.currentAppLang
import com.example.ui.i18n.tr
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardElevated
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.CyberPurple
import com.example.ui.theme.RedDanger
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.cyberShimmerBorder

@Composable
fun ProfilesScreen(
    profiles: List<DeviceProfileEntity>,
    onSaveCurrentProfile: (String) -> Unit,
    onApplyProfile: (DeviceProfileEntity) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onRenameProfile: (Long, String) -> Unit = { _, _ -> },
    onBackupOriginalDevice: () -> Unit = {},
    onRestoreOriginalDevice: () -> Unit = {},
    onExportBackupFile: () -> Unit = {},
    onSaveBackupToUri: (Uri) -> Unit = {},
    onImportBackupFile: (Uri) -> Unit = {},
    backupFileName: String = "ghostdevice_original_backup.json",
    currentIdentity: com.example.data.model.DeviceIdentity? = null,
    onExportSingleProfile: (DeviceProfileEntity) -> Unit = {},
    onExportCurrentIdentity: () -> Unit = {}
) {
    val _lang = currentAppLang()
    val context = LocalContext.current
    var showNameDialog by remember { mutableStateOf(false) }
    var profileToEdit by remember { mutableStateOf<DeviceProfileEntity?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            onSaveBackupToUri(uri)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportBackupFile(uri)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)
    ) {
        // 1. Top Header Card
        item(key = "header_card") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .cyberShimmerBorder(width = 1.35.dp, shape = RoundedCornerShape(16.dp), phaseOffset = 0.1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCard)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(CyberCardElevated, RoundedCornerShape(12.dp))
                            .cyberShimmerBorder(width = 1.25.dp, shape = RoundedCornerShape(12.dp), phaseOffset = 0.25f, accentColor = NeonCyan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = tr("Identity Profiles & Vault", "আইডেন্টিটি প্রোফাইল ও ভল্ট", "आइडेंटिटी प्रोफाइल और वॉल्ट"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 16.sp
                    )

                    Text(
                        text = tr(
                            "Hardware Backup Files & Identity Management",
                            "হার্ডওয়্যার ব্যাকআপ ফাইল ও আইডেন্টিটি ম্যানেজমেন্ট",
                            "हार्डवेयर बैकअप फ़ाइलें और आइडेंटिटी प्रबंधन"
                        ),
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Row 1: Save Current Identity Profile Action
                    Button(
                        onClick = { showNameDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("save_current_profile_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberPurple,
                            contentColor = androidx.compose.ui.graphics.Color.White
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tr("Save Current Identity Profile", "বর্তমান আইডেন্টিটি প্রোফাইল সেভ করুন", "वर्तमान आइडेंटिटी प्रोफाइल सहेजें"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Row 2: File Operations (Download Backup / Restore from File)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onExportBackupFile()
                                try {
                                    createDocumentLauncher.launch(backupFileName)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("export_backup_file_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = CyberBg
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("Download Backup", "ব্যাকআপ ডাউনলোড", "बैकअप डाउनलोड"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }

                        Button(
                            onClick = {
                                filePickerLauncher.launch("*/*")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("import_backup_file_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldGreen,
                                contentColor = CyberBg
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("Restore from File", "ফাইল থেকে রিস্টোর", "फ़ाइल से रीस्टोर"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Display Original Backup / Restoration Card
        item(key = "original_hardware_card") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .cyberShimmerBorder(width = 1.3.dp, shape = RoundedCornerShape(12.dp), phaseOffset = 0.32f, accentColor = EmeraldGreen)
                    .clickable {
                        onRestoreOriginalDevice()
                        Toast.makeText(
                            context,
                            tr("Restoring Real Hardware Profile...", "আসল হার্ডওয়্যার প্রোফাইল রিস্টোর হচ্ছে...", "असली हार्डवेयर प्रोफाइल रीस्टोर हो रहा है..."),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .testTag("restore_original_hardware_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCardElevated)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(EmeraldGreen.copy(alpha = 0.2f), CircleShape)
                            .cyberShimmerBorder(width = 1.2.dp, shape = CircleShape, phaseOffset = 0.48f, accentColor = EmeraldGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Restore Real Phone Hardware", "ফোনের আসল হার্ডওয়্যার রিস্টোর করুন", "फ़ोन का असली हार्डवेयर रीस्टोर करें"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = tr("Revert to original factory device info", "আসল ফ্যাক্টরি ডিভাইস তথ্যে ফিরে যান", "मूल फ़ैक्टरी डिवाइस जानकारी पर वापस जाएं"),
                            fontSize = 11.sp,
                            color = EmeraldGreen
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = EmeraldGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 2. Current Modified Active Profile Preview Card
        currentIdentity?.let { identity ->
            item(key = "current_active_profile_card") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .cyberShimmerBorder(width = 1.35.dp, shape = RoundedCornerShape(12.dp), phaseOffset = 0.55f, accentColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CyberCardElevated)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(NeonCyan.copy(alpha = 0.15f), CircleShape)
                                        .cyberShimmerBorder(width = 1.2.dp, shape = CircleShape, phaseOffset = 0.72f, accentColor = NeonCyan),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Devices,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = tr("Current Modified Active Profile", "বর্তমান পরিবর্তিত সক্রিয় প্রোফাইল", "वर्तमान संशोधित सक्रिय प्रोफाइल"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = tr("Unsaved / Running Active Config", "আনসেভড / চলমান সক্রিয় কনফিগ", "अनसेव्ड / चल रहा सक्रिय कॉन्फ़िग"),
                                        fontSize = 10.sp,
                                        color = NeonCyan
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Render Active fields in a clean layout
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CyberBg.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .cyberShimmerBorder(width = 1.dp, shape = RoundedCornerShape(8.dp), phaseOffset = 0.66f)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ProfileDetailRow(tr("Device Model", "ডিভাইস মডেল", "डिवाइस मॉडल"), "${identity.deviceModel} (${identity.manufacturer})")
                            ProfileDetailRow("Android ID", identity.androidId)
                            ProfileDetailRow("IMEI", identity.imei)
                            ProfileDetailRow("MAC Address", identity.macAddress)
                            ProfileDetailRow("Bluetooth MAC", identity.bluetoothAddress)
                            ProfileDetailRow("Build Number", identity.buildNumber)
                            ProfileDetailRow("Kernel", identity.kernelVersion.take(30) + "...")
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onExportCurrentIdentity() },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan,
                                    contentColor = CyberBg
                                ),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(tr("Download Profile", "প্রোফাইল ডাউনলোড", "प्रोफाइल डाउनलोड"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { showNameDialog = true },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberPurple,
                                    contentColor = androidx.compose.ui.graphics.Color.White
                                ),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(tr("Save to Vault", "ভল্টে সেভ করুন", "वॉल्ट में सहेजें"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (profiles.isNotEmpty()) {
            item(key = "saved_profiles_title") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tr("Saved Profiles Vault (${profiles.size})", "সংরক্ষিত প্রোফাইল ভল্ট (${profiles.size})", "सहेजे गए प्रोफाइल वॉल्ट (${profiles.size})"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 14.sp
                )
            }

            items(profiles, key = { it.id }) { profile ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .cyberShimmerBorder(width = 1.25.dp, shape = RoundedCornerShape(12.dp), phaseOffset = (profile.id * 0.13f) % 1f)
                        .clickable {
                            onApplyProfile(profile)
                            Toast.makeText(
                                context,
                                tr("Applying ${profile.profileName}...", "${profile.profileName} প্রয়োগ করা হচ্ছে...", "${profile.profileName} लागू किया जा रहा है..."),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .testTag("profile_card_${profile.id}"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CyberCard)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(CyberCardElevated, RoundedCornerShape(8.dp))
                                .cyberShimmerBorder(width = 1.1.dp, shape = RoundedCornerShape(8.dp), phaseOffset = ((profile.id + 3) * 0.17f) % 1f, accentColor = NeonCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.profileName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Model: ${profile.deviceModel} (${profile.manufacturer})",
                                fontSize = 11.sp,
                                color = NeonCyan,
                                maxLines = 1
                            )
                            Text(
                                text = "Build Num: ${profile.buildNumber} · Device ID: ${profile.deviceId}",
                                fontSize = 10.sp,
                                color = TextSecondary,
                                maxLines = 1
                            )
                            Text(
                                text = "Kernel: ${profile.kernelVersion.take(20)} · SoC: ${profile.socHardware.take(16)}",
                                fontSize = 10.sp,
                                color = TextMuted,
                                maxLines = 1
                            )
                            Text(
                                text = "IMEI: ${profile.imei.take(12)}... · ICCID: ${profile.simIccid.take(10)}...",
                                fontSize = 10.sp,
                                color = TextMuted,
                                maxLines = 1
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = { profileToEdit = profile },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Profile Name",
                                    tint = EmeraldGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = { onExportSingleProfile(profile) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download Saved Profile",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = { onDeleteProfile(profile.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Profile",
                                    tint = RedDanger,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNameDialog) {
        var profileName by remember { mutableStateOf(currentIdentity?.deviceModel ?: "Device_${System.currentTimeMillis() % 100000}") }
        Dialog(onDismissRequest = { showNameDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(18.dp), phaseOffset = 0.2f),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CyberBg)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = tr("Name Identity Profile", "আইডেন্টিটি প্রোফাইলের নাম দিন", "आइडेंटिटी प्रोफाइल का नाम दें"),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("Profile Name", "প্রোফাইলের নাম", "प्रोफाइल का नाम")) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CyberBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showNameDialog = false }) {
                            Text(tr("Cancel", "বাতিল", "रद्द करें"), color = TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onSaveCurrentProfile(profileName)
                                showNameDialog = false
                                Toast.makeText(
                                    context,
                                    tr("Profile saved to Vault!", "প্রোফাইল ভল্টে সেভ হয়েছে!", "प्रोफाइल वॉल्ट में सहेजा गया!"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = CyberBg
                            )
                        ) {
                            Text(tr("Save", "সেভ করুন", "सहेजें"), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (profileToEdit != null) {
        var editNameText by remember { mutableStateOf(profileToEdit?.profileName ?: "") }
        Dialog(onDismissRequest = { profileToEdit = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .cyberShimmerBorder(width = 1.4.dp, shape = RoundedCornerShape(18.dp), phaseOffset = 0.4f),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CyberBg)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = tr("Rename Profile Name", "প্রোফাইলের নাম পরিবর্তন করুন", "प्रोफाइल का नाम बदलें"),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editNameText,
                        onValueChange = { editNameText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("New Profile Name", "নতুন প্রোফাইলের নাম", "नया प्रोफाइल नाम")) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CyberBorder
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { profileToEdit = null }) {
                            Text(tr("Cancel", "বাতিল", "रद्द करें"), color = TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                profileToEdit?.let {
                                    if (editNameText.isNotBlank()) {
                                        onRenameProfile(it.id, editNameText)
                                        Toast.makeText(
                                            context,
                                            tr("Profile renamed successfully!", "প্রোফাইলের নাম পরিবর্তন হয়েছে!", "प्रोफाइल का नाम सफलतापूर्वक बदला गया!"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                profileToEdit = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = CyberBg
                            )
                        ) {
                            Text(tr("Save", "সেভ করুন", "सहेजें"), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 10.sp, color = TextMuted)
        Text(
            text = value,
            fontSize = 10.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp)
        )
    }
}
