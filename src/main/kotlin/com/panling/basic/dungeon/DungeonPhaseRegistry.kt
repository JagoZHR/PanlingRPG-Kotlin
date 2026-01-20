package com.panling.basic.dungeon

import com.panling.basic.dungeon.phase.AbstractDungeonPhase
// 下一步迁移具体 Phase 后，记得取消这些 import 的注释
import com.panling.basic.dungeon.phase.WaitingPhase
import com.panling.basic.dungeon.phase.CombatPhase
import com.panling.basic.dungeon.phase.RewardPhase
import com.panling.basic.dungeon.phase.PreQuestPhase

class DungeonPhaseRegistry {

    // 存储阶段的构造工厂
    // Key: 阶段类型ID (如 "WAITING")
    // Value: 创建该阶段新实例的函数 () -> AbstractDungeonPhase
    private val phases = HashMap<String, () -> AbstractDungeonPhase>()

    init {
        registerDefaults()
    }

    private fun registerDefaults() {
        // [待办] 当具体的 Phase 类迁移完成后，请取消以下注释：

         register("WAITING") { WaitingPhase() }
         register("COMBAT") { CombatPhase() }
         register("REWARD") { RewardPhase() }
         register("PRE_QUEST") { PreQuestPhase() }

        // [高自由度] 未来你可以在这里注册特定的硬编码阶段
        // register("FIRE_BOSS_MECHANIC") { FireBossPhase() }
    }

    /**
     * 注册新的阶段类型
     * @param type 阶段标识符
     * @param factory 构造工厂函数
     */
    fun register(type: String, factory: () -> AbstractDungeonPhase) {
        phases[type.uppercase()] = factory
    }

    /**
     * 创建阶段实例
     */
    fun createPhase(type: String): AbstractDungeonPhase? {
        val factory = phases[type.uppercase()]
        return factory?.invoke() // 调用工厂函数创建新实例
    }
}