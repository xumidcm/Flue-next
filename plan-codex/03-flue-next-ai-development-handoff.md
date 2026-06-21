# flue-next AI 开发交接文档

更新时间：2026-06-22  
读者：以后接手 `flue-next` 的 AI 或开发者。  
当前旧仓库路径：`C:\Users\22403\Documents\Flue`

## 1. 接手规则

- 默认只读旧 `Flue` 代码。
- 新开发应在全新 `flue-next` 项目中进行。
- 不要把旧版大文件搬进新版。
- 不要复制外部 GitLab/GitHub 参考项目源码，除非用户提供授权。
- 不确定需求时先问用户；用户说得模糊的内容放入 TODO，不要擅自变成当前实现范围。
- 所有用户可见文案必须使用 Android `strings.xml` 资源体系；不要在 Compose、View、Activity、ViewModel、Repository 中硬编码中文或英文 UI 文案。
- 修改文档或代码后，如果在 git 仓库内，默认只提交自己改的文件。

## 2. 当前旧仓库事实

构建栈：

- Gradle Kotlin DSL。
- Android Gradle Plugin `8.13.2`。
- Kotlin `2.3.21`。
- Compose BOM `2026.04.01`。
- `minSdk = 27`。
- `compileSdk = 36`。
- `targetSdk = 36`。
- DataStore Preferences。
- `cloudy 0.5.0` 用于低版本 blur。

主要旧代码路径：

- `app/src/main/kotlin/com/example/wlauncher/LauncherActivity.kt`
- `app/src/main/kotlin/com/example/wlauncher/SettingsActivity.kt`
- `app/src/main/kotlin/com/example/wlauncher/viewmodel/LauncherViewModel.kt`
- `app/src/main/kotlin/com/example/wlauncher/data/repository/AppRepository.kt`
- `app/src/main/kotlin/com/example/wlauncher/data/repository/AppListItemStorage.kt`
- `app/src/main/kotlin/com/example/wlauncher/ui/drawer/HoneycombScreen.kt`
- `app/src/main/kotlin/com/example/wlauncher/ui/drawer/ListDrawerScreen.kt`
- `app/src/main/kotlin/com/example/wlauncher/ui/controlcenter/ControlCenterLayer.kt`
- `app/src/main/kotlin/com/example/wlauncher/ui/smartstack/SmartStackLayer.kt`
- `app/src/main/kotlin/com/example/wlauncher/ui/notification/NotificationLayer.kt`
- `app/src/main/kotlin/com/example/wlauncher/ui/home/WatchFaceLayer.kt`
- `app/src/main/kotlin/com/example/wlauncher/ui/anim/BlurSupport.kt`
- `app/src/main/kotlin/com/example/wlauncher/ui/anim/BlurTransition.kt`
- `app/src/main/kotlin/com/example/wlauncher/ui/input/RotaryInput.kt`
- `app/src/main/kotlin/com/example/wlauncher/ui/common/WatchBatteryPill.kt`
- `app/src/main/java/com/flue/launcher/watchface/*`

工作区在调查时已经有用户侧 dirty/untracked 文件。不要清理、重置、覆盖这些文件。

## 3. 旧版已有能力

应用列表：

- 扫描系统 launcher app。
- 内置设置入口。
- 自定义排序。
- 隐藏应用。
- 图标包。
- 低清/圆形旧图标。
- 双色图标。
- 快捷方式。
- 文件夹。
- 蜂窝、列表、Material dense grid。
- 鱼眼、边缘间距压缩、边缘淡出/模糊、fast flow。
- 长按菜单、拖拽排序、悬停创建文件夹。

Launcher：

- HOME 入口。
- 表盘、应用列表、负一屏、通知中心、控制中心、设置层叠。
- 上滑/下滑/左右滑手势。
- 启动应用前的图标遮罩。
- Direct launch app list。
- 从最近任务隐藏。

表盘：

- 内置默认表盘。
- 图片表盘。
- 视频表盘。
- Lunch 外部表盘兼容。
- 叮叮猫表盘兼容。
- JB `.watch` 相关兼容。
- 表盘颜色提取、底部 fade、充电状态、通知红点。

设置：

- 表盘管理。
- 外观、动画、显示大小。
- 隐藏应用。
- 图标包。
- 性能选项。
- 工具、备份、关于。
- 全局 UI 缩放。
- 应用列表左安全区。
- 小米 17 Pro 系列一次性预设。

通知/控制/负一屏：

- NotificationListenerService。
- 通知聚合。
- 通知中心滑动删除。
- 副一屏快捷入口。
- 音乐控制。
- 电池胶囊。
- 步数。
- 控制中心系统开关入口。

## 4. 旧版不要继承的结构

不要继承：

- 单个 `LauncherViewModel` 管全部业务。
- 单个 `SettingsActivity` 管全部设置页面。
- 单个 `HoneycombScreen` 管布局、动画、拖拽、文件夹、命中测试。
- `AppRepository` 同时负责扫描、排序、启动、图标、文件夹。
- 领域模型持有 Compose `ImageBitmap`。
- 外部 APK 表盘 DexClassLoader 主线。
- 启动返回靠全局 `launchingExternalApp` 判断。

应该重写为：

- `AppEntry` sealed hierarchy。
- `IconRef` 和 `IconPipeline`。
- `AppListLayoutEngine`。
- `FisheyeTransform`。
- `DragController`。
- `LauncherScene` 状态机。
- `ExternalLaunchSession`。
- typed settings schema。
- `DeviceProfile`。
- `BackdropLayer`。
- `WatchFaceIr`。

## 5. 新版建议模块

```text
core/model
core/settings
core/device
core/performance
data/apps
data/icons
data/watchface
launcher/scene
launcher/gesture
launcher/appgrid
launcher/backdrop
launcher/panels
launcher/watchface
ui/onboarding
ui/composeSettings
ui/miuixSettings
```

模块职责：

- `core/model`：纯 Kotlin 领域模型。
- `core/settings`：设置定义、默认值、迁移、备份映射。
- `core/device`：屏幕形状、安全区、设备档案、输入能力。
- `data/apps`：应用扫描、排序、隐藏、快捷方式、文件夹。
- `data/icons`：图标加载、图标包、缓存、双色图标。
- `data/watchface`：`.watch` 导入、解析、资源预算。
- `launcher/scene`：导航状态机和启动 session。
- `launcher/appgrid`：高性能应用列表。
- `launcher/backdrop`：表盘背景模糊、压暗、混色、圆角蒙版。
- `launcher/panels`：通知中心、负一屏、控制中心、小组件卡片。
- `ui/onboarding`：首启设备选择和预览应用。

## 6. 关键接口草案

应用入口：

```kotlin
sealed interface AppEntry {
    val id: EntryId
    val label: String
    val icon: IconRef
}
```

布局引擎：

```kotlin
interface AppListLayoutEngine {
    fun measure(
        entries: List<AppEntry>,
        viewport: Viewport,
        scroll: ScrollState,
        config: AppListLayoutConfig
    ): LayoutSnapshot
}
```

启动 session：

```kotlin
data class ExternalLaunchSession(
    val id: LaunchSessionId,
    val sourceScene: LauncherScene,
    val target: LaunchTarget,
    val origin: NormalizedOffset,
    val startedAtMillis: Long
)
```

设备档案：

```kotlin
data class DeviceProfile(
    val id: String,
    val screenShape: ScreenShape,
    val usageMode: DeviceUsageMode,
    val safeInsets: InsetsPercent,
    val input: InputProfile
)
```

Backdrop：

```kotlin
data class BackdropState(
    val progress: Float,
    val blurRadiusDp: Float,
    val dimAlpha: Float,
    val tintColor: Int,
    val tintAlpha: Float,
    val cornerMask: CornerMask
)
```

表盘：

```kotlin
interface WatchFaceRenderer {
    fun load(project: WatchFaceProject): LoadResult
    fun setVisible(visible: Boolean)
    fun renderFrame(frameTimeNanos: Long)
    fun release()
}
```

## 7. 表盘接手说明

flue-next 主线只支持 WatchMaker `.watch`。

参考事实：

- `.watch` 是 zip 改后缀。
- 主要文件是 `watch.xml`、`preview.jpg` 和资源。
- 标准画布 512x512。
- 中心原点，Y-up。
- 图层按 XML 顺序自底向上。

实现顺序：

1. zip 安全校验。
2. 解包到私有目录。
3. 解析 XML 到 `WatchFaceIr`。
4. 基础图层渲染。
5. 预览图。
6. palette/snapshot 输出给 BackdropLayer。
7. 不支持 tag 输出兼容报告。

不要把旧版 Lunch/叮叮猫兼容层迁入主线。

## 8. 模糊与特效接手说明

旧版 `platformBlur()`：

- API 31+ 用 RenderEffect。
- 低版本用 cloudy。

flue-next：

- API 31+ 使用 RenderEffect。
- API 30 及以下默认半透明/渐变/scrim 降级。
- OpenGL blur 作为后续增强。
- kyant 液态玻璃作为后续增强。
- 所有高级特效必须有关闭开关。

BackdropLayer 是唯一负责“表盘背景压暗、模糊、混色、圆角裁切”的模块。不要在每个卡片里单独写 blur。

## 9. 设备适配接手说明

首批必须支持：

- 方屏手表。
- 圆屏手表。
- 背屏设备。

引导必须提供：

- 设备类型选择。
- UI 缩放预览。
- 上下左右安全区调整。
- 预览后应用。
- 表冠/滚轮测试。

小米 17 Pro/17 Pro Max：

- 旧版检测过 `25098pn5ac`、`2509fpn0bc`、`xiaomi17pro`、`xiaomi17promax`。
- 新版不要只靠硬编码。
- 把这些作为 model alias。
- 用户可手动选择背屏档案和重新校准。

## 10. 设置接手说明

不要继续写一个 3000 行设置页。

正确路径：

1. 先写 setting definitions。
2. 再写设置仓库。
3. 再写 Compose Apple Watch 风格设置。
4. 再写 Miuix 设置。
5. 两套 UI 共享 schema。

设置页必须有实时预览：

- UI 缩放。
- 安全区。
- 鱼眼。
- 图标大小。
- 动画 preset。
- Backdrop 特效。

预览值和应用值要分开。

## 11. 国际化接手说明

flue-next 必须从项目初期就使用 `strings.xml` 做 i18n。

资源规则：

- 默认文案放在 `app/src/main/res/values/strings.xml`。
- 简体中文可放在 `values-zh-rCN/strings.xml`，英文可放在 `values-en/strings.xml`。
- string key 使用稳定、可搜索的命名，例如 `settings_display_title`、`onboarding_device_round_watch`。
- 同一含义的文案不要重复建 key。
- 带变量的文案使用 Android 格式化占位符，例如 `%1$s`、`%2$d`。

代码规则：

- Compose 中使用 `stringResource(R.string.xxx)`。
- Activity、Service、View、自定义 View 中使用 `context.getString(R.string.xxx)`。
- 领域层、状态机、仓库层不要持有 `Context` 只为了取文案；需要文案时返回 `ResourceText`、string resource id 或稳定错误码。
- `SettingDefinition` 只能保存标题/摘要/选项的 resource id，不保存本地化后的字符串。
- Toast、Dialog、权限说明、错误提示、空状态、引导页、设置页标题都必须走 string resource。

不要资源化：

- 应用名、表盘名、用户自定义文件夹名。
- 日志 tag。
- 数据库字段、DataStore key、备份 key、协议字段。
- 内部 enum/id。

检查要求：

- 开启 Android Lint hardcoded text 检查。
- 新增页面时同步新增或复用 string key。
- PR/提交说明里说明是否新增文案资源。

## 12. 测试建议

优先补：

- `AppListLayoutEngine` 单测。
- `FisheyeTransform` 单测。
- `EdgeSpacingCompressionTransform` 单测。
- 排序和最近 7 个应用单测。
- 文件夹命令单测。
- `ExternalLaunchSession` 状态机单测。
- `.watch` zip 安全校验单测。
- setting schema migration 单测。
- string resource 覆盖检查，避免设置项缺 title/summary。
- 圆屏/背屏截图回归。
- 应用列表 macrobenchmark。

## 13. 外部参考

参考链接：

- WatchMaker `.watch` 格式：`https://gitlab.com/ailife8881/watchmaker-definition`
- CloX：`https://gitlab.com/ailife8881/clox`
- WFTools：`https://gitlab.com/ailife8881/wftools`
- WatchMaker 社区商店：`https://gitlab.com/ailife8881/watchmaker-community-store`
- 旧 WatchLauncher：`https://github.com/xumidcm/WatchLauncher`

授权提醒：

- 子代理未确认这些仓库有明确 license。
- 不要复制源码和素材。
- 只参考行为、格式、API 和架构方向。

## 14. 开工前必须问用户的问题

如果开始真正实现 `flue-next`，先问：

1. 新项目目录名是否就是 `flue-next`。
2. 包名是否继续使用 `com.flue.launcher`。
3. 是否保持 `minSdk 27`。
4. `.watch` 首版需要兼容到什么程度。
5. 首启引导是否可跳过。
6. `DESIGN.md` 是否先由 AI 单独起草。
7. 圆屏和背屏优先测试设备有哪些。
8. 激活码/商店是否完全推迟到 MVP 后。
9. i18n 首批维护哪些语言，默认 `values/strings.xml` 使用简体中文还是英文。
