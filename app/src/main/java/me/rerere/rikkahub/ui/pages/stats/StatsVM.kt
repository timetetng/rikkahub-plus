package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.HourUsageRow
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.StatsBucket
import me.rerere.rikkahub.data.db.dao.TokenBucketRow
import me.rerere.rikkahub.data.db.dao.getAssistantUsage
import me.rerere.rikkahub.data.db.dao.getHourUsageProfile
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getModelUsage
import me.rerere.rikkahub.data.db.dao.getTokenBuckets
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.db.dao.getToolUsage
import me.rerere.rikkahub.data.db.dao.getUsageMessageCount
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** 曲线的时间颗粒度 */
enum class StatsGranularity { HOUR, DAY, WEEK, MONTH }

/** 曲线可切换的指标 */
enum class TokenMetric { TOTAL, PROMPT, COMPLETION, CACHED }

/** 曲线上的一个采样点 */
data class TokenPoint(
    val key: String,
    val label: String,
    val prompt: Long = 0L,
    val completion: Long = 0L,
    val cached: Long = 0L,
    val total: Long = 0L,
    val messages: Int = 0,
) {
    /** 精确值（显示用，避免 Float 精度损失） */
    fun valueLong(metric: TokenMetric): Long = when (metric) {
        TokenMetric.TOTAL -> total
        TokenMetric.PROMPT -> prompt
        TokenMetric.COMPLETION -> completion
        TokenMetric.CACHED -> cached
    }

    /** 绘图用值 */
    fun value(metric: TokenMetric): Float = valueLong(metric).toFloat()
}

/** 分布类图表的一行（助手 / 模型） */
data class UsageSlice(
    val name: String,
    val totalTokens: Long,
    val messages: Int,
)

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val usageMessageCount: Int = 0,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    /** 热力图对应区间（近 52 周）的每日 token，用于着色与点击详情 */
    val tokensPerDay: Map<LocalDate, TokenPoint> = emptyMap(),
    val series: Map<StatsGranularity, List<TokenPoint>> = emptyMap(),
    val hourProfile7: List<Float> = List(24) { 0f },
    val hourProfile30: List<Float> = List(24) { 0f },
    val hourProfile7Counts: List<Float> = List(24) { 0f },
    val hourProfile30Counts: List<Float> = List(24) { 0f },
    val toolUsage: List<Pair<String, Int>> = emptyList(),
    val assistantUsage: List<UsageSlice> = emptyList(),
    val modelUsage: List<UsageSlice> = emptyList(),
    val launchCount: Int = 0,
) {
    /** 缓存命中率：cached / prompt */
    val cacheHitRate: Float
        get() = if (totalPromptTokens > 0) {
            (totalCachedTokens.toFloat() / totalPromptTokens).coerceIn(0f, 1f)
        } else {
            0f
        }

    /** 每轮平均 token（一轮 = 一条产生消耗的助手回复） */
    val avgTokensPerTurn: Long
        get() = if (usageMessageCount > 0) {
            (totalPromptTokens + totalCompletionTokens) / usageMessageCount
        } else {
            0L
        }
}

private const val HOUR_SPAN = 72
private const val WEEK_SPAN = 52
private const val MONTH_SPAN = 24

private val HOUR_KEY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH")
private val MONTH_KEY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val locale = Locale.getDefault()

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val heatmapStart = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)

        // 各颗粒度的完整桶键（查询结果里缺失的桶要补 0，否则曲线会失去时间连续性）
        val dayCount = ChronoUnit.DAYS.between(heatmapStart, today).toInt() + 1
        val hourStart = now.truncatedTo(ChronoUnit.HOURS).minusHours((HOUR_SPAN - 1).toLong())
        val hourKeys = (0 until HOUR_SPAN).map { hourStart.plusHours(it.toLong()).format(HOUR_KEY_FMT) }
        val dayKeys = (0 until dayCount).map { heatmapStart.plusDays(it.toLong()).toString() }
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekKeys = (0 until WEEK_SPAN).map { weekStart.minusWeeks((WEEK_SPAN - 1 - it).toLong()).toString() }
        val monthStart = today.withDayOfMonth(1)
        val monthKeys = (0 until MONTH_SPAN).map { monthStart.minusMonths((MONTH_SPAN - 1 - it).toLong()).format(MONTH_KEY_FMT) }

        val loaded = withContext(Dispatchers.IO) {
            coroutineScope {
                // 天颗粒度直接用热力图区间，一份数据同时供热力图着色 / 点击详情 / 天曲线
                val dayRows = async { messageNodeDAO.getTokenBuckets(StatsBucket.DAY, heatmapStart.toString()) }
                val hourRows = async { messageNodeDAO.getTokenBuckets(StatsBucket.HOUR, hourStart.format(HOUR_KEY_FMT)) }
                val weekRows = async { messageNodeDAO.getTokenBuckets(StatsBucket.WEEK, weekKeys.first()) }
                val monthRows = async { messageNodeDAO.getTokenBuckets(StatsBucket.MONTH, monthKeys.first()) }
                val hour7Rows = async { messageNodeDAO.getHourUsageProfile(today.minusDays(6).toString()) }
                val hour30Rows = async { messageNodeDAO.getHourUsageProfile(today.minusDays(29).toString()) }
                val toolRows = async { messageNodeDAO.getToolUsage(10) }
                val assistantRows = async { messageNodeDAO.getAssistantUsage() }
                val modelRows = async { messageNodeDAO.getModelUsage() }
                val perDay = async { messageNodeDAO.getMessageCountPerDay(heatmapStart.toString()) }
                val tokenStats = async { messageNodeDAO.getTokenStats() }
                val conversations = async { conversationDAO.countAll() }
                val usageCount = async { messageNodeDAO.getUsageMessageCount() }

                val settings = settingsStore.settingsFlow.value
                val assistantNames = settings.assistants.associate { it.id.toString() to it.name }
                val modelNames = settings.providers
                    .flatMap { it.models }
                    .associate { it.id.toString() to it.displayName }

                val daySeries = buildSeries(dayKeys, dayRows.await(), StatsGranularity.DAY, locale)
                val tokensPerDay = daySeries.mapNotNull { point ->
                    runCatching { LocalDate.parse(point.key) }.getOrNull()?.let { it to point }
                }.toMap()

                val (hp7, hc7) = buildHourProfile(hour7Rows.await(), 7)
                val (hp30, hc30) = buildHourProfile(hour30Rows.await(), 30)

                AppStats(
                    isLoading = false,
                    totalConversations = conversations.await(),
                    totalMessages = tokenStats.await().totalMessages,
                    totalPromptTokens = tokenStats.await().promptTokens,
                    totalCompletionTokens = tokenStats.await().completionTokens,
                    totalCachedTokens = tokenStats.await().cachedTokens,
                    usageMessageCount = usageCount.await(),
                    conversationsPerDay = perDay.await()
                        .mapNotNull { entry ->
                            runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                        }
                        .toMap(),
                    tokensPerDay = tokensPerDay,
                    series = mapOf(
                        StatsGranularity.HOUR to buildSeries(hourKeys, hourRows.await(), StatsGranularity.HOUR, locale),
                        StatsGranularity.DAY to daySeries,
                        StatsGranularity.WEEK to buildSeries(weekKeys, weekRows.await(), StatsGranularity.WEEK, locale),
                        StatsGranularity.MONTH to buildSeries(monthKeys, monthRows.await(), StatsGranularity.MONTH, locale),
                    ),
                    hourProfile7 = hp7,
                    hourProfile30 = hp30,
                    hourProfile7Counts = hc7,
                    hourProfile30Counts = hc30,
                    toolUsage = toolRows.await().map { it.toolName to it.count },
                    assistantUsage = assistantRows.await().take(6).map { row ->
                        UsageSlice(
                            name = assistantNames[row.assistantId]?.takeIf { it.isNotBlank() }
                                ?: row.assistantId.take(8),
                            totalTokens = row.totalTokens,
                            messages = row.messages,
                        )
                    },
                    modelUsage = modelRows.await().take(6).map { row ->
                        UsageSlice(
                            name = modelNames[row.modelId]?.takeIf { it.isNotBlank() }
                                ?: row.modelId.take(8),
                            totalTokens = row.totalTokens,
                            messages = row.messages,
                        )
                    },
                    launchCount = settings.launchCount,
                )
            }
        }

        _stats.value = loaded
    }

    private fun buildSeries(
        keys: List<String>,
        rows: List<TokenBucketRow>,
        granularity: StatsGranularity,
        locale: Locale,
    ): List<TokenPoint> {
        val byKey = rows.associateBy { it.bucket }
        return keys.map { key ->
            val row = byKey[key]
            TokenPoint(
                key = key,
                label = labelFor(granularity, key, locale),
                prompt = row?.promptTokens ?: 0L,
                completion = row?.completionTokens ?: 0L,
                cached = row?.cachedTokens ?: 0L,
                total = row?.totalTokens ?: 0L,
                messages = row?.messages ?: 0,
            )
        }
    }

    private fun labelFor(granularity: StatsGranularity, key: String, locale: Locale): String =
        when (granularity) {
            StatsGranularity.HOUR -> key.substringAfter('T', key) + ":00"
            StatsGranularity.DAY, StatsGranularity.WEEK -> {
                val date = runCatching { LocalDate.parse(key) }.getOrNull()
                if (date == null) key else "${date.monthValue}/${date.dayOfMonth}"
            }

            StatsGranularity.MONTH -> {
                val date = runCatching { LocalDate.parse("$key-01") }.getOrNull()
                if (date == null) key else date.month.getDisplayName(TextStyle.SHORT, locale)
            }
        }

    /** 返回 24 小时的平均 token / 平均消息数（长度为 24，缺失时段补 0） */
    private fun buildHourProfile(rows: List<HourUsageRow>, days: Int): Pair<List<Float>, List<Float>> {
        val tokens = MutableList(24) { 0f }
        val counts = MutableList(24) { 0f }
        rows.forEach { row ->
            if (row.hour in 0..23) {
                tokens[row.hour] = row.totalTokens.toFloat() / days
                counts[row.hour] = row.messages.toFloat() / days
            }
        }
        return tokens to counts
    }
}
