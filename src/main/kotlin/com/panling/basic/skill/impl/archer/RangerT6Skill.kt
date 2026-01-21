package com.panling.basic.skill.impl.archer

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.ArcherSkillStrategy
import com.panling.basic.util.CombatUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.ArrayDeque
import kotlin.math.cos
import kotlin.math.sin

class RangerT6Skill(private val plugin: PanlingBasic) :
    AbstractSkill("ARCHER_RANGER_T6", "惊鸿掠影", PlayerClass.ARCHER), ArcherSkillStrategy {

    companion object {
        private const val META_MARK = "pl_ranger_t6_mark"
        private const val MARK_DURATION = 300 * 1000L
    }

    // [修复冲突] 显式重写冲突的方法
    override fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {
        // 你可以选择调用接口的默认实现，或者是父类的实现。
        // 由于你的 ArcherSkillStrategy 中该方法是空的，AbstractSkill 中大概率也是空的，
        // 这里调用哪一个都可以，或者干脆留空。
        // 标准写法是指定调用接口的默认实现：
        //super<ArcherSkillStrategy>.onProjectileHit(event, ctx)
    }

    // [屏蔽通用判定]
    override fun onCast(ctx: SkillContext): Boolean {
        return ctx.triggerType != SkillTrigger.SHIFT_RIGHT
    }

    override fun onShoot(player: Player, arrow: AbstractArrow, event: EntityShootBowEvent) {
        val crossbow = event.bow ?: return

        var isSnipe = player.isSneaking

        // === 独立触发器检查 ===
        if (isSnipe) {
            val snipeCtx = SkillContext(
                player, null, crossbow, arrow, player.location, 0.0, SkillTrigger.SHIFT_RIGHT
            )
            // 此时 Key 应该是干净的
            var canCastSnipe = plugin.skillManager.tryApplyCooldown(this, snipeCtx)

            if (canCastSnipe) {
                if (!checkCost(snipeCtx, plugin.playerDataManager)) {
                    canCastSnipe = false
                } else {
                    payCost(snipeCtx, plugin.playerDataManager)
                    player.sendActionBar(Component.text("§a[战斗] 释放技能: 惊鸿掠影").color(NamedTextColor.GREEN))
                }
            }

            if (!canCastSnipe) {
                isSnipe = false // 降级
            }
        }

        val piercingLevel = crossbow.getEnchantmentLevel(Enchantment.PIERCING)
        val maxHits = 1 + piercingLevel
        val multishot = crossbow.containsEnchantment(Enchantment.MULTISHOT)

        val phys = plugin.statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        val rangeMult = plugin.playerDataManager.getPlayerClass(player).rangeMultiplier
        val projectileDamage = phys * rangeMult * 2.0

        arrow.remove()

        val startLoc = player.eyeLocation.subtract(0.0, 0.2, 0.0)
        val direction = startLoc.direction

        if (isSnipe) {
            player.world.playSound(startLoc, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.2f)
        } else {
            player.world.playSound(startLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f)
        }

        val mode = if (isSnipe) 1 else 0
        MeteorTask(player, startLoc, direction, maxHits, projectileDamage, mode).runTaskTimer(plugin, 1L, 1L)

        if (multishot) {
            val left = rotateVector(direction, 15.0)
            val right = rotateVector(direction, -15.0)
            MeteorTask(player, startLoc, left, maxHits, projectileDamage, mode).runTaskTimer(plugin, 1L, 1L)
            MeteorTask(player, startLoc, right, maxHits, projectileDamage, mode).runTaskTimer(plugin, 1L, 1L)
        }
    }

    private fun rotateVector(vector: Vector, angleDegrees: Double): Vector {
        val angle = Math.toRadians(angleDegrees)
        val x = vector.x
        val z = vector.z
        val cos = cos(angle)
        val sin = sin(angle)
        return Vector(x * cos - z * sin, vector.y, x * sin + z * cos).normalize()
    }

    // =========================================================
    // [重写] 链式爆炸逻辑 (BFS + 批量结算)
    // =========================================================
    private fun processChainReaction(player: Player, initialTarget: LivingEntity) {
        // 1. 初始化数据结构
        // 待处理队列 (相当于递归栈)
        val processingQueue = ArrayDeque<LivingEntity>()
        // 伤害计数器: 记录每个实体被炸了多少次 <实体, 次数>
        val damageCounts = HashMap<LivingEntity, Int>()
        // 防止同一个标记被重复入队处理
        val processedMarks = HashSet<Int>()

        // 2. 将初始目标加入队列
        processingQueue.add(initialTarget)
        // 注意：初始目标的标记在调用此方法前已经被 removeMark 了，所以不需要在这里 remove

        val explosionBaseDmg = plugin.statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        var safetyLoop = 0 // 死循环熔断

        // 3. 开始 BFS 广度搜索
        while (!processingQueue.isEmpty() && safetyLoop < 100) {
            val source = processingQueue.poll()
            val center = source.location
            safetyLoop++

            // 播放爆炸特效 (每个爆炸源播放一次)
            center.world.spawnParticle(Particle.EXPLOSION, center.add(0.0, 1.0, 0.0), 1)
            center.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f)

            // 扫描周围受害者
            for (e in center.world.getNearbyEntities(center, 2.5, 2.5, 2.5)) {
                if (e is LivingEntity && e != player) {
                    val victim = e
                    if (!CombatUtil.isValidTarget(player, victim)) continue

                    // A. 记录伤害 (不立即造成伤害，只计数)
                    // 使用 Kotlin 的 merge 或 getOrPut
                    damageCounts.merge(victim, 1, Integer::sum)

                    // B. 链式传导判定
                    // 只有当受害者有标记，且未被处理过时，才加入队列成为新的爆炸源
                    if (hasMark(victim) && !processedMarks.contains(victim.entityId)) {
                        // 移除标记
                        removeMark(victim)
                        processedMarks.add(victim.entityId)

                        // 视觉反馈：标记引爆
                        try {
                            victim.world.spawnParticle(Particle.POOF, victim.eyeLocation, 1)
                        } catch (ignored: Exception) {}

                        // 加入队列，下一轮它也会爆炸
                        processingQueue.add(victim)
                    }
                }
            }
        }

        // 4. [核心] 批量结算伤害
        // 遍历 map，一次性赋予总伤害，彻底解决无敌帧和伤害合并问题
        for ((victim, count) in damageCounts) {
            if (count > 0 && victim.isValid) {
                val totalDamage = explosionBaseDmg * count * 5

                // 强制清空无敌帧 (双重保险)
                victim.noDamageTicks = 0

                // 造成一次巨额伤害
                CombatUtil.dealPhysicalSkillDamage(player, victim, totalDamage)
            }
        }
    }

    // === 标记系统 ===
    private fun applyMark(entity: LivingEntity) {
        entity.setMetadata(META_MARK, FixedMetadataValue(plugin, System.currentTimeMillis()))
        entity.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, 6000, 0, false, false))
        try {
            entity.world.spawnParticle(Particle.RAID_OMEN, entity.eyeLocation.add(0.0, 0.6, 0.0), 1)
        } catch (ignored: Exception) {}
    }

    private fun hasMark(entity: LivingEntity): Boolean {
        if (!entity.hasMetadata(META_MARK)) return false
        val time = entity.getMetadata(META_MARK)[0].asLong()
        if (System.currentTimeMillis() - time > MARK_DURATION) {
            removeMark(entity)
            return false
        }
        return true
    }

    private fun removeMark(entity: LivingEntity) {
        entity.removeMetadata(META_MARK, plugin)
        entity.removePotionEffect(PotionEffectType.GLOWING)
    }

    // === MeteorTask ===
    private inner class MeteorTask(
        private val shooter: Player,
        startLoc: Location,
        direction: Vector,
        private var remainingHits: Int,
        private val damage: Double,
        private val mode: Int
    ) : BukkitRunnable() {

        private var currentLoc: Location = startLoc.clone()
        private var velocity: Vector = direction.normalize().multiply(0.7)
        private var target: LivingEntity? = null
        private val hitEntities = HashSet<Int>()
        private var tick = 0
        private val maxTicks = 140

        override fun run() {
            try {
                if (tick >= maxTicks || remainingHits <= 0) {
                    safeEffect(Particle.POOF)
                    this.cancel()
                    return
                }

                // [修复索敌 Bug]
                if (target != null && hitEntities.contains(target!!.entityId)) {
                    target = null
                }

                if (target != null && !CombatUtil.isValidTarget(shooter, target!!)) {
                    target = null
                }

                if (target == null || target!!.isDead || !target!!.isValid || target!!.location.distanceSquared(currentLoc) > 400) {
                    target = findNearestTarget()
                }

                if (target != null) {
                    val toTarget = target!!.eyeLocation.subtract(0.0, 0.3, 0.0).toVector().subtract(currentLoc.toVector()).normalize()
                    velocity = velocity.normalize().multiply(0.85).add(toTarget.multiply(0.15)).normalize().multiply(1.2)
                }
                currentLoc.add(velocity)

                if (mode == 1) {
                    currentLoc.world.spawnParticle(Particle.FLAME, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)
                    safeEffect(Particle.FIREWORK)
                } else {
                    currentLoc.world.spawnParticle(Particle.END_ROD, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)
                    safeEffect(Particle.FIREWORK)
                }

                checkCollision()
                tick++
            } catch (e: Exception) {
                this.cancel()
                e.printStackTrace()
            }
        }

        private fun findNearestTarget(): LivingEntity? {
            // 使用 sequence 优化查找
            return currentLoc.world.getNearbyEntities(currentLoc, 10.0, 10.0, 10.0)
                .asSequence()
                .filterIsInstance<LivingEntity>()
                .filter { it != shooter }
                // [核心修复] 使用 CombatUtil 的统一索敌逻辑
                .filter { CombatUtil.isValidTarget(shooter, it) }
                .filter { !hitEntities.contains(it.entityId) }
                .minByOrNull { it.location.distanceSquared(currentLoc) }
        }

        private fun checkCollision() {
            if (currentLoc.block.type.isSolid) {
                safeEffect(Particle.POOF)
                this.cancel()
                return
            }

            for (e in currentLoc.world.getNearbyEntities(currentLoc, 0.8, 0.8, 0.8)) {
                if (e is LivingEntity && e != shooter) {
                    val victim = e
                    if (hitEntities.contains(victim.entityId)) continue
                    if (!plugin.mobManager.canAttack(shooter, victim)) continue
                    if (!CombatUtil.isValidTarget(shooter, victim)) continue

                    hitEntities.add(victim.entityId)
                    remainingHits--

                    // 1. 造成流星本体伤害
                    CombatUtil.dealPhysicalSkillDamage(shooter, victim, damage)

                    if (mode == 0) {
                        // 标记模式
                        applyMark(victim)
                        safeEffect(Particle.FIREWORK)
                        victim.world.playSound(victim.location, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6f, 2.0f)
                    } else {
                        // 引爆模式
                        if (hasMark(victim)) {
                            // 移除标记，作为链式反应的起点
                            removeMark(victim)
                            // 调用新的批量处理逻辑
                            processChainReaction(shooter, victim)
                        } else {
                            victim.world.playSound(victim.location, Sound.BLOCK_ANVIL_LAND, 0.4f, 2.0f)
                        }
                    }

                    if (remainingHits <= 0) {
                        safeEffect(Particle.POOF)
                        this.cancel()
                        return
                    }
                }
            }
        }

        private fun safeEffect(p: Particle) {
            try {
                currentLoc.world.spawnParticle(p, currentLoc, 2, 0.1, 0.1, 0.1, 0.05)
            } catch (ignored: Exception) {}
        }
    }
}