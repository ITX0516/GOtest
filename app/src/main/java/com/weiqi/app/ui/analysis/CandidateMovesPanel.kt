package com.weiqi.app.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weiqi.app.core.Vertex
import com.weiqi.app.engine.MoveCandidate

/**
 * 候选着手列表面板。
 *
 * 展示每个候选手的坐标、胜率、目数差、访问数；
 * 点击候选项触发 [onCandidateClick] 回调，棋盘高亮由 BoardView 的 analysisResult 渲染。
 *
 * @param candidates 候选着手列表（按访问数排序）。
 * @param onCandidateClick 点击候选项回调，参数为该着手坐标。
 * @param modifier 修饰符。
 * @param orientation 布局方向：[Orientation.Vertical] 为纵向列表，[Orientation.Horizontal] 为横向列表。
 */
@androidx.compose.runtime.Composable
fun CandidateMovesPanel(
    candidates: List<MoveCandidate>,
    onCandidateClick: (Vertex) -> Unit,
    modifier: Modifier = Modifier,
    orientation: PanelOrientation = PanelOrientation.Vertical
) {
    if (candidates.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无候选手",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    if (orientation == PanelOrientation.Horizontal) {
        // 竖屏布局使用横向列表
        LazyRow(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(candidates, key = { it.vertex.hashCode() }) { candidate ->
                CandidateItem(
                    candidate = candidate,
                    rank = candidates.indexOf(candidate) + 1,
                    onClick = { onCandidateClick(candidate.vertex) },
                    modifier = Modifier.widthIn(min = 120.dp, max = 160.dp)
                )
            }
        }
    } else {
        // 横屏布局使用纵向列表
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(candidates, key = { it.vertex.hashCode() }) { candidate ->
                CandidateItem(
                    candidate = candidate,
                    rank = candidates.indexOf(candidate) + 1,
                    onClick = { onCandidateClick(candidate.vertex) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 面板布局方向。 */
enum class PanelOrientation {
    /** 纵向列表。 */
    Vertical,
    /** 横向列表。 */
    Horizontal
}

/**
 * 单个候选项卡片。
 *
 * @param candidate 候选着手数据。
 * @param rank 排名（1-based）。
 * @param onClick 点击回调。
 * @param modifier 修饰符。
 */
@Composable
private fun CandidateItem(
    candidate: MoveCandidate,
    rank: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 排名 + 棋色指示圆点
        RankBadge(rank = rank, color = colorForRank(rank))
        Spacer(modifier = Modifier.width(8.dp))

        // 坐标
        Text(
            text = formatVertex(candidate.vertex),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 胜率 / 目数差 / 访问数
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "胜率 ${formatPercent(candidate.winRate)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatScoreLead(candidate.scoreLead),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = "访问 ${candidate.visits}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 排名指示圆点。 */
@Composable
private fun RankBadge(rank: Int, color: Color) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            ),
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/** 不同名次使用不同强调色（前 3 名突出）。 */
private fun colorForRank(rank: Int): Color = when (rank) {
    1 -> Color(0xFFE65100) // 橙红，最佳着
    2 -> Color(0xFF1565C0) // 蓝
    3 -> Color(0xFF2E7D32) // 绿
    else -> Color(0xFF616161) // 灰
}

/** 格式化坐标：Pass 显示为 "Pass"，其他显示 GTP 坐标。 */
private fun formatVertex(vertex: Vertex): String {
    if (vertex.isPass()) return "Pass"
    return vertex.displayCoord
}

/** 格式化胜率百分比：0.623 -> "62.3%"。 */
private fun formatPercent(rate: Double): String {
    return "%.1f%%".format(rate * 100.0)
}

/** 格式化目数差：正数加 "+"。 */
private fun formatScoreLead(lead: Double): String {
    val sign = if (lead >= 0) "+" else ""
    return "$sign${"%.1f".format(lead)}目"
}
