package com.panling.basic.api

import org.bukkit.Material
import org.bukkit.inventory.EquipmentSlot

enum class PlayerClass(
    val displayName: String,
    val meleeMultiplier: Double,
    val rangeMultiplier: Double,
    val magicMultiplier: Double
) {
    NONE("无职业", 0.0, 0.0, 0.0),
    WARRIOR("战士", 1.0, 0.0, 0.0),
    ARCHER("弓箭手", 0.0, 1.0, 0.0),
    MAGE("炼丹师", 0.0, 0.0, 1.0);

    /**
     * 判断该职业是否可以使用此类武器
     * 修改后：法师不再局限于炼药锅，而是依赖 InventoryListener 的 Tag 检查
     */
    fun isAllowedWeapon(mat: Material): Boolean {
        if (this == NONE) return false

        // 盔甲永远允许 (职业绑定由Tag决定)
        if (isArmor(mat)) return true

        return when (this) {
            WARRIOR -> {
                // 战士：除了弓、弩、炼药锅，其他都能用 (保持原样)
                !isBow(mat) && mat != Material.CAULDRON
            }
            ARCHER -> {
                // 射手：只能用弓、弩 (保持原样)
                isBow(mat)
            }
            MAGE -> {
                // 法师：放宽限制，允许大部分物品 (例如木棍、烈焰棒等作为法杖)
                // 具体的"主手元素/副手法器"逻辑由 InventoryListener 里的 Tag 控制
                // 我们只禁止法师使用弓箭
                !isBow(mat)
            }
            else -> false
        }
    }

    private fun isBow(mat: Material): Boolean {
        return mat == Material.BOW || mat == Material.CROSSBOW
    }

    companion object {
        /**
         * 判定是否为防具
         */
        fun isArmor(mat: Material): Boolean {
            val slot = mat.equipmentSlot
            return slot == EquipmentSlot.HEAD ||
                    slot == EquipmentSlot.CHEST ||
                    slot == EquipmentSlot.LEGS ||
                    slot == EquipmentSlot.FEET
        }
    }
}