package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.WarriorSkillStrategy
import com.panling.basic.util.CombatUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector

class ZidianActiveSkill(private val plugin: PanlingBasic) :
    AbstractSkill("ZIDIAN2", "雷动九天", PlayerClass.WARRIOR), WarriorSkillStrategy {

    override val canHitPlayers: Boolean = false

    override var castTime: Double = 0.0

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    override fun cast(ctx: SkillContext): Boolean {
        // 1. 触发检查
        if (ctx.triggerType != SkillTrigger.SHIFT_RIGHT) return false

        val p = ctx.player

        // 2. [核心修正] 物理冲锋逻辑 (Rapid Displacement)
        // 获取玩家朝向向量
        val dir = p.location.direction.clone().normalize()

        // 设置冲锋速度 (根据原版手感，通常是 1.5 ~ 2.5 左右，这里保持向量乘法)
        // 如果原版有 setY(0.5) 防止地形卡顿，这里也应该保留
        val velocity = dir.multiply(2.0).setY(0.2)

        p.velocity = velocity

        // 冲锋音效
        p.world.playSound(p.location, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.5f)
        p.world.playSound(p.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 1.5f)

        // 3. 启动延时任务结算伤害 (模拟冲锋过程或到达终点后的爆发)
        // 通常冲锋需要几 tick 的时间，这里延迟 5 tick (0.25秒) 结算伤害，符合"位移后爆发"的感觉
        object : BukkitRunnable() {
            override fun run() {
                if (!p.isOnline) return

                // 执行爆发逻辑
                performDischarge(p)
            }
        }.runTaskLater(plugin, 5L)

        return true
    }

    private fun performDischarge(p: Player) {
        // 1. 索敌逻辑 (6格半径)
        val targets = ArrayList<LivingEntity>()

        p.getNearbyEntities(6.0, 4.0, 6.0)
            .filterIsInstance<LivingEntity>()
            .filter { it != p }
            .forEach { e ->
                // 目标过滤
                if (e is Player && !this.canHitPlayers) return@forEach
                if (!plugin.mobManager.canAttack(p, e)) return@forEach
                if (!CombatUtil.isValidTarget(p, e)) return@forEach

                targets.add(e)
            }

        val targetCount = targets.size

        // 全局特效：爆发点
        p.world.spawnParticle(Particle.FLASH, p.location.add(0.0, 1.0, 0.0), 1)

        if (targetCount > 0) {
            // 2. 伤害计算 (分摊机制)
            val baseDmg = plugin.statCalculator.getPlayerTotalStat(p, BasicKeys.ATTR_PHYSICAL_DAMAGE)

            // 总伤害 = (面板 * 4.0) * (1.0 + 目标数 * 0.5)
            val totalDamage = (baseDmg * 4.0) * (1.0 + (targetCount * 0.5))

            // 单体伤害 = 总伤害 / (目标数 + 1)
            val damagePerTarget = totalDamage / (targetCount + 1)

            // 3. 结算伤害与特效
            for (victim in targets) {
                val victimLoc = victim.location

                // [特效] 紫色落雷光柱
                var y = 0.0
                while (y <= 4.0) {
                    victim.world.spawnParticle(
                        Particle.DUST,
                        victimLoc.clone().add(0.0, y, 0.0),
                        2, 0.1, 0.1, 0.1,
                        Particle.DustOptions(Color.fromRGB(170, 0, 255), 2.0f)
                    )
                    y += 0.5
                }

                // [特效] 核心高亮
                victim.world.spawnParticle(
                    Particle.END_ROD,
                    victimLoc.clone().add(0.0, 1.5, 0.0),
                    5, 0.2, 0.5, 0.2, 0.05
                )

                // [特效] 地面炸裂
                victim.world.spawnParticle(
                    Particle.WITCH,
                    victimLoc,
                    15, 0.5, 0.5, 0.5, 0.2
                )

                // 造成分摊后的伤害 (允许吸血)
                CombatUtil.dealPhysicalSkillDamage(p, victim, damagePerTarget, true)
            }

            p.sendActionBar(Component.text("§5[雷动九天] 命中 $targetCount 个目标!").color(NamedTextColor.DARK_PURPLE))
        } else {
            p.sendActionBar(Component.text("§7[雷动九天] 未命中目标").color(NamedTextColor.GRAY))
        }
    }
}