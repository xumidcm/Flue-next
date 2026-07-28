package com.flue.launcher.i18n

import java.util.concurrent.CancellationException

internal data class LanguageSelectionResult(
    val language: String,
    val isSuccess: Boolean,
)

/**
 * 语言切换的顺序契约：先写启动真源，再等待镜像写入完成。
 * 启动真源写入成功后即可重启；DataStore 只是镜像，镜像失败不能否决已经
 * 持久化的语言，否则冷启动会使用新语言、当前进程却提示失败，两个真相反而
 * 更难收敛。
 */
internal suspend fun persistLanguageSelection(
    language: String,
    saveStartupLanguage: (String) -> Boolean,
    mirrorLanguage: suspend (String) -> Unit,
): LanguageSelectionResult {
    val canonicalLanguage = FlueLocaleManager.normalizeLanguage(language)
    val startupSaved = try {
        saveStartupLanguage(canonicalLanguage)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
    if (!startupSaved) {
        return LanguageSelectionResult(canonicalLanguage, isSuccess = false)
    }

    try {
        mirrorLanguage(canonicalLanguage)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // Best-effort mirror only. SharedPreferences has already committed and
        // remains the sole cold-start authority.
    }
    return LanguageSelectionResult(canonicalLanguage, isSuccess = true)
}
