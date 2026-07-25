package com.weiqi.app.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.weiqi.app.core.Stone

/**
 * 新对局配置对话框。
 *
 * 允许用户选择：棋盘大小（19/13/9）、贴目、让子数（0-9）、人类执黑/白、AI 强度。
 *
 * @param onConfirm 确认回调，参数依次为 boardSize、komi、handicap、humanColor、aiStrength。
 * @param onDismiss 取消回调。
 */
@Composable
fun NewGameDialog(
    onConfirm: (boardSize: Int, komi: Double, handicap: Int, humanColor: Stone, aiStrength: AiStrength) -> Unit,
    onDismiss: () -> Unit
) {
    // 棋盘大小候选
    val boardSizes = remember { listOf(19, 13, 9) }
    var selectedSize by remember { mutableIntStateOf(19) }

    // 贴目（文本输入，便于 6.5 / 7.5 / 0.5 等任意值）
    var komiText by remember { mutableStateOf("7.5") }

    // 让子数 0-9
    var handicap by remember { mutableIntStateOf(0) }

    // 人类执子颜色
    var humanColor by remember { mutableStateOf(Stone.BLACK) }

    // AI 强度
    var aiStrength by remember { mutableStateOf(AiStrength.NORMAL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新对局") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 棋盘大小
                Text("棋盘大小", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.heightIn(min = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    boardSizes.forEach { size ->
                        FilterChip(
                            selected = selectedSize == size,
                            onClick = { selectedSize = size },
                            label = { Text("$size 路") }
                        )
                    }
                }

                Spacer(Modifier.heightIn(min = 12.dp))

                // 贴目
                OutlinedTextField(
                    value = komiText,
                    onValueChange = { komiText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("贴目") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.heightIn(min = 12.dp))

                // 让子数
                Text("让子：$handicap", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (0..4).forEach { n ->
                        FilterChip(
                            selected = handicap == n,
                            onClick = { handicap = n },
                            label = { Text("$n") }
                        )
                    }
                    (5..9).forEach { n ->
                        FilterChip(
                            selected = handicap == n,
                            onClick = { handicap = n },
                            label = { Text("$n") }
                        )
                    }
                }

                Spacer(Modifier.heightIn(min = 12.dp))

                // 执子颜色
                Text("执子", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(Stone.BLACK to "执黑（先手）", Stone.WHITE to "执白（后手）").forEach { (color, label) ->
                        Row(
                            modifier = Modifier
                                .selectable(
                                    selected = humanColor == color,
                                    onClick = { humanColor = color },
                                    role = Role.RadioButton
                                )
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = humanColor == color,
                                onClick = { humanColor = color }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(label)
                        }
                    }
                }

                Spacer(Modifier.heightIn(min = 8.dp))

                // AI 强度
                Text("AI 强度", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.heightIn(min = 4.dp))
                AiStrength.values().forEach { strength ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = aiStrength == strength,
                                onClick = { aiStrength = strength },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = aiStrength == strength,
                            onClick = { aiStrength = strength }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("${strength.displayName}（${strength.label}）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val komi = komiText.toDoubleOrNull() ?: 7.5
                    onConfirm(selectedSize, komi, handicap, humanColor, aiStrength)
                }
            ) {
                Text("开始")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
