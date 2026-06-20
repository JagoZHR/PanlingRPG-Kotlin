package com.panling.basic.skill.impl.accessory

import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill

/**
 * 幽视被动：允许玩家看到 visible_passive=ghost_sight 的特殊怪物
 *
 * 注册方式：饰品配置中加 `passive: ghost_sight`
 * ClassScanner 自动发现并注册
 */
class GhostSightPassive : AbstractSkill("ghost_sight", "幽视", PlayerClass.NONE) {

    override fun onCast(ctx: SkillContext): Boolean = false
}
