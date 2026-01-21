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

class WaterSupportT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val heal = ctx.power * 0.15

        applyHeal(p, p, heal, ctx.power)

        p.getNearbyEntities(3.0, 2.0, 3.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyHeal(p, target, heal, ctx.power)
            }

        p.playSound(p.location, Sound.ENTITY_PLAYER_SPLASH, 1f, 1f)
        return true
    }

    private fun applyHeal(caster: Player, target: Player, heal: Double, power: Double) {
        // 使用 safe call 防止极个别情况下的 NPE，虽然 Player 通常都有这个属性
        val max = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        target.health = (target.health + heal).coerceAtMost(max)

        target.fireTicks = 0
        // 移除一个示例负面 (如果有更复杂的净化列表可在此添加)
        plugin.buffManager.removeBuff(target, BuffType.POISON)

        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.WATER, power)
        target.world.spawnParticle(Particle.SPLASH, target.eyeLocation, 10, 0.5, 1.0, 0.5)
    }
}