# Changelog

本文档记录当前 PR `#19: Splice old side screen into main` 相对 `main` 分支的主要更新内容。

更新时间：2026-04-18

## 当前版本

- 版本名：`beta1.2`
- 当前 PR：`#19`
- 当前分支：`codex/splice-old-side-screen`

## 总览

这次 PR 不是单点修复，而是一次较大规模的功能拼接与交互整理，核心目标是把旧版副一屏能力重新整合进现有主线，同时把通知中心、表盘、设置页和视觉细节一起补齐。

相对 `main`，当前 PR 累计涉及：

- 46 个文件变更
- 约 2900+ 行新增
- 约 1100+ 行删除

## 主要更新

### 1. 副一屏重新整合回主线

- 重新接入完整副一屏场景
- 增加快捷应用槽位
- 增加通知预览聚合展示
- 补齐副一屏与首页之间的过渡和状态同步
- 修复副一屏方向、堆叠和退出行为

相关文件：

- [`SmartStackLayer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/smartstack/SmartStackLayer.kt)
- [`LauncherActivity.kt`](app/src/main/kotlin/com/example/wlauncher/LauncherActivity.kt)
- [`GestureHost.kt`](app/src/main/kotlin/com/example/wlauncher/ui/navigation/GestureHost.kt)
- [`LauncherViewModel.kt`](app/src/main/kotlin/com/example/wlauncher/viewmodel/LauncherViewModel.kt)

### 2. 通知中心重做与通知监听增强

- 重构通知列表与通知分组模型
- 加入通知监听服务数据清洗与同步
- 支持副一屏预览到通知中心的连续操作
- 支持单条通知与整组通知滑动删除
- 优化通知卡片堆叠、展开、删除与进入动画
- 兼容部分 Android 版本的通知 API 差异

相关文件：

- [`WLauncherNotificationListener.kt`](app/src/main/kotlin/com/example/wlauncher/service/WLauncherNotificationListener.kt)
- [`NotificationLayer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/notification/NotificationLayer.kt)
- [`NotificationModels.kt`](app/src/main/kotlin/com/example/wlauncher/ui/notification/NotificationModels.kt)
- [`SwipeRevealDeleteContainer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/notification/SwipeRevealDeleteContainer.kt)

### 3. 表盘能力增强

- 增加图片表盘缓存与降采样
- 优化内置图片表盘裁剪
- 修复表盘图片缓存导入问题
- 强化表盘设置与资源存储流程
- 保持内置表盘与扫描表盘切换体验一致

相关文件：

- [`InternalWatchFaceStorage.kt`](app/src/main/kotlin/com/example/wlauncher/watchface/InternalWatchFaceStorage.kt)
- [`WatchFaceLayer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/home/WatchFaceLayer.kt)
- [`LauncherViewModel.kt`](app/src/main/kotlin/com/example/wlauncher/viewmodel/LauncherViewModel.kt)

### 4. 设置页与配置项扩展

- 调整设置页文案
- 继续收敛设置项结构
- 增强图标包、隐藏应用、性能与表盘相关设置
- 优化关于页图标显示与相关资源
- 刷新启动器图标与设置入口图标

相关文件：

- [`SettingsActivity.kt`](app/src/main/kotlin/com/example/wlauncher/SettingsActivity.kt)
- [`WatchFaceSettingCard.kt`](app/src/main/kotlin/com/example/wlauncher/ui/settings/WatchFaceSettingCard.kt)
- [`app/src/main/res/mipmap-mdpi/ic_launcher.png`](app/src/main/res/mipmap-mdpi/ic_launcher.png)

### 5. 动画、回弹与返回手势优化

- 修复通知中心跟手动画
- 调整通知拖拽灵敏度与速度阈值
- 优化返回桌面、启动应用后的回切动画
- 统一副一屏与通知中心中的回弹表现
- 改善顶部橡皮筋体验

相关文件：

- [`LauncherActivity.kt`](app/src/main/kotlin/com/example/wlauncher/LauncherActivity.kt)
- [`NotificationLayer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/notification/NotificationLayer.kt)
- [`SmartStackLayer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/smartstack/SmartStackLayer.kt)
- [`ScaleBlurModifier.kt`](app/src/main/kotlin/com/example/wlauncher/ui/anim/ScaleBlurModifier.kt)

## 本次最新补充修复

以下内容是当前 PR 最近两次提交补进去的行为修正：

### 电池胶囊统一

- 把通知中心和副一屏底部电池胶囊抽成统一组件
- 修正电量百分比文字垂直居中偏下的问题
- 统一两处胶囊宽度、内层尺寸与圆角
- 充电闪电图标移到胶囊外侧显示，不再撑宽背景

相关文件：

- [`WatchBatteryPill.kt`](app/src/main/kotlin/com/example/wlauncher/ui/common/WatchBatteryPill.kt)
- [`NotificationLayer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/notification/NotificationLayer.kt)
- [`SmartStackLayer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/smartstack/SmartStackLayer.kt)

### 通知下拉返回逻辑修正

- 修复通知列表在“先上滑再下拉”时仍可能直接返回副一屏的问题
- 现在只有列表已经回到顶部且静止后，新的一次下拉手势才会触发返回
- 当列表只是刚回到顶部时，会优先触发顶部橡皮筋效果

相关文件：

- [`NotificationLayer.kt`](app/src/main/kotlin/com/example/wlauncher/ui/notification/NotificationLayer.kt)

## CI 状态

当前这轮修复提交对应的 GitHub Actions 已完成并成功产出 Release APK：

- [PR 构建运行 24598848670](https://github.com/xumidcm/Flue/actions/runs/24598848670)
- [Push 构建运行 24598847621](https://github.com/xumidcm/Flue/actions/runs/24598847621)

## 备注

- 本文档描述的是“当前 PR 相对 `main` 的累计变化”，不是历史 release 的逐版本 changelog
- 如果后续继续在这个 PR 上追加功能，建议直接在本文件追加新小节，或在 PR 合并后再拆成正式版本日志
