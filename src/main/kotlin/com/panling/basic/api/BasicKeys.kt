package com.panling.basic.api

import com.panling.basic.PanlingBasic
import org.bukkit.NamespacedKey
import java.util.EnumMap

/**
 * 核心数据键注册表 (Kotlin重构版)
 *
 * 修复说明：
 * 1. 添加了 load() 方法以兼容主类调用，并强制触发 object 的初始化。
 * 2. 保持了原有的静态数据结构。
 */
object BasicKeys {

    // 获取插件实例 (注意：主类必须先执行 instance = this，否则这里会报错)
    private val plugin = PanlingBasic.instance

    // --- 核心数据结构 ---
    val ALL_STATS = ArrayList<NamespacedKey>()
    val STAT_METADATA = HashMap<NamespacedKey, StatMeta>()
    val SHORT_NAME_MAP = HashMap<String, NamespacedKey>()
    val BASE_TO_PERCENT_MAP = HashMap<NamespacedKey, NamespacedKey>()

    // --- 触发器相关映射 (Trigger Maps) ---
    val TRIGGER_KEYS = EnumMap<SkillTrigger, NamespacedKey>(SkillTrigger::class.java)
    val TRIGGER_COOLDOWN_KEYS = EnumMap<SkillTrigger, NamespacedKey>(SkillTrigger::class.java)
    val TRIGGER_COST_KEYS = EnumMap<SkillTrigger, NamespacedKey>(SkillTrigger::class.java)
    val TRIGGER_LORE_KEYS = EnumMap<SkillTrigger, NamespacedKey>(SkillTrigger::class.java)
    val TRIGGER_LORE_OFFENSE_KEYS = EnumMap<SkillTrigger, NamespacedKey>(SkillTrigger::class.java)
    val TRIGGER_LORE_SUPPORT_KEYS = EnumMap<SkillTrigger, NamespacedKey>(SkillTrigger::class.java)

    /**
     * [修复] 兼容方法
     * 虽然 Kotlin object 会自动初始化，但显式调用此方法有以下好处：
     * 1. 兼容 PanlingBasic 中的 BasicKeys.load(this) 调用。
     * 2. 确保在 onEnable 阶段就立即完成所有 Key 的注册（Eager Loading），而不是等到第一次使用时。
     */
    fun load(plugin: PanlingBasic) {
        // 方法体为空即可。
        // 调用此方法会触发 BasicKeys 类的加载，进而执行 init 块和属性初始化。
    }

    // --- 初始化块：自动填充 Trigger Keys ---
    init {
        for (trigger in SkillTrigger.values()) {
            if (trigger == SkillTrigger.NONE) continue
            val lower = trigger.name.lowercase()

            TRIGGER_KEYS[trigger] = key("pl_skill_$lower")
            TRIGGER_COOLDOWN_KEYS[trigger] = key("pl_cd_$lower")
            TRIGGER_COST_KEYS[trigger] = key("pl_cost_$lower")
            TRIGGER_LORE_KEYS[trigger] = key("pl_lore_$lower")
            TRIGGER_LORE_OFFENSE_KEYS[trigger] = key("pl_lore_off_$lower")
            TRIGGER_LORE_SUPPORT_KEYS[trigger] = key("pl_lore_sup_$lower")
        }
    }

    // --- 基础属性 (Base Attributes) ---
    val ATTR_PHYSICAL_DAMAGE = reg("phys", "pl_attr_physical", "物理强度", false)
    val ATTR_SKILL_DAMAGE    = reg("skill", "pl_attr_skill", "法术强度", false)
    val ATTR_DEFENSE         = reg("def", "pl_attr_defense", "物理防御", false)
    val ATTR_MAGIC_DEFENSE   = reg("mdef", "pl_attr_magic_def", "法术防御", false)

    val ATTR_CRIT_RATE       = reg("crit", "pl_attr_crit_rate", "暴击几率", true)
    val ATTR_CRIT_DMG        = reg("critdmg", "pl_attr_crit_dmg", "暴击伤害", true)
    val ATTR_ARMOR_PEN       = reg("pen", "pl_attr_armor_pen", "物理穿透", false)
    val ATTR_MAGIC_PEN       = reg("mpen", "pl_attr_magic_pen", "法术穿透", false)
    val ATTR_LIFE_STEAL      = reg("lifesteal", "pl_attr_life_steal", "生命偷取", true)
    val ATTR_ATTACK_RANGE    = reg("range", "pl_attr_range", "技能范围", false)

    val ATTR_CDR             = reg("cdr", "pl_attr_cdr", "冷却缩减", true)
    val ATTR_NO_CONSUME      = reg("conserve", "pl_attr_conserve", "元素保留", true)

    val ATTR_MAX_HEALTH      = reg("hp", "pl_attr_health", "生命上限", false)
    val ATTR_MOVE_SPEED      = reg("speed", "pl_attr_speed", "移动速度", true)
    val ATTR_KB_RESIST       = reg("kb", "pl_attr_kb_resist", "击退抗性", true)

    val ATTR_ARROW_VELOCITY  = reg("arrow_velocity", "pl_attr_arrow_vel", "箭矢流速", false)

    // [NEW] 补充缺失的 Key 定义
    val ATTR_EXTRA_PEN = key("pl_attr_extra_pen")

    // --- 百分比属性 (Percent Attributes) ---
    val ATTR_PHYSICAL_PERCENT   = regPercent(ATTR_PHYSICAL_DAMAGE, "phys_pct", "pl_attr_phys_pct", "物理加成")
    val ATTR_SKILL_PERCENT      = regPercent(ATTR_SKILL_DAMAGE, "skill_pct", "pl_attr_skill_pct", "法术加成")
    val ATTR_MAX_HEALTH_PERCENT = regPercent(ATTR_MAX_HEALTH, "hp_pct", "pl_attr_hp_pct", "生命加成")
    val ATTR_ARMOR_PEN_PERCENT  = regPercent(ATTR_ARMOR_PEN, "pen_pct", "pl_attr_pen_pct", "物穿加成")
    val ATTR_MAGIC_PEN_PERCENT  = regPercent(ATTR_MAGIC_PEN, "mpen_pct", "pl_attr_mpen_pct", "法穿加成")
    val ATTR_CRIT_DMG_PERCENT   = regPercent(ATTR_CRIT_DMG, "critdmg_pct", "pl_attr_critdmg_pct", "爆伤加成")

    // --- 物品与数据 Keys (Item & Data) ---
    val ITEM_ID = key("pl_item_id")
    val ITEM_TYPE_TAG = key("pl_item_type")
    val ITEM_RARITY = key("pl_item_rarity")
    val ITEM_RARITY_WEIGHT = key("pl_item_rarity_w")
    val ITEM_DESC = key("pl_item_desc")
    val ITEM_SET_ID = key("pl_item_set_id")
    val ITEM_OWNER = key("pl_item_owner")
    val ITEM_MODEL = key("pl_item_model")
    val ITEM_EQUIPPABLE = key("pl_item_equippable")
    val ITEM_SUB_CLASS = key("pl_item_sub_class")

    // --- 技能与特性 Keys (Features) ---
    val FEATURE_ABILITY_ID = key("pl_feat_ability")
    val FEATURE_REQ_CLASS = key("pl_feat_req_class")
    val FEATURE_REQ_LEVEL = key("pl_feat_req_level")
    val FEATURE_PASSIVE_ID = key("pl_feat_passive")
    val FEATURE_FABAO_SLOT = key("pl_feat_fabao_slot")
    val FEATURE_COOLDOWN = key("pl_feat_cd")
    val FEATURE_TRIGGER = key("feature_trigger")

    // --- 玩家数据 Keys (Player Data) ---
    val DATA_CLASS = key("pl_data_class")
    val DATA_RACE = key("pl_data_race")
    val DATA_ACTIVE_SLOT = key("pl_data_active_slot")
    val DATA_ACCESSORIES = key("pl_data_accessories")
    val DATA_UNLOCKED_ITEMS = key("pl_data_unlocked")
    val DATA_UNLOCKED_RECIPES = key("data_unlocked_recipes")
    val DATA_ARRAY_STANCE = key("pl_data_array_stance")
    val DATA_ELEMENT_POINTS = key("pl_data_element_points")
    val DATA_MONEY = key("pl_data_money")
    val DATA_QUIVER_ARROWS = key("pl_data_quiver_arrows")

    // --- 其他/杂项 Keys ---
    val ARROW_DAMAGE_STORE = key("pl_arrow_dmg_store")
    val PASSIVE_ON_ATTACK = key("pl_pas_attack")
    val PASSIVE_ON_HIT = key("pl_pas_hit")

    val ITEM_DESC_SUPPORT = key("pl_item_desc_support")
    val ITEM_DESC_OFFENSE = key("pl_item_desc_offense")

    val ITEM_ELEMENT_VALUE = key("pl_item_element_val")
    val ITEM_SKILL_COST = key("pl_item_skill_cost")
    val ITEM_MONEY_VALUE = key("pl_item_money_val")

    val MOB_ID = key("pl_mob_id")
    val MOB_OWNER = key("pl_mob_owner")

    val ITEM_SKILL_LORE = key("item_skill_lore")
    val ITEM_SKILL_LORE_SUPPORT = key("item_skill_lore_support")
    val ITEM_SKILL_LORE_OFFENSE = key("item_skill_lore_offense")

    val IS_PHYSICAL_SKILL = key("pl_is_phys_skill")
    val ELEMENT_MARK = key("pl_element_mark")
    val SKILL_ELEMENT = key("pl_skill_element")

    val MAGE_WEAPON_TYPE = key("mage_weapon_type")
    val FABAO_LORE_OFFENSE = key("fabao_lore_offense")
    val FABAO_LORE_SUPPORT = key("fabao_lore_support")


    // --- 辅助方法 ---

    private fun key(key: String): NamespacedKey {
        return NamespacedKey(plugin, key)
    }

    private fun reg(shortName: String, keyKey: String, displayName: String, isPercent: Boolean): NamespacedKey {
        val k = key(keyKey)
        ALL_STATS.add(k)
        STAT_METADATA[k] = StatMeta(displayName, isPercent)
        SHORT_NAME_MAP[shortName.lowercase()] = k
        return k
    }

    private fun regPercent(baseKey: NamespacedKey, shortName: String, keyKey: String, displayName: String): NamespacedKey {
        val pctKey = reg(shortName, keyKey, displayName, true)
        BASE_TO_PERCENT_MAP[baseKey] = pctKey
        return pctKey
    }

    // 数据类
    data class StatMeta(val displayName: String, val isPercent: Boolean)
}