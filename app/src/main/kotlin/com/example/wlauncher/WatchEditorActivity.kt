package com.flue.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.ui.common.flueElasticNestedScroll
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceType
import com.flue.launcher.watchface.editor.WatchEditorLayer
import com.flue.launcher.watchface.editor.WatchEditorLayerKey
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.editor.WatchEditorSnapshot
import com.flue.launcher.watchface.editor.WatchEditorTransform
import com.flue.launcher.watchface.editor.buildWatchEditorTreeRows
import com.flue.launcher.watchface.editor.conversion.WatchConversionResult
import com.flue.launcher.watchface.editor.conversion.WatchFormatConverter
import com.flue.launcher.watchface.standard.WATCHMAKER_ID_PREFIX
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceView
import com.flue.launcher.watchface.standard.rememberWatchmakerWatchDataProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs

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
    var saveAsMode by remember { mutableStateOf(true) }
    var transformPreview by remember { mutableStateOf<WatchEditorTransformPreview?>(null) }

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
        if (busyMessage != null) return
        // Acquire the coarse editor-operation gate synchronously. Setting it
        // inside launch left a one-frame window where a fast double tap could
        // enqueue two DOM/history mutations against the same session.
        busyMessage = status
        errorMessage = null
        scope.launch {
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

    val watchArchivePicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busyMessage = "正在导入 .watch"
                errorMessage = null
                val previous = session
                runCatching {
                    withContext(Dispatchers.IO) {
                        val temporary = File.createTempFile("flue_editor_import_", ".watch", context.cacheDir)
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                temporary.outputStream().use { output -> input.copyTo(output) }
                            } ?: error("无法读取所选文件")
                            val descriptor = vm.importWatchmakerWatchFaceFile(temporary).getOrThrow()
                            val opened = WatchEditorSession.open(context, descriptor)
                            Triple(descriptor, opened, opened.snapshot())
                        } finally {
                            temporary.delete()
                        }
                    }
                }.onSuccess { (descriptor, opened, importedSnapshot) ->
                    session = opened
                    updateSnapshot(importedSnapshot)
                    previewToken++
                    previous?.let { old ->
                        Dispatchers.IO.dispatch(EmptyCoroutineContext) { runCatching { old.close() } }
                    }
                    onInstalled(descriptor)
                    Toast.makeText(context, "已导入 ${descriptor.displayName}", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    errorMessage = error.message ?: "导入 .watch 失败"
                }
                busyMessage = null
            }
        }
    }

    val conversionPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busyMessage = "正在识别并转换表盘"
                errorMessage = null
                val previous = session
                var conversionSummary = ""
                runCatching {
                    withContext(Dispatchers.IO) {
                        val input = File.createTempFile("flue_conversion_input_", ".bin", context.cacheDir)
                        val output = File.createTempFile("flue_conversion_output_", ".watch", context.cacheDir)
                        try {
                            context.contentResolver.openInputStream(uri)?.use { source ->
                                input.outputStream().use { target -> source.copyTo(target) }
                            } ?: error("无法读取所选文件")
                            when (val conversion = WatchFormatConverter().convert(input, output)) {
                                is WatchConversionResult.Failure -> error(
                                    buildString {
                                        append(conversion.message)
                                        val unsupported = conversion.report.unsupportedFeatures
                                        if (unsupported.isNotEmpty()) {
                                            append("；兼容报告：未支持 ")
                                            append(unsupported.distinct().joinToString("、"))
                                        }
                                        conversion.report.warnings.firstOrNull()?.let {
                                            append("；警告：")
                                            append(it)
                                        }
                                    },
                                )
                                is WatchConversionResult.Success -> {
                                    conversionSummary = buildString {
                                        append("图层 ${conversion.report.convertedLayerCount}")
                                        if (conversion.report.unsupportedFeatures.isNotEmpty()) {
                                            append("；未支持 ")
                                            append(
                                                conversion.report.unsupportedFeatures
                                                    .distinct()
                                                    .joinToString("、"),
                                            )
                                        }
                                        if (conversion.report.warnings.isNotEmpty()) {
                                            append("；警告 ${conversion.report.warnings.size} 条")
                                        }
                                    }
                                    val descriptor = vm.importWatchmakerWatchFaceFile(
                                        conversion.outputFile,
                                    ).getOrThrow()
                                    val opened = WatchEditorSession.open(context, descriptor)
                                    Triple(descriptor, opened, opened.snapshot())
                                }
                            }
                        } finally {
                            input.delete()
                            output.delete()
                        }
                    }
                }.onSuccess { (descriptor, opened, convertedSnapshot) ->
                    session = opened
                    updateSnapshot(convertedSnapshot)
                    previewToken++
                    previous?.let { old ->
                        Dispatchers.IO.dispatch(EmptyCoroutineContext) { runCatching { old.close() } }
                    }
                    onInstalled(descriptor)
                    Toast.makeText(
                        context,
                        "已转换并导入 ${descriptor.displayName}（$conversionSummary）",
                        Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure { error ->
                    errorMessage = error.message ?: "表盘转换失败"
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
            title = { Text(if (saveAsMode) "另存为新表盘" else "保存表盘") },
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
                        val createNewLogicalId = saveAsMode || !active.canSaveCurrentRevision
                        showSaveDialog = false
                        scope.launch {
                            busyMessage = if (createNewLogicalId) "正在另存为新表盘" else "正在保存当前修订"
                            errorMessage = null
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    active.updateRootAttribute("name", name)
                                    active.flushDraftForPreview()
                                    if (!createNewLogicalId) {
                                        active.saveCurrentRevision()
                                        val descriptor = WatchmakerWatchFaceStorage.scan(context)
                                            .firstOrNull { it.id == active.logicalId }
                                            ?: error("保存成功但未能重新扫描原 logicalId")
                                        EditorSaveResult(
                                            descriptor = descriptor,
                                            openedSession = null,
                                            snapshot = active.snapshot(),
                                        )
                                    } else {
                                        val archive = File.createTempFile(
                                            "flue_edited_",
                                            ".watch",
                                            context.cacheDir,
                                        )
                                        try {
                                            active.exportWatch(archive)
                                            val descriptor = vm.importWatchmakerWatchFaceFile(archive)
                                                .getOrThrow()
                                            val opened = WatchEditorSession.open(context, descriptor)
                                            EditorSaveResult(
                                                descriptor = descriptor,
                                                openedSession = opened,
                                                snapshot = opened.snapshot(),
                                            )
                                        } finally {
                                            archive.delete()
                                        }
                                    }
                                }
                            }
                            result.onSuccess { saved ->
                                if (saved.openedSession != null) {
                                    session = saved.openedSession
                                }
                                snapshot = saved.snapshot
                                previewToken++
                                onInstalled(saved.descriptor)
                                Toast.makeText(
                                    context,
                                    if (createNewLogicalId) {
                                        "已另存为 ${saved.descriptor.displayName}"
                                    } else {
                                        "已保存 ${saved.descriptor.displayName}（原 logicalId）"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }.onFailure { error ->
                                errorMessage = error.message ?: "保存编辑后的表盘失败"
                            }
                            busyMessage = null
                        }
                    },
                ) { Text(if (saveAsMode) "另存并安装" else "保存") }
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
                    transformPreview = transformPreview,
                    busyMessage = busyMessage,
                    errorMessage = errorMessage,
                    onBack = ::requestClose,
                    onSelectLayer = { selectedLayerKey = it },
                    onBeginTransform = { key ->
                        val initial = active.beginLayerTransformGesture(key)
                        transformPreview = WatchEditorTransformPreview(key, initial)
                        initial
                    },
                    onUpdateTransform = { key, transform ->
                        if (busyMessage == null) {
                            active.updateLayerTransformPreview(key, transform)
                            transformPreview = WatchEditorTransformPreview(key, transform.sanitized())
                        }
                    },
                    onEndTransform = { key, cancelled ->
                        transformPreview = null
                        val wasBusy = busyMessage != null
                        if (shouldHandleWatchEditorTransformEnd(cancelled, wasBusy)) {
                            if (!wasBusy) {
                                busyMessage = if (cancelled) {
                                    "正在撤销画布手势"
                                } else {
                                    "正在提交画布手势"
                                }
                                errorMessage = null
                            }
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        if (cancelled) {
                                            active.cancelLayerTransformGesture(key)
                                        } else {
                                            active.commitLayerTransformGesture(key)
                                            active.flushDraftForPreview()
                                        }
                                        active.snapshot()
                                    }
                                }.onSuccess { next ->
                                    updateSnapshot(next, key)
                                    previewToken++
                                }.onFailure { error ->
                                    errorMessage = error.message ?: "画布手势提交失败"
                                }
                                if (!wasBusy) busyMessage = null
                            }
                        }
                    },
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
                        saveAsMode = true
                        pendingSaveName = current.rootAttributes["name"].orEmpty()
                            .ifBlank { "Flue Edited Watch" }
                        showSaveDialog = true
                    },
                    onSave = {
                        saveAsMode = false
                        pendingSaveName = current.rootAttributes["name"].orEmpty()
                            .ifBlank { "Flue Watch" }
                        showSaveDialog = true
                    },
                    onRename = {
                        saveAsMode = false
                        pendingSaveName = current.rootAttributes["name"].orEmpty()
                            .ifBlank { "Flue Watch" }
                        showSaveDialog = true
                    },
                    onExport = {
                        val name = safeWatchFileName(current.rootAttributes["name"])
                        exportDocument.launch("$name.watch")
                    },
                    onImportWatch = {
                        watchArchivePicker.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "application/x-zip-compressed",
                            ),
                        )
                    },
                    onConvertWatch = {
                        conversionPicker.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "application/vnd.android.package-archive",
                            ),
                        )
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
    transformPreview: WatchEditorTransformPreview?,
    busyMessage: String?,
    errorMessage: String?,
    onBack: () -> Unit,
    onSelectLayer: (WatchEditorLayerKey) -> Unit,
    onBeginTransform: (WatchEditorLayerKey) -> WatchEditorTransform,
    onUpdateTransform: (WatchEditorLayerKey, WatchEditorTransform) -> Unit,
    onEndTransform: (WatchEditorLayerKey, Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddLayer: (String) -> Unit,
    onMoveLayer: (WatchEditorLayerKey, Int) -> Unit,
    onDuplicateLayer: (WatchEditorLayerKey) -> Unit,
    onDeleteLayer: (WatchEditorLayerKey) -> Unit,
    onApplyLayerAttributes: (WatchEditorLayerKey, Map<String, String?>) -> Unit,
    onImportImage: () -> Unit,
    onSaveAsNew: () -> Unit,
    onSave: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onImportWatch: () -> Unit,
    onConvertWatch: () -> Unit,
) {
    var panel by rememberSaveable { mutableStateOf(WatchEditorPanel.LAYERS) }
    // Each editor level keeps its own position. Re-creating one state whenever
    // the tab changes made returning from Properties jump the layer list back
    // to the top, which is especially disorienting on a small watch viewport.
    val layerListState = rememberLazyListState()
    val propertiesListState = rememberLazyListState()
    val watchListState = rememberLazyListState()
    val panelListState: LazyListState = when (panel) {
        WatchEditorPanel.LAYERS -> layerListState
        WatchEditorPanel.PROPERTIES -> propertiesListState
        WatchEditorPanel.WATCH -> watchListState
    }
    var collapsedLayerKeys by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    val context = LocalContext.current
    var bodySensorsPermissionGranted by remember(context) {
        mutableStateOf(hasEditorBodySensorsPermission(context))
    }
    val bodySensorsPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) {
        bodySensorsPermissionGranted = hasEditorBodySensorsPermission(context)
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val previewSize = watchEditorPreviewSizeDp(maxWidth.value, maxHeight.value).dp
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        snapshot.rootAttributes["name"].orEmpty().ifBlank { ".watch 编辑器" },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        if (snapshot.dirty) "草稿有修改" else "草稿已同步",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onUndo, enabled = snapshot.canUndo && busyMessage == null) {
                    Icon(Icons.Default.Undo, contentDescription = "撤销")
                }
                IconButton(onClick = onRedo, enabled = snapshot.canRedo && busyMessage == null) {
                    Icon(Icons.Default.Redo, contentDescription = "重做")
                }
            }
            WatchEditorPreview(
                descriptor = previewDescriptor,
                refreshToken = previewToken,
                bodySensorsPermissionGranted = bodySensorsPermissionGranted,
                selectedLayer = selectedLayer,
                transformPreview = transformPreview,
                enabled = busyMessage == null,
                onBeginTransform = onBeginTransform,
                onUpdateTransform = onUpdateTransform,
                onEndTransform = onEndTransform,
                modifier = Modifier
                    .size(previewSize)
                    .aspectRatio(1f)
                    .align(Alignment.CenterHorizontally),
            )
            TabRow(selectedTabIndex = panel.ordinal) {
                WatchEditorPanel.entries.forEach { candidate ->
                    Tab(
                        selected = panel == candidate,
                        onClick = { panel = candidate },
                        text = { Text(candidate.label, maxLines = 1) },
                    )
                }
            }
            when (panel) {
                WatchEditorPanel.LAYERS -> LazyColumn(
                    state = panelListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .flueElasticNestedScroll(panelListState),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    editorStatusItems(busyMessage, errorMessage)
                    item("add_actions") {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BASIC_LAYER_TYPES.forEach { (type, label) ->
                                OutlinedButton(
                                    onClick = { onAddLayer(type) },
                                    enabled = busyMessage == null,
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(label)
                                }
                            }
                        }
                    }
                    if (snapshot.layers.isEmpty()) {
                        item("layers_empty") {
                            Text(
                                "当前没有图层",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        val treeRows = buildWatchEditorTreeRows(
                            layers = snapshot.layers,
                            collapsedKeys = collapsedLayerKeys,
                        )
                        items(treeRows, key = { it.layer.key.value }) { row ->
                            val layer = row.layer
                            EditorLayerRow(
                                layer = layer,
                                selected = layer.key == selectedLayer?.key,
                                layerCount = snapshot.layers.size,
                                depth = row.depth,
                                hasChildren = row.hasChildren,
                                expanded = row.expanded,
                                enabled = busyMessage == null,
                                onSelect = {
                                    onSelectLayer(layer.key)
                                    panel = WatchEditorPanel.PROPERTIES
                                },
                                onToggleExpanded = {
                                    val key = layer.key.value
                                    collapsedLayerKeys = if (key in collapsedLayerKeys) {
                                        collapsedLayerKeys - key
                                    } else {
                                        collapsedLayerKeys + key
                                    }
                                },
                                onMove = { onMoveLayer(layer.key, it) },
                                onDuplicate = { onDuplicateLayer(layer.key) },
                                onDelete = { onDeleteLayer(layer.key) },
                            )
                        }
                    }
                }

                WatchEditorPanel.PROPERTIES -> LazyColumn(
                    state = panelListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .flueElasticNestedScroll(panelListState),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    editorStatusItems(busyMessage, errorMessage)
                    if (selectedLayer == null) {
                        item("no_selection") {
                            Text(
                                "请先选择一个图层",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        item("properties") {
                            LayerPropertiesEditor(
                                layer = selectedLayer,
                                enabled = busyMessage == null,
                                onApply = { onApplyLayerAttributes(selectedLayer.key, it) },
                                onImportImage = onImportImage,
                            )
                        }
                    }
                }

                WatchEditorPanel.WATCH -> LazyColumn(
                    state = panelListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .flueElasticNestedScroll(panelListState),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    editorStatusItems(busyMessage, errorMessage)
                    item("watch_summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                snapshot.rootAttributes["name"].orEmpty().ifBlank { "未命名表盘" },
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${snapshot.layers.size} 个图层 · ${snapshot.resourcePaths.size} 个资源",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item("watch_data_permission") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("实时表盘数据", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (bodySensorsPermissionGranted) {
                                        "身体传感器已授权，预览可读取设备提供的心率数据。"
                                    } else {
                                        "心率默认保持不可用；只有点击下方按钮后才会向系统申请权限。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!bodySensorsPermissionGranted) {
                                    OutlinedButton(
                                        onClick = {
                                            bodySensorsPermissionLauncher.launch(
                                                Manifest.permission.BODY_SENSORS,
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("授权身体传感器")
                                    }
                                }
                            }
                        }
                    }
                    item("watch_actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onSaveAsNew,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("另存为新表盘")
                            }
                            OutlinedButton(
                                onClick = onSave,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("保存当前表盘")
                            }
                            OutlinedButton(
                                onClick = onRename,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("重命名")
                            }
                            OutlinedButton(
                                onClick = onImportWatch,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("导入 .watch")
                            }
                            OutlinedButton(
                                onClick = onConvertWatch,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("导入并转换 HSC / WFF")
                            }
                            OutlinedButton(
                                onClick = onExport,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("导出 .watch") }
                        }
                    }
                    item("safety_note") {
                        Text(
                            "未知 XML 属性和节点会原样保留。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

private enum class WatchEditorPanel(val label: String) {
    LAYERS("图层"),
    PROPERTIES("属性"),
    WATCH("表盘"),
}

internal fun shouldHandleWatchEditorTransformEnd(cancelled: Boolean, busy: Boolean): Boolean =
    cancelled || !busy

internal enum class WatchEditorGestureEnd {
    COMMIT,
    ROLLBACK,
}

internal class WatchEditorGestureLifecycle {
    private var active = false

    fun begin() {
        check(!active) { "watch editor gesture transaction is already active" }
        active = true
    }

    fun finish(completedNormally: Boolean): WatchEditorGestureEnd? {
        if (!active) return null
        active = false
        return if (completedNormally) {
            WatchEditorGestureEnd.COMMIT
        } else {
            WatchEditorGestureEnd.ROLLBACK
        }
    }
}

/**
 * Keeps the permanent 1:1 canvas useful without starving the controls below it
 * on small round watches. Larger displays progressively give the canvas more
 * room, while the actual editor coordinate system remains 512 x 512.
 */
internal fun watchEditorPreviewSizeDp(maxWidthDp: Float, maxHeightDp: Float): Float {
    val widthBudget = (maxWidthDp - 16f).coerceAtLeast(1f)
    val desiredHeight = when {
        maxHeightDp < 320f -> maxHeightDp * 0.44f
        maxHeightDp < 480f -> maxHeightDp * 0.46f
        else -> maxHeightDp * 0.50f
    }
    val chromeReserve = when {
        maxHeightDp < 320f -> 132f
        maxHeightDp < 480f -> 156f
        else -> 180f
    }
    val heightBudget = (maxHeightDp - chromeReserve).coerceAtLeast(72f)
    val minimumUsefulCanvas = minOf(widthBudget, 120f)
    return minOf(
        widthBudget,
        heightBudget,
        maxOf(desiredHeight, minimumUsefulCanvas),
        512f,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.editorStatusItems(
    busyMessage: String?,
    errorMessage: String?,
) {
    if (busyMessage != null) {
        item("busy") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
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
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WatchEditorPreview(
    descriptor: LunchWatchFaceDescriptor,
    refreshToken: Int,
    bodySensorsPermissionGranted: Boolean,
    selectedLayer: WatchEditorLayer?,
    transformPreview: WatchEditorTransformPreview?,
    enabled: Boolean,
    onBeginTransform: (WatchEditorLayerKey) -> WatchEditorTransform,
    onUpdateTransform: (WatchEditorLayerKey, WatchEditorTransform) -> Unit,
    onEndTransform: (WatchEditorLayerKey, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var loadError by remember(descriptor.stableKey, refreshToken) { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val latestBegin = rememberUpdatedState(onBeginTransform)
    val latestUpdate = rememberUpdatedState(onUpdateTransform)
    val latestEnd = rememberUpdatedState(onEndTransform)
    val dataProvider = rememberWatchmakerWatchDataProvider(
        active = true,
        permissionRevision = bodySensorsPermissionGranted,
    )
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.Black)
            .onSizeChanged { canvasSize = it },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> WatchmakerWatchFaceView(context) },
            update = { view ->
                view.setWatchDataProvider(dataProvider)
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
        transformPreview?.takeIf { it.key == selectedLayer?.key }?.let { preview ->
            EditorTransformOverlay(
                transform = preview.transform,
                canvasSize = canvasSize,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (enabled && selectedLayer != null) {
            val key = selectedLayer.key
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(key, canvasSize) {
                        val lifecycle = WatchEditorGestureLifecycle()
                        awaitEachGesture {
                            var current: WatchEditorTransform? = null
                            var completedNormally = false
                            try {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                current = latestBegin.value(key)
                                lifecycle.begin()
                                down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.isConsumed }) break

                                    val pan = event.calculatePan()
                                    val zoom = event.calculateZoom()
                                    val rotation = event.calculateRotation()
                                    val base = current
                                    if (
                                        base != null &&
                                        (pan != Offset.Zero || zoom != 1f || rotation != 0f)
                                    ) {
                                        val width = canvasSize.width
                                            .takeIf { it > 0 }
                                            ?.toFloat()
                                            ?: 512f
                                        val height = canvasSize.height
                                            .takeIf { it > 0 }
                                            ?.toFloat()
                                            ?: 512f
                                        val next = base.copy(
                                            x = base.x + pan.x / width * 512f,
                                            y = base.y + pan.y / height * 512f,
                                            scaleX = base.scaleX * zoom,
                                            scaleY = base.scaleY * zoom,
                                            rotation = base.rotation + rotation,
                                        ).sanitized()
                                        current = next
                                        latestUpdate.value(key, next)
                                    }
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) {
                                        completedNormally = true
                                        break
                                    }
                                }
                            } finally {
                                lifecycle.finish(completedNormally)?.let { end ->
                                    // Recomposition, navigation or a competing
                                    // gesture cancels this iteration. Roll it
                                    // back; every ordinary UP commits exactly
                                    // one independent transaction.
                                    latestEnd.value(
                                        key,
                                        end == WatchEditorGestureEnd.ROLLBACK,
                                    )
                                }
                            }
                        }
                    },
            )
        }
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

private data class WatchEditorTransformPreview(
    val key: WatchEditorLayerKey,
    val transform: WatchEditorTransform,
)

private data class EditorSaveResult(
    val descriptor: LunchWatchFaceDescriptor,
    val openedSession: WatchEditorSession?,
    val snapshot: WatchEditorSnapshot,
)

@Composable
private fun EditorTransformOverlay(
    transform: WatchEditorTransform,
    canvasSize: IntSize,
    modifier: Modifier = Modifier,
) {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return
    val x = canvasSize.width / 2f + transform.x / 512f * canvasSize.width
    val y = canvasSize.height / 2f + transform.y / 512f * canvasSize.height
    Canvas(modifier = modifier) {
        val radiusX = (28f * abs(transform.scaleX)).coerceIn(18f, 110f)
        val radiusY = (28f * abs(transform.scaleY)).coerceIn(18f, 110f)
        drawOval(
            color = Color.White.copy(alpha = 0.82f),
            topLeft = Offset(x - radiusX, y - radiusY),
            size = Size(radiusX * 2f, radiusY * 2f),
            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.62f),
            start = Offset(x - radiusX - 8f, y),
            end = Offset(x + radiusX + 8f, y),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White.copy(alpha = 0.62f),
            start = Offset(x, y - radiusY - 8f),
            end = Offset(x, y + radiusY + 8f),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun EditorLayerRow(
    layer: WatchEditorLayer,
    selected: Boolean,
    layerCount: Int,
    depth: Int,
    hasChildren: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onToggleExpanded: () -> Unit,
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
            .heightIn(min = 64.dp)
            .background(container, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(start = (10 + depth * 18).dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hasChildren) {
                IconButton(
                    onClick = onToggleExpanded,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        contentDescription = if (expanded) "折叠子图层" else "展开子图层",
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("${layer.order + 1}. ${layer.type}", fontWeight = FontWeight.Medium)
                layer.id?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                layer.parentId?.let {
                    Text(
                        "父级：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = { onMove(layer.order - 1) },
                enabled = enabled && layer.order > 0,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "上移")
            }
            IconButton(
                onClick = { onMove(layer.order + 1) },
                enabled = enabled && layer.order < layerCount - 1,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "下移")
            }
            IconButton(onClick = onDuplicate, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制")
            }
            IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
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
} + mapOf(
    "anim_scale_x" to "100",
    "anim_scale_y" to "100",
)

private fun attributeLabel(name: String): String = when (name) {
    "x" -> "X 坐标"
    "y" -> "Y 坐标"
    "rotation" -> "旋转角度"
    "opacity" -> "不透明度"
    "anim_scale_x" -> "横向缩放（%）"
    "anim_scale_y" -> "纵向缩放（%）"
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

private fun hasEditorBodySensorsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BODY_SENSORS,
    ) == PackageManager.PERMISSION_GRANTED

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
    "anim_scale_x",
    "anim_scale_y",
    "text",
    "color",
    "path",
)

private val NUMERIC_ATTRIBUTES = setOf(
    "x",
    "y",
    "rotation",
    "opacity",
    "anim_scale_x",
    "anim_scale_y",
)
