package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.manager.EconomyManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class BankUI(
    private val plugin: PanlingBasic,
    private val economyManager: EconomyManager
) : Listener {

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin) // 自动注册监听器
    }

    fun openBankMenu(player: Player) {
        val inv = Bukkit.createInventory(BankHolder(), 54, Component.text("§0随身钱庄"))

        // 1. 底部装饰 (45-53)
        val glass = ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        for (i in 45 until 54) inv.setItem(i, glass)

        // 2. 余额显示 (Slot 49)
        val money = economyManager.getBalance(player)
        val info = ItemStack(Material.BOOK).apply {
            editMeta {
                it.displayName(
                    Component.text("§6当前存款: " + "%.0f".format(money))
                        .decoration(TextDecoration.BOLD, true)
                )
            }
        }
        inv.setItem(49, info)

        // 3. 一键出售按钮 (Slot 53)
        val sellBtn = ItemStack(Material.HOPPER).apply {
            editMeta { sm ->
                sm.displayName(Component.text("§a✔ 出售上方区域材料").decoration(TextDecoration.BOLD, true))
                sm.lore(
                    listOf(
                        Component.text("§7将 §f材料类(MATERIAL) §7物品放入上方格子")
                            .decoration(TextDecoration.ITALIC, false),
                        Component.text("§7点击此按钮将其兑换为存款")
                            .decoration(TextDecoration.ITALIC, false)
                    )
                )
                sm.persistentDataContainer.set(
                    NamespacedKey(plugin, "menu_action"),
                    PersistentDataType.STRING,
                    "BANK_SELL_ALL"
                )
            }
        }
        inv.setItem(53, sellBtn)

        // 4. 取款按钮
        inv.setItem(45, createWithdrawBtn(1, EconomyManager.ID_COIN_1))
        inv.setItem(46, createWithdrawBtn(10, EconomyManager.ID_COIN_10))
        inv.setItem(47, createWithdrawBtn(100, EconomyManager.ID_COIN_100))
        inv.setItem(48, createWithdrawBtn(1000, EconomyManager.ID_COIN_1000))

        // 5. 返回按钮 (52)
        val back = ItemStack(Material.ARROW).apply {
            editMeta { backMeta ->
                backMeta.displayName(Component.text("§7返回主菜单").decoration(TextDecoration.ITALIC, false))
                backMeta.persistentDataContainer.set(
                    NamespacedKey(plugin, "menu_action"),
                    PersistentDataType.STRING,
                    "BACK_MAIN"
                )
            }
        }
        inv.setItem(52, back)

        player.openInventory(inv)
    }

    private fun createWithdrawBtn(value: Int, itemId: String): ItemStack {
        val mat = when {
            value >= 1000 -> Material.GOLD_BLOCK
            value >= 100 -> Material.IRON_INGOT
            value >= 10 -> Material.GOLD_INGOT
            else -> Material.GOLD_NUGGET
        }

        return ItemStack(mat).apply {
            editMeta { meta ->
                meta.displayName(Component.text("§e提取 $value 铜钱").decoration(TextDecoration.BOLD, true))
                meta.lore(
                    listOf(
                        Component.text("§a[左键] §f取出一枚").decoration(TextDecoration.ITALIC, false),
                        Component.text("§b[Shift+左键] §f取出尽可能多(一组)").decoration(TextDecoration.ITALIC, false)
                    )
                )

                val pdc = meta.persistentDataContainer
                pdc.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "BANK_WITHDRAW")
                pdc.set(NamespacedKey(plugin, "bank_val"), PersistentDataType.INTEGER, value)
                pdc.set(NamespacedKey(plugin, "bank_item_id"), PersistentDataType.STRING, itemId)
            }
        }
    }

    // === [FIXED] 核心修复：监听界面关闭事件，退还物品 ===
    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.inventory.holder is BankHolder) {
            val player = event.player as? Player ?: return
            returnRemainingItems(player, event.inventory)
        }
    }

    // 辅助方法：将 0-44 格子的物品退还给玩家
    private fun returnRemainingItems(player: Player, inv: Inventory) {
        for (i in 0 until 45) {
            val item = inv.getItem(i)
            if (item != null && item.type != Material.AIR) {
                // 返还给玩家
                val leftOver = player.inventory.addItem(item)
                // 如果背包满了，掉落在地上
                for (drop in leftOver.values) {
                    player.world.dropItem(player.location, drop)
                }
                // 从界面中清除 (防止逻辑重复，虽然界面马上要销毁了)
                inv.setItem(i, null)
            }
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        if (event.inventory.holder is BankHolder) {
            val isTop = event.clickedInventory == event.inventory

            // 保护底部操作区 (45-53)
            if (isTop && event.slot >= 45) {
                event.isCancelled = true
                val clicked = event.currentItem ?: return
                if (!clicked.hasItemMeta()) return

                val pdc = clicked.itemMeta.persistentDataContainer
                val action = pdc.get(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING)

                when (action) {
                    "BANK_SELL_ALL" -> {
                        // 1. 先执行出售，移除掉能卖的
                        economyManager.sellMaterialsInInventory(player, event.inventory, 45)

                        // 2. 刷新界面
                        // 注意：openBankMenu 会打开新界面，从而触发旧界面的 InventoryCloseEvent
                        // 上面的 onInventoryClose 会负责把“不能卖的剩饭剩菜”退还给玩家
                        openBankMenu(player)
                    }
                    "BANK_WITHDRAW" -> {
                        val valAmount = pdc.getOrDefault(
                            NamespacedKey(plugin, "bank_val"),
                            PersistentDataType.INTEGER,
                            0
                        )
                        val id = pdc.get(
                            NamespacedKey(plugin, "bank_item_id"),
                            PersistentDataType.STRING
                        )

                        if (id != null) {
                            economyManager.withdrawCurrency(player, id, valAmount, event.isShiftClick)
                        }
                        // 同理，这里也会触发 close 事件退还物品 (如果有的话)
                        openBankMenu(player)
                    }
                    "BACK_MAIN" -> {
                        plugin.menuManager.openMenu(player)
                    }
                }
            }
        }
    }

    // 简单的 Holder 实现
    class BankHolder : InventoryHolder {
        override fun getInventory(): Inventory {
            throw UnsupportedOperationException("BankHolder does not hold the inventory instance directly.")
        }
    }
}