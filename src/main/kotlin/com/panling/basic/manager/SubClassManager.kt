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
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SubClassManager(
    private val plugin: JavaPlugin,
    private val dataManager: PlayerDataManager,
    private val statCalculator: StatCalculator
) : Listener {

    private val strategies = EnumMap<PlayerSubClass, SubClassStrategy>(PlayerSubClass::class.java)

    // Keys
    private val sniperKbIgnoreKey = NamespacedKey(plugin, "sniper_kb_ignore")
    private val rangerBonusChargeKey = NamespacedKey(plugin, "pl_ranger_bonus_charge")
    private val rangerBonusPierceKey = NamespacedKey(plugin, "pl_ranger_bonus_pierce")
    private val rangerAddedMultishotKey = NamespacedKey(plugin, "pl_ranger_added_multishot")

    // 通知状态缓存
    private val fullStackNotified = HashMap<UUID, Boolean>()

    // [NEW] 血债追踪：玩家UUID → 当前血债值 (破军)
    private val bloodDebt = HashMap<UUID, Double>()

    // [NEW] 前一 tick 的流派，用于检测流派切换时清算血债
    private val prevSubClass = HashMap<UUID, PlayerSubClass>()

    companion object {
        private const val META_AGGRO_OWNER = "pl_aggro_owner"
        // 默认空策略
        private val NO_OP = object : SubClassStrategy {}
    }

    init {
        // [关键] 注入 StatCalculator
        statCalculator.subClassManager = this

        registerStrategies()
        startGlobalTask()
        plugin.server.pluginManager.registerEvents(this, plugin)
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

        // [血债] 流派切换清算：离开破军时一次性造成全部血债伤害
        val prev = prevSubClass[player.uniqueId]
        if (prev == PlayerSubClass.PO_JUN && currentSub != PlayerSubClass.PO_JUN) {
            val debt = bloodDebt.remove(player.uniqueId)
            if (debt != null && debt > 0) {
                player.setMetadata("pl_blood_debt_bleed", FixedMetadataValue(plugin, true))
                player.damage(debt)
                player.removeMetadata("pl_blood_debt_bleed", plugin)
                player.sendActionBar(Component.empty())
                player.sendMessage("§c[血债] 流派变更，血债一次性清算：§4${String.format("%.1f", debt)} §c伤害")
            }
        }
        prevSubClass[player.uniqueId] = currentSub

        if (heldTime < 1.0) {
            fullStackNotified.remove(player.uniqueId)
        }

        maintainRangerEnchants(player, currentSub, activeSlot, heldTime)

        if (heldTime >= 10.0 && !fullStackNotified.getOrDefault(player.uniqueId, false)) {
            sendFullStackNotification(player, currentSub)
            fullStackNotified[player.uniqueId] = true
        }

        // [NEW] 金钟满层回血：每秒恢复已损失血量的 15%
        if (currentSub == PlayerSubClass.GOLDEN_BELL && heldTime >= 10.0 && player.health > 0) {
            val maxHp = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            val missing = maxHp - player.health
            if (missing > 0.5) {
                val heal = missing * 0.08
                player.health = min(maxHp, player.health + heal)
                // 1 tick REGENERATION VI 仅做视觉（血条白边闪烁），不依赖其回血
                player.addPotionEffect(PotionEffect(
                    PotionEffectType.REGENERATION, 2, 5, true, false
                ))
            }
        }

        // [NEW] 破军血债流血：每秒受到血债的 10%
        if (currentSub == PlayerSubClass.PO_JUN && heldTime >= 10.0) {
            val debt = bloodDebt[player.uniqueId] ?: 0.0
            if (debt > 0) {
                val bleed = max(1.0, debt * 0.1)
                val newDebt = debt - bleed
                if (newDebt <= 0.0) {
                    bloodDebt.remove(player.uniqueId)
                    player.sendActionBar(Component.empty())  // 清空 actionbar
                } else {
                    bloodDebt[player.uniqueId] = newDebt
                }
                // 标记为血债伤害，防止 onDamaged 递归拦截
                player.setMetadata("pl_blood_debt_bleed", FixedMetadataValue(plugin, true))
                player.damage(bleed)
                player.noDamageTicks = 0  // 取消无敌帧，防止外部攻击被吞
                player.removeMetadata("pl_blood_debt_bleed", plugin)

                // 血债值持续显示在 actionbar（低优先级，可被其他消息覆盖）
                val maxHp = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                val pct = (newDebt / maxHp * 100).toInt()
                val barLen = (pct / 10).coerceIn(0, 15)
                val bar = "§4${"█".repeat(barLen)}§8${"█".repeat(15 - barLen)}"
                player.sendActionBar(Component.text("§c血债 §7${pct}% $bar").color(NamedTextColor.GRAY))
            }
        }
    }

    // [血债] 公有清理方法，供死亡事件等外部调用
    fun clearBloodDebt(player: Player) {
        bloodDebt.remove(player.uniqueId)
        player.sendActionBar(Component.empty())
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
            val meta = item.itemMeta!!
            val pdc = meta.persistentDataContainer
            if (!pdc.has(BasicKeys.ITEM_ID, PersistentDataType.STRING)) continue

            // 1. 询问策略
            var bonus = intArrayOf(0, 0, 0)
            if (currentSub == PlayerSubClass.RANGER && i == activeSlot) {
                bonus = getStrategy(player).getRangerEnchantBonus(player, heldTime)
            }

            applyRangerEnchant(item, meta, pdc, bonus, i == activeSlot && currentSub == PlayerSubClass.RANGER, player)
        }

        // [NEW] 副手弩检查 (1.21+ getContents 不包含副手)
        val offhand = player.inventory.itemInOffHand
        if (offhand.type == Material.CROSSBOW && offhand.hasItemMeta()) {
            val offMeta = offhand.itemMeta!!
            val offPdc = offMeta.persistentDataContainer
            if (offPdc.has(BasicKeys.ITEM_ID, PersistentDataType.STRING)) {
                var bonus = intArrayOf(0, 0, 0)
                if (currentSub == PlayerSubClass.RANGER && activeSlot == -1) {
                    bonus = getStrategy(player).getRangerEnchantBonus(player, heldTime)
                }
                applyRangerEnchant(offhand, offMeta, offPdc, bonus, activeSlot == -1 && currentSub == PlayerSubClass.RANGER, player)
                player.inventory.setItemInOffHand(offhand)
            }
        }
    }

    private fun applyRangerEnchant(
        item: ItemStack, meta: org.bukkit.inventory.meta.ItemMeta,
        pdc: PersistentDataContainer, bonus: IntArray, isActive: Boolean,
        player: Player
    ) {
        val bonusCharge = bonus[0]
        val bonusPierce = bonus[1]
        val shouldAddMultishot = (bonus[2] == 1)

        var changed = false

        // A. 快速装填
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
    // 2. 策略注册 (核心修改部分)
    // ========================================================================
    private fun registerStrategies() {

        // 辅助函数：线性插值 (Linear Interpolation)
        // time: 当前持有时长 (0-10s)
        // startVal: 0秒时的倍率
        // endVal: 10秒时的倍率
        fun lerp(time: Double, startVal: Double, endVal: Double): Double {
            val t = (time / 10.0).coerceIn(0.0, 1.0)
            return startVal + (endVal - startVal) * t
        }

        // === 1. 破军 (PO_JUN) ===
        // 机制：低血增伤 + 血债系统
        // 惩罚：防御力线性恢复 (-50% -> -20%)
        strategies[PlayerSubClass.PO_JUN] = object : SubClassStrategy {
            override fun modifyAttackDamage(player: Player, damage: Double, seconds: Double): Double {
                val maxHp = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                val currentHp = player.health
                val missingPercent = 1.0 - (currentHp / maxHp)
                return damage * (1.0 + missingPercent)
            }

            override fun modifyDefense(player: Player, originalDefense: Double, holdSeconds: Double): Double {
                val factor = lerp(holdSeconds, 0.5, 0.8)
                return originalDefense * factor
            }

            // [血债] 残损 + 血盾：满层时每次攻击消耗 10% 生命，获得 8% 吸收盾
            override fun onAttackEffect(player: Player, event: EntityDamageByEntityEvent, seconds: Double) {
                if (seconds < 10.0) return
                val maxHp = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                // 残损：消耗 10% 最大生命值，最低到 20%（不会回复血量）
                val cost = maxHp * 0.1
                val afterCost = player.health - cost
                if (afterCost >= maxHp * 0.2) {
                    player.health = afterCost
                } else if (player.health > maxHp * 0.2) {
                    player.health = maxHp * 0.2  // 刚好扣到 20%
                }
                // 血盾：ABSORPTION 药水效果，1级=4HP，取最接近 8% 生命的等级
                val rawAbsorb = maxHp * 0.15
                val level = (rawAbsorb / 4.0).roundToInt().coerceAtLeast(1)
                player.addPotionEffect(PotionEffect(
                    PotionEffectType.ABSORPTION, 200, level - 1, true, false
                ))
            }

            // [血债] 受击吸收：非血债伤害不扣血，全部计入血债
            override fun onDamaged(player: Player, event: EntityDamageByEntityEvent, holdSeconds: Double): Boolean {
                if (holdSeconds < 10.0) return false
                // 血债流血自伤不拦截
                if (player.hasMetadata("pl_blood_debt_bleed")) return false
                // 使用 event.damage（原始伤害，不受护甲/吸收减免），让吸收盾留给流血消耗
                val dmg = event.damage
                if (dmg <= 0) return false
                val maxHp = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                val current = bloodDebt.getOrDefault(player.uniqueId, 0.0)
                val newDebt = current + dmg
                if (newDebt > maxHp * 1.5) {
                    // 崩解：血债超上限 → 立即死亡，清空血债
                    bloodDebt.remove(player.uniqueId)
                    player.health = 0.0
                } else {
                    bloodDebt[player.uniqueId] = newDebt
                }
                return true // 接管事件，取消原伤害
            }
        }

        // === 2. 金钟 (GOLDEN_BELL) ===
        // 机制：线性增强 (防御)
        // 惩罚：伤害线性恢复 (-50% -> -20%)
        strategies[PlayerSubClass.GOLDEN_BELL] = object : SubClassStrategy {
            override fun modifyDefense(player: Player, def: Double, seconds: Double): Double {
                val cdr = statCalculator.getPlayerTotalStat(player, BasicKeys.ATTR_CDR)
                val effectiveSeconds = min(10.0, seconds * (1.0 + cdr))
                val percentBonus = effectiveSeconds * 0.01
                return def * (1.0 + percentBonus)
            }

            override fun modifyAttackDamage(player: Player, originalDamage: Double, holdSeconds: Double): Double {
                // 刚切换：0.5 (减少50%)
                // 10秒后：0.8 (减少20%)
                // 线性过渡
                val factor = lerp(holdSeconds, 0.5, 0.8)
                return originalDamage * factor
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
                if (newTarget != null && newTarget.uniqueId == owner.uniqueId) return true
                if (event.reason == EntityTargetEvent.TargetReason.TARGET_DIED) return true

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
        // 机制：线性增强 (无视击退)
        // 惩罚：移速线性恢复 (-50% -> -30%)
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

            override fun modifyMovementSpeed(player: Player, originalSpeed: Double, holdSeconds: Double): Double {
                // 刚切换：0.5 (减少50%)
                // 10秒后：0.7 (减少30%)
                // 线性过渡
                val factor = lerp(holdSeconds, 0.6, 0.8)
                return originalSpeed * factor
            }
        }

        // === 4. 游侠 (RANGER) ===
        // 机制：阶梯式增强 (附魔等级)
        // 惩罚：阶梯式恢复 (血量 -30% -> -25% -> -20%)
        strategies[PlayerSubClass.RANGER] = object : SubClassStrategy {
            override fun getRangerEnchantBonus(player: Player, heldTime: Double): IntArray {
                var charge = 0
                var pierce = 0
                var multishot = 0
                // 阶段性逻辑
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

            override fun modifyMaxHealth(player: Player, originalHealth: Double, holdSeconds: Double): Double {
                // 0~5秒: -30% (x0.7)
                // 5~10秒: -25% (x0.75)
                // 10秒+: -20% (x0.8)
                val factor = when {
                    holdSeconds >= 10.0 -> 0.8
                    holdSeconds >= 5.0 -> 0.75
                    else -> 0.7
                }
                return originalHealth * factor
            }
        }
    }

    // 无源伤害（pl.damage()、坠落等）也走血债吸收
    @EventHandler(priority = EventPriority.HIGH)
    fun onNonEntityDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return // 实体攻击走 PlayerCombatListener 链路
        val player = event.entity as? Player ?: return
        if (event.isCancelled) return
        if (dataManager.getPlayerSubClass(player) != PlayerSubClass.PO_JUN) return
        val holdSec = dataManager.getSlotHoldDuration(player)
        if (holdSec < 10.0) return
        if (player.hasMetadata("pl_blood_debt_bleed")) return
        val dmg = event.damage
        if (dmg <= 0) return
        val maxHp = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val current = bloodDebt.getOrDefault(player.uniqueId, 0.0)
        val newDebt = current + dmg
        if (newDebt > maxHp * 1.5) {
            bloodDebt.remove(player.uniqueId)
            player.health = 0.0
        } else {
            bloodDebt[player.uniqueId] = newDebt
        }
        event.isCancelled = true
        event.damage = 0.0
    }
}