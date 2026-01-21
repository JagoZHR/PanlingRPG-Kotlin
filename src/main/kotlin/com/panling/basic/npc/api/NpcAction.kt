package com.panling.basic.npc.api

import com.panling.basic.npc.Npc
import org.bukkit.entity.Player

fun interface NpcAction {
    /**
     * 执行动作
     * @param player 交互的玩家
     * @param npc 被点击的 NPC 对象
     */
    fun execute(player: Player, npc: Npc)
}