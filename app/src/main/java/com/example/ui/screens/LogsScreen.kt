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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ResetLogEntity
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
fun LogsScreen(
    logs: List<ResetLogEntity>,
    onClearAllLogs: () -> Unit
) {
    val _lang = currentAppLang()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = tr("Reset Logs", "রিসেট লগসমূহ", "रीसेट लॉग"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = tr(
                        "Audit trail of all executed identity purges",
                        "সম্পন্ন হওয়া সকল আইডেন্টিটি রিসেটের ইতিহাস",
                        "सभी निष्पादित आइडेंटिटी रीसेट का इतिहास"
                    ),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            if (logs.isNotEmpty()) {
                TextButton(
                    onClick = onClearAllLogs,
                    modifier = Modifier.testTag("clear_all_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = RedDanger,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = tr("Clear All Logs", "সব লগ মুছুন", "सभी लॉग साफ़ करें"),
                        color = RedDanger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = tr("No reset logs recorded yet", "এখনো কোনো রিসেট লগ রেকর্ড হয়নি", "अभी तक कोई रीसेट लॉग रिकॉर्ड नहीं हुआ"),
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                    Text(
                        text = tr(
                            "Press the reset button on Dashboard or run Timer to generate logs",
                            "লগ তৈরি করতে ড্যাশবোর্ডে রিসেট বাটন চাপুন অথবা টাইমার চালান",
                            "लॉग जनरेट करने के लिए डैशबोर्ड पर रीसेट बटन दबाएं या टाइमर चलाएं"
                        ),
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    ResetLogCard(log = log)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ResetLogCard(log: ResetLogEntity) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .cyberShimmerBorder(width = 1.25.dp, shape = RoundedCornerShape(14.dp), phaseOffset = (log.id * 0.14f) % 1f)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CyberCard)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = log.formattedDate,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "${log.totalDurationMs}ms total",
                        fontSize = 11.sp,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .background(EmeraldGreen.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .cyberShimmerBorder(width = 1.1.dp, shape = RoundedCornerShape(8.dp), phaseOffset = ((log.id + 2) * 0.19f) % 1f, accentColor = EmeraldGreen)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${log.successfulSteps}/${log.totalSteps} ${log.status}",
                        color = EmeraldGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(CyberBorder)
                )
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = tr("Target Model: ${log.deviceModel}", "টার্গেট মডেল: ${log.deviceModel}", "टार्गेट मॉडल: ${log.deviceModel}"),
                    fontSize = 12.sp,
                    color = NeonCyan,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = tr("Location: ${log.location} (IP: ${log.ipAddress})", "লোকেশন: ${log.location} (IP: ${log.ipAddress})", "लोकेशन: ${log.location} (IP: ${log.ipAddress})"),
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Text(
                    text = "Old ID: ${log.oldAndroidId}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )
                Text(
                    text = "New ID: ${log.newAndroidId}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = EmeraldGreen
                )
            }
        }
    }
}
