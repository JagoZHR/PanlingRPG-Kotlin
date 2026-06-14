package com.panling.basic.dungeon

import org.bukkit.Location
import org.bukkit.util.Vector

data class PrePasteSchematic(
    val name: String,
    val offsetZ: Int
)

/**
 * 副本模板数据类
 * 仅存储副本的"物理属性"和"准入规则"
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
    val exitLoc: Location?,   // 离开/结束后传送的位置

    // 是否让玩家站在高空玻璃平台围观副本生成（室外副本适用）
    val spectatorBuild: Boolean = false,

    // 准入条件：完成列表中任意一个任务即可进入（空列表 = 无限制）
    val requiredQuests: List<String> = emptyList(),

    // 主 schematic 粘贴前，先粘贴的附加 schematic（在 tick 启动前完成，零卡顿）
    val prePasteSchematics: List<PrePasteSchematic> = emptyList(),

    // 副本内死亡复活费用（铜钱，0 = 不允许复活）
    val reviveCost: Double = 0.0
)
