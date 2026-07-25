package com.weiqi.app.engine

/**
 * GTP（Go Text Protocol）命令构造器。
 *
 * 封装与 KataGo / LeelaZero 交互的标准 GTP 命令字符串。
 * 所有方法返回不带换行的命令字符串，调用方需自行追加换行符。
 */
object GtpCommand {

    /** 协议版本查询。 */
    fun protocolVersion() = "protocol_version"

    /** 引擎名称查询。 */
    fun name() = "name"

    /** 引擎版本查询。 */
    fun version() = "version"

    /** 设置棋盘大小。 */
    fun boardsize(n: Int) = "boardsize $n"

    /** 清空棋盘。 */
    fun clearBoard() = "clear_board"

    /** 设置贴目。 */
    fun komi(k: Double) = "komi $k"

    /** 在指定坐标落子。 */
    fun play(color: String, vertex: String) = "play $color $vertex"

    /** 生成指定颜色的着手。 */
    fun genmove(color: String) = "genmove $color"

    /** 设置剩余时间（秒）。 */
    fun timeLeft(color: String, timeMs: Long) = "time_left $color ${timeMs / 1000}"

    /**
     * KataGo 流式分析命令（JSON 输出）。
     * @param color 行棋方 "B" 或 "W"。
     * @param visits 最大访问数。
     * @param candidates 候选手数（仅用于上层解析，未编码进命令）。
     */
    fun kataAnalyze(color: String, visits: Int, candidates: Int) =
        "kata-analyze $color interval 100 visits $visits json 1"

    /**
     * LeelaZero 流式分析命令（文本输出）。
     * @param color 行棋方 "B" 或 "W"。
     * @param visits 最大访问数。
     */
    fun lzAnalyze(color: String, visits: Int) =
        "lz-analyze $color interval 100 visits $visits"

    /** LeelaZero 单次提示。 */
    fun lwHint(color: String) = "lz-hint $color"
}
