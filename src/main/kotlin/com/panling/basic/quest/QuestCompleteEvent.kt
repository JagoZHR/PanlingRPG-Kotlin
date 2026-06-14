package com.panling.basic.quest

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * 任务完成事件
 * 当玩家完成一个任务时触发，供 QUEST_COMPLETE 目标类型监听
 */
class QuestCompleteEvent(
    val player: Player,
    val questId: String
) : Event() {

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }

    override fun getHandlers(): HandlerList = HANDLERS
}
