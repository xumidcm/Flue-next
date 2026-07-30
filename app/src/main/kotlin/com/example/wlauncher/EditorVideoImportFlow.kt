package com.flue.launcher

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.watchface.editor.WatchEditorCropAspect
import com.flue.launcher.watchface.editor.WatchEditorCropRect
import com.flue.launcher.watchface.editor.WatchEditorVideoCrop
import com.flue.launcher.watchface.editor.WatchEditorVideoImportOptions
import com.flue.launcher.watchface.editor.WatchEditorVideoInfo
import com.flue.launcher.watchface.editor.WatchEditorVideoResolutionPreset
import com.flue.launcher.watchface.editor.WatchCanvasSize
import com.flue.launcher.watchface.editor.constrainWatchEditorCropRect
import com.flue.launcher.watchface.editor.mapWatchEditorCropToSource
import com.flue.launcher.watchface.editor.moveWatchEditorCropRect
import com.flue.launcher.watchface.editor.resizeWatchEditorCropRect
import com.flue.launcher.watchface.editor.resolveVideoTransform
import com.flue.launcher.watchface.editor.resolveVideoBitrateKbps
import com.flue.launcher.watchface.editor.resolveWatchEditorCropHandle
import com.flue.launcher.watchface.editor.scaleWatchEditorCropRect
import com.flue.launcher.watchface.editor.watchEditorCropAspectRatio
import com.flue.launcher.watchface.editor.watchEditorFitImageRect
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class EditorVideoImportStage { CROP_AND_TRIM, ENCODING }

@Composable
internal fun EditorVideoImportFlow(
    uri: Uri,
    info: WatchEditorVideoInfo,
    canvasSize: WatchCanvasSize,
    onDismiss: () -> Unit,
    onConfirm: (WatchEditorVideoImportOptions) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isRound = isFlueScreenRound()
    val displayMetrics = context.resources.displayMetrics
    var stage by remember(uri) { mutableStateOf(EditorVideoImportStage.CROP_AND_TRIM) }
    var aspect by remember(uri) { mutableStateOf(WatchEditorCropAspect.FREE) }
    var areaSize by remember(uri) { mutableStateOf(IntSize.Zero) }
    var cropRect by remember(uri) { mutableStateOf<WatchEditorCropRect?>(null) }
    var startMs by remember(uri) { mutableFloatStateOf(0f) }
    var endMs by remember(uri, info.durationMs) { mutableFloatStateOf(info.durationMs.toFloat()) }
    var playing by remember(uri) { mutableStateOf(false) }
    var preset by remember(uri) { mutableStateOf(WatchEditorVideoResolutionPreset.P480) }
    var frameRate by remember(uri) { mutableStateOf("10") }
    var bitrate by remember(uri) { mutableStateOf("3000") }
    var previewView by remember(uri) { mutableStateOf<EditorVideoPreviewView?>(null) }
    val currentPreviewView = rememberUpdatedState(previewView)

    val fit = remember(info, areaSize) {
        if (areaSize.width > 0 && areaSize.height > 0) {
            watchEditorFitImageRect(
                info.width.toFloat(),
                info.height.toFloat(),
                areaSize.width.toFloat(),
                areaSize.height.toFloat(),
            )
        } else null
    }
    val ratio = watchEditorCropAspectRatio(
        aspect,
        displayMetrics.widthPixels,
        displayMetrics.heightPixels,
        canvasSize.width,
        canvasSize.height,
    )
    val minCropSizePx = with(density) { 32.dp.toPx() }
    LaunchedEffect(fit, aspect) {
        val bounds = fit ?: return@LaunchedEffect
        cropRect = constrainWatchEditorCropRect(cropRect ?: bounds, bounds, minCropSizePx, ratio)
    }

    val sourceCrop = remember(cropRect, fit, info) {
        val selected = cropRect
        val bounds = fit
        if (selected == null || bounds == null) {
            WatchEditorVideoCrop.Full
        } else {
            val source = mapWatchEditorCropToSource(selected, bounds, info.width, info.height)
            WatchEditorVideoCrop(
                left = source.left.toFloat() / info.width,
                top = source.top.toFloat() / info.height,
                right = source.right.toFloat() / info.width,
                bottom = source.bottom.toFloat() / info.height,
            )
        }
    }
    val options = remember(startMs, endMs, sourceCrop, preset, frameRate, bitrate, info) {
        runCatching {
            val effectiveBitrateKbps = resolveVideoBitrateKbps(
                info = info,
                preset = preset,
                requestedBitrateKbps = bitrate.toIntOrNull() ?: error("bitrate"),
            )
            val candidate = WatchEditorVideoImportOptions(
                startMs = startMs.roundToLong(),
                endMs = endMs.roundToLong(),
                crop = sourceCrop,
                resolutionPreset = preset,
                frameRate = frameRate.toFloatOrNull() ?: error("fps"),
                bitrateKbps = effectiveBitrateKbps,
            )
            require(candidate.endMs <= info.durationMs)
            resolveVideoTransform(info, candidate)
            candidate
        }.getOrNull()
    }
    val transform = options?.let { resolveVideoTransform(info, it) }
    val estimatedFrames = options?.let {
        (it.durationMs / 1_000.0 * it.frameRate).roundToInt().coerceAtLeast(1)
    }

    BackHandler {
        if (stage == EditorVideoImportStage.ENCODING) {
            stage = EditorVideoImportStage.CROP_AND_TRIM
        } else {
            onDismiss()
        }
    }
    DisposableEffect(uri) {
        onDispose { currentPreviewView.value?.release() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                horizontal = if (isRound) 18.dp else 10.dp,
                vertical = if (isRound) 14.dp else 8.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                if (stage == EditorVideoImportStage.CROP_AND_TRIM) {
                    R.string.watch_editor_video_crop_title
                } else {
                    R.string.watch_editor_video_encoding_title
                },
            ),
            modifier = Modifier.align(Alignment.CenterHorizontally),
            fontWeight = FontWeight.SemiBold,
        )

        if (stage == EditorVideoImportStage.CROP_AND_TRIM) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(10.dp))
                    .onSizeChanged { areaSize = it },
            ) {
                AndroidView(
                    factory = { viewContext ->
                        EditorVideoPreviewView(viewContext).also { view ->
                            previewView = view
                            view.bind(uri, info, startMs.roundToLong(), endMs.roundToLong(), playing)
                        }
                    },
                    update = { view ->
                        previewView = view
                        view.bind(uri, info, startMs.roundToLong(), endMs.roundToLong(), playing)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                VideoCropMask(
                    cropRect = cropRect,
                    bounds = fit,
                    minCropSizePx = minCropSizePx,
                    ratio = ratio,
                    onCropChanged = { cropRect = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    WatchEditorCropAspect.FREE,
                    WatchEditorCropAspect.SCREEN,
                    WatchEditorCropAspect.CANVAS,
                ).forEach { candidate ->
                    val label = stringResource(
                        when (candidate) {
                            WatchEditorCropAspect.FREE -> R.string.watch_editor_crop_free
                            WatchEditorCropAspect.SCREEN -> R.string.watch_editor_crop_screen
                            WatchEditorCropAspect.CANVAS -> R.string.watch_editor_crop_canvas
                            WatchEditorCropAspect.SQUARE -> R.string.watch_editor_crop_square
                        },
                    )
                    if (aspect == candidate) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { aspect = candidate }) { Text(label) }
                    }
                }
            }

            RangeSlider(
                value = startMs..endMs,
                onValueChange = { range ->
                    playing = false
                    val durationMax = info.durationMs.toFloat().coerceAtLeast(1f)
                    startMs = range.start.coerceIn(0f, (durationMax - 1f).coerceAtLeast(0f))
                    endMs = range.endInclusive.coerceIn(startMs + 1f, durationMax)
                },
                valueRange = 0f..info.durationMs.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    R.string.watch_editor_video_trim_fmt,
                    formatEditorVideoTime(startMs.roundToLong()),
                    formatEditorVideoTime(endMs.roundToLong()),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { playing = !playing },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (playing) MiuixIcons.Pause else MiuixIcons.Play,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (playing) R.string.watch_editor_video_pause else R.string.watch_editor_video_play))
                }
                Button(
                    onClick = {
                        playing = false
                        stage = EditorVideoImportStage.ENCODING
                    },
                    enabled = cropRect != null && endMs > startMs,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.watch_editor_video_next))
                }
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Icon(MiuixIcons.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.watch_editor_cancel))
            }
        } else {
            Text(
                stringResource(
                    R.string.watch_editor_video_source_fmt,
                    info.width,
                    info.height,
                    formatEditorVideoSeconds(info.durationMs),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WatchEditorVideoResolutionPreset.entries.forEach { candidate ->
                    val label = when (candidate) {
                        WatchEditorVideoResolutionPreset.ORIGINAL -> stringResource(R.string.watch_editor_video_original)
                        WatchEditorVideoResolutionPreset.P1080 -> "1080P"
                        WatchEditorVideoResolutionPreset.P720 -> "720P"
                        WatchEditorVideoResolutionPreset.P480 -> "480P"
                        WatchEditorVideoResolutionPreset.P360 -> "360P"
                    }
                    if (preset == candidate) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        OutlinedButton(onClick = {
                            if (candidate == WatchEditorVideoResolutionPreset.ORIGINAL) {
                                info.frameRate?.let { frameRate = formatEditorVideoFrameRate(it) }
                                bitrate = (info.bitrateKbps ?: 3000).toString()
                            } else if (preset == WatchEditorVideoResolutionPreset.ORIGINAL) {
                                bitrate = "3000"
                            }
                            preset = candidate
                        }) { Text(label) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = frameRate,
                    onValueChange = { frameRate = it },
                    label = { Text(stringResource(R.string.watch_editor_video_frame_rate)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = bitrate,
                    onValueChange = { bitrate = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.watch_editor_video_bitrate)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (transform == null || estimatedFrames == null) {
                    stringResource(R.string.watch_editor_video_invalid_options)
                } else {
                    stringResource(
                        R.string.watch_editor_video_output_fmt,
                        transform.outputWidth,
                        transform.outputHeight,
                        estimatedFrames,
                    )
                },
                color = if (transform == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { stage = EditorVideoImportStage.CROP_AND_TRIM },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.watch_editor_video_back))
                }
                Button(
                    onClick = { options?.let(onConfirm) },
                    enabled = options != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(MiuixIcons.Ok, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.watch_editor_start_conversion))
                }
            }
        }
    }
}

@Composable
private fun VideoCropMask(
    cropRect: WatchEditorCropRect?,
    bounds: WatchEditorCropRect?,
    minCropSizePx: Float,
    ratio: Float?,
    onCropChanged: (WatchEditorCropRect) -> Unit,
) {
    val density = LocalDensity.current
    val latestCrop = rememberUpdatedState(cropRect)
    val latestBounds = rememberUpdatedState(bounds)
    val latestRatio = rememberUpdatedState(ratio)
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val handleTouchPx = with(density) { 14.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val initial = latestCrop.value ?: return@awaitEachGesture
                    val cropBounds = latestBounds.value ?: return@awaitEachGesture
                    val handle = resolveWatchEditorCropHandle(
                        initial,
                        down.position.x,
                        down.position.y,
                        handleTouchPx,
                    )
                    val moveInside = handle == null && initial.contains(down.position.x, down.position.y)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val current = latestCrop.value ?: break
                        var next = current
                        if (handle != null && pan != Offset.Zero) {
                            next = resizeWatchEditorCropRect(
                                next,
                                handle,
                                pan.x,
                                pan.y,
                                cropBounds,
                                minCropSizePx,
                                latestRatio.value,
                            )
                        } else if (moveInside && pan != Offset.Zero) {
                            next = moveWatchEditorCropRect(next, pan.x, pan.y, cropBounds)
                        }
                        if (zoom != 1f) {
                            next = scaleWatchEditorCropRect(
                                next,
                                zoom,
                                cropBounds,
                                minCropSizePx,
                                latestRatio.value,
                            )
                        }
                        if (next != current) onCropChanged(next)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        val rect = cropRect ?: return@Canvas
        val dim = Color(0x99000000)
        drawRect(dim, Offset.Zero, Size(size.width, rect.top.coerceAtLeast(0f)))
        drawRect(dim, Offset(0f, rect.bottom), Size(size.width, (size.height - rect.bottom).coerceAtLeast(0f)))
        drawRect(dim, Offset(0f, rect.top), Size(rect.left.coerceAtLeast(0f), rect.height))
        drawRect(dim, Offset(rect.right, rect.top), Size((size.width - rect.right).coerceAtLeast(0f), rect.height))
        drawRect(
            Color.White,
            Offset(rect.left, rect.top),
            Size(rect.width, rect.height),
            style = Stroke(width = 1.dp.toPx()),
        )
        val handleRadius = 7.dp.toPx()
        listOf(
            Offset(rect.left, rect.top),
            Offset(rect.right, rect.top),
            Offset(rect.left, rect.bottom),
            Offset(rect.right, rect.bottom),
        ).forEach { drawCircle(Color.White, handleRadius, it) }
    }
}

private fun formatEditorVideoTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (milliseconds.coerceAtLeast(0L) % 1_000L) / 100L
    return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
}

private fun formatEditorVideoSeconds(milliseconds: Long): String =
    String.format(Locale.US, "%.3f", milliseconds / 1_000.0).trimEnd('0').trimEnd('.')

private fun formatEditorVideoFrameRate(value: Float): String =
    String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

private class EditorVideoPreviewView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var sourceUri: Uri? = null
    private var videoInfo: WatchEditorVideoInfo? = null
    private var startMs: Long = 0L
    private var endMs: Long = Long.MAX_VALUE
    private var shouldPlay = false
    private var preparing = false
    private val progressGuard = object : Runnable {
        override fun run() {
            val active = player
            if (active != null && runCatching { active.isPlaying }.getOrDefault(false)) {
                val position = runCatching { active.currentPosition.toLong() }.getOrDefault(startMs)
                if (position >= endMs - 20L) {
                    runCatching { active.seekTo(startMs, MediaPlayer.SEEK_CLOSEST_SYNC) }
                }
            }
            mainHandler.postDelayed(this, 32L)
        }
    }

    init {
        surfaceTextureListener = this
        mainHandler.post(progressGuard)
    }

    fun bind(uri: Uri, info: WatchEditorVideoInfo, startMs: Long, endMs: Long, play: Boolean) {
        val sourceChanged = sourceUri != uri
        val startChanged = this.startMs != startMs
        sourceUri = uri
        videoInfo = info
        this.startMs = startMs.coerceAtLeast(0L)
        this.endMs = endMs.coerceAtLeast(this.startMs + 1L)
        shouldPlay = play
        applyVideoTransform(width, height)
        if (sourceChanged) releasePlayer()
        maybePrepare()
        val active = player ?: return
        if (!play) {
            if (runCatching { active.isPlaying }.getOrDefault(false)) runCatching { active.pause() }
            val position = runCatching { active.currentPosition.toLong() }.getOrDefault(this.startMs)
            if (startChanged || position !in this.startMs until this.endMs) {
                runCatching { active.seekTo(this.startMs, MediaPlayer.SEEK_CLOSEST_SYNC) }
            }
        } else if (!runCatching { active.isPlaying }.getOrDefault(false)) {
            val position = runCatching { active.currentPosition.toLong() }.getOrDefault(this.startMs)
            if (position !in this.startMs until this.endMs) {
                runCatching { active.seekTo(this.startMs, MediaPlayer.SEEK_CLOSEST_SYNC) }
            }
            runCatching { active.start() }
        }
    }

    private fun maybePrepare() {
        val uri = sourceUri ?: return
        val targetSurface = surface ?: return
        if (player != null || preparing) return
        preparing = true
        val next = MediaPlayer()
        player = next
        runCatching {
            next.setDataSource(context, uri)
            next.setSurface(targetSurface)
            next.setOnPreparedListener { prepared ->
                preparing = false
                prepared.seekTo(startMs, MediaPlayer.SEEK_CLOSEST_SYNC)
                if (shouldPlay) prepared.start()
            }
            next.setOnErrorListener { _, _, _ ->
                preparing = false
                true
            }
            next.prepareAsync()
        }.onFailure {
            preparing = false
            releasePlayer()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyVideoTransform(w, h)
    }

    private fun applyVideoTransform(viewWidth: Int, viewHeight: Int) {
        val info = videoInfo ?: return
        if (viewWidth <= 0 || viewHeight <= 0 || info.width <= 0 || info.height <= 0) return
        val viewAspect = viewWidth.toFloat() / viewHeight
        val videoAspect = info.width.toFloat() / info.height
        val scaleX = if (viewAspect > videoAspect) videoAspect / viewAspect else 1f
        val scaleY = if (viewAspect < videoAspect) viewAspect / videoAspect else 1f
        setTransform(Matrix().apply {
            setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        })
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        videoInfo?.let { texture.setDefaultBufferSize(it.width, it.height) }
        surface?.release()
        surface = Surface(texture)
        maybePrepare()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        applyVideoTransform(width, height)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releasePlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun releasePlayer() {
        player?.let { current ->
            runCatching { current.stop() }
            current.reset()
            current.release()
        }
        player = null
        preparing = false
    }

    fun release() {
        mainHandler.removeCallbacks(progressGuard)
        releasePlayer()
        surface?.release()
        surface = null
    }
}
