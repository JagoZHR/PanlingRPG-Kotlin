package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

// [修复] 继承 SubCommand 并传递 plugin 给父类构造函数
class ScriptCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    // [修复] 对应 SubCommand 中的 abstract val name
    override val name: String = "script"

    // [修复] 允许控制台和命令方块执行 (默认为 true)
    override val isPlayerOnly: Boolean = false

    // [修复] 对应 SubCommand 中的 abstract fun perform
    override fun perform(sender: CommandSender, args: Array<out String>) {
        // 用法: /prpg script start <id> <player>
        if (args.size < 3 || !args[0].equals("start", ignoreCase = true)) {
            sender.sendMessage("§c用法: /prpg script start <scriptId> <player>")
            return
        }

        val scriptId = args[1]
        val targetName = args[2]
        val targetPlayer = Bukkit.getPlayer(targetName)

        if (targetPlayer == null) {
            sender.sendMessage("§c找不到玩家: $targetName")
            return
        }

        // 启动脚本
        // 假设 PanlingBasic 中已经有了 worldScriptManager 属性
        plugin.worldScriptManager.startScript(targetPlayer, scriptId)

        // 命令方块通常需要反馈
        sender.sendMessage("§a已为玩家 ${targetPlayer.name} 启动脚本: $scriptId")
    }

    // [可选] 添加 Tab 补全
    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("start")
        }
        if (args.size == 2) {
            // 这里可以返回脚本ID列表，如果 Manager 有公开的 ID 列表的话
            return emptyList()
        }
        if (args.size == 3) {
            return Bukkit.getOnlinePlayers().map { it.name }
        }
        return emptyList()
    }
}