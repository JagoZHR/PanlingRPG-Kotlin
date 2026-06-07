package com.panling.basic.skill.impl.archer

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.random.Random

class AbyssalChillPassive(private val plugin: PanlingBasic) :
    AbstractSkill("ABYSSAL_CHILL", "深渊寒气", PlayerClass.NONE) {

    private val chance = 0.25
    private val duration = 3 * 20

    override fun onCast(ctx: SkillContext): Boolean {
        val target = ctx.target as? LivingEntity ?: return true
        if (Random.nextDouble() > chance) return true
        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, duration, 1))
        target.world.spawnParticle(Particle.SNOWFLAKE, target.location.add(0.0, 1.0, 0.0), 8, 0.3, 0.5, 0.3, 0.0)
        target.world.playSound(target.location, Sound.BLOCK_GLASS_BREAK, 0.4f, 0.5f)
        return true
    }
}
