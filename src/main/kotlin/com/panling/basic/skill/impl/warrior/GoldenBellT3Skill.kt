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
import kotlin.math.min

class GoldenBellT3Skill(private val plugin: PanlingBasic) :
    AbstractSkill("WARRIOR_GOLDEN_BELL_T3", "黯然销魂", PlayerClass.WARRIOR), WarriorSkillStrategy {

    // 这是一个自身增益技能，不会误伤队友
    override var castTime: Double = 0.0

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    override fun cast(ctx: SkillContext): Boolean {
        // 1. 触发检查
        if (ctx.triggerType != SkillTrigger.DAMAGED) return false

        // 2. 概率判定 (10%)
        if (random.nextDouble() > 0.1) return false

        val p = ctx.player // 在受击事件中，SkillContext 的 player 是受击者

        // 3. 回血逻辑 (10% 最大生命值)
        // 使用空安全调用获取最大生命值，保底 20.0
        val maxHealth = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val currentHealth = p.health

        // 只有掉血了才回
        if (currentHealth < maxHealth) {
            val healAmount = maxHealth * 0.1
            val newHealth = min(maxHealth, currentHealth + healAmount)
            p.health = newHealth

            // 4. 特效反馈
            p.world.spawnParticle(Particle.HEART, p.eyeLocation, 5, 0.5, 0.5, 0.5)
            p.world.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f)

            // 可选：提示
            // p.sendActionBar(Component.text("§a[被动] 触发回血!").color(NamedTextColor.GREEN))
            return true
        }

        return false
    }
}