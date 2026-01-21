package com.panling.basic.skill.strategy.fire

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player

class FireSupportT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        applyBuff(p, p, ctx.power)

        p.getNearbyEntities(3.0, 2.0, 3.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyBuff(p, target, ctx.power)
            }

        p.playSound(p.location, Sound.BLOCK_FIRE_AMBIENT, 1f, 1f)
        return true
    }

    private fun applyBuff(caster: Player, target: Player, power: Double) {
        // 提升 15% 攻击 (1.15倍)
        plugin.buffManager.addBuff(target, BuffType.ATTACK_UP, 200, 1.15, true)
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.FIRE, power)
        target.world.spawnParticle(
            Particle.FLAME,
            target.eyeLocation,
            5, 0.3, 0.5, 0.3, 0.05
        )
    }
}