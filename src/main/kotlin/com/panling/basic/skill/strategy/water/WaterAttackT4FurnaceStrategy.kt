package com.panling.basic.skill.strategy.water

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.util.MageUtil
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.cos
import kotlin.math.sin

class WaterAttackT4FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0 // 瞬发

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        // T3是5-6格，T4扩大3格 -> 8格
        val radius = 8.0
        val damage = ctx.power * 0.5 // 范围大，单次伤害系数适中

        // 扩散特效
        object : BukkitRunnable() {
            var r = 0.5
            override fun run() {
                if (r > radius) {
                    this.cancel()
                    return
                }
                for (i in 0 until 72) { // 粒子密度增加
                    val angle = Math.toRadians((i * 5).toDouble())
                    val x = r * cos(angle)
                    val z = r * sin(angle)
                    // 双层粒子
                    p.world.spawnParticle(
                        Particle.SNOWFLAKE,
                        p.location.add(x, 0.5, z),
                        1, 0.0, 0.0, 0.0, 0.0
                    )
                    p.world.spawnParticle(
                        Particle.FALLING_WATER,
                        p.location.add(x, 0.2, z),
                        1, 0.0, 0.0, 0.0, 0.0
                    )
                }
                r += 0.8 // 扩散速度快一点
            }
        }.runTaskTimer(plugin, 0L, 1L)

        p.playSound(p.location, Sound.ENTITY_GENERIC_SPLASH, 1f, 0.5f)

        // 伤害逻辑
        var hitCount = 0
        p.getNearbyEntities(radius, 3.0, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != p }
            .forEach { victim ->
                // [新增] AOE 过滤：如果目标是玩家且禁止PVP，直接跳过
                if (victim is Player && !this.canHitPlayers) return@forEach

                if (plugin.mobManager.canAttack(p, victim)) {
                    plugin.elementalManager.handleElementHit(p, victim, ElementalManager.Element.WATER, damage)
                    MageUtil.dealSkillDamage(p, victim, damage, true)

                    // 增强减速：BuffType.SLOW, 持续60 ticks (3秒), 强度 0.4 (40%减速)
                    plugin.buffManager.addBuff(victim, BuffType.SLOW, 60, 0.4, false)

                    hitCount++
                }
            }

        return true
    }
}