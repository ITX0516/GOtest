package com.weiqi.app.ui.play

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.weiqi.app.core.Vertex
import com.weiqi.app.engine.EngineType
import com.weiqi.app.ui.board.BoardView
import com.weiqi.app.ui.board.StoneInputMode

/**
 * 对弈模式主界面。
 *
 * 根据屏幕方向自适应布局：
 * - 横屏：左侧棋盘 + 右侧侧栏（引擎状态 / 控制条 / 走子列表）
 * - 竖屏：上方棋盘 + 下方控制条 + 走子列表 + 引擎状态
 *
 * 三种输入模式（[StoneInputMode]）的交互在此编排：
 * - [StoneInputMode.SINGLE_TAP]：点击直接落子
 * - [StoneInputMode.DOUBLE_TAP]：首次点击设为待确认（半透明预览），再次点击同点落子；点击别处切换
 * - [StoneInputMode.CONFIRM_BUTTON]：点击设为待确认，由 [BoardView] 的确认按钮触发落子
 *
 * @param viewModel 对弈 ViewModel。
 * @param onOpenSettings 打开设置回调。
 * @param onOpenAnalysis 进入分析回调，参数为当前对局 SGF。
 * @param onImportSgf 导入 SGF 回调。
 * @param modifier 修饰符。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    viewModel: PlayViewModel,
    onOpenSettings: () -> Unit,
    onOpenAnalysis: (sgf: String) -> Unit,
    onImportSgf: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState
    val themeState by viewModel.themeState
    val engineStatus by viewModel.engineStatus

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    // 对话框状态
    var showNewGameDialog by remember { mutableStateOf(false) }
    var showEngineSwitchDialog by remember { mutableStateOf(false) }
    var showSgfDialog by remember { mutableStateOf(false) }
    var sgfText by remember { mutableStateOf("") }

    // 输入模式待确认落子点（由本 Screen 管理，传给 BoardView 渲染预览）
    var pendingVertex by remember { mutableStateOf<Vertex?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // 错误提示
    LaunchedEffect(uiState.lastError) {
        uiState.lastError?.let { snackbarHostState.showSnackbar(it) }
    }
    // 终局/认输提示
    LaunchedEffect(uiState.gameOverMessage) {
        uiState.gameOverMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    // 输入模式 → 点击处理
    // 注意：BoardView 内部已按 inputMode 区分回调时机：
    //  - SINGLE_TAP：单击即回调 onStoneClick
    //  - DOUBLE_TAP：仅在双击确认时回调 onStoneClick（首次单击的预览由 BoardView 内部维护）
    //  - CONFIRM_BUTTON：单击回调 onStoneClick 设置待确认点，由右下角"确认"按钮触发 onConfirmMove
    val inputMode = themeState.inputMode
    val onStoneClick: (Vertex) -> Unit = { vertex ->
        when (inputMode) {
            StoneInputMode.SINGLE_TAP -> {
                // 单击直接落子
                viewModel.onHumanMove(vertex)
                pendingVertex = null
            }
            StoneInputMode.DOUBLE_TAP -> {
                // BoardView 仅在双击确认时回调，直接落子
                viewModel.onHumanMove(vertex)
                pendingVertex = null
            }
            StoneInputMode.CONFIRM_BUTTON -> {
                // 仅设置待确认点，等待右下角确认按钮
                pendingVertex = vertex
            }
        }
    }
    val onConfirmMove: () -> Unit = {
        pendingVertex?.let { v ->
            viewModel.onHumanMove(v)
            pendingVertex = null
        }
    }
    val onCancelPending: () -> Unit = {
        pendingVertex = null
    }

    // 控制条回调
    val onPass = { pendingVertex = null; viewModel.pass() }
    val onUndo = { pendingVertex = null; viewModel.undo() }
    val onResign = { pendingVertex = null; viewModel.resign() }
    val onNewGame = { pendingVertex = null; showNewGameDialog = true }
    val onSaveSgf = {
        sgfText = viewModel.saveToSgf()
        showSgfDialog = true
    }
    val onAnalyze = {
        onOpenAnalysis(viewModel.saveToSgf())
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("对弈") },
                actions = {
                    IconButton(onClick = onImportSgf) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = "导入 SGF")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLandscape) {
                LandscapeLayout(
                    viewModel = viewModel,
                    uiState = uiState,
                    themeState = themeState,
                    engineStatus = engineStatus,
                    pendingVertex = pendingVertex,
                    onStoneClick = onStoneClick,
                    onConfirmMove = onConfirmMove,
                    onCancelPending = onCancelPending,
                    onPass = onPass,
                    onUndo = onUndo,
                    onResign = onResign,
                    onNewGame = onNewGame,
                    onSaveSgf = onSaveSgf,
                    onAnalyze = onAnalyze,
                    onSwitchEngine = { showEngineSwitchDialog = true }
                )
            } else {
                PortraitLayout(
                    viewModel = viewModel,
                    uiState = uiState,
                    themeState = themeState,
                    engineStatus = engineStatus,
                    pendingVertex = pendingVertex,
                    onStoneClick = onStoneClick,
                    onConfirmMove = onConfirmMove,
                    onCancelPending = onCancelPending,
                    onPass = onPass,
                    onUndo = onUndo,
                    onResign = onResign,
                    onNewGame = onNewGame,
                    onSaveSgf = onSaveSgf,
                    onAnalyze = onAnalyze,
                    onSwitchEngine = { showEngineSwitchDialog = true }
                )
            }

            // AI 思考全屏遮罩加载圈
            if (uiState.aiThinking) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopEnd
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    // 新对局对话框
    if (showNewGameDialog) {
        NewGameDialog(
            onConfirm = { boardSize, komi, handicap, humanColor, aiStrength ->
                viewModel.newGame(boardSize, komi, handicap, humanColor, aiStrength)
                pendingVertex = null
                showNewGameDialog = false
            },
            onDismiss = { showNewGameDialog = false }
        )
    }

    // 引擎切换对话框
    if (showEngineSwitchDialog) {
        EngineSwitchDialog(
            currentType = engineStatus.engineType,
            onSelect = { type ->
                viewModel.switchEngine(type)
                showEngineSwitchDialog = false
            },
            onDismiss = { showEngineSwitchDialog = false }
        )
    }

    // SGF 导出对话框
    if (showSgfDialog) {
        AlertDialog(
            onDismissRequest = { showSgfDialog = false },
            title = { Text("SGF 棋谱") },
            text = {
                OutlinedTextField(
                    value = sgfText,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 320.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showSgfDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

/**
 * 横屏布局：左侧棋盘 + 右侧侧栏。
 */
@Composable
private fun LandscapeLayout(
    viewModel: PlayViewModel,
    uiState: PlayUiState,
    themeState: PlayThemeState,
    engineStatus: EngineStatusState,
    pendingVertex: Vertex?,
    onStoneClick: (Vertex) -> Unit,
    onConfirmMove: () -> Unit,
    onCancelPending: () -> Unit,
    onPass: () -> Unit,
    onUndo: () -> Unit,
    onResign: () -> Unit,
    onNewGame: () -> Unit,
    onSaveSgf: () -> Unit,
    onAnalyze: () -> Unit,
    onSwitchEngine: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // 左侧棋盘
        BoardView(
            state = uiState.gameState,
            boardTheme = themeState.boardTheme,
            stoneTheme = themeState.stoneTheme,
            inputMode = themeState.inputMode,
            onStoneClick = onStoneClick,
            pendingVertex = pendingVertex,
            onConfirmMove = onConfirmMove,
            onCancelPending = onCancelPending,
            showCoordinates = themeState.showCoordinates,
            showMoveNumber = themeState.showMoveNumber,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        // 右侧侧栏
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
        ) {
            EngineStatusBar(
                status = engineStatus,
                onSwitchEngine = onSwitchEngine
            )
            PlayControlBar(
                aiThinking = uiState.aiThinking,
                onPass = onPass,
                onUndo = onUndo,
                onResign = onResign,
                onNewGame = onNewGame,
                onSaveSgf = onSaveSgf,
                onAnalyze = onAnalyze
            )
            MoveListPanel(
                moves = uiState.gameState.moveHistory,
                currentIndex = uiState.gameState.moveNumber,
                onJumpTo = { index -> viewModel.jumpToMove(index) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 竖屏布局：上方棋盘 + 下方控制条 + 走子列表 + 引擎状态。
 */
@Composable
private fun PortraitLayout(
    viewModel: PlayViewModel,
    uiState: PlayUiState,
    themeState: PlayThemeState,
    engineStatus: EngineStatusState,
    pendingVertex: Vertex?,
    onStoneClick: (Vertex) -> Unit,
    onConfirmMove: () -> Unit,
    onCancelPending: () -> Unit,
    onPass: () -> Unit,
    onUndo: () -> Unit,
    onResign: () -> Unit,
    onNewGame: () -> Unit,
    onSaveSgf: () -> Unit,
    onAnalyze: () -> Unit,
    onSwitchEngine: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 上方棋盘（占据剩余空间）
        BoardView(
            state = uiState.gameState,
            boardTheme = themeState.boardTheme,
            stoneTheme = themeState.stoneTheme,
            inputMode = themeState.inputMode,
            onStoneClick = onStoneClick,
            pendingVertex = pendingVertex,
            onConfirmMove = onConfirmMove,
            onCancelPending = onCancelPending,
            showCoordinates = themeState.showCoordinates,
            showMoveNumber = themeState.showMoveNumber,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        // 控制条
        PlayControlBar(
            aiThinking = uiState.aiThinking,
            onPass = onPass,
            onUndo = onUndo,
            onResign = onResign,
            onNewGame = onNewGame,
            onSaveSgf = onSaveSgf,
            onAnalyze = onAnalyze
        )
        // 走子列表（可滑动，限高）
        MoveListPanel(
            moves = uiState.gameState.moveHistory,
            currentIndex = uiState.gameState.moveNumber,
            onJumpTo = { index -> viewModel.jumpToMove(index) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
        )
        // 引擎状态栏
        EngineStatusBar(
            status = engineStatus,
            onSwitchEngine = onSwitchEngine
        )
    }
}

/**
 * 引擎切换对话框：列出所有引擎类型供选择。
 *
 * @param currentType 当前引擎类型。
 * @param onSelect 选中回调。
 * @param onDismiss 取消回调。
 */
@Composable
private fun EngineSwitchDialog(
    currentType: EngineType?,
    onSelect: (EngineType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换引擎") },
        text = {
            Column {
                EngineType.values().forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { onSelect(type) }) {
                            Text(
                                text = type.displayName + if (type == currentType) "（当前）" else "",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
