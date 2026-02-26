package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerRace
import com.panling.basic.command.SubCommand
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class DebugLockRaceCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    // 自动注册的子命令名称 /plbasic debuglockrace
    override val name: String = "debuglockrace"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            msg(sender, "§c用法: /plbasic debuglockrace <种族名称> [玩家]")
            return
        }

        // 确定目标
        val target: Player? = if (args.size >= 2) {
            Bukkit.getPlayer(args[1])
        } else {
            asPlayer(sender)
        }

        if (target == null) {
            msg(sender, "§c目标玩家不在线或未指定。")
            return
        }

        // 解析种族
        val targetRace = try {
            PlayerRace.valueOf(args[0].uppercase())
        } catch (e: Exception) {
            msg(sender, "§c未找到该种族！可选: HUMAN, DIVINE, IMMORTAL, DEMON, WAR_GOD")
            return
        }

        // 强行锁定种族试炼 (移除加成)
        plugin.playerDataManager.lockRace(target, targetRace)

        // 强制同步一次原版属性，让扣除的血量/移速立刻生效
        plugin.statCalculator.syncPlayerAttributes(target)

        // 获取当前已解锁的全部种族以供确认
        val unlocked = plugin.playerDataManager.getUnlockedRaces(target)
        msg(sender, "§c[Debug] 成功为 ${target.name} 重新锁定了 ${targetRace.displayName} 试炼！")
        msg(sender, "§e当前已收集的种族: ${unlocked.joinToString(", ") { it.displayName }}")
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        if (args.size == 1) {
            // 补全种族名称，排除 NONE
            return PlayerRace.values()
                .filter { it != PlayerRace.NONE }
                .map { it.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        // 补全在线玩家名称
        return if (args.size == 2) {
            Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
        } else {
            emptyList()
        }
    }
}