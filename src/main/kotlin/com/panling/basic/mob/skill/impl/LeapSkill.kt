package com.panling.basic.mob.skill.impl

import com.panling.basic.mob.skill.MobSkill
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity

class LeapSkill(config: ConfigurationSection) : MobSkill {
    private val power: Double = config.getDouble("power", 1.0)

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        if (target == null) return false

        // 计算朝向目标的方向
        val dir = target.location.toVector().subtract(caster.location.toVector()).normalize()
        dir.multiply(power)
        dir.y = 0.5 // 给一点向上的力

        caster.velocity = dir
        return true
    }
}