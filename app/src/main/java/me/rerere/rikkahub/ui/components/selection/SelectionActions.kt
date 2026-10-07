package me.rerere.rikkahub.ui.components.selection

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SELECTION_EXPLAIN_PROMPT
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceString
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.compose.koinInject
import java.net.URLEncoder
import java.util.Locale

// ── 偏好项（落 SharedPreferences，读写见 ui/hooks/SharedPreferences.kt）──
const val PREF_SELECTION_SEARCH_ENGINE = "selection_menu_search_engine"
const val PREF_SELECTION_EXPLAIN_LANG = "selection_menu_explain_lang"
const val PREF_SELECTION_ENABLE_SEARCH = "selection_menu_enable_search"
const val PREF_SELECTION_ENABLE_EXPLAIN = "selection_menu_enable_explain"

/** 解释用的输出语言 */
const val EXPLAIN_LANG_ZH = "zh"
const val EXPLAIN_LANG_EN = "en"

/** 选中菜单里「搜索」用的引擎，默认 Google */
enum class SelectionSearchEngine(val id: String, val displayName: String) {
    GOOGLE("google", "Google"),
    BING("bing", "Bing"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo"),
    BAIDU("baidu", "百度");

    fun buildUrl(query: String): String {
        val q = URLEncoder.encode(query, "UTF-8")
        return when (this) {
            GOOGLE -> "https://www.google.com/search?q=$q"
            BING -> "https://www.bing.com/search?q=$q"
            DUCKDUCKGO -> "https://duckduckgo.com/?q=$q"
            BAIDU -> "https://www.baidu.com/s?wd=$q"
        }
    }

    companion object {
        fun fromId(id: String?): SelectionSearchEngine =
            SelectionSearchEngine.values().firstOrNull { it.id == id } ?: GOOGLE
    }
}

private data object SelectionSearchItemKey
private data object SelectionExplainItemKey

/**
 * 把消息正文包成「可选中 + 带自定义选中菜单」的容器。
 *
 * - 选中文字后弹出的上下文菜单里多出「搜索 / 解释」两项（复制、全选仍是 Compose 自带的那套）
 * - 点「解释」→ 就地弹一张小卡片，流式显示释义（含音标 / 词性 / 例句）
 *
 * ⚠️ appendTextContextMenuComponents 必须挂在 SelectionContainer 的祖先上
 * （toolbar handler 是从容器内部往上遍历收集菜单项的），所以这里多包了一层 Box。
 */
@Composable
fun SelectableMessageText(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val generationHandler: GenerationHandler = koinInject()

    val selectionState = rememberSelectionState()

    val searchEngineId by rememberSharedPreferenceString(
        PREF_SELECTION_SEARCH_ENGINE,
        SelectionSearchEngine.GOOGLE.id,
    )
    val explainLang by rememberSharedPreferenceString(PREF_SELECTION_EXPLAIN_LANG, EXPLAIN_LANG_ZH)
    val searchEnabled by rememberSharedPreferenceBoolean(PREF_SELECTION_ENABLE_SEARCH, true)
    val explainEnabled by rememberSharedPreferenceBoolean(PREF_SELECTION_ENABLE_EXPLAIN, true)

    val searchLabel = stringResource(R.string.selection_menu_search)
    val explainLabel = stringResource(R.string.selection_menu_explain)

    // 解释卡片：source 非空即显示，结果流式写进 explainText
    var explainSource by remember { mutableStateOf<String?>(null) }
    var explainText by remember { mutableStateOf("") }
    var explainError by remember { mutableStateOf<String?>(null) }
    var explainLoading by remember { mutableStateOf(false) }

    LaunchedEffect(explainSource) {
        val source = explainSource ?: return@LaunchedEffect
        explainText = ""
        explainError = null
        explainLoading = true

        val targetLocale =
            if (explainLang == EXPLAIN_LANG_EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
        val prompt = settings.selectionExplainPrompt
            .ifBlank { DEFAULT_SELECTION_EXPLAIN_PROMPT }
            .applyPlaceholders(
            "source_text" to source,
            "target_lang" to targetLocale.getDisplayLanguage(Locale.ENGLISH),
        )
        // 解释模型：优先用已配置的翻译模型，没配就退回当前聊天模型
        val modelId = settings.translateModeId
            .takeIf { settings.providers.findModelById(it) != null }
            ?: settings.chatModelId

        runCatching {
            generationHandler.streamPromptText(
                settings = settings,
                modelId = modelId,
                prompt = prompt,
                reasoningBudget = settings.translateThinkingBudget,
                onStreamUpdate = { explainText = it },
            ).collect { explainText = it }
        }.onFailure { error ->
            explainError = error.message ?: error.toString()
        }
        explainLoading = false
    }

    val density = LocalDensity.current
    val cardPositionProvider = remember(density) {
        SelectionCardPositionProvider(
            gap = with(density) { 8.dp.roundToPx() },
            margin = with(density) { 12.dp.roundToPx() },
        )
    }

    Box(
        modifier = modifier
            // 只留「复制 / 全选」+ 我们这两项。
            // 系统会往这个菜单里塞一堆东西：PROCESS_TEXT（朗读 / 浏览器搜索 / 笔记 / AnkiDroid …）
            // 加上智能选择项，一共七八项；浮动工具条塞不下就把**末尾**的挤进「⋮」溢出菜单，
            // 而我们的项永远排在最后（builder 是从下往上收集的）→ 不清场就永远看不见。
            .filterTextContextMenuComponents { component ->
                when (component.key) {
                    TextContextMenuKeys.CopyKey,
                    TextContextMenuKeys.CutKey,
                    TextContextMenuKeys.PasteKey,
                    TextContextMenuKeys.SelectAllKey,
                    TextContextMenuKeys.AutofillKey,
                    SelectionSearchItemKey,
                    SelectionExplainItemKey -> true

                    else -> false
                }
            }
            .appendTextContextMenuComponents {
            // 只有「本容器自己选中了文字」才挂这两项：
            // 嵌套的 SelectionContainer（代码块 / 思维链）用的是它们自己的 state，这里读不到 →
            // 不显示；那两处各自包了一层 SelectableMessageText，由它们自己出菜单项。
            fun currentSelection(): String =
                selectionState.selectedTexts.joinToString("\n") { it.text }.trim()

            Log.d(
                "SelMenu",
                "builder: n=${selectionState.selectedTexts.size} text=[${currentSelection().take(40)}]"
            )

            if (currentSelection().isNotEmpty()) {
                if (searchEnabled) {
                    item(key = SelectionSearchItemKey, label = searchLabel) {
                        // 点的时候再读一次：菜单是快照式构建的，选中态有可能在它之后才落定
                        val text = currentSelection()
                        Log.d("SelMenu", "click search: [${text.take(40)}]")
                        close()
                        if (text.isNotEmpty()) {
                            context.openUrl(
                                SelectionSearchEngine.fromId(searchEngineId).buildUrl(text)
                            )
                        }
                    }
                }
                if (explainEnabled) {
                    item(key = SelectionExplainItemKey, label = explainLabel) {
                        val text = currentSelection()
                        Log.d("SelMenu", "click explain: [${text.take(40)}]")
                        close()
                        if (text.isNotEmpty()) explainSource = text
                    }
                }
            }
        }
    ) {
        SelectionContainer(state = selectionState) {
            content()
        }

        val source = explainSource
        if (source != null) {
            Popup(
                popupPositionProvider = cardPositionProvider,
                onDismissRequest = { explainSource = null },
                properties = PopupProperties(focusable = true),
            ) {
                SelectionExplainCard(
                    source = source,
                    result = explainText,
                    error = explainError,
                    loading = explainLoading,
                    onDismiss = { explainSource = null },
                )
            }
        }
    }
}

@Composable
private fun SelectionExplainCard(
    source: String,
    result: String,
    error: String?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val placeholder = if (loading) {
        stringResource(R.string.selection_menu_explain_loading)
    } else {
        stringResource(R.string.selection_menu_explain_empty)
    }

    Surface(
        modifier = Modifier.widthIn(max = 320.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = source.replace('\n', ' '),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
                IconButton(
                    onClick = { context.writeClipboardText(result) },
                    modifier = Modifier.size(28.dp),
                    enabled = result.isNotBlank(),
                ) {
                    Icon(HugeIcons.Copy01, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    error != null -> Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    result.isBlank() -> Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 解释结果本来就是 Markdown（prompt 里写明 Plain Markdown），
                    // 用应用自己的渲染器：加粗 / 列表 / 代码块 / 引用色都跟主题走
                    else -> MarkdownBlock(
                        content = result,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * 解释卡片贴在消息正文下面；下面放不下就翻到上面，横向夹在窗口里。
 * anchorBounds 是「消息正文 Box」在窗口里的位置，返回的也是窗口坐标。
 */
private class SelectionCardPositionProvider(
    private val gap: Int,
    private val margin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = (anchorBounds.left + margin).coerceIn(margin, maxX)
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height - margin) {
            below
        } else {
            (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(margin)
        }
        return IntOffset(x, y)
    }
}
