package com.flue.launcher.ui.drawer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import com.flue.launcher.R
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.ui.style.FlueAlertDialog
import com.flue.launcher.ui.style.flueOverscroll
import com.flue.launcher.ui.theme.LauncherTheme

@Composable
fun AppShortcutOverlay(
    app: AppInfo,
    blurEnabled: Boolean = true,
    onExcludeApp: (() -> Unit)? = null,
    onRemoveShortcut: (() -> Unit)? = null,
    onRenameFolder: ((String) -> Unit)? = null,
    onDissolveFolder: (() -> Unit)? = null,
    onTransitionProgress: (Float) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appDetailsPackage = if (app.isBuiltInSettingsEntry) context.packageName else app.packageName

    var showing by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var renameDialogVisible by remember { mutableStateOf(false) }
    var renameText by remember(app.componentKey) { mutableStateOf(app.label) }
    val dismissInteraction = remember { MutableInteractionSource() }
    val blockInteraction = remember { MutableInteractionSource() }
    val panelScrollState = rememberScrollState()

    fun animateDismiss() {
        dismissing = true
        showing = false
    }

    LaunchedEffect(Unit) { showing = true }

    BackHandler(enabled = showing && !dismissing) {
        animateDismiss()
    }

    // 关门契约与 AppFolderOverlay 完全对齐：卸载时机由 alpha 真正落到 0 决定，
    // 不再用 delay(250) 猜。旧写法的两条时间线是错开的——alpha 走 tween(200)
    // 淡完之后，还要空等 50ms 才 onDismiss()，而列表背景模糊是挂在
    // actionApp != null 上的另一条 tween(180)，只有 onDismiss() 之后才开始退。
    // 结果就是菜单已经看不见了，模糊还满着，然后才突兀地自己化开。
    // 现在两侧同用一条 alpha：菜单淡多少，模糊就退多少。
    val animAlpha by animateFloatAsState(
        targetValue = if (showing && !dismissing) 1f else 0f,
        animationSpec = tween(180),
        label = "overlay_alpha",
        finishedListener = { finalAlpha ->
            if (dismissing && finalAlpha <= 0.001f) {
                onDismiss()
            }
        },
    )
    val animScale by animateFloatAsState(
        targetValue = if (showing && !dismissing) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "overlay_scale"
    )
    // **进度要在能观察到动画的地方上报**。
    //
    // 原来写的是 `SideEffect { onTransitionProgress(animAlpha) }`。SideEffect 的 lambda 在组合
    // 应用之后才执行，里面的 State 读**不算进重组作用域** —— 动画每帧推进都不会让这个 composable
    // 重组，于是这一句只在 showing / dismissing 变化那两次组合里跑到：
    //
    // - 长按打开：那一次组合时 alpha 才刚起步（≈0），报出去的就是 0 —— 列表全程不模糊；
    // - 按返回 / 点空白：这一次组合读到的是早就跑满的 1，模糊**突然出现**；
    // - 紧接着 overlay 卸载，列表侧把进度清 0，模糊又**突然消失**。
    //
    // 正好是老师描述的现象。snapshotFlow 会真正订阅这个 State，每帧都 emit 一次。
    val reportProgress by rememberUpdatedState(onTransitionProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { animAlpha }.collect { reportProgress(it) }
    }

    if (renameDialogVisible) {
        FlueAlertDialog(
            onDismissRequest = { renameDialogVisible = false },
            title = { Text(stringResource(R.string.shortcut_menu_rename_folder_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameFolder?.invoke(renameText)
                        renameDialogVisible = false
                        animateDismiss()
                    }
                ) {
                    Text(stringResource(R.string.shortcut_menu_rename_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogVisible = false }) {
                    Text(stringResource(R.string.shortcut_menu_rename_cancel))
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = animAlpha }
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(indication = null, interactionSource = dismissInteraction) { animateDismiss() }
            .consumeOverlayPointers(),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // dp 本身就是密度无关单位——以前这里再乘一次 densityDpi/320 并
            // 用 180dp 下限兜底，高密度表上 0.76×maxWidth 低于 180dp 被强行
            // 拉宽到近乎满屏，菜单文字贴弦被切。整块只按屏宽比例取，绝不
            // 超过屏宽。
            val panelWidth = (maxWidth * 0.78f).coerceAtMost(312.dp)
            val panelMaxHeight = maxHeight * 0.86f

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(panelWidth)
                    .heightIn(max = panelMaxHeight)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .flueOverscroll(panelScrollState)
                    .verticalScroll(panelScrollState)
                    .graphicsLayer {
                        scaleX = animScale
                        scaleY = animScale
                    }
                    .clickable(indication = null, interactionSource = blockInteraction) { }
                    .consumeOverlayPointers()
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(LauncherTheme.style.cardShape)
                        .background(Color(0xFF2C2C2E))
                ) {
                    if (app.isFolder) {
                        ShortcutMenuItem(stringResource(R.string.shortcut_menu_rename)) {
                            renameText = app.label
                            renameDialogVisible = true
                        }
                        onDissolveFolder?.let { dissolve ->
                            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF48484A)))
                            ShortcutMenuItem(
                                stringResource(R.string.shortcut_menu_dissolve_folder),
                                Color(0xFFFF9F0A),
                            ) {
                                dissolve()
                                onDismiss()
                            }
                        }
                    } else {
                        ShortcutMenuItem(stringResource(R.string.shortcut_menu_app_info)) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$appDetailsPackage")
                            })
                            onDismiss()
                        }
                        // 从应用列表里隐藏这个应用，与设置→隐藏应用写的是同一份数据。
                        // 内置设置入口不可隐藏（隐藏了就没有入口再改回来），
                        // 快捷启动项本身由「移除」处理，这里都不显示。
                        if (!app.isBuiltInSettingsEntry && !app.isAppListShortcut) {
                            onExcludeApp?.let { excludeApp ->
                                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF48484A)))
                                ShortcutMenuItem(
                                    stringResource(R.string.shortcut_menu_exclude_app),
                                    Color(0xFFFF9F0A),
                                ) {
                                    excludeApp()
                                    onDismiss()
                                }
                            }
                        }
                        if (app.isAppListShortcut) {
                            onRemoveShortcut?.let { removeShortcut ->
                                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF48484A)))
                                ShortcutMenuItem(
                                    stringResource(R.string.shortcut_menu_remove),
                                    Color(0xFFFF9F0A),
                                ) {
                                    removeShortcut()
                                    onDismiss()
                                }
                            }
                        } else {
                            onRemoveShortcut?.let { removeShortcut ->
                                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF48484A)))
                                ShortcutMenuItem(
                                    stringResource(R.string.shortcut_menu_remove_from_quick_launch),
                                    Color(0xFFFF9F0A),
                                ) {
                                    removeShortcut()
                                    onDismiss()
                                }
                            }
                            if (!app.isBuiltInSettingsEntry) {
                                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF48484A)))
                                ShortcutMenuItem(
                                    stringResource(R.string.shortcut_menu_uninstall),
                                    Color(0xFFFF453A),
                                ) {
                                    val packageUri = Uri.fromParts("package", app.packageName, null)
                                    val intents = listOf(
                                        Intent(Intent.ACTION_DELETE, packageUri).apply {
                                            putExtra(Intent.EXTRA_RETURN_RESULT, false)
                                            putExtra(Intent.EXTRA_USER, Process.myUserHandle())
                                        }
                                    )
                                    val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = packageUri
                                    }
                                    val launched = intents.any { baseIntent ->
                                        val intent = baseIntent.apply {
                                            if (context !is android.app.Activity) {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        }
                                        val resolved = intent.resolveActivity(context.packageManager) != null
                                        if (!resolved) return@any false
                                        runCatching {
                                            context.startActivity(intent)
                                        }.onFailure {
                                            Log.w("ShortcutPopup", "Failed to launch uninstall intent for ${app.packageName}", it)
                                        }.isSuccess
                                    }
                                    if (!launched) {
                                        runCatching {
                                            if (context !is android.app.Activity) {
                                                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(fallbackIntent)
                                        }.onFailure {
                                            Log.w("ShortcutPopup", "Failed to open app details for ${app.packageName}", it)
                                        }
                                    }
                                    onDismiss()
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Image(
                    bitmap = app.cachedIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(app.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W600)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

private fun Modifier.consumeOverlayPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            event.changes.forEach { it.consume() }
        }
    }
}

@Composable
private fun ShortcutMenuItem(text: String, color: Color = Color.White, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.W500)
    }
}

fun vibrateHaptic(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    } catch (_: Exception) {
    }
}
