package com.weiqi.app.ui.board

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import com.weiqi.app.core.Stone
import com.weiqi.app.core.Vertex
import kotlin.math.roundToInt

/**
 * 引擎分析覆盖层绘制原语。
 *
 * 提供候选手标记、胜率热力方块、着手序号等低层 Canvas 绘制函数，
 * 由 [AnalysisOverlay] 组合使用，也可被其他自定义覆盖层直接调用。
 *
 * 坐标体系沿用 [BoardPainter]：交点 (x,y) 像素中心为 ((x+1)*cell, (y+1)*cell)。
 */
object BoardOverlays {

    /** 候选环颜色（半透明蓝）。 */
    private val CANDIDATE_COLOR = Color(0x880055AA)

    /** 最佳着手标记颜色（绿）。 */
    private val BEST_COLOR = Color(0xFF2E7D32)

    /** 热力图低胜率色（红）。 */
    private val HEAT_LOW = Color(0x66CC3333)

    /** 热力图高胜率色（绿）。 */
    private val HEAT_HIGH = Color(0x6633CC33)

    /**
     * 绘制候选手标记：半透明圆环 + 胜率百分比；最佳着手额外绘制绿色三角形。
     *
     * @param drawScope 绘制作用域。
     * @param center 标记中心像素坐标。
     * @param radius 标记半径（通常为半格大小）。
     * @param winRate 胜率 0.0..1.0。
     * @param isBest 是否为最佳着手。
     */
    fun drawCandidateMarker(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        winRate: Double,
        isBest: Boolean
    ) {
        drawScope.drawCircle(
            color = if (isBest) BEST_COLOR.copy(alpha = 0.55f) else CANDIDATE_COLOR,
            radius = radius,
            center = center,
            style = Stroke(width = radius * 0.18f)
        )
        // 胜率百分比
        val nc = drawScope.drawContext.canvas.nativeCanvas
        val paint = Paint().apply {
            color = (if (isBest) BEST_COLOR else Color(0xEEFFFFFF)).toArgb()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = radius * 0.9f
            setFakeBoldText(true)
        }
        nc.drawText("${(winRate * 100).roundToInt()}%", center.x, center.y + paint.textSize / 3f, paint)

        // 最佳着手：底部绿色三角形
        if (isBest) {
            val path = Path().apply {
                moveTo(center.x, center.y + radius * 0.55f)
                lineTo(center.x - radius * 0.4f, center.y + radius * 1.0f)
                lineTo(center.x + radius * 0.4f, center.y + radius * 1.0f)
                close()
            }
            drawScope.drawPath(path, color = BEST_COLOR)
        }
    }

    /**
     * 在指定交点绘制胜率热力方块（红→绿渐变）。
     *
     * @param drawScope 绘制作用域。
     * @param vertex 交点坐标。
     * @param winRate 胜率 0.0..1.0。
     * @param cellSize 单格像素尺寸。
     */
    fun drawWinRateHeatmap(
        drawScope: DrawScope,
        vertex: Vertex,
        winRate: Double,
        cellSize: Float
    ) {
        val center = Offset((vertex.x + 1) * cellSize, (vertex.y + 1) * cellSize)
        val ratio = winRate.coerceIn(0.0, 1.0).toFloat()
        val color = lerpColor(HEAT_LOW, HEAT_HIGH, ratio)
        drawScope.drawRect(
            color = color,
            topLeft = Offset(center.x - cellSize / 2f, center.y - cellSize / 2f),
            size = androidx.compose.ui.geometry.Size(cellSize, cellSize)
        )
    }

    /**
     * 在棋子上绘制着手序号（与棋子反色）。
     *
     * @param drawScope 绘制作用域。
     * @param center 棋子中心。
     * @param radius 棋子半径。
     * @param number 着手序号（从 1 开始）。
     * @param stone 棋子颜色，决定文字颜色。
     */
    fun drawMoveNumber(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        number: Int,
        stone: Stone
    ) {
        val nc = drawScope.drawContext.canvas.nativeCanvas
        val paint = Paint().apply {
            color = if (stone == Stone.BLACK) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = radius * 1.0f
            setFakeBoldText(true)
        }
        nc.drawText(number.toString(), center.x, center.y + paint.textSize / 3f, paint)
    }

    /** 线性插值两个 ARGB 颜色。 */
    private fun lerpColor(a: Color, b: Color, t: Float): Color {
        val tt = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * tt,
            green = a.green + (b.green - a.green) * tt,
            blue = a.blue + (b.blue - a.blue) * tt,
            alpha = a.alpha + (b.alpha - a.alpha) * tt
        )
    }
}

/**
 * 引擎分析覆盖层 Composable。
 *
 * 在自身可用空间内居中绘制一个正方形区域，将 [analysis] 中的候选手以圆环 + 胜率
 * 形式叠加。最佳着手额外标注绿色三角形。棋盘路数默认取 [Vertex.DEFAULT_SIZE]。
 *
 * 通常由外层将其作为 [BoardView] 上方透明层使用，或直接置于等大正方形布局中。
 *
 * @param analysis 引擎分析结果。
 * @param boardPainter 历史参数，保留以兼容契约，内部不使用。
 * @param modifier 布局修饰符。
 */
@androidx.compose.runtime.Composable
fun AnalysisOverlay(
    analysis: com.weiqi.app.engine.AnalysisResult,
    @Suppress("UNUSED_PARAMETER") boardPainter: BoardPainter,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    val gridSize = Vertex.DEFAULT_SIZE
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val boardPx = minOf(size.width, size.height)
        val cell = BoardPainter.cellSize(boardPx, gridSize)
        val radius = cell * 0.45f
        for (candidate in analysis.candidates) {
            val v = candidate.vertex
            if (v.isPass()) continue
            val center = BoardPainter.vertexToPixel(v, boardPx, gridSize)
            val isBest = analysis.bestMove is com.weiqi.app.core.Move.Play &&
                (analysis.bestMove as com.weiqi.app.core.Move.Play).vertex == v
            BoardOverlays.drawCandidateMarker(
                drawScope = this,
                center = center,
                radius = radius,
                winRate = candidate.winRate,
                isBest = isBest
            )
        }
    }
}
