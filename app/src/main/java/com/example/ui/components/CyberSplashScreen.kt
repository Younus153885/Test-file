package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.i18n.tr
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.cyberShimmerBorder

@Composable
fun CyberSplashScreen(
    appName: String = "GhostDevice",
    statusMessage: String = tr("Loading secure system...", "সুরক্ষিত সিস্টেম লোড হচ্ছে...", "सुरक्षित सिस्टम लोड हो रहा है...")
) {
    val infiniteTransition = rememberInfiniteTransition(label = "splash")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0F2038),
                        Color(0xFF070D18),
                        Color(0xFF03060C)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            // Glowing Halo behind Logo
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(140.dp)
                    .scale(pulseScale)
            ) {
                Box(
                    modifier = Modifier
                        .size(136.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    NeonCyan.copy(alpha = glowAlpha),
                                    EmeraldGreen.copy(alpha = glowAlpha * 0.4f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Logo Card Container with Rotating Comet Border
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF0B1320))
                        .cyberShimmerBorder(
                            width = 2.4.dp,
                            shape = RoundedCornerShape(26.dp),
                            phaseOffset = 0f,
                            accentColor = NeonCyan
                        )
                        .shadow(16.dp, RoundedCornerShape(26.dp), spotColor = NeonCyan),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ghost_device_logo),
                        contentDescription = "GhostDevice Logo",
                        modifier = Modifier
                            .size(102.dp)
                            .clip(RoundedCornerShape(23.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // App Name with Cyber Gradient
            Text(
                text = "${appName.ifBlank { "GhostDevice" }} Pro",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = TextPrimary,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "HYPER-STEALTH KERNEL & IDENTITY SHIELD",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NeonCyan.copy(alpha = 0.85f),
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Minimalist Cyber Loading Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0B1B2B))
                    .border(1.dp, NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(EmeraldGreen)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = statusMessage,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFD6E4FF)
                )
            }
        }

        // Bottom Brand Signature
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "SYSTEM ENGINE • v40 HIGH PERFORMANCE",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary.copy(alpha = 0.6f),
                letterSpacing = 1.5.sp
            )
        }
    }
}
