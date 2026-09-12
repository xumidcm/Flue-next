package com.flue.launcher.ui.settings.agent

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import com.flue.launcher.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.settings.miuix.MiuixIcons
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.style.flueGroupingEnabled
import com.flue.launcher.ui.style.FlueSurface
import com.flue.launcher.ui.style.flueOverscroll
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle

private enum class AgentSettingsGroupLayout { MERGED, SEPARATE }

private val LocalAgentSettingsGroupLayout = staticCompositionLocalOf {
    AgentSettingsGroupLayout.SEPARATE
}

private val LocalAgentSettingsRowSpacing = staticCompositionLocalOf { 10.dp }

@Composable
internal fun AgentSettingsScreen(
    stateKey: Any,
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val style = LauncherTheme.style
    val state = remember(stateKey) { LazyListState() }
    val reveal = remember(stateKey) { Animatable(0f) }
    LaunchedEffect(stateKey) {
        state.scrollToItem(0)
        reveal.animateTo(1f, tween(240))
    }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val roundScreen = isFlueScreenRound()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = style.screenBackground,
        contentColor = style.bodyColor,
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = reveal.value
                    translationY = (1f - reveal.value) * 14.dp.toPx()
                }
                .flueOverscroll(state),
            contentPadding = PaddingValues(
                start = if (roundScreen) 26.dp else 16.dp,
                end = if (roundScreen) 26.dp else 16.dp,
                top = safe.calculateTopPadding() + if (roundScreen) 24.dp else 12.dp,
                bottom = safe.calculateBottomPadding() + if (roundScreen) 34.dp else 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (style.uiStyle == UiStyle.APPLE_WATCH) 16.dp else 12.dp),
        ) {
            item(key = "agent_settings_header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AgentSettingsBackButton(onBack)
                        if (style.uiStyle == UiStyle.MIUIX) Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            color = style.titleColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                        )
                    }
                    if (style.uiStyle != UiStyle.MIUIX) Text(
                        text = title,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                        color = if (style.uiStyle == UiStyle.APPLE_WATCH) style.accentColor else style.titleColor,
                        fontSize = if (style.uiStyle == UiStyle.APPLE_WATCH) 28.sp else 30.sp,
                        fontWeight = if (style.uiStyle == UiStyle.APPLE_WATCH) FontWeight.Bold else FontWeight.Medium,
                        textAlign = if (roundScreen || style.uiStyle == UiStyle.APPLE_WATCH) TextAlign.Center else TextAlign.Start,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            color = style.secondaryTextColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            textAlign = if (roundScreen) TextAlign.Center else TextAlign.Start,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun AgentSettingsBackButton(onClick: () -> Unit) {
    val style = LauncherTheme.style
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "agent_settings_back_scale",
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(if (style.uiStyle == UiStyle.APPLE_WATCH) CircleShape else style.smallControlShape)
            .background(style.topBarChipColor)
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = stringResource(R.string.watch_editor_back),
            tint = style.titleColor,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun AgentSettingsGroup(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LauncherTheme.style
    val layout = if (flueGroupingEnabled(style)) {
        AgentSettingsGroupLayout.MERGED
    } else {
        AgentSettingsGroupLayout.SEPARATE
    }
    val spacing = when (style.uiStyle) {
        UiStyle.MIUIX -> 0.dp
        UiStyle.MATERIAL_3 -> 8.dp
        UiStyle.APPLE_WATCH -> 10.dp
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                color = style.secondaryTextColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        CompositionLocalProvider(
            LocalAgentSettingsGroupLayout provides layout,
            LocalAgentSettingsRowSpacing provides spacing,
        ) {
            if (layout == AgentSettingsGroupLayout.MERGED) {
                FlueSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = style.cardShape,
                    border = null,
                ) {
                    Column(content = content)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun AgentSettingsEntry(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    last: Boolean = false,
) {
    val style = LauncherTheme.style
    val merged = LocalAgentSettingsGroupLayout.current == AgentSettingsGroupLayout.MERGED
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) {
            when (style.uiStyle) {
                UiStyle.APPLE_WATCH -> 0.975f
                UiStyle.MATERIAL_3 -> 0.985f
                UiStyle.MIUIX -> 0.99f
            }
        } else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 480f),
        label = "agent_settings_row_scale",
    )
    val pressedColor by animateColorAsState(
        targetValue = if (pressed && onClick != null) style.pressedCardColor else style.cardColor,
        label = "agent_settings_row_color",
    )
    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(
                    minHeight = when (style.uiStyle) {
                        UiStyle.APPLE_WATCH -> 76.dp
                        UiStyle.MATERIAL_3 -> 64.dp
                        UiStyle.MIUIX -> 58.dp
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    color = if (enabled) style.titleColor else style.secondaryTextColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = style.secondaryTextColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (trailing != null) trailing.invoke(this)
            else if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                Modifier.size(20.dp), tint = style.secondaryTextColor)
        }
    }
    val interactiveModifier = modifier
        .fillMaxWidth()
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
            } else Modifier,
        )

    if (merged) {
        Column(
            modifier = interactiveModifier.background(
                if (pressed && onClick != null) style.pressedCardColor else Color.Transparent,
            ),
        ) {
            rowContent()
            if (!last) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = if (leading == null) 16.dp else 56.dp)
                        .background(style.outlineColor)
                        .height(0.6.dp),
                )
            }
        }
    } else {
        FlueSurface(
            modifier = interactiveModifier,
            shape = style.cardShape,
            color = pressedColor,
            border = null,
        ) { rowContent() }
    }
}

@Composable
internal fun AgentSettingsFormCard(
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LauncherTheme.style
    FlueSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = style.cardShape,
        border = null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
