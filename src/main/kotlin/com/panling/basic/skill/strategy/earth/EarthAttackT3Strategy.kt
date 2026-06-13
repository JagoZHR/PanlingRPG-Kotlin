package com.panling.basic.skill.strategy.earth

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
import org.bukkit.scheduler.BukkitRunnable

class EarthAttackT3Strategy(private val plugin: PanlingBasic) : MageSkillStrategy {

    override val castTime: Double = 1.0
    override val canHitPlayers: Boolean = false

    override fun cast(ctx: SkillContext): Boolean {
        val p = ctx.player
        val tier = MageUtil.getTierValue(p)

        val rock = p.launchProjectile(Snowball::class.java).apply {
            item = ItemStack(Material.COBBLESTONE)
            velocity = p.location.direction.multiply(1.2)
        }

        rock.persistentDataContainer.apply {
            set(NamespacedKey(plugin, "skill_arrow_id"), PersistentDataType.STRING, "MAGE_EARTH")
            set(BasicKeys.SKILL_ELEMENT, PersistentDataType.STRING, ElementalManager.Element.EARTH.name)
            set(BasicKeys.IS_PHYSICAL_SKILL, PersistentDataType.BYTE, 1.toByte())

            val mult = if (tier >= 4) 1.5 else 1.2
            set(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE, ctx.power * mult)
        }

        CombatUtil.bindStrategy(rock, this)

        p.playSound(p.location, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8f, 0.5f)

        // 预计算（不可变，lambda 可安全捕获）
        val damage = ctx.power * (if (tier >= 4) 1.5 else 1.2)

        object : BukkitRunnable() {
            override fun run() {
                if (rock.isDead) {
                    this.cancel()
                    return
                }

                val nearby = rock.world.getNearbyEntities(rock.location, 1.0, 1.0, 1.0)
                    .filterIsInstance<LivingEntity>()
                    .filter { it != p }
                    .filter { it !is Player || canHitPlayers }
                if (nearby.isNotEmpty()) {
                    val victim = nearby.first()

                    // [修复] 改为直接触发元素反应 + 技能伤害
                    if (plugin.mobManager.canAttack(p, victim)) {
                        plugin.elementalManager.handleElementHit(p, victim, ElementalManager.Element.EARTH, damage)
                        // 土系物理伤害
                        MageUtil.dealSkillDamage(p, victim, damage, false)
                        // 击退（原 onAttack 副作用）
                        val knock = victim.location.toVector().subtract(p.location.toVector()).normalize()
                        knock.setY(0.2).multiply(0.5)
                        victim.velocity = victim.velocity.add(knock)
                    }

                    rock.remove()
                    this.cancel()
                    return
                }

                p.world.spawnParticle(
                    Particle.BLOCK_CRUMBLE,
                    rock.location,
                    5, 0.2, 0.2, 0.2,
                    Material.DIRT.createBlockData()
                )
            }
        }.runTaskTimer(plugin, 0L, 1L)
        return true
    }

    override fun onAttack(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        if (event.entity is Player && !this.canHitPlayers) return

        val victim = event.entity as? LivingEntity ?: return

        val knock = victim.location.toVector().subtract(ctx.player.location.toVector()).normalize()
        knock.setY(0.2).multiply(0.5)
        victim.velocity = victim.velocity.add(knock)
    }
}
