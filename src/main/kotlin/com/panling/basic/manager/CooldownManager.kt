package com.panling.basic.manager

import org.bukkit.entity.Player
import java.util.HashMap
import java.util.UUID

class CooldownManager {

    // 存储结构: Map<玩家ID, Map<技能名, 下次可使用的时间戳>>
    private val cooldowns = HashMap<UUID, MutableMap<String, Long>>()

    /**
     * 检查是否处于冷却中
     * @param player 玩家
     * @param skillName 技能名
     * @return 还有多少毫秒冷却（0表示可以使用）
     */
    fun getRemainingCooldown(player: Player, skillName: String): Long {
        val uuid = player.uniqueId

        // 获取玩家的冷却表，如果为空说明无冷却
        val playerCooldowns = cooldowns[uuid] ?: return 0

        // 获取特定技能的结束时间
        val endTime = playerCooldowns[skillName] ?: return 0
        val currentTime = System.currentTimeMillis()

        if (currentTime >= endTime) {
            // 冷却已过期，清理内存
            playerCooldowns.remove(skillName)

            // [优化] 如果该玩家没有任何冷却了，移除玩家条目以释放内存
            if (playerCooldowns.isEmpty()) {
                cooldowns.remove(uuid)
            }
            return 0
        }

        return endTime - currentTime
    }

    /**
     * 设置冷却
     * @param player 玩家
     * @param skillName 技能名
     * @param durationMillis 冷却时长(毫秒)
     */
    fun setCooldown(player: Player, skillName: String, durationMillis: Long) {
        if (durationMillis <= 0) return

        // Kotlin 惯用写法: getOrPut 替代 computeIfAbsent
        val playerCooldowns = cooldowns.getOrPut(player.uniqueId) { HashMap() }
        playerCooldowns[skillName] = System.currentTimeMillis() + durationMillis
    }

    // [建议] 添加清理离线玩家的方法 (可在 PlayerQuitEvent 中调用)
    fun clearPlayer(player: Player) {
        cooldowns.remove(player.uniqueId)
    }
}