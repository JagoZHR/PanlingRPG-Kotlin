package com.panling.basic.api

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 技能执行上下文
 * 包含施法一次技能所需的所有环境信息
 */
data class SkillContext(
    val player: Player,              // 施法者
    val target: LivingEntity? = null, // 目标 (命中实体时存在)
    val sourceItem: ItemStack? = null,// 触发源物品 (射击时存在)
    val projectile: Entity? = null,   // [NEW] 投射物 (箭矢)
    val location: Location? = null,   // [NEW] 目标位置 (射击起点 或 命中落点)
    val power: Double,               // 基础强度
    val triggerType: SkillTrigger    // 触发类型
) {
    companion object {
        // 快捷构建器 (保持与 Java 代码的兼容性)
        fun of(player: Player, power: Double, trigger: SkillTrigger): SkillContext {
            return SkillContext(
                player = player,
                location = player.location,
                power = power,
                triggerType = trigger
            )
        }
    }
}