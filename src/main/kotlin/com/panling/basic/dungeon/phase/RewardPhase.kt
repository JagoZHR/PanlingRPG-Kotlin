package com.panling.basic.dungeon.phase

import com.panling.basic.api.BasicKeys
import com.panling.basic.dungeon.DungeonInstance
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import kotlin.random.Random

class RewardPhase : AbstractDungeonPhase("REWARD") {

    private var chestLocStr: String? = null
    private var rewards: List<String> = emptyList()

    // [NEW] 记录已领取奖励的玩家UUID
    private val lootedPlayers = HashSet<UUID>()
    private var realChestLoc: Location? = null // 缓存真实的箱子坐标

    override fun load(config: ConfigurationSection) {
        this.chestLocStr = config.getString("chest_loc")
        this.rewards = config.getStringList("rewards")
    }

    override fun onStart(instance: DungeonInstance) {
        instance.broadcast("§6[奖励] 宝箱已生成！请点击开启。")
        this.lootedPlayers.clear()

        chestLocStr?.let { str ->
            try {
                val parts = str.split(",")
                val x = parts[0].trim().toDouble()
                val y = parts[1].trim().toDouble()
                val z = parts[2].trim().toDouble()

                // instance.world 是非空的，直接使用
                val loc = Location(instance.world, x, y, z)

                // 确保区块加载
                if (!loc.chunk.isLoaded) {
                    loc.chunk.load()
                }

                this.realChestLoc = loc
                val block = loc.block
                block.type = Material.CHEST

                // 特效与音效
                instance.world.spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0.5, 1.0, 0.5), 15)
                instance.world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f)

            } catch (e: Exception) {
                instance.plugin.logger.warning("RewardPhase 解析箱子坐标失败: $str")
                e.printStackTrace()
            }
        }
    }

    override fun onInteract(instance: DungeonInstance, event: PlayerInteractEvent): Boolean {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return false

        val clicked = event.clickedBlock ?: return false
        // 检查位置是否匹配 (使用 Kotlin 的安全调用和 equals)
        if (realChestLoc == null || clicked.location != realChestLoc) return false

        val player = event.player

        // [核心修复] 检查个人领取状态
        if (lootedPlayers.contains(player.uniqueId)) {
            player.sendMessage("§c[提示] 你已经领取过奖励了！")
            return true
        }

        // 检查背包空间
        if (player.inventory.firstEmpty() == -1) {
            player.sendMessage("§c[提示] 背包已满！请至少清理出 1 格空间。")
            return true
        }

        // 发放奖励
        giveRewards(instance, player)

        // 标记为已领取
        lootedPlayers.add(player.uniqueId)

        // [核心修复] 检查是否所有人都领完了
        // 逻辑：如果 (已领人数 >= 当前副本内总人数) -> 触发结算
        if (lootedPlayers.size >= instance.players.size) {
            instance.broadcast("§a所有成员已领取奖励，副本即将关闭。")

            // 移除箱子
            clicked.type = Material.AIR

            // 触发通关流程
            instance.finishDungeon()
        }

        return true
    }

    private fun giveRewards(instance: DungeonInstance, player: Player) {
        // 音效与特效
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 1f, 0.8f)
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.5f)

        realChestLoc?.let { loc ->
            instance.world.spawnParticle(Particle.FIREWORK, loc.clone().add(0.5, 0.5, 0.5), 30, 0.5, 0.5, 0.5, 0.1)
        }

        for (rewardStr in rewards) {
            try {
                val parts = rewardStr.split(":")
                val itemId = parts[0]
                val amount = if (parts.size > 1) parts[1].toInt() else 1
                val chance = if (parts.size > 2) parts[2].toDouble() else 1.0

                if (Random.nextDouble() <= chance) {
                    // [对接 ItemManager]
                    // 假设 plugin.itemManager 存在
                    val item = instance.plugin.itemManager.createItem(itemId, player)

                    if (item != null) {
                        item.amount = amount
                        player.inventory.addItem(item)

                        val itemName = if (item.hasItemMeta() && item.itemMeta!!.hasDisplayName()) {
                            item.itemMeta!!.displayName
                        } else {
                            itemId
                        }

                        player.sendMessage("§f获得: $itemName §7x$amount")

                        if (isRare(item)) {
                            instance.plugin.server.broadcastMessage("§e[传闻] 玩家 ${player.name} 在副本中获得了稀世珍宝: $itemName")
                        }
                    } else {
                        instance.plugin.logger.warning("RewardPhase 奖励物品不存在: $itemId")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isRare(item: ItemStack): Boolean {
        if (!item.hasItemMeta()) return false

        val meta = item.itemMeta ?: return false
        val container = meta.persistentDataContainer

        // 读取稀有度权重
        val weight = container.get(BasicKeys.ITEM_RARITY_WEIGHT, PersistentDataType.INTEGER)
        return weight != null && weight >= 4 // 假设 4 是紫色史诗
    }

    override fun onTick(instance: DungeonInstance) {
        // 不需要 Tick
    }

    override fun onEnd(instance: DungeonInstance) {
        // 副本结束时，确保箱子消失
        realChestLoc?.block?.type = Material.AIR
    }
}