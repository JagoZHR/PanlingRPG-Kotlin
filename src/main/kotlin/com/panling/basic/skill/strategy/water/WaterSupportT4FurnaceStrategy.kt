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

class WaterSupportT4FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val heal = ctx.power * 0.15

        applyEffects(p, p, heal, ctx.power)

        p.getNearbyEntities(4.0, 3.0, 4.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyEffects(p, target, heal, ctx.power)
            }

        p.playSound(p.location, Sound.ENTITY_PLAYER_SPLASH, 1f, 1.2f)
        return true
    }

    private fun applyEffects(caster: Player, target: Player, heal: Double, power: Double) {
        val max = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        target.health = (target.health + heal).coerceAtMost(max)
        target.fireTicks = 0

        plugin.buffManager.addBuff(target, BuffType.SPEED_UP, 100, 1.2, true)

        // [核心修正] 调用 ElementManager
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.WATER, power)

        target.world.spawnParticle(
            Particle.SPLASH,
            target.location.add(0.0, 1.0, 0.0),
            10, 0.5, 0.5, 0.5
        )
    }
}