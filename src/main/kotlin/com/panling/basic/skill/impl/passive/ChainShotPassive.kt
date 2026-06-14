package com.panling.basic.skill.impl.passive

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.util.CombatUtil
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import java.util.concurrent.ConcurrentHashMap

class ChainShotPassive(plugin: PanlingBasic) : AbstractSkill("CHAIN_SHOT", "连环劲射", PlayerClass.NONE), Listener {
    private val pl = plugin
    init { Bukkit.getPluginManager().registerEvents(this, plugin) }
    override fun onCast(ctx: SkillContext): Boolean = false

    // 独立散弹保护：(attackerUUID, targetUUID) -> lastTick
    private val lastHitTick = ConcurrentHashMap<Pair<java.util.UUID, java.util.UUID>, Int>()

    @EventHandler
    fun onAttack(event: EntityDamageByEntityEvent) {
        val attacker = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player ?: return
            else -> return
        }
        if (!CombatUtil.shouldTriggerAttackPassives(attacker, event)) return
        val target = event.entity as? LivingEntity ?: return
        if (target is Player) return
        val passives = pl.playerDataManager.getCachedPassives(attacker, PlayerDataManager.PassiveTrigger.ATTACK)
        if (passives.find { it.id == id } == null) return

        // 散弹保护：同一 tick 同一射手对同一目标的多发只计一次
        val tick = Bukkit.getCurrentTick()
        val key = Pair(attacker.uniqueId, target.uniqueId)
        val lastTick = lastHitTick[key] ?: -1
        if (tick == lastTick) return
        lastHitTick[key] = tick

        if (tick % 200 == 0) lastHitTick.entries.removeIf { tick - it.value > 2 }

        val lastTargetId = attacker.getMetadata("pl_chain_target").firstOrNull()?.asString()
        val currentId = target.uniqueId.toString()

        if (currentId != lastTargetId) {
            attacker.setMetadata("pl_chain_target", FixedMetadataValue(pl, currentId))
            attacker.setMetadata("pl_chain_count", FixedMetadataValue(pl, 1))
            return
        }

        var count = attacker.getMetadata("pl_chain_count").firstOrNull()?.asInt() ?: 0
        count++
        if (count >= 3) {
            count = 0
            val phys = pl.statCalculator.getPlayerTotalStat(attacker, BasicKeys.ATTR_PHYSICAL_DAMAGE)
            val bonus = phys * 1.5
            event.damage += bonus
            attacker.sendMessage("§9[连环劲射] §7铁环共鸣！额外伤害 +${bonus.toInt()}")
            attacker.playSound(attacker.location, Sound.BLOCK_CHAIN_BREAK, 0.5f, 2.0f)
        }
        attacker.setMetadata("pl_chain_count", FixedMetadataValue(pl, count))
    }
}
