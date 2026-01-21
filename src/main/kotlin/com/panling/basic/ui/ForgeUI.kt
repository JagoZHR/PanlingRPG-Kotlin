package com.panling.basic.ui // [建议] 移入 forge 包

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

    private val recipeKey: NamespacedKey
    private val categoryKey: NamespacedKey

    init {
        // [核心修改] 替换为主插件实例
        // 假设 PanlingBasic 有静态 getInstance() 方法或者伴生对象
        val instance = PanlingBasic.instance
        this.recipeKey = NamespacedKey(instance, "forge_rid")
        this.categoryKey = NamespacedKey(instance, "forge_cat")
    }

    // === Level 1: 主分类菜单 ===
    fun openMainMenu(player: Player) {
        val inv = Bukkit.createInventory(ForgeHolder("MAIN"), 27, Component.text("§8锻造台"))

        val slots = intArrayOf(10, 11, 12, 13, 14, 15, 16)
        var index = 0

        for (cat in ForgeCategory.values()) {
            if (index >= slots.size) break

            val icon = ItemStack(cat.icon)
            val meta = icon.itemMeta // 在 Bukkit 中 itemMeta 可能为 null，但新创建的 Item 通常不为 null，这里视为非空

            meta.displayName(
                Component.text(cat.displayName)
                    .color(NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true)
            )
            val lore = ArrayList<Component>()
            lore.add(Component.text("§7点击查看该分类下的配方").decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)

            meta.persistentDataContainer.set(categoryKey, PersistentDataType.STRING, cat.name)
            icon.itemMeta = meta

            inv.setItem(slots[index++], icon)
        }

        player.openInventory(inv)
    }

    // === Level 2: 分类下的配方列表 (已修改) ===
    fun openCategoryMenu(player: Player, category: ForgeCategory) {
        val inv = Bukkit.createInventory(
            ForgeHolder("CATEGORY:${category.name}"),
            54,
            Component.text("§8${category.displayName}")
        )
        val itemManager = manager.itemManager
        val dataManager = manager.playerDataManager

        val recipes = manager.getRecipesByCategory(category)
        val playerClass = dataManager.getPlayerClass(player)

        var slot = 0
        for (recipe in recipes) {

            // =========================================================
            // [核心逻辑] 配方可见性检查
            // 如果配方要求解锁，且玩家未解锁，则直接隐藏 (不显示)
            // =========================================================
            if (recipe.requiresUnlock && !dataManager.hasUnlockedRecipe(player, recipe.id)) {
                continue
            }
            // =========================================================

            var icon = itemManager.createItem(recipe.targetItemId, player)
            if (icon == null) {
                icon = ItemStack(Material.BARRIER)
                val err = icon.itemMeta
                err.displayName(Component.text("Config Error: ${recipe.targetItemId}"))
                icon.itemMeta = err
            }

            // 职业过滤 (保持原有逻辑)
            // 这里 icon 必定不为null，但 itemMeta 需要安全获取
            val iconMeta = icon!!.itemMeta
            val reqClassStr = iconMeta.persistentDataContainer
                .get(BasicKeys.FEATURE_REQ_CLASS, PersistentDataType.STRING)

            if (reqClassStr != null) {
                try {
                    val itemClass = PlayerClass.valueOf(reqClassStr)
                    if (itemClass != PlayerClass.NONE && itemClass != playerClass) continue
                } catch (ignored: Exception) {}
            }

            // 重新获取 meta (虽然上面获取过，但为了逻辑清晰重新操作)
            val meta = icon.itemMeta
            meta.persistentDataContainer.set(recipeKey, PersistentDataType.STRING, recipe.id)

            // 处理 Lore
            var lore = meta.lore()
            if (lore == null) lore = ArrayList()
            lore.add(Component.empty())
            lore.add(Component.text("§e▶ 点击查看详情").decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)

            icon.itemMeta = meta

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

        val result = itemManager.createItem(recipe.targetItemId, player)
        if (result != null) inv.setItem(13, result)

        var matSlot = 29
        for ((key, value) in recipe.materials) {
            var matIcon = itemManager.createItem(key, player)
            if (matIcon == null) matIcon = ItemStack(Material.PAPER)

            matIcon.amount = value
            val meta = matIcon.itemMeta
            val lore = ArrayList<Component>()
            lore.add(Component.text("§7需要: $value"))

            val has = countItem(player, key)
            val color = if (has >= value) "§a" else "§c"
            lore.add(Component.text("$color 拥有: $has"))

            meta.lore(lore)
            matIcon.itemMeta = meta
            inv.setItem(matSlot++, matIcon)
        }

        // 锻造按钮
        val btn = ItemStack(Material.ANVIL)
        val meta = btn.itemMeta
        meta.displayName(Component.text("§a✔ 确认锻造").decoration(TextDecoration.BOLD, true))

        val lore = ArrayList<Component>()
        val balance = manager.playerDataManager.getMoney(player)
        val cost = recipe.cost

        if (cost > 0) {
            val color = if (balance >= cost) "§e" else "§c"
            lore.add(
                Component.text(color + "花费铜钱: " + "%.0f".format(cost))
                    .decoration(TextDecoration.ITALIC, false)
            )
            lore.add(
                Component.text("§7(当前余额: " + "%.0f".format(balance) + ")")
                    .decoration(TextDecoration.ITALIC, false)
            )
        } else {
            lore.add(Component.text("§a免费锻造").decoration(TextDecoration.ITALIC, false))
        }

        lore.add(Component.empty())
        lore.add(Component.text("§7点击开始锻造").decoration(TextDecoration.ITALIC, false))

        meta.lore(lore)
        btn.itemMeta = meta
        inv.setItem(49, btn)

        addBackButton(inv, 45, "CATEGORY:${recipe.category.name}")

        player.openInventory(inv)
    }

    private fun addBackButton(inv: Inventory, slot: Int, targetType: String) {
        val back = ItemStack(Material.ARROW)
        val meta = back.itemMeta
        meta.displayName(Component.text("§7返回").decoration(TextDecoration.ITALIC, false))

        // [核心修改] 替换为主插件实例
        meta.persistentDataContainer.set(
            NamespacedKey(PanlingBasic.instance, "ui_back_target"),
            PersistentDataType.STRING,
            targetType
        )

        back.itemMeta = meta
        inv.setItem(slot, back)
    }

    private fun countItem(player: Player, itemId: String): Int {
        var count = 0
        // getContents 可能会包含 null，但在 Kotlin 中处理 Array<ItemStack?>
        for (item in player.inventory.contents) {
            if (item != null && item.hasItemMeta()) {
                val id = item.itemMeta.persistentDataContainer
                    .get(BasicKeys.ITEM_ID, PersistentDataType.STRING)
                if (itemId == id) count += item.amount
            }
        }
        return count
    }

    class ForgeHolder(val type: String) : InventoryHolder {
        override fun getInventory(): Inventory {
            throw UnsupportedOperationException("ForgeHolder does not hold the inventory instance directly.")
        }
    }
}