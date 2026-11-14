package com.kilon.agentvscreen.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 终端命令的持久历史：每次 `vscreen shell` 跑完一条命令，就追加一条。
 *
 * 存在应用内部存储（filesDir/terminal-history.jsonl，一行一条 JSON），
 * 服务重启、手机重启后都在；只有重装会丢（内部存储随应用一起删）。
 * 只留最近 [MAX_ENTRIES] 条，超了删最旧的。命令全文不截断；输出太大时
 * 每路只留前面一部分（浮窗终端页的内存日志不受影响，仍是全文）。
 *
 * 和内存里的 [VScreenTerminal.log] 是两份记录：内存那份只留最近 50 条、
 * 进程死了就没了，悬浮窗“终端”页读它；这份是累积的，全屏页“历史命令”读它。
 * [append]/[clear] 失败都静默，绝不影响命令本身。
 */
object VScreenTerminalHistory {
    /** 最多留几条（再多就 50 条一批删最旧的，摊掉重写文件的开销）。 */
    const val MAX_ENTRIES = 200
    private const val TRIM_SLACK = 50
    /** 历史里每路输出最多留多少字（防文件无限长；命令全文不受影响）。 */
    const val MAX_OUTPUT_CHARS = 64 * 1024
    private const val FILE_NAME = "terminal-history.jsonl"

    private val lock = Any()
    @Volatile private var dir: File? = null
    private var cache: MutableList<VScreenTerminal.Entry>? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (dir == null) dir = context.applicationContext.filesDir
        }
    }

    private fun file(): File? = dir?.let { File(it, FILE_NAME) }

    /** 跑完一条命令后调：追加到历史文件。 */
    fun append(entry: VScreenTerminal.Entry) {
        val f = file() ?: return
        synchronized(lock) {
            runCatching {
                val list = loadLocked()
                list += entry
                f.appendText(toJson(entry) + "\n")
                if (list.size > MAX_ENTRIES + TRIM_SLACK) {
                    while (list.size > MAX_ENTRIES) list.removeAt(0)
                    rewriteLocked(f, list)
                }
            }
        }
    }

    /** 全部历史（最旧的在前）；没初始化或文件读不出来就返回空。 */
    fun load(): List<VScreenTerminal.Entry> {
        if (file() == null) return emptyList()
        synchronized(lock) {
            return runCatching { loadLocked().toList() }.getOrDefault(emptyList())
        }
    }

    /** 清空持久历史（和内存日志的“清空”是同一个动作，见 [VScreenTerminal.clear]）。 */
    fun clear() {
        val f = file() ?: return
        synchronized(lock) {
            cache = mutableListOf()
            runCatching { f.delete() }
        }
    }

    private fun loadLocked(): MutableList<VScreenTerminal.Entry> {
        cache?.let { return it }
        val list = mutableListOf<VScreenTerminal.Entry>()
        val f = file()
        if (f != null && f.exists()) {
            runCatching {
                f.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        runCatching { fromJson(JSONObject(line)) }.getOrNull()?.let { list.add(it) }
                    }
                }
            }
        }
        while (list.size > MAX_ENTRIES) list.removeAt(0)
        cache = list
        return list
    }

    private fun rewriteLocked(f: File, list: List<VScreenTerminal.Entry>) {
        runCatching {
            f.writeText(buildString {
                for (e in list) append(toJson(e)).append('\n')
            })
        }
    }

    private fun toJson(e: VScreenTerminal.Entry): String = JSONObject()
        .put("id", e.id).put("atMs", e.atMs).put("command", e.command)
        .put("stdout", capOutput(e.stdout)).put("stderr", capOutput(e.stderr))
        .put("exitCode", e.exitCode).put("durationMs", e.durationMs).toString()

    private fun fromJson(o: JSONObject): VScreenTerminal.Entry = VScreenTerminal.Entry(
        o.optLong("id"), o.optLong("atMs"), o.optString("command"),
        o.optString("stdout"), o.optString("stderr"),
        o.optInt("exitCode"), o.optLong("durationMs"))

    private fun capOutput(s: String): String =
        if (s.length <= MAX_OUTPUT_CHARS) s
        else s.take(MAX_OUTPUT_CHARS) + "\n…（输出太长，历史里只保留了前面 ${MAX_OUTPUT_CHARS / 1024}KB）"
}
