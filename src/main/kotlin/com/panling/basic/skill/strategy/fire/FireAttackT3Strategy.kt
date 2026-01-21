package com.panling.basic.skill.strategy.fire

import com.panling.basic.PanlingBasic
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.util.MageUtil
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.cos
import kotlin.math.sin

class FireAttackT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 1.5

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val tier = MageUtil.getTierValue(p)

        // 射线逻辑
        // 使用 Elvis 操作符处理 rayTraceBlocks 返回 null 或 hitBlock 为 null 的情况
        val targetLoc: Location = p.world.rayTraceBlocks(
            p.eyeLocation,
            p.location.direction,
            20.0,
            FluidCollisionMode.NEVER,
            true
        )?.hitBlock?.location?.add(0.5, 1.0, 0.5)
            ?: p.location.add(p.location.direction.multiply(10)).apply {
                y = p.world.getHighestBlockYAt(this).toDouble() + 1
            }

        // 预警
        object : BukkitRunnable() {
            var t = 0
            override fun run() {
                if (t >= 30) {
                    this.cancel()
                    return
                }
                for (i in 0 until 20) {
                    val angle = Math.toRadians((i * 18).toDouble())
                    targetLoc.world.spawnParticle(
                        Particle.FLAME,
                        targetLoc.clone().add(cos(angle) * 3, 0.2, sin(angle) * 3),
                        1, 0.0, 0.0, 0.0, 0.0
                    )
                }
                t += 5
            }
        }.runTaskTimer(plugin, 0L, 5L)

        // 爆发
        object : BukkitRunnable() {
            override fun run() {
                targetLoc.world.spawnParticle(Particle.EXPLOSION, targetLoc, 1)
                targetLoc.world.playSound(targetLoc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)

                val mult = if (tier >= 4) 3.5 else 3.0
                val damage = ctx.power * mult

                // 标记魔法伤害 (因为是在 Runnable 中，所以很关键)
                try {
                    targetLoc.world.getNearbyEntities(targetLoc, 3.5, 4.0, 3.5)
                        .filterIsInstance<LivingEntity>()
                        .filter { it != p }
                        .forEach { victim ->
                            // [新增] AOE 过滤：如果目标是玩家且禁止PVP，直接跳过
                            if (victim is Player && !this@FireAttackT3Strategy.canHitPlayers) return@forEach

                            if (plugin.mobManager.canAttack(p, victim)) {
                                plugin.elementalManager.handleElementHit(p, victim, ElementalManager.Element.FIRE, damage)
                                MageUtil.dealSkillDamage(p, victim, damage, true)
                            }
                        }
                } finally {
                    p.removeMetadata("pl_magic_damage", plugin)
                }
            }
        }.runTaskLater(plugin, 30L)
        return true
    }
}