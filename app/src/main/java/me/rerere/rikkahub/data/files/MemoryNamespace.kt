package me.rerere.rikkahub.data.files

import android.content.Context
import me.rerere.rikkahub.data.model.Assistant
import java.io.File

/**
 * 长期记忆命名空间 —— 每张助手卡一个目录。
 *
 * 目录名 = 助手当前名字（人可读，方便在文件管理器里找）；
 * 归属靠目录里的 .id 认领：助手改名后仍能靠 id 找回旧目录，
 * 并自动把目录迁移成新名字，记忆不丢；重名卡自动追加序号。
 */
object MemoryNamespace {

    private const val SKILLS_DIR = "skills"
    private const val MEM_ROOT = "memory/memories"
    private const val ID_FILE = ".id"

    /** 记忆根目录：与 SkillManager 同源（external 优先，文件管理器可直接看） */
    fun memoriesRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = base.resolve(SKILLS_DIR).resolve(MEM_ROOT)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 解析该助手的记忆命名空间（目录不存在则建），返回目录名 */
    fun resolve(context: Context, assistant: Assistant): String {
        val root = memoriesRoot(context)
        val id = assistant.id.toString()
        val want = sanitize(assistant.name).ifBlank { id.take(8) }

        // 1) 按 id 认领 —— 改名也能找回旧目录，顺手迁移成新名字
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory || ownerOf(dir) != id) continue
            if (dir.name == want) return want
            val dest = File(root, want)
            if (!dest.exists() && dir.renameTo(dest)) {
                runCatching { File(dest, ID_FILE).writeText(id) }
                return want
            }
            return dir.name
        }

        // 2) 首次：以卡名建目录；被别的卡占了就追加序号
        var dir = File(root, want)
        var n = 2
        while (n <= 50 && dir.exists() && ownerOf(dir).isNotEmpty() && ownerOf(dir) != id) {
            dir = File(root, "$want-$n")
            n++
        }
        // 兜底：同名过多时改用 id 短码，绝不抢占别人的目录
        if (dir.exists() && ownerOf(dir).isNotEmpty() && ownerOf(dir) != id) {
            dir = File(root, "$want-${id.take(8)}")
        }
        if (!dir.exists()) dir.mkdirs()
        runCatching { File(dir, ID_FILE).writeText(id) }
        return dir.name
    }

    /** 命名空间目录的绝对路径（写进提示词给模型用） */
    fun dirPath(context: Context, ns: String): String =
        File(memoriesRoot(context), ns).absolutePath

    private fun ownerOf(dir: File): String = runCatching {
        val f = File(dir, ID_FILE)
        if (f.isFile) f.readText().trim() else ""
    }.getOrDefault("")

    /** 目录名净化：只留字母数字与 -_，其余转 _，最长 40 字 */
    private fun sanitize(raw: String): String {
        val out = StringBuilder()
        for (c in raw) {
            if (c.isLetterOrDigit() || c == '-' || c == '_') out.append(c) else out.append('_')
        }
        return out.toString().trim('_', '.').take(40)
    }
}
