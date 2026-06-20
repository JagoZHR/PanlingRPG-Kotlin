package com.panling.basic.ui

import com.panling.basic.PanlingBasic
import com.panling.basic.api.AttachmentType
import com.panling.basic.api.SpiritAttachment
import com.panling.basic.manager.SpiritAttachmentManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class SpiritAttachmentUI(private val manager: SpiritAttachmentManager) {

    private val PLUGIN = PanlingBasic.instance
    private val ACTION_KEY = NamespacedKey(PLUGIN, "attach_action")
    private val SLOT_KEY = NamespacedKey(PLUGIN, "attach_slot")
    private val BACK_KEY = NamespacedKey(PLUGIN, "ui_back_target")

    // 5槽位对应格子: 10, 12, 14, 16, (居中18格的中间) → 用 11, 13, 15, 20, 22
    private val SLOT_POSITIONS = intArrayOf(10, 12, 14, 16, 22)
    private val TYPE_COLORS = mapOf(
        AttachmentType.EFFICIENCY to NamedTextColor.GREEN,
        AttachmentType.SPECIAL to NamedTextColor.LIGHT_PURPLE,
        AttachmentType.COMBAT to NamedTextColor.RED,
        AttachmentType.FUN to NamedTextColor.YELLOW
    )

    fun open(player: Player) {
        val inv = Bukkit.createInventory(AttachmentHolder(), 36, Component.text("§8❖ 附灵台 ❖"))
        val attachments = manager.getPlayerAttachments(player)

        for ((slot, template) in attachments) {
            val pos = if (slot < SLOT_POSITIONS.size) SLOT_POSITIONS[slot] else continue

            if (template != null) {
                val icon = buildAttachedIcon(template, slot)
                inv.setItem(pos, icon)
            } else {
                val icon = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
                val meta = icon.itemMeta
                meta.displayName(Component.text("§7空槽位 [${slot + 1}]").decoration(TextDecoration.ITALIC, false))
                meta.persistentDataContainer.set(ACTION_KEY, PersistentDataType.STRING, "DETACH")
                meta.persistentDataContainer.set(SLOT_KEY, PersistentDataType.INTEGER, slot)
                icon.itemMeta = meta
                inv.setItem(pos, icon)
            }
        }

        fillBorder(inv)
        addBackButton(inv, 27)
        player.openInventory(inv)
    }

    private fun buildAttachedIcon(template: SpiritAttachment, slot: Int): ItemStack {
        val color = TYPE_COLORS[template.type] ?: NamedTextColor.WHITE
        val icon = ItemStack(Material.ENDER_EYE)
        val meta = icon.itemMeta
        meta.displayName(
            Component.text(template.name).color(color).decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false)
        )
        val lore = ArrayList<Component>()
        lore.add(Component.text("§7类型: ${template.type.displayName} · 等阶 T${template.tier}")
            .decoration(TextDecoration.ITALIC, false))
        lore.add(Component.text("----------------").color(NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false))

        // 效果描述
        for ((stat, value) in template.effects) {
            val desc = statDisplayName(stat)
            val sign = if (value >= 0) "+" else ""
            val pct = if (stat in PERCENT_STATS) "${(value * 100).toInt()}%" else "${value.toInt()}"
            lore.add(Component.text("  $desc: $sign$pct").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false))
        }
        if (template.passiveId != null) {
            lore.add(Component.text("  §b触发特效: ${template.passiveId}").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false))
        }
        if (template.type == AttachmentType.SPECIAL && template.specialMonsters.isNotEmpty()) {
            lore.add(Component.text("  §d可见: ${template.specialMonsters.joinToString(", ")}")
                .color(NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false))
        }
        lore.add(Component.empty())
        lore.add(Component.text("§7拆卸费用: §e${template.tier * 500} 铜钱")
            .decoration(TextDecoration.ITALIC, false))
        lore.add(Component.text("§cShift+左键 拆卸").color(NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false))

        meta.lore(lore)
        meta.persistentDataContainer.set(ACTION_KEY, PersistentDataType.STRING, "DETACH")
        meta.persistentDataContainer.set(SLOT_KEY, PersistentDataType.INTEGER, slot)
        icon.itemMeta = meta
        return icon
    }

    private fun fillBorder(inv: Inventory) {
        val glass = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val meta = glass.itemMeta; meta.displayName(Component.empty()); glass.itemMeta = meta
        for (i in 0 until inv.size) {
            if (i < 9 || i >= inv.size - 9 || i % 9 == 0 || i % 9 == 8) {
                if (inv.getItem(i) == null) inv.setItem(i, glass)
            }
        }
    }

    companion object {
        private val PERCENT_STATS = setOf("crit", "critdmg", "lifesteal", "cdr", "speed", "kb", 
            "phys_pct", "def_pct", "hp_pct", "pen_pct", "skill_pct")

        private fun statDisplayName(short: String): String = when (short) {
            "phys" -> "进攻属性"
            "def" -> "物理防御"
            "hp" -> "生命上限"
            "speed" -> "移动速度"
            "pen" -> "穿透"
            "crit" -> "暴击率"
            "critdmg" -> "暴击伤害"
            "cdr" -> "冷却缩减"
            "lifesteal" -> "生命偷取"
            "kb" -> "击退抗性"
            "skill" -> "法术强度"
            "mdef" -> "法术防御"
            "pen" -> "护甲穿透"
            "range" -> "技能范围"
            else -> short
        }
    }

    class AttachmentHolder : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }

    private fun addBackButton(inv: Inventory, slot: Int) {
        val back = ItemStack(Material.ARROW)
        val meta = back.itemMeta
        meta.displayName(Component.text("⬅ 返回主菜单").color(NamedTextColor.RED)
            .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
        meta.persistentDataContainer.set(BACK_KEY, PersistentDataType.STRING, "MAIN")
        back.itemMeta = meta
        inv.setItem(slot, back)
    }
}
