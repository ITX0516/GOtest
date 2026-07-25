package com.weiqi.app.sgf

/**
 * SGF 一局棋的完整游戏树。
 *
 * 由根节点 [root] 出发，可能包含多个分支变着。
 * 根节点通常保存棋盘元信息（SZ、KM、HA、PB、PW 等）。
 *
 * @property root 根节点。
 */
data class SgfGameTree(
    val root: SgfNode
) {
    /** 棋盘大小，默认 19。 */
    val size: Int
        get() = root[SgfProperty.BOARD_SIZE]?.firstOrNull()?.toIntOrNull() ?: 19

    /** 贴目，默认 7.5。 */
    val komi: Double
        get() = root[SgfProperty.KOMI]?.firstOrNull()?.toDoubleOrNull() ?: 7.5

    /** 让子数，默认 0。 */
    val handicap: Int
        get() = root[SgfProperty.HANDICAP]?.firstOrNull()?.toIntOrNull() ?: 0

    /** 黑方姓名。 */
    val playerBlack: String
        get() = root[SgfProperty.PLAYER_BLACK]?.firstOrNull().orEmpty()

    /** 白方姓名。 */
    val playerWhite: String
        get() = root[SgfProperty.PLAYER_WHITE]?.firstOrNull().orEmpty()

    /** 对局结果。 */
    val result: String
        get() = root[SgfProperty.RESULT]?.firstOrNull().orEmpty()

    /**
     * 取主线节点序列：从根开始，每次取第一个子节点直至叶子。
     * @return 主线节点列表，包含根节点。
     */
    fun mainLine(): List<SgfNode> {
        val result = mutableListOf<SgfNode>()
        var n: SgfNode? = root
        while (n != null) {
            result.add(n)
            n = n.children.firstOrNull()
        }
        return result
    }

    /**
     * 取所有节点（深度优先遍历，包含变着）。
     * @return 全部节点列表。
     */
    fun flatten(): List<SgfNode> {
        val result = mutableListOf<SgfNode>()
        fun walk(n: SgfNode) {
            result.add(n)
            for (c in n.children) walk(c)
        }
        walk(root)
        return result
    }

    /**
     * 在树中查找第一个走子方与坐标同时匹配的节点。
     * @param color "B" 或 "W"
     * @param coord SGF 坐标，如 "ab"；空串视为 pass
     * @return 匹配的节点；未找到返回 null。
     */
    fun findMove(color: String, coord: String): SgfNode? {
        for (n in flatten()) {
            if (n.moveColor() == color && n.moveCoord() == coord) return n
        }
        return null
    }
}
