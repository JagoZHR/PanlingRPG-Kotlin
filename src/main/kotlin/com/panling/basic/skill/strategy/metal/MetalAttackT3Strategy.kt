package com.panling.basic.skill.strategy.metal

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.ElementalManager
import com.panling.basic.skill.strategy.MageSkillStrategy
import com.panling.basic.util.CombatUtil
import com.panling.basic.util.MageUtil
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable

class MetalAttackT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.8
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val tier = MageUtil.getTierValue(p)

        val proj = p.launchProjectile(Snowball::class.java).apply {
            item = ItemStack(Material.IRON_INGOT)
            velocity = p.location.direction.multiply(3.0)
        }

        proj.persistentDataContainer.apply {
            set(NamespacedKey(plugin, "skill_arrow_id"), PersistentDataType.STRING, "MAGE_METAL")
            set(BasicKeys.SKILL_ELEMENT, PersistentDataType.STRING, ElementalManager.Element.METAL.name)

            val mult = if (tier >= 5) 2.4 else if (tier >= 4) 2.0 else 1.8
            set(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE, ctx.power * mult)
        }

        CombatUtil.bindStrategy(proj, this)

        p.playSound(p.location, Sound.ENTITY_ARROW_SHOOT, 1f, 2f)

        // 预计算（不可变，lambda 可安全捕获）
        val damage = ctx.power * (if (tier >= 5) 2.4 else if (tier >= 4) 2.0 else 1.8)
        val extraPen = if (tier >= 5) 0.30 else if (tier >= 4) 0.25 else 0.20

        object : BukkitRunnable() {
            override fun run() {
                if (proj.isDead) {
                    this.cancel()
                    return
                }

                val nearby = proj.world.getNearbyEntities(proj.location, 1.0, 1.0, 1.0)
                    .filterIsInstance<LivingEntity>()
                    .filter { it != p }
                    .filter { it !is Player || canHitPlayers }
                if (nearby.isNotEmpty()) {
                    val victim = nearby.first()

                    // [修复] 改为直接触发元素反应 + 技能伤害
                    if (plugin.mobManager.canAttack(p, victim)) {
                        plugin.elementalManager.handleElementHit(p, victim, ElementalManager.Element.METAL, damage)
                        // 通过 metadata 传递额外穿透（CombatUtil.createSnapshot 会读取）
                        p.setMetadata("pl_extra_pen", FixedMetadataValue(plugin, extraPen))
                        try {
                            MageUtil.dealSkillDamage(p, victim, damage, true)
                        } finally {
                            p.removeMetadata("pl_extra_pen", plugin)
                        }
                    }

                    proj.remove()
                    this.cancel()
                    return
                }

                p.world.spawnParticle(Particle.CLOUD, proj.location, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }.runTaskTimer(plugin, 0L, 1L)
        return true
    }

    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        if (event.entity is Player && !this.canHitPlayers) return

        ctx.projectile?.let { projectile ->
            val tier = MageUtil.getTierValue(ctx.player)
            val pen = if (tier >= 5) 0.30 else if (tier >= 4) 0.25 else 0.20
            projectile.persistentDataContainer.set(BasicKeys.ATTR_EXTRA_PEN, PersistentDataType.DOUBLE, pen)
        }
    }
}
