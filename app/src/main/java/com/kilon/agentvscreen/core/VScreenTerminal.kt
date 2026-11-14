package com.kilon.agentvscreen.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 终端：跑 shell 命令并记下全文。
 *
 * 命令走 Shizuku（uid 2000，不是 root），和虚拟屏里别的操作同一条路
 * （[VScreenShell.run] → ShizukuManager.runProcess）。[run] 会等命令自己
 * 结束，不要在主线程调。
 *
 * 记录分两份：内存里的 [log] 只留最近 50 条、进程死了就没了，悬浮窗的
 * “终端”页读它；另外 [VScreenTerminalHistory] 把每条跑完的命令持久存到
 * 应用内部存储（最近 200 条，重启后还在），全屏页的“历史命令”读它。
 * 命令全文、stdout、stderr 都不截断。单条输出超过上限时才留
 * 前面一部分并注明——那是防内存炸掉的安全阀，正常命令碰不到。
 */
object VScreenTerminal {
    /** 一次跑的结果（纯数据，方便单测）。 */
    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

    /** 一条终端记录。 */
    data class Entry(
        val id: Long,
        val atMs: Long,
        /** 命令全文，不截断。 */
        val command: String,
        val stdout: String,
        val stderr: String,
        /** 0 = 成功；124 = 超时（已终止）；126 = Shizuku 没就绪。 */
        val exitCode: Int,
        val durationMs: Long,
    ) {
        val timedOut: Boolean get() = exitCode == 124
        val notReady: Boolean get() = exitCode == 126 && stderr.startsWith("shizuku not ready")
    }

    /** 最多留几条记录。 */
    const val MAX_ENTRIES = 50
    /** 单条 stdout / stderr 最多留多少字（安全阀；正常命令远到不了）。 */
    const val MAX_OUTPUT_CHARS = 256 * 1024

    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 120_000L

    /** Test seam：单测换成假的执行器。 */
    @Volatile var executor: (String, Long) -> ShellResult = { command, timeoutMs ->
        val r = VScreenShell.run(arrayOf("sh", "-c", command), timeoutMs)
        ShellResult(r.exitCode, r.stdout, r.stderr)
    }

    private val _log = MutableStateFlow<List<Entry>>(emptyList())
    val log: StateFlow<List<Entry>> = _log.asStateFlow()

    private val lock = Any()
    private var nextId = 1L

    /** 跑一条命令，记进 [log]，返回这条记录。不要在主线程调。 */
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Entry {
        val t0 = System.currentTimeMillis()
        val r = runCatching { executor(command, timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)) }
            .getOrElse { ShellResult(1, "", "terminal: ${it.javaClass.simpleName}: ${it.message}") }
        val entry = synchronized(lock) {
            val e = Entry(nextId++, System.currentTimeMillis(), command,
                cap(r.stdout), cap(r.stderr), r.exitCode,
                System.currentTimeMillis() - t0)
            _log.value = (_log.value + e).takeLast(MAX_ENTRIES)
            e
        }
        runCatching { VScreenTerminalHistory.append(entry) }
        return entry
    }

    private fun cap(s: String): String =
        if (s.length <= MAX_OUTPUT_CHARS) s
        else s.take(MAX_OUTPUT_CHARS) + "\n…（输出超过 ${MAX_OUTPUT_CHARS / 1024}KB，只保留了前面部分）"

    fun clear() {
        synchronized(lock) { _log.value = emptyList() }
        runCatching { VScreenTerminalHistory.clear() }
    }

    /** 跑过的命令（最近的在前，相邻重复的只留一次）。 */
    fun history(): List<String> {
        val out = ArrayList<String>()
        for (e in _log.value.asReversed()) {
            if (out.lastOrNull() != e.command) out += e.command
        }
        return out
    }
}
