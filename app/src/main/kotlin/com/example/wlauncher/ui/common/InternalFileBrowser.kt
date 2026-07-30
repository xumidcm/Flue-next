package com.flue.launcher.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.flue.launcher.R
import com.flue.launcher.ui.settings.miuix.MiuixIcons
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Request handled by [InternalFileBrowserHost]. Extensions omit the leading dot. */
data class InternalFileBrowserRequest(
    val title: String,
    val extensions: Set<String>,
    val allowMultiple: Boolean = false,
    val selectDirectory: Boolean = false,
    val onSelected: (List<Uri>) -> Unit,
) {
    val normalizedExtensions: Set<String> = normalizeInternalFileExtensions(extensions)
}

/**
 * Wear-friendly full-screen browser backed by direct filesystem access.
 * Android 11+ uses the user-approved all-files grant because many Wear OS
 * builds do not provide SAF. Older releases use READ_EXTERNAL_STORAGE.
 */
@Composable
fun InternalFileBrowserHost(
    request: InternalFileBrowserRequest?,
    onDismiss: () -> Unit,
) {
    if (request == null) return

    val context = LocalContext.current
    var permissionCheckRevision by remember(request) { mutableIntStateOf(0) }
    val hasAccess = remember(permissionCheckRevision, request) {
        context.hasInternalBrowserStorageAccess()
    }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) {
        permissionCheckRevision++
    }
    val manageFilesLauncher = rememberLauncherForActivityResult(StartActivityForResult()) {
        permissionCheckRevision++
    }

    if (!hasAccess) {
        InternalFileBrowserPermissionDialog(
            onDismiss = onDismiss,
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val appIntent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    val intent = if (appIntent.resolveActivity(context.packageManager) != null) {
                        appIntent
                    } else {
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    }
                    manageFilesLauncher.launch(intent)
                } else {
                    legacyPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            },
        )
        return
    }

    InternalFileBrowserDialog(request = request, onDismiss = onDismiss)
}

@Composable
private fun InternalFileBrowserPermissionDialog(
    onDismiss: () -> Unit,
    onGrant: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.internal_file_browser_permission_title)) },
        text = { Text(stringResource(R.string.internal_file_browser_permission_message)) },
        confirmButton = {
            TextButton(onClick = onGrant) {
                Text(stringResource(R.string.internal_file_browser_grant))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun InternalFileBrowserDialog(
    request: InternalFileBrowserRequest,
    onDismiss: () -> Unit,
) {
    val storageRoot = remember { Environment.getExternalStorageDirectory().canonicalFile }
    var currentDirectory by remember(request) { mutableStateOf(storageRoot) }
    var entries by remember(request) { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember(request) { mutableStateOf(true) }
    var readFailed by remember(request) { mutableStateOf(false) }
    val selectedPaths = remember(request) { mutableStateListOf<String>() }

    LaunchedEffect(currentDirectory, request) {
        loading = true
        readFailed = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                currentDirectory.listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter { file ->
                        file.isDirectory || (
                            file.isFile && matchesInternalFileExtension(
                                file.name,
                                request.normalizedExtensions,
                            )
                            )
                    }
                    .sortedWith(
                        compareByDescending<File> { it.isDirectory }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                    )
                    .toList()
            }
        }
        entries = result.getOrElse {
            readFailed = true
            emptyList()
        }
        loading = false
    }

    fun goUpOrDismiss() {
        val parent = currentDirectory.parentFile?.canonicalFile
        if (parent != null && parent.path.startsWith(storageRoot.path) && currentDirectory != storageRoot) {
            currentDirectory = parent
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BackHandler(onBack = ::goUpOrDismiss)
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::goUpOrDismiss) {
                        Icon(
                            MiuixIcons.Back,
                            contentDescription = stringResource(R.string.internal_file_browser_back),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = request.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = currentDirectory.relativeBrowserPath(storageRoot),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            MiuixIcons.Close,
                            contentDescription = stringResource(android.R.string.cancel),
                        )
                    }
                }

                when {
                    loading -> InternalFileBrowserEmptyState(
                        message = stringResource(R.string.internal_file_browser_loading),
                    )
                    readFailed -> InternalFileBrowserEmptyState(
                        message = stringResource(R.string.internal_file_browser_read_failed),
                    )
                    entries.isEmpty() -> InternalFileBrowserEmptyState(
                        message = stringResource(R.string.internal_file_browser_empty),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        items(entries, key = File::getAbsolutePath) { file ->
                            val selected = file.absolutePath in selectedPaths
                            InternalFileBrowserRow(
                                file = file,
                                selected = selected,
                                onClick = {
                                    if (file.isDirectory) {
                                        currentDirectory = file
                                    } else if (request.allowMultiple) {
                                        if (selected) selectedPaths.remove(file.absolutePath)
                                        else selectedPaths.add(file.absolutePath)
                                    } else {
                                        request.onSelected(listOf(Uri.fromFile(file)))
                                        onDismiss()
                                    }
                                },
                            )
                        }
                    }
                }

                if (request.selectDirectory) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            request.onSelected(listOf(Uri.fromFile(currentDirectory)))
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.internal_file_browser_select_current_folder))
                    }
                } else if (request.allowMultiple) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            request.onSelected(selectedPaths.map { Uri.fromFile(File(it)) })
                            onDismiss()
                        },
                        enabled = selectedPaths.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.internal_file_browser_import_selected_fmt,
                                selectedPaths.size,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InternalFileBrowserRow(
    file: File,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val thumbnail = rememberInternalFileThumbnail(file)
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(if (thumbnail != null || file.isDirectory) RoundedCornerShape(12.dp) else CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                val thumbnailImage = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = thumbnailImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (file.isDirectory) {
                        Icons.Filled.Folder
                    } else {
                        Icons.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name.ifBlank { file.absolutePath }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (file.isFile) {
                Text(
                    text = file.length().browserFileSize(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                MiuixIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ColumnScope.InternalFileBrowserEmptyState(message: String) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class InternalFileThumbnailKey(
    val path: String,
    val modifiedAt: Long,
    val size: Long,
)

private object InternalFileThumbnailCache : LruCache<InternalFileThumbnailKey, Bitmap>(12 * 1024) {
    override fun sizeOf(key: InternalFileThumbnailKey, value: Bitmap): Int =
        (value.allocationByteCount / 1024).coerceAtLeast(1)
}

@Composable
private fun rememberInternalFileThumbnail(file: File): Bitmap? {
    val key = remember(file.absolutePath, file.lastModified(), file.length()) {
        InternalFileThumbnailKey(
            path = file.absolutePath,
            modifiedAt = file.lastModified(),
            size = file.length(),
        )
    }
    val cached = remember(key) { InternalFileThumbnailCache.get(key) }
    return produceState<Bitmap?>(initialValue = cached, key1 = key) {
        if (value != null || !file.canShowInternalThumbnail()) return@produceState
        value = withContext(Dispatchers.IO) {
            loadInternalFileThumbnail(file)?.also { thumbnail ->
                InternalFileThumbnailCache.put(key, thumbnail)
            }
        }
    }.value
}

private fun File.canShowInternalThumbnail(): Boolean {
    if (!isFile) return false
    val extension = extension.lowercase(Locale.ROOT)
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty()
    return mimeType.startsWith("image/") ||
        mimeType.startsWith("video/") ||
        extension in INTERNAL_IMAGE_EXTENSIONS ||
        extension in INTERNAL_VIDEO_EXTENSIONS
}

private fun loadInternalFileThumbnail(file: File): Bitmap? = runCatching {
    val extension = file.extension.lowercase(Locale.ROOT)
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty()
    when {
        mimeType.startsWith("image/") || extension in INTERNAL_IMAGE_EXTENSIONS ->
            decodeSampledImageThumbnail(file, INTERNAL_THUMBNAIL_SIZE_PX)
        mimeType.startsWith("video/") || extension in INTERNAL_VIDEO_EXTENSIONS ->
            decodeVideoThumbnail(file, INTERNAL_THUMBNAIL_SIZE_PX)
        else -> null
    }
}.getOrNull()

private fun decodeSampledImageThumbnail(file: File, targetSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > targetSize * 2) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )?.scaledDownTo(targetSize)
}

private fun decodeVideoThumbnail(file: File, targetSize: Int): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val sourceWidth = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            .orZero()
        val sourceHeight = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            .orZero()
        val rotation = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            .orZero()
        val displayWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val displayHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
        val scaledSize = fittedThumbnailSize(displayWidth, displayHeight, targetSize)
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                scaledSize.first,
                scaledSize.second,
            ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } else {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        frame?.scaledDownTo(targetSize)
    } finally {
        retriever.release()
    }
}

private fun fittedThumbnailSize(width: Int, height: Int, targetSize: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return targetSize to targetSize
    val scale = targetSize.toFloat() / max(width, height)
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

private fun Bitmap.scaledDownTo(targetSize: Int): Bitmap {
    val longestSide = max(width, height)
    if (longestSide <= targetSize) return this
    val scale = targetSize.toFloat() / longestSide
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
}

private fun Int?.orZero(): Int = this ?: 0

private const val INTERNAL_THUMBNAIL_SIZE_PX = 192

private val INTERNAL_IMAGE_EXTENSIONS = setOf(
    "avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "webp",
)

private val INTERNAL_VIDEO_EXTENSIONS = setOf(
    "3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "ts", "webm",
)

internal fun normalizeInternalFileExtensions(extensions: Set<String>): Set<String> =
    extensions.mapNotNullTo(linkedSetOf()) { extension ->
        extension.trim().removePrefix(".").lowercase(Locale.ROOT).takeIf(String::isNotBlank)
    }

internal fun matchesInternalFileExtension(fileName: String, extensions: Set<String>): Boolean =
    extensions.isEmpty() || fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions

private fun Context.hasInternalBrowserStorageAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

private fun File.relativeBrowserPath(root: File): String =
    if (this == root) "/" else "/${relativeTo(root).invariantSeparatorsPath}"

private fun Long.browserFileSize(): String = when {
    this >= 1024L * 1024L * 1024L ->
        String.format(Locale.US, "%.1f GB", this / (1024.0 * 1024.0 * 1024.0))
    this >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", this / (1024.0 * 1024.0))
    this >= 1024L -> String.format(Locale.US, "%.1f KB", this / 1024.0)
    else -> "$this B"
}
