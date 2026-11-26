package android.media;

import android.os.Build;
import android.os.Handler;
import android.os.PersistableBundle;
import com.example.standalone.StandaloneRuntimeEngine;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Bytecode-level replacement for android.media.MediaDrm (exact 24-byte descriptor
 * Landroid/media/MediaDrn;).
 * Intercepts MediaDrm.getPropertyByteArray("deviceUniqueId") so Instagram, TikTok, Facebook,
 * Google, and games NEVER read the host phone's hardware Widevine L1/L3 DRM device ID.
 * Instead, returns the built APK's isolated Widevine DRM ID (which rotates on every "🔄 ক্লিয়ার").
 */
public final class MediaDrn implements AutoCloseable {

    public static final String PROPERTY_VENDOR = "vendor";
    public static final String PROPERTY_VERSION = "version";
    public static final String PROPERTY_DESCRIPTION = "description";
    public static final String PROPERTY_ALGORITHMS = "algorithms";
    public static final String PROPERTY_DEVICE_UNIQUE_ID = "deviceUniqueId";

    private final MediaDrm mRealDrm;

    public MediaDrn(UUID uuid) throws UnsupportedSchemeException {
        StandaloneRuntimeEngine.ensureEarlyBootstrapped();
        MediaDrm real = null;
        try {
            real = new MediaDrm(uuid);
        } catch (UnsupportedSchemeException e) {
            throw e;
        } catch (Throwable ignored) {}
        mRealDrm = real;
    }

    public static boolean isCryptoSchemeSupported(UUID uuid) {
        try {
            return MediaDrm.isCryptoSchemeSupported(uuid);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean isCryptoSchemeSupported(UUID uuid, String mimeType) {
        try {
            return MediaDrm.isCryptoSchemeSupported(uuid, mimeType);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean isCryptoSchemeSupported(UUID uuid, String mimeType, int securityLevel) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return MediaDrm.isCryptoSchemeSupported(uuid, mimeType, securityLevel);
            }
            return MediaDrm.isCryptoSchemeSupported(uuid, mimeType);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static int getMaxSecurityLevel() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return MediaDrm.getMaxSecurityLevel();
            }
        } catch (Throwable ignored) {}
        return 1;
    }

    public byte[] getPropertyByteArray(String propertyName) {
        try {
            if (StandaloneRuntimeEngine.isIdentitySpoofEnabled() && propertyName != null) {
                String lower = propertyName.toLowerCase();
                if ("deviceuniqueid".equals(lower) || "provisioninguniqueid".equals(lower) || lower.contains("uniqueid")) {
                    byte[] virtualId = StandaloneRuntimeEngine.getVirtualWidevineIdBytes();
                    if (virtualId != null && virtualId.length > 0) {
                        return virtualId;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (mRealDrm != null) {
            try {
                byte[] realBytes = mRealDrm.getPropertyByteArray(propertyName);
                if (realBytes != null) return realBytes;
            } catch (Throwable ignored) {}
        }
        if (!StandaloneRuntimeEngine.isIdentitySpoofEnabled()) {
            return new byte[0];
        }
        return StandaloneRuntimeEngine.getVirtualWidevineIdBytes();
    }

    public String getPropertyString(String propertyName) {
        try {
            String spoofed = StandaloneRuntimeEngine.getSpoofedMediaDrmPropertyString(propertyName);
            if (spoofed != null) {
                return spoofed;
            }
        } catch (Throwable ignored) {}
        if (mRealDrm != null) {
            try {
                return mRealDrm.getPropertyString(propertyName);
            } catch (Throwable ignored) {}
        }
        return "";
    }

    public void setPropertyByteArray(String propertyName, byte[] value) {
        if (mRealDrm != null) {
            try {
                mRealDrm.setPropertyByteArray(propertyName, value);
            } catch (Throwable ignored) {}
        }
    }

    public void setPropertyString(String propertyName, String value) {
        if (mRealDrm != null) {
            try {
                mRealDrm.setPropertyString(propertyName, value);
            } catch (Throwable ignored) {}
        }
    }

    public byte[] openSession() throws NotProvisionedException, ResourceBusyException {
        if (mRealDrm != null) {
            return mRealDrm.openSession();
        }
        return new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    }

    public byte[] openSession(int level) throws NotProvisionedException, ResourceBusyException {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 28) {
            return mRealDrm.openSession(level);
        }
        return openSession();
    }

    public void closeSession(byte[] sessionId) {
        if (mRealDrm != null && sessionId != null) {
            try {
                mRealDrm.closeSession(sessionId);
            } catch (Throwable ignored) {}
        }
    }

    public MediaDrm.KeyRequest getKeyRequest(
            byte[] scope,
            byte[] init,
            String mimeType,
            int keyType,
            HashMap<String, String> optionalParameters
    ) throws NotProvisionedException {
        if (mRealDrm != null) {
            return mRealDrm.getKeyRequest(scope, init, mimeType, keyType, optionalParameters);
        }
        throw new NotProvisionedException("MediaDrm not provisioned");
    }

    public byte[] provideKeyResponse(byte[] scope, byte[] response)
            throws NotProvisionedException, DeniedByServerException {
        if (mRealDrm != null) {
            return mRealDrm.provideKeyResponse(scope, response);
        }
        return new byte[0];
    }

    public void restoreKeys(byte[] sessionId, byte[] keySetId) {
        if (mRealDrm != null) {
            mRealDrm.restoreKeys(sessionId, keySetId);
        }
    }

    public void removeKeys(byte[] sessionId) {
        if (mRealDrm != null) {
            mRealDrm.removeKeys(sessionId);
        }
    }

    public HashMap<String, String> queryKeyStatus(byte[] sessionId) {
        if (mRealDrm != null) {
            return mRealDrm.queryKeyStatus(sessionId);
        }
        return new HashMap<String, String>();
    }

    public MediaDrm.ProvisionRequest getProvisionRequest() {
        if (mRealDrm != null) {
            return mRealDrm.getProvisionRequest();
        }
        return null;
    }

    public void provideProvisionResponse(byte[] response) throws DeniedByServerException {
        if (mRealDrm != null) {
            mRealDrm.provideProvisionResponse(response);
        }
    }

    public List<byte[]> getSecureStops() {
        if (mRealDrm != null) {
            return mRealDrm.getSecureStops();
        }
        return java.util.Collections.emptyList();
    }

    public List<byte[]> getSecureStopIds() {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 28) {
            return mRealDrm.getSecureStopIds();
        }
        return java.util.Collections.emptyList();
    }

    public byte[] getSecureStop(byte[] ssid) {
        if (mRealDrm != null) {
            return mRealDrm.getSecureStop(ssid);
        }
        return new byte[0];
    }

    public void releaseSecureStops(byte[] ssRelease) {
        if (mRealDrm != null) {
            mRealDrm.releaseSecureStops(ssRelease);
        }
    }

    public void removeSecureStop(byte[] ssid) {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 28) {
            mRealDrm.removeSecureStop(ssid);
        }
    }

    public void removeAllSecureStops() {
        if (mRealDrm != null) {
            mRealDrm.removeAllSecureStops();
        }
    }

    public int getConnectedHdcpLevel() {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 28) {
            return mRealDrm.getConnectedHdcpLevel();
        }
        return 0;
    }

    public int getMaxHdcpLevel() {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 28) {
            return mRealDrm.getMaxHdcpLevel();
        }
        return 0;
    }

    public int getOpenSessionCount() {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 28) {
            return mRealDrm.getOpenSessionCount();
        }
        return 0;
    }

    public int getMaxSessionCount() {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 28) {
            return mRealDrm.getMaxSessionCount();
        }
        return 16;
    }

    public int getSecurityLevel(byte[] sessionId) {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 28) {
            return mRealDrm.getSecurityLevel(sessionId);
        }
        return 1;
    }

    public PersistableBundle getMetrics() {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 28) {
            return mRealDrm.getMetrics();
        }
        return null;
    }

    public MediaDrm.CryptoSession getCryptoSession(byte[] sessionId, String cipherAlgorithm, String macAlgorithm) {
        if (mRealDrm != null) {
            return mRealDrm.getCryptoSession(sessionId, cipherAlgorithm, macAlgorithm);
        }
        return null;
    }

    public void setOnEventListener(MediaDrm.OnEventListener listener) {
        if (mRealDrm != null) {
            mRealDrm.setOnEventListener(listener);
        }
    }

    public void setOnEventListener(MediaDrm.OnEventListener listener, Handler handler) {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 28) {
            mRealDrm.setOnEventListener(listener, handler);
        }
    }

    public void setOnKeyStatusChangeListener(MediaDrm.OnKeyStatusChangeListener listener, Handler handler) {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 23) {
            mRealDrm.setOnKeyStatusChangeListener(listener, handler);
        }
    }

    public void setOnExpirationUpdateListener(MediaDrm.OnExpirationUpdateListener listener, Handler handler) {
        if (mRealDrm != null && Build.VERSION.SDK_INT >= 23) {
            mRealDrm.setOnExpirationUpdateListener(listener, handler);
        }
    }

    public void release() {
        if (mRealDrm != null) {
            try {
                mRealDrm.release();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void close() {
        if (mRealDrm != null) {
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    mRealDrm.close();
                } else {
                    mRealDrm.release();
                }
            } catch (Throwable ignored) {}
        }
    }
}
