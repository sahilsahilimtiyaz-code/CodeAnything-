package com.codingagent.mobile.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Premium "agent thinking" indicator: three breathing dots, live elapsed
 * time, tool-call count and a reasoning preview. Shown while the run
 * streams; driven by real message state, not a timer simulation
 * (only the dots/elapsed clock animate).
 */
@Composable
fun ThinkingBubble(
    thinking: String,
    startedAt: Long,
    toolCount: Int,
    modifier: Modifier = Modifier
) {
    var elapsed by remember(startedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = (System.currentTimeMillis() - startedAt) / 1000
            delay(1000)
        }
    }
    val transition = rememberInfiniteTransition(label = "thinkingDots")
    val phases = (0..2).map { i ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "thinkingDot$i"
        )
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Agent",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                phases.forEach { p -> Dot(alpha = p.value) }
                Spacer(Modifier.weight(1f))
                Text(
                    formatElapsed(elapsed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            val status = buildString {
                append("Thinking")
                if (toolCount > 0) append(" · $toolCount tool${if (toolCount == 1) "" else "s"}")
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val preview = thinking.takeLast(280).trim()
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "…" + preview.take(240),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4
                )
            }
        }
    }
}

@Composable
private fun Dot(alpha: Float) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(7.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
    )
}

private fun formatElapsed(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m == 0) "${s}s" else "${m}m ${s.toString().padStart(2, '0')}s"
}

/** Compact tool-call timeline under a finished assistant message. */
@Composable
fun ToolTimeline(
    tools: List<com.codingagent.mobile.domain.ToolCallUi>,
    modifier: Modifier = Modifier
) {
    if (tools.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tools.take(8).forEach { tool ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(tool.status)
                Text(
                    text = toolDisplay(tool),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 2
                )
            }
        }
        if (tools.size > 8) {
            Text(
                "+${tools.size - 8} more",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusDot(status: com.codingagent.mobile.domain.ToolCallUi.ToolStatus) {
    val color = when (status) {
        com.codingagent.mobile.domain.ToolCallUi.ToolStatus.SUCCESS ->
            MaterialTheme.colorScheme.primary
        com.codingagent.mobile.domain.ToolCallUi.ToolStatus.ERROR ->
            MaterialTheme.colorScheme.error
        com.codingagent.mobile.domain.ToolCallUi.ToolStatus.DENIED ->
            MaterialTheme.colorScheme.error
        com.codingagent.mobile.domain.ToolCallUi.ToolStatus.RUNNING ->
            MaterialTheme.colorScheme.tertiary
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

private fun toolDisplay(tool: com.codingagent.mobile.domain.ToolCallUi): String {
    val arg = tool.arguments.ifBlank { "" }.take(80)
    val suffix = when (tool.status) {
        com.codingagent.mobile.domain.ToolCallUi.ToolStatus.RUNNING -> "…"
        com.codingagent.mobile.domain.ToolCallUi.ToolStatus.ERROR ->
            tool.result?.take(80)?.let { " — $it" } ?: ""
        else -> ""
    }
    return if (arg.isBlank()) tool.name + suffix else "${tool.name} $arg$suffix"
}
