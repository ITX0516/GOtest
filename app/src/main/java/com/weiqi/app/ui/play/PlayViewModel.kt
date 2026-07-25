package com.weiqi.app.ui.play

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weiqi.app.core.GameState
import com.weiqi.app.core.GoRules
import com.weiqi.app.core.Move
import com.weiqi.app.core.Score
import com.weiqi.app.core.Stone
import com.weiqi.app.core.Vertex
import com.weiqi.app.engine.EngineManager
import com.weiqi.app.engine.EngineType
import com.weiqi.app.engine.GoEngine
import com.weiqi.app.sgf.SgfConverter
import com.weiqi.app.sgf.SgfParser
import com.weiqi.app.sgf.SgfSerializer
import com.weiqi.app.ui.board.StoneInputMode
import com.weiqi.app.ui.theme.BoardTheme
import com.weiqi.app.ui.theme.SoundManager
import com.weiqi.app.ui.theme.StoneTheme
import com.weiqi.app.ui.theme.ThemePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 对弈模式 ViewModel。
 *
 * 持有三块状态：对局 UI 状态（[uiState]）、主题与输入模式（[themeState]）、
 * 引擎状态（[engineStatus]）。所有状态均以 Compose [State] 暴露，便于 Composable 直接订阅。
 *
 * 主流程：人类落子 → 应用着手并播放音效 → 若对局未结束则触发 AI 思考 →
 * AI 落子并播放音效。AI 思考期间 [PlayUiState.aiThinking] 为 true，UI 显示加载圈。
 *
 * 引擎调用统一切换到 [Dispatchers.Default] 执行，避免阻塞主线程。
 *
 * @param engineManager 引擎生命周期管理器。
 * @param soundManager 音效管理器。
 * @param themePreferences 主题与音效偏好（Flow）。
 * @param savedStateHandle 进程恢复句柄（预留）。
 */
class PlayViewModel(
    private val engineManager: EngineManager,
    private val soundManager: SoundManager,
    private val themePreferences: ThemePreferences,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = mutableStateOf(PlayUiState())
    /** 对弈界面状态。 */
    val uiState: State<PlayUiState> = _uiState

    private val _themeState = mutableStateOf(PlayThemeState())
    /** 棋盘/棋子主题 + 输入模式 + 显示选项。 */
    val themeState: State<PlayThemeState> = _themeState

    private val _engineStatus = mutableStateOf(EngineStatusState())
    /** 引擎运行状态。 */
    val engineStatus: State<EngineStatusState> = _engineStatus

    /** 当前 AI 思考任务，便于在 newGame/undo/jumpToMove 时取消。 */
    private var aiJob: Job? = null

    init {
        // 收集主题偏好（棋盘/棋子/音效）
        viewModelScope.launch {
            themePreferences.boardTheme.collectLatest { board ->
                _themeState.value = _themeState.value.copy(boardTheme = board)
            }
        }
        viewModelScope.launch {
            themePreferences.stoneTheme.collectLatest { stone ->
                _themeState.value = _themeState.value.copy(stoneTheme = stone)
            }
        }
        viewModelScope.launch {
            themePreferences.soundEnabled.collectLatest { enabled ->
                soundManager.setEnabled(enabled)
                _themeState.value = _themeState.value.copy(soundEnabled = enabled)
            }
        }
        // 同步当前引擎状态（可能由其他页面已启动）
        refreshEngineStatus()
    }

    /**
     * 开始一局新对局。
     *
     * @param boardSize 棋盘路数（19/13/9）。
     * @param komi 贴目。
     * @param handicap 让子数。
     * @param humanColor 人类执子颜色。
     * @param aiStrength AI 强度。
     */
    fun newGame(
        boardSize: Int = 19,
        komi: Double = 7.5,
        handicap: Int = 0,
        humanColor: Stone = Stone.BLACK,
        aiStrength: AiStrength = AiStrength.NORMAL
    ) {
        aiJob?.cancel()
        aiJob = null
        val newGameState = GameState.newGame(boardSize, komi, handicap)
        _uiState.value = PlayUiState(
            gameState = newGameState,
            humanColor = humanColor,
            aiStrength = aiStrength
        )
        // 让子棋 >= 2 时白方先行；若人类执白则人类先行，否则 AI（黑）先行
        if (newGameState.toMove == humanColor.opponent) {
            triggerAiMove()
        }
    }

    /**
     * 处理人类在 [vertex] 落子。
     *
     * 校验合法性后应用着手、播放音效，并触发 AI 思考。
     * 非法着手将写入 [PlayUiState.lastError]。
     */
    fun onHumanMove(vertex: Vertex) {
        val current = _uiState.value
        if (current.aiThinking || current.gameState.gameOver) return
        if (current.gameState.toMove != current.humanColor) return
        val move = Move.Play(vertex, current.humanColor)
        if (!current.gameState.isLegal(move)) {
            _uiState.value = current.copy(lastError = "非法落子：${vertex.displayCoord}")
            return
        }
        applyMoveWithSound(move)
        clearError()
        if (!_uiState.value.gameState.gameOver) {
            triggerAiMove()
        }
    }

    /** 人类弃权（Pass）。 */
    fun pass() {
        val current = _uiState.value
        if (current.aiThinking || current.gameState.gameOver) return
        if (current.gameState.toMove != current.humanColor) return
        applyMoveWithSound(Move.Pass(current.humanColor))
        clearError()
        if (!_uiState.value.gameState.gameOver) {
            triggerAiMove()
        }
    }

    /**
     * 悔棋：回退 2 步（同时回退 AI 的最后一手和自己的最后一手），
     * 不足 2 步则回退 1 步。会取消进行中的 AI 思考。
     */
    fun undo() {
        val current = _uiState.value
        aiJob?.cancel()
        aiJob = null
        val history = current.gameState.moveHistory
        if (history.isEmpty()) {
            _uiState.value = current.copy(aiThinking = false)
            return
        }
        val steps = if (history.size >= 2) 2 else 1
        val newState = current.gameState.undo(steps)
        _uiState.value = current.copy(
            gameState = newState,
            aiThinking = false,
            gameOverMessage = null,
            scoreResult = null
        )
        clearError()
    }

    /** 人类认输：直接结束对局并写入 [PlayUiState.gameOverMessage]。 */
    fun resign() {
        val current = _uiState.value
        if (current.gameState.gameOver) return
        aiJob?.cancel()
        aiJob = null
        val winner = current.humanColor.opponent
        _uiState.value = current.copy(
            gameState = current.gameState.copy(gameOver = true),
            gameOverMessage = "您认输，${winnerLabel(winner)}方胜"
        )
    }

    /**
     * 复盘跳转：将局面重放到第 [index] 步之后（即应用前 [index] 步）。
     * 会取消进行中的 AI 思考。index 超出历史范围则忽略。
     */
    fun jumpToMove(index: Int) {
        val current = _uiState.value
        val history = current.gameState.moveHistory
        if (index < 0 || index > history.size) return
        aiJob?.cancel()
        aiJob = null
        if (index == history.size) {
            // 已在当前位置，仅清理终局提示
            _uiState.value = current.copy(aiThinking = false)
            return
        }
        val steps = history.size - index
        val newState = current.gameState.undo(steps)
        _uiState.value = current.copy(
            gameState = newState,
            aiThinking = false,
            gameOverMessage = null,
            scoreResult = null
        )
    }

    /** 切换音效开关。 */
    fun toggleSound() {
        val enabled = !_themeState.value.soundEnabled
        soundManager.setEnabled(enabled)
        _themeState.value = _themeState.value.copy(soundEnabled = enabled)
    }

    /** 设置落子输入模式。 */
    fun setStoneInputMode(mode: StoneInputMode) {
        _themeState.value = _themeState.value.copy(inputMode = mode)
    }

    /** 导出当前对局为 SGF 字符串。 */
    fun saveToSgf(): String {
        val state = _uiState.value.gameState
        val tree = SgfConverter.fromGameState(state)
        return SgfSerializer.serialize(tree)
    }

    /**
     * 从 SGF 字符串导入对局。
     * @return 解析成功返回 true 并替换当前对局；失败写入 [PlayUiState.lastError] 并返回 false。
     */
    fun loadFromSgf(sgf: String): Boolean {
        return try {
            val tree = SgfParser.parse(sgf)
            val state = SgfConverter.toGameState(tree)
            aiJob?.cancel()
            aiJob = null
            _uiState.value = _uiState.value.copy(
                gameState = state,
                humanColor = Stone.BLACK,
                aiThinking = false,
                gameOverMessage = if (state.gameOver) "对局已结束" else null,
                scoreResult = null,
                lastError = null
            )
            true
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(lastError = "SGF 导入失败：${e.message}")
            false
        }
    }

    /** 启动引擎；失败信息写入 [EngineStatusState.errorMessage]。 */
    fun startEngine() {
        viewModelScope.launch {
            try {
                _engineStatus.value = _engineStatus.value.copy(errorMessage = null)
                val engine = withContext(Dispatchers.Default) {
                    engineManager.startEngine()
                }
                updateEngineFromActive(engine)
            } catch (e: Exception) {
                _engineStatus.value = _engineStatus.value.copy(
                    isReady = false,
                    errorMessage = "引擎启动失败：${e.message}"
                )
            }
        }
    }

    /** 停止引擎并清空状态。 */
    fun stopEngine() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    engineManager.stopEngine()
                }
            } catch (e: Exception) {
                _engineStatus.value = _engineStatus.value.copy(errorMessage = "停止引擎失败：${e.message}")
                return@launch
            }
            _engineStatus.value = EngineStatusState()
        }
    }

    /**
     * 切换到指定类型的引擎。需求 12 要求点击状态栏可切换引擎。
     * @param type 目标引擎类型。
     */
    fun switchEngine(type: EngineType) {
        viewModelScope.launch {
            try {
                _engineStatus.value = _engineStatus.value.copy(errorMessage = null)
                val engine = withContext(Dispatchers.Default) {
                    engineManager.switchEngine(type)
                }
                updateEngineFromActive(engine)
            } catch (e: Exception) {
                _engineStatus.value = _engineStatus.value.copy(
                    isReady = false,
                    errorMessage = "切换引擎失败：${e.message}"
                )
            }
        }
    }

    // ===== 内部实现 =====

    /**
     * 应用一步着手并播放对应音效。
     * 双方连续 Pass 触发终局数子（中国规则，area scoring）。
     */
    private fun applyMoveWithSound(move: Move) {
        val current = _uiState.value
        val before = current.gameState
        val after = before.applyMove(move)
        // 音效
        when (move) {
            is Move.Play -> {
                val isBlack = move.stone == Stone.BLACK
                val captured = if (move.stone == Stone.BLACK) {
                    after.blackCaptures - before.blackCaptures
                } else {
                    after.whiteCaptures - before.whiteCaptures
                }
                if (captured > 0) soundManager.playCaptureSound() else soundManager.playStoneSound(isBlack)
            }
            is Move.Pass -> soundManager.playPassSound()
            is Move.Resign -> { /* 认输无音效 */ }
        }
        // 终局数子：仅双方 Pass 结束时计算
        val score: Score? = if (after.gameOver && after.consecutivePasses >= 2) {
            GoRules.calculateAreaScore(after.board, after.komi)
        } else null
        val message: String? = when {
            move is Move.Resign -> {
                val winner = move.stone.opponent
                "${winnerLabel(winner)}方胜（对方认输）"
            }
            score != null -> "对局结束：${score.displayText}"
            after.gameOver -> "对局结束"
            else -> null
        }
        _uiState.value = current.copy(
            gameState = after,
            scoreResult = score,
            gameOverMessage = message
        )
    }

    /**
     * 触发 AI 思考并落子。
     * - 设置 [PlayUiState.aiThinking] = true
     * - 调用 [GoEngine.genMove]，timeLimitMs=0 让引擎以 visits 控制
     * - 应用 AI 着手并播放音效
     */
    private fun triggerAiMove() {
        val current = _uiState.value
        if (current.gameState.gameOver) return
        val aiColor = current.humanColor.opponent
        if (current.gameState.toMove != aiColor) return
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(aiThinking = true)
            try {
                val engine = engineManager.activeEngine
                if (engine == null || !engine.isReady) {
                    _uiState.value = _uiState.value.copy(
                        aiThinking = false,
                        lastError = "引擎未启动，无法生成 AI 着手"
                    )
                    return@launch
                }
                // 引擎计算切到 Default 线程；timeLimitMs=0 由引擎以 visits 控制
                val move = withContext(Dispatchers.Default) {
                    engine.genMove(current.gameState, aiColor, timeLimitMs = 0L)
                }
                // 计算期间若对局已被重置/结束则放弃
                val state = _uiState.value
                if (state.gameState.gameOver) return@launch
                if (state.gameState.toMove != aiColor) return@launch
                applyMoveWithSound(move)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(lastError = "AI 思考失败：${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(aiThinking = false)
            }
        }
    }

    /** 从 [engineManager.activeEngine] 同步状态到 [_engineStatus]。 */
    private fun refreshEngineStatus() {
        engineManager.activeEngine?.let { updateEngineFromActive(it) }
    }

    /** 用引擎实例更新引擎状态。 */
    private fun updateEngineFromActive(engine: GoEngine) {
        _engineStatus.value = EngineStatusState(
            engineType = engine.type,
            isReady = engine.isReady,
            isPondering = false,
            info = runCatching { engine.getEngineInfo() }.getOrDefault(emptyMap()),
            errorMessage = null
        )
    }

    /** 清除 [PlayUiState.lastError]。 */
    private fun clearError() {
        if (_uiState.value.lastError != null) {
            _uiState.value = _uiState.value.copy(lastError = null)
        }
    }

    /** 棋子颜色对应的中文标签。 */
    private fun winnerLabel(stone: Stone): String = when (stone) {
        Stone.BLACK -> "黑"
        Stone.WHITE -> "白"
        else -> ""
    }

    override fun onCleared() {
        super.onCleared()
        aiJob?.cancel()
    }
}

/**
 * 对弈界面状态。
 *
 * @param gameState 当前对局状态（含棋盘、走子历史、轮次等）。
 * @param humanColor 人类执子颜色。
 * @param aiThinking AI 是否正在思考。
 * @param aiStrength 当前 AI 强度。
 * @param lastError 最近一次错误提示（Snackbar 展示）。
 * @param scoreResult 终局数子结果；非终局为 null。
 * @param gameOverMessage 对局结束消息；进行中为 null。
 * @param pendingVertex 待确认落子点（用于双击/确认模式预览）。
 */
data class PlayUiState(
    val gameState: GameState = GameState.newGame(),
    val humanColor: Stone = Stone.BLACK,
    val aiThinking: Boolean = false,
    val aiStrength: AiStrength = AiStrength.NORMAL,
    val lastError: String? = null,
    val scoreResult: Score? = null,
    val gameOverMessage: String? = null,
    val pendingVertex: Vertex? = null
)

/**
 * AI 强度档位。每个档位对应不同的最大访问数 [visits]，
 * 调用 genMove(timeLimitMs=0) 时由引擎以 visits 控制思考强度。
 *
 * @property displayName UI 显示名。
 * @property visits 最大访问数。
 * @property label 棋力标签（级/段位）。
 */
enum class AiStrength(val displayName: String, val visits: Int, val label: String) {
    BEGINNER("入门", 50, "5级"),
    EASY("轻松", 100, "1级"),
    NORMAL("标准", 300, "3段"),
    HARD("挑战", 800, "5段"),
    EXPERT("专家", 2000, "6段"),
    MASTER("大师", 5000, "职业");
}

/**
 * 主题与输入模式状态。
 *
 * @param boardTheme 棋盘主题。
 * @param stoneTheme 棋子主题。
 * @param inputMode 落子输入模式。
 * @param soundEnabled 是否启用音效。
 * @param showCoordinates 是否显示坐标。
 * @param showMoveNumber 是否显示手数。
 */
data class PlayThemeState(
    val boardTheme: BoardTheme = BoardTheme.Classic,
    val stoneTheme: StoneTheme = StoneTheme.Standard,
    val inputMode: StoneInputMode = StoneInputMode.SINGLE_TAP,
    val soundEnabled: Boolean = true,
    val showCoordinates: Boolean = true,
    val showMoveNumber: Boolean = false
)

/**
 * 引擎运行状态。
 *
 * @param engineType 当前引擎类型；未启动为 null。
 * @param isReady 引擎是否就绪。
 * @param isPondering 是否正在后台 ponder。
 * @param info 引擎元信息（名称、版本、线程数等）。
 * @param errorMessage 引擎错误信息；正常为 null。
 */
data class EngineStatusState(
    val engineType: EngineType? = null,
    val isReady: Boolean = false,
    val isPondering: Boolean = false,
    val info: Map<String, String> = emptyMap(),
    val errorMessage: String? = null
)
