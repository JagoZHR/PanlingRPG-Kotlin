package com.panling.basic.skill.strategy

import com.panling.basic.api.SkillContext

/**
 * [架构扩展] 战士职业的技能策略接口
 */
interface WarriorSkillStrategy : SkillStrategy {

    /**
     * 执行战士技能
     * @param ctx 技能上下文
     * @return 是否释放成功
     */
    fun cast(ctx: SkillContext): Boolean

    // 战士可能需要更短的前摇或瞬发，这里预留接口
    val castTime: Double
        get() = 0.0
}