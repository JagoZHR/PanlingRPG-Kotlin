package com.panling.basic.skill.impl.passive

import com.panling.basic.PanlingBasic
import com.panling.basic.api.ArrayStance
import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerClass
import com.panling.basic.api.SkillContext
import com.panling.basic.manager.PlayerDataManager
import com.panling.basic.skill.AbstractSkill
import com.panling.basic.util.CombatUtil
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.min
import kotlin.random.Random

class MetalMatrixPassive(plugin: PanlingBasic) : AbstractSkill("METAL_MATRIX", "金煞", PlayerClass.NONE), Listener {
    private val pl = plugin
    init { Bukkit.getPluginManager().registerEvents(this, plugin) }
    override fun onCast(ctx: SkillContext): Boolean = false

    @EventHandler
    fun onAttack(event: EntityDamageByEntityEvent) {
        val attacker = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player ?: return
            else -> return
        }
        if (!CombatUtil.shouldTriggerAttackPassives(attacker, event)) return
        val target = event.entity as? LivingEntity ?: return
        val passives = pl.playerDataManager.getCachedPassives(attacker, PlayerDataManager.PassiveTrigger.ATTACK)
        if (passives.find { it.id == id } == null) return
        if (pl.playerDataManager.getPlayerClass(attacker) != PlayerClass.MAGE) return

        // 防止金煞自身伤害触发递归
        if (attacker.hasMetadata("pl_metal_matrix_active")) return

        val cdKey = NamespacedKey(pl, "set_cd_metal_matrix")
        val now = System.currentTimeMillis()
        val last = attacker.persistentDataContainer.get(cdKey, PersistentDataType.LONG) ?: 0L
        if (now - last < 4000) return
        attacker.persistentDataContainer.set(cdKey, PersistentDataType.LONG, now)

        val center = target.location.clone()
        val skillVal = pl.statCalculator.getPlayerTotalStat(attacker, BasicKeys.ATTR_PHYSICAL_DAMAGE)
        val isSupport = pl.playerDataManager.getArrayStance(attacker) == ArrayStance.SUPPORT

        // 标记 AOE 进行中
        attacker.setMetadata("pl_metal_matrix_active", FixedMetadataValue(pl, true))

        if (isSupport) {
            attacker.sendMessage("§a[金煞·生息] §7金气化为生机！")
            attacker.world.playSound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.0f)
            object : BukkitRunnable() {
                var ticks = 0
                override fun run() {
                    if (ticks >= 60) { attacker.removeMetadata("pl_metal_matrix_active", pl); cancel(); return }
                    for (i in 0 until 3) {
                        val a = Random.nextDouble() * Math.PI * 2
                        val r = Random.nextDouble() * 3.0
                        center.world.spawnParticle(Particle.HAPPY_VILLAGER, center.x + Math.cos(a) * r, center.y + 0.5, center.z + Math.sin(a) * r, 1)
                    }
                    if (ticks % 20 == 0) {
                        for (e in center.world.getNearbyEntities(center, 3.0, 3.0, 3.0)) {
                            if (e is Player && e != attacker) {
                                val maxHp = e.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
                                val heal = min(skillVal * 0.02, maxHp - e.health)
                                if (heal > 0) e.health += heal
                            }
                        }
                    }
                    ticks++
                }
            }.runTaskTimer(pl, 0L, 1L)
        } else {
            attacker.world.playSound(center, Sound.BLOCK_ANVIL_LAND, 0.3f, 2.0f)
            object : BukkitRunnable() {
                var ticks = 0
                override fun run() {
                    if (ticks >= 60) { attacker.removeMetadata("pl_metal_matrix_active", pl); cancel(); return }
                    for (i in 0 until 5) {
                        val a = Random.nextDouble() * Math.PI * 2
                        val r = Random.nextDouble() * 3.0
                        center.world.spawnParticle(Particle.WITCH, center.x + Math.cos(a) * r, center.y + 0.3, center.z + Math.sin(a) * r, 1)
                    }
                    if (ticks % 20 == 0) {
                        for (e in center.world.getNearbyEntities(center, 3.0, 3.0, 3.0)) {
                            if (e is LivingEntity && e != attacker && e !is Player) {
                                e.damage(skillVal * 0.2, attacker)
                            }
                        }
                    }
                    ticks++
                }
            }.runTaskTimer(pl, 0L, 1L)
        }
    }
}
