package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

class UnlockCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    // 对应基类的 abstract val name
    override val name: String = "unlock"

    // 解锁指令通常允许管理人员在控制台执行，故设为 false
    override val isPlayerOnly: Boolean = false

    override fun perform(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            msg(sender, "§c用法: /plbasic unlock <玩家> <物品ID>")
            return
        }

        val target = Bukkit.getPlayer(args[0])
        if (target == null) {
            msg(sender, "§c玩家不在线")
            return
        }

        // 调用资格管理器解锁物品，使用属性访问语法
        plugin.qualificationManager.unlockItem(target, args[1])

        msg(sender, "§a已为 ${target.name} 解锁 ${args[1]}")
        target.updateInventory()
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return when (args.size) {
            // 参数 1：手动补全在线玩家（替代 Java 中的 null 返回）
            1 -> Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }

            // 参数 2：过滤并返回物品 ID 列表
            2 -> plugin.itemManager.itemIds
                .filter { it.startsWith(args[1], ignoreCase = true) }

            else -> emptyList()
        }
    }
}