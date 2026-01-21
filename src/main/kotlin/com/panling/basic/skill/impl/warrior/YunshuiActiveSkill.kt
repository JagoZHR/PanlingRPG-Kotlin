package com.panling.basic.skill.impl.warrior

import com.panling.basic.PanlingBasic
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.BuffType
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.skill.strategy.WarriorSkillStrategy
import com.panling.basic.util.CombatUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

class YunshuiActiveSkill(private val plugin: PanlingBasic) :
    AbstractSkill("YUNSHUI2", "吞天沃日", PlayerClass.WARRIOR), WarriorSkillStrategy {

    override var castTime: Double = 0.0

    companion object {
        private const val META_CHANNELING = "pl_yunshui_channeling"
        const val META_DAMAGE_HOOK = "pl_skill_damage_hook"

        private const val DAMAGE_PER_STACK = 50.0
        private const val MAX_STACKS = 50
        private const val BASE_MULTIPLIER = 5.0
        private const val STACK_MULTIPLIER = 0.2
    }

    override fun cast(ctx: SkillContext): Boolean {
        startChanneling(ctx.player)
        return true
    }

    override fun onCast(ctx: SkillContext): Boolean {
        return cast(ctx)
    }

    private fun startChanneling(p: Player) {
        val data = ChannelingData()
        p.setMetadata(META_CHANNELING, FixedMetadataValue(plugin, data))

        // 挂载受击钩子
        p.setMetadata(META_DAMAGE_HOOK, FixedMetadataValue(plugin, this.id))

        plugin.buffManager.addBuff(p, BuffType.KB_RESIST, 100L, 1.0, false)

        object : BukkitRunnable() {
            var tick = 0
            val maxTicks = 100
            val center = p.location // 捕获初始位置

            override fun run() {
                if (!p.isOnline || p.isDead || !p.hasMetadata(META_CHANNELING)) {
                    cleanup(p)
                    this.cancel()
                    return
                }

                p.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 10, 255, false, false))
                p.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, 10, 128, false, false))
                p.velocity = Vector(0.0, -0.1, 0.0)

                drawTornado(center, tick)

                if (tick % 20 == 0) {
                    p.world.playSound(center, Sound.WEATHER_RAIN, 0.2f, 0.5f)
                    p.world.playSound(center, Sound.BLOCK_WATER_AMBIENT, 0.3f, 0.5f)
                }

                // [优化] 如果是最后一秒(>= maxTicks)，就不要再拉人了，把舞台留给终结技
                // 这样能避免同一 tick 内的伤害堆叠
                if (tick % 5 == 0 && tick < maxTicks) {
                    pullAndDamage(p, center)
                }

                if (tick >= maxTicks) {
                    triggerFinisher(p, data)
                    cleanup(p)
                    this.cancel()
                }

                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun pullAndDamage(attacker: Player, center: Location) {
        val radius = 8.0

        // [Debug] 计算单次伤害
        val phys = plugin.statCalculator.getPlayerTotalStat(attacker, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        val tickDmg = phys * 0.2

        // 原代码使用了 targetCount 变量进行统计但未实际使用(除非开启Debug)，这里保留结构但允许编译器优化
        // var targetCount = 0

        center.world.getNearbyEntities(center, radius, radius, radius).forEach { e ->
            if (e is LivingEntity && CombatUtil.isValidTarget(attacker, e)) {
                // targetCount++

                val kbAttr = e.getAttribute(Attribute.KNOCKBACK_RESISTANCE)
                val originalBase = kbAttr?.baseValue ?: 0.0

                // 临时修改击退抗性
                kbAttr?.baseValue = 1.0

                try {
                    // 使用 dealPhysicalSkillDamage (false = 禁止触发被动)
                    CombatUtil.dealPhysicalSkillDamage(attacker, e, tickDmg, false)
                } finally {
                    // 恢复击退抗性
                    kbAttr?.baseValue = originalBase
                }

                val toCenter = center.toVector().subtract(e.location.toVector())
                val dist = toCenter.length()

                if (dist > 1.5) {
                    e.velocity = toCenter.normalize().multiply(0.6).setY(0.1)
                } else {
                    e.velocity = Vector(0.0, -0.1, 0.0)
                }
            }
        }
    }

    private fun triggerFinisher(p: Player, data: ChannelingData) {
        val def = plugin.statCalculator.getPlayerTotalStat(p, BasicKeys.ATTR_DEFENSE)
        val mDef = plugin.statCalculator.getPlayerTotalStat(p, BasicKeys.ATTR_MAGIC_DEFENSE)
        val baseDmg = def + mDef

        val multiplier = BASE_MULTIPLIER * (1.0 + (data.stacks * STACK_MULTIPLIER))
        val finalDamage = baseDmg * multiplier

        val loc = p.location

        loc.world.spawnParticle(Particle.CLOUD, loc, 100, 5.0, 1.0, 5.0, 0.2)
        loc.world.spawnParticle(Particle.DRIPPING_WATER, loc, 300, 4.0, 2.0, 4.0, 0.5)
        loc.world.spawnParticle(Particle.BUBBLE_POP, loc, 50, 4.0, 2.0, 4.0, 0.1)
        loc.world.playSound(loc, Sound.ENTITY_GENERIC_SPLASH, 2.0f, 0.5f)
        loc.world.playSound(loc, Sound.ITEM_TRIDENT_THUNDER, 1.0f, 1.2f)

        var hitCount = 0
        loc.world.getNearbyEntities(loc, 10.0, 5.0, 10.0).forEach { e ->
            if (e is LivingEntity && CombatUtil.isValidTarget(p, e)) {
                hitCount++
                // 使用 dealPhysicalSkillDamage (true = 允许触发吸血)
                CombatUtil.dealPhysicalSkillDamage(p, e, finalDamage, true)

                val away = e.location.toVector().subtract(loc.toVector()).normalize()
                e.velocity = away.multiply(1.5).setY(0.8)
            }
        }

        p.sendActionBar(
            Component.text("§b[吞天] 爆发! 积攒层数: ${data.stacks} | 伤害倍率: %.1fx".format(multiplier))
                .color(NamedTextColor.AQUA)
        )
    }

    override fun onDamaged(event: EntityDamageByEntityEvent, ctx: SkillContext) {
        val p = event.entity as Player
        if (!p.hasMetadata(META_CHANNELING)) {
            return
        }

        // 获取 Metadata 并强转
        val metaList = p.getMetadata(META_CHANNELING)
        if (metaList.isEmpty()) return // 安全检查
        val data = metaList[0].value() as ChannelingData

        val originalDmg = event.damage
        event.damage = originalDmg * 0.2

        data.addStack(1)
        val dmgStacks = (originalDmg / DAMAGE_PER_STACK).toInt()
        if (dmgStacks > 0) data.addStack(dmgStacks)

        p.world.playSound(p.location, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.5f, 2.0f)
        p.sendActionBar(Component.text("§e[霸体] 吸收伤害! 当前层数: ${data.stacks}").color(NamedTextColor.YELLOW))
    }

    private fun cleanup(p: Player) {
        p.removeMetadata(META_CHANNELING, plugin)

        if (p.hasMetadata(META_DAMAGE_HOOK)) {
            p.removeMetadata(META_DAMAGE_HOOK, plugin)
        }

        p.removePotionEffect(PotionEffectType.SLOWNESS)
        p.removePotionEffect(PotionEffectType.JUMP_BOOST)
        plugin.buffManager.removeBuff(p, BuffType.KB_RESIST)
    }

    private fun drawTornado(center: Location, tick: Int) {
        val radius = 3.0 + sin(tick * 0.1)
        val y = (tick % 40) * 0.2
        val angle = tick * 0.3
        val x = cos(angle) * radius
        val z = sin(angle) * radius

        center.world.spawnParticle(Particle.SWEEP_ATTACK, center.clone().add(x, y, z), 0)
        center.world.spawnParticle(Particle.FALLING_WATER, center.clone().add(-x, y + 2.0, -z), 1)

        if (tick % 5 == 0) {
            center.world.spawnParticle(Particle.DRIPPING_WATER, center.clone().add(x, 0.0, z), 3, 0.2, 0.2, 0.2, 0.0)
        }
    }

    // 内部类可以是 private 的，不需要 static
    private class ChannelingData {
        var stacks = 0
            private set // Set 设为 private，强制使用 addStack

        fun addStack(amount: Int) {
            stacks += amount
            if (stacks > MAX_STACKS) stacks = MAX_STACKS
        }
    }
}