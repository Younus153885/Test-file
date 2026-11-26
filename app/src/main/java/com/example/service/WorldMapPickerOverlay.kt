package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.ui.i18n.LanguageManager
import com.example.ui.i18n.tr
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MapSearchPlace(
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double
)

object WorldMapPickerOverlay {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeOverlayView: View? = null
    private var activeWebView: WebView? = null
    private var searchJob: Job? = null
    private var reverseGeocodeJob: Job? = null

    private fun getQuickWorldPresets(): List<MapSearchPlace> = listOf(
        MapSearchPlace("Dhaka, Bangladesh", tr("Gulshan 2, Dhaka, Bangladesh", "গুলশান ২, ঢাকা, বাংলাদেশ", "गुलशन 2, ढाका, बांग्लादेश"), 23.7925, 90.4078),
        MapSearchPlace("Uttara, Dhaka", tr("Uttara Sector 15, Dhaka", "উত্তরা সেক্টর ১৫, ঢাকা", "उत्तरा सेक्टर 15, ढाका"), 23.8759, 90.3795),
        MapSearchPlace("Chattogram, Bangladesh", tr("Patenga, Chattogram", "পতেঙ্গা, চট্টগ্রাম", "पतेंगा, चटगांव"), 22.2356, 91.7914),
        MapSearchPlace("Sylhet, Bangladesh", tr("Zindabazar, Sylhet", "জিন্দাবাজার, সিলেট", "जिंदाबाजार, सिलहट"), 24.8949, 91.8687),
        MapSearchPlace("Cox's Bazar, Bangladesh", tr("Marine Drive, Cox's Bazar", "মেরিন ড্রাইভ, কক্সবাজার", "मरीन ड्राइव, कॉक्स बाजार"), 21.4272, 92.0058),
        MapSearchPlace("Kolkata, India", "Park Street, Kolkata, India", 22.5726, 88.3639),
        MapSearchPlace("Dubai, UAE", "Downtown Dubai / Burj Khalifa, UAE", 25.1972, 55.2744),
        MapSearchPlace("Mecca, Saudi Arabia", tr("Mecca, Saudi Arabia", "মক্কা, সৌদি আরব", "मक्का, सऊदी अरब"), 21.4225, 39.8262),
        MapSearchPlace("London, United Kingdom", "Westminster, London, UK", 51.5074, -0.1278),
        MapSearchPlace("New York, USA", "Times Square, New York, USA", 40.7580, -73.9855),
        MapSearchPlace("Tokyo, Japan", "Shibuya, Tokyo, Japan", 35.6595, 139.7004),
        MapSearchPlace("Sydney, Australia", "Sydney Harbour, Australia", -33.8688, 151.2093),
        MapSearchPlace("Singapore", "Marina Bay, Singapore", 1.2834, 103.8607),
        MapSearchPlace("Paris, France", "Eiffel Tower, Paris, France", 48.8584, 2.2945),
        MapSearchPlace("Toronto, Canada", "Downtown Toronto, Canada", 43.6532, -79.3832)
    )

    fun isShowing(): Boolean = activeOverlayView != null

    fun dismiss(context: Context) {
        val view = activeOverlayView ?: return
        activeOverlayView = null
        searchJob?.cancel()
        reverseGeocodeJob?.cancel()
        try {
            activeWebView?.stopLoading()
            activeWebView?.destroy()
        } catch (_: Throwable) {}
        activeWebView = null
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (view.isAttachedToWindow) {
                wm.removeView(view)
            }
        } catch (_: Throwable) {}
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun show(
        context: Context,
        onLocationApplied: ((Double, Double, String) -> Unit)? = null
    ) {
        if (activeOverlayView != null) {
            dismiss(context)
            return
        }

        val baseCtx = if (context is android.app.Activity) context else (context.applicationContext ?: context)
        val appCtx = android.view.ContextThemeWrapper(baseCtx, android.R.style.Theme_DeviceDefault)
        val canOverlay = try {
            Settings.canDrawOverlaysCompat(baseCtx)
        } catch (_: Throwable) {
            true
        }

        if (!canOverlay && context !is android.app.Activity) {
            Toast.makeText(
                baseCtx,
                tr(
                    "Display over other apps permission is required to open the map",
                    "ম্যাপ ওপেন করতে Display over other apps পারমিশন প্রয়োজন",
                    "मैप खोलने के लिए 'Display over other apps' अनुमति आवश्यक है"
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val wm = baseCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = baseCtx.resources.displayMetrics
        val density = dm.density

        fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()

        fun TextView.makeSingleLineAutoFit(maxSp: Float, minSp: Float = 7.2f) {
            setSingleLine(true)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, maxSp)
            val fitText = Runnable {
                try {
                    val availW = width - paddingLeft - paddingRight
                    if (availW <= 0) return@Runnable
                    val str = text?.toString() ?: return@Runnable
                    if (str.isEmpty()) return@Runnable
                    val hiPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSp, dm)
                    val loPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSp, dm)
                    val tp = android.text.TextPaint(paint)
                    tp.textSize = hiPx
                    val needed = tp.measureText(str)
                    if (needed <= availW) {
                        if (kotlin.math.abs(textSize - hiPx) > 0.5f) {
                            setTextSize(TypedValue.COMPLEX_UNIT_PX, hiPx)
                        }
                        return@Runnable
                    }
                    val scaledPx = loPx.coerceAtLeast(hiPx * (availW.toFloat() / needed.coerceAtLeast(1f)) * 0.96f)
                    if (kotlin.math.abs(textSize - scaledPx) > 0.5f) {
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledPx)
                    }
                } catch (_: Throwable) {}
            }
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if ((right - left) != (oldRight - oldLeft) && (right - left) > 0) {
                    fitText.run()
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    post(fitText)
                }
            })
        }

        val curState = WalkBridge.state.value
        var selectedLat = if (curState.liveLatitude != 0.0) curState.liveLatitude else WalkBridge.defaultLatitude
        var selectedLng = if (curState.liveLongitude != 0.0) curState.liveLongitude else WalkBridge.defaultLongitude
        var selectedLabel = curState.currentZoneLabel
            .substringBefore("(")
            .trim()
            .ifBlank { "Selected Map Spot" }
        var currentMapLayer = "hybrid" // "hybrid" (Satellite + Labels), "satellite" (Pure Satellite), "street" (Normal Map)

        val layoutType = if (!canOverlay && context is android.app.Activity) {
            WindowManager.LayoutParams.TYPE_APPLICATION
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Focusable overlay window so the user can type in the Search EditText over any target app
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val rootBackdrop = FrameLayout(appCtx).apply {
            setBackgroundColor(Color.parseColor("#B8040810"))
            setPadding(dp(12f), dp(28f), dp(12f), dp(20f))
        }

        val card = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(Color.parseColor("#FA09101C"))
                setStroke(dp(1.5f), Color.parseColor("#00E5FF"))
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(12f))
            elevation = 28f
        }

        // 1. Header Row: Title + Close Button
        val headerRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleCol = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(8f), 0)
            }
        }

        val titleTv = TextView(appCtx).apply {
            text = tr(
                "🗺️ Change Map Location (World Map)",
                "🗺️ ম্যাপ পরিবর্তন করুন (World Map)",
                "🗺️ मैप लोकेशन बदलें (World Map)"
            )
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            makeSingleLineAutoFit(14f, 8f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val subTitleTv = TextView(appCtx).apply {
            text = tr(
                "Search any place worldwide or tap on the satellite map",
                "পৃথিবীর যেকোনো জায়গা সার্চ করুন বা স্যাটেলাইট ম্যাপে ট্যাপ করুন",
                "दुनिया में कहीं भी खोजें या सैटेलाइट मैप पर टैप करें"
            )
            setTextColor(Color.parseColor("#80DEEA"))
            makeSingleLineAutoFit(10.5f, 7f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        titleCol.addView(titleTv)
        titleCol.addView(subTitleTv)

        val closeBtn = TextView(appCtx).apply {
            text = " ✕ "
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 15f
            setSingleLine(true)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(Color.parseColor("#2A121B"))
                setStroke(dp(1f), Color.parseColor("#FF5252"))
            }
            setOnClickListener {
                dismiss(appCtx)
            }
        }

        headerRow.addView(titleCol)
        headerRow.addView(closeBtn)
        card.addView(headerRow)

        card.addView(View(appCtx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8f))
        })

        // 2. Search Bar Row: [ 🔍 Search EditText ] [ Clear ] [ Search Button ]
        val searchRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42f)
            )
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#111C2D"))
                setStroke(dp(1f), Color.parseColor("#00E5FF"))
            }
            setPadding(dp(10f), dp(2f), dp(6f), dp(2f))
        }

        val searchInput = EditText(appCtx).apply {
            hint = tr(
                "Search any place worldwide (e.g. Dhaka, London, Tokyo)...",
                "পৃথিবীর যেকোনো জায়গা সার্চ করুন (যেমন: Dhaka, London, Tokyo)...",
                "दुनिया में कोई भी जगह खोजें (जैसे: Dhaka, London, Tokyo)..."
            )
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val clearSearchBtn = TextView(appCtx).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            visibility = View.GONE
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }

        val searchGoBtn = TextView(appCtx).apply {
            text = tr("🔍 Search", "🔍 সার্চ", "🔍 खोजें")
            setTextColor(Color.parseColor("#050B14"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(11.5f, 8f)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(Color.parseColor("#00E5FF"))
            }
        }

        searchRow.addView(searchInput)
        searchRow.addView(clearSearchBtn)
        searchRow.addView(searchGoBtn)
        card.addView(searchRow)

        card.addView(View(appCtx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6f))
        })

        // 3. Map Layer Mode Switcher Row
        val layerRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32f)
            )
        }

        lateinit var btnHybrid: TextView
        lateinit var btnSatOnly: TextView
        lateinit var btnStreet: TextView

        fun updateLayerButtonsStyle() {
            fun styleBtn(tv: TextView, active: Boolean, activeColorHex: String) {
                tv.setTextColor(if (active) Color.parseColor("#050B14") else Color.parseColor("#E2E8F0"))
                tv.background = GradientDrawable().apply {
                    cornerRadius = 7f * density
                    setColor(if (active) Color.parseColor(activeColorHex) else Color.parseColor("#132032"))
                    setStroke(dp(1f), Color.parseColor(if (active) activeColorHex else "#28415C"))
                }
            }
            styleBtn(btnHybrid, currentMapLayer == "hybrid", "#00E5FF")
            styleBtn(btnSatOnly, currentMapLayer == "satellite", "#10B981")
            styleBtn(btnStreet, currentMapLayer == "street", "#FFB300")
        }

        btnHybrid = TextView(appCtx).apply {
            text = tr("🛰️ Satellite (Hybrid)", "🛰️ Satellite (হাইব্রিড)", "🛰️ Satellite (हाइब्रिड)")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(4f), 0, dp(4f), 0)
            makeSingleLineAutoFit(10.5f, 7.2f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f).apply {
                setMargins(0, 0, dp(4f), 0)
            }
            setOnClickListener {
                currentMapLayer = "hybrid"
                updateLayerButtonsStyle()
                activeWebView?.evaluateJavascript("window.setMapLayer && window.setMapLayer('hybrid');", null)
            }
        }

        btnSatOnly = TextView(appCtx).apply {
            text = tr("🌍 Satellite", "🌍 স্যাটেলাইট", "🌍 सैटेलाइट")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(4f), 0, dp(4f), 0)
            makeSingleLineAutoFit(10.5f, 7.2f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f).apply {
                setMargins(dp(2f), 0, dp(2f), 0)
            }
            setOnClickListener {
                currentMapLayer = "satellite"
                updateLayerButtonsStyle()
                activeWebView?.evaluateJavascript("window.setMapLayer && window.setMapLayer('satellite');", null)
            }
        }

        btnStreet = TextView(appCtx).apply {
            text = tr("🗺️ Street Map", "🗺️ নরমাল ম্যাপ", "🗺️ नॉर्मल मैप")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(4f), 0, dp(4f), 0)
            makeSingleLineAutoFit(10.5f, 7.2f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.95f).apply {
                setMargins(dp(4f), 0, 0, 0)
            }
            setOnClickListener {
                currentMapLayer = "street"
                updateLayerButtonsStyle()
                activeWebView?.evaluateJavascript("window.setMapLayer && window.setMapLayer('street');", null)
            }
        }

        updateLayerButtonsStyle()
        layerRow.addView(btnHybrid)
        layerRow.addView(btnSatOnly)
        layerRow.addView(btnStreet)
        card.addView(layerRow)

        card.addView(View(appCtx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6f))
        })

        // 4. Center Container: Map WebView + Floating Search Results Dropdown Overlay
        val mapFrame = FrameLayout(appCtx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.parseColor("#070D17"))
                setStroke(dp(1f), Color.parseColor("#1E3A5F"))
            }
            clipChildren = true
        }

        // Bottom status textviews declared early so callbacks can update them
        val selectedPlaceTitleTv = TextView(appCtx).apply {
            text = "📍 $selectedLabel"
            setTextColor(Color.parseColor("#10B981"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            makeSingleLineAutoFit(12f, 8f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val selectedCoordsTv = TextView(appCtx).apply {
            text = String.format(Locale.US, "GPS: %.5f, %.5f", selectedLat, selectedLng)
            setTextColor(Color.parseColor("#E0F2FE"))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            makeSingleLineAutoFit(11f, 7.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun triggerReverseGeocode(lat: Double, lng: Double) {
            reverseGeocodeJob?.cancel()
            reverseGeocodeJob = scope.launch {
                delay(220L)
                val resolvedName = withContext(Dispatchers.IO) {
                    reverseGeocodeLocation(appCtx, lat, lng)
                }
                if (!resolvedName.isNullOrBlank()) {
                    selectedLabel = resolvedName
                    selectedPlaceTitleTv.text = "📍 $selectedLabel"
                }
            }
        }

        val webView = WebView(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#070D17"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            }
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onPinMoved(lat: Double, lng: Double) {
                    Handler(Looper.getMainLooper()).post {
                        selectedLat = lat.coerceIn(-85.0, 85.0)
                        selectedLng = ((((lng + 180.0) % 360.0 + 360.0) % 360.0) - 180.0)
                        selectedCoordsTv.text = String.format(Locale.US, "GPS: %.5f, %.5f", selectedLat, selectedLng)
                        // Also push live preview to WalkBridge so if user wants real-time movement it updates smoothly
                        triggerReverseGeocode(selectedLat, selectedLng)
                    }
                }
            }, "PhantomMapBridge")
        }
        activeWebView = webView
        webView.loadDataWithBaseURL(
            "https://maps.google.com/",
            buildInteractiveMapHtml(selectedLat, selectedLng, currentMapLayer),
            "text/html",
            "UTF-8",
            null
        )

        mapFrame.addView(webView)

        // Search Results Overlay Box inside mapFrame (top-aligned)
        val searchResultsScroll = ScrollView(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(230f),
                Gravity.TOP
            ).apply {
                setMargins(dp(6f), dp(6f), dp(6f), dp(6f))
            }
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#F50C1626"))
                setStroke(dp(1.2f), Color.parseColor("#00E5FF"))
            }
            visibility = View.GONE
            elevation = 16f
        }

        val searchResultsList = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }
        searchResultsScroll.addView(searchResultsList)
        mapFrame.addView(searchResultsScroll)

        // Loading indicator inside mapFrame for search
        val searchProgress = ProgressBar(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(28f), dp(28f), Gravity.TOP or Gravity.END).apply {
                setMargins(dp(12f), dp(12f), dp(12f), dp(12f))
            }
            visibility = View.GONE
        }
        mapFrame.addView(searchProgress)

        card.addView(mapFrame)

        card.addView(View(appCtx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8f))
        })

        // 5. Selected Place & Coordinate Summary Box
        val infoBox = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#0F1B2B"))
                setStroke(dp(1f), Color.parseColor("#1E3A5F"))
            }
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
        }

        val infoTextCol = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(6f), 0)
            }
        }
        infoTextCol.addView(selectedPlaceTitleTv)
        infoTextCol.addView(selectedCoordsTv)

        val randomSlotBtn = TextView(appCtx).apply {
            text = tr("🎲 Empty Slot", "🎲 খালি স্লট", "🎲 खाली स्लॉट")
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            makeSingleLineAutoFit(10.5f, 7.5f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = 7f * density
                setColor(Color.parseColor("#132A3A"))
                setStroke(dp(1f), Color.parseColor("#00E5FF"))
            }
            setOnClickListener {
                WalkBridge.shiftToFreshValidationZone(appCtx)
                val st = WalkBridge.state.value
                selectedLat = st.liveLatitude
                selectedLng = st.liveLongitude
                selectedLabel = st.currentZoneLabel.substringBefore("(").trim().ifBlank {
                    tr("Empty Slot Zone", "খালি স্লট জোন", "खाली स्लॉट ज़ोन")
                }
                selectedPlaceTitleTv.text = "📍 $selectedLabel"
                selectedCoordsTv.text = String.format(Locale.US, "GPS: %.5f, %.5f", selectedLat, selectedLng)
                activeWebView?.evaluateJavascript(
                    "window.flyToLocation && window.flyToLocation($selectedLat, $selectedLng, 16);",
                    null
                )
            }
        }

        infoBox.addView(infoTextCol)
        infoBox.addView(randomSlotBtn)
        card.addView(infoBox)

        card.addView(View(appCtx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8f))
        })

        // 6. Bottom Confirm & Apply Button Row
        val bottomBtnRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44f)
            )
        }

        val cancelBtn = TextView(appCtx).apply {
            text = tr("Cancel", "বাতিল করুন", "रद्द करें")
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(6f), 0, dp(6f), 0)
            makeSingleLineAutoFit(12f, 8f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.65f).apply {
                setMargins(0, 0, dp(8f), 0)
            }
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#162030"))
                setStroke(dp(1f), Color.parseColor("#2A3B52"))
            }
            setOnClickListener {
                dismiss(appCtx)
            }
        }

        val applyLocationBtn = TextView(appCtx).apply {
            text = tr(
                "✅ Set This Location (Apply Map)",
                "✅ এই লোকেশন সেট করুন (Apply Map)",
                "✅ यह लोकेशन सेट करें (Apply Map)"
            )
            setTextColor(Color.parseColor("#04120B"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8f), 0, dp(8f), 0)
            makeSingleLineAutoFit(12f, 7.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.45f)
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#10B981"))
                setStroke(dp(1f), Color.parseColor("#34D399"))
            }
            setOnClickListener {
                WalkBridge.setCustomMapLocation(
                    newLat = selectedLat,
                    newLng = selectedLng,
                    placeLabel = selectedLabel,
                    appContext = appCtx
                )
                onLocationApplied?.invoke(selectedLat, selectedLng, selectedLabel)
                Toast.makeText(
                    appCtx,
                    tr(
                        "📍 Map location set: $selectedLabel (${String.format(Locale.US, "%.4f, %.4f", selectedLat, selectedLng)})",
                        "📍 ম্যাপ লোকেশন সেট হয়েছে: $selectedLabel (${String.format(Locale.US, "%.4f, %.4f", selectedLat, selectedLng)})",
                        "📍 मैप लोकेशन सेट: $selectedLabel (${String.format(Locale.US, "%.4f, %.4f", selectedLat, selectedLng)})"
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                dismiss(appCtx)
            }
        }

        bottomBtnRow.addView(cancelBtn)
        bottomBtnRow.addView(applyLocationBtn)
        card.addView(bottomBtnRow)

        // Helper to render search results into searchResultsList
        fun renderSearchResults(places: List<MapSearchPlace>) {
            searchResultsList.removeAllViews()
            if (places.isEmpty()) {
                val emptyTv = TextView(appCtx).apply {
                    text = tr(
                        "No places found. Try typing another name or city.",
                        "কোনো জায়গা পাওয়া যায়নি। অন্য নাম বা শহরের নাম লিখে চেষ্টা করুন।",
                        "कोई स्थान नहीं मिला। किसी अन्य नाम या शहर का नाम लिखकर प्रयास करें।"
                    )
                    setTextColor(Color.parseColor("#94A3B8"))
                    makeSingleLineAutoFit(11.5f, 7.5f)
                    setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                searchResultsList.addView(emptyTv)
                searchResultsScroll.visibility = View.VISIBLE
                return
            }

            places.forEachIndexed { index, place ->
                val itemLayout = LinearLayout(appCtx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                    background = GradientDrawable().apply {
                        cornerRadius = 7f * density
                        setColor(Color.parseColor("#142236"))
                    }
                    setOnClickListener {
                        selectedLat = place.latitude
                        selectedLng = place.longitude
                        selectedLabel = place.title
                        selectedPlaceTitleTv.text = "📍 ${place.title}"
                        selectedCoordsTv.text = String.format(Locale.US, "GPS: %.5f, %.5f", selectedLat, selectedLng)
                        searchResultsScroll.visibility = View.GONE
                        hideKeyboard(appCtx, searchInput)
                        activeWebView?.evaluateJavascript(
                            "window.flyToLocation && window.flyToLocation($selectedLat, $selectedLng, 16);",
                            null
                        )
                    }
                }

                val pTitle = TextView(appCtx).apply {
                    text = "📍 ${place.title}"
                    setTextColor(Color.parseColor("#00E5FF"))
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    makeSingleLineAutoFit(12f, 8f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val pSub = TextView(appCtx).apply {
                    text = "${place.subtitle}  •  (${String.format(Locale.US, "%.4f, %.4f", place.latitude, place.longitude)})"
                    setTextColor(Color.parseColor("#CBD5E1"))
                    makeSingleLineAutoFit(10f, 7.2f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                itemLayout.addView(pTitle)
                itemLayout.addView(pSub)
                searchResultsList.addView(itemLayout)

                if (index < places.lastIndex) {
                    searchResultsList.addView(View(appCtx).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5f))
                    })
                }
            }
            searchResultsScroll.visibility = View.VISIBLE
        }

        fun performPlaceSearch(rawQuery: String, autoFlyFirst: Boolean) {
            val q = rawQuery.trim()
            if (q.isEmpty()) {
                renderSearchResults(getQuickWorldPresets())
                return
            }
            searchJob?.cancel()
            searchProgress.visibility = View.VISIBLE
            searchJob = scope.launch {
                val results = withContext(Dispatchers.IO) {
                    searchWorldwidePlaces(appCtx, q)
                }
                searchProgress.visibility = View.GONE
                renderSearchResults(results)
                if (autoFlyFirst && results.isNotEmpty()) {
                    val first = results.first()
                    selectedLat = first.latitude
                    selectedLng = first.longitude
                    selectedLabel = first.title
                    selectedPlaceTitleTv.text = "📍 ${first.title}"
                    selectedCoordsTv.text = String.format(Locale.US, "GPS: %.5f, %.5f", selectedLat, selectedLng)
                    hideKeyboard(appCtx, searchInput)
                    activeWebView?.evaluateJavascript(
                        "window.flyToLocation && window.flyToLocation($selectedLat, $selectedLng, 16);",
                        null
                    )
                }
            }
        }

        clearSearchBtn.setOnClickListener {
            searchInput.setText("")
            searchResultsScroll.visibility = View.GONE
        }

        searchGoBtn.setOnClickListener {
            performPlaceSearch(searchInput.text?.toString().orEmpty(), autoFlyFirst = true)
        }

        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                performPlaceSearch(searchInput.text?.toString().orEmpty(), autoFlyFirst = true)
                true
            } else {
                false
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val str = s?.toString()?.trim().orEmpty()
                clearSearchBtn.visibility = if (str.isNotEmpty()) View.VISIBLE else View.GONE
                searchJob?.cancel()
                if (str.length >= 2) {
                    searchJob = scope.launch {
                        delay(350L)
                        performPlaceSearch(str, autoFlyFirst = false)
                    }
                } else {
                    searchResultsScroll.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        rootBackdrop.addView(card)
        activeOverlayView = rootBackdrop

        try {
            wm.addView(rootBackdrop, params)
            triggerReverseGeocode(selectedLat, selectedLng)
        } catch (e: Throwable) {
            activeOverlayView = null
            Toast.makeText(
                appCtx,
                tr("Failed to load map: ${e.message}", "ম্যাপ লোড করতে সমস্যা হয়েছে: ${e.message}", "मैप लोड करने में समस्या: ${e.message}"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun hideKeyboard(context: Context, view: View) {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Throwable) {}
    }

    /**
     * Multi-layer worldwide place search:
     * 1. Direct "lat, lng" coordinate parsing
     * 2. Local instant presets matching
     * 3. OpenStreetMap Nominatim global geocoding API
     * 4. Photon Komoot global geocoding API fallback
     * 5. Android Geocoder fallback
     */
    private fun searchWorldwidePlaces(context: Context, query: String): List<MapSearchPlace> {
        val results = ArrayList<MapSearchPlace>()
        val seenKeys = HashSet<String>()

        fun addUnique(place: MapSearchPlace) {
            if (place.latitude.isNaN() || place.longitude.isNaN()) return
            if (kotlin.math.abs(place.latitude) > 85.0 || kotlin.math.abs(place.longitude) > 180.0) return
            val key = "${String.format(Locale.US, "%.3f", place.latitude)}_${String.format(Locale.US, "%.3f", place.longitude)}"
            if (seenKeys.add(key)) {
                results.add(place)
            }
        }

        // 1. Check if query is raw "lat, lng" coordinates
        val coordMatch = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[,\s]\s*(-?\d+(?:\.\d+)?)\s*$""").find(query)
        if (coordMatch != null) {
            val lat = coordMatch.groupValues[1].toDoubleOrNull()
            val lng = coordMatch.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null && lat in -85.0..85.0 && lng in -180.0..180.0) {
                addUnique(
                    MapSearchPlace(
                        title = "GPS Coordinates ($lat, $lng)",
                        subtitle = tr("Direct coordinate location", "সরাসরি কোঅর্ডিনেট লোকেশন", "सीधे कोऑर्डिनेट लोकेशन"),
                        latitude = lat,
                        longitude = lng
                    )
                )
            }
        }

        // 2. Match built-in quick presets
        getQuickWorldPresets().filter {
            it.title.contains(query, ignoreCase = true) || it.subtitle.contains(query, ignoreCase = true)
        }.forEach { addUnique(it) }

        // 3. OpenStreetMap Nominatim Global Search
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&limit=8&q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "GhostDeviceMapPicker/2.0 (Android)")
                setRequestProperty("Accept-Language", "${LanguageManager.currentLang.code},en;q=0.9")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val lat = obj.optString("lat").toDoubleOrNull() ?: continue
                    val lon = obj.optString("lon").toDoubleOrNull() ?: continue
                    val displayName = obj.optString("display_name", "").trim()
                    if (displayName.isNotEmpty()) {
                        val parts = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val shortTitle = if (parts.size >= 2) "${parts[0]}, ${parts[1]}" else parts.firstOrNull() ?: query
                        val sub = parts.drop(1).joinToString(", ").ifBlank { displayName }
                        addUnique(MapSearchPlace(shortTitle, sub, lat, lon))
                    }
                }
            }
            conn.disconnect()
        } catch (_: Throwable) {}

        // 4. Photon Komoot Global Search Fallback (if Nominatim returned few results)
        if (results.size < 4) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://photon.komoot.io/api/?q=$encoded&limit=8")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "GhostDeviceMapPicker/2.0")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features")
                    if (features != null) {
                        for (i in 0 until features.length()) {
                            val feat = features.getJSONObject(i)
                            val geom = feat.optJSONObject("geometry") ?: continue
                            val coords = geom.optJSONArray("coordinates") ?: continue
                            if (coords.length() >= 2) {
                                val lon = coords.optDouble(0, Double.NaN)
                                val lat = coords.optDouble(1, Double.NaN)
                                val props = feat.optJSONObject("properties")
                                val name = props?.optString("name", "")?.takeIf { it.isNotBlank() } ?: query
                                val city = props?.optString("city", "") ?: props?.optString("state", "") ?: ""
                                val country = props?.optString("country", "") ?: ""
                                val title = listOf(name, city).filter { it.isNotBlank() }.distinct().joinToString(", ")
                                val sub = listOf(city, country).filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { title }
                                addUnique(MapSearchPlace(title, sub, lat, lon))
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) {}
        }

        // 5. Android Built-in Geocoder Fallback
        if (results.isEmpty() && Geocoder.isPresent()) {
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(context, Locale.getDefault()).getFromLocationName(query, 5)
                list?.forEach { addr ->
                    if (addr.hasLatitude() && addr.hasLongitude()) {
                        val feature = addr.featureName ?: addr.locality ?: addr.subAdminArea ?: query
                        val country = addr.countryName ?: ""
                        val title = listOf(feature, country).filter { it.isNotBlank() }.joinToString(", ")
                        val fullLine = (0..addr.maxAddressLineIndex).mapNotNull { addr.getAddressLine(it) }.joinToString(", ")
                        addUnique(MapSearchPlace(title.ifBlank { query }, fullLine.ifBlank { title }, addr.latitude, addr.longitude))
                    }
                }
            } catch (_: Throwable) {}
        }

        return results
    }

    private fun reverseGeocodeLocation(context: Context, lat: Double, lng: Double): String? {
        try {
            val url = URL(
                String.format(
                    Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=json&lat=%.6f&lon=%.6f&zoom=16",
                    lat,
                    lng
                )
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "GhostDeviceMapPicker/2.0 (Android)")
                setRequestProperty("Accept-Language", "${LanguageManager.currentLang.code},en;q=0.9")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val addr = json.optJSONObject("address")
                if (addr != null) {
                    val place = listOf(
                        addr.optString("suburb"),
                        addr.optString("neighbourhood"),
                        addr.optString("road"),
                        addr.optString("village"),
                        addr.optString("town"),
                        addr.optString("city"),
                        addr.optString("state_district")
                    ).firstOrNull { it.isNotBlank() } ?: ""
                    val cityOrCountry = listOf(
                        addr.optString("city"),
                        addr.optString("state"),
                        addr.optString("country")
                    ).firstOrNull { it.isNotBlank() && it != place } ?: ""
                    val combined = listOf(place, cityOrCountry).filter { it.isNotBlank() }.joinToString(", ")
                    if (combined.isNotBlank()) {
                        conn.disconnect()
                        return combined
                    }
                }
                val disp = json.optString("display_name", "")
                if (disp.isNotBlank()) {
                    conn.disconnect()
                    return disp.split(",").take(2).joinToString(", ").trim()
                }
            }
            conn.disconnect()
        } catch (_: Throwable) {}

        if (Geocoder.isPresent()) {
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
                val first = list?.firstOrNull()
                if (first != null) {
                    val p = listOfNotNull(first.subLocality, first.locality, first.countryName)
                        .filter { it.isNotBlank() }
                        .take(2)
                        .joinToString(", ")
                    if (p.isNotBlank()) return p
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Self-contained, zero-external-JS-dependency Slippy Map Engine with:
     * - High-res Satellite Hybrid (Google lyrs=y + Esri World Imagery + Labels fallback)
     * - Pure Satellite (Google lyrs=s + Esri World Imagery fallback)
     * - Street Map (Google lyrs=m + OpenStreetMap fallback)
     * - Smooth 1-finger pan, 2-finger pinch zoom, tap-to-move pin, and +/- zoom controls.
     */
    private fun buildInteractiveMapHtml(
        initLat: Double,
        initLng: Double,
        initLayer: String
    ): String {
        val hintStr = tr(
            "🛰️ Drag the map or tap anywhere to move pin",
            "🛰️ ম্যাপ ড্র্যাগ করুন বা যেকোনো জায়গায় ট্যাপ করুন",
            "🛰️ मैप ड्रैग करें या कहीं भी टैप करें"
        )
        val langCode = LanguageManager.currentLang.code
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; user-select: none; -webkit-user-select: none; }
  html, body { width: 100%; height: 100%; overflow: hidden; background: #070D17; font-family: sans-serif; touch-action: none; }
  #mapCanvas { position: absolute; top: 0; left: 0; width: 100%; height: 100%; display: block; }
  .zoom-controls {
    position: absolute; right: 12px; bottom: 16px; display: flex; flex-direction: column; gap: 8px; z-index: 20;
  }
  .zoom-btn {
    width: 38px; height: 38px; border-radius: 10px; background: rgba(11, 20, 34, 0.92);
    border: 1.5px solid #00E5FF; color: #FFFFFF; font-size: 20px; font-weight: bold;
    display: flex; align-items: center; justify-content: center; box-shadow: 0 4px 12px rgba(0,0,0,0.5);
  }
  .zoom-btn:active { background: #00E5FF; color: #050B14; }
  .hint-pill {
    position: absolute; left: 10px; bottom: 12px; background: rgba(9, 16, 28, 0.88);
    border: 1px solid rgba(0, 229, 255, 0.5); color: #E0F7FA; padding: 5px 10px;
    border-radius: 8px; font-size: 10.5px; pointer-events: none; z-index: 20;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: calc(100% - 64px);
  }
</style>
</head>
<body>
<canvas id="mapCanvas"></canvas>
<div class="hint-pill" id="hintText">$hintStr</div>
<div class="zoom-controls">
  <div class="zoom-btn" id="zoomInBtn">+</div>
  <div class="zoom-btn" id="zoomOutBtn">−</div>
</div>
<script>
(function() {
  var canvas = document.getElementById('mapCanvas');
  var ctx = canvas.getContext('2d');
  var width = window.innerWidth || 320;
  var height = window.innerHeight || 320;

  var centerLat = $initLat;
  var centerLng = $initLng;
  var zoom = 16;
  var currentLayer = '$initLayer';
  var tileCache = {};
  var pulsePhase = 0;

  function resize() {
    width = window.innerWidth || document.documentElement.clientWidth || 320;
    height = window.innerHeight || document.documentElement.clientHeight || 320;
    var dpr = window.devicePixelRatio || 1;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    render();
  }
  window.addEventListener('resize', resize);

  function lngToWorldX(lng, z) {
    var scale = 256 * Math.pow(2, z);
    return ((lng + 180) / 360) * scale;
  }
  function latToWorldY(lat, z) {
    var clamped = Math.max(-85.0511, Math.min(85.0511, lat));
    var sinLat = Math.sin(clamped * Math.PI / 180);
    var scale = 256 * Math.pow(2, z);
    return (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * scale;
  }
  function worldXToLng(wx, z) {
    var scale = 256 * Math.pow(2, z);
    var raw = (wx / scale) * 360 - 180;
    return ((((raw + 180) % 360) + 360) % 360) - 180;
  }
  function worldYToLat(wy, z) {
    var scale = 256 * Math.pow(2, z);
    var n = Math.PI - (2 * Math.PI * wy) / scale;
    var deg = (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
    return Math.max(-85.0, Math.min(85.0, deg));
  }

  function getTileUrls(layer, z, x, y) {
    var maxTile = Math.pow(2, z);
    var normX = ((x % maxTile) + maxTile) % maxTile;
    var sub = (normX + y) % 4;
    if (layer === 'hybrid') {
      return [
        'https://mt' + sub + '.google.com/vt/lyrs=y&hl=$langCode&x=' + normX + '&y=' + y + '&z=' + z,
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/' + z + '/' + y + '/' + normX
      ];
    } else if (layer === 'satellite') {
      return [
        'https://mt' + sub + '.google.com/vt/lyrs=s&x=' + normX + '&y=' + y + '&z=' + z,
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/' + z + '/' + y + '/' + normX
      ];
    } else {
      return [
        'https://mt' + sub + '.google.com/vt/lyrs=m&hl=$langCode&x=' + normX + '&y=' + y + '&z=' + z,
        'https://tile.openstreetmap.org/' + z + '/' + normX + '/' + y + '.png'
      ];
    }
  }

  function getTileImage(layer, z, x, y) {
    var maxTile = Math.pow(2, z);
    if (y < 0 || y >= maxTile) return null;
    var normX = ((x % maxTile) + maxTile) % maxTile;
    var key = layer + '_' + z + '_' + normX + '_' + y;
    var entry = tileCache[key];
    if (entry) return entry.loaded ? entry.img : null;

    var urls = getTileUrls(layer, z, normX, y);
    var img = new Image();
    img.crossOrigin = 'anonymous';
    var state = { img: img, loaded: false, urlIdx: 0 };
    tileCache[key] = state;

    img.onload = function() {
      state.loaded = true;
      render();
    };
    img.onerror = function() {
      state.urlIdx++;
      if (state.urlIdx < urls.length) {
        img.src = urls[state.urlIdx];
      }
    };
    img.src = urls[0];
    return null;
  }

  function render() {
    ctx.fillStyle = '#070D17';
    ctx.fillRect(0, 0, width, height);

    var intZoom = Math.max(2, Math.min(20, Math.round(zoom)));
    var centerWx = lngToWorldX(centerLng, intZoom);
    var centerWy = latToWorldY(centerLat, intZoom);

    var startTileX = Math.floor((centerWx - width / 2) / 256);
    var endTileX = Math.floor((centerWx + width / 2) / 256);
    var startTileY = Math.floor((centerWy - height / 2) / 256);
    var endTileY = Math.floor((centerWy + height / 2) / 256);

    for (var tx = startTileX; tx <= endTileX; tx++) {
      for (var ty = startTileY; ty <= endTileY; ty++) {
        var drawX = Math.round((tx * 256) - centerWx + width / 2);
        var drawY = Math.round((ty * 256) - centerWy + height / 2);
        var tileImg = getTileImage(currentLayer, intZoom, tx, ty);
        if (tileImg) {
          ctx.drawImage(tileImg, drawX, drawY, 256, 256);
        } else {
          // Subtle placeholder grid while loading
          ctx.strokeStyle = 'rgba(0, 229, 255, 0.08)';
          ctx.lineWidth = 1;
          ctx.strokeRect(drawX, drawY, 256, 256);
        }
      }
    }

    // Draw Center Target Pin & Accuracy Ring
    var cx = width / 2;
    var cy = height / 2;

    ctx.save();
    ctx.beginPath();
    ctx.arc(cx, cy, 24, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(0, 229, 255, 0.18)';
    ctx.fill();
    ctx.lineWidth = 1.5;
    ctx.strokeStyle = '#00E5FF';
    ctx.stroke();

    // Crosshair lines
    ctx.beginPath();
    ctx.moveTo(cx - 34, cy); ctx.lineTo(cx - 10, cy);
    ctx.moveTo(cx + 10, cy); ctx.lineTo(cx + 34, cy);
    ctx.moveTo(cx, cy - 34); ctx.lineTo(cx, cy - 10);
    ctx.moveTo(cx, cy + 10); ctx.lineTo(cx, cy + 34);
    ctx.strokeStyle = 'rgba(0, 229, 255, 0.75)';
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // Pin shadow
    ctx.beginPath();
    ctx.ellipse(cx, cy + 2, 7, 3.5, 0, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(0, 0, 0, 0.55)';
    ctx.fill();

    // Pin teardrop body
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.bezierCurveTo(cx - 14, cy - 16, cx - 15, cy - 34, cx, cy - 34);
    ctx.bezierCurveTo(cx + 15, cy - 34, cx + 14, cy - 16, cx, cy);
    ctx.fillStyle = '#FF2A6D';
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = '#FFFFFF';
    ctx.stroke();

    // Inner white dot of pin
    ctx.beginPath();
    ctx.arc(cx, cy - 22, 5, 0, Math.PI * 2);
    ctx.fillStyle = '#FFFFFF';
    ctx.fill();

    // Center exact GPS point dot
    ctx.beginPath();
    ctx.arc(cx, cy, 3.5, 0, Math.PI * 2);
    ctx.fillStyle = '#10B981';
    ctx.fill();
    ctx.restore();
  }

  function notifyKotlin() {
    if (window.PhantomMapBridge && window.PhantomMapBridge.onPinMoved) {
      window.PhantomMapBridge.onPinMoved(centerLat, centerLng);
    }
  }

  // Touch & Mouse Interaction (Pan, Tap-to-Select, Pinch-to-Zoom)
  var isDragging = false;
  var startX = 0, startY = 0;
  var startWx = 0, startWy = 0;
  var movedDist = 0;
  var initialPinchDist = 0;
  var initialPinchZoom = 16;

  canvas.addEventListener('touchstart', function(e) {
    e.preventDefault();
    var intZoom = Math.max(2, Math.min(20, Math.round(zoom)));
    if (e.touches.length === 1) {
      isDragging = true;
      movedDist = 0;
      startX = e.touches[0].clientX;
      startY = e.touches[0].clientY;
      startWx = lngToWorldX(centerLng, intZoom);
      startWy = latToWorldY(centerLat, intZoom);
    } else if (e.touches.length >= 2) {
      isDragging = false;
      var dx = e.touches[0].clientX - e.touches[1].clientX;
      var dy = e.touches[0].clientY - e.touches[1].clientY;
      initialPinchDist = Math.hypot(dx, dy);
      initialPinchZoom = zoom;
    }
  }, { passive: false });

  canvas.addEventListener('touchmove', function(e) {
    e.preventDefault();
    var intZoom = Math.max(2, Math.min(20, Math.round(zoom)));
    if (e.touches.length === 1 && isDragging) {
      var dx = e.touches[0].clientX - startX;
      var dy = e.touches[0].clientY - startY;
      movedDist = Math.hypot(dx, dy);
      var newWx = startWx - dx;
      var newWy = startWy - dy;
      centerLng = worldXToLng(newWx, intZoom);
      centerLat = worldYToLat(newWy, intZoom);
      render();
      notifyKotlin();
    } else if (e.touches.length >= 2 && initialPinchDist > 10) {
      var dx2 = e.touches[0].clientX - e.touches[1].clientX;
      var dy2 = e.touches[0].clientY - e.touches[1].clientY;
      var dist = Math.hypot(dx2, dy2);
      var ratio = dist / initialPinchDist;
      var zDelta = Math.log2(ratio);
      var nextZ = Math.max(2, Math.min(20, Math.round(initialPinchZoom + zDelta)));
      if (nextZ !== Math.round(zoom)) {
        zoom = nextZ;
        render();
      }
    }
  }, { passive: false });

  canvas.addEventListener('touchend', function(e) {
    e.preventDefault();
    if (isDragging && movedDist < 8) {
      // Single tap anywhere on the map -> move center pin directly to tapped point!
      var rect = canvas.getBoundingClientRect();
      var tapX = startX - rect.left;
      var tapY = startY - rect.top;
      var offsetX = tapX - (width / 2);
      var offsetY = tapY - (height / 2);
      var intZoom = Math.max(2, Math.min(20, Math.round(zoom)));
      var newWx = lngToWorldX(centerLng, intZoom) + offsetX;
      var newWy = latToWorldY(centerLat, intZoom) + offsetY;
      centerLng = worldXToLng(newWx, intZoom);
      centerLat = worldYToLat(newWy, intZoom);
      render();
      notifyKotlin();
    }
    isDragging = false;
  }, { passive: false });

  // Mouse support for emulator / desktop testing
  canvas.addEventListener('mousedown', function(e) {
    var intZoom = Math.max(2, Math.min(20, Math.round(zoom)));
    isDragging = true;
    movedDist = 0;
    startX = e.clientX;
    startY = e.clientY;
    startWx = lngToWorldX(centerLng, intZoom);
    startWy = latToWorldY(centerLat, intZoom);
  });
  window.addEventListener('mousemove', function(e) {
    if (!isDragging) return;
    var intZoom = Math.max(2, Math.min(20, Math.round(zoom)));
    var dx = e.clientX - startX;
    var dy = e.clientY - startY;
    movedDist = Math.hypot(dx, dy);
    centerLng = worldXToLng(startWx - dx, intZoom);
    centerLat = worldYToLat(startWy - dy, intZoom);
    render();
    notifyKotlin();
  });
  window.addEventListener('mouseup', function(e) {
    if (!isDragging) return;
    if (movedDist < 6) {
      var rect = canvas.getBoundingClientRect();
      var tapX = e.clientX - rect.left;
      var tapY = e.clientY - rect.top;
      var intZoom = Math.max(2, Math.min(20, Math.round(zoom)));
      var newWx = lngToWorldX(centerLng, intZoom) + (tapX - width / 2);
      var newWy = latToWorldY(centerLat, intZoom) + (tapY - height / 2);
      centerLng = worldXToLng(newWx, intZoom);
      centerLat = worldYToLat(newWy, intZoom);
      render();
      notifyKotlin();
    }
    isDragging = false;
  });

  document.getElementById('zoomInBtn').addEventListener('click', function() {
    zoom = Math.min(20, Math.round(zoom) + 1);
    render();
  });
  document.getElementById('zoomOutBtn').addEventListener('click', function() {
    zoom = Math.max(2, Math.round(zoom) - 1);
    render();
  });

  window.setMapLayer = function(layer) {
    currentLayer = layer;
    render();
  };

  window.flyToLocation = function(lat, lng, targetZoom) {
    centerLat = lat;
    centerLng = lng;
    if (targetZoom) zoom = targetZoom;
    render();
    notifyKotlin();
  };

  resize();
})();
</script>
</body>
</html>
        """.trimIndent()
    }

    private object Settings {
        fun canDrawOverlaysCompat(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
        }
    }
}
