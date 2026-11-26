package com.example.ui.theme

import android.content.Context
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Global persisted toggle for GhostDevice's animated comet border effect (strictly inside GhostDevice app).
 * Defaults to ON (true); turning OFF stops all per-frame shimmer animations so low-end devices run smoothly.
 */
object CyberShimmerPrefs {
    private const val PREFS_NAME = "ghost_ui_prefs"
    private const val KEY_COMET_ENABLED = "comet_shimmer_enabled"

    var enabled by mutableStateOf(true)
        private set

    fun init(context: Context) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            enabled = prefs.getBoolean(KEY_COMET_ENABLED, true)
        } catch (_: Throwable) {
            enabled = true
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_COMET_ENABLED, value).apply()
        } catch (_: Throwable) {}
    }

    fun toggle(context: Context): Boolean {
        val next = !enabled
        setEnabled(context, next)
        return next
    }
}

/**
 * Holds a stable State<Float> reference so animated comet borders read phase ONLY in the
 * Compose Draw phase (zero recompositions per frame across the entire UI tree).
 */
val LocalCyberShimmerEnabled = compositionLocalOf { true }
val LocalCyberShimmerPhaseState = compositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }
val LocalCyberShimmerPhase = compositionLocalOf { 0f }
val LocalCyberSparklePulse = compositionLocalOf { 1f }

private val COMET_STOPS = floatArrayOf(
    0.000f,
    0.825f,
    0.865f,
    0.905f,
    0.940f,
    0.968f,
    0.986f,
    0.995f,
    1.000f
)

internal fun steadyBorderColorFor(effAlpha: Float, accentColor: Color?): Color {
    return if (accentColor != null) {
        lerp(Color(0xFF23354D), accentColor, 0.36f)
            .copy(alpha = (effAlpha * 0.78f).coerceIn(0.45f, 0.88f))
    } else {
        Color(0xFF253852).copy(alpha = (effAlpha * 0.85f).coerceIn(0.50f, 0.90f))
    }
}

internal fun buildCometColors(
    effAlpha: Float,
    accentColor: Color?
): IntArray {
    val steadyBorderColor = steadyBorderColorFor(effAlpha, accentColor)
    val tail1 = CyberPurple.copy(alpha = effAlpha * 0.85f).toArgb()
    val tail2 = CyberMagenta.copy(alpha = effAlpha * 0.92f).toArgb()
    val tail3 = EmeraldGreen.copy(alpha = effAlpha * 0.95f).toArgb()
    val neck = (accentColor ?: NeonCyan).copy(alpha = effAlpha).toArgb()
    val head = Color(0xFFFFFFFF).copy(alpha = (effAlpha * 0.98f).coerceAtMost(1f)).toArgb()
    val base = steadyBorderColor.toArgb()

    return intArrayOf(
        base,
        base,
        tail1,
        tail2,
        tail3,
        neck,
        head,
        neck,
        base
    )
}

@Composable
fun ProvideCyberShimmer(content: @Composable () -> Unit) {
    val isEnabled = CyberShimmerPrefs.enabled
    val phaseState: State<Float> = if (isEnabled) {
        val transition = rememberInfiniteTransition(label = "global_cyber_comet")
        // Slow, silky-smooth 7.2-second orbit; pass the State<Float> object itself without reading .value
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 7200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "comet_orbit_phase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    CompositionLocalProvider(
        LocalCyberShimmerEnabled provides isEnabled,
        LocalCyberShimmerPhaseState provides phaseState,
        LocalCyberSparklePulse provides if (isEnabled) 1f else 0f,
        content = content
    )
}

/**
 * Fallback ShaderBrush for legacy BorderStroke callers (not recommended in scrolling lists).
 */
private class RotatingCometBrush(
    private val angleStep: Int,
    private val colors: IntArray
) : ShaderBrush() {

    override fun createShader(size: Size): Shader {
        val cx = (size.width / 2f).coerceAtLeast(1f)
        val cy = (size.height / 2f).coerceAtLeast(1f)
        val angleDegrees = (angleStep % 720) * 0.5f

        val sweep = SweepGradient(0f, 0f, colors, COMET_STOPS)
        val matrix = Matrix()
        matrix.setRotate(angleDegrees)
        matrix.postScale(cx, cy)
        matrix.postTranslate(cx, cy)
        sweep.setLocalMatrix(matrix)
        return sweep
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RotatingCometBrush) return false
        return angleStep == other.angleStep && colors.contentEquals(other.colors)
    }

    override fun hashCode(): Int {
        var result = angleStep
        result = 31 * result + colors.contentHashCode()
        return result
    }
}

/**
 * Generates a smooth, slow-moving colorful Comet brush. Prefer Modifier.cyberShimmerBorder for zero recomposition.
 */
@Composable
fun cyberShimmerBrush(
    phaseOffset: Float = 0f,
    alpha: Float = 0.92f,
    accentColor: Color? = null
): Brush {
    val effAlpha = alpha.coerceIn(0.45f, 1f)
    if (!LocalCyberShimmerEnabled.current) {
        val staticColor = remember(effAlpha, accentColor) { steadyBorderColorFor(effAlpha, accentColor) }
        return remember(staticColor) { SolidColor(staticColor) }
    }
    val basePhase = LocalCyberShimmerPhaseState.current.value
    val p = ((basePhase + phaseOffset) % 1f + 1f) % 1f
    val angleStep = (p * 720f).toInt()
    val colors = remember(effAlpha, accentColor) { buildCometColors(effAlpha, accentColor) }

    return remember(angleStep, colors) {
        RotatingCometBrush(angleStep = angleStep, colors = colors)
    }
}

/**
 * Fallback BorderStroke generator. Prefer Modifier.cyberShimmerBorder on Card/Surface/Box for zero recomposition.
 */
@Composable
fun cyberShimmerBorder(
    width: Dp = 1.dp,
    phaseOffset: Float = 0f,
    alpha: Float = 0.92f,
    accentColor: Color? = null
): BorderStroke {
    val sleekWidth = width.coerceIn(0.8.dp, 1.15.dp)
    return BorderStroke(
        width = sleekWidth,
        brush = cyberShimmerBrush(
            phaseOffset = phaseOffset,
            alpha = alpha,
            accentColor = accentColor
        )
    )
}

/**
 * Zero-recomposition, zero-per-frame-allocation Comet border Modifier.
 * Caches SweepGradient, Matrix, Paint, and Outline geometry once per size/color/shape,
 * and reads LocalCyberShimmerPhaseState.current.value strictly inside the Draw phase.
 * When CyberShimmerPrefs is OFF, renders a lightweight static border with zero per-frame redraws.
 */
@Composable
fun Modifier.cyberShimmerBorder(
    width: Dp = 1.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    phaseOffset: Float = 0f,
    alpha: Float = 0.92f,
    accentColor: Color? = null
): Modifier {
    val sleekWidth = width.coerceIn(0.8.dp, 1.15.dp)
    val effAlpha = alpha.coerceIn(0.45f, 1f)
    if (!LocalCyberShimmerEnabled.current) {
        val staticColor = remember(effAlpha, accentColor) { steadyBorderColorFor(effAlpha, accentColor) }
        return this.border(width = sleekWidth, color = staticColor, shape = shape)
    }
    val phaseState = LocalCyberShimmerPhaseState.current
    val colorKey = accentColor?.toArgb() ?: Int.MIN_VALUE

    return this.drawWithCache {
        val strokePx = sleekWidth.toPx().coerceAtLeast(1f)
        val halfStroke = strokePx * 0.5f
        val insetW = (size.width - strokePx).coerceAtLeast(1f)
        val insetH = (size.height - strokePx).coerceAtLeast(1f)
        val cx = (size.width / 2f).coerceAtLeast(1f)
        val cy = (size.height / 2f).coerceAtLeast(1f)

        val resolvedAccent = if (colorKey == Int.MIN_VALUE) null else Color(colorKey)
        val colors = buildCometColors(effAlpha, resolvedAccent)
        val sweep = SweepGradient(0f, 0f, colors, COMET_STOPS)
        val matrix = Matrix()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            shader = sweep
        }

        val rectF = RectF(
            halfStroke,
            halfStroke,
            (size.width - halfStroke).coerceAtLeast(halfStroke + 1f),
            (size.height - halfStroke).coerceAtLeast(halfStroke + 1f)
        )

        var shapeMode = 0 // 0 = rect, 1 = uniform roundRect, 2 = path
        var uniformRx = 0f
        var uniformRy = 0f
        var cachedPath: Path? = null

        when (val outline = shape.createOutline(Size(insetW, insetH), layoutDirection, this)) {
            is Outline.Rectangle -> {
                shapeMode = 0
            }
            is Outline.Rounded -> {
                val rr = outline.roundRect
                val tl = rr.topLeftCornerRadius
                val tr = rr.topRightCornerRadius
                val br = rr.bottomRightCornerRadius
                val bl = rr.bottomLeftCornerRadius
                if (tl == tr && tr == br && br == bl) {
                    shapeMode = 1
                    uniformRx = tl.x
                    uniformRy = tl.y
                } else {
                    shapeMode = 2
                    cachedPath = Path().apply {
                        addRoundRect(
                            rectF,
                            floatArrayOf(
                                tl.x, tl.y,
                                tr.x, tr.y,
                                br.x, br.y,
                                bl.x, bl.y
                            ),
                            Path.Direction.CW
                        )
                    }
                }
            }
            is Outline.Generic -> {
                shapeMode = 2
                cachedPath = Path(outline.path.asAndroidPath()).apply {
                    offset(halfStroke, halfStroke)
                }
            }
        }

        onDrawWithContent {
            drawContent()
            val p = ((phaseState.value + phaseOffset) % 1f + 1f) % 1f
            val angleDegrees = p * 360f
            matrix.setRotate(angleDegrees)
            matrix.postScale(cx, cy)
            matrix.postTranslate(cx, cy)
            sweep.setLocalMatrix(matrix)

            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                when (shapeMode) {
                    0 -> nc.drawRect(rectF, paint)
                    1 -> nc.drawRoundRect(rectF, uniformRx, uniformRy, paint)
                    else -> cachedPath?.let { nc.drawPath(it, paint) }
                }
            }
        }
    }
}
