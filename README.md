# Flue

Flue 是一个面向 Android 手表的自定义桌面启动器，目标是提供更接近 watchOS 风格的主屏体验，同时保留 Android 生态下的应用启动、通知查看、表盘切换与快捷入口能力。

当前版本仍处于 `beta1.2 / demo` 阶段，但主流程已经可用，分支内也在持续补齐交互细节、通知体验和表盘能力。

## 项目定位

Flue 不是传统意义上的“表盘应用”，而是一个会替代系统桌面的手表 Launcher。它负责：

- 作为 `HOME` 启动器承接表盘首页
- 提供应用抽屉与应用启动动画
- 提供副一屏快捷入口与通知预览
- 提供独立通知中心与通知清除交互
- 提供内置图片 / 视频表盘与外部表盘接入
- 提供隐藏应用、图标包、性能与外观设置

## 当前特性

### 表盘首页

- 支持内置表盘
- 支持图片表盘与视频表盘
- 支持已安装表盘扫描与切换
- 支持时钟位置、字号、粗细、颜色模式等配置

### 应用抽屉

- 支持蜂窝布局与列表布局
- 支持最近打开动画与回桌面过渡
- 支持图标包与图标质量切换

### 副一屏 / Smart Stack

- 支持快捷应用槽位
- 支持通知预览聚合
- 支持从副一屏上滑进入通知中心

### 通知中心

- 支持通知监听服务
- 支持按应用聚合
- 支持展开 / 折叠通知组
- 支持单条或整组滑动删除
- 支持顶部橡皮筋与下拉返回手势

### 设置页

- 表盘管理
- 外观与动画设置
- 隐藏应用
- 图标包切换
- 性能选项
- 工具与关于页

### 其他

- 崩溃保护页
- 从最近任务中隐藏
- GitHub Actions 云端构建 Release APK

## 技术栈

- Kotlin
- Jetpack Compose
- Android ViewModel
- DataStore Preferences
- Android Notification Listener
- GitHub Actions

## 运行要求

- `minSdk = 27`
- `targetSdk = 35`
- `compileSdk = 35`
- JDK 17
- Android Gradle Plugin `8.7.3`
- Kotlin `2.1.0`

核心配置可参考：

- [`app/build.gradle.kts`](app/build.gradle.kts)
- [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
- [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml)

## 项目结构

```text
app/
  src/main/kotlin/com/example/wlauncher/
    LauncherActivity.kt                主桌面入口
    SettingsActivity.kt                设置页
    service/
      WLauncherNotificationListener.kt 通知监听服务
    viewmodel/
      LauncherViewModel.kt             桌面状态与配置中心
    ui/
      drawer/                          应用抽屉
      home/                            表盘首页
      smartstack/                      副一屏
      notification/                    通知中心
      settings/                        设置项 UI
      common/                          通用交互与组件
    watchface/                         内置表盘配置与存储
  src/main/java/com/flue/launcher/watchface/
    LunchWatchFace*.kt                 表盘注册、宿主与扫描逻辑
.github/workflows/build.yml            CI 构建流程
```

## 核心页面

- 首页入口：[`LauncherActivity.kt`](app/src/main/kotlin/com/example/wlauncher/LauncherActivity.kt)
- 设置页：[`SettingsActivity.kt`](app/src/main/kotlin/com/example/wlauncher/SettingsActivity.kt)
- 副一屏：[`SmartStackLayer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/smartstack/SmartStackLayer.kt)
- 通知中心：[`NotificationLayer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/notification/NotificationLayer.kt)
- 状态管理：[`LauncherViewModel.kt`](app/src/main/kotlin/com/example/wlauncher/viewmodel/LauncherViewModel.kt)

## 构建方式

### 方式一：Android Studio 本地调试

1. 用 Android Studio 打开仓库根目录
2. 使用 JDK 17
3. 等待 Gradle Sync 完成
4. 选择设备后运行 `app`

如需签名 Release，可在根目录提供 `keystore.properties`，或通过环境变量 / Gradle 属性传入：

- `FLUE_SIGNING_STORE_FILE`
- `FLUE_SIGNING_STORE_PASSWORD`
- `FLUE_SIGNING_KEY_ALIAS`
- `FLUE_SIGNING_KEY_PASSWORD`

### 方式二：GitHub Actions 云端构建

Workflow file: [`.github/workflows/build.yml`](.github/workflows/build.yml)

触发方式：

- push 任意分支
- 提交 Pull Request
- 手动触发 `workflow_dispatch`

产物：

- `Flue-release` APK artifact

## 配置项概览

根据当前设置页，Flue 已支持以下主要配置：

- 表盘切换与刷新
- 图片 / 视频表盘资源管理
- 表盘文字位置、大小、粗细与颜色
- 首页布局模式切换
- 副一屏开关
- 模糊、边缘模糊、低清图标与动画覆盖
- 蜂窝布局列数、顶部 / 底部模糊与渐隐
- 隐藏应用
- ADW 图标包扫描与切换
- 通知显示开关
- 从最近任务中隐藏

## 当前状态

项目正处于快速迭代阶段，最近的改动重点集中在：

- 将旧版副一屏整合回主分支
- 重做通知中心与预览交互
- 完善通知监听与删除行为
- 优化图片表盘缓存与裁剪
- 统一底部电池胶囊组件
- 修复下拉 / 回弹 / 返回等细节手势

详细更新见：

- [`CHANGELOG.md`](CHANGELOG.md)

## 已知说明

- 当前仓库仍保留了一些实验性质较强的实现，命名与结构还会继续收敛
- 一些视觉与交互细节仍在持续打磨中
- 由于设备差异较大，通知、表盘和动画体验可能需要在真机上进一步校正

## 致谢

这个项目吸收了手表桌面、表盘宿主、通知中心和快捷面板等多个方向的实验成果，也保留了较多迭代痕迹。欢迎继续基于当前分支完善交互、性能与视觉一致性。
