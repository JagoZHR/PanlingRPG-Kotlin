package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.Reloadable
import com.panling.basic.shop.Shop
import com.panling.basic.shop.ShopProduct
import com.panling.basic.ui.ShopUI
import net.kyori.adventure.text.Component
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.HashMap

class ShopManager(private val plugin: PanlingBasic) : Reloadable {

    private val shops = HashMap<String, Shop>()
    private val shopUI = ShopUI(plugin, this) // 初始化 UI

    init {
        // 自动注册重载
        plugin.reloadManager?.register(this)
        loadShops()
    }

    override fun reload() {
        loadShops()
        plugin.logger.info("生物库已重载。") // 严格保持原 Java 代码的文本
    }

    // [MODIFIED] 使用 Kotlin IO 扩展加载
    fun loadShops() {
        shops.clear()

        val folder = File(plugin.dataFolder, "shops")
        if (!folder.exists()) {
            folder.mkdirs()
            plugin.logger.info("已创建 shops 文件夹，请上传商店配置。")
            return
        }

        // 使用 Kotlin 的 walk 遍历文件，更简洁
        folder.walk()
            .filter { it.isFile && it.name.endsWith(".yml") }
            .forEach { loadSingleShopFile(it) }

        plugin.logger.info("已加载 ${shops.size} 个商店。")
    }

    // [NEW] 提取出来的单文件解析逻辑
    private fun loadSingleShopFile(file: File) {
        val config = YamlConfiguration.loadConfiguration(file)

        for (shopId in config.getKeys(false)) {
            val sec = config.getConfigurationSection(shopId) ?: continue

            val title = sec.getString("title", "商店")!!.replace("&", "§")
            val rows = sec.getInt("rows", 3)
            val shop = Shop(shopId, title, rows * 9)

            if (sec.contains("items")) {
                val items = sec.getMapList("items")
                for (map in items) {
                    try {
                        val itemId = map["item"] as String
                        // 安全转换 Number -> Double (处理 Integer/Double 混用的情况)
                        val buy = (map["buy"] as? Number)?.toDouble() ?: -1.0
                        val sell = (map["sell"] as? Number)?.toDouble() ?: -1.0
                        val slot = (map["slot"] as Number).toInt()

                        val product = ShopProduct(itemId, buy, sell, slot)
                        buildDisplayItem(product)
                        shop.addProduct(product)
                    } catch (e: Exception) {
                        plugin.logger.warning("商店 $shopId 的物品配置有误 (文件: ${file.name})")
                    }
                }
            }
            shops[shopId] = shop
        }
    }

    private fun buildDisplayItem(product: ShopProduct) {
        // 从 ItemManager 获取原始物品
        val original = plugin.itemManager?.createItem(product.itemId, null) ?: return

        val display = original.clone()
        val meta = display.itemMeta
        // 确保 lore 是可变列表
        val lore = meta.lore() ?: ArrayList()

        lore.add(Component.empty())
        lore.add(Component.text("§8§m------------------"))
        if (product.buyPrice >= 0) {
            lore.add(Component.text("§a[左键] 购买: §e${product.buyPrice} 金币"))
        } else {
            lore.add(Component.text("§c[不可购买]"))
        }

        // 暂时只做购买逻辑，卖出通常涉及复杂的背包检测，第一版先略过
        // if (product.getSellPrice() >= 0) ...

        meta.lore(lore)
        display.itemMeta = meta
        product.displayItem = display
    }

    // === 业务逻辑 ===

    fun openShop(player: Player, shopId: String) {
        val shop = shops[shopId]
        if (shop != null) {
            shopUI.openShop(player, shop)
        } else {
            player.sendMessage("§c错误：商店 $shopId 不存在！")
        }
    }

    fun handleBuy(player: Player, product: ShopProduct) {
        if (product.buyPrice < 0) {
            player.sendMessage("§c此商品非卖品。")
            return
        }

        // 1. 检查钱
        val eco = plugin.economyManager
        if (!eco.hasMoney(player, product.buyPrice)) {
            player.sendMessage("§c余额不足！你需要 ${product.buyPrice} 金币。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        // 2. 检查背包空间 (简单检查)
        if (player.inventory.firstEmpty() == -1) {
            player.sendMessage("§c背包已满！")
            return
        }

        // 3. 执行交易
        eco.takeMoney(player, product.buyPrice)

        val item = plugin.itemManager?.createItem(product.itemId, player)
        if (item != null) {
            player.inventory.addItem(item)

            // 获取显示名称 (为了保持与 Java 逻辑一致的字符串拼接)
            val displayName = if (item.hasItemMeta() && item.itemMeta.hasDisplayName())
                item.itemMeta.displayName
            else
                product.itemId

            player.sendMessage("§a购买成功: $displayName")
        }
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
    }
}