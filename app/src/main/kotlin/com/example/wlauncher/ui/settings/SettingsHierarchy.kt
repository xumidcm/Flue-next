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
                id = "watchface_status_indicators",
                title = "表盘状态提示",
                prefKey = "watchface_status_indicators",
                subtitle = "顶部充电闪电和通知红点"
            ),
            SettingsEntry.Toggle(
                id = "watchface_charging_power_text",
                title = "充电功率文字",
                prefKey = "watchface_charging_power_text"
            ),
            SettingsEntry.Toggle(
                id = "dingdingcat_fill_screen",
                title = "叮叮猫表盘铺满全屏",
                prefKey = "dingdingcat_fill_screen",
                subtitle = "仅当前表盘为叮叮猫 ZIP 时显示"
            ),
            SettingsEntry.Slider(
                id = "dingdingcat_playback_speed",
                title = "叮叮猫播放速度",
                prefKey = "dingdingcat_playback_speed_percent",
                min = 25f,
                max = 400f,
                unit = "%",
                subtitle = "仅当前表盘为叮叮猫 ZIP 时显示"
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
                subtitle = "非 DataStore：ILog 内部状态，5 分钟自动关闭"
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

// --------------------------------------------------------------------- 搜索

/**
 * 一条可被搜索命中的设置项。
 *
 * [destination] 是 `SettingsDestination` 的枚举名，[itemKey] 是该页 LazyColumn
 * 里对应 item 的 key —— 命中后就靠这两个值跳页 + 滚到那一行并高亮。
 */
data class SettingsSearchEntry(
    val itemKey: String,
    val destination: String,
    val title: String,
    /** 面包屑，例如「外观 › 显示与外观」，让用户知道自己会跳到哪。 */
    val breadcrumb: String,
    /** 额外关键词（拼音首字母、英文名、同义词），只参与匹配不展示。 */
    val keywords: String = "",
)

/**
 * 设置搜索索引：整个设置页里「可以直接跳过去的一行」的平铺清单。
 *
 * 这是 [SettingsHierarchy] 的可导航投影 —— 分组模型讲的是信息架构，
 * 这里讲的是「哪一行、在哪一页、key 是什么」。新增设置项时**两边都要加**，
 * 否则新项搜不到。
 */
object SettingsSearchIndex {

    val entries: List<SettingsSearchEntry> = listOf(
        // ---------------------------------------------------------- 外观
        e("layout_honeycomb", "APPEARANCE", "蜂窝布局", "外观 › 显示与外观", "honeycomb layout fwbj"),
        e("layout_list", "APPEARANCE", "列表布局", "外观 › 显示与外观", "list layout lbbj"),
        e("theme_mode_system", "APPEARANCE", "跟随系统", "外观 › 深色模式", "system theme gssx"),
        e("theme_mode_dark", "APPEARANCE", "深色模式", "外观 › 深色模式", "dark mode ssms"),
        e("theme_mode_light", "APPEARANCE", "浅色模式", "外观 › 深色模式", "light mode qsms"),
        e("ui_style_apple_watch", "APPEARANCE", "Apple Watch 风格", "外观 › 界面风格", "apple watch aw"),
        e("ui_style_material_3", "APPEARANCE", "Material 3 风格", "外观 › 界面风格", "material md3"),
        e("ui_style_miuix", "APPEARANCE", "MIUIX 风格", "外观 › 界面风格", "miuix hyperos xiaomi"),
        e("screen_shape_auto", "APPEARANCE", "屏幕形状自动", "外观 › 屏幕形状", "auto screen shape pmxz"),
        e("screen_shape_round", "APPEARANCE", "圆屏", "外观 › 屏幕形状", "round yp"),
        e("screen_shape_square", "APPEARANCE", "方屏", "外观 › 屏幕形状", "square fp"),
        e("blur_toggle", "APPEARANCE", "高级材质", "外观 › 视觉效果", "blur mohu gjcz"),
        e("edge_blur_toggle", "APPEARANCE", "边缘模糊", "外观 › 视觉效果", "edge blur bymh"),
        e("edge_blur_radius", "APPEARANCE", "边缘模糊半径", "外观 › 视觉效果", "edge blur radius"),
        e("top_fade", "APPEARANCE", "顶部渐隐范围", "外观 › 视觉效果", "top fade dbjy"),
        e("bottom_fade", "APPEARANCE", "底部渐隐范围", "外观 › 视觉效果", "bottom fade dbjy"),
        e("splash_toggle", "APPEARANCE", "启动遮罩", "外观 › 启动", "splash qdzz"),
        e("splash_delay", "APPEARANCE", "遮罩时长", "外观 › 启动", "splash delay zzsc"),
        e("direct_launch_app_list", "APPEARANCE", "打开 Flue 直接进入应用列表", "外观 › 启动", "direct launch app list"),

        // ------------------------------------------------------ 显示适配
        e("global_ui_scale", "DISPLAY_SIZE", "全局 UI 大小", "外观 › 显示适配", "global ui scale qjdx"),
        e("honeycomb_cols", "DISPLAY_SIZE", "蜂窝列数", "外观 › 显示适配", "columns fwls"),
        e("app_list_scale", "DISPLAY_SIZE", "应用列表整体大小", "外观 › 显示适配", "app list scale"),
        e("app_list_fisheye", "DISPLAY_SIZE", "鱼眼效果", "外观 › 显示适配", "fisheye yyxg"),
        e("app_list_fisheye_range_rows", "DISPLAY_SIZE", "鱼眼生效范围", "外观 › 显示适配", "fisheye range"),
        e("app_list_fisheye_strength", "DISPLAY_SIZE", "鱼眼效果强度", "外观 › 显示适配", "fisheye strength"),
        e("app_list_edge_spacing_compression", "DISPLAY_SIZE", "边缘图标间距压缩", "外观 › 显示适配", "edge spacing"),
        e("app_list_left_safe_inset", "DISPLAY_SIZE", "应用列表左侧安全区", "外观 › 显示适配", "safe area zcaqq"),
        e("rotary_haptics", "DISPLAY_SIZE", "旋转输入触感", "外观 › 显示适配", "rotary haptics zhendong 震动 表冠"),

        // ---------------------------------------------------------- 表盘
        e("watchface_refresh", "WATCH_FACES", "刷新表盘列表", "表盘", "refresh watch face"),
        e("watchface_import_archive", "WATCH_FACES", "导入表盘", "表盘", "import watch face drbp"),
        e("watchface_store", "WATCH_FACES", "表盘商店", "表盘", "store bpsd"),
        e("watchface_more", "WATCH_FACES", "表盘更多设置", "表盘", "more watch face settings"),
        e("watchface_font", "WATCH_FACE_MORE", "表盘字体", "表盘 › 更多设置", "font bpzt"),
        e("watchface_bottom_fade", "WATCH_FACE_MORE", "表盘底部渐隐遮罩", "表盘 › 更多设置", "bottom fade"),
        e("watchface_status_indicators", "WATCH_FACE_MORE", "表盘状态提示", "表盘 › 更多设置", "status indicators bpztts"),
        e("watchface_charging_power_text", "WATCH_FACE_MORE", "充电功率文字", "表盘 › 更多设置", "charging power cdglwz"),
        e("watchmaker_force_fill", "WATCH_FACE_MORE", "表盘强制铺满", "表盘 › 更多设置", "force fill"),
        e("watchmaker_body_sensors", "WATCH_FACE_MORE", "表盘心率数据", "表盘 › 更多设置", "heart rate body sensors xinlv"),
        e("dingdingcat_fill_screen", "WATCH_FACE_MORE", "旧版表盘铺满全屏", "表盘 › 更多设置", "fill screen"),
        e("dingdingcat_playback_speed", "WATCH_FACE_MORE", "旧版表盘播放速度", "表盘 › 更多设置", "playback speed"),

        // ------------------------------------------------------ 应用列表
        e("icon_packs", "ROOT", "图标包", "应用列表", "icon pack tbb"),
        e("hidden_apps", "ROOT", "隐藏应用", "应用列表", "hidden apps ycyy"),
        e("app_list_watchface_colors", "APP_LIST_BEHAVIOR", "应用遮罩表盘取色", "功能菜单 › 应用列表行为", "launch mask colors"),
        e("app_list_row_border", "APP_LIST_BEHAVIOR", "列表模式底色遮罩", "功能菜单 › 应用列表行为", "row background"),
        e("app_list_folders_enabled", "APP_LIST_BEHAVIOR", "允许创建文件夹", "功能菜单 › 应用列表行为", "folders wjj"),

        // ---------------------------------------------------- 负一屏 / 通知 / 控制中心
        e("side_screen_toggle", "SIDE_SCREEN", "副一屏", "功能菜单 › 负一屏设置", "side screen fyp negative one"),
        e("widget_center_toggle", "SIDE_SCREEN", "显示小组件页", "功能菜单 › 负一屏设置", "widget page xzjy"),
        e("show_step_count", "SIDE_SCREEN", "显示步数", "功能菜单 › 负一屏设置", "step count bushu"),
        e("notification_center_toggle", "NOTIFICATION_CENTER", "显示通知", "功能菜单 › 通知中心设置", "notifications tongzhi"),
        e("ongoing_notification_toggle", "NOTIFICATION_CENTER", "显示常驻通知", "功能菜单 › 通知中心设置", "ongoing notifications"),
        e("notification_access", "NOTIFICATION_CENTER", "通知使用权", "功能菜单 › 通知中心设置", "notification access permission quanxian"),
        e("control_center_toggle", "CONTROL_CENTER", "显示控制中心", "功能菜单 › 控制中心设置", "control center kzzx"),
        e("music_controls_toggle", "CONTROL_CENTER", "音乐控制卡片", "功能菜单 › 控制中心设置", "music media yinyue"),
        e("media_custom_actions_toggle", "CONTROL_CENTER", "音乐应用自定义按钮", "功能菜单 › 控制中心设置", "media actions"),
        e("music_notification_swap_toggle", "CONTROL_CENTER", "互换通知和音乐组件", "功能菜单 › 控制中心设置", "swap music notification"),
        e("double_tap_lock_screen", "GESTURES", "双击表盘息屏", "功能菜单 › 手势与无障碍", "double tap lock sjxp"),
        e("power_menu_button", "GESTURES", "控制中心电源按钮", "功能菜单 › 手势与无障碍", "power button dyan"),
        e("accessibility_settings", "GESTURES", "无障碍授权", "功能菜单 › 手势与无障碍", "accessibility wzasq permission"),

        // ------------------------------------------------------ 性能与动画
        e("low_res", "PERFORMANCE", "低分辨率图标", "动画 › 性能与动画", "low res icons"),
        e("two_tone_icons", "PERFORMANCE", "双色图标", "动画 › 性能与动画", "two tone icons sstb"),
        e("icon_shadow", "PERFORMANCE", "图标阴影", "动画 › 性能与动画", "icon shadow tbyy"),
        e("anim_override", "PERFORMANCE", "Flue 启动与返回动画", "动画 › 性能与动画", "launch return animation"),
        e("app_list_return_shrink", "PERFORMANCE", "应用列表收缩动画", "动画 › 性能与动画", "shrink animation sssh"),
        e("app_list_return_icon_float", "PERFORMANCE", "返回时图标浮动", "动画 › 性能与动画", "icon float tbfd"),
        e("face_return_shrink", "PERFORMANCE", "表盘收缩动画", "动画 › 性能与动画", "watch face shrink"),
        e("app_list_stagger", "PERFORMANCE", "甩尾入场动画", "动画 › 性能与动画", "stagger swrc"),
        e("reduce_dynamic_effects", "PERFORMANCE", "减弱动态效果", "动画 › 性能与动画", "reduce motion jrdtxg"),
        e("hide_from_recents", "PERFORMANCE", "隐藏后台卡片", "动画 › 性能与动画", "recents ychtkp"),
        e("builtin_manager_thumbnails", "PERFORMANCE", "内置管理器缩略图", "动画 › 性能与动画", "thumbnails"),
        e("motion_profile", "PERFORMANCE", "动画曲线配置", "动画 › 性能与动画", "motion profile dhqx"),
        e("music_text_animation", "PERFORMANCE", "音乐文字切换动画", "动画 › 性能与动画", "music text animation"),
        e("motion_bounce", "MOTION_PROFILE", "触边回弹", "动画 › 动画曲线配置", "bounce cbht"),
        e("motion_edge_strength", "MOTION_PROFILE", "边界强度", "动画 › 动画曲线配置", "edge strength bjqd"),
        e("motion_export", "MOTION_PROFILE", "导出自定义曲线", "动画 › 动画曲线配置", "export motion"),
        e("motion_import", "MOTION_PROFILE", "导入曲线", "动画 › 动画曲线配置", "import motion"),

        // ------------------------------------------------------ 系统与工具
        e("backup_menu", "TOOLS", "导入/导出配置", "系统与工具 › 工具", "backup restore bfhf"),
        e("export_log", "TOOLS", "导出日志", "系统与工具 › 工具", "export log dcrz"),
        e("diagnostic_logging", "TOOLS", "诊断日志模式", "系统与工具 › 工具", "diagnostic log"),
        e("reset_defaults", "TOOLS", "恢复默认设置", "系统与工具 › 工具", "reset hfmr"),
        e("about_card", "ABOUT", "关于 Flue", "关于", "about version gy"),
    )

    /**
     * 搜索：大小写不敏感，标题命中排在关键词命中前面。
     * 空查询返回空列表（调用方据此决定显示普通列表还是结果列表）。
     */
    fun search(query: String): List<SettingsSearchEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val titleHits = entries.filter { it.title.lowercase().contains(q) }
        val otherHits = entries.filter {
            it !in titleHits &&
                (it.breadcrumb.lowercase().contains(q) || it.keywords.lowercase().contains(q))
        }
        return titleHits + otherHits
    }
}

private fun e(
    itemKey: String,
    destination: String,
    title: String,
    breadcrumb: String,
    keywords: String = "",
) = SettingsSearchEntry(
    itemKey = itemKey,
    destination = destination,
    title = title,
    breadcrumb = breadcrumb,
    keywords = keywords,
)
