package com.panling.basic.api

enum class ArrayStance(
    val displayName: String,
    val description: String
) {
    SUPPORT("§a[辅] 生息形态", "§7专注于团队增益与大范围控场，伤害降低。"),
    OFFENSE("§c[攻] 杀伐形态", "§7专注于极致爆发输出，失去群体庇护能力。");

    /**
     * 切换形态
     */
    fun toggle(): ArrayStance {
        return if (this == SUPPORT) OFFENSE else SUPPORT
    }
}