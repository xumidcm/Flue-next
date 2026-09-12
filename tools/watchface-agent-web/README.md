# Flue Studio · 表盘 Agent 可行性原型

本地网页可以配置 Chat Completions、Responses、Claude Messages，使用工具编辑
WatchMaker XML、生成图片、Tavily 搜索、调用 Python 图像处理并生成实际 PNG 预览。
这是一套用于验证 Agent 闭环的独立原型；未修改 Flue Android 工程。

## 启动

在 Windows PowerShell 中：

```powershell
cd D:\Flue\tools\watchface-agent-web
.\start.ps1
```

打开 http://127.0.0.1:8765 。默认只监听本机。换端口用 `-Port 8767`。
启动器优先使用本机已有的 Codex Python 运行环境；也可以指定 `-PythonPath`。
已验证的环境包含 Python 3.12、Pillow、NumPy，不需要 npm 安装或前端构建。
独立运行环境可在自己的虚拟环境中安装 `requirements.txt`；程序不会全局安装依赖。

## 试用

1. 右上角设置 → 对话模型，选择协议并填写接口地址、模型 ID 和 API Key。
   地址可以是基础 `/v1` 地址，也可以是完整的接口端点。
2. 可选配置图片 API（OpenAI Images 兼容 generations/edits）与 Tavily。
3. 描述修改需求。工具调用、实际结果和预览图会显示在对话中。
4. 图层面板可直接改属性；XML 页可编辑 watch.xml 或 scripts/script.txt。
5. 素材 → 制作素材，可以不配置任何 API，直接试 Python 渐变或透明刻度。
6. 切换整盘/单元素、原位置/独立放大、亮屏/息屏和模拟电量；可下载 PNG。
7. 导入/导出 `.watch` 包。导出保留 XML、脚本及素材，并生成 preview.jpg。

密钥默认只存于页面内存，后台不保存。勾选“记住密钥”才会明文保存在本机
浏览器 localStorage；未勾选时自定义请求头也不会持久化。表盘内容存于 IndexedDB。
接口测试会向所填服务发送真实的小请求。停止仅中断本地等待，已经提交给服务商
的生成任务可能继续执行和计费；程序不会自动重试付费请求。

## 工具并行

同一轮可返回多个 tool calls。独立的查询、搜索、素材生成和预览并行执行，
默认并发 4 项。所有观察结果按照原 tool call 顺序一次交回模型。XML/脚本写入是
顺序屏障，避免并发写坏同一份文档。依赖上一结果的调用需要下一轮发出。
每个工具失败独立返回错误，其他结果不会丢失。取消后未开始的工具不执行。

Chat/Responses 可携带 parallel_tool_calls，Claude 直接支持多个 tool_use。
不同网关支持情况不同，行为设置可调整是否发送该参数。

## 预览范围

原型是 Canvas 渲染器，不是 Flue Android/CloX 原生渲染器。支持基础文字、图片、
形状、圆角矩形、环、进度、刻度、曲线文字、文字环、分组、标准 Drop/Outline、
基础变换和 V2 Ambient；支持已明确列出的时间/电量/健康/天气模拟标签和有限表达式。
有些效果是近似显示。全局 Lua、视频/GIF 动画、3D、复杂 shader/遮罩不执行，
会保留原文件并给出预览限制。最终设备效果需接 Flue 原生渲染后验证。

Python 暴露固定 Pillow/NumPy 操作：gradient、ticks、resize、crop、rotate、adjust、
remove_background（颜色阈值，不是语义抠图）。通用工作区、Bash、任意 Python
代码执行按本次需求暂缓。

## 迁移到 Flue

运行 `node export-contracts.mjs` 生成可移植的 `contracts/tools.json`、系统提示词
和格式知识。具体映射见 [INTEGRATION.md](INTEGRATION.md)。

## 验证

```powershell
python -W error::ResourceWarning -m unittest test_backend.py
node --test test_renderer.mjs test_scheduler.mjs
node export-contracts.mjs
```

浏览器开发环境可执行 `import('/browser-tests.js').then(m => m.runBrowserTests())`。
它检查真实 Canvas 像素和模拟模型的文档编辑/预览闭环，临时模拟 fetch 后会恢复。
测试不调用付费 API。没有提供服务商密钥，因此真实付费模型/图像服务仍需用户配置验证。

## 参考

- [WatchMaker 格式和标签](https://gitlab.com/ailife8881/watchmaker-definition)：事实型规则重新整理，未拷贝完整第三方文档。
- [CloX](https://gitlab.com/ailife8881/clox)：行为参考，未拷贝其引擎源码。
- [WFTools](https://gitlab.com/ailife8881/wftools)：格式编辑参考。
- [OpenAI function calling](https://developers.openai.com/api/docs/guides/function-calling)
- [Claude tool use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)
- [Tavily Search](https://docs.tavily.com/documentation/api-reference/endpoint/search)
- 界面图标来自 [Lucide](https://lucide.dev)，经 Iconify 获取；ISC 许可见 `public/lucide-license.txt`。
