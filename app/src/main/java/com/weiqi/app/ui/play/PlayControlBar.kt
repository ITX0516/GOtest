package com.weiqi.app.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.ForwardToInbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 对弈控制条。
 *
 * 包含：Pass、悔棋、认输、新对局、导出 SGF、进入分析等按钮。
 * AI 思考期间（[aiThinking] = true）禁用会影响对局状态的操作按钮，
 * 并显示加载圈以提示用户等待。
 *
 * @param aiThinking AI 是否正在思考。
 * @param onPass 弃权回调。
 * @param onUndo 悔棋回调。
 * @param onResign 认输回调。
 * @param onNewGame 新对局回调。
 * @param onSaveSgf 导出 SGF 回调。
 * @param onAnalyze 进入分析回调。
 * @param modifier 修饰符。
 */
@Composable
fun PlayControlBar(
    aiThinking: Boolean,
    onPass: () -> Unit,
    onUndo: () -> Unit,
    onResign: () -> Unit,
    onNewGame: () -> Unit,
    onSaveSgf: () -> Unit,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 影响对局状态的操作：AI 思考时禁用
            ControlButton(
                icon = Icons.Outlined.ForwardToInbox,
                label = "Pass",
                enabled = !aiThinking,
                onClick = onPass
            )
            ControlButton(
                icon = Icons.Outlined.Undo,
                label = "悔棋",
                enabled = !aiThinking,
                onClick = onUndo
            )
            ControlButton(
                icon = Icons.Outlined.Flag,
                label = "认输",
                enabled = !aiThinking,
                onClick = onResign
            )

            Spacer(Modifier.width(4.dp))

            // 新对局始终可用（会取消进行中的 AI 思考）
            ControlButton(
                icon = Icons.Outlined.AddCircle,
                label = "新局",
                enabled = true,
                onClick = onNewGame
            )

            Spacer(Modifier.width(4.dp))

            // 导出与分析
            ControlButton(
                icon = Icons.Outlined.Save,
                label = "存谱",
                enabled = true,
                onClick = onSaveSgf
            )
            ControlButton(
                icon = Icons.Outlined.Analytics,
                label = "分析",
                enabled = !aiThinking,
                onClick = onAnalyze
            )

            // AI 思考加载圈
            if (aiThinking) {
                Spacer(Modifier.width(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "AI 思考中",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
    }
}

/**
 * 控制条中的单个图标按钮。
 *
 * @param icon 图标。
 * @param label 文本。
 * @param enabled 是否启用。
 * @param onClick 点击回调。
 */
@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 10.dp,
            vertical = 6.dp
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
