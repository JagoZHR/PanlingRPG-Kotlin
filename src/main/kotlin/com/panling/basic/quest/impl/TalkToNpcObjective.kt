package com.panling.basic.quest.impl

import com.panling.basic.api.BasicKeys
import com.panling.basic.quest.api.QuestObjective
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.persistence.PersistentDataType

/**
 * "与 NPC 对话" 类型的任务目标
 * 当玩家右键点击指定 NPC 时，进度 +1
 */
class TalkToNpcObjective(
    override val id: String,
    private val targetNpcId: String,
    override val description: String,
    override val navigationLocation: Location?
) : QuestObjective {

    override val requiredAmount: Int = 1  // 对话类目标只需一次

    /**
     * 判断此目标是否匹配给定的 NPC ID
     */
    fun matchesNpc(npcId: String): Boolean = targetNpcId.equals(npcId, ignoreCase = true)

    override fun onEvent(player: Player, event: Event, currentProgress: Int): Int {
        if (event !is PlayerInteractEntityEvent) return -1
        if (currentProgress >= requiredAmount) return -1

        val entity = event.rightClicked
        val npcId = entity.persistentDataContainer.get(
            BasicKeys.NPC_ID,
            PersistentDataType.STRING
        ) ?: return -1

        if (npcId.equals(targetNpcId, ignoreCase = true)) {
            return currentProgress + 1
        }

        return -1
    }
}
