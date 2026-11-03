package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** 读图上限：再大就别整块塞进模型上下文了（provider 侧还会再压一次）。 */
internal const val MAX_IMAGE_READ_BYTES = 12L * 1024 * 1024

/** 只列 provider 真能消化的位图；svg 是文本，走文本通道。 */
private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
)

internal fun String.looksLikeImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** 按文件头认位图；认不出返回 null。 */
internal fun sniffImageMime(bytes: ByteArray): String? {
    fun ascii(from: Int, to: Int): String =
        String(bytes, from, to - from, Charsets.US_ASCII)

    if (bytes.size >= 12 && ascii(4, 8) == "ftyp") {
        when (ascii(8, 12)) {
            "heic", "heix", "heim", "heis",
            "hevc", "hevx", "hevm", "hevs",
            "mif1", "msf1", "heif",
                -> return "image/heic"
            "avif", "avis" -> return "image/avif"
        }
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
        return "image/jpeg"
    }
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
    ) {
        return "image/png"
    }
    if (bytes.size >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP") {
        return "image/webp"
    }
    if (bytes.size >= 6) {
        val header = ascii(0, 6)
        if (header == "GIF89a" || header == "GIF87a") return "image/gif"
    }
    if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
        return "image/bmp"
    }
    return null
}

/** 只读文件头，避免为了判类型把整张图读进来。 */
internal fun sniffImageMime(file: File): String? =
    runCatching {
        FileInputStream(file).use { input -> sniffImageMime(input) }
    }.getOrNull()

internal fun sniffImageMime(input: InputStream, size: Int = 16): String? {
    val head = ByteArray(size)
    val read = input.read(head).coerceAtLeast(0)
    if (read == 0) return null
    return sniffImageMime(head.copyOf(read))
}

internal fun imageExtensionOf(mime: String): String = when (mime) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/bmp" -> "bmp"
    "image/heic" -> "heic"
    "image/avif" -> "avif"
    else -> "img"
}

/**
 * 把字节存成聊天附件，返回可直接塞进 UIMessagePart 的 file:// URL。
 * provider 发请求时再从本地读、压缩成模型要的 data URL。
 */
internal suspend fun saveImageAsChatPart(
    bytes: ByteArray,
    mime: String,
    originalName: String? = null,
): UIMessagePart.Image = withContext(Dispatchers.IO) {
    val baseName = originalName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
        ?: "image"
    // 用真实 MIME 定扩展名（原文件名可能是 .png 实际是 JPEG）
    val displayName = "${baseName.substringBeforeLast('.', baseName)}.${imageExtensionOf(mime)}"
    val uri = getKoin().get<FilesManager>()
        .createChatFilesByByteArrays(listOf(bytes), displayName, mime)
        .first()
    UIMessagePart.Image(url = uri.toString())
}

internal fun imageReadMeta(
    path: String,
    size: Long,
    mime: String,
    extra: Map<String, String> = emptyMap(),
): UIMessagePart.Text = UIMessagePart.Text(
    buildJsonObject {
        put("path", path)
        put("size", size)
        put("mime", mime)
        put("image", true)
        extra.forEach { (k, v) -> put(k, v) }
    }.toString()
)
