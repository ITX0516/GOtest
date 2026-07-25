package com.weiqi.app.ui.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.weiqi.app.core.GameState
import com.weiqi.app.core.Move
import com.weiqi.app.core.Stone
import com.weiqi.app.core.Vertex
import com.weiqi.app.engine.AnalysisResult
import com.weiqi.app.ui.theme.BoardTheme
import com.weiqi.app.ui.theme.StoneTheme

/** 落子进入动画时长（毫秒）。 */
private const val STONE_ENTER_DURATION_MS = 200

/** 最近一步脉冲动画周期（毫秒）。 */
private const val PULSE_DURATION_MS = 900

/** 棋子半径占单格边长比例。 */
private const val STONE_RADIUS_RATIO = 0.46f

/**
 * 围棋棋盘主组件。
 *
 * 使用 [BoxWithConstraints] 测量可用空间，取宽高较小者作为正方形棋盘边长并居中。
 * Canvas 依次绘制棋盘背景、棋子、最近一步标记（含脉冲高亮）、着手序号、
 * 引擎分析覆盖层与待确认预览。指针输入按 [inputMode] 处理单击 / 双击 / 确认按钮三种落子方式，
 * 并在鼠标设备上提供悬停回调。
 *
 * 落子动画：最近一步使用 [Animatable] 做 200ms 缩放进入；最近一步圆环以脉冲动画高亮。
 *
 * @param state 当前对局状态。
 * @param boardTheme 棋盘主题。
 * @param stoneTheme 棋子主题。
 * @param inputMode 落子交互模式。
 * @param modifier 布局修饰符。
 * @param showCoordinates 是否显示坐标尺。
 * @param showMoveNumber 是否在棋子上显示着手序号。
 * @param lastMoveMarker 是否高亮最近一步。
 * @param analysisResult 可选的引擎分析结果，用于绘制候选手覆盖层。
 * @param onStoneClick 交点被点击/确认时的回调；坐标可能为 null（点击空白）。
 * @param onStoneHover 鼠标悬停回调；离开棋盘时为 null（仅鼠标设备生效）。
 * @param pendingVertex CONFIRM 模式下待确认的点，由外部控制。
 * @param onConfirmMove CONFIRM 模式下点击"确认"按钮时触发。
 * @param onCancelPending CONFIRM 模式下点击"取消"按钮时触发。
 */
@Composable
fun BoardView(
    state: GameState,
    boardTheme: BoardTheme,
    stoneTheme: StoneTheme,
    inputMode: StoneInputMode = StoneInputMode.SINGLE_TAP,
    modifier: Modifier = Modifier,
    showCoordinates: Boolean = true,
    showMoveNumber: Boolean = false,
    lastMoveMarker: Boolean = true,
    analysisResult: AnalysisResult? = null,
    onStoneClick: (Vertex) -> Unit = {},
    onStoneHover: (Vertex?) -> Unit = {},
    pendingVertex: Vertex? = null,
    onConfirmMove: () -> Unit = {},
    onCancelPending: () -> Unit = {}
) {
    BoxWithConstraints(modifier = modifier) {
        // 取较小边作为正方形棋盘边长
        val boardDp = minOf(maxWidth, maxHeight)
        if (boardDp <= 0.dp) return@BoxWithConstraints

        val gridSize = state.boardSize

        // 最近一步缩放进入动画（初值 0，避免恢复对局时首帧闪烁；非最后一步棋子固定用 1）
        val lastMoveScale = remember { Animatable(0f) }
        LaunchedEffect(state.lastMove()) {
            if (state.lastMove() != null) {
                lastMoveScale.snapTo(0f)
                lastMoveScale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(STONE_ENTER_DURATION_MS, easing = FastOutSlowInEasing)
                )
            }
        }

        // 最近一步脉冲圆环
        val infiniteTransition = rememberInfiniteTransition()
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(
                animation = tween(PULSE_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        // DOUBLE_TAP 模式下的本地预览点
        var doubleTapPreview by remember { mutableStateOf<Vertex?>(null) }

        Box(
            modifier = Modifier
                .size(boardDp)
                .pointerInput(inputMode, gridSize) {
                    // 单击 / 双击 / 确认按钮 落子处理
                    val boardPx = size.width.toFloat()
                    // 仅 DOUBLE_TAP 模式注册双击回调，避免其他模式产生双击判定延迟
                    val doubleTapHandler: ((Offset) -> Unit)? =
                        if (inputMode == StoneInputMode.DOUBLE_TAP) {
                            { pos ->
                                val v = BoardPainter.pixelToVertex(pos, boardPx, gridSize)
                                doubleTapPreview = null
                                if (v != null) onStoneClick(v)
                            }
                        } else {
                            null
                        }
                    detectTapGestures(
                        onTap = { pos ->
                            val v = BoardPainter.pixelToVertex(pos, boardPx, gridSize)
                            if (v != null) {
                                when (inputMode) {
                                    StoneInputMode.SINGLE_TAP -> onStoneClick(v)
                                    StoneInputMode.DOUBLE_TAP -> doubleTapPreview = v
                                    StoneInputMode.CONFIRM_BUTTON -> onStoneClick(v)
                                }
                            }
                        },
                        onDoubleTap = doubleTapHandler
                    )
                }
                .pointerInput(gridSize) {
                    // 鼠标悬停（仅鼠标设备生效）
                    val boardPx = size.width.toFloat()
                    awaitPointerEventScope {
                        var lastHovered: Vertex? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.type != PointerType.Mouse) continue
                            val v = BoardPainter.pixelToVertex(change.position, boardPx, gridSize)
                            if (v != lastHovered) {
                                lastHovered = v
                                onStoneHover(v)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 1. 棋盘底层
                BoardPainter.drawBoard(this, gridSize, boardTheme, showCoordinates)

                val boardPx = minOf(size.width, size.height)
                val cell = BoardPainter.cellSize(boardPx, gridSize)
                val stoneR = cell * STONE_RADIUS_RATIO
                val lastVertex = (state.lastMove() as? Move.Play)?.vertex

                // 2. 棋子
                for (x in 0 until gridSize) {
                    for (y in 0 until gridSize) {
                        val stone = state.board[x, y]
                        if (stone == Stone.EMPTY) continue
                        val center = BoardPainter.vertexToPixel(Vertex(x, y), boardPx, gridSize)
                        val isLast = lastVertex != null && lastVertex.x == x && lastVertex.y == y
                        val scale = if (isLast) lastMoveScale.value else 1f
                        StoneRenderer.drawStone(this, center, stoneR * scale, stone, stoneTheme)
                    }
                }

                // 3. 最近一步标记 + 脉冲圆环
                if (lastMoveMarker && lastVertex != null) {
                    val center = BoardPainter.vertexToPixel(lastVertex, boardPx, gridSize)
                    val stone = state.board[lastVertex]
                    StoneRenderer.drawLastMoveMarker(this, center, stoneR, stone)
                    val pulseColor = if (stone == Stone.BLACK) Color.White else Color.Black
                    drawCircle(
                        color = pulseColor,
                        radius = stoneR * (1.05f + pulseAlpha * 0.25f),
                        center = center,
                        alpha = pulseAlpha * 0.6f,
                        style = Stroke(width = stoneR * 0.12f)
                    )
                }

                // 4. 着手序号
                if (showMoveNumber) {
                    state.moveHistory.forEachIndexed { index, move ->
                        val v = (move as? Move.Play)?.vertex ?: return@forEachIndexed
                        val stone = state.board[v]
                        if (stone == Stone.EMPTY) return@forEachIndexed
                        val center = BoardPainter.vertexToPixel(v, boardPx, gridSize)
                        BoardOverlays.drawMoveNumber(this, center, stoneR, index + 1, stone)
                    }
                }

                // 5. 引擎分析覆盖层
                analysisResult?.let { ar ->
                    for (candidate in ar.candidates) {
                        val v = candidate.vertex
                        if (v.isPass()) continue
                        val center = BoardPainter.vertexToPixel(v, boardPx, gridSize)
                        val isBest = (ar.bestMove as? Move.Play)?.vertex == v
                        BoardOverlays.drawCandidateMarker(
                            drawScope = this,
                            center = center,
                            radius = cell * 0.45f,
                            winRate = candidate.winRate,
                            isBest = isBest
                        )
                    }
                }

                // 6. 待确认预览（CONFIRM 模式由外部传入，DOUBLE_TAP 模式由本地维护）
                val preview = when (inputMode) {
                    StoneInputMode.CONFIRM_BUTTON -> pendingVertex
                    StoneInputMode.DOUBLE_TAP -> doubleTapPreview
                    else -> null
                }
                preview?.let { v ->
                    if (!v.isPass() && state.board[v] == Stone.EMPTY) {
                        val center = BoardPainter.vertexToPixel(v, boardPx, gridSize)
                        val previewColor = if (state.toMove == Stone.BLACK) stoneTheme.blackFill else stoneTheme.whiteFill
                        drawCircle(previewColor, stoneR, center, alpha = 0.5f)
                        drawCircle(
                            color = Color.White,
                            radius = stoneR * 1.1f,
                            center = center,
                            alpha = 0.6f,
                            style = Stroke(width = stoneR * 0.1f)
                        )
                    }
                }
            }

            // CONFIRM 模式右下角确认 / 取消按钮
            if (inputMode == StoneInputMode.CONFIRM_BUTTON && pendingVertex != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onCancelPending) {
                        Text("取消")
                    }
                    Button(onClick = onConfirmMove) {
                        Text("确认")
                    }
                }
            }
        }
    }
}
