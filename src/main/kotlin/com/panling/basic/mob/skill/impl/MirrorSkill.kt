package com.panling.basic.mob.skill.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.mob.skill.MobSkill
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.persistence.PersistentDataType

class MirrorSkill(config: ConfigurationSection) : MobSkill {
    private val duration: Long = config.getLong("duration", 60)
    private val rate: Double = config.getDouble("rate", 0.5)
    private val message: String? = config.getString("message")

    companion object {
        val MIRROR_KEY = NamespacedKey(PanlingBasic.instance, "pl_mirror")
    }

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val pdc = caster.persistentDataContainer
        val mirrorData = "$rate:${Bukkit.getCurrentTick() + duration}"
        pdc.set(MIRROR_KEY, PersistentDataType.STRING, mirrorData)

        caster.isInvulnerable = true
        caster.isGlowing = true
        caster.world.spawnParticle(Particle.ENCHANTED_HIT, caster.location.add(0.0, 1.5, 0.0), 30, 0.5, 1.0, 0.5, 0.0)
        caster.world.playSound(caster.location, Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.5f)

        Bukkit.getScheduler().runTaskLater(PanlingBasic.instance, Runnable {
            pdc.remove(MIRROR_KEY)
            caster.isInvulnerable = false
            caster.isGlowing = false
        }, duration)

        if (message != null) broadcast(caster, message)
        return true
    }
}
