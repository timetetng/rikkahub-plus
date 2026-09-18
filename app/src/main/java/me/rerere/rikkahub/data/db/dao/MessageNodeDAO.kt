package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>

    @Query("SELECT COUNT(*) FROM message_node WHERE conversation_id = :conversationId")
    suspend fun countNodesOfConversation(conversationId: String): Int

    // ───────────────────────── 统计页 v2：分桶 / 分布 ─────────────────────────

    @RawQuery
    suspend fun getTokenBucketRows(query: SupportSQLiteQuery): List<TokenBucketRow>

    @RawQuery
    suspend fun getHourUsageRows(query: SupportSQLiteQuery): List<HourUsageRow>

    @RawQuery
    suspend fun getToolUsageRows(query: SupportSQLiteQuery): List<ToolUsageRow>

    @RawQuery
    suspend fun getAssistantUsageRows(query: SupportSQLiteQuery): List<AssistantUsageRow>

    @RawQuery
    suspend fun getModelUsageRows(query: SupportSQLiteQuery): List<ModelUsageRow>

    @RawQuery
    suspend fun getUsageMessageCountRaw(query: SupportSQLiteQuery): Int
}

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class MessageDayCount(val day: String, val count: Int)

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "SELECT COUNT(*) AS totalMessages, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens " +
        "FROM message_node mn, json_each(mn.messages) j"
)

suspend fun MessageNodeDAO.getTokenStats(): MessageTokenStats = getTokenStatsRaw(TOKEN_STATS_SQL)

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "WHERE json_extract(j.value, '$.role') = 'user' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf(startDate)
        )
    )

// ───────────────────────── 统计页 v2 ─────────────────────────
//
// 口径说明：以下查询与 getTokenStats() **完全一致** —— 用 json_each(mn.messages) 展开节点内
// 所有分支，被重新生成/编辑淘汰的候选回复同样计入。若要改成「只算 select_index 选中的分支」，
// 需连同 TOKEN_STATS_SQL 一起改，否则卡片总数与曲线总和会对不上。

/** 统计分桶粒度，附带 SQL 桶键表达式（`j.value` 为 message_node.messages 数组的元素） */
enum class StatsBucket(val sql: String) {
    /** 2026-09-18T14 */
    HOUR("substr(json_extract(j.value, '\$.createdAt'), 1, 13)"),

    /** 2026-09-18 */
    DAY("substr(json_extract(j.value, '\$.createdAt'), 1, 10)"),

    /** 该周周一的日期，如 2026-09-14（%w: 0=周日，故 +6 后取模得到距周一的偏移） */
    WEEK(
        "date(json_extract(j.value, '\$.createdAt'), '-' || " +
            "((CAST(strftime('%w', json_extract(j.value, '\$.createdAt')) AS INTEGER) + 6) % 7) || ' days')"
    ),

    /** 2026-09 */
    MONTH("substr(json_extract(j.value, '\$.createdAt'), 1, 7)"),
}

data class TokenBucketRow(
    val bucket: String = "",
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val messages: Int = 0,
)

data class HourUsageRow(
    val hour: Int = 0,
    val totalTokens: Long = 0L,
    val messages: Int = 0,
)

data class ToolUsageRow(
    val toolName: String = "",
    val count: Int = 0,
)

data class AssistantUsageRow(
    val assistantId: String = "",
    val messages: Int = 0,
    val totalTokens: Long = 0L,
)

data class ModelUsageRow(
    val modelId: String = "",
    val messages: Int = 0,
    val totalTokens: Long = 0L,
)

/** 只统计携带 usage 的消息（即真正产生 token 消耗的助手回复） */
private const val USAGE_PRESENT = "json_extract(j.value, '\$.usage.promptTokens') IS NOT NULL"

private const val TOKEN_FROM = " FROM message_node mn, json_each(mn.messages) j "

private const val TOKEN_AGGS =
    "COALESCE(SUM(CAST(json_extract(j.value, '\$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '\$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '\$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '\$.usage.totalTokens') AS INTEGER)), 0) AS totalTokens, " +
        "COUNT(*) AS messages"

/**
 * 按 [bucket] 粒度聚合 token。空桶不会返回，由调用方补齐（曲线需要连续的 x 轴）。
 *
 * @param start 起始时间（含），与 createdAt 的 ISO 字符串前缀比较；null 表示不限
 */
suspend fun MessageNodeDAO.getTokenBuckets(
    bucket: StatsBucket,
    start: String? = null,
): List<TokenBucketRow> {
    val sql = "SELECT COALESCE(" + bucket.sql + ", '') AS bucket, " + TOKEN_AGGS +
        TOKEN_FROM + "WHERE " + USAGE_PRESENT +
        (if (start != null) " AND json_extract(j.value, '\$.createdAt') >= ?" else "") +
        " GROUP BY bucket ORDER BY bucket ASC"
    val query = if (start != null) {
        SimpleSQLiteQuery(sql, arrayOf(start))
    } else {
        SimpleSQLiteQuery(sql)
    }
    return getTokenBucketRows(query)
}

/** 时间分布：按「一天中的第几小时」聚合，用于统计使用时段习惯 */
suspend fun MessageNodeDAO.getHourUsageProfile(startDate: String): List<HourUsageRow> =
    getHourUsageRows(
        SimpleSQLiteQuery(
            "SELECT CAST(substr(json_extract(j.value, '\$.createdAt'), 12, 2) AS INTEGER) AS hour, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '\$.usage.totalTokens') AS INTEGER)), 0) AS totalTokens, " +
                "COUNT(*) AS messages" + TOKEN_FROM +
                "WHERE " + USAGE_PRESENT +
                " AND substr(json_extract(j.value, '\$.createdAt'), 1, 10) >= ? " +
                "GROUP BY hour ORDER BY hour ASC",
            arrayOf(startDate)
        )
    )

/** 工具调用次数排行（统计 timeline 里 type == 'tool' 的 part） */
suspend fun MessageNodeDAO.getToolUsage(limit: Int = 10): List<ToolUsageRow> =
    getToolUsageRows(
        SimpleSQLiteQuery(
            "SELECT json_extract(p.value, '\$.toolName') AS toolName, COUNT(*) AS count " +
                "FROM message_node mn, json_each(mn.messages) j, " +
                "json_each(json_extract(j.value, '\$.parts')) p " +
                "WHERE json_extract(p.value, '\$.type') = 'tool' " +
                "AND json_extract(p.value, '\$.toolName') IS NOT NULL " +
                "GROUP BY toolName ORDER BY count DESC LIMIT ?",
            arrayOf(limit)
        )
    )

/** 按助手维度聚合（名字映射需要 SettingsStore，在 VM 层做） */
suspend fun MessageNodeDAO.getAssistantUsage(): List<AssistantUsageRow> =
    getAssistantUsageRows(
        SimpleSQLiteQuery(
            "SELECT c.assistant_id AS assistantId, COUNT(*) AS messages, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '\$.usage.totalTokens') AS INTEGER)), 0) AS totalTokens " +
                "FROM message_node mn JOIN conversationentity c ON c.id = mn.conversation_id, " +
                "json_each(mn.messages) j " +
                "WHERE " + USAGE_PRESENT +
                " GROUP BY assistantId ORDER BY totalTokens DESC"
        )
    )

/** 按模型维度聚合（modelId 是 Model.id，映射需要 SettingsStore，在 VM 层做） */
suspend fun MessageNodeDAO.getModelUsage(): List<ModelUsageRow> =
    getModelUsageRows(
        SimpleSQLiteQuery(
            "SELECT json_extract(j.value, '\$.modelId') AS modelId, COUNT(*) AS messages, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '\$.usage.totalTokens') AS INTEGER)), 0) AS totalTokens " +
                TOKEN_FROM +
                "WHERE " + USAGE_PRESENT +
                " AND json_extract(j.value, '\$.modelId') IS NOT NULL " +
                "GROUP BY modelId ORDER BY totalTokens DESC"
        )
    )

/** 携带 usage 的消息条数（用于算「每轮平均 token」） */
suspend fun MessageNodeDAO.getUsageMessageCount(): Int = getUsageMessageCountRaw(
    SimpleSQLiteQuery(
        "SELECT COUNT(*)" + TOKEN_FROM + "WHERE " + USAGE_PRESENT
    )
)
