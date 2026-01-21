package com.panling.basic.skill.strategy.earth

import com.panling.basic.PanlingBasic
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.util.MageUtil
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import java.util.HashSet

class EarthAttackT5FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.8

    // [新增] 显式声明：PVE技能
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val start = p.location
        val centerDir = start.direction.setY(0).normalize()

        // 左偏移 30度
        val leftDir = rotateVector(centerDir.clone(), -30.0)
        // 右偏移 30度
        val rightDir = rotateVector(centerDir.clone(), 30.0)

        val hitEntities = HashSet<Int>()

        // 发射三条
        launchSpikeLine(p, start, centerDir, hitEntities, ctx.power)
        launchSpikeLine(p, start, leftDir, hitEntities, ctx.power)
        launchSpikeLine(p, start, rightDir, hitEntities, ctx.power)

        p.playSound(start, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1f, 0.8f)
        return true
    }

    private fun launchSpikeLine(owner: Player, start: Location, initialDir: Vector, hitEntities: MutableSet<Int>, power: Double) {
        object : BukkitRunnable() {
            var step = 0
            val currentDir = initialDir.clone()
            val currentLoc = start.clone()

            override fun run() {
                if (step++ >= 8) { // 每条线 8 个刺
                    this.cancel()
                    return
                }

                // Homing 补正
                val target = findTarget(currentLoc, currentDir, owner)
                if (target != null) {
                    val toTarget = target.location.toVector().subtract(currentLoc.toVector()).normalize()
                    currentDir.add(toTarget.multiply(0.2)).normalize()
                }

                currentLoc.add(currentDir.clone().multiply(1.5)) // 间距

                // 贴地
                if (currentLoc.block.isEmpty) {
                    currentLoc.subtract(0.0, 1.0, 0.0)
                } else if (!currentLoc.block.isPassable) {
                    currentLoc.add(0.0, 1.0, 0.0)
                }
                currentLoc.add(0.0, 1.0, 0.0) // 抬升至地面

                spawnSpike(owner, currentLoc, currentDir, power * 2.0, hitEntities)
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    private fun spawnSpike(owner: Player, loc: Location, direction: Vector, damage: Double, hitEntities: MutableSet<Int>) {
        val spike = loc.world.spawn(loc.clone().subtract(0.0, 1.5, 0.0), ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.POINTED_DRIPSTONE))
            val t = entity.transformation
            t.leftRotation.set(AxisAngle4f(Math.toRadians(180.0).toFloat(), 0f, 0f, 1f))
            t.scale.set(1.5f, 2.5f, 1.5f)
            entity.transformation = t
        }

        loc.world.spawnParticle(Particle.BLOCK_CRUMBLE, loc, 10, 0.5, 0.5, 0.5, Material.DIRT.createBlockData())
        loc.world.playSound(loc, Sound.BLOCK_STONE_BREAK, 0.5f, 0.5f)

        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick > 10 || !spike.isValid) {
                    spike.remove()
                    this.cancel()
                    return
                }

                if (tick < 3) spike.teleport(spike.location.add(0.0, 0.5, 0.0))
                else if (tick > 7) spike.teleport(spike.location.subtract(0.0, 0.5, 0.0))

                if (tick < 5) {
                    for (e in spike.location.world.getNearbyEntities(spike.location, 1.0, 1.5, 1.0)) {
                        if (e is LivingEntity && e != owner) {
                            val victim = e

                            // [修改] 使用通用接口判断，替换原有的硬编码
                            if (victim is Player && !this@EarthAttackT5FurnaceStrategy.canHitPlayers) continue

                            if (hitEntities.contains(victim.entityId)) continue
                            if (!plugin.mobManager.canAttack(owner, victim)) continue

                            plugin.elementalManager.handleElementHit(owner, victim, ElementalManager.Element.EARTH, damage)
                            MageUtil.dealSkillDamage(owner, victim, damage, false)

                            val knock = direction.clone().multiply(0.8).setY(0.6)
                            victim.velocity = knock
                            hitEntities.add(victim.entityId)
                        }
                    }
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun rotateVector(v: Vector, degrees: Double): Vector {
        val rad = Math.toRadians(degrees)
        val x = v.x * Math.cos(rad) - v.z * Math.sin(rad)
        val z = v.x * Math.sin(rad) + v.z * Math.cos(rad)
        return Vector(x, v.y, z).normalize()
    }

    private fun findTarget(center: Location, currentDir: Vector, owner: Player): LivingEntity? {
        // 使用 Kotlin 集合操作简化搜索逻辑
        return center.world.getNearbyEntities(center, 4.0, 3.0, 4.0)
            .asSequence() // 使用 Sequence 避免中间集合创建
            .filterIsInstance<LivingEntity>()
            .filter { it != owner }
            .filter {
                // [修改] 使用通用接口过滤玩家
                if (it is Player && !this.canHitPlayers) return@filter false
                true
            }
            .filter { plugin.mobManager.canAttack(owner, it) }
            .filter {
                val toE = it.location.toVector().subtract(center.toVector()).normalize()
                currentDir.angle(toE) < Math.toRadians(60.0)
            }
            .minByOrNull { it.location.distanceSquared(center) }
    }
}