package me.rerere.rikkahub.ui.pages.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.convertRegexScriptsFromSillyTavern
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.File01
import kotlin.uuid.Uuid

/**
 * 正则库 Tab —— 全局正则，和世界书一个层级。
 *
 * 对齐酒馆的三层正则模型（见 data/model/RegexLayers.kt）：
 * 这里是**全局层**，执行顺序在所有层之前；预设层来自预设 JSON，
 * 助手/卡内层在助手详情页里管。三层合并后依次套用。
 *
 * 全局层的典型用途：跨助手的思维链隐藏、世界书规则适配、状态栏格式化。
 */
@Composable
fun TavernRegexTab(
    settings: Settings,
    onSettingsUpdate: (Settings) -> Unit,
) {
    val context = LocalContext.current
    val rules = settings.globalRegexes
    var importing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AssistantRegex?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        val parsed = runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            convertRegexScriptsFromSillyTavern(
                runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(text)
                }.getOrNull()
            )
        }.getOrDefault(emptyList())
        importing = false
        if (parsed.isEmpty()) return@rememberLauncherForActivityResult
        onSettingsUpdate(settings.copy(globalRegexes = settings.globalRegexes + parsed))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "全局正则对所有助手和对话生效，执行顺序在预设正则之前。" +
                    "卡内嵌的正则在助手详情页里管；预设自带的在「预设」Tab 里管。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    enabled = !importing,
                ) {
                    Icon(HugeIcons.File01, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(if (importing) "导入中…" else "导入正则 JSON")
                }
                OutlinedButton(onClick = { editing = AssistantRegex(id = Uuid.random()) }) {
                    Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("新建")
                }
            }
        }

        if (rules.isEmpty()) {
            item {
                Text(
                    "还没有全局正则。可以从酒馆导出正则脚本（JSON）后导入，或直接新建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(rules, key = { it.id.toString() }) { rule ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (rule.enabled) "●" else "○",
                        color = if (rule.enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .clickable {
                                onSettingsUpdate(
                                    settings.copy(
                                        globalRegexes = settings.globalRegexes.map {
                                            if (it.id == rule.id) it.copy(enabled = !it.enabled) else it
                                        }
                                    )
                                )
                            },
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { editing = rule },
                    ) {
                        Text(
                            rule.name.ifBlank { rule.externalId.ifBlank { "(未命名正则)" } },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(rule.effectiveTargets.joinToString(",") { it.name.lowercase() })
                                append(" · ").append(rule.effectiveViews.joinToString(",") { it.name.lowercase() })
                                if (rule.macroMode.name != "NONE") append(" · ").append(rule.macroMode.name.lowercase())
                                if (rule.trimRegex.isNotEmpty()) append(" · trim${rule.trimRegex.size}")
                                if (rule.findRegex.isNotBlank()) append("\n").append(rule.findRegex)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        onSettingsUpdate(
                            settings.copy(globalRegexes = settings.globalRegexes.filter { it.id != rule.id })
                        )
                    }) {
                        Icon(HugeIcons.Delete02, contentDescription = "删除")
                    }
                }
            }
        }
    }

    // 编辑弹窗：只暴露最常用的三个字段，其余（targets/view/macroMode/trim/depth）沿用现有值
    val target = editing
    if (target != null) {
        var name by remember(target.id) { mutableStateOf(target.name) }
        var find by remember(target.id) { mutableStateOf(target.findRegex) }
        var replace by remember(target.id) { mutableStateOf(target.replaceString) }

        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑正则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = find,
                        onValueChange = { find = it },
                        label = { Text("查找（findRegex，支持 /pattern/flags）") },
                        minLines = 2,
                    )
                    OutlinedTextField(
                        value = replace,
                        onValueChange = { replace = it },
                        label = { Text("替换（replaceRegex，可用 {{match}} / \$1）") },
                        minLines = 2,
                    )
                    Text(
                        "作用目标与视图：${target.effectiveTargets.joinToString(",") { it.name.lowercase() }}" +
                            " / ${target.effectiveViews.joinToString(",") { it.name.lowercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = target.copy(name = name, findRegex = find, replaceString = replace)
                    val exists = settings.globalRegexes.any { it.id == updated.id }
                    onSettingsUpdate(
                        settings.copy(
                            globalRegexes = if (exists) {
                                settings.globalRegexes.map { if (it.id == updated.id) updated else it }
                            } else {
                                settings.globalRegexes + updated
                            }
                        )
                    )
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }
}
