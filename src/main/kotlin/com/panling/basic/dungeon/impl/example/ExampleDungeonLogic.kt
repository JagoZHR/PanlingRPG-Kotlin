package com.panling.basic.dungeon.impl.example

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.DungeonLogicProvider
import com.panling.basic.dungeon.phase.AbstractCombatPhase
import com.panling.basic.dungeon.phase.AbstractDungeonPhase
import com.panling.basic.dungeon.phase.AbstractRewardPhase
import com.panling.basic.dungeon.phase.WaitingPhase
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class ExampleDungeonLogic(private val plugin: PanlingBasic) : DungeonLogicProvider {

    override val templateId: String = "example_fuben"

    override fun createInitialPhase(instance: DungeonInstance): AbstractDungeonPhase {
        return WaitPhase(instance)
    }

    // --- 阶段定义 ---

    inner class WaitPhase(instance: DungeonInstance) : WaitingPhase(plugin, instance, 10) {
        // [修正] 方法名改为 start()
        override fun start() {
            // [关键] 必须调用 super.start()，否则 WaitingPhase 里的 timer 不会被赋值，导致无限等待或立即结束
            super.start()
            instance.broadcast("§e[副本] 10秒后开始战斗...")
        }

        override fun onTimeout() {
            instance.nextPhase(FightPhase(instance))
        }
    }

    inner class FightPhase(instance: DungeonInstance) : AbstractCombatPhase(plugin, instance) {
        override fun start() {
            instance.broadcast("§c[副本] 战斗开始！")
            val center = instance.centerLocation.clone().add(0.0, 2.0, 0.0)
            spawnMob(instance.world.spawnEntity(center, EntityType.ZOMBIE) as? org.bukkit.entity.LivingEntity)
            spawnMob(instance.world.spawnEntity(center.add(1.0, 0.0, 0.0), EntityType.ZOMBIE) as? org.bukkit.entity.LivingEntity)
        }

        override fun onWaveClear() {
            instance.broadcast("§a[副本] 怪物已清除！")
            instance.nextPhase(MyRewardPhase(instance))
        }
    }

    inner class MyRewardPhase(instance: DungeonInstance) : AbstractRewardPhase(plugin, instance) {
        override fun spawnChest() {
            val loc = instance.centerLocation.clone().add(0.0, 1.0, 0.0)
            loc.block.type = Material.CHEST
            instance.broadcast("§6[奖励] 宝箱已生成在中心！")
        }

        override fun giveReward(player: Player) {
            player.inventory.addItem(ItemStack(Material.DIAMOND, 1))
            player.sendMessage("§6获得奖励：钻石 x1")

            // 只要有人领奖就胜利
            instance.winDungeon()
        }
    }
}