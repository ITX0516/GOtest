package com.weiqi.app.core

/**
 * 终局数子结果。
 *
 * 采用中国规则（数子法 / area scoring）：
 * - 一方实地 + 该方棋子数 = 子数（area）
 * - 再加上提子数（含死子）得到总分
 * - 白方还需加上贴目 [komi]
 *
 * @param blackArea 黑方子数（棋子数 + 单色围空）。
 * @param whiteArea 白方子数（棋子数 + 单色围空）。
 * @param blackCaptures 黑方提子数（即被提的白子数，含死子）。
 * @param whiteCaptures 白方提子数（即被提的黑子数，含死子）。
 * @param komi 贴目。
 * @param winner 胜方；平局为 [Stone.EMPTY]。
 * @param margin 胜负差距（非负数）。
 */
data class Score(
    val blackArea: Int,
    val whiteArea: Int,
    val blackCaptures: Int,
    val whiteCaptures: Int,
    val komi: Double,
    val winner: Stone,
    val margin: Double
) {
    /** 黑方总分：子数 + 提子数。 */
    val blackTotal: Double
        get() = blackArea + blackCaptures.toDouble()

    /** 白方总分：子数 + 提子数 + 贴目。 */
    val whiteTotal: Double
        get() = whiteArea + whiteCaptures + komi

    /** 人类可读的胜负文本，例如 "黑+3.5"、"白+7.5"、"平局"。 */
    val displayText: String
        get() {
            val winnerStr = when (winner) {
                Stone.BLACK -> "黑"
                Stone.WHITE -> "白"
                else -> "平局"
            }
            if (winner == Stone.EMPTY) return winnerStr
            // 整数差距显示为整数，否则保留小数
            val m = if (margin == margin.toInt().toDouble()) {
                margin.toInt().toString()
            } else {
                margin.toString()
            }
            return "$winnerStr+$m"
        }
}
