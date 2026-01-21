package com.panling.basic.skill.strategy.metal

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.util.CombatUtil
import com.panling.basic.util.MageUtil
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable

class MetalAttackT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.8

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val tier = MageUtil.getTierValue(p)

        val arrow = p.launchProjectile(Arrow::class.java).apply {
            shooter = p
            velocity = p.location.direction.multiply(4.0)
            isCritical = true
            pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
        }

        // 标记 (重要：要把父技能的 ID 传进去，或者这里写死 MAGE_METAL)
        // 注意：Listener 还是认 skill_arrow_id 来回调 MageMetalSkill
        arrow.persistentDataContainer.apply {
            set(NamespacedKey(plugin, "skill_arrow_id"), PersistentDataType.STRING, "MAGE_METAL")
            set(BasicKeys.SKILL_ELEMENT, PersistentDataType.STRING, ElementalManager.Element.METAL.name)

            val mult = if (tier >= 5) 2.4 else if (tier >= 4) 2.0 else 1.8
            set(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE, ctx.power * mult)
        }

        // [新增] 将策略规则(canHitPlayers=false) 绑定到投射物
        CombatUtil.bindStrategy(arrow, this)

        p.playSound(p.location, Sound.ENTITY_ARROW_SHOOT, 1f, 2f)

        object : BukkitRunnable() {
            override fun run() {
                // 如果 Listener 拦截了命中玩家，arrow会被移除，isDead为真，此处自动停止
                if (arrow.isDead || arrow.isOnGround) {
                    this.cancel()
                    return
                }
                p.world.spawnParticle(Particle.CLOUD, arrow.location, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }.runTaskTimer(plugin, 0L, 1L)
        return true
    }

    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        // [安全拦截] 再次确认不伤害玩家
        if (event.entity is Player && !this.canHitPlayers) return

        ctx.projectile?.let { projectile ->
            val tier = MageUtil.getTierValue(ctx.player)
            val pen = if (tier >= 5) 0.30 else if (tier >= 4) 0.25 else 0.20
            projectile.persistentDataContainer.set(BasicKeys.ATTR_EXTRA_PEN, PersistentDataType.DOUBLE, pen)
        }
        // 这里可以添加金系特有的流血或破甲效果，目前原文件为空，保持为空即可
    }
}