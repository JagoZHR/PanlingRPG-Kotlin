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

class FireAttackT5FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 1.5

    // [新增] 显式声明：PVE技能
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player

        val targetLoc: Location = p.world.rayTraceBlocks(
            p.eyeLocation,
            p.location.direction,
            22.0,
            FluidCollisionMode.NEVER,
            true
        )?.hitPosition?.toLocation(p.world)
            ?: p.location.add(p.location.direction.multiply(22))

        while (targetLoc.block.isEmpty && targetLoc.y > -64) {
            targetLoc.subtract(0.0, 0.5, 0.0)
        }
        targetLoc.add(0.0, 0.5, 0.0)
        val center = targetLoc

        // [T5] 范围扩大到 5.5
        val mainRadius = 5.5

        object : BukkitRunnable() {
            var t = 0
            override fun run() {
                if (t < 15) {
                    center.world.spawnParticle(Particle.FLAME, center, 3, 1.0, 0.5, 1.0, 0.05)
                    if (t % 5 == 0) drawCircle(center, mainRadius, Particle.FLAME)
                } else {
                    explode(p, center, mainRadius, ctx.power * 4.0) // 伤害略高
                    triggerSplitExplosions(p, center, ctx.power)
                    this.cancel()
                }
                t += 5
            }
        }.runTaskTimer(plugin, 0L, 5L)

        return true
    }

    private fun triggerSplitExplosions(p: Player, center: Location, basePower: Double) {
        // [T5] 分裂 6 个小圆，分布在半径 4.5 的圆周上
        val r = 4.5
        val subRadius = 3.0 // 小圆大小不变

        // Kotlin 循环：60度一个，共6个
        for (i in 0 until 360 step 60) {
            val rad = Math.toRadians(i.toDouble())
            val subLoc = center.clone().add(cos(rad) * r, 0.0, sin(rad) * r)

            // 延迟爆炸
            object : BukkitRunnable() {
                override fun run() {
                    explode(p, subLoc, subRadius, basePower * 2.5)
                }
            }.runTaskLater(plugin, 10L)

            // 预警
            object : BukkitRunnable() {
                var count = 0
                override fun run() {
                    if (count++ >= 2) {
                        this.cancel()
                        return
                    }
                    drawCircle(subLoc, subRadius, Particle.SMALL_FLAME)
                }
            }.runTaskTimer(plugin, 0L, 4L)
        }
    }

    private fun explode(caster: Player, loc: Location, radius: Double, damage: Double) {
        loc.world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1)
        loc.world.spawnParticle(Particle.LAVA, loc, 20, radius / 2, 0.5, radius / 2)
        loc.world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)

        loc.world.getNearbyEntities(loc, radius, 3.0, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != caster }
            .forEach { victim ->
                // [修改] 使用通用接口判断是否跳过玩家
                if (victim is Player && !this.canHitPlayers) return@forEach

                if (plugin.mobManager.canAttack(caster, victim)) {
                    plugin.elementalManager.handleElementHit(caster, victim, ElementalManager.Element.FIRE, damage)
                    MageUtil.dealSkillDamage(caster, victim, damage, true)
                }
            }
    }

    private fun drawCircle(center: Location, radius: Double, particle: Particle) {
        for (i in 0 until 360 step 15) {
            val angle = Math.toRadians(i.toDouble())
            center.world.spawnParticle(
                particle,
                center.clone().add(radius * cos(angle), 0.2, radius * sin(angle)),
                1, 0.0, 0.0, 0.0, 0.0
            )
        }
    }
}