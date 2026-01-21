package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.WarriorSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player

class PoJunT3Skill(private val plugin: PanlingBasic) :
    AbstractSkill("WARRIOR_POJUN_T3", "烈火融金", PlayerClass.WARRIOR), WarriorSkillStrategy {

    override var castTime: Double = 0.0

    override val canHitPlayers: Boolean = false

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    override fun cast(ctx: SkillContext): Boolean {
        // 1. 触发检查
        if (ctx.triggerType != SkillTrigger.ATTACK) return false

        // 2. 概率 (20%)
        if (random.nextDouble() > 0.2) return false

        val target = ctx.target ?: return true

        // 3. PVE 检查
        if (target is Player && !this.canHitPlayers) return true

        // 4. 应用碎甲效果
        // 持续 5秒 (100 ticks)
        // 数值 0.8 (代表 x0.8，即减少 20% 防御，或者增加受到的伤害，具体看 BuffManager 实现)
        plugin.buffManager.addBuff(target, BuffType.ARMOR_BREAK, 100, 0.8, false)

        // 5. 特效
        target.world.spawnParticle(Particle.LAVA, target.eyeLocation, 5, 0.2, 0.2, 0.2)
        target.world.playSound(target.location, Sound.BLOCK_LAVA_EXTINGUISH, 1.0f, 1.5f)

        return true
    }
}