# .watch 点击响应与震动设置验证

## 行为

- 单击在 `onSingleTapUp` 执行，不再等待双击确认窗口。
- 开启双击锁屏时，第一下松手执行表盘动作，第二下识别为双击后锁屏，不重复执行按钮动作。关闭双击锁屏时快速连点逐次执行。
- 长按、滑动和取消不触发单击；隐藏、换源、释放及双击开关变化取消未结束手势，普通重组保留识别状态。
- 有效动作分发前统一反馈一次震动，空白与不支持的动作不震动。脚本错误继续提示；脚本成功仍立即请求重绘并使原有缓存失效。
- “更多表盘选项 → 点击震动”适用于所有 .watch 表盘，默认开启，持久化键为 `watchface_tap_haptics_enabled`。桌面、画布调节、编辑器播放预览共用设置，不重建 Lua 状态。

## 自动化验证

使用 Windows 原生 Gradle、JDK 21。真实 MotionEvent 与原生 Canvas 的回归夹具先断言按钮命中区域正确；恢复旧双击确认分发时，松手后的脚本变量仍为 0，预期 1，测试失败。即时分发实现通过该测试。

- `WatchmakerTapInteractionTest`：17 项通过，覆盖即时响应、快速连点、双击、普通重组、长按、滑动取消、可见性、换源、释放、震动次数与执行顺序、脚本失败、立即重绘、视频宿主分发。
- `WatchFaceTapHapticsPreferenceTest`：1 项通过，使用真实 DataStore 与 LauncherViewModel 验证缺省、保存、重建 ViewModel 后恢复、重置与表冠设置独立。
- 全量 `:app:testDebugUnitTest`：250 个测试类，1930 项，1929 通过、1 跳过、0 失败、0 错误。
- 简体中文、英文、日文资源均存在且无重复键，设置搜索已接入。

## 构建与签名

执行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon --console=plain` 成功。用户追加“不用打 Debug”时，Debug 已生成且进入 Release 编译，此后未再构建 Debug。

- Release：`app/build/outputs/apk/release/app-release.apk`，`com.flue.launcher`，版本 `2.3.3`（28）。
- SDK 35.0.0 `apksigner verify --verbose --print-certs` 通过，APK Signature Scheme v2 有效。
- SHA-256：`faa861be539de3c0b256af284cc2d06f34f26d96a97c3ce449cc3836f3c524a1`。
- `git diff --check` 通过。

## 实机边界与工作区

`adb devices -l` 未发现连接设备。本次未验证物理马达响应、实际帧呈现时间或与 CloX 的端到端延迟差异。待设备连接后，应使用同一份市场互动表盘对比单击、连续点击、震动开关、双击锁屏、长按和滑动。

构建与测试保留了开始前的应用列表等未提交改动；本次回滚提交只包含点击响应与震动开关的增量。原包、脚本内容与预览资源未修改。
