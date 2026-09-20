package me.rerere.rikkahub.ui.pages.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.PromptItem
import me.rerere.rikkahub.data.model.PromptPosition
import me.rerere.rikkahub.data.model.PromptPreset
import me.rerere.rikkahub.data.model.parsePromptPresets
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.File01

/**
 * 预设库 Tab —— 全局共享，和世界书一个层级。
 *
 * 设计要点（对齐酒馆的概念边界）：
 * - **预设是全局的**，不是角色卡的一部分。导入一次，所有助手的预设选择器里都能看到
 * - 助手侧只存一个引用（`Assistant.presetId`），所以「打开预设」是每个助手各自的事，
 *   「导入预设」全局只做一次
 * - 这里管的是库本身：导入 / 查看 / 删除 / 逐条开关提示词与正则
 */
@Composable
fun TavernPresetTab(
    settings: Settings,
    onSettingsUpdate: (Settings) -> Unit,
) {
    val context = LocalContext.current
    val presets = settings.promptPresets
    var importing by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        val parsed = runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val fallbackName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() } ?: "Imported"
            parsePromptPresets(text, fallbackName)
        }.getOrDefault(emptyList())
        importing = false
        if (parsed.isEmpty()) return@rememberLauncherForActivityResult
        onSettingsUpdate(settings.copy(promptPresets = settings.promptPresets + parsed))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "预设是全局的：这里导入一次，所有助手的「酒馆模式」里都能选到。" +
                    "数据层只有一个库（prompt_presets），助手侧存的只是引用，不会各存一份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json")) },
                enabled = !importing,
            ) {
                Icon(HugeIcons.File01, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(if (importing) "导入中…" else "导入预设 JSON")
            }
        }

        if (presets.isEmpty()) {
            item {
                Text(
                    "库里还没有预设。可以从酒馆导出预设（OpenAI Settings 目录下的 json）后在这里导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(presets, key = { it.id.toString() }) { preset ->
            PresetCard(
                preset = preset,
                expanded = expandedId == preset.id.toString(),
                onToggleExpand = {
                    val key = preset.id.toString()
                    expandedId = if (expandedId == key) null else key
                },
                onUpdate = { updated ->
                    onSettingsUpdate(
                        settings.copy(
                            promptPresets = settings.promptPresets.map {
                                if (it.id == updated.id) updated else it
                            }
                        )
                    )
                },
                onDelete = {
                    onSettingsUpdate(
                        settings.copy(promptPresets = settings.promptPresets.filter { it.id != preset.id })
                    )
                },
            )
        }
    }
}

@Composable
private fun PresetCard(
    preset: PromptPreset,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (PromptPreset) -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onToggleExpand),
                ) {
                    Text(
                        preset.name.ifBlank { "(未命名)" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(preset.prompts.count { it.enabled }).append("/")
                            append(preset.prompts.size).append(" 条提示词")
                            append(" · 正则 ").append(preset.regexScripts.count { it.enabled })
                            append("/").append(preset.regexScripts.size)
                            if (preset.tavernHelperScripts > 0) {
                                append(" · 助手脚本 ").append(preset.tavernHelperScripts)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(HugeIcons.Delete02, contentDescription = "删除预设")
                }
            }

            if (preset.tavernHelperScripts > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "含 ${preset.tavernHelperScripts} 个酒馆助手（TavernHelper）脚本。" +
                        "本版不执行卡内 JS，依赖脚本的逻辑（变量更新、楼层渲染等）会缺失。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (!expanded) return@Column

            Spacer(Modifier.height(8.dp))
            Text(
                "顺序即骨架顺序，点一行切换启用（FIXED = 按深度插入对话内部）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            preset.prompts.forEachIndexed { index, item ->
                PromptRow(
                    item = item,
                    onClick = {
                        onUpdate(
                            preset.copy(
                                prompts = preset.prompts.mapIndexed { j, p ->
                                    if (j == index) p.copy(enabled = !p.enabled) else p
                                }
                            )
                        )
                    },
                )
            }

            if (preset.regexScripts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "预设自带正则（点一行切换启用）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preset.regexScripts.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUpdate(
                                    preset.copy(
                                        regexScripts = preset.regexScripts.map {
                                            if (it.id == rule.id) it.copy(enabled = !it.enabled) else it
                                        }
                                    )
                                )
                            }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (rule.enabled) "●" else "○",
                            color = if (rule.enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                rule.name.ifBlank { rule.externalId.ifBlank { "(未命名正则)" } },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append(rule.effectiveTargets.joinToString(",") { it.name.lowercase() })
                                    append(" · ").append(rule.effectiveViews.joinToString(",") { it.name.lowercase() })
                                    if (rule.trimRegex.isNotEmpty()) append(" · trim${rule.trimRegex.size}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptRow(
    item: PromptItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (item.enabled) "●" else "○",
            color = if (item.enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.name.ifBlank { item.identifier },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(item.identifier)
                    append(" · ").append(item.role.name.lowercase())
                    if (item.position == PromptPosition.FIXED) {
                        append(" · FIXED depth=").append(item.depth)
                            .append(" order=").append(item.order)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
