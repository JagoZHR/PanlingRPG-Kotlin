package com.panling.basic.skill.impl.archer

import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.util.CombatUtil
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

class ExplosiveArrowSkill(plugin: JavaPlugin) :
    AbstractSkill("EXPLOSIVE_ARROW", "爆炸射击", PlayerClass.ARCHER) {

    private val skillArrowKey = NamespacedKey(plugin, "skill_arrow_id")

    override fun onCast(ctx: SkillContext): Boolean {
        // === 阶段 1: 射击时 (主动触发) ===
        // 目标：给箭矢打上标记
        if (ctx.triggerType.isCheckRequired) { // RIGHT_CLICK, SHIFT_RIGHT 等
            val projectile = ctx.projectile
            if (projectile is AbstractArrow) {
                // 标记这根箭是 "EXPLOSIVE_ARROW" 的载体
                projectile.persistentDataContainer.set(skillArrowKey, PersistentDataType.STRING, id)

                // 播放射击音效
                ctx.player.playSound(ctx.player.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f)
                return true // 成功，系统会自动扣费/冷却
            }
            return false // 没箭矢，施法失败
        }

        // === 阶段 2: 命中时 (回调触发) ===
        // 目标：造成爆炸
        if (ctx.triggerType == SkillTrigger.PROJECTILE_HIT) {
            // 在落点生成爆炸特效
            val loc = ctx.location
            if (loc != null) {
                loc.world.spawnParticle(Particle.EXPLOSION, loc, 1)
            }
            if (loc != null) {
                loc.world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
            }

            // 范围伤害逻辑
            val radius = 4.0
            val damage = 10.0 + (ctx.power * 1.5) // 基础10 + 1.5倍技能强度

            // [核心修改] 使用 CombatUtil 自动索敌
            // 自动过滤：玩家(包括自己)、宠物、无敌单位
            // 这样爆炸就不会炸到队友，也不会“吞”掉判定
            loc?.let { CombatUtil.getEnemiesAround(it, radius, ctx.player) }?.forEach { victim ->
                // 爆炸通常视为物理伤害 (isMagic=false)
                CombatUtil.dealSkillDamage(ctx.player, victim, damage, false)
            }

            // 命中时也要返回 true
            return true
        }

        return false
    }
}