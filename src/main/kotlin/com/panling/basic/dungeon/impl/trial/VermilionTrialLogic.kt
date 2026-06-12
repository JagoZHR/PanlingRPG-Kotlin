package com.panling.basic.dungeon.impl.trial

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.RewardConfig
import com.panling.basic.dungeon.StandardDungeonLogic
import com.panling.basic.dungeon.phase.AbstractCombatPhase
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.dungeon.phase.StandardWaitingPhase
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Blaze
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Skeleton
import org.bukkit.entity.Zombie
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import kotlin.random.Random

class VermilionTrialLogic(plugin: PanlingBasic) : StandardDungeonLogic(plugin) {

    override val templateId: String = "vermillion_trial"
    override val waitDuration = 10

    // ==========================================
    // 动态难度：1人=1.0x, 2人=1.5x, 3人=2.0x
    // ==========================================
    private fun getHpScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.5
    private fun getAtkScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.1

    // ==========================================
    // 奖励：自动发奖 + 解锁 T3 配方
    // ==========================================
    override fun createRewardConfig(instance: DungeonInstance): RewardConfig {
        return RewardConfig(
            title = "§c[朱雀试炼] 涅槃成功！",
            autoReward = true,
            autoRewardDelay = 100L,
            onReward = { player -> unlockT3Recipes(player) }
        )
    }

    private fun unlockT3Recipes(player: Player) {
        val playerClass = plugin.playerDataManager.getPlayerClass(player)
        val allRecipes = plugin.forgeManager.getAllRecipes()
        var unlockedCount = 0

        allRecipes.forEach { recipe ->
            if (recipe.id.contains("_t3")) {
                if (plugin.itemManager.isItemAllowedForClass(recipe.targetItemId, playerClass)) {
                    if (!plugin.forgeManager.hasUnlockedRecipe(player, recipe.id)) {
                        plugin.forgeManager.unlockRecipe(player, recipe.id)
                        unlockedCount++
                    }
                }
            }
        }

        if (unlockedCount > 0) {
            player.sendMessage("§b[系统] 你领悟了 ${unlockedCount} 个三阶锻造配方！")
        }
    }

    // ==========================================
    // 玩法阶段入口
    // ==========================================
    override fun createGamePhase(instance: DungeonInstance): AbstractDungeonPhase {
        return Phase1Corners(instance)
    }

    // ==========================================
    // 阶段 1：四角射手
    // ==========================================
    inner class Phase1Corners(instance: DungeonInstance) : AbstractCombatPhase(plugin, instance) {

        private val cornerOffsets = listOf(
            Vector(10.0, 0.0, 10.0),
            Vector(10.0, 0.0, -10.0),
            Vector(-10.0, 0.0, 10.0),
            Vector(-10.0, 0.0, -10.0)
        )

        override fun start() {
            val hpScale = getHpScale(instance)
            val atkScale = getAtkScale(instance)
            val center = instance.centerLocation

            cornerOffsets.forEach { offset ->
                val loc = center.clone().add(offset)
                val skelly = plugin.mobManager.spawnMob(loc, "dungeon_vermillion_ember_archer") as? Skeleton ?: return@forEach
                scaleMob(skelly, hpScale, atkScale)
                spawnMob(skelly)
            }

            instance.broadcast("§c四角的余烬射手已出现，消灭他们！")
            instance.broadcastSound(Sound.ENTITY_SKELETON_AMBIENT)
        }

        override fun onWaveClear() {
            instance.broadcast("§c余烬射手全部清除！§6朱雀之影§c即将降临...")
            instance.broadcastSound(Sound.ENTITY_BLAZE_AMBIENT)

            // 5 秒延迟后进入二阶段
            instance.nextPhase(StandardWaitingPhase(plugin, instance, 5) {
                instance.nextPhase(Phase2Boss(instance))
            })
        }

        private fun scaleMob(entity: LivingEntity, hpScale: Double, atkScale: Double) {
            entity.getAttribute(Attribute.MAX_HEALTH)?.let { attr ->
                attr.baseValue = attr.baseValue * hpScale
                entity.health = attr.baseValue
            }
            entity.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr ->
                attr.baseValue = attr.baseValue * atkScale
            }
        }
    }

    // ==========================================
    // 阶段 2：朱雀之影 / 黯淡的火种 循环
    // ==========================================
    inner class Phase2Boss(instance: DungeonInstance) : AbstractDungeonPhase(plugin, instance) {

        private var bossBlaze: Blaze? = null
        private var smallBlaze: Blaze? = null
        private var originalBossHp: Double = 0.0
        private var smallSpawnTick: Long = 0L
        private var isFinished = false
        private var hpScale: Double = 1.0
        private var atkScale: Double = 1.0

        // 副怪刷新
        private var nextZombieSpawnTick: Long = 0L
        private val zombieSpawnInterval = 80L // 每 4 秒

        override fun start() {
            hpScale = getHpScale(instance)
            atkScale = getAtkScale(instance)
            val count = instance.players.size

            instance.broadcast("§c§l朱雀之影 §r§c降临！当前挑战人数: ${count}人")
            originalBossHp = 250.0 * hpScale
            spawnBossBlaze(originalBossHp)

            nextZombieSpawnTick = instance.tickCount + zombieSpawnInterval
        }

        // === Boss 生成 ===

        private fun spawnBossBlaze(hp: Double) {
            val center = instance.centerLocation.clone().add(0.0, 1.0, 0.0)
            val blaze = plugin.mobManager.spawnMob(center, "dungeon_vermillion_boss") as? Blaze ?: return
            // HP 动态覆盖（复活机制），攻击力用 YAML × atkScale
            blaze.getAttribute(Attribute.MAX_HEALTH)?.baseValue = hp
            blaze.health = hp
            scaleMob(blaze, 1.0, atkScale) // hpScale=1.0 因为血量已手动设置
            blaze.isGlowing = true
            bossBlaze = blaze
            instance.broadcast("§c朱雀之影 §7燃烧着降临了！(HP: ${hp.toInt()})")
            instance.broadcastSound(Sound.ENTITY_BLAZE_SHOOT)
        }

        private fun spawnSmallBlaze(hp: Double) {
            val center = instance.centerLocation.clone().add(0.0, 1.0, 0.0)

            val blaze = plugin.mobManager.spawnMob(center, "dungeon_vermillion_dim_ember") as? Blaze ?: return
            blaze.getAttribute(Attribute.MAX_HEALTH)?.baseValue = hp
            blaze.health = hp
            blaze.isGlowing = false

            smallBlaze = blaze
            smallSpawnTick = instance.tickCount

            instance.broadcast("§6黯淡的火种 §e出现了！在 10 秒内消灭它！(HP: ${hp.toInt()})")
            instance.broadcastSound(Sound.BLOCK_FIRE_EXTINGUISH)
        }

        // === 副怪 ===

        private fun spawnZombieWave() {
            if (bossBlaze == null && smallBlaze == null) return

            val target = bossBlaze?.location ?: smallBlaze?.location ?: return
            val count = (1..2).random()

            repeat(count) {
                val offsetX = (Random.nextDouble() - 0.5) * 8
                val offsetZ = (Random.nextDouble() - 0.5) * 8
                val spawnLoc = target.clone().add(offsetX, 0.0, offsetZ)

                val zombie = plugin.mobManager.spawnMob(spawnLoc, "dungeon_vermillion_bandit") as? Zombie ?: return@repeat
                scaleMob(zombie, hpScale, atkScale)
            }
        }

        // === Tick ===

        override fun onTick() {
            if (isFinished) return

            val now = instance.tickCount

            // 定期刷副怪
            if (now >= nextZombieSpawnTick) {
                spawnZombieWave()
                nextZombieSpawnTick = now + zombieSpawnInterval
            }

            // 检测 Boss 死亡 → 召唤小火种
            if (bossBlaze != null && (bossBlaze!!.isDead || !bossBlaze!!.isValid)) {
                val bossMaxHp = bossBlaze!!.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: originalBossHp
                bossBlaze = null
                val halfHp = bossMaxHp / 2.0
                if (halfHp >= 1.0) {
                    spawnSmallBlaze(halfHp)
                }
            }

            // 检测小火种
            if (smallBlaze != null) {
                val blaze = smallBlaze!!

                // 被击杀 → 胜利
                if (blaze.isDead || !blaze.isValid) {
                    smallBlaze = null
                    win()
                    return
                }

                // 10 秒倒计时（200 ticks）
                val elapsed = now - smallSpawnTick
                if (elapsed >= 200L) {
                    // 超时 → 剩余血量复活 Boss
                    val remainingHp = blaze.health.coerceAtLeast(1.0)
                    instance.broadcast("§c火种未灭！朱雀之影从余烬中重生... (HP: ${remainingHp.toInt()})")
                    blaze.remove()
                    smallBlaze = null
                    spawnBossBlaze(remainingHp)
                }

                // 每 20 ticks 提示剩余时间
                if (elapsed > 0 && elapsed % 20 == 0L) {
                    val secondsLeft = ((200L - elapsed) / 20).toInt()
                    if (secondsLeft <= 5 || secondsLeft == 10) {
                        instance.broadcast("§e火种剩余: ${secondsLeft}秒")
                    }
                }
            }
        }

        // === 胜利 ===

        private fun win() {
            if (isFinished) return
            isFinished = true

            instance.broadcast("§c§l朱雀之影已彻底消散！§r")
            instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)

            // 清理所有残余怪物
            instance.centerLocation.getNearbyEntities(50.0, 30.0, 50.0).forEach { entity ->
                if (entity !is Player) entity.remove()
            }

            // 清理引用
            bossBlaze?.remove()
            smallBlaze?.remove()
            bossBlaze = null
            smallBlaze = null

            goToRewardPhase(instance)
        }

        // === 清理 ===

        override fun end() {
            bossBlaze?.remove()
            smallBlaze?.remove()
            bossBlaze = null
            smallBlaze = null
        }

        private fun scaleMob(entity: LivingEntity, hpScale: Double, atkScale: Double) {
            entity.getAttribute(Attribute.MAX_HEALTH)?.let { attr ->
                attr.baseValue = attr.baseValue * hpScale
                entity.health = attr.baseValue
            }
            entity.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr ->
                attr.baseValue = attr.baseValue * atkScale
            }
        }
    }
}
