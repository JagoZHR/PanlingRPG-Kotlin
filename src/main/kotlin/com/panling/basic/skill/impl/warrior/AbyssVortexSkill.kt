package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.util.CombatUtil
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector

class AbyssVortexSkill(private val plugin: PanlingBasic) :
    AbstractSkill("ABYSS_VORTEX", "深渊漩涡", PlayerClass.WARRIOR) {

    override var castTime: Double = 0.0

    override fun onCast(ctx: SkillContext): Boolean {
        val player = ctx.player
        val radius = 6.0
        val pullStrength = 1.5

        var pulled = 0
        player.world.getNearbyEntities(player.location, radius, 3.0, radius).forEach { e ->
            if (e is LivingEntity && CombatUtil.isValidTarget(player, e)) {
                // 拉向玩家
                val toward = player.location.toVector().subtract(e.location.toVector()).normalize()
                e.velocity = toward.multiply(pullStrength).setY(0.6)
                pulled++
            }
        }

        if (pulled > 0) {
            // 延迟 0.5 秒后击飞
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 0.5f)
                player.world.playSound(player.location, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 1.0f, 0.3f)
                player.world.spawnParticle(Particle.EXPLOSION, player.location.add(0.0, 1.0, 0.0), 3, 0.5, 0.5, 0.5, 0.0)
                player.world.spawnParticle(Particle.BUBBLE_POP, player.location, 30, 3.0, 1.5, 3.0, 0.05)

                player.world.getNearbyEntities(player.location, 2.5, 2.0, 2.5).forEach { e ->
                    if (e is LivingEntity && CombatUtil.isValidTarget(player, e)) {
                        e.velocity = Vector(0.0, 1.2, 0.0).add(
                            e.location.toVector().subtract(player.location.toVector()).normalize().multiply(0.8)
                        )
                    }
                }
            }, 10L) // 10 ticks = 0.5s

            player.world.playSound(player.location, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.8f, 0.6f)
            // 漩涡粒子环
            for (i in 0..15) {
                val angle = Math.toRadians(i * 22.5)
                val x = Math.cos(angle) * 2.5
                val z = Math.sin(angle) * 2.5
                player.world.spawnParticle(
                    Particle.BUBBLE, player.location.clone().add(x, 0.2, z),
                    3, 0.3, 0.8, 0.3, 0.0
                )
            }
        }

        return true
    }
}
