package com.panling.basic.dungeon.impl.trial.sacred

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.impl.trial.SacredMountainTrialLogic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ZhenpanguPhase(
    plugin: PanlingBasic, instance: DungeonInstance,
    private val onComplete: () -> Unit
) : AbstractDungeonPhase(plugin, instance), Listener {

    // 圣山专属怪物池（替换旧 regionMobPool）
    private val sacredMobPool = listOf(
        "dungeon_sacred_qinglong", "dungeon_sacred_qinglong_add",
        "dungeon_sacred_zhuque_guard", "dungeon_sacred_baihu_guard",
        "dungeon_sacred_xuanwu_add"
    )

    private val zhenpanPoints = listOf(
        Location(null, -0.9, 112.0, -78.9), Location(null, -48.7, 96.0, -142.8),
        Location(null, -81.4, 87.0, -156.4), Location(null, -103.6, 81.0, -121.8),
        Location(null, -119.5, 96.0, -26.5), Location(null, -87.1, 70.0, 78.4),
        Location(null, -51.6, 63.0, 98.6),  Location(null, 26.0, 60.0, 42.3)
    )
    private val zhenpanBoss1 = Location(null, -19.6, 109.0, -114.6)
    private val zhenpanBoss2 = Location(null, -113.6, 88.0, -80.1)
    private val zhenpanBoss3 = Location(null, -108.4, 88.0, 19.2)
    private val zhenpanBoss4 = Location(null, -6.6, 57.0, 63.4)
    private val zhenpanEnter = Location(null, 22.8, 45.0, -1.3)
    private val zhenpanBigBoss = Location(null, 56.1, 48.0, -58.9)

    private val qinglongElites = mutableListOf<LivingEntity>()
    private val zhuqueElites = mutableListOf<LivingEntity>()
    private var baihuElite: LivingEntity? = null
    private var xuanwuElite: LivingEntity? = null
    private val spawnedMobs = mutableListOf<LivingEntity>()
    private var bigBoss: Evoker? = null; private var bigBossActive = false
    private var skill1Cd = 0; private var skill2Cd = 0; private var skill3Cd = 0; private var skill4Cd = 0; private var skill4Unlocked = false
    private var spawnTimer = 0; private val loadedChunks = mutableSetOf<Chunk>()
 private var isFinished = false; private var allElitesDead = false
    private var spawnComplete = false; private var spawnDelay = 40
    private lateinit var jitanCenter: Location

    override fun getReviveLocation() = jitanCenter

    private fun toWorld(loc: Location) = Location(instance.world, jitanCenter.x + loc.x, loc.y, jitanCenter.z + loc.z)

    private fun drawRing(center: Location, range: Double, count: Int = 48) {
        val w = center.world ?: return
        for (i in 0 until count) {
            val a = 2.0 * Math.PI * i / count
            w.spawnParticle(Particle.ENCHANT, center.clone().add(cos(a) * range, 0.5, sin(a) * range), 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    override fun start() {
        jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, 5000.0)
        instance.setPhaseTitle("§5击败四方守护者")
        instance.broadcast("§5§l真·盘古§e的幻境已然展开 —— 击败四方守护者，直面盘古幻影！")
        instance.broadcastSound(Sound.ENTITY_ENDER_DRAGON_GROWL)
        for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(jitanCenter.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))

        val s = 1.0 + (instance.players.size - 1) * 0.5
        val atkS = 1.0 + (instance.players.size - 1) * 0.1

        // zhenpanpoint: 每点 2-3 精英 + 3-5 小怪
        for (pt in zhenpanPoints) {
            val wpt = toWorld(pt)
            repeat(2 + Random.nextInt(2)) {
                val off = Location(null, (Random.nextDouble() - 0.5) * 8, 0.0, (Random.nextDouble() - 0.5) * 8)
                val sl = wpt.clone().add(off.x, off.y, off.z)
                if (!SacredMountainTrialLogic.isSafeSpawn(sl)) return@repeat
                val m = plugin.mobManager.spawnMob(sl, sacredMobPool.random()) ?: return@repeat
                m.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= s; m.health = attr.baseValue }
                m.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= atkS }
                spawnedMobs.add(m)
            }
            repeat(3 + Random.nextInt(3)) {
                val off = Location(null, (Random.nextDouble() - 0.5) * 8, 0.0, (Random.nextDouble() - 0.5) * 8)
                val sl = wpt.clone().add(off.x, off.y, off.z)
                if (!SacredMountainTrialLogic.isSafeSpawn(sl)) return@repeat
                val m = plugin.mobManager.spawnMob(sl, sacredMobPool.random()) ?: return@repeat
                m.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= s; m.health = attr.baseValue }
                m.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= atkS }
                spawnedMobs.add(m)
            }
        }

        // zhenpanboss1: 青龙精英 ×3
        repeat(3) {
            val m = plugin.mobManager.spawnMob(toWorld(zhenpanBoss1).clone().add((Random.nextDouble() - 0.5) * 3, 0.0, (Random.nextDouble() - 0.5) * 3), "dungeon_sacred_qinglong") ?: return@repeat
            m.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= s; m.health = attr.baseValue }
            m.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= atkS }
            if (m is Mob) { m.lootTable = null; m.isPersistent = true; m.removeWhenFarAway = false }
            val t = Bukkit.getPlayer(instance.players.random()); if (t != null && m is Mob) m.target = t
            qinglongElites.add(m)
        }

        // zhenpanboss2: 朱雀精英 ×3
        repeat(3) {
            val m = plugin.mobManager.spawnMob(toWorld(zhenpanBoss2).clone().add((Random.nextDouble() - 0.5) * 3, 0.0, (Random.nextDouble() - 0.5) * 3), "dungeon_sacred_zhuque_guard") ?: return@repeat
            m.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= s; m.health = attr.baseValue }
            m.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= atkS }
            if (m is Mob) { m.lootTable = null; m.isPersistent = true; m.removeWhenFarAway = false }
            val t = Bukkit.getPlayer(instance.players.random()); if (t != null && m is Mob) m.target = t
            zhuqueElites.add(m)
        }

        // zhenpanboss3: 白虎精英
        val bh = plugin.mobManager.spawnMob(toWorld(zhenpanBoss3), "dungeon_sacred_baihu_guard") ?: return
        bh.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= s; bh.health = attr.baseValue }
        bh.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= atkS }
        if (bh is Mob) { bh.lootTable = null; bh.isPersistent = true; bh.removeWhenFarAway = false }
        val t3 = Bukkit.getPlayer(instance.players.random()); if (t3 != null && bh is Mob) bh.target = t3
        baihuElite = bh

        // zhenpanboss4: 玄武精英
        val xw = plugin.mobManager.spawnMob(toWorld(zhenpanBoss4), "dungeon_sacred_xuanwu_boss") ?: return
        xw.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= s; xw.health = attr.baseValue }
        xw.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= atkS }
        if (xw is Mob) { xw.lootTable = null; xw.isPersistent = true; xw.removeWhenFarAway = false }
        val t4 = Bukkit.getPlayer(instance.players.random()); if (t4 != null && xw is Mob) xw.target = t4
        xuanwuElite = xw

        // 强制加载幻境区块
        val w = instance.world
        for (dx in -8..8) for (dz in -8..8) {
            val chunk = w.getChunkAt(jitanCenter.chunk.x + dx, jitanCenter.chunk.z + dz)
            chunk.addPluginChunkTicket(plugin)
            loadedChunks.add(chunk)
        }

        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler override fun onDamage(event: EntityDamageByEntityEvent) {
        if (isFinished) return
        val damager = event.damager
        if (damager is Player && instance.players.contains(damager.uniqueId) && event.entity is Mob) {
            (event.entity as Mob).target = damager
        }
    }

    override fun onTick() {
        if (isFinished) return

        if (!spawnComplete) { spawnDelay--; if (spawnDelay <= 0) spawnComplete = true }

        // 坠落检测
        for (uuid in instance.players) {
            val p = Bukkit.getPlayer(uuid) ?: continue
            if (p.location.y < 10.0 && !p.isDead) {
                p.fallDistance = 0f; p.damage(30.0)
                p.teleport(jitanCenter.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))
                p.sendMessage("§c你坠入了深渊！§7（提示：深渊不会击杀你，找到盘古幻影即可）")
            }
        }
        // 清理坠落怪物
        val allElites = qinglongElites + zhuqueElites + listOfNotNull(baihuElite, xuanwuElite)
        for (m in allElites) { if (m.isValid && !m.isDead && m.location.y < 10.0) m.remove() }
        if (bigBoss?.isValid == true && !bigBoss!!.isDead && bigBoss!!.location.y < 10.0) bigBoss!!.remove()
        spawnedMobs.removeAll { m -> if (!m.isValid || m.isDead) true else if (m.location.y < 10.0) { m.remove(); true } else false }

        // 检测精英全死
        if (spawnComplete && !allElitesDead) {
            val qlAlive = qinglongElites.any { it.isValid && !it.isDead }
            val zqAlive = zhuqueElites.any { it.isValid && !it.isDead }
            val bhAlive = baihuElite?.let { it.isValid && !it.isDead } == true
            val xwAlive = xuanwuElite?.let { it.isValid && !it.isDead } == true
            if (!qlAlive && !zqAlive && !bhAlive && !xwAlive) {
                allElitesDead = true
                instance.broadcast("§5§l四方守护者已全部倒下！前往盘古斧上直面盘古幻影！")
                instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)
            }
        }

        // 触发盘古
        if (allElitesDead && !bigBossActive) {
            val enter = toWorld(zhenpanEnter)
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distanceSquared(enter) < 25.0) {
                    bigBossActive = true
                    instance.broadcast("§5§l盘古幻影苏醒了！")
                    instance.broadcastSound(Sound.ENTITY_WITHER_SPAWN)
                    val bbLoc = toWorld(zhenpanBigBoss)
                    val ev = instance.world.spawnEntity(bbLoc, EntityType.EVOKER) as Evoker
                    ev.customName(Component.text("§5§l盘古幻影")); ev.isCustomNameVisible = true
                    val s = 1.0 + (instance.players.size - 1) * 0.5
                    ev.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 8000.0 * s; ev.health = 8000.0 * s
                    ev.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 60.0 * (1.0 + (instance.players.size - 1) * 0.1)
                    ev.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.0
                    ev.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
                    try { ev.getAttribute(Attribute.SCALE)?.baseValue = 2.5 } catch (_: Exception) {}
                    ev.lootTable = null; ev.isPersistent = true; ev.removeWhenFarAway = false
                    ev.equipment?.let { it.helmetDropChance = 0f; it.chestplateDropChance = 0f; it.leggingsDropChance = 0f; it.bootsDropChance = 0f }
                    bigBoss = ev
                    instance.glowEntity(ev)
                    instance.setPhaseTitle("§5盘古幻影 §f100%")
                    skill1Cd = 100; skill2Cd = 60; skill3Cd = 140; skill4Cd = 9999; skill4Unlocked = false
                    spawnTimer = 300
                    break
                }
            }
        }

        // 未清精英到enter → 传送回
        if (!allElitesDead && !bigBossActive) {
            val enter = toWorld(zhenpanEnter)
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distanceSquared(enter) < 25.0) {
                    p.teleport(jitanCenter.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))
                    p.sendMessage("§c四方守护者尚未全部击败！先击败它们再来。")
                }
            }
        }

        // 大Boss阶段
        if (bigBossActive && bigBoss != null) {
            val b = bigBoss!!
            val pct = (b.health / b.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue * 100).toInt()
            instance.setPhaseTitle("§5盘古幻影 §f${pct}%")
            if (!b.isValid || b.isDead) {
                isFinished = true
                instance.broadcast("§5§l盘古幻影已被击败！真·盘古的试炼终于完成！")
                instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
                for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(instance.centerLocation.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { onComplete() }, 60L)
                return
            }

            // 技能1: 开天辟地
            skill1Cd--
            if (skill1Cd <= 0) {
                skill1Cd = 440
                instance.broadcast("§5盘古幻影§e施展 §c开天辟地§e——退开！")
                val bc = b.location.clone(); drawRing(bc, 8.0)
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { drawRing(bc, 8.0) }, 20L)
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    drawRing(bc, 8.0)
                    bc.world.spawnParticle(Particle.EXPLOSION, bc.add(0.0, 1.0, 0.0), 20, 1.5, 2.0, 1.5, 0.0)
                    for (uuid in instance.players) {
                        val p = Bukkit.getPlayer(uuid) ?: continue
                        if (p.location.distanceSquared(bc) < 64.0) {
                            p.velocity = p.location.toVector().subtract(bc.toVector()).normalize().multiply(3.0).setY(0.5)
                            p.damage(15.0)
                        }
                    }
                }, 40L)
            }

            // 技能2: 混沌召唤
            skill2Cd--
            if (skill2Cd <= 0) {
                skill2Cd = 360
                instance.broadcast("§5盘古幻影§e施展 §a混沌召唤§e——仆从涌现！")
                repeat(3) {
                    val off = Location(null, (Random.nextDouble() - 0.5) * 16, 0.0, (Random.nextDouble() - 0.5) * 16)
                    val sl = b.location.clone().add(off.x, off.y, off.z)
                    val m = plugin.mobManager.spawnMob(sl, sacredMobPool.random()) ?: return@repeat
                    sl.world.spawnParticle(Particle.PORTAL, sl, 30, 1.0, 1.0, 1.0, 0.02)
                    spawnedMobs.add(m)
                }
            }

            // 技能3: 地脉冲击
            skill3Cd--
            if (skill3Cd <= 0) {
                skill3Cd = 500
                instance.broadcast("§5盘古幻影§e施展 §6地脉冲击§e——大地在拖动你！")
                val bc = b.location.clone(); drawRing(bc, 10.0, 64)
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { drawRing(bc, 10.0, 64) }, 30L)
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    drawRing(bc, 10.0, 64)
                    bc.world.spawnParticle(Particle.CLOUD, bc.add(0.0, 0.5, 0.0), 40, 2.0, 0.5, 2.0, 0.05)
                    for (uuid in instance.players) {
                        val p = Bukkit.getPlayer(uuid) ?: continue
                        val dist = p.location.distance(bc)
                        p.velocity = bc.toVector().subtract(p.location.toVector()).normalize().multiply(2.5).setY(0.3)
                        if (dist > 10.0) { p.damage((minOf(dist, 20.0) - 10.0) / 10.0 * 80.0) }
                        p.world.spawnParticle(Particle.CLOUD, p.location.add(0.0, 0.2, 0.0), 10, 0.5, 0.0, 0.5, 0.02)
                    }
                }, 60L)
            }

            // 技能4: 万钧之势
            if (b.health / b.getAttribute(Attribute.MAX_HEALTH)!!.baseValue < 0.5) {
                if (!skill4Unlocked) { skill4Unlocked = true; skill4Cd = 200; instance.broadcast("§5盘古幻影§c生命垂危——万钧之势即将降临！") }
                skill4Cd--
                if (skill4Cd <= 0) {
                    skill4Cd = 400
                    instance.broadcast("§5盘古幻影§e蓄力 §c万钧之势§e——快跑！")
                    val bc = b.location.clone(); drawRing(bc, 8.0)
                    for (uuid in instance.players) {
                        val p = Bukkit.getPlayer(uuid) ?: continue
                        p.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 2, false, false))
                    }
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable { drawRing(bc, 8.0) }, 20L)
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        drawRing(bc, 8.0)
                        b.world.spawnParticle(Particle.EXPLOSION, b.location.add(0.0, 1.0, 0.0), 15, 1.0, 1.0, 1.0, 0.0)
                        for (uuid in instance.players) {
                            val p = Bukkit.getPlayer(uuid) ?: continue
                            if (p.location.distanceSquared(b.location) < 64.0) {
                                p.damage(25.0)
                                p.velocity = p.location.toVector().subtract(b.location.toVector()).normalize().multiply(2.5).setY(0.6)
                            }
                        }
                    }, 40L)
                }
            }

            // 定时刷怪
            spawnTimer--
            if (spawnTimer <= 0) {
                spawnTimer = 300 + Random.nextInt(100)
                repeat(2 + Random.nextInt(3)) {
                    val angle = Random.nextDouble() * Math.PI * 2; val dist = 10.0 + Random.nextDouble() * 10.0
                    val sl = b.location.clone().add(cos(angle) * dist, 0.0, sin(angle) * dist)
                    val m = plugin.mobManager.spawnMob(sl, sacredMobPool.random()) ?: return@repeat
                    spawnedMobs.add(m)
                }
            }
        }
    }

    override fun end() {
        loadedChunks.forEach { it.removePluginChunkTicket(plugin) }; loadedChunks.clear()
        HandlerList.unregisterAll(this)
        qinglongElites.forEach { if (it.isValid) it.remove() }; qinglongElites.clear()
        zhuqueElites.forEach { if (it.isValid) it.remove() }; zhuqueElites.clear()
        baihuElite?.let { if (it.isValid) it.remove() }; baihuElite = null
        xuanwuElite?.let { if (it.isValid) it.remove() }; xuanwuElite = null
        bigBoss?.let { if (it.isValid) it.remove() }; bigBoss = null
        spawnedMobs.forEach { if (it.isValid) it.remove() }; spawnedMobs.clear()
    }
}