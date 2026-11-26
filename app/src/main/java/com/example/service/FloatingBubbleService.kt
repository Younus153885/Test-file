package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.data.engine.ResetExecutionEngine
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceIdentity
import com.example.data.model.TimerSettings
import com.example.data.device.RealDeviceReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class FloatingBubbleService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "phantom_bubble_service"
        const val NOTIFICATION_ID = 9541
        
        const val EXTRA_TOTAL_SECONDS = "total_seconds"
        const val EXTRA_IS_LOOP = "is_loop"

        private val _remainingSecondsFlow = MutableStateFlow(0)
        val remainingSecondsFlow = _remainingSecondsFlow.asStateFlow()

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow = _isRunningFlow.asStateFlow()

        var isServiceRunning = false
            private set

        fun start(context: Context, totalSeconds: Int, isLoop: Boolean) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                putExtra(EXTRA_TOTAL_SECONDS, totalSeconds)
                putExtra(EXTRA_IS_LOOP, isLoop)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var totalSeconds = 300
    private var isLoop = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        _isRunningFlow.value = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        try {
            val notification = buildNotification("Armed - Waiting for timer")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extraSecs = intent?.getIntExtra(EXTRA_TOTAL_SECONDS, -1) ?: -1
        if (extraSecs > 0) {
            totalSeconds = extraSecs
            _remainingSecondsFlow.value = totalSeconds
        }
        isLoop = intent?.getBooleanExtra(EXTRA_IS_LOOP, true) ?: true

        // Create overlay bubble if permissions granted
        if (android.provider.Settings.canDrawOverlays(this)) {
            showFloatingBubble()
        }

        startCountdown()

        return START_STICKY
    }

    private fun startCountdown() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (_isRunningFlow.value) {
                while (_remainingSecondsFlow.value > 0) {
                    delay(1000L)
                    _remainingSecondsFlow.value -= 1
                    updateFloatingBubbleText()
                    updateNotification()
                }

                // Timer expired -> Trigger Reset in Background!
                triggerBackgroundReset()

                if (isLoop) {
                    _remainingSecondsFlow.value = totalSeconds
                } else {
                    _isRunningFlow.value = false
                    stopSelf()
                    break
                }
            }
        }
    }

    private fun triggerBackgroundReset() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@FloatingBubbleService)
                val targetApps = db.targetAppDao().getAllTargetAppsList().filter { it.isSelected }.map { it.packageName }
                
                // Read current active identity or fall back to live
                val activeIdentity = try {
                    val p = loadIdentityFromDisk()
                    if (p != null) p else RealDeviceReader.readRealDeviceIdentity(this@FloatingBubbleService)
                } catch (_: Throwable) {
                    RealDeviceReader.readRealDeviceIdentity(this@FloatingBubbleService)
                }

                val prefs = getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                val isGlobalSpoof = prefs.getBoolean("global_spoof_enabled", false)

                ResetExecutionEngine.executeResetPipeline(
                    targetAppName = "All Selected Apps",
                    currentIdentity = activeIdentity,
                    timerSettings = TimerSettings(),
                    targetPackageNames = targetApps,
                    isGlobalSpoofEnabled = isGlobalSpoof,
                    context = this@FloatingBubbleService
                ).collect { state ->
                    // Just run it through in background
                }
            } catch (_: Throwable) {}
        }
    }

    private fun loadIdentityFromDisk(): DeviceIdentity? {
        val paths = arrayOf(
            "/data/local/tmp/phantom_identity.json",
            "/data/misc/phantom_identity.json",
            "/data/system/phantom_identity.json",
            "/data/adb/phantom_identity.json",
            "/sdcard/phantom_identity.json",
            "/sdcard/Download/phantom_identity.json"
        )
        for (p in paths) {
            try {
                val f = java.io.File(p)
                if (f.exists()) {
                    val content = f.readText(Charsets.UTF_8)
                    if (content.isNotBlank()) {
                        val json = JSONObject(content)
                        return RealDeviceReader.readRealDeviceIdentity(this).copy(
                            androidId = json.optString("androidId"),
                            advertisingId = json.optString("advertisingId"),
                            imei = json.optString("imei"),
                            deviceModel = json.optString("deviceModel"),
                            manufacturer = json.optString("manufacturer"),
                            gpuRenderer = json.optString("gpuRenderer"),
                            locationName = json.optString("locationName"),
                            timezone = json.optString("timezone")
                        )
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun showFloatingBubble() {
        if (floatingView != null) return

        val density = resources.displayMetrics.density
        val sizePx = (50 * density).toInt()

        // 1. Programmatically construct a beautiful translucent CIRCLE bubble matching cyberpunk theme
        val container = android.widget.FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(sizePx, sizePx)
        }

        // Circular progress ring
        val progressView = CircularProgressTimerView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Timer Text
        val textView = TextView(this).apply {
            id = View.generateViewId()
            text = formatTime(_remainingSecondsFlow.value)
            setTextColor(Color.WHITE) // Bold white text
            textSize = 12f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        container.addView(progressView)
        container.addView(textView)

        floatingView = container

        // 2. Set up layout parameters for floating window overlay
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels) - sizePx - (24 * density).toInt() // Initial placement top right
            y = (150 * density).toInt()
        }

        // 3. Set up drag listener for smooth floating bubble movements
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
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
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
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        params?.let { windowManager.updateViewLayout(floatingView, it) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) {
                            // Tap on bubble -> Launch App main dashboard screen!
                            val launchIntent = Intent(this@FloatingBubbleService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(launchIntent)
                        }
                        return true
                    }
                }
                return false
            }
        })

        // Add to WindowManager
        try {
            windowManager.addView(floatingView, params)
        } catch (_: Throwable) {}
    }

    private fun updateFloatingBubbleText() {
        serviceScope.launch {
            val container = floatingView as? android.widget.FrameLayout ?: return@launch
            val progressView = container.getChildAt(0) as? CircularProgressTimerView
            val text = container.getChildAt(1) as? TextView
            
            text?.text = formatTime(_remainingSecondsFlow.value)
            
            val fraction = if (totalSeconds > 0) {
                _remainingSecondsFlow.value.toFloat() / totalSeconds.toFloat()
            } else {
                1.0f
            }
            progressView?.setProgress(fraction)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification("Active - Reset in ${formatTime(_remainingSecondsFlow.value)}"))
    }

    private fun formatTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%02d:%02d".format(mins, secs)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GhostDevice Reset Timer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time countdown for background hardware reset cycle"
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
            .setContentTitle("GhostDevice Background Shield")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isServiceRunning = false
        _isRunningFlow.value = false
        timerJob?.cancel()
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView)
            } catch (_: Throwable) {}
            floatingView = null
        }
        super.onDestroy()
    }
}
