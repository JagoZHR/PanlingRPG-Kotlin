package com.panling.basic.dungeon.phase

import com.panling.basic.dungeon.DungeonInstance
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.event.player.PlayerInteractEvent

/**
 * 副本阶段抽象基类
 * 替代了原有的 IDungeonPhase 接口
 */
abstract class AbstractDungeonPhase(
    /**
     * 获取阶段类型ID (如 "WAITING", "COMBAT")
     */
    val type: String
) {

    /**
     * [初始化] 从 YAML 读取配置
     * 在副本实例创建时调用
     */
    abstract fun load(config: ConfigurationSection)

    /**
     * [开始] 阶段刚切换时调用
     * 用于生成怪物、设置空气墙、发通知
     */
    abstract fun onStart(instance: DungeonInstance)

    /**
     * [循环] 每秒(或每tick)调用
     * 用于检查击杀数、倒计时、特殊机制触发
     */
    abstract fun onTick(instance: DungeonInstance)

    /**
     * [结束] 阶段切换或副本强制结束时调用
     * 用于清理场地、删除怪物
     */
    abstract fun onEnd(instance: DungeonInstance)

    /**
     * [NEW] 处理玩家交互事件
     * 默认为不处理。子类可以重写此方法来实现特定交互逻辑（如点击机关）。
     * @return true 表示已处理(取消原版事件)，false 表示忽略
     */
    open fun onInteract(instance: DungeonInstance, event: PlayerInteractEvent): Boolean {
        return false
    }
}