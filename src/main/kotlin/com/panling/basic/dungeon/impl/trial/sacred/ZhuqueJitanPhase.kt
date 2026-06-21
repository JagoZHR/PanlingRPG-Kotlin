package com.panling.basic.dungeon.impl.trial.sacred

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.impl.trial.SacredMountainTrialLogic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.random.Random

class ZhuqueJitanPhase(
    plugin: PanlingBasic, instance: DungeonInstance,
    private val beast: String,
    private val onReturn: () -> Unit
) : AbstractDungeonPhase(plugin, instance), Listener {

    private val archerLocs = listOf(
        Location(null, -12.5, 113.0, 3.7), Location(null, -28.5, 116.0, -4.5),
        Location(null, -48.5, 125.0, -17.6), Location(null, -44.5, 131.0, -39.3),
        Location(null, -56.5, 137.0, -44.6)
    )
    private val guardLocs = listOf(Location(null, 1.3, 149.0, -78.1), Location(null, 1.5, 149.0, -38.6))
    private val bigGuardLoc = Location(null, -8.6, 149.0, -56.5)
    private val sourceLoc = Location(null, 26.4, 153.0, -56.5)
    private val targetLocs = listOf(
        Location(null, 16.5, 190.9, -56.3), Location(null, 23.3, 178.1, -24.3), Location(null, 23.5, 178.9, -88.3)
    )

    private val guards = mutableListOf<LivingEntity>()
    private val archers = mutableListOf<LivingEntity>()
    private var bigGuard: LivingEntity? = null
    private var source: Blaze? = null
    private var ghast: Ghast? = null
    private var ghastTimer = 0; private var ghastHits = 0; private var ghastHitCooldown = 0
    private val loadedChunks = mutableSetOf<Chunk>()
    private var phase2 = false; private var isFinished = false
    private val hasTrident = mutableSetOf<UUID>()
    private lateinit var jitanCenter: Location
    private var spawnDelay = 40

    // 火环
    private var nextFireRingTick = 0L

    override fun getReviveLocation() = jitanCenter

    override fun start() {
        val z = SacredMountainTrialLogic.JITAN_Z[beast] ?: 0
        jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, z.toDouble())
        instance.broadcast("§c朱雀§e的幻境已然展开 —— 击败守卫，夺取三叉戟击落恶魂！")
        instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)
        for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(jitanCenter.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))

        val scale = 1.0 + (instance.players.size - 1) * 0.5
        // 弓箭手（保留不动）
        for (loc in archerLocs) {
            val sk = instance.world.spawnEntity(toWorld(loc), EntityType.SKELETON) as Skeleton
            sk.customName(Component.text("§c朱雀弓手")); sk.isCustomNameVisible = true
            sk.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; sk.health = 999999.0
            sk.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 20.0
            sk.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.0
            sk.lootTable = null; sk.isPersistent = true; sk.removeWhenFarAway = false
            sk.equipment?.let { it.helmetDropChance = 0f; it.chestplateDropChance = 0f; it.leggingsDropChance = 0f; it.bootsDropChance = 0f }
            archers.add(sk)
        }

        // 守卫
        for (loc in guardLocs) {
            val g = plugin.mobManager.spawnMob(toWorld(loc), "dungeon_sacred_zhuque_guard") ?: continue
            g.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= scale; g.health = attr.baseValue }
            g.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= 1.0 + (instance.players.size - 1) * 0.1 }
            guards.add(g)
        }
        // 大守卫
        val bg = plugin.mobManager.spawnMob(toWorld(bigGuardLoc), "dungeon_sacred_zhuque_captain") ?: return
        bg.getAttribute(Attribute.MAX_HEALTH)?.let { attr -> attr.baseValue *= scale; bg.health = attr.baseValue }
        bg.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr -> attr.baseValue *= 1.0 + (instance.players.size - 1) * 0.1 }
        bigGuard = bg
        instance.glowEntity(bg)

        instance.setPhaseTitle("§c击败守卫 §f0/3")

        nextFireRingTick = instance.tickCount + Random.nextLong(60, 100)

        // 强制加载幻境区块
        val w = instance.world
        for (dx in -5..5) for (dz in -5..5) {
            val chunk = w.getChunkAt(jitanCenter.chunk.x + dx, jitanCenter.chunk.z + dz)
            chunk.addPluginChunkTicket(plugin)
            loadedChunks.add(chunk)
        }

        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    private fun toWorld(loc: Location) = Location(instance.world, jitanCenter.x + loc.x, loc.y, jitanCenter.z + loc.z)

    override fun onTick() {
        if (isFinished) return

        // 坠落检测
        for (uuid in instance.players) {
            val p = Bukkit.getPlayer(uuid) ?: continue
            if (p.location.y < 90.0 && !p.isDead) {
                p.fallDistance = 0f; p.damage(50.0)
                p.teleport(jitanCenter.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))
                p.sendMessage("§c你坠入了熔岩深渊！")
            }
        }

        // 火环
        if (instance.tickCount >= nextFireRingTick) {
            spawnFireRing()
            nextFireRingTick = instance.tickCount + Random.nextLong(80, 160)
        }

        if (!phase2) {
            if (spawnDelay > 0) { spawnDelay--; return }
            val guardsAlive = guards.any { it.isValid && !it.isDead }
            val bigAlive = bigGuard != null && bigGuard!!.isValid && !bigGuard!!.isDead
            if (!guardsAlive && !bigAlive) {
                phase2 = true
                instance.setPhaseTitle("§e三叉戟击落恶魂 §f0/3")
                instance.broadcast("§c守卫已全部倒下！§e烈焰之源显现，夺取三叉戟击落恶魂！")
                instance.broadcastSound(Sound.ENTITY_WITHER_SPAWN)
                source = instance.world.spawnEntity(toWorld(sourceLoc), EntityType.BLAZE) as Blaze
                source!!.customName(Component.text("§c烈焰之源")); source!!.isCustomNameVisible = true
                source!!.setAI(false); source!!.isSilent = true
                source!!.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 1500000.0; source!!.health = 1500000.0
                source!!.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
                source!!.lootTable = null; source!!.isPersistent = true; source!!.removeWhenFarAway = false
                spawnGhast()
            }
        } else {
            ghastTimer--; if (ghastHitCooldown > 0) ghastHitCooldown--
            if (ghastTimer <= 0 && ghast != null && ghast!!.isValid) spawnGhast()
        }
    }

    private fun spawnFireRing() {
        // 在守卫固定刷新点附近生成火环
        val base = guardLocs.random()
        val worldLoc = toWorld(Location(null, base.x + (Random.nextDouble() - 0.5) * 6, base.y, base.z + (Random.nextDouble() - 0.5) * 6))
        for (i in 0 until 16) {
            val angle = 2.0 * Math.PI * i / 16.0
            worldLoc.world.spawnParticle(Particle.FLAME, worldLoc.x + kotlin.math.cos(angle) * 3, worldLoc.y, worldLoc.z + kotlin.math.sin(angle) * 3, 1, 0.0, 0.0, 0.0, 0.0)
        }
        // 4 秒后清除（伤害通过 tick 检测）
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distance(worldLoc) <= 3.0 && !p.isDead) {
                    p.damage(15.0); p.setFireTicks(20)
                }
            }
        }, 80L)
    }

    private fun spawnGhast() {
        ghast?.let { if (it.isValid) it.remove() }
        val target = targetLocs.random(); val wLoc = toWorld(target)
        if (ghast == null || !ghast!!.isValid) {
            ghast = instance.world.spawnEntity(wLoc, EntityType.GHAST) as Ghast
            ghast!!.customName(Component.text("§c朱雀幻影")); ghast!!.isCustomNameVisible = true
            ghast!!.setAI(false); ghast!!.isSilent = true
            ghast!!.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; ghast!!.health = 999999.0
            try { ghast!!.getAttribute(Attribute.SCALE)?.baseValue = 2.0 } catch (_: Exception) {}
            ghast!!.lootTable = null; ghast!!.isPersistent = true; ghast!!.removeWhenFarAway = false
        } else { ghast!!.teleport(wLoc) }
        ghastTimer = 100
    }

    @EventHandler override fun onDamage(event: EntityDamageByEntityEvent) {
        if (isFinished) return
        val damager = event.damager

        // 烈焰之源被攻击 → 给三叉戟（不限次数，阶段结束时清除）
        if (event.entity == source && damager is Player && instance.players.contains(damager.uniqueId)) {
            val trident = ItemStack(Material.TRIDENT)
                val meta = trident.itemMeta; meta.persistentDataContainer.set(NamespacedKey(plugin, "zhuque_trident"), PersistentDataType.BYTE, 1)
                meta.displayName(Component.text("§c朱雀之戟")); trident.itemMeta = meta
                damager.inventory.addItem(trident); hasTrident.add(damager.uniqueId)
                damager.sendMessage("§c你获得了 §e朱雀之戟§c！用它击落恶魂！")
        }

        // 三叉戟命中恶魂
        if (event.entity == ghast && ghastHitCooldown <= 0) {
            val shooter = when (damager) {
                is Player -> if (hasTrident.contains(damager.uniqueId)) damager else null
                is Trident -> (damager as Trident).shooter as? Player
                else -> null
            }
            if (shooter != null && hasTrident.contains(shooter.uniqueId)) {
                ghastHits++; ghastHitCooldown = 20
                instance.setPhaseTitle("§e三叉戟击落恶魂 §f$ghastHits/3")
                instance.broadcast("§e朱雀之戟命中恶魂！($ghastHits/3)")
                instance.broadcastSound(Sound.ENTITY_GHAST_HURT)
                // 恶魂反击
                val fireball = ghast!!.launchProjectile(org.bukkit.entity.Fireball::class.java) as Fireball
                fireball.direction = shooter.location.toVector().subtract(ghast!!.location.toVector()).normalize()
                if (ghastHits >= 3) { win(); return }
                spawnGhast()
            }
        }
    }

    private fun win() {
        isFinished = true
        instance.broadcast("§c§l恶魂已被击落！朱雀幻境破解！")
        instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
        for (uuid in instance.players) {
            val p = Bukkit.getPlayer(uuid) ?: continue
            p.inventory.forEachIndexed { i, item ->
                if (item != null && item.type == Material.TRIDENT && item.itemMeta.persistentDataContainer.has(NamespacedKey(plugin, "zhuque_trident"), PersistentDataType.BYTE))
                    p.inventory.setItem(i, null)
            }
        }
        val mc = instance.centerLocation.clone()
        for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(mc.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { onReturn() }, 40L)
    }

    override fun end() {
        loadedChunks.forEach { it.removePluginChunkTicket(plugin) }; loadedChunks.clear()
        HandlerList.unregisterAll(this)
        archers.forEach { if (it.isValid) it.remove() }; archers.clear()
        guards.forEach { if (it.isValid) it.remove() }; guards.clear()
        bigGuard?.let { if (it.isValid) it.remove() }
        source?.let { if (it.isValid) it.remove() }; ghast?.let { if (it.isValid) it.remove() }
        for (uuid in instance.players) {
            val p = Bukkit.getPlayer(uuid) ?: continue
            p.inventory.forEachIndexed { i, item ->
                if (item != null && item.type == Material.TRIDENT && item.itemMeta.persistentDataContainer.has(NamespacedKey(plugin, "zhuque_trident"), PersistentDataType.BYTE))
                    p.inventory.setItem(i, null)
            }
        }
    }
}