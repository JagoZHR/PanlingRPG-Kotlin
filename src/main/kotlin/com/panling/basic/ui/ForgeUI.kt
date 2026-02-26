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

    private val recipeKey: NamespacedKey
    private val categoryKey: NamespacedKey

    // 定义类别和配方列表的安全内容展示区 (避开边缘)
    private val CONTENT_SLOTS = intArrayOf(
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    )

    init {
        val instance = PanlingBasic.instance
        this.recipeKey = NamespacedKey(instance, "forge_rid")
        this.categoryKey = NamespacedKey(instance, "forge_cat")
    }

    // === Level 1: 主分类菜单 ===
    fun openMainMenu(player: Player) {
        val inv = Bukkit.createInventory(ForgeHolder("MAIN"), 27, Component.text("§8❖ 锻造台 ❖"))

        var index = 0
        // 将类别显示在中间行 (10-16)
        for (cat in ForgeCategory.values()) {
            if (index >= 7) break

            val icon = ItemStack(cat.icon)
            val meta = icon.itemMeta

            meta.displayName(
                Component.text(cat.displayName)
                    .color(NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false)
            )

            val lore = ArrayList<Component>()
            lore.add(Component.text("----------------").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("点击查看该分类下的所有配方").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)

            meta.persistentDataContainer.set(categoryKey, PersistentDataType.STRING, cat.name)
            icon.itemMeta = meta

            inv.setItem(CONTENT_SLOTS[index++], icon)
        }

        fillBorder(inv)
        player.openInventory(inv)
    }

    // === Level 2: 分类下的配方列表 ===
    fun openCategoryMenu(player: Player, category: ForgeCategory) {
        val inv = Bukkit.createInventory(
            ForgeHolder("CATEGORY:${category.name}"),
            54,
            Component.text("§8❖ 锻造图鉴 - ${category.displayName} ❖")
        )
        val itemManager = manager.itemManager
        val dataManager = manager.playerDataManager

        val recipes = manager.getRecipesByCategory(category)
        val playerClass = dataManager.getPlayerClass(player)

        var slotIndex = 0
        for (recipe in recipes) {
            if (slotIndex >= CONTENT_SLOTS.size) break // 防止超出页面最大容量

            // 配方可见性检查
            if (recipe.requiresUnlock && !dataManager.hasUnlockedRecipe(player, recipe.id)) {
                continue
            }

            var icon = itemManager.createItem(recipe.targetItemId, player)
            if (icon == null) {
                icon = ItemStack(Material.BARRIER)
                val err = icon.itemMeta
                err.displayName(Component.text("配置缺失: ${recipe.targetItemId}").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                icon.itemMeta = err
            }

            // 职业过滤
            val iconMeta = icon!!.itemMeta
            val reqClassStr = iconMeta.persistentDataContainer.get(BasicKeys.FEATURE_REQ_CLASS, PersistentDataType.STRING)
            if (reqClassStr != null) {
                try {
                    val itemClass = PlayerClass.valueOf(reqClassStr)
                    if (itemClass != PlayerClass.NONE && itemClass != playerClass) continue
                } catch (ignored: Exception) {}
            }

            // 写入 UI 数据并追加底部提示
            val meta = icon.itemMeta
            meta.persistentDataContainer.set(recipeKey, PersistentDataType.STRING, recipe.id)

            val lore = meta.lore() ?: ArrayList()
            // 使用明显的分割线替代不可见的空行，彻底解决排版错乱感
            lore.add(Component.text("==================").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("▶ 点击查看锻造详情").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)

            icon.itemMeta = meta
            inv.setItem(CONTENT_SLOTS[slotIndex++], icon)
        }

        addBackButton(inv, 45, "MAIN")
        fillBorder(inv)
        player.openInventory(inv)
    }

    // === Level 3: 详情页 ===
    fun openDetailMenu(player: Player, recipe: ForgeRecipe) {
        val inv = Bukkit.createInventory(
            ForgeHolder("DETAIL:${recipe.id}"),
            54,
            Component.text("§8❖ 锻造详情 ❖")
        )
        val itemManager = manager.itemManager

        // 1. 产物展示 (位于上方中央)
        val result = itemManager.createItem(recipe.targetItemId, player)
        if (result != null) inv.setItem(13, result)

        // 2. 材料需求展示 (根据材料数量动态居中排列)
        val matSlots = getCenteredMaterialSlots(recipe.materials.size)
        var matIndex = 0

        for ((key, value) in recipe.materials) {
            if (matIndex >= matSlots.size) break

            var matIcon = itemManager.createItem(key, player)
            if (matIcon == null) matIcon = ItemStack(Material.PAPER)

            val meta = matIcon.itemMeta
            val lore = ArrayList<Component>()
            val has = countItem(player, key)

            lore.add(Component.text("----------------").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
            lore.add(
                Component.text("需要数量: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(value.toString()).color(NamedTextColor.WHITE))
            )

            val countColor = if (has >= value) NamedTextColor.GREEN else NamedTextColor.RED
            lore.add(
                Component.text("当前拥有: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(has.toString()).color(countColor))
            )

            meta.lore(lore)
            matIcon.itemMeta = meta
            matIcon.amount = if (value in 1..64) value else 1 // 图标数量直观显示

            inv.setItem(matSlots[matIndex++], matIcon)
        }

        // 3. 锻造按钮 (位于下方中央)
        val btn = ItemStack(Material.ANVIL)
        val meta = btn.itemMeta
        meta.displayName(Component.text("✔ 确认锻造").color(NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))

        val lore = ArrayList<Component>()
        val balance = manager.playerDataManager.getMoney(player)
        val cost = recipe.cost

        lore.add(Component.text("----------------").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
        if (cost > 0) {
            val costColor = if (balance >= cost) NamedTextColor.YELLOW else NamedTextColor.RED
            lore.add(
                Component.text("消耗铜钱: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(String.format("%.0f", cost)).color(costColor))
            )
            lore.add(Component.text("当前余额: " + String.format("%.0f", balance)).color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
        } else {
            lore.add(Component.text("免费锻造").color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
        }
        lore.add(Component.empty())
        lore.add(Component.text("▶ 点击左键立即锻造").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))

        meta.lore(lore)
        btn.itemMeta = meta
        inv.setItem(49, btn) // 40 是第 5 行的正中间

        addBackButton(inv, 45, "CATEGORY:${recipe.category.name}")
        fillBorder(inv)

        player.openInventory(inv)
    }

    // === UI 辅助方法 ===

    // 将四周一圈填满黑色玻璃板，营造高级感
    private fun fillBorder(inv: Inventory) {
        val glass = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val meta = glass.itemMeta
        meta.displayName(Component.empty())
        glass.itemMeta = meta

        val size = inv.size
        for (i in 0 until size) {
            // 顶行、底行、左列、右列
            if (i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, glass)
                }
            }
        }
    }

    // 根据材料种类数量，计算出完美居中且对称的槽位 (支持最多14种材料)
    private fun getCenteredMaterialSlots(count: Int): IntArray {
        val safeCount = count.coerceAtMost(14) // 保护机制：最多支持14种
        if (safeCount <= 7) {
            // 如果不超过7个，全放在第 4 行 (Center: 31)
            return getRowSlots(safeCount, 31)
        } else {
            // 如果超过7个，平分到第 4 行 (31) 和第 5 行 (40)
            // 比如 9 个 -> 上面 5 个，下面 4 个，依然绝对居中对称
            val topCount = Math.ceil(safeCount / 2.0).toInt()
            val bottomCount = safeCount - topCount
            return getRowSlots(topCount, 31) + getRowSlots(bottomCount, 40)
        }
    }

    // 单行居中排版算法
    private fun getRowSlots(count: Int, center: Int): IntArray {
        return when (count) {
            1 -> intArrayOf(center)
            2 -> intArrayOf(center - 1, center + 1)
            3 -> intArrayOf(center - 1, center, center + 1)
            4 -> intArrayOf(center - 2, center - 1, center + 1, center + 2)
            5 -> intArrayOf(center - 2, center - 1, center, center + 1, center + 2)
            6 -> intArrayOf(center - 3, center - 2, center - 1, center + 1, center + 2, center + 3)
            else -> intArrayOf(center - 3, center - 2, center - 1, center, center + 1, center + 2, center + 3) // 7个撑满
        }
    }

    private fun addBackButton(inv: Inventory, slot: Int, targetType: String) {
        val back = ItemStack(Material.ARROW)
        val meta = back.itemMeta
        meta.displayName(Component.text("⬅ 返回上一页").color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))

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
            throw UnsupportedOperationException("ForgeHolder 不直接持有 Inventory 实例")
        }
    }
}