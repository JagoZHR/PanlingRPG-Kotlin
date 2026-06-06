package com.panling.basic.skill.impl.passive

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.CooldownManager
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause

class LavaImmunityPassive(plugin: PanlingBasic) : AbstractSkill("lava_immunity", "熔岩免疫", PlayerClass.NONE), Listener {

    private val pl = plugin

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun onCast(ctx: SkillContext): Boolean {
        return false // CONSTANT 被动不通过 onCast 触发
    }

    @EventHandler
    fun onDamage(event: EntityDamageEvent) {
        if (event.cause != DamageCause.LAVA) return
        val player = event.entity as? Player ?: return

        val passives = pl.playerDataManager.getCachedPassives(player, PlayerDataManager.PassiveTrigger.CONSTANT)
        val hasPassive = passives.any { it.id == id }
        if (hasPassive) {
            event.isCancelled = true
        }
    }
}
