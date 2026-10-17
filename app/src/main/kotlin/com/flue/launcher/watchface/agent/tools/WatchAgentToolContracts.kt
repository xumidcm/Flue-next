package com.flue.launcher.watchface.agent.tools

/** The document tool names exposed to a provider. Keep these wire names stable. */
enum class WatchAgentToolName(
    val wireName: String,
    val access: WatchAgentToolAccess,
) {
    INSPECT_FACE("inspect_face", WatchAgentToolAccess.READ_ONLY),
    READ_DOCUMENT("read_document", WatchAgentToolAccess.READ_ONLY),
    SEARCH_KNOWLEDGE("search_knowledge", WatchAgentToolAccess.READ_ONLY),
    VALIDATE_FACE("validate_face", WatchAgentToolAccess.READ_ONLY),
    PREVIEW_FACE("preview_face", WatchAgentToolAccess.READ_ONLY),
    EDIT_DOCUMENT("edit_document", WatchAgentToolAccess.WRITE),
    WRITE_DOCUMENT("write_document", WatchAgentToolAccess.WRITE),
    LIST_WORKSPACE_FILES("list_workspace_files", WatchAgentToolAccess.READ_ONLY),
    READ_IMAGE("read_image", WatchAgentToolAccess.READ_ONLY),
    RUN_BASH("run_bash", WatchAgentToolAccess.WRITE),
    SYNC_FACE_MIRROR("sync_face_mirror", WatchAgentToolAccess.WRITE),
    VALIDATE_FACE_MIRROR("validate_face_mirror", WatchAgentToolAccess.READ_ONLY),
    COMMIT_FACE_MIRROR("commit_face_mirror", WatchAgentToolAccess.WRITE),
    IMPORT_FACE_RESOURCE("import_face_resource", WatchAgentToolAccess.WRITE),
    WEB_SEARCH("web_search", WatchAgentToolAccess.READ_ONLY),
    FETCH_WEB_PAGE("fetch_web_page", WatchAgentToolAccess.READ_ONLY),
    GENERATE_IMAGE("generate_image", WatchAgentToolAccess.WRITE),
    EDIT_IMAGE("edit_image", WatchAgentToolAccess.WRITE),
    READ_FILE("read_file", WatchAgentToolAccess.READ_ONLY),
    LIST_ARCHIVE("list_archive", WatchAgentToolAccess.READ_ONLY),
    EXTRACT_ARCHIVE("extract_archive", WatchAgentToolAccess.WRITE),
    CROP_IMAGE("crop_image", WatchAgentToolAccess.WRITE),
    ;

    val isReadOnly: Boolean get() = access == WatchAgentToolAccess.READ_ONLY
    val isWrite: Boolean get() = access == WatchAgentToolAccess.WRITE

    companion object {
        fun fromWireName(value: String): WatchAgentToolName? =
            entries.firstOrNull { it.wireName == value }
    }
}

enum class WatchAgentToolAccess { READ_ONLY, WRITE }

data class WatchAgentToolParameter(
    val name: String,
    val type: String,
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
)

data class WatchAgentToolContract(
    val name: WatchAgentToolName,
    val description: String,
    val parameters: List<WatchAgentToolParameter> = emptyList(),
) {
    val access: WatchAgentToolAccess get() = name.access
}

/** Versioned schema metadata is intentionally available without a runner instance. */
object WatchAgentToolContracts {
    const val schemaVersion: String = "watch-agent-tools-v3"

    val all: List<WatchAgentToolContract> = listOf(
        WatchAgentToolContract(WatchAgentToolName.LIST_WORKSPACE_FILES, "列出当前绑定工作区与表盘 face/ 镜像中的全部受控文件，包括 watch.xml、脚本、字体、素材和图片；图片路径可用于 read_image。"),
        WatchAgentToolContract(WatchAgentToolName.READ_IMAGE, "读取当前工作区和绑定表盘包内任意位置的图片（包括 images/ 与包根目录图片），返回真实图片供视觉分析。",
            listOf(WatchAgentToolParameter("path", "string", required = true))),
        WatchAgentToolContract(WatchAgentToolName.RUN_BASH, "在已安装的工作区 Rootfs 中执行 Bash 命令。",
            listOf(WatchAgentToolParameter("command", "string", required = true), WatchAgentToolParameter("timeout_seconds", "integer"))),
        WatchAgentToolContract(
            WatchAgentToolName.INSPECT_FACE,
            "读取当前表盘版本、图层结构、素材清单以及预览状态；结果同时给出 layers_parsed、layers_in_xml 和 parse_status，数量不一致说明 XML 图层元素未被宿主解析。编辑前先调用。",
        ),
        WatchAgentToolContract(
            WatchAgentToolName.READ_DOCUMENT,
            "读取完整 watch.xml 或 scripts/script.txt，返回 revision。",
            listOf(WatchAgentToolParameter("path", "string", required = true)),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.SEARCH_KNOWLEDGE,
            "检索内置 WatchMaker 格式、标签和兼容知识。",
            listOf(WatchAgentToolParameter("query", "string", required = true)),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.VALIDATE_FACE,
            "检查当前 XML 结构、资源、路径和表达式诊断；XML 中存在图层节点但宿主解析数量不一致时必须返回错误，不能把解析失败报告为 valid=true。",
        ),
        WatchAgentToolContract(
            WatchAgentToolName.PREVIEW_FACE,
            "通过宿主原生渲染器预览当前表盘或图层；宿主提供时返回 rendered_layers、non_background_pixel_ratio 和 renderer_warning，空图层、纯背景或与上一稿相同的输出必须显式警告；null 表示宿主未暴露该测量。",
            listOf(
                WatchAgentToolParameter("target", "string"),
                WatchAgentToolParameter("mode", "string", enumValues = listOf("in_context", "isolated")),
                WatchAgentToolParameter("time", "string"),
                WatchAgentToolParameter("battery", "number"),
                WatchAgentToolParameter("display", "string", enumValues = listOf("bright", "dim")),
            ),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.EDIT_DOCUMENT,
            "按唯一 old_text 精确替换当前 revision 中的文档片段；写入立即更新当前编辑器草稿。必须使用最近一次 read_document 或成功写入返回的 revision，不能复用旧 revision。",
            listOf(
                WatchAgentToolParameter("path", "string", required = true),
                WatchAgentToolParameter("old_text", "string", required = true),
                WatchAgentToolParameter("new_text", "string", required = true),
                WatchAgentToolParameter("expected_revision", "integer", required = true),
            ),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.WRITE_DOCUMENT,
            "以 expected_revision 为基础立即替换当前编辑器草稿中的完整文档。只能使用最近一次读取或成功写入返回的 revision；不要与其他写入并发。",
            listOf(
                WatchAgentToolParameter("path", "string", required = true),
                WatchAgentToolParameter("content", "string", required = true),
                WatchAgentToolParameter("expected_revision", "integer", required = true),
            ),
        ),
    )

    val extended: List<WatchAgentToolContract> = listOf(
        WatchAgentToolContract(
            WatchAgentToolName.READ_FILE,
            "读取当前工作区、表盘或会话附件目录中的文件内容文本。",
            listOf(WatchAgentToolParameter("path", "string", required = true)),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.LIST_ARCHIVE,
            "读取 ZIP 压缩包中的文件列表，无需 Bash 即可查看压缩包内容。",
            listOf(WatchAgentToolParameter("path", "string", required = true)),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.EXTRACT_ARCHIVE,
            "将 ZIP 压缩包解压到工作区目标目录中，受工作区边界和配额保护。",
            listOf(
                WatchAgentToolParameter("path", "string", required = true),
                WatchAgentToolParameter("target_dir", "string", required = false),
            ),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.CROP_IMAGE,
            "将工作区或表盘内的图片按像素区域裁剪并输出为新文件，无需依赖 Bash。",
            listOf(
                WatchAgentToolParameter("input_path", "string", required = true),
                WatchAgentToolParameter("x", "integer", required = true),
                WatchAgentToolParameter("y", "integer", required = true),
                WatchAgentToolParameter("width", "integer", required = true),
                WatchAgentToolParameter("height", "integer", required = true),
                WatchAgentToolParameter("output_path", "string", required = false),
            ),
        ),
    )
    val mirror: List<WatchAgentToolContract> = listOf(
        WatchAgentToolContract(WatchAgentToolName.SYNC_FACE_MIRROR, "同步当前表盘到绑定工作区 face/ 镜像。"),
        WatchAgentToolContract(WatchAgentToolName.VALIDATE_FACE_MIRROR, "校验 face/ 镜像。"),
        WatchAgentToolContract(WatchAgentToolName.COMMIT_FACE_MIRROR, "按 expected_revision 提交 face/ 镜像回当前表盘。", listOf(WatchAgentToolParameter("expected_revision", "integer", true))),
        WatchAgentToolContract(WatchAgentToolName.IMPORT_FACE_RESOURCE, "导入工作区表盘资源。", listOf(WatchAgentToolParameter("path", "string", true))),
    )

    /** Optional network/image tools. They are only exposed when a configured client is present. */
    val optional: List<WatchAgentToolContract> = listOf(
        WatchAgentToolContract(
            WatchAgentToolName.WEB_SEARCH,
            "使用已配置的网络搜索 API 查询实时网页，返回标题、摘要、来源和 URL。",
            listOf(
                WatchAgentToolParameter("query", "string", required = true),
                WatchAgentToolParameter("max_results", "integer"),
                WatchAgentToolParameter("search_depth", "string", enumValues = listOf("basic", "advanced")),
            ),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.FETCH_WEB_PAGE,
            "抓取指定 HTTP(S) 页面并提取正文文本；只有用户需要网页细节时调用。",
            listOf(
                WatchAgentToolParameter("url", "string", required = true),
                WatchAgentToolParameter("max_chars", "integer"),
            ),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.GENERATE_IMAGE,
            "通过已配置的 OpenAI Images API 生成图片并保存到当前工作区。",
            listOf(
                WatchAgentToolParameter("prompt", "string", required = true),
                WatchAgentToolParameter("size", "string"),
                WatchAgentToolParameter("quality", "string", enumValues = listOf("auto", "low", "medium", "high", "xhigh", "max")),
                WatchAgentToolParameter("background", "string", enumValues = listOf("auto", "opaque", "transparent")),
                WatchAgentToolParameter("output_format", "string", enumValues = listOf("png", "jpeg", "webp")),
                WatchAgentToolParameter("output_compression", "integer"),
            ),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.EDIT_IMAGE,
            "使用 OpenAI Images API 修改当前表盘或工作区中的图片，并将结果保存到当前工作区。",
            listOf(
                WatchAgentToolParameter("prompt", "string", required = true),
                WatchAgentToolParameter("input_path", "string", required = true),
                WatchAgentToolParameter("mask_path", "string"),
                WatchAgentToolParameter("size", "string"),
                WatchAgentToolParameter("quality", "string", enumValues = listOf("auto", "low", "medium", "high", "xhigh", "max")),
                WatchAgentToolParameter("background", "string", enumValues = listOf("auto", "opaque", "transparent")),
                WatchAgentToolParameter("output_format", "string", enumValues = listOf("png", "jpeg", "webp")),
                WatchAgentToolParameter("output_compression", "integer"),
                WatchAgentToolParameter("input_fidelity", "string", enumValues = listOf("low", "high")),
            ),
        ),
    )

    fun find(name: String): WatchAgentToolContract? =
        WatchAgentToolName.fromWireName(name)?.let(::find)

    fun find(name: WatchAgentToolName): WatchAgentToolContract? =
        (all + extended + mirror + optional).firstOrNull { it.name == name }
}

data class WatchAgentToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
) {
    val toolName: WatchAgentToolName?
        get() = WatchAgentToolName.fromWireName(name)
}

typealias WatchAgentCall = WatchAgentToolCall

data class WatchAgentToolError(
    val code: String,
    val message: String,
)

data class WatchAgentToolResult(
    val callId: String,
    val name: String,
    val revision: Long,
    val content: Any? = null,
    val error: WatchAgentToolError? = null,
    /** Wall-clock lifecycle metadata kept with the result for expandable UI details. */
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val durationMs: Long? = null,
) {
    val succeeded: Boolean get() = error == null
}

typealias WatchAgentResult = WatchAgentToolResult

enum class WatchAgentDocumentPath(val wirePath: String) {
    WATCH_XML("watch.xml"),
    GLOBAL_SCRIPT("scripts/script.txt"),
    ;

    companion object {
        fun fromWirePath(value: String): WatchAgentDocumentPath? =
            entries.firstOrNull { it.wirePath == value }
    }
}

data class WatchAgentInspectFaceRequest(val expectedRevision: Long? = null)

data class WatchAgentReadDocumentRequest(
    val path: WatchAgentDocumentPath,
    val expectedRevision: Long? = null,
)

data class WatchAgentSearchKnowledgeRequest(val query: String)

data class WatchAgentValidateFaceRequest(val expectedRevision: Long? = null)

enum class WatchAgentPreviewMode { IN_CONTEXT, ISOLATED }
enum class WatchAgentDisplayMode { BRIGHT, DIM }

data class WatchAgentPreviewRequest(
    val target: String = "face",
    val mode: WatchAgentPreviewMode = WatchAgentPreviewMode.IN_CONTEXT,
    val time: String? = null,
    val battery: Int? = null,
    val display: WatchAgentDisplayMode? = null,
    val expectedRevision: Long? = null,
)

data class WatchAgentEditDocumentRequest(
    val path: WatchAgentDocumentPath,
    val oldText: String,
    val newText: String,
    val expectedRevision: Long,
) {
    val baseRevision: Long get() = expectedRevision
}

data class WatchAgentWriteDocumentRequest(
    val path: WatchAgentDocumentPath,
    val content: String,
    val expectedRevision: Long,
) {
    val baseRevision: Long get() = expectedRevision
}
