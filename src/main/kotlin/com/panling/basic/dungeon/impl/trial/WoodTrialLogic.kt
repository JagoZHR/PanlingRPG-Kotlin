package com.panling.basic.dungeon.impl.trial

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.DungeonLogicProvider
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.dungeon.phase.WaitingPhase
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
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.random.Random

class WoodTrialLogic(private val plugin: PanlingBasic) : DungeonLogicProvider {

    override val templateId: String = "trial_wood"

    override fun createInitialPhase(instance: DungeonInstance): AbstractDungeonPhase {
        return IntroPhase(instance)
    }

    // --- 阶段 1: 介绍与等待 ---
    inner class IntroPhase(instance: DungeonInstance) : WaitingPhase(plugin, instance, 10) {
        override fun start() {
            super.start()
            instance.broadcast("§a[青龙试炼] §f即将在 10 秒后开启。")
            instance.broadcast("§7提示：追逐移动的灵气光柱，避免被干尸推出去！")
        }

        override fun onTimeout() {
            instance.nextPhase(GamePhase(instance))
        }
    }

    // --- 阶段 2: 汲灵之舞 (核心玩法) ---
    inner class GamePhase(instance: DungeonInstance) : AbstractDungeonPhase(plugin, instance) {

        // 游戏配置
        private val arenaRadius = 15.0      // 场地半径
        private val beamRadius = 3.5        // 光柱判定半径
        private val beamSpeed = 0.15        // 光柱移动速度 (格/tick)
        private val maxProgress = 100.0     // 目标进度

        // 运行时状态
        private var currentProgress = 0.0
        private var isFinished = false
        private lateinit var bossBar: BossBar

        // 光柱位置状态
        private lateinit var beamLoc: Location
        private lateinit var targetLoc: Location

        override fun start() {
            // 初始化 BossBar
            bossBar = BossBar.bossBar(
                Component.text("§a灵气充能进度"),
                0f,
                BossBar.Color.GREEN,
                BossBar.Overlay.PROGRESS
            )
            instance.players.forEach { Bukkit.getPlayer(it)?.showBossBar(bossBar) }

            // 初始化光柱位置 (从中心开始)
            beamLoc = instance.centerLocation.clone()
            pickNewTarget()

            instance.broadcast("§a试炼开始！进入光柱充能！")
        }

        override fun onTick() {

            if (isFinished) return
            // 1. 处理光柱移动
            moveBeam()

            // 2. 渲染光柱特效
            renderBeamEffects()

            // 3. 检测玩家进度
            checkPlayers()

            // 4. 刷怪逻辑 (每 3 秒尝试刷一只)
            if (instance.tickCount % 60 == 0L) {
                spawnPusherMob()
            }

            // 5. 更新 UI
            updateBossBar()

            // 6. 胜利判定
            if (currentProgress >= maxProgress) {
                win()
            }
        }

        private fun moveBeam() {
            // 计算方向向量
            val dir = targetLoc.toVector().subtract(beamLoc.toVector())
            val distance = dir.length()

            if (distance < beamSpeed) {
                // 到达目标点，选取新点
                beamLoc = targetLoc.clone()
                pickNewTarget()
            } else {
                // 向目标移动
                dir.normalize().multiply(beamSpeed)
                beamLoc.add(dir)
            }
        }

        private fun pickNewTarget() {
            // 在场地半径内随机选点
            // y 轴保持与中心点一致
            val randomAngle = Random.nextDouble() * 2 * Math.PI
            val randomDist = Random.nextDouble() * arenaRadius
            val x = randomDist * kotlin.math.cos(randomAngle)
            val z = randomDist * kotlin.math.sin(randomAngle)

            targetLoc = instance.centerLocation.clone().add(x, 0.0, z)
        }

        private fun renderBeamEffects() {
            val world = beamLoc.world ?: return

            // 1. 垂直光柱 (高耸)
            for (y in 0..10 step 1) {
                world.spawnParticle(
                    Particle.HAPPY_VILLAGER,
                    beamLoc.clone().add(0.0, y.toDouble(), 0.0),
                    2, 0.2, 0.5, 0.2, 0.0
                )
            }

            // 2. 地面判定圈
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

                // 距离检测 (忽略 Y 轴差异，只看平面距离)
                val distSq = (player.location.x - beamLoc.x) * (player.location.x - beamLoc.x) +
                        (player.location.z - beamLoc.z) * (player.location.z - beamLoc.z)

                if (distSq <= beamRadius * beamRadius) {
                    playerInside = true
                    // 给玩家回血 Buff (只要在圈里就不死)
                    player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 20, 1, false, false))
                }
            }

            if (playerInside) {
                // 有人在圈里，加进度
                currentProgress += 0.25 // 每秒约 +5% (20 ticks * 0.25 = 5)
                if (currentProgress > maxProgress) currentProgress = maxProgress
            } else {
                // 没人，缓慢扣进度
                currentProgress -= 0.05
                if (currentProgress < 0) currentProgress = 0.0
            }
        }

        private fun spawnPusherMob() {
            // 限制最大怪物数量 (比如 8 只)
            // 这是一个简单的计数，实际可以通过 AbstractCombatPhase 的 activeMobUuids 来判断
            val nearbyEntities = beamLoc.world!!.getNearbyEntities(beamLoc, 30.0, 10.0, 30.0)
            val mobCount = nearbyEntities.count { it is Zombie }
            if (mobCount >= 8) return

            // 在玩家周围随机生成
            val randomPlayer = instance.players.mapNotNull { Bukkit.getPlayer(it) }.randomOrNull() ?: return
            val spawnLoc = randomPlayer.location.clone().add(
                (Random.nextDouble() - 0.5) * 10,
                0.0,
                (Random.nextDouble() - 0.5) * 10
            )
            // 修正 Y 轴防止刷在地下
            spawnLoc.y = instance.centerLocation.y + 1 // 假设地面平整，直接 +1

            val mob = instance.world.spawnEntity(spawnLoc, EntityType.ZOMBIE) as Zombie

            // --- 怪物配置 ---
            mob.customName(Component.text("§2腐化干尸"))
            mob.isCustomNameVisible = true
            mob.setBaby(false)

            mob.equipment.helmet = ItemStack(Material.STONE_BUTTON)
            mob.equipment.helmetDropChance = 0f

            // 属性：血量极低，移速快
            mob.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 2.0
            mob.health = 2.0
            mob.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.35

            // 攻击力归零 (靠击退恶心人)
            mob.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 1.0

            // 装备：击退棒子
            val stick = ItemStack(Material.STICK)
            val meta = stick.itemMeta
            meta.addEnchant(Enchantment.KNOCKBACK, 5, true) // 击退 V
            stick.itemMeta = meta

            mob.equipment.setItemInMainHand(stick)
            mob.equipment.itemInMainHandDropChance = 0f // 不掉落

            // AI 目标
            mob.target = randomPlayer
        }

        private fun updateBossBar() {
            bossBar.progress((currentProgress / maxProgress).toFloat().coerceIn(0f, 1f))
            bossBar.name(Component.text("§a灵气充能: ${currentProgress.toInt()}%"))
        }

        // --- 核心修改：分职业发放配方，移除直接资格发放 ---
        private fun win() {
            // [修复] 如果已经标记结束，直接返回，不再执行后续逻辑
            if (isFinished) return

            // [修复] 立即标记为结束！这将阻止 onTick 继续渲染特效和调用 win
            isFinished = true

            instance.players.forEach { Bukkit.getPlayer(it)?.hideBossBar(bossBar) }

            instance.centerLocation.getNearbyEntities(40.0, 20.0, 40.0).forEach { entity ->
                if (entity !is Player) entity.remove()
            }

            instance.players.forEach { uuid ->
                val player = Bukkit.getPlayer(uuid) ?: return@forEach

                player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
                instance.broadcast("§a§l试炼通过！§r §e恭喜 ${player.name} 获得了二阶装备锻造资格！")

                // 2. [核心简化] 一键解锁所有 T2 配方
                // 你的 GUI 会自动过滤掉非本职业的，所以这里全给也没问题！
                // 假设所有 T2 配方的 ID 都包含 "_t2" 或者 "_tier2"
                var unlockedCount = 0

                // 遍历 ForgeManager 中加载的所有配方
                // 这里的 recipes 是私有的，你需要去 ForgeManager 加一个 getAllRecipes() 方法
                // 或者更简单的：既然你手动配置了配方，你肯定知道 T2 配方的特征

                // [方案 A] 通过 ForgeManager 新增的 API 获取所有配方 (推荐)
                val allRecipes = plugin.forgeManager.getAllRecipes()
                allRecipes.forEach { recipe ->
                    // 假设你的 ID 命名规范是 "sword_t2", "armor_t2" 等
                    if (recipe.id.contains("_t2") || recipe.id.contains("_tier2")) {
                        if (!plugin.forgeManager.hasUnlockedRecipe(player, recipe.id)) {
                            plugin.forgeManager.unlockRecipe(player, recipe.id)
                            unlockedCount++
                        }
                    }
                }

                if (unlockedCount > 0) {
                    player.sendMessage("§b[系统] 你领悟了二阶锻造配方！")
                    player.sendMessage("§7提示：请前往锻造台查看新配方。")
                } else {
                    player.sendMessage("§7[系统] 你已经学会了相关配方。")
                }
            }

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                instance.winDungeon()
            }, 100L)
        }

        // 阶段结束时清理 BossBar (防止异常退出残留)
        override fun end() {
            instance.players.forEach { Bukkit.getPlayer(it)?.hideBossBar(bossBar) }
        }
    }
}