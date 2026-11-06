# 表盘引擎重做：导入期预编译 + 静态图层烘焙 + 可调画布

对照参考实现 `ailife8881/clox`（已浅克隆到 `C:\Users\22403\Documents\ref-watchface\clox`）。

> **本文状态**：已按真实代码勘定过一遍，草稿里三处「照抄 clox 即可」的假设与 Flue 现状不符，
> 已在对应阶段里改写并标注 ⚠️。等老师确认后再动手写代码。

## 一、为什么现在慢：架构差异，不是常数因子

clox 的表盘引擎在 `app/src/main/assets/lua/`，是 **推模型**；Flue 是 **拉模型**。

| | clox | Flue 现状 |
|---|---|---|
| 属性表达式 | 导入时编译成 Lua 函数（`wf_expr.lua`），运行时只调用 | 每帧重新做 tag 代入 + 词法 + 递归下降 |
| 更新驱动 | tag 变化 → 推给绑定它的图层（`wf_engine.lua`） | 每帧把每个图层每个属性重算一遍 |
| 静态图层 | 连续静态段烘成一张 RenderTexture，每帧只画动态层 | 全部 377 层每帧都走完整路径 |
| 常量属性 | 导入时判定 `const`，只在 build 时应用一次 | 无此概念 |

`wf_expr.lua:11` 的注释点明了设计意图：

> THIS FILE IS THE ONLY PLACE ATTRIBUTE STRINGS ARE PATTERN-MATCHED. The functions
> it produces do zero gsub/find/match at runtime. Import is allowed to be slow;
> the running face is not.

**「导入后剩 100 多」是 `wf_render.lua:3196` 的 `flattenStatics()`**：把连续的、每帧不变的图层烘成一张位图，用一个 sprite 代替。所以老师看到的层数下降是**烘焙**的结果，不只是表达式缓存。上一轮我只做了表达式结果缓存，那只削掉了重复解析，没有削掉「遍历 377 层」本身。

## 二、现状代码勘定

写步骤之前先把要动的东西钉死，避免计划对不上代码。

### 表达式引擎（`WatchmakerRuntimeContracts.kt`）

| 位置 | 事实 | 对计划的影响 |
|---|---|---|
| `:1704` `evaluate()` | 先 `substituteTags(source, resolveTag)` 把 tag **代入文本**，再 `Parser(substituted, scope).parse()` | ⚠️ tag 在词法**之前**就消失了。要复用 AST，tag 必须变成 AST 节点，词法器得认 `{...}` |
| `:2047` `Parser` | 递归下降**边解析边求值**：`parseAdditive` 直接算 `leftNumber + rightNumber`，`parse()` 返回 `ExpressionValue` | ⚠️ 根本没有 AST 层。草稿说的「拆成 parse/eval 两半」不成立，是**新建 AST + 改写求值**  |
| `:1539` `evaluationCache` | 键 =（代入后文本, scope 引用），2048 条 LRU | 阶段 1 后这层缓存的作用变小但不冲突，保留 |
| `:1513` `MAX_EXPRESSION_LENGTH` | 32K；实测 WFF 导出的天气图标链单条 7665 字符、9 条 | AST 节点数量级要估算，见阶段 1 的内存一节 |
| `:1697` `supports()` | 用 `Resolved("1")` 代入后试解析 | 阶段 1 后改成「试编译」，语义等价 |

### 渲染计划与绘制循环（`WatchmakerWatchFaceView.kt`）

- `:867` `WatchLayerRenderItem(layerIndex, ancestorChain)`；`:927` **group 图层已经不进 renderPlan**（`if (!layers[layerIndex].isGroup)`）
- `ancestorChain != null` 即「不是舞台直接子节点」——clox 的第一条不可烘条件在 Flue 这里是**现成的一个判空**
- `:1312` 绘制循环就是 `for (planIndex in fromIndex until toIndex) drawRenderItem(...)`，烘焙段替换点在这里
- `:1284` `tapTargets.clear()`，可点区域是**在绘制过程中登记**的 → 被烘掉的图层不会登记热区
- `drawRenderItem(canvas, item, prepared, gyroscopeX, gyroscopeY)` 每帧传陀螺仪 → ⚠️ 用重力/视差的图层是 Flue 特有的快依赖，clox 的清单里没有
- `:191` `setRenderSegment(range, drawBackground)`：视频宿主已经按 video 图层把 renderPlan 切段 → ⚠️ 烘焙段**不能跨越视频分段边界**

### 画布变换（三处必须同步）

- `WatchmakerRuntimeContracts.kt:109` `WatchCanvasTransform(scale, left, top)` —— 单一 scale
- `WatchmakerRuntimeContracts.kt:128` `watchCanvasTransform(...)` —— `forceFill` 二选一，`left`/`top` 恒为 `(view - design*scale)/2`
- `WatchmakerWatchFaceView.kt:1231` `composeCanvasBaseMatrix` —— `preScale(transform.scale, transform.scale)`
- `WatchEditorCanvasGeometry.kt:571` 注释明写「和渲染器 `watchCanvasTransform` 用同一套契约」
- 存量单测已经钉住行为：`WatchmakerRuntimeContractsTest.kt:78/87/101/114`、`WatchLayerMatrixContractTest.kt:128`
- ⚠️ `forceFill` 目前是**全局单开关**（`LauncherViewModel.kt:185` `KEY_WATCHMAKER_FORCE_FILL`），**每表盘级配置在项目里不存在**，三层继承要新建存储层

## 三、四个阶段

按依赖顺序：阶段 1 → 2 → 3 有严格依赖（2 要 1 的 `deps`，3 要 2 的桶做重烘触发），
**阶段 4 与前三个完全解耦**，可以先做也可以最后做。每阶段独立可编译、可回滚。
按老师的规矩：**全部写完再统一编译一次**。

### 阶段 1：导入期表达式编译 + tag 依赖记录

#### 1.1 为什么不是「把 Parser 拆成两半」

草稿里我写「拆成 parse / eval 两半即可」，读过代码后这句是错的：

1. **tag 在词法之前就被代入文本了**（`:1711`）。`{bl}` 不是 token，它在 `Parser`
   看见源文本之前已经变成 `85`。要复用 AST，tag 必须是 AST 节点，词法器得自己
   认 `{...}`。
2. **`Parser` 根本没有 AST**（`:2114` `parseAdditive` 直接算
   `leftNumber + rightNumber`），`parse()` 返回的是 `ExpressionValue`。所以这是
   **新建一层 AST + 改写求值**，不是拆分。

第 1 点还带一个语义坑：tag 的**运行时类型由代入文本决定**（`:1838`
`toExpressionLiteral`）—— `{bl}` → `85` → 数值，`{ddw}` → `Monday` → 字符串，
`nil`/`true`/`false` → 关键字。而**引号内**的 tag 走的是另一条路（`:1784`
`escapeForQuote`），它是字符串**内容**的一部分。所以 AST 需要两种 tag 节点。

#### 1.2 目标结构

```
// 编译产物：一条属性表达式的完整描述
sealed interface WatchAttr
  data class Const(val value: ExpressionValue) : WatchAttr        // 不含 tag，导入时算死
  data class Dynamic(
      val node: ExprNode,
      val deps: Set<String>,        // 引用到的 tag 名（已小写归一）
      val source: String,           // 保留原文：求值失败要回退 interpolate
  ) : WatchAttr
  data object Unsupported : WatchAttr                             // 编译不过，运行时直接用 fallback

// AST
sealed interface ExprNode
  Literal(value)                    // 数字 / 字符串 / true / false / nil
  TagValue(name)                    // 裸 tag：代入后按 toExpressionLiteral 定类型
  TagInText(name, quote)            // 引号内 tag：按 escapeForQuote 拼进字符串
  Concat(left, right) / Binary(op, l, r) / Unary(op, x) / Compare(op, l, r)
  And(l, r) / Or(l, r) / Not(x)     // 保持短路语义
  Call(name, args)
  ScopeRef(name)                    // scope 变量，编译期已确认存在
```

`Const` 的判定条件：`deps.isEmpty() && 不含 ScopeRef` 且试求值成功。对照
`wf_expr.lua:289` `WFExpr.compile` 返回 `{const=true, value}` / `{const=false, fn, deps}`。

⚠️ **`ScopeRef` 不能折成 `Const`**：`scope` 按引用进缓存键（`:1562` 的注释说明
编辑器里同时活着好几个 `WatchExpressionScope`），同一条表达式在不同 View 下
`scope.resolve(name)` 结果不同。所以含 `ScopeRef` 的一律是 `Dynamic`。

#### 1.3 编译入口

编译在 `prepareSource()`（`WatchmakerWatchFaceView.kt:2603`）里做，它已经在 IO
dispatcher 上、已经受 `sourceGeneration` 保护，是天然的「导入期」。产物挂到
`PreparedSource`（`:3658`）上，随 generation 一起换掉，不需要额外失效逻辑。

`dynamicNumber` / `evaluateText` / `resolveColor` / `isDisplayed`
（`:3462`–`:3533`）改成先查编译产物、查不到才走老路径 —— 老路径**一行都不删**，
它是 fallback，也是回滚开关。

#### 1.4 保留求值缓存

`evaluationCache`（`:1539`）保留。阶段 1 消掉的是「词法 + 递归下降」，缓存消掉的是
「同一 tag 值下的重复求值」，两者不重叠：`{wci}` 几小时才变，`Dynamic` 求值虽然
比现在快，但仍不如直接命中缓存。

#### 1.5 内存估算

最长的实测表达式 7665 字符 ≈ 2500 token，AST 节点约 1200 个，单节点 ~32 字节
≈ 40KB。9 条天气链 ≈ 360KB。一张 377 层表盘全量约 1–2MB —— 与位图缓存（24MB）
同量级以下，可接受。但要设**编译产物总节点数上限**（建议 512K 节点），超了就把
该条标 `Unsupported` 走老路径，防止恶意档案把内存吃穿。

### 阶段 2：tag 分桶 + 推模型

#### 2.1 桶划分（按 Flue 真实 tag 词汇，`:1100` `builtInTags`）

| 桶 | tag | 备注 |
|---|---|---|
| `FRAME`（每帧） | `dss` `dssz` `dsps` `drss` `drms` | ⚠️ 毫秒级。`:1103` 起确认它们直接读 `Calendar.MILLISECOND` |
| `SECOND` | `ds` `dsz` `drs` `dsps`(整秒部分) | |
| `MINUTE` | `dm` `dmz` `drm` `drm0` `drmh24` | |
| `HOUR` | `dh*` `da` `dampm` `drh` `drh0` `drh24` | |
| `DAY` | `dd*` `dn*` `dy*` `ddw*` `dz` `dzz` `dtp` | |
| `PUSH` | 电量 `bl`/`blp`/`bc`、天气 `w*`、步数 `ssc`、心率 `shr*`、通知 `nbc` | 外部事件驱动 |
| `SENSOR` | `sgx` `sgy` `sgz`（陀螺仪） | ⚠️ 见 2.3 |
| `STATIC` | 其余 | |

#### 2.2 绑定与触发

- 导入时把每个 `(planIndex, 属性)` 登记进它 `deps` 里每个 tag 所属的桶
  （对照 `wf_engine.lua:231` `bind`）
- tag 重算后**值真的变了**才触发（`wf_engine.lua:92` `if v == self.value then return false end`）
- 一个绑定依赖多个同桶 tag 时用 generation 去重，一次 fire 只跑一次
- ⚠️ `wf_engine.lua:122` 的坑必须照抄：`fire` 要**先把所有 tag 重算完，再跑变化
  tag 的桶**。交错执行会让共享绑定读到另一个 tag 的旧值（他们踩过「闹钟小时更新了
  分钟没更新」）

312.watch 实测：276 条表达式/帧，其中仅 40 条依赖秒。推模型下每秒只有这 40 条参与。

#### 2.3 Flue 特有的两个不在 clox 清单里的快依赖

1. **陀螺仪视差**：`drawRenderItem` 每帧收 `gyroscopeX/Y`（`:1322`），而
   `smoothedGyroscopeX += (raw - smoothed) * 0.18f` 是**低通滤波** —— 它在陀螺仪
   停止后仍会继续收敛好几十帧。所以「传感器值没变」不等于「渲染结果没变」，
   带 `gyro` 的图层必须永远算 `FRAME` 桶。
2. **`anim_in` / `anim_out` 入场出场动画**（`:attrs["anim_in"]`）与 GIF 帧序列
   （`shouldAnimateImageLayer`）：它们的时间来源不是 tag，而是渲染器自己的
   elapsed。桶模型看不见它们 → **必须在可烘/推送判定里单独列为快依赖**。

#### 2.4 必须保留全量重绘入口

对照 `wf_engine.lua:337` `repaint`。压暗切换、主题变化、首帧、编辑器改属性、
表盘切换都走它。**漏推比慢更糟**（图层永久卡住不更新），所以这个兜底入口不是
可选项。

### 阶段 3：静态图层烘焙（层数归约）

对照 `wf_render.lua:2956`–`3193`。Flue 是 Canvas 直绘，用离屏 Bitmap 代替 RenderTexture。

#### 3.1 可烘判定（Flue 版，以下任一为真则不可烘）

| 条件 | Flue 里怎么判 |
|---|---|
| 不是舞台直接子节点 | `item.ancestorChain != null`（现成） |
| 是 group | 已经天然满足：group 不进 renderPlan（`:927`） |
| 是 video | `layer.type == "video"`，且**烘焙段不能跨视频分段边界**（见 3.4） |
| 是点击热区 | 有 `WatchTapAction` —— 热区在绘制时登记（`:1284`），烘掉就丢 |
| 依赖快 tag | `deps` 命中 `FRAME`/`SECOND`/`SENSOR` 桶 |
| 带入场出场动画 | `anim_in` / `anim_out` 非空 |
| 是多帧图 / GIF | `framePaths.size > 1`（就是上一轮修的那条路由） |
| 用陀螺仪视差 | 见 2.3 第 1 点 |
| 带 shader / 自定义 blend | 逐属性核对后补 |

判定**从严**：不确定就当不可烘。漏判的代价是图层被烘死冻住（用户可见的 bug），
误判的代价只是少省一点性能。

#### 3.2 成段

只合并**连续**的可烘图层（`wf_render.lua:3212`）。中间夹一个动态层就断开，
z 序天然保持。少于 2 层不值得烘（`FLATTEN_MIN`）。

#### 3.3 重烘触发

取该段所有图层的**慢依赖并集**，绑到阶段 2 的对应桶上。分钟跳、天气变、
变量写、压暗翻转各重烘一次。多屏表盘自动正确 —— 翻页变量是慢依赖。

#### 3.4 三个必须照抄 / 必须新增的细节

1. **alpha 累积**（`wf_render.lua:3093`）：一段里不透明层压在半透明层上时，
   烘出来的整张必须保持正确 alpha。Canvas 侧做法：离屏 Bitmap 先
   `drawColor(TRANSPARENT, CLEAR)` 再逐层 `SRC_OVER` 画，合成回主 Canvas 时
   **不要再乘一次 alpha**（否则半透明层被压暗两次）。
2. **画布尺寸下限**（`wf_render.lua:3056`）：`DESIGN_HALF_DIAG = ceil(512*√2/2) = 363`
   —— 图层旋转后可能超出自身声明范围，按半对角线开画布才不会被裁。上限 2048
   防病态，超了跳过不烘。
3. ⚠️ **不能跨视频分段边界**（Flue 新增，clox 没有这个问题）：
   `WatchmakerWatchFaceStageView` 已按 video 图层把 renderPlan 切成
   `setRenderSegment(range, drawBackground)` 的多段（`:191`），每段一个
   `WatchmakerWatchFaceView` 实例。烘焙段若跨越边界，两个实例会各烘一份重叠内容
   并互相盖掉。**烘焙的成段必须先按视频边界切，再在每个子区间内成段。**

#### 3.5 Canvas 侧比 clox 简单的地方

clox 靠 GL 的 end-of-frame flush，所以必须把 capture 推迟到 after-update 钩子
（`wf_render.lua:3155` 那段安全契约是防 GLThread SIGSEGV 的）。我们用
Bitmap + 同步 Canvas，**没有这个约束** —— 但要自己管 Bitmap 生命周期与 OOM，
且必须复用上一轮刚建立的三级缓存的字节记账习惯（别再开第四个不记账的缓存）。

### 阶段 4：可调画布

老师已定：照搬四预设 + 双轴缩放 + 双轴偏移 + 三层继承。与阶段 1–3 解耦。

#### 4.1 契约改动

```
data class WatchCanvasTransform(scaleX, scaleY, left, top)   // 由单 scale 拆成双轴

watchCanvasTransform(
    viewWidth, viewHeight,
    designWidth, designHeight,
    preset,          // VERTICAL / HORIZONTAL / STRETCH / CUSTOM
    zoomX, zoomY,    // 双轴缩放倍率，默认 1
    offsetX, offsetY // 双轴偏移，默认 0
)
```

- 预设决定基准：`VERTICAL` 按高度贴合、`HORIZONTAL` 按宽度贴合、
  `STRETCH` 双轴各自铺满（可非等比）、`CUSTOM` 完全由滑杆决定
- `zoom` 乘在基准 scale 上；`offset` 加在居中后的 `left`/`top` 上
- 现有 `forceFill: Boolean` 迁移：`true` → `max(scaleX, scaleY)` 等比铺满，
  `false` → `min(...)` 贴合。**保留 `forceFill` 重载**，调用点零改动

#### 4.2 三处必须同步改（漏一处几何就漂移）

1. `WatchmakerRuntimeContracts.kt:109/128` —— 契约本体
2. `WatchmakerWatchFaceView.kt:1231` `composeCanvasBaseMatrix` ——
   `preScale(transform.scale, transform.scale)` 改成 `preScale(scaleX, scaleY)`。
   ⚠️ 这个矩阵**Canvas 路径与视频 TextureView 路径共用**（`:1269` 的注释明写
   「两条路径共用这一个矩阵，几何才不会随时间漂移」），所以改这里两边自动一致
3. `WatchEditorCanvasGeometry.kt:571` —— 注释明写与渲染器同契约，编辑器的
   命中测试/拖拽必须同步，否则**编辑器里点不中自己画出来的图层**

#### 4.3 逐位兼容是硬约束

存量单测已经钉住行为（`WatchmakerRuntimeContractsTest.kt:78/87/101/114`、
`WatchLayerMatrixContractTest.kt:128`）。新参数全部带默认值，**不传新参数时输出
必须与现在逐位相同**，否则所有存量表盘位置全变。新增一条单测显式钉住这点。

#### 4.4 三层继承的存储（⚠️ 需要新建）

草稿说「沿用项目现有偏好方式」—— 勘定后发现**每表盘级配置在项目里根本不存在**：
`forceFill` 是全局单开关 `KEY_WATCHMAKER_FORCE_FILL`（`LauncherViewModel.kt:185`）。

所以三层继承要新建一层，方案：

```
每表盘配置   stringPreferencesKey("watchface_canvas_overrides")  // JSON: id -> 配置
             键用 selectedWatchFaceId 的同一套 id（KEY_SELECTED_WATCHFACE_ID）
App 全局默认  新增 preset / zoomX / zoomY / offsetX / offsetY 五个 key
跟随开关     每表盘配置里的 followGlobal: Boolean，缺省 true
```

读取优先级：`每表盘(followGlobal=false) → App 全局 → 出厂默认`。
出厂默认必须等价于今天的 `forceFill` 行为，保证零迁移。

UI：四个滑杆（宽度倍率 / 高度倍率 / 水平偏移 / 垂直偏移）+ 预设选择 +
「跟随全局默认」开关 + 重置。方屏上四个预设会有部分等价，这是预期的
（对齐 clox 语义，圆屏/异形屏才见差别）。

⚠️ 新增 UI 文案必须走 `stringResource` 三语（沿用长按菜单那条规矩），不写硬编码中文。

## 四、两个未修的 bug

### A. 视频导入后画布全黑，点一次播放/暂停才显示

现象指向明确：黑屏 + 点播放暂停后正常 = 首帧渲染时没有可画的内容，而播放器状态
翻转触发了重绘、且此时已有帧可用。

要查证：`WatchFaceVideoLayerView.kt`（168 行）里视频图层第一次渲染取什么当画面、
有没有 fallback 到首帧位图、播放/暂停回调额外触发了什么。上一轮已给编辑器图层面板
加了 `resolveVideoFirstFrameThumbnail`（`MediaMetadataRetriever.getScaledFrameAtTime`），
**画布侧可以复用同一张首帧位图作为播放器就绪前的占位**。

`WatchmakerWatchFaceStageView` 里已有 `drawStaticMediaFallback`（给超出
`MAX_CONCURRENT_VIDEO_LAYERS = 2` 的视频图层画静态首帧）—— 优先看能不能直接复用它，
而不是新写一条路径。

老师提到「图层面板也没有缩略图」与上一轮改动的预期不符，需要装机确认是改动没生效
还是路径没覆盖到。**先确认再改，不写投机代码。**

### B. 温度/电量文字扭曲粘连（`anim_scale_x != 100` 的图层）

已排除两项：`anim_scale_x` 走 Matrix 而非 `Paint.textScaleX`（做法正确）；
`°` 的 `\xc2\xb0` 是合法 UTF-8。

实测规律：wf_528 的 17/29/30 层（scale 85/80/80）糊，100/95/92 正常 ——
**阈值在 90 附近**，像是缩小到某个比例后字形被重采样压毁。新增查证路径：
clox 的 text builder 如何处理字距与度量，若它逐字符定位而我们整串 `drawText`
（或反之），就是差异点。**仍未定位，不写投机代码。**

## 五、验证

- 全部阶段写完后**统一跑一次**：`compileDebugKotlin` → `testDebugUnitTest` → `assembleDebug`
- 单测覆盖：
  - 阶段 1：常量折叠正确性、`deps` 集合正确性、引号内 tag 的类型语义、
    含 `ScopeRef` 不得折成 `Const`
  - 阶段 2：值未变不触发、`fire` 先全量重算再跑桶、同桶多依赖去重
  - 阶段 3：快 tag 必须不可烘、成段只合并连续层、成段不跨视频边界
  - 阶段 4：默认参数与现状**逐位一致**、四预设各自的 scale 取法、继承优先级
- 装机验收：312.watch 帧率与背景闪烁、wf_528 编辑流畅度与天气图标、
  画布四滑杆实际效果、视频首帧、三层继承的覆盖优先级

## 六、风险

| 风险 | 处置 |
|---|---|
| 烘焙 Bitmap 吃内存 | 段数上限 + 尺寸上限 2048 + 超限跳过；字节记账并入现有三级缓存的记账习惯 |
| 可烘判定漏判 → 动态层被烘死冻住 | 判定从严；陀螺仪 / `anim_in` / `anim_out` / 多帧图一律动态；单测覆盖 |
| 推模型漏推 → 图层不更新（比慢更糟） | 保留全量重绘入口（对齐 `wf_engine.lua:337`），压暗/主题/首帧/编辑器改属性走它 |
| 画布改动移动存量表盘 | 新参数全默认值 + 保留 `forceFill` 重载 + 单测钉住逐位一致 |
| 画布改动漏改编辑器几何 | 三处同步清单（4.2），编辑器命中测试必须一起改 |
| 阶段 1 AST 内存 | 总节点数上限，超限标 `Unsupported` 走老路径 |
| 老路径被删导致无法回滚 | 老求值路径**一行不删**，全部改动都是「先查新产物、miss 才走老路」 |

## 七、落地顺序建议

1. **阶段 4 先做**（与 1–3 解耦，且老师已定方案、改动面最小、有存量单测护着）
2. 阶段 1（AST + 编译产物，最大一块，但纯内部重构、有 fallback）
3. 阶段 2（依赖阶段 1 的 `deps`）
4. 阶段 3（依赖阶段 2 的桶）
5. bug A / B 视装机结论插入 —— 它们与四个阶段互不依赖

**等老师确认本文后再动手写代码**，不计划和实现一把梭。

