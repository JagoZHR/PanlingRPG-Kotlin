package com.panling.basic.api

/**
 * 统一技能触发器枚举
 * 涵盖了 玩家交互 和 怪物生命周期
 */
enum class SkillTrigger(
    val displayName: String,
    /**
     * 元数据：指示系统是否要检查 冷却(Cooldown)、魔法消耗(Mana) 等
     * 玩家主动技能通常为 true，怪物AI或被动通常为 false (或者由怪物的独立CD系统控制)
     */
    val isCheckRequired: Boolean
) {
    // === 玩家交互类 (Player Inputs) ===
    RIGHT_CLICK("右键", true),
    LEFT_CLICK("左键", true),
    SHIFT_RIGHT("蹲+右键", true),
    SHIFT_LEFT("蹲+左键", true),
    SWAP_HAND("F键", true),
    DROP("丢弃", true),
    BLOCK("格挡", true),

    // === 战斗事件类 (Combat Events) ===
    // 既可以是玩家被动，也可以是怪物技能
    ATTACK("攻击时", true),      // 玩家攻击 / 怪物攻击
    DAMAGED("受击时", true),     // 玩家受击 / 怪物受击
    PROJECTILE_HIT("命中", false), // 投射物命中
    PASSIVE("被动", false),      // 通用被动检查

    // === 怪物/实体生命周期类 (Mob Lifecycle) ===
    // 这些通常不需要检查“手持物品”或“消耗”，所以 checkRequired = false 或根据需求定
    SPAWN("出生", false),
    DEATH("死亡", false),
    TIMER("定时", false),        // 定时器循环
    HP_50("血量50%", false),
    HP_20("血量20%", false),

    NONE("无", false);
}