package com.panling.basic.skill.impl.mage

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.skill.strategy.fire.*
import com.panling.basic.util.MageUtil
import org.bukkit.entity.Player

class MageFireSkill(private val plugin: PanlingBasic) : AbstractSkill("MAGE_FIRE", "天劫", PlayerClass.MAGE) {

    // 缓存策略实例
    private val strategies: MutableMap<String, MageSkillStrategy> = HashMap()

    init {
        // [核心修改]
        // 将 T3 策略专门注册给 "UNCOMMON (3阶)" + "FURNACE (炼丹炉)"
        // 这样只有拿着炼丹炉时，才会调用这套逻辑
        registerStrategy("OFFENSE_UNCOMMON_FURNACE", FireAttackT3Strategy(plugin))
        registerStrategy("SUPPORT_UNCOMMON_FURNACE", FireSupportT3Strategy(plugin))
        registerStrategy("OFFENSE_RARE_FURNACE", FireAttackT4FurnaceStrategy(plugin))
        registerStrategy("SUPPORT_RARE_FURNACE", FireSupportT4FurnaceStrategy(plugin))
        registerStrategy("OFFENSE_EPIC_FURNACE", FireAttackT5FurnaceStrategy(plugin))
        registerStrategy("SUPPORT_EPIC_FURNACE", FireSupportT5FurnaceStrategy(plugin))
    }

    private fun registerStrategy(key: String, strategy: MageSkillStrategy) {
        strategies[key] = strategy
    }

    private fun getCurrentStrategy(p: Player): MageSkillStrategy? {
        // 1. 获取形态 (OFFENSE / SUPPORT)
        val stance = plugin.playerDataManager.getArrayStance(p)

        // 2. 获取副手武器信息 (使用 MageUtil)
        val weapon = MageUtil.getMageWeapon(p)
        val rarity = MageUtil.getWeaponRarity(weapon) // e.g. "UNCOMMON"
        val type = MageUtil.getWeaponType(weapon)     // e.g. "FURNACE"

        // 3. 构建精确 Key: [形态]_[稀有度]_[类型]
        // 例如: OFFENSE_UNCOMMON_FURNACE
        val preciseKey = "${stance.name}_${rarity}_${type}"
        if (strategies.containsKey(preciseKey)) {
            return strategies[preciseKey]
        }

        // 4. 向下兼容同类型 (Tier Downscaling)
        // 例如: 拿着 RARE (4阶) 的炼丹炉，但没写 RARE 的策略，则回退用 UNCOMMON (3阶) 的策略
        // 策略内部会通过 MageUtil.getTierValue(p) 获取真实数值来提升伤害
        val baseTypeKey = "${stance.name}_UNCOMMON_${type}"
        if (strategies.containsKey(baseTypeKey)) {
            return strategies[baseTypeKey]
        }

        // 5. 通用保底 (Default)
        // 如果连类型都不匹配，就找该形态下的通用策略
        val defaultKey = "${stance.name}_UNCOMMON_DEFAULT"
        return strategies[defaultKey]
    }

    override fun getCastTime(player: Player): Double {
        val strategy = getCurrentStrategy(player)
        return strategy?.castTime ?: 0.0
    }

    override fun onCast(ctx: SkillContext): Boolean {
        val strategy = getCurrentStrategy(ctx.player) ?: return false
        return strategy.cast(ctx)
    }
}