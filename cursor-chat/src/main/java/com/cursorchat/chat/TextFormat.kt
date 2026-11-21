package com.cursorchat.chat

import java.time.Duration
import java.time.Instant

fun relativeTime(iso: String?, now: Instant = Instant.now()): String {
    if (iso.isNullOrBlank()) return ""
    val then = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val seconds = Duration.between(then, now).seconds
    return when {
        seconds < 45 -> "刚刚"
        seconds < 3_600 -> "${seconds / 60} 分钟前"
        seconds < 86_400 -> "${seconds / 3_600} 小时前"
        seconds < 172_800 -> "昨天"
        seconds < 604_800 -> "${seconds / 86_400} 天前"
        else -> iso.take(10)
    }
}

fun repoLabel(url: String): String {
    val trimmed = url.removePrefix("https://").removePrefix("http://").removeSuffix(".git").trim('/')
    val parts = trimmed.split('/').filter { it.isNotBlank() }
    return if (parts.size >= 2) parts.takeLast(2).joinToString("/") else trimmed
}

sealed interface MarkdownBlock {
    data class Text(val text: String) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
}

fun splitBlocks(source: String): List<MarkdownBlock> {
    if (!source.contains("```")) return listOf(MarkdownBlock.Text(source))
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < source.length) {
        val open = source.indexOf("```", index)
        if (open < 0) {
            blocks += MarkdownBlock.Text(source.substring(index))
            break
        }
        if (open > index) blocks += MarkdownBlock.Text(source.substring(index, open))
        val languageStart = open + 3
        val lineBreak = source.indexOf('\n', languageStart)
        val languageEnd = if (lineBreak < 0) source.length else lineBreak
        val language = source.substring(languageStart, languageEnd).trim()
        val contentStart = if (lineBreak < 0) source.length else lineBreak + 1
        val close = source.indexOf("```", contentStart)
        if (close < 0) {
            blocks += MarkdownBlock.Code(language, source.substring(contentStart))
            break
        }
        blocks += MarkdownBlock.Code(language, source.substring(contentStart, close).removeSuffix("\n"))
        index = close + 3
        if (index < source.length && source[index] == '\n') index += 1
    }
    return blocks.filterNot { it is MarkdownBlock.Text && it.text.isEmpty() }
}
