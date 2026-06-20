package com.panling.basic.mob.skill.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
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
            // 用 spawnPrivateMob 确保召唤物是独立怪（只有目标玩家可见）
            val player = target as? org.bukkit.entity.Player
            val mob = if (player != null)
                plugin.mobManager.spawnPrivateMob(loc, mobId, player)
            else
                plugin.mobManager.spawnMob(loc, mobId)
            mob ?: return@repeat

            // 召唤物不掉落任何物品（清除 MOB_ID 使其不被掉落系统识别）
            mob.persistentDataContainer.remove(BasicKeys.MOB_ID)

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
