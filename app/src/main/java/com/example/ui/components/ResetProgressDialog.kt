package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.engine.ResetProgressState
import com.example.data.model.StepStatus
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
fun AutoFitSingleLineText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxFontSize: TextUnit = 13.sp,
    minFontSize: TextUnit = 7.5.sp,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    var scaledSp by remember(text, maxFontSize) { mutableFloatStateOf(maxFontSize.value) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = scaledSp.sp,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        textAlign = textAlign,
        style = style.copy(lineHeight = (scaledSp + 2f).sp),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if ((result.hasVisualOverflow || result.didOverflowWidth) && scaledSp > minFontSize.value) {
                scaledSp = (scaledSp * 0.92f).coerceAtLeast(minFontSize.value)
            }
        }
    )
}

@Composable
fun ResetProgressDialog(
    state: ResetProgressState?,
    onDismiss: () -> Unit
) {
    if (state == null) return

    val currentLang = com.example.ui.i18n.currentAppLang()
    val completedCount = state.steps.count { it.status == StepStatus.SUCCESS }
    val totalActiveSteps = state.steps.count { it.status != StepStatus.SKIPPED }
    val extraStepsCount = (totalActiveSteps - 26).coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(state.currentStepIndex) {
        if (state.steps.isNotEmpty() && state.currentStepIndex in state.steps.indices) {
            try {
                listState.animateScrollToItem((state.currentStepIndex - 3).coerceAtLeast(0))
            } catch (_: Throwable) {}
        }
    }

    Dialog(
        onDismissRequest = {
            if (state.isComplete) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = state.isComplete,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f)
                .padding(12.dp)
                .cyberShimmerBorder(width = 1.45.dp, shape = RoundedCornerShape(20.dp), phaseOffset = 0.15f, accentColor = NeonCyan),
            shape = RoundedCornerShape(20.dp),
            color = CyberBg
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        AutoFitSingleLineText(
                            text = if (state.isComplete) {
                                com.example.ui.i18n.tr("Reset Complete!", "রিসেট সম্পন্ন!", "रीसेट पूर्ण!", currentLang)
                            } else {
                                com.example.ui.i18n.tr("Resetting Identity...", "আইডেন্টিটি রিসেট হচ্ছে...", "पहचान रीसेट हो रही है...", currentLang)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxFontSize = 16.sp,
                            minFontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (state.isComplete) EmeraldGreen else NeonCyan
                        )
                        AutoFitSingleLineText(
                            text = if (state.isComplete) {
                                if (extraStepsCount > 0) {
                                    com.example.ui.i18n.tr(
                                        "26 Core + $extraStepsCount Extra Deep Hardware Vectors (${state.totalSteps} Total)",
                                        "২৬টি কোর + $extraStepsCount অতিরিক্ত ডিপ হার্ডওয়্যার ভেক্টর (মোট ${state.totalSteps}টি)",
                                        "26 कोर + $extraStepsCount अतिरिक्त डीप हार्डवेयर वेक्टर (कुल ${state.totalSteps})",
                                        currentLang
                                    )
                                } else {
                                    com.example.ui.i18n.tr(
                                        "All ${state.totalSteps} privacy & hardware vectors randomized",
                                        "সকল ${state.totalSteps}টি প্রাইভেসি ও হার্ডওয়্যার ভেক্টর র‍্যান্ডমাইজ করা হয়েছে",
                                        "सभी ${state.totalSteps} गोपनीयता और हार्डवेयर वेक्टर रैंडमाइज़ किए गए",
                                        currentLang
                                    )
                                }
                            } else {
                                com.example.ui.i18n.tr(
                                    "Executing LSPosed & kernel wipe pipeline",
                                    "LSPosed এবং কার্নেল ওয়াইপ পাইপলাইন চলছে",
                                    "LSPosed और कर्नेल वाइप पाइपलाइन चल रही है",
                                    currentLang
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxFontSize = 11.5.sp,
                            minFontSize = 7.5.sp,
                            color = TextSecondary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(CyberCardElevated, RoundedCornerShape(8.dp))
                            .cyberShimmerBorder(width = 1.1.dp, shape = RoundedCornerShape(8.dp), phaseOffset = 0.42f, accentColor = NeonCyan)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "${state.totalElapsedMs}ms",
                            color = NeonCyan,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Steps List
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .background(CyberCard, RoundedCornerShape(14.dp))
                        .cyberShimmerBorder(width = 1.25.dp, shape = RoundedCornerShape(14.dp), phaseOffset = 0.65f)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(state.steps) { index, step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Status icon
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        when (step.status) {
                                            StepStatus.SUCCESS -> EmeraldGreen
                                            StepStatus.RUNNING -> NeonCyan.copy(alpha = 0.2f)
                                            StepStatus.FAILED -> RedDanger
                                            StepStatus.SKIPPED -> CyberCardElevated
                                            StepStatus.PENDING -> CyberCardElevated
                                        },
                                        CircleShape
                                    )
                                    .border(
                                        1.dp,
                                        when (step.status) {
                                            StepStatus.SUCCESS -> EmeraldGreen
                                            StepStatus.RUNNING -> NeonCyan
                                            StepStatus.FAILED -> RedDanger
                                            StepStatus.SKIPPED -> TextMuted.copy(alpha = 0.5f)
                                            StepStatus.PENDING -> CyberBorder
                                        },
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                when (step.status) {
                                    StepStatus.SUCCESS -> Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = CyberBg,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    StepStatus.RUNNING -> CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = NeonCyan
                                    )
                                    StepStatus.FAILED -> Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    StepStatus.SKIPPED -> Text(
                                        text = "—",
                                        fontSize = 11.sp,
                                        color = TextMuted,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    StepStatus.PENDING -> Text(
                                        text = "${step.stepNumber}",
                                        fontSize = 10.sp,
                                        color = TextMuted,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                AutoFitSingleLineText(
                                    text = step.name,
                                    maxFontSize = 13.sp,
                                    minFontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (step.status == StepStatus.SUCCESS) TextPrimary else TextSecondary
                                )
                                AutoFitSingleLineText(
                                    text = step.subtitle,
                                    maxFontSize = 11.sp,
                                    minFontSize = 7.2.sp,
                                    color = when (step.status) {
                                        StepStatus.SUCCESS -> EmeraldGreen.copy(alpha = 0.9f)
                                        StepStatus.SKIPPED -> NeonCyan.copy(alpha = 0.7f)
                                        else -> TextMuted
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            if (step.status == StepStatus.SUCCESS) {
                                Text(
                                    text = "${step.durationMs}ms",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            } else if (step.status == StepStatus.SKIPPED) {
                                Text(
                                    text = "SKIP",
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AutoFitSingleLineText(
                        text = if (extraStepsCount > 0) {
                            com.example.ui.i18n.tr(
                                "$completedCount/$totalActiveSteps steps successful (26 + $extraStepsCount Extra Unique)",
                                "$completedCount/$totalActiveSteps ধাপ সফল (২৬ + $extraStepsCount অতিরিক্ত ইউনিক)",
                                "$completedCount/$totalActiveSteps चरण सफल (26 + $extraStepsCount अतिरिक्त यूनिक)",
                                currentLang
                            )
                        } else {
                            com.example.ui.i18n.tr(
                                "$completedCount/$totalActiveSteps steps successful",
                                "$completedCount/$totalActiveSteps ধাপ সফল",
                                "$completedCount/$totalActiveSteps चरण सफल",
                                currentLang
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        color = if (completedCount == totalActiveSteps) EmeraldGreen else NeonCyan,
                        maxFontSize = 13.5.sp,
                        minFontSize = 8.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                AnimatedVisibility(
                    visible = state.isComplete,
                    enter = fadeIn() + slideInVertically()
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("reset_done_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = CyberBg
                        )
                    ) {
                        Text(
                            text = com.example.ui.i18n.tr("Done", "সম্পন্ন", "पूर्ण", currentLang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
