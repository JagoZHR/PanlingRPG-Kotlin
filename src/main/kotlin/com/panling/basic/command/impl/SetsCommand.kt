package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.command.CommandSender

class SetsCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    // 对应基类的 abstract val name
    override val name: String = "sets"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        // 使用基类提供的辅助方法进行安全转换
        val player = asPlayer(sender) ?: return

        // 使用 Kotlin 属性语法访问 setUI 并打开界面
        plugin.setUI.openSetList(player)
    }
}