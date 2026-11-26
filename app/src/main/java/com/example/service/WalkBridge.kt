package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.example.data.device.RootChecker
import com.example.ui.WalkJoystickState
import com.example.ui.i18n.tr
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

object WalkBridge {

    val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val mutableState = MutableStateFlow(WalkJoystickState())
    val state = mutableState.asStateFlow()
    private val _state = mutableState

    private val _isFloatingServiceRunning = MutableStateFlow(false)
    val isFloatingServiceRunning = _isFloatingServiceRunning.asStateFlow()

    private var walkSimulationJob: Job? = null
    private var nftValidationJob: Job? = null
    private var walkStepFractionAccumulator = 0.0
    private val walkPropSyncInFlight = AtomicBoolean(false)
    private val pendingRootSync = AtomicBoolean(false)
    private val fileWriteInFlight = AtomicBoolean(false)
    private val pendingFileWrite = AtomicBoolean(false)
    @Volatile
    private var lastRootSyncMs: Long = 0L

    var defaultLatitude: Double = 23.8103
    var defaultLongitude: Double = 90.4125
    @Volatile
    var hasCapturedRealPhoneGps: Boolean = false
    @Volatile
    var hasCustomMapLocationSelected: Boolean = false
    @Volatile
    var onCustomMapLocationApplied: ((Double, Double, String) -> Unit)? = null

    fun onRealPhoneLocationDetected(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) return
        if (hasCustomMapLocationSelected) return
        defaultLatitude = lat
        defaultLongitude = lng
        val cur = _state.value
        if (!hasCapturedRealPhoneGps && !cur.isDragging && !cur.isAutoHoldLocked && !cur.isNftValidationRunning) {
            hasCapturedRealPhoneGps = true
            _state.value = cur.copy(
                liveLatitude = lat,
                liveLongitude = lng
            )
        }
    }

    fun setFloatingRunning(running: Boolean) {
        _isFloatingServiceRunning.value = running
    }

    fun updateJoystickVector(nx: Float, ny: Float, isDragging: Boolean) {
        val cur = _state.value
        if (cur.isVolumeSpeedMode) {
            updateVolumeFromStickTouch(nx, ny, isDragging)
            return
        }
        if (!isDragging) {
            if (cur.isAutoHoldLocked && cur.magnitude > 0.03f) {
                // In Auto Hold mode, releasing finger MUST KEEP the current direction and speed hands-free!
                _state.value = cur.copy(isDragging = false)
                startWalkSimulationLoopIfNeeded()
            } else {
                _state.value = cur.copy(
                    isDragging = false,
                    knobX = 0f,
                    knobY = 0f,
                    magnitude = 0f,
                    speedMps = 0f
                )
                pushLiveWalkTelemetryAsync(
                    lat = cur.liveLatitude,
                    lng = cur.liveLongitude,
                    speedMps = 0f,
                    bearingDeg = cur.bearingDegrees,
                    steps = cur.stepsCount,
                    isActive = hasCustomMapLocationSelected,
                    force = true
                )
            }
            return
        }
        hasCustomMapLocationSelected = true

        val rawMag = kotlin.math.sqrt(nx * nx + ny * ny)
        val clampedMag = rawMag.coerceIn(0f, 1f)
        val scale = if (rawMag > 1f) 1f / rawMag else 1f
        val cx = (nx * scale).coerceIn(-1f, 1f)
        val cy = (ny * scale).coerceIn(-1f, 1f)

        val bearing = if (clampedMag > 0.02f) {
            ((Math.toDegrees(kotlin.math.atan2(cx.toDouble(), (-cy).toDouble())).toFloat() + 360f) % 360f)
        } else {
            cur.bearingDegrees
        }
        // Realistic human walking/jogging speed: 0.85 m/s (3.0 km/h slow walk) -> 2.85 m/s (10.2 km/h natural brisk jog)
        val speedMps = if (clampedMag > 0.03f) {
            (0.85f + clampedMag * 2.0f)
        } else {
            0f
        }

        val baseLat = if (cur.liveLatitude != 0.0) cur.liveLatitude else defaultLatitude
        val baseLng = if (cur.liveLongitude != 0.0) cur.liveLongitude else defaultLongitude

        _state.value = cur.copy(
            isDragging = true,
            knobX = cx,
            knobY = cy,
            magnitude = clampedMag,
            bearingDegrees = bearing,
            speedMps = speedMps,
            liveLatitude = baseLat,
            liveLongitude = baseLng
        )
        if (clampedMag > 0.03f) {
            startWalkSimulationLoopIfNeeded()
        }
    }

    fun toggleAutoHoldLock() {
        val cur = _state.value
        val newLocked = !cur.isAutoHoldLocked
        if (newLocked) {
            // Turning lock ON: if already moving, keep exact vector; if standing still, start smooth forward walk (1.75 m/s = 6.3 km/h)
            val hasMotion = cur.magnitude > 0.03f
            val newMag = if (hasMotion) cur.magnitude else 0.5f
            val newSpeed = if (hasMotion) cur.speedMps else 1.75f
            val newKnobX = if (hasMotion) cur.knobX else 0f
            val newKnobY = if (hasMotion) cur.knobY else -0.5f
            val bearing = cur.bearingDegrees
            val baseLat = if (cur.liveLatitude != 0.0) cur.liveLatitude else defaultLatitude
            val baseLng = if (cur.liveLongitude != 0.0) cur.liveLongitude else defaultLongitude

            _state.value = cur.copy(
                isAutoHoldLocked = true,
                isDragging = false,
                knobX = newKnobX,
                knobY = newKnobY,
                magnitude = newMag,
                speedMps = newSpeed,
                bearingDegrees = bearing,
                liveLatitude = baseLat,
                liveLongitude = baseLng
            )
            startWalkSimulationLoopIfNeeded()
            pushLiveWalkTelemetryAsync(
                lat = baseLat,
                lng = baseLng,
                speedMps = newSpeed,
                bearingDeg = bearing,
                steps = cur.stepsCount,
                isActive = true,
                force = true
            )
        } else {
            // Turning lock OFF -> reset knob to center and stop walking
            _state.value = cur.copy(
                isAutoHoldLocked = false,
                isDragging = false,
                knobX = 0f,
                knobY = 0f,
                magnitude = 0f,
                speedMps = 0f
            )
            pushLiveWalkTelemetryAsync(
                lat = cur.liveLatitude,
                lng = cur.liveLongitude,
                speedMps = 0f,
                bearingDeg = cur.bearingDegrees,
                steps = cur.stepsCount,
                isActive = hasCustomMapLocationSelected,
                force = true
            )
        }
    }

    fun togglePanelExpanded() {
        _state.value = _state.value.copy(
            isExpanded = !_state.value.isExpanded
        )
    }

    fun resetWalkStepsAndDistance() {
        walkStepFractionAccumulator = 0.0
        val cur = _state.value
        _state.value = cur.copy(
            stepsCount = 0,
            distanceMeters = 0.0
        )
        pushLiveWalkTelemetryAsync(
            lat = cur.liveLatitude,
            lng = cur.liveLongitude,
            speedMps = cur.speedMps,
            bearingDeg = cur.bearingDegrees,
            steps = 0,
            isActive = cur.magnitude > 0.03f || hasCustomMapLocationSelected,
            force = true
        )
    }

    fun addInstantSteps(extraSteps: Int = 100) {
        if (extraSteps <= 0) return
        val cur = _state.value
        val stride = 0.74
        val extraMeters = extraSteps * stride
        val bearingRad = Math.toRadians(cur.bearingDegrees.toDouble())
        val baseLat = if (cur.liveLatitude != 0.0) cur.liveLatitude else defaultLatitude
        val baseLng = if (cur.liveLongitude != 0.0) cur.liveLongitude else defaultLongitude
        val dLat = (extraMeters * kotlin.math.cos(bearingRad)) / 111320.0
        val cosLat = kotlin.math.cos(Math.toRadians(baseLat)).coerceAtLeast(0.1)
        val dLng = (extraMeters * kotlin.math.sin(bearingRad)) / (111320.0 * cosLat)
        val newLat = (baseLat + dLat).coerceIn(-85.0, 85.0)
        val newLng = ((((baseLng + dLng) + 180.0) % 360.0 + 360.0) % 360.0) - 180.0
        val newSteps = cur.stepsCount + extraSteps
        val newDist = cur.distanceMeters + extraMeters

        _state.value = cur.copy(
            stepsCount = newSteps,
            distanceMeters = newDist,
            liveLatitude = newLat,
            liveLongitude = newLng
        )
        pushLiveWalkTelemetryAsync(
            lat = newLat,
            lng = newLng,
            speedMps = cur.speedMps.coerceAtLeast(1.65f),
            bearingDeg = cur.bearingDegrees,
            steps = newSteps,
            isActive = true,
            force = true
        )
    }

    private fun startWalkSimulationLoopIfNeeded() {
        if (walkSimulationJob?.isActive == true) return
        walkSimulationJob = bridgeScope.launch {
            var lastTickMs = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val cur = _state.value
                if (cur.magnitude <= 0.03f || (!cur.isDragging && !cur.isAutoHoldLocked)) {
                    break
                }
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                val dt = ((nowElapsed - lastTickMs).coerceIn(40L, 160L)) / 1000.0
                lastTickMs = nowElapsed

                // Smooth, natural human walking speed rhythm (gentle breathing/stride wave over 3-5 seconds)
                val tSec = nowElapsed / 1000.0
                val naturalPaceWave = (kotlin.math.sin(tSec * 1.9) * 0.14 + kotlin.math.cos(tSec * 0.85) * 0.08).toFloat()
                val humanSpeed = if (cur.speedMps > 0.1f) {
                    (cur.speedMps * (1f + naturalPaceWave * 0.12f)).coerceIn(0.45f, 3.2f)
                } else {
                    cur.speedMps
                }
                // Gentle, realistic human path sway (±0.7° smooth drift, never jumping backward)
                val humanBearing = if (cur.speedMps > 0.1f) {
                    (cur.bearingDegrees + (kotlin.math.sin(tSec * 1.4) * 0.7).toFloat() + 360f) % 360f
                } else {
                    cur.bearingDegrees
                }

                val distDelta = humanSpeed.toDouble() * dt
                val bearingRad = Math.toRadians(humanBearing.toDouble())
                val baseLat = if (cur.liveLatitude != 0.0) cur.liveLatitude else defaultLatitude
                val baseLng = if (cur.liveLongitude != 0.0) cur.liveLongitude else defaultLongitude

                val dLat = (distDelta * kotlin.math.cos(bearingRad)) / 111320.0
                val cosLat = kotlin.math.cos(Math.toRadians(baseLat)).coerceAtLeast(0.1)
                val dLng = (distDelta * kotlin.math.sin(bearingRad)) / (111320.0 * cosLat)

                val newLat = (baseLat + dLat).coerceIn(-85.0, 85.0)
                val newLng = ((((baseLng + dLng) + 180.0) % 360.0 + 360.0) % 360.0) - 180.0
                val newDist = cur.distanceMeters + distDelta

                val strideMeters = (0.68 + (humanSpeed * 0.03)).coerceIn(0.65, 0.88)
                walkStepFractionAccumulator += (distDelta / strideMeters)
                val addSteps = walkStepFractionAccumulator.toInt()
                if (addSteps > 0) {
                    walkStepFractionAccumulator -= addSteps.toDouble()
                }
                val newSteps = cur.stepsCount + addSteps

                _state.value = cur.copy(
                    liveLatitude = newLat,
                    liveLongitude = newLng,
                    distanceMeters = newDist,
                    stepsCount = newSteps,
                    speedMps = humanSpeed
                )

                pushLiveWalkTelemetryAsync(
                    lat = newLat,
                    lng = newLng,
                    speedMps = humanSpeed,
                    bearingDeg = humanBearing,
                    steps = newSteps,
                    isActive = true,
                    force = false
                )
                delay(80L)
            }
        }
    }

    @Volatile
    private var slotZoneIndex: Int = 0

    private fun getFreshUncongestedZones(): Array<Triple<String, Double, Double>> = arrayOf(
        Triple(tr("Uttara Sector 15 Open Park", "উত্তরা সেক্টর ১৫ ওপেন পার্ক", "उत्तरा सेक्टर 15 ओपन पार्क"), 23.8759, 90.3795),
        Triple(tr("Purbachal Sector 4 Green Zone", "পূর্বাচল সেক্টর ৪ গ্রিন জোন", "पूर्वाचल सेक्टर 4 ग्रीन ज़ोन"), 23.8472, 90.5018),
        Triple(tr("Bashundhara M Block Open Field", "বসুন্ধরা এম ব্লক ওপেন ফিল্ড", "बसुंधरा एम ब्लॉक ओपन फील्ड"), 23.8225, 90.4485),
        Triple(tr("Aftabnagar Eastern Riverview", "আফতাবনগর ইস্টার্ন রিভারভিউ", "आफताबनगर ईस्टर्न रिवरव्यू"), 23.7664, 90.4592),
        Triple(tr("Dhanmondi Lake Open Corridor", "ধানমন্ডি লেক ওপেন করিডোর", "धानमंडी लेक ओपन कॉरिडोर"), 23.7461, 90.3742),
        Triple(tr("Ramna Green Park Zone", "রমনা গ্রিন পার্ক জোন", "रमना ग्रीन पार्क ज़ोन"), 23.7382, 90.4008),
        Triple(tr("Mirpur DOHS Open Avenue", "মিরপুর ডিওএইচএস ওপেন অ্যাভিনিউ", "मिरपुर DOHS ओपन एवेन्यू"), 23.8365, 90.3695),
        Triple(tr("Keraniganj Jhilmil Open Zone", "কেরানীগঞ্জ ঝিলমিল ওপেন জোন", "केरानीगंज झिलमिल ओपन ज़ोन"), 23.6895, 90.3652),
        Triple(tr("Savar Dairy Open Green Field", "সাভার ডেইরি ওপেন গ্রিন ফিল্ড", "सावर डेयरी ओपन ग्रीन फील्ड"), 23.8884, 90.2692),
        Triple(tr("Gazipur Bhawal National Park", "গাজীপুর ভাওয়াল ন্যাশনাল পার্ক", "गाजीपुर भावाल नेशनल पार्क"), 24.0958, 90.4025),
        Triple(tr("Chattogram Patenga Sea Beach Corridor", "চট্টগ্রাম পতেঙ্গা সি-বিচ করিডোর", "चटगांव पतेंगा सी-बीच कॉरिडोर"), 22.2356, 91.7914),
        Triple(tr("Sylhet Tea Garden Open Valley", "সিলেট চা বাগান ওপেন ভ্যালি", "सिलहट टी गार्डन ओपन वैली"), 24.9245, 91.8812),
        Triple(tr("Rajshahi Padma Garden Open Zone", "রাজশাহী পদ্মা গার্ডেন ওপেন জোন", "राजशाही पद्मा गार्डन ओपन ज़ोन"), 24.3636, 88.5941),
        Triple(tr("Khulna Rupsha Riverside Park", "খুলনা রূপসা রিভারসাইড পার্ক", "खुलना रूपसा रिवरसाइड पार्क"), 22.8098, 89.5644),
        Triple(tr("Cox's Bazar Marine Drive Zone", "কক্সবাজার মেরিন ড্রাইভ জোন", "कॉक्स बाजार मरीन ड्राइव ज़ोन"), 21.3854, 92.0125),
        Triple(tr("Cumilla Kotbari Open Reserve", "কুমিল্লা কোটবাড়ি ওপেন রিজার্ভ", "कुमिल्ला कोटबारी ओपन रिज़र्व"), 23.4285, 91.1352)
    )

    fun multiplierToSignedStep(mult: Float): Int {
        val c = mult.coerceIn(0.001f, 1000.0f)
        return when {
            c in 0.96f..1.04f -> 1
            c > 1.04f -> c.roundToInt().coerceIn(2, 1000)
            else -> -((1.0f / c).roundToInt().coerceIn(2, 1000))
        }
    }

    fun signedStepToMultiplier(step: Int): Float {
        return when {
            step >= 2 -> step.coerceIn(2, 1000).toFloat()
            step <= -2 -> (1.0f / (-step).coerceIn(2, 1000).toFloat()).coerceIn(0.001f, 0.5f)
            else -> 1.0f
        }
    }

    fun formatSpeedMultiplier(mult: Float): String {
        val step = multiplierToSignedStep(mult)
        return when {
            step >= 2 -> "+${step}x"
            step <= -2 -> "${step}x"
            else -> "1.0x"
        }
    }

    fun toggleVolumeSpeedMode() {
        val cur = _state.value
        val nextMode = !cur.isVolumeSpeedMode
        if (nextMode) {
            // Switching to Volume Speed Mode: position knob on the rotary arc corresponding to current appSpeedMultiplier
            val (kx, ky) = speedMultiplierToDialKnob(cur.appSpeedMultiplier)
            _state.value = cur.copy(
                isVolumeSpeedMode = true,
                knobX = kx,
                knobY = ky
            )
        } else {
            // Switching back to Walk Joystick Mode
            _state.value = cur.copy(
                isVolumeSpeedMode = false,
                knobX = if (cur.isAutoHoldLocked) cur.knobX else 0f,
                knobY = if (cur.isAutoHoldLocked) cur.knobY else 0f
            )
        }
    }

    fun speedMultiplierToProgress(mult: Float): Float {
        val signedStep = multiplierToSignedStep(mult)
        if (signedStep == 1) return 0.5f
        val absStep = kotlin.math.abs(signedStep).coerceIn(2, 1000)
        val frac = when {
            absStep <= 15 -> 0.02f + ((absStep - 2f) / 13f) * 0.58f
            absStep <= 100 -> 0.60f + ((absStep - 15f) / 85f) * 0.25f
            else -> 0.85f + ((absStep - 100f) / 900f) * 0.15f
        }.coerceIn(0f, 1f)
        return if (signedStep > 1) {
            (0.5f + frac * 0.5f).coerceIn(0.5f, 1.0f)
        } else {
            (0.5f - frac * 0.5f).coerceIn(0.0f, 0.5f)
        }
    }

    fun progressToSpeedMultiplier(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val delta = p - 0.5f
        if (kotlin.math.abs(delta) < 0.025f) return 1.0f
        val sign = if (delta > 0f) 1 else -1
        val frac = (kotlin.math.abs(delta) / 0.5f).coerceIn(0f, 1f)
        val absVal = when {
            frac <= 0.60f -> {
                val t = frac / 0.60f
                2 + kotlin.math.round(t * 13f).toInt() // 2x .. 15x
            }
            frac <= 0.85f -> {
                val t = (frac - 0.60f) / 0.25f
                val raw = 15 + kotlin.math.round(t * 85f).toInt() // 15x .. 100x
                ((raw + 2) / 5) * 5
            }
            else -> {
                val t = (frac - 0.85f) / 0.15f
                val raw = 100 + kotlin.math.round(t * 900f).toInt() // 100x .. 1000x
                ((raw + 25) / 50) * 50
            }
        }.coerceIn(2, 1000)
        return signedStepToMultiplier(sign * absVal)
    }

    private fun normalizeSpeedMultiplier(raw: Float): Float {
        return signedStepToMultiplier(multiplierToSignedStep(raw))
    }

    private fun speedMultiplierToDialKnob(mult: Float): Pair<Float, Float> {
        val progress = speedMultiplierToProgress(mult)
        // Rotary volume dial sweeps from -135° (bottom-left, -1000x SLOW) through 0° (top, 1.0x) to +135° (bottom-right, +1000x FAST)
        val angleDeg = -135.0 + (progress * 270.0)
        val angleRad = Math.toRadians(angleDeg)
        val kx = (kotlin.math.sin(angleRad) * 0.82).toFloat()
        val ky = (-kotlin.math.cos(angleRad) * 0.82).toFloat()
        return Pair(kx, ky)
    }

    fun setAppSpeedMultiplier(newMult: Float, appContext: Context? = null, forceRootSync: Boolean = true) {
        val clamped = normalizeSpeedMultiplier(newMult)
        com.example.provider.PhantomIdentityProvider.liveAppSpeedMultiplier = clamped
        val cur = _state.value
        val (kx, ky) = if (cur.isVolumeSpeedMode) speedMultiplierToDialKnob(clamped) else Pair(cur.knobX, cur.knobY)
        _state.value = cur.copy(
            appSpeedMultiplier = clamped,
            knobX = kx,
            knobY = ky
        )
        if (appContext != null) {
            try {
                appContext.getSharedPreferences("phantomcore_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putFloat("app_speed_multiplier", clamped)
                    .apply()
            } catch (_: Throwable) {}
        }
        pushLiveWalkTelemetryAsync(
            lat = cur.liveLatitude,
            lng = cur.liveLongitude,
            speedMps = cur.speedMps,
            bearingDeg = cur.bearingDegrees,
            steps = cur.stepsCount,
            isActive = cur.magnitude > 0.03f,
            force = forceRootSync
        )
    }

    fun stepAppSpeedMultiplier(increase: Boolean, appContext: Context? = null) {
        val curStep = multiplierToSignedStep(_state.value.appSpeedMultiplier)
        val nextStep = if (increase) {
            when {
                curStep == 1 -> 2
                curStep == -2 -> 1
                curStep > 1 -> {
                    val step = when {
                        curStep < 10 -> 1
                        curStep < 50 -> 5
                        curStep < 200 -> 25
                        else -> 100
                    }
                    (curStep + step).coerceAtMost(1000)
                }
                else -> {
                    val absVal = -curStep
                    val step = when {
                        absVal <= 10 -> 1
                        absVal <= 50 -> 5
                        absVal <= 200 -> 25
                        else -> 100
                    }
                    val nextAbs = absVal - step
                    if (nextAbs < 2) 1 else -nextAbs
                }
            }
        } else {
            when {
                curStep == 1 -> -2
                curStep == 2 -> 1
                curStep > 2 -> {
                    val step = when {
                        curStep <= 10 -> 1
                        curStep <= 50 -> 5
                        curStep <= 200 -> 25
                        else -> 100
                    }
                    val nextVal = curStep - step
                    if (nextVal < 2) 1 else nextVal
                }
                else -> {
                    val absVal = -curStep
                    val step = when {
                        absVal < 10 -> 1
                        absVal < 50 -> 5
                        absVal < 200 -> 25
                        else -> 100
                    }
                    -(absVal + step).coerceAtMost(1000)
                }
            }
        }
        setAppSpeedMultiplier(signedStepToMultiplier(nextStep), appContext, forceRootSync = true)
    }

    fun resetAppSpeedMultiplier(appContext: Context? = null) {
        setAppSpeedMultiplier(1.0f, appContext, forceRootSync = true)
    }

    fun updateVolumeFromStickTouch(nx: Float, ny: Float, isDragging: Boolean) {
        if (!isDragging) {
            val cur = _state.value
            pushLiveWalkTelemetryAsync(
                lat = cur.liveLatitude,
                lng = cur.liveLongitude,
                speedMps = cur.speedMps,
                bearingDeg = cur.bearingDegrees,
                steps = cur.stepsCount,
                isActive = cur.magnitude > 0.03f,
                force = true
            )
            return
        }
        val dist = kotlin.math.sqrt(nx * nx + ny * ny)
        val progress = if (dist > 0.18f) {
            // Rotary volume dial angle (-135° to +135°)
            val angleDeg = Math.toDegrees(kotlin.math.atan2(nx.toDouble(), (-ny).toDouble())).toFloat()
            ((angleDeg + 135f) / 270f).coerceIn(0f, 1f)
        } else {
            // Vertical volume slide fallback near center
            ((-ny + 1f) / 2f).coerceIn(0f, 1f)
        }
        val mult = progressToSpeedMultiplier(progress)
        setAppSpeedMultiplier(mult, appContext = null, forceRootSync = false)
    }

    fun shiftToFreshValidationZone(appContext: Context? = null) {
        val cur = _state.value
        val zones = getFreshUncongestedZones()
        val idx = slotZoneIndex % zones.size
        slotZoneIndex++
        val (zoneTitle, centerLat, centerLng) = zones[idx]

        // Add a deterministic + time-seeded sub-offset (250m - 950m) so every single tap lands on a completely unused S2/H3 cell
        val goldenAngle = (slotZoneIndex * 137.508 + (System.currentTimeMillis() % 360)) * (Math.PI / 180.0)
        val radiusKm = 0.35 + ((slotZoneIndex % 5) * 0.15)
        val dLat = (radiusKm * kotlin.math.cos(goldenAngle)) / 111.32
        val dLng = (radiusKm * kotlin.math.sin(goldenAngle)) / (111.32 * kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1))

        val newLat = (centerLat + dLat).coerceIn(-85.0, 85.0)
        val newLng = ((((centerLng + dLng) + 180.0) % 360.0 + 360.0) % 360.0) - 180.0

        defaultLatitude = newLat
        defaultLongitude = newLng
        hasCapturedRealPhoneGps = true
        hasCustomMapLocationSelected = true

        val zoneSummary = "$zoneTitle (${String.format(Locale.US, "%.4f", newLat)}, ${String.format(Locale.US, "%.4f", newLng)})"
        _state.value = cur.copy(
            liveLatitude = newLat,
            liveLongitude = newLng,
            currentZoneLabel = zoneSummary,
            nftSpot1Seconds = 0,
            nftSpot2Seconds = 0,
            nftValidationPhaseLabel = tr("📍 Empty Slot: $zoneSummary", "📍 খালি স্লট: $zoneSummary", "📍 खाली स्लॉट: $zoneSummary")
        )
        // Push immediately with isActive = true and force = true so target app GPS jumps to the new slot area right away
        pushLiveWalkTelemetryAsync(
            lat = newLat,
            lng = newLng,
            speedMps = cur.speedMps.coerceAtLeast(0.06f),
            bearingDeg = cur.bearingDegrees,
            steps = cur.stepsCount,
            isActive = true,
            force = true
        )
    }

    fun setCustomMapLocation(
        newLat: Double,
        newLng: Double,
        placeLabel: String = "",
        appContext: Context? = null
    ) {
        val clampedLat = newLat.coerceIn(-85.0, 85.0)
        val clampedLng = ((((newLng + 180.0) % 360.0 + 360.0) % 360.0) - 180.0)
        defaultLatitude = clampedLat
        defaultLongitude = clampedLng
        hasCapturedRealPhoneGps = true
        hasCustomMapLocationSelected = true

        val cur = _state.value
        val cleanTitle = placeLabel.trim().ifBlank { "Custom Map Spot" }
        _state.value = cur.copy(
            liveLatitude = clampedLat,
            liveLongitude = clampedLng,
            currentZoneLabel = cleanTitle,
            nftSpot1Seconds = 0,
            nftSpot2Seconds = 0,
            nftValidationPhaseLabel = tr("📍 Map: $cleanTitle", "📍 ম্যাপ: $cleanTitle", "📍 मैप: $cleanTitle")
        )

        pushLiveWalkTelemetryAsync(
            lat = clampedLat,
            lng = clampedLng,
            speedMps = cur.speedMps,
            bearingDeg = cur.bearingDegrees,
            steps = cur.stepsCount,
            isActive = true,
            force = true
        )

        try {
            onCustomMapLocationApplied?.invoke(clampedLat, clampedLng, cleanTitle)
        } catch (_: Throwable) {}

        // Also update cached & on-disk phantom_identity.json so any app reading identity JSON gets the new map coordinates
        bridgeScope.launch(Dispatchers.IO) {
            try {
                val curJsonStr = com.example.provider.PhantomIdentityProvider.cachedIdentityJson
                if (curJsonStr.isNotBlank()) {
                    val json = org.json.JSONObject(curJsonStr).apply {
                        put("latitude", clampedLat)
                        put("longitude", clampedLng)
                        put("locationName", cleanTitle)
                    }.toString()
                    com.example.provider.PhantomIdentityProvider.cachedIdentityJson = json
                    if (appContext != null) {
                        try {
                            File(appContext.filesDir, "phantom_identity.json").writeText(json)
                        } catch (_: Throwable) {}
                    }
                    for (p in arrayOf("/data/local/tmp/phantom_identity.json", "/sdcard/phantom_identity.json")) {
                        try {
                            val f = File(p)
                            f.writeText(json)
                            f.setReadable(true, false)
                            f.setWritable(true, false)
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun toggleNftAutoValidation(appContext: Context? = null) {
        val cur = _state.value
        if (cur.isNftValidationRunning) {
            nftValidationJob?.cancel()
            nftValidationJob = null
            _state.value = cur.copy(
                isNftValidationRunning = false,
                nftValidationPhaseLabel = "",
                knobX = 0f,
                knobY = 0f,
                magnitude = 0f,
                speedMps = 0f
            )
            pushLiveWalkTelemetryAsync(
                lat = cur.liveLatitude,
                lng = cur.liveLongitude,
                speedMps = 0f,
                bearingDeg = cur.bearingDegrees,
                steps = cur.stepsCount,
                isActive = false,
                force = true
            )
            return
        }
        shiftToFreshValidationZone(appContext)
    }

    fun pushLiveWalkTelemetryAsync(
        lat: Double,
        lng: Double,
        speedMps: Float,
        bearingDeg: Float,
        steps: Int,
        isActive: Boolean,
        force: Boolean = false
    ) {
        val appSpeedMult = _state.value.appSpeedMultiplier.coerceIn(0.001f, 1000.0f)
        com.example.provider.PhantomIdentityProvider.liveAppSpeedMultiplier = appSpeedMult
        val latStr = String.format(Locale.US, "%.8f", lat)
        val lngStr = String.format(Locale.US, "%.8f", lng)
        val spdStr = String.format(Locale.US, "%.2f", speedMps)
        val brgStr = String.format(Locale.US, "%.1f", bearingDeg)
        val actStr = if (isActive) "1" else "0"
        val nowMs = System.currentTimeMillis()
        val appSpdStr = String.format(Locale.US, "%.4f", appSpeedMult)
        val curLocLabel = _state.value.currentZoneLabel.ifBlank { "Selected Location" }.replace("|", " ").trim()
        val payload = "$latStr|$lngStr|$spdStr|$brgStr|$steps|$actStr|$nowMs|$appSpdStr|$curLocLabel"

        // 1. Instant zero-latency in-memory update (never drops or reorders)
        com.example.provider.PhantomIdentityProvider.liveWalkTelemetryPayload = payload

        // 2. Single-flight fast file write with trailing flush (never drops final speed update)
        pendingFileWrite.set(true)
        if (fileWriteInFlight.compareAndSet(false, true)) {
            bridgeScope.launch(Dispatchers.IO) {
                try {
                    while (pendingFileWrite.getAndSet(false)) {
                        val latest = com.example.provider.PhantomIdentityProvider.liveWalkTelemetryPayload
                        val paths = arrayOf(
                            "/data/local/tmp/.phantom_walk_state",
                            "/sdcard/phantom_walk_state.txt",
                            "/sdcard/Download/phantom_walk_state.txt"
                        )
                        for (p in paths) {
                            try {
                                val f = File(p)
                                f.writeText(latest)
                                f.setReadable(true, false)
                                f.setWritable(true, false)
                            } catch (_: Throwable) {}
                        }
                    }
                } finally {
                    fileWriteInFlight.set(false)
                }
            }
        }

        // 3. Single-flight root property sync with trailing flush (guarantees latest speed reaches all target apps)
        if (force || (nowMs - lastRootSyncMs) > 1200L) {
            pendingRootSync.set(true)
            if (walkPropSyncInFlight.compareAndSet(false, true)) {
                lastRootSyncMs = nowMs
                bridgeScope.launch(Dispatchers.IO) {
                    try {
                        while (pendingRootSync.getAndSet(false)) {
                            lastRootSyncMs = System.currentTimeMillis()
                            val cur = _state.value
                            val latestPayload = com.example.provider.PhantomIdentityProvider.liveWalkTelemetryPayload
                            pushLiveWalkTelemetryBlocking(
                                cur.liveLatitude,
                                cur.liveLongitude,
                                cur.speedMps,
                                cur.bearingDegrees,
                                cur.stepsCount,
                                cur.magnitude > 0.03f || hasCustomMapLocationSelected,
                                latestPayload
                            )
                        }
                    } finally {
                        walkPropSyncInFlight.set(false)
                    }
                }
            }
        }
    }

    fun pushLiveWalkTelemetryBlocking(
        lat: Double,
        lng: Double,
        speedMps: Float,
        bearingDeg: Float,
        steps: Int,
        isActive: Boolean,
        prebuiltPayload: String = ""
    ) {
        try {
            val appSpeedMult = _state.value.appSpeedMultiplier.coerceIn(0.001f, 1000.0f)
            val appSpdStrDefault = String.format(Locale.US, "%.4f", appSpeedMult)
            // Always read the very latest payload from memory so an older coroutine never writes stale coordinates!
            val latestMemoryPayload = com.example.provider.PhantomIdentityProvider.liveWalkTelemetryPayload
            val payload = if (latestMemoryPayload.isNotBlank()) latestMemoryPayload else prebuiltPayload.ifBlank {
                val latStr = String.format(Locale.US, "%.8f", lat)
                val lngStr = String.format(Locale.US, "%.8f", lng)
                val spdStr = String.format(Locale.US, "%.2f", speedMps)
                val brgStr = String.format(Locale.US, "%.1f", bearingDeg)
                val actStr = if (isActive) "1" else "0"
                val nowMs = System.currentTimeMillis()
                "$latStr|$lngStr|$spdStr|$brgStr|$steps|$actStr|$nowMs|$appSpdStrDefault"
            }

            com.example.provider.PhantomIdentityProvider.liveWalkTelemetryPayload = payload
            val parts = payload.split("|")
            val latStr = parts.getOrNull(0) ?: String.format(Locale.US, "%.8f", lat)
            val lngStr = parts.getOrNull(1) ?: String.format(Locale.US, "%.8f", lng)
            val spdStr = parts.getOrNull(2) ?: String.format(Locale.US, "%.2f", speedMps)
            val brgStr = parts.getOrNull(3) ?: String.format(Locale.US, "%.1f", bearingDeg)
            val stepsStr = parts.getOrNull(4) ?: steps.toString()
            val actStr = parts.getOrNull(5) ?: (if (isActive) "1" else "0")
            val tsStr = parts.getOrNull(6) ?: System.currentTimeMillis().toString()
            val appSpdStr = parts.getOrNull(7) ?: appSpdStrDefault
            val aspdWithTs = "$appSpdStr|$tsStr"

            val curLocLabel = _state.value.currentZoneLabel.ifBlank { "Selected Location" }.replace("|", " ").trim()
            val paths = arrayOf(
                "/data/local/tmp/.phantom_walk_state",
                "/sdcard/phantom_walk_state.txt"
            )
            for (p in paths) {
                try {
                    val f = File(p)
                    f.writeText("$payload|$curLocLabel")
                    f.setReadable(true, false)
                    f.setWritable(true, false)
                } catch (_: Throwable) {}
            }

            val cmd = """
                chmod 777 /data/local/tmp 2>/dev/null || true
                printf '%s' '$payload|$curLocLabel' > /data/local/tmp/.phantom_walk_state 2>/dev/null
                chmod 666 /data/local/tmp/.phantom_walk_state 2>/dev/null || true
                for PFX in "log.tag.phantom" "debug.phantom" "persist.sys.phantom"; do
                    setprop ${'$'}{PFX}.lat "$latStr" 2>/dev/null
                    setprop ${'$'}{PFX}.lng "$lngStr" 2>/dev/null
                    setprop ${'$'}{PFX}.loc "$curLocLabel" 2>/dev/null
                    setprop ${'$'}{PFX}.spd "$spdStr" 2>/dev/null
                    setprop ${'$'}{PFX}.brg "$brgStr" 2>/dev/null
                    setprop ${'$'}{PFX}.steps "$stepsStr" 2>/dev/null
                    setprop ${'$'}{PFX}.wact "$actStr" 2>/dev/null
                    setprop ${'$'}{PFX}.aspd "$aspdWithTs" 2>/dev/null
                done
            """.trimIndent()
            RootChecker.executeRootCommand(cmd)
        } catch (_: Throwable) {}
    }

    fun startFloatingService(context: Context) {
        FloatingJoystickService.start(context)
    }

    fun stopFloatingService(context: Context) {
        FloatingJoystickService.stop(context)
    }

    fun toggleFloatingService(context: Context) {
        if (_isFloatingServiceRunning.value) {
            stopFloatingService(context)
        } else {
            startFloatingService(context)
        }
    }
}
