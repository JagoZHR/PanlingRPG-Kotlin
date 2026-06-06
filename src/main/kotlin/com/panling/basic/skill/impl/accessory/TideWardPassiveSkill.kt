package com.panling.basic.skill.impl.accessory

import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import org.bukkit.potion.PotionEffectType

/**
 * 潮汐护符被动：持续移除玩家的挖掘疲劳效果
 *
 * 注册方式：物品配置中加 `passive: TIDE_WARD`
 * ClassScanner 自动发现并注册，无需修改任何已有代码
 */
class TideWardPassiveSkill : AbstractSkill("TIDE_WARD", "潮汐庇护", PlayerClass.NONE) {

    override fun onCast(ctx: SkillContext): Boolean {
        val player = ctx.player
        if (player.hasPotionEffect(PotionEffectType.MINING_FATIGUE)) {
            player.removePotionEffect(PotionEffectType.MINING_FATIGUE)
        }
        return false // CONSTANT 被动不触发冷却
    }
}
