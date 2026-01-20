package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent

class DungeonListener(private val plugin: PanlingBasic) : Listener {

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        val manager = plugin.dungeonManager

        // 1. 检查玩家是否在副本中
        // 假设 DungeonManager 有 isPlaying 方法
        if (!manager.isPlaying(player)) return

        // 2. 获取副本实例
        // 假设 DungeonManager 有 getDungeon(Player) 方法
        val instance = manager.getDungeon(player)

        if (instance != null) {
            // 3. 转交交互事件给当前阶段
            // 假设 DungeonInstance 有 handleInteract 方法，且返回 Boolean (是否已处理)
            val handled = instance.handleInteract(event)
            if (handled) {
                event.isCancelled = true
            }
        }
    }
}