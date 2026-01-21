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
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

class RangerT5Skill(private val plugin: PanlingBasic) :
    AbstractSkill("ARCHER_RANGER_T5", "流星赶月", PlayerClass.ARCHER), ArcherSkillStrategy {

    // [修复冲突] 显式重写冲突的方法
    override fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {
        // 你可以选择调用接口的默认实现，或者是父类的实现。
        // 由于你的 ArcherSkillStrategy 中该方法是空的，AbstractSkill 中大概率也是空的，
        // 这里调用哪一个都可以，或者干脆留空。
        // 标准写法是指定调用接口的默认实现：
        //super<ArcherSkillStrategy>.onProjectileHit(event, ctx)
    }

    override fun onCast(ctx: SkillContext): Boolean {
        return true
    }

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
        val x = vector.x
        val z = vector.z
        val cos = cos(angle)
        val sin = sin(angle)
        return Vector(x * cos - z * sin, vector.y, x * sin + z * cos).normalize()
    }

    // 使用 inner class 以便访问外部类的 plugin 实例 (如果需要)
    // 但原代码中 runTaskTimer 传递了 plugin，且内部使用了 plugin.getMobManager()
    // 所以这里使用 inner class 方便直接访问 plugin
    private inner class MeteorTask(
        private val shooter: Player,
        startLoc: Location,
        direction: Vector,
        private var remainingHits: Int,
        private val damage: Double
    ) : BukkitRunnable() {

        private var currentLoc: Location = startLoc.clone()
        private var velocity: Vector
        private var target: LivingEntity? = null
        private val hitEntities = HashSet<Int>()

        private var tick = 0
        private val maxTicks = 140
        private val speed = 0.7

        init {
            this.velocity = direction.normalize().multiply(speed)
        }

        override fun run() {
            // [安全] 加上 try-catch 防止报错导致无限循环
            try {
                if (tick >= maxTicks || remainingHits <= 0) {
                    safeExplodeEffect()
                    this.cancel()
                    return
                }

                // [修复索敌 Bug]
                // 如果当前目标已经被命中过（在 hitEntities 里），说明它不再是一个合法目标
                // 立即丢弃它，强制下方逻辑寻找下一个受害者
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
                    val targetCenter = target!!.eyeLocation.subtract(0.0, 0.3, 0.0)
                    val toTarget = targetCenter.toVector().subtract(currentLoc.toVector()).normalize()

                    val newDir = velocity.clone().normalize().multiply(0.85).add(toTarget.multiply(0.15)).normalize()
                    velocity = newDir.multiply(speed)
                }

                currentLoc.add(velocity)

                // 粒子特效
                currentLoc.world.spawnParticle(Particle.END_ROD, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)
                try {
                    currentLoc.world.spawnParticle(Particle.FIREWORK, currentLoc, 1, 0.0, 0.0, 0.0, 0.0)
                } catch (ignored: Exception) {} // 双重保险
                currentLoc.world.spawnParticle(Particle.WAX_OFF, currentLoc, 1, 0.1, 0.1, 0.1, 0.0)

                checkCollision()
                tick++
            } catch (e: Exception) {
                // 发生任何未捕获异常时，强制取消任务
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
                .filter { CombatUtil.isValidTarget(shooter, it) }
                .filter { !hitEntities.contains(it.entityId) }
                .minByOrNull { it.location.distanceSquared(currentLoc) }
        }

        private fun checkCollision() {
            // 方块碰撞
            if (currentLoc.block.type.isSolid) {
                safeExplodeEffect()
                this.cancel()
                return
            }

            // 实体碰撞
            for (e in currentLoc.world.getNearbyEntities(currentLoc, 0.8, 0.8, 0.8)) {
                if (e is LivingEntity && e != shooter) {
                    val victim = e
                    if (hitEntities.contains(victim.entityId)) continue
                    if (!plugin.mobManager.canAttack(shooter, victim)) continue
                    if (!CombatUtil.isValidTarget(shooter, victim)) continue

                    hitEntities.add(victim.entityId)
                    remainingHits--

                    CombatUtil.dealPhysicalSkillDamage(shooter, victim, damage)

                    // 命中特效
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
            } catch (ignored: Exception) {
            }
        }
    }
}