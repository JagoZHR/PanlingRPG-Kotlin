package com.panling.basic.manager

import com.panling.basic.api.BasicKeys
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class QualificationManager(private val dataManager: PlayerDataManager) {

    companion object {
        // [修改] 只有权重/阶级 <= 1 的物品才默认允许使用
        // 假设 BasicKeys.ITEM_RARITY_WEIGHT 存储的是阶级 (1, 2, 3...)
        private const val FREE_USAGE_MAX_TIER = 1

        // [新增] 全局白名单：配置不需要资格就能用的物品ID (如药水、食物、回城卷轴)
        private val GLOBAL_WHITELIST = setOf(
            "quiver_uncommon",
            "alchemy_furnace"
        )
    }

    /**
     * 检查玩家是否有资格使用该物品
     */
    fun checkQualification(player: Player, item: ItemStack?): Boolean {
        if (item == null || !item.hasItemMeta()) return true

        val pdc = item.itemMeta!!.persistentDataContainer

        // 1. 获取物品 ID
        val itemId = pdc.get(BasicKeys.ITEM_ID, PersistentDataType.STRING) ?: return true

        // 2. [核心修改] 检查白名单
        if (GLOBAL_WHITELIST.contains(itemId)) {
            return true
        }

        // 3. [核心修改] 检查阶级/权重
        // 如果物品没有配置权重，默认为 1 (新手物品)
        val weight = pdc.get(BasicKeys.ITEM_RARITY_WEIGHT, PersistentDataType.INTEGER) ?: 1

        // 只有 1 阶及以下物品默认放行
        if (weight <= FREE_USAGE_MAX_TIER) {
            return true
        }

        // 4. 其他所有情况：必须在已解锁列表中
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