package com.weiqi.app.engine

import com.weiqi.app.core.GameState
import com.weiqi.app.core.Move
import com.weiqi.app.core.Stone
import com.weiqi.app.core.Vertex

/**
 * 围棋引擎统一接口。
 *
 * 实现方负责通过 [com.weiqi.app.engine.jni.NativeEngineBridge] 与底层 native 引擎交互，
 * 并对外提供生成着手、局面分析、后台 ponder 等能力。
 *
 * 注意：本接口的 `color` 参数使用 [com.weiqi.app.core.Stone]。
 * （契约文档中标注为 `com.weiqi.app.Stone`，实际类型定义在 `com.weiqi.app.core` 包下。）
 */
interface GoEngine {

    /** 引擎显示名称。 */
    val name: String

    /** 引擎类型。 */
    val type: EngineType

    /** 引擎是否已启动并就绪。 */
    val isReady: Boolean

    /**
     * 启动引擎：加载 native 引擎、设置棋盘与贴目等初始化命令。
     * 重复调用应当幂等。
     */
    suspend fun start()

    /**
     * 关闭引擎并释放 native 资源。
     */
    suspend fun shutdown()

    /**
     * 生成一步着手。
     *
     * @param state 当前局面。
     * @param color 行棋方。
     * @param timeLimitMs 时间限制（毫秒）；0 表示使用引擎默认。
     * @return 引擎选定的着手（可能是 Play / Pass / Resign）。
     */
    suspend fun genMove(state: GameState, color: Stone, timeLimitMs: Long = 0L): Move

    /**
     * 分析当前局面。
     *
     * @param state 当前局面。
     * @param color 行棋方。
     * @param maxVisits 最大访问数。
     * @param candidates 返回的候选手数上限。
     * @return 分析结果。
     */
    suspend fun analyze(state: GameState, color: Stone, maxVisits: Int = 800, candidates: Int = 10): AnalysisResult

    /**
     * 启动后台 ponder（在对手思考时持续计算）。
     */
    suspend fun ponder(state: GameState, color: Stone)

    /**
     * 停止后台 ponder。
     */
    suspend fun stopPonder()

    /**
     * 评估在指定坐标落子的胜率/价值。
     * @return 0.0..1.0 的胜率，或负值表示无法评估。
     */
    suspend fun evaluateMove(state: GameState, vertex: Vertex): Double

    /**
     * 获取引擎元信息（名称、版本、线程数等）。
     */
    fun getEngineInfo(): Map<String, String>
}
