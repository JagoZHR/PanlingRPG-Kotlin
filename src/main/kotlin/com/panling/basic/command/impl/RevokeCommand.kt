package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

class RevokeCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "revoke"

    // 撤销指令通常允许管理人员在控制台执行，故设为 false
    override val isPlayerOnly: Boolean = false

    override fun perform(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            msg(sender, "§c用法: /plbasic revoke <玩家> <物品ID>")
            return
        }

        val target = Bukkit.getPlayer(args[0])
        if (target == null) {
            msg(sender, "§c玩家不在线")
            return
        }

        // 调用权限/资格管理器撤销物品使用资格
        plugin.qualificationManager.revokeItem(target, args[1])

        msg(sender, "§c已撤销 ${target.name} 的 ${args[1]} 使用资格")
        target.updateInventory()
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return when (args.size) {
            // 返回在线玩家列表以匹配原 Java 返回 null 的效果
            1 -> Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }

            // 过滤并返回物品 ID 列表
            2 -> plugin.itemManager.itemIds
                .filter { it.startsWith(args[1], ignoreCase = true) }

            else -> emptyList()
        }
    }
}