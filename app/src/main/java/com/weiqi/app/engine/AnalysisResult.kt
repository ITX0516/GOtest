package com.weiqi.app.engine

import com.weiqi.app.core.Move
import com.weiqi.app.core.Vertex

/**
 * 单个候选着手及其分析数据。
 *
 * @param vertex 着手坐标（Pass 时为 [Vertex.pass]）。
 * @param winRate 胜率，0.0..1.0。
 * @param scoreLead 领先目数（黑视角为正表示黑领先）。
 * @param visits 该候选手的访问数。
 * @param pv 主要变化（Principal Variation）着手序列。
 */
data class MoveCandidate(
    val vertex: Vertex,
    val winRate: Double,
    val scoreLead: Double,
    val visits: Int,
    val pv: List<Move>
)

/**
 * 引擎分析结果。
 *
 * @param bestMove 引擎推荐的最佳着手。
 * @param winRate 当前局面胜率（行棋方视角），0.0..1.0。
 * @param scoreLead 当前局面领先目数（行棋方视角为正）。
 * @param visits 总访问数。
 * @param candidates 候选着手列表，按访问数排序。
 * @param isResign 引擎是否建议认输。
 */
data class AnalysisResult(
    val bestMove: Move,
    val winRate: Double,
    val scoreLead: Double,
    val visits: Int,
    val candidates: List<MoveCandidate>,
    val isResign: Boolean = false
)
