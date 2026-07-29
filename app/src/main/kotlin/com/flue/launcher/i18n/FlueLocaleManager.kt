package com.flue.launcher.i18n

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * 应用级语言管理器。
 *
 * 使用独立的 SharedPreferences 文件存储语言偏好，以便在
 * [android.app.Application.attachBaseContext] / [android.app.Activity.attachBaseContext]
 * 里同步读取（DataStore 为异步，启动时不可用）。
 *
 * 支持的语言代码：
 *  - [LANGUAGE_SYSTEM]  ""   跟随系统（默认）
 *  - "zh"               中文
 *  - "en"               English
 *  - "ja"               日本語
 */
object FlueLocaleManager {

    private const val PREFS_NAME = "flue_global"
    const val KEY_LANGUAGE = "app_language"
    const val LANGUAGE_SYSTEM = ""

    /** 将外部输入归一为 SharedPreferences/DataStore 共用的 canonical 值。 */
    internal fun normalizeLanguage(language: String?): String = canonicalLanguage(language)

    /** 读取已保存的语言代码；未设置时返回 [LANGUAGE_SYSTEM]。 */
    fun getSavedLanguage(context: Context): String =
        runCatching {
            preferenceStore(context).readLanguage()
        }.getOrNull() ?: LANGUAGE_SYSTEM

    /** 同步持久化语言选择；成功后更新 [Locale.getDefault]。 */
    fun saveLanguage(context: Context, language: String): Boolean =
        saveLanguage(context, language, systemLocale())

    /**
     * 同步保存的环境边界；调用方通常使用上面的两参数版本，
     * 这里显式接收系统 Locale 以隔离平台环境并保持行为可验证。
     */
    internal fun saveLanguage(
        context: Context,
        language: String,
        systemLocale: Locale,
    ): Boolean {
        val writeResult = runCatching {
            preferenceStore(context).saveLanguage(language)
        }.getOrNull() ?: return false
        if (writeResult.isSuccess) {
            Locale.setDefault(localeForLanguage(writeResult.language, systemLocale))
        }
        return writeResult.isSuccess
    }

    private fun preferenceStore(context: Context): LanguagePreferenceStore {
        val appContext = context.applicationContext ?: context
        return LanguagePreferenceStore(
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        )
    }

    /** 返回应用了已保存语言的 [Context]。跟随系统时也显式限制到受支持语言。 */
    fun applyLocale(context: Context): Context {
        val language = getSavedLanguage(context)
        val locale = localeForLanguage(language, systemLocale())
        Locale.setDefault(locale)
        return context.withLocale(locale)
    }

    /**
     * 为尚未持久化的语言选择创建资源 Context。
     * 用于让“需要重启”对话框立即显示目标语言，而不是旧 Activity 的语言。
     */
    fun contextForLanguageSelection(context: Context, language: String): Context =
        context.withLocale(localeForLanguage(language, systemLocale()))

    /** 获取当前实际生效的受支持语言代码。 */
    fun effectiveLanguage(context: Context): String {
        val saved = getSavedLanguage(context)
        return localeForLanguage(saved, systemLocale()).language
    }

    /** 所有可选语言描述（用于设置页渲染）。 */
    val options: List<LanguageOption> = listOf(
        LanguageOption(LANGUAGE_SYSTEM, "跟随系统", "Follow System", "システムに従う"),
        LanguageOption("zh",            "中文",     "Chinese",       "中国語"),
        LanguageOption("en",            "English",  "English",       "英語"),
        LanguageOption("ja",            "日本語",   "Japanese",      "日本語"),
    )

    private fun systemLocale(): Locale =
        Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
}

data class LanguageOption(
    val code: String,
    val labelZh: String,
    val labelEn: String,
    val labelJa: String,
)

internal data class LanguagePreferenceWriteResult(
    val language: String,
    val isSuccess: Boolean,
)

/** SharedPreferences 的同步语言读写边界；DataStore 不参与启动语言解析。 */
internal class LanguagePreferenceStore(
    private val preferences: SharedPreferences,
) {
    fun readLanguage(): String = canonicalLanguage(
        preferences.getString(FlueLocaleManager.KEY_LANGUAGE, FlueLocaleManager.LANGUAGE_SYSTEM),
    )

    fun saveLanguage(language: String): LanguagePreferenceWriteResult {
        val canonicalLanguage = canonicalLanguage(language)
        val isSuccess = runCatching {
            preferences.edit()
                .putString(FlueLocaleManager.KEY_LANGUAGE, canonicalLanguage)
                .commit()
        }.getOrDefault(false)
        return LanguagePreferenceWriteResult(
            language = canonicalLanguage,
            isSuccess = isSuccess,
        )
    }
}

private fun canonicalLanguage(language: String?): String {
    val normalizedTag = language
        ?.trim()
        ?.replace('_', '-')
        .orEmpty()
    if (normalizedTag.isBlank()) return FlueLocaleManager.LANGUAGE_SYSTEM
    return when (Locale.forLanguageTag(normalizedTag).language.lowercase(Locale.ROOT)) {
        "zh" -> "zh"
        "en" -> "en"
        "ja" -> "ja"
        else -> FlueLocaleManager.LANGUAGE_SYSTEM
    }
}

internal fun localeForLanguage(language: String?, systemLocale: Locale): Locale {
    val canonicalLanguage = canonicalLanguage(language)
    return if (canonicalLanguage.isBlank()) {
        supportedSystemLocale(systemLocale)
    } else {
        Locale.forLanguageTag(canonicalLanguage)
    }
}

/** Unsupported system locales explicitly fall back to English resources. */
internal fun supportedSystemLocale(systemLocale: Locale): Locale =
    when (systemLocale.language.lowercase(Locale.ROOT)) {
        "zh", "en", "ja" -> systemLocale
        else -> Locale.ENGLISH
    }

private fun Context.withLocale(locale: Locale): Context {
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}
