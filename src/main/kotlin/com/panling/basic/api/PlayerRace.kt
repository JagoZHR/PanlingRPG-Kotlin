package com.panling.basic.api

import org.bukkit.NamespacedKey

enum class PlayerRace(
    val displayName: String,
    val color: String,
    val bonusStats: List<NamespacedKey>,
    val baseVal: Double,
    val growth: Double,
    val statDesc: String
) {

    // === 1. 人族 (原神族天赋) ===
    // 设定：凡人身躯虽小但韧性极强，精通防御之道。
    // 效果：物理防御 + 法术防御
    // 数值：基础 1.0 + 每级 0.2 -> 100级时 +21双抗
    HUMAN("人族", "§f", listOf(BasicKeys.ATTR_DEFENSE, BasicKeys.ATTR_MAGIC_DEFENSE),
        1.0, 0.2, "双重防御"),

    // === 2. 神族 (原人族天赋) ===
    // 设定：天生神体，生命力磅礴。
    // 效果：最大生命值
    // 数值：基础 20 + 每级 2.0 -> 100级时 +220血
    DIVINE("神族", "§e", listOf(BasicKeys.ATTR_MAX_HEALTH),
        20.0, 2.0, "生命上限"),

    // === 3. 仙族 (道法自然) ===
    // 效果：冷却缩减
    // 数值：基础 1% + 每级 0.05% -> 100级时 +6% CDR
    IMMORTAL("仙族", "§b", listOf(BasicKeys.ATTR_CDR),
        0.01, 0.0005, "冷却缩减"),

    // === 4. 妖族 (嗜血本性) ===
    // 效果：暴击几率
    // 数值：基础 1% + 每级 0.1% -> 100级时 +11% 暴击
    DEMON("妖族", "§5", listOf(BasicKeys.ATTR_CRIT_RATE),
        0.01, 0.001, "暴击几率"),

    // === 5. 战神族 (无坚不摧) ===
    // 效果：物理穿透 + 法术穿透
    // 数值：基础 2.0 + 每级 0.3 -> 100级时 +32 双穿
    WAR_GOD("战神族", "§4", listOf(BasicKeys.ATTR_ARMOR_PEN, BasicKeys.ATTR_MAGIC_PEN),
        2.0, 0.3, "双重穿透"),

    NONE("未选择", "§7", emptyList(), 0.0, 0.0, "");

    // 计算属性：带颜色的名称
    val coloredName: String
        get() = "$color$displayName"

    /**
     * 计算当前等级的加成数值 (已限制 100 级上限)
     */
    fun calculateBonus(level: Int): Double {
        if (this == NONE || bonusStats.isEmpty()) return 0.0

        // [核心修改] 锁定等级上限为 100
        val validLevel = level.coerceAtMost(100)
        return baseVal + (validLevel * growth)
    }
}