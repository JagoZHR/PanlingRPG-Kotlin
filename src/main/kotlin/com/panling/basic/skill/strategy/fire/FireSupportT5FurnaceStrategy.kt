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

class FireSupportT5FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val power = ctx.power

        // [T5] 范围扩大到 5 格
        applyBuff(p, p, power)

        p.getNearbyEntities(5.0, 3.0, 5.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyBuff(p, target, power)
            }

        p.playSound(p.location, Sound.ITEM_FIRECHARGE_USE, 1f, 0.8f)
        return true
    }

    private fun applyBuff(caster: Player, target: Player, power: Double) {
        // [T5] 攻击力提升改为 30% (1.3)
        plugin.buffManager.addBuff(target, BuffType.ATTACK_UP, 200, 1.30, true)

        // 炎刃附魔 (T4 机制保留：一次性额外伤害)
        plugin.buffManager.addBuff(target, BuffType.FIRE_IMBUE, 200, 1.0, false)
        target.setMetadata("pl_fire_imbue_power", FixedMetadataValue(plugin, power))

        // 元素联动 (火生土)
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.FIRE, power)

        target.world.spawnParticle(
            Particle.FLAME,
            target.location.add(0.0, 1.0, 0.0),
            15, 0.5, 0.5, 0.5, 0.05
        )
        target.world.spawnParticle(
            Particle.LAVA,
            target.location.add(0.0, 1.0, 0.0),
            2, 0.5, 0.5, 0.5
        )
    }
}