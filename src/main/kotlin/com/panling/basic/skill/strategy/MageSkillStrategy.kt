package com.panling.basic.skill.strategy

import com.panling.basic.api.SkillContext
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent

// [修改] 继承通用的 SkillStrategy
interface MageSkillStrategy : SkillStrategy {

    // 执行施法 (返回 true 表示成功)
    fun cast(ctx: SkillContext): Boolean

    // 获取前摇时间 (秒)
    val castTime: Double

    // 可选：处理投射物命中
    fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {}

    // 可选：处理伤害/效果
    fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {}
}