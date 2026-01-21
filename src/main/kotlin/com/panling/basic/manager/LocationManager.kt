package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.PlayerRace
import com.panling.basic.api.Reloadable
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.util.EulerAngle
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class LocationManager(private val plugin: PanlingBasic) : Reloadable {

    private val file: File = File(plugin.dataFolder, "locations.yml")
    private var config: YamlConfiguration = YamlConfiguration()

    // === 内部类：极简坐标键 ===
    data class BlockKey(val world: String, val x: Int, val y: Int, val z: Int) {
        companion object {
            fun from(loc: Location): BlockKey {
                return BlockKey(loc.world.name, loc.blockX, loc.blockY, loc.blockZ)
            }
        }
    }

    // === 受管实体数据结构 ===
    data class ManagedEntityData(
        val loc: Location,
        val type: EntityType,
        val meta: Map<String, Any?>
    )

    // 存储: 旧 UUID -> 实体物理特征
    private val managedEntities = HashMap<UUID, ManagedEntityData>()

    // === 触发器定义 ===
    enum class TriggerType {
        CLASS,    // 转职
        RACE,     // 转种族
        TELEPORT  // 传送
    }

    data class TriggerData(val type: TriggerType, val value: String)

    // [存储层]
    private val triggers = HashMap<BlockKey, MutableList<TriggerData>>()
    private val entityTriggers = HashMap<UUID, MutableList<TriggerData>>()

    // [逻辑层] 处理器注册表
    private val handlers = EnumMap<TriggerType, TriggerHandler>(TriggerType::class.java)

    // [接口定义] SAM 接口，支持 Lambda
    fun interface TriggerHandler {
        fun execute(player: Player, value: String)
    }

    init {
        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (ignored: IOException) {}
        }

        // 自动注册
        try {
            plugin.reloadManager.register(this)
        } catch (ignored: Exception) {}

        loadConfig()
        registerDefaultHandlers()
    }

    override fun reload() {
        loadConfig()
        plugin.logger.info("位置库已重载。")
    }

    // === 核心：注册默认逻辑处理器 ===
    private fun registerDefaultHandlers() {
        // 1. 转职逻辑
        handlers[TriggerType.CLASS] = TriggerHandler { player, value ->
            try {
                val pc = PlayerClass.valueOf(value)
                if (plugin.playerDataManager.getPlayerClass(player) == pc) {
                    player.sendMessage("§c你已经是 ${pc.displayName} 了。")
                    return@TriggerHandler
                }
                plugin.playerDataManager.setPlayerClass(player, pc)
                plugin.playerDataManager.setActiveSlot(player, 0)

                player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
                player.sendMessage("§e§l--------------------------------")
                player.sendMessage("§f 你现在的职业是: ${pc.displayName}")
                player.sendMessage("§e§l--------------------------------")

                plugin.playerDataManager.clearStatCache(player)
            } catch (e: Exception) {
                player.sendMessage("§c无效的职业配置: $value")
            }
        }

        // 2. 种族逻辑
        handlers[TriggerType.RACE] = TriggerHandler { player, value ->
            try {
                val race = PlayerRace.valueOf(value)
                plugin.playerDataManager.setPlayerRace(player, race)

                player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
                player.sendMessage("§e§l--------------------------------")
                player.sendMessage("§f 你现在的种族是: ${race.coloredName}")
                player.sendMessage("§7 种族天赋：${race.statDesc}")
                player.sendMessage("§e§l--------------------------------")

                plugin.playerDataManager.clearStatCache(player)
            } catch (e: Exception) {
                player.sendMessage("§c无效的种族配置: $value")
            }
        }

        // 3. 传送逻辑
        handlers[TriggerType.TELEPORT] = TriggerHandler { player, value ->
            val target = getLocation(value)
            if (target != null) {
                player.teleport(target)
                player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
            } else {
                player.sendMessage("§c[错误] 未找到传送点: $value")
            }
        }
    }

    // [API] 获取处理器
    fun getHandler(type: TriggerType): TriggerHandler? {
        return handlers[type]
    }

    fun registerHandler(type: TriggerType, handler: TriggerHandler) {
        handlers[type] = handler
    }

    // ==========================================================
    // 配置加载与保存
    // ==========================================================

    fun loadConfig() {
        config = YamlConfiguration.loadConfiguration(file)
        triggers.clear()
        entityTriggers.clear()
        managedEntities.clear()

        // 1. 加载方块触发器
        loadTriggerMap(config.getConfigurationSection("block_triggers"), false)

        // 2. 加载实体触发器
        loadTriggerMap(config.getConfigurationSection("entity_triggers"), true)

        // 3. 加载受管实体数据
        loadManagedEntities()

        plugin.logger.info("已加载配置：方块触发器 ${triggers.size} 个，实体触发器 ${entityTriggers.size} 个。")
    }

    private fun loadTriggerMap(sec: ConfigurationSection?, isEntity: Boolean) {
        if (sec == null) return
        for (keyStr in sec.getKeys(false)) {
            val rawList = sec.getStringList(keyStr)
            val dataList = ArrayList<TriggerData>()

            for (entry in rawList) {
                val parts = entry.split(":", limit = 2)
                if (parts.size < 2) continue
                try {
                    val type = TriggerType.valueOf(parts[0])
                    val `val` = parts[1]
                    dataList.add(TriggerData(type, `val`))
                } catch (ignored: Exception) {}
            }

            if (dataList.isNotEmpty()) {
                if (isEntity) {
                    try {
                        entityTriggers[UUID.fromString(keyStr)] = dataList
                    } catch (ignored: IllegalArgumentException) {}
                } else {
                    val key = parseBlockKey(keyStr)
                    if (key != null) triggers[key] = dataList
                }
            }
        }
    }

    // ==========================================================
    // [API] 实体触发器管理
    // ==========================================================

    fun addEntityTrigger(uuid: UUID, type: TriggerType, value: String) {
        entityTriggers.computeIfAbsent(uuid) { ArrayList() }.add(TriggerData(type, value))
        saveEntityTriggerList(uuid)
    }

    fun clearEntityTriggers(uuid: UUID) {
        if (entityTriggers.remove(uuid) != null) {
            config.set("entity_triggers.$uuid", null)
            save()
        }
        if (managedEntities.remove(uuid) != null) {
            config.set("managed_entities.$uuid", null)
            save()
        }
    }

    fun getEntityTriggers(uuid: UUID): List<TriggerData> {
        return entityTriggers[uuid] ?: emptyList()
    }

    private fun saveEntityTriggerList(uuid: UUID) {
        val list = entityTriggers[uuid]
        if (list.isNullOrEmpty()) {
            config.set("entity_triggers.$uuid", null)
        } else {
            val strList = list.map { "${it.type.name}:${it.value}" }
            config.set("entity_triggers.$uuid", strList)
        }
        save()
    }

    // [API] 方块触发器管理
    fun addTrigger(loc: Location, type: TriggerType, value: String) {
        val key = BlockKey.from(loc)
        triggers.computeIfAbsent(key) { ArrayList() }.add(TriggerData(type, value))
        saveTriggerList(key)
    }

    fun clearTriggers(loc: Location) {
        val key = BlockKey.from(loc)
        if (triggers.remove(key) != null) {
            config.set("block_triggers.${locStr(key)}", null)
            save()
        }
    }

    fun getTriggersAt(loc: Location): List<TriggerData> {
        return triggers[BlockKey.from(loc)] ?: emptyList()
    }

    private fun saveTriggerList(key: BlockKey) {
        val list = triggers[key]
        if (list.isNullOrEmpty()) {
            config.set("block_triggers.${locStr(key)}", null)
        } else {
            val strList = list.map { "${it.type.name}:${it.value}" }
            config.set("block_triggers.${locStr(key)}", strList)
        }
        save()
    }

    // ==========================================================
    // 地标管理 (Waypoints)
    // ==========================================================

    fun setLocation(name: String, loc: Location) {
        val path = "waypoints.$name"
        config.set("$path.world", loc.world.name)
        config.set("$path.x", loc.x)
        config.set("$path.y", loc.y)
        config.set("$path.z", loc.z)
        config.set("$path.yaw", loc.yaw)
        config.set("$path.pitch", loc.pitch)
        save()
    }

    fun getLocation(name: String): Location? {
        val path = "waypoints.$name"
        if (!config.contains(path)) return null
        val worldName = config.getString("$path.world") ?: return null
        val world = Bukkit.getWorld(worldName) ?: return null
        return Location(
            world,
            config.getDouble("$path.x"),
            config.getDouble("$path.y"),
            config.getDouble("$path.z"),
            config.getDouble("$path.yaw").toFloat(),
            config.getDouble("$path.pitch").toFloat()
        )
    }

    fun getWaypointNames(): Set<String> {
        return config.getConfigurationSection("waypoints")?.getKeys(false) ?: emptySet()
    }

    // --- 辅助方法 ---
    private fun locStr(key: BlockKey): String = "${key.world},${key.x},${key.y},${key.z}"

    private fun parseBlockKey(key: String): BlockKey? {
        return try {
            val parts = key.split(",")
            if (parts.size != 4) null
            else BlockKey(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
        } catch (e: Exception) {
            null
        }
    }

    private fun save() {
        try {
            config.save(file)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // ==========================================================
    // 核心修改：受管实体逻辑 (支持 ArmorStand 深度恢复)
    // ==========================================================

    fun registerManagedEntity(entity: Entity) {
        val meta = HashMap<String, Any?>()

        // 1. 通用数据
        if (entity.customName != null) meta["name"] = entity.customName
        meta["glowing"] = entity.isGlowing
        meta["gravity"] = entity.hasGravity()
        meta["silent"] = entity.isSilent

        // 2. Interaction 特有
        if (entity is Interaction) {
            meta["width"] = entity.interactionWidth.toString()
            meta["height"] = entity.interactionHeight.toString()
        }

        // 3. ArmorStand 特有数据
        if (entity is ArmorStand) {
            meta["as_small"] = entity.isSmall
            meta["as_marker"] = entity.isMarker
            meta["as_arms"] = entity.hasArms()
            meta["as_baseplate"] = entity.hasBasePlate()
            meta["as_invisible"] = entity.isInvisible

            entity.equipment?.let { eq ->
                meta["eq_hand"] = eq.itemInMainHand
                meta["eq_offhand"] = eq.itemInOffHand
                meta["eq_head"] = eq.helmet
                meta["eq_chest"] = eq.chestplate
                meta["eq_legs"] = eq.leggings
                meta["eq_boots"] = eq.boots
            }

            meta["pose_head"] = angleToStr(entity.headPose)
            meta["pose_body"] = angleToStr(entity.bodyPose)
            meta["pose_larm"] = angleToStr(entity.leftArmPose)
            meta["pose_rarm"] = angleToStr(entity.rightArmPose)
            meta["pose_lleg"] = angleToStr(entity.leftLegPose)
            meta["pose_rleg"] = angleToStr(entity.rightLegPose)
        }

        val data = ManagedEntityData(entity.location, entity.type, meta)
        managedEntities[entity.uniqueId] = data
        saveManagedEntity(entity.uniqueId, data)
    }

    fun restoreMissingEntities(): Int {
        var restoredCount = 0
        val oldUuids = HashSet(managedEntities.keys)

        for (oldUuid in oldUuids) {
            val data = managedEntities[oldUuid] ?: continue

            // 安全检查：只有区块加载了才检查
            if (!data.loc.chunk.isLoaded) {
                continue
            }

            if (Bukkit.getEntity(oldUuid) == null) {
                // 实体丢失，重生
                plugin.logger.info("检测到实体丢失 (UUID: $oldUuid)，正在重生...")

                val newEntity = data.loc.world.spawnEntity(data.loc, data.type)
                restoreEntityMeta(newEntity, data.meta)

                val newUuid = newEntity.uniqueId

                // 迁移触发器
                val triggers = entityTriggers.remove(oldUuid)
                if (triggers != null) {
                    entityTriggers[newUuid] = triggers

                    config.set("entity_triggers.$oldUuid", null)
                    val strList = triggers.map { "${it.type.name}:${it.value}" }
                    config.set("entity_triggers.$newUuid", strList)
                }

                // 迁移受管实体数据
                managedEntities.remove(oldUuid)
                managedEntities[newUuid] = data

                config.set("managed_entities.$oldUuid", null)
                saveManagedEntity(newUuid, data)

                restoredCount++
            }
        }

        if (restoredCount > 0) save()
        return restoredCount
    }

    private fun restoreEntityMeta(entity: Entity, meta: Map<String, Any?>) {
        // 通用
        (meta["name"] as? String)?.let {
            entity.customName = it
            entity.isCustomNameVisible = true
        }
        (meta["glowing"] as? Boolean)?.let { entity.isGlowing = it }
        (meta["gravity"] as? Boolean)?.let { entity.setGravity(it) }
        (meta["silent"] as? Boolean)?.let { entity.isSilent = it }

        // Interaction
        if (entity is Interaction) {
            (meta["width"] as? String)?.toFloatOrNull()?.let { entity.interactionWidth = it }
            (meta["height"] as? String)?.toFloatOrNull()?.let { entity.interactionHeight = it }
        }

        // ArmorStand
        if (entity is ArmorStand) {
            (meta["as_small"] as? Boolean)?.let { entity.isSmall = it }
            (meta["as_marker"] as? Boolean)?.let { entity.isMarker = it }
            (meta["as_arms"] as? Boolean)?.let { entity.setArms(it) }
            (meta["as_baseplate"] as? Boolean)?.let { entity.setBasePlate(it) }
            (meta["as_invisible"] as? Boolean)?.let { entity.isVisible = !it }

            entity.equipment?.let { eq ->
                (meta["eq_hand"] as? ItemStack)?.let { eq.setItemInMainHand(it) }
                (meta["eq_offhand"] as? ItemStack)?.let { eq.setItemInOffHand(it) }
                (meta["eq_head"] as? ItemStack)?.let { eq.helmet = it }
                (meta["eq_chest"] as? ItemStack)?.let { eq.chestplate = it }
                (meta["eq_legs"] as? ItemStack)?.let { eq.leggings = it }
                (meta["eq_boots"] as? ItemStack)?.let { eq.boots = it }
            }

            (meta["pose_head"] as? String)?.let { entity.headPose = strToAngle(it) }
            (meta["pose_body"] as? String)?.let { entity.bodyPose = strToAngle(it) }
            (meta["pose_larm"] as? String)?.let { entity.leftArmPose = strToAngle(it) }
            (meta["pose_rarm"] as? String)?.let { entity.rightArmPose = strToAngle(it) }
            (meta["pose_lleg"] as? String)?.let { entity.leftLegPose = strToAngle(it) }
            (meta["pose_rleg"] as? String)?.let { entity.rightLegPose = strToAngle(it) }
        }
    }

    private fun loadManagedEntities() {
        val sec = config.getConfigurationSection("managed_entities") ?: return

        for (uuidStr in sec.getKeys(false)) {
            try {
                val uuid = UUID.fromString(uuidStr)
                val type = EntityType.valueOf(sec.getString("$uuidStr.type")!!)

                val w = sec.getString("$uuidStr.world")
                val x = sec.getDouble("$uuidStr.x")
                val y = sec.getDouble("$uuidStr.y")
                val z = sec.getDouble("$uuidStr.z")
                val yaw = sec.getDouble("$uuidStr.yaw").toFloat()
                val pitch = sec.getDouble("$uuidStr.pitch").toFloat()
                val loc = Location(Bukkit.getWorld(w!!), x, y, z, yaw, pitch)

                val meta = HashMap<String, Any?>()
                val metaSec = sec.getConfigurationSection("$uuidStr.meta")
                if (metaSec != null) {
                    for (key in metaSec.getKeys(false)) {
                        meta[key] = metaSec.get(key)
                    }
                }
                managedEntities[uuid] = ManagedEntityData(loc, type, meta)

            } catch (e: Exception) {
                plugin.logger.warning("加载受管实体失败: $uuidStr")
            }
        }
    }

    private fun saveManagedEntity(uuid: UUID, data: ManagedEntityData) {
        val path = "managed_entities.$uuid"
        config.set("$path.type", data.type.name)
        config.set("$path.world", data.loc.world.name)
        config.set("$path.x", data.loc.x)
        config.set("$path.y", data.loc.y)
        config.set("$path.z", data.loc.z)
        config.set("$path.yaw", data.loc.yaw)
        config.set("$path.pitch", data.loc.pitch)

        for ((key, value) in data.meta) {
            config.set("$path.meta.$key", value)
        }
        save()
    }

    private fun angleToStr(ea: EulerAngle): String {
        return "${ea.x},${ea.y},${ea.z}"
    }

    private fun strToAngle(str: String): EulerAngle {
        return try {
            val p = str.split(",")
            EulerAngle(p[0].toDouble(), p[1].toDouble(), p[2].toDouble())
        } catch (e: Exception) {
            EulerAngle.ZERO
        }
    }
}