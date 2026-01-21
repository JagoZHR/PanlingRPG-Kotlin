package com.panling.basic.mob.skill.impl

import com.panling.basic.mob.skill.MobSkill
import net.kyori.adventure.text.Component
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

class MessageSkill(config: ConfigurationSection) : MobSkill {
    private val text: String = config.getString("text", "&cGrrr!")!!.replace("&", "§")
    private val radius: Double = config.getDouble("radius", 10.0)

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val msg = text.replace("{target}", target?.name ?: "None")
        val comp = Component.text("${caster.customName ?: caster.name}: $msg")

        // 发送给周围玩家
        if (caster.world != null) {
            caster.world.getNearbyEntities(caster.location, radius, radius, radius)
                .filterIsInstance<Player>()
                .forEach { it.sendMessage(comp) }
        }
        return true
    }
}