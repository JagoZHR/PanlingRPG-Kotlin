package com.panling.basic.quest.impl

import com.panling.basic.dungeon.DungeonCompleteEvent
import com.panling.basic.quest.api.QuestObjective
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event

/**
 * "通关副本" 类型的任务目标
 * 监听 DungeonCompleteEvent，匹配副本 ID
 */
class DungeonCompleteObjective(
    override val id: String,
    private val targetDungeonId: String,
    override val description: String,
    override val navigationLocation: Location?
) : QuestObjective {

    override val requiredAmount: Int = 1 // 通关一次即可

    override fun onEvent(player: Player, event: Event, currentProgress: Int): Int {
        if (event !is DungeonCompleteEvent) return -1
        if (currentProgress >= requiredAmount) return -1

        // 检查是否是目标玩家
        if (event.player.uniqueId != player.uniqueId) return -1

        // 检查是否是目标副本
        if (event.templateId.equals(targetDungeonId, ignoreCase = true)) {
            return currentProgress + 1
        }

        return -1
    }
}
