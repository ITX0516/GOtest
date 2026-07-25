package com.weiqi.app.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weiqi.app.core.Vertex
import com.weiqi.app.ui.board.BoardView

/**
 * 分析模式主界面。
 *
 * 横屏：左侧棋盘 + 右上胜率图 + 右中候选手列表 + 右下导航
 * 竖屏：上方棋盘 + 中间胜率图（高度固定 120dp）+ 下方候选手横向列表 + 底部导航
 *
 * @param viewModel 分析模式 ViewModel。
 * @param onBack 返回回调。
 * @param onOpenSettings 打开设置回调。
 * @param modifier 修饰符。
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val themeState by viewModel.themeState.collectAsState()
    val analysisResult by viewModel.analysisResult.collectAsState()
    val winRateHistory by viewModel.winRateHistory.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    // 当前预览的 PV 着手序列；非空时显示 PvPreviewDialog
    var pvPreview by remember { mutableStateOf<List<com.weiqi.app.core.Move>?>(null) }

    // 错误/提示信息以 Snackbar 展示
    LaunchedEffect(uiState.lastError) {
        uiState.lastError?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("复盘分析", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLandscape) {
                LandscapeAnalysisContent(
                    uiState = uiState,
                    themeState = themeState,
                    analysisResult = analysisResult,
                    winRateHistory = winRateHistory,
                    onAnalyze = { viewModel.startAnalysis(uiState.targetVisits) },
                    onStop = { viewModel.stopAnalysis() },
                    onToggleAuto = { viewModel.toggleAutoAnalyze() },
                    onVisitsChange = { viewModel.setAnalysisVisits(it) },
                    onVertexClick = { viewModel.analyzeAt(it) },
                    onCandidateClick = { vertex ->
                        // 点击候选项：打开该候选项的 PV 预览
                        val candidate = analysisResult?.candidates?.find { it.vertex == vertex }
                        pvPreview = candidate?.pv
                    },
                    onNavigatorFirst = { viewModel.firstMove() },
                    onNavigatorPrev = { viewModel.prevMove() },
                    onNavigatorNext = { viewModel.nextMove() },
                    onNavigatorLast = { viewModel.lastMove() },
                    onNavigatorJumpTo = { viewModel.jumpToMove(it) },
                    onWinRateJumpTo = { viewModel.jumpToMove(it) }
                )
            } else {
                PortraitAnalysisContent(
                    uiState = uiState,
                    themeState = themeState,
                    analysisResult = analysisResult,
                    winRateHistory = winRateHistory,
                    onAnalyze = { viewModel.startAnalysis(uiState.targetVisits) },
                    onStop = { viewModel.stopAnalysis() },
                    onToggleAuto = { viewModel.toggleAutoAnalyze() },
                    onVisitsChange = { viewModel.setAnalysisVisits(it) },
                    onVertexClick = { viewModel.analyzeAt(it) },
                    onCandidateClick = { vertex ->
                        val candidate = analysisResult?.candidates?.find { it.vertex == vertex }
                        pvPreview = candidate?.pv
                    },
                    onNavigatorFirst = { viewModel.firstMove() },
                    onNavigatorPrev = { viewModel.prevMove() },
                    onNavigatorNext = { viewModel.nextMove() },
                    onNavigatorLast = { viewModel.lastMove() },
                    onNavigatorJumpTo = { viewModel.jumpToMove(it) },
                    onWinRateJumpTo = { viewModel.jumpToMove(it) }
                )
            }
        }
    }

    // PV 预览弹窗
    pvPreview?.let { moves ->
        PvPreviewDialog(
            moves = moves,
            boardSize = uiState.gameState.boardSize,
            onDismiss = { pvPreview = null },
            boardTheme = themeState.boardTheme,
            stoneTheme = themeState.stoneTheme
        )
    }
}

/**
 * 横屏布局：左侧棋盘 + 右侧（上胜率图 / 中候选手 / 下导航）。
 */
@Composable
private fun LandscapeAnalysisContent(
    uiState: AnalysisUiState,
    themeState: AnalysisThemeState,
    analysisResult: com.weiqi.app.engine.AnalysisResult?,
    winRateHistory: List<WinRateSample>,
    onAnalyze: () -> Unit,
    onStop: () -> Unit,
    onToggleAuto: () -> Unit,
    onVisitsChange: (Int) -> Unit,
    onVertexClick: (Vertex) -> Unit,
    onCandidateClick: (Vertex) -> Unit,
    onNavigatorFirst: () -> Unit,
    onNavigatorPrev: () -> Unit,
    onNavigatorNext: () -> Unit,
    onNavigatorLast: () -> Unit,
    onNavigatorJumpTo: (Int) -> Unit,
    onWinRateJumpTo: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 左侧：棋盘 + 操作按钮
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BoardView(
                state = uiState.gameState,
                boardTheme = themeState.boardTheme,
                stoneTheme = themeState.stoneTheme,
                showCoordinates = themeState.showCoordinates,
                showMoveNumber = themeState.showMoveNumber,
                analysisResult = analysisResult,
                onStoneClick = onVertexClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
            AnalysisToolbar(
                uiState = uiState,
                onAnalyze = onAnalyze,
                onStop = onStop,
                onToggleAuto = onToggleAuto,
                onVisitsChange = onVisitsChange
            )
        }

        // 右侧：胜率图 + 候选手 + 导航
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 右上：胜率图
            WinRateChart(
                samples = winRateHistory,
                currentIndex = uiState.currentIndex,
                onJumpTo = onWinRateJumpTo,
                modifier = Modifier.fillMaxWidth()
            )

            // 右中：候选手列表
            Text(
                text = "候选手",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp)
            )
            CandidateMovesPanel(
                candidates = analysisResult?.candidates ?: emptyList(),
                onCandidateClick = onCandidateClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                orientation = PanelOrientation.Vertical
            )

            // 右下：导航
            ReviewNavigator(
                currentIndex = uiState.currentIndex,
                totalMoves = uiState.totalMoves,
                onFirst = onNavigatorFirst,
                onPrev = onNavigatorPrev,
                onNext = onNavigatorNext,
                onLast = onNavigatorLast,
                onJumpTo = onNavigatorJumpTo,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 竖屏布局：上方棋盘 + 中胜率图（120dp）+ 下候选手横向列表 + 底部导航。
 */
@Composable
private fun PortraitAnalysisContent(
    uiState: AnalysisUiState,
    themeState: AnalysisThemeState,
    analysisResult: com.weiqi.app.engine.AnalysisResult?,
    winRateHistory: List<WinRateSample>,
    onAnalyze: () -> Unit,
    onStop: () -> Unit,
    onToggleAuto: () -> Unit,
    onVisitsChange: (Int) -> Unit,
    onVertexClick: (Vertex) -> Unit,
    onCandidateClick: (Vertex) -> Unit,
    onNavigatorFirst: () -> Unit,
    onNavigatorPrev: () -> Unit,
    onNavigatorNext: () -> Unit,
    onNavigatorLast: () -> Unit,
    onNavigatorJumpTo: (Int) -> Unit,
    onWinRateJumpTo: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 上方：棋盘
        BoardView(
            state = uiState.gameState,
            boardTheme = themeState.boardTheme,
            stoneTheme = themeState.stoneTheme,
            showCoordinates = themeState.showCoordinates,
            showMoveNumber = themeState.showMoveNumber,
            analysisResult = analysisResult,
            onStoneClick = onVertexClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )

        // 操作按钮
        AnalysisToolbar(
            uiState = uiState,
            onAnalyze = onAnalyze,
            onStop = onStop,
            onToggleAuto = onToggleAuto,
            onVisitsChange = onVisitsChange
        )

        // 中间：胜率图（高度由 WinRateChart 内部固定 120dp）
        WinRateChart(
            samples = winRateHistory,
            currentIndex = uiState.currentIndex,
            onJumpTo = onWinRateJumpTo,
            modifier = Modifier.fillMaxWidth()
        )

        // 下方：候选手横向列表
        Text(
            text = "候选手",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 4.dp)
        )
        CandidateMovesPanel(
            candidates = analysisResult?.candidates ?: emptyList(),
            onCandidateClick = onCandidateClick,
            modifier = Modifier.fillMaxWidth(),
            orientation = PanelOrientation.Horizontal
        )

        // 底部：导航
        ReviewNavigator(
            currentIndex = uiState.currentIndex,
            totalMoves = uiState.totalMoves,
            onFirst = onNavigatorFirst,
            onPrev = onNavigatorPrev,
            onNext = onNavigatorNext,
            onLast = onNavigatorLast,
            onJumpTo = onNavigatorJumpTo,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 分析操作工具条：分析 / 停止 / 自动分析 + 进度条 + 访问数选择。
 */
@Composable
private fun AnalysisToolbar(
    uiState: AnalysisUiState,
    onAnalyze: () -> Unit,
    onStop: () -> Unit,
    onToggleAuto: () -> Unit,
    onVisitsChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 分析按钮
            IconButton(
                onClick = onAnalyze,
                enabled = !uiState.isAnalyzing
            ) {
                Icon(
                    imageVector = Icons.Default.Analytics,
                    contentDescription = "分析当前局面"
                )
            }

            // 停止按钮
            IconButton(
                onClick = onStop,
                enabled = uiState.isAnalyzing || uiState.autoAnalyze
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "停止分析"
                )
            }

            // 自动分析切换
            FilterChip(
                selected = uiState.autoAnalyze,
                onClick = onToggleAuto,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null
                    )
                },
                label = { Text(if (uiState.autoAnalyze) "自动分析中" else "自动分析") }
            )

            Spacer(modifier = Modifier.weight(1f))

            // 访问数档位选择
            VisitSelector(
                currentVisits = uiState.targetVisits,
                enabled = !uiState.isAnalyzing,
                onVisitsChange = onVisitsChange
            )
        }

        // 进度条
        if (uiState.isAnalyzing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .height(16.dp)
                        .width(16.dp),
                    strokeWidth = 2.dp
                )
                LinearProgressIndicator(
                    progress = { uiState.analysisProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            }
        }
    }
}

/**
 * 分析访问数档位选择。
 *
 * @param currentVisits 当前目标访问数。
 * @param enabled 是否可点击。
 * @param onVisitsChange 切换档位回调，参数为新访问数。
 */
@Composable
private fun VisitSelector(
    currentVisits: Int,
    enabled: Boolean,
    onVisitsChange: (Int) -> Unit
) {
    val options = remember { listOf(200, 400, 800, 1600) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        options.forEach { visits ->
            FilterChip(
                selected = currentVisits == visits,
                onClick = { onVisitsChange(visits) },
                enabled = enabled,
                label = { Text(visits.toString()) },
                modifier = Modifier.padding(end = 4.dp)
            )
        }
    }
}
