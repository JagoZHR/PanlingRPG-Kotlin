package com.panling.basic.dungeon.impl.trial.sacred

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.impl.trial.SacredMountainTrialLogic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import kotlin.random.Random

class BaihuJitanPhase(
    plugin: PanlingBasic, instance: DungeonInstance,
    private val beast: String,
    private val onReturn: () -> Unit
) : AbstractDungeonPhase(plugin, instance), Listener {

    private val tp1Loc = Location(null, -119.4, 111.0, 46.6)
    private val tp2Loc = Location(null, -91.7, 100.0, 41.9)
    private val finalLoc = Location(null, -91.5, 114.0, 133.5)
    private val guardLocs = listOf(
        Location(null, -82.1, 108.0, 68.2), Location(null, -82.2, 108.0, 81.7),
        Location(null, -83.2, 108.0, 99.9), Location(null, -99.8, 108.0, 99.5),
        Location(null, -99.6, 108.0, 83.9), Location(null, -100.2, 108.0, 69.3)
    )
    private val archerLocs = listOf(Location(null, -67.4, 113.0, 122.0), Location(null, -115.5, 113.0, 122.1))

    private val guards = mutableListOf<LivingEntity>()
    private var phase2 = false; private val phase2Triggered = mutableSetOf<Boolean>()
    private var isFinished = false
    private lateinit var jitanCenter: Location

    override fun getReviveLocation() = jitanCenter

    override fun start() {
        val z = SacredMountainTrialLogic.JITAN_Z[beast] ?: 0
        jitanCenter = instance.centerLocation.clone().add(0.0, 0.0, z.toDouble())
        instance.broadcast("§f白虎§e的幻境已然展开 —— 杀出一条血路，直奔终点！")
        instance.broadcastSound(Sound.ENTITY_ENDERMAN_TELEPORT)
        for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(jitanCenter.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))

        instance.setPhaseTitle("§f奔向传送阵")

        // 阶段二会在 onTick 中通过 tp1 接近检测触发（所有玩家传送到 tp2）
        phase2Triggered.add(false)

        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    private fun toWorld(loc: Location) = Location(instance.world, jitanCenter.x + loc.x, loc.y, jitanCenter.z + loc.z)

    @EventHandler override fun onDamage(event: EntityDamageByEntityEvent) {
        if (isFinished) return
        val damager = event.damager
        if (damager is Player && instance.players.contains(damager.uniqueId) && event.entity is Mob) {
            (event.entity as Mob).target = damager
        }
    }

    override fun onTick() {
        if (isFinished) return

        // 坠落检测
        for (uuid in instance.players) {
            val p = Bukkit.getPlayer(uuid) ?: continue
            if (p.location.y < 90.0 && !p.isDead) { p.fallDistance = 0f; p.damage(50.0); p.teleport(jitanCenter.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4)); p.sendMessage("§c你坠入了深渊！") }
        }

        if (!phase2) {
            val tp1 = toWorld(tp1Loc)
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distanceSquared(tp1) < 9.0) {
                    phase2 = true
                    instance.setPhaseTitle("§f绕过禁卫 §7抵达终点")
                    instance.broadcast("§f传送阵已激活！§e白虎禁卫苏醒了——走位绕过它们！")
                    instance.broadcastSound(Sound.ENTITY_ZOMBIE_AMBIENT)
                    val tp2 = toWorld(tp2Loc)
                    for (id in instance.players) Bukkit.getPlayer(id)?.teleport(tp2.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))

                    val scale = 1.0 + (instance.players.size - 1) * 0.5
                    // 白虎禁卫（不可击杀）
                    for (loc in guardLocs) {
                        val g = instance.world.spawnEntity(toWorld(loc), EntityType.ZOMBIE) as Zombie
                        g.customName(Component.text("§f白虎禁卫")); g.isCustomNameVisible = true; g.setBaby(false)
                        g.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; g.health = 999999.0
                        g.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 100.0
                        g.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.28
                        g.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
                        try { g.getAttribute(Attribute.SCALE)?.baseValue = 2.0 } catch (_: Exception) {}
                        g.equipment?.let { it.helmet = ItemStack(Material.LEATHER_HELMET); it.helmetDropChance = 0f; it.setItemInMainHand(ItemStack(Material.GOLDEN_SWORD)); it.itemInMainHandDropChance = 0f }
                        g.lootTable = null; g.isPersistent = true; g.removeWhenFarAway = false
                        val t = Bukkit.getPlayer(instance.players.random())
                        if (t != null) g.target = t
                        guards.add(g)
                    }
                    // 击退骷髅（不可击杀）
                    for (loc in archerLocs) {
                        val sk = instance.world.spawnEntity(toWorld(loc), EntityType.SKELETON) as Skeleton
                        sk.customName(Component.text("§f白虎弓手")); sk.isCustomNameVisible = true
                        sk.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 999999.0; sk.health = 999999.0
                        sk.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 5.0
                        sk.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.0
                        sk.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
                        val bow = ItemStack(Material.BOW); val bm = bow.itemMeta
                        try { bm.addEnchant(org.bukkit.enchantments.Enchantment.PUNCH, 5, true) } catch (_: Exception) {}
                        bow.itemMeta = bm; sk.equipment?.setItemInMainHand(bow)
                        sk.lootTable = null; sk.isPersistent = true; sk.removeWhenFarAway = false
                        sk.equipment?.let { it.helmet = ItemStack(Material.LEATHER_HELMET); it.helmetDropChance = 0f }
                        val t = Bukkit.getPlayer(instance.players.random())
                        if (t != null) sk.target = t
                    }
                    break
                }
            }
        }

        // 阶段二胜利：半数人到达终点
        if (phase2) {
            val final = toWorld(finalLoc)
            var atFinal = 0
            for (uuid in instance.players) {
                val p = Bukkit.getPlayer(uuid) ?: continue
                if (p.location.distanceSquared(final) < 9.0) atFinal++
            }
            val needed = (instance.players.size + 1) / 2
            if (atFinal >= needed) {
                isFinished = true
                instance.broadcast("§f§l半数勇士已抵达终点！白虎幻境破解！")
                instance.broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
                val mc = instance.centerLocation.clone()
                for (uuid in instance.players) Bukkit.getPlayer(uuid)?.teleport(mc.clone().add((Random.nextDouble() - 0.5) * 4, 2.0, (Random.nextDouble() - 0.5) * 4))
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { onReturn() }, 40L)
            }
        }
    }

    override fun end() {
        HandlerList.unregisterAll(this)
        guards.forEach { if (it.isValid) it.remove() }; guards.clear()
    }
}
