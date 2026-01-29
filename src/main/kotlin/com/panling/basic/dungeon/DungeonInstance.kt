package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 副本游戏实例
 * 代表一局正在进行的副本游戏
 */
class DungeonInstance(
    val plugin: PanlingBasic,
    val instanceId: String, // [修改] 这里改用 String 类型的 instanceId 以匹配 Manager
    val template: DungeonTemplate,
    val world: World,
    initialPlayers: List<Player> // [修改] 构造函数接收初始玩家列表
) {
    // 玩家集合
    val players = ConcurrentHashMap.newKeySet<UUID>()

    // 状态管理
    var state: DungeonState = DungeonState.LOADING
        private set

    // [修改] 当前阶段 (不再使用 List<Phase> 和 Index)
    var currentPhase: AbstractDungeonPhase? = null
        private set

    private var startTime: Long = 0L
    private var tickTask: BukkitTask? = null

    // [修复] 新增 tick 计数器，供 Phase 使用
    var tickCount: Long = 0L
        private set

    enum class DungeonState { LOADING, RUNNING, ENDING }

    lateinit var centerLocation: Location

    init {
        // 初始化玩家列表
        initialPlayers.forEach { players.add(it.uniqueId) }
    }

    /**
     * 启动副本
     * [修改] 必须传入初始阶段
     */
    fun start(initialPhase: AbstractDungeonPhase) {
        if (state != DungeonState.LOADING) return

        state = DungeonState.RUNNING
        startTime = System.currentTimeMillis()
        tickCount = 0L // 重置计数器

        // 启动心跳任务
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            onTick()
        }, 0L, 1L)

        // 进入第一阶段
        transitionToPhase(initialPhase)

        broadcast("§a副本已启动！目标：${template.displayName}")
    }

    /**
     * 切换到下一个阶段
     * [修改] 由当前阶段显式传入下一个阶段对象
     */
    fun nextPhase(next: AbstractDungeonPhase) {
        if (state != DungeonState.RUNNING) return
        transitionToPhase(next)
    }

    private fun transitionToPhase(phase: AbstractDungeonPhase) {
        // 结束旧阶段
        currentPhase?.end()

        // 启动新阶段
        currentPhase = phase
        plugin.logger.info("副本 $instanceId 切换阶段 -> ${phase.javaClass.simpleName}")

        try {
            phase.start()
        } catch (e: Exception) {
            plugin.logger.severe("副本阶段启动出错: ${e.message}")
            e.printStackTrace()
            failDungeon("§c副本内部错误，请联系管理员。")
        }
    }

    private fun onTick() {
        if (state != DungeonState.RUNNING) return

        // [修复] 计数器自增
        tickCount++

        // 检查超时 (每秒检查一次即可，节省性能)
        if (tickCount % 20 == 0L) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            if (elapsed > template.timeLimit) {
                failDungeon("§c时间耗尽！")
                return
            }
        }

        // 驱动当前阶段
        currentPhase?.onTick()

        // 可选：更新计分板/Actionbar
        // players.forEach { ... }
    }

    // ==========================================
    // 事件分发 (代理给 currentPhase)
    // ==========================================

    fun handleInteract(event: PlayerInteractEvent) {
        if (state == DungeonState.RUNNING) {
            currentPhase?.onInteract(event)
        }
    }

    fun handleMobDeath(event: EntityDeathEvent) {
        if (state == DungeonState.RUNNING) {
            currentPhase?.onMobDeath(event)
        }
    }

    fun handlePlayerDeath(player: Player) {
        if (state == DungeonState.RUNNING) {
            currentPhase?.onPlayerDeath(player)
            // 简单的失败判定示例：全员死亡
            // if (getAlivePlayers().isEmpty()) failDungeon("全员阵亡")
        }
    }

    fun handleDamage(event: EntityDamageByEntityEvent) {
        if (state == DungeonState.RUNNING) {
            // 如果 Phase 需要处理伤害事件，可以在 AbstractDungeonPhase 加对应方法
            // currentPhase?.onDamage(event)
        }
    }

    // ==========================================
    // 结算与销毁
    // ==========================================

    /**
     * 挑战成功 (通常由最后一个 Phase 调用)
     */
    fun winDungeon() {
        if (state != DungeonState.RUNNING) return
        broadcast("§a恭喜！副本挑战成功！")
        broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)

        // 延时关闭
        state = DungeonState.ENDING
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            stop()
        }, 200L) // 10秒后传出
    }

    /**
     * 挑战失败
     */
    fun failDungeon(reason: String) {
        if (state != DungeonState.RUNNING) return
        broadcast(reason)
        broadcast("§c挑战失败！副本即将关闭...")
        broadcastSound(Sound.ENTITY_VILLAGER_NO)

        state = DungeonState.ENDING
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            stop()
        }, 100L) // 5秒后传出
    }

    /**
     * 停止并清理
     * (Manager.removeInstance 会调用 leave，这里负责内部任务清理)
     */
    fun stop() {
        tickTask?.cancel()
        currentPhase?.end()
        // 通知 Manager 销毁我
        // Manager 会负责把人踢出去并回收 World
        plugin.dungeonManager.removeInstance(instanceId)
    }

    // ==========================================
    // 玩家管理
    // ==========================================

    fun join(player: Player) {
        players.add(player.uniqueId)
        // 传送到副本出生点
        // [修改] 计算绝对坐标：中心点 + 偏移量
        val spawn = centerLocation.clone().add(template.spawnOffset)
        player.teleport(spawn)
        player.sendMessage("§e你加入了副本：${template.displayName}")
    }

    fun leave(player: Player) {
        players.remove(player.uniqueId)

        // 传送到退出点
        val exit = template.exitLoc ?: Bukkit.getWorld("world")?.spawnLocation
        if (exit != null) {
            player.teleport(exit)
        }

        player.sendMessage("§e你离开了副本。")
    }

    // ==========================================
    // 工具方法
    // ==========================================

    fun broadcast(message: String) {
        players.forEach { uid ->
            Bukkit.getPlayer(uid)?.sendMessage(message)
        }
    }

    fun broadcastSound(sound: Sound) {
        players.forEach { uid ->
            Bukkit.getPlayer(uid)?.playSound(Bukkit.getPlayer(uid)!!.location, sound, 1f, 1f)
        }
    }
}