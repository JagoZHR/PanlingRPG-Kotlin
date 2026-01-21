package com.panling.basic.skill.impl.mage

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.skill.strategy.earth.*
import com.panling.basic.util.MageUtil
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent

class MageEarthSkill(private val plugin: PanlingBasic) : AbstractSkill("MAGE_EARTH", "移山", PlayerClass.MAGE) {

    private val strategies: MutableMap<String, MageSkillStrategy> = HashMap()

    init {
        registerStrategy("OFFENSE_UNCOMMON_FURNACE", EarthAttackT3Strategy(plugin))
        registerStrategy("SUPPORT_UNCOMMON_FURNACE", EarthSupportT3Strategy(plugin))
        registerStrategy("OFFENSE_RARE_FURNACE", EarthAttackT4FurnaceStrategy(plugin))
        registerStrategy("SUPPORT_RARE_FURNACE", EarthSupportT4FurnaceStrategy(plugin))
        registerStrategy("OFFENSE_EPIC_FURNACE", EarthAttackT5FurnaceStrategy(plugin))
        registerStrategy("SUPPORT_EPIC_FURNACE", EarthSupportT5FurnaceStrategy(plugin))
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

    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        val s = getCurrentStrategy(ctx.player)
        s?.onAttack(event, ctx)
    }
}