# Third-Party Notices

Flue 以 [GPL-3.0](LICENSE) 发布。下列第三方组件随本项目一同分发，各自遵循其原有许可证。
如果你分发修改后的 Flue，必须同时保留本文件与 `LICENSE`。

## 随 APK 分发的二进制

### PRoot

- 文件：`app/src/main/jniLibs/arm64-v8a/libproot_exec.so`、`libproot_loader.so`
- 版权：Copyright (C) 2015 STMicroelectronics
- 许可证：GPL-2.0-or-later
- 上游：<https://github.com/termux/proot>（基于 <https://proot.me>）
- 说明：这是 Termux 打过补丁的 PRoot，由 Flue 通过 `ProcessBuilder` 作为独立进程启动，
  用于在设备上运行 Agent 的 Linux rootfs。它不与 Flue 的 Kotlin 代码静态链接。
- 对应源码：见 [`third_party/proot`](third_party/proot/README.md)。
  该目录记录精确的上游提交、Termux 补丁与 NDK 构建参数，是 GPLv2 §3 意义上的对应源码入口。

### FFmpegKit

- 依赖：`dev.ffmpegkit-maintained:ffmpeg-kit-video`
- 许可证：LGPL-3.0（以所依赖 AAR 内的 LICENSE 声明为准；若替换为含 x264/x265 的
  `video-gpl` / `full-gpl` 变体，则整体按 GPL-3.0 分发）
- 上游：<https://github.com/arthenica/ffmpeg-kit> 的社区维护分支
- 说明：以未修改的动态库（`.so`）形式使用，用户可以自行替换为兼容构建。

## 编译期与运行期依赖

| 组件 | 坐标 | 许可证 |
|---|---|---|
| AndroidX / Jetpack Compose | `androidx.*` | Apache-2.0 |
| MIUIX | `top.yukonga.miuix.kmp` | Apache-2.0 |
| Cloudy | `com.github.skydoves:cloudy` | Apache-2.0 |
| BlurView | `com.github.Dimezis:BlurView` | Apache-2.0 |
| LuaJ | `org.luaj:luaj-jse` | MIT |
| XZ for Java | `org.tukaani:xz` | Public Domain |
| smart-exception-java | `com.arthenica:smart-exception-java` | 见上游仓库 |
| JUnit | `junit:junit` | EPL-1.0 |
| Robolectric | `org.robolectric:robolectric` | MIT |

## 运行期下载、不随项目分发的组件

- **Ubuntu Base 24.04 rootfs**：Agent 工作区在用户主动点击安装时，从
  `cdimage.ubuntu.com` 下载官方 arm64 镜像到应用私有目录。项目本身不包含、
  也不再分发该镜像；镜像内组件的许可证见镜像自带的 `/usr/share/doc`。
- **系统表盘与第三方表盘包**：由用户在设备上导入，本项目不附带任何表盘包。

## 兼容性与商标

Flue 是独立的桌面启动器实现，**不是** WatchMaker、JB 表盘或其他表盘平台的官方产品。
项目中的兼容运行时（`watchface/standard`、`watchface/jbwatch`、`watchface/dingdingcat`）
是按其公开的文件格式做互操作实现，相关名称与商标归各自所有者。

## 参考资料

下列项目影响了 Flue 的表盘运行时与外部数据接口设计，用于行为对照而非直接引入代码：

- <https://gitlab.com/ailife8881/wftools>
- <https://gitlab.com/ailife8881/watchmaker-definition>
- <https://gitlab.com/ailife8881/clox>
