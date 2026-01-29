package com.panling.basic.dungeon.phase

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDeathEvent
import java.util.UUID

/**
 * 战斗阶段基类
 * * 自动处理：怪物存活追踪、怪物全清检测
 */
abstract class AbstractCombatPhase(
    plugin: PanlingBasic,
    instance: DungeonInstance
) : AbstractDungeonPhase(plugin, instance) {

    // 内存中追踪的怪物 UUID 集合
    private val activeMobUuids = HashSet<UUID>()

    /**
     * [必须实现] 战斗开始时的逻辑
     * 在这里调用 spawnMob() 生成怪物
     */
    abstract override fun start()

    /**
     * [必须实现] 所有怪物被清空后的逻辑
     * 通常在这里写: instance.nextPhase(RewardPhase(...))
     */
    abstract fun onWaveClear()

    /**
     * [工具方法] 生成并追踪怪物
     * 子类应该调用这个方法来生成怪，而不是直接用 world.spawnEntity
     */
    protected fun spawnMob(entity: LivingEntity?) {
        if (entity != null) {
            activeMobUuids.add(entity.uniqueId)
            // 防止怪物因距离过远被系统清除
            entity.removeWhenFarAway = false
        }
    }

    override fun onTick() {
        // 定期清理无效实体 (防止 EntityRemoveEvent 没触发的情况)
        if (instance.tickCount % 20 == 0L) {
            activeMobUuids.removeIf { uuid ->
                val entity = Bukkit.getEntity(uuid)
                // 如果实体不存在(可能被卸载)或者已死亡，则移除
                entity == null || !entity.isValid || entity.isDead
            }

            // 再次检查是否清空
            checkClear()
        }
    }

    override fun onMobDeath(event: EntityDeathEvent) {
        if (activeMobUuids.contains(event.entity.uniqueId)) {
            activeMobUuids.remove(event.entity.uniqueId)
            checkClear()
        }
    }

    private fun checkClear() {
        if (activeMobUuids.isEmpty()) {
            onWaveClear()
        }
    }

    override fun end() {
        // 阶段强制结束时，清理剩余怪物
        activeMobUuids.forEach { uuid ->
            Bukkit.getEntity(uuid)?.remove()
        }
        activeMobUuids.clear()
    }
}