package com.panling.basic.mob.skill.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.mob.skill.MobSkill
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

/**
 * 伤害反馈技能：当怪物受击时，向攻击者显示伤害数值。
 * 配合 DAMAGED 触发器使用。
 *
 * YAML 配置：
 *   skills:
 *     - type: DAMAGE_FEEDBACK
 *       trigger: DAMAGED
 *       chance: 1.0
 */
class DamageFeedbackSkill(config: ConfigurationSection) : MobSkill {

    companion object {
        val LAST_DAMAGE_KEY = NamespacedKey(PanlingBasic.instance, "pl_last_damage")
    }

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        val attacker = target as? Player ?: return false

        val pdc = caster.persistentDataContainer
        val damage = pdc.get(LAST_DAMAGE_KEY, PersistentDataType.DOUBLE) ?: return false

        val mobName = caster.customName ?: caster.name

        attacker.sendMessage(
            Component.text("§e▸ 对 ", NamedTextColor.YELLOW)
                .append(Component.text(mobName))
                .append(Component.text(" §e造成了 ", NamedTextColor.YELLOW))
                .append(Component.text("§c${String.format("%.1f", damage)}", NamedTextColor.RED))
                .append(Component.text(" §e点伤害", NamedTextColor.YELLOW))
        )

        return true
    }
}
