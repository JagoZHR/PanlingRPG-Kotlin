package com.panling.basic.mob.skill.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.mob.skill.MobSkill
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import kotlin.random.Random

class SummonSkill(config: ConfigurationSection) : MobSkill {
    private val mobId: String = config.getString("mob", "forest_zombie")!!
    private val count: Int = config.getInt("count", 1)
    private val message: String? = config.getString("message")
    private val hpMult: Double = config.getDouble("hp_mult", 1.0)
    private val physMult: Double = config.getDouble("phys_mult", 1.0)
    private val speedMult: Double = config.getDouble("speed_mult", 1.0)
    private val scale: Double = config.getDouble("scale", 1.0)

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val plugin = PanlingBasic.instance
        repeat(count) {
            val loc = caster.location.clone().add(
                (Random.nextDouble() - 0.5) * 4, 0.0, (Random.nextDouble() - 0.5) * 4
            )
            val mob = plugin.mobManager.spawnMob(loc, mobId) ?: return@repeat

            // 标记为召唤物，防止递归召唤
            mob.persistentDataContainer.set(
                org.bukkit.NamespacedKey(plugin, "is_summon_minion"),
                org.bukkit.persistence.PersistentDataType.BYTE, 1
            )

            // 属性缩放
            mob.getAttribute(Attribute.MAX_HEALTH)?.let { attr ->
                attr.baseValue = attr.baseValue * hpMult
                mob.health = attr.baseValue
            }
            mob.getAttribute(Attribute.ATTACK_DAMAGE)?.let { attr ->
                attr.baseValue = attr.baseValue * physMult
            }
            mob.getAttribute(Attribute.MOVEMENT_SPEED)?.let { attr ->
                attr.baseValue = attr.baseValue * speedMult
            }
            if (scale != 1.0) {
                mob.getAttribute(Attribute.SCALE)?.baseValue = scale
            }
            if (target != null && mob is Mob) {
                mob.target = target
            }
        }
        if (message != null) broadcast(caster, message)
        return true
    }
}
