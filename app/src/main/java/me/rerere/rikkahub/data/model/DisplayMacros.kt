package me.rerere.rikkahub.data.model

/**
 * 显示侧的身份宏替换。
 *
 * 背景：`{{user}}` / `{{char}}` 在**发送侧**由 PlaceholderTransformer 的完整宏引擎换掉了，
 * 但显示侧一直没有任何人处理 —— 所以气泡里会直接露出裸的 `{{user}}`。
 * 酒馆里这两个宏在显示时就是展开的，这里补齐。
 *
 * 为什么不直接复用完整宏引擎：它会执行 `setvar` / `incvar` / `setglobalvar` 这类**有副作用的宏**，
 * 渲染一屏消息就改一次变量是不能接受的。所以这里只做纯文本替换，不解析、不求值。
 */
private val IDENTITY_MACRO_REGEX = Regex("\\{\\{\\s*(user|char)\\s*\\}\\}", RegexOption.IGNORE_CASE)

/**
 * 把 `{{user}}` / `{{char}}`（大小写不敏感，允许大括号内有空格）替换成实际名字。
 * 其他一切宏原样保留。
 */
fun String.replaceIdentityMacros(userName: String, charName: String): String {
    if (!contains("{{")) return this
    return IDENTITY_MACRO_REGEX.replace(this) { match ->
        if (match.groupValues[1].equals("user", ignoreCase = true)) userName else charName
    }
}
