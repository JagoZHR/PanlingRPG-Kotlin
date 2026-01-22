package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.command.CommandSender

class SpawnMobCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    // 对应基类的 abstract val name
    override val name: String = "spawnmob"

    // 基类默认 isPlayerOnly 为 true，符合生成怪物需要玩家位置的需求

    override fun perform(sender: CommandSender, args: Array<out String>) {
        // 使用基类提供的安全转换方法，若非玩家则直接返回
        val player = asPlayer(sender) ?: return

        // args[0] 就是 mobId
        if (args.isEmpty()) {
            msg(sender, "§c用法: /plbasic spawnmob <ID>")
            return
        }

        val mobId = args[0]

        // 调用 MobManager 生成怪物，并使用属性访问语法
        if (plugin.mobManager.spawnMob(player.location, mobId) != null) {
            msg(sender, "§a成功生成: $mobId")
        } else {
            msg(sender, "§c怪物ID不存在: $mobId")
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return if (args.size == 1) {
            // 自动补全怪物ID，使用 Kotlin 集合操作替代 Stream
            plugin.mobManager.mobIds
                .filter { it.startsWith(args[0], ignoreCase = true) }
        } else {
            super.getTabComplete(sender, args)
        }
    }
}