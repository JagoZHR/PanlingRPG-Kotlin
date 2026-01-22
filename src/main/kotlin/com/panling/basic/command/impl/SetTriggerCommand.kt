package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.PlayerRace
import com.panling.basic.command.SubCommand
import com.panling.basic.manager.LocationManager
import org.bukkit.command.CommandSender

class SetTriggerCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    // 对应基类的 abstract val name
    override val name: String = "settrigger"

    // 基类默认 isPlayerOnly 为 true，满足获取玩家准星方块的需求

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return

        if (args.size < 2) {
            msg(sender, "§c用法: /plbasic settrigger <类型> <值>")
            return
        }

        // 获取准星指向的方块（距离5格）
        val targetBlock = player.getTargetBlockExact(5)
        if (targetBlock == null) {
            msg(sender, "§c请看着一个方块！")
            return
        }

        try {
            val type = LocationManager.TriggerType.valueOf(args[0].uppercase())

            // 智能大小写处理：地标名保留原样，其他（枚举）转大写
            val value = if (type == LocationManager.TriggerType.TELEPORT) {
                args[1]
            } else {
                args[1].uppercase()
            }

            // 校验逻辑：验证职业或种族是否存在
            when (type) {
                LocationManager.TriggerType.CLASS -> PlayerClass.valueOf(value)
                LocationManager.TriggerType.RACE -> PlayerRace.valueOf(value)
                else -> { /* TELEPORT 不需要额外枚举校验 */ }
            }

            // 调用 LocationManager 存储触发器
            plugin.locationManager.addTrigger(targetBlock.location, type, value)

            msg(sender, "§a成功设置 [$type] 触发器！")
            msg(sender, "§7交互此方块将触发: $value")

        } catch (e: IllegalArgumentException) {
            msg(sender, "§c错误：类型或值无效。")
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return when (args.size) {
            // 参数 1：补全触发器类型
            1 -> LocationManager.TriggerType.values()
                .map { it.name }
                .filter { it.startsWith(args[0].uppercase()) }

            // 参数 2：根据类型补全具体的值
            2 -> try {
                val type = LocationManager.TriggerType.valueOf(args[0].uppercase())
                when (type) {
                    LocationManager.TriggerType.CLASS -> PlayerClass.values().map { it.name }
                    LocationManager.TriggerType.RACE -> PlayerRace.values().map { it.name }
                    // 调用 LocationManager 的 getWaypointNames() 方法
                    LocationManager.TriggerType.TELEPORT -> plugin.locationManager.getWaypointNames().toList()
                }
            } catch (e: Exception) {
                emptyList()
            }

            else -> emptyList()
        }
    }
}