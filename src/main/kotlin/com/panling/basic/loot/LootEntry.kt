package com.panling.basic.loot

import com.panling.basic.manager.ItemManager
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ThreadLocalRandom

data class LootEntry(
    val itemId: String,
    val chance: Double,
    val weight: Int,
    val min: Int,
    val max: Int
) {
    // 次级构造函数：用于不带权重的简化情况
    constructor(itemId: String, chance: Double, min: Int, max: Int) : this(itemId, chance, 0, min, max)

    fun testChance(): Boolean {
        return chance >= 1.0 || ThreadLocalRandom.current().nextDouble() < chance
    }

    fun generateItem(itemManager: ItemManager): ItemStack? {
        var amount = min
        if (max > min) {
            amount = ThreadLocalRandom.current().nextInt(min, max + 1)
        }

        val item = itemManager.createItem(itemId, null)
        if (item != null) {
            item.amount = amount
        }
        return item
    }
}