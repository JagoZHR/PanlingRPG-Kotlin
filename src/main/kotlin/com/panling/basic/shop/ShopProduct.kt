package com.panling.basic.shop

import org.bukkit.inventory.ItemStack

data class ShopProduct(
    val itemId: String,      // 物品ID (items.yml 或 原版ID)
    val buyPrice: Double,    // 买入价 (玩家花钱)
    val sellPrice: Double,   // 卖出价 (玩家卖钱，-1表示不可卖)
    val slot: Int            // 在菜单里的位置 (0-53)
) {
    // 缓存显示的物品堆 (包含价格Lore)，避免每次渲染都重新生成
    // 对应 Java 中的 private ItemStack displayItem;
    var displayItem: ItemStack? = null
}