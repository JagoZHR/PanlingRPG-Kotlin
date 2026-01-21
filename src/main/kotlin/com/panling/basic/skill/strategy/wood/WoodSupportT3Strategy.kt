package com.panling.basic.skill.strategy.wood

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player

class WoodSupportT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val regenAmount = ctx.power * 0.05 // 5% SP / sec

        // 对自己
        applyRegen(p, p, regenAmount, ctx.power)

        // 对周围 3 格玩家
        p.getNearbyEntities(3.0, 2.0, 3.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyRegen(p, target, regenAmount, ctx.power)
            }

        p.playSound(p.location, Sound.BLOCK_GRASS_PLACE, 1f, 1f)
        return true
    }

    private fun applyRegen(caster: Player, target: Player, amount: Double, power: Double) {
        // 使用 BuffManager 的自定义数值功能 (false 代表加法/固定值)
        plugin.buffManager.addBuff(target, BuffType.REGEN, 200, amount, false)
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.WOOD, power)
        target.world.spawnParticle(
            Particle.HAPPY_VILLAGER,
            target.eyeLocation,
            5, 0.5, 0.5, 0.5
        )
    }
}