package com.weiqi.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.weiqi.app.engine.EngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自定义权重选择卡片。
 *
 * 点击按钮打开系统文件选择器（SAF, ACTION_OPEN_DOCUMENT），
 * 用户选中任意后缀匹配的权重文件后自动复制到应用外部存储目录。
 *
 * 识别后缀：
 *  - KataGo    → *.bin.gz
 *  - LeelaZero → *.txt.gz
 *
 * 复制后通过 [onPicked] 回调通知调用方；调用方通常需要重启引擎以应用新权重。
 */
@Composable
fun WeightsFilePickerCard(
    engineType: EngineType,
    weightsDirProvider: () -> File?,
    onPicked: (file: File) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lastImported by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    // 系统文件选择器（SAF）
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        // 1. 尝试持久化 URI 权限（部分 provider 不支持，失败可忽略）
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val targetDir = weightsDirProvider()
        if (targetDir == null) {
            lastError = "无法获取权重目录"
            return@rememberLauncherForActivityResult
        }
        if (!targetDir.exists()) targetDir.mkdirs()

        scope.launch {
            val result = copyWeightsFromUri(context, uri, engineType, targetDir)
            result.onSuccess { file ->
                lastImported = "${file.name}  (${formatSize(file.length())})"
                lastError = null
                onPicked(file)
            }.onFailure { e ->
                lastError = "导入失败: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    val expectedSuffix = when (engineType) {
        EngineType.KATAGO -> ".bin.gz"
        EngineType.LEELAZERO -> ".txt.gz"
        EngineType.REMOTE -> ""
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("自定义权重", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "从手机存储中选择权重文件。引擎将使用该文件，无需 root。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expectedSuffix.isNotEmpty()) {
                Text(
                    text = "期望后缀：$expectedSuffix（不匹配也能导入，但建议匹配以免后续混淆）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { launcher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                    Text(" 选择权重文件")
                }
            }

            // 当前状态
            lastImported?.let { name ->
                Text(
                    text = "✓ 已导入：$name",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            lastError?.let { err ->
                Text(
                    text = "✗ $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 显示当前目录下的权重文件列表（辅助用户查看）
            val currentFiles = remember(lastImported, lastError) {
                weightsDirProvider()
                    ?.listFiles { f -> f.isFile && f.length() > 1024L }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
            }
            if (currentFiles.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "目录中已有的权重文件：",
                    style = MaterialTheme.typography.labelMedium
                )
                currentFiles.take(5).forEach { f ->
                    Text(
                        text = "  • ${f.name}  (${formatSize(f.length())})",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * 从 SAF URI 复制权重文件到目标目录。
 *
 * - 文件名优先使用 URI 中的原始文件名（去掉路径分隔符）
 * - 如果目标目录中已存在同名文件，会被覆盖
 * - 复制前会校验大小（至少 1KB）
 *
 * @return 复制成功后的目标文件，失败时抛出异常。
 */
suspend fun copyWeightsFromUri(
    context: Context,
    uri: Uri,
    engineType: EngineType,
    targetDir: File
): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
        val rawName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "weights_${System.currentTimeMillis()}.bin.gz"
        // 清理文件名（移除不安全字符）
        val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val finalName = if (safeName.isBlank()) "weights_${System.currentTimeMillis()}.bin.gz" else safeName
        val target = File(targetDir, finalName)

        val bytesCopied = context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法打开 URI 输入流")

        if (target.length() < 1024L) {
            target.delete()
            throw IllegalStateException("文件太小（< 1KB），可能不是有效的权重文件")
        }

        // 静音未使用变量警告
        @Suppress("UNUSED_VARIABLE")
        val type = engineType
        target
    }
}

/** 格式化文件大小。 */
private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
