package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.PlayerRace
import com.panling.basic.command.SubCommand
import com.panling.basic.manager.LocationManager
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

class SetEntityTriggerCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "setentitytrigger"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return

        if (args.size < 2) {
            msg(sender, "§c用法: /plbasic setentitytrigger <类型> <值>")
            return
        }

        val targetEntity = player.getTargetEntity(5)
        if (targetEntity == null) {
            msg(sender, "§c请看着一个生物/NPC！")
            return
        }

        try {
            val type = LocationManager.TriggerType.valueOf(args[0].uppercase())
            val value = if (type == LocationManager.TriggerType.TELEPORT) args[1] else args[1].uppercase()

            when (type) {
                LocationManager.TriggerType.CLASS -> PlayerClass.valueOf(value)
                LocationManager.TriggerType.RACE -> PlayerRace.valueOf(value)
                else -> { /* 其他类型 */ }
            }

            // 对接 LocationManager.kt 的方法
            plugin.locationManager.addEntityTrigger(targetEntity.uniqueId, type, value)
            plugin.locationManager.registerManagedEntity(targetEntity)

            msg(sender, "§a成功绑定触发器到实体！UUID: ${targetEntity.uniqueId}")

            targetEntity.isGlowing = true
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                targetEntity.isGlowing = false
            }, 20L)

        } catch (e: IllegalArgumentException) {
            msg(sender, "§c参数错误。")
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> {
                LocationManager.TriggerType.values()
                    .map { it.name }
                    .filter { it.startsWith(args[0], ignoreCase = true) }
            }
            2 -> {
                try {
                    val type = LocationManager.TriggerType.valueOf(args[0].uppercase())
                    when (type) {
                        LocationManager.TriggerType.CLASS -> PlayerClass.values().map { it.name }
                        LocationManager.TriggerType.RACE -> PlayerRace.values().map { it.name }
                        // 修正点：显式调用方法 getWaypointNames() 并转为 List
                        LocationManager.TriggerType.TELEPORT -> plugin.locationManager.getWaypointNames().toList()
                        else -> emptyList()
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }
}