package com.panling.basic.skill.impl.archer

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BuffType
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.ArcherSkillStrategy
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.persistence.PersistentDataType

class SniperT4Skill(private val plugin: PanlingBasic) :
    AbstractSkill("ARCHER_SNIPER_T4", "定影", PlayerClass.ARCHER), ArcherSkillStrategy {

    // 必须与 PlayerCombatListener 中的 key 保持一致
    private val skillArrowKey = NamespacedKey(plugin, "skill_arrow_id")

    override fun onCast(ctx: SkillContext): Boolean {
        // 1. 确保有投射物 (必须在 onShoot 中触发才有效)
        val projectile = ctx.projectile ?: return false

        // 2. 给箭矢打上标记
        // 这样当箭矢命中时，PlayerCombatListener 会识别出这是"狙击·定影"射出的箭，
        // 并自动回调下面的 onProjectileHit 方法。
        projectile.persistentDataContainer.set(skillArrowKey, PersistentDataType.STRING, this.id)

        // 3. 发射音效 (沉闷有力的声音)
        ctx.player.world.playSound(ctx.player.location, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.5f, 2.0f)

        return true
    }

    override fun onProjectileHit(event: ProjectileHitEvent, ctx: SkillContext) {
        // 1. 获取目标 (使用 safe cast)
        val target = event.hitEntity as? LivingEntity ?: return

        // 2. 阵营/PVE 检查
        if (!plugin.mobManager.canAttack(ctx.player, target)) return

        // 3. 施加定身 (Root) - 持续 1秒 (20 ticks)
        // 这里的 ROOT 效果由你的 BuffManager 提供
        plugin.buffManager.addBuff(target, BuffType.ROOT, 20)

        // 4. 命中反馈 (锁链/重击感)
        target.world.playSound(target.location, Sound.BLOCK_CHAIN_BREAK, 1.0f, 0.5f)
        target.world.playSound(target.location, Sound.BLOCK_ANVIL_LAND, 0.5f, 2.0f)

        // 脚下生成一点烟雾和暴击星，模拟"钉在地上"的感觉
        target.world.spawnParticle(Particle.CRIT, target.location, 10, 0.3, 0.1, 0.3, 0.1)
        target.world.spawnParticle(Particle.CLOUD, target.location, 5, 0.3, 0.1, 0.3, 0.05)

        // 可选：ActionBar 提示
        // ctx.player().sendActionBar(Component.text("§b[定影] 目标已定身！"))
    }
}