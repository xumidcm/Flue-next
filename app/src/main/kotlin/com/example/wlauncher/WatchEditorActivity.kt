package com.flue.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceType
import com.flue.launcher.watchface.editor.WatchEditorLayer
import com.flue.launcher.watchface.editor.WatchEditorLayerKey
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.editor.WatchEditorSnapshot
import com.flue.launcher.watchface.standard.WATCHMAKER_ID_PREFIX
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

class WatchEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val watchFaceId = intent.getStringExtra(EXTRA_WATCH_FACE_ID)
        setContent {
            val vm: LauncherViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalScale by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle) {
                ProvideGlobalUiScale(globalScale) {
                    WatchEditorRoute(
                        watchFaceId = watchFaceId,
                        vm = vm,
                        onInstalled = { descriptor ->
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(EXTRA_INSTALLED_WATCH_FACE_ID, descriptor.id),
                            )
                        },
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_WATCH_FACE_ID = "watch_editor_watch_face_id"
        const val EXTRA_INSTALLED_WATCH_FACE_ID = "watch_editor_installed_watch_face_id"
    }
}

@Composable
private fun WatchEditorRoute(
    watchFaceId: String?,
    vm: LauncherViewModel,
    onInstalled: (LunchWatchFaceDescriptor) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<WatchEditorSession?>(null) }
    var snapshot by remember { mutableStateOf<WatchEditorSnapshot?>(null) }
    var selectedLayerKey by remember { mutableStateOf<WatchEditorLayerKey?>(null) }
    var previewToken by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var pendingSaveName by remember { mutableStateOf("") }

    fun updateSnapshot(next: WatchEditorSnapshot, preferred: WatchEditorLayerKey? = selectedLayerKey) {
        snapshot = next
        selectedLayerKey = preferred?.takeIf { key -> next.layers.any { it.key == key } }
            ?: next.layers.firstOrNull()?.key
    }

    fun runSessionOperation(
        status: String,
        operation: (WatchEditorSession) -> WatchEditorLayerKey? = { null },
        onComplete: (() -> Unit)? = null,
    ) {
        val active = session ?: return
        scope.launch {
            busyMessage = status
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val preferred = operation(active)
                    active.flushDraftForPreview()
                    active.snapshot() to preferred
                }
            }.onSuccess { (next, preferred) ->
                updateSnapshot(next, preferred ?: selectedLayerKey)
                previewToken++
                onComplete?.invoke()
            }.onFailure { error ->
                errorMessage = error.message ?: "编辑操作失败"
            }
            busyMessage = null
        }
    }

    val exportDocument = rememberLauncherForActivityResult(CreateDocument("application/zip")) { uri ->
        val active = session
        if (uri != null && active != null) {
            scope.launch {
                busyMessage = "正在导出 .watch"
                errorMessage = null
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val temporary = File.createTempFile("flue_watch_export_", ".watch", context.cacheDir)
                        try {
                            active.exportWatch(temporary)
                            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                                temporary.inputStream().use { input -> input.copyTo(output) }
                            } ?: error("无法写入导出位置")
                            active.snapshot()
                        } finally {
                            temporary.delete()
                        }
                    }
                }
                result.onSuccess { next ->
                    updateSnapshot(next)
                    Toast.makeText(context, "已导出 .watch", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    errorMessage = error.message ?: "导出失败"
                }
                busyMessage = null
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        val active = session
        val key = selectedLayerKey
        if (uri != null && active != null && key != null) {
            scope.launch {
                busyMessage = "正在导入图片"
                errorMessage = null
                runCatching {
                    withContext(Dispatchers.IO) {
                        val resourcePath = active.importImage(context, uri)
                        active.updateLayerAttribute(key, "path", resourcePath)
                        active.flushDraftForPreview()
                        active.snapshot()
                    }
                }.onSuccess { next ->
                    updateSnapshot(next, key)
                    previewToken++
                }.onFailure { error ->
                    errorMessage = error.message ?: "图片导入失败"
                }
                busyMessage = null
            }
        }
    }

    LaunchedEffect(watchFaceId) {
        loading = true
        errorMessage = null
        runCatching {
            withContext(Dispatchers.IO) {
                val opened = if (watchFaceId.isNullOrBlank()) {
                    WatchEditorSession.createBlank(context)
                } else {
                    val descriptor = WatchmakerWatchFaceStorage.scan(context)
                        .firstOrNull { it.id == watchFaceId }
                        ?: error("找不到要编辑的标准 .watch 表盘")
                    WatchEditorSession.open(context, descriptor)
                }
                opened to opened.snapshot()
            }
        }.onSuccess { (opened, initialSnapshot) ->
            session = opened
            updateSnapshot(initialSnapshot)
            previewToken++
        }.onFailure { error ->
            errorMessage = error.message ?: "无法打开表盘编辑器"
        }
        loading = false
    }

    DisposableEffect(session) {
        val active = session
        onDispose {
            if (active != null) {
                // A draft may contain tens of MiB of images. Never delete it on
                // the UI thread while this Activity is closing.
                Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                    runCatching { active.close() }
                }
            }
        }
    }

    fun requestClose() {
        if (snapshot?.dirty == true) showDiscardDialog = true else onClose()
    }
    BackHandler(onBack = ::requestClose)

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃草稿？") },
            text = { Text("尚未另存或导出的修改会被删除，已安装的原表盘不会受到影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onClose()
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("另存为新表盘") },
            text = {
                OutlinedTextField(
                    value = pendingSaveName,
                    onValueChange = { pendingSaveName = it.take(80) },
                    label = { Text("表盘名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pendingSaveName.isNotBlank() && busyMessage == null,
                    onClick = {
                        val active = session ?: return@TextButton
                        val name = pendingSaveName.trim()
                        showSaveDialog = false
                        scope.launch {
                            busyMessage = "正在安装新表盘"
                            errorMessage = null
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    active.updateRootAttribute("name", name)
                                    active.flushDraftForPreview()
                                    val archive = File.createTempFile("flue_edited_", ".watch", context.cacheDir)
                                    try {
                                        active.exportWatch(archive)
                                        val descriptor = vm.importWatchmakerWatchFaceFile(archive).getOrThrow()
                                        descriptor to active.snapshot()
                                    } finally {
                                        archive.delete()
                                    }
                                }
                            }
                            result.onSuccess { (descriptor, nextSnapshot) ->
                                snapshot = nextSnapshot
                                previewToken++
                                onInstalled(descriptor)
                                Toast.makeText(context, "已安装 ${descriptor.displayName}", Toast.LENGTH_SHORT).show()
                            }.onFailure { error ->
                                errorMessage = error.message ?: "安装编辑后的表盘失败"
                            }
                            busyMessage = null
                        }
                    },
                ) { Text("安装") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            loading -> CenteredStatus("正在创建安全草稿", showProgress = true)
            session == null || snapshot == null -> ErrorPage(
                message = errorMessage ?: "编辑器初始化失败",
                onClose = onClose,
            )
            else -> {
                val active = session!!
                val current = snapshot!!
                val selected = current.layers.firstOrNull { it.key == selectedLayerKey }
                WatchEditorScreen(
                    snapshot = current,
                    selectedLayer = selected,
                    previewDescriptor = draftDescriptor(active, current),
                    previewToken = previewToken,
                    busyMessage = busyMessage,
                    errorMessage = errorMessage,
                    onBack = ::requestClose,
                    onSelectLayer = { selectedLayerKey = it },
                    onUndo = {
                        runSessionOperation("正在撤销", operation = { editor ->
                            editor.undo()
                            selectedLayerKey
                        })
                    },
                    onRedo = {
                        runSessionOperation("正在重做", operation = { editor ->
                            editor.redo()
                            selectedLayerKey
                        })
                    },
                    onAddLayer = { type ->
                        runSessionOperation("正在添加图层", operation = { editor ->
                            editor.addLayer(type, defaultAttributes(type))
                        })
                    },
                    onMoveLayer = { key, target ->
                        runSessionOperation("正在调整图层顺序", operation = { editor ->
                            editor.reorderLayer(key, target)
                            key
                        })
                    },
                    onDuplicateLayer = { key ->
                        runSessionOperation("正在复制图层", operation = { editor ->
                            editor.duplicateLayer(key)
                        })
                    },
                    onDeleteLayer = { key ->
                        runSessionOperation("正在删除图层", operation = { editor ->
                            editor.deleteLayer(key)
                            null
                        })
                    },
                    onApplyLayerAttributes = { key, changes ->
                        runSessionOperation("正在更新属性", operation = { editor ->
                            editor.updateLayerAttributes(key, changes)
                            key
                        })
                    },
                    onImportImage = {
                        imagePicker.launch(arrayOf("image/*"))
                    },
                    onSaveAsNew = {
                        pendingSaveName = current.rootAttributes["name"].orEmpty()
                            .ifBlank { "Flue Edited Watch" }
                        showSaveDialog = true
                    },
                    onExport = {
                        val name = safeWatchFileName(current.rootAttributes["name"])
                        exportDocument.launch("$name.watch")
                    },
                )
            }
        }
    }
}

@Composable
private fun WatchEditorScreen(
    snapshot: WatchEditorSnapshot,
    selectedLayer: WatchEditorLayer?,
    previewDescriptor: LunchWatchFaceDescriptor,
    previewToken: Int,
    busyMessage: String?,
    errorMessage: String?,
    onBack: () -> Unit,
    onSelectLayer: (WatchEditorLayerKey) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddLayer: (String) -> Unit,
    onMoveLayer: (WatchEditorLayerKey, Int) -> Unit,
    onDuplicateLayer: (WatchEditorLayerKey) -> Unit,
    onDeleteLayer: (WatchEditorLayerKey) -> Unit,
    onApplyLayerAttributes: (WatchEditorLayerKey, Map<String, String?>) -> Unit,
    onImportImage: () -> Unit,
    onSaveAsNew: () -> Unit,
    onExport: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(".watch 编辑器", fontWeight = FontWeight.Bold)
                    Text(
                        if (snapshot.dirty) "草稿有修改" else "草稿已同步",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(48.dp))
            }
        }
        if (busyMessage != null) {
            item("busy") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(busyMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (errorMessage != null) {
            item("error") {
                Text(
                    errorMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item("preview") {
            WatchEditorPreview(previewDescriptor, previewToken)
        }
        item("primary_actions") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onUndo, enabled = snapshot.canUndo && busyMessage == null) {
                    Text("撤销")
                }
                OutlinedButton(onClick = onRedo, enabled = snapshot.canRedo && busyMessage == null) {
                    Text("重做")
                }
                Button(onClick = onSaveAsNew, enabled = busyMessage == null) { Text("另存为新表盘") }
                OutlinedButton(onClick = onExport, enabled = busyMessage == null) { Text("导出") }
            }
        }
        item("add_title") {
            Text("添加基础图层", fontWeight = FontWeight.SemiBold)
        }
        item("add_actions") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BASIC_LAYER_TYPES.forEach { (type, label) ->
                    OutlinedButton(onClick = { onAddLayer(type) }, enabled = busyMessage == null) {
                        Text(label)
                    }
                }
            }
        }
        item("layers_title") {
            Text("图层 · ${snapshot.layers.size}", fontWeight = FontWeight.SemiBold)
        }
        if (snapshot.layers.isEmpty()) {
            item("layers_empty") {
                Text(
                    "当前没有图层。上方可添加文字、形状、图片、圆环或进度图层。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            items(snapshot.layers, key = { it.key.value }) { layer ->
                EditorLayerRow(
                    layer = layer,
                    selected = layer.key == selectedLayer?.key,
                    layerCount = snapshot.layers.size,
                    enabled = busyMessage == null,
                    onSelect = { onSelectLayer(layer.key) },
                    onMove = { onMoveLayer(layer.key, it) },
                    onDuplicate = { onDuplicateLayer(layer.key) },
                    onDelete = { onDeleteLayer(layer.key) },
                )
            }
        }
        if (selectedLayer != null) {
            item("properties_divider") { HorizontalDivider() }
            item("properties") {
                LayerPropertiesEditor(
                    layer = selectedLayer,
                    enabled = busyMessage == null,
                    onApply = { onApplyLayerAttributes(selectedLayer.key, it) },
                    onImportImage = onImportImage,
                )
            }
        }
        item("safety_note") {
            Text(
                "编辑器只操作缓存草稿；保存会生成新的内容寻址表盘，不会覆盖原表盘。未知 XML 属性和节点会原样保留。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun WatchEditorPreview(
    descriptor: LunchWatchFaceDescriptor,
    refreshToken: Int,
) {
    var loadError by remember(descriptor.stableKey, refreshToken) { mutableStateOf<String?>(null) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color.Black, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> WatchmakerWatchFaceView(context) },
            update = { view ->
                view.bind(
                    descriptor = descriptor,
                    refreshToken = refreshToken,
                    isFaceVisible = true,
                    onLongPress = null,
                    onDoubleTap = null,
                    onLoadFailure = { _, error -> loadError = error.message ?: "预览加载失败" },
                )
            },
            onRelease = WatchmakerWatchFaceView::dispose,
        )
        loadError?.let { message ->
            Text(
                message,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .padding(8.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun EditorLayerRow(
    layer: WatchEditorLayer,
    selected: Boolean,
    layerCount: Int,
    enabled: Boolean,
    onSelect: () -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${layer.order + 1}. ${layer.type}", fontWeight = FontWeight.Medium)
            layer.id?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = { onMove(layer.order - 1) },
                enabled = enabled && layer.order > 0,
            ) { Text("上移") }
            TextButton(
                onClick = { onMove(layer.order + 1) },
                enabled = enabled && layer.order < layerCount - 1,
            ) { Text("下移") }
            TextButton(onClick = onDuplicate, enabled = enabled) { Text("复制") }
            TextButton(onClick = onDelete, enabled = enabled) { Text("删除") }
        }
    }
}

@Composable
private fun LayerPropertiesEditor(
    layer: WatchEditorLayer,
    enabled: Boolean,
    onApply: (Map<String, String?>) -> Unit,
    onImportImage: () -> Unit,
) {
    val initial = remember(layer.key, layer.attributes) {
        EDITABLE_ATTRIBUTES.associateWith { layer.attributes[it].orEmpty() }
    }
    var drafts by remember(layer.key, layer.attributes) { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${layer.type} 属性", fontWeight = FontWeight.SemiBold)
        EDITABLE_ATTRIBUTES.forEach { attribute ->
            OutlinedTextField(
                value = drafts[attribute].orEmpty(),
                onValueChange = { value -> drafts = drafts + (attribute to value.take(512)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text(attributeLabel(attribute)) },
                singleLine = attribute != "text",
                minLines = if (attribute == "text") 2 else 1,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (attribute in NUMERIC_ATTRIBUTES) {
                        KeyboardType.Decimal
                    } else {
                        KeyboardType.Text
                    },
                ),
            )
        }
        if (layer.type.equals("image", ignoreCase = true)) {
            OutlinedButton(onClick = onImportImage, enabled = enabled) { Text("导入图片并写入 path") }
        }
        Button(
            onClick = {
                onApply(
                    drafts.mapValues { (name, value) ->
                        if (value.isEmpty() && name != "text") null else value
                    },
                )
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("应用属性并刷新预览") }
        if (layer.attributes.keys.any { it !in EDITABLE_ATTRIBUTES && it != "type" }) {
            Text(
                "其他属性已保留：" + layer.attributes.keys
                    .filter { it !in EDITABLE_ATTRIBUTES && it != "type" }
                    .sorted()
                    .joinToString(", "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun CenteredStatus(message: String, showProgress: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showProgress) CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(message)
    }
}

@Composable
private fun ErrorPage(message: String, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose) { Text("返回") }
    }
}

private fun draftDescriptor(
    session: WatchEditorSession,
    snapshot: WatchEditorSnapshot,
): LunchWatchFaceDescriptor = LunchWatchFaceDescriptor(
    id = "${WATCHMAKER_ID_PREFIX}editor-draft",
    type = LunchWatchFaceType.WATCHMAKER,
    displayName = snapshot.rootAttributes["name"].orEmpty().ifBlank { "Editor draft" },
    summary = "编辑预览 · ${snapshot.layers.size} 个图层",
    sourceDirPath = session.draftDirectory.absolutePath,
    watchFaceName = "editor-draft",
)

private fun defaultAttributes(type: String): Map<String, String> = when (type) {
    "text" -> mapOf(
        "text" to "Flue",
        "text_size" to "48",
        "color" to "#FFFFFFFF",
        "x" to "0",
        "y" to "0",
    )
    "shape" -> mapOf(
        "shape" to "circle",
        "width" to "120",
        "height" to "120",
        "color" to "#FF4A90E2",
        "x" to "0",
        "y" to "0",
    )
    "image" -> mapOf("x" to "0", "y" to "0", "opacity" to "100")
    "ring" -> mapOf(
        "radius" to "120",
        "width" to "12",
        "color" to "#FFFFFFFF",
        "x" to "0",
        "y" to "0",
    )
    "progress" -> mapOf(
        "radius" to "120",
        "width" to "12",
        "color" to "#FFFFFFFF",
        "value" to "50",
        "x" to "0",
        "y" to "0",
    )
    else -> emptyMap()
}

private fun attributeLabel(name: String): String = when (name) {
    "x" -> "X 坐标"
    "y" -> "Y 坐标"
    "rotation" -> "旋转角度"
    "opacity" -> "不透明度"
    "scale" -> "缩放"
    "text" -> "文字 / Watchmaker 表达式"
    "color" -> "颜色"
    "path" -> "资源路径"
    else -> name
}

private fun safeWatchFileName(raw: String?): String {
    val normalized = raw.orEmpty().trim().ifBlank { "flue-watch" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .take(80)
    return normalized.ifBlank { "flue-watch" }
}

private val BASIC_LAYER_TYPES = listOf(
    "text" to "文字",
    "shape" to "形状",
    "image" to "图片",
    "ring" to "圆环",
    "progress" to "进度",
)

private val EDITABLE_ATTRIBUTES = listOf(
    "x",
    "y",
    "rotation",
    "opacity",
    "scale",
    "text",
    "color",
    "path",
)

private val NUMERIC_ATTRIBUTES = setOf("x", "y", "rotation", "opacity", "scale")
