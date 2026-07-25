package com.weiqi.app.sgf

/**
 * SGF 属性标识常量。
 *
 * 收录常用 FF[4] 属性标识，按用途分组：走子、对局信息、计时、标记等。
 * 详见 SGF 规范：https://www.red-bean.com/sgf/
 */
object SgfProperty {
    /** 黑方走子（坐标）。 */
    const val BLACK_MOVE = "B"
    /** 白方走子（坐标）。 */
    const val WHITE_MOVE = "W"
    /** 黑方姓名。 */
    const val PLAYER_BLACK = "PB"
    /** 白方姓名。 */
    const val PLAYER_WHITE = "PW"
    /** 黑方段级位。 */
    const val BLACK_RANK = "BR"
    /** 白方段级位。 */
    const val WHITE_RANK = "WR"
    /** 棋盘大小。 */
    const val BOARD_SIZE = "SZ"
    /** 贴目。 */
    const val KOMI = "KM"
    /** 让子数。 */
    const val HANDICAP = "HA"
    /** 添加黑子（setup）。 */
    const val ADD_BLACK = "AB"
    /** 添加白子（setup）。 */
    const val ADD_WHITE = "AW"
    /** 注释。 */
    const val COMMENT = "C"
    /** 棋谱起始手数。 */
    const val MOVE_NUMBER = "MN"
    /** 黑方剩余时间。 */
    const val TIME_LEFT = "BL"
    /** 白方剩余时间。 */
    const val TIME_LEFT_WHITE = "WL"
    /** 对局结果。 */
    const val RESULT = "RE"
    /** 对局日期。 */
    const val DATE = "DT"
    /** 赛事名称。 */
    const val EVENT = "EV"
    /** 对局地点。 */
    const val PLACE = "PC"
    /** 棋谱名称。 */
    const val GAME_NAME = "GN"
    /** 来源。 */
    const val SOURCE = "SO"
    /** 规则（Chinese / Japanese / ...）。 */
    const val RULESET = "RU"
    /** 加赛用时（读秒）。 */
    const val OVERTIME = "OT"
    /** 时间限制（秒）。 */
    const val TIME_LIMIT = "TM"
    /** 标记（X）。 */
    const val MARK = "MA"
    /** 圆圈标记。 */
    const val CIRCLE = "CR"
    /** 方块标记。 */
    const val SQUARE = "SQ"
    /** 三角标记。 */
    const val TRIANGLE = "TR"
    /** 文字标签（如 LB[ab:1]）。 */
    const val LABEL = "LB"
    /** 热点。 */
    const val HOTSPOT = "HO"

    /** 全部已登记属性标识集合。 */
    val ALL: Set<String> = setOf(
        BLACK_MOVE, WHITE_MOVE,
        PLAYER_BLACK, PLAYER_WHITE, BLACK_RANK, WHITE_RANK,
        BOARD_SIZE, KOMI, HANDICAP,
        ADD_BLACK, ADD_WHITE,
        COMMENT, MOVE_NUMBER,
        TIME_LEFT, TIME_LEFT_WHITE,
        RESULT, DATE, EVENT, PLACE, GAME_NAME, SOURCE,
        RULESET, OVERTIME, TIME_LIMIT,
        MARK, CIRCLE, SQUARE, TRIANGLE, LABEL, HOTSPOT
    )
}
