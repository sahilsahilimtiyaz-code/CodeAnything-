package com.codingagent.mobile.ui.approvals

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.codingagent.mobile.domain.ApprovalRequest
import com.codingagent.mobile.domain.RiskLevel

@Composable
fun ApprovalBanner(
    approvals: List<ApprovalRequest>,
    onAllow: (ApprovalRequest) -> Unit,
    onDeny: (ApprovalRequest) -> Unit
) {
    val first = approvals.firstOrNull() ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (first.riskLevel) {
                RiskLevel.HIGH -> MaterialTheme.colorScheme.errorContainer
                RiskLevel.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer
                RiskLevel.LOW -> MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Tool approval required",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "${first.toolName} (${first.riskLevel.name.lowercase()})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (!first.diff.isNullOrBlank()) {
                DiffView(diff = first.diff, modifier = Modifier.padding(top = 8.dp))
            } else if (first.argsJson.isNotBlank() && first.argsJson != "{}") {
                Text(
                    first.argsJson.take(400) + if (first.argsJson.length > 400) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { onDeny(first) }) {
                    Text("Deny")
                }
                Button(onClick = { onAllow(first) }) {
                    Text("Allow")
                }
            }
        }
    }
}

/** Color-coded unified diff viewer (max 80 lines, scrollable). */
@Composable
fun DiffView(diff: String, modifier: Modifier = Modifier) {
    val lines = rememberDiffLines(diff)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLowest,
                MaterialTheme.shapes.small
            )
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        lines.forEach { (text, kind) ->
            val color = when (kind) {
                DiffKind.REMOVED -> MaterialTheme.colorScheme.error
                DiffKind.ADDED -> MaterialTheme.colorScheme.primary
                DiffKind.HUNK -> MaterialTheme.colorScheme.tertiary
                DiffKind.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

private enum class DiffKind { REMOVED, ADDED, HUNK, CONTEXT }

@Composable
private fun rememberDiffLines(diff: String): List<Pair<String, DiffKind>> {
    return androidx.compose.runtime.remember(diff) {
        diff.lineSequence().take(80).map { line ->
            when {
                line.startsWith("@@") -> line to DiffKind.HUNK
                line.startsWith("-") && !line.startsWith("---") -> line to DiffKind.REMOVED
                line.startsWith("+") && !line.startsWith("+++") -> line to DiffKind.ADDED
                else -> line to DiffKind.CONTEXT
            }
        }.toList()
    }
}
