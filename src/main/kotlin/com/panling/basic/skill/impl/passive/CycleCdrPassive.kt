package com.panling.basic.skill.impl.passive

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType

class CycleCdrPassive(plugin: PanlingBasic) : AbstractSkill("CYCLE_CDR", "太极化生", PlayerClass.NONE), Listener {
    private val pl = plugin
    init { Bukkit.getPluginManager().registerEvents(this, plugin) }
    override fun onCast(ctx: SkillContext): Boolean = false

    @EventHandler
    fun onKill(event: EntityDeathEvent) {
        val killer = event.entity.killer as? Player ?: return
        val passives = pl.playerDataManager.getCachedPassives(killer, PlayerDataManager.PassiveTrigger.ATTACK)
        if (passives.find { it.id == id } == null) return

        val cdKey = NamespacedKey(pl, "set_cd_cycle_cdr")
        val now = System.currentTimeMillis()
        val last = killer.persistentDataContainer.get(cdKey, PersistentDataType.LONG) ?: 0L
        if (now - last < 2000) return
        killer.persistentDataContainer.set(cdKey, PersistentDataType.LONG, now)

        pl.cooldownManager.reduceAllCooldowns(killer, 0.2)
        killer.sendMessage("§d[太极化生] §7阴阳轮转，技能冷却减少！")
        killer.playSound(killer.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.8f)
    }
}
