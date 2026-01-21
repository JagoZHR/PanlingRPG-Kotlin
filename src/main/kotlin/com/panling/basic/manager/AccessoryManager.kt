package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.Reloadable
import com.panling.basic.listener.InventoryListener
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import kotlin.math.min

class AccessoryManager(
    private val plugin: JavaPlugin,
    private val dataManager: PlayerDataManager,
    private val itemManager: ItemManager
) : Listener, Reloadable {

    private var inventoryListener: InventoryListener? = null
    private val slotKey = NamespacedKey(plugin, "accessory_slot")

    // 内存缓存：UUID -> 9个格子的物品数组
    private val accessoryCache = HashMap<UUID, Array<ItemStack?>>()

    init {
        // 自动注册重载
        if (plugin is PanlingBasic) {
            try {
                plugin.reloadManager.register(this)
            } catch (ignored: Exception) {}
        }
    }

    override fun reload() {
        clearAllCaches()
    }

    fun setInventoryListener(listener: InventoryListener) {
        this.inventoryListener = listener
    }

    fun invalidateCache(player: Player) {
        accessoryCache.remove(player.uniqueId)
    }

    fun clearAllCaches() {
        accessoryCache.clear()
    }

    fun openAccessoryInventory(player: Player) {
        val inv = Bukkit.createInventory(AccessoryHolder(), 9, Component.text("§8饰品栏 (仅限饰品)"))
        val items = loadAccessories(player)

        // 强制同步属性
        items.filterNotNull().forEach { itemManager.syncItem(it, player) }

        updateGuiLore(items)
        inv.contents = items
        player.openInventory(inv)
    }

    fun loadAccessories(player: Player): Array<ItemStack?> {
        if (accessoryCache.containsKey(player.uniqueId)) {
            return accessoryCache[player.uniqueId]!!
        }

        val items = arrayOfNulls<ItemStack>(9)
        val data = player.persistentDataContainer.get(BasicKeys.DATA_ACCESSORIES, PersistentDataType.STRING)

        if (!data.isNullOrEmpty()) {
            try {
                val config = YamlConfiguration()
                config.loadFromString(data)
                val list = config.getList("items")
                if (list != null) {
                    val loopLimit = min(list.size, 9)
                    for (i in 0 until loopLimit) {
                        val o = list[i]
                        if (o is ItemStack) {
                            items[i] = o
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        accessoryCache[player.uniqueId] = items
        return items
    }

    private fun saveAccessories(player: Player, inv: Inventory) {
        val contents = inv.contents

        // 1. 更新缓存 (确保大小为9)
        val storage = arrayOfNulls<ItemStack>(9)
        for (i in 0 until min(contents.size, 9)) {
            storage[i] = contents[i]
        }
        accessoryCache[player.uniqueId] = storage

        // 2. 清空属性缓存 (触发重算)
        dataManager.clearStatCache(player)

        // 3. 序列化存储
        val config = YamlConfiguration()
        config.set("items", storage)
        player.persistentDataContainer.set(BasicKeys.DATA_ACCESSORIES, PersistentDataType.STRING, config.saveToString())
    }

    // 辅助：获取物品要求的栏位
    private fun getRequiredSlot(item: ItemStack?): Int {
        if (item == null || !item.hasItemMeta()) return -1
        return item.itemMeta!!.persistentDataContainer
            .get(slotKey, PersistentDataType.INTEGER) ?: -1
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        if (event.inventory.holder is AccessoryHolder) {
            val isTopInv = (event.clickedInventory == event.inventory)
            val cursor = event.cursor
            val current = event.currentItem

            // 1. 处理数字键交换
            if (event.click == ClickType.NUMBER_KEY) {
                if (isTopInv) {
                    val hotbarSlot = event.hotbarButton
                    val swapItem = player.inventory.getItem(hotbarSlot)

                    if (swapItem != null && swapItem.type != Material.AIR) {
                        if (!isAccessory(swapItem)) {
                            event.isCancelled = true
                            player.sendMessage("§c只能放入 [饰品] 类型物品！")
                            return
                        }

                        val reqSlot = getRequiredSlot(swapItem)
                        if (reqSlot != -1 && reqSlot != event.slot) {
                            event.isCancelled = true
                            player.sendMessage("§c该饰品只能放入第 ${reqSlot + 1} 格！")
                            return
                        }
                    }
                }
            }
            // 2. 饰品栏内点击
            else if (isTopInv) {
                if (cursor != null && cursor.type != Material.AIR) {
                    if (!isAccessory(cursor)) {
                        event.isCancelled = true
                        player.sendMessage("§c只能放入 [饰品] 类型物品！")
                        return
                    }

                    val reqSlot = getRequiredSlot(cursor)
                    if (reqSlot != -1 && reqSlot != event.slot) {
                        event.isCancelled = true
                        player.sendMessage("§c该饰品只能放入第 ${reqSlot + 1} 格！")
                        return
                    }
                }
            }
            // 3. Shift 点击 (放入)
            else if (event.isShiftClick) {
                if (current != null && current.type != Material.AIR) {
                    if (!isAccessory(current)) {
                        event.isCancelled = true
                        player.sendMessage("§c只能放入 [饰品] 类型物品！")
                        return
                    }

                    val reqSlot = getRequiredSlot(current)
                    if (reqSlot != -1) {
                        event.isCancelled = true // 阻止乱放
                        val top = event.inventory
                        val targetItem = top.getItem(reqSlot)

                        // 仅当目标为空时放入
                        if (targetItem == null || targetItem.type == Material.AIR) {
                            top.setItem(reqSlot, current.clone())
                            event.currentItem = null
                            player.updateInventory()
                            saveAndUpdate(player, top)
                        } else {
                            player.sendMessage("§c目标栏位 (${reqSlot + 1}) 已被占用！")
                        }
                        return
                    }
                }
            }

            // 延迟更新
            Bukkit.getScheduler().runTask(plugin, Runnable {
                saveAndUpdate(player, event.inventory)
            })
        }
    }

    private fun saveAndUpdate(player: Player, gui: Inventory) {
        val guiContents = gui.contents
        updateGuiLore(guiContents)
        gui.contents = guiContents

        saveAccessories(player, gui)

        inventoryListener?.refreshPlayerStatus(player)
        updatePlayerInventoryLore(player)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.inventory.holder is AccessoryHolder) {
            val player = event.player as? Player ?: return
            saveAccessories(player, event.inventory)
            inventoryListener?.refreshPlayerStatus(player)
            updatePlayerInventoryLore(player)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        invalidateCache(event.player)
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is AccessoryHolder) {
            // 1. 类型检查
            for (item in event.newItems.values) {
                if (!isAccessory(item)) {
                    event.isCancelled = true
                    return
                }
            }

            // 2. 栏位检查
            for ((slot, item) in event.newItems) {
                // slot < 9 表示在 Top Inventory
                if (slot < 9) {
                    val reqSlot = getRequiredSlot(item)
                    if (reqSlot != -1 && reqSlot != slot) {
                        event.isCancelled = true
                        (event.whoClicked as? Player)?.sendMessage("§c该饰品只能放入第 ${reqSlot + 1} 格！")
                        return
                    }
                }
            }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                val player = event.whoClicked as? Player ?: return@Runnable
                saveAndUpdate(player, event.inventory)
            })
        }
    }

    private fun updatePlayerInventoryLore(player: Player) {
        val activeSlot = dataManager.getActiveSlot(player)
        player.inventory.contents.forEach { item ->
            if (item != null && item.hasItemMeta()) {
                val type = item.itemMeta!!.persistentDataContainer.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)
                if ("ACCESSORY" == type) {
                    LoreManager.refreshStatus(item, 6, activeSlot, 0)
                }
            }
        }
    }

    private fun updateGuiLore(items: Array<ItemStack?>) {
        for (item in items) {
            if (item != null && item.hasItemMeta()) {
                val type = item.itemMeta!!.persistentDataContainer.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)
                if ("ACCESSORY" == type) {
                    LoreManager.refreshStatus(item, 1, 0, 0)
                }
            }
        }
    }

    private fun isAccessory(item: ItemStack?): Boolean {
        if (item == null || !item.hasItemMeta()) return false
        val type = item.itemMeta!!.persistentDataContainer.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)
        return "ACCESSORY" == type
    }

    class AccessoryHolder : InventoryHolder {
        override fun getInventory(): Inventory {
            // 作为一个 Holder 标记，不需要返回实际 inventory，返回 null 即可 (Kotlin 中需要抛异常或返回 null，视平台类型而定)
            // Bukkit API 中 getInventory 是 @NotNull，但这里是自定义 Holder 仅做标记用。
            // 为了安全起见，抛出异常表明不应通过 Holder 访问 Inventory
            throw UnsupportedOperationException("AccessoryHolder is a marker")
        }
    }
}