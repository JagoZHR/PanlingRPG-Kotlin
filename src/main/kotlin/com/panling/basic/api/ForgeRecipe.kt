package com.panling.basic.api

/**
 * 锻造配方数据模型 (Immutable)
 */
data class ForgeRecipe(
    val id: String,              // 配方唯一ID
    val targetItemId: String,    // 产出物品ID (引用 PanlingBasic 的 ItemID)
    val category: ForgeCategory, // 新增：分类
    val displayName: String,     // 显示名称
    val materials: Map<String, Int>, // 原料需求: Map<ItemId, Amount>
    val cost: Double,            // 金币消耗 (预留接口)
    val timeSeconds: Int,        // 锻造耗时 (预留接口)
    val requiresUnlock: Boolean  // [NEW] 是否需要解锁才能查看/制作
)