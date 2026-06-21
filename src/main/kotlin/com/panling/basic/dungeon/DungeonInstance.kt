package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DungeonInstance(
    val plugin: PanlingBasic,
    val instanceId: String,
    val template: DungeonTemplate,
    val world: World,
    initialPlayers: List<Player>
) {
    val players = ConcurrentHashMap.newKeySet<UUID>()

    var state: DungeonState = DungeonState.LOADING
        private set

    var currentPhase: AbstractDungeonPhase? = null
        private set

    private var startTime: Long = 0L
    private var tickTask: BukkitTask? = null

    var tickCount: Long = 0L
        private set

    private val loadedChunks = mutableSetOf<Chunk>()

    private val bossBar: BossBar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID)
    private var phaseTitle: String = ""

    private val noInfightingListener = object : Listener {
        @EventHandler
        fun onTarget(event: EntityTargetEvent) {
            if (event.entity.world == world && event.target !is Player) {
                event.isCancelled = true
            }
        }
    }

    enum class DungeonState { LOADING, RUNNING, ENDING }

    lateinit var centerLocation: Location

    init {
        initialPlayers.forEach { players.add(it.uniqueId) }
    }

    fun start(initialPhase: AbstractDungeonPhase) {
        if (state != DungeonState.LOADING) return

        state = DungeonState.RUNNING
        startTime = System.currentTimeMillis()
        tickCount = 0L

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { onTick() }, 0L, 1L)

        bossBar.color = BarColor.YELLOW
        bossBar.style = BarStyle.SOLID
        players.forEach { uid -> Bukkit.getPlayer(uid)?.let { bossBar.addPlayer(it) } }
        bossBar.isVisible = true

        transitionToPhase(initialPhase)
        lockChunks()
        Bukkit.getPluginManager().registerEvents(noInfightingListener, plugin)

        broadcast("§a副本已启动！目标：${template.displayName}")
    }

    fun nextPhase(next: AbstractDungeonPhase) {
        if (state != DungeonState.RUNNING) return
        bossBar.color = BarColor.GREEN
        bossBar.setTitle("§a✅ 阶段完成")
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            bossBar.color = BarColor.YELLOW
            transitionToPhase(next)
        }, 40L)
    }

    private fun transitionToPhase(phase: AbstractDungeonPhase) {
        currentPhase = phase
        phaseTitle = ""
        plugin.logger.info("[副本] $instanceId 切换阶段 -> ${phase.javaClass.simpleName}")
        try {
            phase.start()
        } catch (e: Exception) {
            plugin.logger.severe("副本阶段启动出错: ${e.message}")
            e.printStackTrace()
            failDungeon("§c副本内部错误，请联系管理员。")
        }
    }

    fun setPhaseTitle(title: String, progress: Double = -1.0) {
        phaseTitle = title
        if (progress >= 0.0) bossBar.setProgress(progress.coerceIn(0.0, 1.0))
    }

    fun glowEntity(entity: LivingEntity) {
        entity.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, Int.MAX_VALUE, 0, false, false))
    }

    fun unglowEntity(entity: LivingEntity) {
        entity.removePotionEffect(PotionEffectType.GLOWING)
    }

    private fun onTick() {
        if (state != DungeonState.RUNNING) return
        tickCount++

        if (tickCount % 20 == 0L) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            val remaining = (template.timeLimit - elapsed).coerceAtLeast(0)
            val min = remaining / 60
            val sec = remaining % 60
            val timeStr = "§e⏳ ${min}:${sec.toString().padStart(2, '0')}"
            val title = if (phaseTitle.isNotEmpty()) "$timeStr  §7|  $phaseTitle" else timeStr
            bossBar.setTitle(title)
            bossBar.setProgress(remaining.toDouble() / template.timeLimit)

            if (remaining <= 60) bossBar.color = BarColor.RED

            if (elapsed > template.timeLimit) {
                failDungeon("§c时间耗尽！")
                return
            }
        }

        currentPhase?.onTick()
    }

    fun handleInteract(event: PlayerInteractEvent) {
        if (state == DungeonState.RUNNING) currentPhase?.onInteract(event)
    }

    fun handleMobDeath(event: EntityDeathEvent) {
        if (state == DungeonState.RUNNING) currentPhase?.onMobDeath(event)
    }

    fun handlePlayerDeath(player: Player) {
        if (state == DungeonState.RUNNING) {
            currentPhase?.onPlayerDeath(player)
            val allDead = players.all { uid ->
                val p = Bukkit.getPlayer(uid)
                p == null || p.isDead || !p.isOnline
            }
            if (allDead) failDungeon("§c全员阵亡")
            else plugin.dungeonManager.handleDungeonDeath(player)
        }
    }

    fun handleDamage(event: EntityDamageByEntityEvent) {
        if (state == DungeonState.RUNNING) currentPhase?.onDamage(event)
    }

    fun handleQAnswer(player: Player, letter: String) {
        if (state == DungeonState.RUNNING) currentPhase?.onQAnswer(player, letter)
    }

    fun winDungeon() {
        if (state != DungeonState.RUNNING) return
        broadcast("§a恭喜！副本挑战成功！")
        broadcastSound(Sound.UI_TOAST_CHALLENGE_COMPLETE)
        state = DungeonState.ENDING
        bossBar.removeAll()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { stop() }, 200L)
    }

    fun failDungeon(reason: String) {
        if (state != DungeonState.RUNNING) return
        broadcast(reason)
        broadcastSound(Sound.ENTITY_VILLAGER_NO)
        state = DungeonState.ENDING
        stop()
    }

    fun stop() {
        tickTask?.cancel()
        currentPhase?.end()
        bossBar.removeAll()
        loadedChunks.forEach { try { it.removePluginChunkTicket(plugin) } catch (_: Exception) {} }
        loadedChunks.clear()
        HandlerList.unregisterAll(noInfightingListener)
        plugin.dungeonManager.removeInstance(instanceId)
        plugin.logger.info("[副本] $instanceId (${template.displayName}) 已关闭，区块已释放")
    }

    private fun lockChunks() {
        if (template.reviveCost <= 0.0) return
        val dims = SchematicManager.getDimensions(template.schematicName)
        val halfX = ((dims?.first ?: 500) / 16 / 2) + 3
        val halfZ = ((dims?.third ?: 500) / 16 / 2) + 3
        val world = centerLocation.world ?: return
        for (dx in -halfX..halfX) {
            for (dz in -halfZ..halfZ) {
                val chunk = world.getChunkAt(centerLocation.chunk.x + dx, centerLocation.chunk.z + dz)
                chunk.addPluginChunkTicket(plugin)
                loadedChunks.add(chunk)
            }
        }
    }

    fun join(player: Player) {
        players.add(player.uniqueId)
        val spawn = centerLocation.clone().add(template.spawnOffset)
        player.teleport(spawn)
        bossBar.addPlayer(player)
        player.sendMessage("§e你加入了副本：${template.displayName}")
    }

    fun leave(player: Player) {
        players.remove(player.uniqueId)
        bossBar.removePlayer(player)
        val exit = template.exitLoc ?: Bukkit.getWorld("world")?.spawnLocation
        if (exit != null) player.teleport(exit)
        player.sendMessage("§e你离开了副本。")
    }

    fun broadcast(message: String) {
        players.forEach { uid -> Bukkit.getPlayer(uid)?.sendMessage(message) }
    }

    fun broadcastSound(sound: Sound) {
        players.forEach { uid ->
            Bukkit.getPlayer(uid)?.playSound(Bukkit.getPlayer(uid)!!.location, sound, 1f, 1f)
        }
    }
}
