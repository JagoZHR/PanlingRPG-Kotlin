package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.persistence.PersistentDataType

class EconomyManager(
    private val plugin: PanlingBasic,
    private val dataManager: PlayerDataManager
) {

    companion object {
        // 货币定义
        const val ID_COIN_1 = "coin_copper"
        const val ID_COIN_10 = "coin_silver"
        const val ID_COIN_100 = "coin_gold"
        const val ID_COIN_1000 = "coin_platinum"
    }

    // === 基础余额操作 ===

    fun getBalance(player: Player): Double {
        return dataManager.getMoney(player)
    }

    fun hasMoney(player: Player, amount: Double): Boolean {
        return getBalance(player) >= amount
    }

    fun giveMoney(player: Player, amount: Double) {
        dataManager.giveMoney(player, amount)
    }

    fun takeMoney(player: Player, amount: Double): Boolean {
        return dataManager.takeMoney(player, amount)
    }

    // === 业务逻辑：出售材料 ===

    /**
     * 计算并执行出售逻辑
     * @param inv 钱庄的 Inventory
     * @param limitSlot 只扫描 0 到 limitSlot-1 的格子
     * @return 出售的总价值
     */
    fun sellMaterialsInInventory(player: Player, inv: Inventory, limitSlot: Int): Double {
        var totalValue = 0.0
        var count = 0

        for (i in 0 until limitSlot) {
            val item = inv.getItem(i)
            // 检查物品是否存在且有 Meta
            if (item == null || !item.hasItemMeta()) continue

            val meta = item.itemMeta!!
            val pdc = meta.persistentDataContainer

            // 1. 类型检查
            val type = pdc.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)
            if ("MATERIAL" != type) continue

            // 2. 价值检查
            // Kotlin 中 PDC 没有 getOrDefault，需要手动处理 null
            val value = pdc.get(BasicKeys.ITEM_MONEY_VALUE, PersistentDataType.DOUBLE) ?: 0.0

            if (value > 0) {
                totalValue += value * item.amount
                count += item.amount
                inv.setItem(i, null) // 移除物品
            }
        }

        if (totalValue > 0) {
            giveMoney(player, totalValue)
            player.sendMessage("§a[钱庄] 成功出售 $count 个材料，获得 ${"%.1f".format(totalValue)} 铜钱")
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f)
        } else {
            player.sendMessage("§c区域内没有可出售的材料")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
        }

        return totalValue
    }

    // === 存取款逻辑 ===

    /**
     * 提现 (余额 -> 物品)
     * [修复] 重命名为 withdrawCurrency 以匹配 BankUI 的调用
     */
    fun withdrawCurrency(player: Player, itemId: String, unitValue: Int, isShift: Boolean) {
        val balance = getBalance(player)

        // 1. 计算数量
        var amount = if (isShift) 64 else 1
        val maxAffordable = (balance / unitValue).toInt()

        if (amount > maxAffordable) {
            amount = maxAffordable
        }

        if (amount <= 0) {
            player.sendMessage("§c余额不足，无法提取！")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        // 2. 扣款
        val cost = (amount * unitValue).toDouble()
        if (takeMoney(player, cost)) {
            // 3. 给物品
            // 注意：Kotlin 中属性访问 plugin.itemManager 等同于 plugin.getItemManager()
            // 使用 ?. 安全调用，虽然理论上 Manager 不应该为空
            val item = plugin.itemManager?.createItem(itemId, player)

            if (item == null) {
                player.sendMessage("§c配置错误：货币物品ID $itemId 不存在！请联系管理员。")
                giveMoney(player, cost) // 回滚退款
                return
            }

            item.amount = amount

            val left = player.inventory.addItem(item)
            if (left.isNotEmpty()) {
                for (drop in left.values) {
                    player.world.dropItem(player.location, drop)
                }
                player.sendMessage("§e背包已满，货币掉落在脚下。")
            }

            // 构建显示名称
            val displayName = if (item.hasItemMeta() && item.itemMeta!!.hasDisplayName()) {
                item.itemMeta!!.displayName // 已经被 Adventure/Paper API 转换为 Component 兼容，或者旧版 String
                // 如果环境是新版 Paper，这里可能需要序列化 Component，但为了稳健，直接toString或者依赖Bukkit自动转换
                // 简单起见，这里复用 itemMeta 的显示名
                item.itemMeta!!.displayName
            } else {
                itemId
            }

            player.sendMessage("§a[钱庄] 提取 $amount 个 $displayName")
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
        }
    }

    /**
     * 存款 (背包物品 -> 余额)
     */
    fun deposit(player: Player) {
        val inv = player.inventory
        var totalAdded = 0.0
        var found = false

        // 遍历背包
        for (i in 0 until inv.size) {
            val item = inv.getItem(i)
            // 严格检查 item 是否非空且有 Meta
            if (item == null || !item.hasItemMeta()) continue

            val pdc = item.itemMeta!!.persistentDataContainer

            // 检查是否有金额标识
            if (pdc.has(BasicKeys.ITEM_MONEY_VALUE, PersistentDataType.DOUBLE)) {
                val value = pdc.get(BasicKeys.ITEM_MONEY_VALUE, PersistentDataType.DOUBLE) ?: 0.0

                if (value > 0) {
                    val stackTotal = value * item.amount
                    totalAdded += stackTotal

                    // 移除物品
                    item.amount = 0
                    inv.setItem(i, null)
                    found = true
                }
            }
        }

        if (found && totalAdded > 0) {
            giveMoney(player, totalAdded)
            player.sendMessage("§e已存入 %.1f 铜钱".format(totalAdded))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 2f)
        } else {
            player.sendMessage("§c背包里没有可存入的货币物品。")
        }
    }
}