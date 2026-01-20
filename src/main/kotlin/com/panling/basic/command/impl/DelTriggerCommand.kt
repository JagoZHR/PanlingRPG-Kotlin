package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.command.CommandSender

class DelTriggerCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "deltrigger"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val p = asPlayer(sender) ?: return

        val delTarget = p.getTargetBlockExact(5)
        if (delTarget != null) {
            // 假设 LocationManager 存在 clearTriggers 方法
            plugin.locationManager.clearTriggers(delTarget.location)
            msg(sender, "§a已移除该方块上的所有触发器。")
        } else {
            msg(sender, "§c请看着一个方块！")
        }
    }
}