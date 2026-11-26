package com.example.standalone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

/**
 * Self-contained startup ContentProvider injected into Standalone Patched APKs.
 * Android's ActivityThread instantiates and runs this provider automatically
 * at process startup before Application.onCreate() or any Activity is created.
 */
public final class StandaloneBootstrapProvider extends ContentProvider {

    static {
        try {
            StandaloneRuntimeEngine.ensureEarlyBootstrapped();
        } catch (Throwable ignored) {}
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        if (context != null) {
            try {
                StandaloneRuntimeEngine.install(context);
            } catch (Throwable ignored) {}
        }
        try {
            super.attachInfo(context, info);
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null) {
            try {
                StandaloneRuntimeEngine.install(context);
            } catch (Throwable ignored) {}
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
