package com.flue.launcher

import com.flue.launcher.i18n.FlueComponentActivity

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Slider
import androidx.compose.ui.graphics.toArgb
import java.util.Locale
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.flue.launcher.ui.settings.miuix.MiuixLoadingIndicator
import com.flue.launcher.ui.settings.miuix.MiuixIcons as FlueMiuixIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Redo
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.ui.common.flueElasticNestedScroll
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.common.InternalFileBrowserHost
import com.flue.launcher.ui.common.InternalFileBrowserRequest
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceType
import com.flue.launcher.watchface.editor.WATCH_EDITOR_EXPRESSION_GROUPS
import com.flue.launcher.watchface.editor.WatchCanvasSize
import com.flue.launcher.watchface.editor.WatchEditorHandle
import com.flue.launcher.watchface.editor.WatchEditorImageCropper
import com.flue.launcher.watchface.editor.WatchEditorImageSourceInfo
import com.flue.launcher.watchface.editor.WatchEditorLayer
import com.flue.launcher.watchface.editor.WatchEditorLayerKey
import com.flue.launcher.watchface.editor.WatchEditorTreeRow
import com.flue.launcher.watchface.editor.WatchEditorLiveAttributeApplicator
import com.flue.launcher.watchface.editor.WatchEditorCanvasMapping
import com.flue.launcher.watchface.editor.WatchEditorCropAspect
import com.flue.launcher.watchface.editor.WatchEditorCropHandle
import com.flue.launcher.watchface.editor.WatchEditorCropRect
import com.flue.launcher.watchface.editor.WatchEditorSelectionBox
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.editor.WatchEditorSnapshot
import com.flue.launcher.watchface.editor.WatchEditorSourceRect
import com.flue.launcher.watchface.editor.WatchEditorSnapping
import com.flue.launcher.watchface.editor.WatchEditorTransform
import com.flue.launcher.watchface.editor.WatchEditorVideoFrames
import com.flue.launcher.watchface.editor.WatchEditorVideoImportOptions
import com.flue.launcher.watchface.editor.WatchEditorVideoImporter
import com.flue.launcher.watchface.editor.WatchEditorVideoInfo
import com.flue.launcher.watchface.editor.applyWatchEditorHandleDrag
import com.flue.launcher.watchface.editor.buildWatchEditorLayerGeometries
import com.flue.launcher.watchface.editor.buildWatchEditorTreeRows
import com.flue.launcher.watchface.editor.buildWatchEditorWorldMatrices
import com.flue.launcher.watchface.editor.constrainWatchEditorCropRect
import com.flue.launcher.watchface.editor.hitTestWatchEditorLayer
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
import com.flue.launcher.watchface.standard.MAX_WATCH_OUTLINE_SIZE
import com.flue.launcher.watchface.standard.WATCHMAKER_ID_PREFIX
import com.flue.launcher.watchface.standard.WatchFaceShape
import com.flue.launcher.watchface.standard.WatchOutlineMode
import com.flue.launcher.watchface.standard.WatchmakerGeometry
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceView
import com.flue.launcher.watchface.standard.hasHeartRatePermission
import com.flue.launcher.watchface.standard.rememberWatchmakerWatchDataProvider
import com.flue.launcher.watchface.standard.requiredHeartRatePermission
import com.flue.launcher.watchface.standard.watchOutlineDefaultSize
import com.flue.launcher.watchface.standard.watchOutlineMode
import com.flue.launcher.watchface.standard.watchFaceShape
import com.flue.launcher.watchface.store.HttpWatchStoreApi
import com.flue.launcher.watchface.store.WatchStoreAuthSession
import com.flue.launcher.watchface.store.WatchStoreAuthStore
import com.flue.launcher.watchface.store.WatchStoreCategory
import com.flue.launcher.watchface.store.WatchStoreCredentialStore
import com.flue.launcher.watchface.store.WatchStoreHttpException
import com.flue.launcher.watchface.store.WatchStorePayloadTooLargeException
import com.flue.launcher.watchface.store.WatchStoreScreenType
import com.flue.launcher.watchface.store.WatchStoreRepository
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class WatchEditorActivity : FlueComponentActivity() {
    private var restoreRecentsExclusionOnExit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 仅在用户原本隐藏后台卡片时做临时覆盖；不写回偏好。原本就显示的
        // 情况完全不动，退出编辑器后再恢复原来的隐藏状态。
        if (RecentsVisibility.readPreference(applicationContext)) {
            RecentsVisibility.apply(this, enabled = false)
            restoreRecentsExclusionOnExit = true
        }
        val watchFaceId = intent.getStringExtra(EXTRA_WATCH_FACE_ID)
        setContent {
            val vm: LauncherViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalScale by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShapeOverride by vm.screenShapeOverride.collectAsStateWithLifecycle()
            // 不传 screenShape 时编辑器里的 isFlueScreenRound() 会退化成系统
            // 判定，用户在设置里手动指定的屏幕形状在本页完全失效。
            WatchLauncherTheme(
                themeMode = themeMode,
                uiStyle = uiStyle,
                screenShape = screenShapeOverride,
            ) {
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

    override fun onDestroy() {
        if (restoreRecentsExclusionOnExit) {
            RecentsVisibility.apply(this, enabled = true)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WATCH_FACE_ID = "watch_editor_watch_face_id"
        const val EXTRA_INSTALLED_WATCH_FACE_ID = "watch_editor_installed_watch_face_id"
    }
}

/**
 * 重新生成的 preview.jpg 边长。
 *
 * 与 WatchMaker 设计空间同为 512：更大只是徒增包体（预览图在列表里最多显示
 * 一两百 dp），更小在大屏表盘列表里会发虚。
 */
private const val PREVIEW_IMAGE_SIZE_PX = 512

private data class EditorImageImportRequest(
    val uri: Uri,
    val sourceInfo: WatchEditorImageSourceInfo,
    val session: WatchEditorSession,
    val layerKey: WatchEditorLayerKey,
)

private data class EditorVideoImportRequest(
    val uri: Uri,
    val info: WatchEditorVideoInfo,
    val session: WatchEditorSession,
    val layerKey: WatchEditorLayerKey,
)

private enum class EditorInternalImportKind {
    IMAGE,
    IMAGE_SEQUENCE,
    VIDEO,
    FONT,
    BITMAP_FONT,
    EXPORT_DIRECTORY,
    WATCH,
    CONVERSION,
}

@Composable
private fun WatchEditorRoute(
    watchFaceId: String?,
    vm: LauncherViewModel,
    onInstalled: (WatchFaceDescriptor) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<WatchEditorSession?>(null) }
    var snapshot by remember { mutableStateOf<WatchEditorSnapshot?>(null) }
    var selectedLayerKey by remember { mutableStateOf<WatchEditorLayerKey?>(null) }
    var selectionGeneration by remember { mutableIntStateOf(0) }
    var previewToken by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showUploadSaveRequiredDialog by remember { mutableStateOf(false) }
    var openUploadAfterSave by remember { mutableStateOf(false) }
    var showStoreUpload by remember { mutableStateOf(false) }
    var pendingSaveName by remember { mutableStateOf("") }
    // 作者信息（老师指定）：保存时若用户没改过，自动填已登录账户名，
    // 未登录填 "flue"；改过就按用户输入原样写入。
    var pendingSaveAuthor by remember { mutableStateOf("") }
    var pendingAuthorModified by remember { mutableStateOf(false) }
    var saveAsMode by remember { mutableStateOf(true) }
    var transformPreview by remember { mutableStateOf<WatchEditorTransformPreview?>(null) }
    // A transform writes its history entry asynchronously after UP. Keep its
    // transaction exclusive until that write (and its draft flush) finishes,
    // otherwise the next pointer-down can replace the session's active
    // gesture before the previous coroutine commits it.
    var transformCommitInFlight by remember(session) { mutableStateOf(false) }
    // The renderer keeps the gesture's final live transform until the freshly
    // written watch.xml has actually been parsed and installed. Clearing it at
    // IO completion is one frame too early and exposes the old source geometry.
    var pendingTransformRefreshToken by remember(session) { mutableStateOf<Int?>(null) }
    val transformGate = remember(session) { WatchEditorTransformGate() }
    // Bumped whenever attributes change from OUTSIDE the live property fields
    // (undo, canvas drag, image import…) so the drafts reset; live typing keeps
    // its own in-progress state.
    var propertyDraftRevision by remember { mutableIntStateOf(0) }
    var cropRequest by remember { mutableStateOf<EditorImageImportRequest?>(null) }
    var videoRequest by remember { mutableStateOf<EditorVideoImportRequest?>(null) }
    var videoConversionJob by remember { mutableStateOf<Job?>(null) }
    var videoConversionProgress by remember { mutableStateOf(0f) }
    var internalFileBrowserRequest by remember { mutableStateOf<InternalFileBrowserRequest?>(null) }
    // 画布渲染 View：保存 / 上传前用它离屏重渲 preview.jpg 写回草稿。
    var previewRenderer by remember { mutableStateOf<WatchmakerWatchFaceView?>(null) }

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
        val resolvedSelection = preferred?.takeIf { key -> next.layers.any { it.key == key } }
            ?: next.layers.firstOrNull()?.key
        if (selectedLayerKey != resolvedSelection) selectionGeneration++
        selectedLayerKey = resolvedSelection
        if (resetPropertyDrafts) propertyDraftRevision++
    }

    fun selectLayer(key: WatchEditorLayerKey?) {
        if (selectedLayerKey != key) selectionGeneration++
        selectedLayerKey = key
    }

    /**
     * 用当前画布重渲一张 preview.jpg 写回草稿。
     *
     * 表盘列表和商店都读包内 watch.xml 旁边的 preview.jpg，那张图是原作者
     * 打包时带的；不重写它，改完保存甚至上传后看到的都还是旧预览（老师报的
     * 问题）。必须在 saveCurrentRevision / exportWatch 之前调。
     *
     * 截图走 renderFaceBitmap（离屏、不依赖 View 上屏），失败就静默跳过：
     * 预览图刷新不该阻断保存本身。
     */
    suspend fun refreshDraftPreview(active: WatchEditorSession) {
        val renderer = previewRenderer ?: return
        // 渲染必须在主线程（读 View 持有的解码缓存），压缩再切 IO。
        val bitmap = withContext(Dispatchers.Main.immediate) {
            runCatching { renderer.renderFaceBitmap(PREVIEW_IMAGE_SIZE_PX) }.getOrNull()
        } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, stream)
                active.writePreviewImage(stream.toByteArray())
            }
            bitmap.recycle()
        }
    }

    fun runSessionOperation(
        status: String,
        operation: (WatchEditorSession) -> WatchEditorLayerKey? = { null },
        onComplete: (() -> Unit)? = null,
    ) {
        val active = session ?: return
        if (busyMessage != null || transformGate.isBusy()) return
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
                onError = { errorMessage = it.message ?: context.getString(R.string.watch_editor_error_property_update) },
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
                errorMessage = context.localizedEditorError(R.string.watch_editor_error_operation, error)
            }
            busyMessage = null
        }
    }

    val exportDocument = rememberLauncherForActivityResult(CreateDocument("application/zip")) { uri ->
        val active = session
        if (uri != null && active != null) {
            scope.launch {
        busyMessage = context.getString(R.string.watch_editor_status_exporting)
                errorMessage = null
                liveApplicator?.flush(
                    onApplied = {},
            onError = { errorMessage = it.message ?: context.getString(R.string.watch_editor_error_property_update) },
                )
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val temporary = File.createTempFile("flue_watch_export_", ".watch", context.cacheDir)
                        try {
                            active.exportWatch(temporary)
                            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                                temporary.inputStream().use { input -> input.copyTo(output) }
                } ?: error(context.getString(R.string.watch_editor_error_export_destination))
                            active.snapshot()
                        } finally {
                            temporary.delete()
                        }
                    }
                }
                result.onSuccess { next ->
                    updateSnapshot(next)
            Toast.makeText(context, context.getString(R.string.watch_editor_toast_exported), Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
            errorMessage = context.localizedEditorError(R.string.watch_editor_error_export, error)
                }
                busyMessage = null
            }
        }
    }

    fun handleFontUri(uri: Uri?) {
        val active = session
        val key = selectedLayerKey
        if (uri == null || active == null || key == null) return
        scope.launch {
        busyMessage = context.getString(R.string.watch_editor_status_importing_font)
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val rawName = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.takeIf(String::isNotBlank)
                        ?: "font.ttf"
                    val fileName = if (rawName.contains('.')) rawName else "$rawName.ttf"
                    val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException(context.getString(R.string.watch_editor_error_read_font))
                    // 字体归档到 fonts/ 目录（与安装器/字体索引的分类一致），
                    // 图层 font 属性写 basename，渲染端 WatchFontIndex 会解析。
                    val resourcePath = active.importResource(stream, fileName, directory = "fonts")
                    active.updateLayerAttribute(key, "font", resourcePath.substringAfterLast('/'))
                    active.flushDraftForPreview()
                    active.snapshot()
                }
            }.onSuccess { next ->
                updateSnapshot(next, key)
                previewToken++
            }.onFailure { error ->
                errorMessage = context.localizedEditorError(R.string.watch_editor_error_font_import, error)
            }
            busyMessage = null
        }
    }

    fun performImageImport(
        request: EditorImageImportRequest,
        sourceRect: WatchEditorSourceRect?,
    ) {
        val active = request.session
        val key = request.layerKey
        if (session !== active) {
            cropRequest = null
            return
        }
        cropRequest = null
        scope.launch {
        busyMessage = context.getString(R.string.watch_editor_status_importing_image)
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val resourcePath = if (sourceRect == null) {
                        val stream = context.contentResolver.openInputStream(request.uri)
                    ?: throw IllegalStateException(context.getString(R.string.watch_editor_error_read_image))
                        // Lossless path: raw GIFs always come here, as do static
                        // images whose crop frame was never changed.
                        active.importResource(stream, request.sourceInfo.importName)
                    } else {
                        val bitmap = WatchEditorImageCropper.cropRegion(
                            context.contentResolver,
                            request.uri,
                            sourceRect,
                        )
                        val stream = try {
                            WatchEditorImageCropper.toPngStream(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                        val stem = request.sourceInfo.displayName
                            .substringBeforeLast('.')
                            .takeIf(String::isNotBlank)
                            ?: "image"
                        active.importResource(stream, "$stem.png")
                    }
                    active.updateLayerAttribute(key, "path", resourcePath)
                    active.flushDraftForPreview()
                    active.snapshot()
                }
            }.onSuccess { next ->
                updateSnapshot(next, key)
                previewToken++
            }.onFailure { error ->
                errorMessage = context.localizedEditorError(R.string.watch_editor_error_image_import, error)
            }
            busyMessage = null
        }
    }

    fun displayNameForUri(uri: Uri): String = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "font.bin" }

    fun handleBitmapFontUris(uris: List<Uri>) {
        val active = session
        val key = selectedLayerKey
        if (uris.isEmpty() || active == null || key == null) return
        scope.launch {
            busyMessage = context.getString(R.string.watch_editor_status_importing_font)
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val files = uris.map { uri ->
                        val name = displayNameForUri(uri)
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error(context.getString(R.string.watch_editor_error_read_font))
                        name to bytes
                    }
                    active.importBitmapFont(files, key)
                    active.flushDraftForPreview()
                    active.snapshot()
                }
            }.onSuccess { next ->
                updateSnapshot(next, key)
                previewToken++
            }.onFailure { error ->
                errorMessage = context.localizedEditorError(R.string.watch_editor_error_font_import, error)
            }
            busyMessage = null
        }
    }
    val bitmapFontPicker = rememberLauncherForActivityResult(OpenMultipleDocuments(), ::handleBitmapFontUris)

    fun exportToDirectory(directory: File) {
        val active = session ?: return
        val current = snapshot ?: return
        scope.launch {
            busyMessage = context.getString(R.string.watch_editor_status_exporting)
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    require(directory.isDirectory && directory.canWrite()) {
                        context.getString(R.string.watch_editor_error_export_destination)
                    }
                    val base = safeWatchFileName(current.rootAttributes["name"])
                    var output = File(directory, "$base.watch")
                    var suffix = 2
                    while (output.exists()) output = File(directory, "${base}_$suffix.watch").also { suffix++ }
                    active.exportWatch(output)
                    active.snapshot()
                }
            }.onSuccess { next ->
                updateSnapshot(next)
                Toast.makeText(context, context.getString(R.string.watch_editor_toast_exported), Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                errorMessage = context.localizedEditorError(R.string.watch_editor_error_export, error)
            }
            busyMessage = null
        }
    }
    val fontPicker = rememberLauncherForActivityResult(OpenDocument()) { handleFontUri(it) }

    fun handleImageUri(uri: Uri?) {
        val active = session
        val key = selectedLayerKey
        if (uri == null || active == null || key == null) return
        scope.launch {
        busyMessage = context.getString(R.string.watch_editor_status_reading_image)
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    WatchEditorImageCropper.inspectSource(context.contentResolver, uri)
                }
            }.onSuccess { sourceInfo ->
                if (session !== active) {
                    busyMessage = null
                    return@onSuccess
                }
                val request = EditorImageImportRequest(uri, sourceInfo, active, key)
                busyMessage = null
                if (sourceInfo.isGif) {
                    // A GIF must never enter the bitmap cropper: doing so would
                    // flatten it to a single PNG frame.
                    performImageImport(request, sourceRect = null)
                } else {
                    cropRequest = request
                }
            }.onFailure { error ->
            errorMessage = context.localizedEditorError(R.string.watch_editor_error_read_image, error)
                busyMessage = null
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(OpenDocument()) { handleImageUri(it) }

    fun handleImageSequenceUris(uris: List<Uri>) {
        val active = session
        val key = selectedLayerKey
        if (uris.isEmpty() || active == null || key == null) return
        scope.launch {
        busyMessage = context.getString(R.string.watch_editor_status_importing_sequence)
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val paths = uris.map { uri ->
                        val sourceInfo = WatchEditorImageCropper.inspectSource(context.contentResolver, uri)
                        val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException(context.getString(R.string.watch_editor_error_read_image))
                        // A frame sequence is intentionally lossless. The
                        // runtime understands mixed PNG/JPEG/WebP and raw GIF.
                        active.importResource(stream, sourceInfo.importName)
                    }
                    active.updateLayerAttributes(
                        key,
                        mapOf(
                            "type" to "image_gif",
                            "path" to paths.joinToString("`"),
                            "gif_delay" to active.snapshot().layers
                                .firstOrNull { it.key == key }
                                ?.attributes
                                ?.get("gif_delay")
                                .orEmpty()
                                .ifBlank { "100" },
                        ),
                    )
                    active.flushDraftForPreview()
                    active.snapshot()
                }
            }.onSuccess { next ->
                updateSnapshot(next, key)
                previewToken++
            }.onFailure { error ->
                errorMessage = context.localizedEditorError(R.string.watch_editor_error_sequence_import, error)
            }
            busyMessage = null
        }
    }
    val imageSequencePicker = rememberLauncherForActivityResult(OpenMultipleDocuments()) {
        handleImageSequenceUris(it)
    }

    fun handleVideoUri(uri: Uri?) {
        val active = session
        val key = selectedLayerKey
        if (uri == null || active == null || key == null) return
        scope.launch {
        busyMessage = context.getString(R.string.watch_editor_status_reading_video)
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    WatchEditorVideoImporter.inspect(context, uri)
                }
            }.onSuccess { info ->
                if (session === active) {
                    videoRequest = EditorVideoImportRequest(uri, info, active, key)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
            errorMessage = context.localizedEditorError(R.string.watch_editor_error_read_video, error)
            }
            busyMessage = null
        }
    }
    val videoPicker = rememberLauncherForActivityResult(OpenDocument()) { handleVideoUri(it) }

    fun beginVideoImport(request: EditorVideoImportRequest, options: WatchEditorVideoImportOptions) {
        if (videoConversionJob != null || session !== request.session) return
        videoRequest = null
        videoConversionProgress = 0f
        videoConversionJob = scope.launch {
        busyMessage = context.getString(R.string.watch_editor_status_converting_video_fmt, 0)
            errorMessage = null
            var converted: WatchEditorVideoFrames? = null
            try {
                converted = WatchEditorVideoImporter.convertToFrames(
                    context = context,
                    uri = request.uri,
                    options = options,
                    onProgress = { progress ->
                        // FFmpeg can report its final timestamp before the
                        // process exits and before frames are copied into the
                        // draft. Never leave the dialog apparently stuck at
                        // 100%; it closes only after the import is committed.
                        val visibleProgress = progress.fraction.coerceAtMost(0.99f)
                        videoConversionProgress = visibleProgress
                    busyMessage = context.getString(
                        R.string.watch_editor_status_converting_video_fmt,
                        (visibleProgress * 100f).roundToInt(),
                    )
                    },
                )
                val next = withContext(Dispatchers.IO) {
                    request.session.importVideoFrameSequence(
                        key = request.layerKey,
                        frameFiles = converted.frameFiles,
                        frameDelayMs = converted.frameDelayMs,
                    )
                    request.session.flushDraftForPreview()
                    request.session.snapshot()
                }
                if (session === request.session) {
                    updateSnapshot(next, request.layerKey)
                    previewToken++
                }
            } catch (cancelled: CancellationException) {
                // User cancellation leaves both the draft and selected layer untouched.
            } catch (error: Throwable) {
                errorMessage = context.localizedEditorError(
                    R.string.watch_editor_error_video_import,
                    error,
                    includeDetails = true,
                )
            } finally {
                busyMessage = null
                videoConversionJob = null
                // The draft owns its copies now. Hide the modal first, then
                // delete the potentially large FFmpeg cache off the UI path.
                withContext(Dispatchers.IO + NonCancellable) { converted?.deleteTemporaryFiles() }
            }
        }
    }

    fun handleWatchArchiveUri(uri: Uri?) {
        if (uri != null) {
            scope.launch {
        busyMessage = context.getString(R.string.watch_editor_status_importing_watch)
                errorMessage = null
                val previous = session
                runCatching {
                    withContext(Dispatchers.IO) {
                        val temporary = File.createTempFile("flue_editor_import_", ".watch", context.cacheDir)
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                temporary.outputStream().use { output -> input.copyTo(output) }
                    } ?: error(context.getString(R.string.watch_editor_error_read_file))
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
                    Toast.makeText(
                        context,
                        context.getString(R.string.watch_editor_toast_imported_fmt, descriptor.displayName),
                        Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure { error ->
                    errorMessage = context.localizedEditorError(R.string.watch_editor_error_watch_import, error)
                }
                busyMessage = null
            }
        }
    }
    val watchArchivePicker = rememberLauncherForActivityResult(OpenDocument()) {
        handleWatchArchiveUri(it)
    }

    fun handleConversionUri(uri: Uri?) {
        if (uri != null) {
            scope.launch {
                busyMessage = context.getString(R.string.watch_editor_status_converting_watch)
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
                            } ?: error(context.getString(R.string.watch_editor_error_read_file))
                            when (val conversion = WatchFormatConverter().convert(input, output)) {
                                is WatchConversionResult.Failure -> error(
                                    buildString {
                                        append(conversion.message)
                                        val unsupported = conversion.report.unsupportedFeatures
                                        if (unsupported.isNotEmpty()) {
                                            append(
                                                context.getString(
                                                    R.string.watch_editor_report_unsupported_fmt,
                                                    unsupported.distinct().joinToString(),
                                                ),
                                            )
                                        }
                                        conversion.report.warnings.firstOrNull()?.let {
                                            append(
                                                context.getString(
                                                    R.string.watch_editor_report_warning_fmt,
                                                    it,
                                                ),
                                            )
                                        }
                                    },
                                )
                                is WatchConversionResult.Success -> {
                                    conversionSummary = buildString {
                                        append(
                                            context.getString(
                                                R.string.watch_editor_report_layers_fmt,
                                                conversion.report.convertedLayerCount,
                                            ),
                                        )
                                        if (conversion.report.unsupportedFeatures.isNotEmpty()) {
                                            append(
                                                context.getString(
                                                    R.string.watch_editor_report_summary_unsupported_fmt,
                                                    conversion.report.unsupportedFeatures
                                                        .distinct()
                                                        .joinToString(),
                                                ),
                                            )
                                        }
                                        if (conversion.report.warnings.isNotEmpty()) {
                                            append(
                                                context.getString(
                                                    R.string.watch_editor_report_warning_count_fmt,
                                                    conversion.report.warnings.size,
                                                ),
                                            )
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
                        context.getString(
                            R.string.watch_editor_toast_converted_fmt,
                            descriptor.displayName,
                            conversionSummary,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure { error ->
                    errorMessage = context.localizedEditorError(R.string.watch_editor_error_conversion, error)
                }
                busyMessage = null
            }
        }
    }
    val conversionPicker = rememberLauncherForActivityResult(OpenDocument()) {
        handleConversionUri(it)
    }

    fun openInternalFileBrowser(kind: EditorInternalImportKind) {
        if (kind == EditorInternalImportKind.VIDEO && !WatchEditorVideoImporter.isRuntimeSupported()) {
            errorMessage = context.getString(R.string.watch_editor_error_ffmpeg_unsupported)
            return
        }
        val title = context.getString(
            when (kind) {
                EditorInternalImportKind.IMAGE -> R.string.internal_file_browser_pick_image
                EditorInternalImportKind.IMAGE_SEQUENCE -> R.string.internal_file_browser_pick_images
                EditorInternalImportKind.VIDEO -> R.string.internal_file_browser_pick_video
                EditorInternalImportKind.FONT -> R.string.internal_file_browser_pick_font
                EditorInternalImportKind.BITMAP_FONT -> R.string.internal_file_browser_pick_bitmap_font
                EditorInternalImportKind.EXPORT_DIRECTORY -> R.string.internal_file_browser_pick_export_folder
                EditorInternalImportKind.WATCH -> R.string.internal_file_browser_pick_watch
                EditorInternalImportKind.CONVERSION -> R.string.internal_file_browser_pick_conversion
            },
        )
        val extensions = when (kind) {
            EditorInternalImportKind.IMAGE,
            EditorInternalImportKind.IMAGE_SEQUENCE -> EDITOR_INTERNAL_IMAGE_EXTENSIONS
            EditorInternalImportKind.VIDEO -> EDITOR_INTERNAL_VIDEO_EXTENSIONS
            EditorInternalImportKind.FONT -> EDITOR_FONT_EXTENSIONS
            EditorInternalImportKind.BITMAP_FONT -> EDITOR_BITMAP_FONT_EXTENSIONS
            EditorInternalImportKind.EXPORT_DIRECTORY -> emptySet()
            EditorInternalImportKind.WATCH -> setOf("watch")
            EditorInternalImportKind.CONVERSION -> setOf("wff", "zip")
        }
        internalFileBrowserRequest = InternalFileBrowserRequest(
            title = title,
            extensions = extensions,
            allowMultiple = kind == EditorInternalImportKind.IMAGE_SEQUENCE ||
                kind == EditorInternalImportKind.BITMAP_FONT,
            selectDirectory = kind == EditorInternalImportKind.EXPORT_DIRECTORY,
            onSelected = { uris ->
                when (kind) {
                    EditorInternalImportKind.IMAGE -> handleImageUri(uris.firstOrNull())
                    EditorInternalImportKind.IMAGE_SEQUENCE -> handleImageSequenceUris(uris)
                    EditorInternalImportKind.VIDEO -> handleVideoUri(uris.firstOrNull())
                    EditorInternalImportKind.FONT -> handleFontUri(uris.firstOrNull())
                    EditorInternalImportKind.BITMAP_FONT -> handleBitmapFontUris(uris)
                    EditorInternalImportKind.EXPORT_DIRECTORY -> uris.firstOrNull()?.path?.let { exportToDirectory(File(it)) }
                    EditorInternalImportKind.WATCH -> handleWatchArchiveUri(uris.firstOrNull())
                    EditorInternalImportKind.CONVERSION -> handleConversionUri(uris.firstOrNull())
                }
            },
        )
    }

    val blankFaceIsRound = isFlueScreenRound()
    // WatchMaker 表盘固定使用原生 512×512 设计空间；不再让新建流程
    // 根据设备屏幕或自定义规格改变画布，否则导出的坐标会失真。
    var blankCanvasSize by remember(watchFaceId) {
        mutableStateOf(WatchCanvasSize.DEFAULT)
    }
    LaunchedEffect(watchFaceId, blankCanvasSize) {
        val requestedCanvas = blankCanvasSize ?: return@LaunchedEffect
        loading = true
        errorMessage = null
        runCatching {
            withContext(Dispatchers.IO) {
                val opened = if (watchFaceId.isNullOrBlank()) {
                    // 新建表盘的形状跟随当前屏幕（含设置里的手动覆盖），
                    // 不再无条件写 shape="R" 导致方屏表盘被圆形遮罩裁剪。
                    WatchEditorSession.createBlank(
                        context,
                        roundShape = blankFaceIsRound,
                        canvasSize = requestedCanvas,
                    )
                } else {
                    val descriptor = WatchmakerWatchFaceStorage.scan(context)
                        .firstOrNull { it.id == watchFaceId }
                        ?: error(context.getString(R.string.watch_editor_error_face_not_found))
                    WatchEditorSession.open(context, descriptor)
                }
                opened to opened.snapshot()
            }
        }.onSuccess { (opened, initialSnapshot) ->
            session = opened
            updateSnapshot(initialSnapshot)
            previewToken++
        }.onFailure { error ->
            errorMessage = context.localizedEditorError(R.string.watch_editor_error_open, error)
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

    fun requestSave(saveAs: Boolean, uploadAfterSave: Boolean = false) {
        val current = snapshot ?: return
        saveAsMode = saveAs
        pendingSaveName = current.rootAttributes["name"].orEmpty()
            .ifBlank { context.getString(R.string.watch_editor_default_watch_name) }
        pendingSaveAuthor = current.designerName()
        pendingAuthorModified = false
        openUploadAfterSave = uploadAfterSave
        showSaveDialog = true
    }

    BackHandler(onBack = ::requestClose)

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
                title = { Text(stringResource(R.string.watch_editor_discard_title)) },
                text = { Text(stringResource(R.string.watch_editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onClose()
                    }) { Text(stringResource(R.string.watch_editor_discard)) }
            },
            dismissButton = {
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text(stringResource(R.string.watch_editor_continue))
                    }
            },
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = {
                showSaveDialog = false
                openUploadAfterSave = false
            },
                title = {
                    Text(
                        stringResource(
                            if (saveAsMode) R.string.watch_editor_save_as_title
                            else R.string.watch_editor_save_title,
                        ),
                    )
                },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pendingSaveName,
                        onValueChange = { pendingSaveName = it.take(80) },
                        label = { Text(stringResource(R.string.watch_editor_face_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = pendingSaveAuthor,
                        onValueChange = {
                            pendingSaveAuthor = it.take(80)
                            pendingAuthorModified = true
                        },
                        label = { Text(stringResource(R.string.watch_editor_author)) },
                        placeholder = { Text(stringResource(R.string.watch_editor_author_hint)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pendingSaveName.isNotBlank() && busyMessage == null,
                    onClick = {
                        val active = session ?: return@TextButton
                        val name = pendingSaveName.trim()
                        // 保存只在用户真的改过作者栏时写这个字段（老师指定）。
                        // 以前这里会回退到账户名 / "flue"，把别人做的表盘一保存
                        // 就署上自己的名字；账户名只在上传到商店时才填。
                        val authorDraft = pendingSaveAuthor.trim()
                            .takeIf { pendingAuthorModified && it.isNotBlank() }
                        val createNewLogicalId = saveAsMode || !active.canSaveCurrentRevision
                        showSaveDialog = false
                        scope.launch {
                                busyMessage = context.getString(
                                    if (createNewLogicalId) R.string.watch_editor_status_saving_as
                                    else R.string.watch_editor_status_saving_revision,
                                )
                            errorMessage = null
                            liveApplicator?.flush(
                                onApplied = {},
                                    onError = { errorMessage = it.message ?: context.getString(R.string.watch_editor_error_property_update) },
                            )
                            // 先把当前画布重渲成 preview.jpg 写回草稿，再落盘：
                            // 否则 saveCurrentRevision / exportWatch 打包进去的
                            // 还是原作者那张预览图。
                            refreshDraftPreview(active)
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    active.updateRootAttribute("name", name)
                                    // 只有用户自己填了作者才写回；没填就一个字段都
                                    // 不碰，原作者信息原样保留。
                                    if (authorDraft != null) {
                                        // designer_name 是 Watchmaker 侧的字段，author 才是
                                        // Flue 扫描器读来显示的那个：两个一起写，避免存了
                                        // 作者却在表盘列表里看不到。
                                        active.updateRootAttributes(
                                            mapOf(
                                                "designer_name" to authorDraft,
                                                "author" to authorDraft,
                                            ),
                                        )
                                    }
                                    active.flushDraftForPreview()
                                    if (!createNewLogicalId) {
                                        active.saveCurrentRevision()
                                        active.logicalId?.let { launcherId ->
                                            WatchStoreRepository(context)
                                                .detachEditedMarketInstall(launcherId)
                                        }
                                        val descriptor = WatchmakerWatchFaceStorage.scan(context)
                                            .firstOrNull { it.id == active.logicalId }
                                                ?: error(context.getString(R.string.watch_editor_error_rescan_revision))
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
                                // 保存同一个 logicalId 后，桌面那份 ViewModel 的
                                // refreshToken 不会自己变——两个 Activity 的 ViewModel
                                // 是隔离的。这里直接 bump 进程级共享令牌，
                                // WatchmakerWatchFaceView 的描述符键不变但令牌变了，
                                // 短路失效 → 重新从磁盘加载最新 watch.xml 渲染。
                                LauncherViewModel.sharedWatchFaceRefreshToken.value =
                                    LauncherViewModel.sharedWatchFaceRefreshToken.value + 1
                                Toast.makeText(
                                    context,
                                    if (createNewLogicalId) {
                                        context.getString(
                                            R.string.watch_editor_toast_saved_as_fmt,
                                            saved.descriptor.displayName,
                                        )
                                    } else {
                                        context.getString(
                                            R.string.watch_editor_toast_saved_fmt,
                                            saved.descriptor.displayName,
                                        )
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                                if (openUploadAfterSave) {
                                    openUploadAfterSave = false
                                    showStoreUpload = true
                                }
                            }.onFailure { error ->
                                openUploadAfterSave = false
                                errorMessage = context.localizedEditorError(R.string.watch_editor_error_save, error)
                            }
                            busyMessage = null
                        }
                    },
                    ) {
                        Text(
                            stringResource(
                                if (saveAsMode) R.string.watch_editor_save_and_install
                                else R.string.watch_editor_save,
                            ),
                        )
                    }
            },
            dismissButton = {
                    TextButton(onClick = {
                        showSaveDialog = false
                        openUploadAfterSave = false
                    }) {
                        Text(stringResource(R.string.watch_editor_cancel))
                    }
            },
        )
    }

    if (showUploadSaveRequiredDialog) {
        val canSaveCurrent = session?.canSaveCurrentRevision == true
        AlertDialog(
            onDismissRequest = { showUploadSaveRequiredDialog = false },
            title = { Text(stringResource(R.string.watch_editor_upload_save_required_title)) },
            text = { Text(stringResource(R.string.watch_editor_upload_save_required_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUploadSaveRequiredDialog = false
                        requestSave(saveAs = !canSaveCurrent, uploadAfterSave = true)
                    },
                ) {
                    Text(
                        stringResource(
                            if (canSaveCurrent) R.string.watch_editor_save
                            else R.string.watch_editor_save_as,
                        ),
                    )
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canSaveCurrent) {
                        TextButton(
                            onClick = {
                                showUploadSaveRequiredDialog = false
                                requestSave(saveAs = true, uploadAfterSave = true)
                            },
                        ) {
                            Text(stringResource(R.string.watch_editor_save_as))
                        }
                    }
                    TextButton(onClick = { showUploadSaveRequiredDialog = false }) {
                        Text(stringResource(R.string.watch_editor_cancel))
                    }
                }
            },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
        loading -> CenteredStatus(
            stringResource(R.string.watch_editor_status_creating_draft),
            showProgress = true,
        )
            session == null || snapshot == null -> ErrorPage(
            message = errorMessage ?: stringResource(R.string.watch_editor_error_init),
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
                        previewDescriptor = draftDescriptor(context, active, current),
                        previewToken = previewToken,
                        transformPreview = transformPreview,
                        propertyDraftRevision = propertyDraftRevision,
                        transformCommitInFlight = transformCommitInFlight,
                        busyMessage = busyMessage,
                        errorMessage = errorMessage,
                        imageSizeLookup = imageSizeLookup,
                        draftDirectory = active.draftDirectory,
                        onBack = ::requestClose,
                        onSelectLayer = ::selectLayer,
                        onBeginTransform = { key ->
                            if (!transformGate.tryBeginGesture()) {
                                null
                            } else {
                                runCatching {
                                    pendingTransformRefreshToken = null
                                    previewRenderer?.cancelLiveLayerTransformHandoff()
                                    // Un-applied typed edits must not fire mid-gesture:
                                    // apply them synchronously before the transaction.
                                    liveApplicator?.consumePendingChanges()?.let { (pendingKey, changes) ->
                                        active.updateLayerAttributes(pendingKey, changes)
                                    }
                                    val initial = active.beginLayerTransformGesture(key)
                                    transformPreview = WatchEditorTransformPreview(key, initial)
                                    initial
                                }.onFailure { error ->
                                    transformGate.cancelGesture()
                                    errorMessage = context.localizedEditorError(R.string.watch_editor_error_begin_gesture, error)
                                }.getOrNull()
                            }
                        },
                        onUpdateTransform = { key, transform ->
                            if (busyMessage == null && transformGate.hasActiveGesture()) {
                                runCatching {
                                    active.updateLayerTransformPreview(key, transform)
                                    transformPreview = WatchEditorTransformPreview(key, transform.sanitized())
                                }
                            }
                        },
                        onEndTransform = { key, cancelled ->
                            val wasBusy = busyMessage != null
                            if (shouldHandleWatchEditorTransformEnd(cancelled, wasBusy)) {
                                // 关键顺序：UP 帧绝不清 transformPreview——立刻清会让
                                // 选中框/几何回退到手势前的 snapshot 值，IO 提交完成后
                                // 才跳到新值（「放手后元素从原位置闪现一遍」的根因）。
                                // 也不再挂 busyMessage：它会顺带禁用选中框与命中测试，
                                // 整个面板还会插入状态行跳一下。提交静默进行。
                                if (transformGate.beginCommit()) {
                                    transformCommitInFlight = true
                                    val transformStartSelectionGeneration = selectionGeneration
                                    scope.launch {
                                        try {
                                            val next = withContext(Dispatchers.IO) {
                                                if (cancelled) {
                                                    active.cancelLayerTransformGesture(key)
                                                } else {
                                                    active.commitLayerTransformGesture(key)
                                                    active.flushDraftForPreview()
                                                }
                                                active.snapshot()
                                            }
                                        val preferredSelection = if (
                                            shouldKeepWatchEditorSelectionForTransformCommit(
                                                transformStartSelectionGeneration = transformStartSelectionGeneration,
                                                currentSelectionGeneration = selectionGeneration,
                                            )
                                        ) key else selectedLayerKey
                                        updateSnapshot(next, preferredSelection)
                                        if (cancelled) {
                                            transformPreview = null
                                            pendingTransformRefreshToken = null
                                        } else {
                                            // Keep the native renderer on the
                                            // release transform until its new
                                            // XML source reports ready. A
                                            // snapshot object alone does not
                                            // mean the async View reload has
                                            // completed.
                                            val nextRefreshToken = previewToken + 1
                                            previewRenderer
                                                ?.handoffLiveLayerTransformOnSourceReady(nextRefreshToken)
                                            pendingTransformRefreshToken = nextRefreshToken
                                            previewToken = nextRefreshToken
                                        }
                                        } catch (cancelledError: CancellationException) {
                                            throw cancelledError
                                        } catch (error: Throwable) {
                                            transformPreview = null
                                            errorMessage = context.localizedEditorError(R.string.watch_editor_error_commit_gesture, error)
                                        } finally {
                                            transformGate.finishCommit()
                                            transformCommitInFlight = false
                                        }
                                    }
                                } else {
                                    transformPreview = null
                                }
                            } else {
                                transformGate.cancelGesture()
                                transformPreview = null
                            }
                        },
                        onUndo = {
                            runSessionOperation(context.getString(R.string.watch_editor_status_undo), operation = { editor ->
                                editor.undo()
                                selectedLayerKey
                            })
                        },
                        onRedo = {
                            runSessionOperation(context.getString(R.string.watch_editor_status_redo), operation = { editor ->
                                editor.redo()
                                selectedLayerKey
                            })
                        },
                        onAddLayer = { type ->
                            runSessionOperation(context.getString(R.string.watch_editor_status_add_layer), operation = { editor ->
                                editor.addLayer(type, defaultAttributes(type))
                            })
                        },
                        onMoveLayer = { key, target ->
                            runSessionOperation(context.getString(R.string.watch_editor_status_reorder_layer), operation = { editor ->
                                editor.reorderLayer(key, target)
                                key
                            })
                        },
                        onDuplicateLayer = { key ->
                            runSessionOperation(context.getString(R.string.watch_editor_status_duplicate_layer), operation = { editor ->
                                editor.duplicateLayer(key)
                            })
                        },
                        onDeleteLayer = { key ->
                            runSessionOperation(context.getString(R.string.watch_editor_status_delete_layer), operation = { editor ->
                                editor.deleteLayer(key)
                                null
                            })
                        },
                        onLiveAttributeChange = { key, changes ->
                            val attributeStartSelectionGeneration = selectionGeneration
                            liveApplicator?.submit(
                                scope = scope,
                                key = key,
                                changes = changes,
                                onApplied = { next ->
                                    val preferredSelection = if (
                                        shouldKeepWatchEditorSelectionForAsyncCommit(
                                            commitStartSelectionGeneration = attributeStartSelectionGeneration,
                                            currentSelectionGeneration = selectionGeneration,
                                        )
                                    ) key else selectedLayerKey
                                    updateSnapshot(
                                        next,
                                        preferredSelection,
                                        resetPropertyDrafts = false,
                                    )
                                    previewToken++
                                },
                                onError = { error ->
                                    errorMessage = context.localizedEditorError(R.string.watch_editor_error_property_update, error)
                                },
                            )
                        },
                        onImportImage = {
                            imagePicker.launch(arrayOf("image/*"))
                        },
                        onImportImageLong = {
                            openInternalFileBrowser(EditorInternalImportKind.IMAGE)
                        },
                        onImportImageSequence = {
                            imageSequencePicker.launch(arrayOf("image/*"))
                        },
                        onImportImageSequenceLong = {
                            openInternalFileBrowser(EditorInternalImportKind.IMAGE_SEQUENCE)
                        },
                        onImportVideo = {
                            if (WatchEditorVideoImporter.isRuntimeSupported()) {
                                videoPicker.launch(arrayOf("video/*"))
                            } else {
                                errorMessage = context.getString(
                                    R.string.watch_editor_error_ffmpeg_unsupported,
                                )
                            }
                        },
                        onImportVideoLong = {
                            openInternalFileBrowser(EditorInternalImportKind.VIDEO)
                        },
                        onImportFont = {
                            fontPicker.launch(
                                arrayOf(
                                    "font/ttf",
                                    "font/otf",
                                    "application/x-font-ttf",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        onImportFontLong = {
                            openInternalFileBrowser(EditorInternalImportKind.FONT)
                        },
                        onImportBitmapFont = { bitmapFontPicker.launch(arrayOf("*/*")) },
                        onImportBitmapFontLong = { openInternalFileBrowser(EditorInternalImportKind.BITMAP_FONT) },
                        onSaveAsNew = {
                            requestSave(saveAs = true)
                        },
                        onSave = {
                            requestSave(saveAs = false)
                        },
                        onRename = {
                            requestSave(saveAs = false)
                        },
                        onExport = {
                            val name = safeWatchFileName(current.rootAttributes["name"])
                            exportDocument.launch("$name.watch")
                        },
                        onUpdateShape = { shapeCode ->
                            runSessionOperation(context.getString(R.string.watch_editor_status_update_shape), operation = { editor ->
                                editor.updateRootAttribute("shape", shapeCode)
                                selectedLayerKey
                            })
                        },
                        onUploadToStore = {
                            if (busyMessage == null) {
                                scope.launch {
                                    // Typed-but-unapplied property edits must land in
                                    // the session before deciding whether this revision
                                    // has actually been saved/installed.
                                    var flushFailure: Throwable? = null
                                    liveApplicator?.flush(
                                        onApplied = { next -> updateSnapshot(next) },
                                        onError = { error ->
                                            flushFailure = error
                                            errorMessage = error.message
                                                ?: context.getString(R.string.watch_editor_error_property_update)
                                        },
                                    )
                                    if (flushFailure != null) return@launch
                                    val activeSession = session ?: return@launch
                                    val latest = withContext(Dispatchers.IO) { activeSession.snapshot() }
                                    updateSnapshot(latest)
                                    if (latest.dirty || !activeSession.canSaveCurrentRevision) {
                                        showUploadSaveRequiredDialog = true
                                    } else {
                                        showStoreUpload = true
                                    }
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
                        onExportLong = { openInternalFileBrowser(EditorInternalImportKind.EXPORT_DIRECTORY) },
                        onImportWatchLong = {
                            openInternalFileBrowser(EditorInternalImportKind.WATCH)
                        },
                        onConvertWatch = {
                            conversionPicker.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        onConvertWatchLong = {
                            openInternalFileBrowser(EditorInternalImportKind.CONVERSION)
                        },
                        onPreviewRendererChanged = { previewRenderer = it },
                        onPreviewRefreshReady = { loadedToken ->
                            if (pendingTransformRefreshToken == loadedToken) {
                                pendingTransformRefreshToken = null
                                transformPreview = null
                            }
                        },
                        onPreviewRefreshFailed = { failedToken ->
                            if (pendingTransformRefreshToken == failedToken) {
                                pendingTransformRefreshToken = null
                                transformPreview = null
                            }
                        },
                    )
                    cropRequest?.let { request ->
                        EditorImageCropOverlay(
                            uri = request.uri,
                            canvasSize = WatchCanvasSize.DEFAULT,
                            onCancel = { cropRequest = null },
                            onConfirm = { sourceRect, preserveOriginal ->
                                performImageImport(
                                    request,
                                    sourceRect = sourceRect.takeUnless { preserveOriginal },
                                )
                            },
                        )
                    }
                    videoRequest?.let { request ->
                        EditorVideoImportFlow(
                            uri = request.uri,
                            info = request.info,
                            canvasSize = WatchCanvasSize.DEFAULT,
                            onDismiss = { videoRequest = null },
                            onConfirm = { options -> beginVideoImport(request, options) },
                        )
                    }
                    if (videoConversionJob != null) {
                        EditorVideoConversionProgressDialog(
                            progress = videoConversionProgress,
                            onCancel = { videoConversionJob?.cancel() },
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
                    InternalFileBrowserHost(
                        request = internalFileBrowserRequest,
                        onDismiss = { internalFileBrowserRequest = null },
                    )
                }
            }
        }
    }
}

/**
 * 图层缩略图：直接解码该层引用的素材文件。
 *
 * 与 wftools 的 LayersTab 同思路——缩略图要回答「这层用的是哪张图」，
 * 不是「这层在表盘上长什么样」。所以不走渲染管线（那样会带上 x/y/rotation，
 * 偏离中心的元素在 40dp 方格里只剩一角），而是按 runtime 的解析顺序找到
 * path 指向的文件，按缩略图尺寸降采样后解码。
 *
 * 非图片图层（文字 / 形状 / 指针等）返回 null，由 [EditorLayerRow] 兜底
 * 显示类型图标。
 */
private fun resolveLayerAssetThumbnail(
    draftDirectory: File,
    layer: WatchEditorLayer,
    sizePx: Int,
): androidx.compose.ui.graphics.ImageBitmap? {
    val rawPath = layer.attributes["path"]?.takeIf { it.isNotBlank() } ?: return null
    // GIF 帧序列写成 "a.png`b.png`c.png"：取第一帧代表这一层。
    val path = rawPath.substringBefore('`').trim().replace('\\', '/')
    if (path.isBlank() || path.contains("..") || path.startsWith('/')) return null
    // 与 runtime 一致：先按原样找，再退回约定的 images/ 目录。
    val file = listOf(File(draftDirectory, path), File(draftDirectory, "images/$path"))
        .firstOrNull(File::isFile) ?: return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        // 降采样到不小于目标尺寸的最近 2 的幂，避免把 2000px 大图整张读进来。
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= sizePx &&
            bounds.outHeight / (sample * 2) >= sizePx
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        FileInputStream(file).use { BitmapFactory.decodeStream(it, null, options) }
            ?.asImageBitmap()
    }.getOrNull()
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
    previewDescriptor: WatchFaceDescriptor,
    previewToken: Int,
    transformPreview: WatchEditorTransformPreview?,
    propertyDraftRevision: Int,
    transformCommitInFlight: Boolean,
    busyMessage: String?,
    errorMessage: String?,
    imageSizeLookup: (String) -> Pair<Float, Float>?,
    /** 草稿根目录：图层缩略图直接从这里解码素材文件。 */
    draftDirectory: File,
    onBack: () -> Unit,
    onSelectLayer: (WatchEditorLayerKey?) -> Unit,
    onBeginTransform: (WatchEditorLayerKey) -> WatchEditorTransform?,
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
    onImportImageLong: () -> Unit,
    onImportImageSequence: () -> Unit,
    onImportImageSequenceLong: () -> Unit,
    onImportVideo: () -> Unit,
    onImportVideoLong: () -> Unit,
    onImportFont: () -> Unit,
    onImportFontLong: () -> Unit,
    onImportBitmapFont: () -> Unit,
    onImportBitmapFontLong: () -> Unit,
    onSaveAsNew: () -> Unit,
    onSave: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onExportLong: () -> Unit,
    onUpdateShape: (String) -> Unit,
    onUploadToStore: () -> Unit,
    onImportWatch: () -> Unit,
    onImportWatchLong: () -> Unit,
    onConvertWatch: () -> Unit,
    onConvertWatchLong: () -> Unit,
    onPreviewRefreshReady: (Int) -> Unit = {},
    onPreviewRefreshFailed: (Int) -> Unit = {},
    /**
     * 把画布用的渲染 View 上抛给 Route：保存 / 上传前要用它离屏重渲一张
     * preview.jpg 写回草稿，否则列表和商店永远显示原作者那张预览图。
     */
    onPreviewRendererChanged: (WatchmakerWatchFaceView?) -> Unit = {},
) {
    var panel by rememberSaveable { mutableStateOf(WatchEditorPanel.LAYERS) }
    // 三档布局：上箭头=画布铺满全屏，下箭头=图层/属性/表盘面板铺满全屏，
    // 中间态为分屏。用一个动画化的高度比例驱动，绝不整树替换——以前
    // previewFullscreen 的提前 return 会把 AndroidView 销毁重建，进出全屏
    // 表盘要整个重载一遍。
    var editorLayout by rememberSaveable { mutableStateOf(WatchEditorLayout.SPLIT) }
    val previewFullscreen = editorLayout == WatchEditorLayout.CANVAS_FULL
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
    var scrollToLayerRequest by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val context = LocalContext.current
    var bodySensorsPermissionGranted by remember(context) {
        mutableStateOf(hasEditorBodySensorsPermission(context))
    }
    val bodySensorsPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) {
        bodySensorsPermissionGranted = hasEditorBodySensorsPermission(context)
    }
    val isRound = isFlueScreenRound()
    // 图层缩略图渲染器：直接借预览的 WatchmakerWatchFaceView（同一资源缓存）。
    var thumbnailRenderer by remember { mutableStateOf<WatchmakerWatchFaceView?>(null) }
    var textThumbnailClockTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(thumbnailRenderer) {
        while (thumbnailRenderer != null) {
            delay(1_000L)
            textThumbnailClockTick++
        }
    }
    val layerDragDensity = LocalDensity.current
    var draggedLayerKey by remember { mutableStateOf<WatchEditorLayerKey?>(null) }
    var draggedLayerRow by remember { mutableStateOf<WatchEditorTreeRow?>(null) }
    var draggedLayerPointerY by remember { mutableStateOf(0f) }
    var draggedLayerTouchOffsetY by remember { mutableStateOf(0f) }
    var draggedLayerHeightPx by remember { mutableStateOf(0f) }
    var draggedLayerTargetOrder by remember { mutableStateOf<Int?>(null) }
    val draftSizeBytes by produceState(
        initialValue = 0L,
        key1 = draftDirectory,
        key2 = snapshot.resourcePaths,
        key3 = previewToken,
    ) {
        value = withContext(Dispatchers.IO) { editorDirectorySizeBytes(draftDirectory) }
    }
    val treeRows = remember(snapshot.layers, collapsedLayerKeys) {
        buildWatchEditorTreeRows(layers = snapshot.layers, collapsedKeys = collapsedLayerKeys)
    }
    LaunchedEffect(draggedLayerKey) {
        while (draggedLayerKey != null) {
            val layout = layerListState.layoutInfo
            val edgePx = with(layerDragDensity) { 52.dp.toPx() }
            val pointerY = draggedLayerPointerY
            val viewportStart = 0f
            val viewportEnd = layout.viewportSize.height.toFloat()
            val distanceIntoEdge = when {
                pointerY < viewportStart + edgePx ->
                    pointerY - (viewportStart + edgePx)
                pointerY > viewportEnd - edgePx ->
                    pointerY - (viewportEnd - edgePx)
                else -> 0f
            }
            if (distanceIntoEdge != 0f) {
                val requested = (distanceIntoEdge / edgePx).coerceIn(-1f, 1f) *
                    with(layerDragDensity) { 22.dp.toPx() }
                layerListState.scrollBy(requested)
                layerListState.layoutInfo.visibleItemsInfo
                    .asSequence()
                    .mapNotNull { info ->
                        val row = treeRows.firstOrNull { it.layer.key.value == info.key }
                            ?: return@mapNotNull null
                        row.layer.order to kotlin.math.abs(
                            info.offset + info.size / 2f - pointerY,
                        )
                    }
                    .minByOrNull { it.second }
                    ?.first
                    ?.let { draggedLayerTargetOrder = it }
            }
            delay(16L)
        }
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
    BackHandler(enabled = editorLayout != WatchEditorLayout.SPLIT) {
        editorLayout = WatchEditorLayout.SPLIT
    }

    val previewContent: @Composable (Modifier) -> Unit = { previewModifier ->
        WatchEditorPreview(
            descriptor = previewDescriptor,
            refreshToken = previewToken,
            bodySensorsPermissionGranted = bodySensorsPermissionGranted,
            layers = snapshot.layers,
            selectedLayer = selectedLayer,
            transformPreview = transformPreview,
            enabled = busyMessage == null && !transformCommitInFlight,
            imageSizeLookup = imageSizeLookup,
            onSelectFromCanvas = { key -> onSelectLayer(key) },
            onCanvasTap = { key ->
                if (key != null) {
                    if (previewFullscreen) {
                        editorLayout = WatchEditorLayout.SPLIT
                        panel = WatchEditorPanel.PROPERTIES
                    } else if (panel == WatchEditorPanel.LAYERS) {
                        scrollToLayerRequest =
                            (scrollToLayerRequest?.first ?: 0) + 1 to key.value
                    }
                }
            },
            onBeginTransform = onBeginTransform,
            onUpdateTransform = onUpdateTransform,
            onEndTransform = onEndTransform,
            modifier = previewModifier,
            onFaceViewChanged = {
                thumbnailRenderer = it
                onPreviewRendererChanged(it)
            },
            onRefreshReady = onPreviewRefreshReady,
            onRefreshFailed = onPreviewRefreshFailed,
            designExtentWidth = WatchCanvasSize.DEFAULT.designExtentWidth,
            designExtentHeight = WatchCanvasSize.DEFAULT.designExtentHeight,
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val editorMaxHeight = maxHeight
        // Column 内部拿不到 BoxWithConstraints 作用域，宽度约束要在这里捕获。
        val editorMaxWidth = maxWidth
        val splitPreviewSize = watchEditorPreviewSizeDp(maxWidth.value, maxHeight.value)
        // 三档布局共用一条动画化的高度比例：预览常驻挂载只改高度，
        // AndroidView（表盘渲染器）全程不销毁、切换全屏不再重载表盘。
        val splitFraction = (splitPreviewSize / maxHeight.value).coerceIn(0.24f, 0.60f)
        val targetPreviewFraction = when (editorLayout) {
            WatchEditorLayout.CANVAS_FULL -> 1f
            WatchEditorLayout.SPLIT -> splitFraction
            WatchEditorLayout.PANEL_FULL -> 0f
        }
        val previewFraction by animateFloatAsState(
            targetValue = targetPreviewFraction,
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 340f),
            label = "editor_preview_fraction",
        )
        val horizontalListPadding = if (isRound) 24.dp else 10.dp
        val bottomListPadding = if (isRound) 28.dp else 12.dp
        val listContentPadding = PaddingValues(
            start = horizontalListPadding,
            end = horizontalListPadding,
            top = 10.dp,
            bottom = bottomListPadding,
        )
        // 兜底裁剪：任何一档的子项越界都不会再画到兄弟节点上面。
        Column(modifier = Modifier.fillMaxSize().clipToBounds()) {
            // 顶栏在两个全屏档整块滑出让位（高度动画驱动下方内容重排）。
            AnimatedVisibility(
                visible = editorLayout == WatchEditorLayout.SPLIT,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            // 圆屏整块内缩：顶栏位于弦最窄处，两端按钮压弧。
                            horizontal = if (isRound) 30.dp else 4.dp,
                            vertical = if (isRound) 6.dp else 2.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Icon(
                            MiuixIcons.Back,
                            contentDescription = stringResource(R.string.watch_editor_back),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            snapshot.rootAttributes["name"].orEmpty().ifBlank {
                                stringResource(R.string.watch_editor_title)
                            },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            stringResource(
                                if (snapshot.dirty) {
                                    R.string.watch_editor_draft_dirty
                                } else {
                                    R.string.watch_editor_draft_synced
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { editorLayout = WatchEditorLayout.CANVAS_FULL },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            FlueMiuixIcons.ArrowUp,
                            contentDescription = stringResource(R.string.watch_editor_canvas_fullscreen),
                        )
                    }
                    IconButton(
                        onClick = { editorLayout = WatchEditorLayout.PANEL_FULL },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            FlueMiuixIcons.ArrowDown,
                            contentDescription = stringResource(R.string.watch_editor_panel_fullscreen),
                        )
                    }
                    IconButton(
                        onClick = onUndo,
                        enabled = snapshot.canUndo && busyMessage == null,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            MiuixIcons.Undo,
                            contentDescription = stringResource(R.string.watch_editor_undo),
                        )
                    }
                    IconButton(
                        onClick = onRedo,
                        enabled = snapshot.canRedo && busyMessage == null,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            MiuixIcons.Redo,
                            contentDescription = stringResource(R.string.watch_editor_redo),
                        )
                    }
                }
            }
            // 画布是正方形，但测试设备是竖屏手机（W < H）。以前用
            // size(min(H*fraction, W)) 把画布固定成正方形，panel 的 weight(1f)
            // 就拿走了剩下的 H-W 像素——CANVAS_FULL 时 fraction=1 但 H>W，
            // panel 仍有 H-W 的高度，画布永远不铺满。
            //
            // 现在改为：预览 Box 的高度 = H*fraction（Column 的固定高度），
            // 正方形画布在里面居中（边长 = min(H*fraction, W)）。panel 的
            // weight(1f) 自然得到 H*(1-fraction) → fraction=1 时 = 0，铺满。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(editorMaxHeight * previewFraction),
                contentAlignment = Alignment.Center,
            ) {
                // 预览容器按声明画布比例，不再锁死正方形（老师要求「渲染出
                // 现在的画布大小，等比缩放，只占半个屏幕而不撑破」）。
                // 双向取小：高度不超过 H*fraction，宽度不超过 W，非方画布
                // 两个方向都不会溢出。
                val availableHeight = editorMaxHeight * previewFraction
                // 用 designExtent 的比例而不是声明像素比：渲染器就是按
                // designExtent 画的，容器必须同源，否则出现黑边或裁切。
                val ratio = (WatchCanvasSize.DEFAULT.designExtentWidth / WatchCanvasSize.DEFAULT.designExtentHeight)
                    .takeIf { it.isFinite() && it > 0f } ?: 1f
                val byHeight = availableHeight * ratio        // 以高定宽
                val previewWidth = min(byHeight, editorMaxWidth)
                val previewHeight = previewWidth / ratio
                previewContent(
                    Modifier
                        .size(width = previewWidth, height = previewHeight)
                        // 画布全屏且圆屏时按圆形裁剪提示真实表框边界。
                        .clip(
                            if (isRound && previewFraction > 0.92f) CircleShape else RectangleShape,
                        ),
                )
            }
            // 面板全屏态的紧凑返回条：上箭头回到分屏，切换 tab 仍在下面顶部。
            AnimatedVisibility(
                visible = editorLayout == WatchEditorLayout.PANEL_FULL,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (isRound) 10.dp else 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { editorLayout = WatchEditorLayout.SPLIT },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            FlueMiuixIcons.ArrowUp,
                            contentDescription = stringResource(R.string.watch_editor_return_split),
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = editorLayout != WatchEditorLayout.CANVAS_FULL,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                TabRow(
                    selectedTabIndex = panel.ordinal,
                    modifier = Modifier.padding(horizontal = if (isRound) 26.dp else 0.dp),
                ) {
                    WatchEditorPanel.entries.forEach { candidate ->
                        Tab(
                            selected = panel == candidate,
                            onClick = { panel = candidate },
                            icon = {
                                Icon(
                                    imageVector = when (candidate) {
                                        WatchEditorPanel.LAYERS -> MiuixIcons.Layers
                                        WatchEditorPanel.PROPERTIES -> MiuixIcons.Tune
                                        WatchEditorPanel.WATCH -> MiuixIcons.WorldClock
                                    },
                                    contentDescription = null,
                                )
                            },
                            text = { Text(stringResource(candidate.labelRes), maxLines = 1) },
                        )
                    }
                }
            }
            AnimatedContent(
                targetState = panel,
                transitionSpec = {
                    // 按 tab 顺序决定滑动方向：所有面板切换都有动画，不硬切。
                    val forward = targetState.ordinal >= initialState.ordinal
                    (
                        slideInHorizontally(
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            initialOffsetX = { if (forward) it / 4 else -it / 4 },
                        ) + fadeIn(tween(180))
                        ) togetherWith (
                        slideOutHorizontally(
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            targetOffsetX = { if (forward) -it / 6 else it / 6 },
                        ) + fadeOut(tween(110))
                        )
                },
                label = "editor_panel",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 弹性橡皮筋靠 graphicsLayer{translationY} 实现且默认不
                    // 裁剪——在滚动视口边界裁一刀，面板内容不再飘到表盘上。
                    .clipToBounds(),
            ) { activePanel ->
                when (activePanel) {
                WatchEditorPanel.LAYERS -> Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = layerListState,
                        modifier = Modifier
                            .fillMaxSize()
                        // 手势挂在稳定的列表容器上：被拖行滚出可见区并销毁时，
                        // pointer session 仍继续，直到抬手才提交一次层级变更。
                        .pointerInput(treeRows, busyMessage) {
                            if (busyMessage != null) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { position ->
                                    val info = layerListState.layoutInfo.visibleItemsInfo
                                        .firstOrNull {
                                            position.y >= it.offset && position.y <= it.offset + it.size
                                        } ?: return@detectDragGesturesAfterLongPress
                                    val row = treeRows.firstOrNull { it.layer.key.value == info.key }
                                        ?: return@detectDragGesturesAfterLongPress
                                    draggedLayerKey = row.layer.key
                                    draggedLayerRow = row
                                    draggedLayerPointerY = position.y
                                    draggedLayerTouchOffsetY = position.y - info.offset
                                    draggedLayerHeightPx = info.size.toFloat()
                                    draggedLayerTargetOrder = row.layer.order
                                    onSelectLayer(row.layer.key)
                                },
                                onDrag = { change, dragAmount ->
                                    val activeKey = draggedLayerKey ?: return@detectDragGesturesAfterLongPress
                                    change.consume()
                                    draggedLayerPointerY += dragAmount.y
                                    layerListState.layoutInfo.visibleItemsInfo
                                        .asSequence()
                                        .mapNotNull { info ->
                                            val row = treeRows.firstOrNull {
                                                it.layer.key.value == info.key
                                            } ?: return@mapNotNull null
                                            row.layer.order to kotlin.math.abs(
                                                info.offset + info.size / 2f - draggedLayerPointerY,
                                            )
                                        }
                                        .minByOrNull { it.second }
                                        ?.first
                                        ?.let { draggedLayerTargetOrder = it }
                                    // Keep the captured key read so a deletion during drag
                                    // cancels safely instead of committing a stale row.
                                    if (treeRows.none { it.layer.key == activeKey }) {
                                        draggedLayerKey = null
                                        draggedLayerRow = null
                                    }
                                },
                                onDragEnd = {
                                    val key = draggedLayerKey
                                    val originalOrder = treeRows.firstOrNull { it.layer.key == key }?.layer?.order
                                    val target = draggedLayerTargetOrder
                                    draggedLayerKey = null
                                    draggedLayerRow = null
                                    draggedLayerTargetOrder = null
                                    if (key != null && target != null && target != originalOrder) {
                                        onMoveLayer(key, target)
                                    }
                                },
                                onDragCancel = {
                                    draggedLayerKey = null
                                    draggedLayerRow = null
                                    draggedLayerTargetOrder = null
                                },
                            )
                        }
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
                            BASIC_LAYER_TYPES.forEach { (type, labelRes) ->
                                OutlinedButton(
                                    onClick = { onAddLayer(type) },
                                    enabled = busyMessage == null,
                                ) {
                                    Icon(MiuixIcons.Add, contentDescription = null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(labelRes))
                                }
                            }
                        }
                    }
                    if (snapshot.layers.isEmpty()) {
                        item("layers_empty") {
                            Text(
                                stringResource(R.string.watch_editor_no_layers),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        items(treeRows, key = { it.layer.key.value }) { row ->
                            val layer = row.layer
                            val draggedIndex = treeRows.indexOfFirst { it.layer.key == draggedLayerKey }
                            val targetIndex = treeRows.indexOfFirst {
                                it.layer.order == draggedLayerTargetOrder
                            }
                            val rowIndex = treeRows.indexOf(row)
                            val avoidanceOffsetY = when {
                                draggedIndex < 0 || targetIndex < 0 || rowIndex == draggedIndex -> 0f
                                draggedIndex < targetIndex && rowIndex in (draggedIndex + 1)..targetIndex ->
                                    -(draggedLayerHeightPx + with(layerDragDensity) { 8.dp.toPx() })
                                draggedIndex > targetIndex && rowIndex in targetIndex until draggedIndex ->
                                    draggedLayerHeightPx + with(layerDragDensity) { 8.dp.toPx() }
                                else -> 0f
                            }
                            val thumbSizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
                            // 缩略图展示「这一层用的素材」而不是渲染截图。
                            //
                            // 旧实现走 renderLayerThumbnail：按图层的真实 x/y/
                            // rotation 渲染，元素偏离中心时缩略图里就是一角，
                            // 而且 remember 的 key 里没有 order，上下移动层级后
                            // 根本不重算，缩略图停在原来那层上（老师报的问题）。
                            // 现在直接解码 path 指向的图片文件，形状层画色块。
                            val thumbnail = remember(
                                layer.attributes["path"],
                                layer.type,
                                previewToken,
                            ) {
                                resolveLayerAssetThumbnail(
                                    draftDirectory = draftDirectory,
                                    layer = layer,
                                    sizePx = thumbSizePx,
                                )
                            }
                            val textThumbnail = remember(
                                thumbnailRenderer,
                                layer.order,
                                layer.attributes,
                                previewToken,
                                textThumbnailClockTick,
                            ) {
                                if (layer.type.lowercase(Locale.ROOT).contains("text")) {
                                    thumbnailRenderer?.resolveTextLayerPreview(layer.order)
                                        ?: layer.attributes["text"].orEmpty()
                                            .ifBlank { layer.attributes["value"].orEmpty() }
                                            .takeUnless { it.contains('{') || it.contains('}') }
                                } else {
                                    null
                                }
                            }
                            val renderedTextThumbnail = remember(
                                thumbnailRenderer,
                                layer.order,
                                layer.attributes,
                                previewToken,
                                textThumbnailClockTick,
                                thumbSizePx,
                            ) {
                                if (layer.type.lowercase(Locale.ROOT) in
                                    setOf("text", "text_curved", "text_ring")
                                ) {
                                    thumbnailRenderer
                                        ?.renderTextLayerThumbnail(layer.order, thumbSizePx)
                                        ?.asImageBitmap()
                                } else {
                                    null
                                }
                            }
                            EditorLayerRow(
                                layer = layer,
                                thumbnail = renderedTextThumbnail ?: thumbnail,
                                textThumbnail = textThumbnail,
                                selected = layer.key == selectedLayer?.key,
                                layerCount = snapshot.layers.size,
                                depth = row.depth,
                                hasChildren = row.hasChildren,
                                expanded = row.expanded,
                                enabled = busyMessage == null,
                                modifier = Modifier.graphicsLayer {
                                    alpha = if (draggedLayerKey == layer.key) 0f else 1f
                                },
                                dragging = false,
                                dragOffsetY = 0f,
                                avoidanceOffsetY = avoidanceOffsetY,
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

                    val overlayRow = draggedLayerRow
                    if (overlayRow != null && draggedLayerKey == overlayRow.layer.key) {
                        val overlayLayer = overlayRow.layer
                        val overlayThumbSizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
                        val overlayThumbnail = remember(
                            overlayLayer.attributes["path"],
                            overlayLayer.type,
                            previewToken,
                        ) {
                            resolveLayerAssetThumbnail(
                                draftDirectory = draftDirectory,
                                layer = overlayLayer,
                                sizePx = overlayThumbSizePx,
                            )
                        }
                        val overlayText = remember(
                            thumbnailRenderer,
                            overlayLayer.order,
                            overlayLayer.attributes,
                            previewToken,
                            textThumbnailClockTick,
                        ) {
                            if (overlayLayer.type.lowercase(Locale.ROOT).contains("text")) {
                                thumbnailRenderer?.resolveTextLayerPreview(overlayLayer.order)
                                    ?: overlayLayer.attributes["text"].orEmpty()
                                        .ifBlank { overlayLayer.attributes["value"].orEmpty() }
                                        .takeUnless { it.contains('{') || it.contains('}') }
                            } else {
                                null
                            }
                        }
                        val overlayRenderedText = remember(
                            thumbnailRenderer,
                            overlayLayer.order,
                            overlayLayer.attributes,
                            previewToken,
                            textThumbnailClockTick,
                            overlayThumbSizePx,
                        ) {
                            if (overlayLayer.type.lowercase(Locale.ROOT) in
                                setOf("text", "text_curved", "text_ring")
                            ) {
                                thumbnailRenderer
                                    ?.renderTextLayerThumbnail(overlayLayer.order, overlayThumbSizePx)
                                    ?.asImageBitmap()
                            } else {
                                null
                            }
                        }
                        // LazyList 的 viewportStartOffset 会包含负的 contentPadding，
                        // 不能直接拿来限制浮层，否则第一行能被拖到面板裁剪线之外。
                        // 指针与这个兄弟浮层都使用 Box 的物理坐标系。
                        val viewportStart = 0f
                        val viewportEnd =
                            layerListState.layoutInfo.viewportSize.height.toFloat()
                        val overlayTop = (draggedLayerPointerY - draggedLayerTouchOffsetY)
                            .coerceIn(
                                viewportStart,
                                (viewportEnd - draggedLayerHeightPx).coerceAtLeast(viewportStart),
                            )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalListPadding)
                                .graphicsLayer { translationY = overlayTop }
                                .zIndex(20f),
                        ) {
                            EditorLayerRow(
                                layer = overlayLayer,
                                thumbnail = overlayRenderedText ?: overlayThumbnail,
                                textThumbnail = overlayText,
                                selected = true,
                                layerCount = snapshot.layers.size,
                                depth = overlayRow.depth,
                                hasChildren = overlayRow.hasChildren,
                                expanded = overlayRow.expanded,
                                enabled = false,
                                dragging = true,
                                dragOffsetY = 0f,
                                avoidanceOffsetY = 0f,
                                onSelect = {},
                                onToggleExpanded = {},
                                onMove = {},
                                onDuplicate = {},
                                onDelete = {},
                            )
                        }
                    }
                }

                WatchEditorPanel.PROPERTIES -> LazyColumn(
                    state = propertiesListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .flueElasticNestedScroll(propertiesListState),
                    contentPadding = listContentPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    editorStatusItems(busyMessage, errorMessage)
                    if (selectedLayer == null) {
                        item("no_selection") {
                            Text(
                                stringResource(R.string.watch_editor_select_layer_first),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        item("properties") {
                            LayerPropertiesEditor(
                                layer = selectedLayer,
                                draftResetKey = propertyDraftRevision,
                                enabled = busyMessage == null,
                                fontOptions = remember(snapshot.resourcePaths, selectedLayer.attributes["font"]) {
                                    buildList {
                                        addAll(snapshot.resourcePaths.filter { path ->
                                            path.substringAfterLast('.', "").lowercase(Locale.ROOT) in EDITOR_FONT_EXTENSIONS
                                        })
                                        addAll(snapshot.resourcePaths.filter { it.endsWith(".fnt", true) }.map {
                                            "bm:${it.substringAfterLast('/').substringBeforeLast('.')}"
                                        })
                                        selectedLayer.attributes["font"]?.takeIf { it.startsWith("bm:", true) }?.let(::add)
                                    }.distinctBy { it.lowercase(Locale.ROOT) }
                                },
                                onLiveChange = { changes ->
                                    onLiveAttributeChange(selectedLayer.key, changes)
                                },
                                onImportImage = onImportImage,
                                onImportImageLong = onImportImageLong,
                                onImportImageSequence = onImportImageSequence,
                                onImportImageSequenceLong = onImportImageSequenceLong,
                                onImportVideo = onImportVideo,
                                onImportVideoLong = onImportVideoLong,
                                onImportFont = onImportFont,
                                onImportFontLong = onImportFontLong,
                                onImportBitmapFont = onImportBitmapFont,
                                onImportBitmapFontLong = onImportBitmapFontLong,
                            )
                        }
                    }
                }

                WatchEditorPanel.WATCH -> LazyColumn(
                    state = watchListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .flueElasticNestedScroll(watchListState),
                    contentPadding = listContentPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    editorStatusItems(busyMessage, errorMessage)
                    item("watch_summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                snapshot.rootAttributes["name"].orEmpty().ifBlank {
                                    stringResource(R.string.watch_editor_unnamed_face)
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(
                                    R.string.watch_editor_layer_resource_count_fmt,
                                    snapshot.layers.size,
                                    snapshot.resourcePaths.size,
                                ),
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
                                Text(
                                    stringResource(R.string.watch_editor_live_data),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(
                                        if (bodySensorsPermissionGranted) {
                                            R.string.watch_editor_sensor_granted
                                        } else {
                                            R.string.watch_editor_sensor_not_granted
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!bodySensorsPermissionGranted) {
                                    OutlinedButton(
                                        onClick = {
                                            bodySensorsPermissionLauncher.launch(
                                                requiredHeartRatePermission(),
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.watch_editor_grant_sensor))
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
                                Icon(MiuixIcons.Backup, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.watch_editor_save_as))
                            }
                            OutlinedButton(
                                onClick = onSave,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(MiuixIcons.Backup, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.watch_editor_save_current))
                            }
                            OutlinedButton(
                                onClick = onRename,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.watch_editor_rename))
                            }
                            EditorLongPressImportButton(
                                label = stringResource(R.string.watch_editor_import_watch),
                                enabled = busyMessage == null,
                                onClick = onImportWatch,
                                onLongClick = onImportWatchLong,
                                showIcon = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            EditorLongPressImportButton(
                                label = stringResource(R.string.watch_editor_import_wff),
                                enabled = busyMessage == null,
                                onClick = onConvertWatch,
                                onLongClick = onConvertWatchLong,
                                showIcon = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            EditorLongPressImportButton(
                                label = stringResource(R.string.watch_editor_export_watch),
                                enabled = busyMessage == null,
                                onClick = onExport,
                                onLongClick = onExportLong,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick = onUploadToStore,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(MiuixIcons.UploadCloud, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.watch_editor_upload_store))
                            }
                        }
                    }
                    item("current_watch_size") {
                        Text(
                            text = stringResource(R.string.watch_editor_current_watch_size_fmt, formatEditorFileSize(draftSizeBytes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                }
                }
            }
        }
        // 画布全屏的退出浮钮（圆屏放顶部中央的弦内安全区）。
        androidx.compose.animation.AnimatedVisibility(
            visible = editorLayout == WatchEditorLayout.CANVAS_FULL,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(140)),
            modifier = Modifier
                .align(if (isRound) Alignment.TopCenter else Alignment.TopStart)
                .padding(top = if (isRound) 14.dp else 6.dp, start = if (isRound) 0.dp else 6.dp),
        ) {
            IconButton(onClick = { editorLayout = WatchEditorLayout.SPLIT }) {
                Icon(
                    FlueMiuixIcons.Minimize,
                    contentDescription = stringResource(R.string.watch_editor_exit_fullscreen),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

}

private enum class WatchEditorPanel(@StringRes val labelRes: Int) {
    LAYERS(R.string.watch_editor_panel_layers),
    PROPERTIES(R.string.watch_editor_panel_properties),
    WATCH(R.string.watch_editor_panel_watch),
}

/** 编辑器三档布局：画布全屏 / 分屏 / 面板全屏。 */
private enum class WatchEditorLayout {
    CANVAS_FULL,
    SPLIT,
    PANEL_FULL,
}

internal fun shouldHandleWatchEditorTransformEnd(cancelled: Boolean, busy: Boolean): Boolean =
    cancelled || !busy

/** A transform commit may update the snapshot, but must not resurrect an old focus. */
internal fun shouldKeepWatchEditorSelectionForTransformCommit(
    transformStartSelectionGeneration: Int,
    currentSelectionGeneration: Int,
): Boolean = transformStartSelectionGeneration == currentSelectionGeneration

/** Delayed property writes must never resurrect the layer that launched them. */
internal fun shouldKeepWatchEditorSelectionForAsyncCommit(
    commitStartSelectionGeneration: Int,
    currentSelectionGeneration: Int,
): Boolean = commitStartSelectionGeneration == currentSelectionGeneration

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
 * Serializes the in-memory transform transaction with its asynchronous draft
 * commit. The session itself can only hold one transform gesture, so a new
 * pointer-down must be rejected until the previous commit has finished its
 * `flushDraftForPreview()` call.
 */
internal class WatchEditorTransformGate {
    private enum class Phase {
        IDLE,
        GESTURE,
        COMMIT,
    }

    private var phase = Phase.IDLE

    @Synchronized
    fun tryBeginGesture(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.GESTURE
        return true
    }

    @Synchronized
    fun hasActiveGesture(): Boolean = phase == Phase.GESTURE

    @Synchronized
    fun beginCommit(): Boolean {
        if (phase != Phase.GESTURE) return false
        phase = Phase.COMMIT
        return true
    }

    @Synchronized
    fun finishCommit() {
        if (phase == Phase.COMMIT) phase = Phase.IDLE
    }

    @Synchronized
    fun cancelGesture() {
        if (phase == Phase.GESTURE) phase = Phase.IDLE
    }

    @Synchronized
    fun isBusy(): Boolean = phase != Phase.IDLE
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
                MiuixLoadingIndicator(size = 18.dp)
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
    descriptor: WatchFaceDescriptor,
    refreshToken: Int,
    bodySensorsPermissionGranted: Boolean,
    layers: List<WatchEditorLayer>,
    selectedLayer: WatchEditorLayer?,
    transformPreview: WatchEditorTransformPreview?,
    enabled: Boolean,
    imageSizeLookup: (String) -> Pair<Float, Float>?,
    onSelectFromCanvas: (WatchEditorLayerKey) -> Unit,
    onCanvasTap: (WatchEditorLayerKey?) -> Unit,
    onBeginTransform: (WatchEditorLayerKey) -> WatchEditorTransform?,
    onUpdateTransform: (WatchEditorLayerKey, WatchEditorTransform) -> Unit,
    onEndTransform: (WatchEditorLayerKey, Boolean) -> Unit,
    onRefreshReady: (Int) -> Unit,
    onRefreshFailed: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onFaceViewChanged: (WatchmakerWatchFaceView?) -> Unit = {},
    /**
     * 声明画布的设计空间尺寸。命中测试 / 选中框必须和渲染器同源，
     * 否则非方画布上点选和拖动整体错位。
     */
    designExtentWidth: Float = WatchmakerGeometry.DESIGN_SIZE,
    designExtentHeight: Float = WatchmakerGeometry.DESIGN_SIZE,
) {
    val context = LocalContext.current
    var loadError by remember(descriptor.stableKey, refreshToken) { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 图层自身尺寸、父子关系和基础变换在一次手势里都不变。循环背景的 path
    // 可能非常长，不能因为拖动坐标每帧变化就重新解析所有图层几何。
    val baseGeometries = remember(layers, imageSizeLookup) {
        buildWatchEditorLayerGeometries(layers, imageSizeLookup)
    }
    val geometries = remember(baseGeometries, transformPreview) {
        val preview = transformPreview ?: return@remember baseGeometries
        baseGeometries.map { geometry ->
            if (geometry.key == preview.key) geometry.copy(transform = preview.transform) else geometry
        }
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
    val animatedPreviewLayerIndex = selectedLayer
        ?.takeIf { layer ->
            layer.type.equals("gif", ignoreCase = true) ||
                layer.type.equals("image_gif", ignoreCase = true) ||
                '`' in layer.attributes["path"].orEmpty()
        }
        ?.order
    val dataProvider = rememberWatchmakerWatchDataProvider(
        active = true,
        permissionRevision = bodySensorsPermissionGranted,
    )
    Box(
        modifier = modifier
            // 不再锁 aspectRatio(1f)：容器尺寸由调用方按声明画布比例用
            // .size(w, h) 定死，这里再压一个 1:1 会把非方画布拉回正方形。
            .background(Color.Black)
            // Compose 的 DrawScope 默认不裁剪到节点边界：选中框/旋转手柄
            // 一旦算出画布外坐标就会直接画到 TabRow/面板上。在画布根裁一刀。
            .clipToBounds()
            .onSizeChanged { canvasSize = it },
        contentAlignment = Alignment.Center,
    ) {
        var faceView by remember { mutableStateOf<WatchmakerWatchFaceView?>(null) }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WatchmakerWatchFaceView(context).also {
                    faceView = it
                    onFaceViewChanged(it)
                }
            },
            update = { view ->
                view.setWatchDataProvider(dataProvider)
                view.setEditorAnimatedImageLayer(animatedPreviewLayerIndex)
                // 拖动任意元素时都暂停循环素材；否则它的 30fps 解码/刷新仍会和
                // 当前元素的跟手重绘竞争，即使拖动目标本身不是循环背景。
                view.setEditorAnimationInteractionFrozen(transformPreview != null)
                view.bind(
                    descriptor = descriptor,
                    refreshToken = refreshToken,
                    isFaceVisible = true,
                    onLongPress = null,
                    onDoubleTap = null,
                    onLoadFailure = { _, error ->
                        loadError = context.localizedEditorError(R.string.watch_editor_error_preview_load, error)
                        onRefreshFailed(refreshToken)
                    },
                    onSourceReady = onRefreshReady,
                    // 编辑器预览始终按表盘 XML 播放，和启动器全局开关独立。
                    textEntryAnimationEnabled = true,
                    textExitAnimationEnabled = true,
                )
            },
            onRelease = { view ->
                if (faceView === view) faceView = null
                onFaceViewChanged(null)
                view.dispose()
            },
        )
        // 拖动期间把手势变换逐帧喂给渲染器：元素和选中框一起动，
        // 不再是「只有框在动、松手后像素才跳过去」。
        LaunchedEffect(transformPreview, layers, faceView) {
            val view = faceView ?: return@LaunchedEffect
            val preview = transformPreview
            if (preview == null) {
                view.setEditorAnimationInteractionFrozen(false)
                view.clearLiveLayerTransform()
                return@LaunchedEffect
            }
            val index = layers.firstOrNull { it.key == preview.key }?.order
                ?: layers.indexOfFirst { it.key == preview.key }
            if (index < 0) {
                view.setEditorAnimationInteractionFrozen(false)
                view.clearLiveLayerTransform()
                return@LaunchedEffect
            }
            view.setEditorAnimationInteractionFrozen(true)
            val transform = preview.transform
            view.setLiveLayerTransform(
                layerIndex = index,
                x = transform.x,
                y = transform.y,
                rotationDegrees = transform.rotation,
                scaleX = transform.scaleX,
                scaleY = transform.scaleY,
            )
        }
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
                        // 双轴 mapping：与渲染器同一套等比 + 中心反投口径。
                        // 旧的单标量 viewSize 假设容器是正方形，非方画布上
                        // y 轴会整体偏移（512 画布下 320×385 那档偏 32.5px）。
                        val measured = latestCanvasSize.value
                        val mapping = WatchEditorCanvasMapping(
                            viewWidthPx = measured.width.takeIf { it > 0 }?.toFloat()
                                ?: size.width.toFloat().coerceAtLeast(1f),
                            viewHeightPx = measured.height.takeIf { it > 0 }?.toFloat()
                                ?: size.height.toFloat().coerceAtLeast(1f),
                            designWidth = designExtentWidth,
                            designHeight = designExtentHeight,
                        )
                        val currentGeometries = latestGeometries.value
                        val matrices = buildWatchEditorWorldMatrices(currentGeometries)
                        val downDesignX = mapping.designX(down.position.x)
                        val downDesignY = mapping.designY(down.position.y)
                        val handleRadiusDesign =
                            mapping.designLength(EDITOR_HANDLE_TOUCH_DP.dp.toPx())
                        val rotationOffsetDesign =
                            mapping.designLength(EDITOR_ROTATION_HANDLE_OFFSET_DP.dp.toPx())
                        val hitSlopDesign =
                            mapping.designLength(EDITOR_LAYER_HIT_SLOP_DP.dp.toPx())

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
                        // 已选中元素独占拖动（老师指定）：按下点可在画布任意位置，
                        // 只要越过 touch slop 就拖它。只有「单击」另一个元素
                        // （按下→没超过 touchSlop→抬手）才切换选择。
                        // 换到抬手时机切换，靠 pendingSelectKey 记住候选。
                        var pendingSelectKey: WatchEditorLayerKey? = null
                        if (mode == EditorCanvasGestureMode.NONE && selectedIndex >= 0) {
                            mode = EditorCanvasGestureMode.BODY
                            targetIndex = selectedIndex
                            targetKey = currentGeometries[selectedIndex].key
                            // 命中另一个图层只记录为单击候选；滑动时焦点和拖动
                            // 目标都保持为先前选中的图层。空白则没有候选，轻点后
                            // 也不会清空已有焦点。
                            val topHit = hitTestWatchEditorLayers(
                                geometries = currentGeometries,
                                worldMatrices = matrices,
                                x = downDesignX,
                                y = downDesignY,
                                slopDesign = hitSlopDesign,
                            )
                            val topKey = topHit?.let { currentGeometries[it].key }
                            if (topKey != null && topKey != selectedKey) {
                                pendingSelectKey = topKey
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
                                // 未选中任何元素时按下即选中：此时不存在
                                // 「抢走正在拖的元素」问题。
                                if (hitKey != selectedKey) {
                                    latestSelect.value(hitKey)
                                }
                            }
                        }

                        val touchSlop = viewConfiguration.touchSlop
                        var moved = false
                        var completedNormally = false

                        if (mode == EditorCanvasGestureMode.NONE) {
                            // 没有选中图层时，空白点击没有可更新的焦点。
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
                                    return@awaitEachGesture
                                }
                            }
                        }

                        val key = targetKey ?: return@awaitEachGesture
                        // DOWN only records the tap/drag candidate. The
                        // transform transaction starts after touch slop is
                        // crossed; otherwise a tiny finger wobble during a tap
                        // can write x/y/scale and enqueue an async commit.
                        var startTransform: WatchEditorTransform? = null
                        var current: WatchEditorTransform? = null
                        try {
                            down.consume()
                            // 真实累积位置：必须和「吸附后用于显示的位置」分开记。
                            // 早先直接拿上一帧吸附结果当基准累加，吸住之后每帧
                            // 都是「吸附点 + 小增量」，仍落在阈值内又被锁回原点，
                            // 于是元素彻底拖不动（老师报的粘住）。
                            var rawAccumX = 0f
                            var rawAccumY = 0f
                            var accumulatedRotationDelta = 0f
                            var rotationActivated = false
                            val snapState = WatchEditorSnapping.GestureState()
                            val rotationSnapState = WatchEditorSnapping.RotationGestureState()
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) break

                                if (!moved) {
                                    moved = event.changes.any { change ->
                                        hypot(
                                            change.position.x - down.position.x,
                                            change.position.y - down.position.y,
                                        ) > touchSlop
                                    }
                                }
                                if (moved && startTransform == null) {
                                    // A stale key (for example a layer deleted
                                    // in this frame) simply abandons the drag.
                                    val initial = runCatching {
                                        latestBegin.value(key)
                                    }.getOrNull() ?: break
                                    startTransform = initial
                                    current = initial
                                    rawAccumX = initial.x
                                    rawAccumY = initial.y
                                    lifecycle.begin()
                                }

                                val base = current
                                val gestureStart = startTransform
                                if (gestureStart != null) when (mode) {
                                    EditorCanvasGestureMode.BODY -> {
                                        val pan = event.calculatePan()
                                        val zoom = event.calculateZoom()
                                        val rotationDelta = event.calculateRotation()
                                        accumulatedRotationDelta += rotationDelta
                                        if (
                                            !rotationActivated &&
                                            kotlin.math.abs(accumulatedRotationDelta) >=
                                                WatchEditorSnapping.ROTATION_ACTIVATION_THRESHOLD_DEG
                                        ) {
                                            rotationActivated = true
                                        }
                                        if (
                                            base != null &&
                                            (pan != Offset.Zero || zoom != 1f || rotationActivated)
                                        ) {
                                            val designDeltaX = mapping.designLength(pan.x)
                                            val designDeltaY = mapping.designLength(pan.y)
                                            val parentDelta = watchEditorDragDeltaInParentFrame(
                                                geometries = currentGeometries,
                                                worldMatrices = matrices,
                                                layerIndex = targetIndex,
                                                deltaX = designDeltaX,
                                                deltaY = designDeltaY,
                                            )
                                            // 累加到真实位置上，而不是累加到吸附结果上。
                                            rawAccumX += parentDelta.x
                                            rawAccumY += parentDelta.y
                                            // 吸附辅助：中心 / 与其他元素对齐 / 等距中点。
                                            // peer 坐标取自同批 geometries，排除正在拖的这个。
                                            val peerXs = currentGeometries
                                                .filter { it.key != key }
                                                .map { it.transform.x }
                                                .toFloatArray()
                                            val peerYs = currentGeometries
                                                .filter { it.key != key }
                                                .map { it.transform.y }
                                                .toFloatArray()
                                            val snapped = WatchEditorSnapping.snapTransform(
                                                x = rawAccumX,
                                                y = rawAccumY,
                                                rotation = base.rotation,
                                                peerXs = peerXs,
                                                peerYs = peerYs,
                                                state = snapState,
                                                // 双指手势不动旋转，所以这里也不吸附角度
                                                snapRotationToo = false,
                                            )
                                            val next = base.copy(
                                                x = snapped.x,
                                                y = snapped.y,
                                                scaleX = base.scaleX * zoom,
                                                scaleY = base.scaleY * zoom,
                                                rotation = if (rotationActivated) {
                                                    WatchEditorSnapping.snapRightAngleRotation(
                                                        rotation = gestureStart.rotation +
                                                            accumulatedRotationDelta,
                                                        state = rotationSnapState,
                                                    )
                                                } else {
                                                    gestureStart.rotation
                                                },
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
                                                startTransform = gestureStart,
                                                startX = downDesignX,
                                                startY = downDesignY,
                                                currentX = mapping.designX(change.position.x),
                                                currentY = mapping.designY(change.position.y),
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
                                // 单击才切换选择：按下时若有更上层的候选元素，
                                // 这时候才切过去（拖动过就不切，元素留在手上）。
                                val switchTo = pendingSelectKey
                                if (switchTo != null) {
                                    latestSelect.value(switchTo)
                                    latestTap.value(switchTo)
                                } else {
                                    // A motionless press is a tap: jump to the
                                    // layer in the list (or leave fullscreen).
                                    latestTap.value(key)
                                }
                            }
                        }
                    }
                },
        )
        val selectedGeometryIndex = geometries.indexOfFirst { it.key == selectedLayer?.key }
        // Pointer input is briefly disabled while the DOM is flushed, but the
        // release-frame outline must stay visible with the live transform. If
        // it vanished here, scaling/rotation would still look like a one-frame
        // snap even though the native layer pixels no longer roll back.
        if (
            (enabled || transformPreview != null) &&
            selectedGeometryIndex >= 0 &&
            canvasSize.width > 0
        ) {
            val matrices = remember(geometries) { buildWatchEditorWorldMatrices(geometries) }
            EditorSelectionOverlay(
                box = watchEditorSelectionBox(
                    geometries[selectedGeometryIndex],
                    matrices[selectedGeometryIndex],
                ),
                modifier = Modifier.fillMaxSize(),
                designExtentWidth = designExtentWidth,
                designExtentHeight = designExtentHeight,
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
    val descriptor: WatchFaceDescriptor,
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
    designExtentWidth: Float = WatchmakerGeometry.DESIGN_SIZE,
    designExtentHeight: Float = WatchmakerGeometry.DESIGN_SIZE,
) {
    Canvas(modifier = modifier) {
        // 必须和手势用同一个 mapping：一边等比中心反投、一边单轴按宽换算，
        // 选中框会和实际可拖区域错开。
        val mapping = WatchEditorCanvasMapping(
            viewWidthPx = size.width.coerceAtLeast(1f),
            viewHeightPx = size.height.coerceAtLeast(1f),
            designWidth = designExtentWidth,
            designHeight = designExtentHeight,
        )
        fun toView(point: com.flue.launcher.watchface.editor.WatchEditorPoint): Offset = Offset(
            mapping.viewX(point.x),
            mapping.viewY(point.y),
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

        val rotationOffsetDesign =
            mapping.designLength(EDITOR_ROTATION_HANDLE_OFFSET_DP.dp.toPx())
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
 * can be moved, edge/corner-resized and pinch-scaled. Decoding is downsampled so a
 * large camera picture never materialises at full resolution.
 */
@Composable
private fun EditorImageCropOverlay(
    uri: Uri,
    canvasSize: WatchCanvasSize,
    onCancel: () -> Unit,
    onConfirm: (WatchEditorSourceRect, preserveOriginal: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val isRound = isFlueScreenRound()
    var preview by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var sourceSize by remember(uri) { mutableStateOf<IntSize?>(null) }
    var decodeError by remember(uri) { mutableStateOf<String?>(null) }
    var aspect by remember(uri) { mutableStateOf(WatchEditorCropAspect.FREE) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    var cropRect by remember(uri) { mutableStateOf<WatchEditorCropRect?>(null) }
    var cropModified by remember(uri) { mutableStateOf(false) }
    val displayMetrics = context.resources.displayMetrics
    val aspectRatioValue = watchEditorCropAspectRatio(
        aspect,
        displayMetrics.widthPixels,
        displayMetrics.heightPixels,
        canvasSize.width,
        canvasSize.height,
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
            decodeError = context.localizedEditorError(R.string.watch_editor_error_decode_image, error)
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
            stringResource(R.string.watch_editor_crop_image),
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
                bitmap == null -> MiuixLoadingIndicator(Modifier.align(Alignment.Center))
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
                                        if (next != currentRect) {
                                            cropModified = true
                                            cropRect = next
                                        }
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
                val label = stringResource(watchEditorCropAspectLabel(candidate))
                if (selected) {
                    Button(onClick = {}, enabled = true) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = {
                            cropModified = true
                            aspect = candidate
                        },
                    ) { Text(label) }
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
                Icon(MiuixIcons.Close, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.watch_editor_cancel))
            }
            Button(
                onClick = {
                    val rect = cropRect
                    val bounds = fit
                    val source = sourceSize
                    if (rect != null && bounds != null && source != null) {
                        onConfirm(
                            mapWatchEditorCropToSource(rect, bounds, source.width, source.height),
                            !cropModified,
                        )
                    }
                },
                enabled = cropRect != null && fit != null && sourceSize != null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(MiuixIcons.Ok, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(
                        if (cropModified) {
                            R.string.watch_editor_import_cropped
                        } else {
                            R.string.watch_editor_import_original
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun EditorVideoConversionProgressDialog(
    progress: Float,
    onCancel: () -> Unit,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.watch_editor_converting_video)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(
                    progress = { safeProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${(safeProgress * 100f).roundToInt()}%")
                Text(
                    stringResource(R.string.watch_editor_video_conversion_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.watch_editor_cancel_conversion))
            }
        },
    )
}

@StringRes
private fun watchEditorCropAspectLabel(aspect: WatchEditorCropAspect): Int = when (aspect) {
    WatchEditorCropAspect.FREE -> R.string.watch_editor_crop_free
    WatchEditorCropAspect.SQUARE -> R.string.watch_editor_crop_square
    WatchEditorCropAspect.SCREEN -> R.string.watch_editor_crop_screen
    WatchEditorCropAspect.CANVAS -> R.string.watch_editor_crop_canvas
}

@Composable
private fun EditorLayerRow(
    layer: WatchEditorLayer,
    selected: Boolean,
    layerCount: Int,
    thumbnail: androidx.compose.ui.graphics.ImageBitmap? = null,
    textThumbnail: String? = null,
    depth: Int,
    hasChildren: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    dragging: Boolean,
    dragOffsetY: Float,
    avoidanceOffsetY: Float,
    onSelect: () -> Unit,
    onToggleExpanded: () -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val localizedLayerType = watchEditorLayerTypeLabel(layer.type)
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 2f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY + avoidanceOffsetY
                shadowElevation = if (dragging) 10.dp.toPx() else 0f
            }
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
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (expanded) MiuixIcons.ExpandMore else MiuixIcons.ChevronForward,
                        contentDescription = stringResource(
                            if (expanded) {
                                R.string.watch_editor_collapse_child_layers
                            } else {
                                R.string.watch_editor_expand_child_layers
                            },
                        ),
                    )
                }
            } else {
                Spacer(Modifier.size(8.dp))
            }
            // 素材缩略图；文字则使用运行时解析值单独居中排版，不继承画布位置。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val type = layer.type.lowercase(Locale.ROOT)
                    when {
                        type.contains("text") -> {
                            Text(
                                text = textThumbnail.orEmpty().ifBlank { "T" },
                                color = Color.White,
                                fontSize = 8.sp,
                                lineHeight = 9.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(3.dp),
                            )
                        }
                        type.contains("shape") || type.contains("rounded") -> {
                            val color = layer.attributes["color"]
                                ?.let { raw -> runCatching { Color(android.graphics.Color.parseColor(raw)) }.getOrNull() }
                                ?: Color(0xFF6E8DF5)
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(6.dp)
                                    .background(color, RoundedCornerShape(6.dp)),
                            )
                        }
                        else -> Icon(
                            MiuixIcons.File,
                            contentDescription = stringResource(
                                R.string.watch_editor_layer_thumbnail_fmt,
                                localizedLayerType,
                            ),
                            tint = Color.White,
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("${layer.order + 1}. $localizedLayerType", fontWeight = FontWeight.Medium)
                layer.id?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                layer.parentId?.let {
                    Text(
                        stringResource(R.string.watch_editor_parent_layer_fmt, it),
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
                Icon(
                    FlueMiuixIcons.ArrowUp,
                    contentDescription = stringResource(R.string.watch_editor_move_up),
                )
            }
            IconButton(
                onClick = { onMove(layer.order + 1) },
                enabled = enabled && layer.order < layerCount - 1,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    FlueMiuixIcons.ArrowDown,
                    contentDescription = stringResource(R.string.watch_editor_move_down),
                )
            }
            IconButton(onClick = onDuplicate, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(
                    MiuixIcons.Copy,
                    contentDescription = stringResource(R.string.watch_editor_duplicate),
                )
            }
            IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(
                    MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.watch_editor_delete),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorLongPressImportButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showIcon: Boolean = false,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        modifier = modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        color = MaterialTheme.colorScheme.surface,
        contentColor = contentColor,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, contentColor.copy(alpha = if (enabled) 1f else 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                Icon(MiuixIcons.File, contentDescription = null)
                Spacer(Modifier.width(6.dp))
            }
            Text(label)
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
    onImportImageLong: () -> Unit,
    onImportImageSequence: () -> Unit,
    onImportImageSequenceLong: () -> Unit,
    onImportVideo: () -> Unit,
    onImportVideoLong: () -> Unit,
    fontOptions: List<String> = emptyList(),
    onImportFont: (() -> Unit)? = null,
    onImportFontLong: (() -> Unit)? = null,
    onImportBitmapFont: (() -> Unit)? = null,
    onImportBitmapFontLong: (() -> Unit)? = null,
) {
    val localizedLayerType = watchEditorLayerTypeLabel(layer.type)
    val editableAttributes = remember(layer.type) { editableAttributesFor(layer.type) }
    // Drafts survive live applies (draftResetKey stays put) but reset whenever
    // the attributes change from the outside: undo, canvas drags, imports.
    var drafts by remember(layer.key, draftResetKey) {
        mutableStateOf(
            editableAttributes.associateWith { attribute ->
                val text = layer.attributes[attribute].orEmpty()
                TextFieldValue(text, TextRange(text.length))
            },
        )
    }
    var expandedAttributePicker by remember(layer.key) { mutableStateOf<String?>(null) }
    var colorPickerVisible by remember(layer.key) { mutableStateOf(false) }

    fun liveValue(name: String, value: String): String? =
        if (value.isEmpty() && name != "text") null else value

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.watch_editor_layer_properties_fmt, localizedLayerType),
            fontWeight = FontWeight.SemiBold,
        )
        editableAttributes.forEach { attribute ->
            val hasAnimationPicker = attribute == "anim_in" || attribute == "anim_out"
            val hasColorPicker = attribute == "color"
            val hasExpressionPicker = attribute in WATCH_EXPRESSION_ATTRIBUTES && !hasColorPicker
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = drafts[attribute] ?: TextFieldValue(""),
                    onValueChange = { value ->
                        // 点按只改选区也会触发 onValueChange：以前这里无差别
                        // take(512) 并立刻回写，超长的 Watchmaker 表达式点一下
                        // 就被截断毁掉。不再截断；只有文本真变化才上报。
                        val previous = drafts[attribute] ?: TextFieldValue("")
                        drafts = drafts + (attribute to value)
                        if (value.text != previous.text) {
                            onLiveChange(mapOf(attribute to liveValue(attribute, value.text)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    label = { Text(stringResource(attributeLabelRes(attribute))) },
                    singleLine = attribute != "text",
                    minLines = if (attribute == "text") 2 else 1,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (attribute in NUMERIC_ATTRIBUTES) {
                            KeyboardType.Decimal
                        } else {
                            KeyboardType.Text
                        },
                    ),
                    trailingIcon = when {
                        hasColorPicker -> {
                            {
                                IconButton(
                                    onClick = { colorPickerVisible = true },
                                    enabled = enabled,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                parseEditorHexColor(
                                                    drafts[attribute]?.text.orEmpty(),
                                                ) ?: Color.DarkGray,
                                            ),
                                    )
                                }
                            }
                        }
                        hasAnimationPicker || hasExpressionPicker -> {
                            {
                                IconButton(
                                    onClick = {
                                        expandedAttributePicker =
                                            attribute.takeUnless { expandedAttributePicker == attribute }
                                    },
                                    enabled = enabled,
                                ) {
                                    // 纯下箭头，不使用带中间竖线的导入/下载类图标。
                                    Icon(
                                        MiuixIcons.ExpandMore,
                                        contentDescription = stringResource(
                                            R.string.watch_editor_expand_expression_reference,
                                        ),
                                    )
                                }
                            }
                        }
                        else -> null
                    },
                )
                if (expandedAttributePicker == attribute) {
                    if (hasAnimationPicker) {
                        InlineAnimationOptionsPanel(
                            selectedValue = drafts[attribute]?.text.orEmpty(),
                            enabled = enabled,
                            onSelect = { value ->
                                val next = TextFieldValue(value, TextRange(value.length))
                                if (attribute == "anim_in") {
                                    val restart = if (value.isBlank()) "N" else "Y"
                                    drafts = drafts + mapOf(
                                        attribute to next,
                                        "restart_on_load" to TextFieldValue(
                                            restart,
                                            TextRange(restart.length),
                                        ),
                                    )
                                    onLiveChange(
                                        mapOf(
                                            attribute to value.ifBlank { null },
                                            "restart_on_load" to restart,
                                        ),
                                    )
                                } else {
                                    drafts = drafts + (attribute to next)
                                    onLiveChange(mapOf(attribute to value.ifBlank { null }))
                                }
                                expandedAttributePicker = null
                            },
                        )
                    } else {
                        ExpressionReferencePanel(
                            enabled = enabled,
                            onInsert = { tag ->
                                val currentValue = drafts[attribute] ?: TextFieldValue("")
                                val (newText, cursor) = insertWatchEditorExpression(
                                    text = currentValue.text,
                                    selectionStart = currentValue.selection.start,
                                    selectionEnd = currentValue.selection.end,
                                    tag = tag,
                                )
                                val next = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(cursor.coerceAtMost(newText.length)),
                                )
                                drafts = drafts + (attribute to next)
                                onLiveChange(mapOf(attribute to liveValue(attribute, next.text)))
                                expandedAttributePicker = null
                            },
                        )
                    }
                }
            }
        }
        val supportsAnimatedImageImport = layer.type.equals("image", ignoreCase = true) ||
            layer.type.equals("image_gif", ignoreCase = true) ||
            layer.type.equals("gif", ignoreCase = true)
        if (layer.type.equals("image", ignoreCase = true)) {
            EditorLongPressImportButton(
                label = stringResource(R.string.watch_editor_import_image_path),
                enabled = enabled,
                onClick = onImportImage,
                onLongClick = onImportImageLong,
            )
        }
        if (supportsAnimatedImageImport) {
            EditorLongPressImportButton(
                label = stringResource(R.string.watch_editor_import_image_sequence),
                enabled = enabled,
                onClick = onImportImageSequence,
                onLongClick = onImportImageSequenceLong,
            )
            EditorLongPressImportButton(
                label = stringResource(R.string.watch_editor_import_video_frames),
                enabled = enabled,
                onClick = onImportVideo,
                onLongClick = onImportVideoLong,
            )
        }
        // 颜色输入框右侧的色块直接打开取色器，并写回同一个 draft + live 通道。
        if (colorPickerVisible) {
            EditorColorPickerDialog(
                initial = parseEditorHexColor(drafts["color"]?.text.orEmpty()) ?: Color.White,
                onDismiss = { colorPickerVisible = false },
                onConfirm = { color ->
                    colorPickerVisible = false
                    val hex = editorColorToHex(color)
                    val newValue = TextFieldValue(hex, TextRange(hex.length))
                    drafts = drafts + ("color" to newValue)
                    onLiveChange(mapOf("color" to liveValue("color", hex)))
                },
            )
        }
        if (layer.type.lowercase(Locale.ROOT) in FONT_CAPABLE_LAYER_TYPES) {
            EditorFontSelector(
                current = drafts["font"]?.text
                    ?: layer.attributes["font"].orEmpty(),
                options = fontOptions,
                enabled = enabled,
                onSelect = { name ->
                    onLiveChange(mapOf("font" to name.ifBlank { null }))
                },
                onImportFont = onImportFont,
                onImportFontLong = onImportFontLong,
                onImportBitmapFont = onImportBitmapFont,
                onImportBitmapFontLong = onImportBitmapFontLong,
            )
            EditorOutlineSection(
                layer = layer,
                draftResetKey = draftResetKey,
                enabled = enabled,
                onLiveChange = onLiveChange,
            )
        }
        // ──────── 点击动作 ────────
        run {
            val context = androidx.compose.ui.platform.LocalContext.current
            val currentRaw = drafts["tap_action"]?.text
                ?: layer.attributes["tap_action"].orEmpty()
            var tapActionText by remember(layer.key, draftResetKey) {
                mutableStateOf(TextFieldValue(currentRaw, TextRange(currentRaw.length)))
            }
            var showAppPicker by remember { mutableStateOf(false) }
            // 懒加载已安装应用（仅打开选择器时加载）
            var installedApps by remember {
                mutableStateOf<List<Triple<String, String, String>>>(emptyList()) // (label, pkg, launchActivity)
            }
            var appPickerQuery by remember { mutableStateOf("") }

            fun applyAction(raw: String) {
                tapActionText = TextFieldValue(raw, TextRange(raw.length))
                onLiveChange(mapOf("tap_action" to raw.ifBlank { null }))
            }

            if (showAppPicker) {
                val filtered = remember(installedApps, appPickerQuery) {
                    if (appPickerQuery.isBlank()) installedApps
                    else installedApps.filter {
                        it.first.contains(appPickerQuery, ignoreCase = true) ||
                            it.second.contains(appPickerQuery, ignoreCase = true)
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (installedApps.isEmpty()) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val pm = context.packageManager
                            val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            installedApps = pm
                                .queryIntentActivities(launchIntent, 0)
                                .mapNotNull { info ->
                                    val label = info.loadLabel(pm).toString()
                                    val pkg = info.activityInfo.packageName
                                    val activity = info.activityInfo.name
                                    if (pkg.isNotBlank() && activity.isNotBlank()) {
                                        Triple(label, pkg, activity)
                                    } else null
                                }
                                .sortedBy { (l, _, _) -> l.lowercase() }
                        }
                    }
                }
                AlertDialog(
                    onDismissRequest = { showAppPicker = false; appPickerQuery = "" },
                    title = { Text(stringResource(R.string.watch_editor_select_app_title)) },
                    text = {
                        Column {
                            androidx.compose.material3.OutlinedTextField(
                                value = appPickerQuery,
                                onValueChange = { appPickerQuery = it },
                                placeholder = {
                                    Text(stringResource(R.string.watch_editor_search_app_hint))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.height(280.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(filtered, key = { it.second + "/" + it.third }) { (label, pkg, activity) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                applyAction("w_app:$label`$pkg`$activity")
                                                showAppPicker = false
                                                appPickerQuery = ""
                                            }
                                            .padding(horizontal = 4.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(label, modifier = Modifier.weight(1f))
                                        Text(
                                            pkg,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAppPicker = false; appPickerQuery = "" }) {
                            Text(stringResource(R.string.watch_editor_cancel))
                        }
                    },
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.watch_editor_tap_action),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutlinedTextField(
                        value = tapActionText,
                        onValueChange = { v ->
                            tapActionText = v
                            onLiveChange(mapOf("tap_action" to v.text.ifBlank { null }))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        label = { Text(stringResource(R.string.watch_editor_tap_action)) },
                        placeholder = { Text(stringResource(R.string.watch_editor_tap_action_example)) },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = { showAppPicker = true },
                            enabled = enabled,
                        ) { Text(stringResource(R.string.watch_editor_select_app)) }
                        OutlinedButton(
                            onClick = { applyAction("widget_calendar") },
                            enabled = enabled,
                        ) { Text(stringResource(R.string.watch_editor_calendar)) }
                        OutlinedButton(
                            onClick = { applyAction("widget_weather") },
                            enabled = enabled,
                        ) { Text(stringResource(R.string.watch_editor_weather)) }
                        if (currentRaw.isNotBlank()) {
                            OutlinedButton(
                                onClick = { applyAction("") },
                                enabled = enabled,
                            ) { Text(stringResource(R.string.watch_editor_clear)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Inline reference of common Watchmaker tags for one concrete property field.
 */
@Composable
private fun ExpressionReferencePanel(
    enabled: Boolean,
    onInsert: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.watch_editor_expression_reference),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
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
                                stringResource(group.titleRes),
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
                                                stringResource(entry.descriptionRes),
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

@Composable
private fun InlineAnimationOptionsPanel(
    selectedValue: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TEXT_ENTRY_ANIMATION_OPTIONS.forEach { (value, labelRes) ->
                if (selectedValue == value) {
                    Button(onClick = { onSelect(value) }, enabled = enabled) {
                        Text(stringResource(labelRes))
                    }
                } else {
                    OutlinedButton(onClick = { onSelect(value) }, enabled = enabled) {
                        Text(stringResource(labelRes))
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
    val notSetLabel = stringResource(R.string.watch_editor_not_set)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.watch_editor_watch_shape), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.watch_editor_watch_shape_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WATCH_SHAPE_OPTIONS.forEach { (code, labelRes) ->
                    val selected = when (code) {
                        "R" -> currentShape == WatchFaceShape.ROUND
                        else -> currentShape == WatchFaceShape.SQUARE
                    }
                    if (selected) {
                        Button(
                            onClick = {},
                            enabled = true,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(labelRes)) }
                    } else {
                        OutlinedButton(
                            onClick = { onUpdateShape(code) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(labelRes)) }
                    }
                }
            }
            if (currentShape != WatchFaceShape.ROUND && currentShape != WatchFaceShape.SQUARE) {
                Text(
                    stringResource(
                        R.string.watch_editor_unknown_shape_fmt,
                        rawShape.orEmpty().ifBlank { notSetLabel },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = style.secondaryTextColor,
                )
            }
        }
    }
}

/**
 * 表盘级画布尺寸卡片。历史表盘根节点没有 width / height，这里显示回落值
 * 512×512 并标注「未声明」，改完写回根属性。
 */
@Composable
private fun EditorWatchCanvasSizeCard(
    canvasSize: WatchCanvasSize,
    screenSize: WatchCanvasSize,
    declared: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    val style = LauncherTheme.style
    val undeclaredSuffix = stringResource(R.string.watch_editor_canvas_undeclared_suffix)
    val matchesScreenSuffix = stringResource(R.string.watch_editor_canvas_matches_screen_suffix)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.watch_editor_canvas_size), fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append(canvasSize.label)
                    if (!declared) append(undeclaredSuffix)
                    if (canvasSize == screenSize) append(matchesScreenSuffix)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.watch_editor_canvas_size_description),
                style = MaterialTheme.typography.labelSmall,
                color = style.secondaryTextColor,
            )
            OutlinedButton(
                onClick = onEdit,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.watch_editor_change_canvas_size)) }
        }
    }
}

/**
 * 上传到商店 overlay。三个阶段：登录/注册 → 填写元数据（分类从服务动态拉取，
 * 屏幕形状默认跟随表盘 shape）→ 上传。exportWatch 的产物只在本次上传期间
 * 存在于 cacheDir。密码默认只在内存中经手；仅当用户勾选「记住密码」时才经
 * Android Keystore 加密后由 [WatchStoreCredentialStore] 落盘，绝不明文保存。
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
    val credentialStore = remember { WatchStoreCredentialStore(context) }
    val api = remember { HttpWatchStoreApi() }

    var auth by remember { mutableStateOf<WatchStoreAuthSession?>(null) }
    var authChecked by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberUsername by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var authAction by remember { mutableStateOf<WatchStoreAuthAction?>(null) }
    var authPage by remember { mutableStateOf(WatchStoreAuthPage.LOGIN) }
    var formError by remember { mutableStateOf<String?>(null) }
    var uploadPercent by remember { mutableStateOf<Int?>(null) }
    var name by remember {
        mutableStateOf(
            snapshot.rootAttributes["name"].orEmpty()
                .ifBlank { context.getString(R.string.watch_editor_default_watch_name) }
                .take(100),
        )
    }
    // 作者（老师指定）：上传到商店时这一栏填已登录账户名。表盘文件里原有的
    // designer_name 不参与预填——上传者就是署名人；用户仍可手改这一栏。
    // 未登录时留空，等 LaunchedEffect / 登录成功回填。
    var author by remember { mutableStateOf("") }
    var authorEdited by remember { mutableStateOf(false) }
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
        // 令牌与已记住的凭据都在 IO 上读；打开覆层时自动填充登录表单。
        val (storedAuth, savedCredentials) = withContext(Dispatchers.IO) {
            authStore.read() to credentialStore.read()
        }
        auth = storedAuth
        savedCredentials.username?.let { username = it }
        savedCredentials.password?.let { password = it }
        rememberUsername = savedCredentials.rememberUsername
        rememberPassword = savedCredentials.rememberPassword
        // 上传的署名就是当前账户名；用户手改过就不再覆盖。
        if (!authorEdited) {
            author = storedAuth?.username.orEmpty().ifBlank {
                savedCredentials.username.orEmpty()
            }
        }
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
                categoriesError = watchStoreUploadErrorMessage(context, error)
            }
    }

    fun authenticate(register: Boolean) {
        if (busy != null) return
        val cleanUsername = username.trim()
        val inputError = watchStoreAuthInputError(context, cleanUsername, password)
        if (inputError != null) {
            formError = inputError
            return
        }
        busy = context.getString(
            if (register) R.string.watch_store_status_registering else R.string.watch_store_status_logging_in,
        )
        authAction = if (register) WatchStoreAuthAction.REGISTER else WatchStoreAuthAction.LOGIN
        formError = null
        scope.launch {
            try {
                runCatching {
                    watchStoreLoginOrRegister(
                        api = api,
                        authStore = authStore,
                        credentialStore = credentialStore,
                        username = cleanUsername,
                        password = password,
                        register = register,
                        rememberUsername = rememberUsername,
                        rememberPassword = rememberPassword,
                    )
                }.onSuccess { stored ->
                    password = ""
                    auth = stored
                    // 刚登录 / 注册完：用户没手改过就填成这个账户名。
                    if (!authorEdited) author = stored.username
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    formError = watchStoreAuthErrorMessage(context, error, register)
                }
            } finally {
                busy = null
                authAction = null
            }
        }
    }

    fun upload() {
        if (busy != null) return
        val activeAuth = auth ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            formError = context.getString(R.string.watch_store_error_name_required)
            return
        }
        if (description.isBlank()) {
            formError = context.getString(R.string.watch_store_error_description_required)
            return
        }
        val categoryId = selectedCategoryId
        if (categoryId == null) {
            formError = context.getString(R.string.watch_store_error_category_required)
            return
        }
        // 作者留空就退回账户名，再退回 "flue"：上传出去的包一定带作者。
        val effectiveAuthor = author.trim().ifBlank { activeAuth.username }.ifBlank { "flue" }
        busy = context.getString(R.string.watch_store_status_exporting_package)
        formError = null
        uploadPercent = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("flue_store_upload_", ".watch", context.cacheDir)
                    try {
                        session.exportWatch(
                            archive,
                            rootAttributeOverrides = mapOf(
                                "designer_name" to effectiveAuthor,
                                "author" to effectiveAuthor,
                            ),
                        )
                        if (archive.length() > HttpWatchStoreApi.MAX_UPLOAD_BYTES) {
                            throw WatchStorePayloadTooLargeException(HttpWatchStoreApi.MAX_UPLOAD_BYTES)
                        }
                        busy = context.getString(R.string.watch_store_status_uploading)
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
                Toast.makeText(
                    context,
                    context.getString(R.string.watch_store_upload_success),
                    Toast.LENGTH_SHORT,
                ).show()
                onDismiss()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (error is WatchStoreHttpException && error.statusCode == 401) {
                    // 服务器判定令牌失效比本地过期时间更权威：清掉并回到登录页。
                    authStore.clear()
                    auth = null
                    formError = context.getString(R.string.watch_store_error_auth_expired_upload)
                } else {
                    formError = watchStoreUploadErrorMessage(context, error)
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
                Icon(
                    MiuixIcons.Close,
                    contentDescription = stringResource(R.string.watch_store_close_upload),
                )
            }
            Text(
                stringResource(R.string.watch_editor_upload_store),
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
                    MiuixLoadingIndicator(size = 18.dp)
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
                    stringResource(R.string.watch_store_status_checking_login),
                    style = MaterialTheme.typography.bodySmall,
                    color = style.secondaryTextColor,
                )
                activeAuth == null -> WatchStoreLoginSection(
                    username = username,
                    onUsernameChange = { username = it },
                    password = password,
                    onPasswordChange = { password = it },
                    rememberUsername = rememberUsername,
                    rememberPassword = rememberPassword,
                    onRememberChange = { keepUsername, keepPassword ->
                        rememberUsername = keepUsername
                        rememberPassword = keepPassword
                    },
                    authPage = authPage,
                    onAuthPageChange = { page ->
                        authPage = page
                        formError = null
                    },
                    enabled = busy == null,
                    loadingAction = authAction,
                    onLogin = { authenticate(register = false) },
                    onRegister = { authenticate(register = true) },
                )
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.watch_store_logged_in_fmt, activeAuth.username),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = style.secondaryTextColor,
                        )
                        TextButton(
                            onClick = {
                                if (busy == null) {
                                    authStore.clear()
                                    auth = null
                                    // 登出同步清理已记住的账号/密码（含 Keystore 密文）。
                                    rememberUsername = false
                                    rememberPassword = false
                                    scope.launch(Dispatchers.IO) { credentialStore.clear() }
                                }
                            },
                        ) {
                            Text(
                                stringResource(R.string.watch_store_logout),
                                color = style.accentColor,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(100) },
                        label = { Text(stringResource(R.string.watch_store_watch_name_required)) },
                        singleLine = true,
                        enabled = busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = author,
                        onValueChange = {
                            author = it.take(80)
                            authorEdited = true
                        },
                        label = { Text(stringResource(R.string.watch_editor_author)) },
                        placeholder = { Text(stringResource(R.string.watch_store_author_account_hint)) },
                        singleLine = true,
                        enabled = busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(2000) },
                        label = { Text(stringResource(R.string.watch_store_description_required)) },
                        minLines = 2,
                        enabled = busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.watch_store_screen_shape),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        STORE_SCREEN_TYPE_OPTIONS.forEach { (candidate, labelRes) ->
                            if (screenType == candidate) {
                                Button(onClick = {}, enabled = true) { Text(stringResource(labelRes)) }
                            } else {
                                OutlinedButton(
                                    onClick = { screenType = candidate },
                                    enabled = busy == null,
                                ) { Text(stringResource(labelRes)) }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.watch_store_category),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
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
                            ) { Text(stringResource(R.string.watch_store_reload_categories)) }
                        }
                        categories == null -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MiuixLoadingIndicator(size = 16.dp)
                            Text(
                                stringResource(R.string.watch_store_status_loading_categories),
                                style = MaterialTheme.typography.bodySmall,
                                color = style.secondaryTextColor,
                            )
                        }
                        categories.orEmpty().isEmpty() -> Text(
                            stringResource(R.string.watch_store_no_categories),
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
                                        MiuixIcons.Ok,
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
                        Icon(MiuixIcons.UploadCloud, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.watch_store_upload_current))
                    }
                    Text(
                        stringResource(R.string.watch_store_upload_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = style.secondaryTextColor,
                    )
                }
            }
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
        if (showProgress) MiuixLoadingIndicator()
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
        Button(onClick = onClose) { Text(stringResource(R.string.watch_editor_back)) }
    }
}

private fun draftDescriptor(
    context: Context,
    session: WatchEditorSession,
    snapshot: WatchEditorSnapshot,
): WatchFaceDescriptor = WatchFaceDescriptor(
    id = "${WATCHMAKER_ID_PREFIX}editor-draft",
    type = WatchFaceType.WATCHMAKER,
    displayName = snapshot.rootAttributes["name"].orEmpty().ifBlank {
        context.getString(R.string.watch_editor_draft_display_name)
    },
    summary = context.getString(R.string.watch_editor_preview_summary_fmt, snapshot.layers.size),
    sourceDirPath = session.draftDirectory.absolutePath,
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
    "image_gif" -> mapOf(
        "x" to "0",
        "y" to "0",
        "opacity" to "100",
        "gif_delay" to "100",
    )
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

@StringRes
private fun attributeLabelRes(name: String): Int = when (name) {
    "x" -> R.string.watch_editor_attribute_x
    "y" -> R.string.watch_editor_attribute_y
    "rotation" -> R.string.watch_editor_attribute_rotation
    "opacity" -> R.string.watch_editor_attribute_opacity
    "anim_scale_x" -> R.string.watch_editor_attribute_scale_x
    "anim_scale_y" -> R.string.watch_editor_attribute_scale_y
    "text" -> R.string.watch_editor_attribute_text
    "text_size" -> R.string.watch_editor_attribute_text_size
    "font" -> R.string.watch_editor_attribute_font
    "font2" -> R.string.watch_editor_attribute_font_secondary
    "alignment" -> R.string.watch_editor_attribute_alignment
    "color" -> R.string.watch_editor_attribute_color
    "path" -> R.string.watch_editor_attribute_path
    "gif_delay" -> R.string.watch_editor_attribute_frame_duration
    "anim_in" -> R.string.watch_editor_attribute_anim_in
    "anim_out" -> R.string.watch_editor_attribute_anim_out
    "dur_in" -> R.string.watch_editor_attribute_duration_in
    "dur_on" -> R.string.watch_editor_attribute_duration_on
    "dur_out" -> R.string.watch_editor_attribute_duration_out
    "dur_off" -> R.string.watch_editor_attribute_duration_off
    "delay_start" -> R.string.watch_editor_attribute_start_delay
    "repeat_count" -> R.string.watch_editor_attribute_repeat_count
    "restart_on_load" -> R.string.watch_editor_attribute_restart_on_load
    else -> R.string.watch_editor_attribute_unknown
}

private fun Context.localizedEditorError(
    @StringRes messageRes: Int,
    error: Throwable,
    includeDetails: Boolean = false,
): String {
    val localizedMessage = getString(messageRes)
    Log.e(WATCH_EDITOR_LOG_TAG, localizedMessage, error)
    if (!includeDetails) return localizedMessage
    val detail = error.message
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(MAX_EDITOR_ERROR_DETAIL_CHARS)
        ?: error.javaClass.simpleName
    return if (detail == localizedMessage) localizedMessage else "$localizedMessage\n\n$detail"
}

private const val WATCH_EDITOR_LOG_TAG = "WatchEditor"
private const val MAX_EDITOR_ERROR_DETAIL_CHARS = 8_192

/** 颜色行：色板预览 + 打开取色盘。写回与颜色文本框同一个通道。 */
@Composable
private fun EditorColorPickerRow(
    currentHex: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    var dialogVisible by remember { mutableStateOf(false) }
    val parsedColor = remember(currentHex) { parseEditorHexColor(currentHex) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(parsedColor ?: Color.DarkGray),
        )
        OutlinedButton(onClick = { dialogVisible = true }, enabled = enabled) {
            Text(stringResource(R.string.watch_editor_color_picker))
        }
    }
    if (dialogVisible) {
        EditorColorPickerDialog(
            initial = parsedColor ?: Color.White,
            onDismiss = { dialogVisible = false },
            onConfirm = { color ->
                dialogVisible = false
                onPick(editorColorToHex(color))
            },
        )
    }
}

/** HSV 三滑条取色盘（手表小屏取色盘：预览 + 色相/饱和/明度）。 */
@Composable
private fun EditorColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    val hsv = remember(initial) {
        FloatArray(3).also {
            android.graphics.Color.colorToHSV(initial.toArgb(), it)
        }
    }
    var hue by remember(initial) { mutableStateOf(hsv[0]) }
    var saturation by remember(initial) { mutableStateOf(hsv[1]) }
    var value by remember(initial) { mutableStateOf(hsv[2]) }
    val currentColor = Color(
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watch_editor_color_picker)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(currentColor),
                )
                Text(
                    editorColorToHex(currentColor),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.watch_editor_hue), style = MaterialTheme.typography.labelSmall)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text(
                    stringResource(R.string.watch_editor_saturation),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)
                Text(stringResource(R.string.watch_editor_brightness), style = MaterialTheme.typography.labelSmall)
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentColor) }) {
                Text(stringResource(R.string.watch_editor_use))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.watch_editor_cancel)) }
        },
    )
}

/**
 * 文字描边（字体边框）设置。属性名沿用 WM_FORMAT 契约：`outline` 取
 * Outline/Glow、`o_size` 尺寸、`o_color` 颜色、`o_opacity` 0-100。
 * 选「无」时把四个属性一并清掉，不留孤儿属性。
 */
@Composable
private fun EditorOutlineSection(
    layer: WatchEditorLayer,
    draftResetKey: Int,
    enabled: Boolean,
    onLiveChange: (Map<String, String?>) -> Unit,
) {
    // 模式也走本地 draft：live 通道有 150ms 去抖 + 落盘，直接读
    // layer.attributes 会让 chip 选中态明显滞后于点击。
    var mode by remember(layer.key, draftResetKey) {
        mutableStateOf(watchOutlineMode(layer.attributes["outline"]))
    }
    // 尺寸/不透明度是自由文本（可写 Watchmaker 表达式），所以走 draft 回显。
    var sizeDraft by remember(layer.key, draftResetKey) {
        val raw = layer.attributes["o_size"].orEmpty()
        mutableStateOf(TextFieldValue(raw, TextRange(raw.length)))
    }
    var opacityDraft by remember(layer.key, draftResetKey) {
        val raw = layer.attributes["o_opacity"].orEmpty()
        mutableStateOf(TextFieldValue(raw, TextRange(raw.length)))
    }
    var colorDraft by remember(layer.key, draftResetKey) {
        mutableStateOf(layer.attributes["o_color"].orEmpty())
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.watch_editor_font_outline), fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EDITOR_OUTLINE_MODE_OPTIONS.forEach { (candidate, labelRes) ->
                EditorFontChip(
                    label = stringResource(labelRes),
                    selected = mode == candidate,
                    enabled = enabled,
                    onClick = {
                        mode = candidate
                        if (candidate == WatchOutlineMode.NONE) {
                            // 关掉描边：四个属性一起清，避免残留值下次开启时诈尸。
                            sizeDraft = TextFieldValue("")
                            opacityDraft = TextFieldValue("")
                            colorDraft = ""
                            onLiveChange(
                                mapOf(
                                    "outline" to null,
                                    "o_size" to null,
                                    "o_color" to null,
                                    "o_opacity" to null,
                                ),
                            )
                        } else {
                            // 开启时补默认尺寸，否则 o_size 缺省虽有回落、
                            // 但用户在 UI 上看不到当前生效值。
                            val raw = candidate.name.lowercase(Locale.ROOT)
                                .replaceFirstChar { it.uppercase(Locale.ROOT) }
                            val changes = mutableMapOf<String, String?>("outline" to raw)
                            if (sizeDraft.text.isBlank()) {
                                val fallback = formatEditorOutlineSize(
                                    watchOutlineDefaultSize(candidate),
                                )
                                sizeDraft = TextFieldValue(fallback, TextRange(fallback.length))
                                changes["o_size"] = fallback
                            }
                            onLiveChange(changes)
                        }
                    },
                )
            }
        }
        if (mode != WatchOutlineMode.NONE) {
            OutlinedTextField(
                value = sizeDraft,
                onValueChange = { value ->
                    val previous = sizeDraft
                    sizeDraft = value
                    if (value.text != previous.text) {
                        onLiveChange(mapOf("o_size" to value.text.ifBlank { null }))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = {
                    Text(
                        stringResource(
                            R.string.watch_editor_outline_width_fmt,
                            formatEditorOutlineSize(MAX_WATCH_OUTLINE_SIZE),
                        ),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = opacityDraft,
                onValueChange = { value ->
                    val previous = opacityDraft
                    opacityDraft = value
                    if (value.text != previous.text) {
                        onLiveChange(mapOf("o_opacity" to value.text.ifBlank { null }))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text(stringResource(R.string.watch_editor_outline_opacity)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Text(
                stringResource(R.string.watch_editor_outline_color),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            EditorColorPickerRow(
                currentHex = colorDraft,
                enabled = enabled,
                onPick = { hex ->
                    colorDraft = hex
                    onLiveChange(mapOf("o_color" to hex.ifBlank { null }))
                },
            )
        }
    }
}

/** 整数就不带小数点：写进 XML 的值尽量和手写表盘一致。 */
private fun formatEditorOutlineSize(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()

private fun editorDirectorySizeBytes(directory: File): Long = runCatching {
    directory.walkTopDown()
        .filter(File::isFile)
        .sumOf(File::length)
}.getOrDefault(0L)

private fun formatEditorFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private val EDITOR_OUTLINE_MODE_OPTIONS = listOf(
    WatchOutlineMode.NONE to R.string.watch_editor_none,
    WatchOutlineMode.OUTLINE to R.string.watch_editor_outline,
    WatchOutlineMode.GLOW to R.string.watch_editor_glow,
)

/** 字体选择：默认 + 表盘包内字体资源横向 chips + 自定义导入。 */
@Composable
private fun EditorFontSelector(
    current: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onImportFont: (() -> Unit)?,
    onImportFontLong: (() -> Unit)?,
    onImportBitmapFont: (() -> Unit)?,
    onImportBitmapFontLong: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.watch_editor_font), fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val currentName = current.substringAfterLast('/')
            val defaultSelected = currentName.isBlank() ||
                currentName.equals("Roboto-Regular", ignoreCase = true)
            EditorFontChip(
                label = stringResource(R.string.watch_editor_default),
                selected = defaultSelected,
                enabled = enabled,
                onClick = { onSelect("") },
            )
            options.forEach { path ->
                val name = if (path.startsWith("bm:", true)) path else path.substringAfterLast('/')
                EditorFontChip(
                    label = name.removePrefix("bm:").substringBeforeLast('.'),
                    selected = !defaultSelected && currentName.equals(name, ignoreCase = true),
                    enabled = enabled,
                    onClick = { onSelect(name) },
                )
            }
        }
        if (onImportFont != null && onImportFontLong != null) {
            EditorLongPressImportButton(
                label = stringResource(R.string.watch_editor_import_font_formats),
                enabled = enabled,
                onClick = onImportFont,
                onLongClick = onImportFontLong,
                showIcon = true,
            )
        }
        if (onImportBitmapFont != null && onImportBitmapFontLong != null) {
            EditorLongPressImportButton(
                label = stringResource(R.string.watch_editor_import_bitmap_font),
                enabled = enabled,
                onClick = onImportBitmapFont,
                onLongClick = onImportBitmapFontLong,
                showIcon = true,
            )
        }
    }
}

/** 单一 composable + 动画选中态：不再用「换组件类型」表达选中。 */
@Composable
private fun EditorFontChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "editor_font_chip",
    )
    Text(
        text = label,
        maxLines = 1,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** 解析 Watchmaker 常见的 6/8 位 hex（可带 #）。 */
private fun parseEditorHexColor(raw: String): Color? {
    val value = raw.replace("#", "").trim()
    if (value.length != 6 && value.length != 8) return null
    return runCatching { Color(android.graphics.Color.parseColor("#$value")) }.getOrNull()
}

/** 输出 6 位小写 hex（无 #），与 Watchmaker 生态的常见写法一致。 */
private fun editorColorToHex(color: Color): String {
    val argb = color.toArgb()
    return "%06x".format(argb and 0xFFFFFF)
}

/**
 * 作者字段的读取口径：优先 Watchmaker 的 `designer_name`，回退到 Flue 扫描器
 * 显示用的 `author`。外部导入的表盘常常只写了其中一个。
 */
private fun WatchEditorSnapshot.designerName(): String =
    rootAttributes["designer_name"]?.takeIf(String::isNotBlank)
        ?: rootAttributes["author"]?.takeIf(String::isNotBlank)
        ?: ""

private fun safeWatchFileName(raw: String?): String {
    val normalized = raw.orEmpty().trim().ifBlank { "flue-watch" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .take(80)
    return normalized.ifBlank { "flue-watch" }
}

private fun hasEditorBodySensorsPermission(context: Context): Boolean =
    hasHeartRatePermission(context)

private val BASIC_LAYER_TYPES = listOf(
    "text" to R.string.watch_editor_layer_text,
    "shape" to R.string.watch_editor_layer_shape,
    "image" to R.string.watch_editor_layer_image,
    "image_gif" to R.string.watch_editor_layer_looping_background,
    "ring" to R.string.watch_editor_layer_ring,
    "progress" to R.string.watch_editor_layer_progress,
)

@Composable
private fun watchEditorLayerTypeLabel(type: String): String {
    val labelRes = BASIC_LAYER_TYPES.firstOrNull { (code, _) ->
        code.equals(type, ignoreCase = true)
    }?.second
    return labelRes?.let { stringResource(it) } ?: type
}

/** 根节点 shape 属性的两个可写值；解析端把 R/S 归一化为圆形/方形。 */
private val WATCH_SHAPE_OPTIONS = listOf(
    "R" to R.string.watch_editor_shape_round,
    "S" to R.string.watch_editor_shape_square,
)

private val STORE_SCREEN_TYPE_OPTIONS = listOf(
    WatchStoreScreenType.ROUND to R.string.watch_store_screen_round,
    WatchStoreScreenType.SQUARE to R.string.watch_store_screen_square,
    WatchStoreScreenType.RECTANGULAR to R.string.watch_store_screen_rectangular,
)

private val EDITABLE_ATTRIBUTES = listOf(
    "x",
    "y",
    "rotation",
    "opacity",
    "anim_scale_x",
    "anim_scale_y",
    "text",
    "text_size",
    "color",
    "path",
)

private val TEXT_ANIMATION_ATTRIBUTES = listOf(
    "anim_in",
    "anim_out",
    "dur_in",
    "dur_on",
    "dur_out",
    "dur_off",
    "delay_start",
    "repeat_count",
    "restart_on_load",
)

/** Attributes whose runtime value is parsed as WatchMaker text or a dynamic number/path. */
private val WATCH_EXPRESSION_ATTRIBUTES = (
    EDITABLE_ATTRIBUTES + TEXT_ANIMATION_ATTRIBUTES
).toSet() - setOf("anim_in", "anim_out", "restart_on_load")

private val TEXT_ENTRY_ANIMATION_OPTIONS = listOf(
    "" to R.string.watch_editor_none,
    "SlideLeft" to R.string.watch_editor_animation_slide_left,
    "SlideRight" to R.string.watch_editor_animation_slide_right,
    "SlideUp" to R.string.watch_editor_animation_slide_up,
    "SlideDown" to R.string.watch_editor_animation_slide_down,
    "Fade" to R.string.watch_editor_animation_fade,
    "ZoomIn" to R.string.watch_editor_animation_zoom_in,
    "ZoomOut" to R.string.watch_editor_animation_zoom_out,
    "Spin" to R.string.watch_editor_animation_spin,
)
private fun editableAttributesFor(type: String): List<String> = buildList {
    addAll(EDITABLE_ATTRIBUTES)
    when (type.lowercase(Locale.ROOT)) {
        "gif", "image_gif" -> add("gif_delay")
        "text", "text_curved" -> addAll(TEXT_ANIMATION_ATTRIBUTES)
    }
}

private val NUMERIC_ATTRIBUTES = setOf(
    "x",
    "y",
    "rotation",
    "opacity",
    "anim_scale_x",
    "anim_scale_y",
    "text_size",
    "gif_delay",
    "dur_in",
    "dur_on",
    "dur_out",
    "dur_off",
    "delay_start",
    "repeat_count",
)

/** 有文字渲染路径、应展示字体选择器的图层类型。 */
private val FONT_CAPABLE_LAYER_TYPES = setOf(
    "text",
    "text_curved",
    "text_ring",
    "series",
    "calendar",
)

/** 字体资源扩展名（矢量 + 位图字体索引）。 */
private val EDITOR_FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
private val EDITOR_BITMAP_FONT_EXTENSIONS = setOf("fnt", "png", "jpg", "jpeg", "webp")
private val EDITOR_INTERNAL_IMAGE_EXTENSIONS =
    setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
private val EDITOR_INTERNAL_VIDEO_EXTENSIONS =
    setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp")
