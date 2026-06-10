package com.panling.basic.manager

import com.panling.basic.PanlingBasic
import com.panling.basic.api.Reloadable
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File

/**
 * 传送点管理器：加载配置、检测解锁、执行传送。
 *
 * 配置文件：teleports.yml
 */
class TeleportManager(private val plugin: PanlingBasic) : Reloadable {

    data class TeleportPoint(
        val id: String,
        val name: String,
        val location: Location,
        val unlockRadius: Double,
        val cost: Int
    )

    private val points = LinkedHashMap<String, TeleportPoint>()  // 保持插入顺序

    init {
        plugin.reloadManager?.register(this)
        loadConfig()
        startUnlockChecker()
    }

    override fun reload() = loadConfig()

    // ── 配置加载 ──

    fun loadConfig() {
        points.clear()
        val file = File(plugin.dataFolder, "teleports.yml")
        if (!file.exists()) { file.createNewFile(); return }

        val cfg = YamlConfiguration.loadConfiguration(file)
        for (id in cfg.getKeys(false)) {
            val sec = cfg.getConfigurationSection(id) ?: continue
            try {
                val locStr = sec.getString("location") ?: continue
                val loc = parseLocation(locStr) ?: continue
                points[id] = TeleportPoint(
                    id = id,
                    name = sec.getString("name", id)!!,
                    location = loc,
                    unlockRadius = sec.getDouble("unlock_radius", 5.0),
                    cost = sec.getInt("cost", 10)
                )
            } catch (e: Exception) {
                plugin.logger.warning("[TeleportManager] 加载传送点 $id 失败: ${e.message}")
            }
        }
        plugin.logger.info("已加载 ${points.size} 个传送点。")
    }

    // ── 解锁检测（每秒） ──

    private fun startUnlockChecker() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                val unlocked = plugin.playerDataManager.getUnlockedTeleports(player)
                for ((id, point) in points) {
                    if (id in unlocked) continue
                    if (player.world.name != point.location.world?.name) continue
                    if (player.location.distanceSquared(point.location) <= point.unlockRadius * point.unlockRadius) {
                        plugin.playerDataManager.unlockTeleport(player, id)
                        player.sendMessage("§a✦ 发现了传送点：${point.name}")
                        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f)
                    }
                }
            }
        }, 20L, 20L)
    }

    // ── 传送执行 ──

    fun teleport(player: Player, pointId: String): Boolean {
        val point = points[pointId] ?: return false
        val data = plugin.playerDataManager

        // 检查解锁
        if (!data.isTeleportUnlocked(player, pointId)) {
            player.sendMessage("§c你尚未发现此传送点！")
            return false
        }

        // 检查灵力
        val elements = data.getElementPoints(player)
        if (elements < point.cost) {
            player.sendMessage("§c灵力不足！需要 ${point.cost} 灵力（当前: $elements）")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return false
        }

        // 执行
        data.takeElementPoints(player, point.cost.toLong())
        player.teleport(point.location)
        player.sendMessage("§b传送至 ${point.name} §b消耗了 ${point.cost} 灵力")
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
        return true
    }

    fun getPoints(): Map<String, TeleportPoint> = points

    // ── 辅助 ──

    private fun parseLocation(str: String): Location? {
        val parts = str.split(",").map { it.trim() }
        if (parts.size < 4) return null
        val world = Bukkit.getWorld(parts[0]) ?: return null
        return Location(
            world,
            parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble(),
            if (parts.size > 4) parts[4].toFloat() else 0f,
            if (parts.size > 5) parts[5].toFloat() else 0f
        )
    }
}
