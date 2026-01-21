package com.panling.basic.skill.strategy

/**
 * [通用架构] 所有职业技能策略的顶层接口
 * 定义通用的战斗行为契约
 */
interface SkillStrategy {

    /**
     * 该策略是否允许选中/伤害玩家？
     * 默认为 true (允许 PVP)。
     * 如果是纯 PVE 技能 (如刷怪技能)，请在实现类中重写返回 false。
     */
    val canHitPlayers: Boolean
        get() = false
}