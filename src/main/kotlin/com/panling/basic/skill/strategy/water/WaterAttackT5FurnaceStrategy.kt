package com.panling.basic.skill.strategy.water

import com.panling.basic.PanlingBasic
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.util.MageUtil
import org.bukkit.*
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import java.util.ArrayList
import kotlin.math.cos
import kotlin.math.sin

class WaterAttackT5FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.5

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player

        // 射线确定目标地面
        // 使用 Elvis 操作符
        var center = p.world.rayTraceBlocks(
            p.eyeLocation,
            p.location.direction,
            20.0,
            FluidCollisionMode.NEVER,
            true
        )?.hitPosition?.toLocation(p.world)
            ?: p.location.add(p.location.direction.multiply(15))

        // 贴地修正
        while (center.block.isEmpty && center.y > -64) {
            center.subtract(0.0, 1.0, 0.0)
        }
        if (!center.block.isEmpty) {
            center.add(0.0, 1.0, 0.0) // 抬到方块上方
        }

        // [修改] 使用 BlockDisplay 生成管珊瑚扇组成的莲花
        val parts = spawnBlockLotus(center)

        val pullCenter = center.clone().add(0.0, 0.5, 0.0)

        object : BukkitRunnable() {
            override fun run() {
                // 1. 消失特效
                pullCenter.world.spawnParticle(Particle.SPLASH, pullCenter, 150, 2.0, 1.0, 2.0, 0.1)
                pullCenter.world.spawnParticle(Particle.BUBBLE_POP, pullCenter, 50, 1.5, 1.0, 1.5, 0.1)
                pullCenter.world.playSound(pullCenter, Sound.ENTITY_GENERIC_SPLASH, 2f, 0.6f)

                parts.forEach { it.remove() }

                // 范围 5x5x5
                pullCenter.world.getNearbyEntities(pullCenter, 3.5, 3.5, 3.5)
                    .filterIsInstance<LivingEntity>()
                    .filter { it != p }
                    .forEach { victim ->

                        // [修改] 使用策略接口判断是否跳过玩家 (替代原有的硬编码)
                        if (victim is Player && !this@WaterAttackT5FurnaceStrategy.canHitPlayers) return@forEach

                        if (plugin.mobManager.canAttack(p, victim)) {
                            // [Fix] 向量计算：目标点 - 实体点 = 指向中心的向量
                            val pull = pullCenter.toVector().subtract(victim.location.toVector())

                            // 避免贴太近时的计算异常
                            if (pull.lengthSquared() >= 0.1) {
                                // 归一化并设置力度
                                pull.normalize().multiply(1.8) // 拉力大一点
                                pull.setY(0.4) // 稍微挑飞，防止被地面摩擦减速

                                // 重置原有速度，确保拉扯生效
                                victim.velocity = Vector(0, 0, 0)
                                victim.velocity = pull

                                val damage = ctx.power * 1.0
                                plugin.elementalManager.handleElementHit(p, victim, ElementalManager.Element.WATER, damage)
                                MageUtil.dealSkillDamage(p, victim, damage, true)
                            }
                        }
                    }
            }
        }.runTaskLater(plugin, 10L)

        return true
    }

    // [修改] 使用 BlockDisplay 生成巨大的莲花
    private fun spawnBlockLotus(center: Location): List<BlockDisplay> {
        val list = ArrayList<BlockDisplay>()

        // 内圈花瓣
        for (i in 0 until 360 step 60) {
            list.add(spawnPetal(center, i.toFloat(), 1.0, 1.2f, 30f))
        }
        // 外圈花瓣
        for (i in 30 until 390 step 45) {
            list.add(spawnPetal(center, i.toFloat(), 2.2, 1.8f, 15f))
        }

        return list
    }

    private fun spawnPetal(center: Location, angleDeg: Float, dist: Double, scale: Float, pitchSlope: Float): BlockDisplay {
        val rad = Math.toRadians(angleDeg.toDouble())
        val loc = center.clone().add(cos(rad) * dist, 0.0, sin(rad) * dist)

        // 设置朝向中心
        loc.yaw = angleDeg + 180

        return center.world.spawn(loc, BlockDisplay::class.java) { entity ->
            // 使用管珊瑚扇方块数据
            entity.block = Material.TUBE_CORAL_FAN.createBlockData()

            val t = entity.transformation
            t.scale.set(scale, scale, scale)

            // [关键] 旋转逻辑
            // 先让它有个仰角 (Slope)
            t.leftRotation.set(AxisAngle4f(Math.toRadians((-pitchSlope).toDouble()).toFloat(), 1f, 0f, 0f))

            entity.transformation = t
        }
    }
}