package com.panling.basic.quest.api

import org.bukkit.entity.Player

interface QuestReward {

    // 奖励的描述 (例如 "§e100 铜钱")
    val display: String

    // 给与奖励
    fun give(player: Player)
}