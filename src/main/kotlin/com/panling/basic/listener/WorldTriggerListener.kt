package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import com.panling.basic.manager.LocationManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class WorldTriggerListener(private val plugin: PanlingBasic) : Listener {

    // 直接通过 plugin 属性访问 Manager
    private val locationManager = plugin.locationManager
    private val dataManager = plugin.playerDataManager

    // 1. 方块交互
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        // 安全获取点击的方块，如果是空则返回
        val block = event.clickedBlock ?: return

        val triggers = locationManager.getTriggersAt(block.location)
        if (triggers.isEmpty()) return

        event.isCancelled = true
        executeTriggers(event.player, triggers)
    }

    // 2. 实体交互 (NPC/怪物)
    @EventHandler
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        // 只响应主手
        if (event.hand != EquipmentSlot.HAND) return

        val entity = event.rightClicked

        // 查找该实体是否有触发器
        val triggers = locationManager.getEntityTriggers(entity.uniqueId)

        if (triggers.isEmpty()) return

        event.isCancelled = true // 阻止原版交互 (如骑马、村民交易)
        executeTriggers(event.player, triggers)
    }

    // === DRY: 统一执行逻辑 ===
    private fun executeTriggers(player: Player, triggers: List<LocationManager.TriggerData>) {
        for (trigger in triggers) {
            // 获取 Handler 并执行
            // 假设 TriggerData 是 Record 或 Bean，Kotlin 可以直接访问 .type 和 .value 属性
            val handler = locationManager.getHandler(trigger.type)
            handler?.execute(player, trigger.value)
        }
    }
}