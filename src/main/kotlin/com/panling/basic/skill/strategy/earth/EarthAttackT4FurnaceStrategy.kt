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

class EarthAttackT4FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.8

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val start = p.location.add(0.0, 0.0, 0.0) // 脚下开始
        val dir = p.location.direction.setY(0).normalize()

        spawnSpikeWave(p, start, dir, ctx.power)

        p.playSound(start, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1f, 0.5f)
        return true
    }

    private fun spawnSpikeWave(owner: Player, start: Location, dir: Vector, power: Double) {
        val hitEntities = HashSet<Int>()

        object : BukkitRunnable() {
            var step = 0
            val current = start.clone()

            override fun run() {
                if (step++ > 10) { // 10排地刺
                    this.cancel()
                    return
                }

                // 移动生成点
                current.add(dir.clone().multiply(1.5))

                // 简单的贴地判定
                if (current.block.type.isSolid) {
                    current.add(0.0, 1.0, 0.0)
                } else if (current.clone().subtract(0.0, 1.0, 0.0).block.isEmpty) {
                    current.subtract(0.0, 1.0, 0.0)
                }

                // 在当前位置生成地刺
                spawnSpike(current, dir, power, owner, hitEntities)
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    private fun spawnSpike(loc: Location, direction: Vector, power: Double, owner: Player, hitEntities: MutableSet<Int>) {
        // 生成地刺模型 (滴水石锥)
        val spike = loc.world.spawn(loc.clone().subtract(0.0, 1.5, 0.0), ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.POINTED_DRIPSTONE))
            val t = entity.transformation

            // [修改] 旋转 180 度 (绕 Z 轴)，使其尖端朝上
            t.leftRotation.set(AxisAngle4f(Math.toRadians(180.0).toFloat(), 0f, 0f, 1f))
            t.scale.set(1.5f, 2.0f, 1.5f)
            entity.transformation = t
        }

        loc.world.spawnParticle(Particle.BLOCK_CRUMBLE, loc, 10, 0.5, 0.1, 0.5, Material.DIRT.createBlockData())
        loc.world.playSound(loc, Sound.BLOCK_DRIPSTONE_BLOCK_FALL, 1f, 0.5f)

        val damage = power * 1.8

        // 地刺升降动画 & 伤害判定
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (!spike.isValid) {
                    spike.remove()
                    this.cancel()
                    return
                }

                if (tick < 3) {
                    spike.teleport(spike.location.add(0.0, 0.5, 0.0)) // 升起
                } else if (tick > 7) {
                    spike.teleport(spike.location.subtract(0.0, 0.5, 0.0)) // 落下
                }

                // 只有前几tick有伤害判定
                if (tick < 5) {
                    for (e in spike.location.world.getNearbyEntities(spike.location, 1.0, 1.5, 1.0)) {
                        if (e is LivingEntity && e != owner) {
                            val victim = e

                            // [新增] 目标过滤：如果是玩家且禁止PVP，跳过 (不造成伤害也不击退)
                            if (victim is Player && !this@EarthAttackT4FurnaceStrategy.canHitPlayers) continue

                            if (hitEntities.contains(victim.entityId)) continue
                            if (!plugin.mobManager.canAttack(owner, victim)) continue

                            plugin.elementalManager.handleElementHit(owner, victim, ElementalManager.Element.EARTH, damage)
                            MageUtil.dealSkillDamage(owner, victim, damage, false) // 视为物理/混合伤害

                            // 击退效果
                            val knockback = direction.clone().multiply(0.8).setY(0.6)
                            victim.velocity = knockback

                            hitEntities.add(victim.entityId)
                        }
                    }
                }

                if (tick++ > 10) {
                    spike.remove()
                    this.cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}