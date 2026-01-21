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

    // [新增] 显式声明：我是PVE技能，禁止攻击玩家
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
            // 这里的 ID 必须填父技能的 ID，以便 Listener 回调
            set(NamespacedKey(plugin, "skill_arrow_id"), PersistentDataType.STRING, "MAGE_WOOD")
            // [保留] 您的 ElementalManager 逻辑完整保留
            set(BasicKeys.SKILL_ELEMENT, PersistentDataType.STRING, ElementalManager.Element.WOOD.name)

            val mult = if (tier >= 4) 0.8 else 0.6
            set(BasicKeys.ARROW_DAMAGE_STORE, PersistentDataType.DOUBLE, ctx.power * mult)
        }

        // [新增] 将策略规则(canHitPlayers=false) 绑定到投射物
        // CombatUtil 只会打上禁止PVP的标签，不会覆盖上面的任何设置
        CombatUtil.bindStrategy(proj, this)

        p.playSound(p.location, Sound.ENTITY_SNOWBALL_THROW, 1f, 0.5f)

        object : BukkitRunnable() {
            override fun run() {
                // 如果命中玩家，Listener会移除实体，isDead()变真，这里就会自动停止，不再生成粒子
                if (proj.isDead) {
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
            // [可选安全拦截] 如果是非投射物伤害，这里也是一道防线
            if (victim is Player && !this.canHitPlayers) return

            val tier = MageUtil.getTierValue(ctx.player)
            val duration = if (tier >= 4) 140 else 100
            // 施加中毒
            victim.addPotionEffect(PotionEffect(PotionEffectType.POISON, duration, 1))
        }
    }
}