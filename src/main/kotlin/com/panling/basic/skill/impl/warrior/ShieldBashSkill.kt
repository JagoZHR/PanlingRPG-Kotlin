package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.util.CombatUtil
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

class ShieldBashSkill(private val plugin: PanlingBasic) :
    AbstractSkill("SHIELD_BASH", "盾击", PlayerClass.WARRIOR) {

    override var castTime: Double = 0.0

    override fun onCast(ctx: SkillContext): Boolean {
        val player = ctx.player
        val radius = 3.0

        val phys = plugin.statCalculator.getPlayerTotalStat(player,
            com.panling.basic.api.BasicKeys.ATTR_PHYSICAL_DAMAGE)

        var hitCount = 0
        player.world.getNearbyEntities(player.location, radius, 2.0, radius).forEach { e ->
            if (e is LivingEntity && CombatUtil.isValidTarget(player, e)) {
                CombatUtil.dealPhysicalSkillDamage(player, e, phys * 0.6, false)
                val away = e.location.toVector().subtract(player.location.toVector()).normalize()
                e.velocity = away.multiply(1.2).setY(0.5)
                hitCount++
            }
        }

        if (hitCount > 0) {
            player.world.playSound(player.location, Sound.ITEM_SHIELD_BLOCK, 1.2f, 0.8f)
            player.world.playSound(player.location, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.8f, 1.5f)
            player.world.spawnParticle(Particle.SWEEP_ATTACK, player.location, 20, 2.0, 1.0, 2.0, 0.0)
        }

        return true
    }
}
