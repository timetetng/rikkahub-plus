package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 同一文件的「读 -> 改 -> 写」串行化。
 *
 * 模型会在一条消息里并发发出多个 file 调用：同一文件的两个 patch 同批到达时，
 * 两边都读到改动前的快照，后写的把先写的冲掉（此前记成「FUSE 缓存 / patch 拿到陈旧快照」，
 * 其实是并发，不是缓存）。这里按 canonical path 加锁，把 read-modify-write 整段圈住，
 * 思路同 opencode 的 per-file Semaphore、pi-mono 的 withFileMutationQueue。
 * 只增不删：条目数 = 进程内碰过的文件数，可忽略。
 */
private val fileMutationLocks = ConcurrentHashMap<String, ReentrantLock>()

private fun <T> withFileLock(file: File, block: () -> T): T {
    val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    val lock = fileMutationLocks.computeIfAbsent(key) { ReentrantLock() }
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}

/**
 * 文件操作工具 — 统一 file 工具，通过 action 参数选择操作。
 */
fun createFileTools(workspaceDir: String = "/storage/emulated/0/Download"): List<Tool> {
    val defaultDir = workspaceDir

    fun resolveFile(path: String): File {
        val f = File(path)
        if (f.exists() || path.startsWith("/")) return f
        val fallback = File(defaultDir, path).normalize()
        val canonicalDownload = File(defaultDir).canonicalPath
        val canonicalFallback = fallback.canonicalPath
        return if (canonicalFallback.startsWith(canonicalDownload)) fallback
        else File(defaultDir, fallback.name).normalize()
    }

    fun resolveDestPath(path: String): File {
        val f = File(path)
        if (path.startsWith("/")) return f
        val fallback = File(defaultDir, path).normalize()
        val canonicalDownload = File(defaultDir).canonicalPath
        val canonicalFallback = fallback.canonicalPath
        return if (canonicalFallback.startsWith(canonicalDownload)) fallback
        else File(defaultDir, fallback.name).normalize()
    }

    return listOf(
        Tool(
            name = "file",
            description = buildString {
                appendLine("File operations: read, write, patch, list, search, copy, move, mkdir, delete.")
                appendLine()
                appendLine("Use this tool for all file system operations — reading code, writing files, searching content, and managing directories.")
                appendLine()
                appendLine("When to use:")
                appendLine("- Read file contents with optional offset/limit pagination")
                appendLine("- Write or overwrite files with text content")
                appendLine("- Patch files using surgical find-and-replace (old_string → new_string)")
                appendLine("- List directory contents with file sizes")
                appendLine("- Search files by name glob or text/regex content search")
                appendLine("- Copy, move, create directories, or delete files/directories")
                appendLine()
                appendLine("When NOT to use:")
                appendLine("- Shell commands (use execute_command)")
                appendLine()
                appendLine("Args:")
                appendLine("- action: read|write|patch|list|search|copy|move|mkdir|delete")
                appendLine("- path: File path (read, write, patch, mkdir, delete)")
                appendLine("- source/destination: Source and dest paths (copy, move)")
                appendLine("- content: Text content to write (write)")
                appendLine("- old_string/new_string/replace_all: Find-and-replace (patch)")
                appendLine("- edits: array of {old_string,new_string} — several non-overlapping changes in ONE call; use this instead of two parallel patches")
                appendLine("- offset/limit: Line range for paginated read (read)")
                appendLine("- dir: Directory to list (list, default: ${defaultDir})")
                appendLine("- mode/pattern/root: Search parameters (search)")
                appendLine("- file_pattern/use_regex/context: Advanced search options")
                appendLine()
                appendLine("Absolute paths work as-is. Relative paths resolve to ${defaultDir}.")
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("read"); add("write"); add("list"); add("search")
                                add("copy"); add("move"); add("mkdir"); add("delete"); add("patch")
                            })
                            put("description", "Operation to perform")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "File or directory path. Used by: read, write, list, mkdir, delete")
                        })
                        put("source", buildJsonObject {
                            put("type", "string")
                            put("description", "Source path. Used by: copy, move")
                        })
                        put("destination", buildJsonObject {
                            put("type", "string")
                            put("description", "Destination path. Used by: copy, move")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Text content to write. Used by: write")
                        })
                        put("offset", buildJsonObject {
                            put("type", "integer")
                            put("description", "Starting line (1-indexed). Used by: read (default: 1)")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max lines to read. Used by: read (default: 2000)")
                        })
                        put("dir", buildJsonObject {
                            put("type", "string")
                            put("description", "Directory to list. Used by: list (default: ${defaultDir})")
                        })
                        // search params
                        put("mode", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("name"); add("content") })
                            put("description", "Search mode. Used by: search (default: name)")
                        })
                        put("pattern", buildJsonObject {
                            put("type", "string")
                            put("description", "Search pattern (glob for name mode, text/regex for content mode). Used by: search")
                        })
                        put("root", buildJsonObject {
                            put("type", "string")
                            put("description", "Directory to search under. Used by: search")
                        })
                        put("max_results", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max results. Used by: search (default: 20, max: 100)")
                        })
                        put("old_string", buildJsonObject {
                            put("type", "string")
                            put("description", "Exact text to find and replace. Used by: patch. Must be unique in file (set replace_all=true for all occurrences).")
                        })
                        put("new_string", buildJsonObject {
                            put("type", "string")
                            put("description", "Replacement text. Used by: patch (default: empty string = delete matched text)")
                        })
                        put("replace_all", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Replace all occurrences instead of requiring unique match. Used by: patch (default: false)")
                        })

                        put("edits", buildJsonObject {
                            put("type", "array")
                            put("description", "Several non-overlapping replacements applied to the ORIGINAL file in ONE call — prefer this over firing two patches in parallel (both read the same snapshot and one clobbers the other). Each item: {old_string, new_string}. Each old_string must match uniquely and must not overlap the others; merge nearby changes into a single item. When present, old_string/new_string are ignored.")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("old_string", buildJsonObject { put("type", "string") })
                                    put("new_string", buildJsonObject { put("type", "string") })
                                })
                            })
                        })
                        put("type_filter", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("all"); add("file"); add("dir") })
                            put("description", "Filter by type. Used by: search mode=name")
                        })
                        put("file_pattern", buildJsonObject {
                            put("type", "string")
                            put("description", "Only search files matching this glob. Used by: search mode=content")
                        })
                        put("use_regex", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Use regex for pattern. Used by: search mode=content")
                        })
                        put("context", buildJsonObject {
                            put("type", "integer")
                            put("description", "Context lines before/after match. Used by: search mode=content (default: 2)")
                        })
                    },
                    required = listOf("action"),
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: error("action required")

                when (action) {
                    "read" -> {
                        val path = obj["path"]?.jsonPrimitive?.content ?: error("path required")
                        val file = resolveFile(path)
                        if (!file.exists()) error("File not found: $path")
                        if (!file.canRead()) error("Cannot read file: $path")
                        if (file.isDirectory) {
                            val listing = file.listFiles()?.map { f ->
                                val icon = if (f.isDirectory) "📁" else "📄"
                                val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                                "$icon ${f.name}$size"
                            }?.joinToString("\n") ?: "(empty)"
                            listOf(UIMessagePart.Text("[${file.absolutePath}] 目录内容:\n$listing"))
                        } else {
                            val offset = obj["offset"]?.jsonPrimitive?.intOrNull ?: 1
                            val limit = obj["limit"]?.jsonPrimitive?.intOrNull ?: READ_MAX_LINES
                            listOf(UIMessagePart.Text(readFileWindow(file, offset, limit)))
                        }
                    }
                    "write" -> {
                        val rawPath = obj["path"]?.jsonPrimitive?.content ?: error("path required")
                        val content = obj["content"]?.jsonPrimitive?.content ?: error("content required")
                        val path = resolveDestPath(rawPath)
                        path.parentFile?.mkdirs()
                        withFileLock(path) { path.writeText(content) }
                        listOf(UIMessagePart.Text("OK: wrote ${content.length} bytes to ${path.absolutePath}"))
                    }
                    "list" -> {
                        val dirPath = obj["dir"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: defaultDir
                        val dir = resolveFile(dirPath)
                        if (!dir.exists()) error("Directory not found: $dirPath")
                        if (!dir.isDirectory) error("Not a directory: $dirPath")
                        val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
                        val listing = buildString {
                            appendLine("Contents of ${dir.absolutePath} (${entries.size} items):")
                            appendLine()
                            for (f in entries) {
                                val prefix = if (f.isDirectory) "📁" else "📄"
                                val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                                appendLine("$prefix ${f.name}$size")
                            }
                        }
                        listOf(UIMessagePart.Text(listing))
                    }
                    "copy" -> {
                        val source = obj["source"]?.jsonPrimitive?.content ?: error("source required")
                        val dest = obj["destination"]?.jsonPrimitive?.content ?: error("destination required")
                        val srcFile = resolveFile(source)
                        if (!srcFile.exists()) error("Source not found: $source")
                        val dstFile = resolveDestPath(dest)
                        dstFile.parentFile?.mkdirs()
                        if (srcFile.isDirectory) {
                            srcFile.copyRecursively(dstFile, overwrite = true)
                        } else {
                            srcFile.copyTo(dstFile, overwrite = true)
                        }
                        listOf(UIMessagePart.Text("OK: copied $source → $dest"))
                    }
                    "move" -> {
                        val source = obj["source"]?.jsonPrimitive?.content ?: error("source required")
                        val dest = obj["destination"]?.jsonPrimitive?.content ?: error("destination required")
                        val srcFile = resolveFile(source)
                        if (!srcFile.exists()) error("Source not found: $source")
                        val dstFile = resolveDestPath(dest)
                        dstFile.parentFile?.mkdirs()
                        if (!srcFile.renameTo(dstFile)) {
                            if (srcFile.isDirectory) {
                                srcFile.copyRecursively(dstFile, overwrite = true)
                                srcFile.deleteRecursively()
                            } else {
                                srcFile.copyTo(dstFile, overwrite = true)
                                srcFile.delete()
                            }
                        }
                        listOf(UIMessagePart.Text("OK: moved $source → $dest"))
                    }
                    "mkdir" -> {
                        val path = obj["path"]?.jsonPrimitive?.content ?: error("path required")
                        val dir = resolveDestPath(path)
                        if (dir.exists() && dir.isDirectory) {
                            listOf(UIMessagePart.Text("Directory already exists: $path"))
                        } else {
                            val created = dir.mkdirs()
                            if (created) listOf(UIMessagePart.Text("OK: created directory $path"))
                            else error("Failed to create directory: $path")
                        }
                    }
                    "delete" -> {
                        val path = obj["path"]?.jsonPrimitive?.content ?: error("path required")
                        val file = resolveFile(path)
                        if (!file.exists()) error("File not found: $path")
                        if (!file.canWrite()) error("Cannot delete (no write permission): $path")
                        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                        if (deleted) {
                            listOf(UIMessagePart.Text("OK: deleted ${if (file.isDirectory) "directory" else "file"} $path"))
                        } else error("Failed to delete: $path")
                    }
                    "patch" -> {
                        val path = obj["path"]?.jsonPrimitive?.content ?: error("path required")
                        val replaceAll = obj["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                        val edits = parseTextEdits(obj)
                        if (replaceAll && edits.size > 1) {
                            error("replace_all applies only to the single old_string/new_string form; use edits[] for several changes")
                        }
                        val file = resolveFile(path)
                        if (!file.exists()) error("File not found: $path")
                        val result = withFileLock(file) {
                            val r = applyTextEdits(file.readText(), edits, replaceAll)
                            file.writeText(r.text)
                            r
                        }
                        val where = if (result.lines.isEmpty()) "" else " (line ${result.lines.joinToString(", ")})"
                        listOf(UIMessagePart.Text("Patched $path: ${result.count} replacement(s)$where"))
                    }
                    "search" -> {
                        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "name"
                        val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull ?: error("pattern required")
                        val root = obj["root"]?.jsonPrimitive?.contentOrNull ?: defaultDir
                        val maxResults = (obj["max_results"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
                        val rootDir = File(root)
                        if (!rootDir.exists()) error("Directory not found: $root")
                        if (!rootDir.isDirectory) error("Not a directory: $root")

                        if (mode == "name") {
                            val typeFilter = obj["type_filter"]?.jsonPrimitive?.contentOrNull ?: "all"
                            val regex = pattern.toGlobRegex()
                            val results = mutableListOf<String>()
                            rootDir.walkTopDown().forEach { f ->
                                if (results.size >= maxResults) return@forEach
                                if (typeFilter == "file" && f.isDirectory) return@forEach
                                if (typeFilter == "dir" && f.isFile) return@forEach
                                if (regex.matches(f.name)) {
                                    val icon = if (f.isDirectory) "📁" else "📄"
                                    val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                                    results.add("$icon ${f.absolutePath}$size")
                                }
                            }
                            if (results.isEmpty()) {
                                listOf(UIMessagePart.Text("No files matching '$pattern' found under $root"))
                            } else {
                                listOf(UIMessagePart.Text("Found ${results.size} result(s) for '$pattern':\n${results.joinToString("\n")}"))
                            }
                        } else {
                            val fileGlob = obj["file_pattern"]?.jsonPrimitive?.contentOrNull ?: ""
                            val useRegex = obj["use_regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                            val contextLines = (obj["context"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(0, 10)
                            val cMaxResults = maxResults.coerceAtMost(200)
                            val searchRegex = if (useRegex) {
                                try { Regex(pattern, setOf(RegexOption.IGNORE_CASE)) }
                                catch (e: Exception) { error("Invalid regex: ${e.message}") }
                            } else {
                                Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
                            }
                            val fileFilter = if (fileGlob.isNotBlank()) fileGlob.toGlobRegexForFile() else null
                            val results = mutableListOf<String>()
                            val sb = StringBuilder()
                            try {
                                rootDir.walkTopDown()
                                    .filter { it.isFile && it.length() > 0 && it.length() <= 512_000 }
                                    .filter { f -> fileFilter?.matches(f.name) ?: true }
                                    .forEach { file ->
                                        if (results.size >= cMaxResults) return@forEach
                                        try {
                                            val lines = file.readLines()
                                            var matchCount = 0
                                            lines.forEachIndexed { lineNum, line ->
                                                if (matchCount >= 5 || results.size >= cMaxResults) return@forEachIndexed
                                                if (searchRegex.containsMatchIn(line)) {
                                                    matchCount++
                                                    results.add("${file.absolutePath}:${lineNum + 1}")
                                                    for (c in (lineNum - contextLines).coerceAtLeast(0) until lineNum) {
                                                        sb.appendLine("  ${c + 1}: ${lines[c].take(120)}")
                                                    }
                                                    sb.appendLine("→ ${lineNum + 1}: ${line.take(120)}")
                                                    for (c in (lineNum + 1)..(lineNum + contextLines).coerceAtMost(lines.size - 1)) {
                                                        sb.appendLine("  ${c + 1}: ${lines[c].take(120)}")
                                                    }
                                                    sb.appendLine()
                                                }
                                            }
                                        } catch (_: Exception) { }
                                    }
                            } catch (_: Exception) { }
                            if (results.isEmpty()) {
                                listOf(UIMessagePart.Text("No matches found for '$pattern' under $root${if (fileGlob.isNotBlank()) " in files matching '$fileGlob'" else ""}"))
                            } else {
                                listOf(UIMessagePart.Text("Found ${results.size} match(es) for '$pattern'${if (fileGlob.isNotBlank()) " in $fileGlob files" else ""}:\n${sb.toString().take(15000)}"))
                            }
                        }
                    }
                    else -> error("Unknown action: $action")
                }
            },
        ),
    )
}

private const val READ_MAX_LINES = 2000
private const val READ_MAX_BYTES = 128 * 1024
private const val READ_MAX_FILE_BYTES = 16L * 1024 * 1024

/**
 * 读一段文件：行号前缀 + **行/字节双限**，截断时把「下一步怎么读」直接写进输出。
 * 抄自 pi-mono 的 read 工具 —— 模型不该为了拿下一段再猜一次；单行本身超限时给的是能直接跑的命令。
 */
private fun readFileWindow(file: File, offsetArg: Int, limitArg: Int): String {
    if (file.length() > READ_MAX_FILE_BYTES) {
        error(
            "File is ${formatSize(file.length())} (> ${formatSize(READ_MAX_FILE_BYTES)}), too big to page through here — " +
                "slice it with env_exec: sed -n 'a,bp' <path>  /  head -c $READ_MAX_BYTES <path>"
        )
    }
    val lines = file.readLines()
    val total = lines.size
    if (total == 0) return "(empty file)"
    val offset = offsetArg.coerceAtLeast(1)
    if (offset > total) error("offset $offset is beyond end of file ($total lines)")
    val start = offset - 1
    val limit = limitArg.coerceIn(1, READ_MAX_LINES)
    val endIdx = (start + limit).coerceAtMost(total)

    val sb = StringBuilder()
    var bytes = 0L
    var shownTo = start
    for (i in start until endIdx) {
        val lineBytes = lines[i].toByteArray(Charsets.UTF_8).size + 1
        if (bytes + lineBytes > READ_MAX_BYTES) {
            if (i == start) {
                val one = lines[i].toByteArray(Charsets.UTF_8).size.toLong()
                return "[Line ${i + 1} is ${formatSize(one)}, exceeds the ${formatSize(READ_MAX_BYTES.toLong())} limit. " +
                    "Use: env_exec with `sed -n '${i + 1}p' <path> | head -c $READ_MAX_BYTES`]"
            }
            break
        }
        sb.append(i + 1).append('|').append(lines[i]).append('\n')
        bytes += lineBytes
        shownTo = i + 1
    }
    val byLineLimit = shownTo - start >= limit
    if (shownTo < total) {
        val why = if (byLineLimit) "" else " (${formatSize(READ_MAX_BYTES.toLong())} limit)"
        sb.append("\n[Showing lines $offset-$shownTo of $total$why. Use offset=${shownTo + 1} to continue.]")
    }
    return sb.toString().trimEnd('\n')
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun String.toGlobRegex(): Regex {
    val pattern = Regex.escape(this)
        .replace("\\*", ".*")
        .replace("\\?", ".")
    return Regex("^${pattern}$", RegexOption.IGNORE_CASE)
}

private fun String.toGlobRegexForFile(): Regex {
    val pattern = Regex.escape(this)
        .replace("\\*", ".*")
        .replace("\\?", ".")
    return Regex("^${pattern}$", RegexOption.IGNORE_CASE)
}
