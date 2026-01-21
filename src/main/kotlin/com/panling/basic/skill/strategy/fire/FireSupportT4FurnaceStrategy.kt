package com.panling.basic.skill.strategy.fire

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue

class FireSupportT4FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val power = ctx.power

        applyBuff(p, p, power)

        p.getNearbyEntities(4.0, 3.0, 4.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyBuff(p, target, power)
            }

        p.playSound(p.location, Sound.ITEM_FIRECHARGE_USE, 1f, 1f)
        return true
    }

    private fun applyBuff(caster: Player, target: Player, power: Double) {
        plugin.buffManager.addBuff(target, BuffType.ATTACK_UP, 200, 1.15, true)

        // 炎刃
        plugin.buffManager.addBuff(target, BuffType.FIRE_IMBUE, 200, 1.0, false)
        target.setMetadata("pl_fire_imbue_power", FixedMetadataValue(plugin, power))

        // [核心修正] 调用 ElementManager
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.FIRE, power)

        target.world.spawnParticle(
            Particle.FLAME,
            target.location.add(0.0, 1.0, 0.0),
            10, 0.3, 0.5, 0.3, 0.05
        )
    }
}