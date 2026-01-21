package com.panling.basic.skill.strategy.metal

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.util.MageUtil
import org.bukkit.Bukkit
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
import org.joml.Quaternionf
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

class MetalAttackT5FurnaceStrategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    // Companion Object 替代 static 成员
    companion object {
        private val activeSwords: MutableMap<UUID, MutableList<ItemDisplay>> = HashMap()
        private val activeTasks: MutableMap<UUID, Int> = HashMap()

        private fun clearSwords(pid: UUID) {
            activeSwords[pid]?.forEach { it.remove() }
            activeSwords.remove(pid)
            activeTasks.remove(pid)
        }

        fun tryFireSword(p: Player, plugin: PanlingBasic) {
            val pid = p.uniqueId
            val swords = activeSwords[pid] ?: return

            if (swords.isEmpty()) return

            val sword = swords.removeAt(0)
            if (swords.isEmpty()) {
                activeSwords.remove(pid)
                activeTasks.remove(pid)
            }

            fireLogic(p, sword, plugin)
        }

        private fun fireLogic(owner: Player, sword: ItemDisplay, plugin: PanlingBasic) {
            val start = sword.location.add(0.0, -1.5, 0.0)
            val dir = owner.eyeLocation.direction.normalize()

            start.direction = dir
            sword.teleport(start)

            val t = sword.transformation
            val rotation = Quaternionf()
                .rotateX(Math.toRadians(90.0).toFloat())
                .rotateY(Math.toRadians(90.0).toFloat())
                .rotateZ(Math.toRadians(-45.0).toFloat())

            t.leftRotation.set(rotation)
            sword.transformation = t

            val power = plugin.statCalculator.getPlayerTotalStat(owner, BasicKeys.ATTR_SKILL_DAMAGE)
            val damage = power * 1.6

            owner.playSound(owner.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.5f)
            owner.playSound(owner.location, Sound.ENTITY_WITHER_SHOOT, 0.5f, 2.0f)

            object : BukkitRunnable() {
                var tick = 0
                override fun run() {
                    if (tick++ > 40 || !sword.isValid) {
                        sword.remove()
                        this.cancel()
                        return
                    }

                    val current = sword.location.add(dir.clone().multiply(2.5))
                    sword.teleport(current)

                    var hit = false
                    // 使用 Kotlin 循环
                    for (e in current.world.getNearbyEntities(current, 1.0, 1.0, 1.0)) {
                        if (e is LivingEntity && e != owner) {
                            val victim = e
                            // [核心修改] 穿透玩家：如果目标是玩家 (且策略必定为PVE)，跳过
                            if (victim is Player) continue

                            if (!plugin.mobManager.canAttack(owner, victim)) continue

                            owner.setMetadata("pl_extra_pen", FixedMetadataValue(plugin, 40))

                            plugin.elementalManager.handleElementHit(owner, victim, ElementalManager.Element.METAL, damage)
                            MageUtil.dealSkillDamage(owner, victim, damage, true)

                            owner.removeMetadata("pl_extra_pen", plugin)

                            owner.playSound(owner.location, Sound.ENTITY_ARROW_HIT, 0.5f, 1f)
                            hit = true
                            break
                        }
                    }

                    if (hit || !current.block.isPassable) {
                        sword.remove()
                        this.cancel()
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L)
        }

        fun clearPlayer(pid: UUID) {
            activeSwords[pid]?.forEach { it.remove() }
            activeSwords.remove(pid)

            activeTasks[pid]?.let { taskId ->
                Bukkit.getScheduler().cancelTask(taskId)
                activeTasks.remove(pid)
            }
        }

        fun clearAll() {
            // 使用 toSet() 避免 ConcurrentModificationException
            val players = activeSwords.keys.toSet()
            players.forEach { clearPlayer(it) }
            activeSwords.clear()
            activeTasks.clear()
        }
    }

    override val castTime: Double = 1.5

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val pid = p.uniqueId

        // getOrPut 简化逻辑
        val swords = activeSwords.getOrPut(pid) { ArrayList() }
        val currentCount = swords.size
        val needed = 5 - currentCount

        if (needed <= 0) {
            p.sendMessage("§e[提示] 剑阵已充盈！")
            return false
        }

        for (i in 0 until needed) {
            val sword = p.world.spawn(p.location, ItemDisplay::class.java) { entity ->
                entity.setItemStack(ItemStack(Material.IRON_AXE))
                val t = entity.transformation
                t.scale.set(1.0f, 1.0f, 1.0f)
                t.leftRotation.set(AxisAngle4f(Math.toRadians(-45.0).toFloat(), 0f, 0f, 1f))
                entity.transformation = t
                entity.billboard = org.bukkit.entity.Display.Billboard.FIXED
            }
            swords.add(sword)
        }

        if (!activeTasks.containsKey(pid)) {
            startFollowTask(p)
        }

        p.playSound(p.location, Sound.ITEM_ARMOR_EQUIP_GOLD, 1f, 0.8f)
        p.playSound(p.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f)
        return true
    }

    private fun startFollowTask(p: Player) {
        val pid = p.uniqueId
        val taskId = object : BukkitRunnable() {
            override fun run() {
                if (!p.isOnline || !activeSwords.containsKey(pid) || activeSwords[pid]!!.isEmpty()) {
                    clearSwords(pid)
                    this.cancel()
                    return
                }

                val swords = activeSwords[pid]!!
                val playerLoc = p.location
                val dir = playerLoc.direction.setY(0).normalize()

                val arrayCenter = playerLoc.clone().add(0.0, 1.8, 0.0).subtract(dir.clone().multiply(1.5))
                val right = dir.clone().crossProduct(Vector(0, 1, 0)).normalize()
                val up = Vector(0, 1, 0)

                val count = swords.size
                val radius = 2.0
                val startAngle = Math.toRadians(90.0)

                for (i in 0 until count) {
                    val sword = swords[i]
                    if (!sword.isValid) continue

                    val angle = startAngle + Math.toRadians((i * 72).toDouble())
                    val offsetX = cos(angle) * radius
                    val offsetY = sin(angle) * radius

                    val targetLoc = arrayCenter.clone()
                        .add(right.clone().multiply(offsetX))
                        .add(up.clone().multiply(offsetY))

                    targetLoc.yaw = playerLoc.yaw
                    targetLoc.pitch = 0f

                    sword.teleport(targetLoc)

                    val t = sword.transformation
                    t.leftRotation.set(AxisAngle4f(Math.toRadians(-45.0).toFloat(), -3f, 0f, 2f))
                    sword.transformation = t
                }
            }
        }.runTaskTimer(plugin, 0L, 1L).taskId

        activeTasks[pid] = taskId
    }
}