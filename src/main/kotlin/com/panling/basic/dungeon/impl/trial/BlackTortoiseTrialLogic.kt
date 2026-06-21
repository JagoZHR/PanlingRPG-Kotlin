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
import org.bukkit.event.entity.EntityDamageByEntityEvent
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

    private fun getHpMult(player: Player): Double = when (plugin.playerDataManager.getTrialsCompleted(player)) {
        0 -> 1.0; 1 -> 1.5; 2 -> 2.2; 3 -> 3.0; else -> 1.0
    }
    private fun getAtkMult(player: Player): Double = when (plugin.playerDataManager.getTrialsCompleted(player)) {
        0 -> 1.0; 1 -> 1.3; 2 -> 1.7; 3 -> 2.2; else -> 1.0
    }

    private fun getHpScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.5
    private fun getAtkScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.1

    // ==========================================
    // 奖励
    // ==========================================
    override fun createRewardConfig(instance: DungeonInstance): RewardConfig {
        return RewardConfig(
            title = "§3[玄武试炼] 冥渊试炼通过！",
            autoReward = true,
            autoRewardDelay = 100L,
            money = 200.0
        )
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

                    // 明显的水波粒子环 — 双层
                    val particleCount = (currentRadius * 4).toInt().coerceAtLeast(16)
                    for (i in 0 until particleCount) {
                        val angle = (i.toDouble() / particleCount) * 2 * Math.PI
                        val x = center.x + cos(angle) * currentRadius
                        val z = center.z + sin(angle) * currentRadius
                        // 主环：白色飞溅粒子
                        instance.world.spawnParticle(
                            Particle.SPLASH,
                            x, center.y + 0.3, z,
                            3, 0.3, 0.5, 0.3, 0.1
                        )
                        // 底层：气泡辅助
                        instance.world.spawnParticle(
                            Particle.BUBBLE_POP,
                            x, center.y + 0.1, z,
                            2, 0.2, 0.1, 0.2, 0.05
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

        // Boss技能
        private var bossRef: LivingEntity? = null
        private var nextVortexTick = 0L
        private var nextBreathTick = 0L
        private var shellReflectActive = false

        private fun getTrialsDone(): Int {
            val p = instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull() ?: return 0
            return plugin.playerDataManager.getTrialsCompleted(p)
        }

        override fun start() {
            val hpScale = getHpScale(instance)
            val atkScale = getAtkScale(instance)
            val count = instance.players.size
            val center = instance.centerLocation

            // 重甲冥龟（小型劫掠兽）
            val ravager = plugin.mobManager.spawnMob(center.clone().add(0.0, 1.0, 0.0), "dungeon_xuanwu_boss") as? Ravager ?: return
            ravager.isAware = true
            scaleMob(ravager, hpScale, atkScale)
            bossRef = ravager
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
                val sf1 = plugin.mobManager.spawnMob(loc1, "dungeon_xuanwu_snake") as? Silverfish ?: continue
                scaleMob(sf1, hpScale, atkScale)
                spawnMob(sf1)

                // 副蛇 (半血半攻)
                val loc2 = center.clone().add(offset).add(1.0, 1.0, 0.0)
                val sf2 = plugin.mobManager.spawnMob(loc2, "dungeon_xuanwu_snake") as? Silverfish ?: continue
                scaleMob(sf2, 0.5, 0.5)
                spawnMob(sf2)
            }

            instance.broadcast("§3重甲冥龟 §f与 §b${count * 2} 条幽水灵蛇 §f出现了！")
            nextVortexTick = instance.tickCount + Random.nextLong(140, 200) // 7-10s
            nextBreathTick = instance.tickCount + Random.nextLong(240, 320) // 12-16s
            instance.broadcastSound(Sound.ENTITY_RAVAGER_ROAR)
            nextVortexTick = instance.tickCount + Random.nextLong(140, 200)
            nextBreathTick = instance.tickCount + Random.nextLong(240, 320)
        }

        override fun onTick() {
            val b = bossRef ?: return
            if (!b.isValid || b.isDead) return
            val now = instance.tickCount
            val td = getTrialsDone()

            if (now >= nextVortexTick) { vortexPull(b); nextVortexTick = now + Random.nextLong(160, 220) }
            if (td >= 1 && now >= nextBreathTick) { frostBreath(b); nextBreathTick = now + Random.nextLong(240, 320) }
            shellReflectActive = td >= 3
        }

        private fun vortexPull(boss: LivingEntity) {
            instance.broadcast("§3重甲冥龟释放了漩涡吸力！")
            instance.broadcastSound(Sound.ENTITY_ELDER_GUARDIAN_CURSE)
            val loc = boss.location
            // 水泡粒子
            for (r in 1..10) for (j in 0 until 16) {
                val angle = 2.0 * Math.PI * j / 16.0
                val x = loc.x + Math.cos(angle) * r
                val z = loc.z + Math.sin(angle) * r
                boss.world.spawnParticle(Particle.BUBBLE_POP, x, loc.y + 0.3, z, 1, 0.0, 0.0, 0.0, 0.02)
            }
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distance(loc) <= 10.0) {
                    val dir = loc.toVector().subtract(p.location.toVector()).normalize().multiply(2.0)
                    p.velocity = dir.setY(0.3)
                    p.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, false))
                    p.sendMessage("§3你被漩涡吸入了！")
                }
            }
        }

        private fun frostBreath(boss: LivingEntity) {
            instance.broadcast("§b重甲冥龟喷出了冰霜吐息！")
            instance.broadcastSound(Sound.ENTITY_ELDER_GUARDIAN_HURT)
            val loc = boss.location
            val dir = boss.location.direction
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                val toPlayer = p.location.toVector().subtract(loc.toVector())
                if (toPlayer.length() <= 8.0 && dir.angle(toPlayer) < Math.toRadians(30.0)) {
                    p.damage(30.0)
                    p.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 2, false, false))
                    p.sendMessage("§b你被冰霜吐息冻结了！")
                }
            }
            // Snow particles
            for (i in 0 until 30) {
                val x = loc.x + dir.x * 3 + (Random.nextDouble() - 0.5) * 4
                val z = loc.z + dir.z * 3 + (Random.nextDouble() - 0.5) * 4
                loc.world.spawnParticle(Particle.SNOWFLAKE, x, loc.y + 1.5, z, 1, 0.5, 0.5, 0.5, 0.02)
            }
        }

        override fun onDamage(event: EntityDamageByEntityEvent) {
            if (shellReflectActive && event.entity == bossRef) {
                val damager = event.damager
                if (damager is org.bukkit.entity.Projectile) {
                    event.isCancelled = true
                    damager.velocity = damager.velocity.multiply(-1.5)
                    if (damager.shooter is Player) (damager.shooter as Player).sendMessage("§3龟壳反弹了你的弹射物！")
                }
            }
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