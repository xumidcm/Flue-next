# JBWatch Lua 图层表达式兼容设计

日期：2026-08-13
状态：交互方案已由用户确认，待书面规格复核

## 目标

修复 JBWatch 表盘中保存在 `watch.pxml` 图层属性里的 Lua 表达式无法执行的问题，使文字、数值和显示条件能按 WatchMaker 语义渲染，同时保持现有 JBWatch 普通标签、静态文字和旧表达式的兼容行为。

实现复用 Flue 已有的 `WatchFaceLuaRuntime` 受限沙箱，不增加文件系统、Java 桥接、包加载、系统命令或网络能力，不为单个表盘名称、文件哈希或作者写特判。

## 样本与根因

用户样本 `Untitled_watch_Copy.zip` 包含 `watch.xml`、受保护的 `watch.pxml`、图片、字体和视频，没有独立 `.lua`、`script.txt` 或 `scripts/` 文件。`watch.pxml` 可由现有 `decodePxml()` 正常解码，共包含 30 个图层。

失败表达式位于文字图层的 `text` 属性，例如：

```lua
({ssc}/20) < 1000 and math.floor({ssc}/20)
or string.gsub(string.format('%.1f', ({ssc}/20)), "%.", ",")
```

```lua
({ssc} * 0.72) >= 1000
and string.gsub(string.format("%.1fkm", ({ssc} * 0.72)/1000), "%.", ",")
or math.floor({ssc} * 0.72) .. "m"
```

当前 JBWatch 文字路径 `resolveJbText()` 只执行标签插值和少量日期特判，不会执行完整 Lua 表达式，因此 `and/or`、`math.floor`、`string.format`、`string.gsub` 和 `..` 会作为源码残留或产生错误结果。数值和显示条件走有限的 `WatchmakerExpressionEvaluator`，与文字路径的能力也不一致。

## 运行时设计

### 生命周期

每个已加载的 JBWatch 表盘创建一个独立 `WatchFaceLuaRuntime`，初始脚本为空。运行时由 `JbWatchFaceHost` 按表盘稳定键和刷新版本持有，表盘切换、重新加载或 Composable 离开时关闭，避免状态和表达式缓存跨表盘泄漏。

运行时继续使用现有指令数、表达式长度、字符串结果和字符串分配上限。求值发生在当前表盘绘制与点击链路所在的 UI 线程，不引入后台并发访问。

### 求值顺序

增加一个 JBWatch 专用的轻量求值适配层，统一接收原始属性、标签解析器和安全回退值：

1. 纯静态文字和普通标签模板沿用现有 JBWatch 插值路径，保留日期别名、星期特判和不可用标签占位语义。
2. 带 Lua 运算符、条件关键字或 `math`、`string`、`table` 函数调用的属性，优先交给当前表盘的 Lua 沙箱求值。
3. Lua 成功返回数字、字符串或布尔值时，按属性上下文转换为文字、浮点数或可见性。
4. Lua 编译失败、运行失败、超出限额或结果无法转换时，回退现有 `WatchmakerExpressionEvaluator` 或 JBWatch 标签插值。
5. 回退后仍失败时使用调用方已有安全默认值，不让单个坏属性阻止整张表盘加载。

候选识别只用于保护普通标签和自然语言不被误当作 Lua 标识符；它不是 Lua 语法解析器。真正的语法与限额判断仍由 `WatchFaceLuaRuntime` 完成。

### 接入属性

- `text`：先保留 JBWatch 日期兼容预处理，再执行完整 Lua 表达式；普通文本仍按模板插值。
- `x`、`y`、`rotation`、`angle`：Lua 数值求值成功时使用结果，否则回退现有数值表达式求值器。
- `cond_value`：Lua 布尔或数值结果控制图层显示；失败时保持当前“无法判断则显示”的兼容行为。
- 动态 `path`：仅当内容确实是表达式候选时进入 Lua，普通资源路径继续直接解析，防止文件名被误判。

本次不改变 `tap_action="script:..."` 的权限或功能集合。该样本没有点击脚本，现有 `wm_sfx(...)` 音效处理与应用启动逻辑保持原样。

## 性能与刷新

同一表盘复用一个 Lua 运行时及其表达式编译缓存，不为每个图层或每帧重复创建 VM。普通静态文字和单标签模板绕过 Lua，避免增加常见表盘的绘制成本。

已有 `jbLayerRequiresFrameTicks()` 继续根据动态标签和动画决定刷新频率。本次样本使用步数等数据标签，不需要仅因接入 Lua VM 就强制 30 FPS。

## 测试与验收

测试遵循红—绿—重构，生产代码修改前先观察到回归测试因当前行为失败：

1. 样本的米数表达式在步数较小时返回整数米数。
2. 样本的公里表达式执行 `string.format`、`string.gsub` 和 `..`，并按原表达式输出逗号小数。
3. `and/or` 条件按不同 `{ssc}` 输入选择正确分支。
4. 普通静态文字、单标签、日期别名和日期条件文本保持原行为。
5. 非法 Lua、超限 Lua 和不可转换的数值结果使用既有回退值，不导致表盘加载失败。
6. 两个 JBWatch 表盘的运行时与缓存互相隔离，销毁后不再继续使用。
7. 数值属性和 `cond_value` 至少各有一条 Lua 求值与旧解析器回退测试。

完成后执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleRelease --no-daemon --console=plain
```

Release APK 使用 `local.properties` 指向的 Android SDK 中 `apksigner` 验证签名，并记录构建耗时、APK 大小和 SHA-256。最终只暂存已审查的源码、测试和规格文件，排除 `.gradle-fresh/` 与本地诊断文件，创建如实描述改动的简体中文提交。

## 非目标

- 不修改或重新打包用户提供的 ZIP。
- 不给 JBWatch 增加新的文件、网络、Java 或系统权限。
- 不把 `tap_action` 扩展成任意 Lua 脚本执行入口。
- 不修改标准 WatchMaker 表盘的既有求值顺序。
- 不清理 `.gradle-fresh/`，不使用 ADB，不更新依赖版本。
