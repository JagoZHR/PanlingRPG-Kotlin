package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.ArrayStance
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.PlayerRace
import com.panling.basic.ui.BankUI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import java.util.ArrayList
import java.util.HashMap
import kotlin.math.min

class MenuManager(
    private val plugin: PanlingBasic,
    private val dataManager: PlayerDataManager,
    private val accessoryManager: AccessoryManager,
    private val statCalculator: StatCalculator,
    // [NEW] 冷却管理器通常是全局单例或通过插件获取，这里为了保持构造函数签名一致，我们在 init 中初始化或从 plugin 获取
    // 原 Java 代码是在构造函数里 new CooldownManager()，这里保持一致
) : Listener {

    private val cooldownManager = CooldownManager()
    val menuItem: ItemStack
    private var bankInterface: BankUI? = null

    init {
        // 初始化菜单物品
        menuItem = ItemStack(Material.COMPASS)
        val meta = menuItem.itemMeta
        meta.displayName(Component.text("§e§l[ 角色面板 ]").decoration(TextDecoration.ITALIC, false))

        val lore = ArrayList<Component>()
        lore.add(Component.text("§7右键点击打开属性面板与设置").decoration(TextDecoration.ITALIC, false))
        meta.lore(lore)

        // === [NEW] 核心修改：给菜单书打上 MENU 标签 ===
        meta.persistentDataContainer.set(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING, "MENU")
        meta.persistentDataContainer.set(BasicKeys.ITEM_ID, PersistentDataType.STRING, "MENU_OPENER")

        menuItem.itemMeta = meta

        // === [NEW] 启动自动刷新任务 ===
        startUpdateTask()
    }

    fun setBankUI(bankUI: BankUI) {
        this.bankInterface = bankUI
    }

    // [NEW] 每秒刷新任务
    private fun startUpdateTask() {
        object : BukkitRunnable() {
            override fun run() {
                for (player in Bukkit.getOnlinePlayers()) {
                    // 检查玩家是否正在查看我们的菜单
                    val topInv = player.openInventory.topInventory
                    if (topInv.holder is MenuHolder) {
                        updateStanceButton(player, topInv)
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L) // 20 ticks = 1秒
    }

    // [MODIFIED] 更新按钮逻辑：位置从 24 改为 13
    private fun updateStanceButton(player: Player, inv: Inventory) {
        val pc = dataManager.getPlayerClass(player)
        if (pc != PlayerClass.MAGE) return

        // 按钮现在移动到了 13 号位
        val switchIcon = inv.getItem(13)
        if (switchIcon == null || switchIcon.type == Material.AIR) {
            return
        }

        // 简单校验一下是不是切换按钮（防止覆盖了别的）
        val action = switchIcon.itemMeta.persistentDataContainer
            .get(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING)
        if ("SWITCH_STANCE" != action) return

        val sm = switchIcon.itemMeta
        val current = dataManager.getArrayStance(player)

        val sLore = ArrayList<Component>()
        sLore.add(Component.text(current.description).color(NamedTextColor.GRAY))
        sLore.add(Component.empty())

        val cd = cooldownManager.getRemainingCooldown(player, "STANCE_SWITCH")
        if (cd > 0) {
            sLore.add(Component.text("§c冷却中: ${cd / 1000}s").decoration(TextDecoration.ITALIC, false))
        } else {
            sLore.add(Component.text("§e点击切换流派").decoration(TextDecoration.ITALIC, false))
        }
        sLore.add(Component.text("§8(冷却时间: 5分钟)").decoration(TextDecoration.ITALIC, false))

        sm.lore(sLore)
        switchIcon.itemMeta = sm
    }

    // [NEW] 打开炼化菜单
    fun openRefineMenu(player: Player) {
        val holder = MenuHolder() // 复用 MenuHolder 或者新建一个 RefineHolder
        val inv = Bukkit.createInventory(holder, 54, Component.text("§0元素炼化与存储"))

        // 1. 装饰板 (黑色玻璃)
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val fm = filler.itemMeta
        fm.displayName(Component.empty())
        filler.itemMeta = fm
        for (i in 0 until 54) inv.setItem(i, filler)

        // 2. 核心存入按钮 (放在中间 49 slot 或 22 slot - Java代码中是 slot 4)
        val core = ItemStack(Material.BEACON)
        val cm = core.itemMeta
        val points = dataManager.getElementPoints(player)
        cm.displayName(Component.text("§b§l元素核心").decoration(TextDecoration.ITALIC, false))

        val cLore = ArrayList<Component>()
        cLore.add(Component.text("§7当前存储: §e$points §7点灵力").decoration(TextDecoration.ITALIC, false))
        cLore.add(Component.empty())
        cLore.add(Component.text("§a[左键] §f一键存入背包内所有元素").decoration(TextDecoration.ITALIC, false))
        cLore.add(Component.text("§e[点击下方列表] §f消耗灵力兑换元素").decoration(TextDecoration.ITALIC, false))

        cm.lore(cLore)
        cm.persistentDataContainer.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "DEPOSIT_ALL")
        core.itemMeta = cm
        inv.setItem(4, core) // 放在第一行中间

        // 3. 生成可取出的元素列表
        val elements = plugin.itemManager?.getAllElementItems() ?: emptyList()
        var slot = 9 // 从第二行开始

        for (item in elements) {
            if (slot >= 54) break

            val im = item.itemMeta
            val cost = im.persistentDataContainer.getOrDefault(BasicKeys.ITEM_ELEMENT_VALUE, PersistentDataType.INTEGER, 0)

            var lore = im.lore() ?: ArrayList()
            // 确保 lore 是可变的
            if (lore !is ArrayList) lore = ArrayList(lore)

            lore.add(Component.empty())
            lore.add(Component.text("§b价值: $cost 灵力").decoration(TextDecoration.ITALIC, false))

            if (points >= cost) {
                lore.add(Component.text("§a[点击取出]").decoration(TextDecoration.ITALIC, false))
            } else {
                lore.add(Component.text("§c[灵力不足]").decoration(TextDecoration.ITALIC, false))
            }
            im.lore(lore)

            // 标记动作为取出，并存入物品ID以便知道取哪个
            im.persistentDataContainer.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "WITHDRAW_ELEMENT")
            // 物品原本就有 BasicKeys.ITEM_ID，所以不需要额外存 ID，直接读即可

            item.itemMeta = im
            inv.setItem(slot++, item)
        }

        player.openInventory(inv)
    }

    fun openMenu(player: Player) {
        // [FIXED] 扩大为 27 格，否则放入 Slot 25, 26 会报错
        val fullInv = Bukkit.createInventory(MenuHolder(), 27, Component.text("§8角色详情与设置"))

        // 1. 背景板
        val glass = ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
        val gMeta = glass.itemMeta
        gMeta.displayName(Component.empty())
        glass.itemMeta = gMeta
        for (i in 0 until 27) fullInv.setItem(i, glass)

        // 2. 玩家信息头颅 (显示总属性)
        val activeSlot = dataManager.getActiveSlot(player)
        val pc = dataManager.getPlayerClass(player)

        val skull = ItemStack(Material.PLAYER_HEAD)
        val skullMeta = skull.itemMeta as SkullMeta
        skullMeta.owningPlayer = player
        skullMeta.displayName(Component.text("§e${player.name} 的面板").decoration(TextDecoration.ITALIC, false))

        val lore = ArrayList<Component>()
        lore.add(Component.text("§f职业: ${pc.displayName}").decoration(TextDecoration.ITALIC, false))

        val race = dataManager.getPlayerRace(player)
        // === [NEW] 显示种族 ===
        lore.add(Component.text("§f种族: ${race.coloredName}").decoration(TextDecoration.ITALIC, false))

        // 如果有种族，显示天赋详情
        if (race != PlayerRace.NONE) {
            val bonus = race.calculateBonus(player.level)
            val valStr = if (race == PlayerRace.IMMORTAL || race == PlayerRace.DEMON) {
                String.format("%.1f%%", bonus * 100)
            } else {
                String.format("%.1f", bonus)
            }

            lore.add(Component.text("  §7└ 天赋: ${race.statDesc} +$valStr")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false))
        }

        // 计算原版属性
        val maxHp = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val moveSpeed = player.getAttribute(Attribute.MOVEMENT_SPEED)?.value ?: 0.2
        lore.add(Component.text("§c❤ 生命: ${player.health.toInt()}/${maxHp.toInt()}").decoration(TextDecoration.ITALIC, false))
        lore.add(Component.text("§f⚡ 速度: ${(moveSpeed * 1000).toInt()}%").decoration(TextDecoration.ITALIC, false))

        lore.add(Component.text("§7----------------").decoration(TextDecoration.ITALIC, false))

        // 强制显示所有RPG属性 (已修改为保留小数)
        for (key in BasicKeys.ALL_STATS) {
            // [新增] 过滤掉不需要在此处显示的属性 (速度和生命)
            if (key == BasicKeys.ATTR_MOVE_SPEED || key == BasicKeys.ATTR_MAX_HEALTH) {
                continue
            }
            val `val` = statCalculator.getPlayerTotalStat(player, key)
            val metaData = BasicKeys.STAT_METADATA[key]!!

            val valStr = if (metaData.isPercent) {
                String.format("%.1f%%", `val` * 100) // 格式化为 25.0%
            } else {
                String.format("%.1f", `val`) // 格式化为 15.0
            }

            lore.add(Component.text("${metaData.displayName}: $valStr")
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false))
        }

        lore.add(Component.empty())
        lore.add(Component.text("§7↓ 切换主手激活槽位 ↓").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))

        skullMeta.lore(lore)
        skull.itemMeta = skullMeta
        fullInv.setItem(4, skull)

        // 套装图鉴按钮 (Slot 26)
        val setIcon = ItemStack(Material.GOLDEN_CHESTPLATE)
        val setMeta = setIcon.itemMeta
        setMeta.displayName(Component.text("§e§l[ 套装图鉴 ]").decoration(TextDecoration.ITALIC, false))
        setMeta.lore(listOf(
            Component.text("§7点击查看所有套装及其效果").decoration(TextDecoration.ITALIC, false)
        ))
        setMeta.persistentDataContainer.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "OPEN_SETS")
        setIcon.itemMeta = setMeta
        fullInv.setItem(26, setIcon)

        // 饰品背包按钮 (Slot 25)
        val accIcon = ItemStack(Material.EMERALD)
        val accMeta = accIcon.itemMeta
        accMeta.displayName(Component.text("§a§l[ 饰品背包 ]").decoration(TextDecoration.ITALIC, false))
        val accLore = ArrayList<Component>()
        accLore.add(Component.text("§7点击打开饰品栏位").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        accMeta.lore(accLore)
        accMeta.persistentDataContainer.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "OPEN_ACCESSORY")
        accIcon.itemMeta = accMeta
        fullInv.setItem(25, accIcon)

        // === [MODIFIED] 中间切换按钮 (Slot 13) ===
        // 无论什么职业，切换按钮都放在 13
        if (pc == PlayerClass.MAGE) {
            // --- 阵法师：切换流派 (原 Slot 24 -> 现 Slot 13) ---
            val switchIcon = ItemStack(Material.END_CRYSTAL)
            val sm = switchIcon.itemMeta
            val current = dataManager.getArrayStance(player)

            sm.displayName(Component.text(current.displayName).decoration(TextDecoration.ITALIC, false))
            val sLore = ArrayList<Component>()
            sLore.add(Component.text(current.description).color(NamedTextColor.GRAY))
            sLore.add(Component.empty())
            sLore.add(Component.text("§e点击切换流派").decoration(TextDecoration.ITALIC, false))
            val cd = cooldownManager.getRemainingCooldown(player, "STANCE_SWITCH")
            if (cd > 0) {
                sLore.add(Component.text("§c冷却中: ${cd / 1000}s").decoration(TextDecoration.ITALIC, false))
            }
            sLore.add(Component.text("§8(冷却时间: 5分钟)").decoration(TextDecoration.ITALIC, false))

            sm.lore(sLore)
            sm.persistentDataContainer.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "SWITCH_STANCE")
            switchIcon.itemMeta = sm
            fullInv.setItem(13, switchIcon)
        } else {
            // --- 其他职业：切换主副手 (Slot 13) ---
            val toggleBtn = ItemStack(Material.COMPASS)
            val tm = toggleBtn.itemMeta
            val statusText = if (activeSlot == 0) "§e主手 (第一格)" else "§b副手"
            tm.displayName(Component.text("§f当前激活模式: $statusText").decoration(TextDecoration.ITALIC, false))
            val lore_switch = ArrayList<Component>()
            lore_switch.add(Component.text("§7点击切换至 ${if (activeSlot == 0) "副手" else "主手"} 模式").decoration(TextDecoration.ITALIC, false))
            tm.lore(lore_switch)
            tm.persistentDataContainer.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "TOGGLE_HAND")
            toggleBtn.itemMeta = tm
            fullInv.setItem(13, toggleBtn)
        }

        val questBtn = ItemStack(Material.BLAZE_POWDER)
        val qm = questBtn.itemMeta
        qm.displayName(Component.text("§c§l[ 任务 ]").decoration(TextDecoration.ITALIC, false))
        val qLore = ArrayList<Component>()
        qLore.add(Component.text("§7点击打开任务列表").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        qm.lore(qLore)
        qm.persistentDataContainer.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "OPEN_QUESTS")
        questBtn.itemMeta = qm
        fullInv.setItem(18, questBtn) // [MODIFIED] 设置在 Slot 22 (注释说是22，但代码写的是18，保持Java原样)

        // === Slot 22: 随身仓库 (原定计划) ===
        val warehouseBtn = ItemStack(Material.CHEST)
        val wm = warehouseBtn.itemMeta
        wm.displayName(Component.text("§e§l[ 随身仓库 ]").decoration(TextDecoration.ITALIC, false))
        val wLore = ArrayList<Component>()
        wLore.add(Component.text("§7点击打开仓库列表").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        //wLore.add(Component.empty());
        //wLore.add(Component.text("§a支持多页仓库管理").decoration(TextDecoration.ITALIC, false));
        wm.lore(wLore)
        wm.persistentDataContainer.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "OPEN_WAREHOUSE")
        warehouseBtn.itemMeta = wm
        fullInv.setItem(22, warehouseBtn) // [MODIFIED] 设置在 Slot 22

        // Slot 23: 随身钱庄 (原 Slot 22 -> 现 Slot 23)
        val bankIcon = ItemStack(Material.GOLD_INGOT)
        val bm = bankIcon.itemMeta
        bm.displayName(Component.text("§6§l[ 随身钱庄 ]").decoration(TextDecoration.ITALIC, false))
        val balance = dataManager.getMoney(player)
        bm.lore(listOf(
            Component.text("§7当前余额: §e${String.format("%.1f", balance)}").decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("§a点击存取货币与出售材料").decoration(TextDecoration.ITALIC, false)
        ))
        bm.persistentDataContainer.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "OPEN_BANK")
        bankIcon.itemMeta = bm
        fullInv.setItem(23, bankIcon)

        // Slot 24: 元素炼化 (原 Slot 23 -> 现 Slot 24)
        val refineBtn = ItemStack(Material.HOPPER)
        val rm = refineBtn.itemMeta
        rm.displayName(Component.text("§b§l[ 元素炼化 ]").decoration(TextDecoration.ITALIC, false))
        val rLore = ArrayList<Component>()
        rLore.add(Component.text("§7点击进入元素存取界面").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        rm.lore(rLore)
        rm.persistentDataContainer.set(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING, "OPEN_REFINE")
        refineBtn.itemMeta = rm
        fullInv.setItem(24, refineBtn)
        // =======================================

        // 装饰玻璃板逻辑 (9-18)
        // 中间 (13) 现在已经被占用了，所以只需要填补两边
        // 9, 10, 11, 12 | 13(Button) | 14, 15, 16, 17
        for (i in 9 until 18) {
            if (i != 13) fullInv.setItem(i, glass) // 注意：这里Java复用了 glass (浅灰)，不是 filler (深灰，上面炼化用的)。
        }

        player.openInventory(fullInv)
    }

    // === 事件监听 ===

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            val item = event.item
            if (item != null && item.hasItemMeta()) {
                val id = item.itemMeta.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING)
                if ("MENU_OPENER" == id) {
                    openMenu(event.player)
                    event.isCancelled = true
                }
            }
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.inventory.holder is MenuHolder) {
            event.isCancelled = true

            // [FIXED] 将 player 变量定义前置，解决作用域报错
            val player = event.whoClicked as? Player ?: return

            val clicked = event.currentItem
            if (clicked == null || !clicked.hasItemMeta()) return

            val action = clicked.itemMeta.persistentDataContainer
                .get(NamespacedKey(plugin, "menu_action"), PersistentDataType.STRING)

            if ("OPEN_SETS" == action) {
                plugin.setUI?.openSetList(player)
                return
            }
            if ("OPEN_ACCESSORY" == action) {
                accessoryManager.openAccessoryInventory(player)
                return
            }

            if ("OPEN_REFINE" == action) {
                openRefineMenu(player)
                return
            }

            if ("BACK_MAIN" == action) {
                openMenu(player)
                return
            }

            if ("DEPOSIT_ALL" == action) {
                handleDeposit(player)
                return
            }

            if ("WITHDRAW_ELEMENT" == action) {
                handleWithdraw(player, clicked, event) // 抽取出来的取出逻辑
                return
            }

            if ("OPEN_BANK" == action) {
                // [MODIFIED] 调用 BankUI 打开菜单
                bankInterface?.openBankMenu(player)
                return
            }

            // [NEW] 处理打开仓库
            if ("OPEN_WAREHOUSE" == action) {
                // 通过插件实例获取 WarehouseManager
                plugin.warehouseManager?.openSelectionMenu(player)
                return
            }

            if ("OPEN_QUESTS" == action) {
                plugin.questUI?.openMainMenu(player)
                return
            }

            if ("SWITCH_STANCE" == action) {
                // 1. 冷却检查
                val cd = cooldownManager.getRemainingCooldown(player, "STANCE_SWITCH")
                if (cd > 0) {
                    player.sendMessage("§c切换流派冷却中...")
                    return
                }

                // 2. 切换逻辑
                val current = dataManager.getArrayStance(player)
                val next = current.toggle()
                dataManager.setArrayStance(player, next)

                // 3. 应用冷却 (300秒)
                cooldownManager.setCooldown(player, "STANCE_SWITCH", 300 * 1000)

                player.sendMessage("§a已切换至: ${next.displayName}")
                player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f)

                // 4. 刷新菜单和背包物品 Lore (重要！)
                openMenu(player) // 刷新菜单显示
                // 刷新背包里的物品描述
                for (item in player.inventory.contents) {
                    if (item != null) plugin.itemManager?.syncItem(item, player) // 使用带 player 的 syncItem
                }
            }

            // 切换槽位逻辑
            // === [核心修改] 处理切换按钮 ===
            if ("TOGGLE_HAND" == action) {
                val current = dataManager.getActiveSlot(player)
                // 0 -> 40, 40 -> 0
                val next = if (current == 0) 40 else 0

                dataManager.setActiveSlot(player, next)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
                player.sendMessage("§a激活模式已切换为: " + if (next == 0) "主手" else "副手")

                openMenu(player) // 刷新菜单
                player.updateInventory() // 刷新背包 Lore
                return
            }
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is MenuHolder) {
            for (rawSlot in event.rawSlots) {
                if (rawSlot < 27) { // Size match
                    event.isCancelled = true
                    return
                }
            }
        }
    }

    // 辅助方法：存入
    private fun handleDeposit(player: Player) {
        var totalVal: Long = 0
        for (item in player.inventory.contents) {
            if (item == null || !item.hasItemMeta()) continue
            val type = item.itemMeta.persistentDataContainer.get(BasicKeys.ITEM_TYPE_TAG, PersistentDataType.STRING)
            if ("ELEMENT" == type) {
                val `val` = item.itemMeta.persistentDataContainer.getOrDefault(BasicKeys.ITEM_ELEMENT_VALUE, PersistentDataType.INTEGER, 0)
                if (`val` > 0) {
                    totalVal += (`val` * item.amount).toLong()
                    item.amount = 0
                }
            }
        }
        if (totalVal > 0) {
            dataManager.addElementPoints(player, totalVal)
            player.sendMessage("§a成功存入元素，获得 $totalVal 点灵力！")
            player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f)
            openRefineMenu(player) // 刷新界面
        } else {
            player.sendMessage("§c背包内无可存入的元素物品。")
        }
    }

    // 辅助方法：取出
    private fun handleWithdraw(player: Player, clicked: ItemStack, event: InventoryClickEvent) {
        // 1. 获取单价和ID
        var unitCost = clicked.itemMeta.persistentDataContainer.getOrDefault(BasicKeys.ITEM_ELEMENT_VALUE, PersistentDataType.INTEGER, 0)
        val itemId = clicked.itemMeta.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING) ?: return

        // 2. 决定购买数量
        var amountToBuy = if (event.isShiftClick) 64 else 1

        // 3. 计算玩家能买得起多少
        val currentPoints = dataManager.getElementPoints(player)
        if (unitCost <= 0) unitCost = 999999 // 防止除以0

        // 算出最大可买数量 (long 转 int，因为物品堆叠上限是int)
        val maxAffordable = (currentPoints / unitCost).toInt()

        // 4. 智能调整数量：如果买不起64个，就买能买得起的最大值
        if (amountToBuy > maxAffordable) {
            amountToBuy = maxAffordable
        }

        // 5. 如果连1个都买不起
        if (amountToBuy <= 0) {
            player.sendMessage("§c灵力不足，至少需要 $unitCost 点！")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        // 6. 执行交易
        val totalCost = unitCost.toLong() * amountToBuy

        // 再次确认扣除 (虽然上面算过了，但这步是原子操作)
        if (dataManager.takeElementPoints(player, totalCost)) {
            val newItem = plugin.itemManager?.createItem(itemId, player)
            if (newItem == null) return

            newItem.amount = amountToBuy // 设置数量

            // 检查背包空间 (防止掉地上)
            val leftOver = player.inventory.addItem(newItem)
            if (leftOver.isNotEmpty()) {
                // 如果背包满了，把多余的退还成灵力，或者扔在地上
                // 这里简单处理：扔在地上
                for (drop in leftOver.values) {
                    player.world.dropItem(player.location, drop)
                }
                player.sendMessage("§e背包已满，部分物品掉落在脚下。")
            }

            // 物品名 (显示用)
            val displayName = if (newItem.hasItemMeta() && newItem.itemMeta.hasDisplayName())
                newItem.itemMeta.displayName
            else
                Component.text(newItem.type.name)

            player.sendMessage("§a成功取出 $amountToBuy 个 ${if (displayName is Component) "物品" else displayName}") // 简化了消息处理，可根据需求 refined
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)

            // 刷新界面 (更新余额显示)
            openRefineMenu(player)
        }
    }

    class MenuHolder : InventoryHolder {
        override fun getInventory(): Inventory {
            // 返回 null 是安全的，因为我们不应该直接调用 getInventory 来获取实例
            // 在 Bukkit 插件开发中，这通常只是一个标记
            throw UnsupportedOperationException("MenuHolder is a marker")
        }
    }
}