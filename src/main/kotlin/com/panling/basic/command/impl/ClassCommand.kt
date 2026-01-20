package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.command.SubCommand
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class ClassCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    override val name: String = "class"

    override fun perform(sender: CommandSender, args: Array<out String>) {
        // /plbasic class <玩家> <职业>
        if (args.size < 2) {
            msg(sender, "§c用法: /plbasic class <玩家选择器> <职业>")
            return
        }

        try {
            // 1. 解析目标实体 (支持选择器 @a, @p 等)
            val targets = Bukkit.selectEntities(sender, args[0])

            // 2. 解析职业枚举
            val targetClass = try {
                PlayerClass.valueOf(args[1].uppercase())
            } catch (e: IllegalArgumentException) {
                msg(sender, "§c未知职业: ${args[1]}")
                return
            }

            var count = 0

            for (entity in targets) {
                if (entity is Player) {
                    // [潜在依赖点] 清理旧职业遗留 (如金属流派熔炉)
                    // 如果 MetalAttackT5FurnaceStrategy 还没迁移或不存在，这一行会报错。
                    // 建议：等那个类迁移完再取消注释，或者确保该类在 classpath 中
                    try {
                        com.panling.basic.skill.strategy.metal.MetalAttackT5FurnaceStrategy.clearPlayer(entity.uniqueId)
                    } catch (ignored: NoClassDefFoundError) {
                        // 忽略类未找到错误 (方便迁移过程中测试)
                    } catch (ignored: Exception) {}

                    // 设置职业
                    plugin.playerDataManager.setPlayerClass(entity, targetClass)
                    plugin.playerDataManager.setActiveSlot(entity, 0)

                    entity.sendMessage("§a你的职业已变更为: ${targetClass.displayName}")

                    // 刷新背包 (触发 InventoryListener 的更新逻辑)
                    entity.updateInventory()
                    count++
                }
            }
            msg(sender, "§a已更新 $count 名玩家的职业。")

        } catch (e: IllegalArgumentException) {
            msg(sender, "§c选择器错误: ${e.message}")
        } catch (e: Exception) {
            msg(sender, "§c执行错误: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        // 参数 1: 补全玩家名称
        if (args.size == 1) {
            return Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }

        // 参数 2: 补全职业枚举
        if (args.size == 2) {
            return PlayerClass.values()
                .map { it.name }
                .filter { it.startsWith(args[1], ignoreCase = true) }
        }

        return emptyList()
    }
}