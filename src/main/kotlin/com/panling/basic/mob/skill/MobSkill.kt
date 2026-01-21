package com.panling.basic.mob.skill

import org.bukkit.entity.LivingEntity

fun interface MobSkill {
    /**
     * 执行技能
     * @param caster 施法者 (怪物)
     * @param target 目标 (可能是玩家，也可能是 null，视触发器而定)
     * @return 是否成功执行
     */
    fun cast(caster: LivingEntity, target: LivingEntity?): Boolean
}