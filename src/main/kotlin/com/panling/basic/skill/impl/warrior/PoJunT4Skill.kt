package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.WarriorSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent

class PoJunT4Skill(private val plugin: PanlingBasic) :
    AbstractSkill("WARRIOR_POJUN_T4", "处决", PlayerClass.WARRIOR), WarriorSkillStrategy {

    override var castTime: Double = 0.0

    override val canHitPlayers: Boolean = false

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    // cast 负责：条件判断 (Condition Check)
    override fun cast(ctx: SkillContext): Boolean {
        // 1. 触发检查
        if (ctx.triggerType != SkillTrigger.ATTACK) return false

        val target = ctx.target ?: return false

        // 2. PVE 检查
        if (target is Player && !this.canHitPlayers) return false

        // 3. [核心条件] 检查目标生命值是否低于 50%
        val maxHp = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val currentHp = target.health

        // 如果血量比例 >= 0.5 (即 50% 及以上)，技能不生效
        if ((currentHp / maxHp) >= 0.5) {
            return false // 返回 false，不进冷却，不扣蓝，不触发 onAttack
        }

        // 条件满足，返回 true，SkillManager 会接着调用 onAttack
        return true
    }

    // onAttack 负责：实际效果 (Effect Execution)
    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        // 此时我们确定目标血量 < 50%

        // 1. 增加 30% 伤害
        event.damage = event.damage * 1.3

        // 2. 视觉/听觉反馈 (斩杀特效)
        val target = ctx.target
        if (target != null) {
            // 暗红色暴击效果
            target.world.spawnParticle(Particle.DAMAGE_INDICATOR, target.eyeLocation, 5, 0.3, 0.3, 0.3, 0.1)
            target.world.spawnParticle(Particle.SWEEP_ATTACK, target.location.add(0.0, 1.0, 0.0), 1)
            target.world.playSound(target.location, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.0f, 0.5f)
        }
    }
}