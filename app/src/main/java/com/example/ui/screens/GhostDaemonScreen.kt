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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.engine.DaemonState
import com.example.data.model.DeviceIdentity
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardElevated
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RedDanger
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TerminalText
import com.example.ui.theme.TerminalYellow
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun GhostDaemonScreen(
    daemonState: DaemonState,
    identity: DeviceIdentity,
    onToggleDaemon: () -> Unit
) {
    val listState = rememberLazyListState()
    var showInspectorDialog by remember { mutableStateOf(false) }

    LaunchedEffect(daemonState.logs.size) {
        if (daemonState.logs.isNotEmpty()) {
            listState.animateScrollToItem(daemonState.logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // HUD Top Panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardElevated),
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(if (daemonState.isRunning) TerminalGreen else RedDanger, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GHOST_DAEMON // v2026",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = NeonCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(CyberBg, RoundedCornerShape(6.dp))
                            .border(1.dp, CyberBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "PAYLOADS: ${daemonState.payloadCount}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TerminalYellow,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HudMetricItem("STATE", daemonState.executionState, TerminalGreen)
                    HudMetricItem("TUNNEL", "UNITY:443", NeonCyan)
                    HudMetricItem("LATENCY", "${daemonState.latencyMs}ms", TerminalYellow)
                    HudMetricItem("INTEGRITY", "${daemonState.integrityPercent}%", TerminalGreen)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onToggleDaemon,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (daemonState.isRunning) RedDanger.copy(alpha = 0.2f) else EmeraldGreen.copy(alpha = 0.2f),
                    contentColor = if (daemonState.isRunning) RedDanger else EmeraldGreen
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (daemonState.isRunning) RedDanger else EmeraldGreen),
                modifier = Modifier
                    .height(38.dp)
                    .testTag("toggle_daemon_button")
            ) {
                Icon(
                    imageVector = if (daemonState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (daemonState.isRunning) "HALT_DAEMON" else "INITIATE_DAEMON",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Button(
                onClick = { showInspectorDialog = true },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCardElevated,
                    contentColor = NeonCyan
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder),
                modifier = Modifier
                    .height(38.dp)
                    .testTag("open_ad_inspector_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Inspect Ad Telemetry",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Terminal Console Window
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // Terminal Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = TerminalCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "root@xpos-system ~/logcat // engine_trace",
                            fontSize = 11.sp,
                            color = TerminalCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row {
                        Box(modifier = Modifier.size(8.dp).background(Color(0xFF555555), CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(modifier = Modifier.size(8.dp).background(Color(0xFF888888), CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(modifier = Modifier.size(8.dp).background(TerminalGreen, CircleShape))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(CyberBorder)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Real-time trace lines
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(daemonState.logs) { entry ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${entry.timestamp} ",
                                fontSize = 10.sp,
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = entry.text,
                                fontSize = 11.sp,
                                color = when (entry.colorType) {
                                    "CYAN" -> TerminalCyan
                                    "YELLOW" -> TerminalYellow
                                    "RED" -> TerminalRed
                                    else -> TerminalGreen
                                },
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showInspectorDialog) {
        AdTelemetryInspectorDialog(
            identity = identity,
            onDismiss = { showInspectorDialog = false }
        )
    }
}

@Composable
private fun HudMetricItem(
    label: String,
    value: String,
    accentColor: Color
) {
    Column {
        Text(
            text = label,
            fontSize = 9.sp,
            color = TextMuted,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun AdTelemetryInspectorDialog(
    identity: DeviceIdentity,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CyberBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = NeonCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "A message about your privacy",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "Build Info",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "This demonstrates how Unity Ads / AdMob SDK sees your spoofed identity parameters rather than your real device hardware.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = TerminalBg),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InspectorRow("userAgent", identity.userAgent)
                        InspectorRow("platform", "Android")
                        InspectorRow("campaign", identity.campaignId)
                        InspectorRow("osVersion", "14")
                        InspectorRow("sdk", "4.13.1")
                        InspectorRow("group", "0")
                        InspectorRow("webviewHash", identity.webviewHash)
                        InspectorRow("app", "com.rioo.runner")
                        InspectorRow("creativeId", identity.creativeId)
                        InspectorRow("seatId", identity.seatId)
                        InspectorRow("timestamp", "Sun, 29 Mar 2026 14:40:37 GMT")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = CyberBg
                    )
                ) {
                    Text(text = "Close Inspector", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "$label:",
            fontSize = 10.sp,
            color = TerminalYellow,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = TerminalGreen,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp
        )
    }
}
