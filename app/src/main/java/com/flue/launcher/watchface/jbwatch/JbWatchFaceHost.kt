package com.flue.launcher.watchface.jbwatch

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.BatteryManager
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
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
import kotlin.math.max
import kotlin.math.min

private const val JB_LAYOUT_CANVAS_WIDTH = 512
private const val JB_LAYOUT_CANVAS_HEIGHT = 512
private val JB_PROTECTED_IMAGE_KEY = byteArrayOf(0x53, 0x57, 0x48, 0x6E, 0x2D)
private const val JB_STANDARD_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private const val JB_CUSTOM_BASE64 = "ABCgEFGHIJK4MNOPQRSTUVWXYZabcdefDhijklmnopqrstuvwxyz0123L56789+/"

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
    val pathFrames: List<String> = emptyList()
)

data class JbWatchFace(
    val root: JbWatchRoot,
    val layers: List<JbWatchLayer>,
    val rootDir: File,
    val xmlFile: File,
    val pxmlFile: File,
    val designWidth: Int,
    val designHeight: Int
) {
    val needsHighRefresh: Boolean = layers.any { layer ->
        layer.type == "image_gif" || layer.tapAction?.startsWith("script:") == true || layer.rotation != "0"
    }
}

object JbWatchParser {
    fun parse(rootDir: File): JbWatchFace {
        val watchXml = File(rootDir, "watch.xml")
        val pxmlFile = File(rootDir, "watch.pxml")
        require(watchXml.isFile && pxmlFile.isFile) { "缺少 watch.xml / watch.pxml" }
        val root = parseRoot(watchXml)
        val layers = parseLayers(pxmlFile, rootDir)
        return JbWatchFace(
            root = root,
            layers = layers,
            rootDir = rootDir,
            xmlFile = watchXml,
            pxmlFile = pxmlFile,
            designWidth = JB_LAYOUT_CANVAS_WIDTH,
            designHeight = JB_LAYOUT_CANVAS_HEIGHT
        )
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
                        type = element.getAttribute("type").ifBlank { "image" },
                        name = sanitizeVisibleText(element.getAttribute("name")).takeIf { it.isNotBlank() },
                        x = element.getAttribute("x").toFloatOrZero(),
                        y = element.getAttribute("y").toFloatOrZero(),
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
                        weatherNight = sanitizeVisibleText(element.getAttribute("weather_night")).takeIf { it.isNotBlank() }
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
    descriptor: LunchWatchFaceDescriptor,
    isFaceVisible: Boolean,
    refreshToken: Int,
    onLoadFailure: (LunchWatchFaceDescriptor, Throwable) -> Unit,
    onLongPress: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
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
    val batteryLevel = rememberJbWatchBattery(context)
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val latestFace by rememberUpdatedState(face)
    val latestNowMillis by rememberUpdatedState(nowMillis)
    val latestBatteryLevel by rememberUpdatedState(batteryLevel)
    LaunchedEffect(isFaceVisible, face?.needsHighRefresh, descriptor.stableKey, refreshToken) {
        while (isFaceVisible) {
            nowMillis = System.currentTimeMillis()
            delay(if (face?.needsHighRefresh == true) 80L else 1_000L)
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
                                batteryLevel = latestBatteryLevel
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
            face != null -> {
                val backgroundColor = runCatching {
                    android.graphics.Color.parseColor("#${face!!.root.bgColor.trim().ifBlank { "000000" }}")
                }.getOrDefault(android.graphics.Color.BLACK)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.drawColor(backgroundColor)
                        drawJbFace(
                            canvas = native,
                            face = face!!,
                            nowMillis = nowMillis,
                            batteryLevel = batteryLevel,
                            width = size.width.toInt(),
                            height = size.height.toInt(),
                            is24Hour = is24Hour
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

@Composable
private fun rememberJbWatchBattery(context: Context): Int {
    var level by remember { mutableStateOf(100) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (rawLevel >= 0 && scale > 0) {
                    level = (rawLevel * 100 / scale).coerceIn(0, 100)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return level
}

private fun drawJbFace(
    canvas: AndroidCanvas,
    face: JbWatchFace,
    nowMillis: Long,
    batteryLevel: Int,
    width: Int,
    height: Int,
    is24Hour: Boolean
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
    face.layers.forEach { layer ->
        drawJbLayer(canvas, face, layer, nowMillis, batteryLevel, is24Hour)
    }
    canvas.restore()
}

private fun drawJbLayer(
    canvas: AndroidCanvas,
    face: JbWatchFace,
    layer: JbWatchLayer,
    nowMillis: Long,
    batteryLevel: Int,
    is24Hour: Boolean
) {
    when (layer.type) {
        "image", "image_gif", "image_cond" -> {
            drawJbBitmapLayer(canvas, face, layer, nowMillis, batteryLevel)
        }
        "text" -> drawJbTextLayer(canvas, face, layer, nowMillis, batteryLevel, is24Hour)
    }
}

private fun drawJbBitmapLayer(
    canvas: AndroidCanvas,
    face: JbWatchFace,
    layer: JbWatchLayer,
    nowMillis: Long,
    batteryLevel: Int
) {
    if (!shouldDisplayLayer(layer, nowMillis, batteryLevel)) return
    val file = resolveJbWatchPath(face.rootDir, layer, nowMillis) ?: return
    val bitmap = JbWatchBitmapCache.get(file) ?: return
    val displaySize = resolveJbBitmapDisplaySize(layer, bitmap.width, bitmap.height)
    val rect = resolveJbRect(face, layer, displaySize.first, displaySize.second)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = resolveJbLayerOpacity(layer)
    }
    if (layer.type == "image_cond" && !layer.condGrid.isNullOrBlank()) {
        val index = evaluateWeatherIndex(layer.condValue, weatherCode = "01d")
        drawConditionBitmap(canvas, bitmap, rect, layer.condGrid, index, paint)
    } else {
        canvas.drawBitmap(bitmap, null, rect, paint)
    }
}

private fun drawJbTextLayer(
    canvas: AndroidCanvas,
    face: JbWatchFace,
    layer: JbWatchLayer,
    nowMillis: Long,
    batteryLevel: Int,
    is24Hour: Boolean
) {
    if (!shouldDisplayLayer(layer, nowMillis, batteryLevel)) return
    val text = resolveJbText(layer.text, nowMillis, batteryLevel, is24Hour)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = parseJbColor(layer.color)
        textSize = layer.textSize
        textAlign = when (jbAlignmentHorizontal(layer.alignment)) {
            0f -> Paint.Align.LEFT
            1f -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        alpha = resolveJbLayerOpacity(layer)
        typeface = JbWatchTypefaceCache.get(face.rootDir, layer.font)
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
        canvas.drawText(text, x, y, outlinePaint)
    }
    canvas.drawText(text, x, y, paint)
}

private fun resolveJbText(raw: String, nowMillis: Long, batteryLevel: Int, is24Hour: Boolean): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val hour12 = calendar.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    val weekday = SimpleDateFormat("EEE", Locale.getDefault()).format(nowMillis)
    val monthName = SimpleDateFormat("MMM", Locale.getDefault()).format(nowMillis)
    val dayOfWeekZero = (calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
    val source = resolveJbDayConditionalText(raw, dayOfWeekZero) ?: raw
    return source
        .replace("{dh23z}", calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'))
        .replace("{dh12z}", hour12.toString().padStart(2, '0'))
        .replace("{dmz}", calendar.get(Calendar.MINUTE).toString().padStart(2, '0'))
        .replace("{dsz}", calendar.get(Calendar.SECOND).toString().padStart(2, '0'))
        .replace("{dd}", calendar.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0'))
        .replace("{ddm}", calendar.get(Calendar.MONTH).plus(1).toString().padStart(2, '0'))
        .replace("{ddw2}", weekday)
        .replace("{ddw}", weekday)
        .replace("{ddw0}", dayOfWeekZero.toString())
        .replace("{dmm}", monthName)
        .replace("{wtd}", batteryLevel.toString())
        .replace("{pblp}", batteryLevel.toString())
        .replace("{blp}", batteryLevel.toString())
        .replace("{bl}", batteryLevel.toString())
        .replace("{ssc}", "0")
        .replace("{shr}", "--")
        .replace("{wtld}", "--")
        .replace("{wthd}", "--")
        .replace("{wt}", "--")
        .replace("{dampm}", if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
        .replace("{time}", if (is24Hour) SimpleDateFormat("HH:mm", Locale.getDefault()).format(nowMillis) else SimpleDateFormat("hh:mm", Locale.getDefault()).format(nowMillis))
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
    val bitmapBounds = resolveJbWatchPath(face.rootDir, layer, nowMillis)?.let(::readJbBitmapBounds)
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

private fun readJbBitmapBounds(file: File): Pair<Int, Int>? {
    if (!file.isFile) return null
    return JbWatchBitmapCache.bounds(file)
}

private fun resolveJbWatchPath(rootDir: File, rawPath: String?, nowMillis: Long): File? {
    return resolveJbWatchPath(rootDir, JbWatchLayer(type = "image", path = rawPath), nowMillis)
}

private fun resolveJbWatchPath(rootDir: File, layer: JbWatchLayer, nowMillis: Long): File? {
    val rawPath = layer.path
    val normalized = rawPath?.trim().orEmpty()
    if (normalized.isBlank()) return null
    val candidates = if (layer.pathFrames.isNotEmpty()) {
        val frameDelayMs = layer.gifDelay.takeIf { it > 0 } ?: 120
        val frameStep = ((nowMillis / frameDelayMs).toInt()).coerceAtLeast(0)
        listOf(layer.pathFrames[frameStep.mod(layer.pathFrames.size)])
    } else {
        normalized.split('`').map { it.trim() }.filter { it.isNotBlank() }
    }
    candidates.forEach { candidate ->
        val normalizedCandidate = candidate.replace('\\', '/')
        val candidateVariants = listOf(
            normalizedCandidate,
            normalizedCandidate.removePrefix(".")
        ).distinct()
        candidateVariants.forEach { variant ->
            val direct = File(rootDir, variant)
            if (direct.isFile) return direct
            val inImages = File(rootDir, "images/$variant")
            if (inImages.isFile) return inImages
            val inFonts = File(rootDir, "fonts/$variant")
            if (inFonts.isFile) return inFonts
        }
    }
    return rootDir.walkTopDown().firstOrNull { file ->
        val name = candidates.firstOrNull()?.substringAfterLast('/') ?: ""
        file.isFile && (
            file.name.equals(name, ignoreCase = true) ||
                file.name.equals(name.removePrefix("."), ignoreCase = true)
            )
    }
}

private fun parseJbColor(raw: String): Int {
    val hex = raw.trim().removePrefix("#").takeIf { it.isNotBlank() } ?: "ffffff"
    return runCatching { android.graphics.Color.parseColor("#$hex") }.getOrElse { android.graphics.Color.WHITE }
}

private fun shouldDisplayLayer(layer: JbWatchLayer, nowMillis: Long, batteryLevel: Int): Boolean {
    val expr = layer.condValue?.takeIf { it.isNotBlank() } ?: return true
    return when {
        "{wci}" in expr -> evaluateWeatherCondition(expr, weatherCode = "01d")
        "{pblp}" in expr || "{wtd}" in expr -> batteryLevel >= 0
        else -> true
    }
}

private fun evaluateWeatherCondition(expr: String, weatherCode: String): Boolean {
    return evaluateWeatherIndex(expr, weatherCode) >= 0
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
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) return emptyList()
    return normalized
        .split('`', ',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun handleJbWatchTap(
    context: Context,
    face: JbWatchFace,
    point: Offset,
    viewWidth: Float,
    viewHeight: Float,
    nowMillis: Long,
    batteryLevel: Int
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
    face.layers
        .asReversed()
        .firstOrNull { layer ->
            val hitSize = resolveJbHitContentSize(face, layer, nowMillis)
                shouldDisplayLayer(layer, nowMillis, batteryLevel) &&
                !layer.tapAction.isNullOrBlank() &&
                resolveJbRect(
                    face = face,
                    layer = layer,
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
            val parts = normalized.split('/')
            if (parts.size == 2) {
                runCatching {
                    context.startActivity(Intent().apply {
                        component = ComponentName(parts[0], parts[1])
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.isSuccess
            } else {
                false
            }
        }
    }
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

private object JbWatchTypefaceCache {
    private val cache = mutableMapOf<String, android.graphics.Typeface?>()

    fun get(rootDir: File, fontName: String?): android.graphics.Typeface? {
        val key = fontName?.trim().orEmpty()
        if (key.isBlank()) return android.graphics.Typeface.DEFAULT
        synchronized(cache) {
            cache[key]?.let { return it }
            val candidates = listOf(
                "fonts/$key",
                key,
                "fonts/$key.ttf",
                "$key.ttf",
                "fonts/$key.otf",
                "$key.otf"
            )
            val file = candidates.firstNotNullOfOrNull { candidate ->
                resolveJbWatchPath(rootDir, JbWatchLayer(type = "image", path = candidate), 0L)
            }
            val typeface = file?.takeIf { it.isFile }?.let { runCatching { android.graphics.Typeface.createFromFile(it) }.getOrNull() }
                ?: android.graphics.Typeface.DEFAULT
            cache[key] = typeface
            return typeface
        }
    }
}
