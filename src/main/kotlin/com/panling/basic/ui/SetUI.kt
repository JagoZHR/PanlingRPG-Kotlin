package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.manager.SetManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class SetUI(private val plugin: PanlingBasic) : Listener {

    private val setIdKey = NamespacedKey(plugin, "ui_set_id")
    private val navKey = NamespacedKey(plugin, "ui_nav_target") // 缓存 Key

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    // === 二级菜单：套装列表 ===
    fun openSetList(player: Player) {
        val inv = Bukkit.createInventory(SetHolder("LIST"), 54, Component.text("§8套装图鉴"))

        val pc = plugin.playerDataManager.getPlayerClass(player)
        // [修复] getAllSets() 是函数，必须加括号调用
        val sets = plugin.setManager.getAllSets()

        var slot = 0
        for (set in sets) {
            // 严苛过滤：只显示适合当前职业的套装
            // 如果套装内所有物品都限制了其他职业，则不显示该套装
            if (!isSetSuitableForClass(set, pc)) continue

            val icon = createSetIcon(player, set)
            inv.setItem(slot++, icon)
        }

        // 返回主菜单按钮
        addBackButton(inv, 45, "MAIN_MENU")
        player.openInventory(inv)
    }

    // === 三级菜单：套装详情 ===
    fun openSetDetail(player: Player, set: SetManager.SetDefinition) {
        val inv = Bukkit.createInventory(
            SetHolder("DETAIL"),
            54,
            // [修复] Kotlin data class 属性直接访问，去掉 ()
            Component.text("§8${set.displayName}")
        )
        val pc = plugin.playerDataManager.getPlayerClass(player)
        val calc = plugin.statCalculator
        val itemManager = plugin.itemManager

        // 1. 获取玩家当前激活的该套装件数
        // [修复] set.id
        val activeCount = calc.getSetPieceCount(player, set.id)

        // === 优化：套装信息展示 ===
        val infoIcon = ItemStack(Material.BOOK).apply {
            editMeta { infoMeta ->
                infoMeta.displayName(Component.text("§e套装效果一览").decoration(TextDecoration.BOLD, true))
                val infoLore = ArrayList<Component>()

                infoLore.add(
                    Component.text("§7当前激活: §6$activeCount 件")
                        .decoration(TextDecoration.ITALIC, false)
                )
                infoLore.add(Component.empty())

                // [修复] set.tiers
                set.tiers.forEach { (count, stats) ->
                    val activated = activeCount >= count
                    val color = if (activated) "§a" else "§8"
                    val prefix = if (activated) "✔ " else "✖ "

                    infoLore.add(
                        Component.text("$color$prefix[${count}件套]")
                            .decoration(TextDecoration.BOLD, true)
                            .decoration(TextDecoration.ITALIC, false)
                    )

                    // 1. 优先显示配置的描述
                    // [修复] set.descriptions
                    val desc = set.descriptions[count]
                    if (!desc.isNullOrEmpty()) {
                        for (line in desc) {
                            infoLore.add(
                                Component.text("$color  ${line.replace("&", "§")}")
                                    .decoration(TextDecoration.ITALIC, false)
                            )
                        }
                    } else {
                        // 2. 如果没配置描述，自动生成属性文本 (Fallback)
                        stats.forEach { (key, valNum) ->
                            val meta = BasicKeys.STAT_METADATA[key]
                            // 安全检查，虽然理论上 Key 应该存在
                            if (meta != null) {
                                val valStr = if (meta.isPercent) "${(valNum * 100).toInt()}%" else valNum.toString()
                                infoLore.add(
                                    Component.text("$color  • ${meta.displayName}: $valStr")
                                        .decoration(TextDecoration.ITALIC, false)
                                )
                            }
                        }
                    }
                    infoLore.add(Component.empty())
                }
                infoMeta.lore(infoLore)
            }
        }
        inv.setItem(4, infoIcon)

        // 3. 展示套装部件 (Row 3-5)
        // 找出所有属于该套装的物品ID
        // [修复] set.id
        val itemIds = plugin.setManager.getItemsInSet(set.id)

        var itemSlot = 18
        for (itemId in itemIds) {
            // 职业过滤：只显示本职业能用的部件
            if (!itemManager.isItemAllowedForClass(itemId, pc)) continue

            val item = itemManager.createItem(itemId, player) ?: continue

            // 检查玩家是否穿戴了这件
            val isWearing = calc.isWearingItem(player, itemId)

            item.editMeta { meta ->
                val lore = meta.lore() ?: ArrayList()
                lore.add(Component.empty())

                if (isWearing) {
                    lore.add(
                        Component.text("§a✔ 已装备")
                            .decoration(TextDecoration.BOLD, true)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    // 发光特效
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true)
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
                } else {
                    lore.add(Component.text("§7✖ 未装备").decoration(TextDecoration.ITALIC, false))
                }
                meta.lore(lore)
            }

            if (itemSlot < 54) inv.setItem(itemSlot++, item)
        }

        addBackButton(inv, 45, "SET_LIST")
        player.openInventory(inv)
    }

    private fun createSetIcon(player: Player, set: SetManager.SetDefinition): ItemStack {
        // 使用代表性物品做图标，或者书本
        return ItemStack(Material.PAPER).apply {
            editMeta { meta ->
                meta.displayName(
                    // [修复] set.displayName
                    Component.text(set.displayName)
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true)
                )

                val lore = ArrayList<Component>()
                // 进度显示
                // [修复] set.id
                val active = plugin.statCalculator.getSetPieceCount(player, set.id)
                lore.add(Component.text("§7当前激活: §e$active 件").decoration(TextDecoration.ITALIC, false))
                lore.add(Component.empty())
                lore.add(Component.text("§e▶ 点击查看详情").decoration(TextDecoration.ITALIC, false))

                meta.lore(lore)
                meta.persistentDataContainer.set(setIdKey, PersistentDataType.STRING, set.id)
            }
        }
    }

    private fun isSetSuitableForClass(set: SetManager.SetDefinition, pc: PlayerClass): Boolean {
        // 遍历套装里所有物品，只要有一件是该职业能用的，就显示该套装
        // [修复] set.id
        val items = plugin.setManager.getItemsInSet(set.id)
        // 使用 any 替代循环，代码更简洁
        return items.any { id -> plugin.itemManager.isItemAllowedForClass(id, pc) }
    }

    private fun addBackButton(inv: Inventory, slot: Int, target: String) {
        val back = ItemStack(Material.ARROW).apply {
            editMeta { meta ->
                meta.displayName(Component.text("§7返回").decoration(TextDecoration.ITALIC, false))
                meta.persistentDataContainer.set(navKey, PersistentDataType.STRING, target)
            }
        }
        inv.setItem(slot, back)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? SetHolder ?: return
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        val clicked = event.currentItem ?: return
        if (clicked.type == Material.AIR) return
        val meta = clicked.itemMeta ?: return

        // [FIXED] 导航逻辑
        if (meta.persistentDataContainer.has(navKey, PersistentDataType.STRING)) {
            val target = meta.persistentDataContainer.get(navKey, PersistentDataType.STRING)
            if ("MAIN_MENU" == target) {
                // 调用 MenuManager 打开主菜单
                plugin.menuManager.openMenu(player)
            } else if ("SET_LIST" == target) {
                openSetList(player)
            }
            return
        }

        if ("LIST" == holder.type) {
            val setId = meta.persistentDataContainer.get(setIdKey, PersistentDataType.STRING)
            if (setId != null) {
                val set = plugin.setManager.getSet(setId)
                if (set != null) openSetDetail(player, set)
            }
        }
    }

    class SetHolder(val type: String) : InventoryHolder {
        override fun getInventory(): Inventory {
            throw UnsupportedOperationException("SetHolder does not hold the inventory instance directly.")
        }
    }
}