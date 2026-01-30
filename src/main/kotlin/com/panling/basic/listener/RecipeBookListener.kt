package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

class RecipeBookListener(private val plugin: PanlingBasic) : Listener {

    // 定义识别配方书的 NBT Key
    // 对应 items.yml 配置里的 key
    private val bookKey = NamespacedKey(plugin, "feature_recipe_book_id")

    // [新增] 绑定相关的 Key
    private val boundUuidKey = NamespacedKey(plugin, "bound_owner_uuid")

    @EventHandler
    fun onUseBook(event: PlayerInteractEvent) {
        // 监听右键空气或方块
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val item = event.item ?: return
        if (item.type == Material.AIR || !item.hasItemMeta()) return

        val pdc = item.itemMeta.persistentDataContainer

        // 1. 检查是否是锻造书
        if (pdc.has(bookKey, PersistentDataType.STRING)) {
            val recipeId = pdc.get(bookKey, PersistentDataType.STRING) ?: return
            val player = event.player

            event.isCancelled = true // 阻止可能的误触（比如书原本能打开GUI）

            // =========================================================
            // [新增] 2. 绑定校验逻辑
            // =========================================================
            val ownerUuidStr = pdc.get(boundUuidKey, PersistentDataType.STRING)
            if (ownerUuidStr != null) {
                try {
                    val ownerUuid = UUID.fromString(ownerUuidStr)
                    if (player.uniqueId != ownerUuid) {
                        player.sendMessage("§c[绑定] 这本书不属于你！")
                        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                        return
                    }
                } catch (ignored: Exception) {}
            }
            // =========================================================

            // 2. 检查是否已学会
            if (plugin.forgeManager.hasUnlockedRecipe(player, recipeId)) {
                player.sendMessage("§c[提示] 你已经学会这个配方了，无需重复学习。")
                return
            }

            // 3. (可选) 检查职业限制
            // 如果你想让这书“谁都能捡，但只有本职业能用”，就在这里加判断
            val recipe = plugin.forgeManager.getRecipe(recipeId)
            if (recipe != null) {
                val playerClass = plugin.playerDataManager.getPlayerClass(player)
                if (!plugin.itemManager.isItemAllowedForClass(recipe.targetItemId, playerClass)) {
                    player.sendMessage("§c[限制] 你的职业无法理解这本书的内容。")
                    return
                }
            } else {
                player.sendMessage("§c[错误] 配方 ID 无效: $recipeId")
                return
            }

            // 4. 执行解锁
            plugin.forgeManager.unlockRecipe(player, recipeId)

            // 5. 消耗物品
            item.amount -= 1

            player.sendMessage("§a[系统] 学习成功！你领悟了新的锻造配方。")
            player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f)
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2f)
        }
    }
}