package com.flue.launcher.ui.editor.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.flue.launcher.R
import com.flue.launcher.ui.settings.miuix.MiuixIcons
import com.flue.launcher.ui.style.FlueSurface
import com.flue.launcher.ui.style.FlueTextField
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.watchface.agent.model.AgentMessage
import com.flue.launcher.watchface.agent.model.AgentMessageRole

internal sealed interface AgentConversationItem {
    val key: String

    data class UserMessage(val message: AgentMessage) : AgentConversationItem {
        override val key: String = message.id
    }

    data class AssistantTurn(val messages: List<AgentMessage>) : AgentConversationItem {
        override val key: String = "assistant-turn:${messages.first().id}"
        val expansionKey: String = "thought-chain:${messages.first().id}"
    }
}

/** Keeps every non-user message in its original order inside the assistant turn that produced it. */
internal fun groupConversationForDisplay(messages: List<AgentMessage>): List<AgentConversationItem> = buildList {
    val assistantMessages = mutableListOf<AgentMessage>()

    fun flushAssistantTurn() {
        if (assistantMessages.isNotEmpty()) {
            add(AgentConversationItem.AssistantTurn(assistantMessages.toList()))
            assistantMessages.clear()
        }
    }

    messages.forEach { message ->
        if (message.role == AgentMessageRole.USER) {
            flushAssistantTurn()
            add(AgentConversationItem.UserMessage(message))
        } else {
            assistantMessages += message
        }
    }
    flushAssistantTurn()
}

private data class AgentChromeMetrics(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val spacing: Dp,
    val actionSize: Dp,
)

@Composable
private fun agentChromeMetrics(): AgentChromeMetrics = when (LauncherTheme.style.uiStyle) {
    UiStyle.APPLE_WATCH -> AgentChromeMetrics(10.dp, 8.dp, 8.dp, 44.dp)
    UiStyle.MATERIAL_3 -> AgentChromeMetrics(12.dp, 8.dp, 8.dp, 44.dp)
    UiStyle.MIUIX -> AgentChromeMetrics(10.dp, 6.dp, 6.dp, 42.dp)
}

@Composable
internal fun AgentTopBar(
    title: String,
    totalTokens: Long? = null,
    fullscreen: Boolean,
    expandRestoreDescription: String,
    settingsDescription: String,
    closeDescription: String,
    onToggleSurface: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onMinimize: () -> Unit,
) {
    val style = LauncherTheme.style
    val metrics = agentChromeMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = metrics.horizontalPadding,
                vertical = metrics.verticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spacing),
    ) {
        AgentChromeIconButton(
            icon = if (fullscreen) MiuixIcons.Minimize else MiuixIcons.Maximize,
            contentDescription = expandRestoreDescription,
            size = metrics.actionSize,
            onClick = onToggleSurface,
        )
        AgentChromeIconButton(
            icon = MiuixIcons.Settings,
            contentDescription = settingsDescription,
            size = metrics.actionSize,
            onClick = onOpenSettings,
        )
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenHistory)
                .padding(vertical = 8.dp),
            color = style.titleColor,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        totalTokens?.let {
            Text(stringResource(R.string.agent_total_tokens, it), color = style.topBarTextColor, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        AgentChromeIconButton(
            icon = MiuixIcons.Close,
            contentDescription = closeDescription,
            size = metrics.actionSize,
            onClick = onMinimize,
        )
    }
}

@Composable
internal fun AgentChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
) {
    val style = LauncherTheme.style
    FlueSurface(
        modifier = Modifier.size(size),
        shape = style.smallControlShape,
        color = style.topBarChipColor,
        border = null,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = style.topBarTextColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun AgentComposer(
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String,
    attachmentDescription: String,
    actionDescription: String,
    isGenerating: Boolean,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    onValueChange: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onSubmitOrStop: () -> Unit,
) {
    val style = LauncherTheme.style
    val metrics = agentChromeMetrics()
    FlueSurface(
        modifier = modifier.fillMaxWidth(),
        shape = style.compactShape,
        color = style.cardColor,
        border = null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.spacing, vertical = metrics.spacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.spacing),
        ) {
            AgentChromeIconButton(
                icon = MiuixIcons.Attachment,
                contentDescription = attachmentDescription,
                size = metrics.actionSize,
                onClick = onAddAttachment,
            )
            FlueTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = placeholder,
                keyboardOptions = keyboardOptions,
                singleLine = false,
                minLines = 1,
                maxLines = 5,
            )
            FlueSurface(
                modifier = Modifier.size(metrics.actionSize),
                shape = style.smallControlShape,
                color = if (enabled) style.accentColor else style.pressedCardColor,
                border = null,
            ) {
                IconButton(onClick = onSubmitOrStop, enabled = enabled, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = if (isGenerating) MiuixIcons.Stop else MiuixIcons.Send,
                        contentDescription = actionDescription,
                        tint = if (enabled) style.screenBackground else style.secondaryTextColor,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AgentThoughtChain(
    title: String,
    stepCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    collapsedPreview: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LauncherTheme.style
    FlueSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = style.rowShape,
        color = style.cardColor,
        border = null,
    ) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MiuixIcons.Sparkle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = style.accentColor,
                )
                Text(
                    text = "$title · $stepCount",
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 7.dp),
                    color = style.titleColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = style.secondaryTextColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            }
            if (!expanded && collapsedPreview != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 12.dp, bottom = 10.dp),
                ) {
                    collapsedPreview()
                }
            }
        }
    }
}
