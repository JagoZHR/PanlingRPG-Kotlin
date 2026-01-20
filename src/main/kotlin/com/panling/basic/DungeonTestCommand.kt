package com.panling.basic

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class DungeonTestCommand(private val plugin: PanlingBasic) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true

        if (args.isEmpty()) {
            sender.sendMessage("§c用法:")
            sender.sendMessage("§c/dungeontest enter <schem> - 进入副本")
            sender.sendMessage("§c/dungeontest leave - 离开副本")
            sender.sendMessage("§c/dungeontest stress <schem> <数量> - 压力测试")
            return true
        }

        val action = args[0].lowercase()

        when (action) {
            "enter" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c请指定模板名")
                    return true
                }
                plugin.dungeonManager.startDungeon(sender, args[1])
            }

            "leave" -> {
                val world = sender.world
                // 检查玩家是否在副本世界 (通过名字前缀判断，或者让 Manager 判断)
                // 最稳妥的是调用 stopDungeon，Manager 会自己判断是不是活跃副本
                plugin.dungeonManager.stopDungeon(world)
                // 注意：Manager 的 stopDungeon 会把世界里所有人踢出来
            }

            "stress" -> {
                if (!sender.isOp) return true
                if (args.size < 3) {
                    sender.sendMessage("§c用法: /dungeontest stress <schem> <数量>")
                    return true
                }
                val template = args[1]
                val count = args[2].toIntOrNull() ?: 5

                sender.sendMessage("§e[压测] 正在后台启动 $count 个副本构建任务...")
                sender.sendMessage("§e[压测] 请关注 /mspt 或 /tps")

                plugin.dungeonManager.stressTest(count, template)
            }

            // 兼容旧写法，直接输名字
            else -> {
                plugin.dungeonManager.startDungeon(sender, args[0])
            }
        }

        return true
    }
}