package com.panling.basic.dungeon.phase

import com.panling.basic.api.BasicKeys
import com.panling.basic.dungeon.DungeonInstance
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

class PreQuestPhase : AbstractDungeonPhase("PRE_QUEST") {

    private var skipItemId: String? = null
    private var targetLocationStr: String? = null

    override fun load(config: ConfigurationSection) {
        this.skipItemId = config.getString("skip_item")
        this.targetLocationStr = config.getString("target_loc")
    }

    override fun onStart(instance: DungeonInstance) {
        instance.broadcast("§e[阶段] 正在进行前置试炼...")

        // 1. 检查跳过逻辑 (使用安全调用 ?.let)
        skipItemId?.let { targetId ->
            // 查找持有凭证的玩家
            val playerWithTicket = instance.players.asSequence()
                .mapNotNull { Bukkit.getPlayer(it) }
                .find { hasItem(it, targetId) }

            if (playerWithTicket != null) {
                // 这里可以添加扣除道具逻辑
                playerWithTicket.sendMessage("§6[提示] 检测到通关凭证，跳过前置试炼！")
                playerWithTicket.playSound(playerWithTicket.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)

                instance.nextPhase()
                return
            }
        }

        instance.broadcast("§c请前往指定地点或击败守卫以继续...")
    }

    override fun onTick(instance: DungeonInstance) {
        targetLocationStr?.let { locStr ->
            try {
                // 虽然每 Tick 解析字符串有点浪费性能，但为了保持与原版逻辑一致暂时保留
                // (如果追求极致性能，建议在 load() 里解析好存为 Location 对象)
                val parts = locStr.split(",")
                val tx = parts[0].toDouble()
                val ty = parts[1].toDouble()
                val tz = parts[2].toDouble()
                // instance.world 是 Kotlin 属性访问
                val target = Location(instance.world, tx, ty, tz)

                // 检查是否有玩家到达目标点
                val reached = instance.players.asSequence()
                    .mapNotNull { Bukkit.getPlayer(it) }
                    .any { it.location.distance(target) < 5.0 }

                if (reached) {
                    instance.broadcast("§a前置试炼通过！")
                    instance.nextPhase()
                }
            } catch (ignored: Exception) {
                // 忽略解析或世界错误
            }
        }
    }

    override fun onEnd(instance: DungeonInstance) {
        // 清理逻辑
    }

    private fun hasItem(p: Player, targetId: String): Boolean {
        // 使用 Kotlin 集合操作简化库存检查
        return p.inventory.contents.any { i ->
            if (i == null || !i.hasItemMeta()) return@any false

            // 安全获取 PersistentDataContainer
            val id = i.itemMeta!!.persistentDataContainer.get(BasicKeys.ITEM_ID, PersistentDataType.STRING)
            targetId == id
        }
    }
}