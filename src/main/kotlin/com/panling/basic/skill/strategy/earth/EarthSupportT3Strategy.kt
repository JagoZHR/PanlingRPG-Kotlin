package com.panling.basic.skill.strategy.earth

import com.panling.basic.PanlingBasic
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class EarthSupportT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player

        // 1. 计算目标护盾值 (50% 技能强度)
        // 保底 4.0 (即 1级药水，2颗心)，防止 0 强度时无效果
        val rawShield = (ctx.power * 0.5).coerceAtLeast(4.0)

        // 2. 转换为药水等级 (每级 4 点护盾)
        // Amplifier 0 = Level 1 = 4点
        // Amplifier = (护盾 / 4) - 1
        // 限制最大等级 (防止超过 255 溢出)
        val amplifier = ((rawShield / 4.0).toInt() - 1).coerceIn(0, 255)

        // [调试]
        // println("[Debug] 土系护盾: 原始值=$rawShield, 药水等级=$amplifier")

        // 3. 施加
        applyShield(p, p, amplifier, ctx.power)

        // 使用 Kotlin 集合操作过滤玩家
        p.getNearbyEntities(3.0, 2.0, 3.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyShield(p, target, amplifier, ctx.power)
            }

        p.playSound(p.location, Sound.BLOCK_STONE_PLACE, 1f, 1f)
        return true
    }

    private fun applyShield(caster: Player, target: Player, amplifier: Int, power: Double) {
        // 4. 防叠加逻辑 (只取最大值)
        var shouldApply = true

        val current = target.getPotionEffect(PotionEffectType.ABSORPTION)
        if (current != null) {
            // 如果已有等级 >= 新等级，则不施加 (保留旧的)
            if (current.amplifier >= amplifier) {
                shouldApply = false
            }
        }

        if (shouldApply) {
            // 持续时间：无限 (或设个极长时间，如 10分钟)
            // 20 ticks * 60 * 10 = 12000
            target.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, 12000, amplifier, false, false, true))

            // 视觉特效
            target.world.spawnParticle(
                Particle.BLOCK_CRUMBLE,
                target.eyeLocation,
                10, 0.5, 0.5, 0.5,
                Material.DIRT.createBlockData()
            )
        }

        // 5. 触发反应
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.EARTH, power)
    }
}