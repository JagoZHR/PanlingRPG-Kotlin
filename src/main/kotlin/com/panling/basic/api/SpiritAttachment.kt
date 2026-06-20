package com.panling.basic.api

import org.bukkit.configuration.ConfigurationSection

/**
 * 附灵数据模型
 */
data class SpiritAttachment(
    val id: String,
    val name: String,
    val type: AttachmentType,
    val tier: Int,
    val effects: Map<String, Double>,           // 数值型效果: stat_short → value
    val specialMonsters: List<String>,          // 仅 SPECIAL 类型
    val passiveId: String?,                     // 仅 COMBAT 类型：被动技能ID
    val materialCost: Map<String, Int>?         // 拆卸退还材料 (可选)
) {
    companion object {
        fun load(id: String, section: ConfigurationSection): SpiritAttachment {
            val typeStr = section.getString("type", "FUN")!!
            val type = try {
                AttachmentType.valueOf(typeStr.uppercase())
            } catch (e: Exception) {
                AttachmentType.FUN
            }

            val tier = section.getInt("tier", 1)

            val effects = mutableMapOf<String, Double>()
            val effectsSection = section.getConfigurationSection("effects")
            if (effectsSection != null) {
                for (key in effectsSection.getKeys(false)) {
                    effects[key] = effectsSection.getDouble(key, 0.0)
                }
            }

            val specialMonsters = section.getStringList("special_monsters")

            val passiveId = section.getString("passive_id")

            val materialCost = mutableMapOf<String, Int>()
            val matsSection = section.getConfigurationSection("detach_cost")
            if (matsSection != null) {
                for (key in matsSection.getKeys(false)) {
                    materialCost[key] = matsSection.getInt(key, 0)
                }
            }

            val name = section.getString("name", id)!!

            return SpiritAttachment(
                id = id,
                name = name,
                type = type,
                tier = tier,
                effects = effects,
                specialMonsters = specialMonsters,
                passiveId = passiveId,
                materialCost = if (materialCost.isEmpty()) null else materialCost
            )
        }
    }
}
