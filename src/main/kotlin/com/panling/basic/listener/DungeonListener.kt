package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent

class DungeonListener(private val plugin: PanlingBasic) : Listener {

    /**
     * 【复活拦截】致死伤害前，如果副本支持复活，则取消死亡并触发复活菜单
     * 最高优先级确保在死亡事件前拦截
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onFatalDamage(event: EntityDamageEvent) {
        if (event.isCancelled) return
        val entity = event.entity
        if (entity !is Player) return
        if (event.finalDamage < entity.health) return  // 非致死
        val instance = plugin.dungeonManager.getInstance(entity) ?: return
        if (instance.template.reviveCost <= 0.0) return

        // 取消死亡，回满血，触发复活菜单
        event.isCancelled = true
        entity.health = entity.getAttribute(Attribute.MAX_HEALTH)!!.baseValue
        plugin.dungeonManager.handleDungeonDeath(entity)
    }

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

    /**
     * 实体受伤事件 (用于 Phase 处理反伤等机制)
     */
    @EventHandler
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val entity = event.entity
        val manager = plugin.dungeonManager

        if (entity is Player) {
            val instance = manager.getInstance(entity) ?: return
            instance.handleDamage(event)
        } else {
            val instance = manager.getInstanceAt(entity.location) ?: return
            instance.handleDamage(event)
        }
    }
}