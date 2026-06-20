package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.io.File

/**
 * Boss 图鉴 UI — 分页显示野外 Boss 位置，左键追踪坐标。
 *
 * 布局：54 格
 *   行 0-4 (0-44)：Boss 卡片
 *   行 5 (45-53)：导航栏
 */
class BossGuideUI(private val plugin: PanlingBasic) : Listener {

    private companion object {
        const val ROWS = 6
        const val SIZE = ROWS * 9
        const val ENTRY_SLOTS = (ROWS - 1) * 9

        const val BTN_PREV = 47
        const val BTN_BACK = 48
        const val BTN_PAGE = 49
        const val BTN_NEXT = 50
    }

    data class BossEntry(
        val id: String,
        val bossName: String,
        val region: String,
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val level: Int,
        val material: Material,
        val description: String
    )

    private val entries: List<BossEntry> by lazy { loadEntries() }

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    private fun loadEntries(): List<BossEntry> {
        val file = File(plugin.dataFolder, "boss_guide.yml")
        if (!file.exists()) {
            plugin.logger.warning("[BossGuideUI] boss_guide.yml not found!")
            return emptyList()
        }
        val config = YamlConfiguration.loadConfiguration(file)
        val list = config.getList("bosses") ?: return emptyList()

        return list.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            try {
                BossEntry(
                    id = map["id"] as? String ?: return@mapNotNull null,
                    bossName = map["boss_name"] as? String ?: "?",
                    region = map["region"] as? String ?: "?",
                    world = map["world"] as? String ?: "world",
                    x = (map["x"] as? Number)?.toDouble() ?: 0.0,
                    y = (map["y"] as? Number)?.toDouble() ?: 0.0,
                    z = (map["z"] as? Number)?.toDouble() ?: 0.0,
                    level = (map["level"] as? Number)?.toInt() ?: 1,
                    material = try { Material.valueOf(map["material"] as? String ?: "BARRIER") }
                        catch (_: Exception) { Material.BARRIER },
                    description = map["description"] as? String ?: ""
                )
            } catch (e: Exception) {
                plugin.logger.warning("[BossGuideUI] Failed to parse entry: ${map["id"]} — ${e.message}")
                null
            }
        }
    }

    fun open(player: Player, page: Int = 0) {
        val all = entries
        val totalPages = if (all.isEmpty()) 1 else (all.size - 1) / ENTRY_SLOTS + 1
        val safePage = page.coerceIn(0, totalPages - 1)
        val startIdx = safePage * ENTRY_SLOTS
        val endIdx = (startIdx + ENTRY_SLOTS).coerceAtMost(all.size)
        val pageEntries = all.subList(startIdx, endIdx)

        val inv = Bukkit.createInventory(
            BossGuideHolder(safePage, totalPages),
            SIZE,
            Component.text("§8Boss 图鉴 — 第 ${safePage + 1}/$totalPages 页")
        )

        // 背景
        val glass = ItemStack(Material.BROWN_STAINED_GLASS_PANE)
        val gm = glass.itemMeta; gm.displayName(Component.empty()); glass.itemMeta = gm
        for (i in 0 until SIZE) inv.setItem(i, glass)

        // 条目
        for ((i, entry) in pageEntries.withIndex()) {
            inv.setItem(i, buildBossItem(entry, player))
        }

        // 导航
        drawNavButtons(inv, safePage, totalPages)

        player.openInventory(inv)
    }

    private fun buildBossItem(entry: BossEntry, player: Player): ItemStack {
        val item = ItemStack(entry.material)
        val meta = item.itemMeta
        meta.displayName(Component.text("${entry.bossName.replace('&', '§')} §7[Lv.${entry.level}]").decoration(TextDecoration.ITALIC, false))

        val loc = Location(Bukkit.getWorld(entry.world), entry.x, entry.y, entry.z)
        val dist = if (player.world.name == entry.world)
            loc.distance(player.location).toInt()
        else -1

        val lore = mutableListOf<Component>()
        lore.add(Component.text("§7区域：${entry.region}").decoration(TextDecoration.ITALIC, false))
        lore.add(Component.text("§7坐标：§8${entry.x.toInt()}, ${entry.y.toInt()}, ${entry.z.toInt()}").decoration(TextDecoration.ITALIC, false))
        if (dist >= 0) {
            lore.add(Component.text("§7距离：§e${dist}m").decoration(TextDecoration.ITALIC, false))
        } else {
            lore.add(Component.text("§7§o(不同世界)").decoration(TextDecoration.ITALIC, false))
        }
        lore.add(Component.empty())
        lore.add(Component.text("§7\"${entry.description}\"").decoration(TextDecoration.ITALIC, false))
        lore.add(Component.empty())
        lore.add(Component.text("§e点击追踪 → 指南针指向此处").decoration(TextDecoration.ITALIC, false))

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
        val holder = event.inventory.holder as? BossGuideHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.view.topInventory) return

        when (event.slot) {
            BTN_PREV -> open(player, holder.page - 1)
            BTN_BACK -> plugin.menuManager.openMenu(player)
            BTN_NEXT -> open(player, holder.page + 1)
            in 0 until ENTRY_SLOTS -> {
                val idx = holder.page * ENTRY_SLOTS + event.slot
                if (idx < entries.size) {
                    trackBoss(player, entries[idx])
                }
            }
        }
    }

    private fun trackBoss(player: Player, entry: BossEntry) {
        val world = Bukkit.getWorld(entry.world) ?: run {
            player.sendMessage("§c错误：找不到世界 ${entry.world}")
            return
        }
        val loc = Location(world, entry.x, entry.y, entry.z)
        player.compassTarget = loc
        val dist = loc.distance(player.location).toInt()
        player.sendMessage("§a[Boss追踪] §7指南针已指向：§f${entry.bossName}")
        player.sendMessage("§7目标坐标：${loc.blockX}, ${loc.blockY}, ${loc.blockZ}  §7(约 ${dist}m)")
        player.playSound(player.location, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1f, 1f)
        player.closeInventory()
    }

    private class BossGuideHolder(val page: Int, val totalPages: Int) : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException("marker")
    }
}
