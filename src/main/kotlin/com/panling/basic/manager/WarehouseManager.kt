package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.io.IOException
import java.util.*

class WarehouseManager(
    private val plugin: PanlingBasic,
    private val dataManager: PlayerDataManager
) : Listener {

    private val configs = ArrayList<WarehouseConfig>()
    private val warehouseKey = NamespacedKey(plugin, "warehouse_id")

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        // === 仓库配置 ===
        configs.add(WarehouseConfig(0, "§a初级仓库", Material.CHEST, { true }, "§7默认解锁"))
        configs.add(WarehouseConfig(1, "§b进阶仓库", Material.ENDER_CHEST, { p -> p.level >= 10 }, "§c需要等级达到 10 级"))
        configs.add(WarehouseConfig(2, "§6黄金仓库", Material.GOLD_BLOCK, { p -> p.hasPermission("panling.vip") }, "§c需要 VIP 权限"))
        // 你可以在这里无限添加...
    }

    fun openSelectionMenu(player: Player) {
        val inv = Bukkit.createInventory(SelectionHolder(), 27, Component.text("§8随身仓库选择"))

        val glass = ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
        val gm = glass.itemMeta
        gm.displayName(Component.empty())
        glass.itemMeta = gm
        for (i in 0 until 27) inv.setItem(i, glass)

        var slot = 10
        for (config in configs) {
            if (slot >= 17) break

            val isUnlocked = config.condition.check(player)
            val icon = ItemStack(if (isUnlocked) config.icon else Material.BARRIER)
            val meta = icon.itemMeta
            meta.displayName(Component.text(config.name).decoration(TextDecoration.ITALIC, false))

            val lore = ArrayList<Component>()
            lore.add(Component.empty())
            if (isUnlocked) {
                lore.add(Component.text("§a[点击打开]").decoration(TextDecoration.ITALIC, false))
                lore.add(Component.text("§7容量: 54格").decoration(TextDecoration.ITALIC, false))
            } else {
                lore.add(Component.text("§c[未解锁]").decoration(TextDecoration.ITALIC, false))
                lore.add(Component.text(config.lockReason).decoration(TextDecoration.ITALIC, false))
            }
            meta.lore(lore)

            meta.persistentDataContainer.set(warehouseKey, PersistentDataType.INTEGER, config.id)
            icon.itemMeta = meta

            inv.setItem(slot++, icon)
        }

        player.openInventory(inv)
    }

    fun openWarehouse(player: Player, warehouseId: Int) {
        val config = getConfig(warehouseId)
        if (config == null || !config.condition.check(player)) {
            player.sendMessage("§c该仓库尚未解锁！")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        player.sendMessage("§e正在加载仓库数据...")
        // 异步加载
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val contents = loadData(player.uniqueId, warehouseId)

            // 回到主线程打开
            plugin.server.scheduler.runTask(plugin, Runnable {
                // 如果玩家下线了就不打开了
                if (!player.isOnline) return@Runnable

                val inv = Bukkit.createInventory(WarehouseHolder(warehouseId), 54, Component.text(config.name))
                if (contents != null) {
                    inv.contents = contents
                }
                player.openInventory(inv)
                player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 1f, 1f)
            })
        })
    }

    // === [核心修改] 数据存取逻辑 ===

    // 获取特定玩家、特定仓库的文件
    // 路径示例: plugins/PanlingBasic/warehouses/UUID/0.yml
    private fun getDataFile(uuid: UUID, warehouseId: Int): File {
        // 1. 玩家专属文件夹
        val playerFolder = File(plugin.dataFolder, "warehouses/$uuid")
        if (!playerFolder.exists()) {
            playerFolder.mkdirs() // 自动创建文件夹
        }

        // 2. 具体的仓库文件 (直接用 id.yml 命名)
        return File(playerFolder, "$warehouseId.yml")
    }

    private fun loadData(uuid: UUID, warehouseId: Int): Array<ItemStack?>? {
        val file = getDataFile(uuid, warehouseId)
        if (!file.exists()) return null

        val yaml = YamlConfiguration.loadConfiguration(file)
        // 直接读取 items 节点
        val list = yaml.getList("items") ?: return null

        val items = arrayOfNulls<ItemStack>(54)
        for (i in 0 until minOf(list.size, 54)) {
            val item = list[i]
            if (item is ItemStack) {
                items[i] = item
            }
        }
        return items
    }

    private fun saveData(uuid: UUID, warehouseId: Int, contents: Array<ItemStack?>) {
        val file = getDataFile(uuid, warehouseId)
        val yaml = YamlConfiguration.loadConfiguration(file)

        // 存入 items 节点
        yaml.set("items", contents)

        try {
            yaml.save(file)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @EventHandler
    fun onSelectionClick(event: InventoryClickEvent) {
        if (event.inventory.holder is SelectionHolder) {
            event.isCancelled = true
            val current = event.currentItem
            if (current == null || !current.hasItemMeta()) return

            val id = current.itemMeta.persistentDataContainer.get(warehouseKey, PersistentDataType.INTEGER)

            if (id != null) {
                val player = event.whoClicked as? Player ?: return
                openWarehouse(player, id)
            }
        }
    }

    @EventHandler
    fun onWarehouseClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder
        if (holder is WarehouseHolder) {
            val player = event.player as? Player ?: return
            val contents = event.inventory.contents

            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                saveData(player.uniqueId, holder.id, contents)
            })
            player.playSound(player.location, Sound.BLOCK_CHEST_CLOSE, 1f, 1f)
        }
    }

    private fun getConfig(id: Int): WarehouseConfig? {
        return configs.firstOrNull { it.id == id }
    }

    // === 内部类定义 ===

    private data class WarehouseConfig(
        val id: Int,
        val name: String,
        val icon: Material,
        val condition: UnlockCondition,
        val lockReason: String
    )

    fun interface UnlockCondition {
        fun check(player: Player): Boolean
    }

    class SelectionHolder : InventoryHolder {
        override fun getInventory(): Inventory {
            throw UnsupportedOperationException("SelectionHolder is a marker")
        }
    }

    class WarehouseHolder(val id: Int) : InventoryHolder {
        override fun getInventory(): Inventory {
            throw UnsupportedOperationException("WarehouseHolder is a marker")
        }
    }
}