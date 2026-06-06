package com.panling.basic.npc.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.npc.Npc
import com.panling.basic.npc.api.NpcAction
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class GiveQuestAction(private val questId: String) : NpcAction {

    override fun execute(player: Player, npc: Npc) {
        val plugin = PanlingBasic.instance
        val qm = plugin.questManager
        val quest = qm.getQuest(questId) ?: return

        // 1. 检查是否可接取
        if (qm.isQuestAvailable(player, quest)) {
            val dialogLines = quest.acceptDialog

            if (dialogLines.isEmpty()) {
                // 无自定义对话：直接接取
                qm.acceptQuest(player, questId)
                tryAutoCompleteAndChain(player, npc, plugin, qm)
            } else {
                // 多轮对话
                var delay = 0L
                for (line in dialogLines) {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        player.sendMessage("§e[${npc.name}] §f$line")
                    }, delay)
                    delay += 40L // 每行间隔 2 秒
                }

                // 最后一行后 2 秒，正式接取任务
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    qm.acceptQuest(player, questId)
                    tryAutoCompleteAndChain(player, npc, plugin, qm)
                }, delay)
            }
        } else {
            // 不可接取：根据状态给不同回复
            if (qm.hasCompleted(player, questId)) {
                player.sendMessage("§e[${npc.name}] §7这件事你已经做过了，不用再来找我了。")
            } else if (qm.getActiveQuests(player).any { it.quest.id == questId }) {
                player.sendMessage("§e[${npc.name}] §7任务不是已经给你了吗？快去吧，别耽误时间。")
            } else {
                player.sendMessage("§e[${npc.name}] §7现在我还不需要你的帮助，去别处看看吧。")
            }
        }
    }

    /**
     * 接取后自动完成与当前 NPC 的对话目标，并链式触发同一 NPC 的下一个可用任务
     */
    private fun tryAutoCompleteAndChain(player: Player, npc: Npc, plugin: PanlingBasic, qm: com.panling.basic.manager.QuestManager) {
        // 自动完成当前任务中与当前 NPC 的对话目标
        val progress = qm.getActiveProgress(player, questId)
        if (progress != null && !progress.isCompleted) {
            qm.tryAutoCompleteNpc(player, progress, npc.id)
        }

        // 延迟 3 秒后，检查是否有同一 NPC 的下一个任务，如有则自动触发
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val nextQuests = qm.getAvailableQuests(player)
            val nextQuest = nextQuests.firstOrNull { it.startNpcId == npc.id }
            if (nextQuest != null) {
                GiveQuestAction(nextQuest.id).execute(player, npc)
            }
        }, 60L) // 3 秒
    }
}
