package com.panling.basic.api

import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor

enum class Rarity(
    val weight: Int,
    val displayName: String,
    val color: TextColor
) {
    BROKEN(1, "破损", NamedTextColor.WHITE),
    COMMON(2, "普通", NamedTextColor.GREEN),
    UNCOMMON(3, "优秀", NamedTextColor.BLUE),
    RARE(4, "稀有", NamedTextColor.LIGHT_PURPLE),
    EPIC(5, "史诗", NamedTextColor.YELLOW),
    // === 资格校验分界线 (>= 6) ===
    LEGENDARY(6, "传说", NamedTextColor.RED),
    MYTHIC(7, "神话", NamedTextColor.DARK_RED),
    ANCIENT(8, "远古", NamedTextColor.LIGHT_PURPLE),
    DIVINE(9, "神圣", NamedTextColor.AQUA),
    ETERNAL(10, "永恒", NamedTextColor.DARK_BLUE);

    companion object {
        // [新增] 核心解析方法：支持传入 Int 或 String，不填返回 null
        fun parse(value: Any?): Rarity? {
            if (value == null) return null

            val strValue = value.toString().trim()
            if (strValue.isEmpty()) return null

            // 1. 优先尝试按数字 (weight) 解析
            val weightVal = strValue.toIntOrNull()
            if (weightVal != null) {
                return values().find { it.weight == weightVal }
            }

            // 2. 尝试按枚举名解析 (例如 "COMMON")
            return try {
                valueOf(strValue.uppercase())
            } catch (e: IllegalArgumentException) {
                null // 解析失败也返回 null，不给默认值
            }
        }

        // [保留] 兼容你可能在其他地方调用了这个旧方法
        fun safeValueOf(name: String?): Rarity {
            return parse(name) ?: BROKEN
        }
    }
}