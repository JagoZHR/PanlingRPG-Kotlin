package com.panling.basic.dungeon.impl.trial

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.RewardConfig
import com.panling.basic.dungeon.StandardDungeonLogic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.EntityType
import org.bukkit.entity.IronGolem
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import org.joml.Quaternionf
import java.util.UUID
import kotlin.random.Random

class WhiteTigerTrialLogic(plugin: PanlingBasic) : StandardDungeonLogic(plugin) {

    override val templateId = "white_tiger_trial"
    override val waitDuration = 10

    // ==========================================
    // 动态难度
    // ==========================================
    private fun getHpScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.5
    private fun getAtkScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.1

    // ==========================================
    // 奖励：解锁 T4 装备 + T4 法宝
    // ==========================================
    override fun createRewardConfig(instance: DungeonInstance): RewardConfig {
        return RewardConfig(
            title = "§f[白虎试炼] 金锋试炼通过！",
            autoReward = true,
            autoRewardDelay = 100L,
            onReward = { player -> unlockT4Recipes(player) }
        )
    }

    private fun unlockT4Recipes(player: Player) {
        val playerClass = plugin.playerDataManager.getPlayerClass(player)
        val allRecipes = plugin.forgeManager.getAllRecipes()
        var unlockedCount = 0

        allRecipes.forEach { recipe ->
            // 解锁 _t4 配方（武器+防具）以及 fabao T4
            if (recipe.id.contains("_t4") || recipe.id.contains("fabao_t4")) {
                if (plugin.itemManager.isItemAllowedForClass(recipe.targetItemId, playerClass)) {
                    if (!plugin.forgeManager.hasUnlockedRecipe(player, recipe.id)) {
                        plugin.forgeManager.unlockRecipe(player, recipe.id)
                        unlockedCount++
                    }
                }
            }
        }

        if (unlockedCount > 0) {
            player.sendMessage("§b[系统] 你领悟了 ${unlockedCount} 个四阶锻造配方！")
        }
    }

    // ==========================================
    // 玩法入口
    // ==========================================
    override fun createGamePhase(instance: DungeonInstance): AbstractDungeonPhase {
        return Phase1Swords(instance)
    }

    // ==========================================
    // 阶段 1：飞剑躲避
    // ==========================================
    inner class Phase1Swords(instance: DungeonInstance) : AbstractDungeonPhase(plugin, instance) {

        private val durationTicks = 600L
        private val arenaHalf = 10.0
        private val swordDamageBase = 15.0

        private var startTick = 0L
        private var nextSwordTick = 0L
        private var isFinished = false

        override fun start() {
            startTick = instance.tickCount
            nextSwordTick = startTick + 40L    // 2 秒后第一把
            instance.broadcast("§f飞剑来袭！躲避从墙壁袭来的利刃，坚持 30 秒！")
            instance.broadcastSound(Sound.ITEM_TRIDENT_THROW)
        }

        override fun onTick() {
            if (isFinished) return

            val elapsed = instance.tickCount - startTick

            // 时间到 → 下一阶段
            if (elapsed >= durationTicks) {
                isFinished = true
                instance.broadcast("§f飞剑停止了！§e玄金傀儡§f正在苏醒...")
                instance.broadcastSound(Sound.ENTITY_IRON_GOLEM_STEP)
                instance.nextPhase(Phase2Golems(instance))
                return
            }

            // 倒计时提示
            if (elapsed % 200L == 0L && elapsed > 0) {
                val remaining = (durationTicks - elapsed) / 20
                instance.broadcast("§7剩余 §f${remaining} §7秒")
            }

            // 发射飞剑
            if (instance.tickCount >= nextSwordTick) {
                launchSword()
                nextSwordTick = instance.tickCount + Random.nextLong(15, 35)
            }
        }

        private fun launchSword() {
            val atkScale = getAtkScale(instance)
            val center = instance.centerLocation

            // 随机选墙：0=北 1=南 2=东 3=西
            val wall = Random.nextInt(4)
            val startLoc: Location
            val endLoc: Location
            val y = center.y + 1.5

            when (wall) {
                0 -> { // 北墙 → 南墙
                    startLoc = Location(instance.world, center.x + randArena(), y, center.z - arenaHalf)
                    endLoc   = Location(instance.world, center.x + randArena(), y, center.z + arenaHalf)
                }
                1 -> { // 南墙 → 北墙
                    startLoc = Location(instance.world, center.x + randArena(), y, center.z + arenaHalf)
                    endLoc   = Location(instance.world, center.x + randArena(), y, center.z - arenaHalf)
                }
                2 -> { // 东墙 → 西墙
                    startLoc = Location(instance.world, center.x + arenaHalf, y, center.z + randArena())
                    endLoc   = Location(instance.world, center.x - arenaHalf, y, center.z + randArena())
                }
                else -> { // 西墙 → 东墙
                    startLoc = Location(instance.world, center.x - arenaHalf, y, center.z + randArena())
                    endLoc   = Location(instance.world, center.x + arenaHalf, y, center.z + randArena())
                }
            }

            val direction = endLoc.toVector().subtract(startLoc.toVector())
            val distance = direction.length()
            direction.normalize()

            // 警告粒子（25 ticks ≈ 1.25秒）
            val warnTicks = 25
            val warnStep = direction.clone().multiply(distance / warnTicks.toDouble())
            val warnTask = object : BukkitRunnable() {
                var tick = 0
                override fun run() {
                    if (tick >= warnTicks) { cancel(); return }
                    val pos = startLoc.clone().add(warnStep.clone().multiply(tick.toDouble()))
                    for (i in 0..5) {
                        instance.world.spawnParticle(
                            Particle.END_ROD,
                            pos.x + randOffset(), pos.y + randOffset(), pos.z + randOffset(),
                            0, 0.0, 0.0, 0.0
                        )
                    }
                    tick++
                }
            }
            warnTask.runTaskTimer(plugin, 0L, 1L)

            // 预警结束后发射飞剑
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                spawnSwordEntity(startLoc.clone(), endLoc, direction, distance, atkScale)
            }, warnTicks.toLong())
        }

        private fun spawnSwordEntity(start: Location, end: Location, dir: Vector, dist: Double, scale: Double) {
            val sword = instance.world.spawn(start, ItemDisplay::class.java) { entity ->
                entity.setItemStack(ItemStack(Material.IRON_AXE))
            }

            // 朝向：先面朝方向 → 放平 → 逆时针45°修正斜角
            // 必须先 rotateY，否则 X/Z 变换后 Y 轴已不再是世界 Y 轴
            val yaw = Math.toDegrees(Math.atan2(dir.z, dir.x)).toFloat()
            val t = sword.transformation
            t.leftRotation.set(
                Quaternionf()
                    .rotateY(Math.toRadians(yaw.toDouble()).toFloat())
                    .rotateX(Math.toRadians(90.0).toFloat())
                    .rotateZ(Math.toRadians(-45.0).toFloat())
            )
            sword.transformation = t
            sword.isGlowing = true

            val travelTicks = 8
            val step = dir.clone().multiply(dist / travelTicks)
            val damage = swordDamageBase * scale
            val hitPlayers = mutableSetOf<UUID>()

            object : BukkitRunnable() {
                var tick = 0
                override fun run() {
                    if (tick >= travelTicks || !sword.isValid) {
                        sword.remove()
                        cancel()
                        return
                    }
                    val newLoc = sword.location.add(step)
                    sword.teleport(newLoc)

                    // 碰撞检测（3 格范围 + 防重复命中）
                    for (entity in newLoc.world.getNearbyEntities(newLoc, 3.0, 2.0, 3.0)) {
                        if (entity !is Player) continue
                        if (!instance.players.contains(entity.uniqueId)) continue
                        if (entity.isDead) continue
                        if (hitPlayers.contains(entity.uniqueId)) continue

                        entity.damage(damage)
                        entity.playSound(entity.location, Sound.ENTITY_PLAYER_HURT, 0.5f, 1f)
                        hitPlayers.add(entity.uniqueId)
                    }
                    tick++
                }
            }.runTaskTimer(plugin, 0L, 1L)
        }

        private fun randArena(): Double = (Random.nextDouble() - 0.5) * arenaHalf * 1.8
        private fun randOffset(): Double = (Random.nextDouble() - 0.5) * 0.3

        override fun end() {
            // Phase 自动清理，无需手动操作
        }
    }

    // ==========================================
    // 阶段 2：玄金傀儡 (反伤 + 同步击杀)
    // ==========================================
    inner class Phase2Golems(instance: DungeonInstance) : AbstractDungeonPhase(plugin, instance) {

        private val golems = mutableMapOf<IronGolem, Double>() // golem → maxHp
        private val deadTicks = mutableMapOf<UUID, Long>() // dead golem UUID → death tick

        private var reflectActive = false
        private var nextReflectTick = 0L
        private var lastDeathTick = 0L
        private var isFinished = false

        private val reflectDuration = 60L   // 3 秒
        private val reflectInterval = 100L  // 5 秒间隔
        private val syncWindow = 60L        // 3 秒同步窗口

        override fun start() {
            val count = instance.players.size
            val hpScale = getHpScale(instance)
            val atkScale = getAtkScale(instance)
            val center = instance.centerLocation

            val offsets = when (count) {
                1 -> listOf(Vector(0.0, 0.0, 0.0))
                2 -> listOf(Vector(2.0, 0.0, 0.0), Vector(-2.0, 0.0, 0.0))
                else -> listOf(Vector(2.0, 0.0, 0.0), Vector(-2.0, 0.0, 0.0), Vector(0.0, 0.0, 2.0))
            }

            for (offset in offsets) {
                // 主傀儡
                val loc1 = center.clone().add(offset).add(0.0, 1.0, 0.0)
                val golem1 = plugin.mobManager.spawnMob(loc1, "dungeon_white_tiger_golem") as? IronGolem ?: continue
                scaleMob(golem1, hpScale, atkScale)
                golems[golem1] = golem1.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 450.0

                // 副傀儡 (半血半攻)
                val loc2 = center.clone().add(offset).add(1.5, 1.0, 0.0)
                val golem2 = plugin.mobManager.spawnMob(loc2, "dungeon_white_tiger_golem") as? IronGolem ?: continue
                scaleMob(golem2, hpScale, atkScale, hpMult = 0.5, atkMult = 0.5)
                golems[golem2] = golem2.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 225.0
            }

            nextReflectTick = instance.tickCount + reflectInterval
            instance.broadcast("§f${count} 个§e玄金傀儡§f已激活！必须在 3 秒内同时击杀全部！")
        }

        override fun onTick() {
            if (isFinished) return

            val now = instance.tickCount

            // 切换反伤状态
            if (now >= nextReflectTick) {
                if (reflectActive) {
                    deactivateReflect()
                    nextReflectTick = now + reflectInterval
                } else {
                    activateReflect()
                    // 反伤持续 reflectDuration 后关闭
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        if (!isFinished && reflectActive) deactivateReflect()
                    }, reflectDuration)
                    nextReflectTick = now + reflectInterval + reflectDuration
                }
            }

            // 检查复活条件：有傀儡死亡但同步窗口已过
            if (deadTicks.isNotEmpty() && now - lastDeathTick > syncWindow) {
                reviveAll()
            }

            // 检查存活数
            var aliveCount = 0
            for (golem in golems.keys) {
                if (!golem.isDead && golem.isValid) aliveCount++
            }
            if (aliveCount == 0 && deadTicks.isNotEmpty()) {
                // 全部死亡 → 检查是否在同步窗口内
                if (now - lastDeathTick <= syncWindow) {
                    win()
                }
            }
        }

        private fun activateReflect() {
            reflectActive = true
            for (golem in golems.keys) {
                if (!golem.isDead && golem.isValid) {
                    golem.setMetadata("pl_reflect", FixedMetadataValue(plugin, true))
                    golem.isGlowing = true
                    // 金色粒子
                    golem.world.spawnParticle(
                        Particle.ELECTRIC_SPARK,
                        golem.location.add(0.0, 1.5, 0.0),
                        30, 0.5, 1.0, 0.5, 0.0
                    )
                }
            }
            instance.broadcast("§6玄金傀儡进入了反伤状态！停止攻击！")
            instance.broadcastSound(Sound.BLOCK_ANVIL_LAND)
        }

        private fun deactivateReflect() {
            reflectActive = false
            for (golem in golems.keys) {
                if (!golem.isDead && golem.isValid) {
                    golem.removeMetadata("pl_reflect", plugin)
                    golem.isGlowing = false
                }
            }
            instance.broadcast("§a反伤状态解除，可以攻击了！")
        }

        override fun onDamage(event: EntityDamageByEntityEvent) {
            val entity = event.entity
            if (entity !is IronGolem) return
            if (!entity.hasMetadata("pl_reflect")) return

            // 反伤：取消对傀儡的伤害，反弹给攻击者
            event.isCancelled = true
            val attacker = event.damager
            if (attacker is LivingEntity) {
                attacker.damage(event.damage)
                if (attacker is Player) {
                    attacker.sendMessage("§c你的攻击被反弹了！")
                }
                attacker.world.spawnParticle(
                    Particle.DAMAGE_INDICATOR,
                    attacker.location.add(0.0, 1.0, 0.0),
                    10, 0.3, 0.5, 0.3, 0.0
                )
            }
        }

        override fun onMobDeath(event: org.bukkit.event.entity.EntityDeathEvent) {
            val entity = event.entity
            if (entity !is IronGolem) return

            // 禁止掉落
            event.drops.clear()
            event.droppedExp = 0

            val now = instance.tickCount
            deadTicks[entity.uniqueId] = now
            lastDeathTick = now

            // 清理追踪
            golems.keys.removeIf { it.uniqueId == entity.uniqueId }

            instance.broadcast("§e玄金傀儡被击破！(剩余 ${golems.size} 个)")
        }

        private fun reviveAll() {
            val center = instance.centerLocation
            val hpScale = getHpScale(instance)
            val atkScale = getAtkScale(instance)
            var revived = 0

            val toRevive = deadTicks.keys.toList()
            deadTicks.clear()

            for (uuid in toRevive) {
                repeat(2) { i ->
                    val loc = center.clone().add(
                        (Random.nextDouble() - 0.5) * 4 + i.toDouble(),
                        1.0,
                        (Random.nextDouble() - 0.5) * 4
                    )
                    val golem = plugin.mobManager.spawnMob(loc, "dungeon_white_tiger_golem") as? IronGolem ?: return@repeat
                    // 复活半血：主 0.5 倍，副 0.25 倍
                    val subMult = if (i == 0) 0.5 else 0.25
                    scaleMob(golem, hpScale, atkScale, hpMult = subMult, atkMult = subMult)

                    if (reflectActive) {
                        golem.setMetadata("pl_reflect", FixedMetadataValue(plugin, true))
                        golem.isGlowing = true
                    }

                    golems[golem] = golem.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 225.0
                    revived++
                }
            }

            instance.broadcast("§c时间已过！§6玄金傀儡§c以半血复活了 $revived 个！")
            instance.broadcastSound(Sound.ENTITY_IRON_GOLEM_REPAIR)
        }

        private fun win() {
            if (isFinished) return
            isFinished = true

            instance.broadcast("§f§l全部玄金傀儡已摧毁！§r")
            instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)

            // 清理
            for (golem in golems.keys) {
                golem.removeMetadata("pl_reflect", plugin)
                if (!golem.isDead) golem.remove()
            }
            golems.clear()
            deadTicks.clear()

            goToRewardPhase(instance)
        }

        override fun end() {
            for (golem in golems.keys) {
                golem.removeMetadata("pl_reflect", plugin)
                golem.remove()
            }
            golems.clear()
            deadTicks.clear()
        }

        // 从 YAML 基值 × 难度倍率缩放 (速度不缩放)
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
