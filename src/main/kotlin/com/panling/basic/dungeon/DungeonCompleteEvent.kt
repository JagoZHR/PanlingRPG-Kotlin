package com.panling.basic.dungeon

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * 副本通关事件
 * 当玩家完成副本并获得奖励时触发
 */
class DungeonCompleteEvent(
    val player: Player,
    val templateId: String
) : Event() {

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }

    override fun getHandlers(): HandlerList = HANDLERS
}
