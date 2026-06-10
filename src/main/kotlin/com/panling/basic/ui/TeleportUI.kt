package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.manager.TeleportManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * 传送 UI — 分页显示已解锁的传送点。
 *
 * 布局：54 格
 *   行 0-4 (0-44)：传送点条目（诡异菌钓竿）
 *   行 5 (45-53)：导航栏
 */
class TeleportUI(private val plugin: PanlingBasic) : Listener {

    private companion object {
        const val ROWS = 6
        const val SIZE = ROWS * 9
        const val ENTRY_SLOTS = (ROWS - 1) * 9

        const val BTN_PREV = 47
        const val BTN_BACK = 48
        const val BTN_PAGE = 49
        const val BTN_NEXT = 50
    }

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun open(player: Player, page: Int = 0) {
        val allPoints = plugin.playerDataManager.getUnlockedTeleports(player)
            .mapNotNull { plugin.teleportManager.getPoints()[it] }  // 按解锁顺序
        val totalPages = if (allPoints.isEmpty()) 1 else (allPoints.size - 1) / ENTRY_SLOTS + 1
        val safePage = page.coerceIn(0, totalPages - 1)
        val startIdx = safePage * ENTRY_SLOTS
        val endIdx = (startIdx + ENTRY_SLOTS).coerceAtMost(allPoints.size)
        val pagePoints = allPoints.subList(startIdx, endIdx)
        val elements = plugin.playerDataManager.getElementPoints(player)

        val inv = Bukkit.createInventory(
            TeleportHolder(safePage, totalPages),
            SIZE,
            Component.text("§8传送 — 灵力: $elements — 第 ${safePage + 1}/$totalPages 页")
        )

        // 背景
        val glass = ItemStack(Material.BROWN_STAINED_GLASS_PANE)
        val gm = glass.itemMeta; gm.displayName(Component.empty()); glass.itemMeta = gm
        for (i in 0 until SIZE) inv.setItem(i, glass)

        // 条目
        for ((i, point) in pagePoints.withIndex()) {
            inv.setItem(i, buildPointItem(point, player))
        }

        // 导航
        drawNavButtons(inv, safePage, totalPages)

        player.openInventory(inv)
    }

    private fun buildPointItem(point: TeleportManager.TeleportPoint, player: Player): ItemStack {
        val item = ItemStack(Material.WARPED_FUNGUS_ON_A_STICK)
        val meta = item.itemMeta
        meta.displayName(Component.text("§b§l${point.name}").decoration(TextDecoration.ITALIC, false))

        val lore = listOf(
            Component.text("§7消耗: §e${point.cost} 灵力").decoration(TextDecoration.ITALIC, false),
            Component.text("§7坐标: §8${point.location.blockX}, ${point.location.blockY}, ${point.location.blockZ}")
                .decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("§a点击传送！").decoration(TextDecoration.ITALIC, false)
        )
        meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    private fun drawNavButtons(inv: Inventory, page: Int, total: Int) {
        val glass = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val gm = glass.itemMeta; gm.displayName(Component.empty()); glass.itemMeta = gm
        for (i in intArrayOf(45, 46, 51, 52, 53)) inv.setItem(i, glass)

        if (page > 0) {
            val prev = ItemStack(Material.ARROW)
            val pm = prev.itemMeta; pm.displayName(Component.text("§e← 上一页").decoration(TextDecoration.ITALIC, false)); prev.itemMeta = pm
            inv.setItem(BTN_PREV, prev)
        }

        val back = ItemStack(Material.BARRIER)
        val bm = back.itemMeta; bm.displayName(Component.text("§c返回主菜单").decoration(TextDecoration.ITALIC, false)); back.itemMeta = bm
        inv.setItem(BTN_BACK, back)

        val pageItem = ItemStack(Material.PAPER)
        val pm2 = pageItem.itemMeta; pm2.displayName(Component.text("§7第 ${page + 1} / $total 页").decoration(TextDecoration.ITALIC, false)); pageItem.itemMeta = pm2
        inv.setItem(BTN_PAGE, pageItem)

        if (page < total - 1) {
            val next = ItemStack(Material.ARROW)
            val nm = next.itemMeta; nm.displayName(Component.text("§e下一页 →").decoration(TextDecoration.ITALIC, false)); next.itemMeta = nm
            inv.setItem(BTN_NEXT, next)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? TeleportHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.view.topInventory) return

        when (event.slot) {
            BTN_PREV -> open(player, holder.page - 1)
            BTN_BACK -> plugin.menuManager.openMenu(player)
            BTN_NEXT -> open(player, holder.page + 1)
            in 0 until ENTRY_SLOTS -> {
                val allPoints = plugin.playerDataManager.getUnlockedTeleports(player)
                    .mapNotNull { plugin.teleportManager.getPoints()[it] }
                val idx = holder.page * ENTRY_SLOTS + event.slot
                if (idx < allPoints.size) {
                    val point = allPoints[idx]
                    player.closeInventory()
                    plugin.teleportManager.teleport(player, point.id)
                }
            }
        }
    }

    private class TeleportHolder(val page: Int, val totalPages: Int) : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException("marker")
    }
}
