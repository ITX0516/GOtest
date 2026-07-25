package com.weiqi.app.engine

import com.weiqi.app.core.GameState
import com.weiqi.app.core.Move
import com.weiqi.app.core.Stone
import com.weiqi.app.core.Vertex
import com.weiqi.app.engine.jni.NativeEngineBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * LeelaZero 引擎实现。
 *
 * 通过 [NativeEngineBridge] 调用 native LeelaZero，使用 `lz-analyze` 文本流式输出。
 *
 * 与 KataGo 的差异：
 * - `lz-analyze` 每行输出单个候选（`info move Q16 visits 200 winrate 52 pv ...`），
 *   需要跨多行累积候选列表
 * - winrate 为 0..100（非 0..1），需归一化
 * - 无 scoreLead 字段，置 0.0
 *
 * 真实集成说明：
 * - 用户需将编译好的 `libleelaz.so` 放入 `app/src/main/jniLibs/<abi>/`
 * - 权重文件 `leelaz_b18c384.txt.gz` 放入 `app/src/main/assets/weights/`
 * - 在 CMakeLists 中开启 `-DWEIQI_LINK_LEELAZERO=ON` 并设置 `LEELAZERO_LIB_DIR`
 * - 未链接时使用 stub，genmove 返回随机合法点或 pass
 */
class LeelaZeroEngine(
    config: EngineConfig
) : BaseNativeEngine(config) {

    override val engineTypeCode: Int = NativeEngineBridge.ENGINE_LEELAZERO
    override val engineDisplayName: String = "LeelaZero"

    /**
     * LeelaZero 的 lz-analyze 每行只有一个候选，
     * 这里返回单候选结果用于基类的"最后一行覆盖"逻辑；
     * 真正的多候选累积在重写的 [analyze] 中完成。
     */
    override fun parseAnalysisLine(
        line: String,
        color: Stone,
        boardSize: Int,
        maxCandidates: Int
    ): AnalysisResult? {
        val candidate = parseLzCandidateLine(line, color, boardSize) ?: return null
        val bestMove = if (candidate.vertex.isPass()) Move.Pass(color) else Move.Play(candidate.vertex, color)
        return AnalysisResult(
            bestMove = bestMove,
            winRate = candidate.winRate,
            scoreLead = candidate.scoreLead,
            visits = candidate.visits,
            candidates = listOf(candidate),
            isResign = false
        )
    }

    override fun buildAnalyzeCommand(color: String, maxVisits: Int, candidates: Int): String =
        GtpCommand.lzAnalyze(color, maxVisits)

    /**
     * 重写分析：累积多行 lz-analyze 输出，按坐标去重保留最新，
     * 达到 [maxVisits] 或超时后返回排序后的候选列表。
     */
    override suspend fun analyze(
        state: GameState,
        color: Stone,
        maxVisits: Int,
        candidates: Int
    ): AnalysisResult = withContext(Dispatchers.Default) {
        ensureReady()
        syncState(state)
        val command = buildAnalyzeCommand(color.toGtpColor(), maxVisits, candidates)

        // 候选累积表：vertexKey -> MoveCandidate（线程安全）
        val candidateMap = ConcurrentHashMap<String, MoveCandidate>()
        val bestRef = AtomicReference<AnalysisResult?>(null)
        val done = CompletableDeferred<Unit>()

        val flow = callbackFlow<String> {
            val callbackId = NativeEngineBridge.registerAnalysisCallback { line -> trySend(line) }
            try {
                NativeEngineBridge.startAnalysis(handle, command, callbackId)
                awaitClose {
                    try { NativeEngineBridge.stopAnalysis(handle) } catch (_: Throwable) {}
                    NativeEngineBridge.unregisterAnalysisCallback(callbackId)
                }
            } catch (e: Throwable) {
                close(e)
                NativeEngineBridge.unregisterAnalysisCallback(callbackId)
            }
        }

        // withContext 的代码块本身是 CoroutineScope 的 receiver，可直接 launch
        val collectJob = launch {
            flow.collect { line ->
                val c = parseLzCandidateLine(line, color, state.boardSize) ?: return@collect
                candidateMap[c.vertexKey()] = c
                val maxV = candidateMap.values.maxOfOrNull { it.visits } ?: 0
                if (maxV >= maxVisits) {
                    buildResult(candidateMap, color).let { bestRef.set(it) }
                    done.complete(Unit)
                }
            }
        }

        try {
            withTimeoutOrNull(analysisTimeoutMs) { done.await() }
        } finally {
            collectJob.cancel()
        }

        // 若未达到 visits 阈值但已收到候选，返回累积结果
        bestRef.get() ?: buildResult(candidateMap, color).takeIf { it.candidates.isNotEmpty() }
            ?: throw EngineException("LeelaZero 分析超时且未收到有效结果")
    }

    /** 由累积表构建最终 [AnalysisResult]。 */
    private fun buildResult(
        map: ConcurrentHashMap<String, MoveCandidate>,
        color: Stone
    ): AnalysisResult {
        val sorted = map.values.sortedByDescending { it.visits }
        val best = sorted.firstOrNull()
        val bestMove = when {
            best == null -> Move.Pass(color)
            best.vertex.isPass() -> Move.Pass(color)
            else -> Move.Play(best.vertex, color)
        }
        return AnalysisResult(
            bestMove = bestMove,
            winRate = best?.winRate ?: 0.0,
            scoreLead = best?.scoreLead ?: 0.0,
            visits = sorted.sumOf { it.visits },
            candidates = sorted,
            isResign = false
        )
    }

    /** 解析单行 lz-analyze 输出：`info move Q16 visits 200 winrate 52 prior 0.5 pv Q16 D4 ...` */
    private fun parseLzCandidateLine(line: String, color: Stone, boardSize: Int): MoveCandidate? {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.isEmpty() || tokens[0] != "info") return null

        var move: String? = null
        var visits = 0
        var winRate = 0.0
        var pvStart = -1
        var i = 0
        while (i < tokens.size - 1) {
            when (tokens[i]) {
                "move" -> move = tokens[i + 1]
                "visits" -> visits = tokens[i + 1].toIntOrNull() ?: 0
                "winrate" -> winRate = tokens[i + 1].toDoubleOrNull() ?: 0.0
                "pv" -> { pvStart = i + 1; break }
            }
            i++
        }
        val moveStr = move ?: return null

        val pv = if (pvStart >= 0) {
            tokens.drop(pvStart).mapNotNull { parseGtpMove(it, color, boardSize) }
        } else emptyList()

        val vertex = parseGtpVertex(moveStr, boardSize) ?: return null
        // lz winrate 为 0..100，归一化到 0..1
        return MoveCandidate(
            vertex = vertex,
            winRate = (winRate / 100.0).coerceIn(0.0, 1.0),
            scoreLead = 0.0,
            visits = visits,
            pv = pv
        )
    }

    private fun MoveCandidate.vertexKey(): String =
        if (vertex.isPass()) "pass" else "${vertex.x},${vertex.y}"

    private fun parseGtpVertex(token: String, boardSize: Int): Vertex? {
        val t = token.trim()
        if (t.isEmpty() || t.equals("pass", ignoreCase = true) || t.equals("resign", ignoreCase = true)) {
            return Vertex.pass()
        }
        return try { Vertex.fromDisplay(t, boardSize) } catch (_: IllegalArgumentException) { null }
    }

    private fun parseGtpMove(token: String, color: Stone, boardSize: Int): Move? {
        val t = token.trim()
        if (t.isEmpty()) return null
        if (t.equals("pass", ignoreCase = true)) return Move.Pass(color)
        if (t.equals("resign", ignoreCase = true)) return Move.Resign(color)
        val v = parseGtpVertex(t, boardSize) ?: return null
        return if (v.isPass()) Move.Pass(color) else Move.Play(v, color)
    }
}
