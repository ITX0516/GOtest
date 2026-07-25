package com.weiqi.app.core

/**
 * 围棋棋子类型枚举。
 *
 * 包含空点、黑子、白子三种状态，并提供颜色互转、SGF 编码等工具方法。
 *
 * @property value 棋子在内部数组中的整型表示，0=空、1=黑、2=白。
 */
enum class Stone(val value: Int) {
    /** 空点（无子） */
    EMPTY(0),
    /** 黑子 */
    BLACK(1),
    /** 白子 */
    WHITE(2);

    /** 对方棋子颜色；EMPTY 的对方仍为 EMPTY。 */
    val opponent: Stone
        get() = when (this) {
            BLACK -> WHITE
            WHITE -> BLACK
            else -> EMPTY
        }

    /**
     * 转为 SGF 颜色字符串。
     * @return 黑子返回 "B"，白子返回 "W"，空点返回空串。
     */
    fun toSgfColor(): String = when (this) {
        BLACK -> "B"
        WHITE -> "W"
        EMPTY -> ""
    }

    companion object {
        /**
         * 由 SGF 颜色字符串构造棋子。
         * @param c "B" 或 "W"（大小写不敏感）；其他值返回 EMPTY。
         */
        fun fromSgf(c: String): Stone = when (c.uppercase()) {
            "B" -> BLACK
            "W" -> WHITE
            else -> EMPTY
        }
    }
}
