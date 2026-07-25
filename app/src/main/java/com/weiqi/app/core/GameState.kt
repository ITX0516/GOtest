package com.weiqi.app.core

/**
 * 完整对局状态。所有字段均为不可变（[Board] 内部数组除外，但应通过 [copy] 维护不变性）。
 *
 * @param board 当前棋盘。
 * @param toMove 当前轮到行棋的一方。
 * @param moveHistory 着手历史（按顺序）。
 * @param blackCaptures 黑方累计提子数。
 * @param whiteCaptures 白方累计提子数。
 * @param komi 贴目。
 * @param handicap 让子数。
 * @param boardSize 棋盘路数。
 * @param koPoint 当前劫点；null 表示无劫。
 * @param consecutivePasses 连续 Pass 次数；达到 2 时对局结束。
 * @param gameOver 对局是否已结束。
 */
data class GameState(
    val board: Board,
    val toMove: Stone = Stone.BLACK,
    val moveHistory: List<Move> = emptyList(),
    val blackCaptures: Int = 0,
    val whiteCaptures: Int = 0,
    val komi: Double = 7.5,
    val handicap: Int = 0,
    val boardSize: Int = 19,
    val koPoint: Vertex? = null,
    val consecutivePasses: Int = 0,
    val gameOver: Boolean = false
) {
    /** 已走手数。 */
    val moveNumber: Int
        get() = moveHistory.size

    /**
     * 应用一步着手，返回新状态。委托给 [GoRules.applyMove]。
     * 非法着手将返回原状态。
     */
    fun applyMove(move: Move): GameState = GoRules.applyMove(this, move)

    /**
     * 回退 [steps] 步。通过重放 [moveHistory] 重建状态。
     * 若 [steps] 非法（<=0 或超过历史长度），返回原状态。
     */
    fun undo(steps: Int = 1): GameState {
        if (steps <= 0 || steps > moveHistory.size) return this
        val targetMoves = moveHistory.dropLast(steps)
        var state = newGame(boardSize, komi, handicap)
        for (m in targetMoves) {
            state = GoRules.applyMove(state, m)
        }
        return state
    }

    /**
     * 判断 [move] 在当前状态下是否合法。
     * Pass 与合法落子均返回 true；Resign 视为合法（结束对局）。
     */
    fun isLegal(move: Move): Boolean {
        val v = GoRules.validate(board, move, koPoint)
        return v.result == GoRules.MoveResult.LEGAL ||
            v.result == GoRules.MoveResult.PASS ||
            v.result == GoRules.MoveResult.GAME_OVER
    }

    /** 最近一步着手；无历史返回 null。 */
    fun lastMove(): Move? = moveHistory.lastOrNull()

    companion object {
        /**
         * 创建一局新对局的初始状态。
         *
         * 让子棋（handicap >= 2）时：黑方在星位放置让子棋，白方先行。
         * handicap <= 1 时不放让子，黑方先行（1 子让子通常通过贴目调整体现）。
         *
         * @param size 棋盘路数，默认 19。
         * @param komi 贴目，默认 7.5。
         * @param handicap 让子数。
         */
        fun newGame(size: Int = 19, komi: Double = 7.5, handicap: Int = 0): GameState {
            val board = Board(size)
            if (handicap > 0) {
                val stars = handicapStars(size)
                val placed = minOf(handicap, stars.size)
                for (i in 0 until placed) {
                    val p = stars[i]
                    board.set(p.x, p.y, Stone.BLACK)
                }
            }
            // 让子数 >= 2 时白方先行；否则黑方先行
            val firstMove = if (handicap >= 2) Stone.WHITE else Stone.BLACK
            return GameState(
                board = board,
                toMove = firstMove,
                komi = komi,
                handicap = handicap,
                boardSize = size
            )
        }

        /** 各路数对应让子星位顺序（传统让子顺序，2 子对角起手）。 */
        private fun handicapStars(size: Int): List<Vertex> = when (size) {
            19 -> listOf(
                Vertex(3, 3), Vertex(15, 15),
                Vertex(15, 3), Vertex(3, 15),
                Vertex(9, 9),
                Vertex(9, 3), Vertex(9, 15),
                Vertex(3, 9), Vertex(15, 9)
            )
            13 -> listOf(
                Vertex(3, 3), Vertex(9, 9),
                Vertex(9, 3), Vertex(3, 9),
                Vertex(6, 6)
            )
            9 -> listOf(
                Vertex(2, 2), Vertex(6, 6),
                Vertex(6, 2), Vertex(2, 6),
                Vertex(4, 4)
            )
            else -> Vertex.STAR_POINTS_19.filter { it.x < size && it.y < size }
        }
    }
}
