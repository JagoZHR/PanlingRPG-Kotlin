package com.panling.basic.dungeon.impl.trial

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.RewardConfig
import com.panling.basic.dungeon.StandardDungeonLogic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.random.Random

class WoodTrialLogic(plugin: PanlingBasic) : StandardDungeonLogic(plugin) {

    override val templateId: String = "trial_wood"

    // ==========================================
    // 奖励配置：自动发奖 + 解锁 T2 配方
    // ==========================================
    override fun createRewardConfig(instance: DungeonInstance): RewardConfig {
        return RewardConfig(
            title = "§a[青龙试炼] 试炼通过！",
            autoReward = true,
            autoRewardDelay = 100L,   // 5 秒后关闭
            money = 0.0,
            onReward = { player -> unlockT2Recipes(player) }
        )
    }

    private fun unlockT2Recipes(player: Player) {
        val playerClass = plugin.playerDataManager.getPlayerClass(player)
        val allRecipes = plugin.forgeManager.getAllRecipes()
        var unlockedCount = 0

        allRecipes.forEach { recipe ->
            if (recipe.id.contains("_t2")) {
                if (plugin.itemManager.isItemAllowedForClass(recipe.targetItemId, playerClass)) {
                    if (!plugin.forgeManager.hasUnlockedRecipe(player, recipe.id)) {
                        plugin.forgeManager.unlockRecipe(player, recipe.id)
                        unlockedCount++
                    }
                }
            }
        }

        if (unlockedCount > 0) {
            player.sendMessage("§b[系统] 你领悟了 ${unlockedCount} 个二阶锻造配方！")
        } else {
            player.sendMessage("§7[系统] 你的锻造技艺已至瓶颈，暂无新配方可领悟。")
        }
    }

    // ==========================================
    // 玩法阶段：汲灵之舞
    // ==========================================
    override fun createGamePhase(instance: DungeonInstance): AbstractDungeonPhase {
        return GamePhase(instance)
    }

    inner class GamePhase(instance: DungeonInstance) : AbstractDungeonPhase(plugin, instance) {

        // 游戏配置
        private val arenaRadius = 15.0
        private val beamRadius = 3.5
        private val beamSpeed = 0.15
        private val maxProgress = 100.0

        // 运行时状态
        private var currentProgress = 0.0
        private var isFinished = false
        private lateinit var bossBar: BossBar

        // 光柱位置状态
        private lateinit var beamLoc: Location
        private lateinit var targetLoc: Location

        override fun start() {
            bossBar = BossBar.bossBar(
                Component.text("§a灵气充能进度"),
                0f,
                BossBar.Color.GREEN,
                BossBar.Overlay.PROGRESS
            )
            instance.players.forEach { Bukkit.getPlayer(it)?.showBossBar(bossBar) }

            beamLoc = instance.centerLocation.clone()
            pickNewTarget()

            instance.broadcast("§a试炼开始！进入光柱充能！")
        }

        override fun onTick() {
            if (isFinished) return

            moveBeam()
            renderBeamEffects()
            checkPlayers()

            if (instance.tickCount % 60 == 0L) {
                spawnPusherMob()
            }

            updateBossBar()

            if (currentProgress >= maxProgress) {
                win()
            }
        }

        private fun moveBeam() {
            val dir = targetLoc.toVector().subtract(beamLoc.toVector())
            val distance = dir.length()

            if (distance < beamSpeed) {
                beamLoc = targetLoc.clone()
                pickNewTarget()
            } else {
                dir.normalize().multiply(beamSpeed)
                beamLoc.add(dir)
            }
        }

        private fun pickNewTarget() {
            val randomAngle = Random.nextDouble() * 2 * Math.PI
            val randomDist = Random.nextDouble() * arenaRadius
            val x = randomDist * kotlin.math.cos(randomAngle)
            val z = randomDist * kotlin.math.sin(randomAngle)
            targetLoc = instance.centerLocation.clone().add(x, 0.0, z)
        }

        private fun renderBeamEffects() {
            val world = beamLoc.world ?: return

            for (y in 0..10 step 1) {
                world.spawnParticle(
                    Particle.HAPPY_VILLAGER,
                    beamLoc.clone().add(0.0, y.toDouble(), 0.0),
                    2, 0.2, 0.5, 0.2, 0.0
                )
            }

            for (i in 0 until 16) {
                val angle = (i.toDouble() / 16) * 2 * Math.PI
                val px = beamLoc.x + beamRadius * kotlin.math.cos(angle)
                val pz = beamLoc.z + beamRadius * kotlin.math.sin(angle)
                world.spawnParticle(
                    Particle.COMPOSTER,
                    px, beamLoc.y + 0.5, pz,
                    0, 0.0, 0.0, 0.0
                )
            }
        }

        private fun checkPlayers() {
            var playerInside = false

            for (uuid in instance.players) {
                val player = Bukkit.getPlayer(uuid) ?: continue
                if (player.isDead) continue

                val distSq = (player.location.x - beamLoc.x) * (player.location.x - beamLoc.x) +
                        (player.location.z - beamLoc.z) * (player.location.z - beamLoc.z)

                if (distSq <= beamRadius * beamRadius) {
                    playerInside = true
                    player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 20, 1, false, false))
                }
            }

            if (playerInside) {
                currentProgress += 0.25
                if (currentProgress > maxProgress) currentProgress = maxProgress
            } else {
                currentProgress -= 0.05
                if (currentProgress < 0) currentProgress = 0.0
            }
        }

        private fun spawnPusherMob() {
            val randomPlayer = instance.players.mapNotNull { Bukkit.getPlayer(it) }.randomOrNull() ?: return
            val spawnLoc = randomPlayer.location.clone().add(
                (Random.nextDouble() - 0.5) * 10,
                0.0,
                (Random.nextDouble() - 0.5) * 10
            )
            spawnLoc.y = instance.centerLocation.y + 1

            val mob = instance.world.spawnEntity(spawnLoc, EntityType.ZOMBIE) as Zombie
            mob.customName(Component.text("§2腐化干尸"))
            mob.isCustomNameVisible = true
            mob.setBaby(false)

            mob.equipment.helmet = ItemStack(Material.STONE_BUTTON)
            mob.equipment.helmetDropChance = 0f

            mob.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 2.0
            mob.health = 2.0
            mob.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.35
            mob.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 1.0

            mob.lootTable = org.bukkit.loot.LootTables.VEX.lootTable

            val stick = ItemStack(Material.STICK)
            val meta = stick.itemMeta
            meta.addEnchant(Enchantment.KNOCKBACK, 5, true)
            stick.itemMeta = meta

            mob.equipment.setItemInMainHand(stick)
            mob.equipment.itemInMainHandDropChance = 0f

            mob.target = randomPlayer
        }

        private fun updateBossBar() {
            bossBar.progress((currentProgress / maxProgress).toFloat().coerceIn(0f, 1f))
            bossBar.name(Component.text("§a灵气充能: ${currentProgress.toInt()}%"))
        }

        private fun win() {
            if (isFinished) return
            isFinished = true

            // 清理 BossBar
            instance.players.forEach { Bukkit.getPlayer(it)?.hideBossBar(bossBar) }

            // 清理场地怪物
            instance.centerLocation.getNearbyEntities(40.0, 20.0, 40.0).forEach { entity ->
                if (entity !is Player) entity.remove()
            }

            instance.broadcast("§a§l试炼通过！§r §e正在发放奖励...")

            // [核心改动] 委托给框架的奖励阶段，不再手写解锁/关闭逻辑
            goToRewardPhase(instance)
        }

        override fun end() {
            instance.players.forEach { Bukkit.getPlayer(it)?.hideBossBar(bossBar) }
        }
    }
}
