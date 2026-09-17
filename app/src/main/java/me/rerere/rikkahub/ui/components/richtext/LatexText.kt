package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import android.graphics.Rect
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import me.rerere.rikkahub.utils.writeClipboardText
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathSplitter

fun assumeLatexSize(latex: String, fontSize: Float): Rect {
    return runCatching {
        JLatexMathDrawable.builder(processLatex(latex))
            .textSize(fontSize)
            .padding(0)
            .build()
            .bounds
    }.getOrElse { Rect(0, 0, 0, 0) }
}

@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    val style = style.merge(
        fontSize = fontSize,
        color = color
    )
    val density = LocalDensity.current

    val drawable = remember(latex, fontSize, style) {
        runCatching {
            with(density) {
                getLatexDrawable(
                    latex = processLatex(latex),
                    fontSize = fontSize.toPx(),
                    color = style.color.toArgb(),
                    background = style.background.toArgb()
                )
            }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    if (drawable != null) {
        with(density) {
            Canvas(
                modifier = modifier
                    .size(
                        width = drawable.bounds.width().toDp(),
                        height = drawable.bounds.height().toDp()
                    )
            ) {
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    } else {
        Text(
            text = latex,
            style = style,
            modifier = modifier
        )
    }
}

fun getLatexDrawable(
    latex: String,
    fontSize: Float,
    color: Int,
    background: Int
): JLatexMathDrawable? {
    return runCatching {
        JLatexMathDrawable.builder(processLatex(latex))
            .textSize(fontSize)
            .color(color)
            .background(background)
            .padding(0)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.onFailure {
        it.printStackTrace()
    }.getOrNull()
}

/**
 * 将一条行内公式按顶层运算符水平拆分为多段 Drawable，
 * 以便在文本流中换行，避免单体公式过长被挤出屏幕。
 * 拆分失败时返回空列表，调用方需自行回退。
 */
fun splitLatex(
    latex: String,
    maxWidthPx: Float,
    fontSize: Float,
    color: Int
): List<JLatexMathDrawable> {
    return runCatching {
        JLatexMathSplitter.split(processLatex(latex), maxWidthPx, fontSize, color)
    }.onFailure {
        it.printStackTrace()
    }.getOrElse { emptyList() }
}

/**
 * 行内公式的占位文本（InlineContent 的 alternateText）。
 *
 * 占位文本不是装饰：文本选择 / 复制 / 无障碍朗读拿到的都是它。原先写死 "[Latex]"，
 * 长按选中公式复制只能得到 "[Latex]"。这里直接用 LaTeX 原文，复制即得公式源码。
 * 一条公式被 [splitLatex] 拆成多段时，每段都带完整原文，任意一段被选中都能拿到整条公式。
 */
fun latexPlaceholder(latex: String): String = latex.ifBlank { "[Latex]" }

/**
 * 长按公式就把 LaTeX 原文写进剪贴板。
 *
 * 公式是 Canvas 画出来的，长按命中的是 InlineContent 的占位文本；与其让用户拖选区再复制，
 * 不如长按就地复制。这里不做成 Composable：调用点有的在 buildAnnotatedString 里，拿不到 LocalContext，
 * 所以由调用方把 Context 传进来。
 */
fun latexCopyModifier(context: Context, latex: String): Modifier = Modifier.pointerInput(latex) {
    detectTapGestures(
        onLongPress = {
            context.writeClipboardText(latex)
            Toast.makeText(context, "已复制 LaTeX 公式", Toast.LENGTH_SHORT).show()
        }
    )
}

@Composable
fun LatexDrawable(
    drawable: JLatexMathDrawable,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    with(density) {
        Canvas(
            modifier = modifier.size(
                width = drawable.bounds.width().toDp(),
                height = drawable.bounds.height().toDp()
            )
        ) {
            drawable.draw(drawContext.canvas.nativeCanvas)
        }
    }
}

private val inlineDollarRegex = Regex("""^\$(.*?)\$""", RegexOption.DOT_MATCHES_ALL)
private val displayDollarRegex = Regex("""^\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val inlineParenRegex = Regex("""^\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val displayBracketRegex = Regex("""^\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)

private fun processLatex(latex: String): String {
    val trimmed = latex.trim()
    val inner = when {
        displayDollarRegex.matches(trimmed) ->
            displayDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineDollarRegex.matches(trimmed) ->
            inlineDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        displayBracketRegex.matches(trimmed) ->
            displayBracketRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineParenRegex.matches(trimmed) ->
            inlineParenRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        else -> trimmed
    }
    return rewriteUnsupportedCommands(inner)
}

// JLaTeXMath 只实现了 \textcolor，没有 \color 的声明式写法；就地转换，
// 命名色（red/blue/...）、#RRGGBB、r,g,b 三种写法都能被 \textcolor 吃下。
private val colorDeclarationRegex = Regex("""\\color\s*\{([^{}]+)\}""")

private fun rewriteUnsupportedCommands(latex: String): String =
    colorDeclarationRegex.replace(latex) { "\\textcolor{${it.groupValues[1]}}" }
