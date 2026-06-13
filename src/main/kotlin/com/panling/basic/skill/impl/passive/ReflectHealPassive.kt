package com.panling.basic.skill.impl.passive

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import kotlin.math.max
import kotlin.math.roundToInt

class ReflectHealPassive(plugin: PanlingBasic) : AbstractSkill("REFLECT_HEAL", "明光反照", PlayerClass.NONE), Listener {
    private val pl = plugin
    init { Bukkit.getPluginManager().registerEvents(this, plugin) }
    override fun onCast(ctx: SkillContext): Boolean = false

    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val damager = event.damager as? LivingEntity ?: return

        if (victim.hasMetadata("pl_reflect_proc")) return

        val passives = pl.playerDataManager.getCachedPassives(victim, PlayerDataManager.PassiveTrigger.HIT)
        if (passives.find { it.id == id } == null) return

        val now = System.currentTimeMillis()

        // 优先检查是否已在 3 秒持续窗口内（不受 CD 限制）
        val activeSince = victim.getMetadata("pl_reflect_active").firstOrNull()?.asLong()
        if (activeSince != null) {
            if (now - activeSince > 3000) {
                victim.removeMetadata("pl_reflect_active", pl)
            } else {
                doReflect(victim, damager, event)
            }
            return
        }

        // 未激活 → 检查 HP 阈值
        val maxHp = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        val afterDamage = victim.health - event.damage
        if (afterDamage / maxHp > 0.4) return

        // 检查 60 秒冷却
        val cdKey = NamespacedKey(pl, "set_cd_reflect_heal")
        val lastCd = victim.persistentDataContainer.get(cdKey, PersistentDataType.LONG) ?: 0L
        if (now - lastCd < 60000) return

        // 触发
        victim.persistentDataContainer.set(cdKey, PersistentDataType.LONG, now)
        victim.setMetadata("pl_reflect_active", FixedMetadataValue(pl, now))
        doReflect(victim, damager, event)
    }

    private fun doReflect(victim: Player, damager: LivingEntity, event: EntityDamageByEntityEvent) {
        val maxHp = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        val reflect = max(event.damage * 0.2, 1.0)
        val afterDamage = victim.health - event.damage
        val missing = maxHp - afterDamage
        val heal = reflect.coerceAtMost(missing).coerceAtLeast(0.0)
        if (heal > 0) victim.health = (victim.health + heal).coerceAtMost(maxHp)

        victim.setMetadata("pl_reflect_proc", FixedMetadataValue(pl, true))
        damager.damage(reflect)
        victim.removeMetadata("pl_reflect_proc", pl)

        victim.sendMessage("§d[明光反照] §7护心镜光芒大盛！反伤${reflect.roundToInt()} 恢复${heal.roundToInt()}")
        victim.world.playSound(victim.location, org.bukkit.Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 1.5f)
    }
}
