package me.rerere.rikkahub.ui.pages.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 平滑面积曲线。
 *
 * 简约风格：无边框、无坐标轴，只有 3 条极淡的水平网格线 + 一条圆头曲线 + 渐隐面积，
 * 全部取 [MaterialTheme] 配色，因此跟随主题（含动态取色）。
 *
 * 交互（[onSelectionChange] 非空时生效）：
 * - 单指拖动靠近某一端手柄 → 移动该端点
 * - 点击 → 把手柄移到更近的一侧
 *
 * @param values 采样值（按时间正序）
 * @param labels x 轴标签，与 [values] 等长
 * @param selection 当前选中的索引区间，null 表示全选
 */
@Composable
fun SmoothAreaChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 148.dp,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    selection: IntRange? = null,
    onSelectionChange: ((IntRange) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val handleRingColor = MaterialTheme.colorScheme.surface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val count = values.size

    val padH = with(density) { 14.dp.toPx() }
    val padT = with(density) { 10.dp.toPx() }
    val padB = with(density) { 20.dp.toPx() }

    val latestSelection = rememberUpdatedState(selection)
    val latestCallback = rememberUpdatedState(onSelectionChange)

    fun fullRange(): IntRange = 0..(count - 1).coerceAtLeast(0)

    fun indexAt(x: Float, width: Float): Int {
        if (count <= 1) return 0
        val usable = (width - padH * 2).coerceAtLeast(1f)
        val t = ((x - padH) / usable).coerceIn(0f, 1f)
        return (t * (count - 1)).roundToInt()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .then(
                    if (onSelectionChange == null) Modifier else Modifier
                        .pointerInput(count) {
                            detectTapGestures { offset ->
                                val callback = latestCallback.value ?: return@detectTapGestures
                                if (count <= 1) return@detectTapGestures
                                val range = latestSelection.value ?: fullRange()
                                val index = indexAt(offset.x, size.width.toFloat())
                                val next = if (abs(index - range.first) <= abs(index - range.last)) {
                                    index.coerceAtMost(range.last)..range.last
                                } else {
                                    range.first..index.coerceAtLeast(range.first)
                                }
                                callback(next)
                            }
                        }
                        .pointerInput(count) {
                            var handle = -1
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val range = latestSelection.value ?: fullRange()
                                    val index = indexAt(offset.x, size.width.toFloat())
                                    handle = if (abs(index - range.first) <= abs(index - range.last)) 0 else 1
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val callback = latestCallback.value
                                    val range = latestSelection.value
                                    if (callback != null && range != null) {
                                        val index = indexAt(change.position.x, size.width.toFloat())
                                        if (handle == 0) {
                                            callback(index.coerceAtMost(range.last)..range.last)
                                        } else {
                                            callback(range.first..index.coerceAtLeast(range.first))
                                        }
                                    }
                                },
                                onDragEnd = { handle = -1 },
                                onDragCancel = { handle = -1 },
                            )
                        }
                )
        ) {
            if (values.isEmpty()) return@Canvas

            val width = size.width
            val height = size.height
            val plotWidth = (width - padH * 2).coerceAtLeast(1f)
            val plotHeight = (height - padT - padB).coerceAtLeast(1f)
            val baseY = padT + plotHeight
            val maxValue = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)

            fun xAt(index: Int): Float =
                if (count > 1) padH + plotWidth * index / (count - 1) else padH + plotWidth / 2f

            fun yAt(value: Float): Float =
                padT + plotHeight * (1f - (value / maxValue).coerceIn(0f, 1f))

            // 水平网格（3 等分）
            for (step in 0..3) {
                val y = padT + plotHeight * step / 3f
                drawLine(
                    color = gridColor,
                    start = Offset(padH, y),
                    end = Offset(width - padH, y),
                    strokeWidth = 1f,
                )
            }

            val points = values.mapIndexed { index, value -> Offset(xAt(index), yAt(value)) }
            val linePath = buildSmoothPath(points)
            val areaPath = Path().apply {
                addPath(linePath)
                lineTo(points.last().x, baseY)
                lineTo(points.first().x, baseY)
                close()
            }

            val selected = selection?.takeIf { it.first < it.last && it.last < count }
            val areaBrush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.26f), lineColor.copy(alpha = 0f)),
                startY = padT,
                endY = baseY,
            )
            val dimAreaBrush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.07f), lineColor.copy(alpha = 0f)),
                startY = padT,
                endY = baseY,
            )

            clipRect(top = padT - 2f, bottom = baseY + 2f) {
                if (selected == null) {
                    drawPath(areaPath, areaBrush)
                    drawPath(linePath, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                } else {
                    // 未选中部分压暗，选中部分高亮
                    drawPath(areaPath, dimAreaBrush)
                    drawPath(
                        path = linePath,
                        color = lineColor.copy(alpha = 0.3f),
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                    val left = xAt(selected.first)
                    val right = xAt(selected.last)
                    clipRect(left = left, top = padT - 2f, right = right, bottom = baseY + 2f) {
                        drawPath(areaPath, areaBrush)
                        drawPath(linePath, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            }

            if (selected != null) {
                listOf(selected.first, selected.last).forEach { index ->
                    val center = Offset(xAt(index), yAt(values[index]))
                    drawLine(
                        color = lineColor.copy(alpha = 0.35f),
                        start = Offset(center.x, padT),
                        end = Offset(center.x, baseY),
                        strokeWidth = 1f,
                    )
                    drawCircle(handleRingColor, radius = 6.dp.toPx(), center = center)
                    drawCircle(lineColor, radius = 4.dp.toPx(), center = center)
                }
            }
        }

        if (labels.isNotEmpty() && count > 1) {
            val tickIndices = listOf(0, count / 3, count * 2 / 3, count - 1).distinct()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                tickIndices.forEach { index ->
                    Text(
                        text = labels.getOrElse(index) { "" },
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 分布条：一行标签 + 数值 + 细圆角进度条 */
@Composable
fun UsageBarRow(
    label: String,
    value: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

/**
 * Catmull-Rom 样条转三次贝塞尔，得到过所有采样点的光滑曲线。
 * 单点或空集时退化为一条直线路径。
 */
private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path

    for (index in 0 until points.size - 1) {
        val previous = points.getOrElse(index - 1) { points[index] }
        val current = points[index]
        val next = points[index + 1]
        val afterNext = points.getOrElse(index + 2) { next }

        val control1 = Offset(
            x = current.x + (next.x - previous.x) / 6f,
            y = current.y + (next.y - previous.y) / 6f,
        )
        val control2 = Offset(
            x = next.x - (afterNext.x - current.x) / 6f,
            y = next.y - (afterNext.y - current.y) / 6f,
        )
        path.cubicTo(control1.x, control1.y, control2.x, control2.y, next.x, next.y)
    }
    return path
}
