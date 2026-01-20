package com.panling.basic.api

enum class PlayerSubClass(val displayName: String) {
    NONE("无流派"),

    // 战士流派
    PO_JUN("破军"),       // 低血高攻，随时间叠加吸血
    GOLDEN_BELL("金钟"),  // 随时间叠加防御，横扫RPG化

    // 射手流派
    SNIPER("狙击"),       // 满蓄力无视击退抗性
    RANGER("游侠");       // 随时间加快弩装填
}