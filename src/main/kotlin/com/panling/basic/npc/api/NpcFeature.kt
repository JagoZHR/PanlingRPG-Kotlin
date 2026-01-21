package com.panling.basic.npc.api

import com.panling.basic.npc.Npc
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

interface NpcFeature {
    /**
     * 该功能是否对当前玩家、当前 NPC 可用？
     * @return true 显示按钮，false 隐藏
     */
    fun isAvailable(player: Player, npc: Npc): Boolean

    /**
     * 获取显示在聊天栏的按钮文本（包含样式、悬停、点击事件）
     */
    fun getButton(player: Player, npc: Npc): Component
}