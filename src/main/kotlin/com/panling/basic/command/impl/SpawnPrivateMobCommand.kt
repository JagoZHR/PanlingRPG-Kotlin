package com.panling.basic.command.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.command.SubCommand
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.persistence.PersistentDataType
import java.util.*

class SpawnPrivateMobCommand(plugin: PanlingBasic) : SubCommand(plugin) {

    // 对应基类的 abstract val name
    override val name: String = "spawnprivatemob"

    // 基类默认 isPlayerOnly 为 true，符合生成怪物需要玩家位置的需求

    override fun perform(sender: CommandSender, args: Array<out String>) {
        // 使用基类提供的安全转换方法
        val player = asPlayer(sender) ?: return

        if (args.isEmpty()) {
            msg(sender, "§c用法: /plbasic spawnprivatemob <MobID> [OwnerName/fake]")
            return
        }

        val pMobId = args[0]
        var targetOwner = player

        // 处理可选的第二个参数
        if (args.size >= 2) {
            val targetName = args[1]

            // 处理 "fake" 逻辑
            if (targetName.equals("fake", ignoreCase = true)) {
                val mob = plugin.mobManager.spawnMob(player.location, pMobId)
                if (mob != null) {
                    // 设置 PDC 数据并设置发光
                    mob.persistentDataContainer.set(
                        BasicKeys.MOB_OWNER,
                        PersistentDataType.STRING,
                        UUID.randomUUID().toString()
                    )
                    mob.isGlowing = true
                    msg(sender, "§a已生成属于 [假人] 的私有怪。")
                } else {
                    msg(sender, "§c怪物ID无效。")
                }
                return
            }

            // 获取目标玩家
            val foundPlayer = Bukkit.getPlayer(targetName)
            if (foundPlayer == null) {
                msg(sender, "§c玩家不在线。")
                return
            }
            targetOwner = foundPlayer
        }

        // 调用 MobManager 生成私有怪
        if (plugin.mobManager.spawnPrivateMob(player.location, pMobId, targetOwner) != null) {
            msg(sender, "§a已为 ${targetOwner.name} 生成私有怪: $pMobId")
        } else {
            msg(sender, "§c怪物ID无效。")
        }
    }

    override fun getTabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        return when (args.size) {
            // 第一个参数：补全怪物 ID
            1 -> plugin.mobManager.mobIds
                .filter { it.startsWith(args[0], ignoreCase = true) }

            // 第二个参数：手动补全在线玩家（替代 Java 中的 null 返回）
            2 -> Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[1], ignoreCase = true) }

            else -> emptyList()
        }
    }
}