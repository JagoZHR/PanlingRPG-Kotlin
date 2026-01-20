package com.panling.basic.dungeon

import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.util.Vector

data class DungeonTemplate(
    val id: String,
    val displayName: String,
    val schematicName: String, // 原 worldSource
    val minLevel: Int,
    val questReq: String?,
    val ticketItem: String?,
    val consumeTicket: Boolean,
    val minPlayers: Int,
    val maxPlayers: Int,
    val timeLimit: Int,
    val spawnOffset: Vector, // 相对于 Schematic 原点的偏移
    val exitLoc: Location?,
    val phaseConfig: ConfigurationSection?
)