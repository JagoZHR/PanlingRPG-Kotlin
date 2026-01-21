package com.panling.basic.skill.strategy.water

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player

class WaterSupportT5FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val heal = ctx.power * 0.15

        // [T5] 范围扩大到 5 格
        applyEffects(p, p, heal, ctx.power)

        p.getNearbyEntities(5.0, 3.0, 5.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyEffects(p, target, heal, ctx.power)
            }

        p.playSound(p.location, Sound.ENTITY_PLAYER_SPLASH, 1f, 0.8f)
        return true
    }

    private fun applyEffects(caster: Player, target: Player, heal: Double, power: Double) {
        val max = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        target.health = (target.health + heal).coerceAtMost(max)
        target.fireTicks = 0

        // 加速
        plugin.buffManager.addBuff(target, BuffType.SPEED_UP, 100, 1.2, true)

        // [T5] 恢复一半饱食度 (10点) 和 饱和度
        val oldFood = target.foodLevel
        target.foodLevel = (oldFood + 10).coerceAtMost(20)

        val oldSat = target.saturation
        target.saturation = (oldSat + 10f).coerceAtMost(20f)

        // 元素联动 (水生木)
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.WATER, power)

        target.world.spawnParticle(
            Particle.SPLASH,
            target.location.add(0.0, 1.0, 0.0),
            20, 0.5, 0.5, 0.5
        )
    }
}