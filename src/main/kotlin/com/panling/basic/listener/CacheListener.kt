package com.panling.basic.listener

import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.impl.archer.SniperT5Skill
import com.panling.basic.skill.strategy.metal.MetalAttackT5FurnaceStrategy
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.*

class CacheListener(private val dataManager: PlayerDataManager) : Listener {

    // 1. 玩家加入：初始化/清理旧缓存
    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        // 加入时清空一次，确保数据从 NBT 重新加载
        dataManager.onPlayerQuit(event.player)
    }

    // 2. 玩家退出：清理所有内存缓存
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId

        // [潜在依赖] 如果这些类还没迁移，请确保它们在 Java 源码中存在
        try {
            MetalAttackT5FurnaceStrategy.clearPlayer(uuid)
            SniperT5Skill.clearCache(uuid)
        } catch (e: NoClassDefFoundError) {
            // 忽略迁移过程中的类缺失错误
        }

        dataManager.onPlayerQuit(event.player)
    }

    // 3. F键切换副手：这会改变主/副手物品，必须重算属性
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        dataManager.clearStatCache(event.player)
    }

    // 4. 丢弃物品：背包少了东西，必须重算
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDropItem(event: PlayerDropItemEvent) {
        dataManager.clearStatCache(event.player)
    }

    // 5. 捡起物品：背包多了东西，必须重算
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickupItem(event: EntityPickupItemEvent) {
        // Kotlin 优雅的类型转换：如果是 Player 则赋值，否则 return
        val player = event.entity as? Player ?: return
        dataManager.clearStatCache(player)
    }

    // 6. 切换快捷栏 (1-9)：主手物品变了，必须重算
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemHeld(event: PlayerItemHeldEvent) {
        dataManager.clearStatCache(event.player)
    }

    // 7. 物品损坏：装备爆了，必须重算
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemBreak(event: PlayerItemBreakEvent) {
        dataManager.clearStatCache(event.player)
    }

    @EventHandler
    fun onKick(event: PlayerKickEvent) {
        val uuid = event.player.uniqueId
        try {
            MetalAttackT5FurnaceStrategy.clearPlayer(uuid)
            SniperT5Skill.clearCache(uuid)
        } catch (ignored: Throwable) {}
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        // 玩家死亡时，清理剑阵（如果不清理，复活后剑还在，但逻辑可能断开）
        try {
            MetalAttackT5FurnaceStrategy.clearPlayer(event.entity.uniqueId)
        } catch (ignored: Throwable) {}
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        // 切换世界时，实体通常会无法跟随，建议清理并重置
        try {
            MetalAttackT5FurnaceStrategy.clearPlayer(event.player.uniqueId)
        } catch (ignored: Throwable) {}
    }
}