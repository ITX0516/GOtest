package com.weiqi.app.remote

import com.weiqi.app.core.GameState
import com.weiqi.app.core.Move
import com.weiqi.app.core.Stone
import com.weiqi.app.core.Vertex
import com.weiqi.app.engine.AnalysisResult
import com.weiqi.app.engine.EngineType
import com.weiqi.app.engine.GoEngine
import com.weiqi.app.engine.GtpCommand
import com.weiqi.app.engine.MoveCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 远程计算引擎：通过 GTP 协议接入远程 KataGo / LeelaZero 等引擎，实现 [GoEngine] 接口。
 *
 * 支持四种接入方式（由 [RemoteConfig.platform] 决定）：
 * - [RemotePlatform.ZHIXING_CLOUD]：经由智星云 REST API 创建/启动实例，再连 GTP；
 * - [RemotePlatform.SUANYUN]：经由算云 REST API 创建/启动实例，再连 GTP；
 * - [RemotePlatform.CUSTOM_PC]：直连用户提供的 host:port；
 * - [RemotePlatform.SSH_TUNNEL]：用户自行建立 SSH 隧道，本端按本地端口连接。
 *
 * 工作流程：
 * 1. [start]：根据平台创建客户端 → （若需要）启动实例并等待 RUNNING → 获取 GTP 接入点 →
 *    [GtpClient.connect] → 发送初始化命令（boardsize/komi）。
 * 2. [genMove] / [analyze]：先通过 `clear_board` + 重放 `play` 同步局面，再发对应 GTP 命令。
 * 3. 网络异常时通过 [reconnect] 进行指数退避重连（最多 3 次）。
 *
 * @param config 远程连接配置。
 */
class RemoteEngine(
    private val config: RemoteConfig
) : GoEngine {

    /** 引擎显示名称。 */
    override val name: String
        get() = "远程引擎(${config.platform.displayName})"

    /** 引擎类型固定为 [EngineType.REMOTE]。 */
    override val type: EngineType
        get() = EngineType.REMOTE

    /** 是否就绪（GTP 客户端已连接）。 */
    override val isReady: Boolean
        get() = gtpClient?.isConnected == true

    /** GTP 客户端实例，[start] 后非空。 */
    @Volatile
    private var gtpClient: GtpClient? = null

    /** 平台 REST 客户端（ZhixingCloudClient / SuanyunClient），直连场景为 null。 */
    @Volatile
    private var platformClient: Any? = null

    /** 当前棋盘路数，用于 GTP 坐标换算。 */
    @Volatile
    private var currentBoardSize: Int = 19

    /** 当前贴目。 */
    @Volatile
    private var currentKomi: Double = 7.5

    /** 后台 ponder 任务。 */
    @Volatile
    private var ponderJob: Job? = null

    /** 引擎内部协程作用域，[shutdown] 时取消。 */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 共享 OkHttp 客户端，供平台 REST 调用使用。 */
    private val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(config.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 启动远程引擎：建立 GTP 连接并发送初始化命令。
     *
     * 云平台场景下，若 [RemoteConfig.instanceId] 为空则创建新实例，否则等待已有实例就绪。
     *
     * @throws RemoteComputeException 连接/认证/实例就绪失败。
     */
    override suspend fun start() {
        when (config.platform) {
            RemotePlatform.ZHIXING_CLOUD -> startViaCloud(config.platform)
            RemotePlatform.SUANYUN -> startViaCloud(config.platform)
            RemotePlatform.CUSTOM_PC, RemotePlatform.SSH_TUNNEL -> startDirect()
        }

        // 建立 GTP 连接并发送初始化命令
        val client = gtpClient
            ?: throw RemoteComputeException.ConnectionFailed(config.host, config.port)
        client.connect()
        // 发送初始化命令：boardsize + komi
        client.send(GtpCommand.boardsize(currentBoardSize))
        client.send(GtpCommand.komi(currentKomi))
    }

    /**
     * 显式建立连接（供外部在不调用 [start] 全流程时使用）。
     * 等价于 [start] 中 GTP 连接部分，含指数退避重试。
     */
    suspend fun connect() {
        ensureConnectedWithRetry()
    }

    /**
     * 重连：先断开现有连接，再以指数退避重试（最多 3 次）。
     *
     * 退避间隔：500ms → 1000ms（3 次尝试之间共 2 次退避）。
     *
     * @throws RemoteComputeException.ConnectionFailed 重试耗尽仍失败。
     */
    suspend fun reconnect() {
        try { gtpClient?.disconnect() } catch (_: Exception) {}
        ensureConnectedWithRetry()
    }

    /**
     * 获取人类可读的连接信息。
     * @return 形如 "智星云 1.2.3.4:8080 (实例: abc123) [已连接]"。
     */
    fun getConnectionInfo(): String {
        val sb = StringBuilder()
        sb.append(config.platform.displayName).append(' ')
        sb.append(config.host).append(':').append(config.port)
        if (config.instanceId.isNotEmpty()) {
            sb.append(" (实例: ").append(config.instanceId).append(')')
        }
        sb.append(if (isReady) " [已连接]" else " [未连接]")
        return sb.toString()
    }

    /**
     * 关闭引擎：停止 ponder、断开 GTP、取消协程作用域。
     */
    override suspend fun shutdown() {
        stopPonder()
        engineScope.cancel()
        try { gtpClient?.disconnect() } catch (_: Exception) {}
        gtpClient = null
        platformClient = null
    }

    /**
     * 生成一步着法。
     *
     * 先同步局面到远程，再发送 `genmove` 命令；可选地通过 `time_left` 设置思考时限。
     *
     * @param state 当前局面。
     * @param color 行棋方。
     * @param timeLimitMs 思考时限（毫秒）；0 表示不限。
     * @return 引擎返回的着手（Play / Pass / Resign）。
     */
    override suspend fun genMove(
        state: GameState,
        color: Stone,
        timeLimitMs: Long
    ): Move = withConnectionRetry {
        ensureConnected()
        syncBoard(state)
        val gtpColor = toGtpColor(color)
        // 设置思考时限（部分引擎支持 time_left）
        if (timeLimitMs > 0) {
            runCatching {
                gtpClient?.send(GtpCommand.timeLeft(gtpColor, timeLimitMs))
            }
        }
        val response = gtpClient?.send(GtpCommand.genmove(gtpColor))
            ?: throw RemoteComputeException.ConnectionFailed(config.host, config.port)
        parseGenMoveResponse(response, color, state.boardSize)
    }

    /**
     * 分析当前局面，返回候选着法列表。
     *
     * 先同步局面，再通过 `lz-analyze` 获取分析流（`visits` 参数控制最大访问数，
     * 达到后引擎自动停止），取最后一帧解析为 [AnalysisResult]。
     *
     * @param state 当前局面。
     * @param color 行棋方。
     * @param maxVisits 最大访问数；<=0 时不限制，取首帧后中断。
     * @param candidates 返回的候选着法上限。
     * @return 分析结果。
     */
    override suspend fun analyze(
        state: GameState,
        color: Stone,
        maxVisits: Int,
        candidates: Int
    ): AnalysisResult = withConnectionRetry {
        ensureConnected()
        syncBoard(state)
        val gtpColor = toGtpColor(color)
        // lz-analyze 的 visits 参数控制总访问数上限，达到后自动停止
        val cmd = GtpCommand.lzAnalyze(gtpColor, maxVisits.coerceAtLeast(0))

        var lastLine = ""
        withTimeoutOrNull(config.readTimeoutMs.toLong()) {
            gtpClient?.analyzeStream(cmd)?.collect { line ->
                lastLine = line
                // 无 visits 限制时取到首帧即可
                if (maxVisits <= 0) {
                    gtpClient?.stopAnalyzeStream()
                }
            }
        }

        if (lastLine.isEmpty()) {
            throw RemoteComputeException.GtpError(cmd, "无分析输出")
        }

        parseAnalysisResult(lastLine, color, state.boardSize, candidates)
    }

    /**
     * 后台持续思考（ponder）。在 [engineScope] 中启动 `lz-analyze` 流并丢弃输出，
     * 直到 [stopPonder] 被调用。
     *
     * @param state 当前局面。
     * @param color 行棋方。
     */
    override suspend fun ponder(state: GameState, color: Stone) {
        stopPonder()
        ensureConnected()
        syncBoard(state)
        val gtpColor = toGtpColor(color)
        // visits 0 表示不限，持续计算直到 stopPonder
        ponderJob = engineScope.launch {
            try {
                gtpClient?.analyzeStream(GtpCommand.lzAnalyze(gtpColor, 0))?.collect {
                    // 丢弃输出，仅维持后台分析
                }
            } catch (_: Exception) {
                // 后台 ponder 异常忽略，下次调用会重建
            }
        }
    }

    /** 停止后台 ponder。 */
    override suspend fun stopPonder() {
        ponderJob?.cancel()
        ponderJob = null
        gtpClient?.stopAnalyzeStream()
    }

    /**
     * 评估指定着法的胜率。
     *
     * 同步局面后发起一次轻量分析（visits=200），在候选中查找该着法并返回其 winRate。
     * 若该着法不在候选中，返回当前局面根胜率。
     *
     * @param state 当前局面。
     * @param vertex 待评估的坐标。
     * @return 胜率（0.0-1.0），或负值表示无法评估。
     */
    override suspend fun evaluateMove(state: GameState, vertex: Vertex): Double =
        withConnectionRetry {
            ensureConnected()
            syncBoard(state)
            val gtpColor = toGtpColor(state.toMove)
            val cmd = GtpCommand.lzAnalyze(gtpColor, 200)
            var lastLine = ""
            withTimeoutOrNull(30_000) {
                gtpClient?.analyzeStream(cmd)?.collect { lastLine = it }
            }
            if (lastLine.isEmpty()) return@withConnectionRetry -1.0
            val result = parseAnalysisResult(lastLine, state.toMove, state.boardSize, 64)
            val hit = result.candidates.firstOrNull { it.vertex == vertex }
            hit?.winRate ?: result.winRate
        }

    /**
     * 返回引擎元信息，用于 UI 展示。
     */
    override fun getEngineInfo(): Map<String, String> = linkedMapOf(
        "name" to name,
        "type" to config.platform.displayName,
        "host" to config.host,
        "port" to config.port.toString(),
        "connected" to isReady.toString(),
        "instanceId" to config.instanceId,
        "enginePath" to config.enginePath,
        "weightsPath" to config.weightsPath
    )

    // ====== 内部实现 ======

    /**
     * 以指数退避重试连接（最多 3 次）。
     * 退避间隔：500ms → 1000ms（3 次尝试之间共 2 次退避）。
     */
    private suspend fun ensureConnectedWithRetry() {
        val client = gtpClient
            ?: throw RemoteComputeException.ConnectionFailed(config.host, config.port)
        if (client.isConnected) return

        var delayMs = 500L
        var lastException: Exception? = null
        for (attempt in 1..3) {
            try {
                client.connect()
                // 连接后发送初始化命令
                client.send(GtpCommand.boardsize(currentBoardSize))
                client.send(GtpCommand.komi(currentKomi))
                return
            } catch (e: RemoteComputeException) {
                lastException = e
                if (attempt < 3) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        throw lastException
            ?: RemoteComputeException.ConnectionFailed(config.host, config.port)
    }

    /** 确保已连接，未连接则抛出异常。 */
    private suspend fun ensureConnected() {
        val client = gtpClient
        if (client == null || !client.isConnected) {
            throw RemoteComputeException.ConnectionFailed(config.host, config.port)
        }
    }

    /**
     * 包装操作：遇连接/超时异常时先 [reconnect] 再重试一次。
     * GTP 错误、认证错误等直接抛出，不重试。
     */
    private suspend fun <T> withConnectionRetry(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: RemoteComputeException.ConnectionFailed) {
            reconnect()
            return block()
        } catch (e: RemoteComputeException.Timeout) {
            reconnect()
            return block()
        }
    }

    /**
     * 经云平台启动：创建 REST 客户端 → 确保实例 RUNNING → 获取 GTP 接入点 → 创建 GtpClient。
     */
    private suspend fun startViaCloud(platform: RemotePlatform) {
        val endpoint: GtpEndpoint = when (platform) {
            RemotePlatform.ZHIXING_CLOUD -> {
                val client = ZhixingCloudClient(config.authToken, sharedHttpClient)
                platformClient = client
                ensureInstanceRunningZhixing(client)
            }
            RemotePlatform.SUANYUN -> {
                val client = SuanyunClient(config.authToken, sharedHttpClient)
                platformClient = client
                ensureInstanceRunningSuanyun(client)
            }
            else -> error("非云平台: $platform")
        }
        gtpClient = GtpClient(
            host = endpoint.host,
            port = endpoint.port,
            password = endpoint.password.ifEmpty { config.password },
            connectTimeoutMs = config.connectTimeoutMs,
            readTimeoutMs = config.readTimeoutMs
        )
    }

    /** 智星云：确保实例 RUNNING 并返回 GTP 接入点。 */
    private suspend fun ensureInstanceRunningZhixing(client: ZhixingCloudClient): GtpEndpoint {
        val instance = if (config.instanceId.isNotEmpty()) {
            client.awaitRunning(config.instanceId)
        } else {
            val spec = InstanceSpec(
                name = "weiqi-${System.currentTimeMillis()}",
                gpuType = "RTX3090",
                engineType = config.enginePath.ifEmpty { "katago" },
                weightsName = config.weightsPath.ifEmpty { "b18c384" }
            )
            val created = client.createInstance(spec)
            client.awaitRunning(created.id)
        }
        return instance.gtpEndpoint ?: client.getGtpEndpoint(instance.id)
    }

    /** 算云：确保实例 RUNNING 并返回 GTP 接入点。 */
    private suspend fun ensureInstanceRunningSuanyun(client: SuanyunClient): GtpEndpoint {
        val instance = if (config.instanceId.isNotEmpty()) {
            client.awaitRunning(config.instanceId)
        } else {
            val spec = InstanceSpec(
                name = "weiqi-${System.currentTimeMillis()}",
                gpuType = "RTX3090",
                engineType = config.enginePath.ifEmpty { "katago" },
                weightsName = config.weightsPath.ifEmpty { "b18c384" }
            )
            val created = client.createInstance(spec)
            client.awaitRunning(created.id)
        }
        return instance.gtpEndpoint ?: client.getGtpEndpoint(instance.id)
    }

    /** 直连模式：直接用 config 的 host/port 创建 GtpClient。 */
    private fun startDirect() {
        gtpClient = GtpClient(
            host = config.host,
            port = config.port,
            password = config.password,
            connectTimeoutMs = config.connectTimeoutMs,
            readTimeoutMs = config.readTimeoutMs
        )
    }

    /**
     * 同步局面到远程引擎：boardsize → clear_board → komi → 重放 play。
     * Resign 不重放（GTP 无对应语义）。
     */
    private suspend fun syncBoard(state: GameState) {
        val client = gtpClient
            ?: throw RemoteComputeException.ConnectionFailed(config.host, config.port)
        currentBoardSize = state.boardSize
        currentKomi = state.komi
        client.send(GtpCommand.boardsize(state.boardSize))
        client.send(GtpCommand.clearBoard())
        client.send(GtpCommand.komi(state.komi))
        for (move in state.moveHistory) {
            when (move) {
                is Move.Play -> {
                    val coord = if (move.vertex.isPass()) "pass" else move.vertex.displayCoord
                    client.send(GtpCommand.play(toGtpColor(move.stone), coord))
                }
                is Move.Pass -> {
                    client.send(GtpCommand.play(toGtpColor(move.stone), "pass"))
                }
                is Move.Resign -> {
                    // 认输不重放
                }
            }
        }
    }

    /** Stone → GTP 颜色字符（"B"/"W"）。 */
    private fun toGtpColor(stone: Stone): String = when (stone) {
        Stone.BLACK -> "B"
        Stone.WHITE -> "W"
        else -> "B"
    }

    /** 解析 genmove 响应为 [Move]：resign / pass / 坐标。 */
    private fun parseGenMoveResponse(raw: String, color: Stone, boardSize: Int): Move {
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

    /**
     * 解析 lz-analyze 单帧输出为 [AnalysisResult]。
     *
     * 输入形如（一行包含多个 `info` 段）：
     * ```
     * info move D16 visits 100 winrate 5234 scoreMean -3.5 pv D16 Q4 D3 info move Q16 visits 80 winrate 5198 pv Q16
     * ```
     * 按 `info` 分段解析，winrate 由 0-10000 归一化为 0.0-1.0。
     * 根胜率取访问数最多的候选；总访问数取所有候选之和。
     */
    private fun parseAnalysisResult(
        line: String,
        color: Stone,
        boardSize: Int,
        maxCandidates: Int
    ): AnalysisResult {
        val candidatesList = parseCandidates(line, color, boardSize, maxCandidates)
        val best = candidatesList.firstOrNull()
        val bestMove: Move = when {
            best == null -> Move.Pass(color)
            best.vertex.isPass() -> Move.Pass(color)
            else -> Move.Play(best.vertex, color)
        }
        val totalVisits = candidatesList.sumOf { it.visits }
        val rootWinRate = best?.winRate ?: 0.0
        val rootScoreLead = best?.scoreLead ?: 0.0
        return AnalysisResult(
            bestMove = bestMove,
            winRate = rootWinRate,
            scoreLead = rootScoreLead,
            visits = totalVisits,
            candidates = candidatesList,
            isResign = false
        )
    }

    /** 解析一行 lz-analyze 输出中的全部候选，返回按 visits 降序、最多 [maxCandidates] 条。 */
    private fun parseCandidates(
        line: String,
        color: Stone,
        boardSize: Int,
        maxCandidates: Int
    ): List<MoveCandidate> {
        val candidates = mutableListOf<MoveCandidate>()
        val segments = line.split("info").map { it.trim() }.filter { it.isNotEmpty() }
        // lz-analyze 已知字段名，用于判定 pv 解析终止位置
        val knownKeys = setOf(
            "move", "visits", "winrate", "scoreMean", "scoreLead", "pv",
            "prior", "lcb", "utility", "order", "scoreStdev"
        )
        for (seg in segments) {
            val tokens = seg.split(" ").filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            var move = ""
            var visits = 0
            var winRate = 0.0
            var scoreLead = 0.0
            val pvCoords = mutableListOf<String>()
            var i = 0
            while (i < tokens.size) {
                when (tokens[i]) {
                    "move" -> { move = tokens.getOrNull(i + 1) ?: ""; i += 2 }
                    "visits" -> { visits = tokens.getOrNull(i + 1)?.toIntOrNull() ?: 0; i += 2 }
                    "winrate" -> {
                        val raw = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: 0.0
                        // lz-analyze winrate 范围 0-10000，归一化为 0.0-1.0
                        winRate = if (raw > 1.0) raw / 10000.0 else raw
                        i += 2
                    }
                    "scoreLead" -> {
                        scoreLead = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: 0.0
                        i += 2
                    }
                    "scoreMean" -> {
                        // 部分引擎仅输出 scoreMean，作为 scoreLead 的近似
                        if (scoreLead == 0.0) {
                            scoreLead = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: 0.0
                        }
                        i += 2
                    }
                    "pv" -> {
                        i += 1
                        while (i < tokens.size && tokens[i] !in knownKeys) {
                            pvCoords.add(tokens[i])
                            i += 1
                        }
                    }
                    else -> i += 1
                }
            }
            if (move.isNotEmpty() && !move.equals("pass", ignoreCase = true)) {
                val vertex = parseGtpVertex(move, boardSize) ?: continue
                val pv = pvCoords.mapNotNull { parseGtpMove(it, color, boardSize) }
                candidates.add(
                    MoveCandidate(
                        vertex = vertex,
                        winRate = winRate,
                        scoreLead = scoreLead,
                        visits = visits,
                        pv = pv
                    )
                )
            }
        }
        return candidates.sortedByDescending { it.visits }.take(maxCandidates)
    }

    /** 解析 GTP 坐标（如 "Q16"、"pass"）为 [Vertex]；非法返回 null。 */
    private fun parseGtpVertex(token: String, boardSize: Int): Vertex? {
        val t = token.trim()
        if (t.isEmpty() || t.equals("pass", ignoreCase = true) || t.equals("resign", ignoreCase = true)) {
            return Vertex.pass()
        }
        return try {
            Vertex.fromDisplay(t, boardSize)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 解析 GTP 坐标为 [Move]（PV 还原，颜色沿用行棋方，与本地引擎保持一致）。 */
    private fun parseGtpMove(token: String, color: Stone, boardSize: Int): Move? {
        val t = token.trim()
        if (t.isEmpty()) return null
        if (t.equals("pass", ignoreCase = true)) return Move.Pass(color)
        if (t.equals("resign", ignoreCase = true)) return Move.Resign(color)
        val v = parseGtpVertex(t, boardSize) ?: return null
        return if (v.isPass()) Move.Pass(color) else Move.Play(v, color)
    }
}
