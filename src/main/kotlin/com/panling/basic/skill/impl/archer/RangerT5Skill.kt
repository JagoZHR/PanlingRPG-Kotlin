package com.panling.basic.skill.impl.archer

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.ArcherSkillStrategy
import com.panling.basic.util.CombatUtil
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
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

class RangerT5Skill(private val plugin: PanlingBasic) :
    AbstractSkill("ARCHER_RANGER_T5", "流星赶月", PlayerClass.ARCHER), ArcherSkillStrategy {

    override fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {}

    override fun onCast(ctx: SkillContext): Boolean = true

    override fun onShoot(player: Player, arrow: AbstractArrow, event: EntityShootBowEvent) {
        val crossbow = event.bow ?: return

        val piercingLevel = crossbow.getEnchantmentLevel(Enchantment.PIERCING)
        val maxHits = 1 + piercingLevel
        val multishot = crossbow.containsEnchantment(Enchantment.MULTISHOT)

        val phys = plugin.statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        val rangeMult = plugin.playerDataManager.getPlayerClass(player).rangeMultiplier
        val baseDamage = phys * rangeMult * 2.0

        arrow.remove()

        val startLoc = player.eyeLocation.subtract(0.0, 0.2, 0.0)
        val direction = startLoc.direction

        player.world.playSound(startLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f)
        player.world.playSound(startLoc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.5f, 1.2f)

        MeteorTask(player, startLoc, direction, maxHits, baseDamage).runTaskTimer(plugin, 1L, 1L)

        if (multishot) {
            val left = rotateVector(direction, 15.0)
            val right = rotateVector(direction, -15.0)
            MeteorTask(player, startLoc, left, maxHits, baseDamage).runTaskTimer(plugin, 1L, 1L)
            MeteorTask(player, startLoc, right, maxHits, baseDamage).runTaskTimer(plugin, 1L, 1L)
        }
    }

    private fun rotateVector(vector: Vector, angleDegrees: Double): Vector {
        val angle = Math.toRadians(angleDegrees)
        val x = vector.x; val z = vector.z
        val c = cos(angle); val s = sin(angle)
        return Vector(x * c - z * s, vector.y, x * s + z * c).normalize()
    }

    private inner class MeteorTask(
        private val shooter: Player,
        startLoc: Location,
        direction: Vector,
        private var remainingHits: Int,
        private val damage: Double,
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
                    safeExplodeEffect()
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
                    velocity = velocity.normalize().multiply(0.85).add(toTarget.multiply(0.15)).normalize().multiply(0.7)
                }

                currentLoc.add(velocity)

                currentLoc.world.spawnParticle(Particle.END_ROD, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)
                try { currentLoc.world.spawnParticle(Particle.FIREWORK, currentLoc, 1, 0.0, 0.0, 0.0, 0.0) } catch (_: Exception) {}
                currentLoc.world.spawnParticle(Particle.WAX_OFF, currentLoc, 1, 0.1, 0.1, 0.1, 0.0)

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
                safeExplodeEffect()
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

                    shooter.setMetadata("pl_force_archer_passive", FixedMetadataValue(plugin, true))
                    CombatUtil.dealPhysicalSkillDamage(shooter, victim, damage)
                    shooter.removeMetadata("pl_force_archer_passive", plugin)

                    victim.world.spawnParticle(Particle.FIREWORK, victim.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.3, 0.3, 0.2)

                    if (remainingHits <= 0) {
                        safeExplodeEffect()
                        this.cancel()
                        return
                    }
                }
            }
        }

        private fun safeExplodeEffect() {
            try {
                currentLoc.world.spawnParticle(Particle.CLOUD, currentLoc, 5, 0.2, 0.2, 0.2, 0.05)
                currentLoc.world.playSound(currentLoc, Sound.BLOCK_GLASS_BREAK, 0.5f, 2.0f)
            } catch (_: Exception) {}
        }
    }
}
