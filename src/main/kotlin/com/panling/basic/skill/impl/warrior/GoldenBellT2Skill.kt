package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.WarriorSkillStrategy
import com.panling.basic.util.CombatUtil
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

// [修改] 同时继承 AbstractSkill (用于注册) 和 WarriorSkillStrategy (用于PVE架构)
class GoldenBellT2Skill(private val plugin: PanlingBasic) :
    AbstractSkill("WARRIOR_GOLDEN_BELL_T2", "松风护体", PlayerClass.WARRIOR), WarriorSkillStrategy {

    // [桥接] AbstractSkill 的入口是 onCast，我们把它导向 Strategy 的 cast
    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    override var castTime: Double = 0.0

    // [逻辑实现]
    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player

        val defense = plugin.statCalculator.getPlayerTotalStat(p, BasicKeys.ATTR_DEFENSE)
        // 伤害公式：防御力的 10%，保底 1.0
        val damage = (defense * 0.1).coerceAtLeast(1.0)

        p.world.spawnParticle(Particle.SONIC_BOOM, p.location.add(0.0, 1.0, 0.0), 1)
        p.world.spawnParticle(Particle.CRIT, p.location, 20, 2.0, 0.5, 2.0, 0.1)
        p.world.playSound(p.location, Sound.ITEM_SHIELD_BLOCK, 1f, 0.5f)
        p.world.playSound(p.location, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.8f)

        val radius = 4.0
        p.getNearbyEntities(radius, 2.0, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != p }
            .forEach { victim ->
                // 目标过滤
                if (victim is Player && !this.canHitPlayers) return@forEach
                if (!plugin.mobManager.canAttack(p, victim)) return@forEach

                CombatUtil.dealPhysicalSkillDamage(p, victim, damage, false)

                // 击退逻辑
                val knockback = victim.location.toVector().subtract(p.location.toVector())
                if (knockback.lengthSquared() > 0) {
                    knockback.normalize().multiply(0.8).setY(0.4)
                    victim.velocity = victim.velocity.add(knockback)
                }
            }

        return true
    }
}