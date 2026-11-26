package com.example.standalone;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * Self-contained Virtual Google Play Services (Embedded GmsCore) engine bundled inside
 * phantom_runtime.dex.
 *
 * Responsibilities:
 * 1. For Google first-party apps (YouTube, Play Store, Gmail, Maps, Photos, Drive, etc.):
 *    intercepts host GMS/GSF restricted broker bindings and delivers local Binders so re-signed
 *    or cloned Google apps never fail with host GmsCore SecurityException.
 * 2. For ALL built APKs (3rd-party games/apps AND Google 1st-party apps):
 *    intercepts Google Sign-In across all 3 modern Android APIs:
 *      (a) Classic GoogleSignInClient (SignInHubActivity / com.google.android.gms.auth.GOOGLE_SIGN_IN)
 *      (b) Google Identity Services / One Tap (com.google.android.gms.auth.api.identity.service.signin.START)
 *      (c) Android 14+ Credential Manager (GetGoogleIdOption / GetSignInWithGoogleOption)
 *    plus AccountManager & GoogleAuthUtil.getToken() (IAuthManagerService).
 * 3. Exchanges real Google Master Tokens (aas_et/...) at https://android.clients.google.com/auth
 *    using the original APK's real package name and real SHA-1 signing certificate so Google's
 *    servers mint genuine JWT ID Tokens and OAuth2 Access Tokens without "16: Account reauth failed".
 */
public final class EmbeddedGmsCore {

    public static final String GOOGLE_ACCOUNT_TYPE = "com.google";
    public static final String GMS_PACKAGE = "com.google.android.gms";
    public static final String GSF_PACKAGE = "com.google.android.gsf";
    public static final String GMS_OFFICIAL_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788";
    public static final String YOUTUBE_OFFICIAL_SHA1 = "24bb24c05e47e0aeefa68a58a766179d9b613a60";

    public static final String GOOGLE_ID_CREDENTIAL_TYPE =
            "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL";
    private static final String BUNDLE_KEY_SERVER_CLIENT_ID =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_SERVER_CLIENT_ID";
    private static final String BUNDLE_KEY_NONCE =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_NONCE";
    private static final String SIWG_BUNDLE_KEY_SERVER_CLIENT_ID =
            "com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_SERVER_CLIENT_ID";
    private static final String SIWG_BUNDLE_KEY_NONCE =
            "com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_NONCE";
    private static final String BUNDLE_KEY_ID =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID";
    private static final String BUNDLE_KEY_ID_TOKEN =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN";
    private static final String BUNDLE_KEY_DISPLAY_NAME =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_DISPLAY_NAME";
    private static final String BUNDLE_KEY_GIVEN_NAME =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_GIVEN_NAME";
    private static final String BUNDLE_KEY_FAMILY_NAME =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_FAMILY_NAME";
    private static final String BUNDLE_KEY_PROFILE_PICTURE_URI =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_PROFILE_PICTURE_URI";

    private static final String[] IDENTITY_SIGNIN_FEATURES = new String[]{
            "auth_api_credentials_begin_sign_in",
            "auth_api_credentials_sign_out",
            "auth_api_credentials_authorize",
            "auth_api_credentials_revoke_access",
            "auth_api_credentials_save_password",
            "auth_api_credentials_get_sign_in_intent",
            "auth_api_credentials_save_account_linking_token",
            "auth_api_credentials_get_phone_number_hint_intent",
            "auth_api_credentials_verify_with_google",
            "auth_api_credentials_credential_provider"
    };

    private static final String EMBEDDED_SETUP_URL =
            "https://accounts.google.com/EmbeddedSetup?hl=en&source=android&xoauth_display_name=Android%20Phone&canFrp=0&has_permission=1&cc=us";
    private static final String GOOGLE_AUTH_ENDPOINT = "https://android.clients.google.com/auth";
    private static final String ACTION_EMBEDDED_IDENTITY_SIGNIN = "com.ghostdevice.EMBEDDED_GMS_IDENTITY_SIGNIN";

    private static final long TOKEN_CACHE_TTL_MS = 50L * 60L * 1000L; // 50 minutes
    private static final Map<String, CachedToken> LIVE_TOKEN_CACHE = new ConcurrentHashMap<String, CachedToken>();
    private static final Map<IBinder, IdentitySignInPendingRequest> PENDING_IDENTITY_REQUESTS =
            new ConcurrentHashMap<IBinder, IdentitySignInPendingRequest>();
    private static volatile IdentitySignInPendingRequest sLastIdentityPendingReq = null;
    private static final AtomicInteger PENDING_REQ_CODE_SEQ = new AtomicInteger(42000);
    private static WeakReference<Dialog> sActiveSignInDialogRef = new WeakReference<Dialog>(null);

    private static final class CachedToken {
        final String token;
        final long expiresAtMs;

        CachedToken(String token, long expiresAtMs) {
            this.token = token;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final class IdentitySignInPendingRequest {
        final String serverClientId;
        final String nonce;
        final boolean isBeginSignIn;
        final long createdAtMs;

        IdentitySignInPendingRequest(String serverClientId, String nonce, boolean isBeginSignIn) {
            this.serverClientId = serverClientId != null ? serverClientId : "";
            this.nonce = nonce != null ? nonce : "";
            this.isBeginSignIn = isBeginSignIn;
            this.createdAtMs = System.currentTimeMillis();
        }
    }

    private static final class ParsedGoogleSignInOptions {
        String serverClientId = "";
        boolean idTokenRequested = false;
        boolean serverAuthCodeRequested = false;
        final List<String> scopes = new ArrayList<String>();
    }

    public interface SignInCallback {
        void onSuccess(String email);
        void onCancelled();
    }

    private EmbeddedGmsCore() {}

    public static boolean isGoogleFirstPartyPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String p = pkg.toLowerCase(Locale.US);
        return matchesPkgPrefix(p, "com.google.android.youtube")
                || matchesPkgPrefix(p, "com.android.vending")
                || matchesPkgPrefix(p, "com.google.android.gm")
                || matchesPkgPrefix(p, "com.google.android.apps.youtube.music")
                || matchesPkgPrefix(p, "com.google.android.apps.youtube.kids")
                || matchesPkgPrefix(p, "com.google.android.apps.youtube.creator")
                || matchesPkgPrefix(p, "com.google.android.apps.maps")
                || matchesPkgPrefix(p, "com.google.android.apps.photos")
                || matchesPkgPrefix(p, "com.google.android.apps.docs")
                || matchesPkgPrefix(p, "com.google.android.play.games")
                || matchesPkgPrefix(p, "com.google.android.googlequicksearchbox")
                || matchesPkgPrefix(p, "com.google.android.calendar")
                || matchesPkgPrefix(p, "com.google.android.keep")
                || matchesPkgPrefix(p, "com.google.android.apps.meetings")
                || matchesPkgPrefix(p, "com.google.android.apps.tachyon")
                || matchesPkgPrefix(p, "com.google.android.apps.translate")
                || matchesPkgPrefix(p, "com.google.android.videos")
                || matchesPkgPrefix(p, "com.google.android.apps.books")
                || matchesPkgPrefix(p, "com.google.android.contacts")
                || matchesPkgPrefix(p, "com.google.android.apps.nbu.files");
    }

    private static boolean matchesPkgPrefix(String lowerPkg, String basePkg) {
        return lowerPkg.equals(basePkg) || lowerPkg.startsWith(basePkg + ".");
    }

    public static boolean isPlayStorePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return matchesPkgPrefix(pkg.toLowerCase(Locale.US), "com.android.vending");
    }

    public static String getOfficialSha1ForPackage(String origPkg) {
        String sigHex = StandaloneRuntimeEngine.getOriginalSignatureHexForGms();
        if (sigHex != null && sigHex.length() >= 64) {
            try {
                byte[] certBytes = new Signature(sigHex).toByteArray();
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(certBytes);
                StringBuilder sb = new StringBuilder(40);
                for (byte b : digest) {
                    sb.append(String.format(Locale.US, "%02x", b & 0xFF));
                }
                String computed = sb.toString();
                if (computed.length() == 40) {
                    return computed;
                }
            } catch (Throwable ignored) {}
        }
        if (origPkg != null && origPkg.toLowerCase(Locale.US).contains("youtube")) {
            return YOUTUBE_OFFICIAL_SHA1;
        }
        return GMS_OFFICIAL_SHA1;
    }

    public static boolean isSignedInToGoogle() {
        Account[] accs = StandaloneRuntimeEngine.getSandboxedGoogleAccounts();
        return accs != null && accs.length > 0;
    }

    public static String getPrimaryGoogleEmail() {
        Account[] accs = StandaloneRuntimeEngine.getSandboxedGoogleAccounts();
        if (accs != null && accs.length > 0 && accs[0] != null) {
            return accs[0].name;
        }
        return null;
    }

    public static void clearTokenCache() {
        LIVE_TOKEN_CACHE.clear();
    }

    /**
     * Synthesizes a valid PackageInfo for com.google.android.gms if host device lacks GMS
     * (e.g. Huawei / AOSP / stripped ROMs) so Google apps never show "Update Google Play Services".
     */
    public static PackageInfo createFallbackGmsPackageInfo(String queriedPkg) {
        try {
            PackageInfo pi = new PackageInfo();
            pi.packageName = (queriedPkg != null && !queriedPkg.isEmpty()) ? queriedPkg : GMS_PACKAGE;
            pi.versionName = "24.47.38 (190400-704521890)";
            pi.versionCode = 244738038;
            if (Build.VERSION.SDK_INT >= 28) {
                pi.setLongVersionCode(244738038L);
            }
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = pi.packageName;
            ai.enabled = true;
            ai.flags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_HAS_CODE;
            ai.targetSdkVersion = 34;
            pi.applicationInfo = ai;
            return pi;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Backward-compatible entry point (defaults to Google 1st-party mode).
     */
    public static boolean tryDeliverEmbeddedGmsService(Intent intent, Object[] bindArgs) {
        return tryDeliverEmbeddedGmsService(intent, bindArgs, true);
    }

    /**
     * Shared predicate used by BOTH Chimera's "serviceIntentCall" resolver AND bindService()
     * interception so modern play-services-basement 18.x never bypasses EmbeddedGmsCore via
     * host GMS GmsBoundBrokerService!
     *
     * Strictly returns false for non-auth GMS services (Ads, Firebase, Location, Recaptcha,
     * Measurement, Dynamite, Cronet, Fonts, Billing, etc.) on 3rd-party apps so nothing breaks.
     */
    public static boolean shouldInterceptGmsServiceAction(String action, boolean isGoogle1p) {
        if (action == null || action.isEmpty()) return false;
        String actLower = action.toLowerCase(Locale.US);

        // Universal Google Sign-In / Auth services intercepted for ALL built APKs
        if ("com.google.android.gms.auth.gettoken".equals(actLower)
                || actLower.contains("auth.gettoken")
                || actLower.contains("auth.api.identity.service.signin")
                || actLower.contains("auth.api.identity.service.authorization")
                || actLower.contains("auth.api.signin.service")
                || actLower.contains("auth.api.credentials.service")
                || "com.google.android.gms.signin.service.start".equals(actLower)
                || "com.google.android.gms.auth.service.start".equals(actLower)
                || actLower.contains("auth.account.data")) {
            return true;
        }

        if (!isGoogle1p) {
            return false;
        }

        // Never hijack public/non-broker GMS modules even for Google 1st-party apps
        if (actLower.contains("ads.identifier")
                || actLower.contains("chimera")
                || actLower.contains("dynamite")
                || actLower.contains("cronet")
                || actLower.contains("providerinstaller")
                || actLower.contains("fonts")
                || actLower.contains("c2dm")
                || actLower.contains("gcm")
                || actLower.contains("iid")
                || actLower.contains("measurement")
                || actLower.contains("firebase")
                || actLower.contains("location")
                || actLower.contains("wearable")
                || actLower.contains("nearby")
                || actLower.contains("recaptcha")
                || actLower.contains("vision")
                || actLower.contains("mlkit")) {
            return false;
        }

        return actLower.contains("phenotype")
                || actLower.contains("clearcut")
                || actLower.contains("droidguard")
                || actLower.contains("potokens")
                || actLower.contains("pseudonymous")
                || actLower.contains("usagereporting")
                || actLower.contains("mdisync")
                || actLower.contains("gmscompliance")
                || actLower.contains("googleauthservice")
                || actLower.contains("auth.service.start")
                || actLower.contains("auth.api.accounttransfer")
                || actLower.contains("auth.api.credentials")
                || actLower.contains("auth.api.identity")
                || actLower.contains("auth.api.signin")
                || actLower.contains("auth.folsom")
                || actLower.contains("auth.cryptauth")
                || actLower.contains("blockstore")
                || actLower.contains("credential")
                || actLower.contains("fido")
                || actLower.contains("signin")
                || actLower.contains("people")
                || actLower.contains("icing")
                || actLower.contains("games")
                || actLower.contains("plus")
                || actLower.contains("cast")
                || actLower.contains("feedback")
                || actLower.contains("help")
                || actLower.contains("playintegrity")
                || actLower.contains("herrevad")
                || actLower.contains("constellation")
                || actLower.contains("chromesync")
                || actLower.contains("audit")
                || actLower.contains("lockbox")
                || actLower.contains("udc");
    }

    /**
     * Intercepts GMS/Auth service bindings and delivers in-process local Binders.
     */
    public static boolean tryDeliverEmbeddedGmsService(Intent intent, Object[] bindArgs, boolean isGoogle1p) {
        if (intent == null || bindArgs == null) return false;
        String act = intent.getAction();
        String pkg = intent.getPackage();
        ComponentName cn = intent.getComponent();
        if (pkg == null && cn != null) pkg = cn.getPackageName();

        String actLower = (act != null) ? act.toLowerCase(Locale.US) : "";
        String pkgLower = (pkg != null) ? pkg.toLowerCase(Locale.US) : "";
        String clsLower = (cn != null && cn.getClassName() != null) ? cn.getClassName().toLowerCase(Locale.US) : "";

        boolean isGmsTarget = GMS_PACKAGE.equals(pkgLower)
                || GSF_PACKAGE.equals(pkgLower)
                || actLower.startsWith("com.google.android.gms.")
                || actLower.startsWith("com.google.android.gsf.");
        if (!isGmsTarget) return false;

        boolean isGetToken = "com.google.android.gms.auth.gettoken".equals(actLower)
                || actLower.contains("auth.gettoken")
                || clsLower.equals("com.google.android.gms.auth.gettoken");

        boolean shouldIntercept = isGetToken || shouldInterceptGmsServiceAction(act, isGoogle1p);
        if (!shouldIntercept && isGoogle1p && !clsLower.contains("chimera")) {
            shouldIntercept = clsLower.contains("droidguard")
                    || clsLower.contains("potokens")
                    || clsLower.contains("mdisync")
                    || clsLower.contains("gmscompliance");
        }
        if (!shouldIntercept) {
            return false;
        }

        Object serviceConn = extractServiceConnectionArg(bindArgs);
        if (serviceConn == null) return false;

        final IBinder localBinder;
        final ComponentName comp;
        if (isGetToken) {
            localBinder = new VirtualAuthManagerServiceBinder();
            comp = new ComponentName(GMS_PACKAGE, "com.google.android.gms.auth.GetToken");
        } else {
            localBinder = new VirtualGmsServiceBrokerBinder(act != null ? act : "");
            comp = new ComponentName(GMS_PACKAGE,
                    (cn != null && cn.getClassName() != null && !cn.getClassName().isEmpty())
                            ? cn.getClassName()
                            : "com.google.android.gms.common.service.GmsServiceBroker");
        }

        deliverLocalBinderToServiceConnection(serviceConn, comp, localBinder);
        return true;
    }

    private static Object extractServiceConnectionArg(Object[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a != null && (a.getClass().getName().contains("ServiceDispatcher")
                    || StandaloneRuntimeEngine.hasInterfaceNamedForGms(a.getClass(), "android.app.IServiceConnection"))) {
                return a;
            }
        }
        return null;
    }

    private static boolean isBlockingServiceConnection(android.content.ServiceConnection sc) {
        if (sc == null) return false;
        String clsName = sc.getClass().getName();
        if (clsName.contains("BlockingServiceConnection") || clsName.contains("Blocking")) {
            return true;
        }
        try {
            Class<?> c = sc.getClass();
            while (c != null && c != Object.class) {
                Field[] fields = c.getDeclaredFields();
                for (int i = 0; i < fields.length; i++) {
                    if (java.util.concurrent.BlockingQueue.class.isAssignableFrom(fields[i].getType())) {
                        return true;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static android.content.ServiceConnection unwrapRawServiceConnection(Object connObj) {
        try {
            Field dispField = StandaloneRuntimeEngine.findFieldRecursiveForGms(connObj.getClass(), "mDispatcher");
            if (dispField != null) {
                dispField.setAccessible(true);
                Object dispObj = dispField.get(connObj);
                if (dispObj instanceof WeakReference) {
                    dispObj = ((WeakReference<?>) dispObj).get();
                }
                if (dispObj != null) {
                    Field scField = StandaloneRuntimeEngine.findFieldRecursiveForGms(dispObj.getClass(), "mConnection");
                    if (scField != null) {
                        scField.setAccessible(true);
                        Object rawSc = scField.get(dispObj);
                        if (rawSc instanceof android.content.ServiceConnection) {
                            return (android.content.ServiceConnection) rawSc;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void deliverLocalBinderToServiceConnection(
            final Object connObj,
            final ComponentName comp,
            final IBinder localBinder
    ) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(15L);
                } catch (Throwable ignored) {}

                final android.content.ServiceConnection rawSc = unwrapRawServiceConnection(connObj);

                if (rawSc != null && isBlockingServiceConnection(rawSc)) {
                    try {
                        rawSc.onServiceConnected(comp, localBinder);
                        return;
                    } catch (Throwable ignored) {}
                }

                boolean delivered = false;
                try {
                    Method[] methods = connObj.getClass().getDeclaredMethods();
                    for (int i = 0; i < methods.length; i++) {
                        Method m = methods[i];
                        if ("connected".equals(m.getName())) {
                            m.setAccessible(true);
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length == 2) {
                                m.invoke(connObj, comp, localBinder);
                                delivered = true;
                                break;
                            } else if (pts.length == 3) {
                                m.invoke(connObj, comp, localBinder, Boolean.FALSE);
                                delivered = true;
                                break;
                            } else if (pts.length == 4) {
                                m.invoke(connObj, comp, localBinder, null, Boolean.FALSE);
                                delivered = true;
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                if (!delivered && rawSc != null) {
                    try {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    rawSc.onServiceConnected(comp, localBinder);
                                } catch (Throwable ignored) {}
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            }
        }, "EmbeddedGmsDeliver");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Local Binder implementing com.google.android.auth.IAuthManagerService (GetToken service).
     * Used by GoogleAuthUtil.getToken() in YouTube, Play Store, Gmail, Drive, Maps, and 3rd-party apps.
     */
    private static final class VirtualAuthManagerServiceBinder extends Binder {
        private static final String DESC = "com.google.android.auth.IAuthManagerService";

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public String getInterfaceDescriptor() {
            return DESC;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            try {
                if (code == 1) { // Bundle getToken(String email, String scope, Bundle extras)
                    data.enforceInterface(DESC);
                    String email = data.readString();
                    String scope = data.readString();
                    Bundle extras = (data.readInt() != 0) ? Bundle.CREATOR.createFromParcel(data) : null;
                    Bundle res = handleAuthGetToken(email, scope, extras);
                    writeBundleReply(reply, res);
                    return true;
                } else if (code == 2) { // Bundle clearToken(String token, Bundle extras)
                    data.enforceInterface(DESC);
                    String token = data.readString();
                    if (data.readInt() != 0) {
                        Bundle.CREATOR.createFromParcel(data);
                    }
                    invalidateCachedToken(token);
                    Bundle res = new Bundle();
                    res.putBoolean("booleanResult", true);
                    res.putString("Error", "OK");
                    writeBundleReply(reply, res);
                    return true;
                } else if (code == 5) { // Bundle getTokenByAccount(Account account, String scope, Bundle extras)
                    data.enforceInterface(DESC);
                    Account acc = (data.readInt() != 0) ? Account.CREATOR.createFromParcel(data) : null;
                    String scope = data.readString();
                    Bundle extras = (data.readInt() != 0) ? Bundle.CREATOR.createFromParcel(data) : null;
                    Bundle res = handleAuthGetToken(acc != null ? acc.name : null, scope, extras);
                    writeBundleReply(reply, res);
                    return true;
                } else if (code == 6) { // Bundle getAccounts(Bundle extras)
                    data.enforceInterface(DESC);
                    if (data.readInt() != 0) {
                        Bundle.CREATOR.createFromParcel(data);
                    }
                    Account[] accounts = StandaloneRuntimeEngine.getSandboxedGoogleAccounts();
                    Bundle res = new Bundle();
                    res.putParcelableArray("accounts", accounts != null ? accounts : new Account[0]);
                    writeBundleReply(reply, res);
                    return true;
                } else if (code == 7) { // Bundle requestGoogleAccountsAccess(String packageName)
                    data.enforceInterface(DESC);
                    data.readString();
                    Bundle res = new Bundle();
                    res.putString("Error", "OK");
                    writeBundleReply(reply, res);
                    return true;
                }
            } catch (Throwable t) {
                Bundle err = new Bundle();
                err.putString("Error", "NetworkError");
                writeBundleReply(reply, err);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static void writeBundleReply(Parcel reply, Bundle bundle) {
        if (reply == null) return;
        reply.writeNoException();
        if (bundle != null) {
            reply.writeInt(1);
            bundle.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        } else {
            reply.writeInt(0);
        }
    }

    /**
     * Local Binder implementing com.google.android.gms.common.internal.IGmsServiceBroker.
     * Routes Identity SignInClient (serviceId 212) and Legacy GoogleSignInClient (serviceId 91)
     * to real in-process Sign-In service binders, and resolves restricted Google 1P services cleanly.
     */
    private static final class VirtualGmsServiceBrokerBinder extends Binder {
        private static final String DESC = "com.google.android.gms.common.internal.IGmsServiceBroker";
        private final String action;

        VirtualGmsServiceBrokerBinder(String action) {
            this.action = action != null ? action : "";
        }

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public String getInterfaceDescriptor() {
            return DESC;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 46) { // getService(IGmsCallbacks, GetServiceRequest)
                try {
                    data.enforceInterface(DESC);
                    final IBinder callbacksBinder = data.readStrongBinder();
                    int serviceId = -1;
                    if (data.readInt() != 0) {
                        serviceId = parseServiceIdFromSafeParcelable(data);
                    }
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    if (callbacksBinder != null) {
                        final int finalServiceId = serviceId;
                        Thread cbThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                dispatchGmsPostInitComplete(callbacksBinder, finalServiceId);
                            }
                        }, "EmbeddedGmsCb");
                        cbThread.setDaemon(true);
                        cbThread.start();
                    }
                    return true;
                } catch (Throwable ignored) {
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
            }
            if (code >= 1 && code < 46) {
                try {
                    data.enforceInterface(DESC);
                    final IBinder callbacksBinder = data.readStrongBinder();
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    if (callbacksBinder != null) {
                        Thread cbThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                invokeGmsOnPostInitComplete(callbacksBinder, 16, null, new Bundle());
                            }
                        }, "EmbeddedGmsLegacyCb");
                        cbThread.setDaemon(true);
                        cbThread.start();
                    }
                    return true;
                } catch (Throwable ignored) {
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private void dispatchGmsPostInitComplete(IBinder callbacksBinder, int serviceId) {
            String actLower = action.toLowerCase(Locale.US);
            // Google Identity Services SignInClient (serviceId == 212) — MUST include ConnectionInfo Feature[]
            // via onPostInitCompleteWithConnectionInfo (code 3) so GoogleApiManager allows beginSignIn/getSignInIntent!
            if (serviceId == 212 || actLower.contains("auth.api.identity.service.signin")) {
                invokeGmsOnPostInitCompleteWithConnectionInfo(
                        callbacksBinder, 0, new VirtualIdentitySignInServiceBinder(), IDENTITY_SIGNIN_FEATURES);
                return;
            }
            // Legacy GoogleSignInClient (serviceId == 91) and GMS SignInClient (serviceId == 44)
            if (serviceId == 91 || serviceId == 44 || actLower.contains("auth.api.signin.service")
                    || "com.google.android.gms.signin.service.start".equals(actLower)) {
                invokeGmsOnPostInitCompleteWithConnectionInfo(
                        callbacksBinder, 0, new VirtualGoogleSignInServiceBinder(), null);
                return;
            }
            // 16 == CommonStatusCodes.API_UNAVAILABLE: BaseGmsClient transitions cleanly without crashing
            // or retrying infinitely (and GoogleAuth falls back to AccountManager + GetToken).
            invokeGmsOnPostInitComplete(callbacksBinder, 16, null, new Bundle());
        }
    }

    /**
     * Local Binder implementing com.google.android.gms.auth.api.identity.internal.ISignInService.
     * Handles Identity.getSignInClient(activity).beginSignIn(...) (code 1), signOut(...) (code 2),
     * and getSignInIntent(...) (code 3) for ALL built APKs (3rd-party games like Air Merge & apps)!
     */
    private static final class VirtualIdentitySignInServiceBinder extends Binder {
        private static final String DESC = "com.google.android.gms.auth.api.identity.internal.ISignInService";

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public String getInterfaceDescriptor() {
            return DESC;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            try {
                data.enforceInterface(DESC);
                if (code == 1) { // beginSignIn(IBeginSignInCallback, BeginSignInRequest)
                    final IBinder cb = data.readStrongBinder();
                    final String[] parsed = extractClientIdAndNonceFromParcel(data);
                    if (reply != null) reply.writeNoException();
                    final PendingIntent pi = createTrackedIdentityPendingIntent(parsed[0], parsed[1], true);
                    dispatchIdentityBeginSignInCallback(cb, pi);
                    return true;
                } else if (code == 2) { // signOut(IStatusCallback, String sessionId)
                    final IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    dispatchSimpleStatusCallback(cb, 0);
                    return true;
                } else if (code == 3) { // getSignInIntent(IGetSignInIntentCallback, GetSignInIntentRequest)
                    final IBinder cb = data.readStrongBinder();
                    final String[] parsed = extractClientIdAndNonceFromParcel(data);
                    if (reply != null) reply.writeNoException();
                    final PendingIntent pi = createTrackedIdentityPendingIntent(parsed[0], parsed[1], false);
                    dispatchIdentityGetSignInIntentCallback(cb, pi);
                    return true;
                } else if (code == 4) { // getPhoneNumberHintIntent
                    final IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    dispatchIdentityGetSignInIntentCallback(cb, null);
                    return true;
                }
            } catch (Throwable ignored) {
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    /**
     * Local Binder implementing com.google.android.gms.auth.api.signin.internal.ISignInService.
     * Handles silentSignIn (101), signOut (102), and revokeAccess (103) cleanly.
     */
    private static final class VirtualGoogleSignInServiceBinder extends Binder {
        private static final String DESC = "com.google.android.gms.auth.api.signin.internal.ISignInService";

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public String getInterfaceDescriptor() {
            return DESC;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            try {
                data.enforceInterface(DESC);
                final IBinder cb = data.readStrongBinder();
                if (reply != null) reply.writeNoException();
                if (code == 101) { // silentSignIn -> return SIGN_IN_REQUIRED (4) so app opens interactive Sign-In flow cleanly
                    dispatchLegacySignInCallbacks(cb, 101, 4);
                } else if (code == 102) { // signOut
                    StandaloneRuntimeEngine.clearAllSandboxedGoogleAccountsForGms();
                    LIVE_TOKEN_CACHE.clear();
                    dispatchLegacySignInCallbacks(cb, 102, 0);
                } else if (code == 103) { // revokeAccess
                    StandaloneRuntimeEngine.clearAllSandboxedGoogleAccountsForGms();
                    LIVE_TOKEN_CACHE.clear();
                    dispatchLegacySignInCallbacks(cb, 103, 0);
                }
                return true;
            } catch (Throwable ignored) {
                if (reply != null) reply.writeNoException();
                return true;
            }
        }
    }

    private static void dispatchLegacySignInCallbacks(final IBinder cb, final int code, final int statusCode) {
        if (cb == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken("com.google.android.gms.auth.api.signin.internal.ISignInCallbacks");
                    if (code == 101) {
                        d.writeInt(0); // null GoogleSignInAccount
                        writeSafeParcelStatus(d, statusCode);
                    } else {
                        writeSafeParcelStatus(d, statusCode);
                    }
                    cb.transact(code, d, r, 0);
                    r.readException();
                } catch (Throwable ignored) {
                } finally {
                    try { d.recycle(); } catch (Throwable ignored) {}
                    try { r.recycle(); } catch (Throwable ignored) {}
                }
            }
        }, "EmbeddedLegacySignInCb");
        t.setDaemon(true);
        t.start();
    }

    private static void dispatchSimpleStatusCallback(final IBinder cb, final int statusCode) {
        if (cb == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken("com.google.android.gms.common.api.internal.IStatusCallback");
                    writeSafeParcelStatus(d, statusCode);
                    cb.transact(1, d, r, 0);
                    r.readException();
                } catch (Throwable ignored) {
                } finally {
                    try { d.recycle(); } catch (Throwable ignored) {}
                    try { r.recycle(); } catch (Throwable ignored) {}
                }
            }
        }, "EmbeddedStatusCb");
        t.setDaemon(true);
        t.start();
    }

    private static void dispatchIdentityBeginSignInCallback(final IBinder cb, final PendingIntent pi) {
        if (cb == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken("com.google.android.gms.auth.api.identity.internal.IBeginSignInCallback");
                    writeSafeParcelStatus(d, pi != null ? 0 : 16);
                    if (pi != null) {
                        d.writeInt(1);
                        writeSafeParcelBeginSignInResult(d, pi);
                    } else {
                        d.writeInt(0);
                    }
                    cb.transact(1, d, r, 0);
                    r.readException();
                } catch (Throwable ignored) {
                } finally {
                    try { d.recycle(); } catch (Throwable ignored) {}
                    try { r.recycle(); } catch (Throwable ignored) {}
                }
            }
        }, "EmbeddedBeginSignInCb");
        t.setDaemon(true);
        t.start();
    }

    private static void dispatchIdentityGetSignInIntentCallback(final IBinder cb, final PendingIntent pi) {
        if (cb == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken("com.google.android.gms.auth.api.identity.internal.IGetSignInIntentCallback");
                    writeSafeParcelStatus(d, pi != null ? 0 : 16);
                    if (pi != null) {
                        d.writeInt(1);
                        pi.writeToParcel(d, 0);
                    } else {
                        d.writeInt(0);
                    }
                    cb.transact(1, d, r, 0);
                    r.readException();
                } catch (Throwable ignored) {
                } finally {
                    try { d.recycle(); } catch (Throwable ignored) {}
                    try { r.recycle(); } catch (Throwable ignored) {}
                }
            }
        }, "EmbeddedSignInIntentCb");
        t.setDaemon(true);
        t.start();
    }

    private static void writeSafeParcelStatus(Parcel dest, int statusCode) {
        dest.writeInt(1); // non-null Status
        int headerPos = dest.dataPosition();
        dest.writeInt(0xFFFF4F45); // SafeParcelable object header magic with extended size
        dest.writeInt(0); // size placeholder
        int contentStart = dest.dataPosition();
        // field 1: statusCode (int, size 4)
        dest.writeInt((4 << 16) | 1);
        dest.writeInt(statusCode);
        // field 1000: versionCode = 1 (int, size 4)
        dest.writeInt((4 << 16) | 1000);
        dest.writeInt(1);
        int endPos = dest.dataPosition();
        dest.setDataPosition(headerPos + 4);
        dest.writeInt(endPos - contentStart);
        dest.setDataPosition(endPos);
    }

    private static void writeSafeParcelBeginSignInResult(Parcel dest, PendingIntent pi) {
        int headerPos = dest.dataPosition();
        dest.writeInt(0xFFFF4F45);
        dest.writeInt(0); // outer size placeholder
        int contentStart = dest.dataPosition();
        // field 1: PendingIntent parcelable
        int fHeaderPos = dest.dataPosition();
        dest.writeInt(0xFFFF0001);
        dest.writeInt(0); // field size placeholder
        int fStart = dest.dataPosition();
        pi.writeToParcel(dest, 0);
        int fEnd = dest.dataPosition();
        dest.setDataPosition(fHeaderPos + 4);
        dest.writeInt(fEnd - fStart);
        dest.setDataPosition(fEnd);
        int endPos = dest.dataPosition();
        dest.setDataPosition(headerPos + 4);
        dest.writeInt(endPos - contentStart);
        dest.setDataPosition(endPos);
    }

    /**
     * Extracts Google OAuth Web Client ID (*.apps.googleusercontent.com) and optional nonce
     * from a BeginSignInRequest or GetSignInIntentRequest SafeParcelable WITHOUT calling
     * Parcel.marshall() (which throws RuntimeException whenever the Parcel contains an IBinder!).
     */
    private static String[] extractClientIdAndNonceFromParcel(Parcel data) {
        String[] out = new String[]{"", ""};
        try {
            if (data == null || data.dataAvail() < 4) {
                return out;
            }
            int savedPos = data.dataPosition();
            int nonNullFlag = data.readInt();
            if (nonNullFlag != 0 && data.dataAvail() >= 4) {
                scanSafeParcelObjectForClientIdAndNonce(data, 0, out);
            }
            data.setDataPosition(savedPos);
        } catch (Throwable ignored) {}
        return out;
    }

    private static void scanSafeParcelObjectForClientIdAndNonce(Parcel data, int depth, String[] out) {
        if (data == null || depth > 5 || data.dataAvail() < 4) return;
        int startPos = data.dataPosition();
        int header = data.readInt();
        int tag = header & 0xFFFF;
        if (tag != 0x4F45) {
            data.setDataPosition(startPos);
            return;
        }
        int objSize = (header >>> 16) & 0xFFFF;
        if (objSize == 0xFFFF) {
            if (data.dataAvail() < 4) return;
            objSize = data.readInt();
        }
        if (objSize <= 0) return;
        int endPos = Math.min(data.dataSize(), data.dataPosition() + objSize);
        while (data.dataPosition() + 4 <= endPos) {
            int fHeader = data.readInt();
            int fSize = (fHeader >>> 16) & 0xFFFF;
            if (fSize == 0xFFFF) {
                if (data.dataPosition() + 4 > endPos) break;
                fSize = data.readInt();
            }
            if (fSize < 0) break;
            int fStart = data.dataPosition();
            int fEnd = fStart + fSize;
            if (fEnd > endPos || fEnd < fStart) break;

            if (fSize >= 8) {
                try {
                    int peek = data.readInt();
                    data.setDataPosition(fStart);
                    if ((peek & 0xFFFF) == 0x4F45) {
                        // Nested SafeParcelable (e.g. GoogleIdTokenRequestOptions inside BeginSignInRequest)
                        scanSafeParcelObjectForClientIdAndNonce(data, depth + 1, out);
                    } else if (peek > 0 && peek < 512 && (fStart + 4 + peek * 2) <= fEnd + 4) {
                        // Candidate Parcel String (UTF-16 length prefix)
                        String s = data.readString();
                        if (s != null && !s.isEmpty()) {
                            String foundCid = scanGoogleClientIdInString(s);
                            if (!foundCid.isEmpty() && out[0].isEmpty()) {
                                out[0] = foundCid;
                            } else if (out[1].isEmpty() && depth >= 1 && s.length() >= 8 && s.length() <= 256
                                    && !s.contains(" ") && !s.startsWith("com.") && !s.contains("@")
                                    && !s.contains(".apps.googleusercontent.com")) {
                                // Likely nonce field inside GoogleIdTokenRequestOptions (field 3)
                                int fId = fHeader & 0xFFFF;
                                if (fId == 3 || fId == 4) {
                                    out[1] = s.trim();
                                }
                            }
                        }
                    }
                } catch (Throwable ignoredField) {}
            }
            data.setDataPosition(fEnd);
        }
        data.setDataPosition(endPos);
    }

    private static String scanGoogleClientIdInString(String text) {
        if (text == null || text.isEmpty()) return "";
        int idx = text.indexOf(".apps.googleusercontent.com");
        if (idx <= 0) return "";
        int start = idx;
        while (start > 0) {
            char ch = text.charAt(start - 1);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
                    (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.') {
                start--;
            } else {
                break;
            }
        }
        int end = idx + ".apps.googleusercontent.com".length();
        return (end > start) ? text.substring(start, end) : "";
    }

    private static PendingIntent createTrackedIdentityPendingIntent(String serverClientId, String nonce, boolean isBeginSignIn) {
        try {
            Context ctx = StandaloneRuntimeEngine.getAppContextForGms();
            if (ctx == null) return null;
            Intent intent = new Intent(ACTION_EMBEDDED_IDENTITY_SIGNIN);
            intent.setPackage(ctx.getPackageName());
            intent.putExtra("serverClientId", serverClientId);
            intent.putExtra("nonce", nonce);
            int reqCode = PENDING_REQ_CODE_SEQ.incrementAndGet();
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent, flags);
            if (pi != null) {
                IdentitySignInPendingRequest req = new IdentitySignInPendingRequest(serverClientId, nonce, isBeginSignIn);
                sLastIdentityPendingReq = req;
                // Prune stale entries older than 5 minutes
                long cutoff = System.currentTimeMillis() - 5L * 60L * 1000L;
                for (Map.Entry<IBinder, IdentitySignInPendingRequest> e : PENDING_IDENTITY_REQUESTS.entrySet()) {
                    if (e.getValue() != null && e.getValue().createdAtMs < cutoff) {
                        PENDING_IDENTITY_REQUESTS.remove(e.getKey());
                    }
                }
                IBinder targetBinder = extractTargetBinderFromObject(pi);
                if (targetBinder != null) {
                    PENDING_IDENTITY_REQUESTS.put(targetBinder, req);
                }
                try {
                    IBinder senderBinder = extractTargetBinderFromObject(pi.getIntentSender());
                    if (senderBinder != null) {
                        PENDING_IDENTITY_REQUESTS.put(senderBinder, req);
                    }
                } catch (Throwable ignored) {}
            }
            return pi;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static IBinder extractTargetBinderFromObject(Object obj) {
        if (obj == null) return null;
        try {
            Field f = StandaloneRuntimeEngine.findFieldRecursiveForGms(obj.getClass(), "mTarget");
            if (f != null) {
                f.setAccessible(true);
                Object target = f.get(obj);
                if (target instanceof IInterface) {
                    return ((IInterface) target).asBinder();
                }
                if (target instanceof IBinder) {
                    return (IBinder) target;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Intercepts startActivityIntentSender / startIntentSender / sendIntentSender calls for
     * PendingIntents created by VirtualIdentitySignInServiceBinder (Identity.getSignInClient() / One Tap / Air Merge).
     */
    public static boolean tryHandleTrackedIdentityIntentSender(final Object[] startIntentSenderArgs) {
        if (startIntentSenderArgs == null) return false;
        IdentitySignInPendingRequest matchedReq = null;
        IBinder matchedKey = null;
        if (!PENDING_IDENTITY_REQUESTS.isEmpty()) {
            for (int i = 0; i < startIntentSenderArgs.length; i++) {
                Object a = startIntentSenderArgs[i];
                if (a == null) continue;
                IBinder b = null;
                if (a instanceof IInterface) {
                    b = ((IInterface) a).asBinder();
                } else if (a instanceof IBinder) {
                    b = (IBinder) a;
                }
                if (b != null) {
                    IdentitySignInPendingRequest req = PENDING_IDENTITY_REQUESTS.get(b);
                    if (req != null) {
                        matchedReq = req;
                        matchedKey = b;
                        break;
                    }
                }
            }
        }
        // Fallback for OEM/API 35+ where IIntentSender proxy binder identity may wrap across IPC:
        // if VirtualIdentitySignInServiceBinder issued a PendingIntent within the last 20 seconds, consume it!
        if (matchedReq == null) {
            IdentitySignInPendingRequest recent = sLastIdentityPendingReq;
            if (recent != null && (System.currentTimeMillis() - recent.createdAtMs) <= 20000L) {
                matchedReq = recent;
            }
        }
        if (matchedReq == null) return false;
        if (matchedKey != null) {
            PENDING_IDENTITY_REQUESTS.remove(matchedKey);
        }
        if (sLastIdentityPendingReq == matchedReq) {
            sLastIdentityPendingReq = null;
        }

        Activity hostAct = StandaloneRuntimeEngine.getForegroundActivityForGms();
        if (hostAct == null || hostAct.isFinishing()) {
            return false;
        }
        final IdentitySignInPendingRequest finalReq = matchedReq;
        showGoogleSignInDialog(hostAct, new SignInCallback() {
            @Override
            public void onSuccess(final String email) {
                Thread tokThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String idToken = fetchGoogleIdTokenSync(email, finalReq.serverClientId, finalReq.nonce);
                            Intent resultIntent = buildIdentitySignInResultIntent(email, idToken);
                            StandaloneRuntimeEngine.dispatchActivityResultForGms(
                                    startIntentSenderArgs,
                                    (resultIntent != null ? Activity.RESULT_OK : Activity.RESULT_CANCELED),
                                    resultIntent
                            );
                        } catch (Throwable ignored) {
                            StandaloneRuntimeEngine.dispatchActivityResultForGms(
                                    startIntentSenderArgs, Activity.RESULT_CANCELED, null);
                        }
                    }
                }, "EmbeddedIdentityMint");
                tokThread.setDaemon(true);
                tokThread.start();
            }

            @Override
            public void onCancelled() {
                StandaloneRuntimeEngine.dispatchActivityResultForGms(
                        startIntentSenderArgs, Activity.RESULT_CANCELED, null);
            }
        });
        return true;
    }

    /**
     * Intercepts Android 14+ CredentialManager executeGetCredential / getCredential requests that ask for
     * Google ID Token credentials (GetGoogleIdOption / GetSignInWithGoogleOption) for ALL built APKs!
     */
    public static boolean tryHandleCredentialManagerGoogleSignIn(final Object[] credArgs) {
        if (credArgs == null || Build.VERSION.SDK_INT < 34) return false;
        try {
            Object getCredReq = null;
            Object callbackObj = null;
            for (int i = 0; i < credArgs.length; i++) {
                Object a = credArgs[i];
                if (a == null) continue;
                String cn = a.getClass().getName();
                if (cn.equals("android.credentials.GetCredentialRequest")) {
                    getCredReq = a;
                } else if (cn.contains("IGetCredentialCallback") ||
                        StandaloneRuntimeEngine.hasInterfaceNamedForGms(a.getClass(), "android.credentials.IGetCredentialCallback")) {
                    callbackObj = a;
                }
            }
            if (getCredReq == null || callbackObj == null) return false;

            Method getOptsMethod = getCredReq.getClass().getMethod("getCredentialOptions");
            Object optsObj = getOptsMethod.invoke(getCredReq);
            if (!(optsObj instanceof List)) return false;
            List<?> opts = (List<?>) optsObj;

            String foundServerClientId = null;
            String foundNonce = null;
            boolean hasGoogleIdOption = false;
            for (int i = 0; i < opts.size(); i++) {
                Object opt = opts.get(i);
                if (opt == null) continue;
                Method getTypeM = opt.getClass().getMethod("getType");
                Object typeObj = getTypeM.invoke(opt);
                String typeStr = typeObj != null ? typeObj.toString() : "";
                String typeLower = typeStr.toLowerCase(Locale.US);
                boolean optIsGoogle = GOOGLE_ID_CREDENTIAL_TYPE.equals(typeStr)
                        || typeLower.contains("googleid")
                        || typeLower.contains("google_id")
                        || typeLower.contains("signinwithgoogle");

                Bundle[] candidateBundles = new Bundle[2];
                try {
                    Object d1 = opt.getClass().getMethod("getCredentialRetrievalData").invoke(opt);
                    if (d1 instanceof Bundle) candidateBundles[0] = (Bundle) d1;
                } catch (Throwable ignored) {}
                try {
                    Object d2 = opt.getClass().getMethod("getCandidateQueryData").invoke(opt);
                    if (d2 instanceof Bundle) candidateBundles[1] = (Bundle) d2;
                } catch (Throwable ignored) {}
                for (int bIdx = 0; bIdx < candidateBundles.length; bIdx++) {
                    Bundle b = candidateBundles[bIdx];
                    if (b == null) continue;
                    if (foundServerClientId == null || foundServerClientId.isEmpty()) {
                        foundServerClientId = firstNonEmpty(
                                b.getString(BUNDLE_KEY_SERVER_CLIENT_ID),
                                b.getString(SIWG_BUNDLE_KEY_SERVER_CLIENT_ID));
                    }
                    if (foundNonce == null || foundNonce.isEmpty()) {
                        foundNonce = firstNonEmpty(
                                b.getString(BUNDLE_KEY_NONCE),
                                b.getString(SIWG_BUNDLE_KEY_NONCE));
                    }
                    for (String k : b.keySet()) {
                        if (k != null && k.toLowerCase(Locale.US).contains("googleid")) {
                            optIsGoogle = true;
                        }
                        Object val = b.get(k);
                        if (val instanceof String) {
                            String sv = (String) val;
                            if (sv.contains(".apps.googleusercontent.com")) {
                                optIsGoogle = true;
                                if (foundServerClientId == null || foundServerClientId.isEmpty()) {
                                    foundServerClientId = sv.trim();
                                }
                            }
                        }
                    }
                }
                if (optIsGoogle) {
                    hasGoogleIdOption = true;
                    break;
                }
            }
            if (!hasGoogleIdOption) return false;

            Activity hostAct = StandaloneRuntimeEngine.getForegroundActivityForGms();
            if (hostAct == null || hostAct.isFinishing()) {
                return false;
            }
            final String fClientId = foundServerClientId != null ? foundServerClientId : "";
            final String fNonce = foundNonce != null ? foundNonce : "";
            final Object fCallback = callbackObj;

            showGoogleSignInDialog(hostAct, new SignInCallback() {
                @Override
                public void onSuccess(final String email) {
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                String idToken = fetchGoogleIdTokenSync(email, fClientId, fNonce);
                                if (idToken == null || idToken.isEmpty()) {
                                    invokeCredentialCallbackError(fCallback,
                                            "android.credentials.GetCredentialException.TYPE_UNKNOWN",
                                            "Google ID token unavailable");
                                    return;
                                }
                                Map<String, String> claims = decodeJwtClaims(idToken);
                                Bundle credData = new Bundle();
                                credData.putString(BUNDLE_KEY_ID, email);
                                credData.putString(BUNDLE_KEY_ID_TOKEN, idToken);
                                String displayName = firstNonEmpty(claims.get("name"), email);
                                String givenName = firstNonEmpty(claims.get("given_name"), "");
                                String familyName = firstNonEmpty(claims.get("family_name"), "");
                                String picture = firstNonEmpty(claims.get("picture"), "");
                                credData.putString(BUNDLE_KEY_DISPLAY_NAME, displayName);
                                if (!givenName.isEmpty()) credData.putString(BUNDLE_KEY_GIVEN_NAME, givenName);
                                if (!familyName.isEmpty()) credData.putString(BUNDLE_KEY_FAMILY_NAME, familyName);
                                if (!picture.isEmpty()) {
                                    credData.putParcelable(BUNDLE_KEY_PROFILE_PICTURE_URI, Uri.parse(picture));
                                }

                                Class<?> credCls = Class.forName("android.credentials.Credential");
                                Constructor<?> credCtor = credCls.getConstructor(String.class, Bundle.class);
                                Object credInstance = credCtor.newInstance(GOOGLE_ID_CREDENTIAL_TYPE, credData);

                                Class<?> respCls = Class.forName("android.credentials.GetCredentialResponse");
                                Constructor<?> respCtor = respCls.getConstructor(credCls);
                                Object respInstance = respCtor.newInstance(credInstance);

                                Method onRespM = fCallback.getClass().getMethod("onResponse", respCls);
                                onRespM.setAccessible(true);
                                onRespM.invoke(fCallback, respInstance);
                            } catch (Throwable err) {
                                invokeCredentialCallbackError(fCallback,
                                        "android.credentials.GetCredentialException.TYPE_UNKNOWN",
                                        "Sign-in failed");
                            }
                        }
                    }, "EmbeddedCredMgrMint");
                    t.setDaemon(true);
                    t.start();
                }

                @Override
                public void onCancelled() {
                    invokeCredentialCallbackError(fCallback,
                            "android.credentials.GetCredentialException.TYPE_USER_CANCELED",
                            "User cancelled sign-in");
                }
            });
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void invokeCredentialCallbackError(Object cb, String type, String msg) {
        if (cb == null) return;
        try {
            Method m = cb.getClass().getMethod("onError", String.class, String.class);
            m.setAccessible(true);
            m.invoke(cb, type, msg);
            return;
        } catch (Throwable ignored) {}
        try {
            Method[] methods = cb.getClass().getMethods();
            for (int i = 0; i < methods.length; i++) {
                if ("onError".equals(methods[i].getName())) {
                    Class<?>[] pts = methods[i].getParameterTypes();
                    methods[i].setAccessible(true);
                    if (pts.length == 2) {
                        methods[i].invoke(cb, type, msg);
                        return;
                    } else if (pts.length == 1) {
                        methods[i].invoke(cb, type);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int parseServiceIdFromSafeParcelable(Parcel data) {
        try {
            int startPos = data.dataPosition();
            if (data.dataAvail() < 4) return -1;
            int header = data.readInt();
            int objectTag = header & 0xFFFF;
            if (objectTag != 0x4F45) {
                data.setDataPosition(startPos);
                return -1;
            }
            int objSize = (header >>> 16) & 0xFFFF;
            if (objSize == 0xFFFF) {
                objSize = data.readInt();
            }
            int endPos = Math.min(data.dataSize(), data.dataPosition() + objSize);
            while (data.dataPosition() + 4 <= endPos) {
                int fieldHeader = data.readInt();
                int fieldId = fieldHeader & 0xFFFF;
                int fieldSize = (fieldHeader >>> 16) & 0xFFFF;
                if (fieldSize == 0xFFFF) {
                    if (data.dataPosition() + 4 > endPos) break;
                    fieldSize = data.readInt();
                }
                if (fieldId == 2 && fieldSize == 4 && data.dataPosition() + 4 <= endPos) {
                    return data.readInt();
                }
                int nextPos = data.dataPosition() + fieldSize;
                if (nextPos > endPos || nextPos < data.dataPosition()) break;
                data.setDataPosition(nextPos);
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static void invokeGmsOnPostInitComplete(IBinder callbacksBinder, int statusCode, IBinder serviceBinder, Bundle resolution) {
        if (callbacksBinder == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.google.android.gms.common.internal.IGmsCallbacks");
            data.writeInt(statusCode);
            data.writeStrongBinder(serviceBinder);
            if (resolution != null) {
                data.writeInt(1);
                resolution.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            callbacksBinder.transact(1 /* onPostInitComplete */, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
        } finally {
            try { data.recycle(); } catch (Throwable ignored) {}
            try { reply.recycle(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Calls IGmsCallbacks.onPostInitCompleteWithConnectionInfo (transact code 3) with a valid
     * SafeParcelable ConnectionInfo (zzk) containing the advertised Feature[] array so modern
     * play-services-base 18.x GoogleApiManager allows beginSignIn() and getSignInIntent().
     * Automatically falls back to code 1 if the target app uses an older play-services-basement.
     */
    private static void invokeGmsOnPostInitCompleteWithConnectionInfo(
            IBinder callbacksBinder,
            int statusCode,
            IBinder serviceBinder,
            String[] featureNames
    ) {
        if (callbacksBinder == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        boolean ok = false;
        try {
            data.writeInterfaceToken("com.google.android.gms.common.internal.IGmsCallbacks");
            data.writeInt(statusCode);
            data.writeStrongBinder(serviceBinder);
            data.writeInt(1); // non-null ConnectionInfo (zzk)
            writeSafeParcelConnectionInfo(data, featureNames);
            ok = callbacksBinder.transact(3 /* onPostInitCompleteWithConnectionInfo */, data, reply, 0);
            if (ok) {
                reply.readException();
            }
        } catch (Throwable ignored) {
            ok = false;
        } finally {
            try { data.recycle(); } catch (Throwable ignored) {}
            try { reply.recycle(); } catch (Throwable ignored) {}
        }
        if (!ok) {
            invokeGmsOnPostInitComplete(callbacksBinder, statusCode, serviceBinder, new Bundle());
        }
    }

    private static void writeSafeParcelConnectionInfo(Parcel dest, String[] featureNames) {
        int objHeader = beginSafeParcelObject(dest);
        // Field 1: Bundle zza (resolution / connection info bundle)
        writeSafeParcelBundle(dest, 1, new Bundle());
        // Field 2: Feature[] zzb
        if (featureNames != null && featureNames.length > 0) {
            int fHeader = beginSafeParcelField(dest, 2);
            dest.writeInt(featureNames.length);
            for (int i = 0; i < featureNames.length; i++) {
                int elemSizePos = dest.dataPosition();
                dest.writeInt(0);
                int elemStart = dest.dataPosition();
                writeSafeParcelFeature(dest, featureNames[i], 100L);
                int elemEnd = dest.dataPosition();
                dest.setDataPosition(elemSizePos);
                dest.writeInt(elemEnd - elemStart);
                dest.setDataPosition(elemEnd);
            }
            endSafeParcelField(dest, fHeader);
        }
        // Field 3: int zzc (0)
        dest.writeInt((4 << 16) | 3);
        dest.writeInt(0);
        endSafeParcelObject(dest, objHeader);
    }

    private static void writeSafeParcelFeature(Parcel dest, String name, long version) {
        int objHeader = beginSafeParcelObject(dest);
        writeSafeParcelString(dest, 1, name);
        dest.writeInt((4 << 16) | 2);
        dest.writeInt(-1);
        dest.writeInt((8 << 16) | 3);
        dest.writeLong(version);
        endSafeParcelObject(dest, objHeader);
    }

    private static int beginSafeParcelObject(Parcel dest) {
        int pos = dest.dataPosition();
        dest.writeInt(0xFFFF4F45);
        dest.writeInt(0);
        return pos;
    }

    private static void endSafeParcelObject(Parcel dest, int headerPos) {
        int endPos = dest.dataPosition();
        dest.setDataPosition(headerPos + 4);
        dest.writeInt(endPos - (headerPos + 8));
        dest.setDataPosition(endPos);
    }

    private static int beginSafeParcelField(Parcel dest, int fieldId) {
        int pos = dest.dataPosition();
        dest.writeInt(0xFFFF0000 | (fieldId & 0xFFFF));
        dest.writeInt(0);
        return pos;
    }

    private static void endSafeParcelField(Parcel dest, int headerPos) {
        int endPos = dest.dataPosition();
        dest.setDataPosition(headerPos + 4);
        dest.writeInt(endPos - (headerPos + 8));
        dest.setDataPosition(endPos);
    }

    private static void writeSafeParcelString(Parcel dest, int fieldId, String value) {
        if (value == null) return;
        int pos = beginSafeParcelField(dest, fieldId);
        dest.writeString(value);
        endSafeParcelField(dest, pos);
    }

    private static void writeSafeParcelBundle(Parcel dest, int fieldId, Bundle value) {
        if (value == null) return;
        int pos = beginSafeParcelField(dest, fieldId);
        dest.writeBundle(value);
        endSafeParcelField(dest, pos);
    }

    private static void writeSafeParcelParcelable(Parcel dest, int fieldId, Parcelable value) {
        if (value == null) return;
        int pos = beginSafeParcelField(dest, fieldId);
        value.writeToParcel(dest, 0);
        endSafeParcelField(dest, pos);
    }

    /**
     * Handles real OAuth2 token requests from IAuthManagerService or AccountManager.
     */
    public static Bundle handleAuthGetToken(String email, String scope, Bundle extras) {
        Bundle res = new Bundle();
        if (email == null || email.trim().isEmpty()) {
            email = getPrimaryGoogleEmail();
        }
        if (email == null || email.trim().isEmpty()) {
            res.putString("Error", "NeedsBrowser");
            return res;
        }
        String effectiveScope = (scope != null && !scope.trim().isEmpty())
                ? scope.trim()
                : "oauth2:https://www.googleapis.com/auth/youtube";
        String token = fetchGoogleOAuthTokenSync(email, effectiveScope, extras);
        if (token != null && !token.isEmpty()) {
            res.putString("authtoken", token);
            res.putString(AccountManager.KEY_AUTHTOKEN, token);
            res.putString(AccountManager.KEY_ACCOUNT_NAME, email);
            res.putString(AccountManager.KEY_ACCOUNT_TYPE, GOOGLE_ACCOUNT_TYPE);
            res.putString("Error", "OK");
            res.putLong("expirationTimeSecs", (System.currentTimeMillis() / 1000L) + 3000L);
            return res;
        }
        res.putString("Error", "BadAuthentication");
        return res;
    }

    public static void invalidateCachedToken(String token) {
        if (token == null || token.isEmpty()) return;
        for (Map.Entry<String, CachedToken> e : LIVE_TOKEN_CACHE.entrySet()) {
            if (e.getValue() != null && token.equals(e.getValue().token)) {
                LIVE_TOKEN_CACHE.remove(e.getKey());
            }
        }
        StandaloneRuntimeEngine.clearSandboxedAccountTokenForGms(token);
    }

    /**
     * Mints a genuine Google-signed JWT ID Token (eyJ...) for a 3rd-party or 1st-party app
     * using its real original package name and real original APK SHA-1 certificate.
     */
    public static String fetchGoogleIdTokenSync(String email, String serverClientId, String nonce) {
        if (email == null || email.trim().isEmpty()) return null;
        if (serverClientId == null || serverClientId.trim().isEmpty()) {
            return fetchGoogleOAuthTokenSync(email, "oauth2:openid email profile", null);
        }
        String scope = "audience:server:client_id:" + serverClientId.trim();
        Bundle extras = new Bundle();
        if (nonce != null && !nonce.trim().isEmpty()) {
            extras.putString("nonce", nonce.trim());
        }
        String tok = fetchGoogleOAuthTokenSync(email, scope, extras);
        if (tok != null && !tok.isEmpty()) {
            return tok;
        }
        // Fallback: try with space-separated openid audience if needed
        return fetchGoogleOAuthTokenSync(email, "oauth2:server:client_id:" + serverClientId.trim() + ":api_scope:openid email profile", extras);
    }

    public static String fetchGoogleServerAuthCodeSync(String email, String serverClientId, List<String> scopes) {
        if (email == null || email.trim().isEmpty() || serverClientId == null || serverClientId.trim().isEmpty()) {
            return null;
        }
        StringBuilder scSb = new StringBuilder();
        if (scopes != null && !scopes.isEmpty()) {
            for (int i = 0; i < scopes.size(); i++) {
                String s = scopes.get(i);
                if (s == null || s.trim().isEmpty()) continue;
                if (scSb.length() > 0) scSb.append(' ');
                scSb.append(s.trim());
            }
        }
        if (scSb.length() == 0) {
            scSb.append("openid email profile");
        }
        String scope = "oauth2:server:client_id:" + serverClientId.trim() + ":api_scope:" + scSb.toString();
        return fetchGoogleOAuthTokenSync(email, scope, null);
    }

    /**
     * Exchanges the stored Google Master Token (aas_et/...) at https://android.clients.google.com/auth
     * for a real per-scope OAuth2 Access Token (ya29.*), Google JWT ID Token (eyJ...), or Play Store token.
     */
    public static String fetchGoogleOAuthTokenSync(String email, String scope, Bundle extras) {
        if (email == null || email.trim().isEmpty()) return null;
        if (scope == null || scope.trim().isEmpty()) {
            scope = "oauth2:https://www.googleapis.com/auth/youtube";
        }
        String cacheKey = email.toLowerCase(Locale.US) + "|" + scope;
        CachedToken cached = LIVE_TOKEN_CACHE.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMs > now && cached.token != null && !cached.token.isEmpty()) {
            return cached.token;
        }

        // Check persisted token store first (skip persisted ID tokens when a fresh nonce is provided)
        boolean hasNonce = (extras != null && extras.getString("nonce") != null && !extras.getString("nonce").isEmpty());
        if (!hasNonce) {
            String persistedTok = StandaloneRuntimeEngine.getSandboxedAccountTokenForGms(email, GOOGLE_ACCOUNT_TYPE, scope);
            if (persistedTok != null && persistedTok.length() > 24 &&
                    (persistedTok.startsWith("ya29.") || persistedTok.startsWith("eyJ"))) {
                LIVE_TOKEN_CACHE.put(cacheKey, new CachedToken(persistedTok, now + 15L * 60L * 1000L));
                return persistedTok;
            }
        }

        String masterToken = StandaloneRuntimeEngine.getSandboxedAccountPasswordForGms(email, GOOGLE_ACCOUNT_TYPE);
        if (masterToken == null || masterToken.trim().isEmpty()) {
            return null;
        }

        String origPkg = StandaloneRuntimeEngine.getOriginalPackageNameForGms();
        if (origPkg == null || origPkg.isEmpty()) {
            origPkg = GMS_PACKAGE;
        }
        if (extras != null) {
            String extPkg = extras.getString("androidPackageName");
            if (extPkg != null && !extPkg.isEmpty() && !extPkg.contains(".ghostdevice") && !extPkg.contains(".phantom")) {
                origPkg = extPkg;
            }
        }
        String clientSig = getOfficialSha1ForPackage(origPkg);
        String gsfHex = StandaloneRuntimeEngine.getSpoofedGsfIdHexForGms();

        try {
            Map<String, String> params = new HashMap<String, String>();
            params.put("accountType", "HOSTED_OR_GOOGLE");
            params.put("Email", email);
            params.put("Token", masterToken);
            params.put("has_permission", "1");
            params.put("service", scope);
            params.put("source", "android");
            if (gsfHex != null && !gsfHex.isEmpty()) {
                params.put("androidId", gsfHex);
            }
            params.put("app", origPkg);
            params.put("client_sig", clientSig);
            params.put("callerPkg", GMS_PACKAGE);
            params.put("callerSig", GMS_OFFICIAL_SHA1);
            params.put("device_country", "us");
            params.put("operatorCountry", "us");
            params.put("lang", "en-US");
            params.put("sdk_version", String.valueOf(Math.min(Build.VERSION.SDK_INT, 34)));
            params.put("google_play_services_version", "244738038");
            if (hasNonce) {
                params.put("nonce", extras.getString("nonce"));
            }

            Map<String, String> resp = postGoogleAuthForm(params);
            String auth = resp.get("Auth");
            if ((auth == null || auth.isEmpty()) && !GMS_OFFICIAL_SHA1.equals(clientSig) && isGoogleFirstPartyPackage(origPkg)) {
                // Retry with Google GMS signature for 1st-party Google apps if app-specific SHA-1 was rejected
                params.put("client_sig", GMS_OFFICIAL_SHA1);
                resp = postGoogleAuthForm(params);
                auth = resp.get("Auth");
            }
            if (auth == null || auth.isEmpty()) {
                // Retry without androidId in case Google rejected an unregistered spoofed GSF ID
                params.remove("androidId");
                params.put("client_sig", clientSig);
                resp = postGoogleAuthForm(params);
                auth = resp.get("Auth");
            }
            if (auth != null && !auth.trim().isEmpty()) {
                String cleanTok = auth.trim();
                LIVE_TOKEN_CACHE.put(cacheKey, new CachedToken(cleanTok, now + TOKEN_CACHE_TTL_MS));
                if (!hasNonce) {
                    StandaloneRuntimeEngine.putSandboxedAccountTokenForGms(email, GOOGLE_ACCOUNT_TYPE, scope, cleanTok);
                }
                return cleanTok;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void fetchGoogleOAuthTokenAsync(
            final String email,
            final String scope,
            final Bundle extras,
            final Object[] accountManagerArgs
    ) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String tok = fetchGoogleOAuthTokenSync(email, scope, extras);
                if (tok != null && !tok.isEmpty()) {
                    Bundle b = new Bundle();
                    b.putString(AccountManager.KEY_ACCOUNT_NAME, email);
                    b.putString(AccountManager.KEY_ACCOUNT_TYPE, GOOGLE_ACCOUNT_TYPE);
                    b.putString(AccountManager.KEY_AUTHTOKEN, tok);
                    StandaloneRuntimeEngine.invokeAccountManagerOnResultForGms(accountManagerArgs, b);
                } else {
                    StandaloneRuntimeEngine.invokeAccountManagerOnErrorForGms(
                            accountManagerArgs,
                            AccountManager.ERROR_CODE_INVALID_RESPONSE,
                            "Auth token unavailable"
                    );
                }
            }
        }, "EmbeddedGmsAuthTok");
        t.setDaemon(true);
        t.start();
    }

    private static Map<String, String> postGoogleAuthForm(Map<String, String> params) {
        Map<String, String> out = new HashMap<String, String>();
        HttpURLConnection conn = null;
        try {
            StringBuilder body = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (!first) body.append('&');
                first = false;
                body.append(URLEncoder.encode(e.getKey(), "UTF-8"));
                body.append('=');
                body.append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(GOOGLE_AUTH_ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            // CRITICAL: Do NOT advertise "; gzip" in User-Agent and request identity encoding
            // (while still decoding GZIP defensively below if server sends gzip anyway!)
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "GoogleAuth/1.4 (" + StandaloneRuntimeEngine.getSpoofedDeviceModelForGms() + " UP1A.231005.007)");
            conn.setRequestProperty("app", params.get("app") != null ? params.get("app") : GMS_PACKAGE);
            String dev = params.get("androidId");
            if (dev != null && !dev.isEmpty()) {
                conn.setRequestProperty("device", dev);
            }

            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream rawIs = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (rawIs != null) {
                InputStream is = rawIs;
                String enc = conn.getContentEncoding();
                if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) {
                    is = new GZIPInputStream(rawIs);
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
                br.close();
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    /**
     * Backward-compatible entry point (defaults to Google 1st-party mode).
     */
    public static boolean tryHandleGoogleSignInActivityIntent(Intent intent, final Object[] startActivityArgs) {
        return tryHandleGoogleSignInActivityIntent(intent, startActivityArgs, true);
    }

    /**
     * Intercepts Google Sign-In / Add-Account Activity Intents across ALL built APKs:
     * - Classic GoogleSignInClient (SignInHubActivity / com.google.android.gms.auth.GOOGLE_SIGN_IN)
     * - Google Identity Authorization / UIFlows / Account Pickers
     * - YouTube / Play Store Add-Account & Choose-Account flows
     */
    public static boolean tryHandleGoogleSignInActivityIntent(
            final Intent intent,
            final Object[] startActivityArgs,
            final boolean isGoogle1p
    ) {
        if (intent == null) return false;
        String act = intent.getAction();
        ComponentName cn = intent.getComponent();
        String actLower = (act != null) ? act.toLowerCase(Locale.US) : "";
        String clsLower = (cn != null && cn.getClassName() != null) ? cn.getClassName().toLowerCase(Locale.US) : "";
        String pkgLower = (intent.getPackage() != null)
                ? intent.getPackage().toLowerCase(Locale.US)
                : ((cn != null && cn.getPackageName() != null) ? cn.getPackageName().toLowerCase(Locale.US) : "");

        final boolean isClassicGoogleSignIn =
                "com.google.android.gms.auth.google_sign_in".equals(actLower)
                || "com.google.android.gms.auth.ap_sign_in".equals(actLower)
                || clsLower.endsWith(".signinhubactivity")
                || clsLower.contains("gms.auth.api.signin.internal.signinhubactivity");

        final boolean isEmbeddedIdentityAction =
                ACTION_EMBEDDED_IDENTITY_SIGNIN.equalsIgnoreCase(act);

        boolean isGeneralGoogleAccountFlow =
                actLower.contains("gms.auth.uiflows")
                || actLower.contains("common.account.choose_account")
                || actLower.contains("auth.api.identity")
                || actLower.contains("auth.api.credentials.picker")
                || actLower.contains("account_picker")
                || actLower.contains("chooseaccount")
                || actLower.contains("accountpicker")
                || actLower.contains("google_account")
                || clsLower.contains("choosetypeandaccount")
                || clsLower.contains("chooseaccount")
                || clsLower.contains("accountpicker")
                || clsLower.contains("assisted_signin")
                || clsLower.contains("accountintroactivity")
                || (GMS_PACKAGE.equals(pkgLower) && (actLower.contains("auth") || actLower.contains("signin")
                        || clsLower.contains("auth") || clsLower.contains("signin")))
                || (isGoogle1p && (
                        "android.settings.add_account_settings".equals(actLower)
                        || actLower.contains("addaccount")
                        || clsLower.contains("addaccount")));

        if (!isClassicGoogleSignIn && !isEmbeddedIdentityAction && !isGeneralGoogleAccountFlow) {
            return false;
        }

        final Activity hostAct = StandaloneRuntimeEngine.getForegroundActivityForGms();
        if (hostAct == null || hostAct.isFinishing()) {
            return false;
        }

        if (isClassicGoogleSignIn) {
            final ParsedGoogleSignInOptions gso = extractGoogleSignInOptions(intent);
            showGoogleSignInDialog(hostAct, new SignInCallback() {
                @Override
                public void onSuccess(final String email) {
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                String idToken = null;
                                String serverAuthCode = null;
                                if (gso.serverClientId != null && !gso.serverClientId.isEmpty()) {
                                    if (gso.idTokenRequested || !gso.serverAuthCodeRequested) {
                                        idToken = fetchGoogleIdTokenSync(email, gso.serverClientId, null);
                                    }
                                    if (gso.serverAuthCodeRequested) {
                                        serverAuthCode = fetchGoogleServerAuthCodeSync(email, gso.serverClientId, gso.scopes);
                                    }
                                }
                                Intent resultIntent = buildClassicGoogleSignInResultIntent(
                                        hostAct.getClassLoader(), email, idToken, serverAuthCode, gso.scopes);
                                StandaloneRuntimeEngine.dispatchActivityResultForGms(
                                        startActivityArgs,
                                        (resultIntent != null ? Activity.RESULT_OK : Activity.RESULT_CANCELED),
                                        resultIntent
                                );
                            } catch (Throwable ignored) {
                                StandaloneRuntimeEngine.dispatchActivityResultForGms(
                                        startActivityArgs, Activity.RESULT_CANCELED, null);
                            }
                        }
                    }, "EmbeddedClassicSignInMint");
                    t.setDaemon(true);
                    t.start();
                }

                @Override
                public void onCancelled() {
                    StandaloneRuntimeEngine.dispatchActivityResultForGms(
                            startActivityArgs, Activity.RESULT_CANCELED, null);
                }
            });
            return true;
        }

        if (isEmbeddedIdentityAction) {
            final String clientId = intent.getStringExtra("serverClientId");
            final String nonce = intent.getStringExtra("nonce");
            showGoogleSignInDialog(hostAct, new SignInCallback() {
                @Override
                public void onSuccess(final String email) {
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            String idToken = fetchGoogleIdTokenSync(email, clientId, nonce);
                            Intent resultIntent = buildIdentitySignInResultIntent(email, idToken);
                            StandaloneRuntimeEngine.dispatchActivityResultForGms(
                                    startActivityArgs,
                                    (resultIntent != null ? Activity.RESULT_OK : Activity.RESULT_CANCELED),
                                    resultIntent
                            );
                        }
                    }, "EmbeddedIdentityActMint");
                    t.setDaemon(true);
                    t.start();
                }

                @Override
                public void onCancelled() {
                    StandaloneRuntimeEngine.dispatchActivityResultForGms(
                            startActivityArgs, Activity.RESULT_CANCELED, null);
                }
            });
            return true;
        }

        showGoogleSignInDialog(hostAct, new SignInCallback() {
            @Override
            public void onSuccess(String email) {
                Intent data = new Intent();
                data.putExtra(AccountManager.KEY_ACCOUNT_NAME, email);
                data.putExtra(AccountManager.KEY_ACCOUNT_TYPE, GOOGLE_ACCOUNT_TYPE);
                StandaloneRuntimeEngine.dispatchActivityResultForGms(startActivityArgs, Activity.RESULT_OK, data);
            }

            @Override
            public void onCancelled() {
                StandaloneRuntimeEngine.dispatchActivityResultForGms(startActivityArgs, Activity.RESULT_CANCELED, null);
            }
        });
        return true;
    }

    private static ParsedGoogleSignInOptions extractGoogleSignInOptions(Intent intent) {
        ParsedGoogleSignInOptions out = new ParsedGoogleSignInOptions();
        if (intent == null) return out;
        try {
            Object gsoObj = null;
            Bundle configBundle = intent.getBundleExtra("config");
            if (configBundle != null) {
                Object cfgObj = configBundle.getParcelable("config");
                if (cfgObj != null) {
                    gsoObj = extractFieldByTypeName(cfgObj, "GoogleSignInOptions");
                }
            }
            if (gsoObj == null) {
                try {
                    Object rawCfg = intent.getParcelableExtra("config");
                    if (rawCfg != null) {
                        if (rawCfg.getClass().getName().contains("GoogleSignInOptions")) {
                            gsoObj = rawCfg;
                        } else {
                            gsoObj = extractFieldByTypeName(rawCfg, "GoogleSignInOptions");
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (gsoObj == null) {
                try {
                    gsoObj = intent.getParcelableExtra("googleSignInOptions");
                } catch (Throwable ignored) {}
            }
            if (gsoObj != null) {
                // Read via public getters or fields (works even when R8-obfuscated!)
                try {
                    Method m = gsoObj.getClass().getMethod("getServerClientId");
                    Object v = m.invoke(gsoObj);
                    if (v instanceof String) out.serverClientId = (String) v;
                } catch (Throwable ignored) {}
                try {
                    Method m = gsoObj.getClass().getMethod("isIdTokenRequested");
                    Object v = m.invoke(gsoObj);
                    if (v instanceof Boolean) out.idTokenRequested = (Boolean) v;
                } catch (Throwable ignored) {}
                try {
                    Method m = gsoObj.getClass().getMethod("isServerAuthCodeRequested");
                    Object v = m.invoke(gsoObj);
                    if (v instanceof Boolean) out.serverAuthCodeRequested = (Boolean) v;
                } catch (Throwable ignored) {}
                try {
                    Method m = gsoObj.getClass().getMethod("getScopes");
                    Object v = m.invoke(gsoObj);
                    if (v instanceof Collection) {
                        for (Object sc : (Collection<?>) v) {
                            if (sc != null) out.scopes.add(sc.toString());
                        }
                    }
                } catch (Throwable ignored) {}

                // R8 fallback: inspect declared fields of GoogleSignInOptions directly
                Class<?> c = gsoObj.getClass();
                while (c != null && c != Object.class) {
                    Field[] fields = c.getDeclaredFields();
                    for (int i = 0; i < fields.length; i++) {
                        fields[i].setAccessible(true);
                        Object fv = fields[i].get(gsoObj);
                        if (fv instanceof String) {
                            String s = (String) fv;
                            if (s.contains(".apps.googleusercontent.com") && out.serverClientId.isEmpty()) {
                                out.serverClientId = s;
                                out.idTokenRequested = true;
                            }
                        } else if (fv instanceof Collection && out.scopes.isEmpty()) {
                            for (Object item : (Collection<?>) fv) {
                                if (item != null && item.getClass().getName().contains("Scope")) {
                                    out.scopes.add(item.toString());
                                }
                            }
                        }
                    }
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
        if (out.scopes.isEmpty()) {
            out.scopes.add("openid");
            out.scopes.add("email");
            out.scopes.add("profile");
        }
        return out;
    }

    private static Object extractFieldByTypeName(Object obj, String simpleNamePart) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            Field[] fields = c.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                try {
                    fields[i].setAccessible(true);
                    Object v = fields[i].get(obj);
                    if (v != null && v.getClass().getName().contains(simpleNamePart)) {
                        return v;
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * Constructs a genuine target-app GoogleSignInAccount instance and wraps it into the
     * exact Intent extra ("googleSignInAccount") expected by GoogleSignIn.getSignedInAccountFromIntent().
     */
    private static Intent buildClassicGoogleSignInResultIntent(
            ClassLoader appCl,
            String email,
            String idToken,
            String serverAuthCode,
            List<String> scopes
    ) {
        try {
            ClassLoader cl = (appCl != null) ? appCl : EmbeddedGmsCore.class.getClassLoader();
            Map<String, String> claims = decodeJwtClaims(idToken);
            String userId = firstNonEmpty(claims.get("sub"), String.valueOf(Math.abs(email.hashCode())));
            String displayName = firstNonEmpty(claims.get("name"), email.contains("@") ? email.substring(0, email.indexOf('@')) : email);
            String givenName = firstNonEmpty(claims.get("given_name"), displayName);
            String familyName = firstNonEmpty(claims.get("family_name"), "");
            String photoUrl = firstNonEmpty(claims.get("picture"), "");
            long expSecs = (System.currentTimeMillis() / 1000L) + 3500L;
            String obfId = "ghost_" + Integer.toHexString(email.toLowerCase(Locale.US).hashCode());

            JSONObject json = new JSONObject();
            json.put("id", userId);
            if (idToken != null && !idToken.isEmpty()) json.put("tokenId", idToken);
            json.put("email", email);
            json.put("displayName", displayName);
            if (!givenName.isEmpty()) json.put("givenName", givenName);
            if (!familyName.isEmpty()) json.put("familyName", familyName);
            if (!photoUrl.isEmpty()) json.put("photoUrl", photoUrl);
            if (serverAuthCode != null && !serverAuthCode.isEmpty()) json.put("serverAuthCode", serverAuthCode);
            json.put("expirationTime", expSecs);
            json.put("obfuscatedIdentifier", obfId);
            JSONArray scopeArr = new JSONArray();
            if (scopes != null && !scopes.isEmpty()) {
                for (int i = 0; i < scopes.size(); i++) {
                    scopeArr.put(scopes.get(i));
                }
            } else {
                scopeArr.put("openid");
                scopeArr.put("email");
                scopeArr.put("profile");
            }
            json.put("grantedScopes", scopeArr);

            Class<?> accCls = Class.forName("com.google.android.gms.auth.api.signin.GoogleSignInAccount", false, cl);
            Object accountObj = null;

            // 1. Prefer GoogleSignInAccount's built-in static JSON parser (e.g. zab(String) / fromJsonString(String))
            Method[] methods = accCls.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getReturnType() == accCls
                        && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == String.class) {
                    try {
                        m.setAccessible(true);
                        accountObj = m.invoke(null, json.toString());
                        if (accountObj != null) break;
                    } catch (Throwable ignored) {}
                }
            }

            // 2. Fallback: direct 12-arg SafeParcelable constructor
            if (accountObj == null) {
                Class<?> scopeCls = Class.forName("com.google.android.gms.common.api.Scope", false, cl);
                Constructor<?> scopeCtor = scopeCls.getConstructor(String.class);
                List<Object> scopeList = new ArrayList<Object>();
                for (int i = 0; i < scopeArr.length(); i++) {
                    scopeList.add(scopeCtor.newInstance(scopeArr.getString(i)));
                }
                Constructor<?>[] ctors = accCls.getDeclaredConstructors();
                for (int i = 0; i < ctors.length; i++) {
                    Class<?>[] pts = ctors[i].getParameterTypes();
                    if (pts.length == 12 && pts[0] == int.class && pts[1] == String.class) {
                        ctors[i].setAccessible(true);
                        accountObj = ctors[i].newInstance(
                                Integer.valueOf(3),
                                userId,
                                idToken,
                                email,
                                displayName,
                                !photoUrl.isEmpty() ? Uri.parse(photoUrl) : null,
                                serverAuthCode,
                                Long.valueOf(expSecs),
                                obfId,
                                scopeList,
                                givenName,
                                familyName
                        );
                        break;
                    }
                }
            }

            if (accountObj instanceof Parcelable) {
                Intent res = new Intent();
                res.putExtra("googleSignInAccount", (Parcelable) accountObj);
                return res;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Constructs a genuine target-app com.google.android.gms.auth.api.identity.SignInCredential
     * serialized into Intent extra "sign_in_credential" (SafeParcelable byte array).
     */
    private static Intent buildIdentitySignInResultIntent(String email, String idToken) {
        try {
            Activity act = StandaloneRuntimeEngine.getForegroundActivityForGms();
            ClassLoader cl = (act != null) ? act.getClassLoader() : EmbeddedGmsCore.class.getClassLoader();
            Map<String, String> claims = decodeJwtClaims(idToken);
            String displayName = firstNonEmpty(claims.get("name"), email.contains("@") ? email.substring(0, email.indexOf('@')) : email);
            String givenName = firstNonEmpty(claims.get("given_name"), displayName);
            String familyName = firstNonEmpty(claims.get("family_name"), "");
            String photoUrl = firstNonEmpty(claims.get("picture"), "");
            Uri photoUri = !photoUrl.isEmpty() ? Uri.parse(photoUrl) : null;

            Class<?> credCls = Class.forName("com.google.android.gms.auth.api.identity.SignInCredential", false, cl);
            Object credObj = null;
            Constructor<?>[] ctors = credCls.getDeclaredConstructors();
            for (int i = 0; i < ctors.length; i++) {
                Class<?>[] pts = ctors[i].getParameterTypes();
                ctors[i].setAccessible(true);
                if (pts.length == 9 && pts[0] == String.class) {
                    credObj = ctors[i].newInstance(
                            email, displayName, givenName, familyName, photoUri, null, idToken, null, null);
                    break;
                } else if (pts.length == 8 && pts[0] == String.class) {
                    credObj = ctors[i].newInstance(
                            email, displayName, givenName, familyName, photoUri, null, idToken, null);
                    break;
                } else if (pts.length == 7 && pts[0] == String.class) {
                    credObj = ctors[i].newInstance(
                            email, displayName, givenName, familyName, photoUri, null, idToken);
                    break;
                }
            }
            if (credObj instanceof Parcelable) {
                Parcel p = Parcel.obtain();
                try {
                    ((Parcelable) credObj).writeToParcel(p, 0);
                    byte[] bytes = p.marshall();
                    Intent res = new Intent();
                    res.putExtra("sign_in_credential", bytes);
                    return res;
                } finally {
                    p.recycle();
                }
            }
        } catch (Throwable ignored) {}

        // Universal SafeParcelable wire-format fallback (immune to R8 obfuscation or newer constructor arities!)
        try {
            Map<String, String> claims = decodeJwtClaims(idToken);
            String displayName = firstNonEmpty(claims.get("name"), email.contains("@") ? email.substring(0, email.indexOf('@')) : email);
            String givenName = firstNonEmpty(claims.get("given_name"), displayName);
            String familyName = firstNonEmpty(claims.get("family_name"), "");
            String photoUrl = firstNonEmpty(claims.get("picture"), "");
            Uri photoUri = !photoUrl.isEmpty() ? Uri.parse(photoUrl) : null;

            Parcel p = Parcel.obtain();
            try {
                int objHeader = beginSafeParcelObject(p);
                writeSafeParcelString(p, 1, email);
                writeSafeParcelString(p, 2, displayName);
                writeSafeParcelString(p, 3, givenName);
                if (!familyName.isEmpty()) writeSafeParcelString(p, 4, familyName);
                if (photoUri != null) writeSafeParcelParcelable(p, 5, photoUri);
                if (idToken != null && !idToken.isEmpty()) writeSafeParcelString(p, 7, idToken);
                endSafeParcelObject(p, objHeader);
                byte[] bytes = p.marshall();
                Intent res = new Intent();
                res.putExtra("sign_in_credential", bytes);
                return res;
            } finally {
                p.recycle();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Map<String, String> decodeJwtClaims(String jwt) {
        Map<String, String> out = new HashMap<String, String>();
        if (jwt == null || jwt.isEmpty()) return out;
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return out;
            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject obj = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            String[] keys = new String[]{"sub", "email", "name", "given_name", "family_name", "picture"};
            for (int i = 0; i < keys.length; i++) {
                String v = obj.optString(keys[i], "");
                if (v != null && !v.isEmpty()) {
                    out.put(keys[i], v);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b != null ? b.trim() : "";
    }

    /**
     * Opens the Embedded GMS Google Sign-In dialog with 1-tap quick continue (if already signed in)
     * plus Google's official Android EmbeddedSetup WebView for adding/signing into any Gmail account.
     */
    public static void showGoogleSignInDialog(final Context rawCtx, final SignInCallback callback) {
        final Activity hostActivity = (rawCtx instanceof Activity)
                ? (Activity) rawCtx
                : StandaloneRuntimeEngine.getForegroundActivityForGms();
        if (hostActivity == null || hostActivity.isFinishing()) {
            if (callback != null) callback.onCancelled();
            return;
        }

        hostActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Dialog existing = sActiveSignInDialogRef.get();
                    if (existing != null && existing.isShowing()) {
                        existing.dismiss();
                    }
                } catch (Throwable ignored) {}

                try {
                    final DisplayMetrics dm = hostActivity.getResources().getDisplayMetrics();
                    final float dp = dm.density;
                    final AtomicBoolean completed = new AtomicBoolean(false);
                    final String[] capturedEmail = new String[]{""};
                    Dialog builtDialog;
                    try {
                        builtDialog = new Dialog(hostActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                    } catch (Throwable ignoredTheme) {
                        builtDialog = new Dialog(hostActivity);
                    }
                    final Dialog dialog = builtDialog;
                    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                    dialog.setCancelable(true);

                    LinearLayout root = new LinearLayout(hostActivity);
                    root.setOrientation(LinearLayout.VERTICAL);
                    root.setBackgroundColor(Color.parseColor("#0D1117"));

                    // Top Bar
                    LinearLayout topBar = new LinearLayout(hostActivity);
                    topBar.setOrientation(LinearLayout.HORIZONTAL);
                    topBar.setGravity(Gravity.CENTER_VERTICAL);
                    topBar.setBackgroundColor(Color.parseColor("#161B22"));
                    int pH = Math.round(12f * dp);
                    int pV = Math.round(10f * dp);
                    topBar.setPadding(pH, pV, pH, pV);

                    LinearLayout titleCol = new LinearLayout(hostActivity);
                    titleCol.setOrientation(LinearLayout.VERTICAL);
                    TextView titleTv = new TextView(hostActivity);
                    titleTv.setText("G  Google Play Services Sign-In");
                    titleTv.setTextColor(Color.WHITE);
                    titleTv.setTextSize(14f);
                    titleTv.setTypeface(Typeface.DEFAULT_BOLD);
                    titleCol.addView(titleTv);

                    final TextView subTv = new TextView(hostActivity);
                    final Account[] existingAccounts = StandaloneRuntimeEngine.getSandboxedGoogleAccounts();
                    String currentEmail = (existingAccounts != null && existingAccounts.length > 0 && existingAccounts[0] != null)
                            ? existingAccounts[0].name : null;
                    subTv.setText(currentEmail != null
                            ? ("Signed in: " + currentEmail)
                            : "Embedded GMS • Isolated Gmail Sign-In");
                    subTv.setTextColor(currentEmail != null ? Color.parseColor("#69F0AE") : Color.parseColor("#8B949E"));
                    subTv.setTextSize(11f);
                    titleCol.addView(subTv);

                    topBar.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    if (currentEmail != null) {
                        TextView outBtn = makeSmallPillButton(hostActivity, dp, "Sign Out", "#3B1219", "#FF5252");
                        outBtn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                StandaloneRuntimeEngine.clearAllSandboxedGoogleAccountsForGms();
                                LIVE_TOKEN_CACHE.clear();
                                try {
                                    CookieManager.getInstance().removeAllCookies(null);
                                    CookieManager.getInstance().flush();
                                } catch (Throwable ignored) {}
                                subTv.setText("Signed out • Sign in below");
                                subTv.setTextColor(Color.parseColor("#FFB300"));
                                try {
                                    Toast.makeText(hostActivity, "Google account signed out", Toast.LENGTH_SHORT).show();
                                } catch (Throwable ignored) {}
                            }
                        });
                        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(30f * dp));
                        outLp.rightMargin = Math.round(6f * dp);
                        topBar.addView(outBtn, outLp);
                    }

                    TextView closeBtn = makeSmallPillButton(hostActivity, dp, "✕", "#21262D", "#8B949E");
                    closeBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try { dialog.dismiss(); } catch (Throwable ignored) {}
                        }
                    });
                    topBar.addView(closeBtn, new LinearLayout.LayoutParams(
                            Math.round(32f * dp), Math.round(30f * dp)));
                    root.addView(topBar, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    // Quick 1-Tap Account Strip if already signed in to 1+ sandboxed Gmail accounts
                    if (existingAccounts != null && existingAccounts.length > 0) {
                        LinearLayout quickBox = new LinearLayout(hostActivity);
                        quickBox.setOrientation(LinearLayout.VERTICAL);
                        quickBox.setBackgroundColor(Color.parseColor("#111822"));
                        quickBox.setPadding(pH, Math.round(8f * dp), pH, Math.round(8f * dp));
                        for (int idx = 0; idx < existingAccounts.length; idx++) {
                            final Account acc = existingAccounts[idx];
                            if (acc == null || acc.name == null || acc.name.isEmpty()) continue;
                            TextView accBtn = makeSmallPillButton(
                                    hostActivity, dp,
                                    "✓  Continue as " + acc.name,
                                    "#0F3B2E", "#00E676"
                            );
                            accBtn.setTextSize(12.5f);
                            accBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (completed.compareAndSet(false, true)) {
                                        try { dialog.dismiss(); } catch (Throwable ignored) {}
                                        if (callback != null) callback.onSuccess(acc.name);
                                    }
                                }
                            });
                            LinearLayout.LayoutParams accLp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(42f * dp));
                            if (idx > 0) accLp.topMargin = Math.round(6f * dp);
                            quickBox.addView(accBtn, accLp);
                        }
                        root.addView(quickBox, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    }

                    // Progress indicator
                    final ProgressBar progressBar = new ProgressBar(hostActivity, null, android.R.attr.progressBarStyleHorizontal);
                    progressBar.setMax(100);
                    progressBar.setIndeterminate(true);
                    root.addView(progressBar, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.round(3f * dp)));

                    // WebView container (fallback to applicationContext if Activity theme/resources reject WebView inflation)
                    FrameLayout webHolder = new FrameLayout(hostActivity);
                    WebView createdWebView;
                    try {
                        createdWebView = new WebView(hostActivity);
                    } catch (Throwable ignoredWv) {
                        createdWebView = new WebView(hostActivity.getApplicationContext());
                    }
                    final WebView webView = createdWebView;
                    webView.setBackgroundColor(Color.WHITE);

                    WebSettings ws = webView.getSettings();
                    ws.setJavaScriptEnabled(true);
                    ws.setDomStorageEnabled(true);
                    ws.setDatabaseEnabled(true);
                    ws.setUseWideViewPort(true);
                    ws.setLoadWithOverviewMode(true);
                    ws.setSupportMultipleWindows(false);
                    ws.setJavaScriptCanOpenWindowsAutomatically(true);
                    String defUa = ws.getUserAgentString();
                    String cleanUa = (defUa != null && !defUa.isEmpty())
                            ? defUa.replace("; wv)", ")").replace("Version/4.0 ", "")
                            : "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36";
                    ws.setUserAgentString(cleanUa);

                    try {
                        CookieManager cm = CookieManager.getInstance();
                        cm.setAcceptCookie(true);
                        if (Build.VERSION.SDK_INT >= 21) {
                            cm.setAcceptThirdPartyCookies(webView, true);
                        }
                    } catch (Throwable ignored) {}

                    final Runnable checkForOAuthCookie = new Runnable() {
                        @Override
                        public void run() {
                            if (completed.get()) return;
                            try {
                                String cookies = CookieManager.getInstance().getCookie("https://accounts.google.com");
                                String oauthToken = extractCookieValue(cookies, "oauth_token");
                                if (oauthToken != null && oauthToken.length() > 12) {
                                    if (!completed.compareAndSet(false, true)) return;
                                    final String fOauthToken = oauthToken;
                                    final String fHintEmail = capturedEmail[0];
                                    subTv.setText("Completing Google Sign-In...");
                                    subTv.setTextColor(Color.parseColor("#80E8FF"));
                                    exchangeOAuthTokenForMasterTokenAsync(
                                            hostActivity,
                                            fOauthToken,
                                            fHintEmail,
                                            dialog,
                                            subTv,
                                            completed,
                                            callback
                                    );
                                }
                            } catch (Throwable ignored) {}
                        }
                    };

                    // Official Google EmbeddedSetup JavaScript bridge ("mm")
                    webView.addJavascriptInterface(new Object() {
                        @JavascriptInterface
                        public void setAccountIdentifier(String identifier) {
                            if (identifier != null && identifier.contains("@")) {
                                capturedEmail[0] = identifier.trim();
                            }
                        }

                        @JavascriptInterface
                        public void showView() {}

                        @JavascriptInterface
                        public void closeView() {
                            hostActivity.runOnUiThread(checkForOAuthCookie);
                        }

                        @JavascriptInterface
                        public void notifyOnTermsOfServiceAccepted() {
                            hostActivity.runOnUiThread(checkForOAuthCookie);
                        }

                        @JavascriptInterface
                        public String getAndroidId() {
                            return StandaloneRuntimeEngine.getSpoofedGsfIdHexForGms();
                        }

                        @JavascriptInterface
                        public String getDeviceName() {
                            return StandaloneRuntimeEngine.getSpoofedDeviceModelForGms();
                        }

                        @JavascriptInterface
                        public void setBackButtonEnabled(boolean enabled) {}

                        @JavascriptInterface
                        public boolean isUserOwner() {
                            return true;
                        }

                        @JavascriptInterface
                        public void log(String msg) {}

                        @JavascriptInterface
                        public void skipLogin() {
                            hostActivity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    try { dialog.dismiss(); } catch (Throwable ignored) {}
                                }
                            });
                        }
                    }, "mm");

                    webView.setWebChromeClient(new WebChromeClient() {
                        @Override
                        public void onProgressChanged(WebView view, int newProgress) {
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(newProgress);
                            progressBar.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
                        }
                    });

                    webView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            super.onPageFinished(view, url);
                            checkForOAuthCookie.run();
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                            checkForOAuthCookie.run();
                            return false;
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, String url) {
                            checkForOAuthCookie.run();
                            return false;
                        }
                    });

                    webHolder.addView(webView, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    root.addView(webHolder, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

                    dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(android.content.DialogInterface d) {
                            try {
                                webView.stopLoading();
                                webView.destroy();
                            } catch (Throwable ignored) {}
                            if (completed.compareAndSet(false, true)) {
                                if (callback != null) callback.onCancelled();
                            }
                        }
                    });

                    dialog.setContentView(root);
                    Window w = dialog.getWindow();
                    if (w != null) {
                        w.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#0D1117")));
                        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
                    }
                    dialog.show();
                    if (w != null && w.peekDecorView() != null) {
                        w.peekDecorView().setTag("phantom_internal_dialog_decor");
                    }
                    sActiveSignInDialogRef = new WeakReference<Dialog>(dialog);

                    webView.loadUrl(EMBEDDED_SETUP_URL);
                } catch (Throwable t) {
                    try {
                        Toast.makeText(hostActivity, "Google Sign-In UI error: " + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignoredToast) {}
                    if (callback != null) callback.onCancelled();
                }
            }
        });
    }

    private static void exchangeOAuthTokenForMasterTokenAsync(
            final Activity hostActivity,
            final String oauthToken,
            final String hintEmail,
            final Dialog dialog,
            final TextView subTv,
            final AtomicBoolean completed,
            final SignInCallback callback
    ) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, String> params = new HashMap<String, String>();
                    params.put("accountType", "HOSTED_OR_GOOGLE");
                    if (hintEmail != null && !hintEmail.isEmpty()) {
                        params.put("Email", hintEmail);
                    }
                    params.put("Token", oauthToken);
                    params.put("ACCESS_TOKEN", "1");
                    params.put("get_accountid", "1");
                    params.put("service", "ac2dm");
                    params.put("source", "android");
                    params.put("app", GMS_PACKAGE);
                    params.put("client_sig", GMS_OFFICIAL_SHA1);
                    params.put("device_country", "us");
                    params.put("operatorCountry", "us");
                    params.put("lang", "en-US");
                    params.put("sdk_version", String.valueOf(Math.min(Build.VERSION.SDK_INT, 34)));

                    Map<String, String> resp = postGoogleAuthForm(params);
                    String masterToken = resp.get("Token");
                    if (masterToken == null || masterToken.isEmpty()) {
                        // Retry with androidId included if first exchange needed device id
                        params.put("androidId", StandaloneRuntimeEngine.getSpoofedGsfIdHexForGms());
                        resp = postGoogleAuthForm(params);
                        masterToken = resp.get("Token");
                    }
                    if (masterToken == null || masterToken.isEmpty()) {
                        // Second fallback with service=android
                        params.put("service", "android");
                        resp = postGoogleAuthForm(params);
                        masterToken = resp.get("Token");
                    }
                    if (masterToken == null || masterToken.isEmpty()) {
                        masterToken = resp.get("Auth");
                    }

                    String resolvedEmail = resp.get("Email");
                    if (resolvedEmail == null || resolvedEmail.isEmpty()) {
                        resolvedEmail = hintEmail;
                    }
                    if (resolvedEmail == null || resolvedEmail.isEmpty()) {
                        resolvedEmail = "user@gmail.com";
                    }
                    final String finalEmail = resolvedEmail.trim();
                    final String finalMasterToken = (masterToken != null && !masterToken.isEmpty())
                            ? masterToken.trim()
                            : oauthToken;
                    final String googleUserId = resp.get("GoogleUserId");

                    Map<String, String> userData = new HashMap<String, String>();
                    if (googleUserId != null && !googleUserId.isEmpty()) {
                        userData.put("GoogleUserId", googleUserId);
                    }
                    String firstName = resp.get("firstName");
                    String lastName = resp.get("lastName");
                    if (firstName != null && !firstName.isEmpty()) userData.put("firstName", firstName);
                    if (lastName != null && !lastName.isEmpty()) userData.put("lastName", lastName);
                    userData.put("oauth_token", oauthToken);
                    userData.put("services", "mail,youtube,androidmarket,ac2dm");
                    StandaloneRuntimeEngine.saveSandboxedGoogleAccountForGms(finalEmail, finalMasterToken, userData);
                    LIVE_TOKEN_CACHE.clear();

                    // Pre-warm primary OAuth tokens for Play Store / YouTube so UI refreshes immediately
                    String origPkg = StandaloneRuntimeEngine.getOriginalPackageNameForGms();
                    if (isPlayStorePackage(origPkg)) {
                        fetchGoogleOAuthTokenSync(finalEmail, "oauth2:https://www.googleapis.com/auth/googleplay", null);
                        fetchGoogleOAuthTokenSync(finalEmail, "androidmarket", null);
                    } else if (origPkg != null && origPkg.contains("youtube")) {
                        fetchGoogleOAuthTokenSync(finalEmail, "oauth2:https://www.googleapis.com/auth/youtube", null);
                    }

                    hostActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try { dialog.dismiss(); } catch (Throwable ignored) {}
                            try {
                                Toast.makeText(hostActivity, "✅ Gmail Signed In: " + finalEmail, Toast.LENGTH_LONG).show();
                            } catch (Throwable ignored) {}
                            try {
                                Intent bc = new Intent(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION);
                                bc.setPackage(hostActivity.getPackageName());
                                hostActivity.sendBroadcast(bc);
                            } catch (Throwable ignored) {}
                            StandaloneRuntimeEngine.refreshHudFromGms();
                            if (callback != null) callback.onSuccess(finalEmail);
                        }
                    });
                } catch (Throwable err) {
                    hostActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completed.set(false);
                            subTv.setText("Sign-In failed — try again");
                            subTv.setTextColor(Color.parseColor("#FF5252"));
                        }
                    });
                }
            }
        }, "EmbeddedGmsExchange");
        t.setDaemon(true);
        t.start();
    }

    private static String extractCookieValue(String cookieHeader, String key) {
        if (cookieHeader == null || cookieHeader.isEmpty() || key == null) return null;
        String[] parts = cookieHeader.split(";");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            int eq = p.indexOf('=');
            if (eq > 0) {
                String k = p.substring(0, eq).trim();
                if (key.equals(k)) {
                    String v = p.substring(eq + 1).trim();
                    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                        v = v.substring(1, v.length() - 1);
                    }
                    return v;
                }
            }
        }
        return null;
    }

    private static TextView makeSmallPillButton(Context ctx, float dp, String label, String bgHex, String strokeHex) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        int padH = Math.round(10f * dp);
        tv.setPadding(padH, 0, padH, 0);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setStroke(Math.max(1, Math.round(1.2f * dp)), Color.parseColor(strokeHex));
        gd.setCornerRadius(8f * dp);
        tv.setBackground(gd);
        return tv;
    }
}
