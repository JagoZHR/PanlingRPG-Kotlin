package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.Reloadable
import com.panling.basic.dungeon.nms.VolatileWorldFactory
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.dungeon.phase.WaitingPhase
import com.panling.basic.util.ClassScanner
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class DungeonManager(private val plugin: PanlingBasic) : Reloadable, Listener {

    // 容器包装类：底层的 NMS 世界
    data class DungeonContainer(
        val world: World,
        var currentTemplateId: String? = null
    )

    // --- 核心数据结构 ---
    private val templates = ConcurrentHashMap<String, DungeonTemplate>()

    // [NEW] 逻辑工厂注册表 (Code-First 核心)
    // Key: templateId (yml文件名), Value: 初始化该副本第一阶段的函数
    private val logicRegistry = HashMap<String, (DungeonInstance) -> AbstractDungeonPhase>()

    // 活跃的副本实例 (Key: Instance ID String)
    private val activeInstances = ConcurrentHashMap<String, DungeonInstance>()

    // 玩家 -> 副本实例 ID 映射
    private val playerInstanceMap = ConcurrentHashMap<UUID, String>()

    // --- 资源池管理 (保留原逻辑) ---
    private val availableContainers = ConcurrentLinkedQueue<DungeonContainer>()
    private val worldContainerMap = ConcurrentHashMap<String, DungeonContainer>()
    private val POOL_SIZE = 10

    init {
        // 注册重载与监听
        try {
            plugin.reloadManager.register(this)
        } catch (ignored: Exception) {}
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 1. 初始化 Schematic 管理器
        SchematicManager.init(plugin.dataFolder)

        // 2. 初始化静态世界池
        initializePool()

        // 3. 加载模板
        loadTemplates()

        // [新增] 自动扫描并加载所有副本逻辑
        loadAllLogics()

        // 4. 启动心跳 (每秒 tick 一次所有活跃副本)
        // 注意：具体的 Phase tick 由 Instance 驱动，这里只是驱动 Instance
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickAllInstances() }, 20L, 20L)
    }

    // ==========================================
    // API: 注册副本逻辑
    // ==========================================

    /**
     * [核心修改] 自动扫描逻辑提供者
     */
    private fun loadAllLogics() {
        logicRegistry.clear()
        plugin.logger.info("开始扫描副本逻辑...")

        // 扫描 com.panling.basic 包下所有实现了 DungeonLogicProvider 的类
        val classes = ClassScanner.scanClasses(plugin, "com.panling.basic", DungeonLogicProvider::class.java)

        var count = 0
        for (clazz in classes) {
            try {
                // 实例化逻辑提供者 (尝试构造函数注入 plugin)
                val provider = try {
                    val constructor = clazz.getConstructor(PanlingBasic::class.java)
                    constructor.newInstance(plugin)
                } catch (e: NoSuchMethodException) {
                    val constructor = clazz.getConstructor()
                    constructor.newInstance()
                }

                // 注册逻辑
                // 这里将 provider.createInitialPhase 作为一个函数引用存入注册表
                registerLogic(provider.templateId) { instance ->
                    provider.createInitialPhase(instance)
                }
                count++
            } catch (e: Exception) {
                plugin.logger.severe("加载副本逻辑类 ${clazz.simpleName} 失败: ${e.message}")
                e.printStackTrace()
            }
        }
        plugin.logger.info("共自动注册了 $count 个副本逻辑。")
    }

    /**
     * 将代码中写的副本逻辑注册给管理器
     * @param templateId 对应 dungeons 文件夹下的 yml 文件名
     * @param factory 创建该副本**初始阶段**的工厂函数
     */
    fun registerLogic(templateId: String, factory: (DungeonInstance) -> AbstractDungeonPhase) {
        logicRegistry[templateId] = factory
        plugin.logger.info("已挂载副本逻辑: $templateId")
    }

    // ==========================================
    // 资源池逻辑 (保留)
    // ==========================================

    private fun initializePool() {
        plugin.logger.info(">>> [DungeonManager] 正在初始化 $POOL_SIZE 个持久化虚空容器...")
        for (i in 0 until POOL_SIZE) {
            val name = "dungeon_c_$i"
            try {
                // NMS 创建
                val world = VolatileWorldFactory.create(name)

                // 冻结规则
                world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false)
                world.setGameRule(org.bukkit.GameRule.RANDOM_TICK_SPEED, 0)
                world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false)
                world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false)
                world.time = 6000

                val container = DungeonContainer(world)
                availableContainers.offer(container)
                worldContainerMap[name] = container
            } catch (e: Exception) {
                plugin.logger.severe("容器 $name 初始化失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // ==========================================
    // 流程控制
    // ==========================================

    /**
     * [API] 玩家请求进入副本
     */
    fun startDungeon(leader: Player, templateId: String) {
        val template = templates[templateId]
        if (template == null) {
            leader.sendMessage("§c副本配置不存在！")
            return
        }
        if (isPlaying(leader)) {
            leader.sendMessage("§c你已经在一个副本中了！")
            return
        }

        // --- 1. 前置检查 ---
        if (leader.level < template.minLevel) {
            leader.sendMessage("§c等级不足！需要: ${template.minLevel}")
            return
        }

        // 检查门票 (保留原逻辑)
        /*
        if (template.ticketItem != null) {
            // 需要实现 checkAndConsumeTicket
        }
        */

        // --- 2. 资源分配 ---
        // 检查 Schematic 是否存在 (依赖 SchematicManager)
        if (SchematicManager.get(template.schematicName) == null) {
            leader.sendMessage("§c错误：副本建筑文件 ${template.schematicName}.schem 未找到！")
            return
        }

        val container = availableContainers.poll()
        if (container == null) {
            leader.sendMessage("§c[系统] 副本实例已满，请稍后再试！")
            return
        }

        // 标记占用
        container.currentTemplateId = template.id // 注意这里用 template.id

        leader.sendMessage("§e[系统] 正在构建副本场景 [${template.displayName}]...")

        // --- 3. 异步构建 (使用 AdaptiveBuilder) ---
        // 这里的 BlockVector3.at(0, 65, 0) 是粘贴的原点，通常建议固定
        AdaptiveBuilder.runTask(
            plugin,
            container.world,
            template.schematicName,
            AdaptiveBuilder.Mode.BUILD,
            BlockVector3.at(0, 65, 0)
        ) {
            // --- 4. 构建完成：初始化游戏实例 ---

            // 生成唯一 ID
            val instanceId = UUID.randomUUID().toString()

            // 构造实例 (适配新版 DungeonInstance 构造函数)
            // 假设单人进入，如果是组队，这里传入 players 列表
            val initialPlayers = listOf(leader)

            val instance = DungeonInstance(
                plugin,
                instanceId,
                template,
                container.world,
                initialPlayers
            )

            // 注册到内存
            activeInstances[instanceId] = instance
            initialPlayers.forEach { playerInstanceMap[it.uniqueId] = instanceId }

            // 触发进本逻辑 (传送玩家)
            initialPlayers.forEach { instance.join(it) }

            // [核心修复] 获取代码逻辑，若无则报错并销毁
            val factory = logicRegistry[template.id]
            if (factory == null) {
                // 严重错误：代码没写
                val msg = "严重错误：副本模板 ${template.id} 存在，但未在代码中注册 Phase 逻辑！"
                plugin.logger.severe(msg)
                leader.sendMessage("§c$msg")

                // 立即销毁实例
                removeInstance(instanceId)
                return@runTask
            }

            // 正式启动逻辑
            val initialPhase = factory(instance)
            instance.start(initialPhase)
        }
    }

    /**
     * [系统调用] 销毁/回收副本实例
     */
    fun removeInstance(instanceId: String) {
        val instance = activeInstances.remove(instanceId) ?: return
        val world = instance.world

        // 1. 清理玩家映射
        instance.players.forEach { pUid ->
            playerInstanceMap.remove(pUid)
        }

        // 2. 确保所有玩家都已踢出 (防止 DungeonInstance.stop 没处理干净)
        val lobby = Bukkit.getWorlds()[0].spawnLocation
        world.players.forEach {
            it.teleport(lobby)
            it.sendMessage("§e副本已强制关闭。")
        }

        // 3. 清理实体
        world.entities.forEach { if (it !is Player) it.remove() }

        // 4. 异步拆除建筑并回收 (AdaptiveBuilder Mode.CLEAN)
        // 使用 AdaptiveBuilder 恢复世界状态，比重新生成世界快得多
        val currentTemplateId = worldContainerMap[world.name]?.currentTemplateId

        // 只有当确有模板ID时才清理，否则直接回收
        if (currentTemplateId != null) {
            // 查找模板对应的 schematic 名称
            val schematicName = templates[currentTemplateId]?.schematicName ?: "default"

            AdaptiveBuilder.runTask(
                plugin,
                world,
                schematicName,
                AdaptiveBuilder.Mode.CLEAN,
                BlockVector3.at(0, 65, 0)
            ) {
                recycleContainer(world.name)
            }
        } else {
            recycleContainer(world.name)
        }
    }

    private fun recycleContainer(worldName: String) {
        val container = worldContainerMap[worldName] ?: return

        // 重置状态
        container.currentTemplateId = null
        container.world.time = 6000

        // 归还池子
        availableContainers.offer(container)
        // plugin.logger.info("容器 $worldName 已回收。")
    }

    /**
     * 玩家离开副本 (主动离开/完成/失败)
     */
    fun leaveDungeon(player: Player) {
        val instanceId = playerInstanceMap[player.uniqueId] ?: return
        val instance = activeInstances[instanceId] ?: return

        // 调用 Instance 的移除逻辑 (Instance 内部处理 TP)
        instance.leave(player)

        // 更新管理器映射
        playerInstanceMap.remove(player.uniqueId)

        // 延时检查是否空了
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (instance.players.isEmpty()) {
                removeInstance(instanceId)
            }
        }, 20L)
    }

    // --- 辅助查询 ---
    fun isPlaying(player: Player): Boolean = playerInstanceMap.containsKey(player.uniqueId)

    fun getInstance(player: Player): DungeonInstance? {
        val id = playerInstanceMap[player.uniqueId] ?: return null
        return activeInstances[id]
    }

    private fun tickAllInstances() {
        // 由于 DungeonInstance 内部有 taskTimer，这里其实可以不用
        // 但如果 DungeonInstance 改为了被动 tick，则需要这里调用
        // activeInstances.values.forEach { it.onTick() }
    }

    // --- 模板加载 ---

    override fun reload() {
        loadTemplates()
        plugin.logger.info("副本模板已重载。")
    }

    private fun loadTemplates() {
        templates.clear()
        val folder = File(plugin.dataFolder, "dungeons")
        if (!folder.exists()) folder.mkdirs()

        folder.listFiles { _, name -> name.endsWith(".yml") }?.forEach { file ->
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val id = file.nameWithoutExtension

                // [修改] 不再加载 phases 配置
                val template = DungeonTemplate(
                    id = id,
                    displayName = config.getString("display_name", id)!!,
                    schematicName = config.getString("world_source", "default")!!, // 注意这里字段名
                    minLevel = config.getInt("requirements.min_level", 0),
                    minPlayers = config.getInt("settings.min_players", 1),
                    maxPlayers = config.getInt("settings.max_players", 4),
                    timeLimit = config.getInt("settings.time_limit", 1800),
                    spawnOffset = parseVector(config.getString("settings.spawn_loc", "0, 65, 0")!!),
                    exitLoc = parseLocation(config.getString("settings.exit_loc"))
                )
                templates[id] = template
            } catch (e: Exception) {
                plugin.logger.severe("加载副本模板失败: ${file.name}")
                e.printStackTrace()
            }
        }
        plugin.logger.info("加载了 ${templates.size} 个副本模板。")
    }

    // --- 事件监听 ---

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        leaveDungeon(event.player)
    }

    @EventHandler
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val fromWorld = event.from.world ?: return
        val toWorld = event.to?.world ?: return

        // 防逃逸：如果玩家从副本世界 TP 到了非副本世界
        if (fromWorld != toWorld && worldContainerMap.containsKey(fromWorld.name)) {
            leaveDungeon(event.player)
        }
    }

    // --- 工具 ---
    private fun parseVector(str: String): Vector {
        val parts = str.split(",").map { it.trim().toDouble() }
        return Vector(parts.getOrElse(0){0.0}, parts.getOrElse(1){0.0}, parts.getOrElse(2){0.0})
    }

    private fun parseLocation(str: String?): Location? {
        if (str.isNullOrEmpty()) return null
        return try {
            val parts = str.split(",").map { it.trim() }
            val world = Bukkit.getWorld(parts[0])
            if (world != null) {
                Location(
                    world,
                    parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble(),
                    if (parts.size > 4) parts[4].toFloat() else 0f,
                    if (parts.size > 5) parts[5].toFloat() else 0f
                )
            } else null
        } catch (e: Exception) { null }
    }
    /**
     * 通过世界获取副本实例 (用于处理怪物死亡等非玩家事件)
     */
    fun getInstance(world: World): DungeonInstance? {
        // 遍历活跃实例查找 (由于副本数通常不多，遍历开销可忽略)
        return activeInstances.values.find { it.world == world }
    }
}