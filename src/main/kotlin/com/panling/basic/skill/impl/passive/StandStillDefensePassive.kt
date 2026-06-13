package com.panling.basic.skill.impl.passive

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * 黑铁壁垒：不主动移动 3 秒后获得 15% 减伤。被推不算。
 */
class StandStillDefensePassive(plugin: PanlingBasic) : AbstractSkill("STAND_STILL_DEFENSE", "黑铁壁垒", PlayerClass.NONE), Listener {

    private val pl = plugin

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun onCast(ctx: SkillContext): Boolean = false

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val from = event.from
        val to = event.to ?: return

        val passives = pl.playerDataManager.getCachedPassives(player, PlayerDataManager.PassiveTrigger.CONSTANT)
        val hasPassive = passives.any { it.id == id }
        if (!hasPassive) {
            clearBuff(player)
            return
        }

        // 检查是否跨 block 移动
        if (from.blockX != to.blockX || from.blockY != to.blockY || from.blockZ != to.blockZ) {
            val vel = player.velocity
            // velocity > 0.01 = 外力推动 → 不重置，不打断
            if (vel.lengthSquared() <= 0.01) {
                // 主动 WASD → 重置计时
                player.removeMetadata("pl_still_since", pl)
                clearBuff(player)
                return
            }
        }

        // 站立中：更新时间
        val now = System.currentTimeMillis()
        if (!player.hasMetadata("pl_still_since")) {
            player.setMetadata("pl_still_since", FixedMetadataValue(pl, now))
        }
        val since = player.getMetadata("pl_still_since").firstOrNull()?.asLong() ?: now
        val elapsed = (now - since) / 1000.0

        if (elapsed >= 3.0 && !player.hasMetadata("pl_standstill_def")) {
            player.setMetadata("pl_standstill_def", FixedMetadataValue(pl, true))
            player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, Int.MAX_VALUE, 1, false, false))
        }
    }

    private fun clearBuff(player: Player) {
        if (player.hasMetadata("pl_standstill_def")) {
            player.removeMetadata("pl_standstill_def", pl)
            player.removePotionEffect(PotionEffectType.RESISTANCE)
        }
    }
}
