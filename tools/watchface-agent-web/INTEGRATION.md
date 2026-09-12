# 接入 Flue 的边界

网页只承担可行性验证。Agent 不应通过模拟点击 Android 控件完成编辑。

| 原型模块 | Android 接入位置 |
|---|---|
| public/agent.js：模型→工具→观察循环 | Kotlin AgentRunner，CoroutineScope + 可取消网络请求 |
| public/tool-scheduler.js：并行读/生成，文档写屏障 | coroutineScope / Semaphore；批量 await 后按 toolCallId 返回 |
| providers.py：三协议规范化 | 服务端保留代理，或 Android ProviderAdapter；不能混用协议消息形状 |
| contracts/tools.json | Android assets 中的工具 JSON Schema；与真正可用的工具一致 |
| contracts/system-prompt.txt、knowledge.json | 系统提示词、按需格式检索；把网页能力说明换为当前 Flue 原生能力 |
| read/edit/write_document | WatchEditorSession 草稿；增加从候选 XML 重新解析并提交为单次撤销的入口 |
| inspect_face | 会话快照及嵌套图层映射；不把顶层 order 当渲染 definitionIndex |
| preview_face | WatchmakerWatchFaceView 的整盘/图层离屏渲染；增加固定数据快照与诊断 |
| image_process / generate_image | 素材服务，产物经 Session.importResource 接入 |

## 统一消息契约

请求包含 protocol/config、system、messages、tools。消息角色是 user/assistant/tool；
assistant 的 toolCalls 为 `{id,name,arguments}`，tool 的 toolCallId 必须匹配。
工具结果包含结构化 content 和可选 images（实际 PNG）。必须保留协议适配器返回的
native，尤其 Responses 的 reasoning 项和 Claude 的原始内容块。

每轮收集全部 tool calls；最多 N 项独立任务并发执行，然后一次性发送所有 tool
结果。对不同资料的搜索、不同素材生成、固定版本的不同预览可并行；修改同一文档
的调用作为顺序屏障。操作依赖返回的 asset path 或 revision 时，模型下一轮再调用。
调度器不能猜测模型隐含依赖；工具描述和系统提示词都明确这一点。

## 文档与事务

原始 XML/脚本为候选文档来源。用户每次请求形成一个撤销分组。所有写入检查
base_revision；版本冲突应返回明确错误，让模型读取当前版本。原生 Session 持有
内存 DOM，外部补丁后必须重新解析同步，避免旧 DOM 在 flush 时覆盖文件。

完整提交顺序为：候选修改 → 格式/资源检查 → 原生解析 → 预览 → 单事务入历史。
既有 saveCurrentRevision/exportWatch 的安装身份和元数据语义保持一致。
不要把 API 密钥、搜索结果、聊天记录写入 .watch 包。

## 观察与验证

每张预览绑定 revision、target、固定时间和数据、亮屏/息屏模式，并返回资源就绪
与脚本诊断。模型支持视觉时实际附加图片内容；关闭视觉时只能报告结构/诊断检查。
网页 Canvas 和有限表达式解析器在接入 Android 时由原生实现替换，不作为最终渲染标准。
完整脚本在原生沙箱中受指令/资源预算限制；本网页原型没有执行全局 Lua。
