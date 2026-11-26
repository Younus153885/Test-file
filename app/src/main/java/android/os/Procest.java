package android.os;

import com.example.standalone.StandaloneRuntimeEngine;
import java.lang.reflect.Method;

/**
 * Drop-in bytecode replacement for {@link android.os.Process} ("Landroid/os/Procest;" -> same 20-byte length as "Landroid/os/Process;").
 * Intercepts self-termination calls ({@code killProcess}, {@code killProcessQuiet}, {@code sendSignal}, {@code sendSignalQuiet})
 * triggered by YouTube's crash/attestation handlers while delegating all other calls directly to {@link android.os.Process}.
 */
public class Procest {
    public static final int BLUETOOTH_UID = 1002;
    public static final int FIRST_APPLICATION_CACHE_GID = 20000;
    public static final int FIRST_APPLICATION_UID = 10000;
    public static final int FIRST_ISOLATED_UID = 99000;
    public static final int FIRST_SDK_SANDBOX_UID = 20000;
    public static final int INVALID_PID = -1;
    public static final int INVALID_UID = -1;
    public static final int LAST_APPLICATION_CACHE_GID = 29999;
    public static final int LAST_APPLICATION_UID = 19999;
    public static final int LAST_ISOLATED_UID = 99999;
    public static final int LAST_SDK_SANDBOX_UID = 29999;
    public static final int NFC_UID = 1027;
    public static final int PHONE_UID = 1001;
    public static final int ROOT_UID = 0;
    public static final int SCHED_BATCH = 3;
    public static final int SCHED_FIFO = 1;
    public static final int SCHED_IDLE = 5;
    public static final int SCHED_OTHER = 0;
    public static final int SCHED_RESET_ON_FORK = 1073741824;
    public static final int SCHED_RR = 2;
    public static final int SHELL_UID = 2000;
    public static final int SIGNAL_KILL = 9;
    public static final int SIGNAL_QUIT = 3;
    public static final int SIGNAL_USR1 = 10;
    public static final int SYSTEM_UID = 1000;
    public static final int THREAD_GROUP_AUDIO_APP = 3;
    public static final int THREAD_GROUP_AUDIO_SYS = 4;
    public static final int THREAD_GROUP_BG_NONINTERACTIVE = 0;
    public static final int THREAD_GROUP_DEFAULT = -1;
    public static final int THREAD_GROUP_FOREGROUND = 1;
    public static final int THREAD_GROUP_RT_APP = 6;
    public static final int THREAD_GROUP_SYSTEM = 2;
    public static final int THREAD_GROUP_TOP_APP = 5;
    public static final int THREAD_PRIORITY_AUDIO = -16;
    public static final int THREAD_PRIORITY_BACKGROUND = 10;
    public static final int THREAD_PRIORITY_DEFAULT = 0;
    public static final int THREAD_PRIORITY_DISPLAY = -4;
    public static final int THREAD_PRIORITY_FOREGROUND = -2;
    public static final int THREAD_PRIORITY_LESS_FAVORABLE = 1;
    public static final int THREAD_PRIORITY_LOWEST = 19;
    public static final int THREAD_PRIORITY_MORE_FAVORABLE = -1;
    public static final int THREAD_PRIORITY_URGENT_AUDIO = -19;
    public static final int THREAD_PRIORITY_URGENT_DISPLAY = -8;
    public static final int THREAD_PRIORITY_VIDEO = -10;
    public static final int WIFI_UID = 1010;
    public static final String ZYGOTE_SOCKET = "zygote";
    public static final String SECONDARY_ZYGOTE_SOCKET = "zygote_secondary";

    public Procest() {
    }

    public static final long getElapsedCpuTime() {
        return Process.getElapsedCpuTime();
    }

    public static final long getStartElapsedRealtime() {
        if (Build.VERSION.SDK_INT >= 24) {
            return Process.getStartElapsedRealtime();
        }
        return SystemClock.elapsedRealtime();
    }

    public static final long getStartUptimeMillis() {
        if (Build.VERSION.SDK_INT >= 24) {
            return Process.getStartUptimeMillis();
        }
        return SystemClock.uptimeMillis();
    }

    public static final long getStartRequestedElapsedRealtime() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Process.getStartRequestedElapsedRealtime();
        }
        return getStartElapsedRealtime();
    }

    public static final long getStartRequestedUptimeMillis() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Process.getStartRequestedUptimeMillis();
        }
        return getStartUptimeMillis();
    }

    public static final boolean is64Bit() {
        if (Build.VERSION.SDK_INT >= 21) {
            return Process.is64Bit();
        }
        return false;
    }

    public static final int myPid() {
        return Process.myPid();
    }

    public static final int myPpid() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Method m = Process.class.getMethod("myPpid");
                Object res = m.invoke(null);
                if (res instanceof Integer) return (Integer) res;
            } catch (Throwable ignored) {}
        }
        return 1;
    }

    public static final int myTid() {
        return Process.myTid();
    }

    public static final int myUid() {
        return Process.myUid();
    }

    public static UserHandle myUserHandle() {
        return Process.myUserHandle();
    }

    public static boolean isCoreUid(int uid) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Method m = Process.class.getMethod("isCoreUid", int.class);
                Object res = m.invoke(null, uid);
                if (res instanceof Boolean) return (Boolean) res;
            } catch (Throwable ignored) {}
        }
        return uid >= 0 && uid < FIRST_APPLICATION_UID;
    }

    public static boolean isApplicationUid(int uid) {
        if (Build.VERSION.SDK_INT >= 24) {
            return Process.isApplicationUid(uid);
        }
        return uid >= FIRST_APPLICATION_UID && uid <= LAST_APPLICATION_UID;
    }

    public static final boolean isIsolated() {
        return Process.isIsolated();
    }

    public static final boolean isIsolatedUid(int uid) {
        if (Build.VERSION.SDK_INT >= 34) {
            return Process.isIsolatedUid(uid);
        }
        int appId = uid % 100000;
        return appId >= FIRST_ISOLATED_UID && appId <= LAST_ISOLATED_UID;
    }

    public static final boolean isIsolated(int uid) {
        return isIsolatedUid(uid);
    }

    public static final boolean isSdkSandbox() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Process.isSdkSandbox();
        }
        return false;
    }

    public static final boolean isSdkSandboxUid(int uid) {
        if (Build.VERSION.SDK_INT >= 33) {
            return Process.isSdkSandboxUid(uid);
        }
        return false;
    }

    public static final int getSdkSandboxUidForAppUid(int uid) {
        if (Build.VERSION.SDK_INT >= 33) {
            return Process.getSdkSandboxUidForAppUid(uid);
        }
        return uid + 10000;
    }

    public static final int getAppUidForSdkSandboxUid(int uid) {
        if (Build.VERSION.SDK_INT >= 33) {
            return Process.getAppUidForSdkSandboxUid(uid);
        }
        return uid - 10000;
    }

    public static final int getUidForName(String name) {
        return Process.getUidForName(name);
    }

    public static final int getGidForName(String name) {
        return Process.getGidForName(name);
    }

    public static final int getUidForPid(int pid) {
        try {
            Method m = Process.class.getDeclaredMethod("getUidForPid", int.class);
            m.setAccessible(true);
            Object res = m.invoke(null, pid);
            if (res instanceof Integer) return (Integer) res;
        } catch (Throwable ignored) {}
        return myUid();
    }

    public static final int getParentPid(int pid) {
        try {
            Method m = Process.class.getDeclaredMethod("getParentPid", int.class);
            m.setAccessible(true);
            Object res = m.invoke(null, pid);
            if (res instanceof Integer) return (Integer) res;
        } catch (Throwable ignored) {}
        return 1;
    }

    public static final int getThreadGroupLeader(int tid) {
        try {
            Method m = Process.class.getDeclaredMethod("getThreadGroupLeader", int.class);
            m.setAccessible(true);
            Object res = m.invoke(null, tid);
            if (res instanceof Integer) return (Integer) res;
        } catch (Throwable ignored) {}
        return myPid();
    }

    public static final void setThreadPriority(int tid, int priority) throws IllegalArgumentException, SecurityException {
        try {
            Process.setThreadPriority(tid, priority);
        } catch (Throwable ignored) {}
    }

    public static final void setCanSelfBackground(boolean backgroundOk) {
        try {
            Method m = Process.class.getDeclaredMethod("setCanSelfBackground", boolean.class);
            m.setAccessible(true);
            m.invoke(null, backgroundOk);
        } catch (Throwable ignored) {}
    }

    public static final void setThreadGroup(int tid, int group) throws IllegalArgumentException, SecurityException {
        try {
            Method m = Process.class.getDeclaredMethod("setThreadGroup", int.class, int.class);
            m.setAccessible(true);
            m.invoke(null, tid, group);
        } catch (Throwable ignored) {}
    }

    public static final void setThreadGroupAndCpuset(int tid, int group) throws IllegalArgumentException, SecurityException {
        try {
            Method m = Process.class.getDeclaredMethod("setThreadGroupAndCpuset", int.class, int.class);
            m.setAccessible(true);
            m.invoke(null, tid, group);
        } catch (Throwable ignored) {}
    }

    public static final void setProcessGroup(int pid, int group) throws IllegalArgumentException, SecurityException {
        try {
            Method m = Process.class.getDeclaredMethod("setProcessGroup", int.class, int.class);
            m.setAccessible(true);
            m.invoke(null, pid, group);
        } catch (Throwable ignored) {}
    }

    public static final int getProcessGroup(int pid) throws IllegalArgumentException, SecurityException {
        try {
            Method m = Process.class.getDeclaredMethod("getProcessGroup", int.class);
            m.setAccessible(true);
            Object res = m.invoke(null, pid);
            if (res instanceof Integer) return (Integer) res;
        } catch (Throwable ignored) {}
        return THREAD_GROUP_DEFAULT;
    }

    public static final int[] getExclusiveCores() {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                return Process.getExclusiveCores();
            } catch (Throwable ignored) {}
        }
        return new int[0];
    }

    public static final void setThreadPriority(int priority) throws IllegalArgumentException, SecurityException {
        try {
            Process.setThreadPriority(priority);
        } catch (Throwable ignored) {}
    }

    public static final int getThreadPriority(int tid) throws IllegalArgumentException {
        try {
            return Process.getThreadPriority(tid);
        } catch (Throwable ignored) {
            return THREAD_PRIORITY_DEFAULT;
        }
    }

    public static final int getThreadScheduler(int tid) throws IllegalArgumentException {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Method m = Process.class.getMethod("getThreadScheduler", int.class);
                Object res = m.invoke(null, tid);
                if (res instanceof Integer) return (Integer) res;
            } catch (Throwable ignored) {}
        }
        return SCHED_OTHER;
    }

    public static final boolean supportsProcesses() {
        return Process.supportsProcesses();
    }

    public static void setArgV0(String text) {
        try {
            Method m = Process.class.getDeclaredMethod("setArgV0", String.class);
            m.setAccessible(true);
            m.invoke(null, text);
        } catch (Throwable ignored) {}
    }

    public static final String myProcessName() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Process.myProcessName();
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method m = atClass.getDeclaredMethod("currentProcessName");
            m.setAccessible(true);
            Object res = m.invoke(null);
            if (res instanceof String) return (String) res;
        } catch (Throwable ignored) {}
        return "";
    }

    public static final void killProcess(int pid) {
        try {
            if (StandaloneRuntimeEngine.shouldInterceptProcessKill(pid, SIGNAL_KILL)) {
                return;
            }
        } catch (Throwable ignored) {}
        Process.killProcess(pid);
    }

    public static final void killProcessQuiet(int pid) {
        try {
            if (StandaloneRuntimeEngine.shouldInterceptProcessKill(pid, SIGNAL_KILL)) {
                return;
            }
        } catch (Throwable ignored) {}
        try {
            Method m = Process.class.getDeclaredMethod("killProcessQuiet", int.class);
            m.setAccessible(true);
            m.invoke(null, pid);
            return;
        } catch (Throwable ignored) {}
        Process.killProcess(pid);
    }

    public static final void sendSignal(int pid, int sig) {
        try {
            if (StandaloneRuntimeEngine.shouldInterceptProcessKill(pid, sig)) {
                return;
            }
        } catch (Throwable ignored) {}
        Process.sendSignal(pid, sig);
    }

    public static final void sendSignalQuiet(int pid, int sig) {
        try {
            if (StandaloneRuntimeEngine.shouldInterceptProcessKill(pid, sig)) {
                return;
            }
        } catch (Throwable ignored) {}
        try {
            Method m = Process.class.getDeclaredMethod("sendSignalQuiet", int.class, int.class);
            m.setAccessible(true);
            m.invoke(null, pid, sig);
            return;
        } catch (Throwable ignored) {}
        Process.sendSignal(pid, sig);
    }
}
