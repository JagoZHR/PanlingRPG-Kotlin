package com.panling.basic.quest.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.quest.api.QuestReward
import org.bukkit.Sound
import org.bukkit.entity.Player

class MoneyReward(private val amount: Double) : QuestReward {

    // 可选修正 MoneyReward.kt
    override fun give(player: Player) {
        PanlingBasic.instance.economyManager.giveMoney(player, amount)
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
    }

    override val display: String
        get() = "§e$amount 铜钱"
}