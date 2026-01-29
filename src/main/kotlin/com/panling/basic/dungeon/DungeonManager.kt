package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.panling.basic.api.Reloadable
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.util.ClassScanner
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.WorldCreator
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.util.Vector
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DungeonManager(private val plugin: PanlingBasic) : Reloadable, Listener {

    private val templates = ConcurrentHashMap<String, DungeonTemplate>()
    private val logicRegistry = HashMap<String, (DungeonInstance) -> AbstractDungeonPhase>()
    private val activeInstances = ConcurrentHashMap<String, DungeonInstance>()
    private val playerInstanceMap = ConcurrentHashMap<UUID, String>()
    private val indexInstanceMap = ConcurrentHashMap<Int, String>()

    private val INSTANCE_WORLD_NAME = "panling_instances"

    init {
        try { plugin.reloadManager.register(this) } catch (ignored: Exception) {}
        Bukkit.getPluginManager().registerEvents(this, plugin)

        SchematicManager.init(plugin.dataFolder)
        setupInstanceWorld()
        loadTemplates()
        loadAllLogics()
        InstanceLocationProvider.reset()

        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickAllInstances() }, 20L, 20L)
    }

    private fun setupInstanceWorld() {
        if (Bukkit.getWorld(INSTANCE_WORLD_NAME) == null) {
            val creator = WorldCreator(INSTANCE_WORLD_NAME)
            creator.generator(VoidGenerator())
            val world = Bukkit.createWorld(creator)
            if (world != null) {
                world.isAutoSave = false

                // [核心修改] 设置游戏规则
                world.setGameRule(GameRule.KEEP_INVENTORY, true)      // 死亡不掉落
                world.setGameRule(GameRule.DO_MOB_SPAWNING, false)    // 禁止自然刷怪
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)  // 锁定时间
                world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)   // 锁定天气
                world.setGameRule(GameRule.MOB_GRIEFING, false)       // 禁止怪物破坏(苦力怕炸坑)
                world.time = 6000 // 永远中午

                plugin.logger.info("副本世界 $INSTANCE_WORLD_NAME 已加载并应用规则。")
            }
        }
    }

    fun startDungeon(leader: Player, templateId: String) {
        val template = templates[templateId] ?: return
        if (isPlaying(leader)) {
            leader.sendMessage("§c你已经在一个副本中了！")
            return
        }

        val (centerLocation, index) = InstanceLocationProvider.getNextLocation()

        leader.sendMessage("§e[系统] 正在构建副本...")

        // [核心修改] 不再手动加载区块，直接把任务交给 SchematicManager
        // 它会自动计算蓝图大小，并异步加载所需的全部区块
        SchematicManager.pasteAsync(plugin, centerLocation, template.schematicName) {
            // 粘贴完成回调
            createAndJoinInstance(leader, template, centerLocation, index)
        }
    }

    private fun createAndJoinInstance(leader: Player, template: DungeonTemplate, location: Location, index: Int) {
        val instanceId = UUID.randomUUID().toString()
        val initialPlayers = listOf(leader)

        val instance = DungeonInstance(plugin, instanceId, template, location.world!!, initialPlayers)
        instance.centerLocation = location

        activeInstances[instanceId] = instance
        indexInstanceMap[index] = instanceId
        initialPlayers.forEach { playerInstanceMap[it.uniqueId] = instanceId }

        val factory = logicRegistry[template.id]
        if (factory == null) {
            leader.sendMessage("§c副本逻辑丢失！")
            removeInstance(instanceId)
            return
        }

        initialPlayers.forEach { instance.join(it) }
        val initialPhase = factory(instance)
        instance.start(initialPhase)
    }

    fun removeInstance(instanceId: String) {
        val instance = activeInstances.remove(instanceId) ?: return

        val indexToRemove = indexInstanceMap.entries.find { it.value == instanceId }?.key
        if (indexToRemove != null) {
            indexInstanceMap.remove(indexToRemove)
        }

        val lobby = Bukkit.getWorlds()[0].spawnLocation
        instance.players.forEach {
            val p = Bukkit.getPlayer(it)
            // 先清理Map，防止TP时死循环
            playerInstanceMap.remove(it)
            p?.teleport(lobby)
        }
    }

    fun leaveDungeon(player: Player) {
        val instanceId = playerInstanceMap[player.uniqueId] ?: return
        val instance = activeInstances[instanceId] ?: return

        // [核心修复] 必须先移除映射，再执行 teleport (instance.leave 会 teleport)
        // 这样当 TP 事件触发时，isPlaying(player) 将返回 false，从而打破循环
        playerInstanceMap.remove(player.uniqueId)

        // 这里的 leave 内部会执行 teleport
        instance.leave(player)

        // 延时检查实例是否为空
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (instance.players.isEmpty()) removeInstance(instanceId)
        }, 20L)
    }

    fun isPlaying(player: Player) = playerInstanceMap.containsKey(player.uniqueId)
    fun getInstance(player: Player) = activeInstances[playerInstanceMap[player.uniqueId]]

    fun getInstanceAt(location: Location): DungeonInstance? {
        if (location.world?.name != INSTANCE_WORLD_NAME) return null
        val offset = InstanceLocationProvider.OFFSET_DISTANCE
        val index = (location.blockX + (offset / 2)) / offset
        val instanceId = indexInstanceMap[index] ?: return null
        return activeInstances[instanceId]
    }

    private fun tickAllInstances() {
        // activeInstances.values.forEach { it.onTick() }
    }

    // ==========================================
    // 加载逻辑 (保持原样)
    // ==========================================

    override fun reload() {
        loadTemplates()
    }

    private fun loadAllLogics() {
        logicRegistry.clear()
        val classes = ClassScanner.scanClasses(plugin, "com.panling.basic", DungeonLogicProvider::class.java)
        for (clazz in classes) {
            try {
                val provider = try {
                    clazz.getConstructor(PanlingBasic::class.java).newInstance(plugin)
                } catch (e: NoSuchMethodException) {
                    clazz.getConstructor().newInstance()
                }
                registerLogic(provider.templateId) { instance -> provider.createInitialPhase(instance) }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun registerLogic(templateId: String, factory: (DungeonInstance) -> AbstractDungeonPhase) {
        logicRegistry[templateId] = factory
    }

    private fun loadTemplates() {
        templates.clear()
        val folder = File(plugin.dataFolder, "dungeons")
        if (!folder.exists()) folder.mkdirs()
        folder.listFiles { _, name -> name.endsWith(".yml") }?.forEach { file ->
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val id = file.nameWithoutExtension
                val template = DungeonTemplate(
                    id = id,
                    displayName = config.getString("display_name", id)!!,
                    schematicName = config.getString("world_source", "default")!!,
                    minLevel = config.getInt("requirements.min_level", 0),
                    minPlayers = config.getInt("settings.min_players", 1),
                    maxPlayers = config.getInt("settings.max_players", 4),
                    timeLimit = config.getInt("settings.time_limit", 1800),
                    // 注意：这个 spawnOffset 是相对于 centerLocation 的
                    spawnOffset = parseVector(config.getString("settings.spawn_loc", "0, 65, 0")!!),
                    exitLoc = parseLocation(config.getString("settings.exit_loc"))
                )
                templates[id] = template
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

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

    // ==========================================
    // 事件监听
    // ==========================================

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        leaveDungeon(event.player)
    }

    @EventHandler
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val fromWorld = event.from.world ?: return
        val toWorld = event.to?.world ?: return

        // [Fix] 简单的世界名判断
        // 如果玩家从副本世界 TP 到 非副本世界，且不是通过 leaveDungeon (通常 leaveDungeon 会先移除 map)
        if (fromWorld.name == INSTANCE_WORLD_NAME && toWorld.name != INSTANCE_WORLD_NAME) {
            // 如果玩家还在 Map 里，说明是异常逃逸（比如TP指令）
            if (isPlaying(event.player)) {
                leaveDungeon(event.player)
            }
        }
    }
}