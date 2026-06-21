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

    override fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {}

    override fun onCast(ctx: SkillContext): Boolean {
        return ctx.triggerType != SkillTrigger.SHIFT_RIGHT
    }

    override fun onShoot(player: Player, arrow: AbstractArrow, event: EntityShootBowEvent) {
        val crossbow = event.bow ?: return

        var isSnipe = player.isSneaking

        if (isSnipe) {
            val snipeCtx = SkillContext(player, null, crossbow, arrow, player.location, 0.0, SkillTrigger.SHIFT_RIGHT)
            var canCastSnipe = plugin.skillManager.tryApplyCooldown(this, snipeCtx)
            if (canCastSnipe) {
                if (!checkCost(snipeCtx, plugin.playerDataManager)) canCastSnipe = false
                else {
                    payCost(snipeCtx, plugin.playerDataManager)
                    player.sendActionBar(Component.text("§a[战斗] 释放技能: 惊鸿掠影").color(NamedTextColor.GREEN))
                }
            }
            if (!canCastSnipe) isSnipe = false
        }

        val piercingLevel = crossbow.getEnchantmentLevel(Enchantment.PIERCING)
        val maxHits = 1 + piercingLevel
        val multishot = crossbow.containsEnchantment(Enchantment.MULTISHOT)

        val phys = plugin.statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        val rangeMult = plugin.playerDataManager.getPlayerClass(player).rangeMultiplier
        val baseDamage = phys * rangeMult * 2.0

        arrow.remove()

        val startLoc = player.eyeLocation.subtract(0.0, 0.2, 0.0)
        val direction = startLoc.direction

        if (isSnipe) player.world.playSound(startLoc, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.2f)
        else player.world.playSound(startLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f)

        val mode = if (isSnipe) 1 else 0
        MeteorTask(player, startLoc, direction, maxHits, baseDamage, mode).runTaskTimer(plugin, 1L, 1L)

        if (multishot) {
            val left = rotateVector(direction, 15.0)
            val right = rotateVector(direction, -15.0)
            MeteorTask(player, startLoc, left, maxHits, baseDamage, mode).runTaskTimer(plugin, 1L, 1L)
            MeteorTask(player, startLoc, right, maxHits, baseDamage, mode).runTaskTimer(plugin, 1L, 1L)
        }
    }

    private fun rotateVector(vector: Vector, angleDegrees: Double): Vector {
        val angle = Math.toRadians(angleDegrees)
        val x = vector.x; val z = vector.z
        val c = cos(angle); val s = sin(angle)
        return Vector(x * c - z * s, vector.y, x * s + z * c).normalize()
    }

    private fun forceDeal(shooter: Player, victim: LivingEntity, damage: Double) {
        shooter.setMetadata("pl_force_archer_passive", FixedMetadataValue(plugin, true))
        CombatUtil.dealPhysicalSkillDamage(shooter, victim, damage)
        shooter.removeMetadata("pl_force_archer_passive", plugin)
    }

    private fun processChainReaction(player: Player, initialTarget: LivingEntity) {
        val processingQueue = ArrayDeque<LivingEntity>()
        val damageCounts = HashMap<LivingEntity, Int>()
        val processedMarks = HashSet<Int>()
        processingQueue.add(initialTarget)

        val explosionBaseDmg = plugin.statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        var safetyLoop = 0

        while (!processingQueue.isEmpty() && safetyLoop < 100) {
            val source = processingQueue.poll()
            val center = source.location
            safetyLoop++

            center.world.spawnParticle(Particle.EXPLOSION, center.add(0.0, 1.0, 0.0), 1)
            center.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f)

            for (e in center.world.getNearbyEntities(center, 2.5, 2.5, 2.5)) {
                if (e is LivingEntity && e != player) {
                    val victim = e
                    if (!CombatUtil.isValidTarget(player, victim)) continue
                    damageCounts.merge(victim, 1, Integer::sum)
                    if (hasMark(victim) && !processedMarks.contains(victim.entityId)) {
                        removeMark(victim)
                        processedMarks.add(victim.entityId)
                        try { victim.world.spawnParticle(Particle.POOF, victim.eyeLocation, 1) } catch (_: Exception) {}
                        processingQueue.add(victim)
                    }
                }
            }
        }

        for ((victim, count) in damageCounts) {
            if (count > 0 && victim.isValid) {
                val totalDamage = explosionBaseDmg * count * 5
                forceDeal(player, victim, totalDamage)
            }
        }
    }

    private fun applyMark(entity: LivingEntity) {
        entity.setMetadata(META_MARK, FixedMetadataValue(plugin, System.currentTimeMillis()))
        entity.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, 6000, 0, false, false))
        try { entity.world.spawnParticle(Particle.RAID_OMEN, entity.eyeLocation.add(0.0, 0.6, 0.0), 1) } catch (_: Exception) {}
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

                if (target != null && hitEntities.contains(target!!.entityId)) target = null
                if (target != null && !CombatUtil.isValidTarget(shooter, target!!)) target = null
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
            return currentLoc.world.getNearbyEntities(currentLoc, 10.0, 10.0, 10.0)
                .asSequence()
                .filterIsInstance<LivingEntity>()
                .filter { it != shooter }
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

                    if (mode == 0) {
                        applyMark(victim)
                        forceDeal(shooter, victim, damage)
                        victim.world.playSound(victim.location, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6f, 2.0f)
                        safeEffect(Particle.FIREWORK)
                    } else {
                        if (hasMark(victim)) {
                            removeMark(victim)
                            processChainReaction(shooter, victim)
                        } else {
                            forceDeal(shooter, victim, damage)
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
            try { currentLoc.world.spawnParticle(p, currentLoc, 2, 0.1, 0.1, 0.1, 0.05) } catch (_: Exception) {}
        }
    }
}
