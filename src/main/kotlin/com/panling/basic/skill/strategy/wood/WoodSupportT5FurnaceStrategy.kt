package com.panling.basic.skill.strategy.wood

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

class WoodSupportT5FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val regenAmount = ctx.power * 0.06

        val carriers = HashSet<Player>()

        // 范围扩大到 5 格
        applyEffects(p, p, regenAmount, ctx.power)
        carriers.add(p)

        p.getNearbyEntities(5.0, 3.0, 5.0)
            .filterIsInstance<Player>()
            .forEach { target ->
                applyEffects(p, target, regenAmount, ctx.power)
                carriers.add(target)
            }

        startConductionTask(p, carriers, regenAmount, ctx.power)

        p.playSound(p.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.8f)
        return true
    }

    private fun applyEffects(caster: Player, target: Player, amount: Double, power: Double) {
        plugin.buffManager.addBuff(target, BuffType.REGEN, 100, amount, false)
        plugin.buffManager.addBuff(target, BuffType.HEAL_BOOST, 100, 1.2, true)
        plugin.elementalManager.handleSupportHit(caster, target, ElementalManager.Element.WOOD, power)
        target.world.spawnParticle(
            Particle.HAPPY_VILLAGER,
            target.location.add(0.0, 1.0, 0.0),
            8, 0.5, 0.5, 0.5
        )
    }

    private fun startConductionTask(caster: Player, carriers: Set<Player>, amount: Double, power: Double) {
        // 使用 map 和 toMutableSet 快速构建初始 infectedIds
        val infectedIds = carriers.map { it.entityId }.toMutableSet()

        object : BukkitRunnable() {
            var ticks = 0
            override fun run() {
                if (ticks >= 100) {
                    this.cancel()
                    return
                }

                for (carrier in carriers) {
                    if (!carrier.isValid) continue

                    carrier.getNearbyEntities(2.0, 2.0, 2.0)
                        .filterIsInstance<Player>()
                        .forEach { target ->
                            if (!infectedIds.contains(target.entityId)) {
                                applyEffects(caster, target, amount, power)
                                infectedIds.add(target.entityId)
                                spawnLinkParticle(carrier, target)
                            }
                        }
                }
                ticks += 5
            }
        }.runTaskTimer(plugin, 0L, 5L)
    }

    // [FIXED] 修正向量计算错误
    private fun spawnLinkParticle(from: Player, to: Player) {
        val start = from.location.add(0.0, 1.0, 0.0)
        val end = to.location.add(0.0, 1.0, 0.0)

        // 正确写法：先转 Vector 再相减
        val dir = end.toVector().subtract(start.toVector())
        val dist = start.distance(end)

        if (dist > 0.1) {
            dir.normalize() // 归一化

            // 使用 Kotlin 的步进循环 (注意 Double 不支持 step，用 while 或 generateSequence)
            var d = 0.0
            while (d <= dist) {
                from.world.spawnParticle(
                    Particle.COMPOSTER,
                    start.clone().add(dir.clone().multiply(d)),
                    1, 0.0, 0.0, 0.0, 0.0
                )
                d += 0.5
            }
        }
    }
}