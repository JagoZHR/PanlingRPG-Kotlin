package com.panling.basic.quest.impl

import com.panling.basic.manager.QuestManager
import com.panling.basic.quest.QuestCompleteEvent
import com.panling.basic.quest.QuestProgress
import com.panling.basic.quest.api.QuestObjective
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event

/**
 * "完成另一个任务" 类型的任务目标。
 * 接取时检查目标是否已完成 → 直接标记；后续通过 QuestCompleteEvent 监听。
 */
class QuestCompleteObjective(
    override val id: String,
    private val targetQuestId: String,
    override val description: String,
    override val navigationLocation: Location?,
    private val questManager: QuestManager
) : QuestObjective {

    override val requiredAmount: Int = 1

    /** 接取时：如果目标支线已完成，直接标记进度 */
    override fun onAccept(player: Player, progress: QuestProgress): Boolean {
        if (questManager.hasCompleted(player, targetQuestId)) {
            progress.setProgress(id, 1)
            return true
        }
        return false
    }

    /** 进行中：监听其他任务完成事件 */
    override fun onEvent(player: Player, event: Event, currentProgress: Int): Int {
        if (event !is QuestCompleteEvent) return -1
        if (currentProgress >= requiredAmount) return -1
        if (event.player.uniqueId != player.uniqueId) return -1
        if (event.questId.equals(targetQuestId, ignoreCase = true)) {
            return currentProgress + 1
        }
        return -1
    }
}
