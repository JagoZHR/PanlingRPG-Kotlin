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

    private fun getHpMult(player: Player): Double = when (plugin.playerDataManager.getTrialsCompleted(player)) {
        0 -> 1.0; 1 -> 1.5; 2 -> 2.2; 3 -> 3.0; else -> 1.0
    }
    private fun getAtkMult(player: Player): Double = when (plugin.playerDataManager.getTrialsCompleted(player)) {
        0 -> 1.0; 1 -> 1.3; 2 -> 1.7; 3 -> 2.2; else -> 1.0
    }

    // ==========================================
    // 动态难度
    // ==========================================
    private fun getHpScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.5
    private fun getAtkScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.1

    // ==========================================
    // 奖励
    // ==========================================
    override fun createRewardConfig(instance: DungeonInstance): RewardConfig {
        return RewardConfig(
            title = "§f[白虎试炼] 金锋试炼通过！",
            autoReward = true,
            autoRewardDelay = 100L,
            money = 200.0
        )
    }

    //
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
        private val swordDamageBase = 10.0

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
        private var syncWindow = 60L        // 3 秒同步窗口

        // 傀儡数量上限
        private fun getGolemCap(): Int = when (getTrialsDone()) { 0 -> 6; 1 -> 8; 2 -> 10; 3 -> 12; else -> 6 }
        private fun getTrialsDone(): Int {
            val p = instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull() ?: return 0
            return plugin.playerDataManager.getTrialsCompleted(p)
        }

        // Boss技能
        private var nextCrystalTick = 0L
        private var splitUsed = false

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
                val golem1 = plugin.mobManager.spawnMob(loc1, "dungeon_baihu_golem") as? IronGolem ?: continue
                scaleMob(golem1, hpScale, atkScale)
                golems[golem1] = golem1.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 450.0

                // 副傀儡 (半血半攻)
                val loc2 = center.clone().add(offset).add(1.5, 1.0, 0.0)
                val golem2 = plugin.mobManager.spawnMob(loc2, "dungeon_baihu_golem") as? IronGolem ?: continue
                scaleMob(golem2, 0.5, 0.5)
                golems[golem2] = golem2.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 225.0
            }

            nextReflectTick = instance.tickCount + reflectInterval
            nextCrystalTick = instance.tickCount + Random.nextLong(200, 300) // 10-15s首次晶刺
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

            // === 晶刺爆发 (T4+) ===
            val td = getTrialsDone()
            if (td >= 2 && now >= nextCrystalTick) {
                val livingGolems = golems.keys.filter { it.isValid && !it.isDead }
                if (livingGolems.isNotEmpty()) {
                    crystalBurst(livingGolems.random())
                }
                nextCrystalTick = now + Random.nextLong(240, 320) // 12-16s
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
        // === 分裂 (T5+) ===
        if (getTrialsDone() >= 3 && !splitUsed) {
            splitUsed = true
            instance.broadcast("§6玄金傀儡分裂了！")
            repeat(2) {
                val sloc = entity.location.clone().add((Random.nextDouble() - 0.5) * 3, 0.0, (Random.nextDouble() - 0.5) * 3)
                val child = plugin.mobManager.spawnMob(sloc, "dungeon_baihu_golem") as? IronGolem ?: return@repeat
                scaleMob(child, 0.5, 0.5)
                if (reflectActive) { child.setMetadata("pl_reflect", FixedMetadataValue(plugin, true)); child.isGlowing = true }
                golems[child] = child.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 225.0
            }
        }
        }

        // --- 晶刺爆发 ---
        private fun crystalBurst(golem: IronGolem) {
            instance.broadcast("§e玄金傀儡释放了晶刺！")
            instance.broadcastSound(Sound.BLOCK_GLASS_BREAK)
            val loc = golem.location.clone()
            val directions = listOf(0.0, Math.PI * 2 / 3, Math.PI * 4 / 3)
            // 粒子前摇
            for (d in directions) for (r in 1..8) {
                val x = loc.x + Math.cos(d) * r
                val z = loc.z + Math.sin(d) * r
                loc.world.spawnParticle(Particle.ENCHANT, x, loc.y + 0.3, z, 3, 0.1, 0.1, 0.1, 0.0)
            }
            // 延迟伤害
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!golem.isValid || golem.isDead) return@Runnable
                for (d in directions) for (r in 1..8) {
                    val x = loc.x + Math.cos(d) * r
                    val z = loc.z + Math.sin(d) * r
                    loc.world.spawnParticle(Particle.CRIT, x, loc.y + 0.3, z, 6, 0.0, 0.0, 0.0, 0.5)
                }
                for (uuid in instance.players) {
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (p.location.distance(loc) <= 8.0) {
                        // Check if player is in any of the three lines
                        val dx = p.location.x - loc.x; val dz = p.location.z - loc.z
                        val angle = Math.atan2(dz, dx)
                        val inLine = directions.any {
                            val diff = Math.abs(angle - it) % (Math.PI * 2)
                            diff < 0.5 || diff > Math.PI * 2 - 0.5
                        }
                        if (inLine) {
                            p.damage(35.0)
                            p.sendMessage("§6你被晶刺击中了！")
                        }
                    }
                }
            }, 40L) // 2s delay
        }

        private fun reviveAll() {
            val center = instance.centerLocation
            val hpScale = getHpScale(instance)
            val atkScale = getAtkScale(instance)
            var revived = 0

            // 清理死傀儡
            golems.keys.removeIf { !it.isValid || it.isDead }
            val cap = getGolemCap()
            if (golems.size >= cap) return

            val toRevive = deadTicks.keys.toList()
            deadTicks.clear()
            val canRevive = minOf(toRevive.size, (cap - golems.size + 1) / 2) // 每个死傀儡复活2个

            for (uuid in toRevive.take(canRevive)) {
                repeat(2) { i ->
                    val loc = center.clone().add(
                        (Random.nextDouble() - 0.5) * 4 + i.toDouble(),
                        1.0,
                        (Random.nextDouble() - 0.5) * 4
                    )
                    val golem = plugin.mobManager.spawnMob(loc, "dungeon_baihu_golem") as? IronGolem ?: return@repeat
                    // 复活半血：主 0.5 倍，副 0.25 倍
                    val subMult = if (i == 0) 0.5 else 0.25
                    scaleMob(golem, subMult, subMult)

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

        private fun scaleMob(entity: LivingEntity, hpMult: Double = 1.0, atkMult: Double = 1.0) {
            val player = instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull() ?: return
            val dynHp = getHpMult(player)
            val dynAtk = getAtkMult(player)
            entity.getAttribute(Attribute.MAX_HEALTH)?.let { attr ->
                attr.baseValue = attr.baseValue * dynHp * hpMult
                entity.health = attr.baseValue
            }
            entity.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr ->
                attr.baseValue = attr.baseValue * dynAtk * atkMult
            }
        }
    }
}