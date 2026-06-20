package com.panling.basic.mob.skill.impl

import com.panling.basic.mob.skill.MobSkill
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/**
 * 幽影突袭 — 瞬移到目标身后造成伤害
 * YAML: type: SHADOW_STRIKE, damage: 18
 */
class ShadowStrikeSkill(config: ConfigurationSection) : MobSkill {
    private val damage: Double = config.getDouble("damage", 18.0)
    private val message: String? = config.getString("message")

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        if (target == null || target !is Player) return false
        val world = caster.world ?: return false

        // 计算目标身后位置
        val dir = target.location.direction.clone().normalize().multiply(-2.5)
        val behind = target.location.clone().add(dir).add(0.0, 0.5, 0.0)

        // 瞬移 + 粒子
        world.spawnParticle(Particle.PORTAL, caster.location.clone().add(0.0, 1.0, 0.0), 20, 0.3, 0.5, 0.3, 0.05)
        caster.teleport(behind)
        world.spawnParticle(Particle.SMOKE, caster.location.clone().add(0.0, 1.0, 0.0), 15, 0.3, 0.5, 0.3, 0.0)
        world.playSound(caster.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f)

        target.damage(damage, caster)
        if (message != null) broadcast(caster, message)
        return true
    }
}
