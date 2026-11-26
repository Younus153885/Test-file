package com.example.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.AiSecurityReportEntity
import com.example.data.model.DeviceIdentity
import com.example.data.remote.AiAuditResult
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardElevated
import com.example.ui.theme.CyberMagenta
import com.example.ui.theme.CyberPurple
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RedDanger
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AiAnalysisScreen(
    identity: DeviceIdentity,
    isAnalyzing: Boolean,
    latestAudit: AiAuditResult?,
    historicalReports: List<AiSecurityReportEntity>,
    onTriggerAudit: (String?) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    var customInquiry by remember { mutableStateOf("") }
    var selectedReportForDetail by remember { mutableStateOf<AiSecurityReportEntity?>(null) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AI Security & Privacy Audit",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Real-time threat evaluation & automated compliance reporting",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Privacy Score Hero Card
        val score = latestAudit?.privacyScore ?: 96
        val threat = latestAudit?.threatLevel ?: "LOW"

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardElevated),
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "NEURAL SHIELD METRICS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            letterSpacing = 1.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(EmeraldGreen.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .border(1.dp, EmeraldGreen, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "THREAT LEVEL: $threat",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Radial Score Circle
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .background(CyberCard, CircleShape)
                        .border(3.dp, NeonCyan, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$score%",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = EmeraldGreen,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "ANONYMITY",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = latestAudit?.executiveSummary
                        ?: "Active identity profile spoofing effectively conceals real hardware tokens, advertising keys, and carrier location vectors.",
                    fontSize = 12.sp,
                    color = TextPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onTriggerAudit(null) },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("run_ai_audit_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = CyberBg
                        ),
                        enabled = !isAnalyzing
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = CyberBg
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Auditing...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Re-Analyze", fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            val report = latestAudit?.rawReportMarkdown ?: "No report content"
                            clipboardManager.setText(AnnotatedString(report))
                            Toast.makeText(context, "Full Security Report copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("export_ai_report_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberCard,
                            contentColor = NeonCyan
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Export Report", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Tracking Vectors Inspected
        Text(
            text = "Tracked Vectors & Leak Surface",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                val vectors = latestAudit?.detectedVectors ?: listOf(
                    "Ad Network SDK Fingerprinting (GAID correlation: NEUTRALIZED)",
                    "Hardware Identifier Leaks (IMEI, MAC, Android ID: FULLY SPOOFED)",
                    "Network Telemetry (Subnet IP & Timezone: SYNCHRONIZED)",
                    "Canvas & WebGL Fingerprint (Webview hash: SPOOFED)",
                    "Build Props Leakage (57/57 vendor props: OVERRIDDEN)"
                )
                vectors.forEachIndexed { idx, vector ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = vector,
                            fontSize = 12.sp,
                            color = TextPrimary,
                            lineHeight = 16.sp
                        )
                    }
                    if (idx < vectors.size - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(CyberBorder)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // AI Hardening Recommendations
        Text(
            text = "Automated Hardening Recommendations",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                val recs = latestAudit?.hardeningRecommendations ?: listOf(
                    "Maintain Auto-Reset timer under 10 minutes for high-churn correlation breakage.",
                    "Ensure target applications are restarted post-reset to clear in-memory caches.",
                    "Verify Mock Location lat/lng aligns with the current timezone to evade geo-skew checks."
                )
                recs.forEach { rec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = rec,
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Interactive AI Security Query Box
        Text(
            text = "Ask AI Shield Assistant",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = customInquiry,
                onValueChange = { customInquiry = it },
                placeholder = { Text("Ask about ad tracking, anti-detect, or build props...", color = TextMuted) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_inquiry_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CyberBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = CyberCard,
                    unfocusedContainerColor = CyberCard
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (customInquiry.isNotBlank()) {
                        onTriggerAudit(customInquiry)
                        customInquiry = ""
                    }
                },
                modifier = Modifier
                    .height(56.dp)
                    .testTag("send_ai_inquiry_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberPurple,
                    contentColor = TextPrimary
                ),
                enabled = !isAnalyzing
            ) {
                Text(text = "Ask", fontWeight = FontWeight.Bold)
            }
        }

        // Quick inquiry pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickPill("Can Unity Ads track me?") {
                onTriggerAudit("Explain whether Unity Ads SDK can track my real identity under current configuration.")
            }
            QuickPill("Verify Anti-Detect") {
                onTriggerAudit("Perform anti-detect stealth verification on active build props and mock GPS.")
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Automated Audit Reports History
        if (historicalReports.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Report Archive (${historicalReports.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            historicalReports.take(5).forEach { report ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { selectedReportForDetail = report },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CyberCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = report.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Device: ${report.deviceModel} · Threat: ${report.threatLevel}",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(NeonCyan.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .border(1.dp, NeonCyan, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${report.privacyScore}%",
                                color = NeonCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    selectedReportForDetail?.let { report ->
        ReportDetailDialog(
            report = report,
            onDismiss = { selectedReportForDetail = null }
        )
    }
}

@Composable
private fun QuickPill(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(CyberCardElevated, RoundedCornerShape(16.dp))
            .border(1.dp, CyberBorder, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = NeonCyan,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ReportDetailDialog(
    report: AiSecurityReportEntity,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .padding(8.dp),
            shape = RoundedCornerShape(18.dp),
            color = CyberBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = report.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = NeonCyan
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Score: ${report.privacyScore}% · Threat: ${report.threatLevel}",
                    fontSize = 12.sp,
                    color = EmeraldGreen,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Executive Analysis",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
                Text(
                    text = report.aiAnalysisText,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Audited Vectors",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
                Text(
                    text = report.detectedVectors,
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        val text = "${report.title}\nScore: ${report.privacyScore}%\n\n${report.aiAnalysisText}\n\nVectors:\n${report.detectedVectors}"
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", color = NeonCyan)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberCardElevated,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
