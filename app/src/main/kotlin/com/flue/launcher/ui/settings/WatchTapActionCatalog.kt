package com.flue.launcher.ui.settings

import androidx.annotation.StringRes
import com.flue.launcher.R

internal data class WatchTapActionDefinition(
    val id: String,
    val mappingKeys: List<String>,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
) {
    /**
     * 往表盘图层里**写**动作时用的那个拼法。
     *
     * [mappingKeys] 的第一个键是首选拼法：上游 `TapActionHandler.CONFIGURABLE_ACTIONS`
     * 里有对应名字的（`WM_TAP_ACTIONS.md` §3，26 个）一律排上游那个，后面几个才是 Flue
     * 早年的别名 —— 别名只用于**读**（表盘写 `m_music` 也认得），写出去的 .watch 要能被别的
     * WatchMaker 宿主读懂，就只能用上游拼法。
     *
     * 例外：闹钟 / 计时器 / 秒表 / 录音机 / 系统设置这 5 个上游根本没有对应名字，首选键就是
     * Flue 自己的拼法（`w_alarm` 等），别的宿主会当作不认识的动作静默跳过 —— 这是拿不到
     * 上游名字时唯一的选择，不是笔误。
     */
    val canonicalKey: String get() = mappingKeys.first()
}

internal data class WatchTapActionCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val actions: List<WatchTapActionDefinition>,
)

/** Human-facing groups; compatibility aliases are deliberately edited as one binding. */
internal object WatchTapActionCatalog {
    val categories = listOf(
        WatchTapActionCategory(
            id = "information",
            titleRes = R.string.tap_action_category_information,
            descriptionRes = R.string.tap_action_category_information_sub,
            actions = listOf(
                action("weather", R.string.tap_action_weather, R.string.tap_action_weather_sub, "widget_weather", "w_weather", "m_weather"),
                action("health", R.string.tap_action_health, R.string.tap_action_health_sub, "widget_health"),
                action("calendar", R.string.tap_action_calendar, R.string.tap_action_calendar_sub, "widget_calendar", "m_calendar", "w_calendar"),
                action("agenda", R.string.tap_action_agenda, R.string.tap_action_agenda_sub, "w_agenda"),
                action("fitness", R.string.tap_action_fitness, R.string.tap_action_fitness_sub, "w_fit"),
                action("heart_rate", R.string.tap_action_heart_rate, R.string.tap_action_heart_rate_sub, "w_heart_rate"),
                action("compass", R.string.tap_action_compass, R.string.tap_action_compass_sub, "w_compass"),
                action("sports", R.string.tap_action_sports, R.string.tap_action_sports_sub, "w_sports"),
            ),
        ),
        WatchTapActionCategory(
            id = "communication",
            titleRes = R.string.tap_action_category_communication,
            descriptionRes = R.string.tap_action_category_communication_sub,
            actions = listOf(
                action("email", R.string.tap_action_email, R.string.tap_action_email_sub, "m_email", "w_email"),
                action("voice_search", R.string.tap_action_voice_search, R.string.tap_action_voice_search_sub, "voice_search"),
                action("contacts", R.string.tap_action_contacts, R.string.tap_action_contacts_sub, "w_contacts"),
                action("messaging", R.string.tap_action_messaging, R.string.tap_action_messaging_sub, "w_messaging", "w_sms"),
                action("dialer", R.string.tap_action_dialer, R.string.tap_action_dialer_sub, "w_dialer", "w_phone"),
                action("social", R.string.tap_action_social, R.string.tap_action_social_sub, "socialmedia"),
            ),
        ),
        WatchTapActionCategory(
            id = "media",
            titleRes = R.string.tap_action_category_media,
            descriptionRes = R.string.tap_action_category_media_sub,
            actions = listOf(
                action("gallery", R.string.tap_action_gallery, R.string.tap_action_gallery_sub, "w_gallery"),
                action("camera", R.string.tap_action_camera, R.string.tap_action_camera_sub, "w_camera", "m_camera"),
                action("music", R.string.tap_action_music, R.string.tap_action_music_sub, "w_music", "m_music"),
                action("browser", R.string.tap_action_browser, R.string.tap_action_browser_sub, "browser"),
            ),
        ),
        WatchTapActionCategory(
            id = "tools",
            titleRes = R.string.tap_action_category_tools,
            descriptionRes = R.string.tap_action_category_tools_sub,
            actions = listOf(
                action("launcher", R.string.tap_action_launcher, R.string.tap_action_launcher_sub, "tap_launcher"),
                action("keep", R.string.tap_action_keep, R.string.tap_action_keep_sub, "w_keep"),
                action("clock", R.string.tap_action_clock, R.string.tap_action_clock_sub, "w_clock", "m_clock"),
                action("calculator", R.string.tap_action_calculator, R.string.tap_action_calculator_sub, "w_calculator", "m_calculator"),
                action("payment", R.string.tap_action_payment, R.string.tap_action_payment_sub, "w_payment"),
                action("map", R.string.tap_action_map, R.string.tap_action_map_sub, "w_map", "w_maps"),
                action("flashlight", R.string.tap_action_flashlight, R.string.tap_action_flashlight_sub, "flashlight"),
                action("alarm", R.string.tap_action_alarm, R.string.tap_action_alarm_sub, "w_alarm"),
                action("timer", R.string.tap_action_timer, R.string.tap_action_timer_sub, "w_timer"),
                action("stopwatch", R.string.tap_action_stopwatch, R.string.tap_action_stopwatch_sub, "w_stopwatch"),
                action("recorder", R.string.tap_action_recorder, R.string.tap_action_recorder_sub, "w_recorder"),
                action("settings", R.string.tap_action_settings, R.string.tap_action_settings_sub, "w_settings"),
            ),
        ),
    )

    fun category(id: String?): WatchTapActionCategory? = categories.firstOrNull { it.id == id }

    private fun action(
        id: String,
        @StringRes titleRes: Int,
        @StringRes descriptionRes: Int,
        vararg mappingKeys: String,
    ) = WatchTapActionDefinition(id, mappingKeys.toList(), titleRes, descriptionRes)
}
