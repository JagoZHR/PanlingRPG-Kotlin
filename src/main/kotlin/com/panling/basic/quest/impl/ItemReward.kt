package com.panling.basic.quest.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.quest.api.QuestReward
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

class ItemReward(
    private val itemId: String,
    private val amount: Int = 1,
    private val displayText: String
) : QuestReward {

    override val display: String
        get() = displayText

    override fun give(player: Player) {
        val plugin = PanlingBasic.instance

        // 1. 解锁使用资格
        plugin.playerDataManager.addUnlockedItem(player, itemId)

        // 2. 创建物品
        val item = plugin.itemManager.createItem(itemId, player) ?: run {
            player.sendMessage("§c[错误] 无法创建物品 '$itemId'")
            return
        }
        item.amount = amount

        // 3. 灵魂绑定：设置 bound_owner_name
        val meta = item.itemMeta
        meta.persistentDataContainer.set(
            NamespacedKey(plugin, "bound_owner_name"),
            PersistentDataType.STRING,
            player.name
        )
        item.itemMeta = meta

        // 4. 给予玩家
        val leftover = player.inventory.addItem(item)
        if (leftover.isNotEmpty()) {
            for (entry in leftover) {
                player.world.dropItem(player.location, entry.value)
            }
            player.sendMessage("§e背包已满，部分奖励掉落在地上。")
        }
    }
}
