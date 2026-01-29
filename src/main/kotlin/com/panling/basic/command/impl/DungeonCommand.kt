package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.command.SubCommand
import com.panling.basic.dungeon.InstanceLocationProvider
import com.panling.basic.dungeon.SchematicManager
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

class DungeonCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "dungeon"
    override val permission: String? = null

    override fun perform(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /plbasic dungeon <enter|leave|info|stress>")
            return
        }

        if (args[0].equals("leave", ignoreCase = true)) {
            if (sender is Player) {
                plugin.dungeonManager.leaveDungeon(sender)
            } else {
                sender.sendMessage("控制台无法离开副本。")
            }
            return
        }

        if (args[0].equals("stress", ignoreCase = true)) {
            if (!sender.hasPermission("panling.admin")) {
                sender.sendMessage("§c权限不足。")
                return
            }
            if (args.size < 3) {
                sender.sendMessage("§c用法: /plbasic dungeon stress <templateId> <count>")
                return
            }
            val templateId = args[1]
            val count = args[2].toIntOrNull() ?: 1

            sender.sendMessage("§6[压力测试] 启动！计划生成 $count 个 [$templateId]...")
            val start = System.currentTimeMillis()

            repeat(count) { i ->
                val (loc, index) = InstanceLocationProvider.getNextLocation()
                val schematicName = templateId
                val world = loc.world!!

                // [核心修复] 必须先异步加载区块，就像 DungeonManager 里做的一样！
                val futures = ArrayList<CompletableFuture<Chunk>>()

                // 等待所有区块加载完毕
                CompletableFuture.allOf(*futures.toTypedArray()).thenAccept {
                    // 回到主线程提交任务
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        SchematicManager.pasteAsync(plugin, loc, schematicName) {
                            val time = System.currentTimeMillis() - start
                            if (i % 10 == 0 || i == count - 1) {
                                sender.sendMessage("§a[进度] 副本 #$i (Index: $index) 生成完毕! 累计耗时: ${time}ms")
                            }
                        }
                    })
                }
            }
            return
        }

        if (sender !is Player) {
            sender.sendMessage("§c只有玩家可以使用此指令。")
            return
        }

        when (args[0].lowercase()) {
            "enter" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c请输入副本ID。")
                    return
                }
                plugin.dungeonManager.startDungeon(sender, args[1])
            }
            "info" -> {
                val instance = plugin.dungeonManager.getInstance(sender)
                if (instance == null) {
                    sender.sendMessage("§7你当前不在副本中。")
                } else {
                    sender.sendMessage("§e=== 副本调试信息 ===")
                    sender.sendMessage("§fID: §b${instance.instanceId}")
                    sender.sendMessage("§f模板: §a${instance.template.displayName}")
                    sender.sendMessage("§f中心点: §7${instance.centerLocation.blockX}, ${instance.centerLocation.blockY}, ${instance.centerLocation.blockZ}")
                    sender.sendMessage("§f当前阶段: §d${instance.currentPhase?.javaClass?.simpleName ?: "无"}")
                    sender.sendMessage("§f运行Tick: §7${instance.tickCount}")
                }
            }
            else -> sender.sendMessage("§c未知子指令: ${args[0]}")
        }
    }
}