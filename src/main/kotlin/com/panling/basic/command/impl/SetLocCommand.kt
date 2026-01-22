package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.command.CommandSender

class SetLocCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    // 对应基类的 abstract val name
    override val name: String = "setloc"

    // 默认 isPlayerOnly 为 true，符合设置地标需要玩家位置的需求

    override fun perform(sender: CommandSender, args: Array<out String>) {
        // 使用基类安全转换方法，若非玩家则直接返回
        val player = asPlayer(sender) ?: return

        if (args.isEmpty()) {
            msg(sender, "§c用法: /plbasic setloc <地标名称>")
            return
        }

        val locName = args[0]
        // 调用 LocationManager 设置地标，使用属性访问语法
        plugin.locationManager.setLocation(locName, player.location)

        msg(sender, "§a成功设置地标: $locName")
    }
}