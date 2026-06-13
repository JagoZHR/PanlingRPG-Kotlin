package com.panling.basic.skill.strategy.wood

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
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable

class WoodAttackT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 0.5

    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val tier = MageUtil.getTierValue(p)

        // 发射带刺球
        val proj = p.launchProjectile(Snowball::class.java).apply {
            item = ItemStack(Material.CACTUS)
            velocity = p.location.direction.multiply(1.5)
        }

        proj.persistentDataContainer.apply {
            set(NamespacedKey(plugin, "skill_arrow_id"), PersistentDataType.STRING, "MAGE_WOOD")
            set(BasicKeys.SKILL_ELEMENT, PersistentDataType.STRING, ElementalManager.Element.WOOD.name)

            val mult = if (tier >= 4) 0.8 else 0.6
            set(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE, ctx.power * mult)
        }

        CombatUtil.bindStrategy(proj, this)

        p.playSound(p.location, Sound.ENTITY_SNOWBALL_THROW, 1f, 0.5f)

        // 预计算伤害和时长（不可变，lambda 可安全捕获）
        val damage = ctx.power * (if (tier >= 4) 0.8 else 0.6)
        val poisonDuration = if (tier >= 4) 140 else 100

        object : BukkitRunnable() {
            override fun run() {
                if (proj.isDead) {
                    this.cancel()
                    return
                }

                // 扩展命中判定
                val nearby = proj.world.getNearbyEntities(proj.location, 1.0, 1.0, 1.0)
                    .filterIsInstance<LivingEntity>()
                    .filter { it != p }
                    .filter { it !is Player || canHitPlayers }
                if (nearby.isNotEmpty()) {
                    val victim = nearby.first()

                    // [修复] 改为直接触发元素反应 + 技能伤害，不走 combat listener 射弹物路径
                    if (plugin.mobManager.canAttack(p, victim)) {
                        plugin.elementalManager.handleElementHit(p, victim, ElementalManager.Element.WOOD, damage)
                        MageUtil.dealSkillDamage(p, victim, damage, true)
                        // 施加中毒（原 onAttack 副作用）
                        victim.addPotionEffect(PotionEffect(PotionEffectType.POISON, poisonDuration, 1))
                    }

                    proj.remove()
                    this.cancel()
                    return
                }

                p.world.spawnParticle(
                    Particle.HAPPY_VILLAGER,
                    proj.location,
                    2, 0.1, 0.1, 0.1, 0.0
                )
            }
        }.runTaskTimer(plugin, 0L, 1L)
        return true
    }

    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        val victim = event.entity
        if (victim is LivingEntity) {
            if (victim is Player && !this.canHitPlayers) return

            val tier = MageUtil.getTierValue(ctx.player)
            val duration = if (tier >= 4) 140 else 100
            victim.addPotionEffect(PotionEffect(PotionEffectType.POISON, duration, 1))
        }
    }
}
