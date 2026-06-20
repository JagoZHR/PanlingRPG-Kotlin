package com.panling.basic.mob.skill.impl

import com.panling.basic.mob.skill.MobSkill
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/**
 * 震地击 — AOE伤害 + 击飞附近玩家
 * YAML: type: GROUND_SLAM, radius: 4, damage: 15, knockback: 1.5
 */
class GroundSlamSkill(config: ConfigurationSection) : MobSkill {
    private val radius: Double = config.getDouble("radius", 4.0)
    private val damage: Double = config.getDouble("damage", 15.0)
    private val knockback: Double = config.getDouble("knockback", 1.5)
    private val message: String? = config.getString("message")

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val world = caster.world ?: return false
        val loc = caster.location.clone()
        val nearby = world.getNearbyEntities(loc, radius, radius, radius)
            .filterIsInstance<Player>()
            .filter { !it.isDead }
        if (nearby.isEmpty()) return false

        for (player in nearby) {
            val dir = player.location.toVector().subtract(loc.toVector())
            if (dir.lengthSquared() > 0.01) {
                dir.normalize().multiply(knockback).setY(0.6)
            } else {
                dir.setX(0.0).setY(1.0).setZ(0.0)
            }
            player.velocity = dir
            player.damage(damage, caster)
        }
        world.spawnParticle(Particle.EXPLOSION, loc.add(0.0, 0.3, 0.0), 3, 0.5, 0.2, 0.5, 0.0)
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, 20, 1.0, 0.0, 1.0, 0.0)
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.4f)
        if (message != null) broadcast(caster, message)
        return true
    }
}
