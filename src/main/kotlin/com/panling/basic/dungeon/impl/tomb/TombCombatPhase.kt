package com.panling.basic.dungeon.impl.tomb

import com.panling.basic.PanlingBasic
import com.panling.basic.dungeon.DungeonInstance
import com.panling.basic.dungeon.phase.AbstractCombatPhase
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.EntityType

class TombCombatPhase(
    plugin: PanlingBasic,
    instance: DungeonInstance
) : AbstractCombatPhase(plugin, instance) {

    // 这里演示多波次逻辑
    private var wave = 1

    override fun start() {
        instance.broadcast("§c[警告] 古墓守卫苏醒了！准备战斗！")
        instance.broadcastSound(Sound.ENTITY_ZOMBIE_AMBIENT)
        startWave(1)
    }

    private fun startWave(currentWave: Int) {
        instance.broadcast("§e>>> 第 $currentWave 波敌人出现 <<<")

        // 获取副本中心点 (相对于 schematic 原点偏移)
        // 假设我们在出生点附近刷怪
        val center = instance.template.spawnOffset.toLocation(instance.world)

        // 刷怪逻辑 (这里用原版怪演示，你可以换成 mobManager.spawnMob)
        // 第一波：3只僵尸
        if (currentWave == 1) {
            for (i in 1..3) {
                // 在中心点周围随机偏移一点
                val spawnLoc = center.clone().add(Math.random() * 4 - 2, 0.0, Math.random() * 4 - 2)
                val zombie = instance.world.spawnEntity(spawnLoc, EntityType.ZOMBIE)
                // [重点] 必须调用父类的 spawnMob 进行追踪，否则无法判断何时打完
                spawnMob(zombie as org.bukkit.entity.LivingEntity)
            }
        }
        // 第二波：1只铁傀儡 (BOSS)
        else if (currentWave == 2) {
            val boss = instance.world.spawnEntity(center, EntityType.IRON_GOLEM)
            boss.customName = "§c§l古墓守卫长"
            boss.isCustomNameVisible = true
            spawnMob(boss as org.bukkit.entity.LivingEntity)
        }
    }

    override fun onTick() {
        super.onTick() // 别忘了调用父类 tick (用于清理无效实体引用)

        // [演示] 每 5 tick 在玩家脚下播放一点恐怖特效
        if (instance.tickCount % 5 == 0L) {
            instance.players.forEach { uuid ->
                val p = org.bukkit.Bukkit.getPlayer(uuid)
                if (p != null) {
                    p.world.spawnParticle(Particle.ASH, p.location, 2, 0.5, 0.5, 0.5, 0.0)
                }
            }
        }
    }

    override fun onWaveClear() {
        if (wave == 1) {
            // 第一波打完了，休息 3 秒进第二波
            instance.broadcast("§a首战告捷！3秒后最终BOSS降临...")

            // 简单的延时逻辑可以使用 Bukkit Scheduler，也可以利用 Phase 自身状态
            // 这里简单演示：直接进下一波
            wave++
            startWave(wave)
        } else {
            // 全部打完，进入奖励阶段
            instance.broadcast("§6§l胜利！§a守卫已清除，前往领取奖励吧！")

            // [核心] 切换到奖励阶段
            instance.nextPhase(TombRewardPhase(plugin, instance))
        }
    }
}