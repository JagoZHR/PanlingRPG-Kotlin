package com.panling.basic.skill.impl.archer

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.ArcherSkillStrategy
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable

class RangerT3Skill(private val plugin: PanlingBasic) :
    AbstractSkill("ARCHER_RANGER_T3", "预热", PlayerClass.ARCHER), ArcherSkillStrategy {

    // [修复冲突] 显式重写冲突的方法
    override fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {
        // 你可以选择调用接口的默认实现，或者是父类的实现。
        // 由于你的 ArcherSkillStrategy 中该方法是空的，AbstractSkill 中大概率也是空的，
        // 这里调用哪一个都可以，或者干脆留空。
        // 标准写法是指定调用接口的默认实现：
        //super<ArcherSkillStrategy>.onProjectileHit(event, ctx)
    }

    companion object {
        // 这个 Key 必须和 SubClassManager 里 RANGER 策略读取的 Key 保持一致
        const val META_RANGER_T3_ACTIVE = "pl_ranger_t3_active"
        private const val META_RANGER_T3_TASK = "pl_ranger_t3_task"
    }

    override fun onCast(ctx: SkillContext): Boolean {
        // 1. 触发检查
        val p = ctx.player

        // 2. 刷新机制：如果已经开启，取消旧任务重新计时
        if (p.hasMetadata(META_RANGER_T3_TASK)) {
            try {
                val oldTaskId = p.getMetadata(META_RANGER_T3_TASK)[0].asInt()
                plugin.server.scheduler.cancelTask(oldTaskId)
            } catch (ignored: Exception) {}
        }

        // 3. 施加状态标记
        // Manager 的 RANGER 策略会读取这个标记，并在 getRangerEnchantBonus 中返回 +1
        p.setMetadata(META_RANGER_T3_ACTIVE, FixedMetadataValue(plugin, true))

        // 4. [关键] 强制刷新附魔
        // 立即调用 Manager 更新背包，让附魔立刻加上
        plugin.subClassManager?.updatePlayerStatus(p)

        // 5. 反馈特效
        p.world.playSound(p.location, Sound.ITEM_CROSSBOW_QUICK_CHARGE_3, 1.0f, 1.5f)
        p.world.spawnParticle(Particle.HAPPY_VILLAGER, p.location.add(0.0, 1.0, 0.0), 10, 0.5, 0.5, 0.5, 0.0)
        p.sendActionBar(Component.text("§a[速射] 快速装填等级 +1 (10秒)").color(NamedTextColor.GREEN))

        // 6. 启动定时移除任务 (10秒)
        val task = object : BukkitRunnable() {
            override fun run() {
                if (!p.isOnline) return

                // 移除状态
                p.removeMetadata(META_RANGER_T3_ACTIVE, plugin)
                p.removeMetadata(META_RANGER_T3_TASK, plugin)

                // [关键] 强制刷新附魔 (移除加成)
                // 立即调用 Manager，Manager 发现没有标记了，就会把 +1 撤销
                plugin.subClassManager?.updatePlayerStatus(p)

                p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f)
                p.sendActionBar(Component.text("§7[速射] 效果结束").color(NamedTextColor.GRAY))
            }
        }
        task.runTaskLater(plugin, 200L) // 200 ticks = 10秒

        // 记录 Task ID
        p.setMetadata(META_RANGER_T3_TASK, FixedMetadataValue(plugin, task.taskId))

        return true
    }
}