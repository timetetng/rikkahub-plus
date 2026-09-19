package me.rerere.rikkahub.ui.pages.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** 热力图的着色依据 */
private enum class HeatmapMetric { MESSAGES, TOKENS }

@Composable
fun StatsPage(vm: StatsVM = koinViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // 图表状态提升到页面级：LazyColumn 回收 item 时不会丢失
    var heatmapMetric by remember { mutableStateOf(HeatmapMetric.MESSAGES) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var chartRange by remember { mutableStateOf(StatsRange.DAYS_7) }
    var metric by remember { mutableStateOf(TokenMetric.TOTAL) }
    var selectionStart by remember { mutableStateOf(0) }
    var selectionEnd by remember { mutableStateOf(-1) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.stats_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (stats.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val points = stats.series[chartRange].orEmpty()
            val selection = when {
                selectionEnd >= selectionStart &&
                    selectionStart in points.indices &&
                    selectionEnd in points.indices -> selectionStart..selectionEnd

                points.isNotEmpty() -> points.indices.first..points.indices.last
                else -> null
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    HeatmapCard(
                        stats = stats,
                        metric = heatmapMetric,
                        onMetricChange = { heatmapMetric = it },
                        onDayClick = { selectedDay = it },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    TokenTrendCard(
                        points = points,
                        metric = metric,
                        onMetricChange = { metric = it },
                        range = chartRange,
                        onRangeChange = {
                            chartRange = it
                            selectionStart = 0
                            selectionEnd = -1
                        },
                        selection = selection,
                        onSelectionChange = { range ->
                            selectionStart = range.first
                            selectionEnd = range.last
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    HourProfileCard(
                        stats = stats,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    StatsGrid(
                        stats = stats,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                if (stats.toolUsage.isNotEmpty()) {
                    item {
                        ToolUsageCard(
                            usage = stats.toolUsage,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                if (stats.assistantUsage.isNotEmpty()) {
                    item {
                        BreakdownCard(
                            titleRes = R.string.stats_page_by_assistant,
                            icon = HugeIcons.UserCircle,
                            slices = stats.assistantUsage,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                if (stats.modelUsage.isNotEmpty()) {
                    item {
                        BreakdownCard(
                            titleRes = R.string.stats_page_by_model,
                            icon = HugeIcons.Cpu,
                            slices = stats.modelUsage,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }

    selectedDay?.let { day ->
        DayDetailDialog(
            day = day,
            stats = stats,
            onDismiss = { selectedDay = null },
        )
    }
}

// ─────────────────────────── 热力图 ───────────────────────────

@Composable
private fun HeatmapCard(
    stats: AppStats,
    metric: HeatmapMetric,
    onMetricChange: (HeatmapMetric) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_page_heatmap_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetricChip(
                        label = stringResource(R.string.stats_heatmap_metric_messages),
                        selected = metric == HeatmapMetric.MESSAGES,
                        onClick = { onMetricChange(HeatmapMetric.MESSAGES) },
                    )
                    MetricChip(
                        label = stringResource(R.string.stats_heatmap_metric_tokens),
                        selected = metric == HeatmapMetric.TOKENS,
                        onClick = { onMetricChange(HeatmapMetric.TOKENS) },
                    )
                }
            }

            ChatHeatmap(
                conversationsPerDay = stats.conversationsPerDay,
                tokensPerDay = stats.tokensPerDay,
                metric = metric,
                onDayClick = onDayClick,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_page_heatmap_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                    HeatmapCell(alpha = alpha, sizeDp = 10)
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.stats_page_heatmap_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatHeatmap(
    conversationsPerDay: Map<LocalDate, Int>,
    tokensPerDay: Map<LocalDate, TokenPoint>,
    metric: HeatmapMetric,
    onDayClick: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)

    val numWeeks = 53
    val valueOf: (LocalDate) -> Long = { date ->
        when (metric) {
            HeatmapMetric.MESSAGES -> (conversationsPerDay[date] ?: 0).toLong()
            HeatmapMetric.TOKENS -> tokensPerDay[date]?.total ?: 0L
        }
    }
    val activeCounts = (conversationsPerDay.keys + tokensPerDay.keys)
        .map(valueOf)
        .filter { it > 0 }
        .sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1L }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2L }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3L }

    val cellSize = 11.dp
    val cellSpacing = 2.dp
    val monthLabelHeight = 14.dp

    val dowLabels = listOf(
        "",
        stringResource(R.string.stats_page_dow_mon),
        "",
        stringResource(R.string.stats_page_dow_wed),
        "",
        stringResource(R.string.stats_page_dow_fri),
        ""
    )

    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier.width(12.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val value = if (isFuture) 0L else valueOf(date)
                            val alpha = when {
                                isFuture -> -1f
                                value <= 0L -> 0f
                                value <= q1 -> 0.25f
                                value <= q2 -> 0.5f
                                value <= q3 -> 0.75f
                                else -> 1f
                            }
                            HeatmapCell(
                                alpha = alpha,
                                sizeDp = cellSize.value.toInt(),
                                onClick = if (isFuture) null else ({ onDayClick(date) }),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int, onClick: (() -> Unit)? = null) {
    val color = when {
        alpha < 0f -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) // future
        alpha == 0f -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    )
}

// ─────────────────────────── Token 曲线 ───────────────────────────

@Composable
private fun TokenTrendCard(
    points: List<TokenPoint>,
    metric: TokenMetric,
    onMetricChange: (TokenMetric) -> Unit,
    range: StatsRange,
    onRangeChange: (StatsRange) -> Unit,
    selection: IntRange?,
    onSelectionChange: (IntRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val values = remember(points, metric) { points.map { it.value(metric) } }
    val labels = remember(points) { points.map { it.label } }
    val selectedRange = selection?.takeIf { it.last < points.size }
    val rangeTotal = remember(points, metric, selectedRange) {
        if (selectedRange == null) 0L else points.subList(selectedRange.first, selectedRange.last + 1).sumOf { it.valueLong(metric) }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_page_token_trend),
                    style = MaterialTheme.typography.titleMedium,
                )
                RangeSelector(
                    current = range,
                    onSelect = onRangeChange,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TokenMetric.entries.forEach { option ->
                    MetricChip(
                        label = stringResource(metricLabelRes(option)),
                        selected = option == metric,
                        onClick = { onMetricChange(option) },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (selectedRange == null || (selectedRange.first == 0 && selectedRange.last == points.lastIndex)) {
                        stringResource(R.string.stats_range_all)
                    } else {
                        "${points[selectedRange.first].label} – ${points[selectedRange.last].label}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTokens(rangeTotal),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (points.isEmpty()) {
                EmptyHint()
            } else {
                SmoothAreaChart(
                    values = values,
                    labels = labels,
                    selection = selectedRange,
                    onSelectionChange = onSelectionChange,
                )
            }
        }
    }
}

@Composable
private fun RangeSelector(
    current: StatsRange,
    onSelect: (StatsRange) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(stringResource(rangeLabelRes(current))) },
            trailingIcon = {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            StatsRange.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(rangeLabelRes(option))) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
    )
}

// ─────────────────────────── 时段分布 ───────────────────────────

@Composable
private fun HourProfileCard(stats: AppStats, modifier: Modifier = Modifier) {
    var last30Days by remember { mutableStateOf(false) }
    val profile = if (last30Days) stats.hourProfile30 else stats.hourProfile7
    val counts = if (last30Days) stats.hourProfile30Counts else stats.hourProfile7Counts
    val labels = remember { List(24) { if (it % 3 == 0) "$it:00" else "" } }
    val peakHour = profile.indices.maxByOrNull { profile[it] } ?: 0

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_page_hour_profile),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetricChip(
                        label = stringResource(R.string.stats_period_7d),
                        selected = !last30Days,
                        onClick = { last30Days = false },
                    )
                    MetricChip(
                        label = stringResource(R.string.stats_period_30d),
                        selected = last30Days,
                        onClick = { last30Days = true },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.stats_hour_peak, "${peakHour}:00"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTokens(profile.getOrElse(peakHour) { 0f }.toLong()),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(
                        R.string.stats_hour_avg_messages,
                        counts.getOrElse(peakHour) { 0f },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SmoothAreaChart(
                values = profile,
                labels = labels,
                chartHeight = 120.dp,
                lineColor = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

// ─────────────────────────── 数字卡 ───────────────────────────

@Composable
private fun StatsGrid(stats: AppStats, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.ChartColumn,
                label = stringResource(R.string.stats_page_total_conversations),
                value = formatCount(stats.totalConversations.toLong()),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Message01,
                label = stringResource(R.string.stats_page_total_messages),
                value = formatCount(stats.totalMessages.toLong()),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Cpu,
                label = stringResource(R.string.stats_page_input_tokens),
                value = formatTokens(stats.totalPromptTokens),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Cpu,
                label = stringResource(R.string.stats_page_output_tokens),
                value = formatTokens(stats.totalCompletionTokens),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Zap,
                label = stringResource(R.string.stats_page_cached_tokens),
                value = formatTokens(stats.totalCachedTokens),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Zap,
                label = stringResource(R.string.stats_page_cache_hit_rate),
                value = "%.1f%%".format(stats.cacheHitRate * 100),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Message01,
                label = stringResource(R.string.stats_page_avg_tokens_per_turn),
                value = formatTokens(stats.avgTokensPerTurn),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Rocket01,
                label = stringResource(R.string.stats_page_launch_count),
                value = formatCount(stats.launchCount.toLong()),
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────── 分布 ───────────────────────────

@Composable
private fun ToolUsageCard(usage: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val max = (usage.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    val total = usage.sumOf { it.second }.coerceAtLeast(1)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_page_tool_usage),
                style = MaterialTheme.typography.titleMedium,
            )
            usage.forEach { (name, count) ->
                UsageBarRow(
                    label = name,
                    value = stringResource(R.string.stats_tool_calls, formatCount(count.toLong())),
                    fraction = count.toFloat() / max,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun BreakdownCard(
    titleRes: Int,
    icon: ImageVector,
    slices: List<UsageSlice>,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.totalTokens }.coerceAtLeast(1L)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            slices.forEach { slice ->
                UsageBarRow(
                    label = slice.name,
                    value = "${formatTokens(slice.totalTokens)} · ${((slice.totalTokens * 100.0) / total).toInt()}%",
                    fraction = slice.totalTokens.toFloat() / total,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

// ─────────────────────────── 日期详情 ───────────────────────────

@Composable
private fun DayDetailDialog(
    day: LocalDate,
    stats: AppStats,
    onDismiss: () -> Unit,
) {
    val point = stats.tokensPerDay[day]
    val messages = stats.conversationsPerDay[day] ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "${day.year}/${day.monthValue}/${day.dayOfMonth}",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow(
                    label = stringResource(R.string.stats_day_total_tokens),
                    value = formatTokens(point?.total ?: 0L),
                    emphasized = true,
                )
                DetailRow(
                    label = stringResource(R.string.stats_page_input_tokens),
                    value = formatTokens(point?.prompt ?: 0L),
                )
                DetailRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokens(point?.completion ?: 0L),
                )
                DetailRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = formatTokens(point?.cached ?: 0L),
                )
                DetailRow(
                    label = stringResource(R.string.stats_day_messages),
                    value = formatCount(messages.toLong()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.stats_dialog_close))
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = if (emphasized) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}

@Composable
private fun EmptyHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.stats_empty_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─────────────────────────── 工具函数 ───────────────────────────

private fun rangeLabelRes(range: StatsRange): Int = when (range) {
    StatsRange.HOURS_24 -> R.string.stats_range_hours_24
    StatsRange.DAYS_7 -> R.string.stats_range_days_7
    StatsRange.DAYS_30 -> R.string.stats_range_days_30
    StatsRange.MONTHS_12 -> R.string.stats_range_months_12
}

private fun metricLabelRes(metric: TokenMetric): Int = when (metric) {
    TokenMetric.TOTAL -> R.string.stats_metric_total
    TokenMetric.PROMPT -> R.string.stats_metric_prompt
    TokenMetric.COMPLETION -> R.string.stats_metric_completion
    TokenMetric.CACHED -> R.string.stats_metric_cached
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

private fun formatTokens(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}
