package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent

class DungeonListener(private val plugin: PanlingBasic) : Listener {

    /**
     * 玩家交互事件
     */
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        val manager = plugin.dungeonManager

        // 获取玩家所在的副本实例
        val instance = manager.getInstance(player) ?: return

        // 转交事件
        instance.handleInteract(event)
    }

    /**
     * 实体死亡事件 (用于 CombatPhase 判定清怪进度)
     */
    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val manager = plugin.dungeonManager

        // 1. 这是一个非玩家实体 (怪物)
        if (entity !is Player) {
            // [Fix] 使用新的 getInstanceAt(Location)
            // 因为现在所有副本都在同一个世界，必须通过坐标反查
            val instance = manager.getInstanceAt(entity.location) ?: return

            // 通知副本处理怪物死亡
            instance.handleMobDeath(event)
        }
    }

    /**
     * 玩家死亡事件 (用于判定失败条件)
     */
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val manager = plugin.dungeonManager

        val instance = manager.getInstance(player) ?: return

        // 通知副本玩家阵亡
        instance.handlePlayerDeath(player)
    }
}