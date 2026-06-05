package com.panling.basic.dungeon.phase

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.RewardConfig
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player

/**
 * 标准奖励阶段
 * - autoReward=true: 无宝箱，进入阶段后自动发奖所有玩家，延迟关闭
 * - autoReward=false: 生成宝箱，玩家右键交互领奖（与 AbstractRewardPhase 行为一致）
 */
class StandardRewardPhase(
    plugin: PanlingBasic,
    instance: DungeonInstance,
    private val config: RewardConfig
) : AbstractRewardPhase(plugin, instance) {

    override val maxRewardSlots: Int = config.maxRewardSlots

    override fun start() {
        if (config.autoReward) {
            instance.broadcast(config.title)
            autoRewardAll()
        } else {
            super.start() // 走父类：广播 + spawnChest
        }
    }

    override fun spawnChest() {
        if (config.autoReward) return

        val loc = instance.centerLocation.clone().add(config.chestOffset)
        loc.block.type = config.chestMaterial
        instance.world.spawnParticle(
            Particle.TOTEM_OF_UNDYING,
            loc.clone().add(0.5, 1.0, 0.5),
            50, 0.5, 0.5, 0.5, 0.5
        )
        instance.broadcastSound(Sound.BLOCK_ENDER_CHEST_OPEN)
    }

    override fun giveReward(player: Player) {
        giveRewardInternal(player)
    }

    // ==========================================
    // 内部逻辑
    // ==========================================

    private fun autoRewardAll() {
        instance.players.forEach { uuid ->
            val player = Bukkit.getPlayer(uuid) ?: return@forEach
            giveRewardInternal(player)
            player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        }
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            instance.winDungeon()
        }, config.autoRewardDelay)
    }

    private fun giveRewardInternal(player: Player) {
        // 金钱
        if (config.money > 0) {
            plugin.economyManager.giveMoney(player, config.money)
            player.sendMessage("§e+${config.money} 铜钱")
        }

        // 物品
        for ((itemId, amount) in config.items) {
            repeat(amount) {
                val item = plugin.itemManager.createItem(itemId, player)
                if (item != null) {
                    player.inventory.addItem(item)
                }
            }
        }

        // 自定义回调（如解锁配方）
        config.onReward?.invoke(player)
    }
}
