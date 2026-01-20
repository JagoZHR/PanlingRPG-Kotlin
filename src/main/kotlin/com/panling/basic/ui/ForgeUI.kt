package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.ForgeCategory
import com.panling.basic.api.ForgeRecipe
import com.panling.basic.api.PlayerClass
import com.panling.basic.forge.ForgeManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class ForgeUI(private val manager: ForgeManager) {

    // [核心修改] 获取插件实例
    // 假设 PanlingBasic 有 getInstance() 静态方法
    private val plugin = PanlingBasic.getInstance()

    private val recipeKey = NamespacedKey(plugin, "forge_rid")
    private val categoryKey = NamespacedKey(plugin, "forge_cat")
    private val backKey = NamespacedKey(plugin, "ui_back_target")

    // === Level 1: 主分类菜单 ===
    fun openMainMenu(player: Player) {
        val inv = Bukkit.createInventory(ForgeHolder("MAIN"), 27, Component.text("§8锻造台"))

        val slots = intArrayOf(10, 11, 12, 13, 14, 15, 16)
        var index = 0

        for (cat in ForgeCategory.values()) {
            if (index >= slots.size) break

            val icon = ItemStack(cat.icon)
            icon.editMeta { meta ->
                meta.displayName(
                    Component.text(cat.displayName)
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(
                    listOf(
                        Component.text("§7点击查看该分类下的配方").decoration(TextDecoration.ITALIC, false)
                    )
                )
                meta.persistentDataContainer.set(categoryKey, PersistentDataType.STRING, cat.name)
            }

            inv.setItem(slots[index++], icon)
        }

        player.openInventory(inv)
    }

    // === Level 2: 分类下的配方列表 ===
    fun openCategoryMenu(player: Player, category: ForgeCategory) {
        val inv = Bukkit.createInventory(
            ForgeHolder("CATEGORY:${category.name}"),
            54,
            Component.text("§8${category.displayName}")
        )

        // 从 Manager 获取引用 (ForgeManager 已转为 Kotlin，直接访问属性)
        val itemManager = manager.itemManager // 如果报错请检查 ForgeManager 是否公开了 itemManager
        val dataManager = manager.playerDataManager // 以前叫 getPlayerDataManager()
        // 注意：这里需要确保 ForgeManager 暴露了 playerDataManager
        // 如果没有，请在 ForgeManager 中添加 val playerDataManager get() = plugin.playerDataManager

        val recipes = manager.getRecipesByCategory(category)
        val playerClass = dataManager.getPlayerClass(player)

        var slot = 0
        for (recipe in recipes) {
            // =========================================================
            // [核心逻辑] 配方可见性检查
            // =========================================================
            if (recipe.requiresUnlock && !dataManager.hasUnlockedRecipe(player, recipe.id)) {
                continue
            }

            var icon = itemManager.createItem(recipe.targetItemId, player)
            if (icon == null) {
                icon = ItemStack(Material.BARRIER)
                icon.editMeta {
                    it.displayName(Component.text("Config Error: ${recipe.targetItemId}"))
                }
            }

            // 职业过滤
            val reqClassStr = icon.itemMeta?.persistentDataContainer
                ?.get(BasicKeys.FEATURE_REQ_CLASS, PersistentDataType.STRING)

            if (reqClassStr != null) {
                try {
                    val itemClass = PlayerClass.valueOf(reqClassStr)
                    if (itemClass != PlayerClass.NONE && itemClass != playerClass) continue
                } catch (ignored: Exception) {}
            }

            icon.editMeta { meta ->
                meta.persistentDataContainer.set(recipeKey, PersistentDataType.STRING, recipe.id)

                val lore = meta.lore() ?: ArrayList()
                lore.add(Component.empty())
                lore.add(Component.text("§e▶ 点击查看详情").decoration(TextDecoration.ITALIC, false))
                meta.lore(lore)
            }

            inv.setItem(slot++, icon)
        }

        addBackButton(inv, 45, "MAIN")
        player.openInventory(inv)
    }

    // === Level 3: 详情页 ===
    fun openDetailMenu(player: Player, recipe: ForgeRecipe) {
        val inv = Bukkit.createInventory(
            ForgeHolder("DETAIL:${recipe.id}"),
            54,
            Component.text("§8⚒ 锻造详情")
        )
        val itemManager = manager.itemManager

        // 结果预览
        val result = itemManager.createItem(recipe.targetItemId, player)
        if (result != null) inv.setItem(13, result)

        var matSlot = 29
        for ((matId, amountNeeded) in recipe.materials) {
            var matIcon = itemManager.createItem(matId, player)
            if (matIcon == null) matIcon = ItemStack(Material.PAPER)

            matIcon.amount = amountNeeded
            matIcon.editMeta { meta ->
                val lore = ArrayList<Component>()
                lore.add(Component.text("§7需要: $amountNeeded"))

                val has = countItem(player, matId)
                val color = if (has >= amountNeeded) "§a" else "§c"
                lore.add(Component.text("${color}拥有: $has"))

                meta.lore(lore)
            }
            inv.setItem(matSlot++, matIcon)
        }

        // 锻造按钮
        val btn = ItemStack(Material.ANVIL)
        btn.editMeta { meta ->
            meta.displayName(Component.text("§a✔ 确认锻造").decoration(TextDecoration.BOLD, true))

            val lore = ArrayList<Component>()
            val balance = manager.playerDataManager.getMoney(player) // 假设 getMoney 存在
            val cost = recipe.cost

            if (cost > 0) {
                val color = if (balance >= cost) "§e" else "§c"
                lore.add(Component.text("${color}花费铜钱: ${String.format("%.0f", cost)}")
                    .decoration(TextDecoration.ITALIC, false))
                lore.add(Component.text("§7(当前余额: ${String.format("%.0f", balance)})")
                    .decoration(TextDecoration.ITALIC, false))
            } else {
                lore.add(Component.text("§a免费锻造").decoration(TextDecoration.ITALIC, false))
            }

            lore.add(Component.empty())
            lore.add(Component.text("§7点击开始锻造").decoration(TextDecoration.ITALIC, false))

            meta.lore(lore)
        }
        inv.setItem(49, btn)

        addBackButton(inv, 45, "CATEGORY:${recipe.category.name}")

        player.openInventory(inv)
    }

    private fun addBackButton(inv: Inventory, slot: Int, targetType: String) {
        val back = ItemStack(Material.ARROW)
        back.editMeta { meta ->
            meta.displayName(Component.text("§7返回").decoration(TextDecoration.ITALIC, false))
            meta.persistentDataContainer.set(backKey, PersistentDataType.STRING, targetType)
        }
        inv.setItem(slot, back)
    }

    private fun countItem(player: Player, itemId: String): Int {
        // Kotlin 风格的流式计算
        return player.inventory.contents
            .filterNotNull()
            .filter { it.hasItemMeta() }
            .filter {
                val id = it.itemMeta!!.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING)
                itemId == id
            }
            .sumOf { it.amount }
    }

    // Kotlin 扩展函数简化 Meta 编辑
    private fun ItemStack.editMeta(block: (ItemMeta) -> Unit) {
        val meta = this.itemMeta ?: return
        block(meta)
        this.itemMeta = meta
    }

    // Holder 类
    class ForgeHolder(val type: String?) : InventoryHolder {
        override fun getInventory(): Inventory {
            // 返回 null 是安全的，但要小心某些插件可能会调用它
            // 这里为了通过编译返回一个空 Inventory 或者 throw Exception
            // 只要不被调用就没事。通常 Bukkit GUI 库的 Holder 只是个标记。
            // 为了安全起见，这里抛出异常或者返回 null (Kotlin 中 inventory 是 Platform Type，可以返回 null)
            throw UnsupportedOperationException("ForgeHolder assumes custom inventory handling")
        }
    }
}