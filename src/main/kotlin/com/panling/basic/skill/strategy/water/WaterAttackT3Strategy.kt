package com.panling.basic.skill.strategy.water

import com.panling.basic.PanlingBasic
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

class WaterAttackT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.0

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val tier = MageUtil.getTierValue(p)
        val dmgMult = 0.5
        val radius = if (tier >= 4) 6.0 else 5.0

        // 特效
        object : BukkitRunnable() {
            var r = 0.5
            override fun run() {
                if (r > radius) {
                    this.cancel()
                    return
                }
                for (i in 0 until 36) {
                    val angle = Math.toRadians((i * 10).toDouble())
                    p.world.spawnParticle(
                        Particle.SNOWFLAKE,
                        p.location.add(r * cos(angle), 1.0, r * sin(angle)),
                        1, 0.0, 0.0, 0.0, 0.0
                    )
                }
                r += 0.5
            }
        }.runTaskTimer(plugin, 0L, 1L)
        p.playSound(p.location, Sound.ENTITY_PLAYER_SPLASH, 1f, 0.8f)

        // 伤害逻辑 (AOE 需要标记 Magic Damage)
        val damage = ctx.power * dmgMult
        try {
            p.getNearbyEntities(radius, 2.0, radius)
                .filterIsInstance<LivingEntity>()
                .filter { it != p }
                .forEach { victim ->
                    // [新增] AOE 过滤：如果目标是玩家且禁止PVP，直接跳过
                    if (victim is Player && !this.canHitPlayers) return@forEach

                    if (plugin.mobManager.canAttack(p, victim)) {
                        plugin.elementalManager.handleElementHit(p, victim, ElementalManager.Element.WATER, damage)
                        MageUtil.dealSkillDamage(p, victim, damage, true)
                    }
                }
        } finally {
            p.removeMetadata("pl_magic_damage", plugin) // 清除标记
        }
        return true
    }
}