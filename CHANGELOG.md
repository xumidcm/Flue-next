# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循
[语义化版本](https://semver.org/lang/zh-CN/)。2.3.x 及更早的记录见
[docs/CHANGELOG-archive.md](docs/CHANGELOG-archive.md)。

## [2.4.0] - 2026-11-06

仓库公开后的第一个版本。CYTJ OS 定制版同步公开在 `cytj-os` 分支。

### 变更

- 仓库以 GPL-3.0 开源，补充 `LICENSE`、`THIRD_PARTY_NOTICES.md`、`CONTRIBUTING.md`、`SECURITY.md`
- 发布签名不再进入版本库：`keystore.properties` 与密钥文件改为本地文件 + CI Secrets 注入
- 表盘市场的默认地址改为构建期可覆盖：`-PflueWatchStoreBaseUrl=https://your-host/api`
- 关于页的交流群入口改为复制群号（原来的邀请链接带一次性 `authKey`，会失效）
- 接受外部 PR，补充贡献流程与改动前置讨论范围

### 移除

- 清理未使用的权限声明：`SYSTEM_ALERT_WINDOW`、`ACCESS_NOTIFICATION_POLICY`、`REQUEST_DELETE_PACKAGES`
- 从仓库移除内部工作目录与重复源码备份

### 修复

- 无障碍权限说明文案此前声称“Flue 并不联网”，与实际行为不符，改为如实描述用途与能力边界

### 工程

- CI 增加 `:app:testDebugUnitTest` 任务；Release 构建改为 tag 触发并支持签名注入
- 新增 issue / PR 模板