package com.panling.basic.skill.impl.passive

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import java.util.concurrent.ConcurrentHashMap

class MarkDetonatePassive(plugin: PanlingBasic) : AbstractSkill("MARK_DETONATE", "雁翎追魂", PlayerClass.NONE), Listener {
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

        // 定期清理
        if (tick % 200 == 0) lastHitTick.entries.removeIf { tick - it.value > 2 }

        var count = target.getMetadata("pl_mark_count").firstOrNull()?.asInt() ?: 0
        count++
        if (count >= 3) {
            target.removeMetadata("pl_mark_count", pl)
            val phys = pl.statCalculator.getPlayerTotalStat(attacker, BasicKeys.ATTR_PHYSICAL_DAMAGE)
            val detonate = phys * 1.5
            event.damage += detonate
            target.noDamageTicks = 0

            target.world.spawnParticle(Particle.EXPLOSION, target.location.clone().add(0.0, 1.0, 0.0), 5)
            attacker.sendMessage("§d[雁翎追魂] §7三箭追魂！引爆 +${detonate.toInt()}")
            attacker.playSound(attacker.location, Sound.ENTITY_ARROW_HIT_PLAYER, 0.8f, 1.5f)
        } else {
            target.setMetadata("pl_mark_count", FixedMetadataValue(pl, count))
        }
    }
}
