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
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Blaze
import org.bukkit.entity.EntityType
import org.bukkit.event.entity.EntityDamageByEntityEvent
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

    private fun getHpMult(player: Player): Double = when (plugin.playerDataManager.getTrialsCompleted(player)) {
        0 -> 1.0; 1 -> 1.5; 2 -> 2.2; 3 -> 3.0; else -> 1.0
    }
    private fun getAtkMult(player: Player): Double = when (plugin.playerDataManager.getTrialsCompleted(player)) {
        0 -> 1.0; 1 -> 1.3; 2 -> 1.7; 3 -> 2.2; else -> 1.0
    }

    private fun scaleMob(entity: LivingEntity, hpMult: Double, atkMult: Double) {
        entity.getAttribute(Attribute.MAX_HEALTH)?.let { attr ->
            attr.baseValue = attr.baseValue * hpMult
            entity.health = attr.baseValue
        }
        entity.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr ->
            attr.baseValue = attr.baseValue * atkMult
        }
    }

    // ==========================================
    // 动态难度：1人=1.0x, 2人=1.5x, 3人=2.0x
    // ==========================================
    private fun getHpScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.5
    private fun getAtkScale(instance: DungeonInstance): Double = 1.0 + (instance.players.size - 1) * 0.1

    // ==========================================
    // 奖励
    // ==========================================
    override fun createRewardConfig(instance: DungeonInstance): RewardConfig {
        return RewardConfig(
            title = "§c[朱雀试炼] 涅槃成功！",
            autoReward = true,
            autoRewardDelay = 100L,
            money = 150.0
        )
    }

    //
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
                val skelly = plugin.mobManager.spawnMob(loc, "dungeon_zhuque_priest") as? Skeleton ?: return@forEach
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

        private fun scaleMobWithDifficulty(entity: LivingEntity, player: Player) {
            val hpMult = getHpMult(player)
            val atkMult = getAtkMult(player)
            entity.getAttribute(Attribute.MAX_HEALTH)?.let { attr ->
                attr.baseValue = attr.baseValue * hpMult
                entity.health = attr.baseValue
            }
            entity.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr ->
                attr.baseValue = attr.baseValue * atkMult
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

        // 小怪上限
        private val spawnedAdds = mutableListOf<LivingEntity>()
        private fun getAddCap(): Int = when (getTrialsDone()) { 0 -> 5; 1 -> 7; 2 -> 9; 3 -> 12; else -> 5 }
        private fun getTrialsDone(): Int {
            val p = instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull() ?: return 0
            return plugin.playerDataManager.getTrialsCompleted(p)
        }

        // Boss技能
        private var nextStormTick = 0L
        private var minionsSpawned = false
        private var rebirthUsed = false

        override fun start() {
            hpScale = getHpScale(instance)
            atkScale = getAtkScale(instance)
            val count = instance.players.size

            instance.broadcast("§c§l朱雀之影 §r§c降临！当前挑战人数: ${count}人")
            originalBossHp = 250.0 * hpScale
            spawnBossBlaze(originalBossHp)

            nextZombieSpawnTick = instance.tickCount + zombieSpawnInterval
            nextStormTick = instance.tickCount + Random.nextLong(160, 240) // 8-12s首次风暴
        }

        // === Boss 生成 ===

        private fun spawnBossBlaze(hp: Double) {
            val center = instance.centerLocation.clone().add(0.0, 1.0, 0.0)
            val blaze = plugin.mobManager.spawnMob(center, "dungeon_zhuque_boss") as? Blaze ?: return
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

            val blaze = plugin.mobManager.spawnMob(center, "dungeon_zhuque_ember") as? Blaze ?: return
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

            // 清理死怪 + 检查上限
            spawnedAdds.removeAll { !it.isValid || it.isDead }
            if (spawnedAdds.size >= getAddCap()) return

            val target = bossBlaze?.location ?: smallBlaze?.location ?: return
            val count = minOf((1..2).random(), getAddCap() - spawnedAdds.size)

            repeat(count) {
                val offsetX = (Random.nextDouble() - 0.5) * 8
                val offsetZ = (Random.nextDouble() - 0.5) * 8
                val spawnLoc = target.clone().add(offsetX, 0.0, offsetZ)

                val zombie = plugin.mobManager.spawnMob(spawnLoc, "dungeon_zhuque_scorpion") as? Zombie ?: return@repeat
                scaleMobWithDifficulty(zombie, instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull() ?: return@repeat)
                spawnedAdds.add(zombie)
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

            val td = getTrialsDone()

            // === 烈焰风暴 (T2+) ===
            if (bossBlaze != null && now >= nextStormTick) {
                fireStorm(bossBlaze!!)
                nextStormTick = now + Random.nextLong(200, 280) // 10-14s
            }

            // === 火焰分身 (T3+, HP<50%, 一次) ===
            if (td >= 1 && !minionsSpawned && bossBlaze != null) {
                val blaze = bossBlaze!!
                val maxHp = blaze.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 320.0
                if (blaze.health / maxHp < 0.5) {
                    spawnMinions(blaze.location)
                    minionsSpawned = true
                }
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
                val td = getTrialsDone()
                val timeout = if (td >= 3 && !rebirthUsed) 160L else 200L // T5: 8s
                if (elapsed >= timeout) {
                    // 超时 → 剩余血量复活 Boss
                    val remainingHp = blaze.health.coerceAtLeast(1.0)
                    val mult = if (td >= 3 && !rebirthUsed) { rebirthUsed = true; 1.3 } else 1.0
                    val revivedHp = remainingHp * mult
                    instance.broadcast("§c火种未灭！朱雀之影从余烬中重生... (HP: ${revivedHp.toInt()})")
                    blaze.remove()
                    smallBlaze = null
                    spawnBossBlaze(revivedHp)
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

        private fun fireStorm(boss: Blaze) {
            instance.broadcast("§c朱雀之影正在积蓄烈焰...")
            instance.broadcastSound(Sound.ENTITY_BLAZE_SHOOT)

            // 3s 前摇：火焰粒子环扩散
            val preTicks = 60L
            val center = boss.location.clone()
            for (i in 0 until preTicks.toInt()) {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    if (!boss.isValid || boss.isDead) return@Runnable
                    val radius = 2.0 + (i.toDouble() / preTicks) * 6.0
                    for (j in 0 until 24) {
                        val angle = 2.0 * Math.PI * j / 24
                        val x = center.x + Math.cos(angle) * radius
                        val z = center.z + Math.sin(angle) * radius
                        boss.world.spawnParticle(Particle.FLAME, x, center.y + 0.5, z, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                }, i.toLong())
            }

            // 伤害
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!boss.isValid || boss.isDead) return@Runnable
                for (uuid in instance.players) {
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (p.location.distance(boss.location) <= 6.0) {
                        p.damage(40.0)
                        p.setFireTicks(40) // 2s burn
                        p.sendMessage("§c你被烈焰风暴吞没了！")
                    }
                }
                boss.world.spawnParticle(Particle.LAVA, boss.location, 20, 3.0, 0.5, 3.0, 0.0)
            }, preTicks)
        }

        private fun spawnMinions(loc: Location) {
            instance.broadcast("§c朱雀之影分裂出了火焰分身！")
            instance.broadcastSound(Sound.ENTITY_BLAZE_HURT)
            repeat(2) {
                val offset = Location(loc.world, loc.x + (Random.nextDouble() - 0.5) * 4, loc.y, loc.z + (Random.nextDouble() - 0.5) * 4)
                val minion = loc.world.spawnEntity(offset, EntityType.BLAZE) as Blaze
                minion.customName(Component.text("§6火焰分身"))
                minion.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 80.0
                minion.health = 80.0
                minion.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 10.0
                minion.lootTable = null
                val p = instance.players.mapNotNull { Bukkit.getPlayer(it) }.firstOrNull()
                if (p != null) minion.target = p
                spawnedAdds.add(minion)
            }
        }

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

        private fun scaleMobWithDifficulty(entity: LivingEntity, player: Player) {
            val hpMult = getHpMult(player)
            val atkMult = getAtkMult(player)
            entity.getAttribute(Attribute.MAX_HEALTH)?.let { attr ->
                attr.baseValue = attr.baseValue * hpMult
                entity.health = attr.baseValue
            }
            entity.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr ->
                attr.baseValue = attr.baseValue * atkMult
            }
        }
    }
}