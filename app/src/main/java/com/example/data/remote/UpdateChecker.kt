package com.example.data.remote

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val isForceUpdate: Boolean,
    val updateUrl: String,
    val updateMessage: String
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"

    private const val REPO_OWNER = "demotricks99-arch"
    private const val REPO_NAME = "QUiCKFB_UPDATE"
    private const val FILE_PATH = "QUiCKFB_UPDATE"

    private val FALLBACK_RAW_URLS = listOf(
        "https://github.com/$REPO_OWNER/$REPO_NAME/raw/main/$FILE_PATH",
        "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/$FILE_PATH",
        "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/refs/heads/main/$FILE_PATH",
        "https://raw.githubusercontent.com/$REPO_OWNER/GhostDevice/main/GhostDevice"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cache(null)
        .build()

    suspend fun checkUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<UpdateInfo>()

        // 1. Live uncached fetch via GitHub Blob endpoint (https://github.com/demotricks99-arch/QUiCKFB_UPDATE/blob/main/QUiCKFB_UPDATE)
        fetchFromGitHubBlobPage()?.let { info ->
            candidates.add(info)
        }

        // 2. Live uncached fetch via latest commit SHA from Atom feed (bypasses Fastly 5-min branch CDN cache completely)
        fetchFromLatestCommitShaRaw()?.let { info ->
            candidates.add(info)
        }

        // 3. GitHub Contents REST API (decoding Base64 content or raw body)
        fetchFromGitHubContentsApi()?.let { info ->
            candidates.add(info)
        }

        // If any live uncached QUiCKFB_UPDATE endpoint succeeded, return the highest version among the live endpoints
        if (candidates.isNotEmpty()) {
            return@withContext candidates.maxByOrNull { it.versionCode }
        }

        // 4. Fallback to raw URLs if live endpoints were unreachable
        for (rawUrl in FALLBACK_RAW_URLS) {
            fetchRawText(rawUrl)?.let { body ->
                parseUpdateInfo(body)?.let { info ->
                    candidates.add(info)
                    if (rawUrl.contains(REPO_NAME)) {
                        // Continue checking other QUiCKFB_UPDATE raw URLs in case one CDN edge has newer cache
                    }
                }
            }
        }

        candidates.maxByOrNull { it.versionCode }
    }

    private fun fetchFromGitHubBlobPage(): UpdateInfo? {
        return try {
            val url = "https://github.com/$REPO_OWNER/$REPO_NAME/blob/main/$FILE_PATH?_t=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(url)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json, text/html;q=0.9")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.trim().orEmpty()
                if (body.isEmpty()) return null

                val extractedText = extractTextFromGitHubBlobResponse(body)
                if (!extractedText.isNullOrBlank()) {
                    parseUpdateInfo(extractedText)?.let { return it }
                }

                // Also check if currentOid (commit SHA) is present in the blob payload and fetch commit-specific raw URL
                val commitOid = extractCommitOidFromBlobResponse(body)
                if (!commitOid.isNullOrBlank()) {
                    val shaRawUrl = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$commitOid/$FILE_PATH"
                    fetchRawText(shaRawUrl)?.let { rawBody ->
                        parseUpdateInfo(rawBody)?.let { return it }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchFromGitHubBlobPage failed: ${e.message}")
            null
        }
    }

    private fun extractTextFromGitHubBlobResponse(body: String): String? {
        try {
            val jsonStr = if (body.startsWith("{")) {
                body
            } else {
                val marker = "data-target=\"react-app.embeddedData\">"
                val startIdx = body.indexOf(marker)
                if (startIdx >= 0) {
                    val contentStart = startIdx + marker.length
                    val endIdx = body.indexOf("</script>", contentStart)
                    if (endIdx > contentStart) body.substring(contentStart, endIdx).trim() else null
                } else null
            } ?: return null

            val root = JSONObject(jsonStr)
            val payload = root.optJSONObject("payload") ?: root
            val blob = payload.optJSONObject("blob")
            if (blob != null) {
                val rawLines = blob.optJSONArray("rawLines")
                if (rawLines != null && rawLines.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until rawLines.length()) {
                        if (i > 0) sb.append('\n')
                        sb.append(rawLines.optString(i, ""))
                    }
                    return sb.toString()
                }
                val rawBlob = blob.optString("rawBlob", "")
                if (rawBlob.isNotBlank()) {
                    return rawBlob
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun extractCommitOidFromBlobResponse(body: String): String? {
        return try {
            val match = Regex(""""currentOid"\s*:\s*"([0-9a-f]{40})"""").find(body)
            match?.groupValues?.getOrNull(1)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromLatestCommitShaRaw(): UpdateInfo? {
        return try {
            val atomUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/commits/main.atom?_t=${System.currentTimeMillis()}"
            val atomBody = fetchRawText(atomUrl) ?: return null
            val shaMatch = Regex("""Commit/([0-9a-f]{40})""").find(atomBody)
                ?: Regex("""/commit/([0-9a-f]{40})""").find(atomBody)
            val commitSha = shaMatch?.groupValues?.getOrNull(1) ?: return null

            val immutableRawUrl = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$commitSha/$FILE_PATH"
            val rawContent = fetchRawText(immutableRawUrl) ?: return null
            parseUpdateInfo(rawContent)
        } catch (e: Exception) {
            Log.w(TAG, "fetchFromLatestCommitShaRaw failed: ${e.message}")
            null
        }
    }

    private fun fetchFromGitHubContentsApi(): UpdateInfo? {
        return try {
            val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$FILE_PATH?ref=main&_t=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(apiUrl)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("User-Agent", "GhostDevice-Android-Updater")
                .header("Accept", "application/vnd.github.v3+json")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.trim().orEmpty()
                if (body.isEmpty()) return null

                if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    val base64Content = json.optString("content", "")
                    if (base64Content.isNotBlank()) {
                        val decodedBytes = Base64.decode(base64Content.replace("\n", "").replace("\r", ""), Base64.DEFAULT)
                        val decodedText = String(decodedBytes, Charsets.UTF_8)
                        return parseUpdateInfo(decodedText)
                    }
                    // If GitHub returned an API error object (e.g. rate limit), ignore it
                    if (json.has("documentation_url") || json.has("sha")) {
                        return null
                    }
                }
                parseUpdateInfo(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchFromGitHubContentsApi failed: ${e.message}")
            null
        }
    }

    private fun fetchRawText(url: String): String? {
        return try {
            val sep = if ('?' in url) '&' else '?'
            val request = Request.Builder()
                .url("${url}${sep}_t=${System.currentTimeMillis()}")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("User-Agent", "GhostDevice-Android-Updater")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()?.trim()
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseUpdateInfo(content: String): UpdateInfo? {
        try {
            val trimmedContent = content.replace("\uFEFF", "").trim()
            if (trimmedContent.isEmpty()) return null
            if (trimmedContent.startsWith("<!DOCTYPE", ignoreCase = true) ||
                trimmedContent.startsWith("<html", ignoreCase = true)
            ) {
                return null
            }

            var parsedVersionCode: Int? = null
            var parsedVersionName: String? = null
            var isForceUpdate = false
            var updateUrl = "https://t.me/AirdropKoP24/71494"
            var updateMessage = "একটি নতুন আপডেট উপলব্ধ! দয়া করে নিচের লিংক থেকে নতুন ভার্সনটি ডাউনলোড করে নিন।"

            // 1. Try JSON parsing (supports both flat and nested JSON objects)
            if (trimmedContent.startsWith("{") && trimmedContent.endsWith("}")) {
                try {
                    val json = JSONObject(trimmedContent)
                    // Reject GitHub API metadata/error JSON that isn't an update payload
                    if (json.has("documentation_url") && !json.has("versionCode") && !json.has("version")) {
                        return null
                    }
                    parseJsonObjectRecursive(
                        json = json,
                        onVersionCode = { code ->
                            if (parsedVersionCode == null || code > parsedVersionCode!!) {
                                parsedVersionCode = code
                            }
                        },
                        onVersionName = { name ->
                            parsedVersionName = name
                        },
                        onForceUpdate = { isForceUpdate = it },
                        onUpdateUrl = { updateUrl = it },
                        onUpdateMessage = { updateMessage = it }
                    )
                } catch (_: Exception) {
                    // Fall through to line-by-line parser
                }
            }

            // 2. Also run line-by-line parser if version hasn't been found yet
            if (parsedVersionCode == null && parsedVersionName == null) {
                val normalizedLines = trimmedContent
                    .removePrefix("{")
                    .removeSuffix("}")
                    .lineSequence()

                for (rawLine in normalizedLines) {
                    val trimmed = rawLine.trim()
                        .removePrefix("{")
                        .removeSuffix("}")
                        .removeSuffix(",")
                        .trim()
                    if (trimmed.isEmpty() ||
                        trimmed.startsWith("[") ||
                        trimmed.startsWith("#") ||
                        trimmed.startsWith(";") ||
                        trimmed.startsWith("//")
                    ) {
                        continue
                    }

                    // If a line is a direct URL without a key
                    if (trimmed.startsWith("http://", ignoreCase = true) ||
                        trimmed.startsWith("https://", ignoreCase = true)
                    ) {
                        updateUrl = trimmed.trim('"', '\'')
                        continue
                    }

                    // Check if the line is a standalone version number (e.g., "4", "4.0", "v4", "v4.0")
                    val standaloneVerMatch = Regex("""^[vV]?(?:ersion)?\s*(\d+)(?:\.(\d+))?$""").find(trimmed.trim('"', '\''))
                    if (standaloneVerMatch != null) {
                        val major = standaloneVerMatch.groupValues[1].toIntOrNull()
                        if (major != null) {
                            parsedVersionCode = major
                            parsedVersionName = trimmed.trim('"', '\'').removePrefix("v").removePrefix("V").trim()
                            continue
                        }
                    }

                    val eqIdx = trimmed.indexOf('=')
                    val colIdx = trimmed.indexOf(':')
                    val delimiterIdx = when {
                        eqIdx > 0 && colIdx > 0 -> minOf(eqIdx, colIdx)
                        eqIdx > 0 -> eqIdx
                        colIdx > 0 -> colIdx
                        else -> -1
                    }
                    if (delimiterIdx <= 0) continue

                    val key = trimmed.substring(0, delimiterIdx)
                        .trim()
                        .trim('"', '\'')
                        .lowercase()
                        .replace("_", "")
                        .replace("-", "")
                        .replace(" ", "")
                    val value = trimmed.substring(delimiterIdx + 1)
                        .trim()
                        .removeSuffix(",")
                        .trim()
                        .trim('"', '\'')

                    applyKeyValue(
                        key = key,
                        value = value,
                        onVersionCode = { code ->
                            if (parsedVersionCode == null || code > parsedVersionCode!!) {
                                parsedVersionCode = code
                            }
                        },
                        onVersionName = { name ->
                            parsedVersionName = name
                        },
                        onForceUpdate = { isForceUpdate = it },
                        onUpdateUrl = { updateUrl = it },
                        onUpdateMessage = { updateMessage = it }
                    )
                }
            }

            val versionNameInt = parsedVersionName?.let { extractLeadingVersionInt(it) }
            val finalVersionCode = when {
                parsedVersionCode != null && versionNameInt != null -> maxOf(parsedVersionCode!!, versionNameInt)
                parsedVersionCode != null -> parsedVersionCode!!
                versionNameInt != null -> versionNameInt
                else -> return null
            }

            val finalVersionName = when {
                parsedVersionName.isNullOrBlank() -> "$finalVersionCode.0"
                versionNameInt != null && versionNameInt < finalVersionCode -> "$finalVersionCode.0"
                else -> parsedVersionName!!.removePrefix("v").removePrefix("V").trim()
            }

            return UpdateInfo(
                versionCode = finalVersionCode,
                versionName = finalVersionName,
                isForceUpdate = isForceUpdate,
                updateUrl = updateUrl,
                updateMessage = updateMessage
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing update info: ${e.message}", e)
            return null
        }
    }

    private fun parseJsonObjectRecursive(
        json: JSONObject,
        onVersionCode: (Int) -> Unit,
        onVersionName: (String) -> Unit,
        onForceUpdate: (Boolean) -> Unit,
        onUpdateUrl: (String) -> Unit,
        onUpdateMessage: (String) -> Unit
    ) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val objVal = json.opt(rawKey)
            if (objVal is JSONObject) {
                parseJsonObjectRecursive(objVal, onVersionCode, onVersionName, onForceUpdate, onUpdateUrl, onUpdateMessage)
                continue
            }
            if (objVal is JSONArray) continue
            val normKey = rawKey.trim().lowercase().replace("_", "").replace("-", "").replace(" ", "")
            val rawVal = objVal?.toString()?.trim().orEmpty()
            applyKeyValue(
                key = normKey,
                value = rawVal,
                onVersionCode = onVersionCode,
                onVersionName = onVersionName,
                onForceUpdate = onForceUpdate,
                onUpdateUrl = onUpdateUrl,
                onUpdateMessage = onUpdateMessage
            )
        }
    }

    private fun extractLeadingVersionInt(raw: String): Int? {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V").trim()
        return cleaned.toIntOrNull()
            ?: cleaned.toDoubleOrNull()?.toInt()
            ?: Regex("""^(\d+)""").find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private inline fun applyKeyValue(
        key: String,
        value: String,
        onVersionCode: (Int) -> Unit,
        onVersionName: (String) -> Unit,
        onForceUpdate: (Boolean) -> Unit,
        onUpdateUrl: (String) -> Unit,
        onUpdateMessage: (String) -> Unit
    ): Boolean {
        return when (key) {
            "versioncode", "version", "ver", "vcode", "appversioncode", "latestversioncode", "newversioncode", "updateversioncode", "build", "buildnumber", "code" -> {
                val parsed = extractLeadingVersionInt(value)
                if (parsed != null) {
                    onVersionCode(parsed)
                    if (value.contains(".")) {
                        onVersionName(value)
                    }
                    true
                } else {
                    false
                }
            }
            "versionname", "vname", "latestversion", "appversion", "newversion", "updateversion", "vername" -> {
                if (value.isNotEmpty()) {
                    onVersionName(value)
                    extractLeadingVersionInt(value)?.let { onVersionCode(it) }
                    true
                } else {
                    false
                }
            }
            "isforceupdate", "forceupdate", "force", "mandatory", "ismandatory" -> {
                onForceUpdate(
                    value.equals("true", ignoreCase = true) ||
                        value == "1" ||
                        value.equals("yes", ignoreCase = true)
                )
                true
            }
            "updateurl", "url", "link", "downloadurl", "apkurl", "downloadlink", "telegram", "tgurl" -> {
                if (value.isNotEmpty() && !value.contains("api.github.com")) {
                    onUpdateUrl(value)
                    true
                } else {
                    false
                }
            }
            "updatemessage", "message", "msg", "description", "whatsnew", "note", "changelog", "text" -> {
                if (value.isNotEmpty()) {
                    onUpdateMessage(value.replace("\\n", "\n"))
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }
}
