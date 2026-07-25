package com.weiqi.app.core

/**
 * 一步着手。分为落子（Play）、弃权（Pass）、认输（Resign）三种。
 */
sealed class Move {
    /** 行棋方棋子颜色。 */
    abstract val stone: Stone

    /** 落子。 */
    data class Play(val vertex: Vertex, override val stone: Stone) : Move()

    /** 弃权（Pass）。 */
    data class Pass(override val stone: Stone) : Move()

    /** 认输。 */
    data class Resign(override val stone: Stone) : Move()

    /**
     * 序列化为 SGF 着手字符串。
     * - Play: "B[ab]" / "W[ss]" 等
     * - Pass: "B[]" / "W[]"
     * - Resign: "B[RESIGN]" / "W[RESIGN]"（非标准 SGF，仅供内部往返）
     */
    fun toSgf(): String = when (this) {
        is Play -> "${stone.toSgfColor()}[${vertex.sgfCoord}]"
        is Pass -> "${stone.toSgfColor()}[]"
        is Resign -> "${stone.toSgfColor()}[RESIGN]"
    }

    companion object {
        /**
         * 由 SGF 着手字符串反序列化为 [Move]。
         * @param sgf 形如 "B[ab]" / "W[]" / "B[RESIGN]" 的字符串。
         * @param color 行棋方颜色 "B" 或 "W"。
         */
        fun fromSgf(sgf: String, color: String): Move {
            val stone = Stone.fromSgf(color)
            val content = sgf.substringAfter('[', "").substringBefore(']', "")
            return when {
                content.isEmpty() -> Pass(stone)
                content == "RESIGN" -> Resign(stone)
                else -> Play(Vertex.fromSgf(content), stone)
            }
        }
    }
}
