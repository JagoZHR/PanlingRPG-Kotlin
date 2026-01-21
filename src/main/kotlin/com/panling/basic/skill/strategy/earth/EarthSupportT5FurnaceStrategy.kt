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
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.min

class EarthSupportT5FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player

        // [T5] 范围扩大到 5 格
        applyShield(p, p, ctx.power)

        p.getNearbyEntities(5.0, 3.0, 5.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyShield(p, target, ctx.power)
            }

        p.playSound(p.location, Sound.BLOCK_STONE_PLACE, 1f, 0.8f)
        return true
    }

    private fun applyShield(caster: Player, target: Player, power: Double) {
        // 计算护盾总量 (每级4点)
        val rawShield = (power * 0.6).coerceAtLeast(4.0) // T5 系数稍高
        val amplifier = (rawShield / 4.0).toInt()
        // 实际护盾值 = (amplifier + 1) * 4
        val maxShieldHP = (amplifier + 1) * 4.0

        // 施加护盾
        target.removePotionEffect(PotionEffectType.ABSORPTION)
        // 持续 30 秒
        target.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, 600, amplifier, false, false, true))

        // 标记 (T4逻辑：用于破碎判定)
        target.setMetadata("pl_earth_shield_t4", FixedMetadataValue(plugin, true)) // 沿用 T4 破碎逻辑，或改为 t5 均可

        // [T5] 启动护盾回复任务
        startShieldRegenTask(target, maxShieldHP)

        // 元素联动 (土生金)
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.EARTH, power)

        target.world.spawnParticle(
            Particle.BLOCK_CRUMBLE,
            target.eyeLocation,
            15, 0.5, 0.5, 0.5,
            Material.DIRT.createBlockData()
        )
    }

    private fun startShieldRegenTask(target: Player, maxShield: Double) {
        object : BukkitRunnable() {
            var ticks = 0
            override fun run() {
                // 如果玩家下线或护盾BUFF消失(被打破/时间到)，停止任务
                if (!target.isOnline || !target.hasPotionEffect(PotionEffectType.ABSORPTION)) {
                    this.cancel()
                    return
                }

                // 30秒后停止
                if (ticks >= 600) {
                    this.cancel()
                    return
                }

                // 每 4 秒 (80 ticks) 回复一次
                if (ticks > 0 && ticks % 80 == 0) {
                    val currentAbs = target.absorptionAmount

                    // 只有当护盾不满时才回复
                    if (currentAbs < maxShield) {
                        val newAbs = min(maxShield, currentAbs + 4.0)
                        target.absorptionAmount = newAbs

                        target.world.playSound(target.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.5f)
                        target.world.spawnParticle(Particle.HAPPY_VILLAGER, target.eyeLocation, 3, 0.3, 0.3, 0.3)
                    }
                }

                ticks += 20 // 这里的频率可以自己定，比如 1秒检查一次
            }
        }.runTaskTimer(plugin, 0L, 20L) // 每秒运行一次逻辑检查
    }
}