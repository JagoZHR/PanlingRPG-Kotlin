package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import com.panling.basic.api.ForgeCategory
import com.panling.basic.forge.ForgeManager
import com.panling.basic.ui.ForgeUI
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType

class ForgeListener(private val manager: ForgeManager) : Listener {

    private val ui: ForgeUI = ForgeUI(manager)

    // 获取插件实例 (兼容 Java/Kotlin 主类写法)
    private val plugin = PanlingBasic.instance

    // [核心修改] 将 Key 的注册者统一为 PanlingBasic
    private val recipeKey = NamespacedKey(plugin, "forge_rid")
    private val categoryKey = NamespacedKey(plugin, "forge_cat")
    private val backKey = NamespacedKey(plugin, "ui_back_target")

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        if (event.action == Action.RIGHT_CLICK_BLOCK) {
            val block = event.clickedBlock ?: return

            // 这里保持 DISPENSER (发射器) 作为锻造台
            if (block.type == Material.DISPENSER) {
                event.isCancelled = true
                ui.openMainMenu(event.player) // 打开一级菜单
            }
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val inventory = event.inventory

        // 检查 Holder 类型 (Kotlin 的 as? 智能转换)
        val holder = inventory.holder as? ForgeUI.ForgeHolder ?: return

        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return

        // 防止点击自己的背包触发逻辑 (可选，视需求而定，原逻辑是 return)
        if (event.clickedInventory != inventory) return

        val clicked = event.currentItem
        if (clicked == null || clicked.type == Material.AIR || !clicked.hasItemMeta()) return

        val meta = clicked.itemMeta ?: return
        val pdc = meta.persistentDataContainer

        // 1. 优先处理：统一返回按钮
        val backTarget = pdc.get(backKey, PersistentDataType.STRING)
        if (backTarget != null) {
            when {
                backTarget == "MAIN" -> ui.openMainMenu(player)
                backTarget.startsWith("CATEGORY:") -> {
                    val catName = backTarget.split(":")[1]
                    try {
                        ui.openCategoryMenu(player, ForgeCategory.valueOf(catName))
                    } catch (ignored: Exception) {
                        ui.openMainMenu(player)
                    }
                }
            }
            return
        }

        // 获取 Holder 里的类型标记
        val type = holder.type ?: return

        // 使用 when 表达式处理不同菜单层级的逻辑
        when {
            // 2. 主菜单逻辑 (点击分类)
            type == "MAIN" -> {
                val catName = pdc.get(categoryKey, PersistentDataType.STRING)
                if (catName != null) {
                    try {
                        ui.openCategoryMenu(player, ForgeCategory.valueOf(catName))
                    } catch (e: IllegalArgumentException) {
                        player.sendMessage("§c未知分类: $catName")
                    }
                }
            }

            // 3. 分类列表逻辑 (点击配方)
            type.startsWith("CATEGORY:") -> {
                val rid = pdc.get(recipeKey, PersistentDataType.STRING)
                if (rid != null) {
                    val recipe = manager.getRecipe(rid)
                    if (recipe != null) ui.openDetailMenu(player, recipe)
                }
            }

            // 4. 详情页逻辑 (点击锻造)
            type.startsWith("DETAIL:") -> {
                val rid = type.split(":")[1]
                // 确认点击的是确认按钮 (假设 slot 49 是确认键)
                if (event.slot == 49) {
                    val recipe = manager.getRecipe(rid)
                    if (recipe != null) manager.attemptForge(player, recipe)
                }
            }
        }
    }
}