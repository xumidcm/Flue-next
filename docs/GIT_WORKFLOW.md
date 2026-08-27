# Git 工作流

Flue 只保留一个长期分支：`main`。可发布、可回滚的版本使用 tag 保存，日常开发使用短期主题分支。

## 分支约定

- `main`：唯一长期分支，始终代表最新的已整合代码。
- `yzp/<topic>`：个人主题分支，例如 `yzp/watch-store-filter`。
- 一个分支只处理一个明确主题；不要用分支长期保存版本快照。
- 开始开发前同步 `main`，开发期间避免长期偏离 `main`。
- 合并或确认被 `main` 吸收后，删除本地和远端主题分支。

```powershell
git switch main
git pull --ff-only
git switch -c yzp/<topic>
```

## 合并约定

- 更新 `main` 时优先使用 fast-forward；禁止对共享的 `main` 强制推送。
- 合并前运行与改动相匹配的测试，并检查 `git diff --check`。
- 提交信息使用简体中文，说明实际完成的行为。
- 暂存时显式指定已审查路径，不使用会捎带本地文件的宽泛暂存命令。

## 版本约定

- 正式版本使用 `v<major>.<minor>.<patch>`，例如 `v2.1.0`。
- 测试版本使用 `v<major>.<minor>.<patch>-beta`，例如 `v1.5.0-beta`。
- 不再继续开发、但仍需保留的实验分支使用 `archive/<topic>` tag。
- 正式 tag 只能指向已完成 Release 构建和 APK 签名验证的提交。
- tag 推送到 GitHub 后，才能删除它替代的版本分支或归档分支。

```powershell
git tag -a v2.1.0 <commit> -m "发布 Flue 2.1.0"
git push origin v2.1.0
```

## 清理检查

删除分支前必须确认提交已经进入 `main`，或已有指向该提交的归档 tag：

```powershell
git branch --merged main
git branch --no-merged main
git show-ref --tags
```

存在未提交修改或 stash 时，清理分支不得改写、应用或丢弃这些内容。涉及删除分支、tag、stash 或 worktree 的操作必须先记录目标 SHA，并单独确认。
