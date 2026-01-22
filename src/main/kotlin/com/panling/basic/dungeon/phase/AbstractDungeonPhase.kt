package com.panling.basic.dungeon.phase

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent

/**
 * 副本阶段抽象基类 (Code-First 版)
 * * 子类应通过构造函数接收必要的参数，而不是通过 YAML load
 */
abstract class AbstractDungeonPhase(
    val plugin: PanlingBasic,
    val instance: DungeonInstance
) {

    /**
     * [生命周期] 阶段开始
     * 用于生成怪物、初始化倒计时、生成宝箱等
     */
    abstract fun start()

    /**
     * [生命周期] 阶段结束
     * 用于清理场地、移除当前阶段特有的实体
     */
    open fun end() {}

    /**
     * [生命周期] 每 Tick 调用
     * 用于倒计时、检查胜利条件
     */
    open fun onTick() {}

    // ==========================================
    // 事件钩子 (子类按需覆盖)
    // ==========================================

    open fun onInteract(event: PlayerInteractEvent) {}

    open fun onMobDeath(event: EntityDeathEvent) {}

    open fun onPlayerDeath(player: Player) {}

    open fun onDamage(event: EntityDamageByEntityEvent) {}
}