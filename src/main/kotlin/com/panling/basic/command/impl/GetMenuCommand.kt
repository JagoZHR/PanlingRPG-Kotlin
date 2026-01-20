package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.command.CommandSender

class GetMenuCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "getmenu"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val p = asPlayer(sender) ?: return

        // 假设 MenuManager 存在且有 getMenuItem() 方法 (Kotlin 中访问属性 menuItem)
        p.inventory.addItem(plugin.menuManager.menuItem)
        msg(sender, "§a已获得菜单开启道具！")
    }
}