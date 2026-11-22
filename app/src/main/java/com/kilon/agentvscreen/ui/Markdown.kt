package com.kilon.agentvscreen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 1.4.2 (rainbow): release notes shown as Markdown, not as raw text with # and ** in it.
 *
 * A small reader for what GitHub release notes use: headings, paragraphs, - / * / 1. lists (one level of nesting),
 * > quotes, ``` code blocks, --- rules, and inline **bold**, *italic*, `code`, [links](url), bare https links and
 * ~~strike~~. Tables and HTML are shown as plain lines. No library: the parse is pure and unit-tested.
 */
internal object Md {
    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Para(val text: String) : Block()
        data class Item(val marker: String, val text: String, val depth: Int) : Block()
        data class Quote(val text: String) : Block()
        data class Code(val text: String) : Block()
        object Rule : Block()
    }

    fun parse(src: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val t = line.trimStart()
            val indent = line.length - t.length
            when {
                t.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
                    out += Block.Code(code.toString().trimEnd('\n'))
                }
                t.isEmpty() -> flush()
                Regex("^#{1,6}\\s+.*").matches(t) -> { flush(); val h = t.takeWhile { it == '#' }.length; out += Block.Heading(h, t.drop(h).trim().trimEnd('#').trim()) }
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t.replace(" ", "")) -> { flush(); out += Block.Rule }
                Regex("^[-*+]\\s+.*").matches(t) -> { flush(); out += Block.Item("•", t.drop(1).trim().removePrefix("[ ] ").removePrefix("[x] "), if (indent >= 2) 1 else 0) }
                Regex("^\\d{1,3}[.)]\\s+.*").matches(t) -> { flush(); val n = t.takeWhile(Char::isDigit); out += Block.Item("$n.", t.drop(n.length + 1).trim(), if (indent >= 2) 1 else 0) }
                t.startsWith(">") -> { flush(); out += Block.Quote(t.removePrefix(">").trim()) }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    /** Inline marks as spans. Pure apart from the colours passed in. */
    fun inline(text: String, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        val linkStyle = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
        while (i < text.length) {
            val rest = text.substring(i)
            val m = when {
                rest.startsWith("**") || rest.startsWith("__") -> {
                    val mark = rest.take(2); val end = rest.indexOf(mark, 2)
                    if (end > 2) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(rest.substring(2, end), link, codeBg)) }; end + 2 } else 0
                }
                rest.startsWith("~~") -> {
                    val end = rest.indexOf("~~", 2)
                    if (end > 2) { withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(rest.substring(2, end)) }; end + 2 } else 0
                }
                rest.startsWith("`") -> {
                    val end = rest.indexOf('`', 1)
                    if (end > 1) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(rest.substring(1, end)) }; end + 1 } else 0
                }
                (rest.startsWith("*") || rest.startsWith("_")) && rest.length > 2 && !rest[1].isWhitespace() -> {
                    val mark = rest[0]; val end = rest.indexOf(mark, 1)
                    if (end > 1 && !rest[end - 1].isWhitespace()) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(rest.substring(1, end)) }; end + 1 } else 0
                }
                rest.startsWith("[") -> {
                    val close = rest.indexOf("](")
                    val end = if (close > 0) rest.indexOf(')', close) else -1
                    if (close > 0 && end > close) {
                        val url = rest.substring(close + 2, end).trim()
                        withLink(LinkAnnotation.Url(url, linkStyle)) { append(rest.substring(1, close)) }
                        end + 1
                    } else 0
                }
                rest.startsWith("https://") || rest.startsWith("http://") -> {
                    val end = rest.indexOfFirst { it.isWhitespace() || it == ')' || it == '，' || it == '。' }.let { if (it < 0) rest.length else it }
                    val url = rest.substring(0, end).trimEnd('.', ',', ';')
                    withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }
                    url.length
                }
                else -> 0
            }
            if (m > 0) i += m else { append(text[i]); i++ }
        }
    }
}

/** Markdown text (release notes) in the app's own type and colours. */
@Composable
internal fun MarkdownText(src: String, modifier: Modifier = Modifier) {
    val c = MiuixTheme.colorScheme
    val blocks = remember(src) { Md.parse(src) }
    val codeBg = c.surfaceContainerHigh
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is Md.Block.Heading -> androidx.compose.material3.Text(Md.inline(b.text, c.primary, codeBg),
                fontSize = when (b.level) { 1 -> 17.sp; 2 -> 15.5.sp; else -> 14.sp }, fontWeight = FontWeight.SemiBold, color = c.onSurface,
                modifier = Modifier.padding(top = if (b.level <= 2) 6.dp else 2.dp))
            is Md.Block.Para -> androidx.compose.material3.Text(Md.inline(b.text, c.primary, codeBg), fontSize = 13.sp, lineHeight = 19.sp, color = c.onSurface)
            is Md.Block.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                androidx.compose.material3.Text(b.marker, fontSize = 13.sp, lineHeight = 19.sp, color = c.onSurfaceVariantSummary, modifier = Modifier.width(18.dp))
                androidx.compose.material3.Text(Md.inline(b.text, c.primary, codeBg), fontSize = 13.sp, lineHeight = 19.sp, color = c.onSurface)
            }
            is Md.Block.Quote -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(c.primary.copy(alpha = 0.5f)))
                androidx.compose.material3.Text(Md.inline(b.text, c.primary, codeBg), fontSize = 13.sp, lineHeight = 19.sp, color = c.onSurfaceVariantSummary, modifier = Modifier.padding(start = 8.dp))
            }
            is Md.Block.Code -> androidx.compose.material3.Text(b.text, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, color = c.onSurface,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(codeBg).padding(10.dp))
            Md.Block.Rule -> Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(c.onSurfaceVariantSummary.copy(alpha = 0.25f)))
        }
    }
}
