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
import org.joml.AxisAngle4f

class WoodAttackT5FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.5

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val start = p.location
        val dir = p.location.direction.setY(0).normalize() // 初始水平方向

        spawnVineWave(p, start, dir, ctx.power)

        p.playSound(start, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1f, 1.2f)
        return true
    }

    private fun spawnVineWave(owner: Player, start: Location, initialDir: Vector, power: Double) {
        val hitEntities = HashSet<Int>()

        object : BukkitRunnable() {
            var step = 0
            val currentDir = initialDir.clone()
            val currentLoc = start.clone()

            override fun run() {
                if (step++ >= 15) { // 最多 15 格
                    this.cancel()
                    return
                }

                // 1. 补正方向 (Homing)
                val target = findNearestTarget(currentLoc, currentDir, owner, hitEntities)
                if (target != null) {
                    val toTarget = target.location.toVector().subtract(currentLoc.toVector()).normalize()
                    // 0.3 的补正系数，使转向平滑
                    currentDir.add(toTarget.multiply(0.3)).normalize()
                }

                // 2. 移动
                currentLoc.add(currentDir)

                // 3. 贴地修正
                if (currentLoc.block.isEmpty) { // 如果悬空，向下找
                    if (!currentLoc.clone().subtract(0.0, 1.0, 0.0).block.isEmpty) {
                        // 下面是实心，保持高度
                    } else {
                        currentLoc.subtract(0.0, 1.0, 0.0) // 下坡
                    }
                } else if (!currentLoc.block.isPassable) { // 如果撞墙，向上找
                    currentLoc.add(0.0, 1.0, 0.0)
                }

                // 4. 生成特效实体
                spawnVineEffect(currentLoc, currentDir)

                // 5. 伤害判定
                for (e in currentLoc.world.getNearbyEntities(currentLoc, 1.0, 1.5, 1.0)) {
                    if (e is LivingEntity && e != owner) {
                        val victim = e
                        // [新增] 穿透逻辑：如果是玩家且禁止PVP，直接跳过交互
                        if (victim is Player && !this@WoodAttackT5FurnaceStrategy.canHitPlayers) continue

                        if (hitEntities.contains(victim.entityId)) continue // 不重复命中
                        if (!plugin.mobManager.canAttack(owner, victim)) continue

                        val damage = power * 1.5 // T5 高伤害
                        plugin.elementalManager.handleElementHit(owner, victim, ElementalManager.Element.WOOD, damage)
                        MageUtil.dealSkillDamage(owner, victim, damage, true)

                        // 缠绕/定身效果 (可选)
                        // plugin.getBuffManager().addBuff(victim, BuffType.ROOT, 20, 1.0, false);

                        hitEntities.add(victim.entityId)
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 2L) // 每2 tick 走一格，速度适中
    }

    private fun spawnVineEffect(loc: Location, dir: Vector) {
        // 生成藤蔓展示实体
        loc.world.spawn(loc.clone(), ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.VINE))
            val t = entity.transformation
            t.scale.set(1.5f, 1.5f, 1.5f) // 大一点
            // 旋转使其平铺在地上
            t.leftRotation.set(AxisAngle4f(Math.toRadians(90.0).toFloat(), 1f, 0f, 0f))
            entity.transformation = t
            entity.billboard = org.bukkit.entity.Display.Billboard.FIXED

            // 设置朝向
            val rotLoc = loc.clone()
            rotLoc.direction = dir
            entity.setRotation(rotLoc.yaw, 0f)

            // 0.5秒后消失
            object : BukkitRunnable() {
                override fun run() {
                    entity.remove()
                }
            }.runTaskLater(plugin, 10L)
        }

        // 粒子点缀
        loc.world.spawnParticle(Particle.COMPOSTER, loc, 5, 0.3, 0.2, 0.3, 0.0)
    }

    private fun findNearestTarget(center: Location, currentDir: Vector, owner: Player, ignore: Set<Int>): LivingEntity? {
        return center.world.getNearbyEntities(center, 5.0, 3.0, 5.0)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it != owner }
            // [新增] 索敌过滤：确保自动追踪不会锁定玩家
            .filter { e ->
                if (e is Player && !this.canHitPlayers) return@filter false
                true
            }
            .filter { !ignore.contains(it.entityId) }
            .filter { plugin.mobManager.canAttack(owner, it) }
            // 限制角度：只追踪前方的敌人 (90度扇面)
            .filter { e ->
                val toE = e.location.toVector().subtract(center.toVector()).normalize()
                currentDir.angle(toE) < Math.toRadians(45.0)
            }
            .minByOrNull { it.location.distanceSquared(center) }
    }
}