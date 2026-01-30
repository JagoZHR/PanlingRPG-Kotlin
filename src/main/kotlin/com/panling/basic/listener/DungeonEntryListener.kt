package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import com.panling.basic.ui.DungeonEntryUI
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.persistence.PersistentDataType

class DungeonEntryListener(private val plugin: PanlingBasic) : Listener {

    // 必须与 DungeonEntryUI 中设置的 Key 保持完全一致
    private val dungeonKey = NamespacedKey(plugin, "ui_dungeon_id")

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        // 检查 Holder 是否是我们自定义的 EntryHolder
        if (event.inventory.holder is DungeonEntryUI.EntryHolder) {
            // 彻底禁止拖拽（防止玩家把物品拖进界面）
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        // 1. 检查是否是副本入口 UI
        val holder = event.inventory.holder as? DungeonEntryUI.EntryHolder ?: return

        // 2. 核心：取消事件，防止物品被拿走或放入
        event.isCancelled = true

        // 过滤非玩家操作（虽然极少见）
        val player = event.whoClicked as? Player ?: return

        // 如果点击的是自己的背包，也取消（防止误操作放入物品），或者你可以 return 允许整理背包
        // 这里建议取消，保持界面整洁
        if (event.clickedInventory != event.inventory) return

        // 3. 获取点击的物品
        val item = event.currentItem ?: return
        if (item.type == Material.AIR || !item.hasItemMeta()) return

        val pdc = item.itemMeta!!.persistentDataContainer

        // 4. 检测是否点击了“开始挑战”按钮
        // 我们检查物品上是否有 "ui_dungeon_id" 这个数据
        if (pdc.has(dungeonKey, PersistentDataType.STRING)) {
            val dungeonId = pdc.get(dungeonKey, PersistentDataType.STRING)

            if (dungeonId != null) {
                // 关闭界面
                player.closeInventory()

                // 正式开始副本
                // 此时 player 还在主线程，直接调用即可
                plugin.dungeonManager.startDungeon(player, dungeonId)
            }
        }
    }
}