package com.panling.basic.dungeon.phase

import com.panling.basic.dungeon.DungeonInstance
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import java.util.UUID

class CombatPhase : AbstractDungeonPhase("COMBAT") {

    private var mobsToSpawn: List<String> = emptyList()

    // [核心优化] 使用 UUID 缓存替代 ScoreboardTag
    // 仅在内存中记录当前阶段生成的怪物，无需遍历世界
    private val mobUuids = HashSet<UUID>()

    override fun load(config: ConfigurationSection) {
        this.mobsToSpawn = config.getStringList("mobs")
    }

    override fun onStart(instance: DungeonInstance) {
        instance.broadcast("§c[警告] 敌人出现了！")
        mobUuids.clear()

        if (mobsToSpawn.isEmpty()) {
            instance.nextPhase()
            return
        }

        // 真实刷怪逻辑
        for (entry in mobsToSpawn) {
            try {
                // 解析配置 "MOB_ID:x,y,z"
                val parts = entry.split(":")
                val mobId = parts[0].trim()

                val locParts = parts[1].split(",")
                val x = locParts[0].trim().toDouble()
                val y = locParts[1].trim().toDouble()
                val z = locParts[2].trim().toDouble()

                // 注意：使用 instance.world 确保怪生在副本里
                val loc = Location(instance.world, x, y, z)

                // [对接 MobManager]
                // 假设 MobManager 还是 Java 版，Kotlin 可以直接调用 .getMobManager() 或 .mobManager
                // 这里的 spawnMob 方法需要你在 MobManager 里确认存在
                val entity = instance.plugin.mobManager.spawnMob(loc, mobId)

                if (entity != null) {
                    // [核心优化] 记录 UUID
                    mobUuids.add(entity.uniqueId)

                    // [建议] 设置防止自然消失 (防止玩家跑远后怪物被系统刷掉导致卡关)
                    entity.removeWhenFarAway = false
                } else {
                    instance.plugin.logger.warning("副本 CombatPhase 无法生成怪物 (ID无效?): $mobId")
                }

            } catch (e: Exception) {
                instance.plugin.logger.warning("副本 CombatPhase 刷怪配置错误: $entry")
                e.printStackTrace()
            }
        }
    }

    override fun onTick(instance: DungeonInstance) {
        // [核心优化] 高效检查存活状态
        // Kotlin 的 MutableIterator.removeIf (JDK8+)
        mobUuids.removeIf { uuid ->
            val e = Bukkit.getEntity(uuid)

            // e == null 说明怪物所在区块被卸载了 (玩家跑远了)
            // 这种情况下不能算死，否则会有 BUG。我们只移除 !isValid 的 (死掉或被指令删除的)
            e != null && !e.isValid
        }

        // 如果集合空了，说明怪都死光了
        if (mobUuids.isEmpty()) {
            instance.broadcast("§a威胁清除！继续前进。")
            instance.nextPhase()
        }
    }

    override fun onEnd(instance: DungeonInstance) {
        // [防崩服] 阶段结束时，精准清理剩余怪物
        mobUuids.forEach { uuid ->
            Bukkit.getEntity(uuid)?.remove()
        }
        mobUuids.clear()
    }
}