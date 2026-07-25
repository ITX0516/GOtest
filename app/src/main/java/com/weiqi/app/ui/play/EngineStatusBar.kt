package com.weiqi.app.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 引擎状态栏。
 *
 * 展示当前引擎名称、就绪状态指示灯、元信息及错误信息；
 * 点击"切换"按钮触发 [onSwitchEngine]（由上层弹出引擎选择对话框并调用 switchEngine）。
 *
 * @param status 引擎状态。
 * @param onSwitchEngine 切换引擎回调。
 * @param modifier 修饰符。
 */
@Composable
fun EngineStatusBar(
    status: EngineStatusState,
    onSwitchEngine: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 状态指示灯
                StatusDot(status)
                // 引擎名
                Text(
                    text = status.engineType?.displayName ?: "未启动",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.width(4.dp))
                // 切换按钮
                AssistChip(
                    onClick = onSwitchEngine,
                    label = { Text("切换") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.SwapHoriz,
                            contentDescription = "切换引擎",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            // 就绪状态文本
            val statusText = when {
                status.errorMessage != null -> null // 错误单独显示
                status.engineType == null -> "未启动引擎"
                status.isReady -> "已就绪"
                else -> "启动中…"
            }
            statusText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            // 元信息（名称、版本、线程数等）
            if (status.info.isNotEmpty() && status.engineType != null) {
                Text(
                    text = status.info.entries.joinToString("  ") { "${it.key}: ${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 错误信息
            status.errorMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 状态指示灯：绿色就绪、黄色启动中/ponder、红色出错、灰色未启动。
 */
@Composable
private fun StatusDot(status: EngineStatusState) {
    val color = when {
        status.errorMessage != null -> Color.Red
        status.engineType == null -> Color.Gray
        status.isReady -> Color(0xFF4CAF50) // 绿
        status.isPondering -> Color(0xFFFFC107) // 黄
        else -> Color(0xFFFFC107) // 启动中
    }
    Spacer(
        modifier = Modifier
            .size(10.dp)
            .background(color, shape = CircleShape)
    )
}
