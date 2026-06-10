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
    private val subKey: NamespacedKey
    private val pageKey: NamespacedKey

    private val SLOTS_27 = intArrayOf(10, 11, 12, 13, 14, 15, 16)
    private val LIST_SLOTS = intArrayOf(
        10, 12, 14, 16,
        19, 21, 23, 25,
        28, 30, 32, 34,
        37, 39, 41, 43
    )
    private val PAGE_SIZE = LIST_SLOTS.size // 16 per page

    private data class SlotIcon(val slot: String, val warriorMat: Material, val archerMat: Material, val mageMat: Material, val label: String)
    private val armorSlots = listOf(
        SlotIcon("helmet",   Material.IRON_HELMET,      Material.GOLDEN_HELMET,      Material.CHAINMAIL_HELMET,      "§b头盔"),
        SlotIcon("chest",    Material.IRON_CHESTPLATE,   Material.GOLDEN_CHESTPLATE,   Material.CHAINMAIL_CHESTPLATE,   "§b胸甲"),
        SlotIcon("leggings", Material.IRON_LEGGINGS,     Material.GOLDEN_LEGGINGS,     Material.CHAINMAIL_LEGGINGS,     "§b护腿"),
        SlotIcon("boots",    Material.IRON_BOOTS,        Material.GOLDEN_BOOTS,        Material.CHAINMAIL_BOOTS,        "§b靴子"),
    )

    init {
        val instance = PanlingBasic.instance
        this.recipeKey = NamespacedKey(instance, "forge_rid")
        this.subKey = NamespacedKey(instance, "forge_sub")
        this.pageKey = NamespacedKey(instance, "forge_page")
    }

    // === Level 1: 主菜单 ===
    fun openMainMenu(player: Player) {
        val inv = Bukkit.createInventory(ForgeHolder("MAIN"), 27, Component.text("§8❖ 锻造台 ❖"))
        var idx = 0
        for (cat in ForgeCategory.values()) {
            if (idx >= SLOTS_27.size) break
            val icon = ItemStack(cat.icon)
            val meta = icon.itemMeta
            meta.displayName(Component.text(cat.displayName).color(NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            val lore = ArrayList<Component>()
            lore.add(Component.text("----------------").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("点击选择").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
            meta.persistentDataContainer.set(subKey, PersistentDataType.STRING, cat.name)
            icon.itemMeta = meta
            inv.setItem(SLOTS_27[idx++], icon)
        }
        fillBorder(inv)
        player.openInventory(inv)
    }

    // === 辅助：获取目标物品的 sub_class ===
    private fun getTargetSubClass(recipe: ForgeRecipe): String? {
        return manager.itemManager.getTemplate(recipe.targetItemId)?.subClass
    }
    private fun getTargetRarityWeight(recipe: ForgeRecipe): Int {
        return manager.itemManager.getTemplate(recipe.targetItemId)?.rarity?.weight ?: 0
    }

    // === Level 2: 子菜单 ===
    fun openSubMenu(player: Player, category: ForgeCategory) {
        val playerClass = manager.playerDataManager.getPlayerClass(player)
        when (category) {
            ForgeCategory.WEAPON -> {
                if (playerClass == PlayerClass.MAGE) openRecipeList(player, category, null, 0)
                else openSubclassMenu(player, playerClass)
            }
            ForgeCategory.ARMOR -> openSlotMenu(player, playerClass)
            else -> openRecipeList(player, category, null, 0)
        }
    }

    private fun openSubclassMenu(player: Player, playerClass: PlayerClass) {
        val pairs = when (playerClass) {
            PlayerClass.WARRIOR -> listOf(
                "PO_JUN" to Pair("§c破军", Material.STONE_AXE),
                "GOLDEN_BELL" to Pair("§e金钟", Material.GOLDEN_SWORD)
            )
            PlayerClass.ARCHER -> listOf(
                "SNIPER" to Pair("§9狙击", Material.BOW),
                "RANGER" to Pair("§a游侠", Material.CROSSBOW)
            )
            else -> return
        }
        val inv = Bukkit.createInventory(ForgeHolder("SUBCLASS"), 27, Component.text("§8❖ 选择流派 ❖"))
        for (i in pairs.indices) {
            val (subId, pair) = pairs[i]
            val icon = ItemStack(pair.second)
            val meta = icon.itemMeta
            meta.displayName(Component.text(pair.first).color(NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            meta.persistentDataContainer.set(subKey, PersistentDataType.STRING, subId)
            icon.itemMeta = meta
            inv.setItem(SLOTS_27[i], icon)
        }
        addBackButton(inv, 18, "MAIN")
        fillBorder(inv)
        player.openInventory(inv)
    }

    private fun openSlotMenu(player: Player, playerClass: PlayerClass) {
        val inv = Bukkit.createInventory(ForgeHolder("SLOT"), 27, Component.text("§8❖ 选择部位 ❖"))
        for (i in armorSlots.indices) {
            val s = armorSlots[i]
            val mat = when (playerClass) {
                PlayerClass.WARRIOR -> s.warriorMat
                PlayerClass.ARCHER -> s.archerMat
                else -> s.mageMat
            }
            val icon = ItemStack(mat)
            val meta = icon.itemMeta
            meta.displayName(Component.text(s.label).color(NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            meta.persistentDataContainer.set(subKey, PersistentDataType.STRING, s.slot)
            icon.itemMeta = meta
            inv.setItem(SLOTS_27[i], icon)
        }
        addBackButton(inv, 18, "MAIN")
        fillBorder(inv)
        player.openInventory(inv)
    }

    // === Level 3: 配方列表（分页） ===
    fun openRecipeList(player: Player, category: ForgeCategory, subFilter: String?, page: Int) {
        val playerClass = manager.playerDataManager.getPlayerClass(player)
        val dataManager = manager.playerDataManager
        val allRecipes = manager.getRecipesByCategory(category)
        val subLabel = subFilter ?: ""

        // 过滤
        val filtered = allRecipes.filter { recipe ->
            if (recipe.tier == 0 && recipe.sub == null) return@filter false  // 只过滤非转换的无阶配方
            if (recipe.requiresUnlock && !dataManager.hasUnlockedRecipe(player, recipe.id)) return@filter false
            // 职业过滤
            val reqClassStr = manager.itemManager.getTemplate(recipe.targetItemId)?.reqClass
            if (reqClassStr != null) {
                try {
                    val itemClass = PlayerClass.valueOf(reqClassStr)
                    if (itemClass != PlayerClass.NONE && itemClass != playerClass) return@filter false
                } catch (ignored: Exception) {}
            }
            // 子过滤
            if (subFilter != null) {
                when (category) {
                    ForgeCategory.WEAPON -> recipe.sub == subFilter
                    ForgeCategory.ARMOR -> recipe.slot == subFilter
                    else -> false
                }
            } else true
        }.sortedBy { getTargetRarityWeight(it) }

        // 分页
        val totalPages = ((filtered.size - 1) / PAGE_SIZE) + 1
        val clampedPage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        val pageRecipes = filtered.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        val title = if (subFilter != null)
            "§8❖ ${category.displayName} · $subLabel §7(${clampedPage + 1}/$totalPages) ❖"
        else "§8❖ ${category.displayName} §7(${clampedPage + 1}/$totalPages) ❖"

        val inv = Bukkit.createInventory(ForgeHolder("LIST:${category.name}:${subFilter ?: ""}:$clampedPage"), 54, Component.text(title))

        // 配方排列
        for (i in pageRecipes.indices) {
            if (i >= LIST_SLOTS.size) break
            val recipe = pageRecipes[i]
            var icon = manager.itemManager.createItem(recipe.targetItemId, player) ?: ItemStack(Material.BARRIER).also {
                val err = it.itemMeta
                err.displayName(Component.text("缺失").color(NamedTextColor.RED))
                it.itemMeta = err
            }
            val meta = icon.itemMeta
            meta.persistentDataContainer.set(recipeKey, PersistentDataType.STRING, recipe.id)
            val lore = meta.lore() ?: ArrayList()
            lore.add(Component.text("==================").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("▶ 点击查看锻造详情").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
            icon.itemMeta = meta
            inv.setItem(LIST_SLOTS[i], icon)
        }

        // 翻页按钮
        if (clampedPage > 0) {
            val prev = ItemStack(Material.ARROW)
            val pm = prev.itemMeta
            pm.displayName(Component.text("§a◀ 上一页").decoration(TextDecoration.ITALIC, false))
            pm.persistentDataContainer.set(pageKey, PersistentDataType.INTEGER, clampedPage - 1)
            prev.itemMeta = pm
            inv.setItem(48, prev)
        }
        if (clampedPage < totalPages - 1) {
            val next = ItemStack(Material.ARROW)
            val nm = next.itemMeta
            nm.displayName(Component.text("§a下一页 ▶").decoration(TextDecoration.ITALIC, false))
            nm.persistentDataContainer.set(pageKey, PersistentDataType.INTEGER, clampedPage + 1)
            next.itemMeta = nm
            inv.setItem(50, next)
        }

        addBackButton(inv, 45, "MAIN")
        fillBorder(inv)
        player.openInventory(inv)
    }

    // === Level 4: 详情页 ===
    fun openDetailMenu(player: Player, recipe: ForgeRecipe, parentState: String) {
        val inv = Bukkit.createInventory(ForgeHolder("DETAIL:${recipe.id}"), 54, Component.text("§8❖ 锻造详情 ❖"))
        val itemManager = manager.itemManager

        val result = itemManager.createItem(recipe.targetItemId, player)
        if (result != null) inv.setItem(13, result)

        val matSlots = getCenteredMaterialSlots(recipe.materials.size)
        var matIdx = 0
        for ((key, value) in recipe.materials) {
            if (matIdx >= matSlots.size) break
            var matIcon = itemManager.createItem(key, player) ?: ItemStack(Material.PAPER)
            val meta = matIcon.itemMeta
            val lore = ArrayList<Component>()
            val has = countItem(player, key)
            lore.add(Component.text("----------------").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("需要数量: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false).append(Component.text(value.toString()).color(NamedTextColor.WHITE)))
            val cc = if (has >= value) NamedTextColor.GREEN else NamedTextColor.RED
            lore.add(Component.text("当前拥有: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false).append(Component.text(has.toString()).color(cc)))
            meta.lore(lore)
            matIcon.itemMeta = meta
            matIcon.amount = value.coerceIn(1, 64)
            inv.setItem(matSlots[matIdx++], matIcon)
        }

        val btn = ItemStack(Material.ANVIL)
        val btnMeta = btn.itemMeta
        btnMeta.displayName(Component.text("✔ 确认锻造").color(NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
        val lore = ArrayList<Component>()
        val balance = manager.playerDataManager.getMoney(player)
        val cost = recipe.cost
        val sCost = recipe.spiritCost
        val sBal = manager.playerDataManager.getElementPoints(player)
        lore.add(Component.text("----------------").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
        if (cost > 0) {
            val cc = if (balance >= cost) NamedTextColor.YELLOW else NamedTextColor.RED
            lore.add(Component.text("消耗铜钱: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false).append(Component.text(String.format("%.0f", cost)).color(cc)))
            lore.add(Component.text("当前余额: " + String.format("%.0f", balance)).color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
        } else { lore.add(Component.text("免费锻造").color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)) }
        if (sCost > 0) {
            val sc = if (sBal >= sCost) NamedTextColor.AQUA else NamedTextColor.RED
            lore.add(Component.text("消耗灵力: ").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false).append(Component.text(sCost.toString()).color(sc)))
            lore.add(Component.text("当前灵力: $sBal").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
        }
        lore.add(Component.empty())
        lore.add(Component.text("▶ 点击左键立即锻造").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
        btnMeta.lore(lore)
        btn.itemMeta = btnMeta
        inv.setItem(49, btn)
        addBackButton(inv, 45, parentState)
        fillBorder(inv)
        player.openInventory(inv)
    }

    // === UI 辅助 ===
    private fun fillBorder(inv: Inventory) {
        val glass = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val meta = glass.itemMeta; meta.displayName(Component.empty()); glass.itemMeta = meta
        for (i in 0 until inv.size) {
            if (i < 9 || i >= inv.size - 9 || i % 9 == 0 || i % 9 == 8) {
                if (inv.getItem(i) == null) inv.setItem(i, glass)
            }
        }
    }
    private fun getCenteredMaterialSlots(count: Int): IntArray {
        val sc = count.coerceAtMost(14)
        return if (sc <= 7) getRowSlots(sc, 31) else getRowSlots((sc + 1) / 2, 31) + getRowSlots(sc / 2, 40)
    }
    private fun getRowSlots(count: Int, center: Int): IntArray = when (count) {
        1 -> intArrayOf(center)
        2 -> intArrayOf(center - 1, center + 1)
        3 -> intArrayOf(center - 1, center, center + 1)
        4 -> intArrayOf(center - 2, center - 1, center + 1, center + 2)
        5 -> intArrayOf(center - 2, center - 1, center, center + 1, center + 2)
        6 -> intArrayOf(center - 3, center - 2, center - 1, center + 1, center + 2, center + 3)
        else -> intArrayOf(center - 3, center - 2, center - 1, center, center + 1, center + 2, center + 3)
    }
    private fun addBackButton(inv: Inventory, slot: Int, targetType: String) {
        val back = ItemStack(Material.ARROW)
        val meta = back.itemMeta
        meta.displayName(Component.text("⬅ 返回上一页").color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
        meta.persistentDataContainer.set(NamespacedKey(PanlingBasic.instance, "ui_back_target"), PersistentDataType.STRING, targetType)
        back.itemMeta = meta
        inv.setItem(slot, back)
    }
    private fun countItem(player: Player, itemId: String): Int {
        var c = 0
        for (item in player.inventory.contents) {
            if (item != null && item.hasItemMeta()) {
                if (itemId == item.itemMeta.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING)) c += item.amount
            }
        }
        return c
    }

    class ForgeHolder(val type: String) : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }
}
