package com.panling.basic.dungeon.impl.trial

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.RewardConfig
import com.panling.basic.dungeon.StandardDungeonLogic
import com.panling.basic.dungeon.phase.AbstractCombatPhase
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
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.metadata.FixedMetadataValue
import kotlin.random.Random

class WoodTrialLogic(plugin: PanlingBasic) : StandardDungeonLogic(plugin) {

    override val templateId: String = "trial_wood"

    private fun scaleHp(player: Player, base: Double): Double = base * getHpMult(player)
    private fun scaleAtk(player: Player, base: Double): Double = base * getAtkMult(player)

    private fun getHpMult(player: Player): Double = when (plugin.playerDataManager.getTrialsCompleted(player)) {
        0 -> 1.0; 1 -> 1.5; 2 -> 2.2; 3 -> 3.0; else -> 1.0
    }

    private fun getAtkMult(player: Player): Double = when (plugin.playerDataManager.getTrialsCompleted(player)) {
        0 -> 1.0; 1 -> 1.3; 2 -> 1.7; 3 -> 2.2; else -> 1.0
    }

    // ==========================================
    // 奖励
    // ==========================================
    override fun createRewardConfig(instance: DungeonInstance): RewardConfig {
        return RewardConfig(
            title = "§a[青龙试炼] 试炼通过！",
            autoReward = true,
            autoRewardDelay = 100L,
            money = 100.0
        )
    }

    //
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

        // 小怪追踪与上限
        private val spawnedMobs = mutableListOf<LivingEntity>()
        private fun getMobCap(): Int = when (getTrialsDone()) { 0 -> 5; 1 -> 7; 2 -> 9; 3 -> 12; else -> 5 }
        private fun getTrialsDone(): Int {
            val p = instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull() ?: return 0
            return plugin.playerDataManager.getTrialsCompleted(p)
        }

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
            // 清理死怪 + 检查上限
            spawnedMobs.removeAll { !it.isValid || it.isDead }
            if (spawnedMobs.size >= getMobCap()) return

            val randomPlayer = instance.players.mapNotNull { Bukkit.getPlayer(it) }.randomOrNull() ?: return
            val player = randomPlayer
            val spawnLoc = randomPlayer.location.clone().add(
                (Random.nextDouble() - 0.5) * 10, 0.0, (Random.nextDouble() - 0.5) * 10
            )
            spawnLoc.y = instance.centerLocation.y + 1

            // 交替刷野猪和毒蛛
            val mobId = if (instance.tickCount / 100 % 2 == 0L) "dungeon_qinglong_boar" else "dungeon_qinglong_spider"
            val mob = plugin.mobManager.spawnMob(spawnLoc, mobId) as? LivingEntity ?: return

            // 动态缩放
            mob.getAttribute(Attribute.MAX_HEALTH)?.let { attr ->
                attr.baseValue = scaleHp(player, attr.baseValue)
                mob.health = attr.baseValue
            }
            mob.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr ->
                attr.baseValue = scaleAtk(player, attr.baseValue)
            }

            if (mob is Zombie) {
                mob.isAdult
                val stick = ItemStack(Material.STICK)
                val meta = stick.itemMeta
                meta.addEnchant(Enchantment.KNOCKBACK, 5, true)
                stick.itemMeta = meta
                mob.equipment.setItemInMainHand(stick)
                mob.equipment.itemInMainHandDropChance = 0f
                mob.target = randomPlayer
                spawnedMobs.add(mob)
            } else if (mob is Mob) {
                mob.target = randomPlayer
                spawnedMobs.add(mob)
            }
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

            // 清理场地小怪（保留 Boss 出生点干净）
            instance.centerLocation.getNearbyEntities(40.0, 20.0, 40.0).forEach { entity ->
                if (entity !is Player) entity.remove()
            }

            instance.broadcast("§a灵气充能完成！§e腐化树精§a正在苏醒...")
            instance.broadcastSound(Sound.ENTITY_ZOMBIE_AMBIENT)

            // 进入 Boss 阶段
            instance.nextPhase(BossPhase(instance))
        }

        override fun end() {
            instance.players.forEach { Bukkit.getPlayer(it)?.hideBossBar(bossBar) }
        }
    }

    // ==========================================
    // Boss 阶段：腐化树精
    // ==========================================
    inner class BossPhase(instance: DungeonInstance) : AbstractCombatPhase(plugin, instance) {

        private var boss: LivingEntity? = null
        private var nextRootTick = 0L
        private var nextThornTick = 0L
        private var thornEndTick = 0L
        private var healUsed = false

        private fun getTrialsDone(): Int {
            val p = instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull() ?: return 0
            return plugin.playerDataManager.getTrialsCompleted(p)
        }

        override fun start() {
            val center = instance.centerLocation.clone().add(0.0, 1.0, 0.0)
            val mob = plugin.mobManager.spawnMob(center, "dungeon_qinglong_treant") ?: return
            val player = instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull() ?: return
            mob.getAttribute(Attribute.MAX_HEALTH)?.let { attr ->
                attr.baseValue = scaleHp(player, attr.baseValue)
                mob.health = attr.baseValue
            }
            mob.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr ->
                attr.baseValue = scaleAtk(player, attr.baseValue)
            }
            spawnMob(mob)
            boss = mob
            nextRootTick = instance.tickCount + 100L  // 5s后首次缠绕
            nextThornTick = instance.tickCount + 200L  // 10s后荆棘光环（T3+）
            instance.broadcast("§2§l腐化树精 §r§a已现身！击败它完成试炼！")
            instance.broadcastSound(Sound.ENTITY_ZOMBIE_AMBIENT)
        }

        override fun onTick() {
            val b = boss ?: return
            if (!b.isValid || b.isDead) return

            val now = instance.tickCount
            val td = getTrialsDone()

            // === 根须缠绕 (T2+) ===
            if (now >= nextRootTick) {
                rootEntangle(b)
                nextRootTick = now + Random.nextLong(140, 200) // 7-10s
            }

            // === 荆棘光环 (T3+) ===
            if (td >= 1 && now >= nextThornTick) {
                if (thornEndTick == 0L || now >= thornEndTick) {
                    activateThorns(b)
                    thornEndTick = now + 80L  // 4s
                    nextThornTick = now + Random.nextLong(280, 340) // 14-17s
                }
            }

            // === 自然之愈 (T5+, HP<30%, 一次性) ===
            if (td >= 3 && !healUsed && b.health / b.getAttribute(Attribute.MAX_HEALTH)!!.baseValue < 0.3) {
                natureHeal(b)
                healUsed = true
            }

            // 荆棘光环粒子效果
            if (thornEndTick > 0L && now < thornEndTick) {
                b.world.spawnParticle(Particle.DAMAGE_INDICATOR,
                    b.location.add(0.0, 1.5, 0.0), 5, 0.4, 0.8, 0.4, 0.0)
            }
        }

        // --- 根须缠绕 ---
        private fun rootEntangle(boss: LivingEntity) {
            val player = instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull() ?: return
            if (player.location.distance(boss.location) > 8.0) return
            val loc = player.location.clone()

            // 前摇：ENCHANT 粒子环
            val preTicks = 50L // 2.5s
            instance.broadcast("§2腐化树精的根须正在蔓延...")
            instance.broadcastSound(Sound.BLOCK_ROOTS_BREAK)
            for (i in 0 until preTicks.toInt()) {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    if (!boss.isValid || boss.isDead) return@Runnable
                    for (j in 0 until 12) {
                        val angle = 2.0 * Math.PI * j / 12
                        val x = loc.x + Math.cos(angle) * 2.5
                        val z = loc.z + Math.sin(angle) * 2.5
                        boss.world.spawnParticle(Particle.ENCHANT, x, loc.y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                }, i.toLong())
            }

            // 拉人 + 定身
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!boss.isValid || boss.isDead) return@Runnable
                val p = instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull() ?: return@Runnable
                // 2.5s 前摇期间跑出 4 格外 = 躲开
                if (p.location.distance(loc) > 4.0) {
                    p.sendMessage("§a你躲开了根须缠绕！")
                    return@Runnable
                }
                p.teleport(boss.location.clone().add(0.0, 0.0, 0.5))
                p.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 8, false, false)) // 2s定身
                p.damage(8.0)
                p.sendMessage("§c你被树精的根须缠住了！")
                boss.world.spawnParticle(Particle.CRIT,
                    p.location.add(0.0, 0.5, 0.0), 20, 0.3, 0.5, 0.3, 0.0)
            }, preTicks)
        }

        // --- 荆棘光环 ---
        private fun activateThorns(boss: LivingEntity) {
            boss.setMetadata("pl_thorns", FixedMetadataValue(plugin, true))
            instance.broadcast("§c腐化树精释放了荆棘光环！停止攻击！")
            instance.broadcastSound(Sound.ENCHANT_THORNS_HIT)

            // 4s后清除
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                boss.removeMetadata("pl_thorns", plugin)
                instance.broadcast("§a荆棘光环已消散，可以攻击了！")
            }, 80L)
        }

        override fun onDamage(event: EntityDamageByEntityEvent) {
            if (event.entity.hasMetadata("pl_thorns")) {
                val attacker = event.damager
                if (attacker is LivingEntity) {
                    attacker.damage(event.damage * 0.15)
                    if (attacker is Player) attacker.sendMessage("§c荆棘光环反弹了你的攻击！")
                }
            }
        }

        // --- 自然之愈 ---
        private fun natureHeal(boss: LivingEntity) {
            val attr = boss.getAttribute(Attribute.MAX_HEALTH) ?: return
            val healAmount = attr.baseValue * 0.2
            boss.health = minOf(boss.health + healAmount, attr.baseValue)
            boss.removePotionEffect(PotionEffectType.SLOWNESS)
            instance.broadcast("§2§l腐化树精吸收了自然之力，恢复了生命！")
            instance.broadcastSound(Sound.ITEM_TOTEM_USE)
            boss.world.spawnParticle(Particle.HAPPY_VILLAGER, boss.location.add(0.0, 1.5, 0.0), 30, 0.8, 1.5, 0.8, 0.0)
        }

        override fun onWaveClear() {
            boss?.removeMetadata("pl_thorns", plugin)
            instance.broadcast("§a§l试炼通过！§r §e正在发放奖励...")
            instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
            goToRewardPhase(instance)
        }
    }
}