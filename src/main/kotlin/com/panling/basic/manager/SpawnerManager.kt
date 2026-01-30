package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.Reloadable
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class SpawnerManager(
    private val plugin: PanlingBasic,
    private val mobManager: MobManager
) : Reloadable, Listener {

    private val spawnerConfigMap = HashMap<String, SpawnerConfig>()
    // SpawnerID -> (PlayerUUID -> Set<MobUUID>)
    private val activeMobs = ConcurrentHashMap<String, MutableMap<UUID, MutableSet<UUID>>>()

    // 玩家 UUID -> 枯竭会话数据
    private val depletionSessions = ConcurrentHashMap<UUID, DepletionSession>()
    private val depletionFile = File(plugin.dataFolder, "data/depletion_data.yml")

    private var secondsCounter: Long = 0

    // 重置距离阈值 (平方)：64格 * 64格
    companion object {
        private const val RESET_DISTANCE_SQ = 64.0 * 64.0
    }

    init {
        // 自动注册重载
        try {
            plugin.reloadManager.register(this)
        } catch (ignored: Exception) {}

        plugin.server.pluginManager.registerEvents(this, plugin)

        loadSpawners()
        loadDepletionData()
        startTask()
    }

    fun stop() {
        clearAllMobs()
        saveDepletionData()
        plugin.logger.info("已清理怪物并保存枯竭记录。")
    }

    override fun reload() {
        saveDepletionData()
        clearAllMobs()
        loadSpawners()
    }

    // === 1. 核心任务循环 ===
    private fun startTask() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            secondsCounter++

            for (player in Bukkit.getOnlinePlayers()) {
                for (spawner in spawnerConfigMap.values) {
                    if (player.world.name != spawner.worldName) continue
                    if (player.location.distanceSquared(spawner.center) > spawner.triggerRadiusSq) continue
                    if (secondsCounter % spawner.interval != 0L) continue

                    trySpawn(player, spawner)
                }
            }
            cleanupFarMobs()
        }, 20L, 20L)
    }

    // === 2. 刷怪逻辑 (核心修改) ===
    private fun trySpawn(player: Player, spawner: SpawnerConfig) {
        val spawnerRecords = activeMobs.computeIfAbsent(spawner.id) { ConcurrentHashMap() }
        // 使用 Collections.synchronizedSet 保证线程安全，或者用 ConcurrentHashMap.newKeySet()
        val playerMobs = spawnerRecords.computeIfAbsent(player.uniqueId) { Collections.synchronizedSet(HashSet()) }

        playerMobs.removeIf { uuid ->
            val entity = Bukkit.getEntity(uuid)
            entity == null || !entity.isValid || entity.isDead
        }

        if (playerMobs.size >= spawner.maxAmount) return

        // 获取会话
        val session = depletionSessions.computeIfAbsent(player.uniqueId) { DepletionSession() }

        // [MODIFIED] 1. 检查当前点是否枯竭
        if (spawner.depletionThreshold > 0) {
            val currentCount = session.getCount(spawner.id)
            if (currentCount >= spawner.depletionThreshold) {
                player.sendActionBar(Component.text("§c此处的灵气已耗尽，请前往其他区域狩猎..."))
                return // 拒绝生成，也不会触发重置
            }
        }

        val spawnLoc = if (spawner.mode == SpawnMode.FIXED) {
            spawner.center.clone()
        } else {
            findRandomLocAround(player.location, spawner.spawnRadius)
        } ?: return

        val selectedMobId = spawner.mobPool.getRandomId() ?: return

        val mob = mobManager.spawnPrivateMob(spawnLoc, selectedMobId, player)

        // [MODIFIED] 2. 仅当成功刷怪后，才处理逻辑
        if (mob != null) {
            playerMobs.add(mob.uniqueId)

            // 核心逻辑变化：
            // 只有当当前触发的点也是一个“枯竭点”时，才去尝试重置远处的点。
            if (spawner.depletionThreshold > 0) {
                // A. 增加当前点计数
                session.increment(spawner.id)

                // B. 执行重置逻辑 (Reset Check)
                // 玩家成功在“枯竭点 B”刷出了一只怪 -> 视为有效迁徙 -> 清理掉远处的“枯竭点 A”
                session.cleanupDistance(player.location, spawner.id, spawnerConfigMap)
            }

            // 提示与音效
            if (spawner.spawnMessage != null) player.sendMessage(spawner.spawnMessage.replace("&", "§"))
            if (spawner.spawnTitle != null) player.showTitle(Title.title(Component.empty(), Component.text(spawner.spawnTitle.replace("&", "§"))))

            if (!spawner.spawnSound.isNullOrEmpty()) {
                try {
                    player.playSound(player.location, spawner.spawnSound, 1.0f, 1.0f)
                } catch (ignored: Exception) {}
            }
        }
    }

    // === 3. 数据持久化 ===

    private fun saveDepletionData() {
        if (!depletionFile.parentFile.exists()) depletionFile.parentFile.mkdirs()
        val data = YamlConfiguration()

        depletionSessions.forEach { (uuid, session) ->
            if (!session.isEmpty()) {
                val sec = data.createSection(uuid.toString())
                session.counts.forEach { (k, v) -> sec.set(k, v) }
            }
        }

        try {
            data.save(depletionFile)
        } catch (e: IOException) {
            plugin.logger.warning("无法保存枯竭记录: ${e.message}")
        }
    }

    private fun loadDepletionData() {
        if (!depletionFile.exists()) return
        val data = YamlConfiguration.loadConfiguration(depletionFile)

        for (uuidStr in data.getKeys(false)) {
            try {
                val uuid = UUID.fromString(uuidStr)
                val sec = data.getConfigurationSection(uuidStr)

                if (sec != null) {
                    val session = DepletionSession()
                    for (spawnerId in sec.getKeys(false)) {
                        val count = sec.getInt(spawnerId)
                        session.counts[spawnerId] = count
                    }
                    depletionSessions[uuid] = session
                }
            } catch (ignored: Exception) {}
        }
        plugin.logger.info("已恢复 ${depletionSessions.size} 位玩家的枯竭记录。")
    }

// === 4. 辅助方法 (核心修复版本) ===

    // === 4. 辅助方法 (防卡墙修复版) ===

    private fun findRandomLocAround(center: Location, radius: Double): Location? {
        val world = center.world ?: return null

        // 增加尝试次数以保证成功率
        for (i in 0 until 15) {
            val angle = Math.random() * Math.PI * 2
            val dist = Math.random() * radius

            // 基础坐标 (整数)
            val xBlock = (center.x + cos(angle) * dist).toInt()
            val zBlock = (center.z + sin(angle) * dist).toInt()

            val startY = center.blockY

            // 垂直扫描寻找地面
            for (dy in 0..10) {
                // 优先检查中心，然后上下扩散
                val checkY = if (dy == 0) startY else null // 逻辑占位

                // 检查 +dy
                var attemptY = startY + dy
                if (attemptY < world.maxHeight) {
                    val targetLoc = Location(world, xBlock.toDouble(), attemptY.toDouble(), zBlock.toDouble())
                    if (isValidSpawnSpot(center, targetLoc)) {
                        // [核心修复]
                        // 1. add(0.5, 0.0, 0.5) -> 居中，确保四周有 0.5 的距离，不会卡墙
                        // 2. add(0.0, 0.2, 0.0) -> 垫高，防止卡在地毯/积雪/不平整地面里
                        return targetLoc.add(0.5, 0.2, 0.5)
                    }
                }

                // 检查 -dy
                if (dy != 0) {
                    attemptY = startY - dy
                    if (attemptY > world.minHeight) {
                        val targetLoc = Location(world, xBlock.toDouble(), attemptY.toDouble(), zBlock.toDouble())
                        if (isValidSpawnSpot(center, targetLoc)) {
                            // [核心修复] 同上
                            return targetLoc.add(0.5, 0.2, 0.5)
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 综合判定一个点是否适合刷怪
     * 包含：物理空间检查 + 视线检查
     */
    private fun isValidSpawnSpot(center: Location, loc: Location): Boolean {
        // 1. 基础物理空间检查 (脚下实心，身体不窒息)
        if (!isSafeLocation(loc)) return false

        // 2. [新增] 视线检查 (RayTrace)
        // 原理：从刷怪点中心向目标点发射一条射线。
        // 如果中间碰到了方块，说明目标点在墙后/地下/夹层里 -> 禁止生成
        // 忽略流体和草丛，只检测实心方块
        if (!hasLineOfSight(center, loc)) return false

        return true
    }

    private fun isSafeLocation(loc: Location): Boolean {
        val block = loc.block
        val below = loc.clone().subtract(0.0, 1.0, 0.0).block
        val above = loc.clone().add(0.0, 1.0, 0.0).block

        // 1. 脚下必须是实心
        if (!below.type.isSolid) return false

        // [新增] 2. 脚下不能是栅栏、围墙或活板门 (防止卡在缝隙或骑在墙上)
        val belowType = below.type.name
        if (belowType.contains("FENCE") || belowType.contains("WALL") || belowType.contains("TRAPDOOR")) {
            return false
        }

        // 3. 身体和头必须是安全的 (非窒息方块)
        // isOccluding = 是完整的、不透明的方块。如果为 true，肯定会窒息。
        // 我们要求它必须为 false (比如空气、草、花、雪层)
        if (block.type.isOccluding) return false
        if (above.type.isOccluding) return false

        // 4. 拥挤度检查 (保持不变，这很好)
        var solidNeighbors = 0
        if (block.getRelative(1, 0, 0).type.isSolid) solidNeighbors++
        if (block.getRelative(-1, 0, 0).type.isSolid) solidNeighbors++
        if (block.getRelative(0, 0, 1).type.isSolid) solidNeighbors++
        if (block.getRelative(0, 0, -1).type.isSolid) solidNeighbors++

        if (solidNeighbors >= 3) return false

        return true
    }

    /**
     * [核心] 视线检测
     * @return true = 视线通畅(在露天/同一房间), false = 被墙挡住(在夹层/隔壁)
     */
    private fun hasLineOfSight(start: Location, end: Location): Boolean {
        val world = start.world ?: return false
        val startVec = start.toVector().add(org.bukkit.util.Vector(0.0, 0.5, 0.0)) // 抬高一点视线
        val endVec = end.toVector().add(org.bukkit.util.Vector(0.0, 0.5, 0.0))
        val direction = endVec.clone().subtract(startVec)
        val distance = direction.length()

        if (distance < 1.0) return true // 距离极近，直接允许

        // 发射射线
        val rayTrace = world.rayTraceBlocks(
            start.clone().add(0.0, 0.5, 0.0), // 起点
            direction.normalize(),            // 方向
            distance,                         // 距离
            org.bukkit.FluidCollisionMode.NEVER, // 忽略水
            true // ignorePassable = true (忽略草丛、花朵，只检测墙壁)
        )

        // 如果 rayTrace 为 null，说明中间全是空气 -> 通畅
        if (rayTrace == null) return true

        // 如果碰到了方块
        val hitBlock = rayTrace.hitBlock
        if (hitBlock != null) {
            // 如果碰到的方块距离目标点非常近 (比如就是目标点脚下的方块)，也可以算通过
            // 容差设为 1.5 格
            if (hitBlock.location.distanceSquared(end) < 2.25) {
                return true
            }
            // 否则说明中间有障碍物
            return false
        }

        return true
    }

    private fun cleanupFarMobs() {
        activeMobs.forEach { (spawnerId, playerMap) ->
            val conf = spawnerConfigMap[spawnerId]
            if (conf != null) {
                playerMap.forEach { (playerId, mobUuids) ->
                    val p = Bukkit.getPlayer(playerId)
                    // 如果玩家不在线，稍后由 activeMobs 清理逻辑处理，或者这里也可以清
                    if (p != null && p.isOnline) {
                        mobUuids.removeIf { mobUuid ->
                            val entity = Bukkit.getEntity(mobUuid)

                            // 1. 如果实体已经不存在了
                            if (entity == null || !entity.isValid) {
                                mobManager.unregisterPrivateMob(mobUuid) // [CLEANUP]
                                return@removeIf true
                            }

                            // 2. 如果因为距离太远需要移除
                            if (entity.world != p.world || entity.location.distanceSquared(p.location) > conf.despawnRangeSq) {
                                mobManager.unregisterPrivateMob(entity.uniqueId) // [CLEANUP]
                                entity.remove()
                                return@removeIf true
                            }
                            false
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        removeAllMobsOfPlayer(event.entity.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        removeAllMobsOfPlayer(event.player.uniqueId)
    }

    private fun removeAllMobsOfPlayer(playerId: UUID) {
        activeMobs.values.forEach { map ->
            val mobs = map.remove(playerId)
            if (mobs != null) {
                for (mid in mobs) {
                    val ent = Bukkit.getEntity(mid)
                    // [CLEANUP] 必须先清理队伍记录，再删除实体
                    mobManager.unregisterPrivateMob(mid)
                    ent?.remove()
                }
            }
        }
    }

    private fun clearAllMobs() {
        activeMobs.values.forEach { map ->
            map.values.forEach { set ->
                set.forEach { uuid ->
                    val ent = Bukkit.getEntity(uuid)
                    // [CLEANUP]
                    mobManager.unregisterPrivateMob(uuid)
                    ent?.remove()
                }
            }
        }
        activeMobs.clear()
    }

    // === 5. 配置加载 ===
    private fun loadSpawners() {
        spawnerConfigMap.clear()
        val folder = File(plugin.dataFolder, "spawners")
        if (!folder.exists()) {
            folder.mkdirs()
            val legacyFile = File(plugin.dataFolder, "spawners.yml")
            if (legacyFile.exists()) loadSingleFile(legacyFile)
            return
        }

        folder.walk()
            .filter { it.isFile && it.name.endsWith(".yml") }
            .forEach { loadSingleFile(it) }

        plugin.logger.info("已加载 ${spawnerConfigMap.size} 个刷怪区域。")
    }

    private fun loadSingleFile(file: File) {
        val config = YamlConfiguration.loadConfiguration(file)
        for (id in config.getKeys(false)) {
            try {
                val sec = config.getConfigurationSection(id) ?: continue

                val worldName = sec.getString("world", "world")!!
                val x = sec.getDouble("x")
                val y = sec.getDouble("y")
                val z = sec.getDouble("z")
                val triggerRadius = sec.getDouble("trigger_radius", 10.0)
                val despawnRadius = sec.getDouble("despawn_range", triggerRadius * 2)

                val max = sec.getInt("max_amount", 5)
                val interval = sec.getInt("interval", 5)

                val modeStr = sec.getString("mode", "RANDOM")!!.uppercase()
                val mode = if ("FIXED" == modeStr) SpawnMode.FIXED else SpawnMode.RANDOM

                val spawnRadius = sec.getDouble("spawn_radius", 5.0)

                val msg = sec.getString("spawn_message", null)
                val title = sec.getString("spawn_title", null)
                val sound = sec.getString("spawn_sound", null)
                val depletionThreshold = sec.getInt("depletion_threshold", 0)

                val w = Bukkit.getWorld(worldName)
                if (w == null) continue

                val pool = MobPool()
                if (sec.contains("mobs")) {
                    val mobsSec = sec.getConfigurationSection("mobs")
                    if (mobsSec != null) {
                        for (mid in mobsSec.getKeys(false)) {
                            pool.add(mid, mobsSec.getDouble(mid))
                        }
                    }
                } else if (sec.contains("mob_id")) {
                    pool.add(sec.getString("mob_id")!!, 1.0)
                }

                if (pool.isEmpty()) continue

                // [核心修复] 在这里计算平方！
                val triggerSq = triggerRadius * triggerRadius
                val despawnSq = despawnRadius * despawnRadius

                spawnerConfigMap[id] = SpawnerConfig(
                    id, Location(w, x, y, z),
                    triggerSq, // 传入平方值
                    despawnSq, // 传入平方值
                    pool, max, interval, mode, spawnRadius, worldName,
                    msg, title, sound, depletionThreshold
                )

            } catch (e: Exception) {
                plugin.logger.warning("加载刷怪点 $id 失败: ${e.message}")
            }
        }
    }

    private enum class SpawnMode { RANDOM, FIXED }

    private class MobPool {
        private val map = TreeMap<Double, String>()
        private var totalWeight = 0.0

        fun add(mobId: String, weight: Double) {
            if (weight <= 0) return
            totalWeight += weight
            map[totalWeight] = mobId
        }

        fun getRandomId(): String? {
            if (map.isEmpty()) return null
            val value = ThreadLocalRandom.current().nextDouble() * totalWeight
            val entry = map.higherEntry(value)
            return entry?.value
        }

        fun isEmpty(): Boolean = map.isEmpty()
    }

    private class DepletionSession {
        val counts = HashMap<String, Int>()

        fun isEmpty(): Boolean = counts.isEmpty()

        fun getCount(id: String): Int = counts.getOrDefault(id, 0)

        fun increment(id: String) {
            counts[id] = getCount(id) + 1
        }

        // [核心逻辑]
        fun cleanupDistance(playerLoc: Location, currentTriggerId: String, configMap: Map<String, SpawnerConfig>) {
            val it = counts.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val depletedId = entry.key

                // 不重置自己 (自己正在被刷)
                if (depletedId == currentTriggerId) continue

                val depletedConfig = configMap[depletedId]

                // 如果刷怪点配置已失效(被删了)，或者
                // 玩家与该刷怪点的距离超过了 RESET_DISTANCE_SQ
                // 则视为“已远离”，重置其计数
                if (depletedConfig == null ||
                    depletedConfig.worldName != playerLoc.world.name ||
                    depletedConfig.center.distanceSquared(playerLoc) > RESET_DISTANCE_SQ
                ) {
                    it.remove()
                }
            }
        }
    }

    private data class SpawnerConfig(
        val id: String,
        val center: Location,
        val triggerRadiusSq: Double, // 外部传入的是 radius，这里存的是 radius * radius
        val despawnRangeSq: Double,  // 外部传入的是 radius，这里存的是 radius * radius
        val mobPool: MobPool,
        val maxAmount: Int,
        val interval: Int,
        val mode: SpawnMode,
        val spawnRadius: Double,
        val worldName: String,
        val spawnMessage: String?,
        val spawnTitle: String?,
        val spawnSound: String?,
        val depletionThreshold: Int
    ) {
        // 次级构造函数不是必须的，因为我们在 loadSingleFile 里已经算好了 square，
        // 直接传给主构造函数即可。上面的 data class 定义直接对应计算后的值。

        // 为了确保逻辑与 Java 一致，我们在构造时进行平方计算，或者在外部算好传入。
        // 在 Kotlin 中，通常建议在 data class 内部用 init 块或者外部计算。
        // 上面的 loadSingleFile 里：
        // triggerRadiusSq = triggerRadius * triggerRadius
        // 这样代码更干净。
    }
}