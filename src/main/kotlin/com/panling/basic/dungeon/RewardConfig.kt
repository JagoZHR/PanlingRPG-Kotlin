package com.panling.basic.dungeon

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/**
 * 奖励阶段配置
 *
 * @param title 奖励标题（显示给玩家）
 * @param autoReward true=自动发奖（无宝箱），false=宝箱交互领奖
 * @param autoRewardDelay 自动发奖后延迟关闭的 tick 数（仅 autoReward 模式）
 * @param chestOffset 宝箱相对副本中心点的偏移（仅非 autoReward 模式）
 * @param chestMaterial 宝箱材质（默认末影箱）
 * @param money 奖励铜钱
 * @param items 奖励物品列表 (itemId → 数量)
 * @param onReward 每个玩家领奖时的自定义回调（如解锁配方）
 * @param maxRewardSlots 领奖所需最少背包空位
 */
data class RewardConfig(
    val title: String = "§6[奖励] 副本奖励",
    val autoReward: Boolean = false,
    val autoRewardDelay: Long = 100L,   // 默认 5 秒
    val chestOffset: Vector = Vector(0, 1, 0),
    val chestMaterial: Material = Material.ENDER_CHEST,
    val money: Double = 0.0,
    val items: List<Pair<String, Int>> = emptyList(),
    val onReward: ((Player) -> Unit)? = null,
    val maxRewardSlots: Int = 5
)
