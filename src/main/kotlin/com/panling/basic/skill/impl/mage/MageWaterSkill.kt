package com.panling.basic.skill.impl.mage

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.skill.strategy.water.*
import com.panling.basic.util.MageUtil
import org.bukkit.entity.Player

class MageWaterSkill(private val plugin: PanlingBasic) : AbstractSkill("MAGE_WATER", "却邪", PlayerClass.MAGE) {

    private val strategies: MutableMap<String, MageSkillStrategy> = HashMap()

    init {
        registerStrategy("OFFENSE_UNCOMMON_FURNACE", WaterAttackT3Strategy(plugin))
        registerStrategy("SUPPORT_UNCOMMON_FURNACE", WaterSupportT3Strategy(plugin))
        registerStrategy("OFFENSE_RARE_FURNACE", WaterAttackT4FurnaceStrategy(plugin))
        registerStrategy("SUPPORT_RARE_FURNACE", WaterSupportT4FurnaceStrategy(plugin))
        registerStrategy("OFFENSE_EPIC_FURNACE", WaterAttackT5FurnaceStrategy(plugin))
        registerStrategy("SUPPORT_EPIC_FURNACE", WaterSupportT5FurnaceStrategy(plugin))
    }

    private fun registerStrategy(key: String, strategy: MageSkillStrategy) {
        strategies[key] = strategy
    }

    private fun getCurrentStrategy(p: Player): MageSkillStrategy? {
        val stance = plugin.playerDataManager.getArrayStance(p)
        val weapon = MageUtil.getMageWeapon(p)
        val rarity = MageUtil.getWeaponRarity(weapon)
        val type = MageUtil.getWeaponType(weapon)

        val preciseKey = "${stance.name}_${rarity}_${type}"
        if (strategies.containsKey(preciseKey)) return strategies[preciseKey]

        val baseTypeKey = "${stance.name}_UNCOMMON_${type}"
        if (strategies.containsKey(baseTypeKey)) return strategies[baseTypeKey]

        return strategies["${stance.name}_UNCOMMON_DEFAULT"]
    }

    override fun getCastTime(player: Player): Double {
        val s = getCurrentStrategy(player)
        return s?.castTime ?: 0.0
    }

    override fun onCast(ctx: SkillContext): Boolean {
        val s = getCurrentStrategy(ctx.player)
        return s != null && s.cast(ctx)
    }
}