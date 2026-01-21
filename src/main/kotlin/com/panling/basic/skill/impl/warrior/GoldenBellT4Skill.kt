package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.api.SkillTrigger
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.WarriorSkillStrategy
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue

class GoldenBellT4Skill(private val plugin: PanlingBasic) :
    AbstractSkill("WARRIOR_GOLDEN_BELL_T4", "挪移", PlayerClass.WARRIOR), WarriorSkillStrategy {

    override var castTime: Double = 0.0

    companion object {
        // 复用通用的反伤递归锁，防止 A反伤B -> B反伤A 的无限循环
        private const val META_THORNS_PROCESSING = "pl_thorns_processing"
    }

    override val canHitPlayers: Boolean = false

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    override fun cast(ctx: SkillContext): Boolean {
        // 1. 必须是受击触发
        if (ctx.triggerType != SkillTrigger.DAMAGED) return false

        // 2. 必须有攻击者 (在 SkillManager 中我们已经处理了 target = damager)
        val target = ctx.target ?: return false

        // 3. PVE 检查 (如果不打玩家)
        // 注意：作为反伤技能，通常是被动防御。如果 strict PVE，则不反伤玩家。
        if (target is Player && !this.canHitPlayers) return false

        // 4. [重要] 递归检查
        // 如果攻击者本身正在处理反伤 (比如他刚反伤了你)，则不再反伤回去
        if (target.hasMetadata(META_THORNS_PROCESSING)) return false

        return true
    }

    override fun onDamaged(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        val attacker = ctx.target ?: return // 攻击者
        val player = ctx.player // 缓存当前玩家对象

        // 1. 计算反伤数值 (受到伤害的 20%)
        val incomingDamage = event.finalDamage // 获取最终伤害(计算防御后)还是原始伤害？通常反震是基于受到的实际伤害
        // 如果想要基于"原始伤害"，用 event.damage
        val reflectDamage = incomingDamage * 0.2

        if (reflectDamage <= 0.1) return

        // 2. 执行反伤
        // [核心] 加上递归锁，标记"攻击者"正在处理反伤流程
        // (注：这里的逻辑是，我们即将对 attacker 造成伤害，这会触发 attacker 的受击事件。
        // 我们需要在 attacker 的受击事件中，让 Listener 知道这是反伤，不要再触发他的反伤)

        // 但根据 PlayerCombatListener 的逻辑: if (event.getDamager().hasMetadata("pl_thorns_processing")) return;
        // Listener 检查的是 "Damager" (即造成伤害的人)。
        // 在反伤事件中：Damager 是 ctx.player() (金钟玩家)，Entity 是 attacker (怪物)。
        // 所以我们应该标记 ctx.player()！

        player.setMetadata(META_THORNS_PROCESSING, FixedMetadataValue(plugin, true))
        try {
            attacker.damage(reflectDamage, player)
        } finally {
            player.removeMetadata(META_THORNS_PROCESSING, plugin)
        }

        // 3. 视觉反馈
        player.world.playSound(player.location, Sound.BLOCK_ANVIL_LAND, 0.5f, 2.0f)
        player.world.spawnParticle(Particle.CRIT, player.eyeLocation, 5, 0.5, 0.5, 0.5, 0.1)
        // 在攻击者身上冒出反伤特效
        attacker.world.spawnParticle(Particle.DAMAGE_INDICATOR, attacker.eyeLocation, 3, 0.2, 0.2, 0.2, 0.1)
    }
}