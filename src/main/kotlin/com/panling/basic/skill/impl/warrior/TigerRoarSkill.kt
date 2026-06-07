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
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class TigerRoarSkill(private val plugin: PanlingBasic) :
    AbstractSkill("TIGER_ROAR", "虎啸", PlayerClass.WARRIOR) {

    override var castTime: Double = 0.0

    override fun onCast(ctx: SkillContext): Boolean {
        val player = ctx.player
        val radius = 5.0

        val phys = plugin.statCalculator.getPlayerTotalStat(player,
            com.panling.basic.api.BasicKeys.ATTR_PHYSICAL_DAMAGE)
        val def = plugin.statCalculator.getPlayerTotalStat(player,
            com.panling.basic.api.BasicKeys.ATTR_DEFENSE)

        var hitCount = 0
        player.world.getNearbyEntities(player.location, radius, 2.0, radius).forEach { e ->
            if (e is LivingEntity && CombatUtil.isValidTarget(player, e)) {
                // 伤害 = 物理×0.5 + 防御×0.3
                val dmg = phys * 0.5 + def * 0.3
                CombatUtil.dealPhysicalSkillDamage(player, e, dmg, false)
                // 击退
                val away = e.location.toVector().subtract(player.location.toVector()).normalize()
                e.velocity = away.multiply(1.8).setY(0.6)
                // 减速 III 持续 3 秒
                e.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 3 * 20, 2))
                hitCount++
            }
        }

        if (hitCount > 0) {
            player.world.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.5f)
            player.world.playSound(player.location, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.8f, 0.6f)
            player.world.spawnParticle(Particle.SWEEP_ATTACK, player.location, 30, 3.0, 1.5, 3.0, 0.0)
            player.world.spawnParticle(Particle.CLOUD, player.location.add(0.0, 0.5, 0.0), 15, 2.0, 0.3, 2.0, 0.02)
        }

        return true
    }
}
