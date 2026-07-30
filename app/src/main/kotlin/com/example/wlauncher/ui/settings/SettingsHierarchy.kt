package com.flue.launcher.ui.settings

/**
 * 设置页信息架构的声明式模型（重构脚手架）。
 *
 * 这份层级把 [com.flue.launcher.SettingsActivity] 里散落在各个
 * `SettingsDestination` 页面中的全部设置项，重新按照六大分组建模：
 *
 * 1. 外观（appearance）   —— UI 风格三选一、深浅色、主题色、表盘取色
 * 2. 表盘（watchface）    —— 表盘选择 / 商店 / 编辑器入口、表盘显示设置
 * 3. 应用列表（app_list） —— 布局、图标、鱼眼、甩尾动画、文件夹、隐藏应用
 * 4. 动画（animation）    —— 动画曲线档位、各转场开关、模糊设置
 * 5. 手势与输入（input）  —— 侧屏、控制中心、通知中心、旋钮
 * 6. 系统（system）       —— 备份恢复、无障碍、通知权限、关于
 *
 * 现阶段它只是文档化的数据模型 + 未来重构的骨架：
 * - 每个现有设置项映射为一个 [SettingsEntry]，`prefKey` 对应
 *   [com.flue.launcher.viewmodel.LauncherViewModel] 中的 DataStore key 名；
 * - `status == PLANNED` 的条目是路线图上已确认、但当前代码里还没有
 *   preferences key 的项（例如主工作区里进行中的转场开关）；
 * - 旧文件行号 → 新分组的迁移映射见本次重构报告，不在代码里维护。
 */
data class SettingsGroup(
    val id: String,
    val title: String,
    val items: List<SettingsEntry>
)

/** 条目现状：EXISTING 已有实现与 key；PLANNED 规划中（key 或页面尚未落地）。 */
enum class EntryStatus { EXISTING, PLANNED }

/** 单选项（用于 Picker）。 */
data class PickerOption(
    val id: String,
    val label: String
)

sealed interface SettingsEntry {
    val id: String
    val title: String
    val subtitle: String?
    /** DataStore preferences key 名；导航/动作类条目为 null。 */
    val prefKey: String?
    val status: EntryStatus

    /** 布尔开关。 */
    data class Toggle(
        override val id: String,
        override val title: String,
        override val prefKey: String?,
        override val subtitle: String? = null,
        override val status: EntryStatus = EntryStatus.EXISTING,
        /** 依赖的前置开关（例如通知开关依赖副一屏开启）。 */
        val dependsOn: String? = null
    ) : SettingsEntry

    /** 数值滑块。 */
    data class Slider(
        override val id: String,
        override val title: String,
        override val prefKey: String?,
        val min: Float,
        val max: Float,
        val steps: Int = 0,
        val unit: String? = null,
        override val subtitle: String? = null,
        override val status: EntryStatus = EntryStatus.EXISTING,
        val dependsOn: String? = null
    ) : SettingsEntry

    /** 多选一（枚举）选择器。 */
    data class Picker(
        override val id: String,
        override val title: String,
        override val prefKey: String?,
        val options: List<PickerOption>,
        override val subtitle: String? = null,
        override val status: EntryStatus = EntryStatus.EXISTING
    ) : SettingsEntry

    /** 跳转到子页面 / 其他 Activity。 */
    data class Navigation(
        override val id: String,
        override val title: String,
        /** 目标：SettingsDestination 名或 Activity 类名。 */
        val destination: String,
        override val subtitle: String? = null,
        override val status: EntryStatus = EntryStatus.EXISTING
    ) : SettingsEntry {
        override val prefKey: String? get() = null
    }

    /** 一次性动作（导出、重置、授权跳转等）。 */
    data class Action(
        override val id: String,
        override val title: String,
        /** 动作标识，重构时映射到具体回调。 */
        val action: String,
        override val subtitle: String? = null,
        override val status: EntryStatus = EntryStatus.EXISTING
    ) : SettingsEntry {
        override val prefKey: String? get() = null
    }
}

object SettingsHierarchy {

    // ---------------------------------------------------------------- 外观
    private val appearance = SettingsGroup(
        id = "appearance",
        title = "外观",
        items = listOf(
            SettingsEntry.Picker(
                id = "ui_style",
                title = "界面风格",
                prefKey = "ui_style",
                options = listOf(
                    PickerOption("APPLE_WATCH", "Apple Watch 风格"),
                    PickerOption("MATERIAL_3", "Material 3 风格"),
                    PickerOption("MIUIX", "MIUIX 风格")
                )
            ),
            SettingsEntry.Picker(
                id = "theme_mode",
                title = "深色模式",
                prefKey = "theme_mode",
                options = listOf(
                    PickerOption("SYSTEM", "跟随系统"),
                    PickerOption("DARK", "深色"),
                    PickerOption("LIGHT", "浅色")
                )
            ),
            SettingsEntry.Picker(
                id = "accent_color",
                title = "主题色",
                prefKey = null,
                subtitle = "自定义强调色（规划中，目前由风格令牌决定）",
                options = emptyList(),
                status = EntryStatus.PLANNED
            ),
            SettingsEntry.Toggle(
                id = "app_list_watchface_colors",
                title = "应用列表表盘取色",
                prefKey = "app_list_watchface_colors",
                subtitle = "应用列表背景跟随表盘颜色"
            )
        )
    )

    // ---------------------------------------------------------------- 表盘
    private val watchFace = SettingsGroup(
        id = "watchface",
        title = "表盘",
        items = listOf(
            SettingsEntry.Navigation(
                id = "watchface_picker",
                title = "表盘选择",
                destination = "WATCH_FACES",
                subtitle = "选择、扫描与删除表盘；selected_watchface_id"
            ),
            SettingsEntry.Navigation(
                id = "watchface_store",
                title = "表盘商店",
                destination = "WatchStoreActivity",
                subtitle = "在线表盘（主工作区开发中）",
                status = EntryStatus.PLANNED
            ),
            SettingsEntry.Navigation(
                id = "watchface_editor",
                title = "表盘编辑器",
                destination = "WatchEditorActivity",
                subtitle = "自定义表盘编辑（主工作区开发中）",
                status = EntryStatus.PLANNED
            ),
            SettingsEntry.Navigation(
                id = "builtin_watchface_config",
                title = "内置表盘设置",
                destination = "InternalWatchFaceConfigActivity",
                subtitle = "图片 / 视频表盘的时钟样式、颜色与 MD3E 图形"
            ),
            SettingsEntry.Action(
                id = "watchface_font",
                title = "表盘字体",
                action = "pick_watchface_font",
                subtitle = "watchface_font_path"
            ),
            SettingsEntry.Toggle(
                id = "watchface_bottom_fade",
                title = "表盘底部渐隐遮罩",
                prefKey = "watchface_bottom_fade_enabled"
            ),
            SettingsEntry.Toggle(
                id = "watchface_text_entry_animation",
                title = "表盘文字飞入动画",
                prefKey = "watchface_text_entry_animation",
                subtitle = "每次进入表盘时重播文字进入动画"
            ),
            SettingsEntry.Toggle(
                id = "watchface_text_exit_animation",
                title = "表盘文字退出动画",
                prefKey = "watchface_text_exit_animation",
                subtitle = "滑入其他页面时播放文字退出动画"
            ),
            SettingsEntry.Toggle(
                id = "watchface_status_indicators",
                title = "表盘状态提示",
                prefKey = "watchface_status_indicators",
                subtitle = "顶部充电闪电和通知红点"
            ),
            SettingsEntry.Toggle(
                id = "watchface_charging_power_text",
                title = "充电功率文字",
                prefKey = "watchface_charging_power_text"
            )
        )
    )

    // ------------------------------------------------------------ 应用列表
    private val appList = SettingsGroup(
        id = "app_list",
        title = "应用列表",
        items = listOf(
            SettingsEntry.Picker(
                id = "layout_mode",
                title = "布局",
                prefKey = "layout_mode",
                options = listOf(
                    PickerOption("Honeycomb", "蜂窝布局"),
                    PickerOption("List", "列表布局")
                )
            ),
            SettingsEntry.Slider(
                id = "honeycomb_cols",
                title = "蜂窝列数",
                prefKey = "honeycomb_cols",
                min = 1f,
                max = 5f,
                steps = 3
            ),
            SettingsEntry.Slider(
                id = "app_list_scale",
                title = "应用列表整体大小",
                prefKey = "app_list_scale_percent",
                min = 50f,
                max = 200f,
                steps = 14,
                unit = "%"
            ),
            SettingsEntry.Slider(
                id = "app_list_left_safe_inset",
                title = "应用列表左侧安全区",
                prefKey = "app_list_left_safe_inset_percent",
                min = 0f,
                max = 50f,
                steps = 9,
                unit = "%"
            ),
            SettingsEntry.Navigation(
                id = "icon_packs",
                title = "图标包",
                destination = "ICON_PACKS",
                subtitle = "icon_pack_package"
            ),
            SettingsEntry.Toggle(
                id = "low_res_icons",
                title = "低分辨率图标",
                prefKey = "low_res_icons"
            ),
            SettingsEntry.Toggle(
                id = "two_tone_icons",
                title = "双色图标",
                prefKey = "two_tone_icons_enabled"
            ),
            SettingsEntry.Toggle(
                id = "icon_shadow",
                title = "图标阴影",
                prefKey = "app_icon_shadow_enabled"
            ),
            SettingsEntry.Toggle(
                id = "app_list_fisheye",
                title = "鱼眼效果",
                prefKey = "app_list_fisheye_enabled"
            ),
            SettingsEntry.Slider(
                id = "app_list_fisheye_range_rows",
                title = "鱼眼生效范围",
                prefKey = "app_list_fisheye_range_rows",
                min = 1f,
                max = 8f,
                steps = 6,
                unit = "行",
                dependsOn = "app_list_fisheye"
            ),
            SettingsEntry.Slider(
                id = "app_list_fisheye_strength",
                title = "鱼眼效果强度",
                prefKey = "app_list_fisheye_strength_percent",
                min = 0f,
                max = 200f,
                steps = 19,
                unit = "%",
                dependsOn = "app_list_fisheye"
            ),
            SettingsEntry.Toggle(
                id = "app_list_edge_spacing_compression",
                title = "边缘图标间距压缩",
                prefKey = "app_list_edge_spacing_compression_enabled",
                dependsOn = "app_list_fisheye"
            ),
            SettingsEntry.Toggle(
                id = "fast_flow_animation",
                title = "快速流逝动画",
                prefKey = "fast_flow_animation_enabled",
                subtitle = "蜂窝模式甩尾：远离中心的图标滚动更快"
            ),
            SettingsEntry.Toggle(
                id = "honeycomb_fast_scroll_optimization",
                title = "滑动流畅度优化",
                prefKey = "honeycomb_fast_scroll_optimization"
            ),
            SettingsEntry.Slider(
                id = "honeycomb_top_fade",
                title = "顶部渐隐范围",
                prefKey = "honeycomb_top_fade",
                min = 0f,
                max = 160f,
                steps = 15,
                unit = "dp"
            ),
            SettingsEntry.Slider(
                id = "honeycomb_bottom_fade",
                title = "底部渐隐范围",
                prefKey = "honeycomb_bottom_fade",
                min = 0f,
                max = 160f,
                steps = 15,
                unit = "dp"
            ),
            SettingsEntry.Toggle(
                id = "app_list_row_border",
                title = "列表模式底色遮罩",
                prefKey = "app_list_row_border_enabled",
                dependsOn = "layout_mode == List"
            ),
            SettingsEntry.Toggle(
                id = "app_list_folders",
                title = "允许创建文件夹（实验性）",
                prefKey = "app_list_folders_enabled"
            ),
            SettingsEntry.Navigation(
                id = "hidden_apps",
                title = "隐藏应用",
                destination = "HIDDEN_APPS",
                subtitle = "hidden_apps"
            )
        )
    )

    // ---------------------------------------------------------------- 动画
    private val animation = SettingsGroup(
        id = "animation",
        title = "动画",
        items = listOf(
            SettingsEntry.Picker(
                id = "animation_curve_tier",
                title = "动画曲线档位",
                prefKey = null,
                subtitle = "全局弹簧曲线档位（规划中）",
                options = listOf(
                    PickerOption("SOFT", "柔和"),
                    PickerOption("STANDARD", "标准"),
                    PickerOption("SNAPPY", "利落")
                ),
                status = EntryStatus.PLANNED
            ),
            SettingsEntry.Toggle(
                id = "animation_override",
                title = "桌面返回动画",
                prefKey = "animation_override_enabled",
                subtitle = "启用渐隐过渡"
            ),
            SettingsEntry.Toggle(
                id = "classic_return_animation",
                title = "经典返回动画",
                prefKey = "classic_return_animation_enabled",
                subtitle = "返回应用列表时从屏幕中间缩放"
            ),
            SettingsEntry.Toggle(
                id = "app_list_return_shrink",
                title = "应用列表返回收缩",
                prefKey = "app_list_return_shrink_enabled",
                subtitle = "主工作区开发中的转场开关",
                status = EntryStatus.PLANNED
            ),
            SettingsEntry.Toggle(
                id = "face_return_shrink",
                title = "表盘返回收缩",
                prefKey = "face_return_shrink_enabled",
                subtitle = "主工作区开发中的转场开关",
                status = EntryStatus.PLANNED
            ),
            SettingsEntry.Toggle(
                id = "splash_icon",
                title = "启动遮罩",
                prefKey = "splash_icon",
                subtitle = "打开应用时显示图标过渡"
            ),
            SettingsEntry.Slider(
                id = "splash_delay",
                title = "遮罩时长",
                prefKey = "splash_delay",
                min = 300f,
                max = 1500f,
                steps = 11,
                unit = "ms",
                dependsOn = "splash_icon"
            ),
            SettingsEntry.Navigation(
                id = "music_text_animation",
                title = "音乐文字切换动画",
                destination = "MUSIC_TEXT_ANIMATION",
                subtitle = "music_text_switch_animation"
            ),
            SettingsEntry.Toggle(
                id = "blur",
                title = "高级材质",
                prefKey = "blur_enabled",
                subtitle = "建议 Android 12+ 设备开启"
            ),
            SettingsEntry.Toggle(
                id = "edge_blur",
                title = "边缘模糊（实验性）",
                prefKey = "edge_blur_enabled",
                dependsOn = "blur"
            ),
            SettingsEntry.Slider(
                id = "edge_blur_radius",
                title = "边缘模糊半径",
                prefKey = "honeycomb_edge_blur_tenths",
                min = 0.5f,
                max = 5f,
                steps = 8,
                unit = "dp",
                dependsOn = "edge_blur"
            )
        )
    )

    // ------------------------------------------------------------ 手势与输入
    private val gesturesAndInput = SettingsGroup(
        id = "input",
        title = "手势与输入",
        items = listOf(
            SettingsEntry.Toggle(
                id = "side_screen",
                title = "副一屏",
                prefKey = "side_screen_enabled",
                subtitle = "表盘右滑进入快捷启动和通知预览"
            ),
            SettingsEntry.Toggle(
                id = "show_notification",
                title = "显示通知",
                prefKey = "show_notification",
                dependsOn = "side_screen"
            ),
            SettingsEntry.Toggle(
                id = "show_ongoing_notifications",
                title = "显示常驻通知",
                prefKey = "show_ongoing_notifications",
                dependsOn = "show_notification"
            ),
            SettingsEntry.Toggle(
                id = "show_widget_page",
                title = "显示小组件页",
                prefKey = "show_widget_page",
                subtitle = "表盘左滑进入独立小组件页面"
            ),
            SettingsEntry.Toggle(
                id = "show_control_center",
                title = "显示控制中心",
                prefKey = "show_control_center",
                subtitle = "表盘下拉进入亮度、音量和快捷开关"
            ),
            SettingsEntry.Toggle(
                id = "show_music_controls",
                title = "音乐控制卡片",
                prefKey = "show_music_controls",
                dependsOn = "show_control_center"
            ),
            SettingsEntry.Toggle(
                id = "double_tap_lock_screen",
                title = "双击表盘息屏",
                prefKey = "double_tap_lock_screen_enabled",
                subtitle = "需要无障碍授权"
            ),
            SettingsEntry.Toggle(
                id = "power_menu_button",
                title = "控制中心电源按钮",
                prefKey = "power_menu_button_enabled",
                subtitle = "需要无障碍授权"
            ),
            SettingsEntry.Toggle(
                id = "rotary_haptics",
                title = "表冠滚动震动",
                prefKey = "rotary_haptics_enabled"
            ),
            SettingsEntry.Toggle(
                id = "show_step_count",
                title = "显示步数",
                prefKey = "show_step_count",
                subtitle = "副一屏底部电池右侧"
            )
        )
    )

    // ---------------------------------------------------------------- 系统
    private val system = SettingsGroup(
        id = "system",
        title = "系统",
        items = listOf(
            SettingsEntry.Navigation(
                id = "backup",
                title = "导入/导出配置",
                destination = "BACKUP",
                subtitle = "备份设置、排序和表盘资源为 ZIP"
            ),
            SettingsEntry.Slider(
                id = "global_ui_scale",
                title = "全局 UI 大小",
                prefKey = "global_ui_scale_percent",
                min = 50f,
                max = 150f,
                steps = 9,
                unit = "%"
            ),
            SettingsEntry.Action(
                id = "accessibility",
                title = "无障碍授权",
                action = "open_accessibility_settings",
                subtitle = "双击息屏 / 电源菜单依赖此授权"
            ),
            SettingsEntry.Action(
                id = "notification_access",
                title = "通知使用权",
                action = "open_notification_listener_settings",
                subtitle = "通知中心与音乐控制依赖此授权",
                status = EntryStatus.PLANNED
            ),
            SettingsEntry.Toggle(
                id = "hide_from_recents",
                title = "隐藏后台卡片",
                prefKey = "hide_from_recents"
            ),
            SettingsEntry.Toggle(
                id = "builtin_manager_thumbnails",
                title = "内置管理器缩略图",
                prefKey = "builtin_manager_thumbnails"
            ),
            SettingsEntry.Action(
                id = "export_log",
                title = "导出日志",
                action = "export_log"
            ),
            SettingsEntry.Toggle(
                id = "diagnostic_logging",
                title = "诊断日志模式",
                prefKey = null,
                subtitle = "非 DataStore：FlueLog 内部状态，5 分钟自动关闭"
            ),
            SettingsEntry.Action(
                id = "reset_defaults",
                title = "恢复默认设置",
                action = "reset_settings"
            ),
            SettingsEntry.Navigation(
                id = "about",
                title = "关于",
                destination = "ABOUT",
                subtitle = "版本、作者、交流群与捐赠"
            )
        )
    )

    /** 新信息架构的完整分组列表（顺序即页面展示顺序）。 */
    val groups: List<SettingsGroup> = listOf(
        appearance,
        watchFace,
        appList,
        animation,
        gesturesAndInput,
        system
    )

    /** 按 id 查找条目，供未来的渲染层使用。 */
    fun findEntry(id: String): SettingsEntry? =
        groups.asSequence().flatMap { it.items }.firstOrNull { it.id == id }
}
