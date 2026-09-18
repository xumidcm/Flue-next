package com.flue.launcher.watchface.agent.tools

/** Curated, high-signal format notes; exhaustive references stay out of the base prompt. */
internal object WatchAgentKnowledgeCatalog {
    val entries = listOf(
        WatchAgentKnowledgeEntry(
            title = "WatchMaker 包与路径",
            text = "watch.xml 位于 .watch ZIP 根目录；图片、字体和脚本分别放在 images/、fonts/、scripts/script.txt。图片 path 相对于 images/，不要写 images/ 前缀；禁止绝对路径和 .. 越界。",
            source = "watchmaker-format",
        ),
        WatchAgentKnowledgeEntry(
            title = "变量标签与表达式",
            text = "动态标签遵循 WatchMaker 语义。var_* 与 {var_*} 是脚本值；修复后的 Lua 路径可处理进入 Lua 后端的数值、比较、and/or。花括号只包一个标签名，条件写成 {var_frame} == 1 and 100 or 0，不要写成 {var_frame==1 and 100 or 0}，也不要把 {100} 当作算式容器；未被分类的混合 text 仍可能退回占位符。opacity 的条件显隐必须先 validate_face 和 preview_face 验证，不要把旧版“只认字面数字”的踩坑误写成永久能力或永久禁用。var_s_t1..var_s_t5、var_s_v1..var_s_v5 等脚本变量合法为 nil 时，文字结果为空字符串，不要回显变量源码。",
            source = "watchmaker-runtime",
        ),
        WatchAgentKnowledgeEntry(
            title = "文字的三种存在形式",
            text = "用户说的文字不一定是 text 属性：可能是文本图层、图片/条件图里烧录的像素（例如 1×2 的 image_cond 或 cond_grid），也可能来自 scripts/script.txt 的 var_*。inspect_face/read_document 找不到目标文字时，先 list_workspace_files 再 read_image 看真实图片；不要臆造一个新的 text 图层覆盖像素。",
            source = "watchmaker-editing",
        ),
        WatchAgentKnowledgeEntry(
            title = "条件显隐与兼容回退",
            text = "透明度表达式依赖属性是否被 Lua backend 分类；遇到旧包或静态渲染路径时 opacity 可能只接受字面数字。条件切换优先确认 var_* 和单一文字图层能在真实预览中工作；图片条件图不要用 opacity 代替，也不要把零宽空格当默认修复。历史包若仍触发开头 token 回显，可临时用 U+200B 零宽空格前缀，写入前后都要用预览确认排版。",
            source = "watchmaker-compatibility",
        ),
        WatchAgentKnowledgeEntry(
            title = "revision 是全文档共享计数器",
            text = "watch.xml 与 scripts/script.txt 共用同一个 revision。任何写入成功后都要把返回的新 revision 作为下一次写入的 expected_revision；不能把两个文件当成独立版本，也不能复用旧 revision。发生 stale_revision 时先重新 read_document，再继续写入。",
            source = "watchmaker-editing",
        ),
        WatchAgentKnowledgeEntry(
            title = "old_text 精确替换",
            text = "edit_document 的 old_text 必须在当前文档中唯一匹配，并且与当前内容逐字一致（包括空格、引号和换行）。值出现多次时带上足够的上下文行；不要凭记忆填写 text_size 或脚本变量旧值。",
            source = "watchmaker-editing",
        ),
        WatchAgentKnowledgeEntry(
            title = "preview_face 的验证边界",
            text = "preview_face 是静态渲染，不能验证点击或交互。需要验证 tap/action 时，可分别设置两个初始状态各做一次静态预览，确认两种文字/图层都能渲染；不要把静态截图描述成已经完成了点击测试。",
            source = "watchmaker-validation",
        ),
        WatchAgentKnowledgeEntry(
            title = "全局 Lua 脚本",
            text = "完整 Lua 语法（function、local、return、end）交给 Lua backend；全局脚本只写 scripts/script.txt。脚本修改后必须 validate_face，编译、运行或指令限额失败要保留错误诊断。",
            source = "watchmaker-script",
        ),
        WatchAgentKnowledgeEntry(
            title = "图层点击动作",
            text = "只有显式非空 tap_action 或 action 才登记点击区域；opacity=0 的动作热区仍可点击。点击区域跟随图层最终变换和动态尺寸，没有动作的上层图层不应遮挡下层动作。",
            source = "watchmaker-actions",
        ),
        WatchAgentKnowledgeEntry(
            title = "V1 与 Studio V2",
            text = "常规设计坐标为 512×512，原点在画布中心 (256,256)，x 向右、y 向下，x/y 是相对中心偏移，不是左上角绝对坐标；文档顺序由底到顶。build_source=studio 等信号才表示 V2；保留 id、parentId 和 Ambient 结构，不要擅自升级 V1。",
            source = "watchmaker-format",
        ),
        WatchAgentKnowledgeEntry(
            title = "最小可渲染 watch.xml 模板",
            text = "最小可渲染模板：<Watch width=\"512\" height=\"512\"><Layers><Layer type=\"image\" path=\"bg.png\"/><Layer type=\"text\" text=\"12:34\" t_font=\"sans\" t_size=\"48\" t_color=\"FFFFFF\"/><Layer type=\"shape\" color=\"00FF00\" width=\"80\" height=\"20\"/></Layers></Watch>。元素名必须是 Layer，type 可为 image、text、shape；图片放 images/bg.png；坐标原点为 (256,256)，x 向右、y 向下。",
            source = "watchmaker-format",
        ),
    )
}
