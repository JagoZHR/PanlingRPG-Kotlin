package com.panling.basic.skill.impl.passive

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class DragonRoarPassive(plugin: PanlingBasic) : AbstractSkill("DRAGON_ROAR", "龙威", PlayerClass.NONE), Listener {
    private val pl = plugin
    init { Bukkit.getPluginManager().registerEvents(this, plugin) }
    override fun onCast(ctx: SkillContext): Boolean = false

    @EventHandler
    fun onKill(event: EntityDeathEvent) {
        val killer = event.entity.killer as? Player ?: return
        val passives = pl.playerDataManager.getCachedPassives(killer, PlayerDataManager.PassiveTrigger.ATTACK)
        if (passives.find { it.id == id } == null) return

        val cdKey = NamespacedKey(pl, "set_cd_dragon_roar")
        val now = System.currentTimeMillis()
        val last = killer.persistentDataContainer.get(cdKey, PersistentDataType.LONG) ?: 0L
        if (now - last < 10000) return
        killer.persistentDataContainer.set(cdKey, PersistentDataType.LONG, now)

        killer.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 200, 2, false, true))
        val loc = killer.location
        for (entity in killer.world.getNearbyEntities(loc, 8.0, 8.0, 8.0)) {
            if (entity is Mob) entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, true))
        }
        killer.sendMessage("§e[龙威] §7苍鳞共鸣！周围敌人陷入迟缓")
        killer.world.playSound(loc, org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 0.5f)
    }
}
