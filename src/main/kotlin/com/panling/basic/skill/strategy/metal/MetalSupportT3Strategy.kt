package com.panling.basic.skill.strategy.metal

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player

class MetalSupportT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0 // 瞬发

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player

        // 射线选取 5 格内玩家
        val target: Player? = p.world.rayTraceEntities(
            p.eyeLocation,
            p.location.direction,
            5.0,
            0.5
        ) { e -> e is Player && e != p }?.hitEntity as? Player

        if (target == null) {
            p.sendMessage("§c未指向有效玩家目标！")
            return false
        }

        // 双抗提升 10% (1.1倍)
        plugin.buffManager.addBuff(target, BuffType.DEFENSE_UP, 200, 1.1, true)

        // 触发五行 (Support Hit)
        plugin.elementalManager.handleSupportHit(p, target, ElementalManager.Element.METAL, ctx.power)

        p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 1f, 2f)
        target.world.spawnParticle(
            Particle.WAX_ON,
            target.eyeLocation,
            10, 0.5, 0.5, 0.5
        )
        return true;
    }
}