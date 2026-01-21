package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.manager.ShopManager
import com.panling.basic.shop.Shop
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

class ShopUI(
    private val plugin: PanlingBasic,
    private val shopManager: ShopManager
) : Listener {

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun openShop(player: Player, shop: Shop) {
        val inv = Bukkit.createInventory(ShopHolder(shop), shop.size, Component.text(shop.title))

        // 渲染商品
        for (product in shop.products.values) {
            // 利用 Kotlin 的安全调用，只有当 displayItem 不为 null 时才执行设置
            product.displayItem?.let { item ->
                inv.setItem(product.slot, item)
            }
        }

        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        // 使用 as? 进行安全类型转换，如果不是 ShopHolder 则直接返回
        val holder = event.inventory.holder as? ShopHolder ?: return
        event.isCancelled = true // 商店只读，禁止拿取

        val player = event.whoClicked as? Player ?: return

        // 点击的是下方背包则忽略
        // 注意：在 Kotlin 中使用属性访问语法替代 getter
        if (event.clickedInventory != event.view.topInventory) return

        val slot = event.slot
        val product = holder.shop.getProduct(slot)

        if (product != null) {
            // 左键购买
            if (event.isLeftClick) {
                shopManager.handleBuy(player, product)
            }
            // 右键卖出逻辑 (预留)
            // else if (event.isRightClick) { ... }
        }
    }

    // 内部类：用于标记这是商店界面
    // Kotlin 中的 nested class 默认相当于 Java 的 static inner class
    class ShopHolder(val shop: Shop) : InventoryHolder {
        override fun getInventory(): Inventory {
            throw UnsupportedOperationException("ShopHolder does not hold the inventory instance directly.")
        }
    }
}