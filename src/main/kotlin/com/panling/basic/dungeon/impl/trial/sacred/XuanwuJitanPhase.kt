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
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class XuanwuJitanPhase(
    plugin: PanlingBasic, instance: DungeonInstance,
    private val beast: String,
    private val onReturn: () -> Unit
) : AbstractDungeonPhase(plugin, instance) {

    private data class LineTurtle(val entity: LivingEntity, val a: Location, val b: Location, var toB: Boolean = true)
    private data class BouncyTurtle(val entity: LivingEntity, var vel: Vector)

    private val lineTurtles = mutableListOf<LineTurtle>()
    private var bouncy: BouncyTurtle? = null
    private val silverfish = mutableListOf<LivingEntity>()
    private val spawnedMobs = mutableListOf<LivingEntity>()
    private var isFinished = false; private var bossActive = false
 private val loadedChunks = mutableSetOf<Chunk>()
    private lateinit var jitanCenter: Location; private lateinit var center: Location
    private var boss: Ravager? = null
    private var nextVortexTick = 0L; private var nextBreathTick = 0L
    private var nextWaterTick = 0L

    override fun getReviveLocation() = jitanCenter

    private val linePairs = listOf(
        (Location(null, 135.5, 102.0, -108.2) to Location(null, 135.5, 102.0, -92.2)),
        (Location(null, 125.9, 100.5, -109.5) to Location(null, 125.5, 100.0, -91.7)),
        (Location(null, 116.1, 99.0, -92.2) to Location(null, 116.5, 99.0, -110.3)),
        (Location(null, 106.3, 98.0, -84.3) to Location(null, 105.6, 98.0, -116.6)),
        (Location(null, 133.3, 102.0, -92.1) to Location(null, 138.7, 102.0, -91.8)),
        (Location(null, 133.3, 102.0, -108.8) to Location(null, 138.7, 102.0, -108.7))
    )
    private val centerLoc = Location(null, 149.7, 102.0, -100.5)

    override fun start() {
        val z = SacredMountainTrialLogic.JITAN_Z[beast] ?: 0
        jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, z.toDouble())
        center = toWorld(centerLoc)
        instance.broadcast("§3玄武§e的幻境已然展开 —— 穿越玄龟之阵，抵达中心！")
        instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)
        for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(jitanCenter.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))

        // 水怪刷新
        val xuanwuPoints = listOf(
            Location(null, 37.5, 67.0, -7.3), Location(null, 33.2, 57.6, -34.6),
            Location(null, 48.6, 73.3, -63.1), Location(null, 64.7, 68.1, -43.2),
            Location(null, 79.5, 76.0, -23.2)
        )
        val scale = 1.0 + (instance.players.size - 1) * 0.5
        for (loc in xuanwuPoints) {
            val pointLoc = toWorld(loc)
            val count = 5 + Random.nextInt(5)
            for (j in 0 until count) {
                val off = Location(null, (Random.nextDouble() - 0.5) * 12, 0.0, (Random.nextDouble() - 0.5) * 12)
                val spawnLoc = pointLoc.clone().add(off.x, off.y, off.z)
                val m = plugin.mobManager.spawnMob(spawnLoc, "dungeon_sacred_xuanwu_add") ?: continue
                m.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= scale; m.health = attr.baseValue }
                m.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= 1.0 + (instance.players.size - 1) * 0.1 }
                spawnedMobs.add(m)
            }
        }

        // 线龟（可击杀）
        for ((a, b) in linePairs) {
            val wa = toWorld(a); val wb = toWorld(b); val t = spawnTurtle(wa); lineTurtles.add(LineTurtle(t, wa, wb))
        }
        instance.setPhaseTitle("§3穿越玄龟之阵 §7抵达中心")

        // 弹球龟
        val bt = spawnTurtle(center); bouncy = BouncyTurtle(bt, Vector(Random.nextDouble() - 0.5, 0.0, Random.nextDouble() - 0.5).normalize().multiply(1.2))

        // 幽水幼蛇
        val count = instance.players.size * 2
        for (i in 0 until count) {
            val angle = Random.nextDouble() * Math.PI * 2; val dist = Random.nextDouble() * 10
            val loc = center.clone().add(cos(angle) * dist, 0.0, sin(angle) * dist)
            val m = plugin.mobManager.spawnMob(loc, "dungeon_sacred_xuanwu_add") ?: continue
            m.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= scale; m.health = attr.baseValue }
            m.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= 1.0 + (instance.players.size - 1) * 0.1 }
            silverfish.add(m)
        }

        nextWaterTick = instance.tickCount + Random.nextLong(60, 120)

        // 强制加载幻境区块，防止玩家复活时因距离过远导致区块卸载怪物消失
        val cx = jitanCenter.blockX + centerLoc.blockX
        val cz = jitanCenter.blockZ + centerLoc.blockZ
        val w = instance.world
        for (dx in -8..8) for (dz in -8..8) {
            val chunk = w.getChunkAt((cx shr 4) + dx, (cz shr 4) + dz)
            chunk.addPluginChunkTicket(plugin)
            loadedChunks.add(chunk)
        }
    }

    private fun toWorld(loc: Location) = Location(instance.world, jitanCenter.x + loc.x, loc.y, jitanCenter.z + loc.z)

    private fun spawnTurtle(loc: Location): LivingEntity {
        val t = instance.world.spawnEntity(loc, EntityType.TURTLE) as Turtle
        t.customName(Component.text("§3玄龟")); t.isCustomNameVisible = true; t.setAI(false); t.isSilent = true
        t.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; t.health = 999999.0
        try { t.getAttribute(Attribute.SCALE)?.baseValue = 2.0 } catch (_: Exception) {}
        t.lootTable = null; t.isPersistent = true; t.removeWhenFarAway = false
        return t
    }

    override fun onTick() {
        if (isFinished) return

        val hitSet = mutableSetOf<UUID>()
        val knockStrength = 4.0; val hitDamage = 20.0

        // 线龟（不可击杀，纯障碍）
        for (lt in lineTurtles) {
            if (!lt.entity.isValid) continue
            val target = if (lt.toB) lt.b else lt.a
            val dir = target.toVector().subtract(lt.entity.location.toVector())
            if (dir.lengthSquared() < 1.0) { lt.toB = !lt.toB; continue }
            lt.entity.teleport(lt.entity.location.add(dir.normalize().multiply(0.72)))
            for (uuid in instance.players) {
                if (uuid in hitSet) continue
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distanceSquared(lt.entity.location) < 4.0) {
                    hitSet.add(uuid); p.damage(hitDamage)
                    val away = p.location.toVector().subtract(center.toVector()).normalize()
                    p.velocity = away.multiply(knockStrength).setY(0.3)
                }
            }
        }

        // 弹球龟（不可击杀，纯障碍）
        val bt = bouncy
        if (bt != null && bt.entity.isValid) {
            val next = bt.entity.location.clone().add(bt.vel)
            if (next.block.type.isSolid || next.clone().add(0.0, 1.0, 0.0).block.type.isSolid) bt.vel = bt.vel.multiply(-1.0)
            bt.entity.teleport(bt.entity.location.add(bt.vel))
            for (uuid in instance.players) {
                if (uuid in hitSet) continue
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distanceSquared(bt.entity.location) < 4.0) {
                    hitSet.add(uuid); p.damage(hitDamage)
                    val away = p.location.toVector().subtract(center.toVector()).normalize()
                    p.velocity = away.multiply(knockStrength).setY(0.3)
                }
            }
        }

        // 水花减速
        if (instance.tickCount >= nextWaterTick) {
            val wl = center.clone().add((Random.nextDouble() - 0.5) * 16, 0.0, (Random.nextDouble() - 0.5) * 16)
            for (i in 0 until 12) {
                val angle = 2.0 * Math.PI * i / 12.0
                center.world.spawnParticle(Particle.BUBBLE_POP, wl.x + cos(angle) * 2.5, wl.y + 0.1, wl.z + sin(angle) * 2.5, 1, 0.0, 0.0, 0.0, 0.02)
            }
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distance(wl) <= 2.5) p.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 40, 1, false, false))
            }
            nextWaterTick = instance.tickCount + Random.nextLong(80, 160)
        }

        // Boss 阶段
        if (!bossActive) {
            var atCenter = 0
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distanceSquared(center) < 9.0) atCenter++
            }
            if (atCenter > 0) {
                bossActive = true
                instance.broadcast("§3§l玄武幻影从深渊中浮现！")
                instance.broadcastSound(Sound.ENTITY_ELDER_GUARDIAN_CURSE)
                val b = plugin.mobManager.spawnMob(center.clone().add(0.0, 1.0, 0.0), "dungeon_sacred_xuanwu_boss") as? Ravager ?: return
                val scale = 1.0 + (instance.players.size - 1) * 0.5
                b.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= scale; b.health = attr.baseValue }
                b.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= 1.0 + (instance.players.size - 1) * 0.1 }
                b.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, Int.MAX_VALUE, 3, false, false))
                boss = b
                instance.glowEntity(b)
                instance.setPhaseTitle("§3玄武幻影 §f100%")
                nextVortexTick = instance.tickCount + 100L; nextBreathTick = instance.tickCount + 200L
            }
        } else {
            val b = boss ?: return
            if (!b.isValid || b.isDead) { win(); return }

            val pct = (b.health / b.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue * 100).toInt()
            instance.setPhaseTitle("§3玄武幻影 §f${pct}%")

            // 漩涡
            if (instance.tickCount >= nextVortexTick) {
                instance.broadcast("§3玄武幻影释放了漩涡！")
                for (uuid in instance.players) {
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (p.location.distance(b.location) <= 10.0) {
                        p.velocity = b.location.toVector().subtract(p.location.toVector()).normalize().multiply(2.0).setY(0.3)
                        p.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 30, 1, false, false))
                    }
                }
                nextVortexTick = instance.tickCount + Random.nextLong(160, 220)
            }
            // 冰霜喷吐
            if (instance.tickCount >= nextBreathTick) {
                instance.broadcast("§b玄武幻影喷出了冰霜吐息！")
                val dir = b.location.direction
                for (uuid in instance.players) {
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    val toPlayer = p.location.toVector().subtract(b.location.toVector())
                    if (toPlayer.length() <= 8.0 && dir.angle(toPlayer) < Math.toRadians(30.0)) {
                        p.damage(30.0); p.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 60, 2, false, false))
                    }
                }
                nextBreathTick = instance.tickCount + Random.nextLong(240, 320)
            }
        }
    }

    private fun win() {
        isFinished = true
        instance.broadcast("§3§l玄武幻影已被击败！玄武幻境破解！")
        instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
        val mc = instance.centerLocation.clone()
        for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(mc.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { onReturn() }, 40L)
    }

    override fun end() {
        loadedChunks.forEach { it.removePluginChunkTicket(plugin) }; loadedChunks.clear()
        lineTurtles.forEach { if (it.entity.isValid) it.entity.remove() }; lineTurtles.clear()
        bouncy?.let { if (it.entity.isValid) it.entity.remove() }; bouncy = null
        silverfish.forEach { if (it.isValid) it.remove() }; silverfish.clear()
        spawnedMobs.forEach { if (it.isValid) it.remove() }; spawnedMobs.clear()
        boss?.let { if (it.isValid) it.remove() }
    }
}