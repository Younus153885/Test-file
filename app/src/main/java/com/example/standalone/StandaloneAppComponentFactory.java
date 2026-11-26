package com.example.standalone;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;

/**
 * Step-0 AppComponentFactory injected into Google first-party standalone APKs (API 28+).
 * Runs BEFORE Application.<init>() and Application.attachBaseContext() so
 * StandaloneRuntimeEngine hooks, CrashShield, split resources, and Instrumentation
 * are installed before YouTube / Play Store initializes SplitCompat, Cronet, or Hilt/Dagger.
 * Transparently delegates all 6 component factory methods to the app's original AppComponentFactory.
 */
public final class StandaloneAppComponentFactory extends AppComponentFactory {

    private volatile AppComponentFactory mDelegate;
    private volatile boolean mDelegateResolved;

    public StandaloneAppComponentFactory() {
        super();
        try {
            StandaloneRuntimeEngine.ensureEarlyBootstrapped();
        } catch (Throwable ignored) {}
    }

    private AppComponentFactory resolveDelegate(ClassLoader cl) {
        if (mDelegateResolved) {
            return mDelegate;
        }
        synchronized (this) {
            if (mDelegateResolved) {
                return mDelegate;
            }
            try {
                StandaloneRuntimeEngine.ensureEarlyBootstrapped();
                String origName = StandaloneRuntimeEngine.getOriginalAppComponentFactoryName();
                if (origName != null && !origName.isEmpty()
                        && !origName.equals(StandaloneAppComponentFactory.class.getName())) {
                    ClassLoader targetCl = (cl != null) ? cl : StandaloneAppComponentFactory.class.getClassLoader();
                    if (targetCl != null) {
                        Class<?> cls = Class.forName(origName, false, targetCl);
                        if (AppComponentFactory.class.isAssignableFrom(cls)) {
                            Object inst = cls.getDeclaredConstructor().newInstance();
                            if (inst instanceof AppComponentFactory) {
                                mDelegate = (AppComponentFactory) inst;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                mDelegate = null;
            } finally {
                mDelegateResolved = true;
            }
            return mDelegate;
        }
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        try {
            StandaloneRuntimeEngine.ensureEarlyBootstrapped();
        } catch (Throwable ignored) {}
        AppComponentFactory delegate = resolveDelegate(cl);
        if (Build.VERSION.SDK_INT >= 29) {
            if (delegate != null) {
                try {
                    return delegate.instantiateClassLoader(cl, aInfo);
                } catch (Throwable ignored) {}
            }
            return super.instantiateClassLoader(cl, aInfo);
        }
        return cl;
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            StandaloneRuntimeEngine.ensureEarlyBootstrapped();
        } catch (Throwable ignored) {}
        AppComponentFactory delegate = resolveDelegate(cl);
        if (delegate != null) {
            return delegate.instantiateApplication(cl, className);
        }
        return super.instantiateApplication(cl, className);
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory delegate = resolveDelegate(cl);
        if (delegate != null) {
            return delegate.instantiateActivity(cl, className, intent);
        }
        return super.instantiateActivity(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory delegate = resolveDelegate(cl);
        if (delegate != null) {
            return delegate.instantiateReceiver(cl, className, intent);
        }
        return super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory delegate = resolveDelegate(cl);
        if (delegate != null) {
            return delegate.instantiateService(cl, className, intent);
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory delegate = resolveDelegate(cl);
        if (delegate != null) {
            return delegate.instantiateProvider(cl, className);
        }
        return super.instantiateProvider(cl, className);
    }
}
