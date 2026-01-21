package com.panling.basic.loot

import com.panling.basic.manager.ItemManager
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ThreadLocalRandom

class GlobalLootTable(val id: String) {
    private val conditions = ArrayList<LootCondition>()
    private val entries = ArrayList<LootEntry>()

    // 模式：ALL (全部独立判断), WEIGHTED (权重抽取 1 个)
    var isWeightedMode: Boolean = false
    private var totalWeight: Int = 0

    fun addCondition(condition: LootCondition) {
        conditions.add(condition)
    }

    fun addEntry(entry: LootEntry) {
        entries.add(entry)
        if (entry.weight > 0) {
            totalWeight += entry.weight
        }
    }

    // 核心逻辑：是否满足前置条件
    fun canDrop(context: LootContext): Boolean {
        for (condition in conditions) {
            // 假设 LootCondition 已转为 Kotlin 接口或 SAM
            if (!condition.test(context)) return false
        }
        return true
    }

    // 核心逻辑：计算掉落
    fun rollDrops(itemManager: ItemManager): List<ItemStack> {
        val results = ArrayList<ItemStack>()

        if (isWeightedMode && totalWeight > 0) {
            // === 权重模式 (抽卡) ===
            val roll = ThreadLocalRandom.current().nextInt(totalWeight)
            var current = 0
            for (entry in entries) {
                current += entry.weight
                if (roll < current) {
                    // 命中！
                    val item = entry.generateItem(itemManager)
                    if (item != null) results.add(item)
                    break // 只掉一个
                }
            }
        } else {
            // === 独立概率模式 (每个物品单独判定) ===
            for (entry in entries) {
                if (entry.testChance()) {
                    val item = entry.generateItem(itemManager)
                    if (item != null) results.add(item)
                }
            }
        }
        return results
    }
}