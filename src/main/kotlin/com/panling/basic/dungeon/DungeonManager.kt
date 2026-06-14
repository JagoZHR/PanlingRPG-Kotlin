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
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DungeonManager(private val plugin: PanlingBasic) : Reloadable, Listener {

    private val templates = ConcurrentHashMap<String, DungeonTemplate>()
    private val logicRegistry = HashMap<String, (DungeonInstance) -> AbstractDungeonPhase>()
    private val activeInstances = ConcurrentHashMap<String, DungeonInstance>()
    private val playerInstanceMap = ConcurrentHashMap<UUID, String>()
    private val indexInstanceMap = ConcurrentHashMap<Int, String>()
    private val pendingRevives = ConcurrentHashMap<UUID, PendingRevive>()

    data class PendingRevive(
        val instanceId: String,
        val cost: Double,
        val glassLoc: Location,
        var timerTask: BukkitTask? = null
    )

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
        // 1. 检查世界是否已经加载 (虽然 onEnable 时通常未加载，但为了保险)
        val existingWorld = Bukkit.getWorld(INSTANCE_WORLD_NAME)
        if (existingWorld != null) {
            plugin.logger.info("检测到旧副本世界，正在卸载...")
            Bukkit.unloadWorld(existingWorld, false) // false = 不保存
        }

        // 2. [核心修复] 物理删除世界文件夹
        // 这确保每次重启服务器，副本世界都是一张白纸，index=0 的位置也是空的
        val worldFolder = File(Bukkit.getWorldContainer(), INSTANCE_WORLD_NAME)
        if (worldFolder.exists()) {
            plugin.logger.info("正在清理旧副本数据...")
            val deleted = worldFolder.deleteRecursively()
            if (!deleted) {
                plugin.logger.warning("警告：旧副本数据清理失败！可能会导致地图重叠。请检查文件占用。")
            }
        }

        // 3. 重新创建纯净的虚空世界
        val creator = WorldCreator(INSTANCE_WORLD_NAME)
        creator.generator(VoidGenerator())
        val world = Bukkit.createWorld(creator)

        if (world != null) {
            world.isAutoSave = false

            // 应用规则
            world.setGameRule(GameRule.KEEP_INVENTORY, true)
            world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true)
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
            world.setGameRule(GameRule.DO_MOB_LOOT, false)
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
            world.setGameRule(GameRule.MOB_GRIEFING, false)
            world.time = 6000

            plugin.logger.info("副本世界 $INSTANCE_WORLD_NAME 已重置并加载。")
        }
    }

    fun startDungeon(leader: Player, templateId: String) {
        val template = templates[templateId] ?: return

        // 0. 等级检查
        if (leader.level < template.minLevel) {
            leader.sendMessage("§c你的等级不足！需要 Lv.${template.minLevel} 才能进入此副本。")
            return
        }

        // 1. 获取玩家的队伍状态
        val party = plugin.partyManager.getParty(leader)
        val targetPlayers = mutableListOf<Player>()

        if (party == null) {
            // == 单人模式 ==
            if (template.minPlayers > 1) {
                leader.sendMessage("§c该副本至少需要 ${template.minPlayers} 人组队才能开启！")
                return
            }
            if (isPlaying(leader)) {
                leader.sendMessage("§c你已经在一个副本中了！")
                return
            }
            targetPlayers.add(leader)
        } else {
            // == 组队模式 ==
            if (party.leader != leader.uniqueId) {
                leader.sendMessage("§c只有队长才能开启副本！")
                return
            }
            if (party.members.size < template.minPlayers || party.members.size > template.maxPlayers) {
                leader.sendMessage("§c队伍人数(${party.members.size})不满足副本要求 (${template.minPlayers}-${template.maxPlayers}人)！")
                return
            }

            // 遍历并检查所有队员状态
            for (uuid in party.members) {
                val member = Bukkit.getPlayer(uuid)
                if (member == null || !member.isOnline) {
                    leader.sendMessage("§c队伍中有成员离线，无法开启副本！")
                    return
                }
                if (isPlaying(member)) {
                    leader.sendMessage("§c队员 ${member.name} 已经在副本中了，无法开启！")
                    return
                }
                // (可选) 距离检查：防止队员挂机被强拉
                if (member.location.world != leader.location.world || member.location.distance(leader.location) > 30.0) {
                    leader.sendMessage("§c队员 ${member.name} 距离过远，无法开启副本！")
                    return
                }
                targetPlayers.add(member)
            }
        }

        val (centerLocation, index) = InstanceLocationProvider.getNextLocation()
        leader.sendMessage("§e[系统] 正在构建副本，请稍候...")

        // 准入检查：已接取或已完成指定前置任务（任意一个）
        if (template.requiredQuests.isNotEmpty()) {
            val qm = plugin.questManager
            val hasAny = template.requiredQuests.any {
                qm.hasCompleted(leader, it) || qm.getActiveProgress(leader, it) != null
            }
            if (!hasAny) {
                leader.sendMessage("§c你还没有完成进入此副本所需的前置任务！")
                return
            }
        }

        if (template.spectatorBuild) {
            val dims = SchematicManager.getDimensions(template.schematicName)
            val platformY = centerLocation.blockY + (dims?.second ?: 50) + 4
            val world = centerLocation.world!!
            val platformX = centerLocation.blockX
            val platformZ = centerLocation.blockZ

            // 5×5×5 玻璃笼子：地板 + 四面墙 + 天花板
            for (dx in -2..2) for (dy in 0..5) for (dz in -2..2) {
                val edge = kotlin.math.abs(dx) == 2 || kotlin.math.abs(dz) == 2 || dy == 0 || dy == 5
                if (edge) world.getBlockAt(platformX + dx, platformY + dy, platformZ + dz).setType(org.bukkit.Material.GLASS, false)
            }

            val watchLoc = Location(world, platformX + 0.5, platformY + 1.0, platformZ + 0.5)
            for (p in targetPlayers) {
                p.teleport(watchLoc)
                p.addPotionEffect(org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.DARKNESS, 60, 0, false, false
                ))
            }

            pasteAll(centerLocation, template) {
                for (dx in -2..2) for (dy in 0..5) for (dz in -2..2) {
                    world.getBlockAt(platformX + dx, platformY + dy, platformZ + dz).setType(org.bukkit.Material.AIR, false)
                }
                createAndJoinInstance(targetPlayers, template, centerLocation, index)
            }
        } else {
            pasteAll(centerLocation, template) {
                createAndJoinInstance(targetPlayers, template, centerLocation, index)
            }
        }
    }

    /** 逐个粘贴：主 schematic + 所有 pre_paste，一个完成再下一个 */
    private fun pasteAll(center: Location, template: DungeonTemplate, onAllDone: () -> Unit) {
        val all = mutableListOf<Pair<String, Location>>()
        all.add(template.schematicName to center)
        for (pp in template.prePasteSchematics) {
            all.add(pp.name to center.clone().add(0.0, 0.0, pp.offsetZ.toDouble()))
        }
        pasteNext(all, 0, onAllDone)
    }

    private fun pasteNext(all: List<Pair<String, Location>>, index: Int, onAllDone: () -> Unit) {
        if (index >= all.size) { onAllDone(); return }
        val (name, loc) = all[index]
        SchematicManager.pasteAsync(plugin, loc, name) {
            pasteNext(all, index + 1, onAllDone)
        }
    }

    // [修改参数] 从 leader: Player 改为 players: List<Player>
    private fun createAndJoinInstance(players: List<Player>, template: DungeonTemplate, location: Location, index: Int) {
        val instanceId = UUID.randomUUID().toString()

        // 传入所有验证过的玩家
        val instance = DungeonInstance(plugin, instanceId, template, location.world!!, players)
        instance.centerLocation = location

        activeInstances[instanceId] = instance
        indexInstanceMap[index] = instanceId

        // 批量注册 UUID 映射
        players.forEach { playerInstanceMap[it.uniqueId] = instanceId }

        val factory = logicRegistry[template.id]
        if (factory == null) {
            players.firstOrNull()?.sendMessage("§c副本逻辑丢失！")
            removeInstance(instanceId)
            return
        }

        // 批量加入实例 (执行传送逻辑)
        players.forEach { instance.join(it) }

        val initialPhase = factory(instance)
        instance.start(initialPhase)
    }

    fun removeInstance(instanceId: String) {
        val instance = activeInstances.remove(instanceId) ?: return

        val indexToRemove = indexInstanceMap.entries.find { it.value == instanceId }?.key
        if (indexToRemove != null) {
            indexInstanceMap.remove(indexToRemove)
        }

        // [修复] 获取退本位置：优先使用该副本配置的 exitLoc，如果没有配置则退回到主世界出生点
        val exitLocation = instance.template.exitLoc ?: Bukkit.getWorlds()[0].spawnLocation

        instance.players.forEach {
            val p = Bukkit.getPlayer(it)
            playerInstanceMap.remove(it)
            if (p != null && !p.isDead) {
                p.teleport(exitLocation)
                p.sendMessage("§e副本已关闭，你已返回原世界。")
            }
        }
    }

    fun leaveDungeon(player: Player) {
        val instanceId = playerInstanceMap[player.uniqueId] ?: return
        val instance = activeInstances[instanceId] ?: return

        playerInstanceMap.remove(player.uniqueId)

        instance.leave(player)

        // 人走光 → 立刻关闭，不等 20 tick
        if (instance.players.isEmpty()) {
            removeInstance(instanceId)
        }
    }

    /** 死亡时处理：有复活费用 → 进入等待复活状态；无 → 正常退出 */
    fun handleDungeonDeath(player: Player) {
        val instanceId = playerInstanceMap[player.uniqueId] ?: return
        val instance = activeInstances[instanceId] ?: return
        val template = templates[instance.template.id] ?: return

        if (template.reviveCost > 0.0) {
            // 进入等待复活状态
            val reviveLoc: Location
            if (template.spectatorBuild) {
                // 重建玻璃笼子（构建后已被清除）
                val dims = SchematicManager.getDimensions(template.schematicName)
                val platformY = instance.centerLocation.blockY + (dims?.second ?: 50) + 4
                val px = instance.centerLocation.blockX; val pz = instance.centerLocation.blockZ
                val world = instance.world
                for (dx in -2..2) for (dy in 0..5) for (dz in -2..2) {
                    val edge = kotlin.math.abs(dx) == 2 || kotlin.math.abs(dz) == 2 || dy == 0 || dy == 5
                    if (edge) world.getBlockAt(px + dx, platformY + dy, pz + dz).setType(org.bukkit.Material.GLASS, false)
                }
                reviveLoc = Location(world, px + 0.5, platformY + 1.0, pz + 0.5)
            } else {
                reviveLoc = instance.centerLocation.clone().add(instance.template.spawnOffset)
            }
            player.teleport(reviveLoc)
            player.health = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue

            val pr = PendingRevive(instanceId, template.reviveCost, reviveLoc)
            val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                pendingRevives.remove(player.uniqueId)
                doLeave(player, instanceId, instance)
                player.sendMessage("§c你在副本中阵亡，15秒内未复活，已退出副本。")
            }, 300L)
            pr.timerTask = task
            pendingRevives[player.uniqueId] = pr

            // 发送点击选项
            val msg = Component.text()
                .append(Component.text("§c════════════════════════\n"))
                .append(Component.text("§c你已阵亡！"))
                .append(Component.text("\n  "))
                .append(Component.text("§a[支付 ${"%.0f".format(template.reviveCost)} 铜钱复活]")
                    .clickEvent(ClickEvent.runCommand("/plbasic internal dungeon_revive"))
                    .hoverEvent(Component.text("§7点击支付复活")))
                .append(Component.text("    "))
                .append(Component.text("§7[放弃，退出副本]")
                    .clickEvent(ClickEvent.runCommand("/plbasic internal dungeon_leave"))
                    .hoverEvent(Component.text("§7点击放弃")))
                .append(Component.text("\n§c════════════════════════"))
                .build()
            player.sendMessage(msg)
        } else {
            // 不支持复活 → 直接退出
            doLeave(player, instanceId, instance)
        }
    }

    /** 玩家确认复活 */
    fun revivePlayer(player: Player) {
        val pr = pendingRevives.remove(player.uniqueId) ?: run {
            player.sendMessage("§c你没有等待复活的副本记录。")
            return
        }
        pr.timerTask?.cancel()
        if (!plugin.economyManager.takeMoney(player, pr.cost)) {
            player.sendMessage("§c铜钱不足！复活需要 ${"%.0f".format(pr.cost)} 铜钱。")
            // 重新进入等待状态
            val instanceId = pr.instanceId; val glassLoc = pr.glassLoc; val cost = pr.cost
            val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                pendingRevives.remove(player.uniqueId)
                val inst = activeInstances[instanceId] ?: return@Runnable
                doLeave(player, instanceId, inst)
                player.sendMessage("§c铜钱不足，已退出副本。")
            }, 200L)
            pendingRevives[player.uniqueId] = PendingRevive(instanceId, cost, glassLoc, task)
            return
        }
        val instance = activeInstances[pr.instanceId] ?: return
        val spawn = instance.centerLocation.clone().add(instance.template.spawnOffset)
        player.teleport(spawn)
        player.health = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue
        player.sendMessage("§a你已花费 ${"%.0f".format(pr.cost)} 铜钱复活！")
    }

    /** 玩家放弃复活 / 超时 */
    fun cancelRevive(player: Player) {
        val pr = pendingRevives.remove(player.uniqueId) ?: return
        pr.timerTask?.cancel()
        val instance = activeInstances[pr.instanceId] ?: return
        doLeave(player, pr.instanceId, instance)
        player.sendMessage("§7你放弃了复活，已退出副本。")
    }

    /** 无声清理（玩家退出时用） */
    private fun cancelReviveSilent(uuid: UUID) {
        val pr = pendingRevives.remove(uuid) ?: return
        pr.timerTask?.cancel()
    }

    private fun doLeave(player: Player, instanceId: String, instance: DungeonInstance) {
        playerInstanceMap.remove(player.uniqueId)
        instance.players.remove(player.uniqueId)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (instance.players.isEmpty()) removeInstance(instanceId)
        }, 20L)
        // 击杀玩家触发正常重生（此时已移出副本，不会再拦截）
        player.health = 0.0
    }

    fun isPlaying(player: Player) = playerInstanceMap.containsKey(player.uniqueId)
    // [核心修复] 防御性 NPE 检查
    fun getInstance(player: Player?): DungeonInstance? {
        // 1. 基础判空
        if (player == null) return null

        // 2. 安全获取 UUID (防止极个别情况下的异常)
        val uuid = try {
            player.uniqueId
        } catch (e: Exception) {
            return null
        }

        // 3. 安全查询 Map (ConcurrentHashMap 不允许 get(null))
        val id = playerInstanceMap[uuid] ?: return null

        // 4. 返回实例
        return activeInstances[id]
    }

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

    // ==========================================
    // [新增] 外部 API 方法
    // ==========================================

    /**
     * 获取指定 ID 的副本模板
     * 供 DungeonEntryUI 和 SetTriggerCommand 使用
     */
    fun getTemplate(id: String): DungeonTemplate? {
        return templates[id]
    }

    /**
     * 获取所有加载的副本 ID 列表
     * 供指令 Tab 补全使用
     */
    fun getTemplateIds(): List<String> {
        return templates.keys.toList()
    }

    private fun loadTemplates() {
        templates.clear()
        val folder = File(plugin.dataFolder, "dungeons")
        if (!folder.exists()) folder.mkdirs()
        folder.listFiles { _, name -> name.endsWith(".yml") }?.forEach { file ->
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val id = file.nameWithoutExtension
                val prePaste = mutableListOf<PrePasteSchematic>()
                val ppList = config.getMapList("pre_paste")
                for (map in ppList) {
                    val name = map["schematic"]?.toString() ?: continue
                    val offsetZ = (map["offset_z"] as? Number)?.toInt() ?: 0
                    prePaste.add(PrePasteSchematic(name, offsetZ))
                }

                val template = DungeonTemplate(
                    id = id,
                    displayName = config.getString("display_name", id)!!,
                    schematicName = config.getString("world_source", "default")!!,
                    minLevel = config.getInt("requirements.min_level", 0),
                    minPlayers = config.getInt("settings.min_players", 1),
                    maxPlayers = config.getInt("settings.max_players", 4),
                    timeLimit = config.getInt("settings.time_limit", 1800),
                    spawnOffset = parseVector(config.getString("settings.spawn_loc", "0, 65, 0")!!),
                    exitLoc = parseLocation(config.getString("settings.exit_loc")),
                    spectatorBuild = config.getBoolean("spectator_build", false),
                    requiredQuests = config.getStringList("required_quests").filter { it.isNotBlank() },
                    prePasteSchematics = prePaste,
                    reviveCost = config.getDouble("revive_cost", 0.0)
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
        cancelReviveSilent(event.player.uniqueId)
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