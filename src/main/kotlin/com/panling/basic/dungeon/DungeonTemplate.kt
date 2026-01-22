package com.panling.basic.dungeon

import org.bukkit.Location
import org.bukkit.util.Vector

/**
 * 副本模板数据类
 * 仅存储副本的“物理属性”和“准入规则”
 * * 具体的刷怪、BOSS、机制等逻辑全部由代码(DungeonPhase)控制
 */
data class DungeonTemplate(
    val id: String,
    val displayName: String,

    // 对应 Schematic 文件名 (用于生成地图)
    val schematicName: String,

    // 基础限制
    val minLevel: Int,
    val minPlayers: Int,
    val maxPlayers: Int,
    val timeLimit: Int, // 秒

    // 关键坐标
    val spawnOffset: Vector, // 进本出生点 (相对于 Schematic 原点)
    val exitLoc: Location?   // 离开/结束后传送的位置
)