package com.weiqi.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weiqi.app.engine.EngineConfig
import com.weiqi.app.engine.EngineType
import com.weiqi.app.ui.theme.BoardTheme
import com.weiqi.app.ui.theme.StoneTheme

/**
 * 设置界面：引擎选择 / 各引擎参数 / 远程算力 / 主题 / 音效。
 *
 * 竖屏单列滚动；横屏布局同样适用（未做强制分栏，可后续增强）。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.settingsState.collectAsStateWithLifecycle()
    val supported by viewModel.deviceSupported.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 供子组件使用的 onWeightsPicked 回调：弹出 “已导入” 提示
    val onWeightsPickedLocal: (java.io.File) -> Unit = { file ->
        viewModel.showInfo("已导入权重：${file.name}，重启引擎后生效")
    }

    LaunchedEffect(state.lastError) {
        state.lastError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInfo()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EngineSection(
                current = state.currentEngine,
                supported = supported,
                onSelect = viewModel::setCurrentEngine,
                engineRunning = state.engineRunning,
                onRestart = viewModel::restartEngine,
                onStop = viewModel::stopEngine
            )

            when (state.currentEngine) {
                EngineType.KATAGO -> KataGoConfigSection(
                    config = state.katagoConfig,
                    onUpdate = viewModel::updateKataGoConfig,
                    weightsDirProvider = viewModel::getWeightsDir,
                    onWeightsPicked = onWeightsPickedLocal
                )
                EngineType.LEELAZERO -> LeelaConfigSection(
                    config = state.leelaConfig,
                    onUpdate = viewModel::updateLeelaConfig,
                    weightsDirProvider = viewModel::getWeightsDir,
                    onWeightsPicked = onWeightsPickedLocal
                )
                EngineType.REMOTE -> RemoteConfigSection(
                    config = state.remoteConfig,
                    onUpdate = viewModel::updateRemoteConfig
                )
            }

            HorizontalDivider()

            ThemeSection(
                boardTheme = state.boardTheme,
                stoneTheme = state.stoneTheme,
                onSelectBoard = viewModel::setBoardTheme,
                onSelectStone = viewModel::setStoneTheme
            )

            HorizontalDivider()

            SoundSection(
                enabled = state.soundEnabled,
                onToggle = viewModel::setSoundEnabled
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EngineSection(
    current: EngineType,
    supported: Map<EngineType, Boolean>,
    onSelect: (EngineType) -> Unit,
    engineRunning: Boolean,
    onRestart: () -> Unit,
    onStop: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("引擎", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EngineType.values().forEach { type ->
                    FilterChip(
                        selected = current == type,
                        onClick = { if (supported[type] != false) onSelect(type) },
                        label = {
                            val tag = if (supported[type] == false) "${type.displayName}（不支持）" else type.displayName
                            Text(tag)
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (engineRunning) "● 运行中" else "○ 未运行",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically)
                )
                Button(onClick = onRestart) { Text("启动/重启") }
                Button(onClick = onStop) { Text("停止") }
            }
        }
    }
}

@Composable
private fun KataGoConfigSection(
    config: EngineConfig,
    onUpdate: (EngineConfig) -> Unit,
    weightsDirProvider: () -> java.io.File?,
    onWeightsPicked: (java.io.File) -> Unit
) {
    ConfigCard("KataGo 参数") {
        NumberField(
            label = "线程数",
            value = config.threads,
            onChange = { onUpdate(config.copy(threads = it)) }
        )
        NumberField(
            label = "最大访问数（visits，越大越强）",
            value = config.maxVisits,
            onChange = { onUpdate(config.copy(maxVisits = it)) }
        )
        SwitchRow(
            label = "仅 CPU 模式（无 GPU 时）",
            checked = config.cpuOnly,
            onChange = { onUpdate(config.copy(cpuOnly = it)) }
        )
        SwitchRow(
            label = "后台思考（ponder）",
            checked = config.enablePonder,
            onChange = { onUpdate(config.copy(enablePonder = it)) }
        )
        Spacer(Modifier.height(8.dp))
        WeightsFilePickerCard(
            engineType = EngineType.KATAGO,
            weightsDirProvider = weightsDirProvider,
            onPicked = { file -> onWeightsPicked(file) }
        )
    }
}

@Composable
private fun LeelaConfigSection(
    config: EngineConfig,
    onUpdate: (EngineConfig) -> Unit,
    weightsDirProvider: () -> java.io.File?,
    onWeightsPicked: (java.io.File) -> Unit
) {
    ConfigCard("LeelaZero 参数") {
        NumberField(
            label = "线程数",
            value = config.threads,
            onChange = { onUpdate(config.copy(threads = it)) }
        )
        NumberField(
            label = "最大访问数",
            value = config.maxVisits,
            onChange = { onUpdate(config.copy(maxVisits = it)) }
        )
        SwitchRow(
            label = "仅 CPU 模式",
            checked = config.cpuOnly,
            onChange = { onUpdate(config.copy(cpuOnly = it)) }
        )
        Spacer(Modifier.height(8.dp))
        WeightsFilePickerCard(
            engineType = EngineType.LEELAZERO,
            weightsDirProvider = weightsDirProvider,
            onPicked = { file -> onWeightsPicked(file) }
        )
    }
}

@Composable
private fun RemoteConfigSection(
    config: EngineConfig,
    onUpdate: (EngineConfig) -> Unit
) {
    ConfigCard("远程算力配置") {
        Text(
            "支持智星云、算云、个人 PC。KataGo 与 LeelaZero 都需在远端以 GTP 模式启动。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = config.remoteHost,
            onValueChange = { onUpdate(config.copy(remoteHost = it)) },
            label = { Text("主机/IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        NumberField(
            label = "端口",
            value = config.remotePort,
            onChange = { onUpdate(config.copy(remotePort = it)) }
        )
        OutlinedTextField(
            value = config.remotePassword,
            onValueChange = { onUpdate(config.copy(remotePassword = it)) },
            label = { Text("连接密码（可选）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.remotePlatform,
            onValueChange = { onUpdate(config.copy(remotePlatform = it)) },
            label = { Text("平台标识（zhixing / suanyun / custom）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        NumberField(
            label = "最大访问数",
            value = config.maxVisits,
            onChange = { onUpdate(config.copy(maxVisits = it)) }
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThemeSection(
    boardTheme: BoardTheme,
    stoneTheme: StoneTheme,
    onSelectBoard: (BoardTheme) -> Unit,
    onSelectStone: (StoneTheme) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("棋盘主题", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoardTheme.DEFAULTS.forEach { theme ->
                    FilterChip(
                        selected = boardTheme.id == theme.id,
                        onClick = { onSelectBoard(theme) },
                        label = { Text(theme.displayName) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("棋子主题", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoneTheme.DEFAULTS.forEach { theme ->
                    FilterChip(
                        selected = stoneTheme.id == theme.id,
                        onClick = { onSelectStone(theme) },
                        label = { Text(theme.displayName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SoundSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("音效", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ConfigCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { s ->
            s.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number
        ),
        modifier = modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
