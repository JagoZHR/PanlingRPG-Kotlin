package com.panling.basic.listener

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * 全局怪物抗火 — 消除燃烧无敌帧对攻击判定的干扰。
 * 所有非玩家 LivingEntity 出生时永久获得 Fire Resistance。
 */
class FireResistanceListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onMobSpawn(event: EntitySpawnEvent) {
        if (event.isCancelled) return
        val entity = event.entity
        if (entity is LivingEntity && entity !is Player) {
            entity.addPotionEffect(
                PotionEffect(PotionEffectType.FIRE_RESISTANCE, -1, 0, false, false, false)
            )
        }
    }
}
