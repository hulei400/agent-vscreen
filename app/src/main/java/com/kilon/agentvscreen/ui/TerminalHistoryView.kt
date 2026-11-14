package com.kilon.agentvscreen.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.VScreenTerminal
import com.kilon.agentvscreen.core.VScreenTerminalHistory
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 收起时先看几行输出（超过这个行数才出现“展开全文”按钮）。 */
private const val PREVIEW_LINES = 3

/**
 * 全屏页的“历史命令”：持久保存的全部历史（最近 200 条，重启后还在），时间倒序、
 * 最新的在最上面。
 *
 * 新命令跑完会自动刷新并跳回最新那条：订阅内存日志，最新一条的 id 一变就重载
 * 历史并 scrollToItem(0)。id 是单调递增的，即使内存日志已满 50 条、size 不变，
 * 也能感知到新命令；清空后 id 变 null，会刷新成空列表。事件驱动，不轮询。
 * 每条：命令全文、跑过的时间、退出码、输出。输出太长时默认只露前面 3 行，
 * 下面有个明确的“展开全文（共 N 行）”按钮，点开后变“收起”；短输出直接全显，
 * 没有按钮。展开状态按条目的 id 记（items 加了 key），新命令插到最上面时
 * 各条的展开/收起不会串位。
 * 长按一条就复制它的命令（跟浮窗终端页一样：长按自带一下短震 + “已复制”Toast，
 * 不要再补一次 performHapticFeedback——round9 的教训，补了会震两下），
 * 点卡片其它地方什么都不做，所以上下滑动翻历史时不会误复制。
 */
@Composable
fun TerminalHistoryPage(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<VScreenTerminal.Entry>?>(null) }
    val listState = rememberLazyListState()
    // 订阅内存日志：run() 里先更新 log、后写历史文件，最新 id 一变就知道来了新命令。
    val liveLog by VScreenTerminal.log.collectAsState()
    val newestId = liveLog.lastOrNull()?.id
    var lastShownNewestId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(newestId) {
        // 留一点余量，等 append 写进历史文件再读（run 里 log 和文件是前后脚）。
        delay(150)
        val fresh = withContext(Dispatchers.IO) { VScreenTerminalHistory.load().asReversed() }
        val freshNewestId = fresh.firstOrNull()?.id
        entries = fresh
        if (freshNewestId != null && freshNewestId != lastShownNewestId) {
            lastShownNewestId = freshNewestId
            listState.scrollToItem(0)
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF07080D)).statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        val list = entries
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("历史命令", "Command history"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            if (list != null) Text(tr("共 ${list.size} 条", "${list.size} entries"), color = Color(0x99FFFFFF), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, tr("关闭", "Close"), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            list == null -> Text(tr("加载中…", "Loading…"), color = Color(0x99FFFFFF), fontSize = 14.sp)
            list.isEmpty() -> Text(
                tr("还没有跑过命令：用 vscreen shell 跑一条命令，它就会出现在这里。",
                    "No commands run yet: run one with vscreen shell and it will show up here."),
                color = Color(0x99FFFFFF), fontSize = 14.sp)
            else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 按 id 记 key：新命令插到最上面时，各条的展开/收起状态不串位。
                itemsIndexed(list, key = { _, e -> e.id }) { _, e -> HistoryEntryItem(e) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryEntryItem(e: VScreenTerminal.Entry) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF14161F))
        .combinedClickable(
            onClick = {},
            onLongClick = {
                // 长按本身带一下短震（框架给的），不要再补 performHapticFeedback，会震两下。
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("command", e.command))
                Toast.makeText(ctx, tr("已复制", "Copied"), Toast.LENGTH_SHORT).show()
            })
        .padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(e.atMs), color = Color(0x99FFFFFF), fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (e.exitCode == 0) tr("✓ 成功", "✓ ok")
                else if (e.timedOut) tr("⏱ 超时 124", "⏱ timed out 124")
                else tr("退出码 ${e.exitCode}", "exit ${e.exitCode}"),
                color = if (e.exitCode == 0) Color(0xFF7ED07E) else Color(0xFFFFB347), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(tr("${e.durationMs}ms", "${e.durationMs} ms"), color = Color(0x88FFFFFF), fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text("$ " + e.command, color = Color(0xFF7CC8FF), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        val out = buildString {
            if (e.stdout.isNotEmpty()) append(e.stdout.trimEnd('\n'))
            if (e.stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(e.stderr.trimEnd('\n'))
            }
        }
        if (out.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            val lines = out.lines()
            val isLong = lines.size > PREVIEW_LINES
            if (isLong && !expanded) {
                Text(lines.take(PREVIEW_LINES).joinToString("\n") + "\n…",
                    color = Color(0xFFE8E8E8), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            } else {
                Text(out, color = Color(0xFFE8E8E8), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            if (isLong) {
                // 明确的展开/收起按钮，不要只靠点输出文本那种隐式手势。
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x22FFFFFF))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (expanded) tr("收起 ▴", "Collapse ▴")
                        else tr("展开全文（共 ${lines.size} 行） ▾", "Show all (${lines.size} lines) ▾"),
                        color = Color(0xFF7CC8FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
