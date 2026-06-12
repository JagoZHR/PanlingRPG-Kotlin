package com.panling.basic.mob.skill.impl

import com.panling.basic.mob.skill.MobSkill
import org.bukkit.Particle
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

class FireAuraSkill(config: ConfigurationSection) : MobSkill {
    private val radius: Double = config.getDouble("radius", 3.0)
    private val damage: Double = config.getDouble("damage", 5.0)
    private val message: String? = config.getString("message")

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val world = caster.world ?: return false
        val nearby = world.getNearbyEntities(caster.location, radius, radius, radius)
            .filterIsInstance<Player>()
            .filter { !it.isDead }
        if (nearby.isEmpty()) return false

        for (player in nearby) {
            player.damage(damage)
            player.fireTicks = 40
        }
        world.spawnParticle(Particle.FLAME, caster.location.add(0.0, 1.0, 0.0), 20, 0.5, 1.0, 0.5, 0.02)
        if (message != null) broadcast(caster, message)
        return true
    }
}
