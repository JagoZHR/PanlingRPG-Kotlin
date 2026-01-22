package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerRace
import com.panling.basic.command.SubCommand
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class RaceCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "race"

    override val isPlayerOnly: Boolean = false

    override fun perform(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            msg(sender, "§c用法: /plbasic race <玩家> <种族>")
            return
        }

        try {
            val targets = Bukkit.selectEntities(sender, args[0])
            val targetRace = PlayerRace.valueOf(args[1].uppercase())
            var count = 0

            targets.filterIsInstance<Player>().forEach { p ->
                plugin.playerDataManager.setPlayerRace(p, targetRace)
                plugin.playerDataManager.clearStatCache(p)
                p.sendMessage("§a[系统] 你的血脉觉醒了，现在的种族是: ${targetRace.coloredName}")
                p.updateInventory()
                count++
            }

            msg(sender, "§a已更新 $count 名玩家的种族为 ${targetRace.displayName}")
        } catch (e: IllegalArgumentException) {
            msg(sender, "§c错误: 种族名称无效。")
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return when (args.size) {
            // 原 Java 返回 null 是为了让 Bukkit 自动补全玩家名。
            // 既然基类要求非空 List，我们手动获取在线玩家名单来实现相同效果。
            1 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }

            2 -> PlayerRace.values()
                .map { it.name }
                .filter { it.startsWith(args[1].uppercase()) }

            else -> emptyList()
        }
    }
}