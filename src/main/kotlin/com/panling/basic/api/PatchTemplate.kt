package com.panling.basic.api

import org.bukkit.configuration.ConfigurationSection

/**
 * 贴片模板数据类
 */
data class PatchTemplate(
    val id: String,
    val name: String,
    val materialRarity: Rarity,
    val stat: String,
    val perPatchPct: Double,
    val craftMaterial: String,
    val craftCount: Int,
    val craftCost: Double
) {
    companion object {
        fun load(id: String, section: ConfigurationSection): PatchTemplate {
            val rarity = Rarity.parse(section.get("material_rarity")) ?: Rarity.COMMON
            return PatchTemplate(
                id = id,
                name = section.getString("name", id)!!,
                materialRarity = rarity,
                stat = section.getString("stat", "phys")!!,
                perPatchPct = section.getDouble("per_patch_pct", 3.0),
                craftMaterial = section.getString("craft_material", "iron_shard")!!,
                craftCount = section.getInt("craft_count", 3),
                craftCost = section.getDouble("craft_cost", 100.0)
            )
        }
    }
}
