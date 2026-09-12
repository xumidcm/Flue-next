package com.flue.launcher.ui.home

import android.animation.ValueAnimator
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.InternalWatchFaceConfigActivity
import com.flue.launcher.EXTRA_INTERNAL_WATCHFACE_ID
import com.flue.launcher.EXTRA_SETTINGS_DESTINATION
import com.flue.launcher.EXTRA_SETTINGS_RETURN_TO_FACE
import com.flue.launcher.R
import com.flue.launcher.SETTINGS_DESTINATION_WATCH_FACES
import com.flue.launcher.SettingsActivity
import com.flue.launcher.WatchEditorActivity
import com.flue.launcher.WatchStoreActivity
import com.flue.launcher.ui.settings.WatchFaceStaticPreview
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.style.FlueAlertDialog
import com.flue.launcher.ui.style.FlueFullScreenOverlay
import com.flue.launcher.ui.style.LocalFlueOverlayPresentationProgress
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.selection.WatchFaceCardReducer
import com.flue.launcher.watchface.selection.watchFaceCardLayout
import com.flue.launcher.watchface.selection.watchFaceCardVisualTransform
import com.flue.launcher.watchface.selection.watchFaceCardCapabilities
import com.flue.launcher.watchface.selection.watchFaceCardUseDarkForeground
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Store
import kotlin.math.absoluteValue

@Composable
fun WatchFaceCardSelector(
    vm: LauncherViewModel,
    watchFaces: List<WatchFaceDescriptor>,
    selectedWatchFaceId: String,
    refreshToken: Int,
    uiStyle: UiStyle,
    builtInPhotoPath: String?,
    builtInVideoPath: String?,
    photoOptions: BuiltInWatchFaceOptions,
    videoOptions: BuiltInWatchFaceOptions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val items = remember(watchFaces) { WatchFaceCardReducer.items(watchFaces.map { it.id }) }
    val byId = remember(watchFaces) { watchFaces.associateBy { it.id } }
    var focusedStableId by rememberSaveable(selectedWatchFaceId) { mutableStateOf(selectedWatchFaceId) }
    var deleteTarget by remember { mutableStateOf<WatchFaceDescriptor?>(null) }
    val initialPage = remember(items, selectedWatchFaceId) {
        WatchFaceCardReducer.initialPage(items, selectedWatchFaceId)
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { items.size }
    val focusRequester = remember { FocusRequester() }
    val requestClose: () -> Unit = { latestOnDismiss() }
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }

    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            items.getOrNull(page)?.let { focusedStableId = it.stableId }
        }
    }
    LaunchedEffect(items.map { it.stableId }) {
        val restoredPage = items.indexOfFirst { it.stableId == focusedStableId }
            .takeIf { it >= 0 }
            ?: pagerState.currentPage.coerceIn(0, items.lastIndex.coerceAtLeast(0))
        if (items.isNotEmpty() && restoredPage != pagerState.currentPage) {
            pagerState.scrollToPage(restoredPage)
        }
    }

    FlueFullScreenOverlay(
        onDismissRequest = requestClose,
        modifier = Modifier.fillMaxSize(),
    ) {
        val presentation = LocalFlueOverlayPresentationProgress.current
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val target = when (event.key) {
                    Key.DirectionLeft -> pagerState.currentPage - 1
                    Key.DirectionRight -> pagerState.currentPage + 1
                    Key.Escape, Key.Back -> {
                        requestClose()
                        return@onPreviewKeyEvent true
                    }
                    else -> return@onPreviewKeyEvent false
                }.coerceIn(0, items.lastIndex)
                scope.launch { pagerState.animateScrollToPage(target) }
                true
                },
        ) {
        val focused = byId[items.getOrNull(pagerState.currentPage)?.stableId]
        val capabilities = focused?.let {
            watchFaceCardCapabilities(
                supportsSettings = it.supportsSettings,
                isBuiltIn = it.isBuiltin,
                isJbWatch = it.isJbWatch,
                isWatchmaker = it.isWatchmaker,
            )
        }
        val actionCount = when {
            focused == null -> 1
            else -> listOf(capabilities?.canEdit == true, capabilities?.canDelete == true).count { it }
        }
        val cardLayout = watchFaceCardLayout(
            availableWidthDp = maxWidth.value,
            availableHeightDp = maxHeight.value,
            isRoundScreen = isFlueScreenRound(),
            actionCount = actionCount,
        )
        val cardWidth = cardLayout.previewSideDp.dp
        val pagerHeight = cardWidth + 8.dp
        val horizontalPagerPadding = (maxWidth - cardWidth) / 2
        val focusedBackgroundColor = rememberAppListSeedColor(
            watchFaceId = focused?.id ?: selectedWatchFaceId,
            photoPath = builtInPhotoPath,
            videoPath = builtInVideoPath,
            photoOptions = photoOptions,
            videoOptions = videoOptions,
            watchFaceDescriptor = focused,
            enabled = focused != null,
        )
        val backIconTarget = when {
            focused == null || focusedBackgroundColor.alpha <= 0.01f -> style.titleColor
            watchFaceCardUseDarkForeground(focusedBackgroundColor.luminance()) -> Color.Black
            else -> Color.White
        }
        val backIconColor by animateColorAsState(
            targetValue = backIconTarget,
            animationSpec = tween(durationMillis = 220),
            label = "watchface_card_back_foreground",
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = cardLayout.verticalInsetDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            val headerItem = items.getOrNull(pagerState.currentPage)
            val headerFace = headerItem?.let { byId[it.stableId] }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (uiStyle == UiStyle.APPLE_WATCH) 22.dp else 14.dp)
                    .graphicsLayer { alpha = presentation },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = requestClose, modifier = Modifier.size(40.dp)) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = stringResource(R.string.watch_editor_back),
                        tint = backIconColor,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = headerFace?.let { localizedWatchFaceName(it) }
                            ?: stringResource(R.string.settings_watchface_store),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (animationsEnabled) {
                                    Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                } else {
                                    Modifier
                                },
                            ),
                        color = style.titleColor,
                        fontSize = if (uiStyle == UiStyle.APPLE_WATCH) 20.sp else 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = if (animationsEnabled) TextOverflow.Clip else TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = headerFace?.let { localizedWatchFaceAuthor(it) }
                            ?: stringResource(R.string.watchface_cards_store_subtitle),
                        modifier = Modifier.fillMaxWidth(),
                        color = style.secondaryTextColor,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.size(40.dp))
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pagerHeight)
                    .graphicsLayer {
                        val progress = presentation
                        val initialScale = if (uiStyle == UiStyle.APPLE_WATCH) 1.42f else 1.34f
                        scaleX = initialScale + (1f - initialScale) * progress
                        scaleY = scaleX
                    },
                contentPadding = PaddingValues(horizontal = horizontalPagerPadding),
                pageSpacing = 10.dp,
                pageSize = PageSize.Fixed(cardWidth),
                beyondViewportPageCount = 0,
                // The face list can change while the pager is still composing its
                // previous pages (for example immediately after a download). Keep
                // stale pages renderable until Pager clamps its current page.
                key = { page -> items.getOrNull(page)?.stableId ?: "stale-watchface-page-$page" },
            ) { page ->
                val item = items.getOrNull(page) ?: return@HorizontalPager
                val pageOffset = (
                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    ).absoluteValue.coerceIn(0f, 1f)
                WatchFaceCardVisualSurface(
                    pageOffset = pageOffset,
                    modifier = Modifier.fillMaxSize(),
                    onClick = {
                        val centered = page == pagerState.currentPage &&
                            pagerState.currentPageOffsetFraction.absoluteValue < 0.12f
                        if (!centered) {
                            scope.launch { pagerState.animateScrollToPage(page) }
                        } else if (item.isStore) {
                            context.startActivity(Intent(context, WatchStoreActivity::class.java))
                        } else {
                            vm.selectWatchFace(item.stableId)
                            requestClose()
                        }
                    },
                ) {
                    WatchFacePagerCard(
                        descriptor = byId[item.stableId],
                        selected = item.stableId == selectedWatchFaceId,
                        uiStyle = uiStyle,
                        builtInPhotoPath = builtInPhotoPath,
                        builtInVideoPath = builtInVideoPath,
                        photoOptions = photoOptions,
                        videoOptions = videoOptions,
                        refreshToken = refreshToken,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Row(
                modifier = Modifier.graphicsLayer { alpha = presentation },
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (items.size > 9) {
                    Text("${pagerState.currentPage + 1} / ${items.size}", color = style.secondaryTextColor, fontSize = 11.sp)
                } else items.forEachIndexed { index, _ ->
                    Box(
                        Modifier
                            .size(if (index == pagerState.currentPage) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) style.accentColor
                                else style.secondaryTextColor.copy(alpha = 0.42f),
                            ),
                    )
                }
            }
            if (actionCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp)
                        .graphicsLayer { alpha = presentation },
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    if (focused == null) {
                        WatchFaceCardAction(
                            text = stringResource(R.string.watchface_cards_open_settings),
                            primary = true,
                            uiStyle = uiStyle,
                            onClick = {
                                context.startActivity(
                                    Intent(context, SettingsActivity::class.java)
                                        .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_WATCH_FACES)
                                        .putExtra(EXTRA_SETTINGS_RETURN_TO_FACE, true),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (focused != null && capabilities?.canEdit == true) {
                        WatchFaceCardAction(
                            text = stringResource(R.string.widget_edit),
                            primary = true,
                            uiStyle = uiStyle,
                            onClick = {
                                when {
                                    focused.isBuiltin && focused.id in setOf(
                                        BUILT_IN_PHOTO_WATCHFACE_ID,
                                        BUILT_IN_VIDEO_WATCHFACE_ID,
                                    ) -> context.startActivity(
                                        Intent(context, InternalWatchFaceConfigActivity::class.java)
                                            .putExtra(EXTRA_INTERNAL_WATCHFACE_ID, focused.id),
                                    )
                                    focused.isWatchmaker -> context.startActivity(
                                        Intent(context, WatchEditorActivity::class.java)
                                            .putExtra(WatchEditorActivity.EXTRA_WATCH_FACE_ID, focused.id),
                                    )
                                    else -> Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_no_watchface_config),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (focused != null && capabilities?.canDelete == true) {
                        WatchFaceCardAction(
                            text = stringResource(R.string.action_delete),
                            primary = false,
                            uiStyle = uiStyle,
                            danger = true,
                            onClick = { deleteTarget = focused },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    }

    deleteTarget?.let { target ->
        FlueAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.dialog_delete_watchface_title),
            text = stringResource(
                if (target.isWatchmaker) R.string.dialog_delete_watchmaker_fmt
                else R.string.dialog_delete_jbwatch_fmt,
                target.displayName,
            ),
            confirmText = stringResource(R.string.action_delete),
            dismissText = stringResource(android.R.string.cancel),
            dangerous = true,
            onConfirm = {
                val previousItems = items
                val deletedId = target.id
                deleteTarget = null
                scope.launch {
                    vm.deleteImportedWatchFace(deletedId)
                        .onSuccess {
                            val remainingIds = vm.availableWatchFaces.value.map { it.id }
                            focusedStableId = WatchFaceCardReducer.afterDelete(
                                state = com.flue.launcher.watchface.selection.WatchFaceCardState(
                                    selectedFaceId = selectedWatchFaceId,
                                    focusedStableId = deletedId,
                                ),
                                deletedStableId = deletedId,
                                previousItems = previousItems,
                                remainingFaceIds = remainingIds,
                            ).focusedStableId
                        }
                        .onFailure { error ->
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.toast_delete_failed_fmt,
                                    error.message ?: error.javaClass.simpleName,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                }
            },
        )
    }
}

@Composable
private fun WatchFacePagerCard(
    descriptor: WatchFaceDescriptor?,
    selected: Boolean,
    uiStyle: UiStyle,
    builtInPhotoPath: String?,
    builtInVideoPath: String?,
    photoOptions: BuiltInWatchFaceOptions,
    videoOptions: BuiltInWatchFaceOptions,
    refreshToken: Int,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (descriptor == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .aspectRatio(1f)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Store,
                    contentDescription = stringResource(R.string.settings_watchface_store),
                    tint = style.accentColor,
                    modifier = Modifier.size(72.dp),
                )
            }
        } else {
            WatchFaceStaticPreview(
                descriptor = descriptor,
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(1f)
                    .clip(style.compactShape)
                    .then(
                        if (selected) Modifier.border(2.dp, style.accentColor, style.compactShape)
                        else Modifier,
                    ),
                uiStyle = uiStyle,
                builtInPhotoPath = builtInPhotoPath,
                builtInVideoPath = builtInVideoPath,
                photoOptions = photoOptions,
                videoOptions = videoOptions,
                refreshToken = refreshToken,
            )
        }
    }
}

@Composable
private fun WatchFaceCardVisualSurface(
    pageOffset: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val target = watchFaceCardVisualTransform(pageOffset = pageOffset, pressed = pressed)
    val scale by animateFloatAsState(
        targetValue = target.scale,
        animationSpec = spring(stiffness = 620f, dampingRatio = 0.68f),
        label = "watchface_card_surface_scale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = 1f - pageOffset.coerceIn(0f, 1f) * 0.28f
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun localizedWatchFaceName(descriptor: WatchFaceDescriptor): String = when (descriptor.id) {
    com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID ->
        stringResource(R.string.watchface_builtin_default_name)
    BUILT_IN_PHOTO_WATCHFACE_ID -> stringResource(R.string.watchface_builtin_photo_name)
    BUILT_IN_VIDEO_WATCHFACE_ID -> stringResource(R.string.watchface_builtin_video_name)
    else -> descriptor.displayName
}

@Composable
private fun localizedWatchFaceAuthor(descriptor: WatchFaceDescriptor): String =
    descriptor.author?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (descriptor.isBuiltin) {
            stringResource(R.string.watchface_cards_builtin_author)
        } else {
            stringResource(R.string.watchface_cards_unknown_author)
        }

@Composable
private fun WatchFaceCardAction(
    text: String,
    primary: Boolean,
    uiStyle: UiStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val style = LauncherTheme.style
    val color = when {
        danger -> Color(0xFFFF5B57)
        primary -> style.accentColor
        else -> style.topBarChipColor
    }
    val shape = if (uiStyle == UiStyle.APPLE_WATCH) style.chipShape else style.compactShape
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
        label = "watchface_card_action_press",
    )
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(color.copy(alpha = if (danger) 0.18f else if (primary) 0.24f else 1f))
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (danger) color else if (primary) style.accentColor else style.titleColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
