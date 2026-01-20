package com.panling.basic.api

import org.bukkit.NamespacedKey

enum class BuffType(
    val displayName: String,
    val color: String,
    val defaultMultiplier: Double,
    vararg val affectedAttributes: NamespacedKey // 使用 vararg 处理不定长参数
) {

    // === 负面状态 (杀伐) ===
    WEAKNESS("致盲虚弱", "§b", 0.8, BasicKeys.ATTR_PHYSICAL_DAMAGE, BasicKeys.ATTR_SKILL_DAMAGE),
    ARMOR_BREAK("融甲", "§c", 0.8, BasicKeys.ATTR_DEFENSE, BasicKeys.ATTR_MAGIC_DEFENSE),
    ROOT("岩石禁锢", "§6", 0.0, BasicKeys.ATTR_MOVE_SPEED),
    SILENCE("沉默", "§1", 1.0),
    POISON("剧毒", "§a", 1.0),
    // [NEW] 迟缓：0.6 代表移动速度变为原来的 60% (减速40%)
    SLOW("迟缓", "§8", 0.6, BasicKeys.ATTR_MOVE_SPEED),

    // === 正面状态 (生息) ===
    // 金：双抗提升 (10% 或 动态数值)
    DEFENSE_UP("金石之护", "§e", 1.1, BasicKeys.ATTR_DEFENSE, BasicKeys.ATTR_MAGIC_DEFENSE),

    // 火：攻击/法强提升 (15%)
    ATTACK_UP("烈焰之力", "§c", 1.15, BasicKeys.ATTR_PHYSICAL_DAMAGE, BasicKeys.ATTR_SKILL_DAMAGE),
    // [NEW] T4火：攻击附带充能伤害 (特殊逻辑，不直接修改面板，由Listener处理)
    FIRE_IMBUE("炎刃", "§6", 1.0),

    // 木：持续回血 (特殊逻辑，不直接影响属性面板，由 BuffManager 每一秒执行一次)
    REGEN("自然复苏", "§a", 0.0), // 倍率0.0表示不影响属性，value存储回血量
    // [NEW] T4木：受治疗量提升 (特殊逻辑，Listener/Skill处理)
    HEAL_BOOST("回春", "§a", 1.2),

    // [NEW] T4水：移动速度提升
    SPEED_UP("神行", "§b", 1.2, BasicKeys.ATTR_MOVE_SPEED),

    KB_RESIST("不动如山", "§8", 0.0, BasicKeys.ATTR_KB_RESIST),

    // 木生火：下一次必定暴击 (标记型 Buff)
    CRIT_GUARANTEE("弱点洞悉", "§4", 1.0);
}