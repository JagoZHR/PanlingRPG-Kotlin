package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.manager.QuestManager
import com.panling.basic.quest.Quest
import com.panling.basic.quest.QuestProgress
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class QuestUI(
    private val plugin: PanlingBasic,
    private val questManager: QuestManager
) : Listener {

    // Keys
    private val keyMenuAction = NamespacedKey(plugin, "menu_action")
    private val keyQuestId = NamespacedKey("panling", "quest_id")

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    // === 1. 打开任务主菜单 (一级菜单) ===
    fun openMainMenu(player: Player) {
        val inv = Bukkit.createInventory(QuestHolder("MAIN"), 27, Component.text("§8任务日志"))

        // 背景填充
        fillBackground(inv)

        // --- 按钮 1: 正在进行 (Slot 11) ---
        val activeList = questManager.getActiveQuests(player)
        val activeBtn = ItemStack(Material.WRITABLE_BOOK).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("§e正在进行").decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(
                    listOf(
                        Component.text("§7当前有 §f${activeList.size} §7个任务正在进行")
                            .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("§a[点击查看详情]").decoration(TextDecoration.ITALIC, false)
                    )
                )
                meta.persistentDataContainer.set(keyMenuAction, PersistentDataType.STRING, "OPEN_ACTIVE")
            }
        }
        inv.setItem(11, activeBtn)

        // --- 按钮 2: 可接取/线索 (Slot 13) ---
        val availableList = questManager.getAvailableQuests(player)
        val availableCount = availableList.size

        val clueBtn = ItemStack(Material.MAP).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("§b新任务线索").decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(
                    listOf(
                        Component.text("§7当前区域发现 §f$availableCount §7个新线索")
                            .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("§a[点击查看线索]").decoration(TextDecoration.ITALIC, false)
                    )
                )
                meta.persistentDataContainer.set(keyMenuAction, PersistentDataType.STRING, "OPEN_AVAILABLE")
            }
        }
        inv.setItem(13, clueBtn)

        // --- 按钮 3: 已完成 (Slot 15) ---
        val completedList = questManager.getCompletedQuests(player)
        val doneBtn = ItemStack(Material.ENCHANTED_BOOK).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("§a已完成任务").decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(
                    listOf(
                        Component.text("§7你已经完成了 §f${completedList.size} §7个委托")
                            .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("§a[点击回顾历史]").decoration(TextDecoration.ITALIC, false)
                    )
                )
                meta.persistentDataContainer.set(keyMenuAction, PersistentDataType.STRING, "OPEN_COMPLETED")
            }
        }
        inv.setItem(15, doneBtn)

        // --- 返回按钮 (Slot 22) ---
        val back = ItemStack(Material.ARROW).apply {
            editMeta { meta ->
                meta.displayName(Component.text("§7返回主菜单").decoration(TextDecoration.ITALIC, false))
                meta.persistentDataContainer.set(keyMenuAction, PersistentDataType.STRING, "BACK_MAIN")
            }
        }
        inv.setItem(22, back)

        player.openInventory(inv)
    }

    // === 2. 打开分类列表 (二级菜单) ===
    // type: "ACTIVE", "COMPLETED", "AVAILABLE"
    fun openCategoryMenu(player: Player, type: String) {
        val title = when (type) {
            "ACTIVE" -> "§0正在进行的任务"
            "COMPLETED" -> "§0已完成的任务档案"
            "AVAILABLE" -> "§0可接取的委托"
            else -> "§0任务列表"
        }

        val inv = Bukkit.createInventory(QuestHolder("LIST_$type"), 54, Component.text(title))

        // 1. 获取对应列表
        val displayList = when (type) {
            "ACTIVE" -> questManager.getActiveQuests(player).map { QuestDataWrapper(it.quest, it) }
            "COMPLETED" -> questManager.getCompletedQuests(player).map { QuestDataWrapper(it.quest, it) }
            "AVAILABLE" -> questManager.getAvailableQuests(player).map { QuestDataWrapper(it, null) }
            else -> emptyList()
        }

        // 2. 渲染图标
        var slot = 0
        for (wrapper in displayList) {
            if (slot >= 45) break // 分页逻辑暂略
            val icon = createQuestIcon(wrapper.quest, wrapper.progress, type)
            inv.setItem(slot++, icon)
        }

        // 3. 底部功能栏
        fillBottomBar(inv)

        // 返回按钮 (Slot 49)
        val back = ItemStack(Material.ARROW).apply {
            editMeta { meta ->
                meta.displayName(Component.text("§7返回上一级").decoration(TextDecoration.ITALIC, false))
                meta.persistentDataContainer.set(keyMenuAction, PersistentDataType.STRING, "BACK_QUEST_MAIN")
            }
        }
        inv.setItem(49, back)

        player.openInventory(inv)
    }

    // === 辅助：创建任务图标 ===
    private fun createQuestIcon(quest: Quest, progress: QuestProgress?, type: String): ItemStack {
        val mat = if ("COMPLETED" == type) Material.BLAZE_POWDER else Material.BOOK
        return ItemStack(mat).apply {
            editMeta { meta ->
                // 标题
                meta.displayName(
                    Component.text("§6${quest.name}").decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )

                val lore = ArrayList<Component>()
                lore.add(Component.empty())

                // 描述
                lore.add(
                    Component.text(quest.description).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(Component.empty())

                // 目标进度 (如果是进行中)
                if ("ACTIVE" == type && progress != null) {
                    lore.add(Component.text("§e当前进度:").decoration(TextDecoration.ITALIC, false))
                    for (obj in quest.objectives) {
                        val current = progress.getProgress(obj.id)
                        val total = obj.requiredAmount
                        val color = if (current >= total) "§a" else "§7"
                        val check = if (current >= total) "✔ " else "- "

                        lore.add(
                            Component.text("$color$check${obj.description} ($current/$total)")
                                .decoration(TextDecoration.ITALIC, false)
                        )
                    }
                }

                when (type) {
                    "AVAILABLE" -> lore.add(
                        Component.text("§a[点击接取任务]").decoration(TextDecoration.ITALIC, false)
                    )
                    "ACTIVE" -> lore.add(
                        Component.text("§e[点击追踪/取消追踪]").decoration(TextDecoration.ITALIC, false)
                    )
                }

                meta.lore(lore)
                // 存入 QuestID 以便点击识别
                meta.persistentDataContainer.set(keyQuestId, PersistentDataType.STRING, quest.id)
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }
    }

    // === 事件监听 ===

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? QuestHolder ?: return
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        val clicked = event.currentItem ?: return
        if (!clicked.hasItemMeta()) return

        val pdc = clicked.itemMeta.persistentDataContainer
        val action = pdc.get(keyMenuAction, PersistentDataType.STRING)

        // 1. 处理主菜单点击
        when (action) {
            "OPEN_ACTIVE" -> openCategoryMenu(player, "ACTIVE")
            "OPEN_COMPLETED" -> openCategoryMenu(player, "COMPLETED")
            "OPEN_AVAILABLE" -> openCategoryMenu(player, "AVAILABLE")
            "BACK_MAIN" -> plugin.menuManager.openMenu(player)
            "BACK_QUEST_MAIN" -> openMainMenu(player)
        }

        // 2. 处理任务图标点击
        val questId = pdc.get(keyQuestId, PersistentDataType.STRING)
        if (questId != null) {
            // 这里处理点击具体的任务
            if (holder.type == "LIST_AVAILABLE") {
                val quest = questManager.getQuest(questId)
                val npcId = quest?.startNpcId

                if (npcId != null) {
                    val npc = plugin.npcManager.getNpc(npcId)
                    if (npc != null) {
                        // [核心] 指向 NPC
                        player.compassTarget = npc.location
                        player.sendMessage("§b[新任务线索] §7请前往寻找: §f${npc.name}")
                        player.sendMessage("§7位置已标记在指南针上 (${npc.location.distance(player.location).toInt()}m)")
                        player.closeInventory()
                    } else {
                        player.sendMessage("§c数据错误：找不到该任务的发布人线索。")
                    }
                }
            } else if (holder.type != "LIST_ACTIVE") {
                player.sendMessage("§7该任务无明确线索$questId")
            }
        }

        // 3. 处理正在进行的任务 (追踪逻辑)
        if (holder.type == "LIST_ACTIVE" && questId != null) {
            val qp = questManager.getActiveProgress(player, questId)

            if (qp != null) {
                // 找到第一个未完成的目标
                for (obj in qp.quest.objectives) {
                    if (qp.getProgress(obj.id) < obj.requiredAmount) {
                        // 尝试获取位置
                        val loc = obj.navigationLocation
                        if (loc != null) {
                            // [核心] 设置原版指南针指向
                            player.compassTarget = loc

                            player.sendMessage("§a[任务追踪] §7指南针已指向: §f${obj.description}")
                            player.sendMessage("§7目标坐标: ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}")
                            player.playSound(player.location, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1f, 1f)
                            player.closeInventory()
                            return
                        }
                    }
                }
                player.sendMessage("§c该任务当前阶段暂无明确坐标指引。")
            }
        }
    }

    // === 杂项辅助 ===

    private fun fillBackground(inv: Inventory) {
        val glass = ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        for (i in 0 until inv.size) {
            if (inv.getItem(i) == null) inv.setItem(i, glass)
        }
    }

    private fun fillBottomBar(inv: Inventory) {
        val glass = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        for (i in 45 until 54) {
            inv.setItem(i, glass)
        }
    }

    // 简单包装类
    private data class QuestDataWrapper(val quest: Quest, val progress: QuestProgress?)

    class QuestHolder(val type: String) : InventoryHolder {
        override fun getInventory(): Inventory {
            throw UnsupportedOperationException("QuestHolder does not hold the inventory instance directly.")
        }
    }
}