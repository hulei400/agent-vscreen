package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

/**
 * 1.2: everything that depends on who made the phone sits behind this one interface (before, `when (vendor)` blocks
 * were spread over the shell, the power and the downscale code). A new maker is one more object here; the rest of the
 * app asks [Vendor.current] and never looks at the brand itself.
 */
internal interface Vendor {
    /** Where the user turns off the system's resolution downscaling. */
    fun downscaleHint(): String = tr("在系统 设置 › 显示 里把屏幕分辨率调到最高档，并关闭系统/游戏助手对该应用的降分辨率", "In Settings › Display set the screen resolution to the highest, and turn off downscaling of this app by the system / game assistant")
    /** Things that commonly break background operation on this maker's system, for status. */
    fun hints(): List<String> = emptyList()
    /**
     * What turns off this maker's limit on adb when Shizuku says "adb permission limited" (Shizuku's own setup guide:
     * shizuku.rikka.app/guide/setup). Under that limit Shizuku cannot grant any app, so its "Allow" never shows.
     */
    fun adbLimitedFix(): String = tr("在“开发者选项”里找和 USB 调试、权限监控有关的安全开关并按 Shizuku 文档调整（https://shizuku.rikka.app/zh-hans/guide/setup/）", "In Developer options find the switch about USB debugging security or permission monitoring and set it as Shizuku's guide says (https://shizuku.rikka.app/guide/setup/)")
    /** The system property that names this maker's OS version. */
    fun osName(prop: (String) -> String): String = ""

    /** Phone maker and system. */
    data class Device(val vendor: String, val brand: String, val model: String, val os: String, val sdk: Int)

    private object Generic : Vendor

    private object Vivo : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.vivo.os.build.display.id").ifEmpty { "OriginOS/FuntouchOS " + prop("ro.vivo.os.version") }
        override fun downscaleHint() = tr("vivo/iQOO：设置 › 显示与亮度 › 屏幕分辨率 选最高档（关闭“智能切换”）；游戏还要在 游戏魔盒/电竞模式 里对该游戏关闭“超分辨率/插帧/画质增强”", "vivo/iQOO: Settings › Display and brightness › Screen resolution: choose the highest (turn off \"Smart switch\"); for games also turn off \"super resolution / frame interpolation / picture enhancement\" for that game in Game Cube / e-sports mode")
        override fun hints() = listOf(
            tr("Shizuku 下点击无效或 wm/settings 命令失败时：有的版本开发者选项里有“禁止权限监控”，可以试着打开（Shizuku 文档没有列出 vivo，未验证）", "If taps do nothing or wm/settings commands fail under Shizuku: some versions have \"Disable permission monitoring\" in Developer options; try turning it on (Shizuku's guide does not list vivo; unverified)"),
            tr("i管家 › 应用管理 › 权限管理 › 自启动 允许 Agent-VScreen 和 Shizuku；设置 › 电池 › 后台耗电管理 选“允许后台高耗电”，否则后台副屏会被杀", "i Manager › App manager › Permissions › Autostart: allow Agent-VScreen and Shizuku; Settings › Battery › Background power consumption: choose \"Allow high background power use\", or the background screen gets killed"),
            tr("锁屏久了 OriginOS 可能停掉 Shizuku：status 报 not_connected 时用 fix 重连", "After a long lock OriginOS may stop Shizuku: when status reports not_connected, reconnect with fix"),
            tr("iQOO 电竞模式/游戏魔盒会拦截通知和悬浮窗：在副屏跑游戏时不要对该游戏开“免打扰/极速模式”", "iQOO e-sports mode / Game Cube blocks notifications and floating windows: don't turn on \"Do not disturb / turbo mode\" for a game running on the screen"),
        )
    }

    private object Honor : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.build.version.magic").ifEmpty { prop("ro.build.version.emui") }
        override fun downscaleHint() = tr("荣耀/华为：设置 › 显示和亮度 › 屏幕分辨率 关闭“智能分辨率”或选最高档（这是整机设置；能否去掉某个应用的降分辨率比例未经实测）", "Honor/Huawei: Settings › Display and brightness › Screen resolution: turn off \"Smart resolution\" or choose the highest (a phone-wide setting; untested whether it removes one app's downscale ratio)")
        override fun hints() = listOf(tr("设置 › 应用和服务 › 应用启动管理 把 Agent-VScreen 和 Shizuku 设为手动管理并全部允许", "Settings › Apps & services › App launch: set Agent-VScreen and Shizuku to manual management and allow everything"))
    }

    private object Xiaomi : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.mi.os.version.name").ifEmpty { "MIUI " + prop("ro.miui.ui.version.name") }
        override fun adbLimitedFix() = tr("小米/POCO：“开发者选项”里打开“USB 调试（安全设置）”（和“USB 调试”是两个开关）", "Xiaomi/POCO: in Developer options turn on \"USB debugging (Security settings)\" (a separate switch from \"USB debugging\")")
        override fun downscaleHint() = tr("小米：设置 › 显示 › 屏幕分辨率 选最高档；游戏加速里关闭该游戏的“画质/分辨率”优化", "Xiaomi: Settings › Display › Screen resolution: choose the highest; in Game Turbo turn off the \"quality / resolution\" optimization for that game")
        override fun hints() = listOf(tr("开发者选项里打开“USB 调试（安全设置）”，否则输入注入会被拒绝；省电策略选“无限制”", "Turn on \"USB debugging (Security settings)\" in Developer options, or input injection is refused; set battery saver to \"No restrictions\""))
    }

    private object Oppo : Vendor {
        override fun osName(prop: (String) -> String) = prop("ro.build.version.oplusrom").ifEmpty { prop("ro.build.version.opporom") }
        override fun downscaleHint() = tr("OPPO/一加：设置 › 显示与亮度 › 屏幕分辨率 选最高档（关闭“智能切换”）", "OPPO/OnePlus: Settings › Display and brightness › Screen resolution: choose the highest (turn off \"Smart switch\")")
        override fun adbLimitedFix() = tr("OPPO/一加/realme（ColorOS）：把系统语言临时切成英文，在设置里搜索“Disable permission monitoring”并打开，再切回原来的语言（Find X9 Pro 实测中文里搜不到）。旧版系统里是“权限监控”，要关闭；都找不到就看 Shizuku 文档（https://shizuku.rikka.app/zh-hans/guide/setup/）", "OPPO/OnePlus/realme (ColorOS): switch the system language to English, search Settings for \"Disable permission monitoring\", turn it on, then switch back to your original language (on a Find X9 Pro it cannot be found in Chinese). Older versions have \"Permission monitoring\" instead: turn it off. If you find neither, see Shizuku's guide (https://shizuku.rikka.app/guide/setup/)")
        override fun hints() = listOf(
            tr("Shizuku 授权时报“adb 权限受限”的话：系统语言切成英文，在设置里搜“Disable permission monitoring”打开，再切回原来的语言（Find X9 Pro 中文里搜不到）；旧版是关闭“权限监控”，都找不到看 Shizuku 文档", "If Shizuku says \"adb permission limited\" when granting: switch the system language to English, search Settings for \"Disable permission monitoring\", turn it on, then switch back to your original language (a Find X9 Pro hides it in Chinese); older versions: turn off \"Permission monitoring\"; if neither is there, see Shizuku's guide"),
            tr("设置 › 电池 › 应用耗电管理 允许 Agent-VScreen 和 Shizuku 后台运行", "Settings › Battery › App battery management: allow Agent-VScreen and Shizuku to run in the background"),
        )
    }

    companion object {
        /** Maker key from the build fields (pure, unit-tested). */
        fun keyOf(manufacturer: String?, brand: String?): String {
            val m = (manufacturer ?: "").lowercase(); val b = (brand ?: "").lowercase()
            return when {
                m.contains("vivo") || b.contains("vivo") || b.contains("iqoo") -> "vivo"
                m.contains("honor") || b.contains("honor") -> "honor"
                m.contains("huawei") -> "huawei"
                m.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> "xiaomi"
                m.contains("oppo") || b.contains("oneplus") || b.contains("realme") -> "oppo"
                m.contains("samsung") -> "samsung"
                else -> m.ifEmpty { "unknown" }
            }
        }

        fun of(key: String): Vendor = when (key) { "vivo" -> Vivo; "honor", "huawei" -> Honor; "xiaomi" -> Xiaomi; "oppo" -> Oppo; else -> Generic }

        @Volatile private var cached: Device? = null

        val device: Device get() = cached ?: run {
            fun prop(k: String) = runCatching { VScreenShell.run(arrayOf("getprop", k), 3_000L).combined.trim() }.getOrDefault("")
            val key = keyOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND)
            Device(key, android.os.Build.BRAND ?: "", android.os.Build.MODEL ?: "", of(key).osName(::prop).trim(), android.os.Build.VERSION.SDK_INT).also { cached = it }
        }

        val current: Vendor get() = of(device.vendor)
    }
}
