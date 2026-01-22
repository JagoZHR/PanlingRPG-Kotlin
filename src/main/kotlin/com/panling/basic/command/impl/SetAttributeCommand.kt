package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.command.SubCommand
import com.panling.basic.manager.LoreManager
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.persistence.PersistentDataType

class SetAttributeCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    // 对应基类的 abstract val name
    override val name: String = "set"

    // 默认 isPlayerOnly 为 true，满足玩家手持物品操作的需求

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return

        if (args.size < 2) {
            msg(player, "§c用法: /plbasic set <属性名> <数值>")
            return
        }

        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) {
            msg(player, "§c请手持物品。")
            return
        }

        // 使用 Kotlin 的 Map 索引访问语法获取 NamespacedKey
        val key = BasicKeys.SHORT_NAME_MAP[args[0].lowercase()]

        if (key != null) {
            // 使用 toDoubleOrNull 替代 try-catch 块，使逻辑更扁平
            val value = args[1].toDoubleOrNull()

            if (value != null) {
                val meta = item.itemMeta ?: return
                meta.persistentDataContainer.set(key, PersistentDataType.DOUBLE, value)
                item.itemMeta = meta

                // 刷新物品 Lore
                LoreManager.updateItemLore(item, player)
                msg(player, "§a设置属性成功: ${args[0]} = $value")
            } else {
                msg(player, "§c数值无效。")
            }
        } else {
            msg(player, "§c未知属性名。请使用简写 (如 ad, hp, speed)")
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return if (args.size == 1) {
            // 将 Map 的 keySet 转换为 List 并进行过滤
            BasicKeys.SHORT_NAME_MAP.keys
                .filter { it.startsWith(args[0].lowercase()) }
        } else {
            emptyList()
        }
    }
}