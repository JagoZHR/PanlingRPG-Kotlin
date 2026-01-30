package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonTemplate
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

class DungeonEntryUI(private val plugin: PanlingBasic) {

    private val dungeonKey = NamespacedKey(plugin, "ui_dungeon_id")

    fun open(player: Player, templateId: String) {
        val template = plugin.dungeonManager.getTemplate(templateId) ?: return
        val inv = Bukkit.createInventory(EntryHolder(templateId), 27, Component.text("§8副本挑战: ${template.displayName}"))

        // 1. 中心图标：显示副本信息
        val infoIcon = ItemStack(Material.PAPER) // 或者用 template.iconMaterial
        val meta = infoIcon.itemMeta
        meta.displayName(Component.text(template.displayName).color(NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))

        val lore = ArrayList<Component>()
        lore.add(Component.text("§7------------------------"))
        lore.add(Component.text("§7等级限制: §f${template.minLevel}级"))
        lore.add(Component.text("§7人数限制: §f${template.minPlayers}-${template.maxPlayers}人"))
        lore.add(Component.text("§7时间限制: §f${template.timeLimit / 60}分钟"))
        lore.add(Component.text("§7------------------------"))
        lore.add(Component.empty())

        // 动态检查玩家是否满足条件
        val level = plugin.playerDataManager.getLevel(player) // 假设你有这个方法
        val levelCheck = if (level >= template.minLevel) "§a✔ 满足" else "§c✘ 不足"
        lore.add(Component.text("§7你的等级: $level $levelCheck"))

        meta.lore(lore)
        infoIcon.itemMeta = meta
        inv.setItem(13, infoIcon)

        // 2. 确认按钮
        val startBtn = ItemStack(Material.EMERALD_BLOCK)
        val btnMeta = startBtn.itemMeta
        btnMeta.displayName(Component.text("§a§l开始挑战").decoration(TextDecoration.ITALIC, false))
        btnMeta.persistentDataContainer.set(dungeonKey, PersistentDataType.STRING, templateId)
        startBtn.itemMeta = btnMeta
        inv.setItem(22, startBtn)

        // 3. (可选) 装饰板 - 这里可以放掉落物预览
        // 假设你知道这个副本掉什么，可以手动在这里 hardcode 几个展示品
        // 比如展示 "sword_t2_dragon"
        val displaySlots = listOf(10, 11, 15, 16)
        if (templateId == "trial_wood") {
            val drops = listOf("sword_t2_dragon", "bow_t2_elven", "armor_t2_star", "material_wood_essence")
            drops.forEachIndexed { index, itemId ->
                if (index < displaySlots.size) {
                    val item = plugin.itemManager.createItem(itemId, player)
                    if (item != null) inv.setItem(displaySlots[index], item)
                }
            }
        }

        player.openInventory(inv)
    }

    class EntryHolder(val dungeonId: String) : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }
}