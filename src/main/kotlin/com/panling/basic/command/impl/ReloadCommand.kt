package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import com.panling.basic.event.PanlingReloadEvent
import org.bukkit.command.CommandSender

class ReloadCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "reload"

    // 重载命令通常允许控制台执行，故覆盖基类默认的 true
    override val isPlayerOnly: Boolean = false

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val start = System.currentTimeMillis()
        msg(sender, "§e[PanlingBasic] 正在执行全模块重载...")

        // [核心] 使用 Kotlin 属性访问语法调用重载逻辑
        plugin.reloadManager.reloadAll()

        // 触发一个事件，给那些非 Manager 的散户监听器一个机会
        plugin.server.pluginManager.callEvent(PanlingReloadEvent())

        val time = System.currentTimeMillis() - start
        msg(sender, "§a[PanlingBasic] 重载完成! (耗时 ${time}ms)")
    }
}