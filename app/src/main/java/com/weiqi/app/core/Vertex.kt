package com.weiqi.app.core

/**
 * 棋盘上的一个坐标点。
 *
 * 坐标系约定：原点 (0,0) 位于左上角，x 轴向右为正，y 轴向下为正。
 * 19x19 棋盘的有效坐标范围是 0..18。
 * Pass 使用 (-1, -1) 表示。
 *
 * @property x 横坐标（列），从左到右递增。
 * @property y 纵坐标（行），从上到下递增。
 */
data class Vertex(val x: Int, val y: Int) {

    /**
     * SGF 坐标字符串：使用小写字母 'a'..'s' 表示 0..18。
     * 例如 (0,0) -> "aa"，(18,18) -> "ss"。Pass 返回空串。
     */
    val sgfCoord: String
        get() {
            if (isPass()) return ""
            return "${('a' + x)}${('a' + y)}"
        }

    /**
     * 人类可读坐标（GTP 风格）：列字母 A..T（跳过 I），行号 1..19。
     * 例如 (0,0) -> "A19"，(18,18) -> "T1"。
     *
     * 注意：行号基于 [DEFAULT_SIZE]（19x19），其他棋盘尺寸下行号会不准确，
     * 应使用 [fromDisplay] 配合 size 参数进行反序列化。
     */
    val displayCoord: String
        get() {
            if (isPass()) return "Pass"
            val cols = COLS_SKIP_I
            val col = if (x in cols.indices) cols[x].toString() else "?"
            return "$col${DEFAULT_SIZE - y}"
        }

    /** 是否为 Pass（坐标为负）。 */
    fun isPass(): Boolean = x < 0 || y < 0

    companion object {
        /** 默认棋盘尺寸（标准 19 路）。 */
        const val DEFAULT_SIZE = 19

        /** 列字母（A..T，跳过 I），共 19 个字符。 */
        const val COLS_SKIP_I = "ABCDEFGHJKLMNOPRST"

        /** 19 路棋盘的标准星位（9 个）。 */
        val STAR_POINTS_19: List<Vertex> = listOf(
            Vertex(3, 3), Vertex(9, 3), Vertex(15, 3),
            Vertex(3, 9), Vertex(9, 9), Vertex(15, 9),
            Vertex(3, 15), Vertex(9, 15), Vertex(15, 15)
        )

        /**
         * 由 SGF 坐标字符串构造 Vertex。
         * @param s 形如 "ab" 的两字符小写字母串；长度不足返回 Pass。
         */
        fun fromSgf(s: String): Vertex {
            if (s.length < 2) return pass()
            val x = s[0] - 'a'
            val y = s[1] - 'a'
            return Vertex(x, y)
        }

        /**
         * 由人类可读坐标构造 Vertex。
         * @param s 形如 "A19" / "T1" / "K10" 的字符串（大小写不敏感）。
         * @param size 棋盘尺寸，用于计算行号；默认 19。
         */
        fun fromDisplay(s: String, size: Int = DEFAULT_SIZE): Vertex {
            val colPart = s.takeWhile { it.isLetter() }.uppercase()
            val rowPart = s.dropWhile { it.isLetter() }
            require(colPart.isNotEmpty() && rowPart.isNotEmpty()) { "非法坐标: $s" }
            val x = COLS_SKIP_I.indexOf(colPart.first())
            require(x >= 0) { "非法列字母: $colPart" }
            val row = rowPart.toIntOrNull() ?: throw IllegalArgumentException("非法行号: $rowPart")
            val y = size - row
            return Vertex(x, y)
        }

        /** 构造一个表示 Pass 的 Vertex：(-1, -1)。 */
        fun pass(): Vertex = Vertex(-1, -1)
    }
}
