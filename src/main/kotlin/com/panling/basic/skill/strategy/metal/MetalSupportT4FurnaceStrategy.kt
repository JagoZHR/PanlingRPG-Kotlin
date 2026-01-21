package com.panling.basic.skill.strategy.metal

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player

class MetalSupportT4FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player

        val targets = p.getNearbyEntities(3.0, 3.0, 3.0)
            .asSequence()
            .filterIsInstance<Player>()
            .sortedBy { it.location.distanceSquared(p.location) }
            .take(3)
            .toMutableList()

        targets.add(p)

        for (target in targets) {
            plugin.buffManager.addBuff(target, BuffType.DEFENSE_UP, 200, 1.1, true)

            // [核心修正] 调用 ElementManager 处理辅助命中 (施加标记/触发相生)
            plugin.elementalManager.handleSupportHit(p, target, ElementalManager.Element.METAL, ctx.power)

            target.world.spawnParticle(
                Particle.WAX_ON,
                target.location.add(0.0, 1.0, 0.0),
                10, 0.5, 0.5, 0.5
            )
        }

        p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 1f, 1.5f)
        return true
    }
}