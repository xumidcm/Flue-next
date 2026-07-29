package com.flue.launcher.watchface.jbwatch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.standard.WatchDataProvider
import com.flue.launcher.watchface.standard.WatchDataSnapshot
import com.flue.launcher.watchface.standard.WatchTagResolution
import com.flue.launcher.watchface.standard.WatchmakerExpressionEvaluator
import com.flue.launcher.watchface.standard.WatchmakerTagResolver
import com.flue.launcher.watchface.standard.rememberWatchmakerWatchDataProvider
import com.flue.launcher.watchface.standard.parseWatchGifPaths
import com.flue.launcher.watchface.standard.resolveWatchCounterTag
import com.flue.launcher.watchface.standard.watchGifFrameIndex
import com.flue.launcher.watchface.standard.watchLayerRequiresFrameTicks
import com.flue.launcher.watchface.standard.watchTextAnimationFrame
import com.flue.launcher.watchface.standard.watchWeatherNightSpriteIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val JB_LAYOUT_CANVAS_WIDTH = 512
private const val JB_LAYOUT_CANVAS_HEIGHT = 512
private const val DEFAULT_JB_GIF_DURATION_MS = 1_000
private val JB_PROTECTED_IMAGE_KEY = byteArrayOf(0x53, 0x57, 0x48, 0x6E, 0x2D)
private const val JB_STANDARD_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private const val JB_CUSTOM_BASE64 = "ABCgEFGHIJK4MNOPQRSTUVWXYZabcdefDhijklmnopqrstuvwxyz0123L56789+/"
// Android 14+ uses ICU's regex backend: braces inside a character class must
// be escaped as well, otherwise this top-level initializer can take the whole
// JB host down with PatternSyntaxException / ExceptionInInitializerError.
private val JB_DYNAMIC_TAG_PATTERN = Regex("\\{\\s*([^\\{\\}]+?)\\s*\\}")

data class JbWatchRoot(
    val name: String,
    val description: String,
    val tags: String,
    val features: String,
    val shape: String,
    val bgColor: String,
    val ucolorDefault: String,
    val mode3d: String,
    val indLoc: String,
    val indBg: String,
    val hotwordLoc: String,
    val hotwordBg: String
)

data class JbWatchLayer(
    val type: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val xExpression: String? = null,
    val yExpression: String? = null,
    val gyro: Float = 0f,
    val rotation: String = "0",
    val skewX: Float = 0f,
    val skewY: Float = 0f,
    val opacity: Int = 100,
    val alignment: String = "cc",
    val path: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val gifDelay: Int = 0,
    val display: String = "b",
    val text: String = "",
    val textSize: Float = 24f,
    val animIn: String? = null,
    val animOut: String? = null,
    val durationInSeconds: Float = 0.5f,
    val durationOnSeconds: Float = 0f,
    val durationOutSeconds: Float = 0.5f,
    val durationOffSeconds: Float = 0f,
    val delayStartSeconds: Float = 0f,
    val repeatCount: Int = 1,
    val restartOnLoad: Boolean = false,
    val animScaleX: Float = 100f,
    val animScaleY: Float = 100f,
    val font: String? = null,
    val transform: String = "n",
    val colorDim: String = "ffffff",
    val color: String = "ffffff",
    val outline: String? = null,
    val oColor: String = "000000",
    val oSize: Int = 0,
    val oOpacity: Int = 0,
    val tapAction: String? = null,
    val condValue: String? = null,
    val condGrid: String? = null,
    val weatherNight: String? = null,
    val name: String? = null,
    val radiusOuter: Float = 0f,
    val radiusInner: Float = 0f,
    val radius: Float = 0f,
    val angle: String = "0",
    val angleTotal: Float = 0f,
    val isClockwise: Boolean = true,
    val color2: String = "ffffff",
    val color3: String = "ffffff",
    val outsideOpacity: Int = 100,
    val curveDir: String = "Up",
    val pathFrames: List<String> = emptyList()
)

data class JbWatchFace(
    val root: JbWatchRoot,
    val layers: List<JbWatchLayer>,
    val rootDir: File,
    val xmlFile: File,
    val pxmlFile: File,
    val designWidth: Int,
    val designHeight: Int,
    val assetRevision: Long,
    val animationStartedAtUptimeMs: Long = 0L,
) {
    val needsHighRefresh: Boolean = layers.any(::jbLayerRequiresFrameTicks)
}

object JbWatchParser {
    fun parse(rootDir: File): JbWatchFace {
        val watchXml = File(rootDir, "watch.xml")
        val pxmlFile = File(rootDir, "watch.pxml")
        require(watchXml.isFile && pxmlFile.isFile) { "缺少 watch.xml / watch.pxml" }
        val root = parseRoot(watchXml)
        val layers = parseLayers(pxmlFile, rootDir)
        val assetRevision = JbWatchAssetIndexCache.prepare(rootDir)
        return JbWatchFace(
            root = root,
            layers = layers,
            rootDir = rootDir,
            xmlFile = watchXml,
            pxmlFile = pxmlFile,
            designWidth = JB_LAYOUT_CANVAS_WIDTH,
            designHeight = JB_LAYOUT_CANVAS_HEIGHT,
            assetRevision = assetRevision,
            animationStartedAtUptimeMs = SystemClock.uptimeMillis(),
        ).also(::prewarmJbInitialVisualFrames)
    }

    private fun parseRoot(file: File): JbWatchRoot {
        val document = newSecureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(file)
        val root = document.documentElement ?: error("watch.xml 无根节点")
        return JbWatchRoot(
            name = sanitizeVisibleText(root.getAttribute("name")).ifBlank { file.parentFile?.name.orEmpty() },
            description = sanitizeVisibleText(root.getAttribute("description")),
            tags = sanitizeVisibleText(root.getAttribute("tags")),
            features = sanitizeVisibleText(root.getAttribute("features")),
            shape = sanitizeVisibleText(root.getAttribute("shape")),
            bgColor = sanitizeVisibleText(root.getAttribute("bg_color")).ifBlank { "000000" },
            ucolorDefault = sanitizeVisibleText(root.getAttribute("ucolor_default")).ifBlank { "ffffff" },
            mode3d = sanitizeVisibleText(root.getAttribute("mode_3d")).ifBlank { "0" },
            indLoc = sanitizeVisibleText(root.getAttribute("ind_loc")).ifBlank { "tc" },
            indBg = sanitizeVisibleText(root.getAttribute("ind_bg")).ifBlank { "Y" },
            hotwordLoc = sanitizeVisibleText(root.getAttribute("hotword_loc")).ifBlank { "tc" },
            hotwordBg = sanitizeVisibleText(root.getAttribute("hotword_bg")).ifBlank { "Y" }
        )
    }

    private fun parseLayers(file: File, rootDir: File): List<JbWatchLayer> {
        val decoded = decodePxml(file)
        val document = newSecureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(decoded)))
        val layerNodes = document.getElementsByTagName("Layer")
        return buildList {
            for (index in 0 until layerNodes.length) {
                val element = layerNodes.item(index) as? Element ?: continue
                add(
                    JbWatchLayer(
                        type = element.getAttribute("type")
                            .ifBlank { "image" }
                            .lowercase(Locale.ROOT),
                        name = sanitizeVisibleText(element.getAttribute("name")).takeIf { it.isNotBlank() },
                        x = element.getAttribute("x").toFloatOrZero(),
                        y = element.getAttribute("y").toFloatOrZero(),
                        xExpression = element.getAttribute("x").takeIf(String::isNotBlank),
                        yExpression = element.getAttribute("y").takeIf(String::isNotBlank),
                        gyro = element.getAttribute("gyro").toFloatOrZero(),
                        rotation = element.getAttribute("rotation").ifBlank { "0" },
                        skewX = element.getAttribute("skew_x").toFloatOrZero(),
                        skewY = element.getAttribute("skew_y").toFloatOrZero(),
                        opacity = element.getAttribute("opacity").toIntOrZero(default = 100),
                        alignment = element.getAttribute("alignment").ifBlank { "cc" },
                        path = sanitizeVisibleText(element.getAttribute("path")).takeIf { it.isNotBlank() },
                        pathFrames = parsePaths(element.getAttribute("path")),
                        width = element.getAttribute("width").toIntOrZero(),
                        height = element.getAttribute("height").toIntOrZero(),
                        gifDelay = element.getAttribute("gif_delay").toIntOrZero(),
                        display = element.getAttribute("display").ifBlank { "b" },
                        text = sanitizeVisibleText(element.getAttribute("text")),
                        textSize = element.getAttribute("text_size").toFloatOrZero(default = 24f),
                        animIn = sanitizeVisibleText(element.getAttribute("anim_in"))
                            .takeIf(String::isNotBlank),
                        animOut = sanitizeVisibleText(element.getAttribute("anim_out"))
                            .takeIf(String::isNotBlank),
                        durationInSeconds = element.getAttribute("dur_in").toFloatOrZero(default = 0.5f),
                        durationOnSeconds = element.getAttribute("dur_on").toFloatOrZero(),
                        durationOutSeconds = element.getAttribute("dur_out").toFloatOrZero(default = 0.5f),
                        durationOffSeconds = element.getAttribute("dur_off").toFloatOrZero(),
                        delayStartSeconds = element.getAttribute("delay_start").toFloatOrZero(),
                        repeatCount = element.getAttribute("repeat_count").toIntOrZero(default = 1),
                        restartOnLoad = element.getAttribute("restart_on_load").let { raw ->
                            raw.equals("Y", ignoreCase = true) || raw.equals("true", ignoreCase = true)
                        },
                        animScaleX = element.getAttribute("anim_scale_x").toFloatOrZero(default = 100f),
                        animScaleY = element.getAttribute("anim_scale_y").toFloatOrZero(default = 100f),
                        font = sanitizeVisibleText(element.getAttribute("font")).takeIf { it.isNotBlank() },
                        transform = sanitizeVisibleText(element.getAttribute("transform")).ifBlank { "n" },
                        colorDim = sanitizeVisibleText(element.getAttribute("color_dim")).ifBlank { "ffffff" },
                        color = sanitizeVisibleText(element.getAttribute("color")).ifBlank { "ffffff" },
                        outline = sanitizeVisibleText(element.getAttribute("outline")).takeIf { it.isNotBlank() },
                        oColor = sanitizeVisibleText(element.getAttribute("o_color")).ifBlank { "000000" },
                        oSize = element.getAttribute("o_size").toIntOrZero(),
                        oOpacity = element.getAttribute("o_opacity").toIntOrZero(),
                        tapAction = sanitizeVisibleText(element.getAttribute("tap_action")).takeIf { it.isNotBlank() },
                        condValue = sanitizeVisibleText(element.getAttribute("cond_value")).takeIf { it.isNotBlank() },
                        condGrid = sanitizeVisibleText(element.getAttribute("cond_grid")).takeIf { it.isNotBlank() },
                        weatherNight = sanitizeVisibleText(element.getAttribute("weather_night")).takeIf { it.isNotBlank() },
                        radiusOuter = element.getAttribute("radius_outer").toFloatOrZero(),
                        radiusInner = element.getAttribute("radius_inner").toFloatOrZero(),
                        radius = element.getAttribute("radius").toFloatOrZero(),
                        angle = sanitizeVisibleText(element.getAttribute("angle")).ifBlank { "0" },
                        angleTotal = element.getAttribute("angle_total").toFloatOrZero(),
                        isClockwise = !element.getAttribute("is_clockwise").equals("N", ignoreCase = true),
                        color2 = sanitizeVisibleText(element.getAttribute("color2")).ifBlank { "ffffff" },
                        color3 = sanitizeVisibleText(element.getAttribute("color3")).ifBlank { "ffffff" },
                        outsideOpacity = element.getAttribute("outside_opacity").toIntOrZero(default = 100),
                        curveDir = sanitizeVisibleText(element.getAttribute("curve_dir")).ifBlank { "Up" }
                    )
                )
            }
        }
    }

    private fun decodePxml(file: File): String {
        val raw = file.readText(Charsets.UTF_8).trim()
        val encoded = raw.filter { ch ->
            ch == '=' || ch == '\n' || ch == '\r' || ch == '\t' || ch in JB_STANDARD_BASE64
        }
        if (raw.startsWith("<Watch")) return sanitizeJbXml(raw)

        val candidates = listOf(
            remapJbBase64(encoded, from = JB_CUSTOM_BASE64, to = JB_STANDARD_BASE64),
            encoded
        ).distinct().mapNotNull { candidate ->
            runCatching {
                sanitizeJbXml(
                    Base64.decode(candidate, Base64.DEFAULT).toString(Charsets.UTF_8)
                )
            }.getOrNull()
        }

        return candidates.firstOrNull(::isWellFormedJbXml)
            ?: candidates.firstOrNull { decoded -> decoded.startsWith("<Watch") && decoded.contains("<Layer") }
            ?: error("watch.pxml 解码失败")
    }

    private fun remapJbBase64(raw: String, from: String, to: String): String {
        val map = from.zip(to).toMap()
        return raw.map { ch -> map[ch] ?: ch }.joinToString("")
    }

    private fun sanitizeJbXml(raw: String): String {
        return raw.filter { ch ->
            ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20
        }.trim()
    }

    private fun isWellFormedJbXml(xml: String): Boolean {
        return runCatching {
            val document = newSecureDocumentBuilderFactory()
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
            document.documentElement?.tagName == "Watch"
        }.getOrDefault(false)
    }

    private fun String.toFloatOrZero(default: Float = 0f): Float = toFloatOrNull() ?: default
    private fun String.toIntOrZero(default: Int = 0): Int = toIntOrNull() ?: default
    private fun newSecureDocumentBuilderFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
    }

    private fun sanitizeVisibleText(raw: String?): String {
        return raw.orEmpty()
            .filter { it == '\t' || it == '\n' || it == '\r' || it.code >= 0x20 }
            .replace("�", "")
            .trim()
    }
}

@Composable
fun JbWatchFaceHost(
    descriptor: WatchFaceDescriptor,
    isFaceVisible: Boolean,
    refreshToken: Int,
    onLoadFailure: (WatchFaceDescriptor, Throwable) -> Unit,
    onLongPress: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    dataProvider: WatchDataProvider? = null,
    textEntryAnimationEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var loadError by remember(descriptor.stableKey, refreshToken) { mutableStateOf<Throwable?>(null) }
    val face by produceState<JbWatchFace?>(initialValue = null, descriptor.stableKey, refreshToken) {
        value = null
        loadError = null
        val sourceDir = descriptor.sourceDirPath?.let(::File)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                require(sourceDir != null && sourceDir.isDirectory) { ".watch 表盘目录不存在" }
                JbWatchParser.parse(sourceDir)
            }
        }
        result.onSuccess { value = it }.onFailure { loadError = it }
    }
    LaunchedEffect(loadError) {
        loadError?.let { onLoadFailure(descriptor, it) }
    }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var textAnimationStartedAtUptimeMs by remember(descriptor.stableKey, refreshToken) {
        mutableStateOf(SystemClock.uptimeMillis())
    }
    LaunchedEffect(isFaceVisible, face, textEntryAnimationEnabled) {
        if (isFaceVisible && textEntryAnimationEnabled) {
            textAnimationStartedAtUptimeMs = SystemClock.uptimeMillis()
        }
    }
    val renderFace = remember(face, textAnimationStartedAtUptimeMs) {
        face?.copy(animationStartedAtUptimeMs = textAnimationStartedAtUptimeMs)
    }
    val ownedDataProvider = rememberWatchmakerWatchDataProvider(
        active = dataProvider == null && isFaceVisible,
    )
    val resolvedDataProvider = dataProvider ?: ownedDataProvider
    val dataSnapshot = runCatching { resolvedDataProvider.snapshot(nowMillis) }
        .getOrDefault(WatchDataSnapshot())
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val latestFace by rememberUpdatedState(face)
    val latestNowMillis by rememberUpdatedState(nowMillis)
    val latestDataSnapshot by rememberUpdatedState(dataSnapshot)
    LaunchedEffect(isFaceVisible, face?.needsHighRefresh, descriptor.stableKey, refreshToken) {
        while (isFaceVisible) {
            nowMillis = System.currentTimeMillis()
            delay(if (face?.needsHighRefresh == true) 33L else 1_000L)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(descriptor.stableKey, refreshToken, onLongPress, onDoubleTap) {
                detectTapGestures(
                    onTap = { point ->
                        latestFace?.let {
                            handleJbWatchTap(
                                context = context,
                                face = it,
                                point = point,
                                viewWidth = size.width.toFloat(),
                                viewHeight = size.height.toFloat(),
                                nowMillis = latestNowMillis,
                                dataSnapshot = latestDataSnapshot,
                            )
                        }
                    },
                    onLongPress = onLongPress?.let { handler -> { _ -> handler() } },
                    onDoubleTap = onDoubleTap?.let { handler -> { _ -> handler() } }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            renderFace != null -> {
                val backgroundColor = runCatching {
                    android.graphics.Color.parseColor("#${renderFace.root.bgColor.trim().ifBlank { "000000" }}")
                }.getOrDefault(android.graphics.Color.BLACK)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.drawColor(backgroundColor)
                        drawJbFace(
                            canvas = native,
                            face = renderFace,
                            nowMillis = nowMillis,
                            dataSnapshot = dataSnapshot,
                            width = size.width.toInt(),
                            height = size.height.toInt(),
                            is24Hour = is24Hour,
                            textEntryAnimationEnabled = textEntryAnimationEnabled,
                        )
                    }
                }
            }
            loadError == null -> {
                Text("Loading", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
            }
        }
    }
}

private fun drawJbFace(
    canvas: AndroidCanvas,
    face: JbWatchFace,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    width: Int,
    height: Int,
    is24Hour: Boolean,
    textEntryAnimationEnabled: Boolean,
) {
    val canvasWidth = face.designWidth.coerceAtLeast(1)
    val canvasHeight = face.designHeight.coerceAtLeast(1)
    val scaleX = width.toFloat() / canvasWidth
    val scaleY = height.toFloat() / canvasHeight
    val scale = min(scaleX, scaleY)
    val offsetX = (width - canvasWidth * scale) / 2f
    val offsetY = (height - canvasHeight * scale) / 2f
    canvas.save()
    canvas.translate(offsetX, offsetY)
    canvas.scale(scale, scale)
    face.layers.forEachIndexed { layerIndex, layer ->
        drawJbLayer(
            canvas,
            face,
            layerIndex,
            layer,
            nowMillis,
            dataSnapshot,
            is24Hour,
            textEntryAnimationEnabled,
        )
    }
    canvas.restore()
}

private fun drawJbLayer(
    canvas: AndroidCanvas,
    face: JbWatchFace,
    layerIndex: Int,
    layer: JbWatchLayer,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    is24Hour: Boolean,
    textEntryAnimationEnabled: Boolean,
) {
    val animationElapsedMs = (SystemClock.uptimeMillis() - face.animationStartedAtUptimeMs)
        .coerceAtLeast(0L)
    val animatedLayer = resolveJbAnimatedTransform(
        layer = layer,
        nowMillis = nowMillis,
        dataSnapshot = dataSnapshot,
        animationElapsedMs = animationElapsedMs,
    )
    val textAnimation = if (textEntryAnimationEnabled && animatedLayer.type.startsWith("text") &&
        (!animatedLayer.animIn.isNullOrBlank() || !animatedLayer.animOut.isNullOrBlank())
    ) {
        jbTextAnimationFrame(animatedLayer, animationElapsedMs)
    } else {
        null
    }
    if (textAnimation?.visible == false) return
    val drawableLayer = textAnimation?.let { frame ->
        animatedLayer.copy(
            x = animatedLayer.x + frame.translationX,
            y = animatedLayer.y - frame.translationY,
            opacity = (animatedLayer.opacity * frame.alpha).roundToInt(),
        )
    } ?: animatedLayer

    val saveCount = canvas.save()
    textAnimation?.let { frame ->
        val pivot = jbLayerCenter(face, drawableLayer)
        if (frame.scale != 1f) canvas.scale(frame.scale, frame.scale, pivot.x, pivot.y)
        if (frame.rotation != 0f) canvas.rotate(frame.rotation, pivot.x, pivot.y)
    }
    when (drawableLayer.type) {
        "image", "image_gif", "image_cond" -> {
            drawJbBitmapLayer(
                canvas = canvas,
                face = face,
                layerIndex = layerIndex,
                layer = drawableLayer,
                nowMillis = nowMillis,
                dataSnapshot = dataSnapshot,
                animationElapsedMs = animationElapsedMs,
            )
        }
        "text" -> drawJbTextLayer(
            canvas, face, drawableLayer, nowMillis, dataSnapshot, is24Hour, animationElapsedMs,
        )
        "text_curved" -> drawJbCurvedTextLayer(
            canvas, face, drawableLayer, nowMillis, dataSnapshot, is24Hour, animationElapsedMs,
        )
        "ring" -> drawJbRingLayer(
            canvas, face, drawableLayer, nowMillis, dataSnapshot, animationElapsedMs,
        )
        "markers_hm" -> drawJbMarkersLayer(canvas, face, drawableLayer)
        "shape" -> drawJbShapeLayer(canvas, face, drawableLayer)
    }
    canvas.restoreToCount(saveCount)
}

private fun drawJbBitmapLayer(
    canvas: AndroidCanvas,
    face: JbWatchFace,
    layerIndex: Int,
    layer: JbWatchLayer,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    animationElapsedMs: Long,
) {
    if (!shouldDisplayLayer(layer, nowMillis, dataSnapshot, animationElapsedMs)) return
    val resolvedPathLayer = resolveJbDynamicPathLayer(
        layer = layer,
        nowMillis = nowMillis,
        dataSnapshot = dataSnapshot,
        animationElapsedMs = animationElapsedMs,
    )
    val dayFile = resolveJbWatchPath(
        rootDir = face.rootDir,
        layer = resolvedPathLayer,
        nowMillis = animationElapsedMs,
        assetRevision = face.assetRevision,
    )
    val nightFile = if (
        dayFile != null &&
        layer.type == "image_cond" &&
        layer.weatherNight.equals("Y", ignoreCase = true) &&
        isJbWeatherNight(dataSnapshot)
    ) {
        resolveJbNightWeatherFile(dayFile)
    } else {
        null
    }
    val file = nightFile ?: dayFile
    val frameCacheKey = jbLastVisualFrameKey(face, layerIndex)
    val decodedFrame = file?.let { JbWatchVisualFrame.decode(it) }
    val frame = decodedFrame?.also { JbWatchLastVisualFrameCache.put(frameCacheKey, it) }
        ?: JbWatchLastVisualFrameCache.get(frameCacheKey)
        ?: return
    val movie = frame.movie
    val bitmap = frame.bitmap
    val nativeWidth = frame.width
    val nativeHeight = frame.height
    val displaySize = resolveJbBitmapDisplaySize(layer, nativeWidth, nativeHeight)
    val rect = resolveJbRect(face, layer, displaySize.first, displaySize.second)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = resolveJbLayerOpacity(layer)
    }
    if (bitmap != null && layer.type == "image_cond" && !layer.condGrid.isNullOrBlank()) {
        val weatherCode = WatchmakerTagResolver.resolve("wci", Calendar.getInstance().apply {
            timeInMillis = nowMillis
        }, dataSnapshot).renderedValue
        val dayIndex = evaluateWeatherIndex(layer.condValue, weatherCode = weatherCode)
        val index = if (nightFile != null && resolveJbGrid(layer.condGrid) == (3 to 3)) {
            watchWeatherNightSpriteIndex(dayIndex)
        } else {
            dayIndex
        }
        drawConditionBitmap(canvas, bitmap, rect, layer.condGrid, index, paint)
    } else if (movie != null) {
        val durationMs = movie.duration().takeIf { it > 0 } ?: DEFAULT_JB_GIF_DURATION_MS
        movie.setTime((animationElapsedMs % durationMs).toInt())
        canvas.save()
        rotateJbLayer(canvas, rect, layer.rotation, nowMillis, dataSnapshot, animationElapsedMs)
        canvas.translate(rect.left, rect.top)
        canvas.scale(rect.width() / nativeWidth, rect.height() / nativeHeight)
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restore()
    } else if (bitmap != null) {
        canvas.save()
        rotateJbLayer(canvas, rect, layer.rotation, nowMillis, dataSnapshot, animationElapsedMs)
        canvas.drawBitmap(bitmap, null, rect, paint)
        canvas.restore()
    }
}

private fun isJbWeatherNight(snapshot: WatchDataSnapshot): Boolean {
    val weather = snapshot.weather
    weather?.isDaytime?.let { return !it }
    return (weather?.icon ?: snapshot.weatherIcon)
        ?.trim()
        ?.endsWith("n", ignoreCase = true) == true
}

private fun resolveJbNightWeatherFile(dayFile: File): File? {
    val extension = dayFile.extension
    val nightName = if (extension.isBlank()) {
        "${dayFile.name}_n"
    } else {
        "${dayFile.nameWithoutExtension}_n.$extension"
    }
    val parent = dayFile.parentFile ?: return null
    return File(parent, nightName).takeIf(File::isFile)
        ?: parent.listFiles()?.firstOrNull { candidate ->
            candidate.isFile && candidate.name.equals(nightName, ignoreCase = true)
        }
}

private fun drawJbTextLayer(
    canvas: AndroidCanvas,
    face: JbWatchFace,
    layer: JbWatchLayer,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    is24Hour: Boolean,
    animationElapsedMs: Long,
) {
    if (!shouldDisplayLayer(layer, nowMillis, dataSnapshot, animationElapsedMs)) return
    val text = resolveJbText(layer.text, nowMillis, dataSnapshot, is24Hour, animationElapsedMs)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = parseJbColor(layer.color)
        textSize = layer.textSize
        textAlign = when (jbAlignmentHorizontal(layer.alignment)) {
            0f -> Paint.Align.LEFT
            1f -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        alpha = resolveJbLayerOpacity(layer)
        typeface = JbWatchTypefaceCache.get(face.rootDir, face.assetRevision, layer.font)
    }
    val textDisplaySize = resolveJbTextDisplaySize(layer, text, paint)
    val rect = resolveJbRect(
        face = face,
        layer = layer,
        contentWidth = textDisplaySize.first,
        contentHeight = textDisplaySize.second
    )
    val x = when (jbAlignmentHorizontal(layer.alignment)) {
        0f -> rect.left
        1f -> rect.right
        else -> rect.centerX()
    }
    val y = rect.centerY() - (paint.descent() + paint.ascent()) / 2f
    if (layer.outline?.equals("Outline", ignoreCase = true) == true && layer.oSize > 0) {
        val outlinePaint = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = layer.oSize.toFloat()
            color = parseJbColor(layer.oColor)
            alpha = ((layer.oOpacity.coerceIn(0, 100) / 100f) * 255).toInt()
        }
        canvas.save()
        rotateJbLayer(canvas, rect, layer.rotation, nowMillis, dataSnapshot, animationElapsedMs)
        canvas.drawText(text, x, y, outlinePaint)
        canvas.restore()
    }
    canvas.save()
    rotateJbLayer(canvas, rect, layer.rotation, nowMillis, dataSnapshot, animationElapsedMs)
    canvas.drawText(text, x, y, paint)
    canvas.restore()
}

private fun drawJbCurvedTextLayer(
    canvas: AndroidCanvas,
    face: JbWatchFace,
    layer: JbWatchLayer,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    is24Hour: Boolean,
    animationElapsedMs: Long,
) {
    if (!shouldDisplayLayer(layer, nowMillis, dataSnapshot, animationElapsedMs)) return
    val text = resolveJbText(layer.text, nowMillis, dataSnapshot, is24Hour, animationElapsedMs)
    if (text.isBlank()) return
    val center = jbLayerCenter(face, layer)
    val radius = layer.radius.takeIf { it > 0f } ?: 180f
    val angle = evaluateJbNumericExpression(
        layer.rotation,
        nowMillis,
        dataSnapshot,
        animationElapsedMs,
    )
    val radians = Math.toRadians((angle - 90f).toDouble())
    val x = center.x + cos(radians).toFloat() * radius
    val y = center.y + sin(radians).toFloat() * radius
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = parseJbColor(layer.color)
        textSize = layer.textSize
        textAlign = when (jbAlignmentHorizontal(layer.alignment)) {
            0f -> Paint.Align.LEFT
            1f -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        alpha = resolveJbLayerOpacity(layer)
        typeface = JbWatchTypefaceCache.get(face.rootDir, face.assetRevision, layer.font)
    }
    canvas.save()
    canvas.rotate(angle + if (layer.curveDir.equals("Down", ignoreCase = true)) 180f else 0f, x, y)
    canvas.drawText(text, x, y - (paint.descent() + paint.ascent()) / 2f, paint)
    canvas.restore()
}

private fun drawJbRingLayer(
    canvas: AndroidCanvas,
    face: JbWatchFace,
    layer: JbWatchLayer,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    animationElapsedMs: Long,
) {
    if (!shouldDisplayLayer(layer, nowMillis, dataSnapshot, animationElapsedMs)) return
    val center = jbLayerCenter(face, layer)
    val outer = layer.radiusOuter.takeIf { it > 0f } ?: layer.radius.takeIf { it > 0f } ?: 210f
    val inner = layer.radiusInner.takeIf { it > 0f } ?: (outer - 14f)
    val stroke = (outer - inner).coerceAtLeast(1f)
    val radius = inner + stroke / 2f
    val sweep = evaluateJbNumericExpression(layer.angle, nowMillis, dataSnapshot, animationElapsedMs)
        .takeIf { it > 0f }
        ?: layer.angleTotal.takeIf { it > 0f }
        ?: 360f
    val start = evaluateJbNumericExpression(
        layer.rotation,
        nowMillis,
        dataSnapshot,
        animationElapsedMs,
    ) - 90f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = stroke
        color = parseJbColor(layer.color)
        alpha = resolveJbLayerOpacity(layer)
    }
    val oval = RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
    canvas.drawArc(oval, start, if (layer.isClockwise) sweep else -sweep, false, paint)
}

private fun drawJbMarkersLayer(canvas: AndroidCanvas, face: JbWatchFace, layer: JbWatchLayer) {
    val center = jbLayerCenter(face, layer)
    val radius = layer.radius.takeIf { it > 0f } ?: 181f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f
        color = parseJbColor(layer.color)
        alpha = resolveJbLayerOpacity(layer)
    }
    repeat(60) { tick ->
        val radians = (tick * 6f - 90f) * (PI.toFloat() / 180f)
        val major = tick % 5 == 0
        val outer = radius
        val inner = radius - if (major) 14f else 7f
        canvas.drawLine(
            center.x + cos(radians) * inner,
            center.y + sin(radians) * inner,
            center.x + cos(radians) * outer,
            center.y + sin(radians) * outer,
            paint
        )
    }
}

private fun drawJbShapeLayer(canvas: AndroidCanvas, face: JbWatchFace, layer: JbWatchLayer) {
    val center = jbLayerCenter(face, layer)
    val radius = layer.radius.takeIf { it > 0f }
        ?: (min(layer.width.takeIf { it > 0 } ?: 0, layer.height.takeIf { it > 0 } ?: 0) / 2f)
        .takeIf { it > 0f }
        ?: 24f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = parseJbColor(layer.color)
        alpha = resolveJbLayerOpacity(layer)
    }
    canvas.drawCircle(center.x, center.y, radius, paint)
}

private fun resolveJbText(
    raw: String,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    is24Hour: Boolean,
    animationElapsedMs: Long,
): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    // EEE / MMM 在部分 locale 会带尾句号（es "lun."、de "Mo."），
    // 统一 trimEnd('.') 避免表盘出现「周一.」式拖尾。
    val weekday = SimpleDateFormat("EEE", Locale.getDefault()).format(nowMillis).trimEnd('.')
    val monthName = SimpleDateFormat("MMM", Locale.getDefault()).format(nowMillis).trimEnd('.')
    val dayOfWeekZero = (calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
    val source = (resolveJbDayConditionalText(raw, dayOfWeekZero) ?: raw)
        .replace("{time}", "__JB_TIME__")
        .replace("{ddm}", "__JB_DDM__")
        .replace("{dmm}", "__JB_DMM__")
    val interpolated = JB_DYNAMIC_TAG_PATTERN.replace(source) { match ->
        resolveJbTag(
            tag = match.groupValues[1],
            now = calendar,
            dataSnapshot = dataSnapshot,
            animationElapsedMs = animationElapsedMs,
        ).renderedValue
    }
    return interpolated
        // JB exports use these two aliases even though they are not part of
        // the oldest standard vocabulary. Keep them deterministic here.
        .replace("__JB_DDM__", calendar.get(Calendar.MONTH).plus(1).toString().padStart(2, '0'))
        .replace("__JB_DMM__", monthName)
        .replace("__JB_TIME__", if (is24Hour) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(nowMillis)
        } else {
            SimpleDateFormat("hh:mm", Locale.getDefault()).format(nowMillis)
        })
        .replace("{dh12z}", WatchmakerTagResolver.resolve("dh12z", calendar, dataSnapshot).renderedValue)
        .replace("{ddw2}", weekday)
        .replace("{ddw}", weekday)
        .replace("{ddw0}", dayOfWeekZero.toString())
}

private fun resolveJbDayConditionalText(raw: String, dayOfWeekZero: Int): String? {
    if ("{ddw0}" !in raw || " and " !in raw || " or " !in raw) return null
    Regex("\\{ddw0\\}\\s*==\\s*(\\d+)\\s*and\\s*'([^']*)'")
        .findAll(raw)
        .forEach { match ->
            if (match.groupValues.getOrNull(1)?.toIntOrNull() == dayOfWeekZero) {
                return match.groupValues.getOrNull(2).orEmpty()
            }
        }
    return Regex("or\\s*'([^']*)'\\s*$").find(raw)?.groupValues?.getOrNull(1)
}

private fun resolveJbTextDisplaySize(layer: JbWatchLayer, text: String, paint: Paint): Pair<Float, Float> {
    val measuredWidth = paint.measureText(text).coerceAtLeast(1f)
    val measuredHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
    val baseWidth = layer.width.takeIf { it > 0 }?.toFloat() ?: measuredWidth
    val baseHeight = layer.height.takeIf { it > 0 }?.toFloat() ?: measuredHeight
    return applyJbLayerScale(layer, baseWidth, baseHeight)
}

private fun resolveJbRect(face: JbWatchFace, layer: JbWatchLayer, contentWidth: Int, contentHeight: Int): RectF {
    return resolveJbRect(face, layer, contentWidth.toFloat(), contentHeight.toFloat())
}

private fun resolveJbRect(face: JbWatchFace, layer: JbWatchLayer, contentWidth: Float, contentHeight: Float): RectF {
    val anchor = jbAlignmentAnchor(layer.alignment)
    val positionX = face.designWidth / 2f + layer.x
    val positionY = face.designHeight / 2f - layer.y
    val left = positionX - contentWidth * anchor.first
    val top = positionY - contentHeight * anchor.second
    return RectF(left, top, left + contentWidth, top + contentHeight)
}

private fun jbLayerCenter(face: JbWatchFace, layer: JbWatchLayer): Offset {
    return Offset(
        x = face.designWidth / 2f + layer.x,
        y = face.designHeight / 2f - layer.y
    )
}

private fun jbAlignmentAnchor(alignment: String): Pair<Float, Float> {
    return when (alignment.lowercase(Locale.ROOT)) {
        "cr" -> 1f to 0.5f
        "cl" -> 0f to 0.5f
        "br" -> 1f to 1f
        "bc" -> 0.5f to 1f
        "bl" -> 0f to 1f
        "tr" -> 1f to 0f
        "tc" -> 0.5f to 0f
        "tl" -> 0f to 0f
        else -> 0.5f to 0.5f
    }
}

private fun jbAlignmentHorizontal(alignment: String): Float {
    return jbAlignmentAnchor(alignment).first
}

private fun resolveJbHitContentSize(
    face: JbWatchFace,
    layer: JbWatchLayer,
    nowMillis: Long
): Pair<Float, Float> {
    if (layer.type == "text") {
        val fallbackWidth = max(layer.text.length.coerceAtLeast(4), 1) * layer.textSize * 0.56f
        val fallbackHeight = layer.textSize * 1.35f
        val width = layer.width.takeIf { it > 0 }?.toFloat() ?: fallbackWidth.coerceAtLeast(1f)
        val height = layer.height.takeIf { it > 0 }?.toFloat() ?: fallbackHeight.coerceAtLeast(1f)
        return applyJbLayerScale(layer, width, height)
    }
    val bitmapBounds = resolveJbWatchPath(
        rootDir = face.rootDir,
        layer = layer,
        nowMillis = nowMillis,
        assetRevision = face.assetRevision,
    )?.let(::readJbBitmapBounds)
    return resolveJbBitmapDisplaySize(layer, bitmapBounds?.first ?: 64, bitmapBounds?.second ?: 64)
}

private fun resolveJbBitmapDisplaySize(layer: JbWatchLayer, bitmapWidth: Int, bitmapHeight: Int): Pair<Float, Float> {
    val grid = if (layer.type == "image_cond") resolveJbGrid(layer.condGrid) else 1 to 1
    val sourceWidth = (bitmapWidth / grid.first.toFloat()).coerceAtLeast(1f)
    val sourceHeight = (bitmapHeight / grid.second.toFloat()).coerceAtLeast(1f)
    val aspect = sourceWidth / sourceHeight.coerceAtLeast(1f)
    val baseWidth = when {
        layer.width > 0 -> layer.width.toFloat()
        layer.height > 0 -> layer.height * aspect
        else -> sourceWidth
    }.coerceAtLeast(1f)
    val baseHeight = when {
        layer.height > 0 -> layer.height.toFloat()
        layer.width > 0 -> layer.width / aspect.coerceAtLeast(0.001f)
        else -> sourceHeight
    }.coerceAtLeast(1f)
    return applyJbLayerScale(layer, baseWidth, baseHeight)
}

private fun applyJbLayerScale(layer: JbWatchLayer, width: Float, height: Float): Pair<Float, Float> {
    val scaleX = (layer.animScaleX.takeIf { it > 0f } ?: 100f) / 100f
    val scaleY = (layer.animScaleY.takeIf { it > 0f } ?: 100f) / 100f
    return (width * scaleX).coerceAtLeast(1f) to (height * scaleY).coerceAtLeast(1f)
}

private fun rotateJbLayer(
    canvas: AndroidCanvas,
    rect: RectF,
    rotationRaw: String,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    animationElapsedMs: Long,
) {
    val rotation = evaluateJbNumericExpression(
        rotationRaw,
        nowMillis,
        dataSnapshot,
        animationElapsedMs,
    )
    if (rotation == 0f) return
    canvas.rotate(rotation, rect.centerX(), rect.centerY())
}

private fun evaluateJbNumericExpression(
    raw: String,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    animationElapsedMs: Long = 0L,
    fallback: Float = 0f,
): Float {
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    return WatchmakerExpressionEvaluator.evaluateNumber(
        raw = raw,
        fallback = fallback,
    ) { tag ->
        resolveJbTag(tag, calendar, dataSnapshot, animationElapsedMs)
    }
}

/** Shared WatchMaker text timeline used by both standard and JB renderers. */
internal fun jbTextAnimationFrame(layer: JbWatchLayer, elapsedMs: Long) =
    watchTextAnimationFrame(
        animIn = layer.animIn,
        animOut = layer.animOut,
        durationInSeconds = layer.durationInSeconds,
        durationOnSeconds = layer.durationOnSeconds,
        durationOutSeconds = layer.durationOutSeconds,
        durationOffSeconds = layer.durationOffSeconds,
        delayStartSeconds = layer.delayStartSeconds,
        repeatCount = layer.repeatCount,
        restartOnLoad = layer.restartOnLoad,
        elapsedMs = elapsedMs,
    )

private fun resolveJbAnimatedTransform(
    layer: JbWatchLayer,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    animationElapsedMs: Long,
): JbWatchLayer {
    if (layer.xExpression.isNullOrBlank() && layer.yExpression.isNullOrBlank()) return layer
    return layer.copy(
        x = evaluateJbNumericExpression(
            raw = layer.xExpression ?: layer.x.toString(),
            nowMillis = nowMillis,
            dataSnapshot = dataSnapshot,
            animationElapsedMs = animationElapsedMs,
            fallback = layer.x,
        ),
        y = evaluateJbNumericExpression(
            raw = layer.yExpression ?: layer.y.toString(),
            nowMillis = nowMillis,
            dataSnapshot = dataSnapshot,
            animationElapsedMs = animationElapsedMs,
            fallback = layer.y,
        ),
    )
}

private fun resolveJbDynamicPathLayer(
    layer: JbWatchLayer,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    animationElapsedMs: Long,
): JbWatchLayer {
    val rawPath = layer.path?.takeIf(String::isNotBlank) ?: return layer
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val resolvedPath = WatchmakerExpressionEvaluator.evaluateText(rawPath) { tag ->
        resolveJbTag(tag, calendar, dataSnapshot, animationElapsedMs)
    }.trim()
    if (resolvedPath == rawPath && layer.pathFrames == parsePaths(resolvedPath)) return layer
    return layer.copy(
        path = resolvedPath,
        pathFrames = parsePaths(resolvedPath),
    )
}

private fun resolveJbTag(
    tag: String,
    now: Calendar,
    dataSnapshot: WatchDataSnapshot,
    animationElapsedMs: Long,
): WatchTagResolution {
    resolveWatchCounterTag(tag, animationElapsedMs)?.let { value ->
        return WatchTagResolution.Resolved(value)
    }
    return WatchmakerTagResolver.resolve(tag, now, dataSnapshot)
}

private fun evaluateJbArithmetic(raw: String): Float {
    if (raw.isBlank()) return 0f
    val normalized = raw.replace(',', '.')
    normalized.toFloatOrNull()?.let { return it }
    val tokens = Regex("""[*/+-]?[^*/+-]+""").findAll(normalized).map { it.value }.toList()
    if (tokens.isEmpty()) return normalized.filter { it.isDigit() || it == '.' || it == '-' }
        .toFloatOrNull() ?: 0f
    var result = tokens.first().toFloatOrNull() ?: return 0f
    var cursor = tokens.first().length
    while (cursor < normalized.length) {
        val op = normalized[cursor]
        cursor += 1
        val nextStart = cursor
        while (cursor < normalized.length && normalized[cursor] !in charArrayOf('*', '/', '+', '-')) {
            cursor += 1
        }
        val number = normalized.substring(nextStart, cursor).toFloatOrNull() ?: continue
        result = when (op) {
            '*' -> result * number
            '/' -> if (number != 0f) result / number else result
            '+' -> result + number
            '-' -> result - number
            else -> result
        }
    }
    return result
}

private fun readJbBitmapBounds(file: File): Pair<Int, Int>? {
    if (!file.isFile) return null
    return JbWatchBitmapCache.bounds(file)
}

private data class JbWatchAssetIndex(
    val revision: Long,
    val byRelativePath: Map<String, File>,
    val byBaseName: Map<String, File>,
)

/**
 * Immutable per-parse file index. Dynamic paths are resolved from memory on
 * every frame; a miss is therefore a cheap negative lookup instead of a
 * walkTopDown() on the UI thread.
 */
private object JbWatchAssetIndexCache {
    private const val MAX_INDEXES = 12
    private const val MAX_INDEXED_FILES = 8_192
    private val indexes = LruCache<String, JbWatchAssetIndex>(MAX_INDEXES)
    private val latestRevisionByRoot = mutableMapOf<String, Long>()
    private var nextRevision = 0L

    fun prepare(rootDir: File): Long {
        val canonicalRoot = runCatching { rootDir.canonicalFile }.getOrElse { rootDir.absoluteFile }
        val files = runCatching {
            canonicalRoot.walkTopDown()
                .filter(File::isFile)
                .take(MAX_INDEXED_FILES)
                .mapNotNull { file ->
                    val relative = runCatching { file.relativeTo(canonicalRoot).invariantSeparatorsPath }
                        .getOrNull()
                        ?: return@mapNotNull null
                    normalizeJbAssetPath(relative)?.let { it to file }
                }
                .sortedBy { (relative, _) -> relative.length }
                .toList()
        }.getOrDefault(emptyList())
        val byRelative = LinkedHashMap<String, File>(files.size)
        val byBaseName = LinkedHashMap<String, File>(files.size)
        files.forEach { (relative, file) ->
            byRelative.putIfAbsent(relative, file)
            byBaseName.putIfAbsent(relative.substringAfterLast('/'), file)
        }
        return synchronized(this) {
            val revision = ++nextRevision
            val rootKey = canonicalRoot.absolutePath
            indexes.put(
                indexKey(rootKey, revision),
                JbWatchAssetIndex(revision, byRelative, byBaseName),
            )
            latestRevisionByRoot[rootKey] = revision
            revision
        }
    }

    fun latestRevision(rootDir: File): Long = synchronized(this) {
        latestRevisionByRoot[rootDir.absoluteFile.path]
            ?: latestRevisionByRoot[runCatching { rootDir.canonicalPath }.getOrDefault(rootDir.absolutePath)]
            ?: 0L
    }

    fun find(rootDir: File, revision: Long, rawPath: String): File? {
        val normalized = normalizeJbAssetPath(rawPath) ?: return null
        val rootKey = runCatching { rootDir.canonicalPath }.getOrDefault(rootDir.absolutePath)
        val index = synchronized(this) { indexes.get(indexKey(rootKey, revision)) } ?: return null
        val withoutDot = normalized.removePrefix(".")
        val variants = buildList {
            add(normalized)
            if (withoutDot != normalized) add(withoutDot)
            if (!normalized.startsWith("images/")) add("images/$normalized")
            if (!normalized.startsWith("fonts/")) add("fonts/$normalized")
        }
        variants.distinct().forEach { variant ->
            index.byRelativePath[variant]?.takeIf(File::isFile)?.let { return it }
        }
        return index.byBaseName[normalized.substringAfterLast('/')]
            ?.takeIf(File::isFile)
            ?: index.byBaseName[withoutDot.substringAfterLast('/')]?.takeIf(File::isFile)
    }

    /**
     * Finds one safe installed asset for a dynamic path template. This is used
     * only during IO prewarm: the live frame still resolves the exact tag value.
     */
    fun findDynamicCandidate(rootDir: File, revision: Long, rawPattern: String): File? {
        if ('{' !in rawPattern || '}' !in rawPattern || rawPattern.length > 1_024) return null
        val normalized = normalizeJbAssetPath(rawPattern) ?: return null
        val rootKey = runCatching { rootDir.canonicalPath }.getOrDefault(rootDir.absolutePath)
        val index = synchronized(this) { indexes.get(indexKey(rootKey, revision)) } ?: return null
        val withoutDot = normalized.removePrefix(".")
        val patterns = linkedSetOf(normalized, withoutDot).apply {
            if (!normalized.startsWith("images/")) add("images/$normalized")
        }
        patterns.forEach { pattern ->
            val matcher = jbDynamicAssetPathRegex(pattern) ?: return@forEach
            index.byRelativePath.entries.firstOrNull { (path, file) ->
                file.isFile && matcher.matches(path)
            }?.value?.let { return it }
        }
        val basenameMatcher = jbDynamicAssetPathRegex(withoutDot.substringAfterLast('/'))
            ?: return null
        return index.byBaseName.entries.firstOrNull { (name, file) ->
            file.isFile && basenameMatcher.matches(name)
        }?.value
    }

    private fun indexKey(rootPath: String, revision: Long): String = "$rootPath|$revision"
}

private fun jbDynamicAssetPathRegex(normalizedPattern: String): Regex? {
    val matches = JB_DYNAMIC_TAG_PATTERN.findAll(normalizedPattern).toList()
    if (matches.isEmpty()) return null
    val regex = StringBuilder("^")
    var cursor = 0
    matches.forEach { match ->
        regex.append(Regex.escape(normalizedPattern.substring(cursor, match.range.first)))
        regex.append("[^/]+")
        cursor = match.range.last + 1
    }
    regex.append(Regex.escape(normalizedPattern.substring(cursor)))
    regex.append('$')
    return runCatching { Regex(regex.toString()) }.getOrNull()
}

private fun normalizeJbAssetPath(rawPath: String): String? {
    val normalized = rawPath.trim()
        .replace('\\', '/')
        .removePrefix("./")
        .trimStart('/')
        .lowercase(Locale.ROOT)
    if (normalized.isBlank()) return null
    val segments = normalized.split('/')
    if (segments.any { it == ".." }) return null
    return segments.filter { it.isNotBlank() && it != "." }.joinToString("/")
        .takeIf(String::isNotBlank)
}

private fun resolveJbWatchPath(
    rootDir: File,
    rawPath: String?,
    nowMillis: Long,
    assetRevision: Long = JbWatchAssetIndexCache.latestRevision(rootDir),
): File? {
    return resolveJbWatchPath(
        rootDir,
        JbWatchLayer(type = "image", path = rawPath),
        nowMillis,
        assetRevision,
    )
}

private fun resolveJbWatchPath(
    rootDir: File,
    layer: JbWatchLayer,
    nowMillis: Long,
    assetRevision: Long = JbWatchAssetIndexCache.latestRevision(rootDir),
): File? {
    val rawPath = layer.path
    val normalized = rawPath?.trim().orEmpty()
    if (normalized.isBlank()) return null
    val parsedPaths = parsePaths(normalized)
    val candidates = if (parsedPaths.size > 1) {
        val frameDelayMs = layer.gifDelay.takeIf { it > 0 } ?: 50
        val frameIndex = jbAnimationFrameIndex(
            frameCount = parsedPaths.size,
            timelineMs = nowMillis,
            delayMs = frameDelayMs.toLong(),
        )
        listOf(parsedPaths[frameIndex])
    } else {
        parsedPaths.ifEmpty { listOf(normalized) }
    }
    candidates.forEach { candidate ->
        JbWatchAssetIndexCache.find(rootDir, assetRevision, candidate)?.let { return it }
    }
    return null
}

private fun parseJbColor(raw: String): Int {
    val hex = raw.trim().removePrefix("#").takeIf { it.isNotBlank() } ?: "ffffff"
    return runCatching { android.graphics.Color.parseColor("#$hex") }.getOrElse { android.graphics.Color.WHITE }
}

private fun shouldDisplayLayer(
    layer: JbWatchLayer,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
    animationElapsedMs: Long = 0L,
): Boolean {
    val expr = layer.condValue?.takeIf { it.isNotBlank() } ?: return true
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val result = WatchmakerExpressionEvaluator.evaluateNumber(expr, Float.NaN) { tag ->
        resolveJbTag(tag, calendar, dataSnapshot, animationElapsedMs)
    }
    return result.isNaN() || result != 0f
}

private fun evaluateWeatherIndex(expr: String?, weatherCode: String): Int {
    val value = expr?.takeIf { it.isNotBlank() } ?: return 0
    val code = weatherCode.lowercase(Locale.ROOT)
    Regex("'\\{wci\\}'\\s*==\\s*'([^']+)'\\s*and\\s*(\\d+)").findAll(value).forEach { match ->
        val codeMatch = match.groupValues.getOrNull(1)?.lowercase(Locale.ROOT)
        val numberMatch = match.groupValues.getOrNull(2)?.toIntOrNull()
        if (codeMatch == code && numberMatch != null) {
            return (numberMatch - 1).coerceAtLeast(0)
        }
    }
    if ("and 1" in value) return 0
    return 0
}

private fun drawConditionBitmap(
    canvas: AndroidCanvas,
    bitmap: Bitmap,
    dst: RectF,
    condGrid: String?,
    index: Int,
    paint: Paint?
) {
    val (cols, rows) = resolveJbGrid(condGrid)
    if (cols == 1 && rows == 1) {
        canvas.drawBitmap(bitmap, null, dst, paint)
        return
    }
    val cellWidth = bitmap.width / cols
    val cellHeight = bitmap.height / rows
    val safeIndex = index.coerceIn(0, cols * rows - 1)
    val row = safeIndex / cols
    val col = safeIndex % cols
    val src = Rect(
        col * cellWidth,
        row * cellHeight,
        (col + 1) * cellWidth,
        (row + 1) * cellHeight
    )
    canvas.drawBitmap(bitmap, src, dst, paint)
}

private fun resolveJbGrid(raw: String?): Pair<Int, Int> {
    val grid = raw?.lowercase(Locale.ROOT)?.split('x') ?: emptyList()
    val cols = grid.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val rows = grid.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    return cols to rows
}

private fun resolveJbLayerOpacity(layer: JbWatchLayer): Int {
    return layer.opacity.coerceIn(0, 100) * 255 / 100
}

private fun parsePaths(raw: String?): List<String> {
    return parseWatchGifPaths(raw)
}

/**
 * JB exports sometimes keep a frame sequence on a plain `image` layer.  Treat
 * the content as authoritative instead of relying only on the type label so
 * those old faces keep receiving frame ticks after the face-switch transition
 * has finished.
 */
internal fun jbLayerRequiresFrameTicks(layer: JbWatchLayer): Boolean {
    if (layer.pathFrames.size > 1) return true
    if (watchLayerRequiresFrameTicks(
        type = layer.type,
        attributes = mapOf(
            "path" to layer.path.orEmpty(),
            "anim_in" to layer.animIn.orEmpty(),
            "anim_out" to layer.animOut.orEmpty(),
        ),
    )) return true
    if (layer.tapAction?.startsWith("script:") == true) return true
    return sequenceOf(
        layer.text,
        layer.condValue.orEmpty(),
        layer.rotation,
        layer.angle,
        layer.xExpression.orEmpty(),
        layer.yExpression.orEmpty(),
        layer.path.orEmpty(),
    ).any { value -> WatchmakerTagResolver.requiresFastTicks(value) }
}

/**
 * Keep the division and modulo in Long space.  The previous JB implementation
 * converted the epoch-sized frame counter to Int first; for a large range of
 * gif_delay values it overflowed negative and was then clamped to zero, which
 * locked the animation on its first frame for years at a time.
 */
internal fun jbAnimationFrameIndex(
    frameCount: Int,
    timelineMs: Long,
    delayMs: Long,
): Int = watchGifFrameIndex(frameCount, timelineMs, delayMs)

private fun handleJbWatchTap(
    context: Context,
    face: JbWatchFace,
    point: Offset,
    viewWidth: Float,
    viewHeight: Float,
    nowMillis: Long,
    dataSnapshot: WatchDataSnapshot,
) {
    val canvasWidth = face.designWidth.coerceAtLeast(1)
    val canvasHeight = face.designHeight.coerceAtLeast(1)
    val scaleX = viewWidth / canvasWidth
    val scaleY = viewHeight / canvasHeight
    val scale = min(scaleX, scaleY)
    val offsetX = (viewWidth - canvasWidth * scale) / 2f
    val offsetY = (viewHeight - canvasHeight * scale) / 2f
    val localX = (point.x - offsetX) / scale
    val localY = (point.y - offsetY) / scale
    val animationElapsedMs = (SystemClock.uptimeMillis() - face.animationStartedAtUptimeMs)
        .coerceAtLeast(0L)
    face.layers
        .asReversed()
        .firstOrNull { layer ->
            val animatedLayer = resolveJbAnimatedTransform(
                layer,
                nowMillis,
                dataSnapshot,
                animationElapsedMs,
            )
            val hitSize = resolveJbHitContentSize(face, animatedLayer, animationElapsedMs)
            shouldDisplayLayer(animatedLayer, nowMillis, dataSnapshot, animationElapsedMs) &&
                !animatedLayer.tapAction.isNullOrBlank() &&
                resolveJbRect(
                    face = face,
                    layer = animatedLayer,
                    contentWidth = hitSize.first,
                    contentHeight = hitSize.second
                ).contains(localX, localY)
        }
        ?.tapAction
        ?.let { action -> launchJbWatchTapAction(context, action, face.rootDir) }
}

private fun launchJbWatchTapAction(context: Context, action: String, rootDir: File): Boolean {
    val normalized = normalizeJbTapAction(action)
    return when {
        normalized.startsWith("widget_calendar") -> {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_CALENDAR)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.isSuccess
        }
        normalized.startsWith("widget_weather") || normalized.startsWith("m_update_weather") -> {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_WEATHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.isSuccess
        }
        normalized.startsWith("script:") -> {
            playJbWatchScript(rootDir, normalized.removePrefix("script:").trim())
            true
        }
        else -> {
            parseJbWatchAppAction(normalized)?.let { appAction ->
                return launchJbWatchAppAction(context, appAction)
            }
            val parts = normalized.split('/')
            if (parts.size == 2) {
                val packageName = parts[0].trim()
                val className = parts[1].trim().let { value ->
                    if (value.startsWith(".")) packageName + value else value
                }
                launchJbWatchAppAction(context, JbWatchAppAction(packageName, className))
            } else {
                false
            }
        }
    }
}

private data class JbWatchAppAction(
    val packageName: String,
    val className: String
)

private fun parseJbWatchAppAction(action: String): JbWatchAppAction? {
    val stripped = when {
        action.startsWith("w_app:") -> action.removePrefix("w_app:")
        action.startsWith("m_app:") -> action.removePrefix("m_app:")
        else -> return null
    }
    val parts = stripped.split('`').map { it.trim() }.filter { it.isNotBlank() }
    if (parts.size < 3) return null
    val packageName = parts[1]
    val className = parts[2]
    if (packageName.isBlank() || className.isBlank()) return null
    return JbWatchAppAction(
        packageName = packageName,
        className = if (className.startsWith(".")) packageName + className else className
    )
}

private fun launchJbWatchAppAction(context: Context, appAction: JbWatchAppAction): Boolean {
    // 产品决策（柚子皮 2026-07-26）：保留组件级启动。表盘点击直达特定
    // 页面（如音乐播放页、健康数据页）是 jbwatch 表盘的实际功能需求，
    // 审查报告 P1-22 建议的"仅启动器入口"收敛在这里不适用——不要在
    // 后续安全清理中把它当作疏漏再次收掉。exported 组件本就允许第三方
    // 拉起，风险边界与普通启动器快捷方式一致。
    val launchedComponent = runCatching {
        context.startActivity(Intent().apply {
            component = ComponentName(appAction.packageName, appAction.className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    }.getOrDefault(false)
    if (launchedComponent) return true
    return context.packageManager.getLaunchIntentForPackage(appAction.packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }?.let { intent ->
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    } == true
}

private fun normalizeJbTapAction(raw: String): String {
    return raw
        .replace("x)f8�_weatjr", "widget_weather")
        .replace("m_upfte_weatjr", "m_update_weather")
        .replace("scrn0�", "script:")
        .replace("&ars;", "&apos;")
        .replace("weatjr", "weather")
        .replace("updte", "update")
        .replace(Regex("[^\\x09\\x0A\\x0D\\x20-\\x7E]"), "")
        .trim()
}

private fun playJbWatchScript(rootDir: File, script: String) {
    val match = Regex("wm_sfx\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)").find(script) ?: return
    val soundName = match.groupValues.getOrNull(1)?.trim().orEmpty()
    if (soundName.isBlank()) return
    val candidate = listOf(
        File(rootDir, "sfx/$soundName.mp3"),
        File(rootDir, "sfx/$soundName.wav"),
        File(rootDir, "sfx/$soundName.ogg")
    ).firstOrNull { it.isFile } ?: return
    runCatching {
        MediaPlayer().apply {
            setDataSource(candidate.absolutePath)
            setOnCompletionListener {
                it.release()
            }
            prepare()
            start()
        }
    }
}

private data class JbWatchVisualFrame(
    val bitmap: Bitmap?,
    val movie: Movie?,
) {
    val width: Int = movie?.width() ?: bitmap?.width ?: 0
    val height: Int = movie?.height() ?: bitmap?.height ?: 0

    companion object {
        fun decode(file: File): JbWatchVisualFrame? {
            val movie = JbWatchMovieCache.get(file)
            if (movie != null && movie.width() > 0 && movie.height() > 0) {
                return JbWatchVisualFrame(bitmap = null, movie = movie)
            }
            val bitmap = JbWatchBitmapCache.get(file)
                ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
                ?: return null
            return JbWatchVisualFrame(bitmap = bitmap, movie = null)
        }
    }
}

/**
 * Parser runs on Dispatchers.IO, so prime one valid frame per image layer
 * before publishing the face. Dynamic templates resolve against the initial
 * clock snapshot first, then fall back to one matching installed asset. Raw
 * GIFs are decoded too; the UI receives a ready Movie instead of a black first
 * frame while switching faces.
 */
private fun prewarmJbInitialVisualFrames(face: JbWatchFace) {
    val nowMillis = System.currentTimeMillis()
    val emptySnapshot = WatchDataSnapshot()
    face.layers.forEachIndexed { layerIndex, layer ->
        if (layer.type !in setOf("image", "image_gif", "image_cond")) return@forEachIndexed
        val resolvedLayer = resolveJbDynamicPathLayer(
            layer = layer,
            nowMillis = nowMillis,
            dataSnapshot = emptySnapshot,
            animationElapsedMs = 0L,
        )
        val paths = linkedSetOf<String>().apply {
            addAll(parsePaths(resolvedLayer.path))
            addAll(parsePaths(layer.path))
        }
        paths.asSequence()
            .mapNotNull { path ->
                JbWatchAssetIndexCache.find(face.rootDir, face.assetRevision, path)
                    ?: JbWatchAssetIndexCache.findDynamicCandidate(
                        face.rootDir,
                        face.assetRevision,
                        path,
                    )
            }
            .mapNotNull { file -> JbWatchVisualFrame.decode(file) }
            .firstOrNull()
            ?.let { frame ->
                JbWatchLastVisualFrameCache.put(jbLastVisualFrameKey(face, layerIndex), frame)
            }
    }
}

private fun jbLastVisualFrameKey(face: JbWatchFace, layerIndex: Int): String = buildString {
    append(face.rootDir.absolutePath)
    append(':')
    append(face.pxmlFile.lastModified())
    append(':')
    append(face.pxmlFile.length())
    append(':')
    append(face.assetRevision)
    append(':')
    append(layerIndex)
}

/**
 * A dynamic JB path can briefly point at a frame that has not finished being
 * extracted yet. Keep the last successfully decoded visual for that exact
 * face/layer so the watch background never falls through to its black root
 * colour between two valid frames.
 */
private object JbWatchLastVisualFrameCache {
    private const val MAX_FRAMES = 48
    private val cache = LruCache<String, JbWatchVisualFrame>(MAX_FRAMES)

    fun get(key: String): JbWatchVisualFrame? = synchronized(cache) {
        cache.get(key)?.takeIf { frame ->
            frame.width > 0 &&
                frame.height > 0 &&
                (frame.bitmap?.isRecycled != true)
        }
    }

    fun put(key: String, frame: JbWatchVisualFrame) {
        synchronized(cache) {
            cache.put(key, frame)
        }
    }
}

private object JbWatchBitmapCache {
    private const val MAX_CACHE_KB = 24 * 1024
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(file: File): Bitmap? {
        if (!file.isFile) return null
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        cache.get(key)?.let { return it }
        val bitmap = decodeBitmap(file) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    fun bounds(file: File): Pair<Int, Int>? {
        if (!file.isFile) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeBitmap(file, options)
        val width = options.outWidth.takeIf { it > 0 } ?: return null
        val height = options.outHeight.takeIf { it > 0 } ?: return null
        return width to height
    }

    private fun decodeBitmap(
        file: File,
        options: BitmapFactory.Options? = null
    ): Bitmap? {
        BitmapFactory.decodeFile(file.absolutePath, options)?.let { return it }
        if (!file.extension.equals("ppng", ignoreCase = true) &&
            !file.extension.equals("pjpg", ignoreCase = true)
        ) {
            return null
        }
        val encoded = runCatching { file.readBytes() }.getOrNull() ?: return null
        val decoded = ByteArray(encoded.size) { index ->
            (encoded[index].toInt() xor JB_PROTECTED_IMAGE_KEY[index % JB_PROTECTED_IMAGE_KEY.size].toInt()).toByte()
        }
        return BitmapFactory.decodeByteArray(decoded, 0, decoded.size, options)
    }
}

private object JbWatchMovieCache {
    private const val MAX_MOVIES = 8
    private val cache = LruCache<String, Movie>(MAX_MOVIES)

    @Synchronized
    fun get(file: File): Movie? {
        if (!file.isFile || !file.extension.equals("gif", ignoreCase = true)) return null
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        cache.get(key)?.let { return it }
        val movie = runCatching { Movie.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        cache.put(key, movie)
        return movie
    }
}

private object JbWatchTypefaceCache {
    private val cache = mutableMapOf<String, android.graphics.Typeface?>()

    fun get(rootDir: File, assetRevision: Long, fontName: String?): android.graphics.Typeface? {
        val normalizedName = fontName?.trim().orEmpty()
        if (normalizedName.isBlank()) return android.graphics.Typeface.DEFAULT
        val key = "${rootDir.absolutePath}:$assetRevision:$normalizedName"
        synchronized(cache) {
            cache[key]?.let { return it }
            val candidates = listOf(
                "fonts/$normalizedName",
                normalizedName,
                "fonts/$normalizedName.ttf",
                "$normalizedName.ttf",
                "fonts/$normalizedName.otf",
                "$normalizedName.otf"
            )
            val file = candidates.firstNotNullOfOrNull { candidate ->
                resolveJbWatchPath(
                    rootDir,
                    JbWatchLayer(type = "image", path = candidate),
                    0L,
                    assetRevision,
                )
            }
            val typeface = file?.takeIf { it.isFile }?.let { runCatching { android.graphics.Typeface.createFromFile(it) }.getOrNull() }
                ?: android.graphics.Typeface.DEFAULT
            cache[key] = typeface
            return typeface
        }
    }
}
