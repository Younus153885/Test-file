package com.example.ui.i18n

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLang(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val shortBadge: String
) {
    EN("en", "English", "English", "EN"),
    BN("bn", "Bengali", "বাংলা", "বাং"),
    HI("hi", "Hindi", "हिन्दी", "हिं");

    companion object {
        fun fromCode(code: String?): AppLang {
            return entries.find { it.code.equals(code, ignoreCase = true) } ?: EN
        }
    }
}

object LanguageManager {
    private const val PREFS_NAME = "phantom_language_prefs"
    private const val KEY_SELECTED_LANG = "selected_app_language"

    // Default language is strictly English (AppLang.EN) as requested
    private val _langFlow = MutableStateFlow(AppLang.EN)
    val langFlow: StateFlow<AppLang> = _langFlow.asStateFlow()

    val currentLang: AppLang
        get() = _langFlow.value

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedCode = prefs.getString(KEY_SELECTED_LANG, AppLang.EN.code) ?: AppLang.EN.code
        _langFlow.value = AppLang.fromCode(savedCode)
        persistLiveLanguageFile(_langFlow.value.code)
    }

    fun setLanguage(context: Context, lang: AppLang) {
        _langFlow.value = lang
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_LANG, lang.code)
            .apply()
        persistLiveLanguageFile(lang.code)
    }

    private fun persistLiveLanguageFile(langCode: String) {
        try {
            val pubDir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "PhantomStandaloneAPKs"
            )
            if (!pubDir.exists()) pubDir.mkdirs()
            java.io.File(pubDir, ".phantom_live_language.txt").writeText(langCode, Charsets.UTF_8)
        } catch (_: Throwable) {}
        try {
            val dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (dlDir != null && dlDir.exists()) {
                java.io.File(dlDir, ".phantom_live_language.txt").writeText(langCode, Charsets.UTF_8)
            }
        } catch (_: Throwable) {}
    }

    fun tr(en: String, bn: String, hi: String, lang: AppLang = _langFlow.value): String {
        return when (lang) {
            AppLang.EN -> en
            AppLang.BN -> bn
            AppLang.HI -> hi
        }
    }

    fun formatDigits(number: Int, lang: AppLang = _langFlow.value): String {
        return formatDigits(number.toString(), lang)
    }

    fun formatDigits(text: String, lang: AppLang = _langFlow.value): String {
        return when (lang) {
            AppLang.EN -> text
            AppLang.BN -> {
                val bnDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
                buildString(text.length) {
                    for (ch in text) {
                        if (ch in '0'..'9') append(bnDigits[ch - '0']) else append(ch)
                    }
                }
            }
            AppLang.HI -> {
                val hiDigits = charArrayOf('०', '१', '२', '३', '४', '५', '६', '७', '८', '९')
                buildString(text.length) {
                    for (ch in text) {
                        if (ch in '0'..'9') append(hiDigits[ch - '0']) else append(ch)
                    }
                }
            }
        }
    }
}

/**
 * Top-level helper that translates strings based on the currently active language.
 * Works inside both regular classes/services and Jetpack Compose (when paired with currentAppLang()).
 */
fun tr(en: String, bn: String, hi: String, lang: AppLang = LanguageManager.currentLang): String {
    return LanguageManager.tr(en, bn, hi, lang)
}

/**
 * Observe current language in Compose so the screen recomposes immediately on language change.
 */
@Composable
fun currentAppLang(): AppLang {
    val lang by LanguageManager.langFlow.collectAsState()
    return lang
}

/**
 * Compact cyber-styled Language Selector Chip with popup dialog supporting English, Bengali, and Hindi.
 */
@Composable
fun LanguageSelectorChip(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentLang by LanguageManager.langFlow.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(EmeraldGreen.copy(alpha = 0.14f))
            .cyberShimmerBorder(width = 1.15.dp, shape = RoundedCornerShape(8.dp), phaseOffset = 0.25f, accentColor = EmeraldGreen)
            .clickable { showDialog = true },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = "Language",
            tint = EmeraldGreen,
            modifier = Modifier.size(16.dp)
        )
    }

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CyberCard)
                    .cyberShimmerBorder(width = 1.5.dp, shape = RoundedCornerShape(18.dp), phaseOffset = 0.15f, accentColor = EmeraldGreen)
                    .padding(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = EmeraldGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = tr(
                                "Select App Language",
                                "অ্যাপের ভাষা নির্বাচন করুন",
                                "ऐप की भाषा चुनें",
                                currentLang
                            ),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = tr(
                                "Default language is English",
                                "ডিফল্ট ভাষা হিসেবে ইংলিশ সেট করা আছে",
                                "डिफ़ॉल्ट भाषा अंग्रेज़ी (English) है",
                                currentLang
                            ),
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                AppLang.entries.forEach { lang ->
                    val isSelected = lang == currentLang
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) EmeraldGreen.copy(alpha = 0.16f)
                                else CyberCardElevated
                            )
                            .cyberShimmerBorder(
                                width = if (isSelected) 1.5.dp else 1.1.dp,
                                shape = RoundedCornerShape(12.dp),
                                phaseOffset = (lang.ordinal * 0.28f) % 1f,
                                accentColor = if (isSelected) EmeraldGreen else null
                            )
                            .clickable {
                                LanguageManager.setLanguage(context, lang)
                                showDialog = false
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isSelected) EmeraldGreen else EmeraldGreen.copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = lang.shortBadge,
                                    color = if (isSelected) Color.Black else EmeraldGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Column {
                                Text(
                                    text = lang.nativeName,
                                    color = if (isSelected) EmeraldGreen else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (lang == AppLang.EN) "${lang.displayName} (Default)" else lang.displayName,
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = EmeraldGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                TextButton(
                    onClick = { showDialog = false },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = tr("Close", "বন্ধ করুন", "बंद करें", currentLang),
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
