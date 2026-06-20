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
 * 咆哮减速 — 对附近玩家施加缓慢效果
 * YAML: type: ROAR, radius: 5, duration: 60, amplifier: 1
 */
class RoarSkill(config: ConfigurationSection) : MobSkill {
    private val radius: Double = config.getDouble("radius", 5.0)
    private val duration: Int = config.getInt("duration", 60)
    private val amplifier: Int = config.getInt("amplifier", 1)
    private val message: String? = config.getString("message")

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val world = caster.world ?: return false
        val nearby = world.getNearbyEntities(caster.location, radius, radius, radius)
            .filterIsInstance<Player>()
            .filter { !it.isDead }
        if (nearby.isEmpty()) return false

        for (player in nearby) {
            player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, duration, amplifier, false, true))
        }
        world.spawnParticle(Particle.SONIC_BOOM, caster.location.add(0.0, 1.0, 0.0), 8, 0.5, 0.5, 0.5, 0.0)
        world.playSound(caster.location, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.5f, 0.3f)
        if (message != null) broadcast(caster, message)
        return true
    }
}
