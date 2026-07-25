package com.weiqi.app.ui.board

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import com.weiqi.app.core.Vertex
import com.weiqi.app.ui.theme.BoardTheme
import kotlin.math.roundToInt

/**
 * 棋盘底层绘制器。
 *
 * 负责在 [DrawScope] 上绘制棋盘背景、网格线、星位、外框加粗边框与坐标尺。
 * 坐标体系与 [Vertex] 一致：(0,0) 在左上角。
 *
 * 布局约定：棋盘为正方形，整体留出 1 个 [cellSize] 的内边距用于绘制坐标，
 * 因此 [cellSize] = 棋盘像素尺寸 / (路数 + 1)，交点 (x,y) 的像素中心为
 * ((x+1)*cell, (y+1)*cell)。
 */
object BoardPainter {

    /** 网格线宽度占格子边长比例。 */
    private const val LINE_STROKE_RATIO = 0.02f

    /** 外框线宽度占格子边长比例。 */
    private const val BORDER_STROKE_RATIO = 0.06f

    /** 星位半径占格子边长比例。 */
    private const val STAR_RATIO = 0.11f

    /** 坐标字号占格子边长比例。 */
    private const val COORD_TEXT_RATIO = 0.5f

    /**
     * 绘制完整棋盘（背景 + 网格 + 星位 + 外框 + 可选坐标）。
     *
     * @param drawScope 绘制作用域。
     * @param size 棋盘路数（如 19）。
     * @param theme 棋盘主题。
     * @param showCoordinates 是否绘制外侧坐标尺。
     */
    fun drawBoard(
        drawScope: DrawScope,
        size: Int,
        theme: BoardTheme,
        showCoordinates: Boolean
    ) {
        val px = drawScope.size
        val boardPx = minOf(px.width, px.height)
        val cell = cellSize(boardPx, size)
        val lineStroke = cell * LINE_STROKE_RATIO
        val borderStroke = cell * BORDER_STROKE_RATIO
        val last = size * cell

        // 1. 棋盘底色
        drawScope.drawRect(color = theme.boardColor, topLeft = Offset.Zero, size = px)

        // 2. 网格线
        for (i in 0 until size) {
            val p = (i + 1) * cell
            drawScope.drawLine(theme.lineColor, Offset(p, cell), Offset(p, last), lineStroke)
            drawScope.drawLine(theme.lineColor, Offset(cell, p), Offset(last, p), lineStroke)
        }

        // 3. 外框加粗边框
        drawScope.drawRect(
            color = theme.lineColor,
            topLeft = Offset(cell, cell),
            size = Size((size - 1) * cell, (size - 1) * cell),
            style = Stroke(width = borderStroke)
        )

        // 4. 星位
        for (sp in starPoints(size)) {
            val c = vertexToPixel(sp, boardPx, size)
            drawScope.drawCircle(theme.starPointColor, cell * STAR_RATIO, c)
        }

        // 5. 坐标
        if (showCoordinates) {
            drawCoordinates(drawScope, boardPx, size, cell, theme)
        }
    }

    /**
     * 计算单格像素尺寸。
     * @param boardPixelSize 棋盘总像素尺寸（正方形边长）。
     * @param gridSize 棋盘路数。
     */
    fun cellSize(boardPixelSize: Float, gridSize: Int): Float = boardPixelSize / (gridSize + 1)

    /**
     * 将交点坐标转换为像素中心。
     */
    fun vertexToPixel(v: Vertex, boardPixelSize: Float, gridSize: Int): Offset {
        val cell = cellSize(boardPixelSize, gridSize)
        return Offset((v.x + 1) * cell, (v.y + 1) * cell)
    }

    /**
     * 将像素坐标反向映射成交点；落在棋盘外或内边距区域返回 null。
     */
    fun pixelToVertex(p: Offset, boardPixelSize: Float, gridSize: Int): Vertex? {
        val cell = cellSize(boardPixelSize, gridSize)
        val gx = (p.x / cell - 1f).roundToInt()
        val gy = (p.y / cell - 1f).roundToInt()
        return if (gx in 0 until gridSize && gy in 0 until gridSize) Vertex(gx, gy) else null
    }

    /** 绘制外侧坐标尺（顶部/底部字母，左/右数字，字母跳过 I）。 */
    private fun drawCoordinates(
        drawScope: DrawScope,
        boardPx: Float,
        grid: Int,
        cell: Float,
        theme: BoardTheme
    ) {
        val nc = drawScope.drawContext.canvas.nativeCanvas
        val paint = Paint().apply {
            color = theme.coordinateColor.toArgb()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = cell * COORD_TEXT_RATIO
        }
        val cols = Vertex.COLS_SKIP_I
        val half = cell * 0.5f
        val baselineOffset = paint.textSize / 3f
        for (i in 0 until grid) {
            val letter = if (i < cols.length) cols[i].toString() else "?"
            val cx = (i + 1) * cell
            // 顶/底 字母
            nc.drawText(letter, cx, half + baselineOffset, paint)
            nc.drawText(letter, cx, boardPx - half + baselineOffset, paint)
            // 左/右 数字（顶部为最大行号）
            val num = (grid - i).toString()
            val cy = (i + 1) * cell + baselineOffset
            nc.drawText(num, half, cy, paint)
            nc.drawText(num, boardPx - half, cy, paint)
        }
    }

    /** 返回指定路数的星位列表。19/13/9 路使用标准星位，其他尺寸按对角 + 中心推断。 */
    private fun starPoints(gridSize: Int): List<Vertex> = when (gridSize) {
        19 -> Vertex.STAR_POINTS_19
        13 -> listOf(
            Vertex(3, 3), Vertex(9, 3),
            Vertex(3, 9), Vertex(9, 9),
            Vertex(6, 6)
        )
        9 -> listOf(
            Vertex(2, 2), Vertex(6, 2),
            Vertex(2, 6), Vertex(6, 6),
            Vertex(4, 4)
        )
        else -> {
            val edge = if (gridSize >= 13) 3 else 2
            val last = gridSize - 1 - edge
            buildList {
                add(Vertex(edge, edge)); add(Vertex(last, edge))
                add(Vertex(edge, last)); add(Vertex(last, last))
                if (gridSize % 2 == 1) add(Vertex(gridSize / 2, gridSize / 2))
            }
        }
    }
}
