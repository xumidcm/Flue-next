package com.flue.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.ui.common.flueElasticNestedScroll
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceType
import com.flue.launcher.watchface.editor.WATCH_EDITOR_EXPRESSION_GROUPS
import com.flue.launcher.watchface.editor.WatchEditorHandle
import com.flue.launcher.watchface.editor.WatchEditorImageCropper
import com.flue.launcher.watchface.editor.WatchEditorLayer
import com.flue.launcher.watchface.editor.WatchEditorLayerKey
import com.flue.launcher.watchface.editor.WatchEditorLiveAttributeApplicator
import com.flue.launcher.watchface.editor.WatchEditorCropAspect
import com.flue.launcher.watchface.editor.WatchEditorCropHandle
import com.flue.launcher.watchface.editor.WatchEditorCropRect
import com.flue.launcher.watchface.editor.WatchEditorSelectionBox
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.editor.WatchEditorSnapshot
import com.flue.launcher.watchface.editor.WatchEditorSourceRect
import com.flue.launcher.watchface.editor.WatchEditorTransform
import com.flue.launcher.watchface.editor.applyWatchEditorHandleDrag
import com.flue.launcher.watchface.editor.buildWatchEditorLayerGeometries
import com.flue.launcher.watchface.editor.buildWatchEditorTreeRows
import com.flue.launcher.watchface.editor.buildWatchEditorWorldMatrices
import com.flue.launcher.watchface.editor.constrainWatchEditorCropRect
import com.flue.launcher.watchface.editor.hitTestWatchEditorLayers
import com.flue.launcher.watchface.editor.insertWatchEditorExpression
import com.flue.launcher.watchface.editor.mapWatchEditorCropToSource
import com.flue.launcher.watchface.editor.moveWatchEditorCropRect
import com.flue.launcher.watchface.editor.resizeWatchEditorCropRect
import com.flue.launcher.watchface.editor.resolveWatchEditorCropHandle
import com.flue.launcher.watchface.editor.resolveWatchEditorHandleHit
import com.flue.launcher.watchface.editor.scaleWatchEditorCropRect
import com.flue.launcher.watchface.editor.watchEditorCropAspectRatio
import com.flue.launcher.watchface.editor.watchEditorDesignFromViewPx
import com.flue.launcher.watchface.editor.watchEditorDesignLengthFromViewPx
import com.flue.launcher.watchface.editor.watchEditorDragDeltaInParentFrame
import com.flue.launcher.watchface.editor.watchEditorFitImageRect
import com.flue.launcher.watchface.editor.watchEditorSelectionBox
import com.flue.launcher.watchface.editor.watchEditorViewPxFromDesign
import com.flue.launcher.watchface.editor.conversion.WatchConversionResult
import com.flue.launcher.watchface.editor.conversion.WatchFormatConverter
import com.flue.launcher.watchface.standard.WATCHMAKER_ID_PREFIX
import com.flue.launcher.watchface.standard.WatchFaceShape
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import com.flue.launcher.watchface.standard.WatchLiveLayerTransform
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceView
import com.flue.launcher.watchface.standard.rememberWatchmakerWatchDataProvider
import com.flue.launcher.watchface.standard.watchFaceShape
import com.flue.launcher.watchface.store.HttpWatchStoreApi
import com.flue.launcher.watchface.store.WatchStoreAuthSession
import com.flue.launcher.watchface.store.WatchStoreAuthStore
import com.flue.launcher.watchface.store.WatchStoreCategory
import com.flue.launcher.watchface.store.WatchStoreHttpException
import com.flue.launcher.watchface.store.WatchStorePayloadTooLargeException
import com.flue.launcher.watchface.store.WatchStoreScreenType
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.hypot

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
    var showStoreUpload by remember { mutableStateOf(false) }
    var pendingSaveName by remember { mutableStateOf("") }
    var saveAsMode by remember { mutableStateOf(true) }
    var transformPreview by remember { mutableStateOf<WatchEditorTransformPreview?>(null) }
    // Bumped whenever attributes change from OUTSIDE the live property fields
    // (undo, canvas drag, image import…) so the drafts reset; live typing keeps
    // its own in-progress state.
    var propertyDraftRevision by remember { mutableIntStateOf(0) }
    var cropRequest by remember { mutableStateOf<Uri?>(null) }

    val liveApplicator = remember(session) {
        session?.let { WatchEditorLiveAttributeApplicator(it) }
    }
    DisposableEffect(liveApplicator) {
        onDispose { liveApplicator?.cancelPending() }
    }

    fun updateSnapshot(
        next: WatchEditorSnapshot,
        preferred: WatchEditorLayerKey? = selectedLayerKey,
        resetPropertyDrafts: Boolean = true,
    ) {
        snapshot = next
        selectedLayerKey = preferred?.takeIf { key -> next.layers.any { it.key == key } }
            ?: next.layers.firstOrNull()?.key
        if (resetPropertyDrafts) propertyDraftRevision++
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
            // Typed-but-unapplied property edits must land before the
            // operation so undo/save observe what the user sees.
            liveApplicator?.flush(
                onApplied = {},
                onError = { errorMessage = it.message ?: "属性更新失败" },
            )
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
                liveApplicator?.flush(
                    onApplied = {},
                    onError = { errorMessage = it.message ?: "属性更新失败" },
                )
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
        if (uri != null && session != null && selectedLayerKey != null) {
            // The import continues in the crop overlay; nothing is written
            // into the draft until the user confirms the crop.
            cropRequest = uri
        }
    }

    fun performCroppedImport(uri: Uri, sourceRect: WatchEditorSourceRect) {
        val active = session ?: return
        val key = selectedLayerKey ?: return
        cropRequest = null
        scope.launch {
            busyMessage = "正在导入图片"
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val bitmap = WatchEditorImageCropper.cropRegion(context.contentResolver, uri, sourceRect)
                    val stream = try {
                        WatchEditorImageCropper.toPngStream(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                    val stem = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.substringBeforeLast('.')
                        ?.takeIf(String::isNotBlank)
                        ?: "image"
                    val resourcePath = active.importResource(stream, "$stem.png")
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

    val blankFaceIsRound = isFlueScreenRound()
    LaunchedEffect(watchFaceId) {
        loading = true
        errorMessage = null
        runCatching {
            withContext(Dispatchers.IO) {
                val opened = if (watchFaceId.isNullOrBlank()) {
                    // 新建表盘的形状跟随当前屏幕（含设置里的手动覆盖），
                    // 不再无条件写 shape="R" 导致方屏表盘被圆形遮罩裁剪。
                    WatchEditorSession.createBlank(context, roundShape = blankFaceIsRound)
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
                            liveApplicator?.flush(
                                onApplied = {},
                                onError = { errorMessage = it.message ?: "属性更新失败" },
                            )
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
                                propertyDraftRevision++
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
                val imageSizeCache = remember(active) {
                    mutableMapOf<String, Pair<Float, Float>?>()
                }
                val imageSizeLookup = remember(active) {
                    { path: String ->
                        imageSizeCache.getOrPut(path) {
                            resolveDraftImageSize(active.draftDirectory, path)
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    WatchEditorScreen(
                        snapshot = current,
                        selectedLayer = selected,
                        previewDescriptor = draftDescriptor(active, current),
                        previewToken = previewToken,
                        transformPreview = transformPreview,
                        propertyDraftRevision = propertyDraftRevision,
                        busyMessage = busyMessage,
                        errorMessage = errorMessage,
                        imageSizeLookup = imageSizeLookup,
                        onBack = ::requestClose,
                        onSelectLayer = { selectedLayerKey = it },
                        onBeginTransform = { key ->
                            // Un-applied typed edits must not fire mid-gesture:
                            // apply them synchronously before the transaction.
                            liveApplicator?.consumePendingChanges()?.let { (pendingKey, changes) ->
                                runCatching { active.updateLayerAttributes(pendingKey, changes) }
                            }
                            val initial = active.beginLayerTransformGesture(key)
                            transformPreview = WatchEditorTransformPreview(key, initial)
                            initial
                        },
                        onUpdateTransform = { key, transform ->
                            if (busyMessage == null) {
                                runCatching {
                                    active.updateLayerTransformPreview(key, transform)
                                    transformPreview = WatchEditorTransformPreview(key, transform.sanitized())
                                }
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
                        onLiveAttributeChange = { key, changes ->
                            liveApplicator?.submit(
                                scope = scope,
                                key = key,
                                changes = changes,
                                onApplied = { next ->
                                    updateSnapshot(next, key, resetPropertyDrafts = false)
                                    previewToken++
                                },
                                onError = { error ->
                                    errorMessage = error.message ?: "属性更新失败"
                                },
                            )
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
                        onUpdateShape = { shapeCode ->
                            runSessionOperation("正在更新表盘形状", operation = { editor ->
                                editor.updateRootAttribute("shape", shapeCode)
                                selectedLayerKey
                            })
                        },
                        onUploadToStore = {
                            if (busyMessage == null) {
                                scope.launch {
                                    // Typed-but-unapplied property edits must land in
                                    // the draft before it is exported for upload.
                                    liveApplicator?.flush(
                                        onApplied = {},
                                        onError = { errorMessage = it.message ?: "属性更新失败" },
                                    )
                                    showStoreUpload = true
                                }
                            }
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
                    cropRequest?.let { uri ->
                        EditorImageCropOverlay(
                            uri = uri,
                            onCancel = { cropRequest = null },
                            onConfirm = { sourceRect -> performCroppedImport(uri, sourceRect) },
                        )
                    }
                    if (showStoreUpload) {
                        EditorStoreUploadOverlay(
                            session = active,
                            snapshot = current,
                            onSnapshotRefreshed = { next -> updateSnapshot(next) },
                            onDismiss = { showStoreUpload = false },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Resolves an image layer's `path` attribute against the draft directory the
 * same way the runtime does (raw path first, then the conventional `images/`
 * folder) and reads only the bitmap bounds.
 */
private fun resolveDraftImageSize(draftDirectory: File, path: String): Pair<Float, Float>? {
    val normalized = path.replace('\\', '/')
    if (normalized.isBlank() || normalized.contains("..") || normalized.startsWith('/')) return null
    val candidates = listOf(File(draftDirectory, normalized), File(draftDirectory, "images/$normalized"))
    val file = candidates.firstOrNull(File::isFile) ?: return null
    return runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { input -> BitmapFactory.decodeStream(input, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth.toFloat() to options.outHeight.toFloat()
        } else {
            null
        }
    }.getOrNull()
}

@Composable
private fun WatchEditorScreen(
    snapshot: WatchEditorSnapshot,
    selectedLayer: WatchEditorLayer?,
    previewDescriptor: LunchWatchFaceDescriptor,
    previewToken: Int,
    transformPreview: WatchEditorTransformPreview?,
    propertyDraftRevision: Int,
    busyMessage: String?,
    errorMessage: String?,
    imageSizeLookup: (String) -> Pair<Float, Float>?,
    onBack: () -> Unit,
    onSelectLayer: (WatchEditorLayerKey?) -> Unit,
    onBeginTransform: (WatchEditorLayerKey) -> WatchEditorTransform,
    onUpdateTransform: (WatchEditorLayerKey, WatchEditorTransform) -> Unit,
    onEndTransform: (WatchEditorLayerKey, Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddLayer: (String) -> Unit,
    onMoveLayer: (WatchEditorLayerKey, Int) -> Unit,
    onDuplicateLayer: (WatchEditorLayerKey) -> Unit,
    onDeleteLayer: (WatchEditorLayerKey) -> Unit,
    onLiveAttributeChange: (WatchEditorLayerKey, Map<String, String?>) -> Unit,
    onImportImage: () -> Unit,
    onSaveAsNew: () -> Unit,
    onSave: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onUpdateShape: (String) -> Unit,
    onUploadToStore: () -> Unit,
    onImportWatch: () -> Unit,
    onConvertWatch: () -> Unit,
) {
    var panel by rememberSaveable { mutableStateOf(WatchEditorPanel.LAYERS) }
    var stage by rememberSaveable { mutableStateOf(WatchEditorStage.NORMAL) }
    // Each editor level keeps its own position. Re-creating one state whenever
    // the tab changes made returning from Properties jump the layer list back
    // to the top, which is especially disorienting on a small watch viewport.
    val layerListState = rememberLazyListState()
    val propertiesListState = rememberLazyListState()
    val watchListState = rememberLazyListState()
    var collapsedLayerKeys by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var scrollToLayerRequest by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val context = LocalContext.current
    var bodySensorsPermissionGranted by remember(context) {
        mutableStateOf(hasEditorBodySensorsPermission(context))
    }
    val bodySensorsPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) {
        bodySensorsPermissionGranted = hasEditorBodySensorsPermission(context)
    }
    val isRound = isFlueScreenRound()
    val treeRows = remember(snapshot.layers, collapsedLayerKeys) {
        buildWatchEditorTreeRows(layers = snapshot.layers, collapsedKeys = collapsedLayerKeys)
    }
    LaunchedEffect(scrollToLayerRequest) {
        val request = scrollToLayerRequest ?: return@LaunchedEffect
        val rowIndex = treeRows.indexOfFirst { it.layer.key.value == request.second }
        if (rowIndex >= 0) {
            val itemsBeforeRows = (if (busyMessage != null) 1 else 0) +
                (if (errorMessage != null) 1 else 0) +
                1 // add_actions
            runCatching { layerListState.animateScrollToItem(itemsBeforeRows + rowIndex) }
        }
    }
    BackHandler(enabled = stage != WatchEditorStage.NORMAL) { stage = WatchEditorStage.NORMAL }

    val previewContent: @Composable (Modifier) -> Unit = { previewModifier ->
        WatchEditorPreview(
            descriptor = previewDescriptor,
            refreshToken = previewToken,
            bodySensorsPermissionGranted = bodySensorsPermissionGranted,
            layers = snapshot.layers,
            selectedLayer = selectedLayer,
            transformPreview = transformPreview,
            enabled = busyMessage == null,
            imageSizeLookup = imageSizeLookup,
            onSelectFromCanvas = { key -> onSelectLayer(key) },
            onCanvasTap = { key ->
                if (key != null) {
                    if (stage == WatchEditorStage.PREVIEW_FULLSCREEN) {
                        stage = WatchEditorStage.NORMAL
                        panel = WatchEditorPanel.PROPERTIES
                    } else if (panel == WatchEditorPanel.LAYERS) {
                        scrollToLayerRequest =
                            (scrollToLayerRequest?.first ?: 0) + 1 to key.value
                    }
                } else if (stage != WatchEditorStage.PREVIEW_FULLSCREEN) {
                    onSelectLayer(null)
                }
            },
            onBeginTransform = onBeginTransform,
            onUpdateTransform = onUpdateTransform,
            onEndTransform = onEndTransform,
            modifier = previewModifier,
        )
    }

    val horizontalListPadding = if (isRound) 24.dp else 10.dp
    val bottomListPadding = if (isRound) 28.dp else 10.dp
    val listContentPadding = PaddingValues(
        start = horizontalListPadding,
        end = horizontalListPadding,
        top = 10.dp,
        bottom = bottomListPadding,
    )
    // 属性面板在普通形态和全屏形态下必须是同一份内容：点「下」箭头不再只是
    // 滚动到属性那一栏，而是真的把属性铺满整块表盘（与「上」的全屏预览对称）。
    val propertiesPanel: @Composable (Modifier, PaddingValues) -> Unit =
        { panelModifier, panelPadding ->
            LazyColumn(
                state = propertiesListState,
                modifier = panelModifier
                    .fillMaxWidth()
                    // 弹性回弹靠 graphicsLayer{translationY} 实现，默认 clip=false，
                    // 回弹时滚动内容会画到上面的固定区（预览 / 标题）上面去。
                    // 在滚动视口边界上裁一刀，位移就永远留在自己的区域里。
                    .clipToBounds()
                    .flueElasticNestedScroll(propertiesListState),
                contentPadding = panelPadding,
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
                            draftResetKey = propertyDraftRevision,
                            enabled = busyMessage == null,
                            onLiveChange = { changes ->
                                onLiveAttributeChange(selectedLayer.key, changes)
                            },
                            onImportImage = onImportImage,
                        )
                    }
                }
            }
        }

    AnimatedContent(
        targetState = stage,
        transitionSpec = { watchEditorContentTransition() },
        label = "watch_editor_stage",
        modifier = Modifier.fillMaxSize(),
    ) { currentStage ->
        when (currentStage) {
            WatchEditorStage.PREVIEW_FULLSCREEN -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                previewContent(
                    Modifier
                        .fillMaxSize()
                        .aspectRatio(1f)
                        .align(Alignment.Center),
                )
                // Keep the exit control inside the round safe area.
                IconButton(
                    onClick = { stage = WatchEditorStage.NORMAL },
                    modifier = if (isRound) {
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    } else {
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    },
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "退出全屏预览",
                        tint = Color.White,
                    )
                }
            }

            WatchEditorStage.PROPERTIES_FULLSCREEN -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                propertiesPanel(
                    Modifier.fillMaxSize(),
                    PaddingValues(
                        start = horizontalListPadding,
                        end = horizontalListPadding,
                        // 顶部让出退出按钮，圆屏再多留一点安全区。
                        top = if (isRound) 52.dp else 44.dp,
                        bottom = bottomListPadding,
                    ),
                )
                IconButton(
                    onClick = { stage = WatchEditorStage.NORMAL },
                    modifier = if (isRound) {
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                    } else {
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    },
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "退出全屏属性",
                    )
                }
            }

            WatchEditorStage.NORMAL -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val previewSize = watchEditorPreviewSizeDp(maxWidth.value, maxHeight.value).dp
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 不透明背景 + zIndex：下面的滚动区带着弹性位移，回弹时不能
                            // 让它的内容透过来或者压在标题栏上面。
                            .zIndex(2f)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(
                                horizontal = if (isRound) 20.dp else 4.dp,
                                vertical = if (isRound) 6.dp else 2.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
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
                        IconButton(
                            onClick = { stage = WatchEditorStage.PREVIEW_FULLSCREEN },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "预览全屏（上）")
                        }
                        IconButton(
                            onClick = {
                                panel = WatchEditorPanel.PROPERTIES
                                stage = WatchEditorStage.PROPERTIES_FULLSCREEN
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "属性全屏（下）")
                        }
                        IconButton(
                            onClick = onUndo,
                            enabled = snapshot.canUndo && busyMessage == null,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = "撤销")
                        }
                        IconButton(
                            onClick = onRedo,
                            enabled = snapshot.canRedo && busyMessage == null,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.Redo, contentDescription = "重做")
                        }
                    }
                    previewContent(
                        Modifier
                            .size(previewSize)
                            .aspectRatio(1f)
                            .zIndex(2f)
                            .align(Alignment.CenterHorizontally),
                    )
                    TabRow(
                        selectedTabIndex = panel.ordinal,
                        modifier = Modifier
                            .zIndex(2f)
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        WatchEditorPanel.entries.forEach { candidate ->
                            Tab(
                                selected = panel == candidate,
                                onClick = { panel = candidate },
                                text = { Text(candidate.label, maxLines = 1) },
                            )
                        }
                    }
                    AnimatedContent(
                        targetState = panel,
                        transitionSpec = { watchEditorContentTransition() },
                        label = "watch_editor_panel",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) { currentPanel ->
                        when (currentPanel) {
                        WatchEditorPanel.LAYERS -> LazyColumn(
                            state = layerListState,
                            modifier = Modifier
                                .fillMaxSize()
                                // 见 propertiesPanel 的注释：弹性位移必须裁在视口内。
                                .clipToBounds()
                                .flueElasticNestedScroll(layerListState),
                            contentPadding = listContentPadding,
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

                        WatchEditorPanel.PROPERTIES ->
                            propertiesPanel(Modifier.fillMaxSize(), listContentPadding)

                        WatchEditorPanel.WATCH -> LazyColumn(
                            state = watchListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .flueElasticNestedScroll(watchListState),
                            contentPadding = listContentPadding,
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
                            item("watch_shape") {
                                EditorWatchShapeCard(
                                    rawShape = snapshot.rootAttributes["shape"],
                                    enabled = busyMessage == null,
                                    onUpdateShape = onUpdateShape,
                                )
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
                                    OutlinedButton(
                                        onClick = onUploadToStore,
                                        enabled = busyMessage == null,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("上传到商店")
                                    }
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
        }
    }
}

private enum class WatchEditorPanel(val label: String) {
    LAYERS("图层"),
    PROPERTIES("属性"),
    WATCH("表盘"),
}

/**
 * 编辑器的三种整屏形态。全屏预览（「上」）和全屏属性（「下」）是对称的：
 * 后者不是滚动到属性那一栏，而是把属性真正铺满整块表盘。
 */
private enum class WatchEditorStage {
    NORMAL,
    PREVIEW_FULLSCREEN,
    PROPERTIES_FULLSCREEN,
}

/**
 * 编辑器里所有形态/面板切换共用的过渡：淡入淡出叠一点缩放，弹簧参数固定为
 * `spring(dampingRatio = 0.9f, stiffness = 400f)`。`sizeTransform = null` 是
 * 故意的——容器尺寸由外层布局决定，让它再动一次只会抖。
 */
private fun watchEditorContentTransition(): ContentTransform {
    val spec = spring<Float>(dampingRatio = 0.9f, stiffness = 400f)
    return ContentTransform(
        targetContentEnter = fadeIn(spec) + scaleIn(animationSpec = spec, initialScale = 0.94f),
        initialContentExit = fadeOut(spec) + scaleOut(animationSpec = spec, targetScale = 1.04f),
        sizeTransform = null,
    )
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

private enum class EditorCanvasGestureMode { NONE, BODY, HANDLE }

/** Touch radius (view dp) for grabbing a selection-frame handle. */
private const val EDITOR_HANDLE_TOUCH_DP = 14f

/** Visual radius (view dp) of a selection-frame handle circle (>= 12dp diameter). */
private const val EDITOR_HANDLE_VISUAL_RADIUS_DP = 7f

/** Distance (view dp) between the top edge and the rotation handle. */
private const val EDITOR_ROTATION_HANDLE_OFFSET_DP = 26f

/** Extra hit slop (view dp) around layer bounds for stubby watch fingers. */
private const val EDITOR_LAYER_HIT_SLOP_DP = 6f

private val EDITOR_SELECTION_STROKE_COLOR = Color(0xFF53D6FF)
private val EDITOR_HANDLE_FILL_COLOR = Color.White
private val EDITOR_HANDLE_STROKE_COLOR = Color(0xFF20262E)

@Composable
private fun WatchEditorPreview(
    descriptor: LunchWatchFaceDescriptor,
    refreshToken: Int,
    bodySensorsPermissionGranted: Boolean,
    layers: List<WatchEditorLayer>,
    selectedLayer: WatchEditorLayer?,
    transformPreview: WatchEditorTransformPreview?,
    enabled: Boolean,
    imageSizeLookup: (String) -> Pair<Float, Float>?,
    onSelectFromCanvas: (WatchEditorLayerKey) -> Unit,
    onCanvasTap: (WatchEditorLayerKey?) -> Unit,
    onBeginTransform: (WatchEditorLayerKey) -> WatchEditorTransform,
    onUpdateTransform: (WatchEditorLayerKey, WatchEditorTransform) -> Unit,
    onEndTransform: (WatchEditorLayerKey, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var loadError by remember(descriptor.stableKey, refreshToken) { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val transformOverrides = transformPreview?.let { mapOf(it.key to it.transform) } ?: emptyMap()
    val geometries = remember(layers, transformOverrides, imageSizeLookup) {
        buildWatchEditorLayerGeometries(layers, imageSizeLookup, transformOverrides)
    }
    val latestGeometries = rememberUpdatedState(geometries)
    val latestSelectedKey = rememberUpdatedState(selectedLayer?.key)
    val latestEnabled = rememberUpdatedState(enabled)
    val latestCanvasSize = rememberUpdatedState(canvasSize)
    val latestSelect = rememberUpdatedState(onSelectFromCanvas)
    val latestTap = rememberUpdatedState(onCanvasTap)
    val latestBegin = rememberUpdatedState(onBeginTransform)
    val latestUpdate = rememberUpdatedState(onUpdateTransform)
    val latestEnd = rememberUpdatedState(onEndTransform)
    val dataProvider = rememberWatchmakerWatchDataProvider(
        active = true,
        permissionRevision = bodySensorsPermissionGranted,
    )
    // 手势期间 watch.xml 只在内存 DOM 里被改写，草稿不会重新落盘，渲染视图也
    // 就不会重新解析。把同一份变换直接推给渲染器，画出来的元素才会和选择框
    // 一起跟着手指走，而不是松手提交后才「跳」过去。
    val liveTransform = remember(transformPreview, layers) {
        val preview = transformPreview ?: return@remember null
        val layer = layers.firstOrNull { it.key == preview.key } ?: return@remember null
        WatchLiveLayerTransform(
            layerIndex = layer.order,
            layerId = layer.id,
            layerType = layer.type,
            x = preview.transform.x,
            y = preview.transform.y,
            scaleX = preview.transform.scaleX,
            scaleY = preview.transform.scaleY,
            rotation = preview.transform.rotation,
        )
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.Black)
            // 选择框是画布的子节点，但 Compose 的 Canvas 绘制默认不裁剪：
            // 贴边图层的角点句柄会画到表盘画布外面去，看起来像层级错乱。
            // 在画布边界上裁一刀，框和句柄就永远留在表盘里。
            .clipToBounds()
            .onSizeChanged { canvasSize = it },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> WatchmakerWatchFaceView(context) },
            update = { view ->
                view.setWatchDataProvider(dataProvider)
                view.setLiveLayerTransform(liveTransform)
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
        // Direct-manipulation surface: hit the layer under the finger, drag it,
        // grab a selection-frame handle, or tap to select / deselect.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val lifecycle = WatchEditorGestureLifecycle()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!latestEnabled.value) return@awaitEachGesture
                        val viewSize = latestCanvasSize.value.width
                            .takeIf { it > 0 }
                            ?.toFloat()
                            ?: size.width.toFloat().coerceAtLeast(1f)
                        val currentGeometries = latestGeometries.value
                        val matrices = buildWatchEditorWorldMatrices(currentGeometries)
                        val downDesignX = watchEditorDesignFromViewPx(down.position.x, viewSize)
                        val downDesignY = watchEditorDesignFromViewPx(down.position.y, viewSize)
                        val handleRadiusDesign = watchEditorDesignLengthFromViewPx(
                            EDITOR_HANDLE_TOUCH_DP.dp.toPx(),
                            viewSize,
                        )
                        val rotationOffsetDesign = watchEditorDesignLengthFromViewPx(
                            EDITOR_ROTATION_HANDLE_OFFSET_DP.dp.toPx(),
                            viewSize,
                        )
                        val hitSlopDesign = watchEditorDesignLengthFromViewPx(
                            EDITOR_LAYER_HIT_SLOP_DP.dp.toPx(),
                            viewSize,
                        )

                        var mode = EditorCanvasGestureMode.NONE
                        var handle: WatchEditorHandle? = null
                        var startBox: WatchEditorSelectionBox? = null
                        var targetKey: WatchEditorLayerKey? = null
                        var targetIndex = -1

                        val selectedKey = latestSelectedKey.value
                        val selectedIndex = currentGeometries.indexOfFirst { it.key == selectedKey }
                        if (selectedIndex >= 0) {
                            val box = watchEditorSelectionBox(
                                currentGeometries[selectedIndex],
                                matrices[selectedIndex],
                            )
                            val hitHandle = resolveWatchEditorHandleHit(
                                box = box,
                                x = downDesignX,
                                y = downDesignY,
                                touchRadiusDesign = handleRadiusDesign,
                                rotationOffsetDesign = rotationOffsetDesign,
                            )
                            if (hitHandle != null) {
                                mode = EditorCanvasGestureMode.HANDLE
                                handle = hitHandle
                                startBox = box
                                targetKey = currentGeometries[selectedIndex].key
                                targetIndex = selectedIndex
                            }
                        }
                        if (mode == EditorCanvasGestureMode.NONE) {
                            val hitIndex = hitTestWatchEditorLayers(
                                geometries = currentGeometries,
                                worldMatrices = matrices,
                                x = downDesignX,
                                y = downDesignY,
                                slopDesign = hitSlopDesign,
                            )
                            if (hitIndex != null) {
                                val hitKey = currentGeometries[hitIndex].key
                                mode = EditorCanvasGestureMode.BODY
                                targetIndex = hitIndex
                                targetKey = hitKey
                                if (hitKey != selectedKey) {
                                    latestSelect.value(hitKey)
                                }
                            }
                        }

                        val touchSlop = viewConfiguration.touchSlop
                        var moved = false
                        var completedNormally = false

                        if (mode == EditorCanvasGestureMode.NONE) {
                            // Empty canvas: a motionless tap clears selection.
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) return@awaitEachGesture
                                event.changes.forEach { change ->
                                    if (
                                        !moved &&
                                        hypot(
                                            change.position.x - down.position.x,
                                            change.position.y - down.position.y,
                                        ) > touchSlop
                                    ) {
                                        moved = true
                                    }
                                    change.consume()
                                }
                                if (event.changes.none { it.pressed }) {
                                    if (!moved) latestTap.value(null)
                                    return@awaitEachGesture
                                }
                            }
                        }

                        val key = targetKey ?: return@awaitEachGesture
                        // A stale key (layer deleted in the same frame) must
                        // not crash the pointer pipeline.
                        val startTransform = runCatching { latestBegin.value(key) }.getOrNull()
                            ?: return@awaitEachGesture
                        var current: WatchEditorTransform? = null
                        try {
                            current = startTransform
                            lifecycle.begin()
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) break

                                val base = current
                                when (mode) {
                                    EditorCanvasGestureMode.BODY -> {
                                        val pan = event.calculatePan()
                                        val zoom = event.calculateZoom()
                                        val rotation = event.calculateRotation()
                                        if (
                                            base != null &&
                                            (pan != Offset.Zero || zoom != 1f || rotation != 0f)
                                        ) {
                                            val designDeltaX =
                                                watchEditorDesignLengthFromViewPx(pan.x, viewSize)
                                            val designDeltaY =
                                                watchEditorDesignLengthFromViewPx(pan.y, viewSize)
                                            val parentDelta = watchEditorDragDeltaInParentFrame(
                                                geometries = currentGeometries,
                                                worldMatrices = matrices,
                                                layerIndex = targetIndex,
                                                deltaX = designDeltaX,
                                                deltaY = designDeltaY,
                                            )
                                            val next = base.copy(
                                                x = base.x + parentDelta.x,
                                                y = base.y + parentDelta.y,
                                                scaleX = base.scaleX * zoom,
                                                scaleY = base.scaleY * zoom,
                                                rotation = base.rotation + rotation,
                                            ).sanitized()
                                            current = next
                                            latestUpdate.value(key, next)
                                        }
                                    }
                                    EditorCanvasGestureMode.HANDLE -> {
                                        val change = event.changes.firstOrNull { it.pressed }
                                        val frame = startBox
                                        val grabbed = handle
                                        if (change != null && frame != null && grabbed != null) {
                                            val next = applyWatchEditorHandleDrag(
                                                handle = grabbed,
                                                box = frame,
                                                startTransform = startTransform,
                                                startX = downDesignX,
                                                startY = downDesignY,
                                                currentX = watchEditorDesignFromViewPx(
                                                    change.position.x,
                                                    viewSize,
                                                ),
                                                currentY = watchEditorDesignFromViewPx(
                                                    change.position.y,
                                                    viewSize,
                                                ),
                                            )
                                            if (next != base) {
                                                current = next
                                                latestUpdate.value(key, next)
                                            }
                                        }
                                    }
                                    EditorCanvasGestureMode.NONE -> Unit
                                }
                                event.changes.forEach { change ->
                                    if (
                                        !moved &&
                                        hypot(
                                            change.position.x - down.position.x,
                                            change.position.y - down.position.y,
                                        ) > touchSlop
                                    ) {
                                        moved = true
                                    }
                                    change.consume()
                                }
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
                            if (completedNormally && !moved) {
                                // A motionless press is a tap: jump to the
                                // layer in the list (or leave fullscreen).
                                latestTap.value(key)
                            }
                        }
                    }
                },
        )
        val selectedGeometryIndex = geometries.indexOfFirst { it.key == selectedLayer?.key }
        if (enabled && selectedGeometryIndex >= 0 && canvasSize.width > 0) {
            val matrices = remember(geometries) { buildWatchEditorWorldMatrices(geometries) }
            EditorSelectionOverlay(
                box = watchEditorSelectionBox(
                    geometries[selectedGeometryIndex],
                    matrices[selectedGeometryIndex],
                ),
                modifier = Modifier.fillMaxSize(),
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

/**
 * The adjustment frame around the selected layer: a 1dp bright outline whose
 * corners scale proportionally, edge midpoints scale a single axis, and a
 * rotation handle floats above the top edge. Handles are white circles with a
 * dark outline and at least a 12dp visual diameter for watch-sized fingers.
 */
@Composable
private fun EditorSelectionOverlay(
    box: WatchEditorSelectionBox,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val viewSize = size.width.coerceAtLeast(1f)
        fun toView(point: com.flue.launcher.watchface.editor.WatchEditorPoint): Offset = Offset(
            watchEditorViewPxFromDesign(point.x, viewSize),
            watchEditorViewPxFromDesign(point.y, viewSize),
        )

        val strokeWidth = 1.dp.toPx()
        val topLeft = toView(box.topLeft)
        val topRight = toView(box.topRight)
        val bottomLeft = toView(box.bottomLeft)
        val bottomRight = toView(box.bottomRight)
        drawLine(EDITOR_SELECTION_STROKE_COLOR, topLeft, topRight, strokeWidth)
        drawLine(EDITOR_SELECTION_STROKE_COLOR, topRight, bottomRight, strokeWidth)
        drawLine(EDITOR_SELECTION_STROKE_COLOR, bottomRight, bottomLeft, strokeWidth)
        drawLine(EDITOR_SELECTION_STROKE_COLOR, bottomLeft, topLeft, strokeWidth)

        val rotationOffsetDesign = watchEditorDesignLengthFromViewPx(
            EDITOR_ROTATION_HANDLE_OFFSET_DP.dp.toPx(),
            viewSize,
        )
        val rotationHandle = toView(box.rotationHandle(rotationOffsetDesign))
        val topMid = toView(box.topMid)
        drawLine(EDITOR_SELECTION_STROKE_COLOR, topMid, rotationHandle, strokeWidth)

        val handleRadius = EDITOR_HANDLE_VISUAL_RADIUS_DP.dp.toPx()
        val handleStroke = 1.5f.dp.toPx()
        val handleCenters = listOf(
            topLeft,
            topRight,
            bottomLeft,
            bottomRight,
            topMid,
            toView(box.bottomMid),
            toView(box.leftMid),
            toView(box.rightMid),
            rotationHandle,
        )
        handleCenters.forEach { center ->
            drawCircle(EDITOR_HANDLE_FILL_COLOR, handleRadius, center)
            drawCircle(
                EDITOR_HANDLE_STROKE_COLOR,
                handleRadius,
                center,
                style = Stroke(width = handleStroke),
            )
        }
    }
}

/**
 * Full-screen crop step between picking an image and writing it into the
 * draft. Free-form by default with 1:1 and physical-screen presets; the frame
 * can be moved, corner-resized and pinch-scaled. Decoding is downsampled so a
 * large camera picture never materialises at full resolution.
 */
@Composable
private fun EditorImageCropOverlay(
    uri: Uri,
    onCancel: () -> Unit,
    onConfirm: (WatchEditorSourceRect) -> Unit,
) {
    val context = LocalContext.current
    val isRound = isFlueScreenRound()
    var preview by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var sourceSize by remember(uri) { mutableStateOf<IntSize?>(null) }
    var decodeError by remember(uri) { mutableStateOf<String?>(null) }
    var aspect by remember(uri) { mutableStateOf(WatchEditorCropAspect.FREE) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    var cropRect by remember(uri) { mutableStateOf<WatchEditorCropRect?>(null) }
    val displayMetrics = context.resources.displayMetrics
    val aspectRatioValue = watchEditorCropAspectRatio(
        aspect,
        displayMetrics.widthPixels,
        displayMetrics.heightPixels,
    )

    LaunchedEffect(uri) {
        runCatching {
            withContext(Dispatchers.IO) {
                val bounds = WatchEditorImageCropper.readBounds(context.contentResolver, uri)
                val bitmap = WatchEditorImageCropper.decodePreview(context.contentResolver, uri)
                bounds to bitmap
            }
        }.onSuccess { (bounds, bitmap) ->
            sourceSize = IntSize(bounds.width, bounds.height)
            preview = bitmap
        }.onFailure { error ->
            decodeError = error.message ?: "图片解码失败"
        }
    }
    // The preview bitmap is intentionally left to the GC: recycling it while a
    // final frame still references the ImageBitmap would crash the render node.
    BackHandler(onBack = onCancel)

    val bitmap = preview
    val fit = if (bitmap != null && areaSize.width > 0 && areaSize.height > 0) {
        watchEditorFitImageRect(
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            areaSize.width.toFloat(),
            areaSize.height.toFloat(),
        )
    } else {
        null
    }
    val minCropSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
    if (fit != null && cropRect == null) {
        cropRect = constrainWatchEditorCropRect(fit, fit, minCropSizePx, aspectRatioValue)
    }
    LaunchedEffect(aspect) {
        val bounds = fit ?: return@LaunchedEffect
        cropRect?.let { existing ->
            cropRect = constrainWatchEditorCropRect(existing, bounds, minCropSizePx, aspectRatioValue)
        }
    }

    val latestCrop = rememberUpdatedState(cropRect)
    val latestFit = rememberUpdatedState(fit)
    val latestRatio = rememberUpdatedState(aspectRatioValue)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow everything so the editor beneath cannot be touched.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .padding(
                horizontal = if (isRound) 18.dp else 8.dp,
                vertical = if (isRound) 14.dp else 6.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "裁剪图片",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(8.dp))
                .onSizeChanged { areaSize = it },
        ) {
            when {
                decodeError != null -> Text(
                    decodeError.orEmpty(),
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                bitmap == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                val handleTouchPx = EDITOR_HANDLE_TOUCH_DP.dp.toPx()
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val rect = latestCrop.value ?: return@awaitEachGesture
                                    val bounds = latestFit.value ?: return@awaitEachGesture
                                    val grabbed = resolveWatchEditorCropHandle(
                                        rect,
                                        down.position.x,
                                        down.position.y,
                                        handleTouchPx,
                                    )
                                    val moveInside = grabbed == null &&
                                        rect.contains(down.position.x, down.position.y)
                                    down.consume()
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.any { it.isConsumed }) break
                                        val pan = event.calculatePan()
                                        val zoom = event.calculateZoom()
                                        val currentRect = latestCrop.value ?: break
                                        val ratio = latestRatio.value
                                        var next = currentRect
                                        if (grabbed != null && pan != Offset.Zero) {
                                            next = resizeWatchEditorCropRect(
                                                rect = next,
                                                handle = grabbed,
                                                deltaX = pan.x,
                                                deltaY = pan.y,
                                                bounds = bounds,
                                                minSize = minCropSizePx,
                                                ratio = ratio,
                                            )
                                        } else if (moveInside && pan != Offset.Zero) {
                                            next = moveWatchEditorCropRect(next, pan.x, pan.y, bounds)
                                        }
                                        if (zoom != 1f) {
                                            next = scaleWatchEditorCropRect(
                                                rect = next,
                                                factor = zoom,
                                                bounds = bounds,
                                                minSize = minCropSizePx,
                                                ratio = ratio,
                                            )
                                        }
                                        if (next != currentRect) cropRect = next
                                        event.changes.forEach { it.consume() }
                                        if (event.changes.none { it.pressed }) break
                                    }
                                }
                            },
                    ) {
                        val rect = cropRect ?: return@Canvas
                        val dim = Color(0x99000000)
                        // Dim everything outside the crop frame.
                        drawRect(dim, Offset.Zero, Size(size.width, rect.top.coerceAtLeast(0f)))
                        drawRect(
                            dim,
                            Offset(0f, rect.bottom),
                            Size(size.width, (size.height - rect.bottom).coerceAtLeast(0f)),
                        )
                        drawRect(
                            dim,
                            Offset(0f, rect.top),
                            Size(rect.left.coerceAtLeast(0f), rect.height),
                        )
                        drawRect(
                            dim,
                            Offset(rect.right, rect.top),
                            Size((size.width - rect.right).coerceAtLeast(0f), rect.height),
                        )
                        drawRect(
                            EDITOR_SELECTION_STROKE_COLOR,
                            Offset(rect.left, rect.top),
                            Size(rect.width, rect.height),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                        val handleRadius = EDITOR_HANDLE_VISUAL_RADIUS_DP.dp.toPx()
                        val handleStroke = 1.5f.dp.toPx()
                        listOf(
                            Offset(rect.left, rect.top),
                            Offset(rect.right, rect.top),
                            Offset(rect.left, rect.bottom),
                            Offset(rect.right, rect.bottom),
                        ).forEach { corner ->
                            drawCircle(EDITOR_HANDLE_FILL_COLOR, handleRadius, corner)
                            drawCircle(
                                EDITOR_HANDLE_STROKE_COLOR,
                                handleRadius,
                                corner,
                                style = Stroke(width = handleStroke),
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WatchEditorCropAspect.entries.forEach { candidate ->
                val selected = aspect == candidate
                if (selected) {
                    Button(onClick = {}, enabled = true) { Text(candidate.label) }
                } else {
                    OutlinedButton(onClick = { aspect = candidate }) { Text(candidate.label) }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isRound) 8.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Close, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("取消")
            }
            Button(
                onClick = {
                    val rect = cropRect
                    val bounds = fit
                    val source = sourceSize
                    if (rect != null && bounds != null && source != null) {
                        onConfirm(
                            mapWatchEditorCropToSource(rect, bounds, source.width, source.height),
                        )
                    }
                },
                enabled = cropRect != null && fit != null && sourceSize != null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("裁剪导入")
            }
        }
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

/**
 * Properties editing with live preview: every keystroke lands in the session
 * through a debounced applier, so the canvas follows within ~150ms and no
 * explicit "apply" press is needed.
 */
@Composable
private fun LayerPropertiesEditor(
    layer: WatchEditorLayer,
    draftResetKey: Int,
    enabled: Boolean,
    onLiveChange: (Map<String, String?>) -> Unit,
    onImportImage: () -> Unit,
) {
    // Drafts survive live applies (draftResetKey stays put) but reset whenever
    // the attributes change from the outside: undo, canvas drags, imports.
    var drafts by remember(layer.key, draftResetKey) {
        mutableStateOf(
            EDITABLE_ATTRIBUTES.associateWith { attribute ->
                val text = layer.attributes[attribute].orEmpty()
                TextFieldValue(text, TextRange(text.length))
            },
        )
    }
    var lastFocusedAttribute by remember(layer.key) { mutableStateOf<String?>(null) }

    fun liveValue(name: String, value: String): String? =
        if (value.isEmpty() && name != "text") null else value

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${layer.type} 属性", fontWeight = FontWeight.SemiBold)
        EDITABLE_ATTRIBUTES.forEach { attribute ->
            OutlinedTextField(
                value = drafts[attribute] ?: TextFieldValue(""),
                onValueChange = { value ->
                    val limited = if (value.text.length > 512) {
                        value.copy(text = value.text.take(512))
                    } else {
                        value
                    }
                    drafts = drafts + (attribute to limited)
                    onLiveChange(mapOf(attribute to liveValue(attribute, limited.text)))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (state.isFocused) lastFocusedAttribute = attribute
                    },
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
        ExpressionReferencePanel(
            enabled = enabled,
            onInsert = { tag ->
                val target = lastFocusedAttribute ?: "text"
                val currentValue = drafts[target] ?: TextFieldValue("")
                val (newText, cursor) = insertWatchEditorExpression(
                    text = currentValue.text,
                    selectionStart = currentValue.selection.start,
                    selectionEnd = currentValue.selection.end,
                    tag = tag,
                )
                val limited = newText.take(512)
                val newValue = TextFieldValue(
                    text = limited,
                    selection = TextRange(cursor.coerceAtMost(limited.length)),
                )
                drafts = drafts + (target to newValue)
                onLiveChange(mapOf(target to liveValue(target, newValue.text)))
            },
        )
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

/**
 * Collapsible quick reference of common Watchmaker tags. Tapping an entry
 * inserts the tag at the cursor of the most recently focused property field.
 */
@Composable
private fun ExpressionReferencePanel(
    enabled: Boolean,
    onInsert: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "表达式参考",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (expanded) "折叠表达式参考" else "展开表达式参考",
                )
            }
            if (expanded) {
                Text(
                    "点击任意条目，插入到当前聚焦的属性输入框光标处。",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val twoColumns = maxWidth >= 300.dp
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        WATCH_EDITOR_EXPRESSION_GROUPS.forEach { group ->
                            Text(
                                group.title,
                                modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val columns = if (twoColumns) 2 else 1
                            group.entries.chunked(columns).forEach { rowEntries ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    rowEntries.forEach { entry ->
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable(enabled = enabled) { onInsert(entry.tag) }
                                                .padding(horizontal = 6.dp, vertical = 5.dp),
                                        ) {
                                            Text(
                                                entry.tag,
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                entry.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (rowEntries.size < columns) {
                                            repeat(columns - rowEntries.size) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 表盘级形状开关。存量表盘曾被无条件烙上 shape="R"，方屏表盘因此始终带着
 * 圆形遮罩且没有任何入口可改；这里直接读写根节点的 shape 属性，改完由
 * runSessionOperation 刷新预览。
 */
@Composable
private fun EditorWatchShapeCard(
    rawShape: String?,
    enabled: Boolean,
    onUpdateShape: (String) -> Unit,
) {
    val style = LauncherTheme.style
    val currentShape = watchFaceShape(rawShape)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("表盘形状", fontWeight = FontWeight.SemiBold)
            Text(
                "圆形表盘渲染时会按圆形裁剪；方形表盘保留完整方形画布。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WATCH_SHAPE_OPTIONS.forEach { (code, label) ->
                    val selected = when (code) {
                        "R" -> currentShape == WatchFaceShape.ROUND
                        else -> currentShape == WatchFaceShape.SQUARE
                    }
                    if (selected) {
                        Button(
                            onClick = {},
                            enabled = true,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { onUpdateShape(code) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    }
                }
            }
            if (currentShape != WatchFaceShape.ROUND && currentShape != WatchFaceShape.SQUARE) {
                Text(
                    "当前 shape=\"${rawShape.orEmpty().ifBlank { "未设置" }}\"，点击上方按钮即可改为圆形或方形。",
                    style = MaterialTheme.typography.labelSmall,
                    color = style.secondaryTextColor,
                )
            }
        }
    }
}

/**
 * 上传到商店 overlay。三个阶段：登录/注册 → 填写元数据（分类从服务动态拉取，
 * 屏幕形状默认跟随表盘 shape）→ 上传。exportWatch 的产物只在本次上传期间
 * 存在于 cacheDir；密码只在内存中经手，绝不落盘。
 */
@Composable
private fun EditorStoreUploadOverlay(
    session: WatchEditorSession,
    snapshot: WatchEditorSnapshot,
    onSnapshotRefreshed: (WatchEditorSnapshot) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRound = isFlueScreenRound()
    val style = LauncherTheme.style
    val authStore = remember { WatchStoreAuthStore(context) }
    val api = remember { HttpWatchStoreApi() }

    var auth by remember { mutableStateOf<WatchStoreAuthSession?>(null) }
    var authChecked by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var uploadPercent by remember { mutableStateOf<Int?>(null) }
    var name by remember {
        mutableStateOf(snapshot.rootAttributes["name"].orEmpty().ifBlank { "Flue Watch" }.take(100))
    }
    var description by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf<List<WatchStoreCategory>?>(null) }
    var categoriesError by remember { mutableStateOf<String?>(null) }
    var categoriesRetryToken by remember { mutableIntStateOf(0) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var screenType by remember {
        mutableStateOf(
            when (watchFaceShape(snapshot.rootAttributes["shape"])) {
                WatchFaceShape.ROUND -> WatchStoreScreenType.ROUND
                WatchFaceShape.SQUARE -> WatchStoreScreenType.SQUARE
                WatchFaceShape.RECTANGULAR -> WatchStoreScreenType.RECTANGULAR
                WatchFaceShape.UNKNOWN ->
                    if (isRound) WatchStoreScreenType.ROUND else WatchStoreScreenType.SQUARE
            },
        )
    }

    LaunchedEffect(Unit) {
        auth = withContext(Dispatchers.IO) { authStore.read() }
        authChecked = true
    }

    LaunchedEffect(auth, categoriesRetryToken) {
        if (auth == null || categories != null) return@LaunchedEffect
        categoriesError = null
        runCatching { withContext(Dispatchers.IO) { api.getCategories() } }
            .onSuccess { list ->
                categories = list
                if (selectedCategoryId == null) selectedCategoryId = list.firstOrNull()?.id
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                categoriesError = watchStoreUploadErrorMessage(error)
            }
    }

    fun authenticate(register: Boolean) {
        if (busy != null) return
        val cleanUsername = username.trim()
        if (!cleanUsername.matches(Regex("^[A-Za-z0-9_]{3,50}$"))) {
            formError = "用户名需要 3-50 位字母、数字或下划线"
            return
        }
        if (password.length < 6) {
            formError = "密码至少需要 6 位"
            return
        }
        busy = if (register) "正在注册" else "正在登录"
        formError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val result = if (register) {
                        api.register(cleanUsername, password)
                    } else {
                        api.login(cleanUsername, password)
                    }
                    authStore.write(result.token, result.username)
                }
            }.onSuccess { stored ->
                password = ""
                auth = stored
            }.onFailure { error ->
                if (error is CancellationException) throw error
                formError = watchStoreAuthErrorMessage(error, register)
            }
            busy = null
        }
    }

    fun upload() {
        if (busy != null) return
        val activeAuth = auth ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            formError = "请填写表盘名称"
            return
        }
        if (description.isBlank()) {
            formError = "请填写表盘描述"
            return
        }
        val categoryId = selectedCategoryId
        if (categoryId == null) {
            formError = "请选择一个分类"
            return
        }
        busy = "正在导出表盘包"
        formError = null
        uploadPercent = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("flue_store_upload_", ".watch", context.cacheDir)
                    try {
                        session.exportWatch(archive)
                        if (archive.length() > HttpWatchStoreApi.MAX_UPLOAD_BYTES) {
                            throw WatchStorePayloadTooLargeException(HttpWatchStoreApi.MAX_UPLOAD_BYTES)
                        }
                        busy = "正在上传"
                        api.uploadWatchFace(
                            token = activeAuth.token,
                            file = archive,
                            name = trimmedName,
                            description = description.trim(),
                            screenType = screenType,
                            categoryId = categoryId,
                        ) { sent, total ->
                            val percent = ((sent * 100L) / total.coerceAtLeast(1L))
                                .toInt()
                                .coerceIn(0, 100)
                            if (uploadPercent != percent) uploadPercent = percent
                        }
                        session.snapshot()
                    } finally {
                        archive.delete()
                    }
                }
            }.onSuccess { refreshed ->
                onSnapshotRefreshed(refreshed)
                Toast.makeText(context, "已上传到表盘商店", Toast.LENGTH_SHORT).show()
                onDismiss()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (error is WatchStoreHttpException && error.statusCode == 401) {
                    // 服务器判定令牌失效比本地过期时间更权威：清掉并回到登录页。
                    authStore.clear()
                    auth = null
                    formError = "登录已过期，请重新登录后再上传"
                } else {
                    formError = watchStoreUploadErrorMessage(error)
                }
            }
            busy = null
            uploadPercent = null
        }
    }

    // 上传中禁止返回键关闭，避免协程随组合一起被取消而中断传输。
    BackHandler { if (busy == null) onDismiss() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 吞掉所有触摸，下层编辑器不可再被点到（与裁剪 overlay 一致）。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .padding(
                horizontal = if (isRound) 22.dp else 10.dp,
                vertical = if (isRound) 14.dp else 6.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { if (busy == null) onDismiss() },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭上传")
            }
            Text(
                "上传到商店",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.size(40.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            busy?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        message + (uploadPercent?.let { " $it%" }.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            formError?.let { message ->
                Text(
                    message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val activeAuth = auth
            when {
                !authChecked -> Text(
                    "正在检查登录状态…",
                    style = MaterialTheme.typography.bodySmall,
                    color = style.secondaryTextColor,
                )
                activeAuth == null -> {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.take(50) },
                        label = { Text("用户名") },
                        singleLine = true,
                        enabled = busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(128) },
                        label = { Text("密码") },
                        singleLine = true,
                        enabled = busy == null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { authenticate(register = false) },
                            enabled = busy == null,
                            modifier = Modifier.weight(1f),
                        ) { Text("登录") }
                        OutlinedButton(
                            onClick = { authenticate(register = true) },
                            enabled = busy == null,
                            modifier = Modifier.weight(1f),
                        ) { Text("注册") }
                    }
                    Text(
                        "注册即创建表盘商店账号（用户名 3-50 位字母数字下划线，密码至少 6 位）。" +
                            "密码只用于本次登录，不会保存在手表上。",
                        style = MaterialTheme.typography.labelSmall,
                        color = style.secondaryTextColor,
                    )
                }
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已登录：${activeAuth.username}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = style.secondaryTextColor,
                        )
                        TextButton(
                            onClick = {
                                if (busy == null) {
                                    authStore.clear()
                                    auth = null
                                }
                            },
                        ) { Text("退出登录", color = style.accentColor) }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(100) },
                        label = { Text("表盘名称（必填）") },
                        singleLine = true,
                        enabled = busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(2000) },
                        label = { Text("描述（必填）") },
                        minLines = 2,
                        enabled = busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("屏幕形状", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        STORE_SCREEN_TYPE_OPTIONS.forEach { (candidate, label) ->
                            if (screenType == candidate) {
                                Button(onClick = {}, enabled = true) { Text(label) }
                            } else {
                                OutlinedButton(
                                    onClick = { screenType = candidate },
                                    enabled = busy == null,
                                ) { Text(label) }
                            }
                        }
                    }
                    Text("分类", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    when {
                        categoriesError != null -> {
                            Text(
                                categoriesError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(
                                onClick = { categoriesRetryToken++ },
                                enabled = busy == null,
                            ) { Text("重新加载分类") }
                        }
                        categories == null -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "正在加载分类…",
                                style = MaterialTheme.typography.bodySmall,
                                color = style.secondaryTextColor,
                            )
                        }
                        categories.orEmpty().isEmpty() -> Text(
                            "商店暂时没有可用分类，稍后再试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = style.secondaryTextColor,
                        )
                        else -> categories.orEmpty().forEach { category ->
                            val selected = category.id == selectedCategoryId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (selected) style.selectedCardColor else style.cardColor,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable(enabled = busy == null) {
                                        selectedCategoryId = category.id
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    category.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = style.bodyColor,
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = style.accentColor,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { upload() },
                        enabled = busy == null &&
                            name.isNotBlank() &&
                            description.isNotBlank() &&
                            selectedCategoryId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("上传当前表盘")
                    }
                    Text(
                        "上传前会自动导出当前草稿为 .watch（上限 50MB）；预览图由商店从表盘包中自动生成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = style.secondaryTextColor,
                    )
                }
            }
        }
    }
}

/** 把商店写接口的失败翻译成给用户看的人话。 */
private fun watchStoreUploadErrorMessage(error: Throwable): String = when {
    error is WatchStoreHttpException && error.statusCode == 401 ->
        "登录已过期，请重新登录"
    error is WatchStoreHttpException && error.statusCode == 400 ->
        error.detail?.let { "内容被服务器拒绝：$it" }
            ?: "内容被服务器拒绝，请检查名称、描述和分类"
    error is WatchStoreHttpException && error.statusCode == 413 ->
        "表盘包超过 50MB 上限，请压缩图片资源后再试"
    error is WatchStoreHttpException && error.statusCode == 429 ->
        "操作太频繁，请等一分钟后再试"
    error is WatchStoreHttpException ->
        "商店服务返回了错误（${error.statusCode}）" +
            (error.detail?.let { "：$it" } ?: "，请稍后再试")
    error is WatchStorePayloadTooLargeException ->
        "表盘包超过 50MB 上限，请压缩图片资源后再试"
    error is IllegalArgumentException || error is IllegalStateException ->
        error.message ?: "上传失败"
    else ->
        error.message?.takeIf(String::isNotBlank)?.let { "网络请求失败：$it" }
            ?: "网络请求失败，请检查网络后重试"
}

private fun watchStoreAuthErrorMessage(error: Throwable, register: Boolean): String = when {
    error is WatchStoreHttpException && error.statusCode == 401 ->
        "用户名或密码不正确"
    error is WatchStoreHttpException && error.statusCode == 400 ->
        error.detail?.let { "服务器拒绝了提交：$it" }
            ?: if (register) {
                "用户名或密码不符合要求（用户名 3-50 位字母数字下划线，密码至少 6 位）"
            } else {
                "用户名或密码不正确"
            }
    error is WatchStoreHttpException && error.statusCode == 409 ->
        "该用户名已被注册，换一个试试"
    error is WatchStoreHttpException && error.statusCode == 429 ->
        "尝试太频繁，请等一分钟后再试"
    else -> watchStoreUploadErrorMessage(error)
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

/** 根节点 shape 属性的两个可写值；解析端把 R/S 归一化为圆形/方形。 */
private val WATCH_SHAPE_OPTIONS = listOf(
    "R" to "圆形",
    "S" to "方形",
)

private val STORE_SCREEN_TYPE_OPTIONS = listOf(
    WatchStoreScreenType.ROUND to "圆形",
    WatchStoreScreenType.SQUARE to "方形",
    WatchStoreScreenType.RECTANGULAR to "长方形",
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
