package com.panling.basic.api

import org.bukkit.Material

enum class ForgeCategory(
    val displayName: String,
    val icon: Material,
    vararg val bindingTypes: String // vararg 对应 Java 的 String...
) {
    WEAPON("§c武器锻造", Material.IRON_SWORD, "WEAPON"),
    ARMOR("§b防具锻造", Material.DIAMOND_CHESTPLATE, "ARMOR"),
    ACCESSORY("§d饰品锻造", Material.EMERALD, "ACCESSORY"),
    FABAO("§6法宝锻造", Material.NETHER_STAR, "FABAO"),
    //ELEMENT("§e元素炼制", Material.BLAZE_POWDER, "ELEMENT"),
    OTHER("§7杂项/材料", Material.PAPER, "MATERIAL", "OTHER");

    companion object {
        /**
         * 根据 items.yml 的 type 自动归类 (预留功能)
         */
        fun fromItemType(type: String?): ForgeCategory {
            if (type.isNullOrEmpty()) return OTHER

            // 使用 Kotlin 的集合操作简化查找逻辑
            return entries.find { cat ->
                cat.bindingTypes.any { it.equals(type, ignoreCase = true) }
            } ?: OTHER
        }
    }
}