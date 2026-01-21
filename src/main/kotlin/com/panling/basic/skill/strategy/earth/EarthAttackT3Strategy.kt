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

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
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
            // 标记为物理伤害 (保留原逻辑)
            set(BasicKeys.IS_PHYSICAL_SKILL, PersistentDataType.BYTE, 1.toByte())

            val mult = if (tier >= 4) 1.5 else 1.2
            set(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE, ctx.power * mult)
        }

        // [新增] 将策略规则(canHitPlayers=false) 绑定到投射物
        CombatUtil.bindStrategy(rock, this)

        p.playSound(p.location, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8f, 0.5f)

        object : BukkitRunnable() {
            override fun run() {
                // 如果 Listener 拦截了命中玩家，rock会被移除，这里会自动停止
                if (rock.isDead) {
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
        // [安全拦截] 防止击退队友
        if (event.entity is Player && !this.canHitPlayers) return

        val victim = event.entity as? LivingEntity ?: return

        // 土系特色：击退
        val knock = victim.location.toVector().subtract(ctx.player.location.toVector()).normalize()
        knock.setY(0.2).multiply(0.5)
        victim.velocity = victim.velocity.add(knock)
    }
}