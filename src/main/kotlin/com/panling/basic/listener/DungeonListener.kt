package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent

class DungeonListener(private val plugin: PanlingBasic) : Listener {

    /**
     * 玩家交互事件 (开箱、点击机关、前置任务触发)
     */
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        val manager = plugin.dungeonManager

        // 1. 获取玩家所在的副本实例
        val instance = manager.getInstance(player) ?: return

        // 2. 转交事件给 Instance -> CurrentPhase
        instance.handleInteract(event)
    }

    /**
     * 实体死亡事件 (用于 CombatPhase 判定清怪进度)
     */
    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val world = entity.world
        val manager = plugin.dungeonManager

        // 1. 这是一个非玩家实体 (怪物)
        if (entity !is Player) {
            // 通过 World 找到对应的副本
            val instance = manager.getInstance(world) ?: return

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

    /**
     * 伤害事件 (可选：用于处理特殊机制，如无敌护盾、反伤等)
     */
    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val entity = event.entity
        val manager = plugin.dungeonManager

        // 尝试通过受击者或攻击者找到副本
        // 这里简单点，通过 World 找
        val instance = manager.getInstance(entity.world) ?: return

        instance.handleDamage(event)
    }
}