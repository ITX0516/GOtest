package com.weiqi.app.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weiqi.app.core.Move
import com.weiqi.app.core.Stone

/**
 * 走子列表面板（复盘用）。
 *
 * 显示从起手到当前的全部着手，高亮当前所在位置；点击任意行可跳转到该手之后的状态。
 *
 * 索引语义：[currentIndex] 表示"已应用的着手数"（0 = 起手局面，k = 第 k 手之后）。
 * 列表中第 i 个元素（0-based）对应位置 i+1。点击该行触发 [onJumpTo](i+1)。
 *
 * @param moves 着手列表（按顺序）。
 * @param currentIndex 当前已应用的着手数（0..moves.size）。
 * @param onJumpTo 跳转回调，参数为目标着手数。
 * @param modifier 修饰符。
 */
@Composable
fun MoveListPanel(
    moves: List<Move>,
    currentIndex: Int,
    onJumpTo: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "着手记录",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 4.dp,
                    vertical = 4.dp
                ),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                // 起手行：currentIndex == 0 时高亮
                item(key = "start") {
                    MoveRow(
                        number = 0,
                        stoneColor = null,
                        text = "起手",
                        highlighted = currentIndex == 0,
                        onClick = { onJumpTo(0) }
                    )
                }
                // 每一着手
                itemsIndexed(moves) { index, move ->
                    val moveNumber = index + 1
                    MoveRow(
                        number = moveNumber,
                        stoneColor = move.stone,
                        text = moveDisplayText(move),
                        highlighted = currentIndex == moveNumber,
                        onClick = { onJumpTo(moveNumber) }
                    )
                }
            }
        }
    }
}

/**
 * 单行走子记录。
 *
 * @param number 着手序号（0 表示起手）。
 * @param stoneColor 棋子颜色；起手为 null。
 * @param text 着手描述（坐标 / Pass / 认输）。
 * @param highlighted 是否高亮当前。
 * @param onClick 点击回调。
 */
@Composable
private fun MoveRow(
    number: Int,
    stoneColor: Stone?,
    text: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    val background = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 棋子颜色圆点
        StoneDot(stoneColor)
        // 序号
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(34.dp)
        )
        // 着手描述
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 棋子颜色圆点；[color] 为 null 时显示占位。 */
@Composable
private fun StoneDot(color: Stone?) {
    val dotColor = when (color) {
        Stone.BLACK -> Color.Black
        Stone.WHITE -> Color.White
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(14.dp)
            .background(dotColor, shape = CircleShape)
            .padding(1.dp)
    )
}

/** 将着手转为可读文本。 */
private fun moveDisplayText(move: Move): String = when (move) {
    is Move.Play -> move.vertex.displayCoord
    is Move.Pass -> "Pass"
    is Move.Resign -> "认输"
}
