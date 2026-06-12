package com.panling.basic.mob.skill.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.mob.skill.MobSkill
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

class CobwebSkill(config: ConfigurationSection) : MobSkill {
    private val radius: Int = config.getInt("radius", 2)
    private val message: String? = config.getString("message")

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val world = caster.world ?: return false
        val players = world.getNearbyEntities(caster.location, 10.0, 5.0, 10.0)
            .filterIsInstance<Player>()
            .filter { !it.isDead }

        var placed = false
        for (player in players) {
            val footBlock = player.location.block
            if (footBlock.type == Material.AIR || footBlock.type == Material.CAVE_AIR) {
                footBlock.type = Material.COBWEB
                placed = true
                Bukkit.getScheduler().runTaskLater(
                    PanlingBasic.instance,
                    Runnable { if (footBlock.type == Material.COBWEB) footBlock.type = Material.AIR },
                    100L
                )
            }
        }
        if (message != null) broadcast(caster, message)
        return placed
    }
}
