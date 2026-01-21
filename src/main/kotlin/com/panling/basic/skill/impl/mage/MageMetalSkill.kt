package com.panling.basic.skill.impl.mage

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.skill.strategy.metal.*
import com.panling.basic.util.MageUtil
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent

class MageMetalSkill(private val plugin: PanlingBasic) : AbstractSkill("MAGE_METAL", "贯日", PlayerClass.MAGE) {

    private val strategies: MutableMap<String, MageSkillStrategy> = HashMap()

    init {
        // [修改] 注册为炼丹炉 (FURNACE) 专用的 T3 (UNCOMMON) 策略
        registerStrategy("OFFENSE_UNCOMMON_FURNACE", MetalAttackT3Strategy(plugin))
        registerStrategy("SUPPORT_UNCOMMON_FURNACE", MetalSupportT3Strategy(plugin))

        registerStrategy("OFFENSE_RARE_FURNACE", MetalAttackT4FurnaceStrategy(plugin))
        registerStrategy("SUPPORT_RARE_FURNACE", MetalSupportT4FurnaceStrategy(plugin))
        registerStrategy("OFFENSE_EPIC_FURNACE", MetalAttackT5FurnaceStrategy(plugin))
        registerStrategy("SUPPORT_EPIC_FURNACE", MetalSupportT5FurnaceStrategy(plugin))
    }

    private fun registerStrategy(key: String, strategy: MageSkillStrategy) {
        strategies[key] = strategy
    }

    private fun getCurrentStrategy(p: Player): MageSkillStrategy? {
        val stance = plugin.playerDataManager.getArrayStance(p)
        val weapon = MageUtil.getMageWeapon(p)
        val rarity = MageUtil.getWeaponRarity(weapon)
        val type = MageUtil.getWeaponType(weapon)

        // 1. 精确查找 [OFFENSE_RARE_FURNACE]
        val preciseKey = "${stance.name}_${rarity}_${type}"
        if (strategies.containsKey(preciseKey)) return strategies[preciseKey]

        // 2. 同级通用查找 [OFFENSE_RARE_DEFAULT]
        // 注意：这是金系特有的逻辑，保留
        val defaultKey = "${stance.name}_${rarity}_DEFAULT"
        if (strategies.containsKey(defaultKey)) return strategies[defaultKey]

        // 3. [关键] 向下兼容同类型 [OFFENSE_UNCOMMON_FURNACE]
        // 如果手里是 RARE 的炉子，但没写 RARE 策略，就用 UNCOMMON 的炉子策略 (策略内部会根据 tier >= 4 提升数值)
        val baseTypeKey = "${stance.name}_UNCOMMON_${type}"
        if (strategies.containsKey(baseTypeKey)) return strategies[baseTypeKey]

        // 4. 最终保底 [OFFENSE_UNCOMMON_DEFAULT]
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

    override fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {
        val s = getCurrentStrategy(ctx.player)
        s?.onProjectileHit(event, ctx)
    }

    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        val s = getCurrentStrategy(ctx.player)
        s?.onAttack(event, ctx)
    }
}