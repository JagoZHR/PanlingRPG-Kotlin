package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import com.panling.basic.quest.QuestLoader
import org.bukkit.command.CommandSender

class NpcCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "npc"

    // 原代码中返回 false，表示控制台也可以执行
    override val isPlayerOnly: Boolean = false

    override fun perform(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            msg(sender, "§c用法: /plbasic npc [reload|restore]")
            return
        }

        // 使用 lowercase() 替代 equalsIgnoreCase，配合 when 更加整洁
        when (args[0].lowercase()) {
            "reload" -> {
                plugin.npcManager.reload()
                // 重载任务逻辑
                QuestLoader(plugin, plugin.questManager).loadAll()
                msg(sender, "§aNPC 系统已重载，且任务逻辑已重新绑定。")
            }

            "restore" -> {
                val count = plugin.npcManager.restoreNpcs()
                msg(sender, "§a检查完成，共修复了 $count 个丢失的 NPC。")
            }

            else -> {
                msg(sender, "§c未知参数。用法: /plbasic npc [reload|restore]")
            }
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return if (args.size == 1) {
            listOf("reload", "restore")
        } else {
            emptyList()
        }
    }
}