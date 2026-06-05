package com.panling.basic.dungeon

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.dungeon.phase.StandardRewardPhase
import com.panling.basic.dungeon.phase.StandardWaitingPhase

/**
 * 标准副本逻辑基类（Template Method 模式）
 *
 * 子类只需实现：
 *   1. [createGamePhase] — 独特的玩法阶段
 *   2. [createRewardConfig] — 奖励内容配置
 *
 * 框架自动处理：
 *   - 等待阶段（可配秒数）
 *   - 奖励阶段（自动发奖 or 宝箱交互）
 *
 * 用法示例：
 * ```kotlin
 * class MyDungeon(plugin) : StandardDungeonLogic(plugin) {
 *     override val templateId = "my_dungeon"
 *     override val waitDuration = 10
 *
 *     override fun createGamePhase(instance) = MyGamePhase(instance)
 *     override fun createRewardConfig(instance) = RewardConfig(money = 100.0, autoReward = true)
 * }
 * ```
 */
abstract class StandardDungeonLogic(
    protected val plugin: PanlingBasic
) : DungeonLogicProvider {

    /** 等待阶段秒数（可覆盖） */
    open val waitDuration: Int = 10

    /** 子类实现：创建玩法阶段 */
    abstract fun createGamePhase(instance: DungeonInstance): AbstractDungeonPhase

    /** 子类实现：奖励配置（返回 null 表示无奖励，直接结束副本） */
    abstract fun createRewardConfig(instance: DungeonInstance): RewardConfig?

    /**
     * 模板方法：框架自动组装 [等待] → [玩法]
     * 玩法阶段完成后，子类调用 [goToRewardPhase] 进入奖励阶段
     */
    override fun createInitialPhase(instance: DungeonInstance): AbstractDungeonPhase {
        return StandardWaitingPhase(plugin, instance, waitDuration) {
            instance.nextPhase(createGamePhase(instance))
        }
    }

    /**
     * 工具方法：玩法阶段结束时调用，自动进入奖励阶段
     * 如果 createRewardConfig 返回 null，则直接结束副本
     */
    fun goToRewardPhase(instance: DungeonInstance) {
        val config = createRewardConfig(instance)
        if (config != null) {
            instance.nextPhase(StandardRewardPhase(plugin, instance, config))
        } else {
            instance.winDungeon()
        }
    }
}
