package com.panling.basic.skill.strategy.metal

import com.panling.basic.PanlingBasic
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.util.MageUtil
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import org.joml.AxisAngle4f

class MetalAttackT4FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 1.0

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val origin = p.eyeLocation
        val direction = origin.direction.normalize()

        val damage = ctx.power * 1.6

        val upOffset = Vector(0.0, 0.5, 0.0)
        val leftOffset = Vector(-0.4, -0.3, 0.0)
        val rightOffset = Vector(0.4, -0.3, 0.0)

        launchSword(p, origin, direction, upOffset, damage)
        launchSword(p, origin, direction, leftOffset, damage)
        launchSword(p, origin, direction, rightOffset, damage)

        p.playSound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.5f)
        return true
    }

    private fun launchSword(owner: Player, origin: Location, dir: Vector, offset: Vector, damage: Double) {
        val up = Vector(0, 1, 0)
        val right = dir.clone().crossProduct(up).normalize()
        val localUp = right.clone().crossProduct(dir).normalize()

        val startLoc = origin.clone()
        startLoc.add(right.clone().multiply(offset.x))
        startLoc.add(localUp.clone().multiply(offset.y))

        val sword = origin.world.spawn(startLoc, ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.GOLDEN_SWORD))
            val t = entity.transformation
            t.scale.set(0.5f, 0.5f, 0.5f)
            t.leftRotation.set(AxisAngle4f(Math.toRadians(90.0).toFloat(), 1f, 0f, -0.7f))
            entity.transformation = t
            entity.billboard = org.bukkit.entity.Display.Billboard.FIXED
            entity.setRotation(origin.yaw, origin.pitch)
        }

        object : BukkitRunnable() {
            var ticks = 0
            override fun run() {
                if (ticks++ > 40 || !sword.isValid) {
                    sword.remove()
                    this.cancel()
                    return
                }

                val current = sword.location.add(dir.clone().multiply(1.5))
                sword.teleport(current)

                var hitSomething = false
                // 使用 Kotlin 循环替代 Java 循环
                for (e in current.world.getNearbyEntities(current, 0.5, 0.5, 0.5)) {
                    if (e is LivingEntity && e != owner) {
                        val victim = e
                        // [新增] 穿透逻辑：如果目标是玩家且策略禁止PVP，直接跳过 (视为不存在)
                        // 飞剑将继续飞行，穿过玩家身体
                        if (victim is Player && !this@MetalAttackT4FurnaceStrategy.canHitPlayers) {
                            continue
                        }
                        if (!plugin.mobManager.canAttack(owner, victim)) continue

                        owner.setMetadata("pl_extra_pen", FixedMetadataValue(plugin, 20))

                        plugin.elementalManager.handleElementHit(owner, victim, ElementalManager.Element.METAL, damage)
                        MageUtil.dealSkillDamage(owner, victim, damage, true)

                        owner.removeMetadata("pl_extra_pen", plugin)

                        victim.world.playSound(victim.location, Sound.ENTITY_ARROW_HIT, 0.5f, 2f)
                        hitSomething = true
                    }
                }

                if (hitSomething) {
                    sword.remove()
                    this.cancel()
                }

                if (!current.block.isPassable) {
                    sword.remove()
                    this.cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}