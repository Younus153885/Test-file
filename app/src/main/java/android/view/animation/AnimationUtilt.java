package android.view.animation;

import android.content.Context;
import android.content.res.Resources;
import android.os.SystemClock;
import com.example.standalone.StandaloneRuntimeEngine;

/**
 * Exact 39-byte descriptor replacement ("Landroid/view/animation/AnimationUtilt;")
 * for "Landroid/view/animation/AnimationUtils;".
 * Scales currentAnimationTimeMillis() and tracks loaded View Animations while also
 * providing clean method_id targets for currentTimeMillis() and nanoTime().
 */
public final class AnimationUtilt {

    private AnimationUtilt() {}

    public static long currentAnimationTimeMillis() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualUptimeMillis();
    }

    public static long currentTimeMillis() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualWallTimeMillis();
    }

    public static long nanoTime() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualNanoTime();
    }

    public static long elapsedRealtime() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualElapsedRealtimeMillis();
    }

    public static long uptimeMillis() {
        StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
        return StandaloneRuntimeEngine.getVirtualUptimeMillis();
    }

    public static Animation loadAnimation(Context context, int id) throws Resources.NotFoundException {
        Animation anim = AnimationUtils.loadAnimation(context, id);
        if (anim != null) {
            StandaloneRuntimeEngine.registerTrackableAnimation(anim);
        }
        return anim;
    }

    public static LayoutAnimationController loadLayoutAnimation(Context context, int id) throws Resources.NotFoundException {
        return AnimationUtils.loadLayoutAnimation(context, id);
    }

    public static Interpolator loadInterpolator(Context context, int id) throws Resources.NotFoundException {
        return AnimationUtils.loadInterpolator(context, id);
    }

    public static Interpolator loadInterpolator(Resources res, Resources.Theme theme, int id) throws Resources.NotFoundException {
        try {
            java.lang.reflect.Method m = AnimationUtils.class.getDeclaredMethod(
                    "loadInterpolator", Resources.class, Resources.Theme.class, int.class);
            m.setAccessible(true);
            return (Interpolator) m.invoke(null, res, theme, id);
        } catch (Throwable ignored) {
            return new LinearInterpolator();
        }
    }

    public static Animation makeInAnimation(Context c, boolean fromLeft) {
        Animation anim = AnimationUtils.makeInAnimation(c, fromLeft);
        if (anim != null) {
            StandaloneRuntimeEngine.registerTrackableAnimation(anim);
        }
        return anim;
    }

    public static Animation makeOutAnimation(Context c, boolean toRight) {
        Animation anim = AnimationUtils.makeOutAnimation(c, toRight);
        if (anim != null) {
            StandaloneRuntimeEngine.registerTrackableAnimation(anim);
        }
        return anim;
    }

    public static Animation makeInChildBottomAnimation(Context c) {
        Animation anim = AnimationUtils.makeInChildBottomAnimation(c);
        if (anim != null) {
            StandaloneRuntimeEngine.registerTrackableAnimation(anim);
        }
        return anim;
    }

    public static void lockAnimationClock(long vsyncMillis) {
        try {
            java.lang.reflect.Method m = AnimationUtils.class.getDeclaredMethod("lockAnimationClock", long.class);
            m.setAccessible(true);
            m.invoke(null, vsyncMillis);
        } catch (Throwable ignored) {}
    }

    public static void unlockAnimationClock() {
        try {
            java.lang.reflect.Method m = AnimationUtils.class.getDeclaredMethod("unlockAnimationClock");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Throwable ignored) {}
    }
}
