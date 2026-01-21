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
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.cos
import kotlin.math.sin

class FireAttackT4FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 1.5

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player

        // 1. 确定中心点
        // 优先使用 rayTrace 的 hitPosition，否则向前延伸 20 格
        val targetLoc: Location = p.world.rayTraceBlocks(
            p.eyeLocation,
            p.location.direction,
            20.0,
            FluidCollisionMode.NEVER,
            true
        )?.hitPosition?.toLocation(p.world)
            ?: p.location.add(p.location.direction.multiply(20))

        // 贴地逻辑
        while (targetLoc.block.isEmpty && targetLoc.y > -64) {
            targetLoc.subtract(0.0, 0.5, 0.0)
        }
        targetLoc.add(0.0, 0.5, 0.0)

        val center = targetLoc

        // [修改] 范围扩大到 4.5
        val mainRadius = 4.5

        object : BukkitRunnable() {
            var t = 0
            override fun run() {
                // 蓄力特效 + [新增] 范围提示圈
                if (t < 15) {
                    center.world.spawnParticle(Particle.FLAME, center, 2, 0.5, 0.5, 0.5, 0.05)
                    // 每 5 tick 画一次圈，避免太密集
                    if (t % 5 == 0) drawCircle(center, mainRadius, Particle.FLAME)
                } else {
                    // === 第一次爆炸 ===
                    // [修改] 范围 4.5
                    explode(p, center, mainRadius, ctx.power * 3.5)

                    // === 分裂逻辑 ===
                    triggerSplitExplosions(p, center, ctx.power)

                    this.cancel()
                }
                t += 5
            }
        }.runTaskTimer(plugin, 0L, 5L)

        return true
    }

    private fun triggerSplitExplosions(p: Player, center: Location, basePower: Double) {
        val angle1 = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2)
        val angle2 = angle1 + Math.PI
        val r = 3.5 // 在大圈(4.5)内部边缘一点生成

        val loc1 = center.clone().add(cos(angle1) * r, 0.0, sin(angle1) * r)
        val loc2 = center.clone().add(cos(angle2) * r, 0.0, sin(angle2) * r)

        // [修改] 小圈范围扩大到 3.0
        val subRadius = 3.0

        object : BukkitRunnable() {
            override fun run() {
                explode(p, loc1, subRadius, basePower * 2.0)
                explode(p, loc2, subRadius, basePower * 2.0)
            }
        }.runTaskLater(plugin, 10L)

        // 预警粒子
        object : BukkitRunnable() {
            var count = 0
            override fun run() {
                if (count++ >= 2) {
                    this.cancel()
                    return
                }
                // [修改] 画出小圈的范围提示
                drawCircle(loc1, subRadius, Particle.SMALL_FLAME)
                drawCircle(loc2, subRadius, Particle.SMALL_FLAME)
            }
        }.runTaskTimer(plugin, 0L, 4L)
    }

    private fun explode(caster: Player, loc: Location, radius: Double, damage: Double) {
        loc.world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1) // 换个更大的爆炸粒子
        loc.world.spawnParticle(Particle.LAVA, loc, 15, 1.5, 0.5, 1.5)
        loc.world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)

        loc.world.getNearbyEntities(loc, radius, 3.0, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != caster }
            .forEach { victim ->
                // [新增] AOE 过滤：如果目标是玩家且禁止PVP，直接跳过
                if (victim is Player && !this.canHitPlayers) return@forEach

                if (plugin.mobManager.canAttack(caster, victim)) {
                    plugin.elementalManager.handleElementHit(caster, victim, ElementalManager.Element.FIRE, damage)
                    MageUtil.dealSkillDamage(caster, victim, damage, true)
                }
            }
    }

    // [新增] 画圆辅助方法
    private fun drawCircle(center: Location, radius: Double, particle: Particle) {
        // Kotlin 惯用的循环方式
        for (i in 0 until 360 step 15) {
            val angle = Math.toRadians(i.toDouble())
            val x = radius * cos(angle)
            val z = radius * sin(angle)
            center.world.spawnParticle(
                particle,
                center.clone().add(x, 0.2, z),
                1, 0.0, 0.0, 0.0, 0.0
            )
        }
    }
}