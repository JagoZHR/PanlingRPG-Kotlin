package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.ForgeCategory
import com.panling.basic.forge.ForgeManager
import com.panling.basic.manager.PatchManager
import com.panling.basic.ui.ForgeUI
import com.panling.basic.ui.PatchEmbedUI
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

class ForgeListener(private val manager: ForgeManager) : Listener {

    private val ui = ForgeUI(manager)
    private val plugin = PanlingBasic.instance

    private val subKey = NamespacedKey(plugin, "forge_sub")
    private val recipeKey = NamespacedKey(plugin, "forge_rid")
    private val pageKey = NamespacedKey(plugin, "forge_page")
    private val backKey = NamespacedKey(plugin, "ui_back_target")

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.clickedBlock?.type == Material.DISPENSER) {
            event.isCancelled = true
            ui.openMainMenu(event.player)
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? ForgeUI.ForgeHolder ?: return
        val player = event.whoClicked as? Player ?: return
        val type = holder.type

        event.isCancelled = true
        if (event.clickedInventory != event.inventory) return

        val clicked = event.currentItem ?: return
        if (clicked.type == Material.AIR || !clicked.hasItemMeta()) return
        val meta = clicked.itemMeta ?: return
        val pdc = meta.persistentDataContainer

        // 贴片稀有度选择
        if (type == "PATCH_CRAFT_RARITY") {
            val rarity = pdc.get(subKey, PersistentDataType.STRING)
            if (rarity != null) {
                ui.openRecipeList(player, ForgeCategory.PATCH_CRAFT, rarity, 0, "PATCH_CRAFT_RARITY")
                return
            }
        }

        // 1. 返回按钮
        val backTarget = pdc.get(backKey, PersistentDataType.STRING)
        if (backTarget != null) {
            when {
                backTarget == "MAIN" -> ui.openMainMenu(player)
                backTarget == "PATCH_CRAFT_RARITY" -> ui.openPatchRarityMenu(player)
                backTarget.startsWith("LIST:") -> {
                    val parts = backTarget.split(":")
                    val catName = parts[1]
                    val subFilter = if (parts.size > 2 && parts[2].isNotEmpty()) parts[2] else null
                    val page = if (parts.size > 3) parts[3].toIntOrNull() ?: 0 else 0
                    try {
                        ui.openRecipeList(player, ForgeCategory.valueOf(catName), subFilter, page)
                    } catch (ignored: Exception) { ui.openMainMenu(player) }
                }
            }
            return
        }

        // 2. 翻页
        val newPage = pdc.get(pageKey, PersistentDataType.INTEGER)
        if (newPage != null && type.startsWith("LIST:")) {
            val parts = type.split(":")
            val catName = parts[1]
            val subFilter = if (parts.size > 2 && parts[2].isNotEmpty()) parts[2] else null
            try {
                ui.openRecipeList(player, ForgeCategory.valueOf(catName), subFilter, newPage)
            } catch (ignored: Exception) {}
            return
        }

        // 3. 主菜单 → 子菜单
        if (type == "MAIN") {
            val catName = pdc.get(subKey, PersistentDataType.STRING) ?: return
            try { ui.openSubMenu(player, ForgeCategory.valueOf(catName)) } catch (ignored: Exception) {}
            return
        }

        // 4. 流派 / 部位 → 配方列表
        if (type == "SUBCLASS" || type == "SLOT") {
            val sub = pdc.get(subKey, PersistentDataType.STRING) ?: return
            val cat = if (type == "SUBCLASS") ForgeCategory.WEAPON else ForgeCategory.ARMOR
            ui.openRecipeList(player, cat, sub, 0)
            return
        }

        // 5. 配方列表 → 详情
        if (type.startsWith("LIST:")) {
            val rid = pdc.get(recipeKey, PersistentDataType.STRING) ?: return
            manager.getRecipe(rid)?.let { ui.openDetailMenu(player, it, type) }
            return
        }

        // 6. 详情 → 锻造
        if (type.startsWith("DETAIL:") && event.slot == 49) {
            val rid = type.split(":")[1]
            manager.getRecipe(rid)?.let { manager.attemptForge(player, it) }
        }
    }

    // 贴片镶嵌：状态机
    @EventHandler
    fun onEmbedInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        val topHolder = event.view.topInventory.holder
        val isTop = event.clickedInventory == event.view.topInventory
        val isEmbedTop = topHolder is PatchEmbedUI.EmbedHolder

        // 锻造界面中点击 → 不干预
        if (topHolder is ForgeUI.ForgeHolder) return

        // 装备信息等只读 EmbedHolder → 禁止操作即可
        if (isEmbedTop && !PatchEmbedUI.embedPhase.containsKey(player.uniqueId)) {
            event.isCancelled = true
            return
        }

        val phase = PatchEmbedUI.embedPhase[player.uniqueId] ?: return

        // 不是镶嵌界面 → 取消
        if (!isEmbedTop) { PatchEmbedUI().cancel(player); return }
        // SLOTS 阶段：允许点顶部槽位
        if (phase == "SLOTS" && isTop) {
            event.isCancelled = true
            PatchEmbedUI().handleInventoryClick(player, event.currentItem, true)
            return
        }

        // 选贴片阶段：禁止顶部，拦截背包
        if (isTop) { event.isCancelled = true; return }
        event.isCancelled = true
        PatchEmbedUI().handleInventoryClick(player, event.currentItem, false)
    }

    @EventHandler
    fun onEmbedClose(event: InventoryCloseEvent) {
        // EmbedHolder 关闭时不自动取消状态。
        // 状态取消由 handleInventoryClick 中检测 isEmbedTop 来处理。
        // 当玩家关闭镶嵌界面后在背包中点击任意物品，会自动取消。
    }
}
