package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import org.bukkit.command.CommandSender

class DelEntityTriggerCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "delentitytrigger"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val p = asPlayer(sender) ?: return

        // 获取视线内的实体 (距离 5 格)
        val target = p.getTargetEntity(5)

        if (target != null) {
            // 清除触发器
            // 假设 LocationManager 中有 clearEntityTriggers 方法
            plugin.locationManager.clearEntityTriggers(target.uniqueId)
            msg(sender, "§a已清除该实体的所有触发器。")
        } else {
            msg(sender, "§c请看着一个生物！")
        }
    }
}