package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.manager.ChangelogManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/**
 * 更新公告 UI。
 *
 * 布局：54 格
 *   行 0-4 (0-44)：公告条目（烈焰粉，按时间倒序）
 *   行 5 (45-53)：导航栏
 *     45-46: 装饰玻璃
 *     47: ← 上一页
 *     48: 返回主菜单
 *     49: 页数指示
 *     50: 下一页 →
 *     51-53: 装饰玻璃
 */
class ChangelogUI(private val plugin: PanlingBasic) : Listener {

    companion object {
        private const val ROWS = 6
        private const val SIZE = ROWS * 9        // 54
        private const val ENTRY_SLOTS = (ROWS - 1) * 9  // 45 entries per page

        private const val BTN_PREV  = 47
        private const val BTN_BACK  = 48
        private const val BTN_PAGE  = 49
        private const val BTN_NEXT  = 50
    }

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun open(player: Player, page: Int = 0) {
        val entries = plugin.changelogManager.getEntries()
        val totalPages = if (entries.isEmpty()) 1 else (entries.size - 1) / ENTRY_SLOTS + 1
        val safePage = page.coerceIn(0, totalPages - 1)
        val startIdx = safePage * ENTRY_SLOTS
        val endIdx = (startIdx + ENTRY_SLOTS).coerceAtMost(entries.size)
        val pageEntries = entries.subList(startIdx, endIdx)

        val inv = Bukkit.createInventory(
            ChangelogHolder(safePage, totalPages),
            SIZE,
            Component.text("§8更新公告 — 第 ${safePage + 1}/$totalPages 页")
        )

        // 背景
        val glass = ItemStack(Material.BROWN_STAINED_GLASS_PANE)
        val gm = glass.itemMeta
        gm.displayName(Component.empty())
        glass.itemMeta = gm
        for (i in 0 until SIZE) inv.setItem(i, glass)

        // 条目
        for ((i, entry) in pageEntries.withIndex()) {
            inv.setItem(i, buildEntryItem(entry))
        }

        // 导航行
        drawNavButtons(inv, safePage, totalPages)

        player.openInventory(inv)
    }

    private fun buildEntryItem(entry: ChangelogManager.Entry): ItemStack {
        val item = ItemStack(Material.BLAZE_POWDER)
        val meta = item.itemMeta
        val title = entry.title

        if (title != null) {
            meta.displayName(Component.text("§6§l$title").decoration(TextDecoration.ITALIC, false))
            val lore = mutableListOf<Component>()
            lore.add(Component.text("§7${entry.dateStr}").decoration(TextDecoration.ITALIC, false))
            lore.add(Component.empty())
            lore.addAll(entry.content.map { Component.text(it).decoration(TextDecoration.ITALIC, false) })
            meta.lore(lore)
        } else {
            meta.displayName(Component.text("§6§l${entry.dateStr}").decoration(TextDecoration.ITALIC, false))
            val lore = entry.content.map {
                Component.text(it).decoration(TextDecoration.ITALIC, false)
            }
            meta.lore(lore)
        }
        item.itemMeta = meta
        return item
    }

    private fun drawNavButtons(inv: Inventory, page: Int, total: Int) {
        // 装饰
        val glass = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val gm = glass.itemMeta; gm.displayName(Component.empty()); glass.itemMeta = gm
        for (i in intArrayOf(45, 46, 51, 52, 53)) inv.setItem(i, glass)

        // 上一页
        if (page > 0) {
            val prev = ItemStack(Material.ARROW)
            val pm = prev.itemMeta
            pm.displayName(Component.text("§e← 上一页").decoration(TextDecoration.ITALIC, false))
            prev.itemMeta = pm
            inv.setItem(BTN_PREV, prev)
        }

        // 返回
        val back = ItemStack(Material.BARRIER)
        val bm = back.itemMeta
        bm.displayName(Component.text("§c返回主菜单").decoration(TextDecoration.ITALIC, false))
        back.itemMeta = bm
        inv.setItem(BTN_BACK, back)

        // 页码
        val pageItem = ItemStack(Material.PAPER)
        val pm2 = pageItem.itemMeta
        pm2.displayName(Component.text("§7第 ${page + 1} / $total 页").decoration(TextDecoration.ITALIC, false))
        pageItem.itemMeta = pm2
        inv.setItem(BTN_PAGE, pageItem)

        // 下一页
        if (page < total - 1) {
            val next = ItemStack(Material.ARROW)
            val nm = next.itemMeta
            nm.displayName(Component.text("§e下一页 →").decoration(TextDecoration.ITALIC, false))
            next.itemMeta = nm
            inv.setItem(BTN_NEXT, next)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? ChangelogHolder ?: return
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.view.topInventory) return

        when (event.slot) {
            BTN_PREV -> open(player, holder.page - 1)
            BTN_BACK -> plugin.menuManager.openMenu(player)
            BTN_NEXT -> open(player, holder.page + 1)
        }
    }

    private class ChangelogHolder(val page: Int, val totalPages: Int) : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException("marker")
    }
}
