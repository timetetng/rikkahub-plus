package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse

@Composable
fun MathInline(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val proceededLatex = latex
    LatexText(
        latex = proceededLatex,
        color = LocalContentColor.current,
        fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
        modifier = modifier.then(latexCopyModifier(LocalContext.current, latexCopySource(proceededLatex))),
    )
}

@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val proceededLatex = latex
    Box(
        modifier = modifier.padding(8.dp)
    ) {
        LatexText(
            latex = proceededLatex,
            color = LocalContentColor.current,
            fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
            modifier = Modifier
                .align(Alignment.Center)
                // 长按复制放在外层、横向滚动放内层：手势按 Main pass 由内向外派发，
                // 里面的滚动先拿到按压事件；detectTapGestures 会消费 down，
                // 反过来放就会抢掉横向拖动，导致超宽公式滑不动。
                .then(latexCopyModifier(LocalContext.current, latexCopySource(proceededLatex, display = true)))
                .horizontalScroll(
                    rememberScrollState()
                ),
        )
    }
}
