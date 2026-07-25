package com.weiqi.app.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weiqi.app.core.GameState
import com.weiqi.app.core.Move
import com.weiqi.app.core.Stone
import com.weiqi.app.ui.board.BoardView
import com.weiqi.app.ui.theme.BoardTheme
import com.weiqi.app.ui.theme.StoneTheme

/**
 * 主要变化（PV）预览弹窗。
 *
 * 在弹窗内显示一个迷你 [BoardView]，按 [moves] 序列摆出来，让用户预览主要变化。
 * 顶部展示 PV 着手坐标序列，底部提供关闭按钮。
 *
 * @param moves PV 着手序列（按顺序）。
 * @param boardSize 棋盘路数。
 * @param onDismiss 关闭弹窗回调。
 * @param modifier 修饰符。
 * @param boardTheme 棋盘主题，默认 [BoardTheme.Classic]。
 * @param stoneTheme 棋子主题，默认 [StoneTheme.Standard]。
 */
@androidx.compose.runtime.Composable
fun PvPreviewDialog(
    moves: List<Move>,
    boardSize: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    boardTheme: BoardTheme = BoardTheme.Classic,
    stoneTheme: StoneTheme = StoneTheme.Standard
) {
    // 从 newGame 开始依次应用 PV 着手，构建预览局面
    val previewState = remember(moves, boardSize) {
        buildPreviewState(moves, boardSize)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "主要变化预览",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭"
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 迷你棋盘：保持正方形比例
                BoardView(
                    state = previewState,
                    boardTheme = boardTheme,
                    stoneTheme = stoneTheme,
                    showCoordinates = false,
                    showMoveNumber = true,
                    analysisResult = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                )

                // PV 坐标序列展示
                Text(
                    text = "PV 序列",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(moves.withIndex().toList(), key = { it.index }) { (idx, move) ->
                        PvMoveChip(index = idx + 1, move = move)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * PV 序列中单个着手的小标签。
 */
@Composable
private fun PvMoveChip(index: Int, move: Move) {
    val stone = move.stone
    val coord = when (move) {
        is Move.Play -> move.vertex.displayCoord
        is Move.Pass -> "Pass"
        is Move.Resign -> "Resign"
    }
    val stoneLabel = if (stone == Stone.BLACK) "●" else "○"
    androidx.compose.material3.Surface(
        modifier = Modifier.sizeIn(minWidth = 44.dp),
        shape = RoundedCornerShape(4.dp),
        color = if (stone == Stone.BLACK) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = "$index.$stoneLabel $coord",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp
            ),
            color = if (stone == Stone.BLACK) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

/**
 * 从 newGame 开始依次应用 [moves] 构建预览局面。
 * 跳过 Resign 着手（无法在棋盘上呈现）。
 */
private fun buildPreviewState(moves: List<Move>, boardSize: Int): GameState {
    var state = GameState.newGame(size = boardSize)
    for (m in moves) {
        if (m is Move.Resign) continue
        state = state.applyMove(m)
    }
    return state
}
