package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.Reloadable
import com.panling.basic.dungeon.nms.VolatileWorldFactory
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
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

    // 活跃的副本实例 (UUID 是 Instance 的 ID，不是玩家 ID)
    // 这里存储的是正在进行的“游戏局”，它持有一个 DungeonContainer
    private val activeInstances = ConcurrentHashMap<UUID, DungeonInstance>()

    // 玩家 -> 副本实例 ID 映射
    private val playerInstanceMap = ConcurrentHashMap<UUID, UUID>()

    // --- 资源池管理 ---
    private val availableContainers = ConcurrentLinkedQueue<DungeonContainer>()
    // 记录世界名对应的容器 (方便通过 World 查找)
    private val worldContainerMap = ConcurrentHashMap<String, DungeonContainer>()
    private val POOL_SIZE = 10

    // --- 阶段注册表 ---
    val phaseRegistry = DungeonPhaseRegistry()

    init {
        plugin.reloadManager.register(this)
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 1. 初始化 Schematic 管理器
        SchematicManager.init(plugin.dataFolder)

        // 2. 初始化静态世界池
        initializePool()

        // 3. 加载模板
        loadTemplates()

        // 4. 启动心跳 (每秒 tick 一次所有活跃副本)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickAllInstances() }, 20L, 20L)
    }

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

    override fun reload() {
        loadTemplates()
        plugin.logger.info("副本模板已重载。")
    }

    /**
     * 加载所有 YAML 模板
     */
    private fun loadTemplates() {
        templates.clear()
        val folder = File(plugin.dataFolder, "dungeons")
        if (!folder.exists()) folder.mkdirs()

        folder.listFiles { _, name -> name.endsWith(".yml") }?.forEach { file ->
            try {
                val id = file.nameWithoutExtension
                val config = YamlConfiguration.loadConfiguration(file)
                loadOneTemplate(id, config)
            } catch (e: Exception) {
                plugin.logger.severe("加载副本模板失败: ${file.name}")
                e.printStackTrace()
            }
        }
        plugin.logger.info("加载了 ${templates.size} 个副本模板。")
    }

    private fun loadOneTemplate(id: String, config: YamlConfiguration) {
        // 使用 Kotlin 的 run 块来组织逻辑
        val template = DungeonTemplate(
            id = id,
            displayName = config.getString("display_name", id)!!,
            // worldSource 在新架构中不再是指 Bukkit World，而是 Schematic 文件名
            // 假设 world_source 填的是 "room1" (对应 room1.schem)
            schematicName = config.getString("world_source", "world")!!,
            minLevel = config.getInt("requirements.min_level", 0),
            questReq = config.getString("requirements.quest"),
            ticketItem = config.getString("requirements.ticket_item"),
            consumeTicket = config.getBoolean("requirements.consume_ticket", true),
            minPlayers = config.getInt("settings.min_players", 1),
            maxPlayers = config.getInt("settings.max_players", 4),
            timeLimit = config.getInt("settings.time_limit", 1800),
            // 解析出生点偏移量 (相对于 Schematic 原点)
            spawnOffset = parseVector(config.getString("settings.spawn_loc", "0, 65, 0")!!),
            exitLoc = parseLocation(config.getString("settings.exit_loc")),
            phaseConfig = config.getConfigurationSection("phases")
        )
        templates[id] = template
    }

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

        // 检查任务 (假设 QuestManager 已存在，或暂且注释)
        /* val qm = plugin.questManager
        if (template.questReq != null && !qm.hasCompleted(leader, template.questReq)) {
             // ... 逻辑 ...
             return
        }
        */

        // 检查门票
        if (template.ticketItem != null) {
            if (!checkAndConsumeTicket(leader, template)) {
                leader.sendMessage("§c缺少入场凭证！")
                return
            }
        }

        // --- 2. 资源分配 ---
        // 检查 Schematic 是否存在
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
        container.currentTemplateId = template.schematicName

        leader.sendMessage("§e[系统] 正在构建副本场景 [${template.displayName}]...")

        // --- 3. 异步构建 ---
        AdaptiveBuilder.runTask(
            plugin,
            container.world,
            template.schematicName,
            AdaptiveBuilder.Mode.BUILD,
            BlockVector3.at(0, 65, 0)
        ) {
            // --- 4. 构建完成：初始化游戏实例 ---

            // 创建逻辑层的 Instance (后续需要重写 DungeonInstance.kt)
            val instance = DungeonInstance(plugin, this, template, leader, container.world)
            activeInstances[instance.uuid] = instance
            playerInstanceMap[leader.uniqueId] = instance.uuid

            // 触发进本逻辑 (如传送、开始阶段)
            instance.start()
        }
    }

    /**
     * 玩家离开副本 (主动离开/完成/失败)
     */
    fun leaveDungeon(player: Player) {
        val instanceId = playerInstanceMap[player.uniqueId] ?: return
        val instance = activeInstances[instanceId] ?: return

        // 调用 Instance 的移除逻辑 (它内部应该调用 dungeonManager.removePlayerMap)
        instance.removePlayer(player)
    }

    /**
     * [系统调用] 销毁/回收副本实例
     */
    fun removeInstance(uuid: UUID) {
        val instance = activeInstances.remove(uuid) ?: return
        val world = instance.world

        // 1. 清理玩家映射
        instance.players.forEach { pUid ->
            playerInstanceMap.remove(pUid)
        }

        // 2. 踢出所有玩家
        val lobby = Bukkit.getWorlds()[0].spawnLocation
        world.players.forEach {
            it.teleport(lobby)
            it.sendMessage("§e副本已结束。")
        }

        // 3. 清理实体
        world.entities.forEach { if (it !is Player) it.remove() }

        // 4. 异步拆除建筑并回收
        val templateId = worldContainerMap[world.name]?.currentTemplateId ?: return

        // plugin.logger.info("正在回收副本: ${world.name}")
        AdaptiveBuilder.runTask(
            plugin,
            world,
            templateId,
            AdaptiveBuilder.Mode.CLEAN,
            BlockVector3.at(0, 65, 0)
        ) {
            // 重置状态
            worldContainerMap[world.name]?.currentTemplateId = null
            world.time = 6000

            // 归还池子
            worldContainerMap[world.name]?.let { availableContainers.offer(it) }
        }
    }

    // --- 辅助方法 ---

    private fun checkAndConsumeTicket(player: Player, template: DungeonTemplate): Boolean {
        // Kotlin 风格的查找
        val item = player.inventory.contents.find {
            it != null && it.hasItemMeta() &&
                    it.itemMeta!!.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING) == template.ticketItem
        }

        if (item != null) {
            if (template.consumeTicket) {
                item.amount = item.amount - 1
            }
            return true
        }
        return false
    }

    // 解析 Phases 配置 (供 DungeonInstance 使用)
    fun createPhases(config: ConfigurationSection?): List<AbstractDungeonPhase> {
        val list = ArrayList<AbstractDungeonPhase>()
        if (config == null) return list

        config.getKeys(false).sorted().forEach { key ->
            val pConfig = config.getConfigurationSection(key)
            if (pConfig != null) {
                val type = pConfig.getString("type", "")!!.uppercase()
                val phase = phaseRegistry.createPhase(type)

                if (phase != null) {
                    try {
                        phase.load(pConfig)
                        list.add(phase)
                    } catch (e: Exception) {
                        plugin.logger.severe("加载阶段失败 [$type]: ${e.message}")
                    }
                } else {
                    plugin.logger.warning("未知阶段类型: $type")
                }
            }
        }
        return list
    }

    // 清理玩家映射 (供 DungeonInstance 调用)
    fun clearPlayerMap(uuid: UUID) {
        playerInstanceMap.remove(uuid)
    }

    fun isPlaying(player: Player): Boolean = playerInstanceMap.containsKey(player.uniqueId)
    fun getDungeon(player: Player): DungeonInstance? = activeInstances[playerInstanceMap[player.uniqueId]]

    private fun tickAllInstances() {
        activeInstances.values.forEach { it.tick() }
    }

    // --- 事件监听 ---

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val instanceId = playerInstanceMap[event.player.uniqueId] ?: return
        val instance = activeInstances[instanceId] ?: return

        // 代理给 Instance 处理退出逻辑
        instance.removePlayer(event.player)

        // 如果没人了，销毁副本 (延时一小会防止误判)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (instance.players.isEmpty()) {
                removeInstance(instanceId)
            }
        }, 20L)
    }

    @EventHandler
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        // 防止玩家 TP 出去还占着坑位
        val fromWorld = event.from.world ?: return
        val toWorld = event.to?.world ?: return

        if (fromWorld != toWorld && worldContainerMap.containsKey(fromWorld.name)) {
            // 如果玩家离开了副本世界
            leaveDungeon(event.player)
        }
    }

    // --- 字符串解析工具 ---
    private fun parseVector(str: String): Vector {
        val parts = str.split(",").map { it.trim().toDouble() }
        return Vector(parts[0], parts[1], parts[2])
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
}