package com.panling.basic.manager

import com.panling.basic.api.BasicKeys
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class QualificationManager(private val dataManager: PlayerDataManager) {

    companion object {
        // 阈值：稀有度权重 >= 6 时需要检测资格
        private const val QUALIFICATION_THRESHOLD = 6
    }

    /**
     * 检查玩家是否有资格使用该物品
     * @return true=通过 (包括不需要检查的情况), false=未通过
     */
    fun checkQualification(player: Player, item: ItemStack?): Boolean {
        if (item == null || !item.hasItemMeta()) return true

        // item.itemMeta!! 在 hasItemMeta() 为 true 时是安全的
        val pdc = item.itemMeta!!.persistentDataContainer

        // 1. 快速检查权重 (int 对比，极快)
        // 使用 Elvis 操作符处理 null，如果没配权重默认视作 0
        val weight = pdc.get(BasicKeys.ITEM_RARITY_WEIGHT, PersistentDataType.INTEGER) ?: 0

        if (weight < QUALIFICATION_THRESHOLD) {
            return true // 低稀有度直接通过
        }

        // 2. 获取物品 ID
        val itemId = pdc.get(BasicKeys.ITEM_ID, PersistentDataType.STRING) ?: return true // 理论上不会发生

        // 3. 检查玩家数据
        return hasUnlocked(player, itemId)
    }

    /**
     * [API] 检查玩家是否解锁了特定 ID 的物品
     */
    fun hasUnlocked(player: Player, itemId: String): Boolean {
        // dataManager.getUnlockedItems 在 Kotlin 版本返回的是 List<String>
        return dataManager.getUnlockedItems(player).contains(itemId)
    }

    /**
     * [API] 为玩家解锁物品 (供外部插件调用)
     */
    fun unlockItem(player: Player, itemId: String) {
        dataManager.addUnlockedItem(player, itemId)
        player.sendMessage("§e[系统] 你获得了珍稀装备的使用资格: $itemId")
    }

    /**
     * [API] 移除资格
     */
    fun revokeItem(player: Player, itemId: String) {
        dataManager.removeUnlockedItem(player, itemId)
    }
}