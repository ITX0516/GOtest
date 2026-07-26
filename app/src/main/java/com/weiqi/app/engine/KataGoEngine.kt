package com.weiqi.app.engine

import com.weiqi.app.core.GameState
import com.weiqi.app.core.Move
import com.weiqi.app.core.Stone
import com.weiqi.app.core.Vertex
import com.weiqi.app.engine.jni.NativeEngineBridge
import com.weiqi.app.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

/**
 * 引擎运行时异常。封装 native 加载失败、GTP 错误、分析无结果等情况。
 */
class EngineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 本地 native GTP 引擎的共享基类。
 *
 * 负责：
 * - native handle 生命周期（[start]/[shutdown]）
 * - GTP 命令同步发送（[send]）
 * - 局面同步（[syncState]：clear_board + 重放 moveHistory）
 * - genMove 响应解析（"resign"/"pass"/坐标）
 * - 流式分析的基础设施（[analysisLineFlow] + 超时控制）
 *
 * 子类只需实现 [engineTypeCode]、[engineDisplayName]、[buildAnalyzeCommand]、
 * [parseAnalysisLine] 即可获得完整的引擎行为。
 */
abstract class BaseNativeEngine(
    protected val config: EngineConfig
) : GoEngine {

    @Volatile protected var handle: Long = 0L
        private set

    protected val gtpLock = Any()

    protected companion object {
        const val TAG = "BaseNativeEngine"
    }

    /** 流式分析默认超时（毫秒）。 */
    protected open val analysisTimeoutMs: Long = 10_000L

    final override val type: EngineType get() = config.type
    final override val isReady: Boolean get() = handle != 0L

    /** native 引擎类型码（[NativeEngineBridge.ENGINE_KATAGO] / [ENGINE_LEELAZERO]）。 */
    protected abstract val engineTypeCode: Int

    /** 引擎显示名称。 */
    protected abstract val engineDisplayName: String

    final override val name: String get() = engineDisplayName

    /** 后台 ponder 注册的回调 id；null 表示未在 ponder。 */
    @Volatile private var ponderCallbackId: Int? = null

    override suspend fun start() = withContext(Dispatchers.Default) {
        if (handle != 0L) {
            AppLogger.d(TAG, "start() 已有 handle，跳过")
            return@withContext // 幂等
        }
        AppLogger.i(TAG, "start() 调用 nativeCreateEngine，type=$engineTypeCode")
        AppLogger.d(TAG, "config: ${config.toJson()}")
        val h = try {
            NativeEngineBridge.createEngine(engineTypeCode, config.toJson())
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e(TAG, "nativeCreateEngine UnsatisfiedLinkError: ${e.message}", e)
            throw EngineException("无法加载 native 引擎库 libweiqi_engine.so：${e.message}", e)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "nativeCreateEngine 异常: ${e.javaClass.simpleName}: ${e.message}", e)
            throw EngineException("创建 native 引擎异常：${e.message}", e)
        }
        if (h == 0L) {
            AppLogger.e(TAG, "nativeCreateEngine 返回 0，详情见上方 native 日志")
            throw EngineException("创建 native 引擎失败（type=${engineTypeCode}），请检查权重文件与 ABI")
        }
        AppLogger.i(TAG, "nativeCreateEngine 成功，handle=$h")
        handle = h
        try {
            // 初始化棋盘与贴目
            AppLogger.i(TAG, "初始化棋盘 boardsize=${config.boardSize} komi=${config.komi}")
            send(GtpCommand.boardsize(config.boardSize))
            send(GtpCommand.clearBoard())
            send(GtpCommand.komi(config.komi))
            AppLogger.i(TAG, "引擎就绪")
        } catch (e: EngineException) {
            AppLogger.e(TAG, "初始化棋盘失败: ${e.message}", e)
            try { NativeEngineBridge.destroyEngine(h) } catch (_: Throwable) {}
            handle = 0L
            throw e
        }
    }

    override suspend fun shutdown() = withContext(Dispatchers.Default) {
        stopPonder()
        val h = handle
        if (h != 0L) {
            try { NativeEngineBridge.destroyEngine(h) } catch (_: Throwable) {}
            handle = 0L
        }
    }

    /**
     * 同步发送一条 GTP 命令并返回响应正文。
     * @throws EngineException 引擎未启动或 GTP 返回错误。
     */
    protected fun send(command: String): String {
        synchronized(gtpLock) {
            check(handle != 0L) { "引擎未启动" }
            val resp = try {
                NativeEngineBridge.sendGtpCommand(handle, command)
            } catch (e: Throwable) {
                AppLogger.e(TAG, "GTP 命令发送失败 [$command]: ${e.javaClass.simpleName}: ${e.message}", e)
                throw EngineException("GTP 命令发送失败 [$command]：${e.message}", e)
            }
            if (resp.startsWith("error:")) {
                val err = resp.removePrefix("error:").trim()
                AppLogger.e(TAG, "GTP 错误 [$command]: $err")
                throw EngineException("GTP 错误 [$command]：$err")
            }
            return resp
        }
    }

    /** 确保引擎已启动。 */
    protected fun ensureReady() {
        check(handle != 0L) { "引擎未启动，请先调用 start()" }
    }

    /**
     * 同步局面到引擎：从空棋盘开始，按 moveHistory 重放所有 play 命令。
     * Resign 不重放（GTP 无对应语义）。
     */
    protected fun syncState(state: GameState) {
        send(GtpCommand.clearBoard())
        if (state.boardSize != config.boardSize) {
            send(GtpCommand.boardsize(state.boardSize))
        }
        send(GtpCommand.komi(state.komi))
        for (move in state.moveHistory) {
            when (move) {
                is Move.Play -> {
                    val coord = if (move.vertex.isPass()) "pass" else move.vertex.displayCoord
                    send(GtpCommand.play(move.stone.toGtpColor(), coord))
                }
                is Move.Pass -> {
                    send(GtpCommand.play(move.stone.toGtpColor(), "pass"))
                }
                is Move.Resign -> {
                    // 认输不重放
                }
            }
        }
    }

    override suspend fun genMove(state: GameState, color: Stone, timeLimitMs: Long): Move =
        withContext(Dispatchers.Default) {
            ensureReady()
            syncState(state)
            if (timeLimitMs > 0) {
                try { send(GtpCommand.timeLeft(color.toGtpColor(), timeLimitMs)) } catch (_: EngineException) {}
            }
            val raw = send(GtpCommand.genmove(color.toGtpColor()))
            parseGenMoveResponse(raw, color, state.boardSize)
        }

    /** 解析 genmove 响应：resign / pass / 坐标。 */
    protected fun parseGenMoveResponse(raw: String, color: Stone, boardSize: Int): Move {
        val token = raw.trim().split(Regex("\\s+")).firstOrNull()?.uppercase().orEmpty()
        return when {
            token == "RESIGN" -> Move.Resign(color)
            token == "PASS" || token.isEmpty() -> Move.Pass(color)
            else -> try {
                Move.Play(Vertex.fromDisplay(token, boardSize), color)
            } catch (_: IllegalArgumentException) {
                // 无法识别的响应降级为 pass，避免上层崩溃
                Move.Pass(color)
            }
        }
    }

    override suspend fun analyze(
        state: GameState,
        color: Stone,
        maxVisits: Int,
        candidates: Int
    ): AnalysisResult = withContext(Dispatchers.Default) {
        ensureReady()
        syncState(state)
        val command = buildAnalyzeCommand(color.toGtpColor(), maxVisits, candidates)
        runStreamingAnalysis(command, color, state.boardSize, maxVisits, candidates)
    }

    /** 构造引擎特定的流式分析命令。 */
    protected abstract fun buildAnalyzeCommand(color: String, maxVisits: Int, candidates: Int): String

    /**
     * 解析一条分析输出行，返回完整 [AnalysisResult]；无法解析返回 null。
     * - KataGo：每行是一段完整 JSON（含 rootInfo + moveInfos）
     * - LeelaZero：每行是单个候选的 `info move ...` 文本，需累积
     */
    protected abstract fun parseAnalysisLine(
        line: String,
        color: Stone,
        boardSize: Int,
        maxCandidates: Int
    ): AnalysisResult?

    /**
     * 流式分析：通过 [callbackFlow] 把 native 回调转成 Flow，收集直到达到 [maxVisits] 或超时。
     * 返回最后一条有效结果；超时且无结果时抛 [EngineException]。
     */
    protected suspend fun runStreamingAnalysis(
        command: String,
        color: Stone,
        boardSize: Int,
        maxVisits: Int,
        candidates: Int
    ): AnalysisResult = coroutineScope {
        val bestRef = AtomicReference<AnalysisResult?>(null)
        val done = CompletableDeferred<Unit>()

        val flow: Flow<String> = callbackFlow {
            val callbackId = NativeEngineBridge.registerAnalysisCallback { line ->
                trySend(line)
            }
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

        // 在当前 coroutineScope 中启动收集协程
        val collectJob = launch {
            flow.collect { line ->
                val r = parseAnalysisLine(line, color, boardSize, candidates)
                if (r != null) {
                    bestRef.set(r)
                    if (r.visits >= maxVisits) {
                        done.complete(Unit)
                    }
                }
            }
        }

        try {
            withTimeoutOrNull(analysisTimeoutMs) { done.await() }
        } finally {
            collectJob.cancel()
        }

        bestRef.get() ?: throw EngineException("$engineDisplayName 分析超时且未收到有效结果")
    }

    override suspend fun ponder(state: GameState, color: Stone) = withContext(Dispatchers.Default) {
        stopPonder()
        ensureReady()
        syncState(state)
        // 用流式分析命令近似 ponder：C++ 端持续计算直到 stopAnalysis 被调用
        val command = buildAnalyzeCommand(color.toGtpColor(), config.maxVisits, 5)
        val cbId = NativeEngineBridge.registerAnalysisCallback { /* 丢弃输出，仅维持分析运行 */ }
        ponderCallbackId = cbId
        try {
            NativeEngineBridge.startAnalysis(handle, command, cbId)
        } catch (e: Throwable) {
            NativeEngineBridge.unregisterAnalysisCallback(cbId)
            ponderCallbackId = null
            throw EngineException("启动 ponder 失败：${e.message}", e)
        }
    }

    override suspend fun stopPonder() {
        val cbId = ponderCallbackId
        ponderCallbackId = null
        if (cbId != null) {
            if (handle != 0L) {
                try { NativeEngineBridge.stopAnalysis(handle) } catch (_: Throwable) {}
            }
            NativeEngineBridge.unregisterAnalysisCallback(cbId)
        }
    }

    override suspend fun evaluateMove(state: GameState, vertex: Vertex): Double =
        withContext(Dispatchers.Default) {
            ensureReady()
            syncState(state)
            val smallVisits = minOf(100, config.maxVisits.coerceAtLeast(1))
            val result = analyze(state, state.toMove, maxVisits = smallVisits, candidates = 10)
            val hit = result.candidates.firstOrNull { it.vertex == vertex }
            hit?.winRate ?: result.winRate
        }

    override fun getEngineInfo(): Map<String, String> {
        val info = linkedMapOf(
            "name" to engineDisplayName,
            "type" to config.type.displayName,
            "threads" to config.threads.toString(),
            "maxVisits" to config.maxVisits.toString(),
            "boardSize" to config.boardSize.toString(),
            "komi" to config.komi.toString(),
            "weightsPath" to config.weightsPath,
            "cpuOnly" to config.cpuOnly.toString()
        )
        if (handle != 0L) {
            try {
                info["protocolVersion"] = send(GtpCommand.protocolVersion())
                info["engineName"] = send(GtpCommand.name())
                info["version"] = send(GtpCommand.version())
            } catch (_: EngineException) {
                // 忽略查询失败
            }
        }
        return info
    }

    /** [Stone] 转 GTP 颜色字符串。 */
    protected fun Stone.toGtpColor(): String = when (this) {
        Stone.BLACK -> "B"
        Stone.WHITE -> "W"
        Stone.EMPTY -> "B"
    }
}

/**
 * KataGo 引擎实现。
 *
 * 通过 [NativeEngineBridge] 调用 native KataGo，使用 `kata-analyze` JSON 流式输出。
 *
 * 真实集成说明：
 * - 用户需将编译好的 `libkatago.so` 放入 `app/src/main/jniLibs/<abi>/`
 * - 权重文件 `katago_b18c384.bin.gz` 放入 `app/src/main/assets/weights/`
 * - 在 CMakeLists 中开启 `-DWEIQI_LINK_KATAGO=ON` 并设置 `KATAGO_LIB_DIR`
 * - 未链接时使用 stub，genmove 返回随机合法点或 pass
 */
class KataGoEngine(
    config: EngineConfig
) : BaseNativeEngine(config) {

    override val engineTypeCode: Int = NativeEngineBridge.ENGINE_KATAGO
    override val engineDisplayName: String = "KataGo"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun buildAnalyzeCommand(color: String, maxVisits: Int, candidates: Int): String =
        GtpCommand.kataAnalyze(color, maxVisits, candidates)

    override fun parseAnalysisLine(
        line: String,
        color: Stone,
        boardSize: Int,
        maxCandidates: Int
    ): AnalysisResult? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return try {
            val root = json.parseToJsonElement(trimmed).jsonObject
            val rootInfo = root["rootInfo"]?.jsonObject
            val winRate = rootInfo?.get("winrate")?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: return null
            val scoreLead = rootInfo["scoreLead"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val visits = rootInfo["visits"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

            val moveInfos = root["moveInfos"]?.jsonArray ?: emptyList()
            val candidatesList = moveInfos.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val moveStr = obj["move"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val mvWinRate = obj["winrate"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: winRate
                val mvScore = obj["scoreLead"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: scoreLead
                val mvVisits = obj["visits"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val pvArr = obj["pv"]?.jsonArray ?: emptyList()
                val pv = pvArr.mapNotNull { pvEl ->
                    val pvStr = pvEl.jsonPrimitive.content
                    parseGtpMove(pvStr, color, boardSize)
                }
                val vertex = parseGtpVertex(moveStr, boardSize) ?: return@mapNotNull null
                MoveCandidate(vertex, mvWinRate, mvScore, mvVisits, pv)
            }.sortedByDescending { it.visits }.take(maxCandidates)

            val best = candidatesList.firstOrNull()
            val bestMove = when {
                best == null -> Move.Pass(color)
                best.vertex.isPass() -> Move.Pass(color)
                else -> Move.Play(best.vertex, color)
            }

            AnalysisResult(
                bestMove = bestMove,
                winRate = winRate,
                scoreLead = scoreLead,
                visits = visits,
                candidates = candidatesList,
                isResign = false
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** 解析 GTP 坐标（如 "Q16"、"pass"）为 [Vertex]。 */
    private fun parseGtpVertex(token: String, boardSize: Int): Vertex? {
        val t = token.trim()
        if (t.isEmpty() || t.equals("pass", ignoreCase = true) || t.equals("resign", ignoreCase = true)) {
            return Vertex.pass()
        }
        return try { Vertex.fromDisplay(t, boardSize) } catch (_: IllegalArgumentException) { null }
    }

    /** 解析 GTP 坐标为 [Move]（假设全部为行棋方颜色，简化 PV 还原）。 */
    private fun parseGtpMove(token: String, color: Stone, boardSize: Int): Move? {
        val t = token.trim()
        if (t.isEmpty()) return null
        if (t.equals("pass", ignoreCase = true)) return Move.Pass(color)
        if (t.equals("resign", ignoreCase = true)) return Move.Resign(color)
        val v = parseGtpVertex(t, boardSize) ?: return null
        return if (v.isPass()) Move.Pass(color) else Move.Play(v, color)
    }
}
