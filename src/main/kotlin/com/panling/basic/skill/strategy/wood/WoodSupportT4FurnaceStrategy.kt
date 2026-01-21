package com.panling.basic.skill.strategy.wood

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player

class WoodSupportT4FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val regenAmount = ctx.power * 0.05

        applyEffects(p, p, regenAmount, ctx.power)

        p.getNearbyEntities(4.0, 3.0, 4.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyEffects(p, target, regenAmount, ctx.power)
            }

        p.playSound(p.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f)
        return true
    }

    private fun applyEffects(caster: Player, target: Player, amount: Double, power: Double) {
        plugin.buffManager.addBuff(target, BuffType.REGEN, 100, amount, false)
        plugin.buffManager.addBuff(target, BuffType.HEAL_BOOST, 100, 1.2, true)

        // [核心修正] 调用 ElementManager
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.WOOD, power)

        target.world.spawnParticle(
            Particle.HAPPY_VILLAGER,
            target.location.add(0.0, 1.0, 0.0),
            5, 0.5, 0.5, 0.5
        )
    }
}