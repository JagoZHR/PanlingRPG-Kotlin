package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.WarriorSkillStrategy
import com.panling.basic.util.CombatUtil
import net.kyori.adventure.text.Component
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.cos
import kotlin.math.sin

class GoldenBellT5Skill(private val plugin: PanlingBasic) :
    AbstractSkill("WARRIOR_GOLDEN_BELL_T5", "剑气", PlayerClass.WARRIOR), WarriorSkillStrategy {

    override var castTime: Double = 0.0

    companion object {
        private const val META_ARRAY_DATA = "pl_golden_bell_t5_array"
        private const val ARRAY_RADIUS = 5.0
    }

    override val canHitPlayers: Boolean = false

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    override fun cast(ctx: SkillContext): Boolean {
        // 1. 触发判断: SHIFT_RIGHT (主动开启)
        if (ctx.triggerType != SkillTrigger.SHIFT_RIGHT) return false

        val owner = ctx.player

        // 2. 检查互斥 (防止重复开启)
        if (owner.hasMetadata(META_ARRAY_DATA)) {
            owner.sendMessage("§c剑气领域已在运行中！")
            return false
        }

        // 3. 记录中心点
        val center = owner.location.clone()

        // 标记玩家正在释放 (存入中心点位置)
        owner.setMetadata(META_ARRAY_DATA, FixedMetadataValue(plugin, center))

        owner.world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.5f)
        owner.sendActionBar(Component.text("§e[剑气] 领域展开!"))

        // 4. 计算伤害 (防御力 * 0.5, 保底 2.0)
        val defense = plugin.statCalculator.getPlayerTotalStat(owner, BasicKeys.ATTR_DEFENSE)
        val damage = (defense * 0.5).coerceAtLeast(2.0)

        // 5. 启动任务 (持续 15秒)
        object : BukkitRunnable() {
            var tick = 0

            override fun run() {
                // 结束条件：玩家下线 / 死亡 / 时间到 (15秒 = 300 ticks)
                if (tick >= 300 || !owner.isOnline || owner.isDead) {
                    owner.removeMetadata(META_ARRAY_DATA, plugin)
                    this.cancel()
                    return
                }

                // === 特效逻辑 ===
                // 1. 边缘光圈 (每5 tick)
                if (tick % 5 == 0) {
                    // Kotlin 步进循环: 0, 15, 30 ... 345
                    for (i in 0 until 360 step 15) {
                        val rad = Math.toRadians((i + tick).toDouble()) // 旋转效果
                        val x = cos(rad) * ARRAY_RADIUS
                        val z = sin(rad) * ARRAY_RADIUS
                        center.world.spawnParticle(
                            Particle.CRIT,
                            center.clone().add(x, 0.2, z),
                            0, 0.0, 0.0, 0.0
                        )
                    }
                }

                // 2. 内部剑气 (随机生成)
                if (tick % 10 == 0) {
                    val r = Math.random() * ARRAY_RADIUS
                    val angle = Math.random() * 2 * Math.PI
                    val swordLoc = center.clone().add(cos(angle) * r, 0.0, sin(angle) * r)

                    // 模拟剑气冲天 (END_ROD 向上)
                    center.world.spawnParticle(
                        Particle.END_ROD,
                        swordLoc.add(0.0, 0.5, 0.0),
                        1, 0.0, 0.1, 0.0, 0.0
                    )
                }

                // === 伤害逻辑 ===
                // 每秒 (20 tick) 结算一次伤害
                if (tick % 20 == 0) {
                    center.world.getNearbyEntities(center, ARRAY_RADIUS, 3.0, ARRAY_RADIUS)
                        .filterIsInstance<LivingEntity>()
                        .filter { it != owner }
                        .forEach { victim ->

                            // 目标过滤：PVE 保护 & 阵营检查
                            if (victim is Player && !this@GoldenBellT5Skill.canHitPlayers) return@forEach
                            if (!plugin.mobManager.canAttack(owner, victim)) return@forEach

                            // 造成物理技能伤害
                            CombatUtil.dealPhysicalSkillDamage(owner, victim, damage)

                            // 元素反应：金系 (METAL)
                            plugin.elementalManager.handleElementHit(owner, victim, ElementalManager.Element.METAL, damage)

                            // 击中反馈
                            victim.world.playSound(victim.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.5f)
                            victim.world.spawnParticle(Particle.SWEEP_ATTACK, victim.location.add(0.0, 1.0, 0.0), 1)
                        }
                }

                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)

        return true
    }
}