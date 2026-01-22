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
 * * 自动处理：箱子交互、防重复领取、领奖后自动结算
 */
abstract class AbstractRewardPhase(
    plugin: PanlingBasic,
    instance: DungeonInstance
) : AbstractDungeonPhase(plugin, instance) {

    // 记录已领奖玩家
    private val lootedPlayers = HashSet<UUID>()

    override fun start() {
        instance.broadcast("§6[奖励] 战斗结束！宝箱已出现。")
        spawnChest()
    }

    /**
     * [必须实现] 在场景中生成宝箱 (或者特效)
     */
    abstract fun spawnChest()

    /**
     * [必须实现] 给玩家发奖
     */
    abstract fun giveReward(player: Player)

    override fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return

        // 简单判定：只要点击的是箱子或者末影箱，就视为开箱
        // 子类如果需要更精确的判定（比如坐标判定），可以在这里加逻辑，或者重写此方法
        if (block.type == Material.CHEST || block.type == Material.ENDER_CHEST || block.type == Material.TRAPPED_CHEST) {

            event.isCancelled = true // 阻止原版箱子界面打开
            val player = event.player

            if (lootedPlayers.contains(player.uniqueId)) {
                player.sendMessage("§c你已经领取过奖励了！")
                return
            }

            // 发奖
            giveReward(player)
            lootedPlayers.add(player.uniqueId)

            player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 1f, 1f)
            player.sendMessage("§a领取成功！副本将在 10秒 后关闭。")

            // 检查：如果所有人都领完了，或者有人触发了，就开始倒计时结束副本
            // 这里简单处理：只要有人领奖，就触发副本胜利结算 (DungeonInstance 会负责倒计时踢人)
            // 如果你想让每个人都必须领，可以判断 lootedPlayers.size == instance.players.size
            instance.winDungeon()
        }
    }
}