package com.example.standalone;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native In-Process Kiwi Browser TaskPulse Automation Service (v87).
 *
 * Runs directly inside Kiwi Browser's MAIN process (Foreground Service, stopWithTask="false",
 * START_STICKY, PARTIAL_WAKE_LOCK) — NEVER opens a separate WebView or external browser!
 *
 * Key capabilities (v87):
 *  - R8/ProGuard-proof Deep-BFS discovery of Kiwi Browser's Chromium TabModel, Tab, WebContents,
 *    and ViewAndroidDelegate/CompositorViewHolder.
 *  - Real Android MotionEvent taps & smooth finger swipe-up scrolling directly on Kiwi's viewport
 *    with automatic toolbar-height calibration so TaskPulse's "Start" button (with real Chromium
 *    user activation!) and X.com's "Follow", "Retweet", and "Repost" buttons behave 100% like
 *    human touches — including scrolling horizontally on TaskPulse tables and scrolling down on
 *    long X.com posts to find the focal tweet's Retweet button!
 *  - 3-tier JS evaluation (WebContentsImpl evaluateJavaScript -> JNI fallback -> javascript: URL
 *    via LoadUrlParams + title channel) so automation never stalls on R8-obfuscated Kiwi builds.
 *  - Strictly alternates 1 Follower <-> 1 Retweet continuously, handles "Cancel and retry" and
 *    category cooldowns, preserves all Kiwi Chrome Extensions, and continues in background even
 *    when minimized or swiped out of Recents.
 */
public class KiwiTaskPulseService extends Service {

    public static final String ACTION_START = "com.example.standalone.KIWI_TP_START";
    public static final String ACTION_STOP = "com.example.standalone.KIWI_TP_STOP";
    public static final String ACTION_RESTART_KEEPALIVE = "com.example.standalone.KIWI_TP_RESTART";

    public static final String URL_FOLLOWERS = "https://taskpul.se/tasks/social/tw-followers";
    public static final String URL_RETWEETS = "https://taskpul.se/tasks/social/tw-retweets";
    public static final String STATUS_FILE_NAME = "ghost_tp_status.json";

    private static final String CHANNEL_ID = "ghost_kiwi_taskpulse_ch";
    private static final int NOTIF_ID = 49107;
    private static final int ALARM_REQ_CODE = 49108;

    private static final int MODE_FOLLOWERS = 0;
    private static final int MODE_RETWEETS = 1;

    private static final int STATE_IDLE = 0;
    private static final int STATE_WAIT_TASK_LIST = 1;
    private static final int STATE_WAIT_SOCIAL_TAB = 2;
    private static final int STATE_SOCIAL_ACTION = 3;
    private static final int STATE_SOCIAL_REPOST_CONFIRM = 4;
    private static final int STATE_SOCIAL_VERIFY = 5;
    private static final int STATE_VERIFY_COMPLETION = 6;

    private static volatile KiwiTaskPulseService sInstance;

    // Static live snapshot read directly by the Floating HUD in the same process
    public static volatile boolean sLiveRunning = false;
    public static volatile int sLiveFollowers = 0;
    public static volatile int sLiveRetweets = 0;
    public static volatile String sLiveBalance = "";
    public static volatile String sLiveStatus = "Idle";

    // Cached reflection handles per process lifetime
    private static volatile Object sCachedTabModelOwnerAct = null;
    private static volatile Object sCachedNormalTabModel = null;
    private static volatile Method sCachedSetIndexMethod = null;
    private static volatile int sCachedSetIndexArity = 0;
    private static volatile Method sCachedEvalJsMethod = null;
    private static volatile Class<?> sCachedJsCallbackClass = null;
    private static volatile Method sCachedNavLoadUrlMethod = null;
    private static volatile Class<?> sCachedLoadUrlParamsClass = null;
    private static volatile boolean sPreferLoadUrlJsFallback = false;

    private Handler mainHandler;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean isRunning = false;
    private int nextMode = MODE_FOLLOWERS;
    private int activeMode = MODE_FOLLOWERS;
    private int state = STATE_IDLE;
    private int followersDone = 0;
    private int retweetsDone = 0;
    private int skipRowOffset = 0;
    private int socialRetryCount = 0;
    private int socialScrollSteps = 0;
    private int startTapAttempts = 0;
    private int repostConfirmRetries = 0;
    private int consecutiveNoTaskCount = 0;
    private int tabDiscoveryRetries = 0;
    private long stateEnteredAtMs = 0L;

    private String balanceBeforeTask = "";
    private String currentBalance = "";
    private String currentStatusText = "Idle";

    // Tracking visible Kiwi tabs
    private int trackedTaskTabId = -1;
    private int trackedSocialTabId = -1;
    private String trackedSocialUrl = "";
    private final Set<Integer> knownSocialTabIdsBeforeStart = new HashSet<Integer>();

    // Headless real Kiwi Chromium WebContents fallback (ONLY used if user swipes Kiwi Activity out of Recents!)
    private Object headlessTaskWc = null;
    private Object headlessSocialWc = null;
    private boolean usingHeadlessFallback = false;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            try {
                onEngineTick();
            } catch (Throwable t) {
                setStatus("Working... (" + safeMsg(t) + ")");
                scheduleNextTick(2200L);
            }
        }
    };

    public static KiwiTaskPulseService getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        loadPersistedState();
        ensureNotificationChannel();
        startForegroundSafely();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureNotificationChannel();
        startForegroundSafely();

        String action = (intent != null && intent.getAction() != null) ? intent.getAction() : "";

        if (ACTION_STOP.equals(action)) {
            stopEngineAndService();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action) || ACTION_RESTART_KEEPALIVE.equals(action) || isRunning) {
            startEngine();
            return START_STICKY;
        }

        stopEngineAndService();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Keep Foreground Service & Chromium engine alive when Kiwi is swiped from Recents!
        if (isRunning) {
            setStatus("Background mode...");
            persistAndNotify();
            scheduleKeepAliveAlarm(2000L);
            scheduleNextTick(1500L);
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        if (isRunning) {
            scheduleKeepAliveAlarm(2000L);
        }
        try {
            stopSelf(startId);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        releaseWakeLock();
        destroyHeadlessWebContents();
        boolean keepAlive = isRunning;
        if (keepAlive) {
            scheduleKeepAliveAlarm(2500L);
        }
        super.onDestroy();
    }

    private void startEngine() {
        isRunning = true;
        sLiveRunning = true;
        acquireWakeLock();
        tabDiscoveryRetries = 0;
        consecutiveNoTaskCount = 0;
        setStatus("Starting in Kiwi...");
        persistAndNotify();
        loadCurrentCategoryPage();
    }

    public void stopEngineAndService() {
        isRunning = false;
        sLiveRunning = false;
        state = STATE_IDLE;
        setStatus("Stopped");
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        cancelKeepAliveAlarm();
        releaseWakeLock();
        destroyHeadlessWebContents();
        persistStateOnly();
        StandaloneRuntimeEngine.notifyKiwiHudRefresh();
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {}
        try {
            stopSelf();
        } catch (Throwable ignored) {}
    }

    private void scheduleNextTick(long delayMs) {
        if (!isRunning || mainHandler == null) return;
        mainHandler.removeCallbacks(tickRunnable);
        mainHandler.postDelayed(tickRunnable, Math.max(350L, delayMs));
    }

    // =========================================================================
    // Core Alternating State Machine (Strictly 1 Follower <-> 1 Retweet)
    // =========================================================================

    private void loadCurrentCategoryPage() {
        if (!isRunning) return;
        activeMode = nextMode;
        state = STATE_WAIT_TASK_LIST;
        stateEnteredAtMs = SystemClock.elapsedRealtime();
        socialRetryCount = 0;
        socialScrollSteps = 0;
        startTapAttempts = 0;
        repostConfirmRetries = 0;
        trackedSocialTabId = -1;
        trackedSocialUrl = "";

        String url = (activeMode == MODE_FOLLOWERS) ? URL_FOLLOWERS : URL_RETWEETS;
        String label = (activeMode == MODE_FOLLOWERS) ? "Followers" : "Retweets";
        setStatus("Opening " + label + "...");
        persistAndNotify();

        Object tabModel = resolveKiwiNormalTabModel();
        if (tabModel != null) {
            if (usingHeadlessFallback) {
                destroyHeadlessWebContents();
                usingHeadlessFallback = false;
            }
            tabDiscoveryRetries = 0;

            Object taskTab = findTaskPulseTab(tabModel);
            if (taskTab == null) {
                Object curTab = getCurrentTab(tabModel);
                String curUrl = getTabUrl(curTab);
                if (curTab != null && !isProtectedExtensionOrSystemTab(curUrl)) {
                    taskTab = curTab;
                } else {
                    taskTab = createNewKiwiTab(url);
                }
            }

            if (taskTab != null) {
                trackedTaskTabId = getTabId(taskTab);
                int idx = findTabIndex(tabModel, taskTab);
                if (idx >= 0) {
                    selectTabInModel(tabModel, idx);
                }
                Object wc = getTabWebContents(taskTab);
                keepWebContentsAwake(wc);
                String existingUrl = getTabUrl(taskTab);
                if (!urlsMatchCategory(existingUrl, activeMode)) {
                    navigateTabToUrl(taskTab, wc, url);
                    scheduleNextTick(2600L);
                } else {
                    scheduleNextTick(1200L);
                }
                return;
            }
        }

        // No live ChromeTabbedActivity (swiped out of Recents!) -> Headless Kiwi Chromium
        if (ensureHeadlessChromiumWebContents()) {
            usingHeadlessFallback = true;
            keepWebContentsAwake(headlessTaskWc);
            loadUrlOnWebContents(headlessTaskWc, url);
            scheduleNextTick(2800L);
            return;
        }

        tabDiscoveryRetries++;
        setStatus("Waiting Kiwi tab (" + tabDiscoveryRetries + ")...");
        persistAndNotify();
        scheduleNextTick(2000L);
    }

    private void onEngineTick() {
        if (!isRunning) return;
        long elapsed = SystemClock.elapsedRealtime() - stateEnteredAtMs;

        Object tabModel = resolveKiwiNormalTabModel();
        if (tabModel != null) {
            if (usingHeadlessFallback) {
                destroyHeadlessWebContents();
                usingHeadlessFallback = false;
                loadCurrentCategoryPage();
                return;
            }
            onVisibleKiwiTick(tabModel, elapsed);
        } else {
            if (ensureHeadlessChromiumWebContents()) {
                if (!usingHeadlessFallback) {
                    usingHeadlessFallback = true;
                    loadCurrentCategoryPage();
                    return;
                }
                onHeadlessChromiumTick(elapsed);
            } else {
                setStatus("Background standby");
                persistAndNotify();
                scheduleNextTick(2500L);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Mode A: Driving Kiwi Browser's REAL Tabs (Foreground real touches + Minimized JS)
    // -------------------------------------------------------------------------

    private void onVisibleKiwiTick(Object tabModel, long elapsed) {
        if (state == STATE_WAIT_TASK_LIST) {
            if (elapsed > 35000L) {
                loadCurrentCategoryPage();
                return;
            }
            Object taskTab = findTaskPulseTabByIdOrUrl(tabModel);
            if (taskTab == null) {
                loadCurrentCategoryPage();
                return;
            }
            int taskIdx = findTabIndex(tabModel, taskTab);
            if (taskIdx >= 0 && getSelectedTabIndex(tabModel) != taskIdx) {
                selectTabInModel(tabModel, taskIdx);
            }
            Object wc = getTabWebContents(taskTab);
            keepWebContentsAwake(wc);
            inspectTaskPulseWebContents(taskTab, wc, tabModel, false);

        } else if (state == STATE_WAIT_SOCIAL_TAB) {
            Object socialTab = findNewlyOpenedSocialTab(tabModel);
            if (socialTab != null) {
                trackedSocialTabId = getTabId(socialTab);
                trackedSocialUrl = getTabUrl(socialTab);
                int sIdx = findTabIndex(tabModel, socialTab);
                if (sIdx >= 0 && getSelectedTabIndex(tabModel) != sIdx) {
                    selectTabInModel(tabModel, sIdx);
                }
                Object sWc = getTabWebContents(socialTab);
                keepWebContentsAwake(sWc);
                state = STATE_SOCIAL_ACTION;
                stateEnteredAtMs = SystemClock.elapsedRealtime();
                socialRetryCount = 0;
                socialScrollSteps = 0;
                repostConfirmRetries = 0;
                String modeName = (activeMode == MODE_FOLLOWERS) ? "Follow" : "Retweet";
                setStatus("Loading X (" + modeName + ")...");
                persistAndNotify();
                scheduleNextTick(2200L);
                return;
            }
            if (elapsed > 7500L) {
                Object taskTab = findTaskPulseTabByIdOrUrl(tabModel);
                String tUrl = getTabUrl(taskTab);
                if (isSocialUrl(tUrl)) {
                    trackedSocialTabId = getTabId(taskTab);
                    trackedSocialUrl = tUrl;
                    state = STATE_SOCIAL_ACTION;
                    stateEnteredAtMs = SystemClock.elapsedRealtime();
                    socialRetryCount = 0;
                    socialScrollSteps = 0;
                    scheduleNextTick(1500L);
                    return;
                }
                // Retry clicking Start (perhaps Task in progress banner appeared or scroll wasn't aligned)
                state = STATE_WAIT_TASK_LIST;
                stateEnteredAtMs = SystemClock.elapsedRealtime();
                scheduleNextTick(1000L);
                return;
            }
            scheduleNextTick(750L);

        } else if (state == STATE_SOCIAL_ACTION) {
            if (elapsed > 32000L) {
                switchBackToTaskPulseTabAndVerify(tabModel);
                return;
            }
            Object socialTab = findTabById(tabModel, trackedSocialTabId);
            if (socialTab == null) {
                socialTab = findAnySocialTab(tabModel);
            }
            if (socialTab == null) {
                switchBackToTaskPulseTabAndVerify(tabModel);
                return;
            }
            int sIdx = findTabIndex(tabModel, socialTab);
            if (sIdx >= 0 && getSelectedTabIndex(tabModel) != sIdx) {
                selectTabInModel(tabModel, sIdx);
            }
            Object sWc = getTabWebContents(socialTab);
            keepWebContentsAwake(sWc);
            String curUrl = getTabUrl(socialTab);
            if (curUrl != null && !curUrl.isEmpty()) trackedSocialUrl = curUrl;
            performSocialActionOnWebContents(socialTab, sWc, tabModel, false);

        } else if (state == STATE_SOCIAL_REPOST_CONFIRM) {
            if (elapsed > 10000L) {
                // Bottom sheet didn't appear -> retry tapping Retweet icon
                state = STATE_SOCIAL_ACTION;
                stateEnteredAtMs = SystemClock.elapsedRealtime();
                scheduleNextTick(900L);
                return;
            }
            Object socialTab = findTabById(tabModel, trackedSocialTabId);
            if (socialTab == null) socialTab = findAnySocialTab(tabModel);
            if (socialTab == null) {
                switchBackToTaskPulseTabAndVerify(tabModel);
                return;
            }
            Object sWc = getTabWebContents(socialTab);
            keepWebContentsAwake(sWc);
            clickRepostConfirmOnWebContents(socialTab, sWc, false);

        } else if (state == STATE_SOCIAL_VERIFY) {
            if (elapsed > 14000L) {
                switchBackToTaskPulseTabAndVerify(tabModel);
                return;
            }
            Object socialTab = findTabById(tabModel, trackedSocialTabId);
            if (socialTab == null) {
                switchBackToTaskPulseTabAndVerify(tabModel);
                return;
            }
            Object sWc = getTabWebContents(socialTab);
            keepWebContentsAwake(sWc);
            verifySocialActionOnWebContents(socialTab, sWc, tabModel, false);

        } else if (state == STATE_VERIFY_COMPLETION) {
            Object taskTab = findTaskPulseTabByIdOrUrl(tabModel);
            if (taskTab == null) {
                loadCurrentCategoryPage();
                return;
            }
            int taskIdx = findTabIndex(tabModel, taskTab);
            if (taskIdx >= 0 && getSelectedTabIndex(tabModel) != taskIdx) {
                selectTabInModel(tabModel, taskIdx);
            }
            Object wc = getTabWebContents(taskTab);
            keepWebContentsAwake(wc);
            if (elapsed > 18000L) {
                handleUnverifiedTaskTimeout(taskTab, wc, tabModel, false);
                return;
            }
            checkTaskPulseCompletionOnWebContents(taskTab, wc, tabModel, false);

        } else {
            loadCurrentCategoryPage();
        }
    }

    private void switchBackToTaskPulseTabAndVerify(Object tabModel) {
        if (!isRunning) return;
        state = STATE_VERIFY_COMPLETION;
        stateEnteredAtMs = SystemClock.elapsedRealtime();
        setStatus("Checking credit...");
        persistAndNotify();

        Object taskTab = findTaskPulseTabByIdOrUrl(tabModel);
        if (taskTab != null) {
            int idx = findTabIndex(tabModel, taskTab);
            if (idx >= 0) {
                selectTabInModel(tabModel, idx);
            }
            Object wc = getTabWebContents(taskTab);
            keepWebContentsAwake(wc);
            evalJsOnWebContents(taskTab, wc,
                    "(function(){try{window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'));}catch(e){}})();",
                    null);
        }
        scheduleNextTick(1400L);
    }

    // -------------------------------------------------------------------------
    // Mode B: Headless Real Kiwi Chromium WebContents (After Recents Swipe!)
    // -------------------------------------------------------------------------

    private void onHeadlessChromiumTick(long elapsed) {
        keepWebContentsAwake(headlessTaskWc);
        keepWebContentsAwake(headlessSocialWc);

        if (state == STATE_WAIT_TASK_LIST) {
            if (elapsed > 35000L) {
                loadCurrentCategoryPage();
                return;
            }
            inspectTaskPulseWebContents(null, headlessTaskWc, null, true);
        } else if (state == STATE_WAIT_SOCIAL_TAB) {
            evalJsOnWebContents(null, headlessTaskWc,
                    "(function(){var u=window.__ghCapturedPopupUrl||'';window.__ghCapturedPopupUrl='';return u;})();",
                    new JsCallback() {
                        @Override
                        public void onResult(String raw) {
                            if (!isRunning || state != STATE_WAIT_SOCIAL_TAB) return;
                            String url = unquoteJsString(raw);
                            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                                trackedSocialUrl = url;
                                state = STATE_SOCIAL_ACTION;
                                stateEnteredAtMs = SystemClock.elapsedRealtime();
                                socialRetryCount = 0;
                                socialScrollSteps = 0;
                                repostConfirmRetries = 0;
                                loadUrlOnWebContents(headlessSocialWc, url);
                                setStatus("BG X (" + (activeMode == MODE_FOLLOWERS ? "Follow" : "Retweet") + ")...");
                                persistAndNotify();
                                scheduleNextTick(2400L);
                                return;
                            }
                            if (SystemClock.elapsedRealtime() - stateEnteredAtMs > 10000L) {
                                state = STATE_WAIT_TASK_LIST;
                                stateEnteredAtMs = SystemClock.elapsedRealtime();
                                scheduleNextTick(1400L);
                            } else {
                                scheduleNextTick(900L);
                            }
                        }
                    });
        } else if (state == STATE_SOCIAL_ACTION) {
            if (elapsed > 30000L) {
                enterHeadlessVerifyCompletion();
                return;
            }
            String u = getWebContentsUrl(headlessSocialWc);
            if (u != null && !u.isEmpty()) trackedSocialUrl = u;
            performSocialActionOnWebContents(null, headlessSocialWc, null, true);
        } else if (state == STATE_SOCIAL_REPOST_CONFIRM) {
            if (elapsed > 9000L) {
                state = STATE_SOCIAL_ACTION;
                stateEnteredAtMs = SystemClock.elapsedRealtime();
                scheduleNextTick(900L);
                return;
            }
            clickRepostConfirmOnWebContents(null, headlessSocialWc, true);
        } else if (state == STATE_SOCIAL_VERIFY) {
            if (elapsed > 13000L) {
                enterHeadlessVerifyCompletion();
                return;
            }
            verifySocialActionOnWebContents(null, headlessSocialWc, null, true);
        } else if (state == STATE_VERIFY_COMPLETION) {
            if (elapsed > 18000L) {
                handleUnverifiedTaskTimeout(null, headlessTaskWc, null, true);
                return;
            }
            checkTaskPulseCompletionOnWebContents(null, headlessTaskWc, null, true);
        } else {
            loadCurrentCategoryPage();
        }
    }

    private void enterHeadlessVerifyCompletion() {
        state = STATE_VERIFY_COMPLETION;
        stateEnteredAtMs = SystemClock.elapsedRealtime();
        setStatus("BG verifying...");
        persistAndNotify();
        evalJsOnWebContents(null, headlessTaskWc,
                "(function(){try{window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'));}catch(e){}})();",
                null);
        scheduleNextTick(1600L);
    }

    // =========================================================================
    // Shared TaskPulse & X.com DOM + Real-Touch Automation Scripts
    // =========================================================================

    private static final String JS_CLICK_HELPER =
            "function __ghSmartClick(el){" +
            "  if(!el)return;" +
            "  try{el.focus();}catch(e){}" +
            "  var r=el.getBoundingClientRect();" +
            "  var cx=r.left+r.width/2, cy=r.top+r.height/2;" +
            "  try{el.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true,pointerType:'touch',clientX:cx,clientY:cy,isPrimary:true}));}catch(e){}" +
            "  try{el.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,clientX:cx,clientY:cy}));}catch(e){}" +
            "  try{el.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,cancelable:true,pointerType:'touch',clientX:cx,clientY:cy,isPrimary:true}));}catch(e){}" +
            "  try{el.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,clientX:cx,clientY:cy}));}catch(e){}" +
            "  try{el.click();}catch(e){}" +
            "}";

    private void inspectTaskPulseWebContents(final Object tab, final Object wc, final Object tabModel, final boolean isHeadless) {
        if (wc == null) {
            scheduleNextTick(1800L);
            return;
        }
        final boolean canUseRealTouch = !isHeadless && isKiwiActivityVisibleOnScreen();
        final boolean doJsClickFallback = !canUseRealTouch || (startTapAttempts >= 1);

        String popupHookPrefix = isHeadless
                ? "if(!window.__ghHeadlessOpenHooked){window.__ghHeadlessOpenHooked=true;window.__ghCapturedPopupUrl='';window.open=function(u){if(u)window.__ghCapturedPopupUrl=String(u);return {closed:false,focus:function(){},close:function(){this.closed=true;}};};}"
                : "";

        final String js =
                "(function(){" +
                "  try {" +
                JS_CLICK_HELPER +
                popupHookPrefix +
                "    var href = String(location.href || '');" +
                "    var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';" +
                "    var lower = bodyText.toLowerCase();" +
                "    if (href.indexOf('taskpul.se') < 0) return JSON.stringify({status:'WRONG_PAGE', href:href});" +
                "    if (href.indexOf('/login') >= 0 || (lower.indexOf('sign in') >= 0 && lower.indexOf('perform tasks') < 0 && lower.indexOf('start') < 0)) {" +
                "      return JSON.stringify({status:'LOGIN_REQUIRED'});" +
                "    }" +
                "    var balMatch = bodyText.match(/\\$[0-9]+\\.[0-9]+/);" +
                "    var bal = balMatch ? balMatch[0] : '';" +
                "    var els = document.querySelectorAll('button, a, [role=\"button\"]');" +
                "    for (var i = 0; i < els.length; i++) {" +
                "      var cEl = els[i];" +
                "      var t = (cEl.innerText || cEl.textContent || '').trim().toLowerCase();" +
                "      if (t.indexOf('cancel and retry') >= 0 || (t === 'cancel' && lower.indexOf('task in progress') >= 0)) {" +
                "        cEl.scrollIntoView({behavior:'instant', block:'center', inline:'center'});" +
                "        var cp = cEl.parentElement;" +
                "        while (cp && cp !== document.body) { if (cp.scrollWidth > cp.clientWidth + 16) cp.scrollLeft = cp.scrollWidth; cp = cp.parentElement; }" +
                "        var cr = cEl.getBoundingClientRect();" +
                "        if (" + doJsClickFallback + ") __ghSmartClick(cEl);" +
                "        return JSON.stringify({status:'CANCEL_READY', balance:bal, vx:cr.left+cr.width/2, vy:cr.top+cr.height/2, vw:window.innerWidth, vh:window.innerHeight, clicked:" + doJsClickFallback + "});" +
                "      }" +
                "    }" +
                "    if (lower.indexOf('take a short break') >= 0 || lower.indexOf('cooldown for this category') >= 0) {" +
                "      var cm = bodyText.match(/in\\s+(\\d+)\\s*s/i);" +
                "      var waitSec = cm ? parseInt(cm[1], 10) : 5;" +
                "      return JSON.stringify({status:'COOLDOWN', waitSec:waitSec, balance:bal});" +
                "    }" +
                "    var startBtns = [];" +
                "    for (var j = 0; j < els.length; j++) {" +
                "      var el = els[j];" +
                "      var txt = (el.innerText || el.textContent || '').trim().toLowerCase();" +
                "      if (txt === 'start' && !el.disabled && el.getAttribute('aria-disabled') !== 'true') {" +
                "        var style = window.getComputedStyle(el);" +
                "        if (style && style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0') {" +
                "          var testR = el.getBoundingClientRect();" +
                "          if (testR.width > 10 && testR.height > 10) startBtns.push(el);" +
                "        }" +
                "      }" +
                "    }" +
                "    if (startBtns.length > 0) {" +
                "      var idx = " + skipRowOffset + " % startBtns.length;" +
                "      var target = startBtns[idx];" +
                "      target.scrollIntoView({behavior:'instant', block:'center', inline:'center'});" +
                "      var p = target.parentElement;" +
                "      while (p && p !== document.body) {" +
                "        if (p.scrollWidth > p.clientWidth + 16) {" +
                "          p.scrollLeft = p.scrollWidth;" +
                "        }" +
                "        p = p.parentElement;" +
                "      }" +
                "      var r = target.getBoundingClientRect();" +
                "      var vw = window.innerWidth || 360;" +
                "      var vh = window.innerHeight || 640;" +
                "      if (r.right > vw || r.left < 0) {" +
                "        try { window.scrollBy(Math.round(r.left - vw * 0.55), 0); } catch(e) {}" +
                "        r = target.getBoundingClientRect();" +
                "      }" +
                "      var inView = (r.left >= 0 && r.right <= vw + 4 && r.top >= 4 && r.bottom <= vh - 4);" +
                "      if (" + doJsClickFallback + ") __ghSmartClick(target);" +
                "      return JSON.stringify({status:'START_READY', count:startBtns.length, balance:bal, inView:inView, vx:r.left+r.width/2, vy:r.top+r.height/2, vw:vw, vh:vh, clicked:" + doJsClickFallback + "});" +
                "    }" +
                "    if (lower.indexOf('no tasks') >= 0 || lower.indexOf('no available') >= 0) {" +
                "      return JSON.stringify({status:'NO_TASKS', balance:bal});" +
                "    }" +
                "    return JSON.stringify({status:'LOADING', balance:bal});" +
                "  } catch(e) { return JSON.stringify({status:'ERR', msg:String(e)}); }" +
                "})();";

        if (!isHeadless && tabModel != null) {
            snapshotExistingSocialTabIds(tabModel);
        }

        evalJsOnWebContents(tab, wc, js, new JsCallback() {
            @Override
            public void onResult(String raw) {
                if (!isRunning || state != STATE_WAIT_TASK_LIST) return;
                JSONObject obj = parseJsJson(raw);
                String st = obj.optString("status", "LOADING");
                String bal = obj.optString("balance", "");
                if (!bal.isEmpty()) currentBalance = bal;

                if ("START_READY".equals(st)) {
                    balanceBeforeTask = currentBalance;
                    consecutiveNoTaskCount = 0;
                    boolean alreadyJsClicked = obj.optBoolean("clicked", false);
                    final float vx = (float) obj.optDouble("vx", -1);
                    final float vy = (float) obj.optDouble("vy", -1);
                    final float vw = (float) obj.optDouble("vw", 360);
                    final float vh = (float) obj.optDouble("vh", 640);
                    boolean inView = obj.optBoolean("inView", false);

                    String modeName = (activeMode == MODE_FOLLOWERS) ? "Follow" : "Retweet";
                    setStatus("Tap Start (" + modeName + ")");
                    persistAndNotify();

                    if (canUseRealTouch && inView && vx > 4f && vy > 4f) {
                        // Dispatch genuine Android MotionEvent tap so Chromium grants real user activation!
                        boolean tapped = dispatchRealTapOnKiwiViewport(wc, vx, vy, vw, vh);
                        if (!tapped && !alreadyJsClicked) {
                            evalJsOnWebContents(tab, wc, buildDirectStartClickJs(skipRowOffset), null);
                        }
                    } else if (!alreadyJsClicked) {
                        evalJsOnWebContents(tab, wc, buildDirectStartClickJs(skipRowOffset), null);
                    }

                    startTapAttempts++;
                    state = STATE_WAIT_SOCIAL_TAB;
                    stateEnteredAtMs = SystemClock.elapsedRealtime();
                    scheduleNextTick(1200L);

                } else if ("CANCEL_READY".equals(st)) {
                    boolean alreadyJsClicked = obj.optBoolean("clicked", false);
                    float vx = (float) obj.optDouble("vx", -1);
                    float vy = (float) obj.optDouble("vy", -1);
                    float vw = (float) obj.optDouble("vw", 360);
                    float vh = (float) obj.optDouble("vh", 640);
                    if (canUseRealTouch && vx > 4f && vy > 4f) {
                        dispatchRealTapOnKiwiViewport(wc, vx, vy, vw, vh);
                    }
                    if (!alreadyJsClicked) {
                        evalJsOnWebContents(tab, wc,
                                "(function(){try{" + JS_CLICK_HELPER + "var els=document.querySelectorAll('button,a,[role=\"button\"]');for(var i=0;i<els.length;i++){var t=(els[i].innerText||els[i].textContent||'').trim().toLowerCase();if(t.indexOf('cancel and retry')>=0){__ghSmartClick(els[i]);break;}}}catch(e){}})();",
                                null);
                    }
                    setStatus("Cancel & retry...");
                    stateEnteredAtMs = SystemClock.elapsedRealtime();
                    persistAndNotify();
                    scheduleNextTick(2400L);

                } else if ("COOLDOWN".equals(st)) {
                    int waitSec = obj.optInt("waitSec", 5);
                    if (waitSec <= 0) waitSec = 2;
                    waitSec = Math.min(50, waitSec);
                    setStatus("Cooldown " + waitSec + "s...");
                    stateEnteredAtMs = SystemClock.elapsedRealtime() + (waitSec * 1000L);
                    persistAndNotify();
                    scheduleNextTick((waitSec + 1) * 1000L);

                } else if ("NO_TASKS".equals(st)) {
                    consecutiveNoTaskCount++;
                    nextMode = (activeMode == MODE_FOLLOWERS) ? MODE_RETWEETS : MODE_FOLLOWERS;
                    setStatus("Switching category...");
                    persistAndNotify();
                    long waitMs = (consecutiveNoTaskCount >= 2) ? 12000L : 2800L;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (isRunning) loadCurrentCategoryPage();
                        }
                    }, waitMs);

                } else if ("LOGIN_REQUIRED".equals(st)) {
                    setStatus("Login TaskPulse in Kiwi");
                    stateEnteredAtMs = SystemClock.elapsedRealtime();
                    persistAndNotify();
                    scheduleNextTick(7000L);

                } else if ("WRONG_PAGE".equals(st)) {
                    loadCurrentCategoryPage();

                } else {
                    scheduleNextTick(1400L);
                }
            }
        });
    }

    private String buildDirectStartClickJs(int offset) {
        return "(function(){try{" + JS_CLICK_HELPER +
                "var els=document.querySelectorAll('button,a,[role=\"button\"]');var arr=[];" +
                "for(var i=0;i<els.length;i++){var t=(els[i].innerText||els[i].textContent||'').trim().toLowerCase();" +
                "if(t==='start'&&!els[i].disabled&&els[i].getAttribute('aria-disabled')!=='true'){arr.push(els[i]);}}" +
                "if(arr.length>0){var el=arr[" + offset + "%arr.length];el.scrollIntoView({behavior:'instant',block:'center',inline:'center'});__ghSmartClick(el);}" +
                "}catch(e){}})();";
    }

    private void performSocialActionOnWebContents(final Object tab, final Object wc, final Object tabModel, final boolean isHeadless) {
        if (wc == null) {
            scheduleNextTick(1400L);
            return;
        }
        final boolean isFollowMode = (activeMode == MODE_FOLLOWERS);
        final boolean canUseRealTouch = !isHeadless && isKiwiActivityVisibleOnScreen();
        final String targetStatusId = extractTweetStatusId(trackedSocialUrl);

        final String js;
        if (isFollowMode) {
            js =
                "(function(){" +
                "  try {" +
                "    var href = String(location.href || '');" +
                "    var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';" +
                "    var lower = bodyText.toLowerCase();" +
                "    if (href.indexOf('/i/flow/login') >= 0 || href.indexOf('/login') >= 0) return JSON.stringify({status:'X_LOGIN'});" +
                "    if (lower.indexOf('account suspended') >= 0 || lower.indexOf(\"doesn't exist\") >= 0 || lower.indexOf('doesn’t exist') >= 0) return JSON.stringify({status:'UNAVAILABLE'});" +
                "    var vw = window.innerWidth || 360, vh = window.innerHeight || 640;" +
                "    var confirmBtn = document.querySelector('[data-testid=\"confirmationSheetConfirm\"]');" +
                "    if (confirmBtn) {" +
                "      var cr = confirmBtn.getBoundingClientRect();" +
                "      return JSON.stringify({status:'TAP_FOLLOW', vx:cr.left+cr.width/2, vy:cr.top+cr.height/2, vw:vw, vh:vh, sel:'confirm'});" +
                "    }" +
                "    if (document.querySelector('[data-testid$=\"-unfollow\"]')) return JSON.stringify({status:'ALREADY_DONE'});" +
                "    var followBtn = document.querySelector('[data-testid$=\"-follow\"]');" +
                "    if (!followBtn) {" +
                "      var btns = document.querySelectorAll('button, [role=\"button\"]');" +
                "      for (var i = 0; i < btns.length; i++) {" +
                "        var b = btns[i];" +
                "        var t = (b.innerText || b.textContent || '').trim();" +
                "        var al = (b.getAttribute('aria-label') || '').trim();" +
                "        if (t === 'Following' || t === 'Pending') return JSON.stringify({status:'ALREADY_DONE'});" +
                "        if (t === 'Follow' || t === 'Follow back' || al.indexOf('Follow @') === 0) { followBtn = b; break; }" +
                "      }" +
                "    }" +
                "    if (followBtn) {" +
                "      var r = followBtn.getBoundingClientRect();" +
                "      if (r.width < 10 || r.height < 10) return JSON.stringify({status:'WAITING'});" +
                "      if (r.top < 50 || r.bottom > vh - 60) {" +
                "        followBtn.scrollIntoView({behavior:'instant', block:'center'});" +
                "        return JSON.stringify({status:'SCROLLED_TO_TARGET'});" +
                "      }" +
                "      return JSON.stringify({status:'TAP_FOLLOW', vx:r.left+r.width/2, vy:r.top+r.height/2, vw:vw, vh:vh, sel:'follow'});" +
                "    }" +
                "    return JSON.stringify({status:'WAITING'});" +
                "  } catch(e) { return JSON.stringify({status:'ERR'}); }" +
                "})();";
        } else {
            final String safeId = targetStatusId.replace("'", "");
            js =
                "(function(){" +
                "  try {" +
                "    var href = String(location.href || '');" +
                "    var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';" +
                "    var lower = bodyText.toLowerCase();" +
                "    if (href.indexOf('/i/flow/login') >= 0 || href.indexOf('/login') >= 0) return JSON.stringify({status:'X_LOGIN'});" +
                "    if (lower.indexOf('post was deleted') >= 0 || lower.indexOf(\"page doesn't exist\") >= 0 || lower.indexOf('account suspended') >= 0) return JSON.stringify({status:'UNAVAILABLE'});" +
                "    var vw = window.innerWidth || 360, vh = window.innerHeight || 640;" +
                "    var menuConfirm = document.querySelector('[data-testid=\"retweetConfirm\"]');" +
                "    if (menuConfirm) {" +
                "      var mr = menuConfirm.getBoundingClientRect();" +
                "      if (mr.width > 10 && mr.height > 10) {" +
                "        return JSON.stringify({status:'REPOST_SHEET_OPEN', vx:mr.left+mr.width/2, vy:mr.top+mr.height/2, vw:vw, vh:vh});" +
                "      }" +
                "    }" +
                "    var articles = document.querySelectorAll('article[data-testid=\"tweet\"]');" +
                "    if (!articles || articles.length === 0) return JSON.stringify({status:'WAITING'});" +
                "    var targetId = '" + safeId + "';" +
                "    var focal = null;" +
                "    for (var i = 0; i < articles.length; i++) {" +
                "      if (articles[i].getAttribute('tabindex') === '-1') { focal = articles[i]; break; }" +
                "    }" +
                "    if (!focal && targetId) {" +
                "      for (var k = 0; k < articles.length; k++) {" +
                "        if (articles[k].querySelector('a[href*=\"' + targetId + '\"]')) { focal = articles[k]; break; }" +
                "      }" +
                "    }" +
                "    if (!focal) focal = articles[0];" +
                "    if (focal.querySelector('[data-testid=\"unretweet\"]')) return JSON.stringify({status:'ALREADY_DONE'});" +
                "    var rtBtn = focal.querySelector('[data-testid=\"retweet\"]');" +
                "    if (!rtBtn) {" +
                "      var bList = focal.querySelectorAll('button, [role=\"button\"]');" +
                "      for (var b = 0; b < bList.length; b++) {" +
                "        var al = (bList[b].getAttribute('aria-label') || '').toLowerCase();" +
                "        if (al.indexOf('repost') >= 0 || al.indexOf('retweet') >= 0) { rtBtn = bList[b]; break; }" +
                "      }" +
                "    }" +
                "    if (rtBtn) {" +
                "      var r = rtBtn.getBoundingClientRect();" +
                "      var safeTop = Math.max(68, vh * 0.12);" +
                "      var safeBot = Math.min(vh - 76, vh * 0.86);" +
                "      if (r.width < 8 || r.height < 8 || r.top < safeTop || r.bottom > safeBot) {" +
                "        var delta = (r.top + r.height / 2) - (vh * 0.50);" +
                "        if (Math.abs(delta) > 360) {" +
                "          window.scrollBy({top: delta > 0 ? Math.min(delta, vh * 0.65) : Math.max(delta, -vh * 0.65), behavior: 'smooth'});" +
                "          return JSON.stringify({status:'NEED_SCROLL_DOWN', delta:delta, vw:vw, vh:vh});" +
                "        } else {" +
                "          rtBtn.scrollIntoView({behavior:'smooth', block:'center'});" +
                "          return JSON.stringify({status:'SCROLLED_TO_TARGET', vw:vw, vh:vh});" +
                "        }" +
                "      }" +
                "      return JSON.stringify({status:'TAP_RETWEET', vx:r.left+r.width/2, vy:r.top+r.height/2, vw:vw, vh:vh});" +
                "    }" +
                "    window.scrollBy({top: Math.round(vh * 0.55), behavior: 'smooth'});" +
                "    return JSON.stringify({status:'NEED_SCROLL_DOWN', delta:400, vw:vw, vh:vh});" +
                "  } catch(e) { return JSON.stringify({status:'ERR'}); }" +
                "})();";
        }

        evalJsOnWebContents(tab, wc, js, new JsCallback() {
            @Override
            public void onResult(String raw) {
                if (!isRunning || state != STATE_SOCIAL_ACTION) return;
                JSONObject obj = parseJsJson(raw);
                String st = obj.optString("status", "WAITING");
                float vx = (float) obj.optDouble("vx", -1);
                float vy = (float) obj.optDouble("vy", -1);
                float vw = (float) obj.optDouble("vw", 360);
                float vh = (float) obj.optDouble("vh", 640);

                if ("TAP_FOLLOW".equals(st)) {
                    setStatus("Tapping Follow...");
                    persistAndNotify();
                    boolean tapped = false;
                    if (canUseRealTouch && vx > 4f && vy > 4f) {
                        tapped = dispatchRealTapOnKiwiViewport(wc, vx, vy, vw, vh);
                    }
                    if (!tapped || socialRetryCount > 0) {
                        // Also trigger smart JS click on headless/retry for maximum reliability
                        evalJsOnWebContents(tab, wc,
                                "(function(){try{" + JS_CLICK_HELPER +
                                "var b=document.querySelector('[data-testid=\"confirmationSheetConfirm\"]')||document.querySelector('[data-testid$=\"-follow\"]');" +
                                "if(!b){var bs=document.querySelectorAll('button,[role=\"button\"]');for(var i=0;i<bs.length;i++){var t=(bs[i].innerText||bs[i].textContent||'').trim();if(t==='Follow'||t==='Follow back'){b=bs[i];break;}}}" +
                                "if(b)__ghSmartClick(b);}catch(e){}})();",
                                null);
                    }
                    state = STATE_SOCIAL_VERIFY;
                    stateEnteredAtMs = SystemClock.elapsedRealtime();
                    scheduleNextTick(2100L);

                } else if ("TAP_RETWEET".equals(st)) {
                    setStatus("Tapping Retweet...");
                    persistAndNotify();
                    boolean tapped = false;
                    if (canUseRealTouch && vx > 4f && vy > 4f) {
                        tapped = dispatchRealTapOnKiwiViewport(wc, vx, vy, vw, vh);
                    }
                    if (!tapped || socialRetryCount > 0) {
                        final String safeId = targetStatusId.replace("'", "");
                        evalJsOnWebContents(tab, wc,
                                "(function(){try{" + JS_CLICK_HELPER +
                                "var as=document.querySelectorAll('article[data-testid=\"tweet\"]');var f=null;" +
                                "for(var i=0;i<as.length;i++){if(as[i].getAttribute('tabindex')==='-1'){f=as[i];break;}}" +
                                "if(!f&&'" + safeId + "'){for(var k=0;k<as.length;k++){if(as[k].querySelector('a[href*=\"" + safeId + "\"]')){f=as[k];break;}}}" +
                                "if(!f&&as.length>0)f=as[0];" +
                                "var r=f?f.querySelector('[data-testid=\"retweet\"]'):document.querySelector('[data-testid=\"retweet\"]');" +
                                "if(r)__ghSmartClick(r);}catch(e){}})();",
                                null);
                    }
                    state = STATE_SOCIAL_REPOST_CONFIRM;
                    stateEnteredAtMs = SystemClock.elapsedRealtime();
                    repostConfirmRetries = 0;
                    scheduleNextTick(850L);

                } else if ("REPOST_SHEET_OPEN".equals(st)) {
                    state = STATE_SOCIAL_REPOST_CONFIRM;
                    stateEnteredAtMs = SystemClock.elapsedRealtime();
                    repostConfirmRetries = 0;
                    clickRepostConfirmOnWebContents(tab, wc, isHeadless);

                } else if ("NEED_SCROLL_DOWN".equals(st)) {
                    socialScrollSteps++;
                    setStatus("Scrolling tweet (" + socialScrollSteps + ")...");
                    persistAndNotify();
                    // Also perform a real smooth finger swipe-up in foreground (like user's video at 02:19-02:24!)
                    if (canUseRealTouch && socialScrollSteps <= 12) {
                        dispatchRealSwipeUpOnKiwiViewport(wc);
                    }
                    scheduleNextTick(950L);

                } else if ("SCROLLED_TO_TARGET".equals(st)) {
                    setStatus("Centering button...");
                    scheduleNextTick(750L);

                } else if ("ALREADY_DONE".equals(st) || "UNAVAILABLE".equals(st)) {
                    if (isHeadless) {
                        enterHeadlessVerifyCompletion();
                    } else {
                        switchBackToTaskPulseTabAndVerify(tabModel);
                    }

                } else if ("X_LOGIN".equals(st)) {
                    setStatus("Login X.com in Kiwi");
                    stateEnteredAtMs = SystemClock.elapsedRealtime();
                    persistAndNotify();
                    scheduleNextTick(7000L);

                } else {
                    scheduleNextTick(1200L);
                }
            }
        });
    }

    private void clickRepostConfirmOnWebContents(final Object tab, final Object wc, final boolean isHeadless) {
        final boolean canUseRealTouch = !isHeadless && isKiwiActivityVisibleOnScreen();
        final String js =
                "(function(){" +
                "  try {" +
                "    var vw = window.innerWidth || 360, vh = window.innerHeight || 640;" +
                "    var c = document.querySelector('[data-testid=\"retweetConfirm\"]');" +
                "    if (!c) {" +
                "      var items = document.querySelectorAll('[role=\"menuitem\"], button, [role=\"button\"]');" +
                "      for (var i = 0; i < items.length; i++) {" +
                "        var t = (items[i].innerText || items[i].textContent || '').trim().toLowerCase();" +
                "        if (t === 'repost' || t === 'retweet') { c = items[i]; break; }" +
                "      }" +
                "    }" +
                "    if (c) {" +
                "      var r = c.getBoundingClientRect();" +
                "      if (r.width > 10 && r.height > 10) {" +
                "        return JSON.stringify({status:'READY_REPOST', vx:r.left+r.width/2, vy:r.top+r.height/2, vw:vw, vh:vh});" +
                "      }" +
                "    }" +
                "    return JSON.stringify({status:'NOT_FOUND'});" +
                "  } catch(e) { return JSON.stringify({status:'ERR'}); }" +
                "})();";

        evalJsOnWebContents(tab, wc, js, new JsCallback() {
            @Override
            public void onResult(String raw) {
                if (!isRunning || state != STATE_SOCIAL_REPOST_CONFIRM) return;
                JSONObject obj = parseJsJson(raw);
                if ("READY_REPOST".equals(obj.optString("status"))) {
                    float vx = (float) obj.optDouble("vx", -1);
                    float vy = (float) obj.optDouble("vy", -1);
                    float vw = (float) obj.optDouble("vw", 360);
                    float vh = (float) obj.optDouble("vh", 640);
                    setStatus("Tapping Repost...");
                    persistAndNotify();

                    boolean tapped = false;
                    if (canUseRealTouch && vx > 4f && vy > 4f) {
                        tapped = dispatchRealTapOnKiwiViewport(wc, vx, vy, vw, vh);
                    }
                    if (!tapped || repostConfirmRetries > 0) {
                        evalJsOnWebContents(tab, wc,
                                "(function(){try{" + JS_CLICK_HELPER +
                                "var c=document.querySelector('[data-testid=\"retweetConfirm\"]');" +
                                "if(!c){var its=document.querySelectorAll('[role=\"menuitem\"],button,[role=\"button\"]');for(var i=0;i<its.length;i++){var t=(its[i].innerText||its[i].textContent||'').trim().toLowerCase();if(t==='repost'||t==='retweet'){c=its[i];break;}}}" +
                                "if(c)__ghSmartClick(c);}catch(e){}})();",
                                null);
                    }
                    state = STATE_SOCIAL_VERIFY;
                    stateEnteredAtMs = SystemClock.elapsedRealtime();
                    scheduleNextTick(2100L);
                } else {
                    repostConfirmRetries++;
                    if (repostConfirmRetries >= 3) {
                        // Sheet didn't open -> go back and tap Retweet button again
                        state = STATE_SOCIAL_ACTION;
                        stateEnteredAtMs = SystemClock.elapsedRealtime();
                        scheduleNextTick(800L);
                    } else {
                        scheduleNextTick(700L);
                    }
                }
            }
        });
    }

    private void verifySocialActionOnWebContents(final Object tab, final Object wc, final Object tabModel, final boolean isHeadless) {
        final boolean isFollowMode = (activeMode == MODE_FOLLOWERS);
        final String targetStatusId = extractTweetStatusId(trackedSocialUrl);
        final String js;
        if (isFollowMode) {
            js =
                "(function(){" +
                "  try {" +
                "    if (document.querySelector('[data-testid$=\"-unfollow\"]')) return JSON.stringify({done:true});" +
                "    var btns = document.querySelectorAll('button, [role=\"button\"]');" +
                "    for (var i = 0; i < btns.length; i++) {" +
                "      var t = (btns[i].innerText || btns[i].textContent || '').trim();" +
                "      if (t === 'Following' || t === 'Pending') return JSON.stringify({done:true});" +
                "    }" +
                "    return JSON.stringify({done:false});" +
                "  } catch(e) { return JSON.stringify({done:false}); }" +
                "})();";
        } else {
            final String safeId = targetStatusId.replace("'", "");
            js =
                "(function(){" +
                "  try {" +
                "    var articles = document.querySelectorAll('article[data-testid=\"tweet\"]');" +
                "    var targetId = '" + safeId + "';" +
                "    var focal = null;" +
                "    for (var i = 0; i < articles.length; i++) {" +
                "      if (articles[i].getAttribute('tabindex') === '-1') { focal = articles[i]; break; }" +
                "    }" +
                "    if (!focal && targetId) {" +
                "      for (var k = 0; k < articles.length; k++) {" +
                "        if (articles[k].querySelector('a[href*=\"' + targetId + '\"]')) { focal = articles[k]; break; }" +
                "      }" +
                "    }" +
                "    if (!focal && articles.length > 0) focal = articles[0];" +
                "    if (focal && focal.querySelector('[data-testid=\"unretweet\"]')) return JSON.stringify({done:true});" +
                "    if (document.querySelector('[data-testid=\"unretweet\"]')) return JSON.stringify({done:true});" +
                "    return JSON.stringify({done:false});" +
                "  } catch(e) { return JSON.stringify({done:false}); }" +
                "})();";
        }

        evalJsOnWebContents(tab, wc, js, new JsCallback() {
            @Override
            public void onResult(String raw) {
                if (!isRunning || state != STATE_SOCIAL_VERIFY) return;
                JSONObject obj = parseJsJson(raw);
                if (obj.optBoolean("done", false)) {
                    setStatus((isFollowMode ? "Followed!" : "Reposted!") + " Returning...");
                    persistAndNotify();
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!isRunning) return;
                            if (isHeadless) enterHeadlessVerifyCompletion();
                            else switchBackToTaskPulseTabAndVerify(tabModel);
                        }
                    }, 1200L);
                } else if (socialRetryCount < 3) {
                    socialRetryCount++;
                    state = STATE_SOCIAL_ACTION;
                    stateEnteredAtMs = SystemClock.elapsedRealtime();
                    setStatus("Retry " + (isFollowMode ? "Follow" : "Repost") + " (" + socialRetryCount + "/3)");
                    persistAndNotify();
                    scheduleNextTick(1000L);
                } else {
                    if (isHeadless) enterHeadlessVerifyCompletion();
                    else switchBackToTaskPulseTabAndVerify(tabModel);
                }
            }
        });
    }

    private void checkTaskPulseCompletionOnWebContents(final Object tab, final Object wc, final Object tabModel, final boolean isHeadless) {
        final String prevBalSafe = balanceBeforeTask.replace("'", "");
        final String js =
                "(function(){" +
                "  try {" +
                "    window.dispatchEvent(new Event('focus'));" +
                "    document.dispatchEvent(new Event('visibilitychange'));" +
                "    var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';" +
                "    var lower = bodyText.toLowerCase();" +
                "    var balMatch = bodyText.match(/\\$[0-9]+\\.[0-9]+/);" +
                "    var bal = balMatch ? balMatch[0] : '';" +
                "    var prevBal = '" + prevBalSafe + "';" +
                "    var hasToast = lower.indexOf('completed the task') >= 0 || lower.indexOf('added to your balance') >= 0 || lower.indexOf('you can close the social network tab') >= 0;" +
                "    var balChanged = (prevBal && bal && bal !== prevBal);" +
                "    return JSON.stringify({completed:(hasToast || balChanged), balance:bal});" +
                "  } catch(e) { return JSON.stringify({completed:false}); }" +
                "})();";

        evalJsOnWebContents(tab, wc, js, new JsCallback() {
            @Override
            public void onResult(String raw) {
                if (!isRunning || state != STATE_VERIFY_COMPLETION) return;
                JSONObject obj = parseJsJson(raw);
                String bal = obj.optString("balance", "");
                if (!bal.isEmpty()) currentBalance = bal;

                if (obj.optBoolean("completed", false)) {
                    if (activeMode == MODE_FOLLOWERS) followersDone++;
                    else retweetsDone++;
                    skipRowOffset = 0;
                    closeFinishedSocialTab(tabModel, isHeadless);

                    // Strictly alternate: 1 Follower <-> 1 Retweet
                    nextMode = (activeMode == MODE_FOLLOWERS) ? MODE_RETWEETS : MODE_FOLLOWERS;
                    String nextLabel = (nextMode == MODE_FOLLOWERS) ? "Followers" : "Retweets";
                    setStatus("Done! Next: " + nextLabel);
                    persistAndNotify();

                    state = STATE_IDLE;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (isRunning) loadCurrentCategoryPage();
                        }
                    }, 2800L);
                } else {
                    scheduleNextTick(1300L);
                }
            }
        });
    }

    private void handleUnverifiedTaskTimeout(Object tab, Object wc, Object tabModel, boolean isHeadless) {
        evalJsOnWebContents(tab, wc,
                "(function(){try{" + JS_CLICK_HELPER + "var els=document.querySelectorAll('button,a,[role=\"button\"]');for(var i=0;i<els.length;i++){var t=(els[i].innerText||els[i].textContent||'').trim().toLowerCase();if(t.indexOf('cancel and retry')>=0){__ghSmartClick(els[i]);break;}}}catch(e){}})();",
                null);
        closeFinishedSocialTab(tabModel, isHeadless);
        skipRowOffset = (skipRowOffset + 1) % 5;
        nextMode = (activeMode == MODE_FOLLOWERS) ? MODE_RETWEETS : MODE_FOLLOWERS;
        setStatus("Next category...");
        persistAndNotify();
        state = STATE_IDLE;
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isRunning) loadCurrentCategoryPage();
            }
        }, 2600L);
    }

    private void closeFinishedSocialTab(Object tabModel, boolean isHeadless) {
        if (isHeadless) {
            if (headlessSocialWc != null) loadUrlOnWebContents(headlessSocialWc, "about:blank");
            return;
        }
        if (tabModel == null || trackedSocialTabId < 0 || trackedSocialTabId == trackedTaskTabId) return;
        Object socialTab = findTabById(tabModel, trackedSocialTabId);
        if (socialTab != null) {
            String u = getTabUrl(socialTab);
            // Safety guard: NEVER close an extension tab or TaskPulse tab!
            if (isSocialUrl(u) && !isProtectedExtensionOrSystemTab(u)) {
                closeTabInModel(tabModel, socialTab);
            }
        }
        trackedSocialTabId = -1;
    }

    // =========================================================================
    // Real Android MotionEvent Tap & Smooth Finger Swipe-Up on Kiwi Viewport
    // =========================================================================

    private boolean isKiwiActivityVisibleOnScreen() {
        Activity act = StandaloneRuntimeEngine.findKiwiBrowserActivity();
        if (act == null || act.isFinishing()) return false;
        if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed()) return false;
        try {
            Window w = act.getWindow();
            if (w == null) return false;
            View decor = w.peekDecorView();
            return decor != null && decor.getWindowVisibility() == View.VISIBLE && decor.isShown();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private View findKiwiTouchTargetView(Activity act, Object webContents) {
        if (act == null) return null;
        // 1. Prefer CompositorViewHolder (runs Chromium's full native touch + scroll pipeline)
        try {
            Window w = act.getWindow();
            if (w != null) {
                View decor = w.peekDecorView();
                if (decor instanceof ViewGroup) {
                    View comp = findViewByClassNameHint((ViewGroup) decor, "CompositorViewHolder");
                    if (comp != null && comp.isShown() && comp.getWidth() > 100 && comp.getHeight() > 100) {
                        return comp;
                    }
                }
            }
        } catch (Throwable ignored) {}
        // 2. Try Chromium WebContents.getViewAndroidDelegate().getContainerView() -> exact ContentView
        if (webContents != null) {
            try {
                Object vad = invokeNoArg(webContents, "getViewAndroidDelegate");
                if (vad != null) {
                    Object cv = invokeNoArg(vad, "getContainerView");
                    if (cv instanceof View && ((View) cv).isShown() && ((View) cv).getWidth() > 100 && ((View) cv).getHeight() > 100) {
                        return (View) cv;
                    }
                }
            } catch (Throwable ignored) {}
        }
        // 3. Fallback to ContentView or DecorView
        try {
            Window w = act.getWindow();
            if (w == null) return null;
            View decor = w.peekDecorView();
            if (!(decor instanceof ViewGroup)) return null;
            View best = findViewByClassNameHint((ViewGroup) decor, "ContentView");
            if (best != null && best.isShown() && best.getWidth() > 100) return best;
            return decor;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private View findViewByClassNameHint(ViewGroup root, String hint) {
        if (root == null) return null;
        int count = root.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = root.getChildAt(i);
            if (child == null) continue;
            String cn = child.getClass().getName();
            if (cn.contains(hint) && !cn.contains("HudCanvasView")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findViewByClassNameHint((ViewGroup) child, hint);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findKiwiToolbarView(ViewGroup root) {
        if (root == null) return null;
        int count = root.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = root.getChildAt(i);
            if (child == null || !child.isShown() || child.getHeight() <= 0) continue;
            String cn = child.getClass().getName();
            if (cn.contains("ToolbarControlContainer") || cn.contains("ControlContainer") || cn.contains("ToolbarPhone")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findKiwiToolbarView((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Computes exact view-local (x, y) inside targetView for a CSS viewport point (cssX, cssY).
     * Automatically accounts for Kiwi Browser's top or bottom address bar!
     */
    private float[] mapCssViewportToTargetViewCoords(Activity act, View targetView, float cssX, float cssY, float cssW, float cssH) {
        if (targetView == null || cssW <= 1f || cssH <= 1f) return null;
        int viewW = targetView.getWidth();
        int viewH = targetView.getHeight();
        if (viewW <= 10 || viewH <= 10) return null;

        int[] targetLoc = new int[2];
        targetView.getLocationInWindow(targetLoc);

        float pageTopLocal = 0f;
        float pageBottomLocal = viewH;

        try {
            Window w = act.getWindow();
            View decor = (w != null) ? w.peekDecorView() : null;
            int decorH = (decor != null && decor.getHeight() > 0) ? decor.getHeight() : viewH;
            if (decor instanceof ViewGroup) {
                View toolbar = findKiwiToolbarView((ViewGroup) decor);
                if (toolbar != null && toolbar.isShown() && toolbar.getHeight() > 0) {
                    int[] tbLoc = new int[2];
                    toolbar.getLocationInWindow(tbLoc);
                    int tbTop = tbLoc[1];
                    int tbBottom = tbLoc[1] + toolbar.getHeight();
                    if (tbBottom < decorH / 2) {
                        // Top toolbar (standard Kiwi layout as seen in user's video!)
                        float overlap = tbBottom - targetLoc[1];
                        if (overlap > 0f && overlap < viewH * 0.4f) {
                            pageTopLocal = overlap;
                        }
                    } else if (tbTop > decorH / 2) {
                        // Bottom toolbar mode
                        float topOfBottomTb = tbTop - targetLoc[1];
                        if (topOfBottomTb > viewH * 0.5f && topOfBottomTb < viewH) {
                            pageBottomLocal = topOfBottomTb;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        float pageH = Math.max(100f, pageBottomLocal - pageTopLocal);
        float scaleX = viewW / cssW;
        // Use aspect-consistent Y scale clamped to visible page area
        float scaleY = pageH / cssH;
        float localX = Math.max(4f, Math.min(viewW - 4f, cssX * scaleX));
        float localY = Math.max(pageTopLocal + 4f, Math.min(pageBottomLocal - 4f, pageTopLocal + cssY * scaleY));
        return new float[]{ localX, localY };
    }

    private boolean dispatchRealTapOnKiwiViewport(Object webContents, float cssX, float cssY, float cssW, float cssH) {
        try {
            Activity act = StandaloneRuntimeEngine.findKiwiBrowserActivity();
            if (act == null || act.isFinishing()) return false;
            final View target = findKiwiTouchTargetView(act, webContents);
            if (target == null) return false;
            float[] pt = mapCssViewportToTargetViewCoords(act, target, cssX, cssY, cssW, cssH);
            if (pt == null) return false;
            final float x = pt[0];
            final float y = pt[1];
            final long downTime = SystemClock.uptimeMillis();

            MotionEvent down = buildFingerMotionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y);
            target.dispatchTouchEvent(down);
            down.recycle();

            if (mainHandler != null) {
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            long upTime = SystemClock.uptimeMillis();
                            MotionEvent up = buildFingerMotionEvent(downTime, upTime, MotionEvent.ACTION_UP, x, y);
                            target.dispatchTouchEvent(up);
                            up.recycle();
                        } catch (Throwable ignored) {}
                    }
                }, 65L);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void dispatchRealSwipeUpOnKiwiViewport(Object webContents) {
        try {
            Activity act = StandaloneRuntimeEngine.findKiwiBrowserActivity();
            if (act == null || act.isFinishing()) return;
            final View target = findKiwiTouchTargetView(act, webContents);
            if (target == null || target.getWidth() < 100 || target.getHeight() < 200) return;

            final float x = target.getWidth() * 0.52f;
            final float startY = target.getHeight() * 0.74f;
            final float endY = target.getHeight() * 0.28f;
            final int steps = 7;
            final long downTime = SystemClock.uptimeMillis();

            MotionEvent down = buildFingerMotionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY);
            target.dispatchTouchEvent(down);
            down.recycle();

            for (int i = 1; i <= steps; i++) {
                final float frac = (float) i / (float) steps;
                final float curY = startY + (endY - startY) * frac;
                final boolean isLast = (i == steps);
                final long delay = i * 32L;
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            long now = SystemClock.uptimeMillis();
                            MotionEvent move = buildFingerMotionEvent(downTime, now, MotionEvent.ACTION_MOVE, x, curY);
                            target.dispatchTouchEvent(move);
                            move.recycle();
                            if (isLast) {
                                MotionEvent up = buildFingerMotionEvent(downTime, now + 18L, MotionEvent.ACTION_UP, x, curY);
                                target.dispatchTouchEvent(up);
                                up.recycle();
                            }
                        } catch (Throwable ignored) {}
                    }
                }, delay);
            }
        } catch (Throwable ignored) {}
    }

    private static MotionEvent buildFingerMotionEvent(long downTime, long eventTime, int action, float x, float y) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = (action == MotionEvent.ACTION_UP) ? 0f : 0.85f;
        pc.size = 0.14f;
        return MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                new MotionEvent.PointerProperties[]{ pp },
                new MotionEvent.PointerCoords[]{ pc },
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
        );
    }

    // =========================================================================
    // Obfuscation-Proof Deep-BFS Kiwi Browser Chromium Reflection Helpers
    // =========================================================================

    private interface JsCallback {
        void onResult(String rawJson);
    }

    private Object resolveKiwiNormalTabModel() {
        Activity act = StandaloneRuntimeEngine.findKiwiBrowserActivity();
        if (act == null || act.isFinishing()) return null;
        if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed()) return null;

        // Return cached normal TabModel if owned by the same live Activity and still valid
        if (sCachedTabModelOwnerAct == act && sCachedNormalTabModel != null) {
            if (isNormalTabModel(sCachedNormalTabModel)) {
                return sCachedNormalTabModel;
            }
            sCachedNormalTabModel = null;
        }

        // 1. Try direct un-obfuscated methods first
        Object selector = invokeNoArg(act, "getTabModelSelector");
        if (selector == null) {
            Object supplier = invokeNoArg(act, "getTabModelSelectorSupplier");
            if (supplier != null) selector = invokeNoArg(supplier, "get");
        }
        if (selector != null) {
            Object normal = extractNormalModelFromSelector(selector);
            if (normal != null) {
                sCachedTabModelOwnerAct = act;
                sCachedNormalTabModel = normal;
                return normal;
            }
        }
        Object directModel = invokeNoArg(act, "getCurrentTabModel");
        if (isNormalTabModel(directModel)) {
            sCachedTabModelOwnerAct = act;
            sCachedNormalTabModel = directModel;
            return directModel;
        }

        // 2. Bounded Deep-BFS from ChromeTabbedActivity (handles Activity -> Orchestrator -> Selector -> TabModel!)
        Object found = deepBfsFindNormalTabModel(act, 6, 550);
        if (found != null) {
            sCachedTabModelOwnerAct = act;
            sCachedNormalTabModel = found;
            return found;
        }
        return null;
    }

    private Object deepBfsFindNormalTabModel(Object root, int maxDepth, int maxNodes) {
        if (root == null) return null;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        ArrayDeque<Object> queue = new ArrayDeque<Object>();
        ArrayDeque<Integer> depths = new ArrayDeque<Integer>();
        visited.add(root);
        queue.add(root);
        depths.add(0);

        int inspected = 0;
        while (!queue.isEmpty() && inspected < maxNodes) {
            Object cur = queue.poll();
            int depth = depths.poll();
            inspected++;

            if (cur == null) continue;
            if (cur != root && isNormalTabModel(cur)) {
                return cur;
            }
            if (depth >= maxDepth) continue;

            // ONLY unwrap Chromium Supplier classes (never call Future.get() or arbitrary .get()!)
            if (cur != root) {
                String cName = cur.getClass().getName();
                if (cName.contains("Supplier") || cName.contains("supplier")) {
                    try {
                        Method getM = cur.getClass().getMethod("get");
                        if (getM.getParameterTypes().length == 0 && !getM.getReturnType().isPrimitive()) {
                            getM.setAccessible(true);
                            Object supplied = getM.invoke(cur);
                            enqueueIfTraversable(supplied, depth + 1, visited, queue, depths);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            if (cur instanceof List) {
                List<?> list = (List<?>) cur;
                int lim = Math.min(list.size(), 16);
                for (int i = 0; i < lim; i++) {
                    enqueueIfTraversable(list.get(i), depth + 1, visited, queue, depths);
                }
                continue;
            }
            if (cur instanceof Object[]) {
                Object[] arr = (Object[]) cur;
                int lim = Math.min(arr.length, 16);
                for (int i = 0; i < lim; i++) {
                    enqueueIfTraversable(arr[i], depth + 1, visited, queue, depths);
                }
                continue;
            }

            // Scan declared fields up to 5 superclasses
            Class<?> cls = cur.getClass();
            for (int sup = 0; sup < 5 && cls != null && cls != Object.class; sup++, cls = cls.getSuperclass()) {
                String clsName = cls.getName();
                if (clsName.startsWith("android.") || clsName.startsWith("androidx.") || clsName.startsWith("java.")) {
                    break;
                }
                Field[] fields;
                try { fields = cls.getDeclaredFields(); } catch (Throwable t) { continue; }
                for (Field f : fields) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft == String.class) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(cur);
                        enqueueIfTraversable(val, depth + 1, visited, queue, depths);
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private void enqueueIfTraversable(Object val, int nextDepth, Set<Object> visited,
                                      ArrayDeque<Object> queue, ArrayDeque<Integer> depths) {
        if (val == null || visited.contains(val)) return;
        if (val instanceof View || val instanceof Context || val instanceof Class || val instanceof CharSequence
                || val instanceof Number || val instanceof Boolean || val instanceof android.graphics.drawable.Drawable
                || val instanceof android.graphics.Bitmap || val instanceof Handler || val instanceof Looper) {
            return;
        }
        String cn = val.getClass().getName();
        if (cn.startsWith("android.") || cn.startsWith("dalvik.") || cn.startsWith("sun.")) return;
        if (cn.startsWith("java.") && !(val instanceof List)) return;
        visited.add(val);
        queue.add(val);
        depths.add(nextDepth);
    }

    private Object extractNormalModelFromSelector(Object candidate) {
        if (candidate == null) return null;
        Object normal = invokeOneBoolArg(candidate, "getModel", false);
        if (isNormalTabModel(normal)) return normal;
        Object cur = invokeNoArg(candidate, "getCurrentModel");
        if (isNormalTabModel(cur)) return cur;
        return null;
    }

    private boolean isNormalTabModel(Object obj) {
        if (!isTabModelLike(obj)) return false;
        Object incog = invokeNoArg(obj, "isIncognito");
        if (incog instanceof Boolean && ((Boolean) incog).booleanValue()) {
            return false;
        }
        return true;
    }

    private boolean isTabModelLike(Object obj) {
        if (obj == null) return false;
        return findMethodNoArgReturningInt(obj, "getCount") != null
                && findMethodOneIntArgReturningObject(obj, "getTabAt") != null;
    }

    private int getTabCount(Object tabModel) {
        Object res = invokeNoArg(tabModel, "getCount");
        return (res instanceof Integer) ? (Integer) res : 0;
    }

    private Object getTabAt(Object tabModel, int idx) {
        if (tabModel == null || idx < 0) return null;
        try {
            Method m = findMethodOneIntArgReturningObject(tabModel, "getTabAt");
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(tabModel, idx);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private int getSelectedTabIndex(Object tabModel) {
        Object res = invokeNoArg(tabModel, "index");
        return (res instanceof Integer) ? (Integer) res : -1;
    }

    private Object getCurrentTab(Object tabModel) {
        int idx = getSelectedTabIndex(tabModel);
        if (idx >= 0 && idx < getTabCount(tabModel)) {
            return getTabAt(tabModel, idx);
        }
        return getTabCount(tabModel) > 0 ? getTabAt(tabModel, 0) : null;
    }

    private int getTabId(Object tab) {
        Object res = invokeNoArg(tab, "getId");
        return (res instanceof Integer) ? (Integer) res : System.identityHashCode(tab);
    }

    private String getTabTitle(Object tab, Object wc) {
        Object t = invokeNoArg(tab, "getTitle");
        if (t instanceof String) return (String) t;
        t = invokeNoArg(wc, "getTitle");
        return (t instanceof String) ? (String) t : "";
    }

    private String getTabUrl(Object tab) {
        if (tab == null) return "";
        Object u = invokeNoArg(tab, "getUrlString");
        if (u instanceof String && !((String) u).isEmpty()) return (String) u;
        Object gurl = invokeNoArg(tab, "getUrl");
        String spec = extractGurlSpec(gurl);
        if (!spec.isEmpty()) return spec;
        Object wc = getTabWebContents(tab);
        return getWebContentsUrl(wc);
    }

    private String getWebContentsUrl(Object wc) {
        if (wc == null) return "";
        Object s = invokeNoArg(wc, "getVisibleUrlString");
        if (s instanceof String && !((String) s).isEmpty()) return (String) s;
        String spec = extractGurlSpec(invokeNoArg(wc, "getVisibleUrl"));
        if (!spec.isEmpty()) return spec;
        spec = extractGurlSpec(invokeNoArg(wc, "getLastCommittedUrl"));
        if (!spec.isEmpty()) return spec;
        Object s2 = invokeNoArg(wc, "getLastCommittedUrlString");
        return (s2 instanceof String) ? (String) s2 : "";
    }

    private String extractGurlSpec(Object gurl) {
        if (gurl == null) return "";
        if (gurl instanceof String) return (String) gurl;
        Object s = invokeNoArg(gurl, "getSpec");
        if (s instanceof String && !((String) s).isEmpty()) return (String) s;
        Object s2 = invokeNoArg(gurl, "getValidSpecOrEmpty");
        if (s2 instanceof String && !((String) s2).isEmpty()) return (String) s2;
        String ts = gurl.toString();
        if (ts != null && (ts.startsWith("http://") || ts.startsWith("https://") || ts.startsWith("chrome"))) {
            return ts;
        }
        return "";
    }

    private Object getTabWebContents(Object tab) {
        if (tab == null) return null;
        Object wc = invokeNoArg(tab, "getWebContents");
        if (wc != null) return wc;
        Class<?> cls = tab.getClass();
        for (int depth = 0; depth < 5 && cls != null && cls != Object.class; depth++, cls = cls.getSuperclass()) {
            try {
                for (Field f : cls.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(tab);
                    if (v != null && hasOnEvaluateJsResultMethod(v.getClass())) {
                        return v;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private boolean hasOnEvaluateJsResultMethod(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if ("onEvaluateJavaScriptResult".equals(m.getName())) return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Safe 2-Tier R8-Proof JavaScript Execution Engine (ZERO native JNI guessing!)
    // -------------------------------------------------------------------------

    private void evalJsOnWebContents(final Object tab, final Object webContents, final String script, final JsCallback cb) {
        if (webContents == null || script == null) {
            if (cb != null) cb.onResult(null);
            return;
        }

        if (sPreferLoadUrlJsFallback) {
            evalJsViaLoadUrlTitleChannel(tab, webContents, script, cb);
            return;
        }

        try {
            // Tier 1: Discover exact JavaScriptCallback interface from @CalledByNative onEvaluateJavaScriptResult
            if (sCachedJsCallbackClass == null) {
                for (Class<?> c = webContents.getClass(); c != null && c != Object.class && sCachedJsCallbackClass == null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if ("onEvaluateJavaScriptResult".equals(m.getName()) && m.getParameterTypes().length == 2) {
                            Class<?> cand = m.getParameterTypes()[1];
                            if (cand != null && cand != Object.class && cand.isInterface()) {
                                sCachedJsCallbackClass = cand;
                                break;
                            }
                        }
                    }
                }
            }

            if (sCachedEvalJsMethod == null && sCachedJsCallbackClass != null) {
                Method best = null;
                Method fallback = null;
                for (Class<?> c = webContents.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    Method[] ms;
                    try { ms = c.getDeclaredMethods(); } catch (Throwable t) { continue; }
                    for (Method m : ms) {
                        if (Modifier.isStatic(m.getModifiers())) continue;
                        if ("onEvaluateJavaScriptResult".equals(m.getName())) continue;
                        Class<?>[] pt = m.getParameterTypes();
                        // STRICT exact interface match — NEVER match Object.class!
                        if (pt.length == 2 && pt[0] == String.class && pt[1] == sCachedJsCallbackClass) {
                            if ("evaluateJavaScript".equals(m.getName())) {
                                best = m;
                                break;
                            } else if ("evaluateJavaScriptForTests".equals(m.getName())) {
                                fallback = m;
                            } else if (best == null) {
                                best = m;
                            }
                        }
                    }
                    if (best != null && "evaluateJavaScript".equals(best.getName())) break;
                }
                sCachedEvalJsMethod = (best != null) ? best : fallback;
            }

            if (sCachedEvalJsMethod != null && sCachedJsCallbackClass != null) {
                final AtomicBoolean delivered = new AtomicBoolean(false);
                Object proxyCb = null;
                if (cb != null) {
                    proxyCb = Proxy.newProxyInstance(
                            sCachedJsCallbackClass.getClassLoader(),
                            new Class<?>[]{ sCachedJsCallbackClass },
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) {
                                    if (args != null && args.length == 1 && args[0] instanceof String) {
                                        if (delivered.compareAndSet(false, true)) {
                                            final String res = (String) args[0];
                                            if (mainHandler != null) {
                                                mainHandler.post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        cb.onResult(res);
                                                    }
                                                });
                                            } else {
                                                cb.onResult(res);
                                            }
                                        }
                                    }
                                    return null;
                                }
                            }
                    );
                }
                sCachedEvalJsMethod.setAccessible(true);
                sCachedEvalJsMethod.invoke(webContents, script, proxyCb);

                // Watchdog: if Chromium's evaluateJavaScript didn't fire callback, seamlessly use Tier 2 javascript: URL!
                if (cb != null && mainHandler != null) {
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (delivered.compareAndSet(false, true)) {
                                evalJsViaLoadUrlTitleChannel(tab, webContents, script, cb);
                            }
                        }
                    }, 1500L);
                }
                return;
            }
        } catch (Throwable ignored) {}

        // Tier 2 (Universal R8-proof): javascript: URL via NavigationController/Tab loadUrl + @CalledByNative getTitle()!
        evalJsViaLoadUrlTitleChannel(tab, webContents, script, cb);
    }

    /**
     * Universal safe fallback that works on EVERY Chromium/Kiwi release build even when R8 strips
     * WebContents.evaluateJavaScript: executes `javascript:...;void(0);` via NavigationController/Tab
     * loadUrl and reads back JSON via @CalledByNative TabImpl.getTitle().
     */
    private void evalJsViaLoadUrlTitleChannel(final Object tab, final Object wc, String script, final JsCallback cb) {
        final String token = "__GHJS" + (SystemClock.uptimeMillis() % 10000L) + "__";
        final String rawJs = (cb != null)
                ? "(function(){var __ot=document.title;try{var __r=" + script + ";document.title='" + token + "'+(__r==null?'':String(__r));setTimeout(function(){if(document.title.indexOf('" + token + "')===0)document.title=__ot;},1100);}catch(e){}})();void(0);"
                : "(function(){try{" + script + "}catch(e){}})();void(0);";
        // Percent-encode '%' and '#' so Chromium's javascript: URL parser never corrupts modulo (%) or selectors
        final String wrapped = "javascript:" + rawJs
                .replace("%", "%25")
                .replace("#", "%23")
                .replace("\n", " ")
                .replace("\r", " ");

        boolean launched = loadUrlOnWebContents(wc, wrapped);
        if (!launched && tab != null) launched = loadUrlOnTarget(tab, wrapped);
        if (!launched || cb == null) {
            if (cb != null) cb.onResult(null);
            return;
        }
        if (mainHandler != null) {
            pollTitleChannelResult(tab, wc, token, cb, 0);
        } else {
            cb.onResult(null);
        }
    }

    private void pollTitleChannelResult(final Object tab, final Object wc, final String token,
                                        final JsCallback cb, final int attempt) {
        long delayMs = (attempt == 0) ? 200L : ((attempt == 1) ? 280L : 420L);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                String title = getTabTitle(tab, wc);
                if (title != null && title.startsWith(token)) {
                    sPreferLoadUrlJsFallback = true;
                    cb.onResult(title.substring(token.length()));
                } else if (attempt < 2) {
                    pollTitleChannelResult(tab, wc, token, cb, attempt + 1);
                } else {
                    cb.onResult(null);
                }
            }
        }, delayMs);
    }

    private void keepWebContentsAwake(Object wc) {
        if (wc == null) return;
        invokeNoArg(wc, "onShow");
    }

    private void navigateTabToUrl(Object tab, Object wc, String url) {
        if (url == null || url.isEmpty()) return;
        if (loadUrlOnWebContents(wc, url)) return;
        if (loadUrlOnTarget(tab, url)) return;
        String curUrl = getTabUrl(tab);
        if (wc != null && curUrl != null && (curUrl.startsWith("http://") || curUrl.startsWith("https://"))) {
            String safeUrl = url.replace("'", "\\'");
            evalJsOnWebContents(tab, wc, "(function(){try{window.location.href='" + safeUrl + "';}catch(e){}})();", null);
            return;
        }
        try {
            Activity act = StandaloneRuntimeEngine.findKiwiBrowserActivity();
            if (act != null) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.setPackage(getPackageName());
                i.putExtra("com.android.browser.application_id", getPackageName());
                i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                act.startActivity(i);
            }
        } catch (Throwable ignored) {}
    }

    private Object findNavigationControllerOnWebContents(Object wc) {
        if (wc == null) return null;
        Object nav = invokeNoArg(wc, "getNavigationController");
        if (nav != null) return nav;
        // R8-proof discovery: NavigationControllerImpl ALWAYS has @CalledByNative addToNavigationHistory / create!
        for (Class<?> c = wc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft == String.class) continue;
                    f.setAccessible(true);
                    Object v = f.get(wc);
                    if (v != null && isNavigationControllerLike(v.getClass())) {
                        return v;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private boolean isNavigationControllerLike(Class<?> cls) {
        if (cls == null) return false;
        String cn = cls.getName().toLowerCase(Locale.US);
        if (cn.contains("navigationcontroller")) return true;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    String mn = m.getName();
                    if ("addToNavigationHistory".equals(mn) || "createNavigationEntry".equals(mn)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private boolean loadUrlOnWebContents(Object wc, String url) {
        if (wc == null || url == null) return false;
        Object nav = findNavigationControllerOnWebContents(wc);
        if (nav == null) return false;

        // Fast path if we already resolved NavigationControllerImpl's loadUrl(LoadUrlParams)
        if (sCachedNavLoadUrlMethod != null && sCachedLoadUrlParamsClass != null
                && sCachedNavLoadUrlMethod.getDeclaringClass().isInstance(nav)) {
            try {
                Object params = constructLoadUrlParams(sCachedLoadUrlParamsClass, url);
                if (params != null) {
                    sCachedNavLoadUrlMethod.setAccessible(true);
                    sCachedNavLoadUrlMethod.invoke(nav, params);
                    return true;
                }
            } catch (Throwable ignored) {}
        }

        // 1. Try named loadUrl first
        if (loadUrlOnTarget(nav, url)) return true;

        // 2. R8-proof structural match on NavigationControllerImpl:
        //    loadUrl(LoadUrlParams) takes a non-JDK POJO with a (String) or (String, int) ctor and >= 5 fields
        //    (distinguishing LoadUrlParams from small value types like GURL). Return type is void (older) or NavigationHandle (Chromium 118+).
        try {
            for (Class<?> c = nav.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    Class<?> rt = m.getReturnType();
                    if (rt.isPrimitive() && rt != void.class) continue;
                    if (rt == String.class || rt == Object.class) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length != 1) continue;
                    Class<?> pCls = pt[0];
                    if (pCls.isPrimitive() || pCls == String.class || pCls == Object.class || pCls.isInterface()) continue;
                    String pName = pCls.getName();
                    if (pName.startsWith("java.") || pName.startsWith("android.")) continue;
                    if (pName.toLowerCase(Locale.US).contains("gurl")) continue;
                    if (pCls.getDeclaredFields().length < 5) continue;
                    Object params = constructLoadUrlParams(pCls, url);
                    if (params != null) {
                        m.setAccessible(true);
                        m.invoke(nav, params);
                        sCachedNavLoadUrlMethod = m;
                        sCachedLoadUrlParamsClass = pCls;
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean loadUrlOnTarget(Object target, String url) {
        if (target == null || url == null) return false;
        try {
            // 1. Named loadUrl(LoadUrlParams)
            for (Method m : target.getClass().getMethods()) {
                if ("loadUrl".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    Class<?> pCls = m.getParameterTypes()[0];
                    Object params = constructLoadUrlParams(pCls, url);
                    if (params != null) {
                        m.setAccessible(true);
                        m.invoke(target, params);
                        if (isNavigationControllerLike(target.getClass())) {
                            sCachedNavLoadUrlMethod = m;
                            sCachedLoadUrlParamsClass = pCls;
                        }
                        return true;
                    }
                }
            }
            // 2. Known LoadUrlParams class (either cached or un-obfuscated)
            Class<?> loadUrlParamsCls = sCachedLoadUrlParamsClass;
            if (loadUrlParamsCls == null) {
                try {
                    loadUrlParamsCls = Class.forName("org.chromium.content_public.browser.LoadUrlParams", false, target.getClass().getClassLoader());
                } catch (Throwable ignored) {}
            }
            if (loadUrlParamsCls != null) {
                for (Method m : target.getClass().getMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 1 && pt[0] == loadUrlParamsCls) {
                        Object params = constructLoadUrlParams(loadUrlParamsCls, url);
                        if (params != null) {
                            m.setAccessible(true);
                            m.invoke(target, params);
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private Object constructLoadUrlParams(Class<?> paramCls, String url) {
        if (paramCls == String.class) return url;
        try {
            Constructor<?> c1 = paramCls.getDeclaredConstructor(String.class);
            c1.setAccessible(true);
            return c1.newInstance(url);
        } catch (Throwable ignored) {}
        try {
            Constructor<?> c2 = paramCls.getDeclaredConstructor(String.class, int.class);
            c2.setAccessible(true);
            return c2.newInstance(url, 0);
        } catch (Throwable ignored) {}
        return null;
    }

    private Object createNewKiwiTab(String url) {
        try {
            Activity act = StandaloneRuntimeEngine.findKiwiBrowserActivity();
            if (act == null) return null;
            Object creator = invokeOneBoolArg(act, "getTabCreator", false);
            if (creator == null) {
                Object selector = invokeNoArg(act, "getTabModelSelector");
                if (selector != null) creator = invokeOneBoolArg(selector, "getTabCreator", false);
            }
            if (creator != null) {
                for (Method m : creator.getClass().getMethods()) {
                    if ("launchUrl".equals(m.getName()) && m.getParameterTypes().length == 2
                            && m.getParameterTypes()[0] == String.class && m.getParameterTypes()[1] == int.class) {
                        m.setAccessible(true);
                        return m.invoke(creator, url, 2);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * R8-proof tab switcher: tries un-obfuscated setIndex first, then discovers renamed
     * setIndex(int, int, boolean) or setIndex(int, int) on TabModel and verifies index() changed!
     */
    private void selectTabInModel(Object tabModel, int index) {
        if (tabModel == null || index < 0) return;
        int count = getTabCount(tabModel);
        if (count <= 0 || index >= count) return;
        if (getSelectedTabIndex(tabModel) == index) return;

        // 0. Use cached winning method if already discovered
        if (sCachedSetIndexMethod != null) {
            try {
                sCachedSetIndexMethod.setAccessible(true);
                if (sCachedSetIndexArity == 3) {
                    sCachedSetIndexMethod.invoke(tabModel, index, 3, false);
                } else if (sCachedSetIndexArity == 2) {
                    sCachedSetIndexMethod.invoke(tabModel, index, 3);
                } else if (sCachedSetIndexArity == 1) {
                    sCachedSetIndexMethod.invoke(tabModel, index);
                }
                if (getSelectedTabIndex(tabModel) == index) return;
            } catch (Throwable ignored) {
                sCachedSetIndexMethod = null;
            }
        }

        // 1. Named setIndex overloads
        try {
            for (Method m : tabModel.getClass().getMethods()) {
                if ("setIndex".equals(m.getName())) {
                    Class<?>[] pt = m.getParameterTypes();
                    m.setAccessible(true);
                    if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class && pt[2] == boolean.class) {
                        m.invoke(tabModel, index, 3, false);
                        sCachedSetIndexMethod = m;
                        sCachedSetIndexArity = 3;
                        return;
                    } else if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                        m.invoke(tabModel, index, 3);
                        sCachedSetIndexMethod = m;
                        sCachedSetIndexArity = 2;
                        return;
                    } else if (pt.length == 1 && pt[0] == int.class) {
                        m.invoke(tabModel, index);
                        sCachedSetIndexMethod = m;
                        sCachedSetIndexArity = 1;
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2. R8-obfuscated Chromium 105-135: void xxx(int, int, boolean) is UNIQUE on TabModel!
        try {
            for (Method m : tabModel.getClass().getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class && pt[2] == boolean.class) {
                    m.setAccessible(true);
                    m.invoke(tabModel, index, 3, false);
                    if (getSelectedTabIndex(tabModel) == index) {
                        sCachedSetIndexMethod = m;
                        sCachedSetIndexArity = 3;
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 3. R8-obfuscated older Chromium: void xxx(int, int) on TabModel
        try {
            for (Method m : tabModel.getClass().getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
                String mn = m.getName().toLowerCase(Locale.US);
                if (mn.contains("move") || mn.contains("close") || mn.contains("remove")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                    m.setAccessible(true);
                    m.invoke(tabModel, index, 3);
                    if (getSelectedTabIndex(tabModel) == index) {
                        sCachedSetIndexMethod = m;
                        sCachedSetIndexArity = 2;
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void closeTabInModel(Object tabModel, Object tab) {
        if (tabModel == null || tab == null) return;
        // 1. @CalledByNative protected closeTabAt(int index) on TabModelJniBridge (never obfuscated!)
        try {
            int idx = findTabIndex(tabModel, tab);
            if (idx >= 0) {
                for (Class<?> c = tabModel.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    try {
                        Method m = c.getDeclaredMethod("closeTabAt", int.class);
                        m.setAccessible(true);
                        m.invoke(tabModel, idx);
                        return;
                    } catch (NoSuchMethodException ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        // 2. Public closeTab overloads
        try {
            for (Method m : tabModel.getClass().getMethods()) {
                if ("closeTab".equals(m.getName())) {
                    Class<?>[] pt = m.getParameterTypes();
                    m.setAccessible(true);
                    if (pt.length == 1 && pt[0].isInstance(tab)) {
                        m.invoke(tabModel, tab);
                        return;
                    } else if (pt.length == 4 && pt[0].isInstance(tab)
                            && pt[1] == boolean.class && pt[2] == boolean.class && pt[3] == boolean.class) {
                        m.invoke(tabModel, tab, false, false, false);
                        return;
                    } else if (pt.length == 5 && pt[0].isInstance(tab)
                            && pt[2] == boolean.class && pt[3] == boolean.class && pt[4] == boolean.class) {
                        m.invoke(tabModel, tab, null, false, false, false);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
        // 3. Fallback: window.close()
        Object wc = getTabWebContents(tab);
        if (wc != null) {
            evalJsOnWebContents(tab, wc, "try{window.close();}catch(e){}", null);
        }
    }

    // --- Headless Real Kiwi Chromium WebContents (ONLY when Kiwi Activity is swiped out of Recents!) ---

    private boolean ensureHeadlessChromiumWebContents() {
        if (headlessTaskWc != null && headlessSocialWc != null) return true;
        try {
            ClassLoader cl = getClassLoader();
            Object profile = resolveChromiumProfile(cl);
            if (profile == null) return false;
            if (headlessTaskWc == null) headlessTaskWc = createChromiumWebContents(cl, profile);
            if (headlessSocialWc == null) headlessSocialWc = createChromiumWebContents(cl, profile);
            return headlessTaskWc != null && headlessSocialWc != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object resolveChromiumProfile(ClassLoader cl) {
        String[] candidates = new String[]{
                "org.chromium.chrome.browser.profiles.Profile",
                "org.chromium.chrome.browser.profiles.ProfileManager"
        };
        for (String clsName : candidates) {
            try {
                Class<?> c = Class.forName(clsName, false, cl);
                for (Method m : c.getMethods()) {
                    if (("getLastUsedRegularProfile".equals(m.getName()) || "getLastUsedProfile".equals(m.getName()))
                            && m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        Object p = m.invoke(null);
                        if (p != null) return p;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Object createChromiumWebContents(ClassLoader cl, Object profile) {
        try {
            Class<?> factory = Class.forName("org.chromium.chrome.browser.content.WebContentsFactory", false, cl);
            for (Method m : factory.getMethods()) {
                if ("createWebContents".equals(m.getName())) {
                    Class<?>[] pt = m.getParameterTypes();
                    m.setAccessible(true);
                    if (pt.length == 2 && pt[0].isInstance(profile) && pt[1] == boolean.class) {
                        return m.invoke(null, profile, false);
                    } else if (pt.length == 3 && pt[0].isInstance(profile) && pt[1] == boolean.class && pt[2] == boolean.class) {
                        return m.invoke(null, profile, false, false);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void destroyHeadlessWebContents() {
        if (headlessTaskWc != null) {
            invokeNoArg(headlessTaskWc, "destroy");
            headlessTaskWc = null;
        }
        if (headlessSocialWc != null) {
            invokeNoArg(headlessSocialWc, "destroy");
            headlessSocialWc = null;
        }
    }

    // --- Tab lookup helpers ---

    private Object findTaskPulseTab(Object tabModel) {
        int count = getTabCount(tabModel);
        for (int i = count - 1; i >= 0; i--) {
            Object tab = getTabAt(tabModel, i);
            String u = getTabUrl(tab).toLowerCase(Locale.US);
            if (u.contains("taskpul.se") && !u.startsWith("chrome-extension://")) {
                return tab;
            }
        }
        return null;
    }

    private Object findTaskPulseTabByIdOrUrl(Object tabModel) {
        if (trackedTaskTabId >= 0) {
            Object t = findTabById(tabModel, trackedTaskTabId);
            if (t != null) return t;
        }
        return findTaskPulseTab(tabModel);
    }

    private Object findTabById(Object tabModel, int id) {
        if (tabModel == null || id < 0) return null;
        int count = getTabCount(tabModel);
        for (int i = 0; i < count; i++) {
            Object t = getTabAt(tabModel, i);
            if (t != null && getTabId(t) == id) return t;
        }
        return null;
    }

    private int findTabIndex(Object tabModel, Object target) {
        if (tabModel == null || target == null) return -1;
        int targetId = getTabId(target);
        int count = getTabCount(tabModel);
        for (int i = 0; i < count; i++) {
            Object t = getTabAt(tabModel, i);
            if (t != null && (t == target || getTabId(t) == targetId)) return i;
        }
        return -1;
    }

    private void snapshotExistingSocialTabIds(Object tabModel) {
        knownSocialTabIdsBeforeStart.clear();
        int count = getTabCount(tabModel);
        for (int i = 0; i < count; i++) {
            Object t = getTabAt(tabModel, i);
            if (t != null && isSocialUrl(getTabUrl(t))) {
                knownSocialTabIdsBeforeStart.add(getTabId(t));
            }
        }
    }

    private Object findNewlyOpenedSocialTab(Object tabModel) {
        int count = getTabCount(tabModel);
        Object cur = getCurrentTab(tabModel);
        if (cur != null && getTabId(cur) != trackedTaskTabId && isSocialUrl(getTabUrl(cur))
                && !knownSocialTabIdsBeforeStart.contains(getTabId(cur))) {
            return cur;
        }
        for (int i = count - 1; i >= 0; i--) {
            Object t = getTabAt(tabModel, i);
            if (t != null && getTabId(t) != trackedTaskTabId && isSocialUrl(getTabUrl(t))
                    && !knownSocialTabIdsBeforeStart.contains(getTabId(t))) {
                return t;
            }
        }
        return null;
    }

    private Object findAnySocialTab(Object tabModel) {
        int count = getTabCount(tabModel);
        for (int i = count - 1; i >= 0; i--) {
            Object t = getTabAt(tabModel, i);
            if (t != null && getTabId(t) != trackedTaskTabId && isSocialUrl(getTabUrl(t))) {
                return t;
            }
        }
        return null;
    }

    private static boolean isSocialUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String l = url.toLowerCase(Locale.US);
        return (l.startsWith("http://") || l.startsWith("https://"))
                && (l.contains("://x.com/") || l.contains("://www.x.com/")
                || l.contains("://mobile.x.com/") || l.contains("://twitter.com/")
                || l.contains("://mobile.twitter.com/") || l.contains("://t.co/"));
    }

    private static boolean isProtectedExtensionOrSystemTab(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(Locale.US);
        return l.startsWith("chrome-extension://") || l.startsWith("chrome://");
    }

    private static boolean urlsMatchCategory(String url, int mode) {
        if (url == null) return false;
        String l = url.toLowerCase(Locale.US);
        return (mode == MODE_FOLLOWERS) ? l.contains("taskpul.se/tasks/social/tw-followers")
                : l.contains("taskpul.se/tasks/social/tw-retweets");
    }

    // --- Reflection utilities ---

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeOneBoolArg(Object target, String name, boolean arg) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name, boolean.class);
            m.setAccessible(true);
            return m.invoke(target, arg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethodNoArgReturningInt(Object target, String preferredName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(preferredName);
            if (m.getReturnType() == int.class) return m;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Method findMethodOneIntArgReturningObject(Object target, String preferredName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(preferredName, int.class);
            if (!m.getReturnType().isPrimitive()) return m;
        } catch (Throwable ignored) {}
        return null;
    }

    // --- Notification, WakeLock, Keep-Alive Alarm & Persistence ---

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_ID,
                            "Kiwi TaskPulse Auto",
                            NotificationManager.IMPORTANCE_LOW
                    );
                    ch.setDescription("Runs TaskPulse Followers & Retweets inside Kiwi Browser");
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            } catch (Throwable ignored) {}
        }
    }

    private Notification buildNotification() {
        Intent launchIntent = null;
        try {
            launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        } catch (Throwable ignored) {}
        if (launchIntent == null) {
            launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.setPackage(getPackageName());
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int piFlags = Build.VERSION.SDK_INT >= 23
                ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent contentPi = PendingIntent.getActivity(this, 49101, launchIntent, piFlags);

        Intent stopIntent = new Intent(this, KiwiTaskPulseService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 49102, stopIntent, piFlags);

        String title = isRunning
                ? ("Kiwi Auto Task ON • F:" + followersDone + " R:" + retweetsDone
                    + (currentBalance.isEmpty() ? "" : (" • " + currentBalance)))
                : "Kiwi Auto Task OFF";

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(currentStatusText)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(isRunning)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi);

        if (Build.VERSION.SDK_INT >= 20) {
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Stop",
                    stopPi
            ).build());
        }
        return b.build();
    }

    private void startForegroundSafely() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            try { startForeground(NOTIF_ID, n); } catch (Throwable ignored) {}
        }
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {}
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ghostdevice:kiwi_tp");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
    }

    private void scheduleKeepAliveAlarm(long delayMs) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, KiwiTaskPulseService.class);
            i.setAction(ACTION_RESTART_KEEPALIVE);
            int piFlags = Build.VERSION.SDK_INT >= 23
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = (Build.VERSION.SDK_INT >= 26)
                    ? PendingIntent.getForegroundService(this, ALARM_REQ_CODE, i, piFlags)
                    : PendingIntent.getService(this, ALARM_REQ_CODE, i, piFlags);
            long triggerAt = SystemClock.elapsedRealtime() + delayMs;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
        } catch (Throwable ignored) {}
    }

    private void cancelKeepAliveAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, KiwiTaskPulseService.class);
            i.setAction(ACTION_RESTART_KEEPALIVE);
            int piFlags = Build.VERSION.SDK_INT >= 23
                    ? (PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.FLAG_NO_CREATE;
            PendingIntent pi = (Build.VERSION.SDK_INT >= 26)
                    ? PendingIntent.getForegroundService(this, ALARM_REQ_CODE, i, piFlags)
                    : PendingIntent.getService(this, ALARM_REQ_CODE, i, piFlags);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Throwable ignored) {}
    }

    private void setStatus(String text) {
        currentStatusText = text != null ? text : "";
        sLiveRunning = isRunning;
        sLiveFollowers = followersDone;
        sLiveRetweets = retweetsDone;
        sLiveBalance = currentBalance;
        sLiveStatus = currentStatusText;
    }

    private void loadPersistedState() {
        try {
            File f = new File(getFilesDir(), STATUS_FILE_NAME);
            if (!f.exists()) return;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            in.close();
            JSONObject obj = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            isRunning = obj.optBoolean("running", false);
            followersDone = obj.optInt("followers", 0);
            retweetsDone = obj.optInt("retweets", 0);
            nextMode = obj.optInt("nextMode", MODE_FOLLOWERS);
            currentBalance = obj.optString("balance", "");
            setStatus(obj.optString("status", "Idle"));
        } catch (Throwable ignored) {}
    }

    private void persistStateOnly() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("running", isRunning);
            obj.put("followers", followersDone);
            obj.put("retweets", retweetsDone);
            obj.put("nextMode", nextMode);
            obj.put("balance", currentBalance);
            obj.put("status", currentStatusText);
            File dst = new File(getFilesDir(), STATUS_FILE_NAME);
            FileOutputStream fos = new FileOutputStream(dst);
            fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {}
    }

    private void persistAndNotify() {
        persistStateOnly();
        updateNotification();
        StandaloneRuntimeEngine.notifyKiwiHudRefresh();
    }

    private static JSONObject parseJsJson(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return new JSONObject();
        try {
            String s = unquoteJsString(raw);
            if (s != null && s.startsWith("{")) return new JSONObject(s);
        } catch (Throwable ignored) {}
        return new JSONObject();
    }

    private static String unquoteJsString(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            try {
                return new JSONObject("{\"v\":" + s + "}").optString("v", "");
            } catch (Throwable ignored) {}
        }
        return s;
    }

    private static String extractTweetStatusId(String url) {
        if (url == null) return "";
        try {
            Matcher m = Pattern.compile("/status/(\\d+)").matcher(url);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}
        return "";
    }

    private static String safeMsg(Throwable t) {
        if (t == null || t.getMessage() == null) return "retry";
        String m = t.getMessage();
        return m.length() > 24 ? m.substring(0, 24) : m;
    }
}
