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
 * 冰霜新星 — AOE 伤害 + 减速
 * YAML: type: FROST_NOVA, radius: 5, damage: 20, slow_duration: 60, slow_amplifier: 1
 */
class FrostNovaSkill(config: ConfigurationSection) : MobSkill {
    private val radius: Double = config.getDouble("radius", 5.0)
    private val damage: Double = config.getDouble("damage", 20.0)
    private val slowDuration: Int = config.getInt("slow_duration", 60)
    private val slowAmplifier: Int = config.getInt("slow_amplifier", 1)
    private val message: String? = config.getString("message")

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val world = caster.world ?: return false
        val loc = caster.location.clone()
        val nearby = world.getNearbyEntities(loc, radius, radius, radius)
            .filterIsInstance<Player>()
            .filter { !it.isDead }
        if (nearby.isEmpty()) return false

        for (player in nearby) {
            player.damage(damage, caster)
            player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, slowDuration, slowAmplifier, false, true))
        }
        world.spawnParticle(Particle.SNOWFLAKE, loc.add(0.0, 1.0, 0.0), 30, 1.5, 1.0, 1.5, 0.01)
        world.spawnParticle(Particle.SNOWFLAKE, loc, 20, 0.5, 0.2, 0.5, 0.01)
        world.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.3f)
        if (message != null) broadcast(caster, message)
        return true
    }
}
