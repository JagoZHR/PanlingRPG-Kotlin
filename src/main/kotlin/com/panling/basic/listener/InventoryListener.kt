package com.panling.basic.listener

import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.manager.ItemManager
import com.panling.basic.manager.LoreManager
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.manager.StatCalculator
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

class InventoryListener(
    private val plugin: JavaPlugin,
    private val dataManager: PlayerDataManager,
    private val itemManager: ItemManager,
    private val statCalculator: StatCalculator
) : Listener {

    fun refreshPlayerStatus(player: Player) {
        dataManager.clearStatCache(player)
        updateInventoryLore(player)
    }

    private fun updateInventoryLore(player: Player) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            // 获取当前激活槽位和职业
            val activeSlot = dataManager.getActiveSlot(player) // 假设 getter 存在
            val playerClass = dataManager.getPlayerClass(player)
            val contents = player.inventory.contents

            // 使用 forEachIndexed 替代传统的 for 循环
            contents.forEachIndexed { index, itemStack ->
                if (itemStack != null) {
                    checkAndRefresh(player, itemStack, index, activeSlot, playerClass)
                }
            }

            // [修改] 调用 StatCalculator 的同步方法
            statCalculator.syncPlayerAttributes(player)
        })
    }

    /**
     * 核心修复：单一事实来源 (Single Source of Truth)
     * UI层 (InventoryListener) 不应包含任何业务判断逻辑，必须全权委托给 逻辑层 (StatCalculator)。
     */
    private fun checkAndRefresh(
        player: Player,
        item: ItemStack,
        slotIndex: Int,
        activeSlot: Int,
        playerClass: PlayerClass
    ) {
        if (!item.hasItemMeta()) return
        if (!isPanlingItem(item)) return

        // 1. 同步数据
        itemManager.syncItem(item, player)

        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer

        // === 新增：检查是否为材料 ===
        val type = pdc.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)

        if ("MATERIAL" == type) {
            // 只有当玩家把它放在快捷栏 (0-8) 或者副手 (40) 时才提示
            if (slotIndex in 0..8 || slotIndex == 40) {
                // 需要在 LoreManager/StatCalculator 加这个状态常量
                LoreManager.refreshStatus(item, StatCalculator.STATUS_MATERIAL_ONLY, activeSlot, 0)
                return
            }
            // 如果在背包里，不显示状态行 (清除之前的 Lore, -1 代表清除)
            LoreManager.refreshStatus(item, -1, activeSlot, 0)
            return
        }

        // 2. 询问 Calculator 获取状态码
        // 任何资格判断、槽位判断、职业判断都在 getValidationStatus 内部完成
        val status = statCalculator.getValidationStatus(player, item, slotIndex, activeSlot, playerClass)

        // 3. 获取法宝目标槽位 (仅用于显示 Status 8 时的提示信息)
        var targetSlot = 0
        if (status == StatCalculator.STATUS_FABAO_ACTIVE || status == StatCalculator.STATUS_FABAO_SLOT) {
            targetSlot = statCalculator.getFabaoTargetSlot(player, item)
        }

        // 4. 刷新 Lore
        LoreManager.refreshStatus(item, status, activeSlot, targetSlot)
    }

    private fun isPanlingItem(item: ItemStack?): Boolean {
        if (item == null || !item.hasItemMeta()) return false
        val pdc = item.itemMeta?.persistentDataContainer ?: return false

        // 只要是本插件管理的物品，都应该有 ID
        if (pdc.has(BasicKeys.ITEM_ID, PersistentDataType.STRING)) return true

        // 兼容旧物品或仅有属性的物品
        return pdc.has(BasicKeys.ATTR_PHYSICAL_DAMAGE, PersistentDataType.DOUBLE)
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val item = event.item
        if (item == null || item.type == Material.AIR) return

        if (PlayerClass.isArmor(item.type)) {
            refreshPlayerStatus(event.player)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val who = event.whoClicked
        if (who is Player) {
            refreshPlayerStatus(who)
        }
    }

    @EventHandler
    fun onSwap(event: PlayerItemHeldEvent) {
        refreshPlayerStatus(event.player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        refreshPlayerStatus(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        dataManager.onPlayerQuit(event.player)
    }
}