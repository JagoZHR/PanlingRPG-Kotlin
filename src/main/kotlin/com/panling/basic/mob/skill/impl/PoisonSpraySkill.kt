package com.panling.basic.mob.skill.impl

import com.panling.basic.mob.skill.MobSkill
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * 毒雾喷吐 — 对附近玩家施加中毒效果
 * YAML: type: POISON_SPRAY, radius: 4, duration: 80, amplifier: 1
 */
class PoisonSpraySkill(config: ConfigurationSection) : MobSkill {
    private val radius: Double = config.getDouble("radius", 4.0)
    private val duration: Int = config.getInt("duration", 80)
    private val amplifier: Int = config.getInt("amplifier", 1)
    private val message: String? = config.getString("message")

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val world = caster.world ?: return false
        val nearby = world.getNearbyEntities(caster.location, radius, radius, radius)
            .filterIsInstance<Player>()
            .filter { !it.isDead }
        if (nearby.isEmpty()) return false

        for (player in nearby) {
            player.addPotionEffect(PotionEffect(PotionEffectType.POISON, duration, amplifier, false, true))
        }
        world.spawnParticle(Particle.ITEM_SLIME, caster.location.add(0.0, 1.0, 0.0), 15, 1.0, 0.5, 1.0, 0.0)
        world.playSound(caster.location, Sound.ENTITY_SPIDER_HURT, 1.0f, 0.5f)
        if (message != null) broadcast(caster, message)
        return true
    }
}
