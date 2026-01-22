package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.command.SubCommand
import com.panling.basic.manager.LoreManager
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.persistence.PersistentDataType

class ReqClassCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "reqclass"

    // 基类默认 isPlayerOnly 为 true，此处无需显式重写

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return

        if (args.isEmpty()) {
            msg(player, "§c用法: /plbasic reqclass <职业>")
            return
        }

        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) {
            msg(player, "§c请手持物品。")
            return
        }

        try {
            // 使用 uppercase() 替代 toUpperCase()
            val pc = PlayerClass.valueOf(args[0].uppercase())

            // 使用安全调用和属性访问
            val meta = item.itemMeta ?: return
            meta.persistentDataContainer.set(BasicKeys.FEATURE_REQ_CLASS, PersistentDataType.STRING, pc.name)
            item.itemMeta = meta

            // 刷新 Lore (调用 Java 静态方法)
            LoreManager.updateItemLore(item, player)
            msg(player, "§a绑定职业限制: ${pc.name}")
        } catch (e: Exception) {
            msg(player, "§c职业无效。")
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return if (args.size == 1) {
            // 使用 Kotlin 惯用的集合操作替代 Stream
            PlayerClass.values()
                .map { it.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }
        } else {
            emptyList()
        }
    }
}