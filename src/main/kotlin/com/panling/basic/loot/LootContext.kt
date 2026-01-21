package com.panling.basic.loot

import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

data class LootContext(val mob: LivingEntity, val killer: Player?) {

    val world: World
        get() = mob.world

    // 辅助属性：获取怪物名字 (用于条件判断)
    val mobName: String?
        get() = mob.customName ?: mob.name
}