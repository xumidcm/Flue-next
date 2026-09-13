# CYTJ OS 定制版

定制版分支为 `codex/cytj-os-custom`，从 `main` 创建。主线有更新时，在该分支执行：

```powershell
git switch codex/cytj-os-custom
git merge main
cmd /c gradlew.bat :app:assembleRelease --no-daemon
```

应用 ID 为 `com.dw.launcher`。定制分支默认使用 `cytj_os_watch.jpg` 图片表盘、心流应用列表、深色主题，并将应用列表背景设为当前表盘。关于页显示“CYTJ OS 定制版”。

发布前执行 APK 签名验证和 `git diff --check`，再按版本号创建定制版 Release。
