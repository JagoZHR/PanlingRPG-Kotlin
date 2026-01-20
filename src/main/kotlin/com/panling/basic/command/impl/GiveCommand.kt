package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class GiveCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "give"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            msg(sender, "§c用法: /plbasic give <物品ID> [玩家]")
            return
        }

        // 确定目标：如果有第2个参数则查找玩家，否则默认为发送者
        val target: Player? = if (args.size >= 2) {
            Bukkit.getPlayer(args[1])
        } else {
            asPlayer(sender)
        }

        if (target == null) {
            msg(sender, "§c目标玩家不在线或未指定。")
            return
        }

        // 假设 ItemManager 存在 createItem 方法
        // 这里传入 null 作为 ownerContext (原代码逻辑)
        val ni = plugin.itemManager.createItem(args[0], null)

        if (ni != null) {
            target.inventory.addItem(ni)
            msg(sender, "§a给予成功: ${args[0]}")
        } else {
            msg(sender, "§c物品ID不存在: ${args[0]}")
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        if (args.size == 1) {
            // 假设 ItemManager.getItemIds() 返回 Collection<String>
            return plugin.itemManager.itemIds
                .filter { it.startsWith(args[0], ignoreCase = true) }
                .toList()
        }
        // 如果是第二个参数 (玩家名)，返回 null 或 emptyList
        // 返回 emptyList 代表不补全自定义内容 (Bukkit 默认不会自动补全玩家名，除非返回 null，但 SubCommand 定义返回 List)
        // 如果你想让它补全玩家，可以像 ClassCommand 那样写
        return if (args.size == 2) {
            Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
        } else {
            emptyList()
        }
    }
}