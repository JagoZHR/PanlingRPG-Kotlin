package com.panling.basic.api

import org.bukkit.entity.Player

interface PlayerDataManager {
    // 设置玩家职业
    fun setPlayerClass(player: Player, rpgClass: PlayerClass)

    // 获取玩家职业
    fun getPlayerClass(player: Player): PlayerClass

    // 未来可以扩展：
    // fun setMana(player: Player, mana: Double)
    // fun getMana(player: Player): Double
}