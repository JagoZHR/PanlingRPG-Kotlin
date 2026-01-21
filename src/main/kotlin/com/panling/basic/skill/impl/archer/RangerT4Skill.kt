package com.panling.basic.skill.impl.archer

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.ArcherSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable

// [修改] 实现 ArcherSkillStrategy 接口
class RangerT4Skill(private val plugin: PanlingBasic) :
    AbstractSkill("ARCHER_RANGER_T4", "游龙", PlayerClass.ARCHER), ArcherSkillStrategy {

    // [修复冲突] 显式重写冲突的方法
    override fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {
        // 你可以选择调用接口的默认实现，或者是父类的实现。
        // 由于你的 ArcherSkillStrategy 中该方法是空的，AbstractSkill 中大概率也是空的，
        // 这里调用哪一个都可以，或者干脆留空。
        // 标准写法是指定调用接口的默认实现：
        //super<ArcherSkillStrategy>.onProjectileHit(event, ctx)
    }

    // 1. 处理被动 Buff (由 SkillManager 定时任务调用)
    override fun onCast(ctx: SkillContext): Boolean {
        if (ctx.triggerType == SkillTrigger.PASSIVE) {
            if (ctx.player.isInWater) {
                ctx.player.addPotionEffect(PotionEffect(PotionEffectType.DOLPHINS_GRACE, 40, 2, false, false, true))
                ctx.player.addPotionEffect(PotionEffect(PotionEffectType.CONDUIT_POWER, 40, 0, false, false, true))
            }
            return true
        }
        return false
    }

    // 2. [新增] 处理射击物理逻辑 (实现接口方法)
    override fun onShoot(player: Player, arrow: AbstractArrow, event: EntityShootBowEvent) {
        // 只有在水中才生效
        if (!player.isInWater) return

        // 修正初速度 (满弦修正)
        val force = event.force
        val intendedSpeed = force * 3.0
        arrow.velocity = arrow.velocity.normalize().multiply(intendedSpeed)

        player.world.playSound(player.location, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.5f, 1.5f)

        // 启动水下无阻力任务
        object : BukkitRunnable() {
            val speed = arrow.velocity.length()

            override fun run() {
                if (arrow.isDead || arrow.isOnGround) {
                    this.cancel()
                    return
                }
                if (arrow.isInWater) {
                    arrow.velocity = arrow.velocity.normalize().multiply(speed)
                    arrow.world.spawnParticle(Particle.BUBBLE_POP, arrow.location, 1)
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }
}