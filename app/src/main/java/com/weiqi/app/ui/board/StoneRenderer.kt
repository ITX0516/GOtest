package com.weiqi.app.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.weiqi.app.core.Stone
import com.weiqi.app.ui.theme.StoneTheme

/**
 * 棋子渲染器。
 *
 * 在 [DrawScope] 上绘制带径向渐变与阴影的立体棋子。
 * 黑棋高光来自左上角，白棋使用柔和高光，落子时附带椭圆阴影。
 */
object StoneRenderer {

    /** 阴影相对棋子的偏移比例（向右下）。 */
    private const val SHADOW_OFFSET_RATIO = 0.12f

    /** 阴影纵向拉伸比例（模拟椭圆投影）。 */
    private const val SHADOW_DROP_RATIO = 0.18f

    /** 高光中心相对棋子中心的偏移比例（向左上）。 */
    private const val HIGHLIGHT_OFFSET_RATIO = 0.35f

    /** 渐变半径相对棋子半径的倍数。 */
    private const val GRADIENT_RADIUS_RATIO = 1.4f

    /**
     * 绘制单颗棋子。
     *
     * @param drawScope 绘制作用域。
     * @param center 棋子中心像素坐标。
     * @param radius 棋子半径。
     * @param stone 棋子颜色；[Stone.EMPTY] 直接返回。
     * @param theme 棋子主题。
     */
    fun drawStone(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        stone: Stone,
        theme: StoneTheme
    ) {
        if (stone == Stone.EMPTY || radius <= 0f) return

        // 落子阴影（偏右下的椭圆，用圆近似）
        drawScope.drawCircle(
            color = Color.Black,
            radius = radius * 0.95f,
            center = Offset(
                center.x + radius * SHADOW_OFFSET_RATIO,
                center.y + radius * SHADOW_DROP_RATIO
            ),
            alpha = theme.shadowAlpha
        )

        // 棋子主体径向渐变
        val isBlack = stone == Stone.BLACK
        val fill = if (isBlack) theme.blackFill else theme.whiteFill
        val highlight = if (isBlack) theme.blackHighlight else theme.whiteHighlight
        val lightCenter = Offset(
            center.x - radius * HIGHLIGHT_OFFSET_RATIO,
            center.y - radius * HIGHLIGHT_OFFSET_RATIO
        )
        val brush = Brush.radialGradient(
            colors = listOf(highlight, fill, fill),
            center = lightCenter,
            radius = radius * GRADIENT_RADIUS_RATIO
        )
        drawScope.drawCircle(brush = brush, radius = radius, center = center)

        // 白子加一圈浅灰描边增强边缘
        if (!isBlack) {
            drawScope.drawCircle(
                color = Color(0xFFB8B8B8),
                radius = radius,
                center = center,
                style = Stroke(width = radius * 0.06f)
            )
        }
    }

    /**
     * 在棋子上绘制最近一步标记（脉冲圆环由调用方动画驱动，
     * 此处仅绘制静态标记环）。
     *
     * 黑子上用浅色环，白子上用深色环。
     *
     * @param drawScope 绘制作用域。
     * @param center 棋子中心。
     * @param radius 棋子半径。
     * @param stone 棋子颜色。
     */
    fun drawLastMoveMarker(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        stone: Stone
    ) {
        if (stone == Stone.EMPTY) return
        val color = if (stone == Stone.BLACK) Color(0xE6FFFFFF) else Color(0xE6000000)
        drawScope.drawCircle(
            color = color,
            radius = radius * 0.42f,
            center = center,
            style = Stroke(width = radius * 0.12f)
        )
    }
}
