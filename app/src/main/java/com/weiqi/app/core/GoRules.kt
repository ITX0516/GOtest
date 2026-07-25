package com.weiqi.app.core

/**
 * 围棋规则判定与状态推进。
 *
 * 实现：
 * - 落子合法性校验（占据、自杀、打劫）
 * - 提子（捕获无气群）
 * - 简单劫（simple ko，禁止立即回提单子）
 * - 中国规则数子（area scoring）
 * - 简化死活判定（启发式，TODO 应由引擎确认）
 */
object GoRules {

    /** 着手校验结果分类。 */
    enum class MoveResult {
        /** 合法落子 */
        LEGAL,
        /** 目标点已被占据 */
        OCCUPIED,
        /** 自杀禁手 */
        SUICIDE,
        /** 打劫禁手 */
        KO,
        /** Pass（弃权） */
        PASS,
        /** 对局结束（认输或非法） */
        GAME_OVER
    }

    /**
     * 着手校验结果。
     *
     * @param result 结果分类。
     * @param captures 若合法，被提子的坐标列表。
     * @param newBoard 落子后的新棋盘（合法或 Pass 时返回；非法时为 null）。
     * @param koPoint 落子后形成的新劫点；无劫为 null。
     */
    data class MoveValidation(
        val result: MoveResult,
        val captures: List<Vertex> = emptyList(),
        val newBoard: Board? = null,
        val koPoint: Vertex? = null
    )

    /**
     * 校验一步着手在给定棋盘上是否合法，并计算其后果。
     *
     * @param board 当前棋盘（不会被修改）。
     * @param move 待校验的着手。
     * @param koPoint 当前劫点（禁止立即回提）；null 表示无劫。
     * @return 校验结果，含新棋盘、提子列表、新劫点。
     */
    fun validate(board: Board, move: Move, koPoint: Vertex? = null): MoveValidation {
        return when (move) {
            is Move.Pass -> MoveValidation(
                result = MoveResult.PASS,
                newBoard = board.copy(),
                koPoint = null
            )
            is Move.Resign -> MoveValidation(
                result = MoveResult.GAME_OVER,
                newBoard = board.copy(),
                koPoint = null
            )
            is Move.Play -> validatePlay(board, move, koPoint)
        }
    }

    private fun validatePlay(board: Board, move: Move.Play, koPoint: Vertex?): MoveValidation {
        val v = move.vertex
        // Pass 顶点视为弃权
        if (v.isPass()) {
            return MoveValidation(MoveResult.PASS, newBoard = board.copy(), koPoint = null)
        }
        // 越界视为非法
        if (!board.inBounds(v)) {
            return MoveValidation(MoveResult.OCCUPIED)
        }
        // 目标点已被占据
        if (board[v] != Stone.EMPTY) {
            return MoveValidation(MoveResult.OCCUPIED)
        }
        // 劫点禁手：禁止立即在劫点回提
        if (koPoint != null && v == koPoint) {
            return MoveValidation(MoveResult.KO)
        }

        val stone = move.stone
        val opponent = stone.opponent
        val newBoard = board.copy()
        newBoard.set(v.x, v.y, stone)

        // 提子：检查落子点的邻接对方群是否无气
        val captures = mutableListOf<Vertex>()
        val processed = mutableSetOf<Vertex>()
        for (n in board.neighbors(v)) {
            if (newBoard[n] == opponent && n !in processed) {
                val group = newBoard.getGroup(n.x, n.y)
                processed.addAll(group)
                if (newBoard.getLiberties(group) == 0) {
                    for (g in group) {
                        newBoard.set(g.x, g.y, Stone.EMPTY)
                        captures.add(g)
                    }
                }
            }
        }

        // 自杀禁手：落子后己方群无气且未提子（提子后己方群必有气）
        val myGroup = newBoard.getGroup(v.x, v.y)
        if (newBoard.getLiberties(myGroup) == 0) {
            return MoveValidation(MoveResult.SUICIDE)
        }

        // 计算新劫点：仅当提子恰为 1 颗、且落子本身为单子单气（典型打劫形）
        val newKoPoint: Vertex? = if (
            captures.size == 1 &&
            myGroup.size == 1 &&
            newBoard.getLiberties(myGroup) == 1
        ) {
            captures[0]
        } else {
            null
        }

        return MoveValidation(
            result = MoveResult.LEGAL,
            captures = captures,
            newBoard = newBoard,
            koPoint = newKoPoint
        )
    }

    /**
     * 在 [state] 上应用 [move]，返回新的 [GameState]。原状态不变。
     *
     * 对于非法落子（OCCUPIED/SUICIDE/KO），直接返回原状态；
     * 调用方应先使用 [validate] 或 [GameState.isLegal] 判定。
     */
    fun applyMove(state: GameState, move: Move): GameState {
        val validation = validate(state.board, move, state.koPoint)
        return when (validation.result) {
            MoveResult.LEGAL -> {
                val newBoard = validation.newBoard!!
                val addedBlackCaptures = if (move.stone == Stone.BLACK) validation.captures.size else 0
                val addedWhiteCaptures = if (move.stone == Stone.WHITE) validation.captures.size else 0
                state.copy(
                    board = newBoard,
                    toMove = move.stone.opponent,
                    moveHistory = state.moveHistory + move,
                    blackCaptures = state.blackCaptures + addedBlackCaptures,
                    whiteCaptures = state.whiteCaptures + addedWhiteCaptures,
                    koPoint = validation.koPoint,
                    consecutivePasses = 0,
                    gameOver = false
                )
            }
            MoveResult.PASS -> {
                val newPasses = state.consecutivePasses + 1
                state.copy(
                    toMove = move.stone.opponent,
                    moveHistory = state.moveHistory + move,
                    koPoint = null,
                    consecutivePasses = newPasses,
                    gameOver = newPasses >= 2
                )
            }
            MoveResult.GAME_OVER -> {
                // 认输：记录着手并标记对局结束
                state.copy(
                    moveHistory = state.moveHistory + move,
                    gameOver = true
                )
            }
            else -> {
                // 非法着手（OCCUPIED/SUICIDE/KO）：原样返回
                state
            }
        }
    }

    /**
     * 中国规则数子（area scoring）。
     *
     * 计算流程：
     * 1. 先将 [deadStones] 指定的死子从棋盘移除（视为被提）。
     * 2. 统计黑白棋子数。
     * 3. 对每个空区域做 flood fill；若仅与单色棋子相邻，则该区域归属此色。
     * 4. 双方均相邻的空区域为公气（dame），不计分。
     * 5. 死子计入对方提子数。
     *
     * @param board 终局棋盘。
     * @param komi 贴目，默认 7.5。
     * @param deadStones 已确认的死子集合。
     */
    fun calculateAreaScore(board: Board, komi: Double = 7.5, deadStones: Set<Vertex> = emptySet()): Score {
        val scoringBoard = board.copy()
        var deadBlack = 0
        var deadWhite = 0
        for (v in deadStones) {
            if (!scoringBoard.inBounds(v)) continue
            when (scoringBoard[v]) {
                Stone.BLACK -> deadBlack++
                Stone.WHITE -> deadWhite++
                else -> {}
            }
            scoringBoard.set(v.x, v.y, Stone.EMPTY)
        }

        var blackArea = 0
        var whiteArea = 0
        val visited = mutableSetOf<Vertex>()

        for (x in 0 until scoringBoard.size) {
            for (y in 0 until scoringBoard.size) {
                val v = Vertex(x, y)
                if (v in visited) continue
                val s = scoringBoard[v]
                if (s == Stone.BLACK) {
                    blackArea++
                } else if (s == Stone.WHITE) {
                    whiteArea++
                } else {
                    // 空点 flood fill，判定归属
                    val region = ArrayList<Vertex>()
                    val queue = ArrayDeque<Vertex>()
                    queue.addLast(v)
                    visited.add(v)
                    val borders = mutableSetOf<Stone>()
                    while (queue.isNotEmpty()) {
                        val cur = queue.removeFirst()
                        region.add(cur)
                        for (n in scoringBoard.neighbors(cur)) {
                            val ns = scoringBoard[n]
                            if (ns == Stone.EMPTY) {
                                if (n !in visited) {
                                    visited.add(n)
                                    queue.addLast(n)
                                }
                            } else {
                                borders.add(ns)
                            }
                        }
                    }
                    // 仅单一颜色包围时归属该色；否则为公气不计分
                    if (borders.size == 1) {
                        val owner = borders.first()
                        if (owner == Stone.BLACK) blackArea += region.size
                        else if (owner == Stone.WHITE) whiteArea += region.size
                    }
                }
            }
        }

        // 黑方提子数 = 死掉的白子数；白方提子数 = 死掉的黑子数
        val blackCaptures = deadWhite
        val whiteCaptures = deadBlack

        val blackTotal = blackArea + blackCaptures.toDouble()
        val whiteTotal = whiteArea + whiteCaptures + komi
        val winner = when {
            blackTotal > whiteTotal -> Stone.BLACK
            blackTotal < whiteTotal -> Stone.WHITE
            else -> Stone.EMPTY
        }
        val margin = if (blackTotal >= whiteTotal) blackTotal - whiteTotal else whiteTotal - blackTotal

        return Score(
            blackArea = blackArea,
            whiteArea = whiteArea,
            blackCaptures = blackCaptures,
            whiteCaptures = whiteCaptures,
            komi = komi,
            winner = winner,
            margin = margin
        )
    }

    /**
     * 简化的死活判定（启发式）。
     *
     * TODO: 实际项目中死活应由专业引擎（如 KataGo / Leela Zero）通过搜索确认，
     * 当前实现仅为粗略启发式，会误判打劫、可救的打吃群等复杂局面。
     *
     * 当前策略：
     * - 0 气群：理论不应出现，保险起见判死。
     * - 1 气群（打吃状态）：判为死棋。
     *
     * 调用 [calculateAreaScore] 时建议手工传入 [deadStones] 以覆盖本结果。
     *
     * @return 判定为死棋的坐标集合。
     */
    fun findDeadStones(board: Board): Set<Vertex> {
        val dead = mutableSetOf<Vertex>()
        val visited = mutableSetOf<Vertex>()

        for (x in 0 until board.size) {
            for (y in 0 until board.size) {
                val v = Vertex(x, y)
                if (v in visited) continue
                val s = board[v]
                if (s == Stone.EMPTY) continue
                val group = board.getGroup(x, y)
                visited.addAll(group)
                val libs = board.getLiberties(group)
                if (libs <= 1) {
                    dead.addAll(group)
                }
            }
        }
        return dead
    }
}
