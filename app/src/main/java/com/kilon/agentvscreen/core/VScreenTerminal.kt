package com.kilon.agentvscreen.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 终端：跑 shell 命令并记下全文。
 *
 * 命令以 root（uid 0）跑：每次单独起 `su -c`（KernelSU），不走 Shizuku，
 * 和虚拟屏里别的操作不是同一条路。第一次跑时手机可能弹一次 KernelSU
 * 的 root 授权，点允许就行。[run] 会等命令自己结束，不要在主线程调。
 * 每条命令跑之前都会弹一次浮窗（终端页），让用户看得到在干嘛——就算
 * 没有副屏、就算用户刚用 × 关了浮窗，下一条命令来了一样弹出来。
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
        /** 0 = 成功；124 = 超时（已终止）；126 = root 没就绪（su 起不来或被拒）。 */
        val exitCode: Int,
        val durationMs: Long,
    ) {
        val timedOut: Boolean get() = exitCode == 124
        val notReady: Boolean get() = exitCode == 126 && stderr.startsWith("root not ready")
    }

    /** 最多留几条记录。 */
    const val MAX_ENTRIES = 50
    /** 单条 stdout / stderr 最多留多少字（安全阀；正常命令远到不了）。 */
    const val MAX_OUTPUT_CHARS = 256 * 1024

    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 120_000L

    /** Test seam：单测换成假的执行器。 */
    @Volatile var executor: (String, Long) -> ShellResult = { command, timeoutMs ->
        runAsRoot(command, timeoutMs)
    }

    /**
     * 以 root 跑一条命令：每次单独起 `su -c`，不走 Shizuku（KernelSU root，
     * uid 0）。stdout/stderr 各用一个线程同时读，避免任一满了就卡死；
     * 超时到了就杀进程、返回 124；`su` 起不来或被拒时返回 126（stderr
     * 以 "root not ready" 开头），不静默降级回 Shizuku。
     */
    private fun runAsRoot(command: String, timeoutMs: Long): ShellResult {
        val process = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: Exception) {
            return ShellResult(126, "", "root not ready: ${e.javaClass.simpleName}: ${e.message}")
        }
        val outData = java.io.ByteArrayOutputStream()
        val errData = java.io.ByteArrayOutputStream()
        val outThread = drainThread(process.inputStream, outData)
        val errThread = drainThread(process.errorStream, errData)
        val finished = try {
            process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            outThread.join(500); errThread.join(500)
            return ShellResult(124, outData.toString(Charsets.UTF_8), errData.toString(Charsets.UTF_8))
        }
        outThread.join(1000); errThread.join(1000)
        val code = process.exitValue()
        val stdout = outData.toString(Charsets.UTF_8)
        val stderr = errData.toString(Charsets.UTF_8)
        // root 被拒：su 没跑成命令（KernelSU/Magisk 拒绝时退出非 0，stderr 里写 Permission denied）。
        if (code != 0 && stdout.isEmpty() && stderr.contains("denied", ignoreCase = true)) {
            return ShellResult(126, stdout, "root not ready: $stderr")
        }
        return ShellResult(code, stdout, stderr)
    }

    /**
     * 把 [input] 读进 [sink]。只存前面 MAX_OUTPUT_CHARS + 1 字节，多了的
     * 继续读但丢掉——既不会把内存撑爆，[run] 里的 [cap] 也还能认出被
     * 截断并注明；一直读到底，子进程写满管道时不会卡死。
     */
    private fun drainThread(input: java.io.InputStream, sink: java.io.ByteArrayOutputStream): Thread {
        val limit = MAX_OUTPUT_CHARS + 1
        return Thread {
            // r42: 读流时另一条线程关了流（命令跑完、超时被杀、进程被销毁都会关流），
            // read() 会抛 InterruptedIOException——这是正常结束，不是错误。
            // 之前没接这个异常，它一路抛到线程顶、杀死整个 App 进程（前台服务、
            // ApiServer、Tailscale 内网、无障碍全跟着死），就是服务老冻住的根因
            // （dropbox 里的 crash 全是这一个异常）。现在接住当流正常结束，
            // 安静退出线程，不记错、不影响退出码和输出内容。
            try {
                input.use { stream ->
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = try {
                            stream.read(buf)
                        } catch (e: java.io.IOException) {
                            break
                        }
                        if (n < 0) break
                        val room = limit - sink.size()
                        if (room > 0) sink.write(buf, 0, minOf(n, room))
                    }
                }
            } catch (e: java.io.IOException) {
                // 关流时抛也一样，正常结束。
            }
        }.apply { isDaemon = true; start() }
    }

    private val _log = MutableStateFlow<List<Entry>>(emptyList())
    val log: StateFlow<List<Entry>> = _log.asStateFlow()

    private val lock = Any()
    private var nextId = 1L

    /**
     * 持久历史里已有的最大 id 往后接：进程重启后 nextId 从 1 重来，会跟文件里的
     * 旧条目撞号——历史页 LazyColumn 用 id 做 key，撞号就整进程闪退（round33
     * 根因）。只往前、不往后；由 [VScreenTerminalHistory.init] 在服务起来时调。
     */
    fun seedNextId(fromId: Long) {
        synchronized(lock) { if (fromId > nextId) nextId = fromId }
    }

    /** 跑一条命令，记进 [log]，返回这条记录。不要在主线程调。 */
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Entry {
        // Pop the floating window up on the terminal page so the user can see the command run — even
        // with no screen and even after they closed the window with × (the next command pops it again).
        BackgroundOperation.popTerminalWindow()
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
