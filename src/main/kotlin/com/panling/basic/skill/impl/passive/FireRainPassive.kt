package com.panling.basic.skill.impl.passive

import com.panling.basic.PanlingBasic
import org.bukkit.entity.Projectile
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import kotlin.random.Random

class FireRainPassive(plugin: PanlingBasic) : AbstractSkill("FIRE_RAIN", "焚天箭雨", PlayerClass.NONE), Listener {
    private val pl = plugin
    init { Bukkit.getPluginManager().registerEvents(this, plugin) }
    override fun onCast(ctx: SkillContext): Boolean = false

    @EventHandler
    fun onAttack(event: EntityDamageByEntityEvent) {
        val attacker = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player ?: return
            else -> return
        }
        val target = event.entity as? LivingEntity ?: return
        if (target is Player) return
        val passives = pl.playerDataManager.getCachedPassives(attacker, PlayerDataManager.PassiveTrigger.ATTACK)
        if (passives.find { it.id == id } == null) return
        if (!event.isCritical && Random.nextDouble() > 0.2) return

        val cdKey = NamespacedKey(pl, "set_cd_fire_rain")
        val now = System.currentTimeMillis()
        val last = attacker.persistentDataContainer.get(cdKey, PersistentDataType.LONG) ?: 0L
        if (now - last < 8000) return
        attacker.persistentDataContainer.set(cdKey, PersistentDataType.LONG, now)

        val center = target.location.clone()
        val phys = pl.statCalculator.getPlayerTotalStat(attacker, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        attacker.sendMessage("§e[焚天箭雨] §7天降火矢！")
        attacker.world.playSound(center, Sound.ITEM_CROSSBOW_SHOOT, 1f, 0.3f)

        object : BukkitRunnable() {
            var ticks = 0
            override fun run() {
                if (ticks >= 40) { cancel(); return }
                for (i in 0 until 8) {
                    val a = Random.nextDouble() * Math.PI * 2
                    val r = Random.nextDouble() * 4.0
                    center.world.spawnParticle(Particle.FLAME, center.x + Math.cos(a) * r, center.y + 5.0, center.z + Math.sin(a) * r, 1)
                }
                if (ticks % 10 == 0) {
                    for (e in center.world.getNearbyEntities(center, 4.0, 4.0, 4.0)) {
                        if (e is LivingEntity && e != attacker && e !is Player) {
                            e.damage(phys * 0.4, attacker)
                            e.fireTicks = (e.fireTicks + 40).coerceAtMost(100)
                        }
                    }
                }
                ticks++
            }
        }.runTaskTimer(pl, 0L, 1L)
    }
}
