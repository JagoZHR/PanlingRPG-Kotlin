package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.nms.VolatileWorldFactory
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 最小化核心版 DungeonManager
 * 用于测试 Kotlin + NMS 环境下的世界生成与管理
 */
class DungeonManager(private val plugin: PanlingBasic) {

    // [测试用] 存储活跃的副本世界，Key=世界名
    // 真实的 Manager 会存储 DungeonInstance 对象，这里我们只存 World 对象用于测试
    private val activeWorlds = ConcurrentHashMap<String, World>()

    /**
     * 启动测试副本
     * 模拟原本 startDungeon 的核心流程：创建世界 -> 传送玩家
     */
    fun startTestDungeon(player: Player) {
        player.sendMessage("§e[Manager] 正在通过工厂构建虚空副本...")

        // 1. 生成随机世界名
        val worldName = "dungeon_test_${UUID.randomUUID().toString().substring(0, 8)}"
        val world: World

        try {
            // 2. 调用 NMS 工厂 (核心验证点)
            world = VolatileWorldFactory.create(worldName)

            // 3. 注册到管理器
            activeWorlds[worldName] = world

            player.sendMessage("§a[Manager] 副本世界构建成功: ${world.name}")

            // 4. 模拟简单的“进本”逻辑
            // 因为没有 Copier 复制地图，我们在脚下放块玻璃防止掉下去
            val spawnLoc = Location(world, 0.5, 65.0, 0.5)
            spawnLoc.block.type = Material.GLASS // 安全垫

            // 传送
            player.teleport(spawnLoc)
            player.sendMessage("§a[Manager] 已传送至副本实例。")

        } catch (e: Exception) {
            player.sendMessage("§c[Manager] 创建失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 卸载指定的测试世界
     */
    fun unloadTestWorld(world: World) {
        val worldName = world.name

        // 1. 踢出玩家
        if (world.players.isNotEmpty()) {
            val lobby = Bukkit.getWorlds()[0].spawnLocation
            for (p in world.players) {
                p.teleport(lobby)
                p.sendMessage("§e[Manager] 副本已销毁。")
            }
        }

        // 2. 卸载世界
        // false = 不保存 (核心特性)
        val success = Bukkit.unloadWorld(world, false)

        if (success) {
            activeWorlds.remove(worldName)
            plugin.logger.info("已卸载测试副本: $worldName")
        } else {
            plugin.logger.warning("卸载失败: $worldName")
        }
    }

    /**
     * 卸载所有活跃的副本 (用于插件关闭时)
     */
    fun unloadAll() {
        // 创建一个副本列表进行遍历，防止并发修改异常
        val worlds = ArrayList(activeWorlds.values)
        worlds.forEach { unloadTestWorld(it) }
    }
}