package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.manager.PatchSlot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class PatchEmbedUI {
    private val plugin = PanlingBasic.instance
    private val SLOT_KEY = NamespacedKey(plugin, "patch_slot_idx")
    private val EQ_KEY = NamespacedKey(plugin, "patch_eq_slot")

    companion object {
        val embedPhase = HashMap<java.util.UUID, String>()
        val embedNewPatch = HashMap<java.util.UUID, ItemStack>()
        // 避免 PDC 被 syncItem 刷掉，额外存储 stat/pct
        val embedPatchStat = HashMap<java.util.UUID, String?>()
        val embedPatchPct = HashMap<java.util.UUID, Double?>()
        val embedPatchItemId = HashMap<java.util.UUID, String?>()
    }

    fun open(player: Player) {
        embedPhase[player.uniqueId] = "SELECT_PATCH"
        embedNewPatch.remove(player.uniqueId)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            showGuide(player, "请点击背包中的贴片", "左键单击要镶嵌的贴片物品")
        })
    }

    private fun showGuide(player: Player, title: String, sub: String) {
        val inv = Bukkit.createInventory(EmbedHolder(), 9, Component.text("§8❖ 贴片镶嵌 ❖"))
        val icon = ItemStack(Material.BOOK)
        val m = icon.itemMeta
        m.displayName(Component.text("§e$title").decoration(TextDecoration.ITALIC, false))
        m.lore(listOf(Component.text("§7$sub").decoration(TextDecoration.ITALIC, false)))
        icon.itemMeta = m; inv.setItem(4, icon); player.openInventory(inv)
    }

    private fun showResult(player: Player, ok: Boolean, msg: String) {
        embedPhase.remove(player.uniqueId); embedNewPatch.remove(player.uniqueId)
        val inv = Bukkit.createInventory(EmbedHolder(), 9, Component.text("§8❖ 贴片镶嵌 ❖"))
        val icon = ItemStack(if (ok) Material.EMERALD else Material.REDSTONE)
        val m = icon.itemMeta
        m.displayName(Component.text(if (ok) "§a$msg" else "§c$msg").decoration(TextDecoration.ITALIC, false))
        icon.itemMeta = m; inv.setItem(4, icon); player.openInventory(inv)
    }

    fun handleInventoryClick(player: Player, item: ItemStack?, isTop: Boolean) {
        val phase = embedPhase[player.uniqueId] ?: return

        // SLOTS 阶段：处理顶部槽位点击
        if (phase == "SLOTS" && isTop) {
            val idx = item?.itemMeta?.persistentDataContainer?.get(SLOT_KEY, PersistentDataType.INTEGER)
            if (idx != null) {
                val eqSlot = item?.itemMeta?.persistentDataContainer?.get(EQ_KEY, PersistentDataType.STRING)
                handleSlotClick(player, eqSlot, idx)
                return
            }
            // 返回按钮
            if (item?.type == Material.ARROW) {
                cancel(player)
                open(player)
                return
            }
            return
        }

        // 选贴片阶段：只处理背包点击
        if (isTop) return

        if (phase == "SELECT_PATCH") {
            if (item == null || item.type == Material.AIR) return
            val itemId = item.itemMeta.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING)
            val isPatch = (item.hasItemMeta() && item.itemMeta.persistentDataContainer
                .has(NamespacedKey(plugin, "pl_patch_pct"), PersistentDataType.DOUBLE))
                || (itemId != null && plugin.patchManager.getTemplate(itemId) != null)
            if (!isPatch) { player.sendMessage("§c不是贴片物品！"); return }

            embedNewPatch[player.uniqueId] = item.clone()
            // 通过物品 ID 查模板获取 stat 和 pct（不走 PDC，避免 syncItem 刷掉）
            val tpl = itemId?.let { plugin.patchManager.getTemplate(it) }
            embedPatchStat[player.uniqueId] = tpl?.stat
            embedPatchPct[player.uniqueId] = tpl?.perPatchPct
            embedPatchItemId[player.uniqueId] = itemId
            embedPhase[player.uniqueId] = "SLOTS"
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
            Bukkit.getScheduler().runTask(plugin, Runnable { showSlotUI(player) })
            return
        }
    }

    // 装备槽位列表（防具4行 + 武器1行动态检测）
    private val ARMOR_SLOTS = listOf(
        EquipmentSlot.HEAD to "头盔", EquipmentSlot.CHEST to "胸甲",
        EquipmentSlot.LEGS to "护腿", EquipmentSlot.FEET to "靴子"
    )

    private fun buildEquipRows(player: Player): List<Triple<ItemStack, String, String>> {
        val rows = ArrayList<Triple<ItemStack, String, String>>()
        val statCalc = plugin.statCalculator

        // 4 行防具
        for ((eqSlot, eqName) in ARMOR_SLOTS) {
            val eq = player.inventory.getItem(eqSlot) ?: continue
            if (!eq.hasItemMeta()) continue
            if (statCalc.isFabao(eq)) continue
            val itemId = eq.itemMeta.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING) ?: continue
            rows.add(Triple(eq, eqName, itemId))
        }

        // 1 行武器（根据 activeSlot 取激活位武器，排除法宝）
        val activeSlot = plugin.playerDataManager.getActiveSlot(player)
        val weaponItem = if (activeSlot == -1) player.inventory.itemInOffHand
                         else player.inventory.getItem(activeSlot)
        if (weaponItem != null && weaponItem.hasItemMeta() && !statCalc.isFabao(weaponItem)) {
            val itemId = weaponItem.itemMeta.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING)
            if (itemId != null) {
                rows.add(Triple(weaponItem, "武器 [激活]", itemId))
            }
        }
        return rows
    }

    private fun showSlotUI(player: Player) {
        val newPatch = embedNewPatch[player.uniqueId] ?: return
        val pm = plugin.patchManager
        val inv = Bukkit.createInventory(EmbedHolder(), 54, Component.text("§8❖ 选择槽位镶嵌"))
        val rows = buildEquipRows(player)

        for (rowIdx in rows.indices) {
            val (eq, eqName, itemId) = rows[rowIdx]
            val patches = pm.getPatchesForItem(player, itemId)
            val slots = pm.getPatchSlots(player, itemId)

            // 行标题
            val eqIcon = eq.clone()
            val em = eqIcon.itemMeta
            em.displayName(Component.text("§e$eqName").decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            eqIcon.itemMeta = em
            inv.setItem(rowIdx * 9, eqIcon)

            for (i in 0 until slots) {
                if (i >= 6) break
                if (i < patches.size) {
                    val p = patches[i]
                    val icon = patchIcon(player, p)
                    val m = icon.itemMeta
                    m.displayName(Component.text("§d${ForgeUI.statDisplay(p.stat)} +${(p.pct*100).toInt()}%").decoration(TextDecoration.ITALIC, false))
                    m.lore(listOf(
                        Component.text("§c点击覆盖为新贴片").decoration(TextDecoration.ITALIC, false),
                        Component.text("§7旧片将被销毁").decoration(TextDecoration.ITALIC, false)
                    ))
                    m.persistentDataContainer.set(SLOT_KEY, PersistentDataType.INTEGER, i)
                    m.persistentDataContainer.set(EQ_KEY, PersistentDataType.STRING, itemId)
                    icon.itemMeta = m; inv.setItem(rowIdx * 9 + 1 + i, icon)
                } else {
                    val glass = ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                    val m = glass.itemMeta
                    m.displayName(Component.text("§7空槽位 [${i+1}/$slots]").decoration(TextDecoration.ITALIC, false))
                    m.lore(listOf(Component.text("§a点击嵌入新贴片").decoration(TextDecoration.ITALIC, false)))
                    m.persistentDataContainer.set(SLOT_KEY, PersistentDataType.INTEGER, i)
                    m.persistentDataContainer.set(EQ_KEY, PersistentDataType.STRING, itemId)
                    glass.itemMeta = m; inv.setItem(rowIdx * 9 + 1 + i, glass)
                }
            }
        }

        // 预览
        val preview = newPatch.clone()
        val pm2 = preview.itemMeta
        val l2 = ArrayList<Component>(); pm2.lore()?.forEach { l2.add(it) }
        l2.add(Component.text("§b▲ 点击上方槽位嵌入").decoration(TextDecoration.ITALIC, false))
        pm2.lore(l2); preview.itemMeta = pm2
        inv.setItem(52, preview)
        // 返回
        val back = ItemStack(Material.ARROW)
        val bm = back.itemMeta
        bm.displayName(Component.text("§7⬅ 返回重新选贴片").decoration(TextDecoration.ITALIC, false))
        back.itemMeta = bm; inv.setItem(53, back)
        player.openInventory(inv)
    }

    private fun handleSlotClick(player: Player, equipId: String?, slotIdx: Int) {
        if (equipId == null) { player.sendMessage("§7操作已过期"); return }
        val newStat = embedPatchStat[player.uniqueId]
        val newPct = embedPatchPct[player.uniqueId]
        if (newStat == null || newPct == null) { player.sendMessage("§7贴片数据异常"); return }

        val pm = plugin.patchManager
        val patches = pm.getPatchesForItem(player, equipId)
        val currentPct = patches.filter { it.stat == newStat }.sumOf { it.pct }
        val cap = pm.getStatCaps()[newStat] ?: 0.3
        val effectiveCurrent = if (slotIdx < patches.size && patches[slotIdx].stat == newStat) currentPct - patches[slotIdx].pct else currentPct

        if (effectiveCurrent + newPct > cap) {
            player.sendMessage("§c${ForgeUI.statDisplay(newStat)}超上限！(${(cap*100).toInt()}%)")
            cancel(player); player.closeInventory()
            return
        }

        val success = if (slotIdx < patches.size)
            pm.overwritePatch(player, equipId, slotIdx, newStat, newPct)
        else
            pm.embedPatch(player, equipId, newStat, newPct)

        if (success) {
            // 通过 ITEM_ID 消耗背包中的贴片（ITEM_ID 不会被 syncItem 刷掉）
            val patchId = embedPatchItemId[player.uniqueId]
            if (patchId != null) {
                val inInv = player.inventory.contents.find {
                    it != null && it.hasItemMeta() &&
                    it.itemMeta.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING) == patchId
                }
                if (inInv != null && inInv.amount > 0) inInv.amount--
            }
            player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1f, 1.5f)
            player.sendMessage("§a贴片镶嵌成功！(§e${ForgeUI.statDisplay(newStat)} +${(newPct*100).toInt()}%§a)")
        } else {
            player.sendMessage("§c贴片镶嵌失败")
        }
        cancel(player)
        player.closeInventory()
    }

    fun cancel(player: Player) {
        embedPhase.remove(player.uniqueId)
        embedNewPatch.remove(player.uniqueId)
        embedPatchStat.remove(player.uniqueId)
        embedPatchPct.remove(player.uniqueId)
        embedPatchItemId.remove(player.uniqueId)
    }

    // 根据贴片数据反查模板获取实际物品材质
    private fun patchIcon(player: Player, p: PatchSlot): ItemStack {
        val tpl = plugin.patchManager.findTemplate(p.stat, p.pct)
        return if (tpl != null) {
            plugin.itemManager.createItem(tpl.id, player)?.clone() ?: ItemStack(Material.ENDER_EYE)
        } else {
            ItemStack(Material.ENDER_EYE)
        }
    }

    class EmbedHolder : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }

    // 装备信息（只读）
    fun openEquipInfo(player: Player) {
        val pm = plugin.patchManager
        val inv = Bukkit.createInventory(EmbedHolder(), 54, Component.text("§8❖ 装备贴片信息"))
        val rows = buildEquipRows(player)

        for (rowIdx in rows.indices) {
            val (eq, eqName, itemId) = rows[rowIdx]
            val patches = pm.getPatchesForItem(player, itemId)
            val slots = pm.getPatchSlots(player, itemId)

            val eqIcon = eq.clone()
            val em = eqIcon.itemMeta

            // 构建贴片加成汇总 lore
            val lore = ArrayList<Component>()
            if (patches.isNotEmpty()) {
                // 按 stat 聚合贴片百分比
                val patchByStat = HashMap<String, Double>()
                for (p in patches) {
                    patchByStat[p.stat] = (patchByStat[p.stat] ?: 0.0) + p.pct
                }

                val pdc = eq.itemMeta?.persistentDataContainer
                lore.add(Component.text("§6━━ 贴片加成 ━━").decoration(TextDecoration.ITALIC, false))

                for ((stat, totalPct) in patchByStat) {
                    val pdcKey = BasicKeys.SHORT_NAME_MAP[stat]
                    val baseValue = if (pdcKey != null && pdc != null)
                        pdc.get(pdcKey, PersistentDataType.DOUBLE) ?: 0.0
                    else 0.0
                    val bonusAmount = baseValue * totalPct
                    val displayName = ForgeUI.statDisplay(stat)
                    val pctDisplay = (totalPct * 100).toInt()

                    // 百分比属性（暴击/冷却等）：bonus 也按百分比显示
                    val isPercentStat = BasicKeys.STAT_METADATA[pdcKey]?.isPercent == true
                    val bonusStr = if (isPercentStat) {
                        String.format("%.1f%%", bonusAmount * 100)
                    } else {
                        val raw = String.format("%.1f", bonusAmount)
                        if (raw.endsWith(".0")) raw.substring(0, raw.length - 2) else raw
                    }

                    lore.add(Component.text("§b$displayName：${pctDisplay}%（+${bonusStr}）")
                        .decoration(TextDecoration.ITALIC, false))
                }
            } else {
                lore.add(Component.text("§7无贴片加成").decoration(TextDecoration.ITALIC, false))
            }

            em.lore(lore)
            em.displayName(Component.text("§e$eqName").decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            eqIcon.itemMeta = em; inv.setItem(rowIdx * 9, eqIcon)

            for (i in 0 until slots) {
                if (i >= 6) break
                if (i < patches.size) {
                    val p = patches[i]
                    val icon = patchIcon(player, p)
                    val m = icon.itemMeta
                    m.displayName(Component.text("§d${ForgeUI.statDisplay(p.stat)} +${(p.pct*100).toInt()}%").decoration(TextDecoration.ITALIC, false))
                    icon.itemMeta = m; inv.setItem(rowIdx * 9 + 1 + i, icon)
                } else {
                    inv.setItem(rowIdx * 9 + 1 + i, ItemStack(Material.AIR))
                }
            }
        }
        player.openInventory(inv)
    }
}
