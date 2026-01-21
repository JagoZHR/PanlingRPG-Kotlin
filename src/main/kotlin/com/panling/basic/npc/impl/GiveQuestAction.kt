package com.panling.basic.npc.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.npc.Npc
import com.panling.basic.npc.api.NpcAction
import org.bukkit.entity.Player

class GiveQuestAction(
    private val questId: String,
    private val talkMsg: String? // 可选参数，设为可空
) : NpcAction {

    override fun execute(player: Player, npc: Npc) {
        // 假设主类有 getInstance() 静态方法
        val plugin = PanlingBasic.instance
        val qm = plugin.questManager // 假设有 getQuestManager()
        val quest = qm.getQuest(questId) ?: return

        // 1. 检查是否可接取 (包含等级、前置、是否已做过)
        if (qm.isQuestAvailable(player, quest)) {
            // 2. 接取任务
            qm.acceptQuest(player, questId)

            // 3. 播放 NPC 台词
            if (talkMsg != null) {
                player.sendMessage("§e[NPC] ${npc.name}: §f$talkMsg")
            }
        } else {
            // 如果不可接，判断是因为已经做过了，还是条件不足
            if (qm.hasCompleted(player, questId)) {
                player.sendMessage("§e[NPC] ${npc.name}: §7谢谢你的帮助，勇士。")
            } else if (qm.getActiveQuests(player).any { it.quest.id == questId }) {
                // 使用 Kotlin 的 any 替代 Java Stream
                player.sendMessage("§e[NPC] ${npc.name}: §7任务进展如何了？快去吧。")
            } else {
                player.sendMessage("§e[NPC] ${npc.name}: §7我现在没有什么委托给你。(条件不足)")
            }
        }
    }
}