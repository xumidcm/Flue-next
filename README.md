# Flue

**Android 手表上的桌面启动器（HOME 应用）**，Kotlin + Jetpack Compose 实现。

目标是把手表主屏做成能真正日常使用的桌面：表盘、应用抽屉、副一屏、通知中心、控制中心都在
同一个进程里，手势和转场连续，而不是在几个 App 之间来回跳。

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![CI](https://github.com/xumidcm/Flue/actions/workflows/ci.yml/badge.svg)](https://github.com/xumidcm/Flue/actions/workflows/ci.yml)
![minSdk 27](https://img.shields.io/badge/minSdk-27-informational)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)

当前版本 `2.3.5 (30)`，仍是 beta，功能可用但细节还在打磨。

## 功能

### 表盘首页
- 作为 `HOME` 启动器承接首页
- 内置表盘：数字、拟真时钟、MD3E 时钟（形状、字号、粗细、颜色、字体可配）
- 图片表盘与视频表盘，支持从首帧自动取色，也可用 RGB 滑块手动配色
- 扫描并切换设备上已安装的第三方表盘

### 表盘运行时（互操作实现）
- `.watch` 格式（WatchMaker 兼容）：Lua 脚本在 Flue 自己的白名单沙箱里执行
- JB 表盘、ClockSkin 表盘的解析与渲染
- XML / zip 解析走安全配置（禁用 DTD 与外部实体）
- 静态图层烘焙 + tag 分桶，降低复杂表盘的掉帧

### 应用抽屉
- 蜂窝布局与列表布局
- 图标包（ADW 格式）扫描与切换、图标质量切换
- 鱼眼、快速流逝动画，跟随表盘主色调配色

### 副一屏 / Smart Stack
- 快捷应用槽位、通知预览聚合
- 上滑进入通知中心，底部电池与步数组合显示

### 通知中心与控制中心
- 通知监听、按应用聚合、分组展开折叠
- 单条 / 整组滑动删除，顶部橡皮筋与下拉返回

### 设置与其他
- 隐藏应用、性能选项、外观与动画、备份恢复
- 崩溃保护页、从最近任务隐藏
- 表盘编辑器与表盘市场客户端（可指向自建服务）
- 实验性：表盘 AI Agent（自带 API Key，本地工具调用）

## 支持情况

| 项目 | 要求 |
|---|---|
| 系统 | Android 8.1（API 27）及以上，Wear OS 与国产手表 ROM 均可 |
| 屏幕 | 圆屏 / 方屏，自适应布局 |
| ABI | `arm64-v8a`、`armeabi-v7a`（AI Agent 工作区只支持 `arm64-v8a`） |
| 构建 | JDK 21、Android SDK 36、AGP 8.13.2、Kotlin 2.3.21 |

## 安装

1. 从 [Releases](https://github.com/xumidcm/Flue/releases) 下载 APK；
2. `adb install -r Flue-release.apk`，或在手表上用文件管理器点开安装；
3. 按系统提示把 Flue 设为默认桌面（设置 → 应用 → 默认应用 → 主屏幕）；
4. 需要通知中心就授予通知使用权，需要双击息屏就授予无障碍权限。

### 官方签名指纹

Flue 没有内置更新器，安装包从 Releases 手动更新，所以请核对签名后再安装：

```
证书 SHA-256: 62bd781c365503b33b12b59b7874e6b4f919d41231ac5715e0b6e851fb2d82a6
```

```powershell
apksigner verify --print-certs Flue-release.apk
```

指纹不一致的包不是官方构建。

## 权限说明

Flue 是桌面启动器，权限比普通应用多。每一项的用途和申请时机如下，代码里都能对上：

| 权限 | 用途 | 何时生效 |
|---|---|---|
| `QUERY_ALL_PACKAGES` | 枚举可启动应用，HOME 应用的核心职责 | 安装即生效 |
| `INTERNET`、`ACCESS_NETWORK_STATE` | 表盘市场、天气、AI Agent | 安装即生效 |
| `BLUETOOTH_CONNECT`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE` | 控制中心的状态显示与开关 | 用户点击时申请 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 通知中心读取通知（仅本地处理） | 用户在系统设置手动授权 |
| `BIND_ACCESSIBILITY_SERVICE` | 双击息屏与电源菜单（`canRetrieveWindowContent=false`，读不到屏幕内容） | 用户在系统设置手动授权 |
| `BIND_DEVICE_ADMIN` | 锁屏（`force-lock`） | 用户手动激活 |
| `MANAGE_EXTERNAL_STORAGE`、`READ_MEDIA_*` | 导入导出表盘、备份恢复 | 用户主动操作时申请 |
| `WRITE_SETTINGS` | 调整系统亮度等设置 | 用户主动操作时申请 |
| `BODY_SENSORS`、`health.READ_HEART_RATE`、`ACTIVITY_RECOGNITION` | 表盘上的心率与步数 | 表盘用到时申请 |
| `ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION` | 表盘天气的城市定位，可改用城市搜索 | 用户点“使用系统定位”时申请 |
| `READ_CALENDAR` | 表盘日历标签 `{cex}` | 用户在设置里打开时申请 |

所有数据都留在设备本地。详情见 [SECURITY.md](SECURITY.md)。

## 从源码构建

```powershell
# 测试（提交前必须通过）
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain

# Debug 安装到已连接的手表
.\gradlew.bat installDebug

# Release（未配置签名时产出 app-release-unsigned.apk，属正常）
.\gradlew.bat :app:assembleRelease --no-daemon --console=plain
```

签名通过根目录的 `keystore.properties`（本地文件，不入库）或环境变量提供：

```
FLUE_SIGNING_STORE_FILE
FLUE_SIGNING_STORE_PASSWORD
FLUE_SIGNING_KEY_ALIAS
FLUE_SIGNING_KEY_PASSWORD
```

也可以用 Gradle 属性 `-PFLUE_SIGNING_STORE_FILE=...` 传。四个值齐全才会启用 release 签名。

### 指向自己的表盘市场

默认市场地址可以被 Gradle 属性覆盖，不需要改代码：

```powershell
.\gradlew.bat :app:assembleRelease -PflueWatchStoreBaseUrl=https://your-host/api
```

## 项目结构

```text
app/src/main/kotlin/                  主代码（包名 com.flue.launcher）
  LauncherActivity.kt                 桌面入口（HOME）
  SettingsActivity.kt                 设置入口
  viewmodel/LauncherViewModel.kt      桌面状态与配置中心
  ui/home, ui/drawer, ui/smartstack,  各层界面
  ui/notification, ui/controlcenter,
  ui/settings, ui/anim, ui/theme
  service/                            通知监听、无障碍、设备管理
  watchface/agent/                    实验性 AI Agent（工具宿主、Lua/XML 检查、工作区）
  watchface/store/                    表盘市场客户端
  watchface/editor/                   表盘编辑器
app/src/main/java/com/flue/launcher/  watchface/ 下的表盘运行时
  watchface/standard/                 .watch 解析、渲染、Lua 沙箱
  watchface/jbwatch/                  JB 表盘
  watchface/clockskin/                ClockSkin 表盘
app/src/main/jniLibs/                 PRoot 二进制（见 third_party/proot）
app/src/test/                         JVM + Robolectric 测试（250+ 用例文件）
docs/                                 设计文档与验证记录
tools/watchface-agent-web/            表盘 Agent 的浏览器侧辅助工具
```

## 开发

- 提交前跑 `:app:testDebugUnitTest` 和 `git diff --check`
- 通知、表盘、手势类改动需要真机验证，单测替代不了
- 提交信息用简体中文短句，说明完成的行为
- 开发约定见 [CONTRIBUTING.md](CONTRIBUTING.md)，分支与版本约定见 [docs/GIT_WORKFLOW.md](docs/GIT_WORKFLOW.md)

## 已知限制

- 天气数据走的是小米未公开接口，随时可能失效；定位请求失败时会回落到手动城市搜索
- 默认表盘市场指向作者维护的社区服务，可用上面的 Gradle 属性改掉
- 第三方表盘为格式互操作实现，不保证覆盖全部特性；不同厂商 ROM 的表现也有差异
- AI Agent 工作区需要 `arm64-v8a` 且首次使用要下载约 30MB 的 Ubuntu Base 镜像
- 圆屏与方屏的布局差异只能靠真机校正，欢迎提交适配报告

## 贡献与反馈

- Bug 与建议：用 issue 模板提交，请附设备型号与系统版本
- 安全漏洞：请走 [私密报告](https://github.com/xumidcm/Flue/security/advisories/new)，不要开公开 issue
- 代码贡献：见 [CONTRIBUTING.md](CONTRIBUTING.md)

## 许可证

以 [GPL-3.0](LICENSE) 发布。任何分发的修改版 APK 都必须同时提供完整对应源码。

随项目分发的第三方组件（PRoot、FFmpegKit 等）遵循各自许可证，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Flue 不是 WatchMaker、JB 或其他表盘平台的官方产品，相关名称与商标归各自所有者。

## 致谢

这个项目积累了不少手表桌面、表盘宿主、通知中心和快捷面板方向的实验成果。
感谢所有提交过反馈、报告过问题的用户 —— `docs/validation/` 里能看到他们的贡献痕迹。
