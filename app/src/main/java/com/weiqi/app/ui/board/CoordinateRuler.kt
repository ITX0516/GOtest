package com.weiqi.app.ui.board

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.weiqi.app.core.Vertex

/**
 * 坐标尺方向。
 *
 * - [HORIZONTAL]：水平排列字母（A、B、…，跳过 I），常置于棋盘上/下方。
 * - [VERTICAL]：竖直排列数字（自上而下为大号→小号），常置于棋盘左/右侧。
 */
enum class Orientation { HORIZONTAL, VERTICAL }

/**
 * 棋盘外侧坐标尺。
 *
 * 在自身尺寸内均匀排列 [size] 个字符：
 * - [Orientation.HORIZONTAL] 时绘制字母（取自 [Vertex.COLS_SKIP_I] 前 [size] 个，跳过 I）；
 * - [Orientation.VERTICAL] 时绘制数字（自上而下为 size..1）。
 *
 * 棋盘正方形居中布局时，外层可用本组件在四条边各放一个，并传入对应方向。
 *
 * @param size 交点数量（即坐标字符数量）。
 * @param color 字符颜色。
 * @param orientation 排列方向。
 * @param modifier 布局修饰符。
 */
@Composable
fun CoordinateRuler(
    size: Int,
    color: Color,
    orientation: Orientation,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = this.size.width
        val h = this.size.height
        val nc = drawContext.canvas.nativeCanvas
        val cell = if (orientation == Orientation.HORIZONTAL) w / size else h / size
        val paint = Paint().apply {
            this.color = color.toArgb()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = cell * 0.55f
        }
        val baselineOffset = paint.textSize / 3f
        val cols = Vertex.COLS_SKIP_I
        when (orientation) {
            Orientation.HORIZONTAL -> {
                val cy = h / 2f + baselineOffset
                for (i in 0 until size) {
                    val label = if (i < cols.length) cols[i].toString() else "?"
                    val cx = (i + 0.5f) * cell
                    nc.drawText(label, cx, cy, paint)
                }
            }
            Orientation.VERTICAL -> {
                val cx = w / 2f
                for (i in 0 until size) {
                    val label = (size - i).toString()
                    val cy = (i + 0.5f) * cell + baselineOffset
                    nc.drawText(label, cx, cy, paint)
                }
            }
        }
    }
}
