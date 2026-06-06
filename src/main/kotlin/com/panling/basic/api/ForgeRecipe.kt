package com.panling.basic.api

/**
 * 锻造配方数据模型 (Immutable)
 */
data class ForgeRecipe(
    val id: String,
    val targetItemId: String,
    val category: ForgeCategory,
    val displayName: String,
    val materials: Map<String, Int>,
    val cost: Double,
    val spiritCost: Long,
    val tier: Int,
    val sub: String?,      // 流派 PO_JUN/GOLDEN_BELL/SNIPER/RANGER 或 null
    val slot: String?,     // 部位 helmet/chest/leggings/boots 或 null
    val timeSeconds: Int,
    val requiresUnlock: Boolean
)