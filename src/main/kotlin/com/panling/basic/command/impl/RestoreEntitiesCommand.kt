package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.command.CommandSender

class RestoreEntitiesCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "restoreentities"

    // 作为维护类指令，通常允许控制台执行，故覆盖默认的 true
    override val isPlayerOnly: Boolean = false

    override fun perform(sender: CommandSender, args: Array<out String>) {
        // 使用 Kotlin 属性访问语法获取 LocationManager 并执行修复
        val count = plugin.locationManager.restoreMissingEntities()

        // 使用字符串模板优化消息拼接
        msg(sender, "§a检查完成，共修复/重生了 $count 个丢失的交互实体。")
    }
}