package com.panling.basic.mob.skill.impl

import com.panling.basic.PanlingBasic
import com.panling.basic.mob.skill.MobSkill
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class ThrowSkill(config: ConfigurationSection) : MobSkill {
    private val itemType: Material = try {
        Material.valueOf(config.getString("item", "SNOWBALL")!!.uppercase())
    } catch (e: Exception) { Material.SNOWBALL }
    private val damage: Double = config.getDouble("damage", 10.0)
    private val slowTicks: Int = config.getInt("slow_ticks", 0)
    private val message: String? = config.getString("message")
    private val plugin = PanlingBasic.instance

    override fun cast(caster: LivingEntity, target: LivingEntity?): Boolean {
        if (target == null) return false
        val world = caster.world ?: return false

        val proj = caster.launchProjectile(Snowball::class.java).apply {
            item = ItemStack(itemType)
            velocity = target.location.toVector().subtract(caster.location.toVector())
                .normalize().multiply(3.0).setY(0.5)
        }

        // 每 5 ticks 检查命中，持续 2 秒
        var checks = 0
        val maxChecks = 8
        val task = object : Runnable {
            override fun run() {
                if (!proj.isValid || proj.isDead) return
                checks++
                val nearby = world.getNearbyEntities(proj.location, 2.0, 2.0, 2.0)
                    .filterIsInstance<LivingEntity>()
                    .filter { it != caster && !it.isDead }
                if (nearby.isNotEmpty()) {
                    val victim = nearby.first()
                    victim.damage(damage, caster)
                    if (slowTicks > 0) {
                        victim.addPotionEffect(
                            PotionEffect(PotionEffectType.SLOWNESS, slowTicks, 2, false, false, true)
                        )
                    }
                    proj.remove()
                    if (message != null) broadcast(caster, message)
                    return
                }
                if (checks < maxChecks) {
                    Bukkit.getScheduler().runTaskLater(plugin, this, 5L)
                } else {
                    proj.remove()
                }
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, 5L)

        world.playSound(caster.location, Sound.ENTITY_SNOWBALL_THROW, 1f, 1.5f)
        return true
    }
}
