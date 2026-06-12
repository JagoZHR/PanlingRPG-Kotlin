package com.panling.basic.mob.skill.impl

import com.panling.basic.mob.skill.MobSkill
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

class DashSkill(config: ConfigurationSection) : MobSkill {
    private val damage: Double = config.getDouble("damage", 10.0)
    private val range: Double = config.getDouble("range", 6.0)
    private val message: String? = config.getString("message")

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        if (target == null) return false
        val world = caster.world ?: return false

        val dir = target.location.toVector().subtract(caster.location.toVector())
        if (dir.lengthSquared() < 1.0) return false
        dir.normalize().multiply(1.5)

        caster.velocity = dir.setY(0.3)
        world.playSound(caster.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.5f)
        world.spawnParticle(Particle.CLOUD, caster.location, 10, 0.3, 0.3, 0.3, 0.0)

        val nearby = world.getNearbyEntities(caster.location, 2.5, 2.5, 2.5)
            .filterIsInstance<Player>()
            .filter { !it.isDead }
        nearby.forEach { it.damage(damage * 0.5, caster) }
        target.damage(damage, caster)

        if (message != null) broadcast(caster, message)
        return true
    }
}
