package com.flue.launcher.ui.style

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle

internal enum class FlueExpressiveActionKind {
    LOGIN_ACTIONS,
    WEB_ACTIONS,
    EDITOR_TOOLBAR,
}

internal enum class FlueExpressiveActionContainer {
    LEGACY,
    BUTTON_GROUP,
    SPLIT_BUTTON,
    FLOATING_TOOLBAR,
}

internal fun useMaterial3ExpressiveComponents(uiStyle: UiStyle): Boolean =
    uiStyle == UiStyle.MATERIAL_3

internal fun flueExpressiveActionContainer(
    uiStyle: UiStyle,
    kind: FlueExpressiveActionKind,
): FlueExpressiveActionContainer = if (!useMaterial3ExpressiveComponents(uiStyle)) {
    FlueExpressiveActionContainer.LEGACY
} else {
    when (kind) {
        FlueExpressiveActionKind.LOGIN_ACTIONS -> FlueExpressiveActionContainer.BUTTON_GROUP
        FlueExpressiveActionKind.WEB_ACTIONS -> FlueExpressiveActionContainer.SPLIT_BUTTON
        FlueExpressiveActionKind.EDITOR_TOOLBAR -> FlueExpressiveActionContainer.FLOATING_TOOLBAR
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FlueExpressiveButtonGroup(
    modifier: Modifier = Modifier,
    slot: @Composable () -> Unit,
) {
    if (useMaterial3ExpressiveComponents(LauncherTheme.style.uiStyle)) {
        ButtonGroup(modifier = modifier) {
            customItem(
                buttonGroupContent = slot,
                menuContent = {},
            )
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = { slot() },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FlueExpressiveSplitButton(
    primaryOnClick: () -> Unit,
    secondaryOnClick: () -> Unit,
    modifier: Modifier = Modifier,
    primaryContent: @Composable () -> Unit,
    secondaryContent: @Composable () -> Unit,
) {
    if (useMaterial3ExpressiveComponents(LauncherTheme.style.uiStyle)) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.TonalLeadingButton(onClick = primaryOnClick) {
                    primaryContent()
                }
            },
            trailingButton = {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = false,
                    onCheckedChange = { secondaryOnClick() },
                ) {
                    secondaryContent()
                }
            },
            modifier = modifier,
        )
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(onClick = primaryOnClick, modifier = Modifier.weight(1f)) { primaryContent() }
            OutlinedButton(
                onClick = secondaryOnClick,
                modifier = Modifier.weight(1f),
                content = { secondaryContent() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FlueExpressiveFloatingToolbar(
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (useMaterial3ExpressiveComponents(LauncherTheme.style.uiStyle)) {
        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = modifier,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
            content = { content() },
        )
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = { content() },
        )
    }
}
