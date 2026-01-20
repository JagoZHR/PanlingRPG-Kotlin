package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 副本游戏实例
 * 代表一局正在进行的副本游戏
 */
class DungeonInstance(
    val plugin: PanlingBasic,
    val manager: DungeonManager,
    val template: DungeonTemplate,
    val leader: Player,
    val world: World
) {
    val uuid: UUID = UUID.randomUUID()

    // 使用线程安全的集合 (虽然一般来说 tick 是单线程的，但为了稳健)
    val players = ConcurrentHashMap.newKeySet<UUID>()

    var state: DungeonState = DungeonState.LOADING
        private set

    private var startTime: Long = 0L

    // 阶段管理
    private val phases = ArrayList<AbstractDungeonPhase>()
    private var phaseIndex = -1
    private var currentPhase: AbstractDungeonPhase? = null

    enum class DungeonState { LOADING, WAITING, RUNNING, ENDING, CLEARED }

    init {
        players.add(leader.uniqueId)
        // 从模板配置中加载阶段
        // 注意：这里调用了 Manager 的辅助方法
        val loadedPhases = manager.createPhases(template.phaseConfig)
        phases.addAll(loadedPhases)
    }

    /**
     * 开始副本 (进本逻辑)
     * 由 DungeonManager 构建完世界后调用
     */
    fun start() {
        this.state = DungeonState.RUNNING
        this.startTime = System.currentTimeMillis()

        // 计算出生点 (基于 Schematic 原点的偏移)
        val spawnLoc = Location(
            world,
            template.spawnOffset.x,
            template.spawnOffset.y,
            template.spawnOffset.z
        )

        // 传送玩家
        players.forEach { uid ->
            val p = Bukkit.getPlayer(uid)
            if (p != null) {
                p.teleport(spawnLoc)
                p.sendMessage("§a[副本] 传送完成，挑战开始！")
                p.playSound(p.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
            }
        }

        // 启动第一个阶段
        nextPhase()
    }

    /**
     * 推进到下一阶段
     */
    fun nextPhase() {
        // 结束当前阶段
        currentPhase?.onEnd(this)

        phaseIndex++

        // 检查是否所有阶段都结束了
        if (phaseIndex >= phases.size) {
            finishDungeon()
            return
        }

        // 启动新阶段
        currentPhase = phases[phaseIndex]
        plugin.logger.info("[Instance $uuid] 进入阶段: ${currentPhase?.type}")

        // 这里的 try-catch 是为了防止某个阶段报错导致副本卡死
        try {
            currentPhase?.onStart(this)
        } catch (e: Exception) {
            plugin.logger.severe("阶段启动异常: ${e.message}")
            e.printStackTrace()
            failDungeon("§c副本内部错误")
        }
    }

    /**
     * 心跳 (每秒由 Manager 调用)
     */
    fun tick() {
        if (state == DungeonState.LOADING || state == DungeonState.ENDING) return

        // 1. 检查超时
        val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
        if (elapsedSeconds > template.timeLimit) {
            failDungeon("§c副本时间耗尽！")
            return
        }

        // 2. 检查玩家在线
        // 如果所有人都不在线了，直接销毁
        val hasOnline = players.any { Bukkit.getPlayer(it) != null }
        if (!hasOnline) {
            manager.removeInstance(uuid)
            return
        }

        // 3. 驱动当前阶段
        if (state == DungeonState.RUNNING) {
            try {
                currentPhase?.onTick(this)
            } catch (e: Exception) {
                plugin.logger.warning("阶段 Tick 异常: ${e.message}")
            }
        }
    }

    /**
     * 移除玩家 (离开/退出)
     */
    fun removePlayer(player: Player) {
        val uid = player.uniqueId
        if (!players.contains(uid)) return

        players.remove(uid)

        // 1. 传送到退出点
        val exitLoc = template.exitLoc ?: Bukkit.getWorlds()[0].spawnLocation
        player.teleport(exitLoc)
        player.sendMessage("§e[系统] 你已离开副本。")

        // 2. 通知 Manager 清理映射
        manager.clearPlayerMap(uid)

        // 3. 如果没人了，销毁实例
        if (players.isEmpty()) {
            manager.removeInstance(uuid)
        }
    }

    /**
     * 正常通关
     */
    fun finishDungeon() {
        state = DungeonState.CLEARED
        broadcast("§6§l副本通关！30秒后将自动传送离开...")

        // 播放音效
        broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)

        // 延时踢人
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            // 复制一份列表防止并发修改异常
            val playerList = ArrayList(players)
            playerList.forEach { uid ->
                val p = Bukkit.getPlayer(uid)
                if (p != null) removePlayer(p)
            }
        }, 600L) // 30秒
    }

    /**
     * 挑战失败
     */
    fun failDungeon(reason: String) {
        if (state == DungeonState.ENDING) return
        broadcast(reason)
        broadcast("§c挑战失败！将在 10 秒后关闭副本...")

        state = DungeonState.ENDING // 防止重复触发
        broadcastSound(Sound.ENTITY_VILLAGER_NO)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            // 强制结束
            endDungeon()
        }, 200L)
    }

    /**
     * 强制关闭 (通常由 failDungeon 或 管理员指令触发)
     */
    fun endDungeon() {
        state = DungeonState.ENDING
        manager.removeInstance(uuid) // 这会触发 Manager 的清理逻辑 (踢人+回收)
    }

    /**
     * 处理交互事件 (分发给 Phase)
     */
    fun handleInteract(event: PlayerInteractEvent): Boolean {
        if (state == DungeonState.RUNNING && currentPhase != null) {
            return currentPhase!!.onInteract(this, event)
        }
        return false
    }

    // --- 辅助方法 ---

    fun broadcast(msg: String) {
        players.forEach { uid -> Bukkit.getPlayer(uid)?.sendMessage(msg) }
    }

    fun broadcastSound(sound: Sound) {
        players.forEach { uid ->
            val p = Bukkit.getPlayer(uid)
            p?.playSound(p.location, sound, 1f, 1f)
        }
    }
}