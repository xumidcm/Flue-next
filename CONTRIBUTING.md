# 参与开发

感谢你愿意花时间改进 Flue。这个项目是手表上的桌面启动器（HOME 应用），改动会直接
影响用户的日常使用，所以我们对“能构建、能测试、真机上没坏”这三件事要求比较严。

## 环境

- JDK 21
- Android SDK 36（`compileSdk` / `targetSdk` 都是 36，`minSdk` 27）
- Android Studio 或命令行 Gradle 均可

`local.properties` 里的 `sdk.dir` 需要指向你自己的 SDK，这个文件不入库。

## 构建与测试

```powershell
# JVM / Robolectric 测试（提交前必须跑）
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain

# Debug APK
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain

# Release APK（未配置签名时产出 unsigned，属正常现象）
.\gradlew.bat :app:assembleRelease --no-daemon --console=plain

# 安装到已连接的手表
.\gradlew.bat installDebug
```

迭代时先只跑受影响的测试类，例如：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.flue.launcher.watchface.standard.*" --no-daemon
```

`tools/watchface-agent-web` 是表盘 Agent 的浏览器侧辅助工具，单独测试：

```powershell
cd tools/watchface-agent-web
python -W error::ResourceWarning -m unittest test_backend.py
npm test
```

## 真机验证

单元测试和 Release 构建**不能**替代真机检查。涉及以下改动时请在 PR 里说明验证过的设备、
系统版本和结果：

- 通知监听、通知中心交互
- 表盘渲染、表盘切换、外部表盘导入
- 手势、转场、动画帧率
- 圆屏 / 方屏布局差异
- 无障碍、设备管理员、权限申请流程

## 代码风格

- Kotlin 官方风格，四空格缩进
- 类名 `PascalCase`，函数与属性 `camelCase`，常量 `UPPER_SNAKE_CASE`
- 用户可见文案放 `app/src/main/res/values*/strings.xml`，不要硬编码；新增字符串请同时补
  `values-en`（`values-ja` 可选）
- Compose 组件保持小而专注，状态提升到 ViewModel
- 注释写“为什么”，不要复述“做了什么”

## 提交信息

使用简体中文短句，说明**完成的行为**，例如：

```text
修复表盘列表更新分页越界
补齐副一屏电量与步数组合显示
```

一个提交只做一件事；不要把格式化、重命名和功能混在一起。

## 分支与 PR

- 分支：`yzp/<topic>` 或 `feat/<topic>`，一个分支只处理一个主题
- 从最新 `main` 切出，开发期间避免长期偏离
- PR 描述请包含：用户可见的变化、验证命令、真机检查结果；UI 改动附截图或录屏
- 提交前跑 `git diff --check`，确认没有尾随空格和冲突标记

## 签名与密钥

**不要在提交里包含 keystore、`keystore.properties`、密码或任何生成物。**
`.gitignore` 已经覆盖这些路径。本地签名通过以下任一方式提供：

- 根目录的 `keystore.properties`（本地文件，不入库）
- Gradle 属性
- 环境变量 `FLUE_SIGNING_STORE_FILE` / `FLUE_SIGNING_STORE_PASSWORD` /
  `FLUE_SIGNING_KEY_ALIAS` / `FLUE_SIGNING_KEY_PASSWORD`

CI 通过仓库 Secrets 注入，贡献者不需要也无法访问发布签名。

## 许可证

提交即表示你同意以 [GPL-3.0](LICENSE) 授权你的贡献。请只提交你有权提交的代码；
移植或参考第三方实现时，请在 PR 里注明来源与许可证。
