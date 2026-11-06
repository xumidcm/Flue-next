# 安全策略

## 报告漏洞

请**不要**在公开 issue 里描述安全问题。使用 GitHub 的私密报告渠道：

**Security** 标签页 → **Report a vulnerability**
（<https://github.com/xumidcm/Flue-next/security/advisories/new>）

报告里请尽量包含：影响版本、系统版本与设备、复现步骤、预期与实际结果。
如果涉及用户数据，请只提供必要的最小样本。

我们会尽快确认收到，并在修复发布后于 Security Advisory 中公开说明。

## 支持范围

只对最新发布版本和 `main` 分支的最新提交提供安全修复。

## 安全模型与已知边界

Flue 是一个需要较高权限的桌面启动器。以下设计是有意为之，**不是漏洞**：

- 监听通知：通知中心功能所需，数据只在设备本地处理和存储，不上传
- 无障碍服务：只用于双击息屏与打开电源选项。配置里 `canRetrieveWindowContent="false"`，
  事件类型只有 `typeWindowStateChanged`，无法读取屏幕内容
- 设备管理员：用于防误触与锁屏，不含远程擦除
- 枚举已安装应用（`QUERY_ALL_PACKAGES`）：HOME 应用的核心职责
- 接收 `com.ailife.clox.WRITE_DATA` 广播：这是 clox 外部数据接口的上游约定，任何应用都可
  写入 10 个字符串槽位，表盘脚本只能把它们当字符串读取，不进入求值路径

## 特别关注的攻击面

如果你在这些区域发现问题，我们会优先处理：

- 表盘解析：`.watch` / zip / XML / Lua 脚本（Lua 沙箱逃逸、路径穿越、zip bomb）
- 表盘渲染的图片与字体解码
- 内置 WebView 与 JavaScript 桥（站点白名单绕过、登录态泄漏）
- 外部 Intent / 广播输入（`ShortcutInstallReceiver`、`WatchExternalDataReceiver`）
- AI Agent 的本地工具调用边界（文件系统越权、命令注入）
- API key 的本地存储（`AiSecretStore`）

## 数据处理

- 通知内容、表盘数据、设置项都存在应用私有目录或 DataStore 中
- AI 相关请求只在用户配置了服务商后发出，密钥保存在本地
- 崩溃日志不会自动上传；如果你开启日志分享，请自行检查其中的敏感信息
