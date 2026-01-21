package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.WarriorSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDamageByEntityEvent

class PoJunT2Skill(private val plugin: PanlingBasic) :
    AbstractSkill("WARRIOR_POJUN_T2", "开山劲", PlayerClass.WARRIOR), WarriorSkillStrategy {

    override var castTime: Double = 0.0

    override val canHitPlayers: Boolean = false

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player

        // 1. 注册蓄力状态
        // 告诉 SkillManager，这个技能在等待下一次攻击
        plugin.skillManager.addCharge(p, this.id)

        // 2. 视觉反馈 (蓄力)
        p.world.spawnParticle(Particle.ANGRY_VILLAGER, p.location.add(0.0, 1.5, 0.0), 3)
        p.world.spawnParticle(Particle.CRIT, p.location, 15, 0.5, 1.0, 0.5, 0.1)
        p.world.playSound(p.location, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 0.5f)
        p.world.playSound(p.location, Sound.ENTITY_PLAYER_BREATH, 1.0f, 0.5f)

        return true
    }

    // [核心] 当蓄力被触发时 (即下一次攻击时) 执行的逻辑
    override fun onNextAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        val victim = event.entity as? LivingEntity ?: return

        // 1. 伤害提升 30%
        event.damage = event.damage * 1.3

        // 2. 攻击特效 (强力打击感)
        victim.world.playSound(victim.location, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0f, 0.5f)
        victim.world.spawnParticle(Particle.EXPLOSION, victim.location.add(0.0, 1.0, 0.0), 1)

        // 3. 击退增强
        // 将目标向攻击方向猛烈推开
        val knockback = ctx.player.location.direction.multiply(0.8).setY(0.3)
        victim.velocity = victim.velocity.add(knockback)
    }
}