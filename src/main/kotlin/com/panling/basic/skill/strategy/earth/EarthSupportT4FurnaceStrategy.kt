package com.panling.basic.skill.strategy.earth

import com.panling.basic.PanlingBasic
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class EarthSupportT4FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val power = ctx.power

        applyShield(p, p, power)

        p.getNearbyEntities(4.0, 3.0, 4.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyShield(p, target, power)
            }

        p.playSound(p.location, Sound.BLOCK_STONE_PLACE, 1f, 1f)
        return true
    }

    private fun applyShield(caster: Player, target: Player, power: Double) {
        val rawShield = (power * 0.5).coerceAtLeast(4.0)
        // 注意：T4 Java 原代码逻辑此处没有 -1，保留原样
        val amplifier = (rawShield / 4.0).toInt()

        target.removePotionEffect(PotionEffectType.ABSORPTION)
        target.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, 600, amplifier, false, false, true))

        target.setMetadata("pl_earth_shield_t4", FixedMetadataValue(plugin, true))

        // [核心修正] 调用 ElementManager
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.EARTH, power)

        target.world.spawnParticle(
            Particle.BLOCK_CRUMBLE,
            target.eyeLocation,
            10, 0.5, 0.5, 0.5,
            Material.DIRT.createBlockData()
        )
    }
}