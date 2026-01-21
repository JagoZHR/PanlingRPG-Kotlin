package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
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

class PoJunT5Skill(private val plugin: PanlingBasic) :
    AbstractSkill("WARRIOR_POJUN_T5", "镇魔", PlayerClass.WARRIOR), WarriorSkillStrategy {

    override var castTime: Double = 0.0

    override val canHitPlayers: Boolean = false

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    // 负责条件判定
    override fun cast(ctx: SkillContext): Boolean {
        // 1. 触发器检查
        if (ctx.triggerType != SkillTrigger.ATTACK) return false

        val target = ctx.target ?: return false

        // 2. PVE 检查
        if (target is Player && !this.canHitPlayers) return false

        // 3. [核心条件] 检查目标生命值是否高于 90% (用于起手爆发)
        val maxHp = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val currentHp = target.health

        // 如果血量比例 <= 0.9，则不触发
        if ((currentHp / maxHp) <= 0.9) {
            return false
        }

        return true
    }

    // 负责伤害执行
    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        val p = ctx.player

        // 1. 获取玩家的物理攻击面板 (ATTR_PHYSICAL_DAMAGE)
        val physAttr = plugin.statCalculator.getPlayerTotalStat(p, BasicKeys.ATTR_PHYSICAL_DAMAGE)

        // 2. 计算额外伤害 (50% 的物理面板)
        // 理解为：额外附加 0.5 * 面板
        val bonusDamage = physAttr * 0.5

        // 3. 应用伤害
        // 直接加在原有伤害上，这样只会飘一个大数字，且享受暴击/吸血
        event.damage = event.damage + bonusDamage

        // 4. 视觉反馈 (重击感)
        val target = ctx.target
        if (target != null) {
            // 崩山裂地特效
            // target.world.spawnParticle(Particle.EXPLOSION, target.location.add(0.0, 1.0, 0.0), 1)

            target.world.spawnParticle(Particle.ANGRY_VILLAGER, target.eyeLocation, 5)
            //target.world.playSound(target.location, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0f, 0.5f)
            target.world.playSound(target.location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.5f)
        }
    }
}