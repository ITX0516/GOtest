package com.weiqi.app.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * 胜率折线图。
 *
 * 用 Canvas 绘制：
 * - x 轴：手数 0..N
 * - y 轴：黑方胜率 0..1
 * - 50% 处虚线基准
 * - 当前进度竖线标记
 * - 点击图上某点跳转到对应手数
 *
 * @param samples 胜率采样点（按 moveIndex 排序，黑方视角）。
 * @param currentIndex 当前后退/前进所停手数。
 * @param onJumpTo 点击图上某点时回调，参数为目标手数。
 * @param modifier 修饰符。
 */
@androidx.compose.runtime.Composable
fun WinRateChart(
    samples: List<WinRateSample>,
    currentIndex: Int,
    onJumpTo: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorLine = MaterialTheme.colorScheme.primary
    val colorBaseline = MaterialTheme.colorScheme.outline
    val colorCurrent = MaterialTheme.colorScheme.tertiary
    val colorGrid = MaterialTheme.colorScheme.outlineVariant
    val colorFillBlack = colorLine.copy(alpha = 0.15f)
    val colorFillWhite = colorLine.copy(alpha = 0.05f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 按手数排序、去重，便于绘制连续折线
    val sortedSamples = remember(samples) {
        samples.sortedBy { it.moveIndex }.distinctBy { it.moveIndex }
    }
    val maxIndex = remember(sortedSamples) {
        sortedSamples.maxOfOrNull { it.moveIndex } ?: 0
    }
    // x 轴范围：至少到 currentIndex 与最大采样点的较大者，保证留有余量
    val xRange = max(maxIndex, currentIndex).coerceAtLeast(1)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .pointerInput(xRange) {
                // 监听点击：根据 x 坐标反推手数
                detectTapGestures { offset ->
                    val width = size.width
                    if (width <= 0f) return@detectTapGestures
                    val ratio = (offset.x / width).coerceIn(0f, 1f)
                    val index = (ratio * xRange).toInt()
                    onJumpTo(index)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // 上下留白，避免贴边
            val padTop = 12f
            val padBottom = 12f
            val plotH = h - padTop - padBottom

            // 1) 50% 基准虚线
            val baselineY = padTop + plotH * 0.5f
            drawLine(
                color = colorBaseline,
                start = Offset(0f, baselineY),
                end = Offset(w, baselineY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )

            // 2) 上下区域淡色填充，提示黑白优势
            drawRect(
                color = colorFillBlack,
                topLeft = Offset(0f, padTop),
                size = Size(w, plotH * 0.5f)
            )
            drawRect(
                color = colorFillWhite,
                topLeft = Offset(0f, baselineY),
                size = Size(w, plotH * 0.5f)
            )

            if (sortedSamples.isEmpty()) {
                // 无采样点时仅显示基准
                return@Canvas
            }

            // 3) 折线
            val path = Path()
            sortedSamples.forEachIndexed { i, sample ->
                val x = if (xRange == 0) 0f else (sample.moveIndex.toFloat() / xRange) * w
                // blackWinRate=1 -> 顶部，0 -> 底部
                val y = padTop + (1f - sample.blackWinRate.toFloat().coerceIn(0f, 1f)) * plotH
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = colorLine,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
            )

            // 4) 采样点小圆点
            sortedSamples.forEach { sample ->
                val x = if (xRange == 0) 0f else (sample.moveIndex.toFloat() / xRange) * w
                val y = padTop + (1f - sample.blackWinRate.toFloat().coerceIn(0f, 1f)) * plotH
                drawCircle(
                    color = colorLine,
                    radius = 3f,
                    center = Offset(x, y)
                )
            }

            // 5) 当前进度竖线
            if (currentIndex in 0..xRange) {
                val cx = if (xRange == 0) 0f else (currentIndex.toFloat() / xRange) * w
                drawLine(
                    color = colorCurrent,
                    start = Offset(cx, padTop),
                    end = Offset(cx, padTop + plotH),
                    strokeWidth = 2f
                )
                // 顶部小三角标记
                drawCircle(
                    color = colorCurrent,
                    radius = 4f,
                    center = Offset(cx, padTop)
                )
            }
        }

        // 左上角说明文字
        Text(
            text = "黑方胜率",
            style = TextStyle(color = textColor, fontSize = 10.sp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 4.dp, top = 2.dp)
        )
    }
}
