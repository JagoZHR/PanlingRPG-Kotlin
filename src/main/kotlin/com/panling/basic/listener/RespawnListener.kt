package com.panling.basic.listener

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.PlayerRace
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.persistence.PersistentDataType

/**
 * 管理玩家的重生点：
 *   - 未选择种族/职业 → 重生在 (1315, 76, 42) 下方安全位
 *   - 已完成选择     → 重生在 (179, 43, 63) 下方安全位
 *
 * 核心策略：不在 respawn 事件里设 location（会被 MC 事后推上去），
 * 而是 1 tick 后强制传送，彻底绕开 MC 的"找安全位"逻辑。
 */
class RespawnListener(private val plugin: PanlingBasic) : Listener {

    companion object {
        /** 已完成选择的玩家重生点 */
        private val INITIALIZED_RESPAWN = Location(null, 179.5, 45.0, 63.5)

        /** 未完成选择的玩家重生点 */
        private val UNINITIALIZED_RESPAWN = Location(null, 1315.5, 76.0, 42.5)

        private const val MIN_BUILD_Y = -64
    }

    private val dataManager get() = plugin.playerDataManager

    // ── 玩家加入：根据状态记录重生目标 ──
    @EventHandler(priority = EventPriority.LOW)
    fun onJoin(event: PlayerJoinEvent) {
        saveRespawnTarget(event.player)
    }

    // ── 重生时：1 tick 后强制传送到安全位 ──
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val world = player.world

        // 根据状态选目标坐标
        val isInit = isPlayerInitialized(player)
        val targetLoc = if (isInit) INITIALIZED_RESPAWN else UNINITIALIZED_RESPAWN

        // 在目标世界的目标 XZ 向下搜索安全 Y
        val safe = findSafeY(world, targetLoc.blockX, targetLoc.blockZ, targetLoc.blockY)
            ?: targetLoc.blockY

        // 1 tick 后强制传送 — 此时 MC 已经执行完它自己的推送，我们直接覆盖
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) {
                player.teleport(Location(world, targetLoc.x, safe.toDouble(), targetLoc.z))
            }
        }, 1L)
    }

    // ── 保存/刷新重生目标 ──

    fun saveRespawnTarget(player: Player) {
        val isInit = isPlayerInitialized(player)
        val key = if (isInit) "inited" else "uninited"
        player.persistentDataContainer.set(RESPAWN_TARGET_KEY, PersistentDataType.STRING, key)
    }

    // ── 内部 ──

    private val RESPAWN_TARGET_KEY = org.bukkit.NamespacedKey(plugin, "pl_respawn_target")

    private fun isPlayerInitialized(player: Player): Boolean {
        val raceStr = player.persistentDataContainer.get(BasicKeys.DATA_RACE, PersistentDataType.STRING)
        val classStr = player.persistentDataContainer.get(BasicKeys.DATA_CLASS, PersistentDataType.STRING)
        if (raceStr == null || classStr == null) return false
        return try {
            PlayerRace.valueOf(raceStr) != PlayerRace.NONE && PlayerClass.valueOf(classStr) != PlayerClass.NONE
        } catch (_: Exception) { false }
    }

    /**
     * 在给定 XZ 下，从 startY 向下搜索第一个安全 Y（脚+头均非固体）。
     * getBlockAt() 是 O(1) 块数组访问，无性能问题。
     */
    private fun findSafeY(world: org.bukkit.World, x: Int, z: Int, startY: Int): Int? {
        var y = startY
        while (y >= MIN_BUILD_Y) {
            val feet = world.getBlockAt(x, y, z).type
            val head = world.getBlockAt(x, y + 1, z).type
            if (isSafeBlock(feet) && isSafeBlock(head)) return y
            y--
        }
        return null
    }

    private fun isSafeBlock(mat: Material): Boolean {
        return mat.isAir || mat == Material.WATER || mat == Material.LAVA
                || mat == Material.VOID_AIR || mat == Material.CAVE_AIR
                || mat == Material.LIGHT
    }
}
