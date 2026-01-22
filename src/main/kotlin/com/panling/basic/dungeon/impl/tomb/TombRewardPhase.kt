package com.panling.basic.dungeon.impl.tomb

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.phase.AbstractRewardPhase
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class TombRewardPhase(
    plugin: PanlingBasic,
    instance: DungeonInstance
) : AbstractRewardPhase(plugin, instance) {

    override fun spawnChest() {
        // 在副本出生点前方 5 格生成一个箱子
        val chestLoc = instance.template.spawnOffset.toLocation(instance.world).add(0.0, 0.0, 5.0)

        // 放置方块
        chestLoc.block.type = Material.ENDER_CHEST

        // 特效
        instance.world.spawnParticle(Particle.TOTEM_OF_UNDYING, chestLoc.add(0.5, 1.0, 0.5), 50, 0.5, 0.5, 0.5, 0.5)
        instance.broadcastSound(Sound.BLOCK_ENDER_CHEST_OPEN)
    }

    override fun giveReward(player: Player) {
        // [逻辑] 给玩家发奖励
        // 1. 给钱
        plugin.economyManager.giveMoney(player, 100.0)

        // 2. 给物品 (示例)
        val diamond = ItemStack(Material.DIAMOND, 2)
        player.inventory.addItem(diamond)

        player.sendMessage("§e[奖励] §f获得: §6100铜钱 §f和 §b2颗钻石")
    }
}