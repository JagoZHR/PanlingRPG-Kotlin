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
    ANCIENT(8, "远古", NamedTextColor.LIGHT_PURPLE), // 可换 Hex
    DIVINE(9, "神圣", NamedTextColor.AQUA),
    ETERNAL(10, "永恒", NamedTextColor.DARK_BLUE);

    companion object {
        // 默认回退
        fun safeValueOf(name: String?): Rarity {
            if (name.isNullOrEmpty()) return BROKEN
            return try {
                valueOf(name.uppercase())
            } catch (e: IllegalArgumentException) {
                BROKEN
            }
        }
    }
}