package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.data.device.RootChecker
import com.example.ui.WalkJoystickState
import com.example.ui.i18n.LanguageManager
import com.example.ui.i18n.tr
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FloatingJoystickService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "phantom_floating_joystick"
        const val NOTIFICATION_ID = 9642

        var isServiceRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingJoystickService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingJoystickService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateCollectJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var rootContainer: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var isExpandedMode = false

    // UI references
    private var bubbleContainer: FrameLayout? = null
    private var bubbleStepsText: TextView? = null
    private var bubbleTopBadgeText: TextView? = null
    private var bubbleIcon: ImageView? = null

    private var hudContainer: LinearLayout? = null
    private var hudTitleText: TextView? = null
    private var hudStepsText: TextView? = null
    private var hudSpeedText: TextView? = null
    private var modeStickBtn: FrameLayout? = null
    private var modeStickText: TextView? = null
    private var modeVolumeBtn: FrameLayout? = null
    private var modeVolumeText: TextView? = null
    private var slotAreaButton: FrameLayout? = null
    private var slotAreaButtonText: TextView? = null
    private var joystickPadView: JoystickPadView? = null
    private var walkActionRow: LinearLayout? = null
    private var speedActionRow: LinearLayout? = null
    private var autoHoldButton: FrameLayout? = null
    private var autoHoldButtonText: TextView? = null
    private var add100ButtonText: TextView? = null
    private var resetButtonText: TextView? = null
    private var minusSpeedButtonText: TextView? = null
    private var speedNormalButtonText: TextView? = null
    private var plusSpeedButtonText: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        WalkBridge.setFloatingRunning(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        try {
            val notification = buildNotification(
                tr(
                    "Floating controller active (Stick & Volume Speed)",
                    "ফ্লোটিং কন্ট্রোলার চালু আছে (স্টিক ও ভলিউম স্পিড)",
                    "फ्लोटिंग कंट्रोलर सक्रिय है (स्टिक और वॉल्यूम स्पीड)"
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {}

        startObservingWalkState()

        serviceScope.launch(Dispatchers.IO) {
            try {
                com.example.data.device.LspScopeSyncCli.syncTargetScopeWithLsposed(
                    context = this@FloatingJoystickService,
                    selectedTargetPackages = emptyList()
                )
            } catch (_: Throwable) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch(Dispatchers.Main) {
            val hasPerm = try {
                android.provider.Settings.canDrawOverlays(this@FloatingJoystickService)
            } catch (_: Throwable) {
                false
            }

            if (!hasPerm) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    RootChecker.executeRootCommand("appops set $packageName SYSTEM_ALERT_WINDOW allow 2>/dev/null || true")
                }
                kotlinx.coroutines.delay(200L)
            }

            val finalPerm = try {
                android.provider.Settings.canDrawOverlays(this@FloatingJoystickService)
            } catch (_: Throwable) {
                false
            }

            if (finalPerm) {
                createFloatingWindow()
            } else {
                try {
                    val permIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(permIntent)
                    android.widget.Toast.makeText(
                        this@FloatingJoystickService,
                        tr(
                            "Please allow 'Display over other apps' permission for the floating window",
                            "ফ্লোটিং উইন্ডোর জন্য Display over other apps পারমিশন Allow করুন",
                            "फ्लोटिंग विंडो के लिए 'Display over other apps' अनुमति दें"
                        ),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) {}
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GhostDevice Floating Controller",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating Walk Stick & Volume Speed Controller"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("GhostDevice Floating HUD")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWindow() {
        if (rootContainer != null && rootContainer?.isAttachedToWindow == true) return

        val density = resources.displayMetrics.density
        val bubbleSizePx = (56 * density).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels) - bubbleSizePx - (16 * density).toInt()
            y = (180 * density).toInt()
        }

        val root = FrameLayout(this)
        rootContainer = root

        // Build both views
        buildBubbleView(density)
        buildHudView(density)

        root.addView(bubbleContainer)
        root.addView(hudContainer)

        // Show bubble initially
        switchToBubbleMode(density)

        try {
            windowManager.addView(root, windowParams)
        } catch (_: Throwable) {
            rootContainer = null
            serviceScope.launch {
                kotlinx.coroutines.delay(250L)
                try {
                    if (rootContainer == null) {
                        windowManager.addView(root, windowParams)
                        rootContainer = root
                    }
                } catch (_: Throwable) {
                    rootContainer = null
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubbleView(density: Float) {
        val bubbleSizePx = (56 * density).toInt()

        val container = object : FrameLayout(this) {
            private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#F50A1422")
            }
            private val innerDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#3816283C")
            }
            private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
            }
            private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.0f * density
                pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
            }
            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            private val shaderMatrix = Matrix()
            private var sweepShader: SweepGradient? = null
            private var lastCx = -1f
            private var lastCy = -1f

            override fun dispatchDraw(canvas: Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                if (w > 0f && h > 0f) {
                    val cx = w / 2f
                    val cy = h / 2f
                    val r = w.coerceAtMost(h) / 2f
                    val s = WalkBridge.state.value

                    // 1. Soft Outer Neon Halo
                    haloPaint.color = when {
                        s.isAutoHoldLocked -> Color.parseColor("#33E040FB")
                        s.isVolumeSpeedMode -> Color.parseColor("#33FFB300")
                        else -> Color.parseColor("#3300E5FF")
                    }
                    canvas.drawCircle(cx, cy, r - 0.5f * density, haloPaint)

                    // 2. Deep Obsidian Circular Core
                    corePaint.color = when {
                        s.isAutoHoldLocked -> Color.parseColor("#F51A0E29")
                        s.isVolumeSpeedMode -> Color.parseColor("#F51E150C")
                        else -> Color.parseColor("#F50A1524")
                    }
                    val ringR = r - 2.4f * density
                    canvas.drawCircle(cx, cy, ringR, corePaint)

                    // 3. Inner Subtle Center Disc
                    canvas.drawCircle(cx, cy, r - 8.2f * density, innerDiscPaint)

                    // 4. Steady Border Line + Slow, Smooth Colorful Comet Ring (7.2s orbit)
                    if (sweepShader == null || lastCx != cx || lastCy != cy) {
                        lastCx = cx
                        lastCy = cy
                        val baseCol = Color.parseColor("#2A415E")
                        sweepShader = SweepGradient(
                            cx,
                            cy,
                            intArrayOf(
                                baseCol,
                                baseCol,
                                Color.parseColor("#A855F7"),
                                Color.parseColor("#E040FB"),
                                Color.parseColor("#10B981"),
                                Color.parseColor("#00E5FF"),
                                Color.parseColor("#FFFFFF"),
                                Color.parseColor("#00E5FF"),
                                baseCol
                            ),
                            floatArrayOf(0f, 0.80f, 0.85f, 0.89f, 0.93f, 0.965f, 0.985f, 0.995f, 1f)
                        )
                    }
                    val angle = ((SystemClock.elapsedRealtime() % 7200L) * 360f) / 7200f
                    shaderMatrix.setRotate(angle, cx, cy)
                    sweepShader?.setLocalMatrix(shaderMatrix)
                    ringPaint.shader = sweepShader
                    canvas.drawCircle(cx, cy, ringR, ringPaint)

                    // 5. Inner Dashed Cyber Ring & 4 Cardinal Glow Nodes
                    val accentDotCol = when {
                        s.isAutoHoldLocked -> Color.parseColor("#E040FB")
                        s.isVolumeSpeedMode -> Color.parseColor("#FFB300")
                        else -> Color.parseColor("#10B981")
                    }
                    dashedPaint.color = accentDotCol
                    dashedPaint.alpha = 175
                    canvas.drawCircle(cx, cy, r - 6.4f * density, dashedPaint)

                    dotPaint.color = Color.parseColor("#E0F7FA")
                    val dotR = 1.7f * density
                    canvas.drawCircle(cx, cy - ringR, dotR, dotPaint)
                    canvas.drawCircle(cx, cy + ringR, dotR, dotPaint)
                    canvas.drawCircle(cx - ringR, cy, dotR, dotPaint)
                    canvas.drawCircle(cx + ringR, cy, dotR, dotPaint)
                }
                super.dispatchDraw(canvas)
                if (isAttachedToWindow && visibility == View.VISIBLE) {
                    postInvalidateDelayed(55L)
                }
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
            elevation = 18f
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((5 * density).toInt(), (4 * density).toInt(), (5 * density).toInt(), (4 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Organized Top Mode Badge Inside the Minimized Orb
        val topBadge = TextView(this).apply {
            text = "🕹️"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bubbleTopBadgeText = topBadge

        // Organized Bottom Pill Readout Inside the Minimized Orb
        val text = TextView(this).apply {
            text = "0"
            setTextColor(Color.parseColor("#E0F7FA"))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((4 * density).toInt(), (1 * density).toInt(), (4 * density).toInt(), (1 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 5f * density
                setColor(Color.parseColor("#CC102236"))
                setStroke((0.8f * density).toInt(), Color.parseColor("#6600E5FF"))
            }
            makeSingleLineAutoFit(8.5f, 6.2f)
            layoutParams = LinearLayout.LayoutParams(
                (38 * density).toInt(),
                (15 * density).toInt()
            ).apply {
                topMargin = (2 * density).toInt()
            }
        }
        bubbleStepsText = text

        contentLayout.addView(topBadge)
        contentLayout.addView(text)
        container.addView(contentLayout)

        // Drag & Click listener for bubble
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDrag = false

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                val ev = event ?: return false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = windowParams?.x ?: 0
                        initialY = windowParams?.y ?: 0
                        initialTouchX = ev.rawX
                        initialTouchY = ev.rawY
                        isDrag = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - initialTouchX).toInt()
                        val dy = (ev.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDrag = true
                        }
                        windowParams?.x = initialX + dx
                        windowParams?.y = initialY + dy
                        safeUpdateLayout()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) {
                            switchToHudMode(density)
                        }
                        return true
                    }
                }
                return false
            }
        })

        bubbleContainer = container
    }

    private var lastRenderedVolumeMode: Boolean? = null
    private var lastRenderedHoldLocked: Boolean? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun buildHudView(density: Float) {
        val hudWidthPx = (242 * density).toInt()

        val card = object : LinearLayout(this) {
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.15f * density
            }
            private val borderRect = RectF()
            private val shaderMatrix = Matrix()
            private val baseCol = Color.parseColor("#263B54")
            private val sweepShader = SweepGradient(
                0f,
                0f,
                intArrayOf(
                    baseCol,
                    baseCol,
                    Color.parseColor("#A855F7"),
                    Color.parseColor("#E040FB"),
                    Color.parseColor("#10B981"),
                    Color.parseColor("#00E5FF"),
                    Color.parseColor("#FFFFFF"),
                    Color.parseColor("#00E5FF"),
                    baseCol
                ),
                floatArrayOf(0f, 0.825f, 0.865f, 0.905f, 0.940f, 0.968f, 0.986f, 0.995f, 1f)
            )

            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                if (w > 0f && h > 0f) {
                    val cx = (w / 2f).coerceAtLeast(1f)
                    val cy = (h / 2f).coerceAtLeast(1f)
                    val angle = ((SystemClock.elapsedRealtime() % 7200L) * 360f) / 7200f
                    shaderMatrix.setRotate(angle)
                    shaderMatrix.postScale(cx, cy)
                    shaderMatrix.postTranslate(cx, cy)
                    sweepShader.setLocalMatrix(shaderMatrix)
                    borderPaint.shader = sweepShader
                    val inset = 0.8f * density
                    borderRect.set(inset, inset, w - inset, h - inset)
                    canvas.drawRoundRect(borderRect, 16f * density, 16f * density, borderPaint)
                }
                if (isAttachedToWindow && visibility == View.VISIBLE) {
                    postInvalidateDelayed(55L)
                }
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                hudWidthPx,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(Color.parseColor("#FA0A0F18"))
            }
            background = bg
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            elevation = 20f
        }

        // 1. Top Header Row (Drag handle + Title + Step/Speed Counter + Min / Close)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (26 * density).toInt()
            )
        }

        val titleText = TextView(this).apply {
            text = tr("🕹️ Controller HUD", "🕹️ কন্ট্রোলার HUD", "🕹️ कंट्रोलर HUD")
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            makeSingleLineAutoFit(11f, 7.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        hudTitleText = titleText

        val stepsBadge = TextView(this).apply {
            text = tr("0 Steps", "0 স্টেপ", "0 स्टेप")
            setTextColor(Color.parseColor("#10B981"))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            makeSingleLineAutoFit(9.5f, 7f)
            setPadding((5 * density).toInt(), (2 * density).toInt(), (5 * density).toInt(), (2 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 5 * density
                setColor(Color.parseColor("#1C2C24"))
                setStroke((0.8f * density).toInt(), Color.parseColor("#10B981"))
            }
        }
        hudStepsText = stepsBadge

        val minBtn = TextView(this).apply {
            text = "—"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 11.5f
            gravity = Gravity.CENTER
            setSingleLine(true)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 5f * density
                setColor(Color.parseColor("#162638"))
                setStroke((0.9f * density).toInt(), Color.parseColor("#00E5FF"))
            }
            layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (21 * density).toInt()).apply {
                leftMargin = (4 * density).toInt()
            }
            setOnClickListener {
                switchToBubbleMode(resources.displayMetrics.density)
            }
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF80AB"))
            textSize = 11f
            gravity = Gravity.CENTER
            setSingleLine(true)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 5f * density
                setColor(Color.parseColor("#2A1324"))
                setStroke((0.9f * density).toInt(), Color.parseColor("#FF4081"))
            }
            layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (21 * density).toInt()).apply {
                leftMargin = (4 * density).toInt()
            }
            setOnClickListener {
                stopSelf()
            }
        }

        headerRow.addView(titleText)
        headerRow.addView(stepsBadge)
        headerRow.addView(minBtn)
        headerRow.addView(closeBtn)

        // Drag listener on header to move HUD around the screen smoothly
        headerRow.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                val ev = event ?: return false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = windowParams?.x ?: 0
                        initialY = windowParams?.y ?: 0
                        initialTouchX = ev.rawX
                        initialTouchY = ev.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - initialTouchX).toInt()
                        val dy = (ev.rawY - initialTouchY).toInt()
                        windowParams?.x = initialX + dx
                        windowParams?.y = initialY + dy
                        safeUpdateLayout()
                        return true
                    }
                }
                return false
            }
        })

        card.addView(headerRow)

        // Space
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (5 * density).toInt())
        })

        // 2. Dual Mode Switcher Row: [ 🕹️ স্টিক মোড ] | [ 🔊 ভলিউম স্পিড ]
        val modeSwitcherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (27 * density).toInt()
            )
        }

        val stickModeBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(0, 0, (3 * density).toInt(), 0)
            }
            setOnClickListener {
                if (WalkBridge.state.value.isVolumeSpeedMode) {
                    WalkBridge.toggleVolumeSpeedMode()
                }
            }
        }
        modeStickBtn = stickModeBtn
        val stickModeLabel = TextView(this).apply {
            text = tr("🕹️ Stick Mode", "🕹️ স্টিক মোড", "🕹️ स्टिक मोड")
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(9.5f, 7f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        modeStickText = stickModeLabel
        stickModeBtn.addView(stickModeLabel)

        val volModeBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f).apply {
                setMargins((3 * density).toInt(), 0, 0, 0)
            }
            setOnClickListener {
                if (!WalkBridge.state.value.isVolumeSpeedMode) {
                    WalkBridge.toggleVolumeSpeedMode()
                }
            }
        }
        modeVolumeBtn = volModeBtn
        val volModeLabel = TextView(this).apply {
            text = tr("🔊 Volume Speed", "🔊 ভলিউম স্পিড", "🔊 वॉल्यूम स्पीड")
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(9.5f, 7f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        modeVolumeText = volModeLabel
        volModeBtn.addView(volModeLabel)

        modeSwitcherRow.addView(stickModeBtn)
        modeSwitcherRow.addView(volModeBtn)
        card.addView(modeSwitcherRow)

        // Space
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (5 * density).toInt())
        })

        // 3. Interactive World Map & Satellite Location Picker Button
        val slotBtnContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (28 * density).toInt()
            )
            val bg = GradientDrawable().apply {
                cornerRadius = 7 * density
                setColor(Color.parseColor("#132533"))
                setStroke((1f * density).toInt(), Color.parseColor("#00E5FF"))
            }
            background = bg
            setOnClickListener {
                WorldMapPickerOverlay.show(this@FloatingJoystickService)
            }
        }
        slotAreaButton = slotBtnContainer

        val slotLabel = TextView(this).apply {
            text = tr("🗺️ Change Map Location", "🗺️ ম্যাপ পরিবর্তন করুন", "🗺️ मैप लोकेशन बदलें")
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            makeSingleLineAutoFit(9.5f, 7f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        slotAreaButtonText = slotLabel
        slotBtnContainer.addView(slotLabel)
        card.addView(slotBtnContainer)

        // Space
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (6 * density).toInt())
        })

        // 4. Center: 360° Circular Radar Joystick OR Rotary Volume Speed Dial Canvas View
        val joystickSizePx = (118 * density).toInt()
        val joystickWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                joystickSizePx
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val padView = JoystickPadView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                joystickSizePx,
                joystickSizePx,
                Gravity.CENTER
            )
        }
        joystickPadView = padView
        joystickWrapper.addView(padView)
        card.addView(joystickWrapper)

        // 5. Direction & Speed Telemetry Label
        val speedLabel = TextView(this).apply {
            text = "0.0 km/h • N 0°"
            setTextColor(Color.parseColor("#90CAF9"))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(9.5f, 6.8f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (18 * density).toInt()
            )
        }
        hudSpeedText = speedLabel
        card.addView(speedLabel)

        // Space
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * density).toInt())
        })

        // 6A. Walk Mode Bottom Action Row: [Auto Hold] | [+100 Steps] | [Reset]
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (28 * density).toInt()
            )
        }
        walkActionRow = actionRow

        // Auto Hold Button
        val holdBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f)
            background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(Color.parseColor("#131D28"))
                setStroke((1f * density).toInt(), Color.parseColor("#28415C"))
            }
            setOnClickListener {
                WalkBridge.toggleAutoHoldLock()
            }
        }
        autoHoldButton = holdBtn
        val holdText = TextView(this).apply {
            text = tr("🔒 Hold Lock", "🔒 হোল্ড লক", "🔒 होल्ड लॉक")
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(9.5f, 7f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        autoHoldButtonText = holdText
        holdBtn.addView(holdText)

        // +100 Steps Button
        val add100Btn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.95f).apply {
                setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(Color.parseColor("#152C22"))
                setStroke((1f * density).toInt(), Color.parseColor("#10B981"))
            }
            setOnClickListener {
                WalkBridge.addInstantSteps(100)
            }
        }
        val add100Text = TextView(this).apply {
            text = tr("+100 Steps", "+100 স্টেপ", "+100 स्टेप")
            setTextColor(Color.parseColor("#10B981"))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(9.5f, 7f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        add100ButtonText = add100Text
        add100Btn.addView(add100Text)

        // Reset Button
        val resetBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.75f)
            background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(Color.parseColor("#1E1624"))
                setStroke((1f * density).toInt(), Color.parseColor("#FF4081"))
            }
            setOnClickListener {
                WalkBridge.resetWalkStepsAndDistance()
            }
        }
        val resetText = TextView(this).apply {
            text = tr("Reset", "রিসেট", "रीसेट")
            setTextColor(Color.parseColor("#FF80AB"))
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(9f, 6.8f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        resetButtonText = resetText
        resetBtn.addView(resetText)

        actionRow.addView(holdBtn)
        actionRow.addView(add100Btn)
        actionRow.addView(resetBtn)
        card.addView(actionRow)

        // 6B. Volume Speed Mode Bottom Action Row
        val spdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (28 * density).toInt()
            )
        }
        speedActionRow = spdRow

        val minusBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(Color.parseColor("#122332"))
                setStroke((1f * density).toInt(), Color.parseColor("#00E5FF"))
            }
            setOnClickListener {
                WalkBridge.stepAppSpeedMultiplier(increase = false, appContext = this@FloatingJoystickService)
            }
        }
        val minusText = TextView(this).apply {
            text = tr("➖ Slow (-1)", "➖ স্লো (-1)", "➖ स्लो (-1)")
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(9.5f, 7f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        minusSpeedButtonText = minusText
        minusBtn.addView(minusText)

        val normBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f).apply {
                setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(Color.parseColor("#152C22"))
                setStroke((1f * density).toInt(), Color.parseColor("#10B981"))
            }
            setOnClickListener {
                WalkBridge.resetAppSpeedMultiplier(appContext = this@FloatingJoystickService)
            }
        }
        val normText = TextView(this).apply {
            text = tr("1.0x Normal", "1.0x নরমাল", "1.0x नॉर्मल")
            setTextColor(Color.parseColor("#10B981"))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(9f, 6.8f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        speedNormalButtonText = normText
        normBtn.addView(normText)

        val plusBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(Color.parseColor("#2C1528"))
                setStroke((1f * density).toInt(), Color.parseColor("#FF4081"))
            }
            setOnClickListener {
                WalkBridge.stepAppSpeedMultiplier(increase = true, appContext = this@FloatingJoystickService)
            }
        }
        val plusText = TextView(this).apply {
            text = tr("➕ Fast (+1)", "➕ ফাস্ট (+1)", "➕ फास्ट (+1)")
            setTextColor(Color.parseColor("#FF80AB"))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(9.5f, 7f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        plusSpeedButtonText = plusText
        plusBtn.addView(plusText)

        spdRow.addView(minusBtn)
        spdRow.addView(normBtn)
        spdRow.addView(plusBtn)
        card.addView(spdRow)

        hudContainer = card
    }

    private fun TextView.makeSingleLineAutoFit(maxSp: Float, minSp: Float = 7f) {
        setSingleLine(true)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
        setTextSize( android.util.TypedValue.COMPLEX_UNIT_SP, maxSp)
        val dm = resources.displayMetrics
        val fitText = Runnable {
            try {
                val availW = width - paddingLeft - paddingRight
                if (availW <= 0) return@Runnable
                val str = text?.toString() ?: return@Runnable
                if (str.isEmpty()) return@Runnable
                val hiPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, maxSp, dm)
                val loPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, minSp, dm)
                val tp = android.text.TextPaint(paint)
                tp.textSize = hiPx
                val needed = tp.measureText(str)
                if (needed <= availW) {
                    if (kotlin.math.abs(textSize - hiPx) > 0.5f) {
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, hiPx)
                    }
                    return@Runnable
                }
                val scaledPx = loPx.coerceAtLeast(hiPx * (availW.toFloat() / needed.coerceAtLeast(1f)) * 0.95f)
                if (kotlin.math.abs(textSize - scaledPx) > 0.5f) {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, scaledPx)
                }
            } catch (_: Throwable) {}
        }
        addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if ((right - left) != (oldRight - oldLeft) && (right - left) > 0) {
                fitText.run()
            }
        }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                post(fitText)
            }
        })
    }

    private fun safeUpdateLayout() {
        val root = rootContainer ?: return
        val params = windowParams ?: return
        if (root.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Throwable) {}
        }
    }

    private fun switchToBubbleMode(density: Float) {
        try {
            isExpandedMode = false
            bubbleContainer?.visibility = View.VISIBLE
            hudContainer?.visibility = View.GONE

            val bubbleSizePx = (56 * density).toInt()
            windowParams?.width = bubbleSizePx
            windowParams?.height = bubbleSizePx
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val maxX = (screenW - bubbleSizePx - 4).coerceAtLeast(4)
            val maxY = (screenH - bubbleSizePx - 4).coerceAtLeast(4)
            windowParams?.x = (windowParams?.x ?: 0).coerceIn(4, maxX)
            windowParams?.y = (windowParams?.y ?: 0).coerceIn(4, maxY)
            safeUpdateLayout()
        } catch (_: Throwable) {}
    }

    private fun switchToHudMode(density: Float) {
        try {
            isExpandedMode = true
            bubbleContainer?.visibility = View.GONE
            hudContainer?.visibility = View.VISIBLE

            val hudWidthPx = (242 * density).toInt()
            val estHudHeightPx = (288 * density).toInt()

            windowParams?.width = hudWidthPx
            windowParams?.height = WindowManager.LayoutParams.WRAP_CONTENT
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val minX = 6
            val maxX = (screenW - hudWidthPx - 6).coerceAtLeast(minX)
            val minY = 6
            val maxY = (screenH - estHudHeightPx - 6).coerceAtLeast(minY)
            windowParams?.x = (windowParams?.x ?: 0).coerceIn(minX, maxX)
            windowParams?.y = (windowParams?.y ?: 0).coerceIn(minY, maxY)
            safeUpdateLayout()
        } catch (_: Throwable) {}
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            val density = resources.displayMetrics.density
            if (isExpandedMode) {
                switchToHudMode(density)
            } else {
                switchToBubbleMode(density)
            }
        } catch (_: Throwable) {}
    }

    private fun startObservingWalkState() {
        stateCollectJob?.cancel()
        stateCollectJob = serviceScope.launch {
            launch {
                WalkBridge.state.collect { s ->
                    updateUiWithState(s)
                }
            }
            launch {
                LanguageManager.langFlow.collect {
                    lastRenderedHoldLocked = null
                    updateStaticLabelsForLanguage()
                    updateUiWithState(WalkBridge.state.value)
                }
            }
        }
    }

    private fun updateStaticLabelsForLanguage() {
        hudTitleText?.text = tr("🕹️ Controller HUD", "🕹️ কন্ট্রোলার HUD", "🕹️ कंट्रोलर HUD")
        modeStickText?.text = tr("🕹️ Stick Mode", "🕹️ স্টিক মোড", "🕹️ स्टिक मोड")
        modeVolumeText?.text = tr("🔊 Volume Speed", "🔊 ভলিউম স্পিড", "🔊 वॉल्यूम स्पीड")
        add100ButtonText?.text = tr("+100 Steps", "+100 স্টেপ", "+100 स्टेप")
        resetButtonText?.text = tr("Reset", "রিসেট", "रीसेट")
        minusSpeedButtonText?.text = tr("➖ Slow (-1)", "➖ স্লো (-1)", "➖ स्लो (-1)")
        plusSpeedButtonText?.text = tr("➕ Fast (+1)", "➕ ফাস্ট (+1)", "➕ फास्ट (+1)")
        joystickPadView?.invalidate()
    }

    private fun updateUiWithState(s: WalkJoystickState) {
        val density = resources.displayMetrics.density
        val multStr = WalkBridge.formatSpeedMultiplier(s.appSpeedMultiplier)

        // 1. Bubble state
        bubbleTopBadgeText?.text = when {
            s.isVolumeSpeedMode -> "⚡"
            s.isAutoHoldLocked -> "🔒"
            s.magnitude > 0.03f -> "🚶"
            else -> "🕹️"
        }
        bubbleStepsText?.text = if (s.isVolumeSpeedMode) {
            multStr
        } else if (s.stepsCount > 999) {
            "${s.stepsCount / 1000}k"
        } else {
            "${s.stepsCount}"
        }
        bubbleContainer?.invalidate()

        // 2. Mode Switcher Buttons (only reallocate Drawables when mode actually changes)
        if (lastRenderedVolumeMode != s.isVolumeSpeedMode) {
            lastRenderedVolumeMode = s.isVolumeSpeedMode
            if (s.isVolumeSpeedMode) {
                modeStickText?.setTextColor(Color.parseColor("#90CAF9"))
                modeStickBtn?.background = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#111924"))
                    setStroke((1f * density).toInt(), Color.parseColor("#243B53"))
                }
                modeVolumeText?.setTextColor(Color.parseColor("#FFD54F"))
                modeVolumeBtn?.background = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#2E2010"))
                    setStroke((1.2f * density).toInt(), Color.parseColor("#FFB300"))
                }
                walkActionRow?.visibility = View.GONE
                speedActionRow?.visibility = View.VISIBLE
            } else {
                modeStickText?.setTextColor(Color.parseColor("#00E5FF"))
                modeStickBtn?.background = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#122836"))
                    setStroke((1.2f * density).toInt(), Color.parseColor("#00E5FF"))
                }
                modeVolumeText?.setTextColor(Color.parseColor("#90CAF9"))
                modeVolumeBtn?.background = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#111924"))
                    setStroke((1f * density).toInt(), Color.parseColor("#243B53"))
                }
                walkActionRow?.visibility = View.VISIBLE
                speedActionRow?.visibility = View.GONE
            }
        }

        // 3. HUD Header & Telemetry
        hudStepsText?.text = if (s.isVolumeSpeedMode) {
            "⚡ $multStr"
        } else {
            tr("${s.stepsCount} Steps", "${s.stepsCount} স্টেপ", "${s.stepsCount} स्टेप")
        }

        if (s.isVolumeSpeedMode) {
            val modeTag = when {
                s.appSpeedMultiplier < 0.96f -> tr("Minus Slow (SLOW)", "মাইনাস স্লো (SLOW)", "माइनस स्लो (SLOW)")
                s.appSpeedMultiplier > 1.04f -> tr("Fast Boost (FAST)", "ফাস্ট বুস্ট (FAST)", "फास्ट बूस्ट (FAST)")
                else -> tr("Normal Speed (1.0x)", "নরমাল গতি (1.0x)", "नॉर्मल गति (1.0x)")
            }
            hudSpeedText?.text = tr(
                "Speed: $multStr • $modeTag (-1000x ⇄ +1000x)",
                "স্পিড: $multStr • $modeTag (-1000x ⇄ +1000x)",
                "स्पीड: $multStr • $modeTag (-1000x ⇄ +1000x)"
            )
            speedNormalButtonText?.text = if (s.appSpeedMultiplier in 0.96f..1.04f) {
                tr("1.0x Active", "1.0x সক্রিয়", "1.0x सक्रिय")
            } else {
                tr("1.0x Reset", "1.0x রিসেট", "1.0x रीसेट")
            }
        } else {
            val speedKmh = s.speedMps * 3.6f
            val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val dirIdx = (((s.bearingDegrees + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
            val compassStr = "${dirs[dirIdx]} ${s.bearingDegrees.roundToInt()}°"
            hudSpeedText?.text = "${String.format(Locale.US, "%.1f", speedKmh)} km/h • $compassStr"
        }

        // 4. World Map Location Picker Button
        val shortCoordinate = "${String.format(Locale.US, "%.4f", s.liveLatitude)}, ${String.format(Locale.US, "%.4f", s.liveLongitude)}"
        slotAreaButtonText?.text = tr(
            "🗺️ Change Map ($shortCoordinate)",
            "🗺️ ম্যাপ পরিবর্তন করুন ($shortCoordinate)",
            "🗺️ मैप बदलें ($shortCoordinate)"
        )

        // 5. Auto Hold button UI (only reallocate Drawable when lock state changes)
        if (lastRenderedHoldLocked != s.isAutoHoldLocked) {
            lastRenderedHoldLocked = s.isAutoHoldLocked
            if (s.isAutoHoldLocked) {
                autoHoldButtonText?.text = tr("🔒 Lock ON", "🔒 লক ON", "🔒 लॉक ON")
                autoHoldButtonText?.setTextColor(Color.parseColor("#FF4081"))
                autoHoldButton?.background = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#2E1029"))
                    setStroke((1f * density).toInt(), Color.parseColor("#FF4081"))
                }
            } else {
                autoHoldButtonText?.text = tr("Hold Lock", "হোল্ড লক", "होल्ड लॉक")
                autoHoldButtonText?.setTextColor(Color.WHITE)
                autoHoldButton?.background = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#131D28"))
                    setStroke((1f * density).toInt(), Color.parseColor("#28415C"))
                }
            }
        }

        // 6. Joystick / Volume Dial pad state
        joystickPadView?.setPadState(
            kx = s.knobX,
            ky = s.knobY,
            mag = s.magnitude,
            locked = s.isAutoHoldLocked,
            volumeMode = s.isVolumeSpeedMode,
            speedMult = s.appSpeedMultiplier
        )
    }

    override fun onDestroy() {
        isServiceRunning = false
        WalkBridge.setFloatingRunning(false)
        stateCollectJob?.cancel()
        WorldMapPickerOverlay.dismiss(this)
        val root = rootContainer
        if (root != null) {
            try {
                if (root.isAttachedToWindow) {
                    windowManager.removeView(root)
                }
            } catch (_: Throwable) {}
            rootContainer = null
        }
        super.onDestroy()
    }

    // Custom 360° Circular Joystick Pad AND Rotary Volume Speed Dial View
    inner class JoystickPadView(context: Context) : View(context) {

        private var knobX = 0f
        private var knobY = 0f
        private var magnitude = 0f
        private var isAutoHoldLocked = false
        private var isVolumeSpeedMode = false
        private var appSpeedMultiplier = 1.0f

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
        }
        private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#4400E5FF")
            pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.parseColor("#28415C")
        }
        private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
        }
        private val thumbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val thumbCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80DEEA")
            textSize = 20f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val centerValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 31f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD54F")
            textSize = 17f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val arcBounds = android.graphics.RectF()
        private val cachedShaderMatrix = Matrix()
        private val cometStops = floatArrayOf(0f, 0.81f, 0.855f, 0.895f, 0.935f, 0.965f, 0.985f, 0.995f, 1f)
        private val cometSweepCache = android.util.SparseArray<SweepGradient>(6)
        private var cachedBgCx = -1f
        private var cachedBgCy = -1f
        private var cachedBgRadius = -1f
        private var cachedVolRadial: RadialGradient? = null
        private var cachedWalkRadial: RadialGradient? = null

        private fun getOrCreateCometSweep(accentCol: Int): SweepGradient {
            var sweep = cometSweepCache.get(accentCol)
            if (sweep == null) {
                val baseCol = Color.parseColor("#283E58")
                sweep = SweepGradient(
                    0f,
                    0f,
                    intArrayOf(
                        baseCol,
                        baseCol,
                        Color.parseColor("#A855F7"),
                        Color.parseColor("#E040FB"),
                        Color.parseColor("#10B981"),
                        accentCol,
                        Color.parseColor("#FFFFFF"),
                        accentCol,
                        baseCol
                    ),
                    cometStops
                )
                cometSweepCache.put(accentCol, sweep)
            }
            return sweep
        }

        fun setPadState(
            kx: Float,
            ky: Float,
            mag: Float,
            locked: Boolean,
            volumeMode: Boolean,
            speedMult: Float
        ) {
            knobX = kx
            knobY = ky
            magnitude = mag
            isAutoHoldLocked = locked
            isVolumeSpeedMode = volumeMode
            appSpeedMultiplier = speedMult
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val outerRadius = (w.coerceAtMost(h) / 2f) - 6f
            val maxKnobRadius = outerRadius * 0.78f

            if (cachedVolRadial == null || cachedWalkRadial == null ||
                cachedBgCx != cx || cachedBgCy != cy || cachedBgRadius != outerRadius
            ) {
                cachedBgCx = cx
                cachedBgCy = cy
                cachedBgRadius = outerRadius
                val safeR = outerRadius.coerceAtLeast(1f)
                cachedVolRadial = RadialGradient(
                    cx, cy, safeR,
                    Color.parseColor("#261D14"),
                    Color.parseColor("#081018"),
                    Shader.TileMode.CLAMP
                )
                cachedWalkRadial = RadialGradient(
                    cx, cy, safeR,
                    Color.parseColor("#1A2634"),
                    Color.parseColor("#081018"),
                    Shader.TileMode.CLAMP
                )
            }
            bgPaint.shader = if (isVolumeSpeedMode) cachedVolRadial else cachedWalkRadial
            canvas.drawCircle(cx, cy, outerRadius, bgPaint)

            if (isVolumeSpeedMode) {
                // VOLUME SPEED ROTARY DIAL MODE (-1000x SLOW ➔ 1.0x ➔ +1000x FAST)
                val progress = WalkBridge.speedMultiplierToProgress(appSpeedMultiplier)
                val dialRadius = outerRadius * 0.82f
                arcBounds.set(cx - dialRadius, cy - dialRadius, cx + dialRadius, cy + dialRadius)

                // Track arc (-225° from 3 o'clock = 135° start, sweep 270°)
                arcPaint.color = Color.parseColor("#243648")
                canvas.drawArc(arcBounds, 135f, 270f, false, arcPaint)

                // Active volume arc
                val volColor = when {
                    appSpeedMultiplier < 0.96f -> Color.parseColor("#00E5FF")
                    appSpeedMultiplier > 1.04f -> Color.parseColor("#FFB300")
                    else -> Color.parseColor("#10B981")
                }
                arcPaint.color = volColor
                canvas.drawArc(arcBounds, 135f, 270f * progress, false, arcPaint)

                // Steady Border Line + Slow, Smooth Colorful Comet Outer Ring for Volume Speed Dial
                val shimmerAngle = ((SystemClock.elapsedRealtime() % 7200L) * 360f) / 7200f
                val dialSweep = getOrCreateCometSweep(volColor)
                cachedShaderMatrix.setRotate(shimmerAngle)
                cachedShaderMatrix.postTranslate(cx, cy)
                dialSweep.setLocalMatrix(cachedShaderMatrix)
                ringPaint.strokeWidth = 2.2f
                ringPaint.shader = dialSweep
                canvas.drawCircle(cx, cy, outerRadius, ringPaint)
                ringPaint.shader = null

                // Labels for -1000x / 1.0x / +1000x
                textPaint.textSize = 15f
                textPaint.color = Color.parseColor("#00E5FF")
                canvas.drawText("-1000x", cx - outerRadius * 0.45f, cy + outerRadius * 0.76f, textPaint)
                textPaint.color = Color.parseColor("#10B981")
                canvas.drawText("1.0x", cx, cy - outerRadius * 0.56f, textPaint)
                textPaint.color = Color.parseColor("#FF4081")
                canvas.drawText("+1000x", cx + outerRadius * 0.45f, cy + outerRadius * 0.76f, textPaint)

                // Center digital multiplier readout
                val multLabel = WalkBridge.formatSpeedMultiplier(appSpeedMultiplier)
                centerValuePaint.color = Color.WHITE
                canvas.drawText(multLabel, cx, cy + 6f, centerValuePaint)

                centerSubPaint.color = volColor
                val subLabel = when {
                    appSpeedMultiplier < 0.96f -> tr("MINUS SLOW", "মাইনাস স্লো", "माइनस स्लो")
                    appSpeedMultiplier > 1.04f -> tr("FAST SPEED", "ফাস্ট স্পিড", "फास्ट स्पीड")
                    else -> "NORMAL"
                }
                canvas.drawText(subLabel, cx, cy + 28f, centerSubPaint)

                // Rotary knob position on the dial ring
                val angleDeg = -135.0 + (progress * 270.0)
                val angleRad = Math.toRadians(angleDeg)
                val thumbX = cx + (kotlin.math.sin(angleRad) * dialRadius).toFloat()
                val thumbY = cy - (kotlin.math.cos(angleRad) * dialRadius).toFloat()

                thumbGlowPaint.color = volColor
                thumbGlowPaint.alpha = 95
                canvas.drawCircle(thumbX, thumbY, 22f, thumbGlowPaint)

                thumbPaint.color = volColor
                canvas.drawCircle(thumbX, thumbY, 13f, thumbPaint)
                canvas.drawCircle(thumbX, thumbY, 4.5f, thumbCenterPaint)
                if (isAttachedToWindow && visibility == View.VISIBLE) {
                    postInvalidateDelayed(55L)
                }
                return
            }

            // WALK JOYSTICK RADAR MODE WITH STEADY BORDER LINE + SLOW ORBITING COMET
            val activeColor = when {
                magnitude > 0.7f -> Color.parseColor("#FF4081")
                magnitude > 0.03f -> Color.parseColor("#00E5FF")
                isAutoHoldLocked -> Color.parseColor("#FF4081")
                else -> Color.parseColor("#00E5FF")
            }
            val shimmerAngle = ((SystemClock.elapsedRealtime() % 7200L) * 360f) / 7200f
            val radarSweep = getOrCreateCometSweep(activeColor)
            cachedShaderMatrix.setRotate(shimmerAngle)
            cachedShaderMatrix.postTranslate(cx, cy)
            radarSweep.setLocalMatrix(cachedShaderMatrix)
            ringPaint.strokeWidth = 2.2f
            ringPaint.shader = radarSweep
            canvas.drawCircle(cx, cy, outerRadius, ringPaint)
            ringPaint.shader = null
            if (isAttachedToWindow && visibility == View.VISIBLE) {
                postInvalidateDelayed(55L)
            }

            // Speed rings
            canvas.drawCircle(cx, cy, maxKnobRadius * 0.65f, innerRingPaint)
            canvas.drawCircle(cx, cy, maxKnobRadius * 0.35f, axisPaint)

            // Crosshair axes
            canvas.drawLine(cx, cy - outerRadius + 8f, cx, cy + outerRadius - 8f, axisPaint)
            canvas.drawLine(cx - outerRadius + 8f, cy, cx + outerRadius - 8f, cy, axisPaint)

            // Cardinal labels
            textPaint.textSize = 20f
            textPaint.color = Color.parseColor("#80DEEA")
            canvas.drawText("N", cx, cy - outerRadius + 22f, textPaint)
            canvas.drawText("S", cx, cy + outerRadius - 10f, textPaint)
            canvas.drawText("W", cx - outerRadius + 18f, cy + 7f, textPaint)
            canvas.drawText("E", cx + outerRadius - 18f, cy + 7f, textPaint)

            // Knob position
            val thumbX = cx + (knobX * maxKnobRadius)
            val thumbY = cy + (knobY * maxKnobRadius)

            // Vector line
            if (magnitude > 0.02f) {
                vectorPaint.color = if (magnitude > 0.7f) Color.parseColor("#FF4081") else Color.parseColor("#00E5FF")
                canvas.drawLine(cx, cy, thumbX, thumbY, vectorPaint)
            }

            // Center anchor
            canvas.drawCircle(cx, cy, 4f, axisPaint)

            // Thumb knob
            val thumbCol = when {
                magnitude > 0.7f -> Color.parseColor("#FF4081")
                magnitude > 0.03f -> Color.parseColor("#00E5FF")
                else -> Color.parseColor("#10B981")
            }
            thumbGlowPaint.color = thumbCol
            thumbGlowPaint.alpha = 80
            canvas.drawCircle(thumbX, thumbY, 24f, thumbGlowPaint)

            thumbPaint.color = thumbCol
            canvas.drawCircle(thumbX, thumbY, 15f, thumbPaint)
            canvas.drawCircle(thumbX, thumbY, 5f, thumbCenterPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val cx = width / 2f
            val cy = height / 2f
            val maxR = (width.coerceAtMost(height) / 2f * 0.78f).coerceAtLeast(1f)

            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val dx = (event.x - cx) / maxR
                    val dy = (event.y - cy) / maxR
                    WalkBridge.updateJoystickVector(dx, dy, isDragging = true)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    WalkBridge.updateJoystickVector(0f, 0f, isDragging = false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
