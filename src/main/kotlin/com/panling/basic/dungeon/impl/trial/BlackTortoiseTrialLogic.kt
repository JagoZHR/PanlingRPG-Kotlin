package com.panling.basic.dungeon.impl.trial

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.RewardConfig
import com.panling.basic.dungeon.StandardDungeonLogic
import com.panling.basic.dungeon.phase.AbstractCombatPhase
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Ravager
import org.bukkit.entity.Silverfish
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class BlackTortoiseTrialLogic(plugin: PanlingBasic) : StandardDungeonLogic(plugin) {

    override val templateId = "black_tortoise_trial"
    override val waitDuration = 10

    private fun getHpScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.5
    private fun getAtkScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.1

    // ==========================================
    // 奖励：解锁 T5 装备 + T5 法宝
    // ==========================================
    override fun createRewardConfig(instance: DungeonInstance): RewardConfig {
        return RewardConfig(
            title = "§3[玄武试炼] 冥渊试炼通过！",
            autoReward = true,
            autoRewardDelay = 100L,
            onReward = { player -> unlockT5Recipes(player) }
        )
    }

    private fun unlockT5Recipes(player: Player) {
        val playerClass = plugin.playerDataManager.getPlayerClass(player)
        val allRecipes = plugin.forgeManager.getAllRecipes()
        var unlockedCount = 0

        allRecipes.forEach { recipe ->
            if (recipe.id.contains("_t5") || recipe.id.contains("fabao_t5")) {
                if (plugin.itemManager.isItemAllowedForClass(recipe.targetItemId, playerClass)) {
                    if (!plugin.forgeManager.hasUnlockedRecipe(player, recipe.id)) {
                        plugin.forgeManager.unlockRecipe(player, recipe.id)
                        unlockedCount++
                    }
                }
            }
        }

        if (unlockedCount > 0) {
            player.sendMessage("§b[系统] 你领悟了 ${unlockedCount} 个五阶锻造配方！")
        }
    }

    override fun createGamePhase(instance: DungeonInstance): AbstractDungeonPhase {
        return Phase1Waves(instance)
    }

    // ==========================================
    // 阶段 1：水波冲击（30 秒）
    // ==========================================
    inner class Phase1Waves(instance: DungeonInstance) : AbstractDungeonPhase(plugin, instance) {

        private val durationTicks = 600L
        private val arenaRadius = 16.0
        private val waveInterval = 50L
        private val pushStrength = 3.0

        private var startTick = 0L
        private var nextWaveTick = 0L
        private var isFinished = false
        private val fallenPlayers = mutableSetOf<UUID>()

        override fun start() {
            startTick = instance.tickCount
            nextWaveTick = startTick + 20L     // 1 秒后第一波
            instance.broadcast("§3水波从深渊中涌来！坚持 30 秒，利用跳跃躲避冲击！")
            instance.broadcastSound(Sound.AMBIENT_UNDERWATER_ENTER)
        }

        override fun onTick() {
            if (isFinished) return

            val elapsed = instance.tickCount - startTick

            // 持续给予跳跃提升
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (!p.isDead) {
                    p.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, 40, 3, false, false, false))
                }
            }

            // 时间到
            if (elapsed >= durationTicks) {
                isFinished = true
                instance.broadcast("§3水波平息了！§e重甲冥龟§3从深渊中浮现...")
                instance.broadcastSound(Sound.ENTITY_ELDER_GUARDIAN_CURSE)
                instance.nextPhase(Phase2Boss(instance))
                return
            }

            // 倒计时提示
            if (elapsed % 200L == 0L && elapsed > 0) {
                val remaining = (durationTicks - elapsed) / 20
                instance.broadcast("§7剩余 §3${remaining} §7秒")
            }

            // 发射水波
            if (instance.tickCount >= nextWaveTick) {
                spawnWave()
                nextWaveTick = instance.tickCount + waveInterval
            }

            // 坠落检测：Y < -20 则坠入深渊
            for (uuid in instance.players) {
                if (fallenPlayers.contains(uuid)) continue
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.isDead) continue
                if (p.location.y < -20.0) {
                    fallenPlayers.add(uuid)
                    p.sendMessage("§c你被水波冲入了深渊！")
                    p.damage(1000.0)
                }
            }
        }

        private fun spawnWave() {
            val center = instance.centerLocation
            val maxRadius = arenaRadius
            val speed = 0.6
            val thickness = 1.0

            object : BukkitRunnable() {
                var currentRadius = 0.0
                val hitPlayers = mutableSetOf<UUID>()

                override fun run() {
                    if (currentRadius >= maxRadius || isFinished) {
                        cancel()
                        return
                    }

                    // 蓝色水波粒子环
                    val particleCount = (currentRadius * 3).toInt().coerceAtLeast(12)
                    for (i in 0 until particleCount) {
                        val angle = (i.toDouble() / particleCount) * 2 * Math.PI
                        val x = center.x + cos(angle) * currentRadius
                        val z = center.z + sin(angle) * currentRadius
                        instance.world.spawnParticle(
                            Particle.BUBBLE_POP,
                            x, center.y + 0.5, z,
                            1, 0.0, 0.0, 0.0, 0.0
                        )
                    }

                    // 碰撞检测：玩家在水波厚度范围内且在地面附近才被击退
                    for (uuid in instance.players) {
                        val p = Bukkit.getPlayer(uuid) ?: continue
                        if (p.isDead || hitPlayers.contains(p.uniqueId)) continue

                        val playerXZ = Vector(p.location.x, 0.0, p.location.z)
                        val centerXZ = Vector(center.x, 0.0, center.z)
                        val dist = playerXZ.distance(centerXZ)

                        // Y 轴检测：只有贴近地面才会被水波命中（跳跃可躲避）
                        val waveY = center.y + 0.5
                        if (Math.abs(p.location.y - waveY) > 2.0) continue

                        if (dist >= currentRadius - thickness && dist <= currentRadius + thickness) {
                            hitPlayers.add(p.uniqueId)
                            val dir = playerXZ.subtract(centerXZ).normalize()
                            p.velocity = dir.multiply(pushStrength).setY(0.4)
                        }
                    }

                    currentRadius += speed
                }
            }.runTaskTimer(plugin, 0L, 1L)
        }

        override fun end() {}
    }

    // ==========================================
    // 阶段 2：重甲冥龟 + 幽水灵蛇
    // ==========================================
    inner class Phase2Boss(instance: DungeonInstance) : AbstractCombatPhase(plugin, instance) {

        override fun start() {
            val hpScale = getHpScale(instance)
            val atkScale = getAtkScale(instance)
            val count = instance.players.size
            val center = instance.centerLocation

            // 重甲冥龟（小型劫掠兽）
            val ravager = plugin.mobManager.spawnMob(center.clone().add(0.0, 1.0, 0.0), "dungeon_black_tortoise_boss") as? Ravager ?: return
            ravager.isAware = true
            scaleMob(ravager, hpScale, atkScale)
            // 劫掠兽不走 MOVEMENT_SPEED 属性，用药水减速替代
            ravager.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, Int.MAX_VALUE, 3, false, false))
            spawnMob(ravager)

            // 幽水灵蛇（蠹虫 × 玩家数 × 2，一主一副）
            for (i in 0 until count) {
                val offset = when (i) {
                    0 -> Vector(3.0, 0.0, 0.0)
                    1 -> Vector(-3.0, 0.0, 0.0)
                    else -> Vector(0.0, 0.0, 3.0)
                }
                // 主蛇
                val loc1 = center.clone().add(offset).add(0.0, 1.0, 0.0)
                val sf1 = plugin.mobManager.spawnMob(loc1, "dungeon_black_tortoise_add") as? Silverfish ?: continue
                scaleMob(sf1, hpScale, atkScale)
                spawnMob(sf1)

                // 副蛇 (半血半攻)
                val loc2 = center.clone().add(offset).add(1.0, 1.0, 0.0)
                val sf2 = plugin.mobManager.spawnMob(loc2, "dungeon_black_tortoise_add") as? Silverfish ?: continue
                scaleMob(sf2, hpScale, atkScale, hpMult = 0.5, atkMult = 0.5)
                spawnMob(sf2)
            }

            instance.broadcast("§3重甲冥龟 §f与 §b${count * 2} 条幽水灵蛇 §f出现了！")
            instance.broadcastSound(Sound.ENTITY_RAVAGER_ROAR)
        }

        override fun onMobDeath(event: EntityDeathEvent) {
            super.onMobDeath(event)
            event.drops.clear()
            event.droppedExp = 0
        }

        override fun onWaveClear() {
            instance.broadcast("§3§l深渊生物已全部清除！§r")
            instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
            goToRewardPhase(instance)
        }

        private fun scaleMob(entity: LivingEntity, hpScale: Double, atkScale: Double, hpMult: Double = 1.0, atkMult: Double = 1.0) {
            entity.getAttribute(Attribute.MAX_HEALTH)?.let { attr ->
                attr.baseValue = attr.baseValue * hpScale * hpMult
                entity.health = attr.baseValue
            }
            entity.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr ->
                attr.baseValue = attr.baseValue * atkScale * atkMult
            }
        }
    }
}
