package com.panling.basic

import net.minecraft.server.level.ServerLevel
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player

object NMSVerifier {

    fun verify(player: Player) {
        val bukkitWorld = player.world
        player.sendMessage("§e[系统] 开始环境自检 (安全版)...")

        try {
            // 1. 验证 CraftBukkit 映射
            if (bukkitWorld !is CraftWorld) {
                player.sendMessage("§c[失败] 世界对象不是 CraftWorld！")
                return
            }
            player.sendMessage("§a[通过] CraftWorld 映射正常")

            // 2. 验证 NMS 映射
            // 这步如果成功，说明 NMS 包名映射没问题
            val nmsLevel: ServerLevel = bukkitWorld.handle
            player.sendMessage("§a[通过] NMS ServerLevel 获取成功")

            // 3. 验证字段读取 (只读你反编译确认存在的字段)
            // 读取 uuid (public final UUID uuid;)
            val uuid = nmsLevel.uuid
            // 读取 noSave (public boolean noSave;)
            val noSave = nmsLevel.noSave
            // 读取 LevelData (public final PrimaryLevelData serverLevelData;)
            val levelData = nmsLevel.serverLevelData

            player.sendMessage("§b[信息] NMS 字段读取成功:")
            player.sendMessage("§7 - 类名: ${nmsLevel.javaClass.simpleName}")
            player.sendMessage("§7 - UUID: $uuid")
            player.sendMessage("§7 - 禁止保存: $noSave")
            player.sendMessage("§7 - 数据对象: ${levelData.javaClass.simpleName}")

            player.sendMessage("§a====== 环境配置完美！Kotlin + NMS 正常运行 ======")

        } catch (e: Throwable) {
            player.sendMessage("§c[致命错误] 验证过程发生异常: ${e.message}")
            e.printStackTrace()
        }
    }
}