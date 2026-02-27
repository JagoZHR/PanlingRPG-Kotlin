package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import com.panling.basic.npc.impl.GiveQuestAction
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class InternalCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "internal"

    // 对应原 Java 中的 getPermission() { return null; }
    override val permission: String? = null

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (args.isEmpty()) return

        val action = args[0]

        when (action) {
            "close_dialog" -> {
                msg(player, "§7你结束了对话。")
            }

            "quest_dialog" -> {
                if (args.size >= 2) {
                    handleQuestDialog(player, args[1])
                }
            }

            "open_shop" -> {
                if (args.size >= 2) {
                    plugin.shopManager.openShop(player, args[1])
                }
            }

            "open_barter" -> {
                if (args.size >= 2) {
                    plugin.barterManager.openBarter(player, args[1])
                }
            }
        }
    }

    private fun handleQuestDialog(player: Player, npcId: String) {
        val availableQuests = plugin.questManager.getAvailableQuests(player)

        // 查找第一个发布人是该 NPC 的任务
        val targetQuest = availableQuests.firstOrNull { it.startNpcId == npcId }

        if (targetQuest != null) {
            val npc = plugin.npcManager.getNpc(npcId)
            npc?.let { GiveQuestAction(targetQuest.id, "这是给你的委托。").execute(player, it) }
        } else {
            msg(player, "§e[NPC] §7我现在没有适合你的任务。")
        }
    }
}