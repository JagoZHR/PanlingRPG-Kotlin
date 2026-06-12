package com.panling.basic.mob.skill.impl

import com.panling.basic.mob.skill.MobSkill
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

class PullSkill(config: ConfigurationSection) : MobSkill {
    private val radius: Double = config.getDouble("radius", 6.0)
    private val strength: Double = config.getDouble("strength", 1.0)
    private val message: String? = config.getString("message")

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val world = caster.world ?: return false
        val nearby = world.getNearbyEntities(caster.location, radius, radius, radius)
            .filterIsInstance<Player>()
            .filter { !it.isDead }
        if (nearby.isEmpty()) return false

        for (player in nearby) {
            val dir = caster.location.toVector().subtract(player.location.toVector()).normalize()
            player.velocity = dir.multiply(strength).setY(0.3)
        }
        if (message != null) broadcast(caster, message)
        return true
    }
}
