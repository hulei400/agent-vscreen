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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏页的“历史命令”：持久保存的全部历史（最近 200 条，重启后还在），最新的在最上面。
 *
 * 每条：命令全文、跑过的时间、退出码、输出（先看前面 8 行，点一下展开看全文）。
 * 长按一条就复制它的命令（跟浮窗终端页一样：长按自带一下短震 + “已复制”Toast，
 * 不要再补一次 performHapticFeedback——round9 的教训，补了会震两下），
 * 点一下什么都不做，所以上下滑动翻历史时不会误复制。
 */
@Composable
fun TerminalHistoryPage(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<VScreenTerminal.Entry>?>(null) }
    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) { VScreenTerminalHistory.load().asReversed() }
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
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(list) { _, e -> HistoryEntryItem(e) }
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
            if (expanded) {
                Text(out, color = Color(0xFFE8E8E8), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(tr("点一下收起 ↑", "Tap to collapse ↑"), color = Color(0x88FFFFFF), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp).clickable { expanded = false })
            } else {
                val lines = out.lines()
                val preview = if (lines.size > 8) lines.take(8).joinToString("\n") + "\n…（共 ${lines.size} 行，点一下看全文）"
                    else out
                Text(preview, color = Color(0xFFE8E8E8), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.clickable { if (lines.size > 8) expanded = true })
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
