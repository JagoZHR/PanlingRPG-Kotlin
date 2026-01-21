package com.panling.basic.manager

import com.panling.basic.api.BasicKeys
import com.panling.basic.api.PlayerSubClass
import com.panling.basic.subclass.SubClassStrategy
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import kotlin.math.max
import kotlin.math.min

class SubClassManager(
    private val plugin: JavaPlugin,
    private val dataManager: PlayerDataManager,
    private val statCalculator: StatCalculator
) {

    private val strategies = EnumMap<PlayerSubClass, SubClassStrategy>(PlayerSubClass::class.java)

    // Keys
    private val sniperKbIgnoreKey = NamespacedKey(plugin, "sniper_kb_ignore")
    private val rangerBonusChargeKey = NamespacedKey(plugin, "pl_ranger_bonus_charge")
    private val rangerBonusPierceKey = NamespacedKey(plugin, "pl_ranger_bonus_pierce")
    private val rangerAddedMultishotKey = NamespacedKey(plugin, "pl_ranger_added_multishot")

    // 通知状态缓存
    private val fullStackNotified = HashMap<UUID, Boolean>()

    companion object {
        private const val META_AGGRO_OWNER = "pl_aggro_owner"
        // 默认空策略
        private val NO_OP = object : SubClassStrategy {}
    }

    init {
        // [关键] 注入 StatCalculator
        // 假设 StatCalculator.kt 中定义了 var subClassManager: SubClassManager?
        statCalculator.subClassManager = this

        registerStrategies()
        startGlobalTask()
    }

    fun getStrategy(player: Player): SubClassStrategy {
        val sub = dataManager.getPlayerSubClass(player)
        return strategies[sub] ?: NO_OP
    }

    // ========================================================================
    // 1. 全局定时任务 (每秒执行)
    // ========================================================================
    private fun startGlobalTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                updatePlayerStatus(player)
            }
        }, 20L, 20L)
    }

    fun updatePlayerStatus(player: Player) {
        val currentSub = dataManager.getPlayerSubClass(player)
        val activeSlot = dataManager.getActiveSlot(player)
        val heldTime = dataManager.getSlotHoldDuration(player)

        if (heldTime < 1.0) {
            fullStackNotified.remove(player.uniqueId)
        }

        maintainRangerEnchants(player, currentSub, activeSlot, heldTime)

        if (heldTime >= 10.0 && !fullStackNotified.getOrDefault(player.uniqueId, false)) {
            sendFullStackNotification(player, currentSub)
            fullStackNotified[player.uniqueId] = true
        }
    }

    // ========================================================================
    // 附魔维护逻辑
    // ========================================================================
    private fun maintainRangerEnchants(player: Player, currentSub: PlayerSubClass, activeSlot: Int, heldTime: Double) {
        val contents = player.inventory.contents

        // 使用索引遍历，以便判断 activeSlot
        for (i in contents.indices) {
            val item = contents[i]
            if (item == null || item.type != Material.CROSSBOW || !item.hasItemMeta()) continue

            // 只有当是本插件物品时才处理
            val meta = item.itemMeta!! // hasItemMeta checked
            val pdc = meta.persistentDataContainer
            if (!pdc.has(BasicKeys.ITEM_ID, PersistentDataType.STRING)) continue

            // 1. 询问策略
            var bonus = intArrayOf(0, 0, 0)
            if (currentSub == PlayerSubClass.RANGER && i == activeSlot) {
                bonus = getStrategy(player).getRangerEnchantBonus(player, heldTime)
            }

            val bonusCharge = bonus[0]
            val bonusPierce = bonus[1]
            val shouldAddMultishot = (bonus[2] == 1)

            var changed = false

            // A. 快速装填
            // [修复] 使用 Kotlin 标准写法 pdc.get(...) ?: 0，移除自定义 getOrDefault 扩展函数
            val lastBonusCharge = pdc.get(rangerBonusChargeKey, PersistentDataType.INTEGER) ?: 0

            val currentLevelCharge = meta.getEnchantLevel(Enchantment.QUICK_CHARGE)
            val baseCharge = max(0, currentLevelCharge - lastBonusCharge)
            var newCharge = baseCharge + bonusCharge
            if (newCharge > 5) newCharge = 5

            if (currentLevelCharge != newCharge) {
                if (newCharge > 0) meta.addEnchant(Enchantment.QUICK_CHARGE, newCharge, true)
                else meta.removeEnchant(Enchantment.QUICK_CHARGE)
                changed = true
            }
            if (bonusCharge > 0) pdc.set(rangerBonusChargeKey, PersistentDataType.INTEGER, bonusCharge)
            else pdc.remove(rangerBonusChargeKey)

            // B. 穿透
            // [修复] 同上，使用 ?: 0
            val lastBonusPierce = pdc.get(rangerBonusPierceKey, PersistentDataType.INTEGER) ?: 0

            val currentLevelPierce = meta.getEnchantLevel(Enchantment.PIERCING)
            val basePierce = max(0, currentLevelPierce - lastBonusPierce)
            var newPierce = basePierce + bonusPierce
            if (newPierce > 5) newPierce = 5

            if (currentLevelPierce != newPierce) {
                if (newPierce > 0) meta.addEnchant(Enchantment.PIERCING, newPierce, true)
                else meta.removeEnchant(Enchantment.PIERCING)
                changed = true
            }
            if (bonusPierce > 0) pdc.set(rangerBonusPierceKey, PersistentDataType.INTEGER, bonusPierce)
            else pdc.remove(rangerBonusPierceKey)

            // C. 多重射击
            val addedPreviously = pdc.has(rangerAddedMultishotKey, PersistentDataType.BYTE)
            val hasMultishotNow = meta.hasEnchant(Enchantment.MULTISHOT)
            val baseHasMultishot = hasMultishotNow && !addedPreviously
            val finalMultishot = baseHasMultishot || shouldAddMultishot

            if (hasMultishotNow != finalMultishot) {
                if (finalMultishot) meta.addEnchant(Enchantment.MULTISHOT, 1, true)
                else meta.removeEnchant(Enchantment.MULTISHOT)
                changed = true
            }
            if (shouldAddMultishot && !baseHasMultishot) pdc.set(rangerAddedMultishotKey, PersistentDataType.BYTE, 1.toByte())
            else pdc.remove(rangerAddedMultishotKey)

            // D. 应用与提示
            if (changed) {
                item.itemMeta = meta

                if (bonusCharge > 0 && bonusCharge > lastBonusCharge) {
                    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.5f)
                    if (bonusCharge == 2) {
                        player.sendActionBar(Component.text("§a[游侠] 蓄力完成: 装填+2 | 穿透+2 | 多重射击").color(NamedTextColor.GREEN))
                    } else {
                        player.sendActionBar(Component.text("§a[游侠] 蓄力阶段: 装填+1 | 穿透+1").color(NamedTextColor.YELLOW))
                    }
                }
            }
        }
    }

    private fun sendFullStackNotification(player: Player, sub: PlayerSubClass) {
        when (sub) {
            PlayerSubClass.PO_JUN -> {
                player.sendActionBar(Component.text("§c[破军] 煞气已至巅峰 (吸血最大化)").color(NamedTextColor.RED))
                player.playSound(player.location, Sound.ENTITY_WITHER_AMBIENT, 0.5f, 2.0f)
            }
            PlayerSubClass.GOLDEN_BELL -> {
                player.sendActionBar(Component.text("§6[金钟] 金身已成 (防御最大化)").color(NamedTextColor.GOLD))
                player.playSound(player.location, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.5f)
            }
            PlayerSubClass.SNIPER -> {
                player.sendActionBar(Component.text("§b[狙击] 专注度已满 (100% 无视击退抗性)").color(NamedTextColor.AQUA))
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f)
            }
            PlayerSubClass.RANGER -> {
                player.sendActionBar(Component.text("§a[游侠] 快速装填 +II (最大)").color(NamedTextColor.GREEN))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 2.0f)
            }
            else -> {}
        }
    }

    // ========================================================================
    // 2. 策略注册
    // ========================================================================
    private fun registerStrategies() {

        // === 1. 破军 (PO_JUN) ===
        strategies[PlayerSubClass.PO_JUN] = object : SubClassStrategy {
            override fun modifyAttackDamage(player: Player, damage: Double, seconds: Double): Double {
                val maxHp = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                val currentHp = player.health
                val missingPercent = 1.0 - (currentHp / maxHp)
                return damage * (1.0 + missingPercent)
            }

            override fun modifyLifeSteal(player: Player, original: Double, seconds: Double): Double {
                val maxHp = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                val currentHp = player.health
                val ratio = currentHp / maxHp

                val baseRate = when {
                    ratio > 0.5 -> 0.003
                    ratio >= 0.2 -> 0.007
                    else -> 0.015
                }

                val cdr = statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_CDR)
                val effectiveSeconds = min(10.0, seconds * (1.0 + cdr))

                return original + (effectiveSeconds * baseRate)
            }
        }

        // === 2. 金钟 (GOLDEN_BELL) ===
        strategies[PlayerSubClass.GOLDEN_BELL] = object : SubClassStrategy {
            override fun modifyDefense(player: Player, def: Double, seconds: Double): Double {
                val cdr = statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_CDR)
                val effectiveSeconds = min(10.0, seconds * (1.0 + cdr))
                val percentBonus = effectiveSeconds * 0.01
                return def * (1.0 + percentBonus)
            }

            override fun onAttackEffect(player: Player, event: EntityDamageByEntityEvent, seconds: Double) {
                if (event.cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                    val phys = statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_PHYSICAL_DAMAGE)
                    event.damage = phys * 0.5
                }
            }

            override fun onAttack(attacker: Player, victim: LivingEntity, holdSeconds: Double) {
                if (holdSeconds >= 10.0 && victim is Mob) {
                    victim.setMetadata(META_AGGRO_OWNER, FixedMetadataValue(plugin, attacker.uniqueId.toString()))
                    victim.target = attacker
                    victim.world.spawnParticle(Particle.ANGRY_VILLAGER, victim.eyeLocation.add(0.0, 0.5, 0.0), 1)
                }
            }

            override fun onMobTarget(owner: Player, mob: Mob, event: EntityTargetEvent): Boolean {
                val holdTime = dataManager.getSlotHoldDuration(owner)
                val isValid = (owner.isOnline && !owner.isDead
                        && owner.world == mob.world
                        && owner.location.distanceSquared(mob.location) < 64 * 64
                        && holdTime >= 10.0)

                if (!isValid) {
                    mob.removeMetadata(META_AGGRO_OWNER, plugin)
                    return false
                }

                val newTarget = event.target
                if (newTarget != null && newTarget.uniqueId == owner.uniqueId) {
                    return true
                }

                if (event.reason == EntityTargetEvent.TargetReason.TARGET_DIED) {
                    return true
                }

                event.isCancelled = true
                if (mob.target == null || mob.target!!.uniqueId != owner.uniqueId) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        if (mob.isValid && owner.isValid) mob.target = owner
                    })
                }
                return true
            }
        }

        // === 3. 狙击 (SNIPER) ===
        strategies[PlayerSubClass.SNIPER] = object : SubClassStrategy {
            override fun onShoot(player: Player, arrow: org.bukkit.entity.AbstractArrow, force: Float) {
                if (force >= 0.95) {
                    val seconds = dataManager.getSlotHoldDuration(player)
                    val cdr = statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_CDR)
                    val effectiveSeconds = min(10.0, seconds * (1.0 + cdr))
                    val ignorePercent = effectiveSeconds * 0.1

                    if (ignorePercent > 0) {
                        arrow.persistentDataContainer.set(sniperKbIgnoreKey, PersistentDataType.DOUBLE, ignorePercent)
                        if (ignorePercent >= 1.0) {
                            arrow.isCritical = true
                            player.world.spawnParticle(Particle.CRIT, arrow.location, 10)
                        }
                    }
                }
            }
        }

        // === 4. 游侠 (RANGER) ===
        strategies[PlayerSubClass.RANGER] = object : SubClassStrategy {
            override fun getRangerEnchantBonus(player: Player, heldTime: Double): IntArray {
                var charge = 0
                var pierce = 0
                var multishot = 0

                if (heldTime >= 10.0) {
                    charge += 2
                    pierce += 2
                    multishot = 1
                } else if (heldTime >= 5.0) {
                    charge += 1
                    pierce += 1
                }

                if (player.hasMetadata("pl_ranger_t3_active")) {
                    charge += 1
                }

                return intArrayOf(charge, pierce, multishot)
            }
        }
    }

    // [修复] 删除了导致泛型推断错误的 getOrDefault 扩展函数
}