package com.panling.basic.skill.strategy.metal

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable

class MetalSupportT5FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player

        // [T5] 范围扩大到 4 格，选取最近的 4 名队友 (T4是3名)
        val targets = p.getNearbyEntities(4.0, 4.0, 4.0)
            .asSequence()
            .filterIsInstance<Player>()
            .sortedBy { it.location.distanceSquared(p.location) }
            .take(4)
            .toMutableList()

        targets.add(p)

        for (target in targets) {
            // 1. 基础双抗提升 (10%)
            plugin.buffManager.addBuff(target, BuffType.DEFENSE_UP, 200, 1.1, true)

            // 2. [T5] 施加防御反伤标记 (持续10秒)
            // 这里我们用一个 Metadata 标记，并通过延时任务移除
            target.setMetadata("pl_metal_thorns_t5", FixedMetadataValue(plugin, true))

            object : BukkitRunnable() {
                override fun run() {
                    if (target.isOnline) {
                        target.removeMetadata("pl_metal_thorns_t5", plugin)
                    }
                }
            }.runTaskLater(plugin, 200L) // 10秒

            // 3. 元素联动 (金生水)
            plugin.elementalManager.handleSupportHit(p, target, ElementalManager.Element.METAL, ctx.power)

            // 特效：金色护盾感
            target.world.spawnParticle(
                Particle.WAX_OFF,
                target.location.add(0.0, 1.0, 0.0),
                15, 0.5, 0.5, 0.5
            )
        }

        p.playSound(p.location, Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f)
        return true
    }
}