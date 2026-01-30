package com.panling.basic.dungeon.phase

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import java.util.UUID

/**
 * 奖励阶段基类
 */
abstract class AbstractRewardPhase(
    plugin: PanlingBasic,
    instance: DungeonInstance
) : AbstractDungeonPhase(plugin, instance) {

    // 记录已领奖玩家
    private val lootedPlayers = HashSet<UUID>()

    /**
     * [可重写配置] 该副本奖励最多可能占用的背包格数
     * 子类可以重写此值，例如：override val maxRewardSlots = 10
     */
    protected open val maxRewardSlots: Int = 5

    override fun start() {
        instance.broadcast("§6[奖励] 战斗结束！宝箱已出现。")
        spawnChest()
    }

    abstract fun spawnChest()
    abstract fun giveReward(player: Player)

    override fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return

        if (block.type == Material.CHEST || block.type == Material.ENDER_CHEST || block.type == Material.TRAPPED_CHEST) {

            event.isCancelled = true // 阻止原版界面
            val player = event.player

            if (lootedPlayers.contains(player.uniqueId)) {
                player.sendMessage("§c你已经领取过奖励了！")
                return
            }

            // =========================================================
            // [新增] 背包空间检查逻辑
            // =========================================================
            if (getFreeSlots(player) < maxRewardSlots) {
                player.sendMessage("§c[提示] 背包空间不足！")
                player.sendMessage("§7请至少预留 $maxRewardSlots 格空位来领取奖励。")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                return // 直接返回，不标记为已领取
            }
            // =========================================================

            // 发奖
            giveReward(player)
            lootedPlayers.add(player.uniqueId)

            player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 1f, 1f)
            player.sendMessage("§a领取成功！副本将在 10秒 后关闭。")

            instance.winDungeon()
        }
    }

    /**
     * 获取玩家背包剩余空位数 (不包含装备栏和副手)
     */
    private fun getFreeSlots(player: Player): Int {
        var free = 0
        // storageContents 只包含 0-35 格 (主体+快捷栏)
        for (item in player.inventory.storageContents) {
            if (item == null || item.type == Material.AIR) {
                free++
            }
        }
        return free
    }
}