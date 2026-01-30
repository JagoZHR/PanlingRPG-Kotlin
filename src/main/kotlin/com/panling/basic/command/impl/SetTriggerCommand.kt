package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.PlayerRace
import com.panling.basic.command.SubCommand
import com.panling.basic.manager.LocationManager
import org.bukkit.command.CommandSender

class SetTriggerCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "settrigger"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return

        if (args.size < 2) {
            msg(sender, "§c用法: /plbasic settrigger <类型> <值>")
            return
        }

        val targetBlock = player.getTargetBlockExact(5)
        if (targetBlock == null) {
            msg(sender, "§c请看着一个方块！")
            return
        }

        try {
            val type = LocationManager.TriggerType.valueOf(args[0].uppercase())

            // 智能大小写处理
            val value = if (type == LocationManager.TriggerType.TELEPORT || type == LocationManager.TriggerType.DUNGEON) {
                args[1] // 副本ID和地标名通常是大小写敏感或小写的，保留原样
            } else {
                args[1].uppercase()
            }

            // 校验逻辑
            when (type) {
                LocationManager.TriggerType.CLASS -> PlayerClass.valueOf(value)
                LocationManager.TriggerType.RACE -> PlayerRace.valueOf(value)
                LocationManager.TriggerType.DUNGEON -> {
                    // [新增] 校验副本ID是否存在
                    if (plugin.dungeonManager.getTemplate(value) == null) {
                        msg(sender, "§c副本 ID '$value' 不存在！")
                        return
                    }
                }
                else -> { /* TELEPORT 运行时校验 */ }
            }

            plugin.locationManager.addTrigger(targetBlock.location, type, value)

            msg(sender, "§a成功设置 [$type] 触发器！")
            msg(sender, "§7交互此方块将触发: $value")

        } catch (e: IllegalArgumentException) {
            msg(sender, "§c错误：类型无效或参数不匹配。")
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> LocationManager.TriggerType.values()
                .map { it.name }
                .filter { it.startsWith(args[0].uppercase()) }

            2 -> try {
                val type = LocationManager.TriggerType.valueOf(args[0].uppercase())
                when (type) {
                    LocationManager.TriggerType.CLASS -> PlayerClass.values().map { it.name }
                    LocationManager.TriggerType.RACE -> PlayerRace.values().map { it.name }
                    LocationManager.TriggerType.TELEPORT -> plugin.locationManager.getWaypointNames().toList()
                    // [新增] 补全副本 ID (需要 DungeonManager 提供 getTemplateIds 方法)
                    // 假设 plugin.dungeonManager.templates.keys 是可访问的
                    LocationManager.TriggerType.DUNGEON -> plugin.dungeonManager.getTemplateIds()
                }
            } catch (e: Exception) {
                emptyList()
            }

            else -> emptyList()
        }
    }
}