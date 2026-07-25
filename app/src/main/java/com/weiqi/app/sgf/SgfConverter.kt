package com.weiqi.app.sgf

import com.weiqi.app.core.GameState
import com.weiqi.app.core.Move
import com.weiqi.app.core.Stone
import com.weiqi.app.core.Vertex

/**
 * SGF 与 [GameState] 互转。
 *
 * - [toGameState]：从根节点出发，沿路径到目标节点依次 replay，得到当前局面
 * - [fromGameState]：将 GameState 的走子历史写成单线 SGF 棋谱（不含变着）
 */
object SgfConverter {
    /**
     * 将 SGF 游戏树重放到 [GameState]。
     *
     * 从根节点开始遍历到 [untilNode]（默认主线末尾），依次：
     * 1. 处理 AB/AW setup 属性（让子棋初始局面）
     * 2. 对 B/W 走子调用 [GameState.applyMove]
     * 3. Resign 在 SGF 中无标准表示，自然跳过
     *
     * @param tree SGF 游戏树。
     * @param untilNode 重放终点；为 null 时取主线末尾。
     * @return 终点节点对应的 GameState。
     */
    fun toGameState(tree: SgfGameTree, untilNode: SgfNode? = null): GameState {
        val size = tree.size
        val komi = tree.komi
        val handicap = tree.handicap

        val target = untilNode ?: tree.mainLine().last()
        val path = target.pathFromRoot()

        // 创建初始局面
        var state = GameState.newGame(size, komi, handicap)

        // 沿路径 replay：每个节点先处理 setup，再处理走子
        for (node in path) {
            state = applySetup(state, node)
            if (node.isMove()) {
                val move = decodeMove(node)
                state = state.applyMove(move)
            }
        }
        return state
    }

    /**
     * 将 [GameState] 写成单线 SGF 棋谱。
     *
     * 根节点写入对局元信息（PB/PW/SZ/KM/HA 等），后续每个走子（含 pass）写为主线节点。
     * Resign 无 SGF 节点表示，跳过。
     *
     * @param state 当前对局状态。
     * @param meta 对局元信息。
     * @return 单线 SGF 游戏树。
     */
    fun fromGameState(state: GameState, meta: SgfMetadata = SgfMetadata()): SgfGameTree {
        val root = SgfNode()
        // 元信息
        if (meta.playerBlack.isNotEmpty()) root.setProperty(SgfProperty.PLAYER_BLACK, meta.playerBlack)
        if (meta.playerWhite.isNotEmpty()) root.setProperty(SgfProperty.PLAYER_WHITE, meta.playerWhite)
        if (meta.blackRank.isNotEmpty()) root.setProperty(SgfProperty.BLACK_RANK, meta.blackRank)
        if (meta.whiteRank.isNotEmpty()) root.setProperty(SgfProperty.WHITE_RANK, meta.whiteRank)
        if (meta.date.isNotEmpty()) root.setProperty(SgfProperty.DATE, meta.date)
        if (meta.event.isNotEmpty()) root.setProperty(SgfProperty.EVENT, meta.event)
        if (meta.result.isNotEmpty()) root.setProperty(SgfProperty.RESULT, meta.result)
        root.setProperty(SgfProperty.RULESET, meta.ruleset)
        // 棋盘参数
        root.setProperty(SgfProperty.BOARD_SIZE, state.boardSize.toString())
        root.setProperty(SgfProperty.KOMI, state.komi.toString())
        if (state.handicap > 0) root.setProperty(SgfProperty.HANDICAP, state.handicap.toString())

        // 走子节点链
        var current = root
        for (move in state.moveHistory) {
            val node = encodeMove(move) ?: continue
            current = current.addChild(node)
        }
        return SgfGameTree(root)
    }

    /**
     * 处理节点中的 AB/AW setup 属性，把棋子摆到棋盘上。
     *
     * 让子棋约定：AB setup 后白方先行，因此把 [GameState.toMove] 调整为 [Stone.WHITE]。
     * 仅有 AW 时维持当前轮次（通常为黑先）。
     *
     * 依赖：[GameState.board] 可访问且可变，[com.weiqi.app.core.Board] 提供 set(x, y, stone) 方法。
     */
    private fun applySetup(state: GameState, node: SgfNode): GameState {
        val ab = node[SgfProperty.ADD_BLACK] ?: emptyList()
        val aw = node[SgfProperty.ADD_WHITE] ?: emptyList()
        if (ab.isEmpty() && aw.isEmpty()) return state

        val board = state.board
        for (coord in ab) {
            if (coord.isEmpty()) continue
            val v = Vertex.fromSgf(coord)
            board.set(v.x, v.y, Stone.BLACK)
        }
        for (coord in aw) {
            if (coord.isEmpty()) continue
            val v = Vertex.fromSgf(coord)
            board.set(v.x, v.y, Stone.WHITE)
        }

        // AB 后白方先行；其他情况维持当前轮次
        return if (ab.isNotEmpty()) {
            GameState(
                board = board,
                toMove = Stone.WHITE,
                moveHistory = state.moveHistory,
                blackCaptures = state.blackCaptures,
                whiteCaptures = state.whiteCaptures,
                komi = state.komi,
                handicap = state.handicap,
                boardSize = state.boardSize
            )
        } else {
            state
        }
    }

    /** 将 B/W 走子节点解码为 [Move]：空坐标视为 Pass。 */
    private fun decodeMove(node: SgfNode): Move {
        val color = node.moveColor()!!
        val coord = node.moveCoord() ?: ""
        val stone = if (color == SgfProperty.BLACK_MOVE) Stone.BLACK else Stone.WHITE
        return if (coord.isEmpty()) {
            Move.Pass(stone)
        } else {
            Move.Play(Vertex.fromSgf(coord), stone)
        }
    }

    /** 将 [Move] 编码为 SGF 节点；Resign 返回 null（无节点表示）。 */
    private fun encodeMove(move: Move): SgfNode? = when (move) {
        is Move.Play -> {
            val node = SgfNode()
            node.setProperty(move.stone.toSgfColor(), move.vertex.sgfCoord)
            node
        }
        is Move.Pass -> {
            // pass 用空方括号表示
            val node = SgfNode()
            node.setProperty(move.stone.toSgfColor(), "")
            node
        }
        is Move.Resign -> {
            // SGF 无 Resign 节点，结果由 RE 属性表达
            null
        }
    }
}

/**
 * SGF 元信息，用于 [SgfConverter.fromGameState] 写入对局元数据。
 *
 * @property playerBlack 黑方姓名。
 * @property playerWhite 白方姓名。
 * @property blackRank 黑方段级位。
 * @property whiteRank 白方段级位。
 * @property date 对局日期。
 * @property event 赛事名称。
 * @property result 对局结果（如 "B+2.5"、"W+R"）。
 * @property ruleset 规则，默认 "Chinese"。
 */
data class SgfMetadata(
    val playerBlack: String = "",
    val playerWhite: String = "",
    val blackRank: String = "",
    val whiteRank: String = "",
    val date: String = "",
    val event: String = "",
    val result: String = "",
    val ruleset: String = "Chinese"
)
