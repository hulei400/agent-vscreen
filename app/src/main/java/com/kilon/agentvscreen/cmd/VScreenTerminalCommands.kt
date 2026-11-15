package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.core.VScreenTerminal
import com.kilon.agentvscreen.tr
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `shell`：终端命令。结果同时进终端记录，悬浮窗的“终端”页能看到。 */
internal fun VScreenOffloadHandler.shellCmd(
    @Suppress("UNUSED_PARAMETER") sid: String?,
    args: OffloadArgs,
): NativeOffloadResult {
    if (args.hasFlag("clear")) {
        VScreenTerminal.clear()
        return text(tr("终端记录已清空", "Terminal log cleared"))
    }
    if (args.hasFlag("history")) {
        val h = VScreenTerminal.history()
        return text(if (h.isEmpty()) tr("终端还没有跑过命令", "No commands run in the terminal yet")
        else tr("跑过的命令（最近的在前）：", "Commands run (most recent first):") + "\n" +
            h.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n"))
    }
    val command = args.positional.joinToString(" ").trim()
    if (command.isEmpty()) return text(logText())
    val timeoutMs = (args.get("timeout")?.toLongOrNull() ?: VScreenTerminal.DEFAULT_TIMEOUT_MS)
        .coerceIn(1_000L, VScreenTerminal.MAX_TIMEOUT_MS)
    val e = VScreenTerminal.run(command, timeoutMs)
    if (e.notReady) return err("NOT_CONNECTED",
        tr("root 没就绪，命令没有跑：${e.stderr}。请用户打开 Agent-VScreen，点允许 KernelSU 的 root 授权。",
            "Root is not ready, the command did not run: ${e.stderr}. Ask the user to open Agent-VScreen and allow the KernelSU root authorization."))
    // 跑完的时间（MM-dd HH:mm:ss，和历史页 formatTime 同格式），不再显示耗时毫秒。
    // 注：本文件 logText 里已有同名 fmt（HH:mm:ss），这里另起一个，两个各用各的。
    val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    val body = buildString {
        append("$ ").append(e.command).append('\n')
        if (e.stdout.isNotEmpty()) append(e.stdout.trimEnd('\n')).append('\n')
        if (e.stderr.isNotEmpty()) append(e.stderr.trimEnd('\n')).append('\n')
        if (e.exitCode != 0) {
            if (e.timedOut) append(tr("⏱ 超时：${timeoutMs / 1000} 秒内没跑完，已终止（退出码 124）", "⏱ timed out: did not finish in ${timeoutMs / 1000} s, killed (exit code 124)"))
            else append(tr("退出码 ${e.exitCode}（${fmt.format(Date(e.atMs))}）", "exit code ${e.exitCode} (${fmt.format(Date(e.atMs))})"))
            append('\n')
        }
    }
    val data = JSONObject()
        .put("command", e.command).put("exit_code", e.exitCode)
        .put("stdout", e.stdout).put("stderr", e.stderr)
        .put("duration_ms", e.durationMs).put("timed_out", e.timedOut)
    return if (e.exitCode == 0) NativeOffloadResult(0, body, "OK", JSONObject().put("result", data))
    else NativeOffloadResult(1, body, "OPERATION_FAILED", JSONObject().put("result", data))
}

/** 最近的终端记录（命令全文；输出只看前面一部分，全文在悬浮窗“终端”页）。 */
private fun logText(): String {
    val log = VScreenTerminal.log.value
    if (log.isEmpty()) return tr("终端还没有跑过命令", "No commands run in the terminal yet")
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val tail = log.takeLast(10)
    return buildString {
        append(tr("终端记录（共 ${log.size} 条，最近 ${tail.size} 条）：", "Terminal log (${log.size} entries, latest ${tail.size}):")).append('\n')
        for (e in tail) {
            append("[${fmt.format(Date(e.atMs))}] $ ${e.command} → ${tr("退出", "exit")} ${e.exitCode}\n")
            val out = (e.stdout.ifEmpty { e.stderr }).trim()
            if (out.isNotEmpty()) {
                val preview = if (out.length > 300) out.take(300) + "…（共 ${out.length} 字，全文见悬浮窗）" else out
                append(preview).append('\n')
            }
        }
    }.trimEnd()
}
