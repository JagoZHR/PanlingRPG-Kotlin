package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.nms.VolatileWorldFactory
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class DungeonManager(private val plugin: PanlingBasic) : Listener {

    // 容器包装类：记录世界对象和当前加载的模板
    data class DungeonContainer(
        val world: World,
        var currentTemplateId: String? = null
    )

    // 空闲容器池 (线程安全)
    private val availableContainers = ConcurrentLinkedQueue<DungeonContainer>()

    // 活跃容器映射 (WorldName -> Container)
    private val activeContainers = ConcurrentHashMap<String, DungeonContainer>()

    // 玩家映射 (PlayerUUID -> WorldName) 用于快速查找玩家所在的副本
    private val playerDungeonMap = ConcurrentHashMap<java.util.UUID, String>()

    // 配置：池子大小
    private val POOL_SIZE = 10

    init {
        // 1. 注册事件监听 (处理玩家中途退出等情况)
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 2. 初始化 Schematic 管理器 (加载文件)
        // 假设 schematics 文件夹在插件数据目录下
        SchematicManager.init(plugin.dataFolder)

        // 3. 初始化世界池
        initializePool()
    }

    private fun initializePool() {
        plugin.logger.info(">>> [DungeonManager] 正在初始化 $POOL_SIZE 个持久化虚空容器...")
        for (i in 0 until POOL_SIZE) {
            val name = "dungeon_c_$i" // c = container
            try {
                // NMS 创建，无 IO，永不保存
                val world = VolatileWorldFactory.create(name)

                // 冻结规则，节省 CPU
                world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false)
                world.setGameRule(org.bukkit.GameRule.RANDOM_TICK_SPEED, 0)
                world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false)
                world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false)
                world.time = 6000 // 正午

                availableContainers.offer(DungeonContainer(world))
            } catch (e: Exception) {
                plugin.logger.severe("容器 $name 初始化失败: ${e.message}")
                e.printStackTrace()
            }
        }
        plugin.logger.info(">>> [DungeonManager] 初始化完成。")
    }

    /**
     * 玩家请求进入副本
     */
    fun startDungeon(player: Player, templateId: String) {
        if (playerDungeonMap.containsKey(player.uniqueId)) {
            player.sendMessage("§c你已经在一个副本中了！")
            return
        }

        // 1. 检查模板是否存在
        if (SchematicManager.get(templateId) == null) {
            player.sendMessage("§c错误：副本模板文件 $templateId.schem 不存在！")
            return
        }

        // 2. 获取容器
        val container = availableContainers.poll()
        if (container == null) {
            player.sendMessage("§c[系统] 副本实例已满，请稍后再试！")
            return
        }

        // 3. 标记状态
        container.currentTemplateId = templateId
        activeContainers[container.world.name] = container
        playerDungeonMap[player.uniqueId] = container.world.name

        player.sendMessage("§e[系统] 正在构建副本场景，请稍候...")

        // 4. 开始异步构建 (BUILD 模式)
        AdaptiveBuilder.runTask(
            plugin,
            container.world,
            templateId,
            AdaptiveBuilder.Mode.BUILD,
            BlockVector3.at(0, 65, 0) // 粘贴原点，可根据需求修改
        ) {
            // --- 构建完成回调 ---
            // 传送玩家
            val spawnLoc = Location(container.world, 0.5, 66.0, 0.5)
            player.teleport(spawnLoc)
            player.sendMessage("§a[系统] 副本加载完成！")
        }
    }

    /**
     * 结束副本 / 清理回收
     * @param world 副本世界
     * @param force 如果为 true，强制踢人并回收
     */
    fun stopDungeon(world: World) {
        val container = activeContainers[world.name] ?: return

        // 1. 踢出所有玩家
        val lobby = Bukkit.getWorlds()[0].spawnLocation
        for (p in world.players) {
            p.teleport(lobby)
            p.sendMessage("§e副本已结束。")
            playerDungeonMap.remove(p.uniqueId)
        }

        // 2. 清理实体 (掉落物、怪物等)
        world.entities.forEach { if (it !is Player) it.remove() }

        // 3. 获取刚才用的模板 ID，用于定点清理
        val templateId = container.currentTemplateId ?: return

        // plugin.logger.info("正在清理回收实例: ${world.name}")

        // 4. 开始异步清理 (CLEAN 模式)
        AdaptiveBuilder.runTask(
            plugin,
            world,
            templateId,
            AdaptiveBuilder.Mode.CLEAN,
            BlockVector3.at(0, 65, 0)
        ) {
            // --- 清理完成回调 ---
            // 重置状态
            container.currentTemplateId = null
            world.time = 6000
            activeContainers.remove(world.name)

            // 归还池子
            availableContainers.offer(container)
            // plugin.logger.info("实例 ${world.name} 已重置并归还池中。")
        }
    }

    /**
     * 监听玩家退出：如果在副本里下线，自动清理副本
     * 注意：如果是多人副本，这里需要判断 "副本里是否还有其他人"，没人了再清理
     * 本示例默认为单人副本逻辑
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val worldName = playerDungeonMap.remove(event.player.uniqueId) ?: return
        val container = activeContainers[worldName] ?: return

        // 如果副本里没活人了，就回收
        // 注意：PlayerQuitEvent 触发时玩家还在 world.players 里，所以这里判断 size <= 1
        if (container.world.players.size <= 1) {
            stopDungeon(container.world)
        }
    }

    /**
     * 监听传送：如果玩家用指令传出去了，也要处理
     */
    @EventHandler
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val fromWorld = event.from.world ?: return
        val toWorld = event.to?.world ?: return

        if (fromWorld != toWorld && activeContainers.containsKey(fromWorld.name)) {
            // 玩家离开了副本世界
            playerDungeonMap.remove(event.player.uniqueId)

            // 延时一小会检查，确保玩家已经走了
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (fromWorld.players.isEmpty()) {
                    stopDungeon(fromWorld)
                }
            }, 20L)
        }
    }

    /**
     * 关服清理
     */
    fun unloadAll() {
        // 虽然不用 save，但要把所有玩家踢出来，并 unload 释放内存
        val allWorlds = HashSet<World>()
        availableContainers.forEach { allWorlds.add(it.world) }
        activeContainers.values.forEach { allWorlds.add(it.world) }

        val lobby = Bukkit.getWorlds()[0].spawnLocation

        for (w in allWorlds) {
            for (p in w.players) {
                p.teleport(lobby)
            }
            Bukkit.unloadWorld(w, false)
        }
    }
    /**
     * [压力测试] 模拟批量创建副本
     * @param count 模拟并发数量
     * @param templateId 模板ID
     */
    fun stressTest(count: Int, templateId: String) {
        val plugin = this.plugin // 访问外部类属性
        plugin.logger.info(">>> 开始压力测试：同时构建 $count 个 [$templateId] ...")

        var successCount = 0

        for (i in 0 until count) {
            val container = availableContainers.poll()
            if (container == null) {
                plugin.logger.warning(">>> 池子已空，停止压测 (已触发 $i 个)")
                break
            }

            // 标记占用
            container.currentTemplateId = templateId
            activeContainers[container.world.name] = container
            successCount++

            // 启动构建 (BUILD 模式)
            // 注意：这里没有玩家，所以回调里不需要 teleport
            AdaptiveBuilder.runTask(
                plugin,
                container.world,
                templateId,
                AdaptiveBuilder.Mode.BUILD,
                BlockVector3.at(0, 65, 0)
            ) {
                // 构建完成后，自动触发清理 (模拟“打完副本”)
                // 这样能形成一个 建造 -> 清理 的闭环，持续给服务器施压
                plugin.logger.info(">>> [压测] 实例 ${container.world.name} 构建完毕，开始自动清理...")
                stopDungeon(container.world)
            }
        }

        plugin.logger.info(">>> 压测任务下发完毕。共启动: $successCount 个并发构建。")
    }
}