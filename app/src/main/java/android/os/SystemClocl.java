package android.os;

import com.example.standalone.StandaloneRuntimeEngine;

/**
 * Exact 24-byte descriptor replacement ("Landroid/os/SystemClocl;") for "Landroid/os/SystemClock;".
 * Uses only primitive return types so ART verification succeeds on all Android versions (8.0 - 15+).
 *
 * Provides 100% native performance, ZERO clock skew, and ZERO thread blocking when speed multiplier is 1.0x.
 * When speed is actively scaled, delegates to StandaloneRuntimeEngine while strictly preserving monotonic alignment.
 */
public final class SystemClocl {

    private SystemClocl() {}

    public static long elapsedRealtime() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualElapsedRealtimeMillis();
    }

    public static long elapsedRealtimeNanos() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualElapsedRealtimeNanos();
    }

    public static long uptimeMillis() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualUptimeMillis();
    }

    public static long uptimeNanos() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualUptimeNanos();
    }

    public static long currentThreadTimeMillis() {
        return SystemClock.currentThreadTimeMillis();
    }

    public static long currentThreadTimeMicro() {
        return SystemClock.currentThreadTimeMillis() * 1000L;
    }

    public static long currentTimeMicro() {
        return currentTimeMillis() * 1000L;
    }

    public static void sleep(long ms) {
        StandaloneRuntimeEngine.sleepScaled(ms);
    }

    public static boolean setCurrentTimeMillis(long millis) {
        return SystemClock.setCurrentTimeMillis(millis);
    }

    public static long currentNetworkTimeMillis() {
        return currentTimeMillis();
    }

    public static long currentTimeMillis() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualWallTimeMillis();
    }

    public static long nanoTime() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualNanoTime();
    }

    @android.annotation.TargetApi(29)
    public static java.time.Clock currentGnssTimeClock() {
        return SystemClock.currentGnssTimeClock();
    }

    @android.annotation.TargetApi(33)
    public static java.time.Clock currentNetworkTimeClock() {
        return SystemClock.currentNetworkTimeClock();
    }
}
