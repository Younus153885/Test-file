package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.i18n.currentAppLang
import com.example.ui.i18n.tr
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBgDarker
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardElevated
import com.example.ui.theme.CyberPurple
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.cyberShimmerBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

fun extractYouTubeVideoId(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null
    val directIdRegex = Regex("^[a-zA-Z0-9_-]{11}$")
    if (directIdRegex.matches(trimmed)) return trimmed

    return try {
        val normalized = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = Uri.parse(normalized)
        val host = uri.host?.lowercase().orEmpty()
        when {
            host.contains("youtu.be") -> {
                val seg = uri.pathSegments.firstOrNull()?.trim().orEmpty()
                if (seg.length >= 11) seg.take(11) else null
            }
            host.contains("youtube.com") || host.contains("youtube-nocookie.com") -> {
                val vParam = uri.getQueryParameter("v")?.trim()
                if (!vParam.isNullOrBlank() && vParam.length >= 11) {
                    vParam.take(11)
                } else {
                    val segments = uri.pathSegments
                    val markerIdx = segments.indexOfFirst {
                        it.equals("embed", ignoreCase = true) ||
                            it.equals("shorts", ignoreCase = true) ||
                            it.equals("live", ignoreCase = true) ||
                            it.equals("v", ignoreCase = true)
                    }
                    if (markerIdx != -1 && markerIdx + 1 < segments.size) {
                        val candidate = segments[markerIdx + 1].trim()
                        if (candidate.length >= 11) candidate.take(11) else null
                    } else {
                        null
                    }
                }
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

fun isYouTubeShortsUrl(rawUrl: String): Boolean {
    return rawUrl.contains("/shorts/", ignoreCase = true)
}

private suspend fun fetchYouTubeThumbnailBitmap(videoId: String): Bitmap? = withContext(Dispatchers.IO) {
    val urls = listOf(
        "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
        "https://img.youtube.com/vi/$videoId/0.jpg"
    )
    for (urlStr in urls) {
        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                conn.disconnect()
                if (bmp != null && bmp.width > 120) {
                    return@withContext bmp
                }
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
    }
    null
}

/**
 * Sleek, compact, single-line advanced Setup Video button bar.
 * No extra subtitle clutter or oversized blocks.
 */
@Composable
fun SetupVideoGuideBanner(
    title: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val _lang = currentAppLang()
    val displayTitle = title.trim().ifBlank {
        tr(
            "App Setup Video Guide",
            "অ্যাপ সেটআপ ভিডিও গাইড",
            "ऐप सेटअप वीडियो गाइड"
        )
    }
    val buttonLabel = tr("Watch", "ভিডিও দেখুন", "वीडियो देखें")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .cyberShimmerBorder(width = 1.35.dp, shape = RoundedCornerShape(12.dp), phaseOffset = 0.22f, accentColor = NeonCyan)
            .clickable { onClick() }
            .testTag("setup_video_banner_button"),
        shape = RoundedCornerShape(12.dp),
        color = CyberCard,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            NeonCyan.copy(alpha = 0.08f),
                            CyberPurple.copy(alpha = 0.12f),
                            CyberCard
                        )
                    )
                )
                .padding(horizontal = 10.dp, vertical = if (compact) 7.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sleek Compact Play Badge
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(CyberPurple, NeonCyan)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Setup Video",
                    tint = CyberBg,
                    modifier = Modifier.size(17.dp)
                )
            }

            Spacer(modifier = Modifier.width(9.dp))

            Text(
                text = displayTitle,
                color = TextPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Compact Pill Action Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                CyberPurple.copy(alpha = 0.32f),
                                NeonCyan.copy(alpha = 0.22f)
                            )
                        )
                    )
                    .cyberShimmerBorder(
                        width = 1.15.dp,
                        shape = RoundedCornerShape(8.dp),
                        phaseOffset = 0.58f,
                        accentColor = NeonCyan
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.OndemandVideo,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = buttonLabel,
                        color = NeonCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

private class YouTubePlayerBridge(
    private val onReady: () -> Unit
) {
    @JavascriptInterface
    fun notifyVideoReady() {
        onReady()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SetupVideoDialog(
    videoUrl: String,
    videoTitle: String,
    onDismiss: () -> Unit
) {
    val _lang = currentAppLang()
    val context = LocalContext.current
    val videoId = remember(videoUrl) { extractYouTubeVideoId(videoUrl) }
    val isShorts = remember(videoUrl) { isYouTubeShortsUrl(videoUrl) }
    var isExpanded by remember { mutableStateOf(false) }
    var isPlaybackStarted by remember { mutableStateOf(false) }
    var isPlayerReady by remember { mutableStateOf(false) }
    var thumbnailBitmap by remember(videoId) { mutableStateOf<Bitmap?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var customFullscreenView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val resolvedTitle = videoTitle.trim().ifBlank {
        tr(
            "App Setup Video Guide",
            "অ্যাপ সেটআপ ভিডিও গাইড",
            "ऐप सेटअप वीडियो गाइड"
        )
    }

    LaunchedEffect(videoId) {
        if (videoId != null) {
            thumbnailBitmap = fetchYouTubeThumbnailBitmap(videoId)
        }
    }

    // Fallback timeout so once user taps play, the player is revealed smoothly even if JS callback is delayed
    LaunchedEffect(isPlaybackStarted) {
        if (isPlaybackStarted && !isPlayerReady) {
            delay(2200L)
            isPlayerReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                customViewCallback?.onCustomViewHidden()
            } catch (_: Throwable) {}
            try {
                webViewRef?.apply {
                    stopLoading()
                    onPause()
                    loadUrl("about:blank")
                    clearHistory()
                    removeAllViews()
                    destroy()
                }
            } catch (_: Throwable) {}
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.84f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(if (isExpanded) 6.dp else 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isExpanded) {
                            Modifier.fillMaxHeight()
                        } else {
                            Modifier
                        }
                    )
                    .animateContentSize()
                    .cyberShimmerBorder(width = 1.5.dp, shape = RoundedCornerShape(20.dp), phaseOffset = 0.14f, accentColor = NeonCyan),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Compact Single-Line Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CyberBgDarker)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(listOf(CyberPurple, NeonCyan))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.OndemandVideo,
                                contentDescription = null,
                                tint = CyberBg,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(9.dp))

                        Text(
                            text = resolvedTitle,
                            color = TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyberCardElevated)
                                .cyberShimmerBorder(width = 1.1.dp, shape = RoundedCornerShape(8.dp), phaseOffset = 0.35f)
                                .clickable { isExpanded = !isExpanded },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isExpanded) "Exit Fullscreen" else "Expand Player",
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyberCardElevated)
                                .cyberShimmerBorder(width = 1.1.dp, shape = RoundedCornerShape(8.dp), phaseOffset = 0.65f)
                                .clickable { onDismiss() }
                                .testTag("close_setup_video_dialog_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Video",
                                tint = TextSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Video Player & Thumbnail Area
                    val playerModifier = when {
                        isExpanded -> Modifier
                            .fillMaxWidth()
                            .weight(1f)
                        isShorts -> Modifier
                            .fillMaxWidth()
                            .heightIn(min = 320.dp, max = 450.dp)
                        else -> Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    }

                    Box(
                        modifier = playerModifier.background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (videoId != null) {
                            // Clean isolated YouTube player script that hides page chrome on m.youtube.com
                            // and avoids WebView embed Error 152-4 completely.
                            val cleanPlayerJs = remember {
                                """
                                (function() {
                                  function applyCleanPlayer() {
                                    var styleId = 'phantom-yt-clean-player';
                                    if (!document.getElementById(styleId)) {
                                      var st = document.createElement('style');
                                      st.id = styleId;
                                      st.innerHTML =
                                        'html, body, ytm-app, #app, ytm-watch, .watch-main-col, .page-container { margin:0!important; padding:0!important; width:100%!important; height:100%!important; background:#000!important; overflow:hidden!important; }' +
                                        'ytm-mobile-topbar-renderer, .mobile-topbar-header, header, #header-bar, ytm-single-column-watch-next-results-renderer, .watch-below-the-player, #below-the-player, ytm-slim-video-metadata-section-renderer, ytm-item-section-renderer, ytm-pivot-bar-renderer, ytm-comments-entry-point-header-renderer, ytm-companion-slot, ytm-mealbar-promo-renderer, .related-chips-slot-wrapper, .ytp-OpenInApp, .ytm-open-in-app { display:none!important; visibility:hidden!important; height:0!important; max-height:0!important; overflow:hidden!important; pointer-events:none!important; }' +
                                        '#player-container-id, .player-container, #player, ytm-player, .html5-video-player, #shorts-player, ytm-reel-video-renderer { position:fixed!important; top:0!important; left:0!important; right:0!important; bottom:0!important; width:100vw!important; height:100vh!important; max-width:100vw!important; max-height:100vh!important; z-index:999999!important; background:#000!important; }' +
                                        'video.html5-main-video, video { width:100%!important; height:100%!important; object-fit:contain!important; background:#000!important; }';
                                      (document.head || document.documentElement).appendChild(st);
                                    }
                                    var v = document.querySelector('video');
                                    if (v) {
                                      if (!window.__phantomStarted) {
                                        window.__phantomStarted = true;
                                        v.muted = false;
                                        var playPromise = v.play();
                                        if (playPromise && playPromise.catch) {
                                          playPromise.catch(function() {
                                            var btn = document.querySelector('.ytp-large-play-button, .player-control-play-pause-icon, button[aria-label*="Play"]');
                                            if (btn) btn.click();
                                          });
                                        }
                                      }
                                      if (v.readyState >= 1 || !v.paused) {
                                        if (window.PhantomBridge && window.PhantomBridge.notifyVideoReady) {
                                          window.PhantomBridge.notifyVideoReady();
                                        }
                                      }
                                    }
                                  }
                                  applyCleanPlayer();
                                  if (!window.__phantomTimer) {
                                    window.__phantomTimer = setInterval(applyCleanPlayer, 250);
                                  }
                                })();
                                """.trimIndent()
                            }

                            if (isPlaybackStarted) {
                                val watchUrl = remember(videoId, isShorts) {
                                    if (isShorts) {
                                        "https://m.youtube.com/shorts/$videoId"
                                    } else {
                                        "https://m.youtube.com/watch?v=$videoId&autoplay=1"
                                    }
                                }

                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        val container = FrameLayout(ctx).apply {
                                            setBackgroundColor(AndroidColor.BLACK)
                                            layoutParams = ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        }
                                        val wv = WebView(ctx).apply {
                                            setBackgroundColor(AndroidColor.BLACK)
                                            layoutParams = FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.mediaPlaybackRequiresUserGesture = false
                                            settings.loadWithOverviewMode = true
                                            settings.useWideViewPort = true
                                            settings.javaScriptCanOpenWindowsAutomatically = false
                                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                            // Remove '; wv' marker so YouTube serves standard mobile HTML5 stream without Error 152-4
                                            settings.userAgentString =
                                                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"

                                            addJavascriptInterface(
                                                YouTubePlayerBridge {
                                                    post { isPlayerReady = true }
                                                },
                                                "PhantomBridge"
                                            )

                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    view: WebView?,
                                                    request: WebResourceRequest?
                                                ): Boolean {
                                                    val targetUrl = request?.url?.toString().orEmpty()
                                                    val scheme = request?.url?.scheme?.lowercase().orEmpty()
                                                    if (scheme == "intent" || scheme == "vnd.youtube") {
                                                        return true
                                                    }
                                                    val host = request?.url?.host?.lowercase().orEmpty()
                                                    return !(host.contains("youtube.com") ||
                                                        host.contains("youtube-nocookie.com") ||
                                                        host.contains("youtu.be") ||
                                                        host.contains("googlevideo.com") ||
                                                        host.contains("ytimg.com") ||
                                                        host.contains("ggpht.com") ||
                                                        host.contains("google.com"))
                                                }

                                                override fun onPageCommitVisible(view: WebView?, url: String?) {
                                                    super.onPageCommitVisible(view, url)
                                                    view?.evaluateJavascript(cleanPlayerJs, null)
                                                }

                                                override fun onPageFinished(view: WebView?, url: String?) {
                                                    super.onPageFinished(view, url)
                                                    view?.evaluateJavascript(cleanPlayerJs, null)
                                                }
                                            }

                                            webChromeClient = object : WebChromeClient() {
                                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                    super.onProgressChanged(view, newProgress)
                                                    if (newProgress >= 40) {
                                                        view?.evaluateJavascript(cleanPlayerJs, null)
                                                    }
                                                    if (newProgress >= 85) {
                                                        isPlayerReady = true
                                                    }
                                                }

                                                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                                    if (customFullscreenView != null) {
                                                        callback?.onCustomViewHidden()
                                                        return
                                                    }
                                                    customFullscreenView = view
                                                    customViewCallback = callback
                                                    if (view != null) {
                                                        container.addView(
                                                            view,
                                                            FrameLayout.LayoutParams(
                                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                                ViewGroup.LayoutParams.MATCH_PARENT
                                                            )
                                                        )
                                                    }
                                                    isExpanded = true
                                                }

                                                override fun onHideCustomView() {
                                                    val v = customFullscreenView ?: return
                                                    try {
                                                        container.removeView(v)
                                                    } catch (_: Throwable) {}
                                                    customFullscreenView = null
                                                    try {
                                                        customViewCallback?.onCustomViewHidden()
                                                    } catch (_: Throwable) {}
                                                    customViewCallback = null
                                                }
                                            }

                                            loadUrl(
                                                watchUrl,
                                                mapOf("Referer" to "https://m.youtube.com/")
                                            )
                                        }
                                        webViewRef = wv
                                        container.addView(wv)
                                        container
                                    }
                                )
                            }

                            // High-Definition YouTube Thumbnail Poster Overlay (visible before play & while stream initializes)
                            if (!isPlaybackStarted || !isPlayerReady) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(CyberBgDarker)
                                        .clickable {
                                            if (!isPlaybackStarted) {
                                                isPlaybackStarted = true
                                            }
                                        }
                                        .testTag("setup_video_thumbnail_overlay"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val bmp = thumbnailBitmap
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = resolvedTitle,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.radialGradient(
                                                        listOf(
                                                            CyberPurple.copy(alpha = 0.32f),
                                                            CyberBgDarker
                                                        )
                                                    )
                                                )
                                        )
                                    }

                                    // Dark gradient vignette for crisp contrast over thumbnail
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.Black.copy(alpha = 0.25f),
                                                        Color.Black.copy(alpha = 0.45f),
                                                        Color.Black.copy(alpha = 0.72f)
                                                    )
                                                )
                                            )
                                    )

                                    // Center Glowing Play Button or Loading Spinner
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(CyberPurple, NeonCyan)
                                                )
                                            )
                                            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isPlaybackStarted && !isPlayerReady) {
                                            CircularProgressIndicator(
                                                color = CyberBg,
                                                strokeWidth = 2.8.dp,
                                                modifier = Modifier.size(30.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play Video",
                                                tint = CyberBg,
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }
                                    }

                                    // Bottom-left status pill over thumbnail
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(10.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black.copy(alpha = 0.72f))
                                            .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (isPlaybackStarted) {
                                                tr("Starting video...", "ভিডিও চালু হচ্ছে...", "वीडियो शुरू हो रहा है...")
                                            } else {
                                                tr("▶ Tap to Play Video", "▶ ভিডিওটি চালাতে ট্যাপ করুন", "▶ वीडियो चलाने के लिए टैप करें")
                                            },
                                            color = NeonCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Compact Bottom Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CyberBgDarker)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val normalized = if (videoUrl.trim().startsWith("http://", ignoreCase = true) ||
                                        videoUrl.trim().startsWith("https://", ignoreCase = true)
                                    ) {
                                        videoUrl.trim()
                                    } else {
                                        "https://www.youtube.com/watch?v=${videoId ?: videoUrl.trim()}"
                                    }
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
                                } catch (_: Exception) {
                                    Toast.makeText(
                                        context,
                                        tr(
                                            "Could not open video link",
                                            "ভিডিও লিংক ওপেন করা যায়নি",
                                            "वीडियो लिंक नहीं खोला जा सका"
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .cyberShimmerBorder(width = 1.15.dp, shape = RoundedCornerShape(10.dp), phaseOffset = 0.42f),
                            shape = RoundedCornerShape(10.dp),
                            border = null,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("YouTube App", "YouTube অ্যাপ", "YouTube ऐप"),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberPurple,
                                contentColor = TextPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("Close", "বন্ধ করুন", "बंद करें"),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
