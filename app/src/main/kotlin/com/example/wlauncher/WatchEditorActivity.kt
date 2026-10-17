package com.flue.launcher

import com.flue.launcher.i18n.FlueComponentActivity

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.ClipData
import androidx.core.content.FileProvider
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.flue.launcher.ui.style.FlueAlertDialog
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueCard
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.style.FlueSlider
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import com.flue.launcher.ui.style.FlueLoadingIndicator
import com.flue.launcher.ui.style.FlueDeterminateProgressIndicator
import com.flue.launcher.ui.style.FlueLoadingIndicatorUsage
import com.flue.launcher.ui.style.FlueExpressiveFloatingToolbar
import com.flue.launcher.ui.settings.miuix.MiuixIcons as FlueMiuixIcons
import com.flue.launcher.ui.settings.WatchAppPickerDialog
import com.flue.launcher.ui.settings.WatchTapActionPresetDialog
// 官方 miuix 图标集。本文件里 MiuixIcons 这个名字已经被上面那份自绘图标占了，
// 所以照 SettingsActivity 的写法加 Official 前缀区分，避免两套图标混着看不出来源。
import top.yukonga.miuix.kmp.icon.MiuixIcons as OfficialMiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear as OfficialClear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Folder
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
import com.flue.launcher.ui.common.FlueColorPickerDialog
import com.flue.launcher.ui.common.chordScaleForListItem
import com.flue.launcher.ui.common.chordScaleForPosition
import com.flue.launcher.ui.common.chordScaleItem
import com.flue.launcher.ui.common.flueContentSizeSpring
import com.flue.launcher.ui.common.roundScrollablePadding
import com.flue.launcher.ui.store.StoreRoundList
import com.flue.launcher.ui.style.flueOverscroll
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.common.InternalFileBrowserHost
import com.flue.launcher.ui.common.InternalFileBrowserRequest
import com.flue.launcher.ui.common.WfToolsPromoRow
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceType
import com.flue.launcher.watchface.editor.WATCH_EDITOR_EXPRESSION_GROUPS
import com.flue.launcher.watchface.editor.WatchCanvasSize
import com.flue.launcher.watchface.editor.WatchEditorHandle
import com.flue.launcher.watchface.editor.WatchEditorImageCropper
import com.flue.launcher.watchface.editor.WatchEditorImageFormat
import com.flue.launcher.watchface.editor.WatchEditorImageSourceInfo
import com.flue.launcher.watchface.editor.WatchEditorLayer
import com.flue.launcher.watchface.editor.WatchEditorLayerKey
import com.flue.launcher.watchface.editor.WatchEditorTreeRow
import com.flue.launcher.watchface.editor.WatchEditorLiveAttributeApplicator
import com.flue.launcher.watchface.editor.WatchEditorCanvasMapping
import com.flue.launcher.watchface.standard.WatchCanvasSettings
import com.flue.launcher.watchface.editor.WatchEditorCropAspect
import com.flue.launcher.watchface.editor.WatchEditorCropHandle
import com.flue.launcher.watchface.editor.WatchEditorCropRect
import com.flue.launcher.watchface.editor.WatchEditorSelectionBox
import com.flue.launcher.watchface.editor.WatchEditorSession
import com.flue.launcher.watchface.editor.watchXmlAssetPath
import com.flue.launcher.watchface.agent.runtime.WatchAgentViewModel
import com.flue.launcher.watchface.agent.storage.AgentFeatureSettingsStore
import com.flue.launcher.watchface.agent.storage.WatchAgentContinuation
import com.flue.launcher.watchface.agent.storage.WatchAgentContinuationStore
import com.flue.launcher.watchface.agent.workspace.WorkspaceManager
import com.flue.launcher.watchface.agent.tools.WatchAgentDisplayMode
import com.flue.launcher.watchface.agent.tools.WatchAgentPreviewMode
import com.flue.launcher.watchface.agent.tools.WatchAgentPreviewResult
import com.flue.launcher.watchface.agent.tools.WatchEditorSessionAgentToolHost
import com.flue.launcher.ui.editor.agent.WatchAgentFullscreen
import com.flue.launcher.ui.editor.agent.WatchAgentPanel
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.editor.EditorResourceThumbnail
import com.flue.launcher.watchface.editor.WatchEditorResourceEntry
import com.flue.launcher.watchface.editor.WatchEditorResourceKind
import com.flue.launcher.watchface.editor.WatchEditorViewport
import com.flue.launcher.watchface.editor.watchEditorAudioTapAction
import com.flue.launcher.watchface.editor.watchEditorLayerSelectionBlock
import com.flue.launcher.watchface.editor.EditorPreviewViewportDialog
import com.flue.launcher.watchface.editor.EditorAmbientProperties
import com.flue.launcher.watchface.editor.rememberEditorAudioPreview
import com.flue.launcher.watchface.editor.WatchEditorPoint
import com.flue.launcher.watchface.standard.WatchmakerLiveLayerTransform
import com.flue.launcher.watchface.standard.watchmakerDisplayAttributes
import com.flue.launcher.watchface.standard.watchmakerDisplayVisible
import com.flue.launcher.watchface.editor.WatchEditorSnapshot
import com.flue.launcher.watchface.editor.WatchEditorSourceRect
import com.flue.launcher.watchface.editor.WatchEditorSnapping
import com.flue.launcher.watchface.editor.WatchEditorTransform
import com.flue.launcher.watchface.editor.WatchEditorVideoFrames
import com.flue.launcher.watchface.editor.WatchEditorVideoFile
import com.flue.launcher.watchface.editor.WatchEditorVideoImportOptions
import com.flue.launcher.watchface.editor.WatchEditorVideoImporter
import com.flue.launcher.watchface.editor.WatchEditorVideoInfo
import com.flue.launcher.watchface.editor.WatchEditorFfmpegUnsupportedException
import com.flue.launcher.watchface.editor.resolveVideoTransform
import com.flue.launcher.watchface.editor.WatchFormatVersion
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
import com.flue.launcher.watchface.editor.shape.WATCH_SHAPE_ATTRIBUTE_VALUES
import com.flue.launcher.watchface.editor.shape.watchShapeCornerRadii
import com.flue.launcher.watchface.standard.MAX_WATCH_OUTLINE_SIZE
import com.flue.launcher.watchface.standard.PLAYABLE_VIDEO_EXTENSIONS
import com.flue.launcher.watchface.standard.WATCHMAKER_ID_PREFIX
import com.flue.launcher.watchface.standard.WatchFaceShape
import com.flue.launcher.watchface.standard.WatchOutlineMode
import com.flue.launcher.watchface.standard.WatchShapeShaderKind
import com.flue.launcher.watchface.standard.watchShapeShaderAttributeValue
import com.flue.launcher.watchface.standard.watchShapeShaderDefaults
import com.flue.launcher.watchface.standard.watchShapeShaderKindOf
import com.flue.launcher.watchface.standard.WatchmakerGeometry
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStageView
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
import com.flue.launcher.watchface.store.WatchStoreFeatureFlagCache
import com.flue.launcher.watchface.store.WatchStoreFeatureFlags
import com.flue.launcher.watchface.store.WatchStoreHttpException
import com.flue.launcher.watchface.store.WatchStorePayloadTooLargeException
import com.flue.launcher.watchface.store.WatchStoreScreenType
import com.flue.launcher.watchface.store.WatchStoreRepository
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luaj.vm2.Globals
import org.luaj.vm2.compiler.LuaC
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class WatchEditorActivity : FlueComponentActivity() {
    private var restoreRecentsExclusionOnExit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_FULLSCREEN disables platform resize on older Android versions.
        // Keep immersive chrome through insets instead, and let Compose own IME space.
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
    /**
     * 目标图层当前的 type。导入终点由它决定，而不是由文档格式版本决定：
     * 只有本来就是 video 的图层才允许收视频，image_gif（循环背景）一律转帧序列。
     */
    val layerType: String,
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
    val agentViewModel: WatchAgentViewModel = viewModel()
    val agentWorkspaceManager = remember(context) {
        WorkspaceManager(File(context.filesDir, "agent-runtime").toPath())
    }
    val agentContinuationStore = remember(context) { WatchAgentContinuationStore(context) }
    var pendingAgentContinuation by remember { mutableStateOf<WatchAgentContinuation?>(null) }
    var showAgentContinuationDialog by remember { mutableStateOf(false) }
    val openAgentSettings = remember(context) {
        {
            context.startActivity(
                Intent(context, SettingsActivity::class.java).apply {
                    putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_AGENT)
                    putExtra(EXTRA_SETTINGS_RETURN_TO_FACE, true)
                },
            )
        }
    }
    val tapHapticsEnabled by vm.watchFaceTapHapticsEnabled.collectAsStateWithLifecycle()
    // 预览的模糊底图跟随设置（景深本身在预览里强制开，理由见 WatchEditorPreview）。
    val editorGravityBackdropBlurEnabled by
        vm.watchfaceGravityBackdropBlurEnabled.collectAsStateWithLifecycle()
    var session by remember { mutableStateOf<WatchEditorSession?>(null) }
    var snapshot by remember { mutableStateOf<WatchEditorSnapshot?>(null) }
    var selectedLayerKey by remember { mutableStateOf<WatchEditorLayerKey?>(null) }
    var selectedLayerKeys by remember { mutableStateOf<Set<WatchEditorLayerKey>>(emptySet()) }
    var multiSelectMode by rememberSaveable { mutableStateOf(false) }
    var previewAmbient by rememberSaveable { mutableStateOf(false) }
    var batchTransformPreview by remember { mutableStateOf<Map<WatchEditorLayerKey, WatchEditorTransform>>(emptyMap()) }
    var selectionGeneration by remember { mutableIntStateOf(0) }
    var previewToken by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showExportOptions by remember { mutableStateOf(false) }
    var pendingExportProtected by rememberSaveable { mutableStateOf(false) }
    var pendingExportToDirectory by rememberSaveable { mutableStateOf(false) }
    var showUploadSaveRequiredDialog by remember { mutableStateOf(false) }
    var openUploadAfterSave by remember { mutableStateOf(false) }
    var showStoreUpload by remember { mutableStateOf(false) }
    var globalScriptDraft by remember(session) { mutableStateOf<String?>(null) }
    // 资源管理器：null = 未打开。打开时一次性把清单读出来（要摸文件大小，走 IO）。
    var resourceInventory by remember(session) {
        mutableStateOf<List<WatchEditorResourceEntry>?>(null)
    }
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
    // 绑了表达式的变换属性拖不动（见 WatchEditorSession.boundTransformAttributes）。
    // 不提示的话表现就是「松手后弹回」，像 bug；每层只提示一次，免得连续拖动时
    // Toast 刷屏。
    var boundTransformNoticeShownFor by remember(session) {
        mutableStateOf<WatchEditorLayerKey?>(null)
    }
    // Bumped whenever attributes change from OUTSIDE the live property fields
    // (undo, canvas drag, image import…) so the drafts reset; live typing keeps
    // its own in-progress state.
    var propertyDraftRevision by remember { mutableIntStateOf(0) }
    var cropRequest by remember { mutableStateOf<EditorImageImportRequest?>(null) }
    var videoRequest by remember { mutableStateOf<EditorVideoImportRequest?>(null) }
    var videoConversionJob by remember { mutableStateOf<Job?>(null) }
    var videoConversionProgress by remember { mutableStateOf(0f) }
    // 与 videoConversionJob 同生命周期：进度对话框靠它决定说哪套文案。
    var videoConversionKind by remember {
        mutableStateOf(EditorVideoConversionKind.FRAMES)
    }
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
            ?: if (multiSelectMode) next.layers.firstOrNull { it.key in selectedLayerKeys }?.key
            else next.layers.firstOrNull()?.key
        if (selectedLayerKey != resolvedSelection) selectionGeneration++
        selectedLayerKey = resolvedSelection
        selectedLayerKeys = if (multiSelectMode) selectedLayerKeys.intersect(next.layers.map { it.key }.toSet())
            else setOfNotNull(resolvedSelection)
        if (resetPropertyDrafts) propertyDraftRevision++
    }

    fun selectLayer(key: WatchEditorLayerKey?) {
        if (multiSelectMode && key != null) {
            selectedLayerKeys = if (key in selectedLayerKeys) selectedLayerKeys - key else selectedLayerKeys + key
            selectedLayerKey = key.takeIf { it in selectedLayerKeys } ?: selectedLayerKeys.lastOrNull()
            selectionGeneration++
            return
        }
        if (selectedLayerKey != key) selectionGeneration++
        selectedLayerKey = key
        selectedLayerKeys = setOfNotNull(key)
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
     *
     * 出图**之前**必须先把视频首帧烘进缓存：视频层的首帧是异步解码的，离屏出图
     * 只画一次，缺帧那一层就永远缺 —— 视频当背景的表盘存出来的预览图几乎全黑
     * （老师报的「保存表盘里包含视频会导致整个预览图直接黑掉」）。预热自己会把
     * MediaMetadataRetriever 切到 IO，不占主线程。
     */
    suspend fun refreshDraftPreview(active: WatchEditorSession) {
        val renderer = previewRenderer ?: return
        withContext(Dispatchers.Main.immediate) {
            runCatching { renderer.prewarmBitmapFontAtlases() }
            runCatching { renderer.prewarmVideoFirstFrames() }
        }
        // 渲染必须在主线程（读 View 持有的解码缓存），压缩再切 IO。
        val bitmap = withContext(Dispatchers.Main.immediate) {
            runCatching { renderer.renderFaceBitmap(PREVIEW_IMAGE_SIZE_PX) }.getOrNull()
        } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, stream)
                val dimBitmap = android.graphics.Bitmap.createBitmap(
                    bitmap.width,
                    bitmap.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                try {
                    val dimCanvas = android.graphics.Canvas(dimBitmap)
                    dimCanvas.drawBitmap(bitmap, 0f, 0f, null)
                    dimCanvas.drawColor(0x52000000, android.graphics.PorterDuff.Mode.SRC_OVER)
                    val dimStream = java.io.ByteArrayOutputStream()
                    dimBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, dimStream)
                    active.writePreviewImage(stream.toByteArray(), dimStream.toByteArray())
                } finally {
                    dimBitmap.recycle()
                }
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

    // MIME 必须是 */*，不能是 application/zip：SAF 的 DocumentsUI 会用
    // MimeTypeMap.getExtensionFromMimeType 反查扩展名，发现建议名的后缀和 MIME 不符就
    // 自动追加，于是 xxx.watch 变成 xxx.watch.zip。换成 application/octet-stream 也不行
    // （会追加 .bin）。*/* 查不到扩展名，才不会动我们给的文件名。
    val exportDocument = rememberLauncherForActivityResult(CreateDocument("*/*")) { uri ->
        val active = session
        if (uri != null && active != null) {
            scope.launch {
        busyMessage = context.getString(R.string.watch_editor_status_exporting)
                errorMessage = null
                liveApplicator?.flush(
                    onApplied = {},
            onError = { errorMessage = it.message ?: context.getString(R.string.watch_editor_error_property_update) },
                )
                // 导出前也要刷 preview.jpg：它在包里是 required（1.0 §1），不刷的话
                // 导出的包带的还是原作者那张旧预览。
                refreshDraftPreview(active)
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val temporary = File.createTempFile("flue_watch_export_", ".watch", context.cacheDir)
                        try {
                            active.exportWatch(temporary, protectedExport = pendingExportProtected)
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

    fun requestExportOptions(toDirectory: Boolean) {
        if (busyMessage != null || session == null || snapshot == null) return
        pendingExportToDirectory = toDirectory
        showExportOptions = true
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
                    // BMP 必须转 PNG，哪怕用户没裁剪。
                    //
                    // 规范的 images/ 只列了 png / jpg（1.0 §1），受保护导出的加壳映射
                    // （`protectedImagePath`）也只认 png/jpg/jpeg —— 一张 .bmp 会原样、
                    // 不加壳地进包，别的渲染器多半解不出来，而 BMP 本来就是**未压缩**
                    // 的，一张 512×512 就是 1 MB，白占归档预算。Android 能解 BMP，所以
                    // 这个问题只在把表盘拿出 Flue 时才暴露。
                    val mustReencode = request.sourceInfo.format == WatchEditorImageFormat.BMP
                    val resourcePath = if (sourceRect == null && !mustReencode) {
                        val stream = context.contentResolver.openInputStream(request.uri)
                    ?: throw IllegalStateException(context.getString(R.string.watch_editor_error_read_image))
                        // Lossless path: raw GIFs always come here, as do static
                        // images whose crop frame was never changed.
                        active.importResource(stream, request.sourceInfo.importName)
                    } else {
                        // 没有裁剪框（只是为了转码 BMP）时，用整张图当区域。
                        val region = sourceRect ?: WatchEditorImageCropper
                            .readBounds(context.contentResolver, request.uri)
                            .let { WatchEditorSourceRect(0, 0, it.width, it.height) }
                        val bitmap = WatchEditorImageCropper.cropRegion(
                            context.contentResolver,
                            request.uri,
                            region,
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
                    // 裸文件名：规范的 path 是「images/ 内的文件名」，
                    // 见 watchXmlAssetPath 的注释。
                    active.updateLayerAttribute(key, "path", watchXmlAssetPath(resourcePath, "images"))
                    // width / height 对 image / image_gif 是必填（1.0 §7.1，商店也这么查）。
                    // 只能在这里补：素材已落盘，真实像素尺寸此刻才知道。
                    resolveDraftImageSize(active.draftDirectory, resourcePath)?.let { (w, h) ->
                        active.applyImportedImageSize(key, w.toInt(), h.toInt())
                    }
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

    val audioPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            runSessionOperation(context.getString(R.string.watch_editor_audio_import), operation = { editor ->
                context.contentResolver.openInputStream(uri)?.use { input ->
                    editor.importResource(input, displayNameForUri(uri), "sfx")
                } ?: error(context.getString(R.string.watch_editor_error_read_file))
                selectedLayerKey
            }, onComplete = {
                scope.launch { resourceInventory = withContext(Dispatchers.IO) { session?.resourceInventory() } }
            })
        }
    }

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

    fun exportToDirectory(directory: File, protectedExport: Boolean = false) {
        val active = session ?: return
        val current = snapshot ?: return
        scope.launch {
            busyMessage = context.getString(R.string.watch_editor_status_exporting)
            errorMessage = null
            // 同上：导出到目录也要带上最新的 preview.jpg。
            refreshDraftPreview(active)
            runCatching {
                withContext(Dispatchers.IO) {
                    require(directory.isDirectory && directory.canWrite()) {
                        context.getString(R.string.watch_editor_error_export_destination)
                    }
                    val base = safeWatchFileName(current.rootAttributes["name"])
                    var output = File(directory, "$base.watch")
                    var suffix = 2
                    while (output.exists()) output = File(directory, "${base}_$suffix.watch").also { suffix++ }
                    active.exportWatch(output, protectedExport = protectedExport)
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
                            "path" to paths.joinToString("`") {
                                // 裸文件名，见 watchXmlAssetPath。
                                watchXmlAssetPath(it, "images")
                            },
                            "gif_delay" to active.snapshot().layers
                                .firstOrNull { it.key == key }
                                ?.attributes
                                ?.get("gif_delay")
                                .orEmpty()
                                .ifBlank { "100" },
                        ),
                    )
                    // 序列帧同样要 width / height（1.0 §7.2 的 image_gif 必填）。
                    // 取第一帧的尺寸：一套帧序列的每帧本来就该同尺寸。
                    paths.firstOrNull()
                        ?.let { resolveDraftImageSize(active.draftDirectory, it) }
                        ?.let { (w, h) -> active.applyImportedImageSize(key, w.toInt(), h.toInt()) }
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
                    val layerType = snapshot?.layers
                        ?.firstOrNull { it.key == key }
                        ?.type
                        .orEmpty()
                    videoRequest = EditorVideoImportRequest(uri, info, active, key, layerType)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                errorMessage = context.localizedEditorError(
                    R.string.watch_editor_error_read_video,
                    error,
                    includeDetails = true,
                )
            }
            busyMessage = null
        }
    }
    val videoPicker = rememberLauncherForActivityResult(OpenDocument()) { handleVideoUri(it) }

    fun openVideoPicker() {
        if (!WatchEditorVideoImporter.isRuntimeSupported()) {
            errorMessage = context.getString(R.string.watch_editor_error_ffmpeg_unsupported)
            return
        }
        videoPicker.launch(arrayOf("video/*"))
    }

    fun beginVideoImport(request: EditorVideoImportRequest, options: WatchEditorVideoImportOptions) {
        if (videoConversionJob != null || session !== request.session) return
        videoRequest = null
        videoConversionProgress = 0f
        // video 图层统一重编码为 H.264 MP4；image_gif 等其它图层仍转为序列帧，
        // 不改变目标图层类型。
        val targetsVideoLayer = request.layerType.equals("video", ignoreCase = true)
        videoConversionKind = if (targetsVideoLayer) {
            EditorVideoConversionKind.REENCODE
        } else {
            EditorVideoConversionKind.FRAMES
        }
        videoConversionJob = scope.launch {
            busyMessage = context.getString(R.string.watch_editor_status_converting_video_fmt, 0)
            errorMessage = null
            var converted: WatchEditorVideoFrames? = null
            var videoFile: WatchEditorVideoFile? = null
            try {
                val next = if (targetsVideoLayer) {
                    val encodedVideo = WatchEditorVideoImporter.reencodeToVideo(
                        context = context,
                        uri = request.uri,
                        options = options,
                        onProgress = { progress ->
                            val visibleProgress = progress.fraction.coerceAtMost(0.99f)
                            videoConversionProgress = visibleProgress
                            busyMessage = context.getString(
                                R.string.watch_editor_status_converting_video_fmt,
                                (visibleProgress * 100f).roundToInt(),
                            )
                        },
                    )
                    videoFile = encodedVideo
                    val transform = resolveVideoTransform(request.info, options)
                    withContext(Dispatchers.IO) {
                        request.session.importVideoFile(
                            key = request.layerKey,
                            videoFile = encodedVideo.videoFile,
                            videoWidth = transform.outputWidth,
                            videoHeight = transform.outputHeight,
                        )
                        request.session.flushDraftForPreview()
                        request.session.snapshot()
                    }
                } else {
                    val frames = WatchEditorVideoImporter.convertToFrames(
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
                    converted = frames
                    withContext(Dispatchers.IO) {
                        request.session.importVideoFrameSequence(
                            key = request.layerKey,
                            frameFiles = frames.frameFiles,
                            frameDelayMs = frames.frameDelayMs,
                        )
                        request.session.flushDraftForPreview()
                        request.session.snapshot()
                    }
                }
                if (session === request.session) {
                    updateSnapshot(next, request.layerKey)
                    previewToken++
                }
            } catch (cancelled: CancellationException) {
                // User cancellation leaves both the draft and selected layer untouched.
            } catch (error: WatchEditorFfmpegUnsupportedException) {
                errorMessage = context.getString(R.string.watch_editor_error_ffmpeg_unsupported)
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
                // delete the potentially large cache off the UI path.
                withContext(Dispatchers.IO + NonCancellable) {
                    converted?.deleteTemporaryFiles()
                    videoFile?.deleteTemporaryFiles()
                }
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
        if (kind == EditorInternalImportKind.VIDEO &&
            !WatchEditorVideoImporter.isRuntimeSupported()
        ) {
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
                    EditorInternalImportKind.EXPORT_DIRECTORY -> uris.firstOrNull()?.path?.let {
                        exportToDirectory(File(it), protectedExport = pendingExportProtected)
                        pendingExportProtected = false
                        pendingExportToDirectory = false
                    }
                    EditorInternalImportKind.WATCH -> handleWatchArchiveUri(uris.firstOrNull())
                    EditorInternalImportKind.CONVERSION -> handleConversionUri(uris.firstOrNull())
                }
            },
        )
    }

    fun beginExport(protectedExport: Boolean, toDirectory: Boolean) {
        pendingExportProtected = protectedExport
        pendingExportToDirectory = toDirectory
        showExportOptions = false
        if (toDirectory) {
            openInternalFileBrowser(EditorInternalImportKind.EXPORT_DIRECTORY)
        } else {
            val current = snapshot ?: return
            val name = safeWatchFileName(current.rootAttributes["name"])
            exportDocument.launch("$name${if (protectedExport) "_protected" else ""}.watch")
        }
    }

    val blankFaceIsRound = isFlueScreenRound()
    // WatchMaker 表盘固定使用原生 512×512 设计空间；不再让新建流程
    // 根据设备屏幕或自定义规格改变画布，否则导出的坐标会失真。
    var blankCanvasSize by remember(watchFaceId) {
        mutableStateOf(WatchCanvasSize.DEFAULT)
    }
    // 新建表盘统一生成 WatchMaker 1.0。已有 Studio 2.0 表盘仍可导入和编辑，
    // 但新建入口不再暴露版本选择，也不会写 build_source="studio"。
    LaunchedEffect(watchFaceId, blankCanvasSize) {
        val requestedCanvas = blankCanvasSize
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
                        formatVersion = WatchFormatVersion.V1,
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
        val scopeId = active?.let { it.logicalId ?: "draft:${it.draftDirectory.name}" }
        onDispose {
            if (active != null) {
                // A draft may contain tens of MiB of images. Never delete it on
                // the UI thread while this Activity is closing. Persist the
                // Agent draft first; close() then removes only the disposable
                // cache copy and leaves a durable workspace continuation.
                Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                    runCatching {
                        val scope = scopeId ?: return@runCatching
                        val conversationId = agentViewModel.activeConversationId()
                        if (conversationId != null && active.isDirty) {
                            val binding = agentWorkspaceManager.bindingFor(scope)
                                ?: agentWorkspaceManager.create(
                                    displayName = "Flue ${scope.take(28)}",
                                    watchScope = scope,
                                ).binding
                            val workspaceId = binding?.workspaceId
                                ?: error("无法创建表盘绑定工作区")
                            val candidate = active.agentCandidate()
                            val resources = active.resourceInventory().associate { asset ->
                                asset.relativePath to Files.readAllBytes(
                                    active.draftDirectory.toPath().resolve(asset.relativePath),
                                )
                            }
                            check(
                                agentWorkspaceManager.syncFaceMirror(
                                    workspaceId = workspaceId,
                                    watchXml = candidate.watchXml,
                                    globalScript = candidate.globalScript,
                                    resources = resources,
                                    watchScope = scope,
                                ),
                            ) { "镜像同步失败" }
                            agentContinuationStore.save(
                                watchScope = scope,
                                workspaceId = workspaceId,
                                conversationId = conversationId,
                                mirrorRevision = candidate.revision,
                            )
                        } else {
                            agentContinuationStore.clear(scope)
                        }
                    }.onFailure { error ->
                        Log.w("WatchEditor", "保存 Agent 工作区续接草稿失败", error)
                    }
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

    /** Keep the Bash mirror downstream from the live editor before any save/export. */
    suspend fun syncAgentMirrorFromEditor(active: WatchEditorSession) {
        val scope = active.logicalId ?: "draft:${active.draftDirectory.name}"
        val workspaceId = agentWorkspaceManager.bindingFor(scope)?.workspaceId ?: return
        val candidate = active.agentCandidate()
        val resources = active.resourceInventory().associate { asset ->
            asset.relativePath to Files.readAllBytes(active.draftDirectory.toPath().resolve(asset.relativePath))
        }
        agentWorkspaceManager.syncFaceMirror(workspaceId, candidate.watchXml, candidate.globalScript, resources, scope)
    }

    BackHandler(onBack = ::requestClose)

    if (showDiscardDialog) {
        FlueAlertDialog(
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

    if (showExportOptions) {
        FlueAlertDialog(
            onDismissRequest = { showExportOptions = false },
            title = { Text(stringResource(R.string.watch_editor_export_mode_title)) },
            text = { Text(stringResource(R.string.watch_editor_export_mode_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        beginExport(
                            protectedExport = false,
                            toDirectory = pendingExportToDirectory,
                        )
                    },
                ) {
                    Text(stringResource(R.string.watch_editor_export_plain))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        beginExport(
                            protectedExport = true,
                            toDirectory = pendingExportToDirectory,
                        )
                    },
                ) {
                    Text(stringResource(R.string.watch_editor_export_protected))
                }
            },
            extraAction = {
                TextButton(onClick = { showExportOptions = false }) {
                    Text(stringResource(R.string.watch_editor_cancel))
                }
            },
        )
    }

    if (showSaveDialog) {
        FlueAlertDialog(
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
                                    // Save is editor -> installed face. Refresh the
                                    // downstream mirror first so a later Agent/Bash
                                    // action cannot resurrect an older script.
                                    syncAgentMirrorFromEditor(active)
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
                                            active.exportWatch(archive, protectedExport = active.isProtected)
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
        FlueAlertDialog(
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
                TextButton(onClick = { showUploadSaveRequiredDialog = false }) {
                    Text(stringResource(R.string.watch_editor_cancel))
                }
            },
            // 「另存为」是第三个动作：走 extraAction 独占一行胶囊，不和「取消」共用一枚。
            extraAction = if (canSaveCurrent) {
                {
                    TextButton(
                        onClick = {
                            showUploadSaveRequiredDialog = false
                            requestSave(saveAs = true, uploadAfterSave = true)
                        },
                    ) {
                        Text(stringResource(R.string.watch_editor_save_as))
                    }
                }
            } else {
                null
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
                    val agentScope = active.logicalId ?: "draft:${active.draftDirectory.name}"
                    LaunchedEffect(agentScope) {
                        pendingAgentContinuation = withContext(Dispatchers.IO) {
                            agentContinuationStore.load(agentScope)
                        }
                    }
                    LaunchedEffect(agentScope) { agentViewModel.onEditorEntered(agentScope) }
                    DisposableEffect(Unit) {
                        onDispose { agentViewModel.onEditorExited() }
                    }
                    val agentToolHost = remember(active, previewRenderer, previewAmbient, agentWorkspaceManager, agentScope) {
                        WatchEditorSessionAgentToolHost(
                            session = active,
                            nativePreview = previewRenderer?.let { renderer ->
                                WatchEditorSessionAgentToolHost.NativePreviewRenderer { session, request, candidate ->
                                    val render = {
                                        val requestedAmbient = request.display == WatchAgentDisplayMode.DIM
                                        renderer.setAmbientMode(requestedAmbient)
                                        try {
                                            val target = request.target.removePrefix("layer:")
                                            val layer = session.snapshot().layers.firstOrNull {
                                                it.key.value == target || it.id == target
                                            }
                                            if (layer != null && request.mode == WatchAgentPreviewMode.ISOLATED) {
                                                renderer.renderLayerThumbnail(layer.definitionIndex, PREVIEW_IMAGE_SIZE_PX)
                                            } else {
                                                renderer.renderFaceBitmap(PREVIEW_IMAGE_SIZE_PX)
                                            }
                                        } finally {
                                            renderer.setAmbientMode(previewAmbient)
                                        }
                                    }
                                    // The renderer is main-thread bound. Post from a worker and
                                    // wait only there; never runBlocking(Main.immediate), which
                                    // can deadlock the editor while a canvas gesture is committing.
                                    val bitmap = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                                        runCatching { render() }.getOrNull()
                                    } else {
                                        val future = java.util.concurrent.FutureTask<android.graphics.Bitmap?> { runCatching { render() }.getOrNull() }
                                        android.os.Handler(android.os.Looper.getMainLooper()).post(future)
                                        runCatching { future.get(8, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                                    }
                                    val bytes = bitmap?.let { rendered ->
                                        try {
                                            java.io.ByteArrayOutputStream().use { output ->
                                                rendered.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                                                output.toByteArray()
                                            }
                                        } finally {
                                            rendered.recycle()
                                        }
                                    }
                                    WatchAgentPreviewResult(
                                        revision = candidate.revision,
                                        target = request.target,
                                        renderer = "flue-watchmaker-native",
                                        available = bytes != null,
                                        imageBytes = bytes,
                                    )
                                }
                            },
                            workspaceManager = agentWorkspaceManager,
                            watchScope = agentScope,
                            onDocumentMutated = {
                                scope.launch {
                                    val next = withContext(Dispatchers.IO) {
                                        active.flushDraftForPreview()
                                        val candidate = active.agentCandidate()
                                        val workspaceId = agentWorkspaceManager.bindingFor(agentScope)?.workspaceId
                                        if (workspaceId != null) {
                                            val resources = active.resourceInventory().associate { asset ->
                                                asset.relativePath to Files.readAllBytes(active.draftDirectory.toPath().resolve(asset.relativePath))
                                            }
                                            agentWorkspaceManager.syncFaceMirror(workspaceId, candidate.watchXml, candidate.globalScript, resources, agentScope)
                                        }
                                        active.snapshot()
                                    }
                                    updateSnapshot(next)
                                    previewToken++
                                }
                            },
                        )
                    }
                    DisposableEffect(agentToolHost) {
                        agentViewModel.bindToolHost(agentToolHost)
                        onDispose { agentViewModel.unbindToolHost(agentToolHost) }
                    }
                    fun openAgentFromEditor() {
                        if (pendingAgentContinuation != null) {
                            showAgentContinuationDialog = true
                        } else {
                            agentViewModel.openAgent()
                        }
                    }

                    fun restoreAgentContinuation() {
                        val continuation = pendingAgentContinuation ?: return
                        showAgentContinuationDialog = false
                        busyMessage = context.getString(R.string.watch_editor_status_restoring_agent_draft)
                        errorMessage = null
                        scope.launch {
                            runCatching {
                                val restored = withContext(Dispatchers.IO) {
                                    val mirror = agentWorkspaceManager.readFaceMirror(continuation.workspaceId)
                                        ?: error("续接镜像不存在")
                                    require(mirror.watchXml.isNotBlank()) { "续接镜像缺少 watch.xml" }
                                    val resources = mirror.resources.associateWith { relative ->
                                        val path = agentWorkspaceManager.resolveWorkspaceFile(
                                            continuation.workspaceId,
                                            "face/$relative",
                                        ) ?: error("续接资源不存在：$relative")
                                        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                                            "续接资源不是普通文件：$relative"
                                        }
                                        Files.readAllBytes(path)
                                    }
                                    active.restoreAgentMirror(mirror.watchXml, mirror.globalScript, resources)
                                }
                                updateSnapshot(restored)
                                previewToken++
                                withContext(Dispatchers.IO) {
                                    agentContinuationStore.clear(agentScope)
                                }
                                pendingAgentContinuation = null
                                agentViewModel.resumeConversation(continuation.conversationId)
                                agentViewModel.openAgent()
                            }.onFailure { error ->
                                errorMessage = context.localizedEditorError(
                                    R.string.watch_editor_error_restore_agent_draft,
                                    error,
                                )
                            }
                            busyMessage = null
                        }
                    }

                    WatchEditorScreen(
                        snapshot = current,
                        selectedLayer = selected,
                        selectedLayerKeys = selectedLayerKeys,
                        multiSelectMode = multiSelectMode,
                        onToggleMultiSelect = {
                            multiSelectMode = !multiSelectMode
                            if (!multiSelectMode) selectedLayerKeys = setOfNotNull(selectedLayerKey)
                            selectionGeneration++
                        },
                        previewAmbient = previewAmbient,
                        onToggleAmbient = { previewAmbient = !previewAmbient },
                        batchTransformPreview = batchTransformPreview,
                        onBeginTranslation = { key ->
                            if (!transformGate.tryBeginGesture()) false else {
                                runCatching {
                                    liveApplicator?.consumePendingChanges()?.let { (pendingKey, changes) -> active.updateLayerAttributes(pendingKey, changes) }
                                    pendingTransformRefreshToken = null
                                    previewRenderer?.cancelLiveLayerTransformHandoff()
                                    val resolved = current.layers.mapNotNull { layer ->
                                        previewRenderer?.resolveEditorLayerTransform(layer.definitionIndex)?.let { t ->
                                            layer.key to WatchEditorTransform(t.x, t.y, t.scaleX, t.scaleY, t.rotationDegrees)
                                        }
                                    }.toMap()
                                    batchTransformPreview = active.beginLayerTranslationGesture(
                                        if (multiSelectMode) selectedLayerKeys else setOf(key), resolved, previewAmbient,
                                    )
                                    true
                                }.getOrElse { error ->
                                    transformGate.cancelGesture()
                                    errorMessage = context.localizedEditorError(R.string.watch_editor_error_begin_gesture, error)
                                    false
                                }
                            }
                        },
                        onUpdateTranslation = { dx, dy ->
                            if (transformGate.hasActiveGesture()) {
                                batchTransformPreview = active.updateLayerTranslationPreview(dx, dy)
                            }
                        },
                        onUpdateSelectionScale = { factor ->
                            if (transformGate.hasActiveGesture()) {
                                batchTransformPreview = active.updateLayerSelectionScalePreview(factor)
                            }
                        },
                        onEndTranslation = { cancelled ->
                            if (transformGate.beginCommit()) {
                                transformCommitInFlight = true
                                scope.launch {
                                    try {
                                        val next = withContext(Dispatchers.IO) {
                                            if (cancelled) active.cancelLayerTranslationGesture()
                                            else { active.commitLayerTranslationGesture(); active.flushDraftForPreview() }
                                            active.snapshot()
                                        }
                                        updateSnapshot(next)
                                        if (cancelled) batchTransformPreview = emptyMap() else {
                                            val token = previewToken + 1
                                            previewRenderer?.handoffLiveLayerTransformOnSourceReady(token)
                                            pendingTransformRefreshToken = token
                                            previewToken = token
                                        }
                                    } catch (cancelledError: CancellationException) {
                                        runCatching { active.cancelLayerTranslationGesture() }
                                        batchTransformPreview = emptyMap()
                                        throw cancelledError
                                    } catch (error: Throwable) {
                                        runCatching { active.cancelLayerTranslationGesture() }
                                        batchTransformPreview = emptyMap()
                                        errorMessage = context.localizedEditorError(R.string.watch_editor_error_commit_gesture, error)
                                    } finally { transformGate.finishCommit(); transformCommitInFlight = false }
                                }
                            }
                        },
                        previewDescriptor = draftDescriptor(context, active, current),
                        previewToken = previewToken,
                        transformPreview = transformPreview,
                        propertyDraftRevision = propertyDraftRevision,
                        transformCommitInFlight = transformCommitInFlight,
                        busyMessage = busyMessage,
                        errorMessage = errorMessage,
                        imageSizeLookup = imageSizeLookup,
                        draftDirectory = active.draftDirectory,
                        tapHapticsEnabled = tapHapticsEnabled,
                        gravityBackdropBlurEnabled = editorGravityBackdropBlurEnabled,
                        onBack = ::requestClose,
                        onSelectLayer = ::selectLayer,
                        onFocusLayer = { key ->
                            selectedLayerKeys = if (multiSelectMode) selectedLayerKeys + key else setOf(key)
                            if (selectedLayerKey != key) selectionGeneration++
                            selectedLayerKey = key
                        },
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
                                    active.layerBoundTransformAttributes(key)
                                        .takeIf { it.isNotEmpty() && boundTransformNoticeShownFor != key }
                                        ?.let { bound ->
                                            boundTransformNoticeShownFor = key
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.watch_editor_transform_bound_fmt,
                                                    bound.sorted().joinToString("、"),
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
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
                                editor.addLayer(type, defaultAttributes(type, editor.snapshot().formatVersion))
                            })
                        },
                        onMoveLayer = { key, target ->
                            runSessionOperation(context.getString(R.string.watch_editor_status_reorder_layer), operation = { editor ->
                                if (multiSelectMode && key in selectedLayerKeys) editor.reorderLayers(selectedLayerKeys, target)
                                else editor.reorderLayer(key, target)
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
                            openVideoPicker()
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
                            requestExportOptions(toDirectory = false)
                        },
                        onStepLayer = { key, direction ->
                            runSessionOperation(context.getString(R.string.watch_editor_status_reorder_layer), operation = { editor ->
                                if (multiSelectMode && key in selectedLayerKeys) {
                                    editor.moveLayersByOne(selectedLayerKeys, direction)
                                } else {
                                    editor.snapshot().layers.firstOrNull { it.key == key }?.let { layer ->
                                        editor.reorderLayer(key, layer.order + direction)
                                    }
                                }
                                key
                            })
                        },
                        onOpenOtherApps = {
                            if (busyMessage == null && !transformGate.isBusy()) scope.launch {
                                busyMessage = context.getString(R.string.watch_editor_status_exporting)
                                errorMessage = null
                                try {
                                    var flushError: Throwable? = null
                                    liveApplicator?.flush(onApplied = {}, onError = { flushError = it })
                                    flushError?.let { throw it }
                                    refreshDraftPreview(active)
                                    val file = withContext(Dispatchers.IO) {
                                        active.flushDraftForPreview()
                                        File.createTempFile("flue_open_", ".watch", context.cacheDir).also {
                                            active.exportWatch(it, protectedExport = active.isProtected)
                                        }
                                    }
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        .apply { clipData = ClipData.newRawUri("watch", uri) }
                                    if (context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
                                        errorMessage = context.getString(R.string.watch_editor_open_no_handler)
                                    } else context.startActivity(Intent.createChooser(intent, context.getString(R.string.watch_editor_open_other_apps)))
                                } catch (_: ActivityNotFoundException) {
                                    errorMessage = context.getString(R.string.watch_editor_open_no_handler)
                                } catch (error: Throwable) {
                                    errorMessage = context.localizedEditorError(R.string.watch_editor_error_operation, error)
                                } finally { busyMessage = null }
                            }
                        },
                        onUpdateShape = { shapeCode ->
                            runSessionOperation(context.getString(R.string.watch_editor_status_update_shape), operation = { editor ->
                                editor.updateRootAttribute("shape", shapeCode)
                                selectedLayerKey
                            })
                        },
                        onUpdateBackground = { hex ->
                            runSessionOperation(context.getString(R.string.watch_editor_status_update_background), operation = { editor ->
                                // null 走 removeAttribute，回落到渲染侧的默认黑。
                                editor.updateRootAttribute("bg_color", hex)
                                selectedLayerKey
                            })
                        },
                        onEditGlobalScript = {
                            if (busyMessage == null) {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { active.globalScript() }
                                    }.onSuccess { script ->
                                        globalScriptDraft = script
                                    }.onFailure { error ->
                                        errorMessage = context.localizedEditorError(
                                            R.string.watch_editor_error_operation,
                                            error,
                                        )
                                    }
                                }
                            }
                        },
                        onManageResources = {
                            if (busyMessage == null) {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { active.resourceInventory() }
                                    }.onSuccess { inventory ->
                                        resourceInventory = inventory
                                    }.onFailure { error ->
                                        errorMessage = context.localizedEditorError(
                                            R.string.watch_editor_error_operation,
                                            error,
                                        )
                                    }
                                }
                            }
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
                        onExportLong = { requestExportOptions(toDirectory = true) },
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
                        onOpenAgentSettings = openAgentSettings,
                        onOpenAgent = ::openAgentFromEditor,
                        watchScope = agentScope,
                        agentViewModel = agentViewModel,
                        onPreviewRefreshReady = { loadedToken ->
                            if (pendingTransformRefreshToken == loadedToken) {
                                pendingTransformRefreshToken = null
                                transformPreview = null
                                batchTransformPreview = emptyMap()
                            }
                        },
                        onPreviewRefreshFailed = { failedToken ->
                            if (pendingTransformRefreshToken == failedToken) {
                                pendingTransformRefreshToken = null
                                transformPreview = null
                                batchTransformPreview = emptyMap()
                            }
                        },
                    )
                    val continuation = pendingAgentContinuation
                    if (showAgentContinuationDialog && continuation != null) {
                        FlueAlertDialog(
                            onDismissRequest = { showAgentContinuationDialog = false },
                            title = { Text(stringResource(R.string.watch_editor_agent_resume_title)) },
                            text = { Text(stringResource(R.string.watch_editor_agent_resume_message)) },
                            confirmButton = {
                                TextButton(onClick = ::restoreAgentContinuation) {
                                    Text(stringResource(R.string.watch_editor_agent_resume))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        showAgentContinuationDialog = false
                                        pendingAgentContinuation = null
                                        scope.launch(Dispatchers.IO) {
                                            agentContinuationStore.clear(agentScope)
                                        }
                                        agentViewModel.openAgent()
                                    },
                                ) {
                                    Text(stringResource(R.string.watch_editor_agent_start_fresh))
                                }
                            },
                        )
                    }
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
                            kind = videoConversionKind,
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
                    globalScriptDraft?.let { script ->
                        EditorGlobalScriptOverlay(
                            initialScript = script,
                            errorMessage = errorMessage,
                            onCancel = { globalScriptDraft = null },
                            onSave = { updated ->
                                runSessionOperation(
                                    status = context.getString(R.string.watch_editor_status_saving_script),
                                    operation = { editor ->
                                        editor.updateGlobalScript(updated)
                                        selectedLayerKey
                                    },
                                    onComplete = { globalScriptDraft = null },
                                )
                            },
                        )
                    }
                    resourceInventory?.let { inventory ->
                        EditorResourceManagerOverlay(
                            entries = inventory,
                            busy = busyMessage != null,
                            draftDirectory = active.draftDirectory,
                            onImportAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onDelete = { path ->
                                runSessionOperation(
                                    status = context.getString(R.string.watch_editor_status_deleting_resource),
                                    operation = { editor ->
                                        editor.deleteResource(path)
                                        selectedLayerKey
                                    },
                                    // 删完就地刷新清单，否则那一行还留在屏幕上。
                                    onComplete = {
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) { active.resourceInventory() }
                                            }.onSuccess { resourceInventory = it }
                                        }
                                    },
                                )
                            },
                            onDismiss = { resourceInventory = null },
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
    // 与 runtime 一致：先按原样找，再退回约定目录。videos/ 也要试 —— video
    // 图层的素材落在那里，只找 images/ 时整层没有缩略图（老师实机反馈）。
    val file = listOf(
        File(draftDirectory, path),
        File(draftDirectory, "images/$path"),
        File(draftDirectory, "videos/$path"),
    ).firstOrNull(File::isFile) ?: return null
    if (file.extension.lowercase(Locale.ROOT) in PLAYABLE_VIDEO_EXTENSIONS) {
        return resolveVideoFirstFrameThumbnail(file, sizePx)
    }
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
 * video 图层的缩略图 = 视频首帧。
 *
 * 用 `getScaledFrameAtTime` 让平台直接给缩放好的帧，省掉「解全尺寸再缩」的
 * 一步。取 0 时刻并允许向后找最近关键帧（`OPTION_CLOSEST_SYNC`），首帧不是
 * 关键帧的视频也能出图。
 */
private fun resolveVideoFirstFrameThumbnail(
    file: File,
    sizePx: Int,
): androidx.compose.ui.graphics.ImageBitmap = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        retriever.getScaledFrameAtTime(
            0L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            sizePx,
            sizePx,
        )?.asImageBitmap()
    } finally {
        // release() 自身可能抛（损坏文件），不能让它盖掉上面的结果。
        runCatching { retriever.release() }
    }
}.getOrNull() ?: watchEditorBlackVideoThumbnail(sizePx).asImageBitmap()

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
    selectedLayerKeys: Set<WatchEditorLayerKey>,
    multiSelectMode: Boolean,
    onToggleMultiSelect: () -> Unit,
    previewAmbient: Boolean,
    onToggleAmbient: () -> Unit,
    batchTransformPreview: Map<WatchEditorLayerKey, WatchEditorTransform>,
    onBeginTranslation: (WatchEditorLayerKey) -> Boolean,
    onUpdateTranslation: (Float, Float) -> Unit,
    onUpdateSelectionScale: (Float) -> Unit,
    onEndTranslation: (Boolean) -> Unit,
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
    onFocusLayer: (WatchEditorLayerKey) -> Unit,
    onBeginTransform: (WatchEditorLayerKey) -> WatchEditorTransform?,
    onUpdateTransform: (WatchEditorLayerKey, WatchEditorTransform) -> Unit,
    onEndTransform: (WatchEditorLayerKey, Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddLayer: (String) -> Unit,
    onMoveLayer: (WatchEditorLayerKey, Int) -> Unit,
    onStepLayer: (WatchEditorLayerKey, Int) -> Unit,
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
    onOpenOtherApps: () -> Unit,
    onExportLong: () -> Unit,
    onUpdateShape: (String) -> Unit,
    onUpdateBackground: (String?) -> Unit,
    onEditGlobalScript: () -> Unit,
    onManageResources: () -> Unit,
    onUploadToStore: () -> Unit,
    onImportWatch: () -> Unit,
    onImportWatchLong: () -> Unit,
    onConvertWatch: () -> Unit,
    onConvertWatchLong: () -> Unit,
    tapHapticsEnabled: Boolean = true,
    /** 底部模糊背景层开关，跟随设置（预览要和桌面观感一致）。 */
    gravityBackdropBlurEnabled: Boolean = true,
    onPreviewRefreshReady: (Int) -> Unit = {},
    onPreviewRefreshFailed: (Int) -> Unit = {},
    /**
     * 把画布用的渲染 View 上抛给 Route：保存 / 上传前要用它离屏重渲一张
     * preview.jpg 写回草稿，否则列表和商店永远显示原作者那张预览图。
     */
    onPreviewRendererChanged: (WatchmakerWatchFaceView?) -> Unit = {},
    watchScope: String,
    agentViewModel: WatchAgentViewModel,
    onOpenAgentSettings: () -> Unit,
    onOpenAgent: () -> Unit = { agentViewModel.openAgent() },
) {
    var panel by rememberSaveable { mutableStateOf(WatchEditorPanel.LAYERS) }
    var previousEditorPanel by rememberSaveable { mutableStateOf(WatchEditorPanel.LAYERS) }
    val agentState by agentViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val agentFeatureStore = remember(context) { AgentFeatureSettingsStore(context) }
    var agentEnabled by remember { mutableStateOf(agentFeatureStore.load().enabled) }
    DisposableEffect(lifecycleOwner, agentFeatureStore) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) agentEnabled = agentFeatureStore.load().enabled
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val visiblePanels = remember(agentEnabled) {
        if (agentEnabled) WatchEditorPanel.entries else WatchEditorPanel.entries.filter { it != WatchEditorPanel.AGENT }
    }
    // 三档布局：上箭头=画布铺满全屏，下箭头=图层/属性/表盘面板铺满全屏，
    // 中间态为分屏。用一个动画化的高度比例驱动，绝不整树替换——以前
    // previewFullscreen 的提前 return 会把 AndroidView 销毁重建，进出全屏
    // 表盘要整个重载一遍。
    var editorLayout by rememberSaveable { mutableStateOf(WatchEditorLayout.SPLIT) }
    LaunchedEffect(agentEnabled) {
        if (!agentEnabled && panel == WatchEditorPanel.AGENT) {
            agentViewModel.minimize()
            panel = previousEditorPanel
            editorLayout = WatchEditorLayout.SPLIT
        }
    }
    val previewFullscreen = editorLayout == WatchEditorLayout.CANVAS_FULL
    // 预览播放开关：一次点击同时控制序列帧、视频与重力景深。默认暂停，
    // 进编辑器先看到表盘的默认正视角，也避免一进来就 30fps 解码。
    var previewPlaying by rememberSaveable { mutableStateOf(false) }
    // Each editor level keeps its own position. Re-creating one state whenever
    // the tab changes made returning from Properties jump the layer list back
    // to the top, which is especially disorienting on a small watch viewport.
    val layerListState = rememberLazyListState()
    val propertiesListState = rememberLazyListState()
    val watchListState = rememberLazyListState()
    val agentListState = rememberLazyListState()
    val panelListState: LazyListState = when (panel) {
        WatchEditorPanel.LAYERS -> layerListState
        WatchEditorPanel.PROPERTIES -> propertiesListState
        WatchEditorPanel.WATCH -> watchListState
        WatchEditorPanel.AGENT -> agentListState
    }
    val selectedBlockOrders = remember(snapshot.layers, selectedLayerKeys) {
        val block = watchEditorLayerSelectionBlock(snapshot.layers, selectedLayerKeys)
        snapshot.layers.filter { it.key in block }.map { it.order }
    }
    var collapsedLayerKeys by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var scrollToLayerRequest by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val deviceViewport = remember(context.resources.displayMetrics) {
        context.resources.displayMetrics.let { WatchEditorViewport(it.widthPixels.coerceAtLeast(1), it.heightPixels.coerceAtLeast(1)) }
    }
    // WatchMaker's authored coordinate space is square. A physical device is an optional preview
    // target, not the editor default; otherwise a phone-shaped host distorts the outer canvas as
    // soon as the editor opens.
    var previewViewport by remember { mutableStateOf(WatchEditorViewport(512, 512)) }
    var previewRoundOverride by remember { mutableStateOf<Boolean?>(null) }
    var showViewportDialog by remember { mutableStateOf(false) }
    if (showViewportDialog) EditorPreviewViewportDialog(
        currentSize = previewViewport, roundOverride = previewRoundOverride,
        onApply = { size, round -> previewViewport = size; previewRoundOverride = round; showViewportDialog = false },
        onDismiss = { showViewportDialog = false },
    )
    var bodySensorsPermissionGranted by remember(context) {
        mutableStateOf(hasEditorBodySensorsPermission(context))
    }
    val bodySensorsPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) {
        bodySensorsPermissionGranted = hasEditorBodySensorsPermission(context)
    }
    val isRound = isFlueScreenRound()
    val toolbarMaxWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val toolbarViewportDp = watchEditorToolbarViewportWidth(isRound, toolbarMaxWidthDp)
    val roundContentMode = roundEditorContentMode(
        isRound = isRound,
        panelFullscreen = editorLayout == WatchEditorLayout.PANEL_FULL,
    )
    val editorFisheyeEnabled = roundContentMode == RoundEditorContentMode.FISHEYE
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
    BackHandler(enabled = agentState.surface == com.flue.launcher.watchface.agent.model.AgentSurface.FULLSCREEN) {
        agentViewModel.exitFullscreen()
    }
    BackHandler(enabled = agentState.surface != com.flue.launcher.watchface.agent.model.AgentSurface.FULLSCREEN && panel == WatchEditorPanel.AGENT) {
        agentViewModel.minimize()
        panel = previousEditorPanel
    }
    BackHandler(enabled = editorLayout != WatchEditorLayout.SPLIT && panel != WatchEditorPanel.AGENT) {
        editorLayout = WatchEditorLayout.SPLIT
    }

    var selectionScaleFactor by remember { mutableFloatStateOf(1f) }
    var selectionScaleGestureActive by remember { mutableStateOf(false) }
    LaunchedEffect(batchTransformPreview.isEmpty(), transformCommitInFlight) {
        if (batchTransformPreview.isEmpty() && !transformCommitInFlight) {
            selectionScaleFactor = 1f
            selectionScaleGestureActive = false
        }
    }
    val previewContent: @Composable (Modifier) -> Unit = { previewModifier ->
        Box(modifier = previewModifier) {
        WatchEditorPreview(
            descriptor = previewDescriptor,
            refreshToken = previewToken,
            bodySensorsPermissionGranted = bodySensorsPermissionGranted,
            layers = snapshot.layers,
            selectedLayer = selectedLayer,
            selectedLayerKeys = selectedLayerKeys,
            multiSelectMode = multiSelectMode,
            ambientMode = previewAmbient,
            previewRoundOverride = previewRoundOverride,
            batchTransformPreview = batchTransformPreview,
            onBeginTranslation = onBeginTranslation,
            onUpdateTranslation = onUpdateTranslation,
            onEndTranslation = onEndTranslation,
            transformPreview = transformPreview,
            enabled = busyMessage == null && !transformCommitInFlight,
            imageSizeLookup = imageSizeLookup,
            onSelectFromCanvas = { key -> onSelectLayer(key) },
            onCanvasTap = { key ->
                if (key != null && !multiSelectMode) {
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
            modifier = Modifier.fillMaxSize(),
            onFaceViewChanged = {
                thumbnailRenderer = it
                onPreviewRendererChanged(it)
            },
            onRefreshReady = onPreviewRefreshReady,
            onRefreshFailed = onPreviewRefreshFailed,
            previewPlaying = previewPlaying,
            tapHapticsEnabled = tapHapticsEnabled,
            gravityBackdropBlurEnabled = gravityBackdropBlurEnabled,
            designExtentWidth = WatchCanvasSize.DEFAULT.designExtentWidth,
            designExtentHeight = WatchCanvasSize.DEFAULT.designExtentHeight,
            // 视频层的圆形裁剪判据，与渲染器给 Canvas 段那一刀同源（表盘声明的形状）。
            faceIsRound = previewRoundOverride ?: (watchFaceShape(snapshot.rootAttributes["shape"]) == WatchFaceShape.ROUND),
        )
        // 息屏预览只切换渲染状态，不应额外出现图层缩放滑杆；缩放是多选编辑手势的工具。
        if (selectedLayerKeys.isNotEmpty() && multiSelectMode) {
            EditorSelectionScaleSlider(
                value = selectionScaleFactor,
                enabled = busyMessage == null && !transformCommitInFlight &&
                    (batchTransformPreview.isEmpty() || selectionScaleGestureActive),
                onValueChange = { factor ->
                    val key = selectedLayer?.key ?: selectedLayerKeys.first()
                    if (selectionScaleGestureActive || onBeginTranslation(key)) {
                        selectionScaleGestureActive = true
                        selectionScaleFactor = factor
                        onUpdateSelectionScale(factor)
                    }
                },
                onValueChangeFinished = {
                    if (selectionScaleGestureActive) {
                        selectionScaleGestureActive = false
                        onEndTranslation(false)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
        }
    }

    // 圆屏弦宽适配的两个测量点（详见下方 chordViewportWidth 处的注释）。
    var chordRootTopInWindow by remember { mutableFloatStateOf(0f) }
    var chordLayerListTopInWindow by remember { mutableFloatStateOf(0f) }
    var chordPropertiesListTopInWindow by remember { mutableFloatStateOf(0f) }
    var chordWatchListTopInWindow by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .onGloballyPositioned { chordRootTopInWindow = it.positionInWindow().y },
    ) {
        val editorMaxHeight = maxHeight
        // Column 内部拿不到 BoxWithConstraints 作用域，宽度约束要在这里捕获。
        val editorMaxWidth = maxWidth
        val splitPreviewSize = watchEditorPreviewSizeDp(maxWidth.value, maxHeight.value)
        // 三档布局共用一条动画化的高度比例：预览常驻挂载只改高度，
        // AndroidView（表盘渲染器）全程不销毁、切换全屏不再重载表盘。
        val splitFraction = (splitPreviewSize / maxHeight.value).coerceIn(0.24f, 0.60f)
        val agentKeyboardVisible = panel == WatchEditorPanel.AGENT &&
            androidx.compose.foundation.layout.WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val targetPreviewFraction = if (agentKeyboardVisible) 0f else when (editorLayout) {
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
        // 圆屏弦宽适配需要「圆」的坐标系：直径 = 编辑器根视口全宽，圆心 y =
        // 根视口垂直中心。图层列表不是从屏幕顶端开始的（上面还有预览画布和
        // TabRow），所以要记录列表顶边相对根容器的偏移，把它补进元素中心 y，
        // 否则圆心会被当成列表自己的中点，弦宽全算错。
        //
        // 两点测量（根容器顶边 + 列表顶边）而不是只读 positionInRoot：编辑器
        // 根容器未必就贴在 composition root 上，做差才不依赖这个假设。
        //
        // 用 mutableFloatStateOf + 只在 graphicsLayer 的 lambda 里读：那是绘制期
        // 回调，值变化只让图层失效，不会重组带缩略图的图层行。
        val chordDensity = LocalDensity.current
        val chordViewportWidth = with(chordDensity) { editorMaxWidth.toPx() }
        val chordCenterY = with(chordDensity) { editorMaxHeight.toPx() } / 2f
        val layerListTopInViewport = { chordLayerListTopInWindow - chordRootTopInWindow }
        val propertiesListTopInViewport = { chordPropertiesListTopInWindow - chordRootTopInWindow }
        val watchListTopInViewport = { chordWatchListTopInWindow - chordRootTopInWindow }
        fun editorListContentPadding(contentTopPx: Float): PaddingValues {
            if (!editorFisheyeEnabled) {
                return PaddingValues(
                    start = horizontalListPadding,
                    end = horizontalListPadding,
                    top = 10.dp,
                    bottom = 12.dp,
                )
            }
            val roundPadding = roundScrollablePadding(
                viewportHeight = with(chordDensity) { editorMaxHeight.toPx() },
                contentTop = contentTopPx,
                itemHeight = with(chordDensity) { 72.dp.toPx() },
                minimumPadding = with(chordDensity) { 10.dp.toPx() },
            )
            return PaddingValues(
                start = horizontalListPadding,
                end = horizontalListPadding,
                top = with(chordDensity) { roundPadding.top.toDp() },
                bottom = with(chordDensity) { roundPadding.bottom.toDp() },
            )
        }
        // 兜底裁剪：任何一档的子项越界都不会再画到兄弟节点上面。
        Column(modifier = Modifier.fillMaxSize().clipToBounds()) {
            // 顶栏在两个全屏档整块滑出让位（高度动画驱动下方内容重排）。
            AnimatedVisibility(
                visible = editorLayout == WatchEditorLayout.SPLIT && !agentKeyboardVisible,
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
                        Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.watch_editor_back))
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
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                if (multiSelectMode) R.string.watch_editor_selection_count else if (snapshot.dirty) {
                                    R.string.watch_editor_draft_dirty
                                } else {
                                    R.string.watch_editor_draft_synced
                                },
                                selectedLayerKeys.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            // 圆屏顶栏两侧被返回键和浮动工具条夹住，weight(1f) 分给
                            // 标题列的宽度很窄。以前这两行都没有 maxLines，Compose 会
                            // 逐字换行 —— 「草稿已同步」被挤成竖排五字（老师报的问题）。
                            // 限定单行 + 省略号，宽度不够时宁可截断也不竖排。
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlueExpressiveFloatingToolbar(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            // Material3's minimum touch target makes five editor actions wider
                            // than the usable chord on a round display. Keep the toolbar bounded
                            // and let the user sweep across the actions instead of painting into
                            // the curved edge.
                            .widthIn(max = toolbarViewportDp.dp)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        IconButton(onClick = onToggleMultiSelect,
                            enabled = busyMessage == null && !transformCommitInFlight && transformPreview == null && batchTransformPreview.isEmpty(),
                            modifier = Modifier.size(36.dp)) {
                            Icon(
                                FlueMiuixIcons.Check,
                                contentDescription = stringResource(if (multiSelectMode) R.string.watch_editor_multi_select_done else R.string.watch_editor_multi_select),
                                tint = if (multiSelectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(
                            onClick = {
                                if (!previewPlaying) onSelectLayer(null)
                                previewPlaying = !previewPlaying
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                if (previewPlaying) FlueMiuixIcons.Pause else FlueMiuixIcons.Play,
                                contentDescription = stringResource(
                                    if (previewPlaying) {
                                        R.string.watch_editor_preview_pause
                                    } else {
                                        R.string.watch_editor_preview_play
                                    },
                                ),
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
                val ratio = previewViewport.aspectRatio
                    .takeIf { it.isFinite() && it > 0f } ?: 1f
                val byHeight = availableHeight * ratio        // 以高定宽
                val previewWidth = min(byHeight, editorMaxWidth)
                val previewHeight = previewWidth / ratio
                previewContent(
                    Modifier
                        .size(width = previewWidth, height = previewHeight),
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
                    selectedTabIndex = visiblePanels.indexOf(panel).coerceAtLeast(0),
                    modifier = Modifier.padding(horizontal = if (isRound) 26.dp else 0.dp),
                ) {
                    visiblePanels.forEach { candidate ->
                        Tab(
                            selected = panel == candidate,
                            modifier = Modifier,
                            onClick = {
                                if (candidate == WatchEditorPanel.AGENT) {
                                    if (panel != WatchEditorPanel.AGENT) previousEditorPanel = panel
                                    editorLayout = WatchEditorLayout.SPLIT
                                    onOpenAgent()
                                }
                                panel = candidate
                            },
                            icon = {
                                Icon(
                                    imageVector = when (candidate) {
                                        WatchEditorPanel.LAYERS -> MiuixIcons.Layers
                                        WatchEditorPanel.PROPERTIES -> MiuixIcons.Tune
                                        WatchEditorPanel.WATCH -> MiuixIcons.WorldClock
                                        WatchEditorPanel.AGENT -> FlueMiuixIcons.Sparkle
                                    },
                                    contentDescription = null,
                                    tint = LocalContentColor.current,
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
                        .onGloballyPositioned {
                            chordLayerListTopInWindow = it.positionInWindow().y
                        }
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
                                    onFocusLayer(row.layer.key)
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
                        .flueOverscroll(layerListState),
                    contentPadding = editorListContentPadding(layerListTopInViewport()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    editorStatusItems(busyMessage, errorMessage)
                    item("add_actions") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .chordScaleItem(
                                    listState = layerListState,
                                    key = "add_actions",
                                    screenCenterY = chordCenterY,
                                    viewportWidth = chordViewportWidth,
                                    enabled = editorFisheyeEnabled,
                                    listTopInViewport = layerListTopInViewport,
                                )
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            insertableLayerTypes(snapshot.formatVersion).forEach { (type, labelRes) ->
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
                                layer.definitionIndex,
                                layer.attributes,
                                previewToken,
                                textThumbnailClockTick,
                            ) {
                                if (layer.type.lowercase(Locale.ROOT).contains("text")) {
                                    thumbnailRenderer?.resolveTextLayerPreview(layer.definitionIndex)
                                        ?: layer.attributes["text"].orEmpty()
                                            .ifBlank { layer.attributes["value"].orEmpty() }
                                            .takeUnless { it.contains('{') || it.contains('}') }
                                } else {
                                    null
                                }
                            }
                            val renderedTextThumbnail = remember(
                                thumbnailRenderer,
                                layer.definitionIndex,
                                layer.attributes,
                                previewToken,
                                textThumbnailClockTick,
                                thumbSizePx,
                            ) {
                                if (layer.type.lowercase(Locale.ROOT) in
                                    setOf("text", "text_curved", "text_ring")
                                ) {
                                    thumbnailRenderer
                                        ?.renderTextLayerThumbnail(layer.definitionIndex, thumbSizePx)
                                        ?.asImageBitmap()
                                } else {
                                    null
                                }
                            }
                            EditorLayerRow(
                                layer = layer,
                                thumbnail = renderedTextThumbnail ?: thumbnail,
                                textThumbnail = textThumbnail,
                                selected = layer.key in selectedLayerKeys,
                                layerCount = snapshot.layers.size,
                                depth = row.depth,
                                hasChildren = row.hasChildren,
                                expanded = row.expanded,
                                enabled = busyMessage == null,
                                modifier = Modifier.graphicsLayer {
                                    alpha = if (draggedLayerKey == layer.key) 0f else 1f
                                    // 圆屏弦宽：只在这里施加 scale，绝不改行宽/
                                    // padding/字号（那会触发重新测量 → 抽搐）。
                                    if (editorFisheyeEnabled) {
                                        val s = chordScaleForListItem(
                                            listState = layerListState,
                                            key = layer.key.value,
                                            screenCenterY = chordCenterY,
                                            viewportWidth = chordViewportWidth,
                                            listTopInViewport = layerListTopInViewport(),
                                        )
                                        scaleX = s
                                        scaleY = s
                                    }
                                },
                                dragging = false,
                                dragOffsetY = 0f,
                                avoidanceOffsetY = avoidanceOffsetY,
                                onSelect = {
                                    onSelectLayer(layer.key)
                                    if (!multiSelectMode) panel = WatchEditorPanel.PROPERTIES
                                },
                                onToggleExpanded = {
                                    val key = layer.key.value
                                    collapsedLayerKeys = if (key in collapsedLayerKeys) {
                                        collapsedLayerKeys - key
                                    } else {
                                        collapsedLayerKeys + key
                                    }
                                },
                                onMoveStep = { onStepLayer(layer.key, it) },
                                canMoveUp = if (multiSelectMode && layer.key in selectedLayerKeys) {
                                    (selectedBlockOrders.minOrNull() ?: 0) > 0
                                } else layer.order > 0,
                                canMoveDown = if (multiSelectMode && layer.key in selectedLayerKeys) {
                                    (selectedBlockOrders.maxOrNull() ?: snapshot.layers.lastIndex) < snapshot.layers.lastIndex
                                } else layer.order < snapshot.layers.lastIndex,
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
                            overlayLayer.definitionIndex,
                            overlayLayer.attributes,
                            previewToken,
                            textThumbnailClockTick,
                        ) {
                            if (overlayLayer.type.lowercase(Locale.ROOT).contains("text")) {
                                thumbnailRenderer?.resolveTextLayerPreview(overlayLayer.definitionIndex)
                                    ?: overlayLayer.attributes["text"].orEmpty()
                                        .ifBlank { overlayLayer.attributes["value"].orEmpty() }
                                        .takeUnless { it.contains('{') || it.contains('}') }
                            } else {
                                null
                            }
                        }
                        val overlayRenderedText = remember(
                            thumbnailRenderer,
                            overlayLayer.definitionIndex,
                            overlayLayer.attributes,
                            previewToken,
                            textThumbnailClockTick,
                            overlayThumbSizePx,
                        ) {
                            if (overlayLayer.type.lowercase(Locale.ROOT) in
                                setOf("text", "text_curved", "text_ring")
                            ) {
                                thumbnailRenderer
                                    ?.renderTextLayerThumbnail(
                                        overlayLayer.definitionIndex,
                                        overlayThumbSizePx,
                                    )
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
                                .graphicsLayer {
                                    translationY = overlayTop
                                    // 拖拽浮层是绝对定位的兄弟节点，不在 LazyList
                                    // 的可见项里，弦宽只能按它自己的 top + 高度算。
                                    if (editorFisheyeEnabled) {
                                        val s = chordScaleForPosition(
                                            itemCenterY = layerListTopInViewport() +
                                                overlayTop + draggedLayerHeightPx / 2f,
                                            viewportWidth = chordViewportWidth,
                                            screenCenterY = chordCenterY,
                                        )
                                        scaleX = s
                                        scaleY = s
                                    }
                                }
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
                                onMoveStep = {},
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
                        .onGloballyPositioned {
                            chordPropertiesListTopInWindow = it.positionInWindow().y
                        }
                        .flueOverscroll(propertiesListState),
                    contentPadding = editorListContentPadding(propertiesListTopInViewport()),
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
                            Box(
                                modifier = Modifier
                                    // 属性面板里的段落会随图层类型增减（点击动作、描边、
                                    // 文字动画…）。不给动画的话换一次选中层，整块面板
                                    // 的高度就是跳变。与设置页用同一条 spec。
                                    .animateContentSize(flueContentSizeSpring)
                                    .chordScaleItem(
                                    listState = propertiesListState,
                                    key = "properties",
                                    screenCenterY = chordCenterY,
                                    viewportWidth = chordViewportWidth,
                                    enabled = editorFisheyeEnabled,
                                    listTopInViewport = propertiesListTopInViewport,
                                ),
                            ) {
                                LayerPropertiesEditor(
                                    layer = selectedLayer,
                                    formatVersion = snapshot.formatVersion,
                                    audioOptions = snapshot.resourcePaths.filter { it.startsWith("sfx/", true) },
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
                }

                WatchEditorPanel.WATCH -> LazyColumn(
                    state = watchListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            chordWatchListTopInWindow = it.positionInWindow().y
                        }
                        .flueOverscroll(watchListState),
                    contentPadding = editorListContentPadding(watchListTopInViewport()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    editorStatusItems(busyMessage, errorMessage)
                    item("watch_summary") {
                        Column(
                            modifier = Modifier.chordScaleItem(
                                listState = watchListState,
                                key = "watch_summary",
                                screenCenterY = chordCenterY,
                                viewportWidth = chordViewportWidth,
                                enabled = editorFisheyeEnabled,
                                listTopInViewport = watchListTopInViewport,
                            ),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
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
                        // 卡片组件本身不收 modifier，外面包一层挂弦宽缩放，
                        // 比逐个改卡片签名侵入小。
                        Box(
                            modifier = Modifier.chordScaleItem(
                                listState = watchListState,
                                key = "watch_shape",
                                screenCenterY = chordCenterY,
                                viewportWidth = chordViewportWidth,
                                enabled = editorFisheyeEnabled,
                                listTopInViewport = watchListTopInViewport,
                            ),
                        ) {
                            EditorWatchShapeCard(
                                rawShape = snapshot.rootAttributes["shape"],
                                enabled = busyMessage == null,
                                onUpdateShape = onUpdateShape,
                            )
                        }
                    }
                    item("watch_preview_settings") {
                        Box(
                            modifier = Modifier.chordScaleItem(
                                listState = watchListState,
                                key = "watch_preview_settings",
                                screenCenterY = chordCenterY,
                                viewportWidth = chordViewportWidth,
                                enabled = editorFisheyeEnabled,
                                listTopInViewport = watchListTopInViewport,
                            ),
                        ) {
                            EditorPreviewSettingsCard(
                                previewViewport = previewViewport,
                                deviceViewport = deviceViewport,
                                ambient = previewAmbient,
                                enabled = busyMessage == null &&
                                    !transformCommitInFlight &&
                                    transformPreview == null &&
                                    batchTransformPreview.isEmpty(),
                                onSelectViewport = { previewViewport = it },
                                onEditCustom = { showViewportDialog = true },
                                onSelectAmbient = { nextAmbient ->
                                    if (nextAmbient != previewAmbient) onToggleAmbient()
                                },
                            )
                        }
                    }
                    item("watch_bg_color") {
                        Box(
                            modifier = Modifier.chordScaleItem(
                                listState = watchListState,
                                key = "watch_bg_color",
                                screenCenterY = chordCenterY,
                                viewportWidth = chordViewportWidth,
                                enabled = editorFisheyeEnabled,
                                listTopInViewport = watchListTopInViewport,
                            ),
                        ) {
                            EditorWatchBackgroundCard(
                                rawBackground = snapshot.rootAttributes["bg_color"],
                                enabled = busyMessage == null,
                                onUpdateBackground = onUpdateBackground,
                            )
                        }
                    }
                    item("watch_data_permission") {
                        Surface(
                            modifier = Modifier.chordScaleItem(
                                listState = watchListState,
                                key = "watch_data_permission",
                                screenCenterY = chordCenterY,
                                viewportWidth = chordViewportWidth,
                                enabled = editorFisheyeEnabled,
                                listTopInViewport = watchListTopInViewport,
                            ),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = LauncherTheme.style.compactShape,
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
                    item("watch_global_script") {
                        Surface(
                            modifier = Modifier.chordScaleItem(
                                listState = watchListState,
                                key = "watch_global_script",
                                screenCenterY = chordCenterY,
                                viewportWidth = chordViewportWidth,
                                enabled = editorFisheyeEnabled,
                                listTopInViewport = watchListTopInViewport,
                            ),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = LauncherTheme.style.compactShape,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.watch_editor_global_script),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(R.string.watch_editor_global_script_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlueOutlinedButton(
                                    onClick = onEditGlobalScript,
                                    enabled = busyMessage == null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.watch_editor_edit_script))
                                }
                            }
                        }
                    }
                    item("watch_actions") {
                        Column(
                            modifier = Modifier.chordScaleItem(
                                listState = watchListState,
                                key = "watch_actions",
                                screenCenterY = chordCenterY,
                                viewportWidth = chordViewportWidth,
                                enabled = editorFisheyeEnabled,
                                listTopInViewport = watchListTopInViewport,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 这一排以前混着 Material 的 Button / OutlinedButton 和
                            // 我们自己的 Surface 按钮：前者用 ButtonDefaults 的胶囊形，
                            // 后者用风格令牌，于是 MIUIX 下同一列按钮圆角一个 20dp
                            // 一个 14dp（老师报的「miuix 下面按钮圆角不统一」）。
                            // 全部换成 Flue* 之后统一走 rowShape。
                            FlueButton(
                                onClick = onSaveAsNew,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(MiuixIcons.Backup, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.watch_editor_save_as))
                            }
                            FlueOutlinedButton(
                                onClick = onSave,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(MiuixIcons.Backup, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.watch_editor_save_current))
                            }
                            FlueOutlinedButton(
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
                            FlueOutlinedButton(onClick = onOpenOtherApps, enabled = busyMessage == null, modifier = Modifier.fillMaxWidth()) {
                                Icon(MiuixIcons.Share, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.watch_editor_open_other_apps))
                            }
                            FlueOutlinedButton(
                                onClick = onUploadToStore,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(MiuixIcons.UploadCloud, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.watch_editor_upload_store))
                            }
                            // 资源管理器：包里到底有什么以前完全不可见（老师要的）。
                            FlueOutlinedButton(
                                onClick = onManageResources,
                                enabled = busyMessage == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(MiuixIcons.Folder, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.watch_editor_manage_resources))
                            }
                        }
                    }
                    item("current_watch_size") {
                        Text(
                            text = stringResource(R.string.watch_editor_current_watch_size_fmt, formatEditorFileSize(draftSizeBytes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = if (isRound) TextAlign.Center else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .chordScaleItem(
                                    listState = watchListState,
                                    key = "current_watch_size",
                                    screenCenterY = chordCenterY,
                                    viewportWidth = chordViewportWidth,
                                    enabled = editorFisheyeEnabled,
                                    listTopInViewport = watchListTopInViewport,
                                ),
                        )
                    }
                    item("wftools_promo") {
                        WfToolsPromoRow(
                            modifier = Modifier.chordScaleItem(
                                listState = watchListState,
                                key = "wftools_promo",
                                screenCenterY = chordCenterY,
                                viewportWidth = chordViewportWidth,
                                enabled = editorFisheyeEnabled,
                                listTopInViewport = watchListTopInViewport,
                            ),
                        )
                    }
                }
                WatchEditorPanel.AGENT -> WatchAgentPanel(
                    viewModel = agentViewModel,
                    onMaximize = { agentViewModel.enterFullscreen() },
                    onOpenSettings = onOpenAgentSettings,
                    onMinimize = {
                        agentViewModel.minimize()
                        panel = previousEditorPanel
                    },
                )
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
        if (agentState.surface == com.flue.launcher.watchface.agent.model.AgentSurface.FULLSCREEN) {
            WatchAgentFullscreen(
                viewModel = agentViewModel,
                onRestore = { agentViewModel.exitFullscreen() },
                onOpenSettings = onOpenAgentSettings,
                onMinimize = {
                    agentViewModel.minimize()
                    panel = previousEditorPanel
                },
            )
        }
    }

}

private enum class WatchEditorPanel(@StringRes val labelRes: Int) {
    LAYERS(R.string.watch_editor_panel_layers),
    PROPERTIES(R.string.watch_editor_panel_properties),
    WATCH(R.string.watch_editor_panel_watch),
    AGENT(R.string.watch_editor_panel_agent),
}

internal fun watchEditorScriptByteCount(script: String): Int =
    script.toByteArray(Charsets.UTF_8).size

internal fun canSaveWatchEditorScript(script: String): Boolean =
    watchEditorScriptByteCount(script) <= WatchEditorSession.MAX_WATCH_SCRIPT_BYTES

internal fun validateWatchEditorLua(script: String): String? {
    if (script.isBlank()) return null
    return runCatching {
        val globals = Globals()
        LuaC.install(globals)
        globals.load(script, "global_script")
        null
    }.getOrElse { error ->
        error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
    }
}

@Composable
private fun EditorGlobalScriptOverlay(
    initialScript: String,
    errorMessage: String?,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var script by remember(initialScript) {
        mutableStateOf(
            TextFieldValue(
                text = initialScript,
                selection = TextRange(initialScript.length),
            ),
        )
    }
    var syntaxError by remember(initialScript) { mutableStateOf<String?>(null) }
    LaunchedEffect(script.text) {
        delay(300)
        syntaxError = withContext(Dispatchers.Default) { validateWatchEditorLua(script.text) }
    }
    val byteCount = remember(script.text) { watchEditorScriptByteCount(script.text) }
    val withinLimit = byteCount <= WatchEditorSession.MAX_WATCH_SCRIPT_BYTES
    val completionQuery = remember(script.text, script.selection) {
        val beforeCursor = script.text.take(script.selection.start.coerceIn(0, script.text.length))
        beforeCursor.substringAfterLast('{', "").takeIf { '{' in beforeCursor && '}' !in it }
    }
    val completionEntries = remember(completionQuery) {
        WATCH_EDITOR_EXPRESSION_GROUPS
            .flatMap { it.entries }
            .filter { completionQuery == null || it.tag.startsWith("{$completionQuery") }
            .take(12)
    }
    val scriptScrollState = rememberScrollState()
    BackHandler(onBack = onCancel)

    Surface(
        modifier = Modifier.fillMaxSize().zIndex(20f),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 键盘弹起时整页上移，否则输入框被软键盘盖住、看不到自己在打什么
                //（老师报的「不会被键盘把输入框顶上去」）。
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.watch_editor_cancel))
                }
                Text(
                    text = stringResource(R.string.watch_editor_global_script),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = withinLimit && syntaxError == null,
                    onClick = { onSave(script.text) },
                ) {
                    Text(stringResource(R.string.watch_editor_save))
                }
            }
            OutlinedTextField(
                value = script,
                onValueChange = { script = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(stringResource(R.string.watch_editor_script_placeholder)) },
                shape = LauncherTheme.style.rowShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 脚本经常几十行，输入框自己不滚。外面套一层带阻尼的滚动，
                    // 和全局的滚动手感一致（老师报的「滑动没有阻尼」）。
                    .flueOverscroll(scriptScrollState)
                    .verticalScroll(scriptScrollState),
            )
            if (completionQuery != null && completionEntries.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, LauncherTheme.style.rowShape)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    completionEntries.forEach { entry ->
                        TextButton(
                            onClick = {
                                val (updated, cursor) = insertWatchEditorExpression(
                                    text = script.text,
                                    selectionStart = script.selection.start,
                                    selectionEnd = script.selection.end,
                                    tag = entry.tag,
                                )
                                script = TextFieldValue(updated, TextRange(cursor))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(entry.tag, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Text(
                text = stringResource(
                    R.string.watch_editor_script_bytes_fmt,
                    byteCount,
                    WatchEditorSession.MAX_WATCH_SCRIPT_BYTES,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (withinLimit) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!withinLimit) {
                Text(
                    text = stringResource(R.string.watch_editor_script_too_large),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!syntaxError.isNullOrBlank()) {
                Text(
                    text = syntaxError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 表盘内部资源管理器。
 *
 * ## 为什么需要它
 *
 * 包里到底有什么，作者以前完全看不到：换过三版视频就躺着三个 mp4、删过的序列帧、
 * 早年导入的字体，全都只体现为「当前表盘大小」那一行数字。等到上传被 20MB 上限拦下来
 * 才发现，而那时也不知道该删哪个（老师要的「表盘内部资源管理器」）。
 *
 * ## 只允许删「没人引用」的
 *
 * 被图层引用的素材不给删按钮 —— 资源管理器不该成为把表盘删坏的快捷通道。字体、脚本、
 * 着色器、预览图一律算已引用：它们不是按 `path` 引用的，静态分析看不出来
 * （见 `WatchEditorSession.resourceInventory`）。删除走的是同一套 stash，可以撤销。
 */
@Composable
private fun EditorResourceManagerOverlay(
    entries: List<WatchEditorResourceEntry>,
    busy: Boolean,
    /**
     * 草稿目录。缩略图要按绝对路径自己解码 —— 这些素材是应用私有存储里的临时拷贝，
     * 没有 content URI、也不在媒体库里，任何现成加载器都拿不到（见 EditorResourceThumbnail）。
     */
    draftDirectory: File,
    onImportAudio: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val style = LauncherTheme.style
    BackHandler(onBack = onDismiss)
    val orphanBytes = remember(entries) { entries.filterNot { it.referenced }.sumOf { it.bytes } }
    val context = LocalContext.current
    val playAudio = rememberEditorAudioPreview {
        Toast.makeText(context, R.string.watch_editor_audio_error, Toast.LENGTH_SHORT).show()
    }

    Surface(
        modifier = Modifier.fillMaxSize().zIndex(20f),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = stringResource(R.string.watch_editor_back),
                        tint = style.titleColor,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.watch_editor_manage_resources),
                        color = style.titleColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(
                            R.string.watch_editor_resources_summary_fmt,
                            entries.size,
                            formatEditorFileSize(entries.sumOf { it.bytes }),
                            formatEditorFileSize(orphanBytes),
                        ),
                        color = style.secondaryTextColor,
                        fontSize = 10.sp,
                    )
                }
            }
            // StoreRoundList 那一套（圆屏弦宽 + 回弹 + 首尾能拉到圆心）在这里同样适用。
            StoreRoundList(modifier = Modifier.weight(1f)) {
                item("import_audio") {
                    OutlinedButton(onClick = onImportAudio, enabled = !busy) { Text(stringResource(R.string.watch_editor_audio_import)) }
                }
                if (entries.isEmpty()) {
                    item("empty") {
                        Text(
                            stringResource(R.string.watch_editor_resources_empty),
                            color = style.secondaryTextColor,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                items(entries, key = { it.relativePath }, contentType = { "resource_row" }) { entry ->
                    FlueCard(modifier = Modifier.fillMaxWidth(), border = null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EditorResourceThumbnail(
                                file = File(draftDirectory, entry.relativePath),
                                kind = entry.kind,
                                fileBytes = entry.bytes,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.fileName,
                                    color = style.titleColor,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        entry.directory.takeIf(String::isNotEmpty)?.let {
                                            append(it).append(" · ")
                                        }
                                        append(formatEditorFileSize(entry.bytes))
                                    },
                                    color = style.secondaryTextColor,
                                    fontSize = 10.sp,
                                )
                            }
                            if (entry.kind == WatchEditorResourceKind.AUDIO) {
                                IconButton(onClick = { playAudio(File(draftDirectory, entry.relativePath)) }, enabled = !busy) {
                                    Icon(FlueMiuixIcons.Play, contentDescription = stringResource(R.string.watch_editor_audio_play))
                                }
                            }
                            if (entry.referenced) {
                                Text(
                                    stringResource(R.string.watch_editor_resource_in_use),
                                    color = style.secondaryTextColor,
                                    fontSize = 10.sp,
                                )
                            } else {
                                IconButton(
                                    onClick = { onDelete(entry.relativePath) },
                                    enabled = !busy,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        MiuixIcons.Delete,
                                        contentDescription = stringResource(R.string.action_remove),
                                        tint = WatchColors.ActiveRed,
                                        modifier = Modifier.size(17.dp),
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

/** 编辑器三档布局：画布全屏 / 分屏 / 面板全屏。 */
private enum class WatchEditorLayout {
    CANVAS_FULL,
    SPLIT,
    PANEL_FULL,
}

internal enum class RoundEditorContentMode {
    NONE,
    STATIC_SAFE_AREA,
    FISHEYE,
}

internal fun roundEditorContentMode(
    isRound: Boolean,
    panelFullscreen: Boolean,
): RoundEditorContentMode = when {
    !isRound -> RoundEditorContentMode.NONE
    panelFullscreen -> RoundEditorContentMode.FISHEYE
    else -> RoundEditorContentMode.STATIC_SAFE_AREA
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
                FlueLoadingIndicator(size = 18.dp)
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
    selectedLayerKeys: Set<WatchEditorLayerKey>,
    multiSelectMode: Boolean,
    ambientMode: Boolean,
    previewRoundOverride: Boolean?,
    batchTransformPreview: Map<WatchEditorLayerKey, WatchEditorTransform>,
    onBeginTranslation: (WatchEditorLayerKey) -> Boolean,
    onUpdateTranslation: (Float, Float) -> Unit,
    onEndTranslation: (Boolean) -> Unit,
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
    previewPlaying: Boolean,
    tapHapticsEnabled: Boolean,
    /**
     * 底部模糊背景层开关，跟随设置。
     *
     * 与上面强制 `setGravityParallaxEnabled(true)` 的理由相反：景深是「作者要调
     * gyro 图层就得看得见」的编辑能力，而这一层是纯观感，预览就该和用户桌面
     * 上看到的一致 —— 作者自己叠了背景素材时，两层打不打架必须在这里看得出来。
     */
    gravityBackdropBlurEnabled: Boolean,
    modifier: Modifier = Modifier,
    onFaceViewChanged: (WatchmakerWatchFaceView?) -> Unit = {},
    /**
     * 声明画布的设计空间尺寸。命中测试 / 选中框必须和渲染器同源，
     * 否则非方画布上点选和拖动整体错位。
     */
    designExtentWidth: Float = WatchmakerGeometry.DESIGN_SIZE,
    designExtentHeight: Float = WatchmakerGeometry.DESIGN_SIZE,
    /**
     * 表盘自己声明的形状是不是圆。
     *
     * 只用来裁视频层：Canvas 段自己会按 `definition.shape` 裁内切圆
     * （`drawPreparedSource` 里那一刀），但视频是独立 SurfaceTexture，吃不到 Canvas 的
     * clip —— 圆形表盘上视频于是方方正正地铺出圆外（老师报的「开启圆屏之后视频不会被
     * 遮罩裁切到圆形」）。判据与那一刀同源，用**表盘形状**而不是设备屏形：编辑器画的是
     * 声明画布，方屏机器上编圆表盘也要看到圆。
     */
    faceIsRound: Boolean = false,
) {
    val context = LocalContext.current
    var loadError by remember(descriptor.stableKey, refreshToken) { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var faceView by remember { mutableStateOf<WatchmakerWatchFaceView?>(null) }
    var faceStage by remember { mutableStateOf<WatchmakerWatchFaceStageView?>(null) }
    var geometryRevision by remember { mutableIntStateOf(0) }
    var geometrySourceKey by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var geometryAmbientMode by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(faceView, previewPlaying, transformPreview, batchTransformPreview) {
        while (faceView != null && previewPlaying && transformPreview == null && batchTransformPreview.isEmpty()) {
            delay(250L)
            geometryRevision++
        }
    }
    // 图层自身尺寸、父子关系和基础变换在一次手势里都不变。循环背景的 path
    // 可能非常长，不能因为拖动坐标每帧变化就重新解析所有图层几何。
    val baseGeometries = remember(layers, imageSizeLookup, ambientMode, geometryRevision) {
        val resolved = layers.mapNotNull { layer ->
            faceView?.resolveEditorLayerTransform(layer.definitionIndex)?.let { t ->
                layer.key to WatchEditorTransform(t.x, t.y, t.scaleX, t.scaleY, t.rotationDegrees)
            }
        }.toMap()
        buildWatchEditorLayerGeometries(layers.map { layer ->
            val attrs = watchmakerDisplayAttributes(layer.attributes, layer.ambientAttributes, ambientMode)
            val hidden = !watchmakerDisplayVisible(attrs["display"], ambientMode) { true }
            layer.copy(attributes = if (hidden) attrs + ("display" to "false") else attrs)
        }, imageSizeLookup, resolved)
    }
    val geometries = remember(baseGeometries, transformPreview, batchTransformPreview) {
        val preview = transformPreview
        baseGeometries.map { geometry ->
            val transform = batchTransformPreview[geometry.key] ?: preview?.takeIf { it.key == geometry.key }?.transform
            if (transform != null) geometry.copy(transform = transform) else geometry
        }
    }
    val latestGeometries = rememberUpdatedState(geometries)
    val latestLayers = rememberUpdatedState(layers)
    val latestSelectedKey = rememberUpdatedState(selectedLayer?.key)
    val latestSelectedKeys = rememberUpdatedState(selectedLayerKeys)
    val latestMulti = rememberUpdatedState(multiSelectMode)
    val latestAmbient = rememberUpdatedState(ambientMode)
    val latestBeginTranslation = rememberUpdatedState(onBeginTranslation)
    val latestUpdateTranslation = rememberUpdatedState(onUpdateTranslation)
    val latestEndTranslation = rememberUpdatedState(onEndTranslation)
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
        // definitionIndex 而不是 order：渲染器按含嵌套的扁平表找层，见
        // WatchEditorLayer.definitionIndex 的注释。
        ?.definitionIndex
    val dataProvider = rememberWatchmakerWatchDataProvider(
        active = true,
        permissionRevision = bodySensorsPermissionGranted,
        ambientMode = ambientMode,
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
        // 实时变换要广播给所有 Canvas 段（见 WatchmakerWatchFaceStageView），
        // 所以另外记住 stage 本身，不能只拿首段。
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WatchmakerWatchFaceStageView(context).also { stage ->
                    faceView = stage.renderer
                    faceStage = stage
                    onFaceViewChanged(stage.renderer)
                }
            },
            update = { stage ->
                val view = stage.renderer
                // Device previews use the same centered crop as Canvas Adjust's
                // "Fill" mode instead of silently falling back to FIT.
                view.setCanvasSettings(WatchCanvasSettings.GLOBAL_DEFAULT)
                stage.setAmbientMode(ambientMode)
                // Evaluation must follow the native mode switch, including while playback is paused.
                if (geometryAmbientMode != ambientMode) {
                    geometryAmbientMode = ambientMode
                    geometryRevision++
                }
                stage.setEditorPreviewRoundOverride(previewRoundOverride)
                view.setWatchDataProvider(dataProvider)
                // Editor preview is intentionally independent from the launcher
                // setting: authors must be able to see and tune gyro layers even
                // when the everyday gravity-parallax switch is disabled.
                view.setGravityParallaxEnabled(true)
                view.setTapHapticsEnabled(tapHapticsEnabled)
                view.setGravityBackdropBlurEnabled(gravityBackdropBlurEnabled)
                if (previewPlaying) {
                    view.clearEditorAnimatedImageLayerLimit()
                } else {
                    view.setEditorAnimatedImageLayer(animatedPreviewLayerIndex)
                }
                // 统一的预览播放开关：序列帧、视频、重力景深三者同进同退。
                view.setEditorPreviewPlaying(previewPlaying)
                if (!previewPlaying) view.resetEditorPreviewViewpoint()
                // 拖动任意元素时都暂停循环素材；否则它的 30fps 解码/刷新仍会和
                // 当前元素的跟手重绘竞争，即使拖动目标本身不是循环背景。
                view.setEditorAnimationInteractionFrozen(transformPreview != null || batchTransformPreview.isNotEmpty())
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
                    onSourceReady = { token ->
                        // 素材就绪才知道有几个可播 video、路径各是什么。
                        stage.syncVideoSegments()
                        val sourceKey = descriptor.stableKey to token
                        if (geometrySourceKey != sourceKey) {
                            geometrySourceKey = sourceKey
                            geometryRevision++
                        }
                        onRefreshReady(token)
                    },
                    // 编辑器预览始终按表盘 XML 播放，和启动器全局开关独立。
                    textEntryAnimationEnabled = true,
                    textExitAnimationEnabled = true,
                )
                // 分段之后再下发圆形裁剪：视频面是在 syncVideoSegments 里建出来的，
                // 而 setRoundClip 只在有视频面时才真的裁（见 dispatchDraw）。
                stage.setRoundClip(faceIsRound)
            },
            onRelease = { stage ->
                if (faceView === stage.renderer) faceView = null
                if (faceStage === stage) faceStage = null
                onFaceViewChanged(null)
                stage.dispose()
            },
        )
        // 拖动期间把手势变换逐帧喂给渲染器：元素和选中框一起动，
        // 不再是「只有框在动、松手后像素才跳过去」。
        LaunchedEffect(transformPreview, batchTransformPreview, layers, faceStage) {
            val stage = faceStage ?: return@LaunchedEffect
            if (batchTransformPreview.isNotEmpty()) {
                stage.setEditorAnimationInteractionFrozen(true)
                stage.setLiveLayerTransforms(layers.mapNotNull { layer ->
                    batchTransformPreview[layer.key]?.let { t ->
                        WatchmakerLiveLayerTransform(layer.definitionIndex, layer.id, t.x, t.y, t.rotation, t.scaleX, t.scaleY)
                    }
                })
                return@LaunchedEffect
            }
            val preview = transformPreview
            if (preview == null) {
                stage.setEditorAnimationInteractionFrozen(false)
                stage.clearLiveLayerTransform()
                return@LaunchedEffect
            }
            val target = layers.firstOrNull { it.key == preview.key }
            // definitionIndex 是渲染器那张**含嵌套**扁平表里的下标；以前传的是
            // order（只数顶层图层），带分组的表盘上两者不等，于是按下标匹配落到别的层，
            // 像素不跟手。图层有 id 时按 id 匹配本来就对，所以以前只有「没有 id 的层」
            // 表现出问题 —— 文字层正是通常不写 id 的那一类。
            val index = target?.definitionIndex
                ?: layers.indexOfFirst { it.key == preview.key }
            if (index < 0) {
                stage.setEditorAnimationInteractionFrozen(false)
                stage.clearLiveLayerTransform()
                return@LaunchedEffect
            }
            stage.setEditorAnimationInteractionFrozen(true)
            val transform = preview.transform
            stage.setLiveLayerTransform(
                layerIndex = index,
                layerId = target?.id,
                x = transform.x,
                y = transform.y,
                rotationDegrees = transform.rotation,
                scaleX = transform.scaleX,
                scaleY = transform.scaleY,
            )
        }
        // Direct-manipulation surface: hit the layer under the finger, drag it,
        // grab a selection-frame handle, or tap to select / deselect. During
        // playback the native renderer must receive the gesture so its
        // WatchMaker tap target dispatch behaves like WFTools action mode.
        if (!previewPlaying) Box(
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
                            fill = true,
                        )
                        val currentGeometries = latestGeometries.value.map { geometry ->
                            val layer = latestLayers.value.firstOrNull { it.key == geometry.key }
                            val evaluated = layer?.let { faceStage?.resolveEditorLayerTransform(it.definitionIndex) }
                            if (evaluated == null) geometry else geometry.copy(transform = WatchEditorTransform(
                                evaluated.x, evaluated.y, evaluated.scaleX, evaluated.scaleY, evaluated.rotationDegrees,
                            ))
                        }
                        val matrices = buildWatchEditorWorldMatrices(currentGeometries)
                        val downDesignX = mapping.designX(down.position.x)
                        val downDesignY = mapping.designY(down.position.y)
                        val handleRadiusDesign =
                            mapping.designLength(EDITOR_HANDLE_TOUCH_DP.dp.toPx())
                        val rotationOffsetDesign =
                            mapping.designLength(EDITOR_ROTATION_HANDLE_OFFSET_DP.dp.toPx())
                        val hitSlopDesign =
                            mapping.designLength(EDITOR_LAYER_HIT_SLOP_DP.dp.toPx())

                        if (latestMulti.value || latestAmbient.value) {
                            val hit = hitTestWatchEditorLayers(currentGeometries, matrices, downDesignX, downDesignY, hitSlopDesign)
                            val key = hit?.let { currentGeometries[it].key }
                            var moved = false
                            var began = false
                            var completed = false
                            var dx = 0f
                            var dy = 0f
                            try {
                                down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (!latestEnabled.value || event.changes.any { it.isConsumed }) break
                                    val pan = event.calculatePan()
                                    dx += mapping.designLength(pan.x)
                                    dy += mapping.designLength(pan.y)
                                    moved = moved || event.changes.any {
                                        hypot(it.position.x - down.position.x, it.position.y - down.position.y) > viewConfiguration.touchSlop
                                    }
                                    if (moved && !began && key != null && (!latestMulti.value || key in latestSelectedKeys.value)) {
                                        if (!latestMulti.value && key != latestSelectedKey.value) latestSelect.value(key)
                                        began = latestBeginTranslation.value(key)
                                        if (!began) break
                                    }
                                    if (began) latestUpdateTranslation.value(dx, dy)
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) { completed = true; break }
                                }
                            } finally {
                                if (began) latestEndTranslation.value(!completed)
                                if (completed && !moved && key != null) {
                                    latestSelect.value(key)
                                    latestTap.value(key)
                                }
                            }
                            return@awaitEachGesture
                        }

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
            !previewPlaying &&
            (enabled || transformPreview != null || batchTransformPreview.isNotEmpty()) &&
            selectedGeometryIndex >= 0 &&
            canvasSize.width > 0
        ) {
            val matrices = remember(geometries) { buildWatchEditorWorldMatrices(geometries) }
            val selectedBoxes = geometries.mapIndexedNotNull { index, geometry ->
                if (geometry.key in selectedLayerKeys) watchEditorSelectionBox(geometry, matrices[index]) else null
            }
            val combinedBox = if (multiSelectMode && selectedBoxes.isNotEmpty()) {
                val points = selectedBoxes.flatMap { listOf(it.topLeft, it.topRight, it.bottomLeft, it.bottomRight) }
                val left = points.minOf { it.x }; val right = points.maxOf { it.x }
                val top = points.minOf { it.y }; val bottom = points.maxOf { it.y }
                WatchEditorSelectionBox(WatchEditorPoint((left + right) / 2, (top + bottom) / 2),
                    WatchEditorPoint(left, top), WatchEditorPoint(right, top), WatchEditorPoint(left, bottom), WatchEditorPoint(right, bottom))
            } else null
            EditorSelectionOverlay(
                box = combinedBox ?: watchEditorSelectionBox(
                    geometries[selectedGeometryIndex],
                    matrices[selectedGeometryIndex],
                ),
                modifier = Modifier.fillMaxSize(),
                designExtentWidth = designExtentWidth,
                designExtentHeight = designExtentHeight,
                fill = true,
                showHandles = !multiSelectMode && !ambientMode,
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

@Composable
private fun EditorSelectionScaleSlider(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(style.panelShape)
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                R.string.watch_editor_selection_scale,
                (value * 100f).roundToInt(),
            ),
            color = style.titleColor,
            style = MaterialTheme.typography.labelMedium,
        )
        FlueSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            valueRange = 0.25f..3f,
            steps = 54,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

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
    fill: Boolean = false,
    showHandles: Boolean = true,
) {
    Canvas(modifier = modifier) {
        // 必须和手势用同一个 mapping：一边等比中心反投、一边单轴按宽换算，
        // 选中框会和实际可拖区域错开。
        val mapping = WatchEditorCanvasMapping(
            viewWidthPx = size.width.coerceAtLeast(1f),
            viewHeightPx = size.height.coerceAtLeast(1f),
            designWidth = designExtentWidth,
            designHeight = designExtentHeight,
            fill = fill,
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

        if (!showHandles) return@Canvas

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
                bitmap == null -> FlueLoadingIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    usage = FlueLoadingIndicatorUsage.WATCH_FACE_LOADING,
                )
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

/**
 * 视频导入的两条终点，决定进度对话框的说明文案。
 *
 * 帧序列用于 image_gif 等图层，视频图层则固定重编码为 H.264 MP4。
 */
private enum class EditorVideoConversionKind { FRAMES, REENCODE }

@Composable
private fun EditorVideoConversionProgressDialog(
    progress: Float,
    kind: EditorVideoConversionKind,
    onCancel: () -> Unit,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    FlueAlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                stringResource(
                    when (kind) {
                        else -> R.string.watch_editor_converting_video
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlueDeterminateProgressIndicator(
                    progress = safeProgress,
                    modifier = Modifier.fillMaxWidth(),
                    usage = FlueLoadingIndicatorUsage.WATCH_FACE_LOADING,
                )
                Text("${(safeProgress * 100f).roundToInt()}%")
                Text(
                    stringResource(
                        when (kind) {
                            EditorVideoConversionKind.FRAMES ->
                                R.string.watch_editor_video_conversion_message
                            EditorVideoConversionKind.REENCODE ->
                                R.string.watch_editor_video_reencode_message
                        },
                    ),
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
    onMoveStep: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    canMoveUp: Boolean = layer.order > 0,
    canMoveDown: Boolean = layer.order < layerCount - 1,
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
                onClick = { onMoveStep(-1) },
                enabled = enabled && canMoveUp,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    FlueMiuixIcons.ArrowUp,
                    contentDescription = stringResource(R.string.watch_editor_move_up),
                )
            }
            IconButton(
                onClick = { onMoveStep(1) },
                enabled = enabled && canMoveDown,
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
        shape = LauncherTheme.style.compactShape,
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
    formatVersion: WatchFormatVersion,
    audioOptions: List<String>,
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
    val editableAttributes = remember(layer.type, formatVersion) {
        editableAttributesFor(layer.type).filterNot { formatVersion == WatchFormatVersion.V2 && it == "color_dim" }
    }
    // Drafts survive live applies (draftResetKey stays put) but reset whenever
    // the attributes change from the outside: undo, canvas drags, imports.
    var drafts by remember(layer.key, draftResetKey) {
        mutableStateOf(
            editableAttributes.associateWith { attribute ->
                // `path` 显示成「相对 images/ 的路径」。XML 里可能写着 images/foo.png
                // （导入的外来表盘就这么写），照原样显示等于让作者接着把错误传下去 ——
                // 规范里这个字段是约定目录**内部**的名字，多一层就变成 images/images/。
                val raw = layer.attributes[attribute].orEmpty()
                val text = if (attribute == "path") {
                    normalizeWatchEditorAssetPath(layer.type, raw)
                } else {
                    raw
                }
                TextFieldValue(text, TextRange(text.length))
            },
        )
    }
    var expandedAttributePicker by remember(layer.key) { mutableStateOf<String?>(null) }
    // 六个颜色通道共用一个取色器，记住的是「正在给哪个属性取色」而不是布尔开关。
    var colorPickerAttribute by remember(layer.key) { mutableStateOf<String?>(null) }

    fun liveValue(name: String, value: String): String? = when {
        value.isEmpty() && name != "text" -> null
        // 写进 XML 前再规范化一次。刻意**不**回写 draft：正在打 `images/` 的时候把前缀
        // 实时吃掉会让光标乱跳，所以显示留用户的原文，落盘的是剥好的值。
        name == "path" -> normalizeWatchEditorAssetPath(layer.type, value)
        else -> value
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.watch_editor_layer_properties_fmt, localizedLayerType),
            fontWeight = FontWeight.SemiBold,
        )
        if (formatVersion == WatchFormatVersion.V2) EditorAmbientProperties(layer, draftResetKey, enabled, onLiveChange)
        // 分组标题的去重游标。形状族一层能有二十多个属性，平铺一长条在 230dp
        // 圆屏上根本找不到东西，所以按「变换 / 几何 / 颜色 / 描边 / 阴影 / 着色器」分段。
        var lastSectionHeader: Int? = null
        editableAttributes.forEach { attribute ->
            attributeSectionHeaderRes(attribute)
                ?.takeIf { it != lastSectionHeader }
                ?.let { headerRes ->
                    lastSectionHeader = headerRes
                    Text(
                        stringResource(headerRes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            val enumOptions = attributeEnumOptions(layer.type, attribute)
            val hasAnimationPicker = attribute == "anim_in" || attribute == "anim_out"
            val hasColorPicker = attribute in COLOR_ATTRIBUTES
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
                    label = { Text(stringResource(attributeLabelRes(layer.type, attribute))) },
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
                                    onClick = { colorPickerAttribute = attribute },
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
                        enumOptions != null || hasExpressionPicker -> {
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
                    if (enumOptions != null) {
                        InlineOptionsPanel(
                            options = enumOptions,
                            selectedValue = drafts[attribute]?.text.orEmpty(),
                            enabled = enabled,
                            onSelect = { value ->
                                val next = TextFieldValue(value, TextRange(value.length))
                                // 两处联动写入：anim_in 决定 restart_on_load；shader 要把
                                // u_1..u_5 铺上缺省值，否则选了 GradientLinear 画面毫无变化，
                                // 用户会以为着色器没生效。
                                val companions: Map<String, String?> = when (attribute) {
                                    "anim_in" -> mapOf(
                                        "restart_on_load" to if (value.isBlank()) "N" else "Y",
                                    )
                                    "shader" -> watchShaderCompanionAttributes(value)
                                    else -> emptyMap()
                                }
                                drafts = drafts + (attribute to next) + companions.mapValues { (_, raw) ->
                                    val text = raw.orEmpty()
                                    TextFieldValue(text, TextRange(text.length))
                                }
                                onLiveChange(
                                    mapOf(attribute to value.ifBlank { null }) + companions,
                                )
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
        // video 图层只给「导入视频」一个入口：它收的是视频本身，不该出现
        // 「导入图片转序列帧」那一路（那会把图层改写成 image_gif）。
        if (layer.type.equals("video", ignoreCase = true)) {
            EditorLongPressImportButton(
                label = stringResource(R.string.watch_editor_import_video),
                enabled = enabled,
                onClick = onImportVideo,
                onLongClick = onImportVideoLong,
            )
        }
        // 颜色输入框右侧的色块直接打开取色器，并写回同一个 draft + live 通道。
        colorPickerAttribute?.let { target ->
            EditorColorPickerDialog(
                initial = parseEditorHexColor(drafts[target]?.text.orEmpty()) ?: Color.White,
                onDismiss = { colorPickerAttribute = null },
                onConfirm = { color ->
                    colorPickerAttribute = null
                    val hex = editorColorToHex(color)
                    val newValue = TextFieldValue(hex, TextRange(hex.length))
                    drafts = drafts + (target to newValue)
                    onLiveChange(mapOf(target to liveValue(target, hex)))
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
            var showPresetPicker by remember { mutableStateOf(false) }
            var showAudioPicker by remember { mutableStateOf(false) }

            fun applyAction(raw: String) {
                tapActionText = TextFieldValue(raw, TextRange(raw.length))
                onLiveChange(mapOf("tap_action" to raw.ifBlank { null }))
            }

            if (showAppPicker) {
                // 与「设置 → 表盘点击动作」共用同一个选择器（名单缓存 + 图标懒取都在里面）。
                // 以前这里是另一份自搓实现：没有图标、用的是 MaterialTheme 的颜色，
                // 在 miuix / Apple 档下和周围格格不入。
                WatchAppPickerDialog(
                    title = stringResource(R.string.watch_editor_app_picker),
                    subtitle = stringResource(R.string.watch_editor_tap_action_example),
                    selected = null,
                    onPick = { app ->
                        // WM_TAP_ACTIONS 的 `w_app:` 语法：标签`包名`Activity。
                        applyAction("w_app:${app.label}`${app.packageName}`${app.activityClass}")
                        showAppPicker = false
                    },
                    onDismiss = { showAppPicker = false },
                )
            }

            if (showPresetPicker) {
                // 名单与「设置 → 表盘点击动作」共用一份（见 WatchTapActionPresetDialog 注释）：
                // 编辑器写进图层的动作名必须正好是设置页能绑定应用的那个键，
                // 否则表盘写一个键、映射存另一个键，点下去静默无反应。
                WatchTapActionPresetDialog(
                    current = tapActionText.text,
                    onPick = { preset ->
                        applyAction(preset)
                        showPresetPicker = false
                    },
                    onDismiss = { showPresetPicker = false },
                )
            }

            if (showAudioPicker) FlueAlertDialog(
                onDismissRequest = { showAudioPicker = false },
                title = { Text(stringResource(R.string.watch_editor_audio_action)) },
                text = {
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        if (audioOptions.isEmpty()) Text(stringResource(R.string.watch_editor_audio_empty))
                        audioOptions.forEach { path ->
                            TextButton(onClick = {
                                applyAction(watchEditorAudioTapAction(path))
                                showAudioPicker = false
                            }) { Text(path.substringAfterLast('/')) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showAudioPicker = false }) { Text(stringResource(R.string.watch_editor_back)) } },
            )

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
                    OutlinedButton(onClick = { showAudioPicker = true }, enabled = enabled) {
                        Text(stringResource(R.string.watch_editor_audio_action))
                    }
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { showAppPicker = true },
                            enabled = enabled,
                        ) { Text(stringResource(R.string.watch_editor_more_watch_options)) }
                        // 这里原来是「日历」「天气」两个写死的按钮，只覆盖 widget_calendar /
                        // widget_weather 两个动作；上游命名动作一共 26 个（WM_TAP_ACTIONS.md §3），
                        // 其余的只能手打进输入框 —— 打错一个字母解析器就归到 Unsupported，
                        // 表盘上点下去只有一句「动作不可用」，编辑时根本看不出是拼错了。
                        // 换成完整预设名单，日历和天气也还在里面，没丢东西。
                        OutlinedButton(
                            onClick = { showPresetPicker = true },
                            enabled = enabled,
                        ) { Text(stringResource(R.string.watch_editor_tap_action_preset)) }
                        // 清除改成纯图标，并且**常驻**（原来是有值才出现）：三个文字按钮在圆屏
                        // 属性面板里排一行会被右边裁掉，而清除是「撤掉刚才那次误选」的唯一出口，
                        // 不能是被裁掉的那个；常驻则让这一行宽度不随取值跳动。
                        OutlinedButton(
                            onClick = { applyAction("") },
                            enabled = enabled && tapActionText.text.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                OfficialMiuixIcons.OfficialClear,
                                contentDescription = stringResource(R.string.watch_editor_clear),
                                modifier = Modifier.size(16.dp),
                            )
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

/**
 * 枚举属性的横向芯片行。原本只服务文字入场动画，现在形状 / 圆角位 / 着色器 /
 * 端头 / 锚点 / 显示模式共用同一套：横向滚动比纵向列表省竖向空间，`corner_type`
 * 那 17 个取值铺成一行也还能扫。
 */
@Composable
private fun InlineOptionsPanel(
    options: List<EditorEnumOption>,
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
            options.forEach { option ->
                val label = option.literalLabel
                    ?: option.labelRes?.let { stringResource(it) }
                    ?: option.value
                if (selectedValue.equals(option.value, ignoreCase = true)) {
                    Button(onClick = { onSelect(option.value) }, enabled = enabled) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(onClick = { onSelect(option.value) }, enabled = enabled) {
                        Text(label)
                    }
                }
            }
        }
    }
}

/**
 * 选中某个 `shader` 时要一并写下的 `u_*`。
 *
 * 缺省值取渲染器那份唯一的 [watchShapeShaderDefaults]（它照抄上游 `.fsh` 的
 * uniform 初值），所以面板与渲染永远说的是同一套数。切成「无」时把五个 uniform
 * 一起清掉 —— 留着会跟着下一个着色器被重新解释成别的意思。
 */
internal fun watchShaderCompanionAttributes(rawShader: String): Map<String, String?> {
    val seeded = watchShapeShaderDefaults(watchShapeShaderKindOf(rawShader)).toMap()
    return (1..5).associate { index ->
        val name = "u_$index"
        name to seeded[name]
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
        shape = LauncherTheme.style.compactShape,
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
 * Preview-only viewport and display-mode controls. They live beside the watch-level shape because
 * all three affect what the author sees, while none of them should consume scarce toolbar width.
 */
@Composable
private fun EditorPreviewSettingsCard(
    previewViewport: WatchEditorViewport,
    deviceViewport: WatchEditorViewport,
    ambient: Boolean,
    enabled: Boolean,
    onSelectViewport: (WatchEditorViewport) -> Unit,
    onEditCustom: () -> Unit,
    onSelectAmbient: (Boolean) -> Unit,
) {
    val style = LauncherTheme.style
    val nativeViewport = remember { WatchEditorViewport(512, 512) }
    val viewportOptions = remember(deviceViewport) {
        listOf(
            nativeViewport,
            deviceViewport,
            WatchEditorViewport(480, 480),
            WatchEditorViewport(320, 386),
            WatchEditorViewport(368, 448),
            WatchEditorViewport(410, 502),
        ).distinct()
    }
    val usesPreset = previewViewport in viewportOptions
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = style.compactShape,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.watch_editor_preview_viewport),
                color = style.titleColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                previewViewport.label,
                style = MaterialTheme.typography.bodySmall,
                color = style.secondaryTextColor,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                viewportOptions.forEach { viewport ->
                    val label = if (viewport == deviceViewport && viewport != nativeViewport) {
                        stringResource(R.string.watch_editor_preview_current_device) + " · " + viewport.label
                    } else {
                        viewport.label
                    }
                    EditorPreviewChoiceButton(
                        label = label,
                        selected = previewViewport == viewport,
                        enabled = enabled,
                        onClick = { onSelectViewport(viewport) },
                    )
                }
                EditorPreviewChoiceButton(
                    label = if (usesPreset) {
                        stringResource(R.string.watch_editor_preview_custom)
                    } else {
                        stringResource(R.string.watch_editor_preview_custom) + " · " + previewViewport.label
                    },
                    selected = !usesPreset,
                    enabled = enabled,
                    onClick = onEditCustom,
                )
            }
            Text(
                stringResource(R.string.watch_editor_preview_only),
                style = MaterialTheme.typography.labelSmall,
                color = style.secondaryTextColor,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditorPreviewChoiceButton(
                    label = stringResource(R.string.watch_editor_preview_bright),
                    selected = !ambient,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectAmbient(false) },
                )
                EditorPreviewChoiceButton(
                    label = stringResource(R.string.watch_editor_preview_ambient),
                    selected = ambient,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectAmbient(true) },
                )
            }
        }
    }
}

@Composable
private fun EditorPreviewChoiceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        FlueButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(label, maxLines = 1)
        }
    } else {
        FlueOutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            contentColor = LauncherTheme.style.bodyColor,
        ) {
            Text(label, maxLines = 1)
        }
    }
}

/**
 * 表盘级背景色卡片。渲染时 `bg_color` 是所有图层之下的底色（见
 * WatchmakerWatchFaceView 的 drawPreparedSource），此前只有渲染侧在读、
 * 编辑器没有任何入口。清空则移除属性，回落到渲染侧的默认黑。
 */
@Composable
private fun EditorWatchBackgroundCard(
    rawBackground: String?,
    enabled: Boolean,
    onUpdateBackground: (String?) -> Unit,
) {
    val style = LauncherTheme.style
    val notSetLabel = stringResource(R.string.watch_editor_not_set)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = LauncherTheme.style.compactShape,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.watch_editor_watch_background), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.watch_editor_watch_background_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EditorColorPickerRow(
                // 属性缺失时按渲染侧的回落值显示，避免色块画成灰。
                currentHex = rawBackground.orEmpty().ifBlank { "000000" },
                enabled = enabled,
                onPick = { hex -> onUpdateBackground(hex) },
            )
            Text(
                stringResource(
                    R.string.watch_editor_watch_background_current_fmt,
                    rawBackground.orEmpty().ifBlank { notSetLabel },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = style.secondaryTextColor,
            )
            if (!rawBackground.isNullOrBlank()) {
                OutlinedButton(
                    onClick = { onUpdateBackground(null) },
                    enabled = enabled,
                ) { Text(stringResource(R.string.watch_editor_watch_background_clear)) }
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
        shape = LauncherTheme.style.compactShape,
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
    // 注册入口必须跟商店 / 我的上传共用服务端开关；编辑器不能用登录组件的
    // 默认 true，否则 `/config.registration_enabled=false` 时仍会露出注册。
    val featureFlagCache = remember(api) { WatchStoreFeatureFlagCache.of(api) }
    var featureFlags by remember { mutableStateOf(WatchStoreFeatureFlags()) }

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
    var description by remember { mutableStateOf(snapshot.rootAttributes["description"].orEmpty()) }
    var tagsText by remember { mutableStateOf(snapshot.rootAttributes["tags"].orEmpty()) }
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
    var uploadProtected by remember {
        mutableStateOf(
            snapshot.rootAttributes.entries.any { (name, value) ->
                name.equals("protection", ignoreCase = true) &&
                    value.trim().lowercase(Locale.ROOT) in setOf("y", "yes", "true", "1")
            },
        )
    }

    LaunchedEffect(featureFlagCache) {
        featureFlags = featureFlagCache.flags()
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
        val uploadTags = tagsText.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
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
                            protectedExport = uploadProtected,
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
                            tags = uploadTags,
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
                    FlueLoadingIndicator(size = 18.dp)
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
                    registrationEnabled = featureFlags.registrationEnabled,
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
                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it.take(500) },
                        label = { Text(stringResource(R.string.watch_store_tags)) },
                        singleLine = true,
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
                            FlueLoadingIndicator(size = 16.dp)
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = busy == null) {
                                uploadProtected = !uploadProtected
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = uploadProtected,
                            onCheckedChange = { uploadProtected = it },
                            enabled = busy == null,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.watch_store_upload_protected),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.watch_store_upload_protected_sub),
                                style = MaterialTheme.typography.labelSmall,
                                color = style.secondaryTextColor,
                            )
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
        if (showProgress) FlueLoadingIndicator()
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

/**
 * 新建图层的默认属性。
 *
 * ## 属性名必须用规范名，不能自己起
 *
 * 这张表原先给 `ring` 写 `radius` / `width`、给 `progress` 写 `radius` / `width` / `value`，
 * 而 WM 1.0 §7.3/§7.4 定的是 `radius_outer` / `radius_inner` / `angle_total` / `angle` 与
 * `width` / `height` / `pct_complete` —— **我们自己的渲染器读的就是规范名**
 * （见 `WatchmakerWatchFaceView` 对 `attrs["radius_outer"]` / `attrs["pct_complete"]` 的读取）。
 * 于是插进来的环和进度条连本地预览都取不到值，只能吃渲染器的兜底默认。
 *
 * 更要紧的是社区商店：上游 `watchParser.js` 的 `LAYER_REQUIRED` 会对每个缺失的必填
 * 属性记一条 validation error，`scan.success` 转 false 之后表盘落进待审并带上
 * `rejection_reason` —— 也就是老师转述的「上传不规范的被自动拦截」。一个环缺四条、
 * 一个进度条缺三条，随手插一个就必拦。
 *
 * ## 为什么 image / image_gif / video 不补 width / height
 *
 * `WatchEditorSession.applyContainedVideoSize` 在图层**已有** width 或 height 时直接
 * 早退（`overwriteSize = false` 那条分支），靠的就是「没有尺寸 = 还没定过 = 按素材
 * 比例自动适配」。这里给个默认尺寸，导入的图片和视频就会被钉死在默认值上，不再按
 * 原始比例铺开。这几类的 `path` 在选素材之前本来就是空的，那属于「图层还没做完」，
 * 该由上传前的本地预检拦下来，不该靠在这里编一个假尺寸去骗过商店的必填检查。
 *
 * ## 颜色为什么是 6 位小写、不带 #
 *
 * 规范里所有颜色属性都写成 `hex RRGGBB`（1.0 §5 的根属性表、§7 每个图层表；示例值
 * `000000` / `ffffff`），上游两个真实样本包也一律裸 6 位。带 `#` 的 8 位 ARGB 是
 * Android 的写法：Flue 自己的 `normalizeWatchColorLiteral` 会顺手 `removePrefix("#")`
 * 并接受 8 位，所以本地看不出问题；拿到官方 WatchMaker / CloX 就是解析失败 →
 * 图层用兜底色，作者以为 Flue 存坏了。编辑器取色器早就写的是裸 6 位
 * （`editorColorToHex`），只有这张默认表还留着旧写法，两边口径必须一致。
 */
internal fun defaultAttributes(
    type: String,
    formatVersion: WatchFormatVersion = WatchFormatVersion.V1,
): Map<String, String> = when (type) {
    // font 是 LAYER_REQUIRED 里 text 的必填项之一，空字符串同样算缺失。
    // 取 "Roboto-Regular"：这既是上游示例表盘（examplewf/all-datapoint.watch）用的名字，
    // 也正好是 Android 的系统默认字体族 —— 空白草稿里没有 fonts/ 目录，任何渲染器
    // 解析不到这个引用都会落回系统默认，落点仍是 Roboto Regular，观感不变。
    "text" -> mapOf(
        "text" to "Flue",
        "text_size" to "48",
        "font" to "Roboto-Regular",
        "color" to WATCH_DEFAULT_COLOR,
        "x" to "0",
        "y" to "0",
    )
    // shape 的合法值在 1.0 §7.11 是**首字母大写**的枚举（Square / Circle / Triangle /
    // Star / Pentagon / Hexagon / Heart），官方渲染器拿这个名字去拼内置遮罩
    // `shape_<小写>.png`。这里以前写小写 "circle"：Flue 自己 lowercase 之后照样画得
    // 出来，别的渲染器按原样查表就查不到，图层直接不显示。
    // 2.0 还要额外写出那张遮罩的 path（§11），值就是 `shape_<小写名>.png`。
    "shape" -> buildMap {
        put("shape", "Circle")
        if (formatVersion == WatchFormatVersion.V2) put("path", "shape_circle.png")
        put("width", "120")
        put("height", "120")
        put("color", "4a90e2")
        put("x", "0")
        put("y", "0")
    }
    // `rounded` 的四个必填项（上游 watchParser.js 的 LAYER_REQUIRED：
    // width / height / radius / corner_type）一次写齐 —— 少一个上传就会被记账。
    // 缺省宽高按 clox 的 applyBox（100×60），radius 给 12 是为了「插进来就看得出
    // 是圆角矩形」；corner_type=1 是规范缺省（四角全圆）。
    "rounded" -> mapOf(
        "width" to "100",
        "height" to "60",
        "radius" to "12",
        "corner_type" to "1",
        "color" to WATCH_DEFAULT_COLOR,
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
    // 120 外径 / 108 内径 = 12 单位厚，与旧默认想表达的粗细一致；angle 给满 360
    // 是为了插进来就看得见 —— angle 是**已填充角度**，给 0 会画出一个空环，
    // 用户会以为没插上。
    "ring" -> mapOf(
        "radius_outer" to "120",
        "radius_inner" to "108",
        "angle_total" to "360",
        "angle" to "360",
        "is_clockwise" to "Y",
        "color" to WATCH_DEFAULT_COLOR,
        "x" to "0",
        "y" to "0",
    )
    // 规范 §7.4。写齐 radius / corner_type / margin / color3 / color4 五项，插进来
    // 就是一条「药丸形、有底槽」的进度条 —— 也就是所有人心里「进度条」的样子。
    //
    // 关键在 margin：**只有 margin > 0 才画 color4 边框与 color3 底槽**。以前默认不写
    // margin，插出来是一条没有底槽的裸色块，作者不看规范根本猜不到底槽开关在哪。
    // radius 显式写 12（= height/2，正好是药丸形）而不是留空靠规范缺省 50：留空的话
    // 面板里那一格是空的，而实际生效值是 50 —— 用户改不了自己看不见的数。
    "progress" -> mapOf(
        "width" to "240",
        "height" to "24",
        "pct_complete" to "50",
        "radius" to "12",
        "corner_type" to "1",
        "margin" to "2",
        "color" to WATCH_DEFAULT_COLOR,
        "color3" to "3a3a3c",
        "color4" to "1c1c1e",
        "x" to "0",
        "y" to "0",
    )
    // 1.0 §7.14。display="b" 是规范的建议默认：视频解码很耗电，
    // 息屏/dim 模式不该继续播。
    // 不写 path：规范里「没选素材」的表示是**没有这个属性**，不是空串。
    // 空串会被上游 watchParser.js 判成 missing required（`undefined || === ''`
    // 两种都算），既没换来任何好处，还让 `path=""` 出现在导出的 XML 里。
    "video" -> mapOf(
        "width" to "512",
        "height" to "512",
        "display" to "b",
        "x" to "0",
        "y" to "0",
    )
    else -> emptyMap()
} + mapOf(
    "anim_scale_x" to "100",
    "anim_scale_y" to "100",
)

/** 规范口径的白色：`hex RRGGBB`，小写、不带 `#`。见 [defaultAttributes] 的说明。 */
internal const val WATCH_DEFAULT_COLOR: String = "ffffff"

/**
 * 属性的显示名。
 *
 * 按 [layerType] 分流是必需的，不是讲究：同一个属性名在不同图层上是**不同的东西** ——
 * `ring` 的 `radius` 是端帽倒角（外半径叫 `radius_outer`），`rounded` / `progress` 的
 * `radius` 才是圆角半径；`ring` 的 `angle` 是已填充角度，`progress` 的 `angle` 是填充
 * 轴向。以前只按名字查表，写出来的标签有一半是错的。
 */
@StringRes
internal fun attributeLabelRes(layerType: String, name: String): Int = when (name) {
    "radius" -> if (layerType.equals("ring", ignoreCase = true)) {
        R.string.watch_editor_attribute_cap_radius
    } else {
        R.string.watch_editor_attribute_radius
    }
    "angle" -> when {
        layerType.equals("ring", ignoreCase = true) ->
            R.string.watch_editor_attribute_ring_angle
        layerType.equals("progress", ignoreCase = true) ->
            R.string.watch_editor_attribute_progress_angle
        else -> R.string.watch_editor_attribute_angle
    }
    "shape" -> R.string.watch_editor_attribute_shape
    "width" -> R.string.watch_editor_attribute_width
    "height" -> R.string.watch_editor_attribute_height
    "corner_type" -> R.string.watch_editor_attribute_corner_type
    "margin" -> R.string.watch_editor_attribute_margin
    "pct_complete" -> R.string.watch_editor_attribute_pct_complete
    "end_style" -> R.string.watch_editor_attribute_end_style
    "outside_opacity" -> R.string.watch_editor_attribute_outside_opacity
    "color2" -> R.string.watch_editor_attribute_color2
    "color3" -> R.string.watch_editor_attribute_color3
    "color4" -> R.string.watch_editor_attribute_color4
    "outline" -> R.string.watch_editor_attribute_outline
    "o_color" -> R.string.watch_editor_attribute_o_color
    "o_size" -> R.string.watch_editor_attribute_o_size
    "o_opacity" -> R.string.watch_editor_attribute_o_opacity
    "shadow" -> R.string.watch_editor_attribute_shadow
    "w_color" -> R.string.watch_editor_attribute_w_color
    "w_distance" -> R.string.watch_editor_attribute_w_distance
    "w_angle" -> R.string.watch_editor_attribute_w_angle
    "w_opacity" -> R.string.watch_editor_attribute_w_opacity
    "shader" -> R.string.watch_editor_attribute_shader
    "u_1" -> R.string.watch_editor_attribute_u1
    "u_2" -> R.string.watch_editor_attribute_u2
    "u_3" -> R.string.watch_editor_attribute_u3
    "u_4" -> R.string.watch_editor_attribute_u4
    "u_5" -> R.string.watch_editor_attribute_u5
    "radius_outer" -> R.string.watch_editor_attribute_radius_outer
    "radius_inner" -> R.string.watch_editor_attribute_radius_inner
    "angle_total" -> R.string.watch_editor_attribute_angle_total
    "is_clockwise" -> R.string.watch_editor_attribute_is_clockwise
    "display" -> R.string.watch_editor_attribute_display
    "x" -> R.string.watch_editor_attribute_x
    "y" -> R.string.watch_editor_attribute_y
    "rotation" -> R.string.watch_editor_attribute_rotation
    "opacity" -> R.string.watch_editor_attribute_opacity
    "anim_scale_x" -> R.string.watch_editor_attribute_scale_x
    "anim_scale_y" -> R.string.watch_editor_attribute_scale_y
    "gyro" -> R.string.watch_editor_attribute_gyro
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
    "restart_on_bright" -> R.string.watch_editor_attribute_restart_on_bright
    "color_dim" -> R.string.watch_editor_attribute_color_dim
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

/**
 * HSV 三滑条取色盘。
 *
 * 实现已经搬进公共件 [com.flue.launcher.ui.common.FlueColorPickerDialog] —— 应用列表背景的
 * 「自定义颜色」也要用同一套，项目里不能再多一份手搓取色 UI。这里只留一层转发，
 * 编辑器内部的调用点不用改。
 */
@Composable
private fun EditorColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    FlueColorPickerDialog(
        initial = initial,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
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
    // rounded 是规范 §7.12 的独立类型（不是 shape 的一个枚举值），而且是 corner_type
    // 唯一真正用得上的地方。以前插不了，用户只能靠导入外来表盘才能拿到这种图层。
    "rounded" to R.string.watch_editor_layer_rounded,
    "image" to R.string.watch_editor_layer_image,
    "image_gif" to R.string.watch_editor_layer_looping_background,
    "ring" to R.string.watch_editor_layer_ring,
    "progress" to R.string.watch_editor_layer_progress,
)

/** 1.0 专属的可插入类型。2.0 序列化器的白名单里没有 video。 */
private val V1_ONLY_LAYER_TYPES = listOf(
    "video" to R.string.watch_editor_layer_video,
)

/**
 * 按文档格式版本过滤「可插入」类型。
 *
 * 只影响新增按钮，不影响 [watchEditorLayerTypeLabel] —— 已打开的 2.0 文档若
 * 因某种途径含 video 图层，图层列表仍要显示它的中文名而不是裸类型串。
 */
internal fun insertableLayerTypes(
    version: WatchFormatVersion,
): List<Pair<String, Int>> = when (version) {
    WatchFormatVersion.V1 -> BASIC_LAYER_TYPES + V1_ONLY_LAYER_TYPES
    WatchFormatVersion.V2 -> BASIC_LAYER_TYPES
}

/** 标签查表覆盖全部已知类型，与可插入与否无关。 */
private val LAYER_TYPE_LABELS = BASIC_LAYER_TYPES + V1_ONLY_LAYER_TYPES

@Composable
private fun watchEditorLayerTypeLabel(type: String): String {
    val labelRes = LAYER_TYPE_LABELS.firstOrNull { (code, _) ->
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
    "gyro",
    "text",
    "text_size",
    "color",
    "color_dim",
    "display",
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
    "restart_on_bright",
)

/**
 * 描边族。渲染器只在 `text` / 位图文字 / `shape` / `rounded` 上读它们
 * （`resolveOutline` 的四个调用点），与 `WM_EFFECTS.md` §7 的支持矩阵一致 ——
 * 所以别给 `ring` / `progress` 挂这一组，那会让面板承诺渲染器不做的事。
 */
private val OUTLINE_ATTRIBUTES = listOf("outline", "o_color", "o_size", "o_opacity")

/** 阴影族。支持范围同 [OUTLINE_ATTRIBUTES]。 */
private val SHADOW_ATTRIBUTES = listOf("shadow", "w_color", "w_distance", "w_angle", "w_opacity")

/**
 * 着色器族。`u_1..u_5` 的含义随 `shader` 变（见 `WM_EFFECTS.md` §3），
 * 所以标签只能是通用的「着色器参数 u_n」。
 *
 * 只挂在 `shape` / `rounded` 上：这两个是 Flue 渲染器真正实现了 `shader` 的类型。
 * 规范说 `progress` **不支持** shader；`text` / `image` / `ring` 支持但 Flue 还没做 ——
 * 后者是渲染器缺口，不该先在面板上放出来。
 */
private val SHADER_ATTRIBUTES = listOf("shader", "u_1", "u_2", "u_3", "u_4", "u_5")

/** `shape`（规范 §7.11）。 */
internal val SHAPE_STYLE_ATTRIBUTES =
    listOf("shape", "width", "height", "alignment", "display") +
        OUTLINE_ATTRIBUTES + SHADOW_ATTRIBUTES + SHADER_ATTRIBUTES

/** `rounded`（规范 §7.12）—— `corner_type` 唯一真正用得上的图层类型。 */
internal val ROUNDED_STYLE_ATTRIBUTES =
    listOf("width", "height", "radius", "corner_type", "alignment", "display") +
        OUTLINE_ATTRIBUTES + SHADOW_ATTRIBUTES + SHADER_ATTRIBUTES

/**
 * `progress`（规范 §7.4）。
 *
 * `margin` 排在 `radius` 前面是有意的：**只有 margin > 0 才画 color4 边框与 color3
 * 轨道**，所以「我的进度条没有底槽」这类问题的答案就在这一格，得让人先看到它。
 *
 * 描边与阴影两族是 **Flue 扩展**：`WM_EFFECTS.md` §7 的支持矩阵里 progress 的
 * Shadow / Outline / Glow 都是 `—`，但 `rounded` 在同一张矩阵里也是 `—` 而 Flue 一直
 * 在画，两边保持一致比守着一张我们已经不遵守的表更有用。渲染侧在
 * `WatchmakerWatchFaceView.drawProgress` 里按外框画，导出到官方渲染器时这两族会被
 * 忽略（不会画错，只是没有边和影）。
 *
 * **不给 shader**：progress 的填充段已经被 `color` → `color2` 渐变占着（规范 §7.6：
 * 渐变只跨已填充那一段），shader 挂到同一个 Paint 上会把 color2 整个盖掉 —— 面板上
 * 两个都能调、实际只有一个生效，比不给更糟。矩阵里 progress 的 shader 也是 `—`。
 */
internal val PROGRESS_STYLE_ATTRIBUTES = listOf(
    "width",
    "height",
    "pct_complete",
    "angle",
    "margin",
    "radius",
    "corner_type",
    "end_style",
    "color2",
    "color3",
    "color4",
    "outside_opacity",
    "alignment",
    "display",
) + OUTLINE_ATTRIBUTES + SHADOW_ATTRIBUTES

/**
 * `ring`（规范 §7.3）。
 *
 * 这里的 `radius` 是**端帽倒角**，不是外半径 —— 外半径是 `radius_outer`。
 * 渲染器把它夹在 `[0, 环厚/2]`，省略时取上限（经典半圆帽）。
 */
internal val RING_STYLE_ATTRIBUTES = listOf(
    "radius_outer",
    "radius_inner",
    "angle_total",
    "angle",
    "is_clockwise",
    "radius",
    "end_style",
    "color2",
    "color3",
    "outside_opacity",
    "alignment",
    "display",
)

/** 走取色器的属性。这些位置不给表达式入口 —— 色块本身就是最快的输入方式。 */
internal val COLOR_ATTRIBUTES = setOf("color", "color_dim", "color2", "color3", "color4", "o_color", "w_color")

/**
 * 有固定取值表的属性名。这些位置的下拉槽位给枚举面板，不给表达式面板。
 *
 * 单独列一个 set 而不是去问 [attributeEnumOptions]，是因为后者按图层类型分流
 * （`end_style` 在 `progress` 与 `ring` 上取值不同），而这里只需要「是不是枚举」。
 */
internal val ENUM_ATTRIBUTE_NAMES = setOf(
    "restart_on_load",
    "restart_on_bright",
    "shape",
    "corner_type",
    "outline",
    "shadow",
    "shader",
    "end_style",
    "is_clockwise",
    "alignment",
    "display",
    "anim_in",
    "anim_out",
)

/**
 * 能挂 WatchMaker 表达式的属性 —— 输入框右侧给「插入变量」入口。
 *
 * 排除三类：枚举（槽位给枚举面板）、颜色（槽位给取色器）、以及
 * `restart_on_load` 那种由 `anim_in` 联动写入的开关。
 */
internal val WATCH_EXPRESSION_ATTRIBUTES = (
    EDITABLE_ATTRIBUTES + TEXT_ANIMATION_ATTRIBUTES + SHAPE_STYLE_ATTRIBUTES +
        ROUNDED_STYLE_ATTRIBUTES + PROGRESS_STYLE_ATTRIBUTES + RING_STYLE_ATTRIBUTES
).toSet() - setOf("restart_on_load") - ENUM_ATTRIBUTE_NAMES - COLOR_ATTRIBUTES

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
internal fun editableAttributesFor(type: String): List<String> = buildList {
    addAll(EDITABLE_ATTRIBUTES)
    when (type.lowercase(Locale.ROOT)) {
        "gif", "image_gif" -> add("gif_delay")
        "text", "text_curved" -> addAll(TEXT_ANIMATION_ATTRIBUTES)
        // 形状族四种：渲染器早就读这些属性了，以前面板一个都不给，作者只能靠
        // 导入外来表盘或手写 XML 才能碰到 corner_type / shader / margin 这些。
        "shape" -> addAll(SHAPE_STYLE_ATTRIBUTES)
        "rounded" -> addAll(ROUNDED_STYLE_ATTRIBUTES)
        "progress" -> addAll(PROGRESS_STYLE_ATTRIBUTES)
        "ring" -> addAll(RING_STYLE_ATTRIBUTES)
    }
}.distinct()

/**
 * 属性面板里 `path` 输入框的显示值：把任何约定目录前缀剥掉，只留「相对 `images/` 的路径」。
 *
 * 规范里 `path` 是**约定目录内部**的名字（`WM_FORMAT_1.0.md` §7.1「Filename inside
 * `images/`」），所以 `images/` 这一层根本不该出现在这个字段里 —— 出现了就意味着官方
 * 渲染器会去找 `images/images/foo.png`。写入侧一直是对的（[watchXmlAssetPath] 剥了），
 * 但**面板显示**的是 XML 里的原文：导入的外来表盘若写了 `images/foo.png`，面板就照原样
 * 显示，作者照着编辑等于把错误传下去。
 *
 * 三条规则：
 * - `image_gif` 的序列帧用反引号分隔多段，**逐段**剥，不能整串当一个路径；
 * - 含 `{` 的值是表达式（`path="{dynamic_image}"`），原样返回 —— 求值结果不在这里，
 *   猜着剥只会把 tag 名切坏；
 * - `video` 的约定目录是 `videos/`，其余都是 `images/`。
 */
internal fun normalizeWatchEditorAssetPath(layerType: String, rawPath: String): String {
    if (rawPath.contains('{')) return rawPath
    val directory = if (layerType.equals("video", ignoreCase = true)) "videos" else "images"
    return rawPath.split('`').joinToString("`") { segment ->
        if (segment.isBlank()) segment else watchXmlAssetPath(segment, directory)
    }
}

/** 枚举选项：写进 XML 的值 + 显示名。显示名可以是资源，也可以是与语言无关的字面量。 */
internal data class EditorEnumOption(
    val value: String,
    @StringRes val labelRes: Int? = null,
    val literalLabel: String? = null,
)

/**
 * 某个属性的固定取值表；返回 null 表示这不是枚举属性。
 *
 * **写进 XML 的值一律保持规范原样大小写**（`Square` / `Outline` / `GradientLinear`…）。
 * Flue 自己读的时候会 lowercase，但别的渲染器是拿原值去拼 `shape_<小写>.png`
 * 这类查表键的 —— 写成小写就整层不显示。这条坑 [defaultAttributes] 的注释里
 * 已经踩过一次。
 *
 * 按 [layerType] 分流是因为 `end_style` 在 `progress` 与 `ring` 上取值不同。
 */
internal fun attributeEnumOptions(
    layerType: String,
    name: String,
): List<EditorEnumOption>? = when (name) {
    "restart_on_load", "restart_on_bright" -> listOf(
        EditorEnumOption("Y", labelRes = R.string.watch_editor_protocol_yes),
        EditorEnumOption("N", labelRes = R.string.watch_editor_protocol_no),
    )
    "anim_in", "anim_out" -> TEXT_ENTRY_ANIMATION_OPTIONS.map { (value, labelRes) ->
        EditorEnumOption(value, labelRes = labelRes)
    }
    "shape" -> WATCH_SHAPE_ATTRIBUTE_VALUES.map { value ->
        EditorEnumOption(value, labelRes = watchShapeKindLabelRes(value))
    }
    "corner_type" -> (0..16).map { type ->
        EditorEnumOption(type.toString(), literalLabel = cornerTypeChipLabel(type))
    }
    "outline" -> listOf(
        EditorEnumOption("", labelRes = R.string.watch_editor_none),
        EditorEnumOption("Outline", labelRes = R.string.watch_editor_attribute_outline_stroke),
        EditorEnumOption("Glow", labelRes = R.string.watch_editor_attribute_outline_glow),
    )
    "shadow" -> listOf(
        EditorEnumOption("", labelRes = R.string.watch_editor_none),
        EditorEnumOption("Drop", labelRes = R.string.watch_editor_attribute_shadow_drop),
        EditorEnumOption("Flat", labelRes = R.string.watch_editor_attribute_shadow_flat),
    )
    // 着色器名不翻译：GradientLinear 这类是规范里的专有名字，每份教程、每个
    // 编辑器都这么写，译成中文反而对不上文档。
    "shader" -> WatchShapeShaderKind.entries.map { kind ->
        if (kind == WatchShapeShaderKind.NONE) {
            EditorEnumOption("", labelRes = R.string.watch_editor_none)
        } else {
            val value = watchShapeShaderAttributeValue(kind)
            EditorEnumOption(value, literalLabel = value)
        }
    }
    "end_style" -> if (layerType.equals("ring", ignoreCase = true)) {
        // ring 省略 end_style 时是半圆帽；butt = 平、square = 方。
        listOf(
            EditorEnumOption("", labelRes = R.string.watch_editor_attribute_cap_round),
            EditorEnumOption("butt", labelRes = R.string.watch_editor_attribute_cap_butt),
            EditorEnumOption("square", labelRes = R.string.watch_editor_attribute_cap_square),
        )
    } else {
        // progress 的规范取值是 round / flat，缺省 flat。
        listOf(
            EditorEnumOption("", labelRes = R.string.watch_editor_attribute_cap_butt),
            EditorEnumOption("round", labelRes = R.string.watch_editor_attribute_cap_round),
            EditorEnumOption("flat", labelRes = R.string.watch_editor_attribute_cap_butt),
        )
    }
    "is_clockwise" -> listOf(
        EditorEnumOption("Y", labelRes = R.string.watch_editor_attribute_clockwise),
        EditorEnumOption("N", labelRes = R.string.watch_editor_attribute_counter_clockwise),
    )
    // 九个锚点用规范自己的两字母代码。译成「左上／上中／…」会让这一行在 230dp
    // 圆屏上宽出三倍，而 tl/tc/tr 本身就是文档、教程里通用的写法。
    "alignment" -> listOf("tl", "tc", "tr", "cl", "cc", "cr", "bl", "bc", "br").map { code ->
        EditorEnumOption(code, literalLabel = code)
    }
    "display" -> listOf(
        EditorEnumOption("", labelRes = R.string.watch_editor_attribute_display_default),
        EditorEnumOption("b", labelRes = R.string.watch_editor_attribute_display_bright),
        EditorEnumOption("d", labelRes = R.string.watch_editor_attribute_display_dim),
        EditorEnumOption("bd", labelRes = R.string.watch_editor_attribute_display_both),
    )
    else -> null
}

@StringRes
private fun watchShapeKindLabelRes(value: String): Int = when (value) {
    "Square" -> R.string.watch_editor_shape_kind_square
    "Circle" -> R.string.watch_editor_shape_kind_circle
    "Triangle" -> R.string.watch_editor_shape_kind_triangle
    "Pentagon" -> R.string.watch_editor_shape_kind_pentagon
    "Hexagon" -> R.string.watch_editor_shape_kind_hexagon
    "Star" -> R.string.watch_editor_shape_kind_star
    "Heart" -> R.string.watch_editor_shape_kind_heart
    else -> R.string.watch_editor_attribute_unknown
}

/**
 * `corner_type` 的芯片文案：值 + 被倒圆的角，例如 `6 · TL TR`、`0 · —`。
 *
 * 直接从 [watchShapeCornerRadii] 反推，不另写一张 17 行的对照表 —— 那张表在
 * 渲染器里已经有唯一一份，抄第二遍就会漂。`TL/TR/BL/BR` 也正是规范 §7.12
 * 那张表的列名，所以这串文案不需要翻译。
 */
internal fun cornerTypeChipLabel(cornerType: Int): String {
    val radii = watchShapeCornerRadii(cornerType, 1f)
    // addRoundRect 的数组顺序是 TL,TL, TR,TR, BR,BR, BL,BL。
    val rounded = buildList {
        if (radii[0] > 0f) add("TL")
        if (radii[2] > 0f) add("TR")
        if (radii[6] > 0f) add("BL")
        if (radii[4] > 0f) add("BR")
    }
    return "$cornerType · " + rounded.joinToString(" ").ifEmpty { "—" }
}

/**
 * 分组标题：在这个属性之前插一条小标题。相邻重复的标题由调用方去重，
 * 所以 `shape` 与 `width` 都指向「几何与样式」也不会画出两条。
 */
@StringRes
private fun attributeSectionHeaderRes(name: String): Int? = when (name) {
    "x" -> R.string.watch_editor_section_transform
    "shape", "width", "radius_outer" -> R.string.watch_editor_section_geometry
    "color2" -> R.string.watch_editor_section_colors
    "outline" -> R.string.watch_editor_section_outline
    "shadow" -> R.string.watch_editor_section_shadow
    "shader" -> R.string.watch_editor_section_shader
    "alignment" -> R.string.watch_editor_section_anchor
    "anim_in" -> R.string.watch_editor_section_text_animation
    else -> null
}

internal val NUMERIC_ATTRIBUTES = setOf(
    "x",
    "y",
    "rotation",
    "opacity",
    "anim_scale_x",
    "anim_scale_y",
    "gyro",
    "text_size",
    "gif_delay",
    "dur_in",
    "dur_on",
    "dur_out",
    "dur_off",
    "delay_start",
    "repeat_count",
    // 形状族的纯几何 / 效果数值。
    //
    // 刻意**不**收 `angle` 与 `pct_complete`：整份格式里最常被写成表达式的就是这两个
    // （`angle="{dh}*30"`、`pct_complete="{hp}"`），给它们上数字键盘等于让人打不出
    // 花括号。它们仍有 fx 入口，键盘留 Text。
    "width",
    "height",
    "radius",
    "corner_type",
    "margin",
    "outside_opacity",
    "radius_outer",
    "radius_inner",
    "angle_total",
    "o_size",
    "o_opacity",
    "w_distance",
    "w_angle",
    "w_opacity",
    // u_1 / u_2 在两种渐变里是十六进制颜色，不能锁数字键盘；u_3..u_5 恒为数值。
    "u_3",
    "u_4",
    "u_5",
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
