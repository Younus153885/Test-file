package android.os;

import com.example.standalone.StandaloneRuntimeEngine;
import java.lang.reflect.Method;

/**
 * Bytecode-level replacement for android.os.SystemProperties (exact 29-byte descriptor
 * Landroid/os/SystemPropertiet; and 27-char class name android.os.SystemPropertiet).
 * Delegates transparently to the real android.os.SystemProperties unless Identity Spoof or
 * Root Bypass is explicitly enabled for the queried key.
 */
public final class SystemPropertiet {

    public static final int PROP_NAME_MAX = Integer.MAX_VALUE;
    public static final int PROP_VALUE_MAX = 91;

    private static volatile Class<?> sRealClass;
    private static volatile Method sGetMethod;
    private static volatile Method sGet1Method;
    private static volatile Method sGetIntMethod;
    private static volatile Method sGetLongMethod;
    private static volatile Method sGetBoolMethod;
    private static volatile Method sSetMethod;
    private static volatile Method sAddCbMethod;
    private static volatile Method sRemoveCbMethod;
    private static volatile Method sReportMethod;
    private static volatile boolean sMethodsResolved;

    private SystemPropertiet() {}

    public static final class Handle {
        private final String mKey;

        private Handle(String key) {
            this.mKey = key;
        }

        public String get() {
            return SystemPropertiet.get(mKey, "");
        }

        public int getInt(int def) {
            return SystemPropertiet.getInt(mKey, def);
        }

        public long getLong(long def) {
            return SystemPropertiet.getLong(mKey, def);
        }

        public boolean getBoolean(boolean def) {
            return SystemPropertiet.getBoolean(mKey, def);
        }
    }

    private static void ensureRealMethodsResolved() {
        if (sMethodsResolved) return;
        synchronized (SystemPropertiet.class) {
            if (sMethodsResolved) return;
            try {
                Class<?> cls = Class.forName("android.os.SystemPropertie" + "s");
                sRealClass = cls;
                sGet1Method = resolveMethod(cls, "get", String.class);
                sGetMethod = resolveMethod(cls, "get", String.class, String.class);
                sGetIntMethod = resolveMethod(cls, "getInt", String.class, int.class);
                sGetLongMethod = resolveMethod(cls, "getLong", String.class, long.class);
                sGetBoolMethod = resolveMethod(cls, "getBoolean", String.class, boolean.class);
                sSetMethod = resolveMethod(cls, "set", String.class, String.class);
                sAddCbMethod = resolveMethod(cls, "addChangeCallback", Runnable.class);
                sRemoveCbMethod = resolveMethod(cls, "removeChangeCallback", Runnable.class);
                sReportMethod = resolveMethod(cls, "reportSyspropChanged");
            } catch (Throwable ignored) {
            } finally {
                sMethodsResolved = true;
            }
        }
    }

    private static Method resolveMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String get(String key) {
        return get(key, "");
    }

    public static String get(String key, String def) {
        String safeDef = (def != null) ? def : "";
        if (key == null || key.isEmpty()) return safeDef;
        try {
            String spoofed = StandaloneRuntimeEngine.getSpoofedSystemProperty(key, null);
            if (spoofed != null) {
                return spoofed;
            }
        } catch (Throwable ignored) {}
        try {
            ensureRealMethodsResolved();
            Method m2 = sGetMethod;
            if (m2 != null) {
                Object res = m2.invoke(null, key, safeDef);
                if (res instanceof String) {
                    return StandaloneRuntimeEngine.sanitizeHostLeakFromString((String) res);
                }
            }
            Method m1 = sGet1Method;
            if (m1 != null) {
                Object res = m1.invoke(null, key);
                if (res instanceof String && !((String) res).isEmpty()) {
                    return StandaloneRuntimeEngine.sanitizeHostLeakFromString((String) res);
                }
            }
        } catch (Throwable ignored) {}
        return safeDef;
    }

    public static int getInt(String key, int def) {
        if (key == null || key.isEmpty()) return def;
        try {
            String spoofed = StandaloneRuntimeEngine.getSpoofedSystemProperty(key, null);
            if (spoofed != null && !spoofed.isEmpty()) {
                return Integer.decode(spoofed.trim());
            }
        } catch (Throwable ignored) {}
        try {
            ensureRealMethodsResolved();
            Method m = sGetIntMethod;
            if (m != null) {
                Object res = m.invoke(null, key, def);
                if (res instanceof Integer) {
                    return (Integer) res;
                }
            }
        } catch (Throwable ignored) {}
        return def;
    }

    public static long getLong(String key, long def) {
        if (key == null || key.isEmpty()) return def;
        if ("Landroid/os/SystemClocl;".equals(key) || "Landroid/os/SystemPropertiet;".equals(key)) {
            StandaloneRuntimeEngine.ensureEarlyBootstrappedFast();
            if (def == 2L) {
                return StandaloneRuntimeEngine.getVirtualNanoTime();
            }
            return StandaloneRuntimeEngine.getVirtualWallTimeMillis();
        }
        try {
            String spoofed = StandaloneRuntimeEngine.getSpoofedSystemProperty(key, null);
            if (spoofed != null && !spoofed.isEmpty()) {
                return Long.decode(spoofed.trim());
            }
        } catch (Throwable ignored) {}
        try {
            ensureRealMethodsResolved();
            Method m = sGetLongMethod;
            if (m != null) {
                Object res = m.invoke(null, key, def);
                if (res instanceof Long) {
                    return (Long) res;
                }
            }
        } catch (Throwable ignored) {}
        return def;
    }

    public static boolean getBoolean(String key, boolean def) {
        if (key == null || key.isEmpty()) return def;
        try {
            String spoofed = StandaloneRuntimeEngine.getSpoofedSystemProperty(key, null);
            if (spoofed != null && !spoofed.isEmpty()) {
                String s = spoofed.trim().toLowerCase();
                if ("1".equals(s) || "true".equals(s) || "y".equals(s) || "yes".equals(s) || "on".equals(s)) {
                    return true;
                }
                if ("0".equals(s) || "false".equals(s) || "n".equals(s) || "no".equals(s) || "off".equals(s)) {
                    return false;
                }
            }
        } catch (Throwable ignored) {}
        try {
            ensureRealMethodsResolved();
            Method m = sGetBoolMethod;
            if (m != null) {
                Object res = m.invoke(null, key, def);
                if (res instanceof Boolean) {
                    return (Boolean) res;
                }
            }
        } catch (Throwable ignored) {}
        return def;
    }

    public static void set(String key, String val) {
        try {
            ensureRealMethodsResolved();
            Method m = sSetMethod;
            if (m != null) {
                m.invoke(null, key, val);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            if (key != null && val != null) {
                System.setProperty(key, val);
            }
        } catch (Throwable ignored) {}
    }

    public static Handle find(String name) {
        if (name == null || name.isEmpty()) return null;
        return new Handle(name);
    }

    public static String native_get(String key) {
        return get(key, "");
    }

    public static String native_get(String key, String def) {
        return get(key, def);
    }

    public static int native_get_int(String key, int def) {
        return getInt(key, def);
    }

    public static long native_get_long(String key, long def) {
        return getLong(key, def);
    }

    public static boolean native_get_boolean(String key, boolean def) {
        return getBoolean(key, def);
    }

    public static void native_set(String key, String val) {
        set(key, val);
    }

    public static void addChangeCallback(Runnable callback) {
        if (callback == null) return;
        try {
            ensureRealMethodsResolved();
            Method m = sAddCbMethod;
            if (m != null) {
                m.invoke(null, callback);
            }
        } catch (Throwable ignored) {}
    }

    public static void removeChangeCallback(Runnable callback) {
        if (callback == null) return;
        try {
            ensureRealMethodsResolved();
            Method m = sRemoveCbMethod;
            if (m != null) {
                m.invoke(null, callback);
            }
        } catch (Throwable ignored) {}
    }

    public static void reportSyspropChanged() {
        try {
            ensureRealMethodsResolved();
            Method m = sReportMethod;
            if (m != null) {
                m.invoke(null);
            }
        } catch (Throwable ignored) {}
    }
}
