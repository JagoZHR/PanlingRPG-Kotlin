package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class DungeonCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "dungeon"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            msg(sender, "§c用法: /plbasic dungeon <enter/leave> [ID]")
            return
        }

        // 使用 Kotlin 的智能转换逻辑，或者直接判断类型
        if (sender !is Player) {
            msg(sender, "§c只有玩家可以使用此指令。")
            return
        }

        // 处理 enter 指令
        if (args[0].equals("enter", ignoreCase = true)) {
            if (args.size < 2) {
                msg(sender, "§c请输入副本ID。")
                return
            }
            // 假设 DungeonManager 有 startDungeon 方法
            plugin.dungeonManager.startDungeon(sender, args[1])
            return
        }

        // [NEW] 离开指令
        if (args[0].equals("leave", ignoreCase = true)) {
            plugin.dungeonManager.leaveDungeon(sender)
            return
        }
    }
}