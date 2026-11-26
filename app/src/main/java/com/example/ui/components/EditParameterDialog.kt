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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.i18n.currentAppLang
import com.example.ui.i18n.tr
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

@Composable
fun EditParameterDialog(
    paramKey: String,
    paramLabel: String,
    paramCategory: String,
    currentValue: String,
    icon: ImageVector,
    isCurrentlyEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (newValue: String, disableAutoRandomize: Boolean) -> Unit
) {
    val _lang = currentAppLang()
    var editedValue by remember { mutableStateOf(currentValue) }
    var keepManualValueOff by remember { mutableStateOf(true) }
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 20.dp)
                .cyberShimmerBorder(width = 1.5.dp, shape = RoundedCornerShape(20.dp), phaseOffset = 0.18f, accentColor = NeonCyan),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(NeonCyan.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = tr(
                                    "Manual Edit: $paramLabel",
                                    "ম্যানুয়াল এডিট: $paramLabel",
                                    "मैनुअल एडिट: $paramLabel"
                                ),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tr(
                                    "Category: $paramCategory",
                                    "ক্যাটাগরি: $paramCategory",
                                    "कैटेगरी: $paramCategory"
                                ),
                                fontSize = 11.5.sp,
                                color = NeonCyan,
                                fontWeight = FontWeight.SemiBold
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
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Input Field
                OutlinedTextField(
                    value = editedValue,
                    onValueChange = { editedValue = it },
                    label = {
                        Text(
                            tr("Custom Value", "কাস্টম মান (Custom Value)", "कस्टम मान (Custom Value)"),
                            color = TextSecondary
                        )
                    },
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberBorder,
                        focusedContainerColor = CyberCardElevated,
                        unfocusedContainerColor = CyberCardElevated,
                        cursorColor = NeonCyan
                    ),
                    trailingIcon = {
                        if (editedValue.isNotEmpty()) {
                            IconButton(onClick = { editedValue = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Checkbox: Keep manual value and turn OFF switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (keepManualValueOff) EmeraldGreen.copy(alpha = 0.10f) else CyberCardElevated,
                            RoundedCornerShape(10.dp)
                        )
                        .cyberShimmerBorder(
                            width = 1.15.dp,
                            shape = RoundedCornerShape(10.dp),
                            phaseOffset = 0.44f,
                            accentColor = if (keepManualValueOff) EmeraldGreen else null
                        )
                        .clickable { keepManualValueOff = !keepManualValueOff }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = keepManualValueOff,
                        onCheckedChange = { keepManualValueOff = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = EmeraldGreen,
                            uncheckedColor = TextMuted,
                            checkmarkColor = CyberBg
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr(
                                "Keep Switch OFF - Lock Manual Value",
                                "বাটনটি বন্ধ (OFF) রাখুন - ম্যানুয়াল মান সংরক্ষিত থাকবে",
                                "बटन बंद (OFF) रखें - मैनुअल मान सुरक्षित रहेगा"
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (keepManualValueOff) EmeraldGreen else TextSecondary
                        )
                        Text(
                            text = tr(
                                "Even when clicking Randomize, this custom value will stay unchanged.",
                                "পরিবর্তন বাটনে চাপ দিলেও এই নির্দিষ্ট মানটি বদলাবে না, যা লিখেছেন তাই থাকবে।",
                                "बदलें बटन दबाने पर भी यह मान नहीं बदलेगा, जो आपने लिखा है वही रहेगा।"
                            ),
                            fontSize = 10.sp,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberCardElevated,
                            contentColor = TextSecondary
                        )
                    ) {
                        Text(
                            tr("Cancel", "বাতিল", "रद्द करें"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            val cleanVal = editedValue.trim()
                            if (cleanVal.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    tr("Value cannot be empty!", "মান খালি রাখা যাবে না!", "मान खाली नहीं रखा जा सकता!"),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }
                            onSave(cleanVal, keepManualValueOff)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1.3f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = CyberBg
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = CyberBg,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tr("Save Value", "সংরক্ষণ করুন", "सहेजें"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = CyberBg
                        )
                    }
                }
            }
        }
    }
}
