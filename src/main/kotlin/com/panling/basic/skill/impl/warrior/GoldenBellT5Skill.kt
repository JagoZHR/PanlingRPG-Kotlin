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
import org.bukkit.event.entity.EntityDamageByEntityEvent
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
        val owner = ctx.player

        when (ctx.triggerType) {
            // SHIFT_RIGHT: 创建剑阵
            SkillTrigger.SHIFT_RIGHT -> {
                if (owner.hasMetadata(META_ARRAY_DATA)) {
                    owner.sendMessage("§c剑气领域已在运行中！")
                    return false
                }

                val center = owner.location.clone()
                owner.setMetadata(META_ARRAY_DATA, FixedMetadataValue(plugin, center))

                owner.world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.5f)
                owner.sendActionBar(Component.text("§e[剑气] 领域展开!"))

                // 仅负责特效（伤害由 onDamaged 处理）
                object : BukkitRunnable() {
                    var tick = 0

                    override fun run() {
                        if (tick >= 300 || !owner.isOnline || owner.isDead) {
                            owner.removeMetadata(META_ARRAY_DATA, plugin)
                            this.cancel()
                            return
                        }

                        if (tick % 5 == 0) {
                            for (i in 0 until 360 step 15) {
                                val rad = Math.toRadians((i + tick).toDouble())
                                val x = cos(rad) * ARRAY_RADIUS
                                val z = sin(rad) * ARRAY_RADIUS
                                center.world.spawnParticle(
                                    Particle.CRIT,
                                    center.clone().add(x, 0.2, z),
                                    0, 0.0, 0.0, 0.0
                                )
                            }
                        }

                        if (tick % 10 == 0) {
                            val r = Math.random() * ARRAY_RADIUS
                            val angle = Math.random() * 2 * Math.PI
                            val swordLoc = center.clone().add(cos(angle) * r, 0.0, sin(angle) * r)
                            center.world.spawnParticle(
                                Particle.END_ROD,
                                swordLoc.add(0.0, 0.5, 0.0),
                                1, 0.0, 0.1, 0.0, 0.0
                            )
                        }

                        tick++
                    }
                }.runTaskTimer(plugin, 0L, 1L)

                return true
            }

            // DAMAGED: 受击时 let execute() 成功，实际伤害由 onDamaged() 处理
            SkillTrigger.DAMAGED -> {
                return owner.hasMetadata(META_ARRAY_DATA)
            }

            else -> return false
        }
    }

    // 受击反击：对剑阵内所有敌人造成 20% 防御伤害
    override fun onDamaged(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        val owner = ctx.player
        val center = owner.getMetadata(META_ARRAY_DATA).firstOrNull()?.value() as? org.bukkit.Location ?: return

        val defense = plugin.statCalculator.getPlayerTotalStat(owner, BasicKeys.ATTR_DEFENSE)
        val damage = (defense * 0.2).coerceAtLeast(1.0)

        center.world!!.getNearbyEntities(center, ARRAY_RADIUS, 3.0, ARRAY_RADIUS)
            .filterIsInstance<LivingEntity>()
            .filter { it != owner }
            .forEach { victim ->
                if (victim is Player && !this.canHitPlayers) return@forEach
                if (!plugin.mobManager.canAttack(owner, victim)) return@forEach
                CombatUtil.dealPhysicalSkillDamage(owner, victim, damage)
                plugin.elementalManager.handleElementHit(owner, victim, ElementalManager.Element.METAL, damage)
            }

        // 视觉反馈
        owner.world.playSound(owner.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.5f)
        owner.world.spawnParticle(Particle.SWEEP_ATTACK, owner.location.add(0.0, 1.0, 0.0), 3)
    }
}
