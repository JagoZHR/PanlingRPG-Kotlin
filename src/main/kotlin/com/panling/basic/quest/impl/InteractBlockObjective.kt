package com.panling.basic.quest.impl

import com.panling.basic.quest.api.QuestObjective
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractEvent

class InteractBlockObjective(
    override val id: String,
    private val targetMaterial: Material,
    private val targetLocation: Location,
    override val description: String,
    override val navigationLocation: Location?
) : QuestObjective {

    override val requiredAmount: Int = 1

    override fun onEvent(player: Player, event: Event, currentProgress: Int): Int {
        if (event !is PlayerInteractEvent) return -1
        if (currentProgress >= requiredAmount) return -1

        val block = event.clickedBlock ?: return -1
        if (block.type != targetMaterial) return -1

        // 检查坐标是否匹配 (允许 ±1 格误差)
        if (block.x == targetLocation.blockX &&
            block.y == targetLocation.blockY &&
            block.z == targetLocation.blockZ) {
            return currentProgress + 1
        }

        return -1
    }
}
