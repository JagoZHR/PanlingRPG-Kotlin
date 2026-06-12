package com.panling.basic.mob.skill.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.mob.skill.MobSkill
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity

class RegenSkill(config: ConfigurationSection) : MobSkill {
    private val percentPerSec: Double = config.getDouble("percent", 5.0)
    private val duration: Long = config.getLong("duration", 100)
    private val message: String? = config.getString("message")
    private val plugin = PanlingBasic.instance

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val maxHp = caster.getAttribute(Attribute.MAX_HEALTH)?.value ?: return false
        val healPerTick = maxHp * percentPerSec / 100.0 / 20.0
        val endTick = Bukkit.getCurrentTick() + duration

        caster.world.spawnParticle(Particle.HEART, caster.location.add(0.0, 2.0, 0.0), 5, 0.5, 0.5, 0.5, 0.0)

        val task = object : Runnable {
            override fun run() {
                if (!caster.isValid || caster.isDead) return
                if (Bukkit.getCurrentTick() >= endTick) return
                val newHp = (caster.health + healPerTick).coerceAtMost(maxHp)
                caster.health = newHp
                Bukkit.getScheduler().runTaskLater(plugin, this, 1L)
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, 1L)
        if (message != null) broadcast(caster, message)
        return true
    }
}
