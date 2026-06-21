# flue-next 技术重构与生产级上线计划

更新时间：2026-06-22  
适用范围：全新项目 `flue-next`。旧 `Flue` 仓库仅作为只读证据来源，不在重构过程中原地修补。

## 1. 目标与边界

`flue-next` 的目标不是把旧版代码搬进新壳，而是重新实现一个可长期维护的 Android 手表 Launcher。新版必须保留旧版已经跑通的产品能力，但代码结构、状态机、渲染管线、设置系统和表盘系统要重新设计。

明确纳入本计划的内容：

- 首批适配圆屏、方屏手表、背屏设备，保留两套界面方向。Miuix 版本不要求做圆屏特殊适配。
- 首次启动引导页，包含设备类型选择、UI 整体缩放预览、上下左右安全区调整、预览后再应用。
- 小米 17 Pro/17 Pro Max 背屏保留左侧边距适配，并从硬编码升级为设备档案。
- 抛弃旧版 Lunch 表盘格式和叮叮猫表盘兼容，主线只支持 WatchMaker `.watch` 表盘格式。
- 重做下拉通知中心、负一屏/控制中心、小组件卡片、表盘压暗、表盘模糊、混色背景、圆角裁切。
- 重构应用列表、鱼眼、排序、关闭动画、启动/退出动画、最近 7 个应用界面、手势映射。
- 重构设置界面。Compose 自写 Apple Watch 风格一套，Miuix 一套。kyant 液态玻璃库作为后续性能分档特效。
- 保留旧版可迁移能力，但迁移前先审查，不直接复制旧版大文件。

不在本计划主线深挖的内容：

- 激活码服务器、收费后台、爱发电分发、私有表盘商店，只做长期 TODO。
- 内置 Watch 表盘编辑器只做长期 TODO。
- 完美复刻 watchOS 启动动画的逐帧参数，等后续提供细节后再落规格。
- Telegram 头像水滴模糊效果先作为技术实验，不承诺进入首版。

## 2. 旧版证据摘要

旧版当前是单 app 模块，技术栈为 Kotlin、Jetpack Compose、DataStore Preferences，`minSdk = 27`，`targetSdk = 36`，`compileSdk = 36`。核心问题是功能已经很多，但边界过于集中。

关键旧版文件：

- `LauncherActivity.kt`：主入口、页面层叠、手势、启动遮罩、表盘宿主接线。
- `LauncherViewModel.kt`：约 2600 行，集中管理设置 key、导航状态、表盘、应用列表、启动、通知、文件夹。
- `SettingsActivity.kt`：约 3300 行，单文件 Compose 设置页，页面导航和业务操作混在一起。
- `HoneycombScreen.kt`：约 2200 行，蜂窝布局、鱼眼、拖拽、文件夹、边缘模糊和滚动逻辑混合。
- `ControlCenterLayer.kt`：约 2160 行，控制中心 UI、媒体、系统开关、手势、卡片逻辑混合。
- `AppRepository.kt`：扫描 app、图标处理、缓存、排序、隐藏过滤、启动外部 app、文件夹合成集中在一个类。
- `BlurSupport.kt`：API 31+ 使用 `RenderEffect`，低版本走 `cloudy`。
- `WatchFaceLayer.kt`：内置图片/视频表盘、充电状态、通知红点、底部 fade。
- `WatchBatteryPill.kt`：电池胶囊和充电小闪电，可迁移为新版状态组件素材。

旧版必须避开的点：

- 不再把所有设置 key 平铺在一个 ViewModel。
- 不再让领域模型直接持有 Compose `ImageBitmap`。
- 不再把应用、快捷方式、文件夹、系统入口塞进同一个过载 `AppInfo`。
- 不再把启动返回动画依赖 `onPause/onResume + launchingExternalApp + delay`。
- 不再在应用列表 item 上堆过多 `graphicsLayer`、实时 blur、内部 mutable state。
- 不再靠单一 `leftSafeInsetPercent` 处理背屏和圆屏。
- 不再使用 DexClassLoader 外部 APK 表盘作为主线能力。

## 3. 新架构总览

建议新项目采用分层模块，即使首版仍是单 Gradle app，也按包边界强制拆分。

```text
flue-next/
  app/
    src/main/kotlin/com/flue/next/
      core/
        model/              纯领域模型
        settings/           typed settings schema、迁移、备份映射
        device/             DeviceProfile、Insets、InputProfile
        performance/        特效等级、能力探测、帧率预算
      data/
        apps/               InstalledAppSource、ShortcutStore、FolderStore、OrderStore
        icons/              IconPipeline、IconCache、IconRef
        watchface/          .watch 导入、解析、资源预算、IR 存储
      launcher/
        scene/              LauncherScene 状态机、ExternalLaunchSession
        gesture/            GestureActionMap、方向锁定、表冠输入
        appgrid/            View/自绘应用列表、LayoutEngine、Transforms
        backdrop/           BackdropLayer、模糊/压暗/混色/圆角裁切
        panels/             通知中心、负一屏、控制中心、小组件卡片
        watchface/          WatchFaceRenderer、WatchFaceHost
      ui/
        composeSettings/    Apple Watch 风格设置
        miuixSettings/      Miuix 设置
        onboarding/         首启引导
      testing/
        fixtures/           布局、表盘、设备档案测试数据
```

核心设计原则：

- 领域层不依赖 Compose、View、Android Canvas。
- UI 配置以 typed schema 为准，所有设置项有 id、默认值、范围、分组、迁移策略。
- Launcher 导航使用显式状态机，禁止散落的布尔 flag 互相推断。
- 应用列表布局、鱼眼、边缘压缩、fast flow 都做成纯函数或策略接口，可单测。
- 模糊和液态玻璃类高级特效必须有性能等级和关闭开关。
- 参考外部仓库只参考行为和 API，不复制源码。外部项目未确认 license。

## 4. 设备适配与首启引导

### 4.1 设备档案

新增 `DeviceProfile`：

```kotlin
data class DeviceProfile(
    val id: String,
    val label: String,
    val screenShape: ScreenShape,
    val usageMode: DeviceUsageMode,
    val safeInsets: InsetsPercent,
    val recommendedAppList: AppListDeviceDefaults,
    val input: InputProfile,
    val modelAliases: List<String> = emptyList()
)
```

必须支持的 `screenShape/usageMode`：

- 方屏手表
- 圆屏手表
- 背屏设备

小米 17 Pro/17 Pro Max：

- 保留旧版左侧安全区 20% 的思路，但不再直接写死在 ViewModel。
- 作为 `DeviceProfile` 的默认推荐值。
- 用户在首启可以选择“背屏设备”，也可以手动覆盖左、右、上、下安全区。

### 4.2 圆屏适配规则

圆屏首版重点做 Launcher 主体验，不要求 Miuix 设置页特殊适配。

应用列表圆屏策略：

- 中间组件变大并尽量铺满视觉中心。
- 顶部和底部鱼眼增强，越靠近圆屏边缘越明显收缩。
- 边缘选项和图标收缩，减少切圆边缘时的遮挡。
- 边缘图标间距可压缩，压缩程度、图标大小、中心图标放大比例进入 `AppListLayoutConfig`。
- 支持表冠滚动，表冠输入进入 `InputProfile`，不要写死在单个 Composable。

安全区策略：

- 从旧版单一 `leftSafeInsetPercent` 升级为 `{left, top, right, bottom}`。
- 每个值都可在引导中预览调整。
- 调整页必须先预览，不立即生效；用户点击下一步或应用后才写入设置。

### 4.3 首次启动引导

引导页顺序：

1. 欢迎和权限说明。
2. 设备类型选择：方屏手表、圆屏手表、背屏设备。
3. UI 整体缩放预览：调节全局 UI 缩放，显示应用列表和控制面板预览。
4. 安全区校准：上下左右安全区，圆屏显示圆形裁切参考，背屏显示左侧避让参考。
5. 表冠/滚轮测试：检测 rotary/mouse wheel/touch scroll，保存输入倍率和触觉开关。
6. 默认桌面、通知监听、无障碍能力引导。
7. 应用配置并进入 Launcher。

实现要求：

- 引导中的所有调整写入 `PendingOnboardingConfig`。
- 用户确认前不落主配置。
- 引导可以重新进入，用于重新校准设备档案。

## 5. 表盘系统

### 5.1 格式决策

主线只支持 WatchMaker `.watch`。旧版 Lunch 表盘、叮叮猫表盘和外部 APK/DexClassLoader 表盘不进入 flue-next 主线。

`.watch` 已确认事实：

- 本质是 ZIP 改后缀。
- 核心文件包含 `watch.xml`、`preview.jpg` 和图片/字体/视频资源。
- 画布以 512x512 为标准，中心原点，Y-up。
- 图层按 XML 顺序自底向上绘制。
- WatchMaker 2.0 包含 ThemeSets、分组、gif/image_tap 等扩展。

### 5.2 导入与安全

导入流程：

1. 读取 `.watch` 文件。
2. 验证 zip 大小、文件数量、单文件大小、总解压大小、路径穿越。
3. 解析 `watch.xml` 到 `WatchFaceIr`。
4. 资源统一复制到 app 私有目录，建立资源索引。
5. 生成预览图、能力声明、兼容性报告。
6. 保存为受控项目，不直接使用外部 zip 内容路径。

首版必须有资源预算：

- zip 最大大小。
- 解压后最大大小。
- 最大图片数量。
- 最大单张位图像素。
- 最大字体/视频资源数量。
- 最大动态层数量。

### 5.3 渲染层

新增统一接口：

```kotlin
interface WatchFaceRenderer {
    fun load(project: WatchFaceProject): LoadResult
    fun attach(host: WatchFaceHost)
    fun setVisible(visible: Boolean)
    fun renderFrame(frameTimeNanos: Long)
    fun release()
}
```

首版后端建议：

- `CanvasWatchFaceRenderer`：优先实现，覆盖静态图片、文字、基础时间、电量、日期。
- `TextureWatchFaceRenderer`：用于视频和需要被模糊/截图的高动态内容。
- `GlWatchFaceRenderer`：后续用于高动态表盘和高性能特效，不作为 MVP 唯一依赖。

不要在表盘核心里直接耦合 Compose。Compose 或 View 只负责承载 host。

### 5.4 表盘作为背景源

表盘必须输出：

- 当前帧 snapshot 或低分辨率 snapshot。
- 当前 palette/主色。
- 可见性和动态程度。
- 是否支持实时 blur、是否仅支持 dim/scrim。

这些能力供 `BackdropLayer` 使用。

## 6. BackdropLayer 与模糊特效

### 6.1 目标

下拉通知中心、负一屏、控制中心、小组件卡片出现时，底面应该是表盘背景的压暗、模糊、混色版本。页面跟手时背景也跟手，而不是单纯切换页面。

新增 `BackdropLayer`：

```kotlin
data class BackdropState(
    val progress: Float,
    val blurRadiusDp: Float,
    val dimAlpha: Float,
    val tintColor: ColorInt,
    val tintAlpha: Float,
    val cornerMask: CornerMask,
    val source: BackdropSource
)
```

### 6.2 Android 版本策略

- Android 12/API 31+：优先使用系统 `RenderEffect`。
- Android 11/API 30 及以下：默认灰色半透明/黑色压暗/渐变替代。
- 如果 OpenGL blur 实验通过，可为 API 27-30 提供性能等级较高的 GL blur。
- 所有高级 blur 都必须可关闭。

### 6.3 OpenGL blur 方向

参考 CloX 的方向：GLES/EGL、FBO、水平/垂直 shader blur、保存和恢复 GL 状态。flue-next 不复制代码，只重新实现自己的 GL blur backend。

首版策略：

- MVP 先完成 `RenderEffect + Scrim fallback`。
- GL blur 作为 P1 实验，必须有真实设备帧率测试。
- API 27-30 不强承诺实时模糊，先保证稳定和省电。

### 6.4 圆角与圆屏裁切

新增 `CornerMask`：

- 圆屏默认圆形蒙版。
- 用户可用拉杆调整圆角/圆形裁切强度。
- 控制表盘向下下沉时周围黑边的蒙版裁切。
- 对 Compose 内容、View 内容、TextureView 内容分别提供实现。

SurfaceView/GLSurfaceView 裁切不稳定的场景必须标记能力限制，优先使用 TextureView 或同层 GL 合成。

## 7. 应用列表重构

### 7.1 领域模型

替换旧版 `AppInfo`，使用 sealed hierarchy：

```kotlin
sealed interface AppEntry {
    val id: EntryId
    val label: String
    val icon: IconRef
}

data class InstalledAppEntry(...)
data class ShortcutEntry(...)
data class FolderEntry(...)
data class SystemActionEntry(...)
```

图标只使用 `IconRef`，bitmap 缓存在 `IconPipeline` 内部，避免 View/Compose/GL 渲染栈互相污染。

拆分数据源：

- `InstalledAppSource`
- `ShortcutStore`
- `FolderStore`
- `AppOrderStore`
- `HiddenAppsStore`
- `RecentAppStore`
- `IconPipeline`
- `LaunchController`

### 7.2 渲染选择

应用列表首选原生 View 或自定义 View 渲染，理由：

- 应用列表是高频滚动、拖拽、缩放、鱼眼、图标模糊的性能热点。
- 旧版 Compose `LazyGrid + graphicsLayer + per-item state` 已经需要在快速滚动时关闭部分效果。
- View/自绘可以更直接控制对象分配、命中测试、帧同步和缓存。

Compose 可以继续用于负一屏、控制中心、设置页，但要遵守边界：

- Compose 面板不能每帧重组大量图标。
- 面板中的背景模糊走 `BackdropLayer`，不在每个卡片重复 blur。
- Compose 与 View 通过稳定状态对象通信，不让 ViewModel 暴露上百个 flow 给一个页面。

表盘建议：

- WatchFace host 用 View/Texture/Renderer 抽象。
- `.watch` 静态/基础动态表盘可用 Canvas/Texture。
- 高动态和视频表盘逐步迁到 Texture/GL。

### 7.3 LayoutEngine

所有应用列表布局统一走策略接口：

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

独立 transform：

- `FisheyeTransform`
- `EdgeSpacingCompressionTransform`
- `FastFlowTransform`
- `EntryAnimationTransform`
- `SafeAreaTransform`

这样 Flue classic、Flue next、最近 7 个应用界面可以共享排序、拖拽和命中测试。

### 7.4 鱼眼重做

提供两个鱼眼选项：

- `FlueClassic`：保留旧版观感，作为兼容预设。
- `FlueNext`：更接近 Apple Watch，只有到屏幕边缘时才逐渐收缩变小，中间区域保持稳定。

必须支持：

- 鱼眼强度。
- 鱼眼范围。
- 边缘图标间距压缩。
- 中心图标大小。
- 顶部/底部鱼眼强度单独调节。
- 用户基于预设微调后另存为自定义配置。

### 7.5 排序、拖拽和文件夹

保留旧版能力：

- 自定义排序。
- 隐藏应用。
- 图标包。
- 双色图标。
- 快捷方式。
- 文件夹创建、重命名、解散、移入/移出。

新增：

- `RecentAppStore` 记录最近启动应用，用于 watchOS 27 风格最近 7 个应用界面。
- 排序策略接口：手动排序、最近使用、安装时间、名称、系统动作置底。
- 拖拽控制器独立于 UI，输出 `DragPresentation` 和 `ReorderCommand`。

## 8. 应用列表进入/关闭/启动动画

### 8.1 进入应用列表

新增 `AppListEntryMotionPreset`：

- `FlueClassic`
- `FlueNext`
- `WatchLikeRecent7`

明确要实现的 FlueNext 进入动画：

- 表盘压暗。
- 图标从模糊到清晰。
- 图标向上逐渐进入屏幕。
- 应用列表不从底部进入时，使用抽屉抽拉式进入。
- 最近 7 个应用界面只显示最近 7 款应用。

### 8.2 启动外部应用

新增 `ExternalLaunchSession`：

```kotlin
data class ExternalLaunchSession(
    val id: LaunchSessionId,
    val sourceScene: LauncherScene,
    val target: LaunchTarget,
    val origin: NormalizedOffset,
    val startedAt: Instant,
    val systemTaskToken: String?
)
```

修复旧版 bug 的核心原则：

- 点击某个应用启动后，只有从这个启动 session 对应的应用返回，才播放对应的从图标回收动画。
- 从其他应用、系统路径、通知、设置返回 Launcher，不复用上一次点击图标。
- 区分“从其他应用返回”和“从启动的应用退出”。

启动动画选项：

- 渐隐渐出。
- 无动画。
- 跟随系统。
- Flue 经典。
- Flue next。
- Android 原生曲线。

后续预设：

- 完美模仿 watchOS 的预设，等待后续详细复刻。
- CloX/原生 Android 曲线风格预设。

### 8.3 全面屏手势

全面屏手势支持参考 CloX 的方向进入 P1/P2：

- 方向锁定。
- 跟手 progress。
- 释放后 spring。
- 从哪来回哪去。
- 切换应用时不残留上一个启动动画。

## 9. 负一屏、控制中心、小组件合并

### 9.1 产品边界

这里的小组件是 flue-next 内置卡片，不是 Android AppWidget。

要合并：

- 原负一屏。
- 音乐卡片。
- 快捷启动。
- 控制中心中可卡片化的部分。

新增 `WidgetCard` 模型：

```kotlin
sealed interface WidgetCard {
    val id: String
    val type: WidgetCardType
    val size: WidgetCardSize
}
```

首批内置卡片：

- 音乐控制卡片。
- 快捷启动卡片。
- 通知预览卡片。
- 电池/设备状态卡片。

### 9.2 顶部时间与电池显示

明确改动：

- 电池图标不再在底部显示。
- 顶部时间滑出屏幕后，电池变成顶部灰色小字。
- 应用列表设置也要添加头部和底部模糊。

可复用组件：

- `AdaptiveHeaderBlur`
- `AdaptiveFooterBlur`
- `ScrollLinkedKernelBlur`
- `TopStatusText`

兼容要求：

- 高级头尾 blur 可关闭。
- API 31+ 用 RenderEffect。
- API 30 及以下用半透明渐变替代。

## 10. 设置系统重构

### 10.1 设置 schema

设置必须从 UI 中抽离：

```kotlin
data class SettingDefinition<T>(
    val key: SettingKey<T>,
    val group: SettingGroup,
    val defaultValue: T,
    val range: ValueRange<T>?,
    val migration: MigrationRule<T>?
)
```

要求：

- 每个设置项有默认值、范围、说明、所属 feature。
- 支持 schema version。
- 支持旧配置迁移。
- 支持备份/恢复映射。
- 支持“预览值”和“已应用值”区分。

### 10.2 两套 UI

Compose 自写 Apple Watch 风格：

- 使用 `DESIGN.md` 作为视觉规范来源。
- 操作逻辑和层级重新排序。
- 滑块、按钮、开关、分段控件都从统一 setting definition 生成。
- 设置页需要实时预览图，尤其是应用列表、鱼眼、动画、安全区、UI 缩放。

Miuix 版本：

- 使用同一套 setting schema。
- 不要求圆屏特殊适配。
- 不复制业务逻辑。

kyant 液态玻璃：

- 后期引入。
- 必须分档开放。
- 低性能设备默认关闭或使用弱特效。
- 所有液态玻璃组件要能回退到普通材质。

## 11. 兼容与性能策略

### 11.1 版本兼容

建议仍以旧版 `minSdk 27` 作为初始参考，是否调整需要后续确认。

特效分层：

- `PerformanceTier.Basic`：无实时 blur，半透明/渐变替代。
- `PerformanceTier.Standard`：API 31+ RenderEffect，有限 snapshot blur。
- `PerformanceTier.Enhanced`：RenderEffect + 部分 GL blur + 高级动画。
- `PerformanceTier.Experimental`：液态玻璃、复杂水滴 blur、动态卷积核。

### 11.2 测试指标

上线前硬指标：

- 应用列表滚动目标 60fps，低性能设备允许 45fps 但不能卡死。
- 启动外部应用状态机单测覆盖所有返回路径。
- `.watch` 导入必须能拒绝恶意 zip。
- 圆屏/方屏/背屏三类截图回归。
- API 27、30、31、36 至少覆盖一轮兼容测试。
- 通知中心/负一屏跟手动画无明显层级错乱。

## 12. 分阶段工期

总预估：15 到 22 周，取决于 `.watch` 兼容深度和 GL blur 是否进入首版。

### Phase 0：项目启动与规范，1 周，P0

- 新建 `flue-next`。
- 建立模块/包结构。
- 建立 ktlint/detekt 或等价静态检查。
- 写 `DESIGN.md` 初版。
- 写 settings schema 雏形。
- 建立测试与 benchmark 骨架。

交付标准：

- 空 Launcher 可启动。
- CI 能跑单测和 assemble。
- 新项目不依赖旧版大文件。

### Phase 1：设备档案与首启引导，2 周，P0

- `DeviceProfile`。
- 方屏/圆屏/背屏选项。
- UI 缩放预览再应用。
- 上下左右安全区预览再应用。
- 小米 17 Pro/17 Pro Max 背屏默认档案。
- 表冠输入测试。

交付标准：

- 首启能完成设备选择和校准。
- 设置中可重新进入校准页。
- 圆屏和背屏不会出现明显裁切错误。

### Phase 2：应用列表核心，3 到 4 周，P0

- sealed `AppEntry`。
- app 扫描、隐藏、排序、快捷方式、文件夹存储。
- `IconPipeline`。
- `AppListLayoutEngine`。
- View/自绘应用列表首版。
- FlueClassic/FlueNext 鱼眼。
- 排序、拖拽、文件夹命令控制器。

交付标准：

- 可替代旧版应用列表主流程。
- 用户可以排序、隐藏、打开应用。
- 鱼眼和安全区配置可预览。

### Phase 3：Launcher 状态机与动画，2 到 3 周，P0

- `LauncherScene`。
- `GestureActionMap`。
- `ExternalLaunchSession`。
- 启动/退出动画选项。
- 应用列表进入动画。
- 最近 7 个应用界面。

交付标准：

- 不再出现“从任意 app 返回都复用上次启动动画”的旧 bug。
- 上滑/下滑/左滑/右滑可配置目标页面。
- 动画 preset 可切换。

### Phase 4：.watch 表盘 MVP，3 到 5 周，P0/P1

- `.watch` 导入、安全校验、资源预算。
- `watch.xml` 解析到 IR。
- 静态图片、文字、时间、日期、电量基础 tag。
- 表盘预览。
- 表盘作为 BackdropSource。

交付标准：

- 能导入并渲染基础 `.watch`。
- 恶意/超预算 `.watch` 会被拒绝。
- 不支持的 tag 有清晰兼容报告。

### Phase 5：Backdrop 与面板，2 到 3 周，P1

- `BackdropLayer`。
- RenderEffect blur。
- scrim fallback。
- 通知中心、负一屏/控制中心、小组件卡片统一 panel host。
- 头部/底部 blur 组件。

交付标准：

- 通知中心和负一屏出现时表盘会压暗/混色/模糊或降级。
- API 30 以下能稳定显示降级效果。
- 高级特效可关闭。

### Phase 6：设置 UI 双实现，2 到 3 周，P1

- Compose Apple Watch 风格设置。
- Miuix 设置。
- 设置层级重排。
- 实时预览图。
- 设置备份/恢复迁移。

交付标准：

- 两套 UI 使用同一 schema。
- UI 不直接持有业务规则。
- 常用设置可实时预览。

### Phase 7：性能、兼容、Beta，2 到 3 周，P0/P1

- Macrobenchmark。
- 真机截图回归。
- 圆屏/方屏/背屏体验验收。
- 崩溃保护。
- beta 发布流程。

交付标准：

- 内测 APK 可发。
- 关键路径无阻塞 bug。
- 旧版迁移清单完成第一轮。

## 13. 迁移清单

必须审查后迁移：

- 应用扫描。
- 隐藏应用。
- 图标包。
- 双色图标。
- 内置设置入口。
- 快捷方式/pinned shortcut。
- 文件夹。
- 应用排序。
- 应用列表 overscroll 回表盘。
- 充电小闪电。
- 通知红点。
- 电池胶囊。
- 崩溃保护。
- 备份/恢复思路。

禁止直接搬迁：

- `LauncherViewModel` 大文件结构。
- `SettingsActivity` 单文件设置结构。
- `HoneycombScreen` 内部拖拽/鱼眼/状态混合结构。
- Lunch/DingDingCat/DexClassLoader 表盘兼容层。
- 没有授权确认的外部仓库源码。

## 14. 上线优先级

P0：

- 新项目基础结构。
- 设备档案、首启引导、安全区、UI 缩放。
- 应用列表核心、排序、启动。
- Launcher 状态机。
- `.watch` 基础导入渲染。
- 基础设置页。
- 低版本降级。

P1：

- 高级 BackdropLayer。
- FlueNext 鱼眼和动画 preset。
- 最近 7 个应用界面。
- 通知中心/负一屏/控制中心合并。
- 双设置 UI。
- 备份迁移。

P2：

- OpenGL blur。
- kyant 液态玻璃分档。
- 全面屏手势。
- 更多 `.watch` tag/effects。
- 水滴/图标模糊实验。

P3：

- 内置表盘编辑器。
- 激活码/收费/服务器/后台。
- 私有表盘商店。
- 上传审核和社区治理。

## 15. 待确认问题

这些问题不阻塞写计划，但实现前要问用户：

1. `flue-next` 是否继续保持 `minSdk 27`。
2. `.watch` 首版兼容目标是基础显示，还是要覆盖脚本、tap action、gif、视频、3D。
3. 圆屏目标设备型号和分辨率。
4. 小米 17 Pro 背屏真实方向、分辨率、系统 inset 行为。
5. 首启引导是否允许跳过。
6. 安全区是否按全局保存，还是允许每个页面单独覆盖。
7. 最近 7 个应用界面是否只按启动时间排序，还是允许固定/隐藏。
8. 设置页 `DESIGN.md` 需要先单独设计，还是直接在 flue-next 中边做边沉淀。
