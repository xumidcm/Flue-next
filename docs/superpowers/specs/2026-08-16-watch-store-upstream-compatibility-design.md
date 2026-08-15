# 表盘市场上游兼容与动作支持设计

## 范围

本次修改只同步上游契约到本地 Flue，不向 GitLab 仓库反向推送。保留当前工作区已有的 5 个未提交源码文件和 `.gradle-fresh/`，不把它们混入本次设计提交或后续无关修改。

目标是修复：

1. 上传表盘请求命中旧接口而返回 404/`Watch store request failed`。
2. `Gravity_Sim` 等合法 WatchMaker 动作被 Flue 显示为 `unsupported action`。
3. 工具设置缺少自定义表盘市场地址。
4. 市场网络加载失败时仍展示本地已下载管理内容，导致用户误以为在线市场可用。

## 参考契约

- `watchmaker-community-store` 当前主分支：
  - `POST /api/my/watchfaces/parse`：只上传 `.watch` 并返回解析/扫描结果。
  - `POST /api/my/watchfaces`：再次上传文件并提交 `name`、`description`、`screen_type`、`category_id`，返回 201。
  - 上传服务端默认限制为 20 MB，错误响应可能是 `{errors:[...]}` 或 `{error:...}`。
- `watchmaker-definition/WM_TAP_ACTIONS.md` 与 CloX 动作表：
  - 系统动作：`open_settings`、`battery_status`。
  - 可配置命名动作：`w_camera`、`w_clock`、`w_music`、`w_calculator`、`browser` 等。
  - `w_link:<uri>` 深链；`script:<lua>` 继续走已有表盘 Lua 沙箱。
- `Gravity_Sim.zip` 仅作为合法表盘样本与回归材料，不对其作者、文件名或哈希做特判。

## 方案与组件

### 1. 市场地址偏好

`WatchStoreUiPreferences` 增加 API 基址读写：默认值为 `https://jbwatchface.ai-life.xyz/api`。自定义值允许 `http` 和 `https`，但必须有主机、合法端口、无用户信息/查询/片段；保存时规范化尾部 `/`。

工具设置页新增“自定义表盘市场 URL”入口：

- 显示当前基址；
- 弹出可编辑文本框；
- 保存前执行 URI 校验；
- HTTP 地址显示明文传输风险提示；
- 提供恢复默认；
- 保存后商店页重新创建 API/Repository 并刷新分类与列表。

自定义地址变更时清理旧预览缓存，避免不同市场复用同一远程 ID 的图片。现有已安装文件不删除；在线列表失败时不显示本地市场管理卡片或已上传管理入口。

### 2. HTTP API 适配

保留 UI 现有 `uploadWatchFace(...)` 调用签名，底层改为：

1. 发送带 Bearer token 的 multipart 文件到 `my/watchfaces/parse`；
2. 用同一临时归档发送带元数据的 multipart 到 `my/watchfaces`；
3. 两步都要求 2xx，错误保持原有状态码与服务端字段详情；
4. 上传进度回调仅报告第二步文件传输，不重复伪造进度；
5. 仍使用临时文件，成功/失败/取消都删除。

写请求不自动跟随重定向。自定义 HTTP 基址允许明文连接，但令牌只发往用户明确保存的地址；重定向仍必须经过主机与地址安全校验，避免把令牌转交给第三方。

### 3. 市场失败态

当分类或首屏列表请求失败时：

- 清空在线条目与分页状态；
- 不渲染离线“已下载表盘”卡片；
- 不显示“我的上传/已上传管理”入口；
- 在错误状态中显示错误详情、重试按钮、自定义 URL 按钮；
- 修改 URL 后重新加载，成功后恢复完整浏览/管理入口。

详情页与单个下载失败继续使用现有局部重试，不改变已安装表盘的磁盘数据。

### 4. 动作兼容

扩展 `WatchTapAction` 与 `WatchTapActionParser`：

- `open_settings` → `Settings.ACTION_SETTINGS`；
- `battery_status` → `Intent.ACTION_POWER_USAGE_SUMMARY`；
- CloX 配置动作进入 `Named` 类型，由现有 `tapActionHandler` 解析用户映射；未映射时静默返回，不弹 `unsupported action`；
- `w_link:` 仅允许经过 `Uri.parse` 的非空 URI，并通过 `ACTION_VIEW` 启动；保留现有自定义 intent 拒绝策略；
- `w_app/m_app`、脚本、天气、日历、心率保持现有行为。

## 测试设计

先写失败测试，再实现：

1. URL 偏好：默认值、HTTP/HTTPS 自定义值、非法 URL 拒绝、恢复默认。
2. URL policy：HTTP 基址可构造 API 端点；跨主机/危险重定向仍拒绝。
3. HTTP API：录制连接验证两步上传路径、Bearer header、multipart 字段、异常状态与错误详情。
4. 动作解析：样本中的 `battery_status`、`open_settings`、`w_camera`、`w_clock`、`browser`、`w_music`、`w_calculator` 不再是 `Unsupported`；未知动作仍保持拒绝。
5. 市场失败态：错误时不生成离线已下载管理行，错误状态提供重试和 URL 配置回调；成功时恢复入口。
6. 回归：现有 WatchMaker/CloX tag、Lua、商店安装/目录测试保持通过。

## 验收标准

- 三个上游主分支契约已在本地代码与测试中对齐。
- `Gravity_Sim` 中列出的动作解析为可执行/可配置动作，不再显示 `unsupported action`。
- 上传不再调用 `/api/watchfaces/upload`，上游两步接口均成功时完成上传。
- 商店加载失败只显示错误、重试、自定义 URL；成功加载后才显示管理入口。
- 相关测试、完整 JVM 单测、`git diff --check` 和 Release 构建/签名验证全部通过；不使用 ADB。
