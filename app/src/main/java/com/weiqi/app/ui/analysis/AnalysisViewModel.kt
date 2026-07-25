package com.weiqi.app.ui.analysis

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weiqi.app.core.GameState
import com.weiqi.app.core.Stone
import com.weiqi.app.core.Vertex
import com.weiqi.app.engine.AnalysisResult
import com.weiqi.app.engine.EngineManager
import com.weiqi.app.sgf.SgfConverter
import com.weiqi.app.sgf.SgfParser
import com.weiqi.app.sgf.SgfSerializer
import com.weiqi.app.ui.theme.BoardTheme
import com.weiqi.app.ui.theme.StoneTheme
import com.weiqi.app.ui.theme.ThemePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分析模式 UI 状态。
 *
 * @property gameState 当前显示的局面（已 replay 到 [currentIndex]）。
 * @property currentIndex 当前查看的着手索引（0 = 初始局面）。
 * @property totalMoves 完整棋谱总手数（用于导航条与滑块范围）。
 * @property isAnalyzing 是否正在分析。
 * @property analysisProgress 分析进度，0..1，基于 visits / targetVisits。
 * @property autoAnalyze 是否开启自动逐手分析。
 * @property targetVisits 目标访问数。
 * @property lastError 最近一次错误或提示信息。
 */
data class AnalysisUiState(
    val gameState: GameState = GameState.newGame(),
    val currentIndex: Int = 0,
    val totalMoves: Int = 0,
    val isAnalyzing: Boolean = false,
    val analysisProgress: Float = 0f,
    val autoAnalyze: Boolean = false,
    val targetVisits: Int = 800,
    val lastError: String? = null
)

/**
 * 分析模式主题状态。
 *
 * @property boardTheme 棋盘主题。
 * @property stoneTheme 棋子主题。
 * @property showCoordinates 是否显示坐标。
 * @property showMoveNumber 是否显示手数（分析模式默认显示）。
 * @property showWinRateHeatmap 是否显示胜率热力图。
 * @property showCandidateLabels 是否显示候选手标签。
 */
data class AnalysisThemeState(
    val boardTheme: BoardTheme = BoardTheme.Classic,
    val stoneTheme: StoneTheme = StoneTheme.Standard,
    val showCoordinates: Boolean = true,
    val showMoveNumber: Boolean = true,
    val showWinRateHeatmap: Boolean = false,
    val showCandidateLabels: Boolean = true
)

/**
 * 胜率图采样点。
 *
 * @property moveIndex 着手索引。
 * @property blackWinRate 黑方视角胜率，0..1。
 * @property scoreLead 黑方视角目数差（正为黑领先）。
 */
data class WinRateSample(
    val moveIndex: Int,
    val blackWinRate: Double,
    val scoreLead: Double
)

/**
 * 分析模式 ViewModel。
 *
 * 负责：
 * - 加载 SGF / GameState 棋谱并按 [currentIndex] 重建显示局面
 * - 调用 [EngineManager] 进行局面分析与单点评估
 * - 维护胜率历史 [winRateHistory]（黑方视角）
 * - 自动逐手分析循环（[autoAnalyze]）
 *
 * 引擎调用统一切换到 [Dispatchers.Default] 以避免阻塞主线程。
 *
 * @param engineManager 引擎管理器。
 * @param themePreferences 主题偏好（读取棋盘/棋子主题与显示选项）。
 * @param savedStateHandle 已保存状态句柄，用于持久化 currentIndex 等。
 */
class AnalysisViewModel(
    private val engineManager: EngineManager,
    private val themePreferences: ThemePreferences,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 内部 StateFlow 作为可观察状态源；UI 通过 collectAsState() 取得 State<T>
    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    private val _themeState = MutableStateFlow(AnalysisThemeState())
    val themeState: StateFlow<AnalysisThemeState> = _themeState.asStateFlow()

    private val _analysisResult = MutableStateFlow<AnalysisResult?>(null)
    val analysisResult: StateFlow<AnalysisResult?> = _analysisResult.asStateFlow()

    private val _winRateHistory = MutableStateFlow<List<WinRateSample>>(emptyList())
    val winRateHistory: StateFlow<List<WinRateSample>> = _winRateHistory.asStateFlow()

    // 完整棋谱信息（用于按索引重建局面）
    private var fullMoveHistory: List<com.weiqi.app.core.Move> = emptyList()
    private var boardSize: Int = 19
    private var komi: Double = 7.5
    private var handicap: Int = 0

    // 当前分析任务与自动分析循环任务
    private var analysisJob: Job? = null
    private var autoAnalyzeJob: Job? = null

    init {
        // 从主题偏好加载初始主题（具体 API 以 ThemePreferences 实现为准）
        loadThemeFromPreferences()

        // 恢复已保存的 currentIndex
        savedStateHandle.get<Int>(KEY_CURRENT_INDEX)?.let { idx ->
            if (idx > 0) jumpToMove(idx)
        }
    }

    /**
     * 从 SGF 文本加载棋谱。
     * 解析后从头开始（currentIndex = 0）。
     */
    fun loadFromSgf(sgf: String) {
        viewModelScope.launch {
            try {
                val tree = SgfParser.parse(sgf)
                val fullState = SgfConverter.toGameState(tree)
                applyLoadedGame(fullState)
            } catch (e: Exception) {
                _uiState.update { it.copy(lastError = "SGF 加载失败：${e.message}") }
            }
        }
    }

    /**
     * 从 [GameState] 加载棋谱。
     * 使用其 moveHistory 作为完整棋谱，从头开始查看。
     */
    fun loadFromGameState(state: GameState) {
        applyLoadedGame(state)
    }

    /**
     * 跳转到第 [index] 步后的局面（0 = 初始局面）。
     * 通过从 newGame 开始 replay 前 [index] 步重建 GameState。
     */
    fun jumpToMove(index: Int) {
        val total = fullMoveHistory.size
        val clamped = index.coerceIn(0, total)
        _uiState.update { it.copy(currentIndex = clamped) }
        savedStateHandle[KEY_CURRENT_INDEX] = clamped
        rebuildGameState()
        // 跳转后清除旧分析结果，避免与新局面不符
        _analysisResult.value = null
    }

    /** 下一步。 */
    fun nextMove() = jumpToMove(_uiState.value.currentIndex + 1)

    /** 上一步。 */
    fun prevMove() = jumpToMove(_uiState.value.currentIndex - 1)

    /** 回到初始局面。 */
    fun firstMove() = jumpToMove(0)

    /** 跳到末尾。 */
    fun lastMove() = jumpToMove(fullMoveHistory.size)

    /**
     * 启动当前局面的分析。
     *
     * 调用 `engine.analyze(state, toMove, maxVisits, candidates)`。
     * 引擎同步返回时 progress 直接跳变到 1.0。
     * 注：真实流式进度更新需要引擎回调，本实现以一次性返回模拟。
     *
     * 分析完成后追加一个 [WinRateSample]（黑方视角）到 [winRateHistory]。
     */
    fun startAnalysis(maxVisits: Int = 800) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            performAnalysis(maxVisits)
        }
    }

    /** 停止当前分析与自动分析循环。 */
    fun stopAnalysis() {
        analysisJob?.cancel()
        autoAnalyzeJob?.cancel()
        _uiState.update { it.copy(isAnalyzing = false, autoAnalyze = false) }
    }

    /**
     * 评估在 [vertex] 落子的胜率。
     * 调用 `engine.evaluateMove(state, vertex)`，结果显示在 [AnalysisUiState.lastError]。
     */
    fun analyzeAt(vertex: Vertex) {
        viewModelScope.launch {
            try {
                val state = _uiState.value.gameState
                val engine = ensureEngine()
                val winRate = withContext(Dispatchers.Default) {
                    engine.evaluateMove(state, vertex)
                }
                if (winRate < 0.0) {
                    _uiState.update {
                        it.copy(lastError = "无法评估着点 ${vertex.displayCoord}")
                    }
                } else {
                    val percent = "%.1f".format(winRate * 100.0)
                    _uiState.update {
                        it.copy(lastError = "着点 ${vertex.displayCoord} 评估胜率：$percent%")
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(lastError = "评估失败：${e.message}") }
            }
        }
    }

    /**
     * 切换自动逐手分析。
     *
     * 开启后从 [currentIndex] 开始分析每一步，分析完自动 nextMove()，直到结束。
     */
    fun toggleAutoAnalyze() {
        val enable = !_uiState.value.autoAnalyze
        _uiState.update { it.copy(autoAnalyze = enable) }
        autoAnalyzeJob?.cancel()
        if (enable) {
            startAutoAnalyzeLoop()
        }
    }

    /** 设置分析目标访问数。 */
    fun setAnalysisVisits(visits: Int) {
        _uiState.update { it.copy(targetVisits = visits.coerceAtLeast(1)) }
    }

    /**
     * 导出当前棋谱为 SGF 文本（包含完整 moveHistory）。
     */
    fun exportSgf(): String {
        val fullState = buildStateAt(fullMoveHistory.size)
        val tree = SgfConverter.fromGameState(fullState)
        return SgfSerializer.serialize(tree, pretty = false)
    }

    // ===== 内部实现 =====

    /** 应用加载的完整棋谱，重置查看位置到初始局面。 */
    private fun applyLoadedGame(fullState: GameState) {
        fullMoveHistory = fullState.moveHistory
        boardSize = fullState.boardSize
        komi = fullState.komi
        handicap = fullState.handicap
        _winRateHistory.value = emptyList()
        _analysisResult.value = null
        _uiState.update {
            it.copy(
                currentIndex = 0,
                totalMoves = fullMoveHistory.size,
                lastError = null
            )
        }
        savedStateHandle[KEY_CURRENT_INDEX] = 0
        rebuildGameState()
    }

    /**
     * 从 newGame 开始 replay 前 [index] 步重建 GameState。
     */
    private fun buildStateAt(index: Int): GameState {
        var state = GameState.newGame(boardSize, komi, handicap)
        val upper = index.coerceAtLeast(0).coerceAtMost(fullMoveHistory.size)
        for (i in 0 until upper) {
            state = state.applyMove(fullMoveHistory[i])
        }
        return state
    }

    /** 根据 currentIndex 重建当前显示的局面。 */
    private fun rebuildGameState() {
        val idx = _uiState.value.currentIndex
        val state = buildStateAt(idx)
        _uiState.update { it.copy(gameState = state) }
    }

    /**
     * 执行一次完整分析并更新状态。
     *
     * 在分析开始时捕获当前 [AnalysisUiState.currentIndex] 与 gameState，
     * 避免分析过程中用户跳转导致采样点手数与局面错位。
     */
    private suspend fun performAnalysis(maxVisits: Int) {
        // 捕获分析起始快照，避免分析期间导航导致错位
        val snapshot = _uiState.value
        val state = snapshot.gameState
        val toMove = state.toMove
        val moveIndex = snapshot.currentIndex
        _uiState.update {
            it.copy(
                isAnalyzing = true,
                targetVisits = maxVisits,
                analysisProgress = 0f,
                lastError = null
            )
        }
        try {
            val engine = ensureEngine()
            val result = withContext(Dispatchers.Default) {
                engine.analyze(state, toMove, maxVisits, candidates = 10)
            }
            _analysisResult.value = result
            // 同步返回的引擎无法提供中间进度，直接置 1.0
            // 真实流式更新需要引擎回调（如 KataGo 的 lz-analyze 周期输出）
            _uiState.update { it.copy(analysisProgress = 1f) }

            // 胜率视角转换：AnalysisResult.winRate 为行棋方视角，存为黑方视角
            val blackWinRate = if (toMove == Stone.WHITE) 1.0 - result.winRate else result.winRate
            val blackScoreLead = if (toMove == Stone.WHITE) -result.scoreLead else result.scoreLead
            val sample = WinRateSample(
                moveIndex = moveIndex,
                blackWinRate = blackWinRate,
                scoreLead = blackScoreLead
            )
            // 同一 moveIndex 的样本替换，避免重复
            _winRateHistory.update { history ->
                history.filterNot { it.moveIndex == sample.moveIndex } + sample
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(lastError = "分析失败：${e.message}") }
        } finally {
            _uiState.update { it.copy(isAnalyzing = false) }
        }
    }

    /**
     * 自动分析循环：从当前手开始，逐手分析并自动推进，直到棋谱末尾或被取消。
     */
    private fun startAutoAnalyzeLoop() {
        autoAnalyzeJob = viewModelScope.launch {
            try {
                while (_uiState.value.autoAnalyze) {
                    // 已到末尾，停止
                    if (_uiState.value.currentIndex > fullMoveHistory.size) break
                    performAnalysis(_uiState.value.targetVisits)
                    if (!_uiState.value.autoAnalyze) break
                    if (_uiState.value.currentIndex < fullMoveHistory.size) {
                        nextMove()
                    } else {
                        // 完成全部手数分析，自动关闭
                        _uiState.update { it.copy(autoAnalyze = false) }
                        break
                    }
                }
            } catch (e: CancellationException) {
                // 正常取消，无需处理
            }
        }
    }

    /** 确保引擎已启动并返回就绪的 [GoEngine]。 */
    private suspend fun ensureEngine(): com.weiqi.app.engine.GoEngine {
        engineManager.activeEngine?.let { if (it.isReady) return it }
        return engineManager.startEngine()
    }

    /** 从 [themePreferences] 读取初始主题设置。 */
    private fun loadThemeFromPreferences() {
        _themeState.update { current ->
            current.copy(
                boardTheme = themePreferences.boardTheme,
                stoneTheme = themePreferences.stoneTheme,
                showCoordinates = themePreferences.showCoordinates
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        analysisJob?.cancel()
        autoAnalyzeJob?.cancel()
    }

    companion object {
        private const val KEY_CURRENT_INDEX = "analysis_current_index"
    }
}
