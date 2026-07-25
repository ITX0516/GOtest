package com.weiqi.app.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 复盘导航条。
 *
 * 包含：首 / 上一步 / 下一步 / 末按钮 + 当前位置/总数显示 + 滑块跳转。
 *
 * @param currentIndex 当前着手索引（0 = 初始局面）。
 * @param totalMoves 总手数。
 * @param onFirst 跳到首步。
 * @param onPrev 上一步。
 * @param onNext 下一步。
 * @param onLast 跳到末步。
 * @param onJumpTo 滑块拖动到指定手数时回调。
 * @param modifier 修饰符。
 */
@androidx.compose.runtime.Composable
fun ReviewNavigator(
    currentIndex: Int,
    totalMoves: Int,
    onFirst: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
    onJumpTo: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeTotal = totalMoves.coerceAtLeast(1)
    val safeIndex = currentIndex.coerceIn(0, safeTotal)
    val atFirst = safeIndex <= 0
    val atLast = safeIndex >= safeTotal

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onFirst, enabled = !atFirst) {
                Icon(
                    imageVector = Icons.Default.FirstPage,
                    contentDescription = "首步",
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onPrev, enabled = !atFirst) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "上一步",
                    modifier = Modifier.size(28.dp)
                )
            }

            // 当前位置 / 总数显示
            Text(
                text = "$safeIndex / $safeTotal",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            IconButton(onClick = onNext, enabled = !atLast) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "下一步",
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onLast, enabled = !atLast) {
                Icon(
                    imageVector = Icons.Default.LastPage,
                    contentDescription = "末步",
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // 滑块跳转
        Slider(
            value = safeIndex.toFloat(),
            onValueChange = { onJumpTo(it.toInt()) },
            valueRange = 0f..safeTotal.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
