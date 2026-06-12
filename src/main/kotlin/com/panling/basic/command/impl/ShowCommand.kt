package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.command.SubCommand
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class ShowCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "show"
    override val permission: String? = null  // 所有玩家可用
    override val isPlayerOnly: Boolean = true

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        val item = player.inventory.itemInMainHand

        if (item.type == Material.AIR) {
            msg(sender, "§c你手上没有物品！")
            return
        }

        val showMsg = buildShowMessage(player, item)
        // 广播给所有在线玩家
        for (online in Bukkit.getOnlinePlayers()) {
            online.sendMessage(showMsg)
        }

        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f)
    }

    private fun buildShowMessage(player: Player, item: ItemStack): Component {
        val builder = Component.text()

        // [展示]
        builder.append(Component.text("§e§l[展示] ", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))

        // 玩家名
        builder.append(Component.text("${player.name} ", NamedTextColor.GOLD))

        // "展示了"
        builder.append(Component.text("展示了 ", NamedTextColor.GRAY))

        // 物品 — 优先用 display name，否则用类型名
        val meta = item.itemMeta
        if (meta != null && meta.hasDisplayName()) {
            builder.append(meta.displayName()!!)
        } else {
            // 中文映射原版物品名
            val cnName = getChineseItemName(item.type)
            builder.append(Component.text(cnName).color(NamedTextColor.WHITE))
        }

        // ×数量 (大于1时显示)
        if (item.amount > 1) {
            builder.append(Component.text(" §7x${item.amount}", NamedTextColor.GRAY))
        }

        // —— 插件物品额外信息悬浮 ——
        if (item.hasItemMeta()) {
            val meta = item.itemMeta
            val lore = meta.lore()
            val itemId = meta.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING)

            if (lore != null && lore.isNotEmpty()) {
                // 把 lore 拼成 hover 文本
                val hoverText = Component.text()
                if (itemId != null) {
                    hoverText.append(Component.text("§8ID: $itemId\n"))
                }
                for (line in lore) {
                    hoverText.append(line).append(Component.newline())
                }
                builder.hoverEvent(HoverEvent.showText(hoverText))
            }
        }

        return builder.build()
    }

    private fun getChineseItemName(material: Material): String {
        return when (material) {
            Material.DIAMOND_SWORD -> "钻石剑"
            Material.IRON_SWORD -> "铁剑"
            Material.GOLDEN_SWORD -> "金剑"
            Material.STONE_SWORD -> "石剑"
            Material.WOODEN_SWORD -> "木剑"
            Material.NETHERITE_SWORD -> "下界合金剑"
            Material.BOW -> "弓"
            Material.CROSSBOW -> "弩"
            Material.TRIDENT -> "三叉戟"
            Material.SHIELD -> "盾牌"
            Material.DIAMOND_AXE -> "钻石斧"
            Material.IRON_AXE -> "铁斧"
            Material.GOLDEN_AXE -> "金斧"
            Material.DIAMOND_PICKAXE -> "钻石镐"
            Material.IRON_PICKAXE -> "铁镐"
            Material.DIAMOND_HELMET -> "钻石头盔"
            Material.DIAMOND_CHESTPLATE -> "钻石胸甲"
            Material.DIAMOND_LEGGINGS -> "钻石护腿"
            Material.DIAMOND_BOOTS -> "钻石靴子"
            Material.IRON_HELMET -> "铁头盔"
            Material.IRON_CHESTPLATE -> "铁胸甲"
            Material.IRON_LEGGINGS -> "铁护腿"
            Material.IRON_BOOTS -> "铁靴子"
            Material.GOLDEN_HELMET -> "金头盔"
            Material.GOLDEN_CHESTPLATE -> "金胸甲"
            Material.GOLDEN_LEGGINGS -> "金护腿"
            Material.GOLDEN_BOOTS -> "金靴子"
            Material.NETHERITE_HELMET -> "下界合金头盔"
            Material.NETHERITE_CHESTPLATE -> "下界合金胸甲"
            Material.NETHERITE_LEGGINGS -> "下界合金护腿"
            Material.NETHERITE_BOOTS -> "下界合金靴子"
            Material.ELYTRA -> "鞘翅"
            Material.TOTEM_OF_UNDYING -> "不死图腾"
            Material.ENDER_PEARL -> "末影珍珠"
            Material.GOLDEN_APPLE -> "金苹果"
            Material.ENCHANTED_GOLDEN_APPLE -> "附魔金苹果"
            Material.BLAZE_ROD -> "烈焰棒"
            Material.NETHER_STAR -> "下界之星"
            Material.DRAGON_EGG -> "龙蛋"
            Material.DRAGON_HEAD -> "龙头"
            else -> material.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }
        }
    }
}
