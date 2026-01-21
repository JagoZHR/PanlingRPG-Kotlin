package com.panling.basic.skill.strategy.wood

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

class WoodAttackT4FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.5

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        launchProjectile(
            p,
            p.eyeLocation,
            p.eyeLocation.direction.normalize(),
            ctx.power * 0.9,
            3,
            HashSet()
        )
        p.playSound(p.location, Sound.ENTITY_SNOWBALL_THROW, 1f, 0.5f)
        return true
    }

    private fun launchProjectile(
        owner: Player,
        startLoc: Location,
        dir: Vector,
        damage: Double,
        remainingBounces: Int,
        hitHistory: MutableSet<Int>
    ) {

        val proj = startLoc.world.spawn(startLoc, ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.TURTLE_SCUTE))
            val t = entity.transformation
            t.scale.set(0.6f, 0.6f, 0.6f)
            entity.transformation = t
            entity.billboard = org.bukkit.entity.Display.Billboard.CENTER
        }

        object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                if (ticks++ > 30 || !proj.isValid) {
                    proj.remove()
                    this.cancel()
                    return
                }

                val current = proj.location.add(dir.clone().multiply(1.2))
                proj.teleport(current)

                current.world.spawnParticle(Particle.COMPOSTER, current, 1, 0.0, 0.0, 0.0, 0.0)

                var hitTarget: LivingEntity? = null
                // [修改] 碰撞检测逻辑
                for (e in current.world.getNearbyEntities(current, 0.6, 0.6, 0.6)) {
                    if (e is LivingEntity && e != owner) {
                        val victim = e
                        // [新增] 穿透逻辑：如果目标是玩家且策略禁止PVP，直接跳过 (视为不存在)
                        // 这样投射物就不会因为碰到玩家而停下，实现"穿过队友"的效果
                        if (victim is Player && !this@WoodAttackT4FurnaceStrategy.canHitPlayers) {
                            continue
                        }

                        if (hitHistory.contains(victim.entityId)) continue

                        if (plugin.mobManager.canAttack(owner, victim)) {
                            hitTarget = victim
                            break
                        }
                    }
                }

                if (hitTarget != null) {
                    // 因为上面的过滤，这里的 hitTarget 绝不可能是玩家
                    plugin.elementalManager.handleElementHit(owner, hitTarget, ElementalManager.Element.WOOD, damage)
                    MageUtil.dealSkillDamage(owner, hitTarget, damage, true)
                    hitTarget.world.playSound(hitTarget.location, Sound.BLOCK_SHROOMLIGHT_HIT, 1f, 1.2f)

                    hitHistory.add(hitTarget.entityId)
                    proj.remove()
                    this.cancel()

                    if (remainingBounces > 0) {
                        val nextTarget = findNextTarget(hitTarget, owner, hitHistory)
                        if (nextTarget != null) {
                            val newDir = nextTarget.eyeLocation.toVector()
                                .subtract(hitTarget.eyeLocation.toVector())
                                .normalize()
                            launchProjectile(
                                owner,
                                hitTarget.eyeLocation,
                                newDir,
                                damage * 0.9,
                                remainingBounces - 1,
                                hitHistory
                            )
                        }
                    }
                    return
                }

                if (!current.block.isPassable) {
                    proj.remove()
                    this.cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun findNextTarget(center: LivingEntity, owner: Player, ignoreList: Set<Int>): LivingEntity? {
        // 使用 sequence 和 filter 链
        return center.getNearbyEntities(8.0, 5.0, 8.0)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it != owner && it != center }
            // [新增] 弹射索敌过滤：确保不会弹向玩家
            .filter { e ->
                if (e is Player && !this.canHitPlayers) return@filter false
                true
            }
            .filter { !ignoreList.contains(it.entityId) }
            .filter { plugin.mobManager.canAttack(owner, it) }
            .minByOrNull { it.location.distanceSquared(center.location) }
    }
}