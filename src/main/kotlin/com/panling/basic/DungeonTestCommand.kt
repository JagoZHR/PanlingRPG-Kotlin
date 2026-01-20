package com.panling.basic

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.system.measureNanoTime
import org.bukkit.Bukkit

// 注入 Plugin 实例以便访问 Manager
class DungeonTestCommand(private val plugin: PanlingBasic) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c只有玩家可以使用此命令")
            return true
        }

        // 参数处理：
        // /dungeontest       -> 创建并进入
        // /dungeontest clean -> 清理当前所在的副本
        if (args.isNotEmpty() && args[0].equals("clean", ignoreCase = true)) {
            val world = sender.world
            if (world.name.startsWith("dungeon_test_")) {
                sender.sendMessage("§e正在请求卸载当前世界...")
                plugin.dungeonManager.unloadTestWorld(world)
            } else {
                sender.sendMessage("§c你不在测试副本中！")
            }
            return true
        }
        if (args.isNotEmpty() && args[0].equals("stress", ignoreCase = true)) {
            sender.sendMessage("§c[警告] 正在进行高压测试 (20连发)...")

            val totalTime = measureNanoTime {
                for (i in 1..20) {
                    // 这里我们就不传送了，只管生成
                    plugin.dungeonManager.startTestDungeon(sender)
                }
            }

            val ms = totalTime / 1_000_000.0
            sender.sendMessage("§e[报告] 20个世界生成完毕！总耗时: ${String.format("%.3f", ms)} ms")
            sender.sendMessage("§e[分析] 平均每个世界耗时: ${String.format("%.3f", ms / 20)} ms")
            return true
        }

        if (args.isNotEmpty() && args[0].equals("monitor", ignoreCase = true)) {
            sender.sendMessage("§b[监测] 启动主线程心跳监测 (持续10秒)...")

            var lastTime = System.currentTimeMillis()
            var taskId = -1

            // 每 1 Tick (50ms) 运行一次
            taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
                val now = System.currentTimeMillis()
                val delta = now - lastTime

                // 如果两帧之间间隔超过 60ms (允许10ms误差)，说明有卡顿
                if (delta > 60) {
                    val lag = delta - 50
                    sender.sendMessage("§c[卡顿警告] 主线程阻塞！延迟: ${delta}ms (丢帧: ${lag}ms)")
                }

                lastTime = now
            }, 0L, 1L)

            // 10秒后自动关闭监测
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                Bukkit.getScheduler().cancelTask(taskId)
                sender.sendMessage("§b[监测] 监测结束。")
            }, 200L)

            return true
        }

        // 性能测试模式
        sender.sendMessage("§e[测试] 请求 DungeonManager 创建副本...")

        try {
            val elapsedNs = measureNanoTime {
                // 调用 Manager 的方法
                plugin.dungeonManager.startTestDungeon(sender)
            }

            val elapsedMs = elapsedNs / 1_000_000.0
            sender.sendMessage("§a[性能] Manager 处理耗时: ${String.format("%.3f", elapsedMs)} ms")

        } catch (e: Exception) {
            sender.sendMessage("§c[错误] 流程异常: ${e.message}")
            e.printStackTrace()
        }

        return true
    }
}