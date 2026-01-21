package com.panling.basic.skill.strategy

import com.panling.basic.api.SkillContext
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent

/**
 * 弓箭手系技能的通用策略接口
 * 所有游侠、狙击手的技能都应该实现此接口
 */
interface ArcherSkillStrategy {

    // 默认行为：允许射击 (可以在这里做统一拦截)
    fun canShoot(event: EntityShootBowEvent, ctx: SkillContext): Boolean {
        return true
    }

    // 默认行为：允许命中
    fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {}

    // 可以在这里定义弓箭手特有的方法，例如：
    // val headshotMultiplier: Double get() = 1.5
    fun onShoot(player: Player, arrow: AbstractArrow, event: EntityShootBowEvent) {}
}